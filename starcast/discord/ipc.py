"""Talks to the Discord desktop app on this computer over its local IPC socket to set the activity.

Linux and macOS: a Unix socket (discord-ipc-0..9 in the runtime/temp folders, including the Flatpak and Snap
versions of Discord and Discord-compatible apps such as Vesktop). Windows: a named pipe (\\\\.\\pipe\\discord-ipc-N).
No account login or token is involved: Discord only trusts programs running on the same computer.

The Windows named-pipe transport is implemented but has never been run (no Windows machine was available).
"""
import fnmatch
import json
import logging
import os
import socket
import stat
import struct
import sys
import threading
import time

log = logging.getLogger("starcast.discord")

OP_HANDSHAKE = 0
OP_FRAME = 1
OP_CLOSE = 2
OP_PING = 3
OP_PONG = 4

MAX_FRAME = 1 << 20
REQUEST_TIMEOUT = 5.0     # write deadline and how long to wait for an answer
HANDSHAKE_TIMEOUT = 5.0
DIAL_TIMEOUT = 2.0

_HEADER = struct.Struct("<II")


class IPCError(Exception):
    """A plain connection problem (the text is shown as is)."""


class NotFoundError(IPCError):
    """No Discord app could be reached."""

    def __init__(self, endpoints, details):
        self.endpoints, self.details = list(endpoints), list(details)
        super().__init__(str(self))

    def __str__(self):
        if not self.endpoints:
            return "Discord is not running (no Discord IPC socket found)"
        return "could not talk to Discord (tried %d socket(s)): %s" % (len(self.endpoints), "; ".join(self.details))


class HandshakeError(IPCError):
    """Discord answered but refused the connection (for example an invalid application ID)."""

    def __init__(self, code=0, message=""):
        self.code, self.message = code, message
        super().__init__(str(self))

    def __str__(self):
        if not self.message:
            return "Discord refused the connection"
        return "Discord refused the connection: " + self.message


class APIError(IPCError):
    """Discord understood the request but rejected it. The connection stays open."""

    def __init__(self, code=0, message=""):
        self.code, self.message = code, message
        super().__init__(str(self))

    def __str__(self):
        return "Discord rejected the request (%d): %s" % (self.code, self.message)


class Cancelled(IPCError):
    pass


def _err_text(e):
    return str(e) or e.__class__.__name__


# ---- framing ----

def write_frame(transport, op, payload):
    """One write per frame, so frames never interleave."""
    transport.sendall(_HEADER.pack(op, len(payload)) + bytes(payload))


class FrameReader:
    """Reads frames, keeping a partial frame across read timeouts. Never reads past the frame it returns."""

    def __init__(self, transport):
        self.t = transport
        self.buf = bytearray()

    def _need(self, k, deadline, should_stop):
        while len(self.buf) < k:
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise IPCError("i/o timeout")
                self.t.settimeout(remaining)
            try:
                chunk = self.t.recv(k - len(self.buf))
            except socket.timeout:
                if deadline is None and should_stop is not None and should_stop():
                    raise IPCError("closed") from None
                continue
            if not chunk:
                raise IPCError("unexpected EOF" if self.buf else "EOF")
            self.buf += chunk

    def read_frame(self, deadline=None, should_stop=None):
        """(op, payload). deadline is a time.monotonic() value; without one, read timeouts just mean "no data yet"."""
        self._need(8, deadline, should_stop)
        op, n = _HEADER.unpack_from(self.buf)
        if n > MAX_FRAME:
            raise IPCError("Discord sent an oversized frame (%d bytes)" % n)
        self._need(8 + n, deadline, should_stop)
        payload = bytes(self.buf[8:8 + n])
        del self.buf[:8 + n]
        return op, payload


def read_frame(transport):
    return FrameReader(transport).read_frame()


def _dumps(obj):
    # ASCII-only JSON: identical meaning, and nothing can fail to encode.
    return json.dumps(obj, separators=(",", ":")).encode("ascii")


def _parse_frame(payload):
    """{cmd, evt, nonce, data} from a FRAME payload. Raises ValueError like Go's decoder would."""
    try:
        r = json.loads(payload.decode("utf-8", "replace"))
    except RecursionError:
        raise ValueError("nested too deeply") from None
    if not isinstance(r, dict):
        raise ValueError("expected a JSON object")
    for k in ("cmd", "evt", "nonce"):
        if r.get(k) is not None and not isinstance(r[k], str):
            raise ValueError("%s is not a string" % k)
    return r


def parse_error_data(data):
    """(code, message) from Discord's {"code": ..., "message": ...}; (0, "") when missing or invalid."""
    if isinstance(data, (bytes, bytearray)):
        try:
            data = json.loads(bytes(data).decode("utf-8", "replace"))
        except ValueError:
            return 0, ""
    if not isinstance(data, dict):
        return 0, ""
    code = data.get("code")
    msg = data.get("message")
    return (code if isinstance(code, int) and not isinstance(code, bool) else 0), (msg if isinstance(msg, str) else "")


def _parse_user(data):
    user = data.get("user") if isinstance(data, dict) else None
    if not isinstance(user, dict):
        return ""
    g, u = user.get("global_name"), user.get("username")
    if isinstance(g, str) and g:
        return g
    return u if isinstance(u, str) else ""


def _close_transport(t):
    try:
        t.shutdown(socket.SHUT_RDWR)
    except (OSError, AttributeError):
        pass
    try:
        t.close()
    except OSError:
        pass


# ---- the connection ----

_WAITING = object()


class Client:
    """A live connection to Discord. Thread safe."""

    def __init__(self, transport, reader, endpoint, user):
        self._t = transport
        self._reader = reader
        self.endpoint = endpoint
        self.user = user
        self._wlock = threading.Lock()
        self._cond = threading.Condition()
        self._pending = {}
        self._failed = False
        self._close_err = None
        self._callbacks = []
        self.done = threading.Event()
        self._thread = None

    def _start(self):
        self._thread = threading.Thread(target=self._read_loop, name="ytm-starcast-discord-ipc", daemon=True)
        self._thread.start()

    def info(self):
        return {"endpoint": self.endpoint, "user": self.user}

    @property
    def closed(self):
        return self.done.is_set()

    def err(self):
        """Why the connection ended (None while it is still open)."""
        return self._close_err if self.done.is_set() else None

    def add_done_callback(self, fn):
        """fn() runs once when the connection ends (at once if it already has)."""
        with self._cond:
            if not self._failed:
                self._callbacks.append(fn)
                return
        fn()

    def close(self):
        self._fail(IPCError("closed"))
        t = self._thread
        if t is not None and t is not threading.current_thread():
            t.join(2.0)

    def _fail(self, err):
        with self._cond:
            if self._failed:
                return
            self._failed = True
            self._close_err = err
            callbacks, self._callbacks = self._callbacks, []
            self._cond.notify_all()
        _close_transport(self._t)
        self.done.set()
        for fn in callbacks:
            try:
                fn()
            except Exception:
                log.exception("Discord connection callback failed")

    def _read_loop(self):
        try:
            while True:
                op, payload = self._reader.read_frame(should_stop=self.done.is_set)
                if op == OP_PING:
                    self._write(OP_PONG, payload)
                elif op == OP_CLOSE:
                    _, msg = parse_error_data(payload)
                    self._fail(IPCError(msg or "Discord closed the connection"))
                    return
                elif op == OP_FRAME:
                    try:
                        r = _parse_frame(payload)
                    except ValueError:
                        continue        # an event we did not ask for
                    nonce = r.get("nonce")
                    if not nonce:
                        continue
                    with self._cond:
                        if self._pending.get(nonce, None) is _WAITING:
                            self._pending[nonce] = r
                            self._cond.notify_all()
        except Exception as e:      # EOF, a reset, a closed socket, an oversized frame...
            self._fail(e)

    def _write(self, op, payload):
        with self._wlock:
            write_frame(self._t, op, payload)

    def set_activity(self, activity):
        """Shows the activity (a dict), or clears it when activity is None. Raises APIError when Discord
        rejects it (the connection stays open), or another exception when the connection is gone."""
        args = {}
        if activity is not None:
            args["activity"] = activity
        args["pid"] = os.getpid()
        r = self._request("SET_ACTIVITY", args)
        if r.get("evt") == "ERROR":
            code, msg = parse_error_data(r.get("data"))
            raise APIError(code, msg)

    def _request(self, cmd, args):
        if self.done.is_set():
            raise self._close_err or IPCError("closed")
        nonce = os.urandom(16).hex()
        body = _dumps({"args": args, "cmd": cmd, "nonce": nonce})
        with self._cond:
            self._pending[nonce] = _WAITING
        try:
            try:
                self._write(OP_FRAME, body)
            except Exception as e:
                self._fail(e)
                raise
            end = time.monotonic() + REQUEST_TIMEOUT
            with self._cond:
                while self._pending.get(nonce) is _WAITING and not self._failed:
                    remaining = end - time.monotonic()
                    if remaining <= 0:
                        break
                    self._cond.wait(remaining)
                r = self._pending.get(nonce)
                if r is not _WAITING and r is not None:
                    return r
                if self._failed:
                    raise self._close_err or IPCError("closed")
            raise IPCError("timed out waiting for Discord to answer")
        finally:
            with self._cond:
                self._pending.pop(nonce, None)


def _handshake(t, endpoint, client_id, timeout):
    deadline = time.monotonic() + timeout
    reader = FrameReader(t)
    try:
        t.settimeout(timeout)
        write_frame(t, OP_HANDSHAKE, _dumps({"client_id": client_id, "v": 1}))
        while True:
            op, payload = reader.read_frame(deadline=deadline)
            if op == OP_CLOSE:
                raise HandshakeError(*parse_error_data(payload))
            if op == OP_PING:
                try:
                    write_frame(t, OP_PONG, payload)
                except OSError:
                    pass
            elif op == OP_FRAME:
                try:
                    r = _parse_frame(payload)
                except ValueError as e:
                    raise IPCError("unexpected reply from Discord: %s" % e) from None
                if r.get("evt") == "ERROR":
                    raise HandshakeError(*parse_error_data(r.get("data")))
                if r.get("evt") == "READY":
                    return reader, _parse_user(r.get("data"))
    finally:
        try:
            t.settimeout(REQUEST_TIMEOUT)
        except OSError:
            pass


def connect(client_id, endpoints=None, dial=None, timeout=HANDSHAKE_TIMEOUT, stop=None):
    """Finds a running Discord app and completes the handshake. Returns a Client.

    endpoints=None looks for Discord's sockets; an explicit list (even []) is used as is. dial(endpoint)
    opens one endpoint and returns a socket-like object; None uses the real socket or pipe. stop is an
    optional threading.Event that cancels the search between endpoints."""
    if dial is None:
        dial = dial_endpoint
    eps = discover_endpoints() if endpoints is None else list(endpoints)
    details = []
    for ep in eps:
        if stop is not None and stop.is_set():
            raise Cancelled("stopped")
        try:
            t = dial(ep)
        except Exception as e:
            details.append("%s: %s" % (ep, _err_text(e)))
            continue
        try:
            reader, user = _handshake(t, ep, client_id, timeout)
        except HandshakeError:
            _close_transport(t)
            raise                   # another socket will not fix a bad application ID
        except Exception as e:
            _close_transport(t)
            details.append("%s: %s" % (ep, _err_text(e)))
            continue
        c = Client(t, reader, ep, user)
        c._start()
        return c
    raise NotFoundError(eps, details)


# ---- where Discord listens ----

def _is_windows(system=None):
    return (system or sys.platform).startswith("win")


def search_dirs(environ=None, system=None, uid=None):
    """Folders where Discord may keep its IPC socket, in search order (Unix), or the pipe prefix (Windows).

    Discord puts its socket in XDG_RUNTIME_DIR when that is set and only otherwise in TMPDIR, TMP, TEMP or
    /tmp, so the temporary folders (which other accounts can write to) are searched only when it is unset."""
    if _is_windows(system):
        return ["\\\\.\\pipe\\"]
    env = os.environ if environ is None else environ
    system = system or sys.platform
    dirs, seen = [], set()

    def add(d):
        if d and d not in seen:
            seen.add(d)
            dirs.append(d)

    runtime_set = bool(env.get("XDG_RUNTIME_DIR"))
    runtime = env.get("XDG_RUNTIME_DIR") or ""
    if not runtime and system.startswith("linux"):
        runtime = "/run/user/%d" % (os.getuid() if uid is None else uid)
    add(runtime)
    if not runtime_set:
        for k in ("TMPDIR", "TMP", "TEMP"):
            add(env.get(k) or "")
    if runtime:
        join = lambda *p: os.path.normpath(os.path.join(*p))     # noqa: E731
        add(join(runtime, "app", "com.discordapp.Discord"))
        add(join(runtime, "snap.discord"))
        add(join(runtime, "snap.discord-canary"))
        # other Flatpak apps that offer Discord's socket (Vesktop, Legcord...); like Go, "*" matches dot-names
        for d in _glob_names(join(runtime, "app")):
            add(join(runtime, "app", d))
        for d in _glob_names(join(runtime, ".flatpak")):
            base = join(runtime, ".flatpak", d)
            if os.path.isdir(base) and "xdg-run" in _list(base):
                add(join(base, "xdg-run"))
    if not runtime_set:
        add("/tmp")
    return dirs


def _list(d):
    try:
        return os.listdir(d)
    except OSError:
        return []


def _glob_names(d):
    return sorted(n for n in _list(d) if fnmatch.fnmatchcase(n, "*"))


def _own_socket(path, uid):
    """True when path is a socket that belongs to this account. A symbolic link counts only when the link and
    the socket it leads to are both this account's: another account can leave either in a shared folder."""
    try:
        st = os.lstat(path)
        if stat.S_ISLNK(st.st_mode):
            if st.st_uid != uid:
                return False
            st = os.stat(path)
    except OSError:
        return False
    return stat.S_ISSOCK(st.st_mode) and st.st_uid == uid


def endpoints_in(dirs, system=None, uid=None):
    """The Discord sockets in these folders that belong to this account, lowest number first (Unix); all ten
    pipes (Windows)."""
    if _is_windows(system):
        return ["\\\\.\\pipe\\discord-ipc-%d" % i for i in range(10)]
    me = os.getuid() if uid is None else uid
    eps = []
    for i in range(10):
        for d in dirs:
            p = os.path.normpath(os.path.join(d, "discord-ipc-%d" % i))
            if _own_socket(p, me):
                eps.append(p)
    return eps


def discover_endpoints():
    """The default endpoint search (real folders on this computer)."""
    return endpoints_in(search_dirs())


_UCRED = struct.Struct("iII")      # struct ucred: pid, uid, gid


def _check_peer(s, uid):
    """Linux: the program at the other end must run as this account. This also covers a socket that was
    swapped after the folder search. Other systems rely on the owner check in endpoints_in."""
    opt = getattr(socket, "SO_PEERCRED", None)
    if opt is None:
        return
    _pid, peer_uid, _gid = _UCRED.unpack(s.getsockopt(socket.SOL_SOCKET, opt, _UCRED.size))
    if peer_uid != uid:
        raise IPCError("the socket belongs to another account")


def _dial_unix(endpoint, uid=None):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.settimeout(DIAL_TIMEOUT)
        s.connect(endpoint)
        _check_peer(s, os.getuid() if uid is None else uid)
        s.settimeout(REQUEST_TIMEOUT)
        return s
    except BaseException:
        s.close()
        raise


class _PipeTransport:
    """A Windows named pipe with the parts of the socket interface the client uses. UNTESTED (no Windows here).

    A pipe opened for ordinary (synchronous) I/O serialises reads and writes, so a read that waits for Discord
    would block every write. Reads therefore only happen when PeekNamedPipe says data is waiting, and then take
    everything waiting (so a pipe in message mode never sees a short read); timeouts are done by polling."""

    POLL = 0.05

    def __init__(self, f, handle, peek):
        self._f, self._h, self._peek_fn = f, handle, peek
        self._timeout = None
        self._closed = False
        self._rbuf = bytearray()
        self._rlock = threading.Lock()

    def settimeout(self, t):
        self._timeout = t

    def _available(self):
        if self._closed:
            return -1
        return self._peek_fn(self._h)

    def recv(self, n):
        deadline = None if self._timeout is None else time.monotonic() + self._timeout
        while True:
            with self._rlock:
                if self._rbuf:
                    out = bytes(self._rbuf[:n])
                    del self._rbuf[:n]
                    return out
                avail = self._available()
                if avail < 0:
                    return b""          # pipe closed: EOF
                if avail > 0:
                    data = self._f.read(avail)
                    if not data:
                        return b""
                    self._rbuf += data
                    continue
            if deadline is not None and time.monotonic() >= deadline:
                raise socket.timeout("timed out")
            time.sleep(self.POLL)

    def sendall(self, data):
        if self._closed:
            raise IPCError("closed")
        view = memoryview(bytes(data))
        while view:
            n = self._f.write(view)
            if not n:
                raise IPCError("could not write to Discord")
            view = view[n:]

    def shutdown(self, how):
        pass

    def close(self):
        if not self._closed:
            self._closed = True
            self._f.close()


def _dial_pipe(endpoint):
    """UNTESTED: opens \\\\.\\pipe\\discord-ipc-N, waiting up to 2 s while the pipe is busy."""
    import ctypes
    import msvcrt
    from ctypes import wintypes

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    peek = k32.PeekNamedPipe
    peek.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD, wintypes.LPDWORD, wintypes.LPDWORD,
                     wintypes.LPDWORD]
    peek.restype = wintypes.BOOL
    wait = k32.WaitNamedPipeW
    wait.argtypes = [wintypes.LPCWSTR, wintypes.DWORD]
    wait.restype = wintypes.BOOL

    deadline = time.monotonic() + DIAL_TIMEOUT
    while True:
        try:
            f = open(endpoint, "r+b", buffering=0)
            break
        except OSError as e:
            remaining = deadline - time.monotonic()
            if getattr(e, "winerror", None) == 231 and remaining > 0:      # ERROR_PIPE_BUSY
                wait(endpoint, max(1, int(remaining * 1000)))
                continue
            raise

    def available(handle):
        n = wintypes.DWORD(0)
        if not peek(handle, None, 0, None, ctypes.byref(n), None):
            return -1
        return n.value

    return _PipeTransport(f, wintypes.HANDLE(msvcrt.get_osfhandle(f.fileno())), available)


def dial_endpoint(endpoint):
    """The real dialer: a Unix socket, or a named pipe on Windows."""
    if _is_windows():
        return _dial_pipe(endpoint)
    return _dial_unix(endpoint)
