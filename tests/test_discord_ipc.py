"""Discord IPC client: framing, handshake, requests, ping, disconnects, and where the sockets are looked for.

The "Discord" here is a fake on the other end of a socket pair; the real Discord socket is never dialed."""
import json
import os
import queue
import socket
import struct
import tempfile
import threading
import unittest
from unittest import mock

from starcast.discord import ipc
from starcast.discord.ipc import (OP_CLOSE, OP_FRAME, OP_HANDSHAKE, OP_PING, OP_PONG, APIError, FrameReader,
                                HandshakeError, IPCError, NotFoundError, connect, read_frame, write_frame)

from .test_discord_fakes import GUARD, REAL_DIAL_UNIX, UnixFakeDiscord

READY = b'{"cmd":"DISPATCH","evt":"READY","data":{"user":{"username":"tester","global_name":"Tester"}},"nonce":null}'
CID = "123456789012345678"


def setUpModule():
    GUARD.install()


def tearDownModule():
    GUARD.uninstall()
    GUARD.check()


class FakeDiscord:
    """Plays Discord on the other end of a socket pair: answers the handshake with READY, records requests and
    answers them (or lets on_frame answer)."""

    def __init__(self, test, ready=READY):
        self.ready = ready
        self.frames = queue.Queue()
        self.ops = queue.Queue()
        self.hello = queue.Queue()
        self.server_ends = queue.Queue()
        self.on_frame = None
        self._socks, self._threads = [], []
        test.addCleanup(self.close)

    def dial(self, endpoint):
        client, srv = socket.socketpair()
        self._socks.append(srv)
        self.server_ends.put(srv)
        t = threading.Thread(target=self.serve, args=(srv,), daemon=True)
        self._threads.append(t)
        t.start()
        return client

    def serve(self, srv):
        reader = FrameReader(srv)
        try:
            op, payload = reader.read_frame()
            if op != OP_HANDSHAKE:
                return
            self.hello.put(json.loads(payload))
            write_frame(srv, OP_FRAME, self.ready)
            while True:
                op, payload = reader.read_frame()
                if op != OP_FRAME:
                    self.ops.put((op, payload))
                    continue
                req = json.loads(payload)
                self.frames.put(req)
                if self.on_frame is not None:
                    self.on_frame(srv, req)
                    continue
                write_frame(srv, OP_FRAME, json.dumps({"cmd": req["cmd"], "evt": None, "nonce": req["nonce"],
                                                       "data": {}}).encode())
        except (IPCError, OSError):
            return

    def close(self):
        for s in self._socks:
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        for t in self._threads:
            t.join(2)
        for s in self._socks:
            s.close()


def connect_fake(test, fake, **kw):
    c = connect(CID, endpoints=["fake"], dial=fake.dial, timeout=2.0, **kw)
    test.addCleanup(c.close)
    return c


class ClientTests(unittest.TestCase):
    def test_connect_and_set_activity(self):
        f = FakeDiscord(self)
        c = connect_fake(self, f)
        h = f.hello.get(timeout=2)
        self.assertEqual(h, {"client_id": CID, "v": 1})
        self.assertEqual(c.info(), {"endpoint": "fake", "user": "Tester"})   # global_name wins
        act = {"type": 2, "details": "Song", "state": "Artist", "timestamps": {"start": 1000, "end": 2000},
               "assets": {"large_image": "https://example.com/a.png", "small_image": "play"},
               "buttons": [{"label": "Listen", "url": "https://music.youtube.com/watch?v=abc"}], "instance": False}
        c.set_activity(act)
        req = f.frames.get(timeout=2)
        self.assertEqual(req["cmd"], "SET_ACTIVITY")
        self.assertRegex(req["nonce"], r"^[0-9a-f]{32}$")
        self.assertEqual(req["args"]["pid"], os.getpid())
        a = req["args"]["activity"]
        self.assertEqual((a["type"], a["details"], a["state"]), (2, "Song", "Artist"))
        self.assertEqual(a["timestamps"], {"start": 1000, "end": 2000})
        self.assertNotIn("status_display_type", a)
        c.set_activity(act)
        self.assertNotEqual(f.frames.get(timeout=2)["nonce"], req["nonce"], "a fresh nonce per request")

    def test_username_when_no_global_name(self):
        f = FakeDiscord(self, ready=b'{"cmd":"DISPATCH","evt":"READY","data":{"user":{"username":"tester",'
                                    b'"global_name":null}},"nonce":null}')
        self.assertEqual(connect_fake(self, f).info()["user"], "tester")

    def test_clear_sends_no_activity(self):
        f = FakeDiscord(self)
        c = connect_fake(self, f)
        c.set_activity(None)
        args = f.frames.get(timeout=2)["args"]
        self.assertNotIn("activity", args)
        self.assertEqual(args, {"pid": os.getpid()})

    def test_set_activity_api_error(self):
        f = FakeDiscord(self)

        def reply(srv, req):
            write_frame(srv, OP_FRAME, json.dumps({"cmd": "SET_ACTIVITY", "evt": "ERROR", "nonce": req["nonce"],
                                                   "data": {"code": 4000, "message": "bad activity"}}).encode())
        f.on_frame = reply
        c = connect_fake(self, f)
        with self.assertRaises(APIError) as cm:
            c.set_activity({"type": 2, "details": "x", "instance": False})
        self.assertEqual((cm.exception.code, cm.exception.message), (4000, "bad activity"))
        self.assertEqual(str(cm.exception), "Discord rejected the request (4000): bad activity")
        self.assertFalse(c.closed, "an API error must not kill the connection")

    def test_ignores_unrequested_events_and_waits_for_its_nonce(self):
        f = FakeDiscord(self)

        def reply(srv, req):
            write_frame(srv, OP_FRAME, b'{"cmd":"DISPATCH","evt":"ACTIVITY_JOIN","nonce":null,"data":{}}')
            write_frame(srv, OP_FRAME, b"not json")
            write_frame(srv, OP_FRAME, b'{"nonce":"someone-else","evt":"ERROR","data":{"code":1}}')
            write_frame(srv, OP_FRAME, json.dumps({"cmd": "SET_ACTIVITY", "evt": None, "nonce": req["nonce"]}).encode())
        f.on_frame = reply
        c = connect_fake(self, f)
        c.set_activity(None)        # no error: only its own reply counts
        self.assertFalse(c.closed)

    def test_handshake_rejected(self):
        tried = []
        socks, threads = [], []

        def dial(ep):
            tried.append(ep)
            client, srv = socket.socketpair()
            socks.append(srv)

            def serve():
                try:
                    read_frame(srv)
                    write_frame(srv, OP_CLOSE, b'{"code":4000,"message":"Invalid Client ID"}')
                except OSError:
                    pass
            t = threading.Thread(target=serve, daemon=True)
            threads.append(t)
            t.start()
            return client

        def cleanup():
            for t in threads:
                t.join(2)
            for s in socks:
                s.close()
        self.addCleanup(cleanup)
        with self.assertRaises(HandshakeError) as cm:
            connect("1", endpoints=["a", "b"], dial=dial, timeout=1.0)
        self.assertEqual(cm.exception.message, "Invalid Client ID")
        self.assertEqual(str(cm.exception), "Discord refused the connection: Invalid Client ID")
        self.assertEqual(tried, ["a"], "no other socket is tried after a refusal")

    def test_handshake_error_event(self):
        f = FakeDiscord(self, ready=b'{"cmd":"DISPATCH","evt":"ERROR","data":{"code":4000,"message":"nope"},"nonce":null}')
        with self.assertRaises(HandshakeError):
            connect(CID, endpoints=["fake"], dial=f.dial, timeout=1.0)

    def test_handshake_garbage_tries_next_socket(self):
        bad = FakeDiscord(self, ready=b"{garbage")
        good = FakeDiscord(self)
        dial = lambda ep: (bad if ep == "bad" else good).dial(ep)   # noqa: E731
        c = connect(CID, endpoints=["bad", "good"], dial=dial, timeout=1.0)
        self.addCleanup(c.close)
        self.assertEqual(c.info()["endpoint"], "good")

    def test_handshake_timeout_tries_next_socket(self):
        socks = []

        def silent(ep):
            client, srv = socket.socketpair()
            socks.append(srv)
            return client
        self.addCleanup(lambda: [s.close() for s in socks])
        with self.assertRaises(NotFoundError) as cm:
            connect(CID, endpoints=["quiet"], dial=silent, timeout=0.2)
        self.assertIn("quiet: i/o timeout", str(cm.exception))

    def test_not_found(self):
        def dial(ep):
            raise ConnectionRefusedError("connection refused")
        with self.assertRaises(NotFoundError) as cm:
            connect("1", endpoints=["a", "b"], dial=dial)
        self.assertEqual(len(cm.exception.details), 2)
        self.assertEqual(str(cm.exception),
                         "could not talk to Discord (tried 2 socket(s)): a: connection refused; b: connection refused")
        with self.assertRaises(NotFoundError) as cm:
            connect("1", endpoints=[], dial=dial)
        self.assertEqual(str(cm.exception), "Discord is not running (no Discord IPC socket found)")

    def test_stop_cancels_between_endpoints(self):
        stop = threading.Event()
        stop.set()
        with self.assertRaises(ipc.Cancelled):
            connect("1", endpoints=["a"], dial=lambda ep: self.fail("dialed"), stop=stop)

    def test_falls_through_to_next_socket(self):
        f = FakeDiscord(self)

        def dial(ep):
            if ep == "dead":
                raise ConnectionRefusedError("connection refused")
            return f.dial(ep)
        c = connect("1", endpoints=["dead", "alive"], dial=dial, timeout=1.0)
        self.addCleanup(c.close)
        self.assertEqual(c.info()["endpoint"], "alive")

    def test_answers_ping(self):
        f = FakeDiscord(self)
        connect_fake(self, f)
        srv = f.server_ends.get(timeout=2)
        write_frame(srv, OP_PING, b'{"n":1}')
        op, payload = f.ops.get(timeout=2)
        self.assertEqual((op, payload), (OP_PONG, b'{"n":1}'))

    def test_done_when_discord_closes(self):
        f = FakeDiscord(self)
        c = connect_fake(self, f)
        called = threading.Event()
        c.add_done_callback(called.set)
        srv = f.server_ends.get(timeout=2)
        srv.shutdown(socket.SHUT_RDWR)
        self.assertTrue(c.done.wait(2), "done not set after Discord hung up")
        self.assertTrue(called.wait(2), "the done callback must run")
        self.assertIsNotNone(c.err())
        with self.assertRaises(Exception):
            c.set_activity({"type": 2, "details": "x", "instance": False})
        late = threading.Event()
        c.add_done_callback(late.set)
        self.assertTrue(late.is_set(), "a callback added after the end runs at once")

    def test_close_frame_ends_connection_with_its_message(self):
        f = FakeDiscord(self)
        c = connect_fake(self, f)
        write_frame(f.server_ends.get(timeout=2), OP_CLOSE, b'{"code":1000,"message":"bye now"}')
        self.assertTrue(c.done.wait(2))
        self.assertEqual(str(c.err()), "bye now")

    def test_request_times_out(self):
        f = FakeDiscord(self)
        f.on_frame = lambda srv, req: None      # never answers
        c = connect_fake(self, f)
        old = ipc.REQUEST_TIMEOUT
        ipc.REQUEST_TIMEOUT = 0.2
        self.addCleanup(setattr, ipc, "REQUEST_TIMEOUT", old)
        with self.assertRaisesRegex(IPCError, "timed out waiting for Discord to answer"):
            c.set_activity(None)
        self.assertFalse(c.closed, "a timeout is reported, the connection is left to the engine")

    def test_close_is_idempotent_and_joins_the_reader(self):
        f = FakeDiscord(self)
        c = connect_fake(self, f)
        c.close()
        c.close()
        self.assertTrue(c.closed)
        self.assertEqual(str(c.err()), "closed")
        self.assertFalse(c._thread.is_alive())


class FramingTests(unittest.TestCase):
    def pair(self):
        a, b = socket.socketpair()
        self.addCleanup(a.close)
        self.addCleanup(b.close)
        return a, b

    def test_frame_round_trip(self):
        a, b = self.pair()
        write_frame(a, OP_FRAME, b"hello")
        self.assertEqual(read_frame(b), (OP_FRAME, b"hello"))

    def test_header_is_little_endian(self):
        a, b = self.pair()
        write_frame(a, 3, b"xy")
        self.assertEqual(b.recv(10), struct.pack("<II", 3, 2) + b"xy")

    def test_oversized_frame_is_refused(self):
        a, b = self.pair()
        a.sendall(struct.pack("<II", OP_FRAME, (1 << 20) + 1))
        with self.assertRaisesRegex(IPCError, r"oversized frame \(1048577 bytes\)"):
            read_frame(b)

    def test_partial_frame_survives_a_read_timeout(self):
        a, b = self.pair()
        b.settimeout(0.05)
        r = FrameReader(b)
        a.sendall(struct.pack("<II", OP_FRAME, 5) + b"he")
        stop = iter([False, True])
        with self.assertRaises(IPCError):
            r.read_frame(should_stop=lambda: next(stop))      # times out twice, then gives up
        a.sendall(b"llo")
        self.assertEqual(r.read_frame(), (OP_FRAME, b"hello"))

    def test_eof(self):
        a, b = self.pair()
        a.sendall(struct.pack("<II", OP_FRAME, 5) + b"he")
        a.shutdown(socket.SHUT_WR)
        with self.assertRaisesRegex(IPCError, "unexpected EOF"):
            read_frame(b)


    def test_deeply_nested_reply_is_just_not_ours(self):
        with self.assertRaises(ValueError):
            ipc._parse_frame(b"[" * 200000)


class FakePipeFile:
    """Stands in for the opened Windows pipe: what Discord sent waits in `incoming`."""

    def __init__(self):
        self.incoming = bytearray()
        self.written = bytearray()
        self.closed = False
        self.reads = []

    def peek(self, _handle):
        return -1 if self.closed else len(self.incoming)

    def read(self, n):
        self.reads.append(n)
        out = bytes(self.incoming[:n])
        del self.incoming[:n]
        return out

    def write(self, b):
        self.written += bytes(b)
        return len(b)

    def close(self):
        self.closed = True


class PipeTransportTests(unittest.TestCase):
    """The pipe transport's own logic, with a fake pipe (the Windows calls themselves are untested)."""

    def transport(self):
        f = FakePipeFile()
        t = ipc._PipeTransport(f, None, f.peek)
        t.POLL = 0.005
        return f, t

    def test_reads_everything_waiting_and_hands_it_out_in_pieces(self):
        f, t = self.transport()
        f.incoming += struct.pack("<II", OP_FRAME, 5) + b"hello" + struct.pack("<II", OP_PING, 2)
        r = FrameReader(t)
        self.assertEqual(r.read_frame(), (OP_FRAME, b"hello"))
        self.assertEqual(f.reads, [21], "one read takes all that is waiting (no short reads in message mode)")
        f.incoming += b"{}"
        self.assertEqual(r.read_frame(), (OP_PING, b"{}"))

    def test_timeout_then_eof_when_closed(self):
        f, t = self.transport()
        t.settimeout(0.02)
        with self.assertRaises(socket.timeout):
            t.recv(8)
        t.close()
        self.assertTrue(f.closed)
        self.assertEqual(t.recv(8), b"")
        with self.assertRaises(IPCError):
            t.sendall(b"x")

    def test_sendall_and_handshake_over_a_pipe(self):
        f, t = self.transport()
        f.incoming += struct.pack("<II", OP_FRAME, len(READY)) + READY
        c = connect(CID, endpoints=["\\\\.\\pipe\\discord-ipc-0"], dial=lambda ep: t, timeout=1.0)
        self.addCleanup(c.close)
        self.assertEqual(c.info()["user"], "Tester")
        op, n = struct.unpack_from("<II", f.written)
        self.assertEqual(op, OP_HANDSHAKE)
        self.assertEqual(json.loads(bytes(f.written[8:8 + n])), {"client_id": CID, "v": 1})
        c.close()
        self.assertTrue(f.closed, "closing the client closes the pipe")


class UnixSocketTests(unittest.TestCase):
    """The real Unix-socket dialer and socket search, against a fake Discord in a temporary folder."""

    def test_finds_and_talks_over_a_unix_socket(self):
        f = UnixFakeDiscord(self)
        self.assertEqual(ipc.endpoints_in([f.dir], system="linux"), [f.path])
        c = connect(CID, endpoints=[f.path], dial=REAL_DIAL_UNIX, timeout=2.0)
        self.addCleanup(c.close)
        self.assertEqual(f.hello.get(timeout=2), {"client_id": CID, "v": 1})
        self.assertEqual(c.info(), {"endpoint": f.path, "user": "Tester"})
        c.set_activity({"type": 2, "details": "Song", "instance": False})
        self.assertEqual(f.frames.get(timeout=2)["args"]["activity"]["details"], "Song")
        c.set_activity(None)
        self.assertNotIn("activity", f.frames.get(timeout=2)["args"])
        c.close()
        self.assertTrue(c.closed)

    @unittest.skipUnless(hasattr(socket, "SO_PEERCRED"), "no peer credentials here")
    def test_refuses_a_socket_another_account_listens_on(self):
        f = UnixFakeDiscord(self)
        other = os.getuid() + 1
        with self.assertRaises(IPCError) as cm:
            REAL_DIAL_UNIX(f.path, uid=other)
        self.assertEqual(str(cm.exception), "the socket belongs to another account")
        with self.assertRaises(NotFoundError) as cm:
            connect(CID, endpoints=[f.path], dial=lambda ep: REAL_DIAL_UNIX(ep, uid=other), timeout=1.0)
        self.assertIn(f.path + ": the socket belongs to another account", str(cm.exception))
        self.assertTrue(f.hello.empty(), "nothing is sent to a socket that belongs to another account")

    def test_a_socket_nobody_listens_on(self):
        f = UnixFakeDiscord(self)
        missing = os.path.join(f.dir, "discord-ipc-1")
        with self.assertRaises(NotFoundError) as cm:
            connect(CID, endpoints=[missing], dial=REAL_DIAL_UNIX, timeout=1.0)
        self.assertTrue(str(cm.exception).startswith("could not talk to Discord (tried 1 socket(s)): " + missing))


class EndpointTests(unittest.TestCase):
    def test_search_dirs_order(self):
        with tempfile.TemporaryDirectory() as rt:
            for d in ("app/com.example.Vesktop", "app/.hidden", ".flatpak/abc/xdg-run", ".flatpak/def"):
                os.makedirs(os.path.join(rt, d))
            env = {"XDG_RUNTIME_DIR": rt, "TMPDIR": "/var/tmpx", "TEMP": "/var/tmpx"}
            dirs = ipc.search_dirs(environ=env, system="linux", uid=1000)
            self.assertEqual(dirs, [
                rt,
                os.path.join(rt, "app", "com.discordapp.Discord"),
                os.path.join(rt, "snap.discord"),
                os.path.join(rt, "snap.discord-canary"),
                os.path.join(rt, "app", ".hidden"),             # like Go, "*" matches dot-names
                os.path.join(rt, "app", "com.example.Vesktop"),
                os.path.join(rt, ".flatpak", "abc", "xdg-run"),
            ])
            # Discord only uses the shared temporary folders when XDG_RUNTIME_DIR is unset
            self.assertNotIn("/tmp", dirs)
            self.assertNotIn("/var/tmpx", dirs)

    def test_search_dirs_without_runtime_dir(self):
        self.assertEqual(ipc.search_dirs(environ={}, system="linux", uid=1234)[:1], ["/run/user/1234"])
        self.assertEqual(ipc.search_dirs(environ={}, system="linux", uid=1234)[-1], "/tmp")
        self.assertEqual(ipc.search_dirs(environ={"TMPDIR": "/var/folders/x/T", "TEMP": "/var/tmpx"},
                                         system="darwin"),
                         ["/var/folders/x/T", "/var/tmpx", "/tmp"])
        self.assertEqual(ipc.search_dirs(environ={}, system="darwin"), ["/tmp"])
        self.assertEqual(ipc.search_dirs(environ={}, system="win32"), ["\\\\.\\pipe\\"])

    def test_endpoints_skip_sockets_of_another_account(self):
        with tempfile.TemporaryDirectory() as d:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.addCleanup(s.close)
            s.bind(os.path.join(d, "discord-ipc-0"))
            me = os.getuid()
            self.assertEqual(ipc.endpoints_in([d], system="linux", uid=me), [os.path.join(d, "discord-ipc-0")])
            self.assertEqual(ipc.endpoints_in([d], system="linux", uid=me + 1), [])

    def test_endpoints_symlinks(self):
        with tempfile.TemporaryDirectory() as real, tempfile.TemporaryDirectory() as d:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.addCleanup(s.close)
            target = os.path.join(real, "discord-ipc-0")
            s.bind(target)
            link = os.path.join(d, "discord-ipc-1")
            os.symlink(target, link)
            me = os.getuid()
            # a link this account made to its own socket is fine (the usual Flatpak workaround)
            self.assertEqual(ipc.endpoints_in([d], system="linux", uid=me), [link])
            # a link that is not this account's is skipped even when it leads to a socket
            real_lstat = os.lstat

            def foreign_link(p, *a, **k):
                st = real_lstat(p, *a, **k)
                if p == link:
                    return os.stat_result((st.st_mode, st.st_ino, st.st_dev, st.st_nlink, me + 1) + tuple(st)[5:])
                return st

            with mock.patch.object(ipc.os, "lstat", foreign_link):
                self.assertEqual(ipc.endpoints_in([d], system="linux", uid=me), [])
            # a dangling link and a link to a plain file are skipped
            os.remove(link)
            os.symlink(os.path.join(real, "gone"), link)
            open(os.path.join(real, "plain"), "w").close()
            os.symlink(os.path.join(real, "plain"), os.path.join(d, "discord-ipc-2"))
            self.assertEqual(ipc.endpoints_in([d], system="linux", uid=me), [])

    def test_endpoints_only_sockets_lowest_number_first(self):
        with tempfile.TemporaryDirectory() as d1, tempfile.TemporaryDirectory() as d2:
            # a socket we bind ourselves in a temporary folder (never connected to), plus a plain file
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.addCleanup(s.close)
            s.bind(os.path.join(d2, "discord-ipc-3"))
            s2 = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.addCleanup(s2.close)
            s2.bind(os.path.join(d1, "discord-ipc-7"))
            open(os.path.join(d1, "discord-ipc-0"), "w").close()
            self.assertEqual(ipc.endpoints_in([d1, d2], system="linux"),
                             [os.path.join(d2, "discord-ipc-3"), os.path.join(d1, "discord-ipc-7")])

    def test_windows_lists_all_ten_pipes(self):
        eps = ipc.endpoints_in([], system="win32")
        self.assertEqual(len(eps), 10)
        self.assertEqual(eps[0], "\\\\.\\pipe\\discord-ipc-0")


if __name__ == "__main__":
    unittest.main()
