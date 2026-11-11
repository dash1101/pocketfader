"""Fakes shared by the Discord status tests: a clock, a Discord connection, an engine harness, and a guard that
fails loudly if anything tries to reach the real Discord (its sockets are never dialed in tests)."""
import json
import logging
import os
import queue
import socket
import tempfile
import threading
import unittest
from unittest import mock

from starcast.discord import ipc
from starcast.discord import settings as st
from starcast.discord.engine import Engine
from starcast.discord.tracker import Tracker, Update

T0 = 1793707200.0           # 2026-11-03 12:00:00 UTC
T0_MS = 1793707200000
CLIENT_ID_VARS = st.CLIENT_ID_ENV
READY = b'{"cmd":"DISPATCH","evt":"READY","data":{"user":{"username":"tester","global_name":"Tester"}},"nonce":null}'

# The real Unix-socket dialer, kept before any guard replaces it. Tests only ever give it the path of a socket
# they made themselves in a temporary folder (UnixFakeDiscord), never one of Discord's.
REAL_DIAL_UNIX = ipc._dial_unix


class FakeClock:
    def __init__(self, t=T0):
        self.t = float(t)

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


class FakeConn:
    """Records every set_activity argument (an activity dict, or None for a clear)."""

    def __init__(self):
        self._lock = threading.Lock()
        self.calls = []
        self.fail = None            # fail(act) -> exception or None; a failed call is not recorded
        self.done = threading.Event()
        self.close_count = 0

    def set_activity(self, act):
        with self._lock:
            if self.fail is not None:
                err = self.fail(act)
                if err is not None:
                    raise err
            self.calls.append(act)

    @property
    def closed(self):
        return self.done.is_set()

    def close(self):
        self.close_count += 1
        self.done.set()

    def info(self):
        return {"endpoint": "fake", "user": "Tester"}

    def count(self):
        with self._lock:
            return len(self.calls)

    def last(self):
        with self._lock:
            return self.calls[-1] if self.calls else None


def config_with_id():
    c = st.default_config()
    c["clientId"] = "123456789012345678"
    return c


def song(source, title, state, pos, dur):
    return Update(source=source, agent="Test", state=state, title=title, artist="Artist", album="Album",
                  artwork="https://lh3.googleusercontent.com/x=w544-h544", video_id="dQw4w9WgXcQ",
                  position=float(pos), duration=float(dur))


class Harness:
    def __init__(self, cfg, assets=None):
        self.clock = FakeClock()
        self.tracker = Tracker(self.clock)
        self.store = st.Store(cfg)          # memory store: never touches the disk
        self.conns = []
        self.dials = 0
        self.dial_err = None
        self.on_new = None
        self.engine = Engine(self.store, self.tracker, self._dial, self.clock, assets)

    def _dial(self, client_id):
        self.dials += 1
        if self.dial_err is not None:
            raise self.dial_err
        c = FakeConn()
        if self.on_new is not None:
            self.on_new(c)
        self.conns.append(c)
        return c

    def conn(self):
        return self.conns[-1]

    def step(self):
        self.engine.step()


class UnixFakeDiscord:
    """A fake Discord listening on a Unix socket of its own in a temporary folder: answers the handshake with
    READY, records each request and answers it. Exercises the real socket code without going near Discord."""

    def __init__(self, test):
        if not hasattr(socket, "AF_UNIX"):
            test.skipTest("no Unix sockets here")
        self._tmp = tempfile.TemporaryDirectory(prefix="hd")
        test.addCleanup(self._tmp.cleanup)
        self.dir = self._tmp.name
        self.path = os.path.join(self.dir, "discord-ipc-0")
        if len(self.path) > 100:
            test.skipTest("temporary folder path too long for a Unix socket")
        self.hello = queue.Queue()
        self.frames = queue.Queue()
        self._stop = threading.Event()
        self._conns = []
        self._srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._srv.bind(self.path)
        self._srv.listen(4)
        self._srv.settimeout(0.1)
        self._thread = threading.Thread(target=self._accept_loop, daemon=True)
        self._thread.start()
        test.addCleanup(self.close)

    def _accept_loop(self):
        workers = []
        while not self._stop.is_set():
            try:
                c, _ = self._srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            c.settimeout(0.1)
            self._conns.append(c)
            t = threading.Thread(target=self._serve, args=(c,), daemon=True)
            t.start()
            workers.append(t)
        for t in workers:
            t.join(3)

    def _serve(self, c):
        r = ipc.FrameReader(c)
        try:
            op, payload = r.read_frame(should_stop=self._stop.is_set)
            if op != ipc.OP_HANDSHAKE:
                return
            self.hello.put(json.loads(payload))
            ipc.write_frame(c, ipc.OP_FRAME, READY)
            while True:
                op, payload = r.read_frame(should_stop=self._stop.is_set)
                if op != ipc.OP_FRAME:
                    continue
                req = json.loads(payload)
                self.frames.put(req)
                ipc.write_frame(c, ipc.OP_FRAME, json.dumps(
                    {"cmd": req["cmd"], "evt": None, "nonce": req["nonce"], "data": {}}).encode())
        except (ipc.IPCError, OSError):
            return

    def close(self):
        self._stop.set()
        self._thread.join(5)
        self._srv.close()
        for c in self._conns:
            c.close()


def no_client_id_env():
    """Like the Go tests' t.Setenv("YTM_BEACON_CLIENT_ID", ""): the variables count as unset."""
    return mock.patch.dict(os.environ, {k: "" for k in CLIENT_ID_VARS})


class DialGuard:
    """Replaces the real dialer and the real socket search. Any call is recorded and fails at once."""

    def __init__(self):
        self.calls = []
        self._patchers = []

    def _trip(self, name):
        def f(*a, **k):
            self.calls.append((name, a))
            raise AssertionError("a test tried to reach the real Discord (%s)" % name)
        return f

    def install(self):
        self._patchers = [mock.patch.object(ipc, "dial_endpoint", self._trip("dial_endpoint")),
                          mock.patch.object(ipc, "discover_endpoints", self._trip("discover_endpoints")),
                          mock.patch.object(ipc, "_dial_unix", self._trip("_dial_unix")),
                          mock.patch.object(ipc, "_dial_pipe", self._trip("_dial_pipe"))]
        for p in self._patchers:
            p.start()
        # keep the engine's expected warnings ("cannot connect to Discord: ...") out of the test output
        self._quiet = logging.NullHandler()
        self._log = logging.getLogger("starcast.discord")
        self._log.addHandler(self._quiet)
        self._propagate, self._log.propagate = self._log.propagate, False

    def uninstall(self):
        for p in reversed(self._patchers):
            p.stop()
        self._patchers = []
        self._log.removeHandler(self._quiet)
        self._log.propagate = self._propagate

    def check(self):
        if self.calls:
            raise AssertionError("the real Discord dialer was used during the tests: %r" % (self.calls,))


GUARD = DialGuard()


def setUpModule():
    GUARD.install()


def tearDownModule():
    GUARD.uninstall()
    GUARD.check()


class GuardTests(unittest.TestCase):
    def test_guard_catches_the_default_dialer(self):
        before = list(GUARD.calls)
        with self.assertRaises(AssertionError):
            ipc.connect("123456789012345678")          # no endpoints and no dialer given: the defaults
        self.assertEqual(GUARD.calls[-1][0], "discover_endpoints")
        # endpoints given, but the default dialer: connect() reports the failure as a detail, the guard records it
        with self.assertRaises(ipc.NotFoundError) as cm:
            ipc.connect("123456789012345678", endpoints=["x"])
        self.assertIn("real Discord", str(cm.exception))
        self.assertEqual(GUARD.calls[-1][0], "dial_endpoint")
        GUARD.calls[:] = before

    def test_clock(self):
        c = FakeClock()
        c.advance(1.5)
        self.assertEqual(c(), T0 + 1.5)
