"""Discord status for the apps (/api/beacon...), the old YTM Beacon app (finding it, keeping out of its way, moving
over from it), and the guard that keeps every test away from the real one.

A real YTM Beacon may run on the computer that runs these tests. So nothing here may contact 127.0.0.1:41713-41717
or run a real ytm-beacon program: the old app is always a small fake HTTP server on a free port, its program a fake
script in a temporary folder (or a fake function), and the guard below fails the run if anything slips through.
Discord is never contacted either: the engine gets a fake connection, or none.
"""
import copy
import http.client
import json
import os
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from starcast import __version__
from starcast import beacon as bc
from starcast.audio import AudioFetcher
from starcast.backends.base import NullEngine
from starcast.browser_ext import BrowserExtension
from starcast.beacon import BadRequest, BeaconApp, BeaconError, DiscordStatus
from starcast.config import JsonFile
from starcast.discord import IPCError, Presence
from starcast.opener import Opener
from starcast.server import App, Handler
from starcast.updater import Updater

from .fakes import FakeAudio, FakeMedia
from .test_discord_fakes import DialGuard, FakeConn


# ---------------------------------------------------------------- the guard

class BeaconGuard:
    """Refuses (and records) any connection to YTM Beacon's real ports, on any address, and running any program
    called ytm-beacon outside the temporary folder, or at the places the real one is installed."""

    def __init__(self):
        self.calls = []
        self._patchers = []

    def _real_programs(self):
        return {os.path.realpath(p) for p in bc.beacon_binaries()}

    def install(self):
        real_connect = socket.create_connection
        real_popen = subprocess.Popen
        guard = self
        tmp = os.path.realpath(tempfile.gettempdir())
        real_programs = self._real_programs()

        def create_connection(address, *a, **k):
            try:
                port = int(address[1])
            except (TypeError, ValueError, IndexError):
                port = None
            if port in bc.PORTS:
                guard.calls.append(("connect", address))
                raise AssertionError("a test tried to reach YTM Beacon's real port %r" % (address,))
            return real_connect(address, *a, **k)

        class GuardedPopen(real_popen):
            def __init__(self, args, *a, **k):
                argv = list(args) if isinstance(args, (list, tuple)) else [args]
                prog = os.path.realpath(str(argv[0])) if argv else ""
                named = os.path.basename(prog).lower().startswith("ytm-beacon")
                if prog in real_programs or (named and not prog.startswith(tmp + os.sep)):
                    guard.calls.append(("run", tuple(map(str, argv))))
                    raise AssertionError("a test tried to run a real ytm-beacon program: %r" % (argv,))
                super().__init__(args, *a, **k)

        self._patchers = [mock.patch.object(socket, "create_connection", create_connection),
                          mock.patch.object(subprocess, "Popen", GuardedPopen)]
        for p in self._patchers:
            p.start()

    def uninstall(self):
        for p in reversed(self._patchers):
            p.stop()
        self._patchers = []

    def check(self):
        if self.calls:
            raise AssertionError("the real YTM Beacon was reached during the tests: %r" % (self.calls,))


GUARD = BeaconGuard()
DISCORD_GUARD = DialGuard()


def setUpModule():
    GUARD.install()
    DISCORD_GUARD.install()


def tearDownModule():
    DISCORD_GUARD.uninstall()
    GUARD.uninstall()
    DISCORD_GUARD.check()
    GUARD.check()


# ---------------------------------------------------------------- fakes

PLAYING = {     # the engine's raw status (YTM Beacon's /v1/status keys)
    "app": "ytm-starcast", "version": "0.2.0a1", "port": None, "uptimeSec": 600, "pid": 4242,
    "configPath": "/home/someone/.config/ytm-starcast/discord.json", "logPath": "",
    "extensionDirs": {}, "clientIdSet": True,
    "discord": {"state": "connected", "user": "ExampleUser", "endpoint": "/run/user/1000/discord-ipc-0"},
    "extension": {"lastSeenAgoSec": 2, "everConnected": True, "quietAfterSec": 90,
                  "sources": [{"id": "tab-1", "agent": "Brave", "state": "playing", "ageSec": 2}]},
    "nowPlaying": {"title": "Song", "artist": "Artist", "album": "Album", "artwork": "https://example.com/a.jpg",
                   "videoId": "abc123", "state": "playing", "position": 42.5, "duration": 201},
    "presenceActive": True, "visible": True, "snoozeSec": 0, "hidden": "",
    "config": {"enabled": True, "clientId": "123456789012345678", "showButtons": True, "showPaused": True,
               "pauseTimeoutMinutes": 5, "showAlbumArt": True, "statusDisplay": "name", "showProgress": True,
               "showStateIcon": True, "privateMode": False, "hideMatching": ["secret word"]},
}

# A fake answers at once, but a busy machine may make it wait: give it time (the product waits 1.5 s).
SLOW_MACHINE = 5.0

BEACON_CONFIG = {"enabled": True, "clientId": "", "showButtons": False, "showPaused": True, "pauseTimeoutMinutes": 15,
                 "showAlbumArt": True, "statusDisplay": "details", "showProgress": True, "showStateIcon": True,
                 "privateMode": True, "hideMatching": ["secret"]}


def variant(**edits):
    s = copy.deepcopy(PLAYING)
    for k, v in edits.items():
        if k in ("discord", "extension"):
            s[k].update(v)
        else:
            s[k] = v
    return s


def wait_until(cond, timeout=5.0):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.02)
    return bool(cond())


class FakeBeaconApp:
    """The old YTM Beacon app, as a tiny HTTP server on a free port. Records what it was asked."""

    def __init__(self, app="ytm-beacon", version="v0.1.4", config=None, snooze=0):
        self.app, self.version = app, version
        self.config = copy.deepcopy(BEACON_CONFIG if config is None else config)
        self.snooze = snooze            # seconds left of a timed hide it was told
        self.calls = []
        self.closed = False
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, code, obj):
                raw = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                outer.calls.append(("GET", self.path, self.headers.get("Origin"), self.headers.get("Host")))
                if self.path == "/v1/ping":
                    return self._reply(200, {"app": outer.app, "version": outer.version})
                if self.path == "/v1/config":
                    return self._reply(200, outer.config)
                if self.path == "/v1/status":
                    return self._reply(200, {"app": outer.app, "version": outer.version, "snoozeSec": outer.snooze,
                                             "visible": outer.snooze == 0, "config": outer.config})
                self._reply(404, {"error": "no such thing"})

            def do_POST(self):
                outer.calls.append(("POST", self.path, self.headers.get("Origin"), self.headers.get("Host")))
                self._reply(404, {"error": "no such thing"})

        self.httpd = bind_away_from_beacon(lambda: ThreadingHTTPServer(("127.0.0.1", 0), H),
                                           lambda h: h.server_address[1], lambda h: h.server_close())
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]

    def close(self):
        if not self.closed:
            self.closed = True
            self.httpd.shutdown()
            self.httpd.server_close()


def bind_away_from_beacon(bind, port_of, close):
    """bind() until the system hands out a port that isn't one of YTM Beacon's (it may, now and then: they lie in the
    range it hands out). Returns what bind() made."""
    for _ in range(50):
        made = bind()
        if port_of(made) not in bc.PORTS:
            return made
        close(made)
    raise AssertionError("only YTM Beacon's ports were handed out")


def free_port():
    """A port nothing listens on (taken, then given back)."""
    def bind():
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        return s
    with bind_away_from_beacon(bind, lambda s: s.getsockname()[1], lambda s: s.close()) as s:
        return s.getsockname()[1]


class FakeDiscord:
    """dial(client_id) for the engine: a fake connection each time, counted."""

    def __init__(self, fail=False):
        self.conns, self.fail = [], fail

    def __call__(self, client_id):
        if self.fail:
            raise IPCError("Discord isn't running (test)")
        c = FakeConn()
        self.conns.append(c)
        return c


def refuse_to_run(cmd, timeout):
    raise AssertionError("this test gives no uninstall command, so none may run: %r" % (cmd,))


def make_discord(folder, ports=(), binaries=(), runner=None, dry_run=False, dial=None, beacon_config=None, **kw):
    """A Discord status that can't reach anything real: no ports to look at (or the fake's), no program (or a
    fake one), no Discord (or a fake one), settings in a temporary folder."""
    pres = Presence(Path(folder) / "discord.json", dial=dial or FakeDiscord(fail=True), dry_run=dry_run,
                    import_beacon_from=None)
    old = BeaconApp(timeout=SLOW_MACHINE, ports=ports, binaries=list(binaries), runner=runner or refuse_to_run)
    return DiscordStatus(pres, beacon_app=old, dry_run=dry_run, beacon_config=beacon_config, **kw)


class TempCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)

    def old_app(self, **kw):
        b = FakeBeaconApp(**kw)
        self.addCleanup(b.close)
        return b

    def fake_program(self, name="ytm-beacon"):
        d = self.tmp / "home" / ".local" / "bin"
        d.mkdir(parents=True, exist_ok=True)
        p = d / name
        p.write_text("#!/bin/sh\nexit 0\n")
        p.chmod(0o755)
        return p

    def discord(self, **kw):
        ds = make_discord(self.tmp / "ytm-starcast", **kw)
        self.addCleanup(ds.stop)
        return ds


# ---------------------------------------------------------------- the guard itself

class GuardTests(unittest.TestCase):
    def test_the_guard_stops_the_real_ports(self):
        before = list(GUARD.calls)
        for port in bc.PORTS:
            with self.assertRaises(AssertionError):
                socket.create_connection(("127.0.0.1", port), timeout=0.1)
        # the defaults point at the real app, and the guard stops them before any connection is made
        self.assertEqual(BeaconApp().ports, bc.PORTS)
        with self.assertRaises(AssertionError):
            BeaconApp().find()
        self.assertEqual(len(GUARD.calls), len(before) + len(bc.PORTS) + 1)
        GUARD.calls[:] = before

    def test_the_guard_stops_the_real_program(self):
        before = list(GUARD.calls)
        for prog in bc.beacon_binaries() + [Path("/usr/local/bin/ytm-beacon"), Path("ytm-beacon")]:
            with self.assertRaises(AssertionError, msg=str(prog)):
                bc.run_command([str(prog), "uninstall"], 1)
        self.assertEqual(len(GUARD.calls), len(before) + len(bc.beacon_binaries()) + 2)
        GUARD.calls[:] = before

    def test_fakes_are_never_on_the_real_ports(self):
        b = FakeBeaconApp()
        self.addCleanup(b.close)
        self.assertNotIn(b.port, bc.PORTS)
        self.assertNotIn(free_port(), bc.PORTS)

    def test_a_beacon_port_handed_out_is_given_back_and_bound_again(self):
        handed = iter([bc.PORTS[0], bc.PORTS[2], 50123])
        closed = []
        self.assertEqual(bind_away_from_beacon(lambda: next(handed), lambda p: p, closed.append), 50123)
        self.assertEqual(closed, [bc.PORTS[0], bc.PORTS[2]])
        with self.assertRaises(AssertionError):
            bind_away_from_beacon(lambda: bc.PORTS[0], lambda p: p, closed.append)


# ---------------------------------------------------------------- headline and the shape the apps see

class HeadlineTests(unittest.TestCase):
    def h(self, s, ok=True):
        return bc.headline(s, ok)

    def test_fixtures(self):
        cases = [
            (PLAYING, "showing"),
            (variant(visible=False, snoozeSec=0, hidden="off", presenceActive=False), "off"),
            (variant(visible=False, snoozeSec=3120, hidden="snoozed", presenceActive=False), "snoozed"),
            (variant(discord={"state": "disconnected", "lastError": "Discord isn't running"}), "discord-down"),
            (variant(extension={"lastSeenAgoSec": None, "everConnected": False, "sources": []}), "extension-missing"),
            (variant(extension={"lastSeenAgoSec": None, "everConnected": True, "sources": []}), "extension-quiet"),
            (variant(extension={"lastSeenAgoSec": 300}), "extension-quiet"),
            (variant(nowPlaying=None, hidden="", presenceActive=False), "idle"),
            (variant(hidden="paused", presenceActive=False), "paused-hidden"),
            (variant(hidden="paused-timeout", presenceActive=False), "paused-timeout"),
            (variant(hidden="filtered", presenceActive=False), "filtered"),
            (variant(presenceActive=False), "waiting"),
        ]
        for status, want in cases:
            self.assertEqual(self.h(status), want, want)

    def test_built_in_states(self):
        idle = variant(nowPlaying=None, presenceActive=False)
        self.assertEqual(bc.builtin_headline(idle, ad=True), "ad")
        self.assertEqual(bc.builtin_headline(idle), "idle")
        self.assertEqual(bc.builtin_headline(PLAYING, ad=True), "showing")      # another tab plays a song
        self.assertEqual(bc.builtin_headline(variant(visible=False, nowPlaying=None), ad=True), "off")
        for s in (PLAYING, idle, variant(visible=False)):
            self.assertEqual(bc.builtin_headline(s, beacon_running=True, ad=True), "beacon-running")

    def test_unreachable_and_unsupported(self):
        self.assertEqual(self.h(None), "unreachable")
        self.assertEqual(self.h(PLAYING, ok=False), "unsupported")

    def test_without_visible_uses_enabled(self):
        old = variant()
        del old["visible"]
        old["config"]["enabled"] = False
        self.assertEqual(self.h(old), "off")

    def test_versions(self):
        self.assertTrue(bc.version_ok("v0.1.4"))
        self.assertTrue(bc.version_ok("v0.2.0"))
        self.assertTrue(bc.version_ok(__version__))         # the built-in engine reports YTM Starcast's version
        self.assertTrue(bc.version_ok("dev"))
        self.assertFalse(bc.version_ok("v0.1.3"))
        self.assertEqual(bc.normalize(variant(version="v0.1.1"))["headline"], "unsupported")


class NormalizeTests(unittest.TestCase):
    def test_private_fields_never_leave(self):
        out = json.dumps(bc.normalize(PLAYING))
        for secret in ("123456789012345678", "/home/someone", "4242", "secret word", "clientId", "hideMatching",
                       "tab-1", "discord-ipc", "Brave"):
            self.assertNotIn(secret, out)

    def test_shape(self):
        n = bc.normalize(PLAYING, now=1000)
        self.assertEqual(n["headline"], "showing")
        self.assertEqual(n["fetchedAt"], 1_000_000)
        self.assertEqual(n["discord"], {"state": "connected", "user": "ExampleUser"})
        self.assertEqual(n["nowPlaying"]["positionSec"], 42.5)
        self.assertEqual(n["settings"]["statusDisplay"], "name")
        self.assertEqual(bc.normalize(None, now=1)["reachable"], False)

    def test_detail_from_discord_error(self):
        n = bc.normalize(variant(discord={"state": "disconnected", "lastError": "Discord isn't running"}))
        self.assertEqual(n["detail"], "Discord isn't running")

    def test_discord_settings_add_the_words_and_the_id(self):
        s = bc.discord_settings(PLAYING["config"])
        self.assertEqual((s["hideMatching"], s["clientId"]), (["secret word"], "123456789012345678"))
        self.assertEqual(len(s), 10)
        self.assertEqual(bc.discord_settings({}), {**bc.normalize(PLAYING)["settings"], "hideMatching": [],
                                                    "clientId": ""})


class ValidationTests(unittest.TestCase):
    def test_visibility(self):
        self.assertEqual(bc.validate_visibility({"action": "show"}), {"action": "show"})
        self.assertEqual(bc.validate_visibility({"action": "hide", "minutes": 60}), {"action": "hide", "minutes": 60})
        for bad in ({"action": "toggle"}, {"action": "hide", "minutes": 0}, {"action": "hide", "minutes": 99999},
                    {"action": "hide", "minutes": True}, {"action": "hide", "minutes": 1.5}, {"action": "hide", "minutes": "5"},
                    {"action": "show", "minutes": 5}, {"action": "hide", "extra": 1}, {}, [], None):
            with self.assertRaises(BadRequest, msg=str(bad)):
                bc.validate_visibility(bad)

    def test_settings(self):
        ok = {"privateMode": True, "pauseTimeoutMinutes": 0, "statusDisplay": "details", "hideMatching": ["a", "B"]}
        self.assertEqual(bc.validate_settings(ok), ok)
        self.assertEqual(bc.validate_settings({"hideMatching": []}), {"hideMatching": []})
        for bad in ({}, {"clientId": "123456789012345678"}, {"enabled": False}, {"hideMatching": "x"},
                    {"hideMatching": [1]}, {"hideMatching": [None]}, {"hideMatching": ["x"] * 201},
                    {"hideMatching": ["x" * 401]}, {"privateMode": "yes"}, {"privateMode": 1},
                    {"pauseTimeoutMinutes": -1}, {"pauseTimeoutMinutes": 1441}, {"pauseTimeoutMinutes": True},
                    {"statusDisplay": "bogus"}, {"privateMode": True, "nope": 1}, [], None):
            with self.assertRaises(BadRequest, msg=str(bad)):
                bc.validate_settings(bad)


# ---------------------------------------------------------------- the old app: finding it

class BeaconAppTests(TempCase):
    def test_running_and_its_version(self):
        b = self.old_app(version="v0.1.4")
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(free_port(), b.port), binaries=[])
        self.assertEqual(old.find(), (b.port, "v0.1.4"))
        self.assertEqual(old.info(), {"running": True, "installed": False, "canMigrate": False, "version": "v0.1.4"})
        # what it was asked: only /v1/ping, as a local program (no Origin, loopback Host)
        self.assertEqual({c[1] for c in b.calls}, {"/v1/ping"})
        self.assertEqual({c[2] for c in b.calls}, {None})
        self.assertTrue(all(c[3].startswith("127.0.0.1:") for c in b.calls))

    def test_another_program_on_the_port_is_not_it(self):
        other = self.old_app(app="something-else")
        self.assertIsNone(BeaconApp(timeout=SLOW_MACHINE, ports=(other.port,), binaries=[]).find())

    def test_not_running(self):
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(free_port(),), binaries=[])
        self.assertIsNone(old.find())
        self.assertIsNone(old.live_config())
        self.assertEqual(old.info(), {"running": False, "installed": False, "canMigrate": False, "version": None})

    def test_found_again_on_another_port(self):
        first, second = FakeBeaconApp(), self.old_app()
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(first.port, second.port), binaries=[])
        self.assertEqual(old.find()[0], first.port)
        first.close()
        self.assertEqual(old.find()[0], second.port)

    def test_live_config(self):
        b = self.old_app()
        self.assertEqual(BeaconApp(timeout=SLOW_MACHINE, ports=(b.port,), binaries=[]).live_config(), BEACON_CONFIG)

    def test_installed(self):
        prog = self.fake_program()
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(), binaries=[self.tmp / "nope", prog])
        self.assertEqual(old.binary(), prog)
        self.assertEqual(old.info(), {"running": False, "installed": True, "canMigrate": True, "version": None})

    def test_info_is_remembered_for_a_while(self):
        b = self.old_app()
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(b.port,), binaries=[])
        self.assertTrue(old.info()["running"])
        b.close()
        self.assertTrue(old.info(max_age=60)["running"])        # remembered
        self.assertFalse(old.info()["running"])                 # found out again

    def test_wait_gone(self):
        b = self.old_app()
        old = BeaconApp(timeout=SLOW_MACHINE, ports=(b.port,), binaries=[])
        self.assertFalse(old.wait_gone(0.2, step=0.05))
        threading.Timer(0.2, b.close).start()
        t = time.time()
        self.assertTrue(old.wait_gone(3, step=0.05))
        self.assertLess(time.time() - t, 2)

    def test_where_its_installer_puts_it(self):
        home = Path("/home/u")
        self.assertEqual(bc.beacon_binaries("linux", {}, home), [home / ".local" / "bin" / "ytm-beacon"])
        self.assertEqual(bc.beacon_binaries("darwin", {}, home), [home / ".local" / "bin" / "ytm-beacon"])
        win = bc.beacon_binaries("win32", {"LOCALAPPDATA": "C:\\Users\\u\\AppData\\Local"}, home)
        self.assertEqual(win, [Path("C:\\Users\\u\\AppData\\Local") / "ytm-beacon" / "ytm-beacon.exe",
                               Path("C:\\Users\\u\\AppData\\Local") / "ytm-beacon" / "ytm-beacon-bg.exe"])
        self.assertEqual(bc.beacon_binaries("win32", {}, home)[0],
                         home / "AppData" / "Local" / "ytm-beacon" / "ytm-beacon.exe")
        self.assertEqual(bc.beacon_binaries("linux", {"YTM_BEACON_HOME": "/opt/b"}, home), [Path("/opt/b/bin/ytm-beacon")])
        self.assertEqual(bc.beacon_binaries("win32", {"YTM_BEACON_HOME": "D:\\b"}, home),
                         [Path("D:\\b") / "bin" / "ytm-beacon.exe"])


@unittest.skipIf(os.name == "nt", "the fake programs are shell scripts")
class RunCommandTests(TempCase):
    """run_command, for real, on fake programs in a temporary folder."""

    def script(self, body, name="ytm-beacon"):
        d = self.tmp / "bin"
        d.mkdir(exist_ok=True)
        p = d / name
        p.write_text("#!/bin/sh\n" + body)
        p.chmod(0o755)
        return p

    def test_no_input_its_words_and_exit_code(self):
        log = self.tmp / "ran.txt"
        p = self.script(f'echo "args: $*" > "{log}"\n'
                        f'if read line; then echo "stdin: data" >> "{log}"; else echo "stdin: none" >> "{log}"; fi\n'
                        'echo "Removed YTM Beacon."\necho "oops" >&2\nexit 3\n')
        code, out = bc.run_command([str(p), "uninstall"], 10)
        self.assertEqual(code, 3)
        self.assertEqual(out.split(), ["Removed", "YTM", "Beacon.", "oops"])
        self.assertEqual(log.read_text().splitlines(), ["args: uninstall", "stdin: none"])    # never waits for Enter

    def test_it_cannot_hang(self):
        p = self.script("exec sleep 5\n")
        t = time.time()
        code, out = bc.run_command([str(p), "uninstall"], 0.3)
        self.assertLess(time.time() - t, 3)
        self.assertNotEqual(code, 0)
        self.assertIn("did not finish", out)

    def test_missing_program(self):
        code, out = bc.run_command([str(self.tmp / "missing"), "uninstall"], 5)
        self.assertEqual(code, 1)
        self.assertTrue(out)


# ---------------------------------------------------------------- Discord status

class StatusTests(TempCase):
    def test_shape_for_the_apps(self):
        ds = self.discord()
        ds.presence.set_config({"hideMatching": ["Secret Word"], "clientId": "123456789012345678"})
        st = ds.status()
        self.assertTrue(st["reachable"])
        self.assertEqual(st["version"], __version__)
        self.assertEqual(st["headline"], "discord-down")         # not started: nothing talks to Discord
        self.assertEqual(st["discord"], {"state": "disconnected"})
        self.assertEqual(st["settings"]["hideMatching"], ["secret word"])
        self.assertEqual(st["settings"]["clientId"], "123456789012345678")
        self.assertEqual(st["beaconApp"], {"running": False, "installed": False, "canMigrate": False, "version": None})
        self.assertEqual(st["install"], {"installed": True, "canInstall": False, "canStart": False, "latest": None,
                                         "updateAvailable": False, "job": {"state": "idle", "message": ""}})
        for key in ("visible", "snoozeSec", "extension", "nowPlaying", "presenceActive", "fetchedAt"):
            self.assertIn(key, st)
        out = json.dumps(st)
        for private in (str(self.tmp), "discord.json", str(os.getpid()), "sources", "configPath"):
            self.assertNotIn(private, out)

    def test_shows_on_discord_once_running(self):
        dial = FakeDiscord()
        ds = self.discord(dial=dial)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.presence.running and dial.conns))
        ds.feed("tab-1", {"title": "Song", "artist": "Band - Topic", "album": "LP", "playing": True,
                          "position": 10, "duration": 200}, agent="Firefox")
        self.assertTrue(wait_until(lambda: ds.status()["headline"] == "showing"))
        st = ds.status()
        self.assertEqual(st["discord"], {"state": "connected", "user": "Tester"})
        self.assertEqual((st["nowPlaying"]["title"], st["nowPlaying"]["artist"], st["nowPlaying"]["album"]),
                         ("Song", "Band", "LP"))
        self.assertEqual(dial.conns[-1].last()["details"], "Song")
        ds.stop()
        self.assertIsNone(dial.conns[-1].last())                 # the card is cleared on the way out
        self.assertTrue(dial.conns[-1].closed)

    def test_a_paused_tab_is_kept_alive_by_its_polls(self):
        ds = self.discord()
        ds.feed("A", {"title": "Song", "paused": True})
        self.assertTrue(ds.touch("A"))
        self.assertFalse(ds.touch("B"))
        self.assertIsNotNone(ds.status()["extension"]["lastSeenAgoSec"])

    def test_adverts(self):
        clock = [1000.0]
        dial = FakeDiscord()
        ds = self.discord(dial=dial, clock=lambda: clock[0])
        ds.start()
        ds.feed("A", {"title": "Song", "playing": True})
        self.assertTrue(wait_until(lambda: ds.status()["headline"] == "showing"))
        ds.feed("A", {"title": "Advert", "playing": True, "ad": True})      # the engine drops the tab's song
        self.assertTrue(wait_until(lambda: ds.status()["headline"] == "ad"))
        self.assertIsNone(ds.status()["nowPlaying"])
        self.assertTrue(wait_until(lambda: dial.conns[-1].last() is None))   # nothing on Discord during it
        ds.feed("A", {"title": "Song", "playing": True, "ad": False})
        self.assertFalse(ds.ad_playing())
        self.assertTrue(wait_until(lambda: ds.status()["headline"] == "showing"))
        ds.feed("B", {"title": "Advert", "ad": True})
        ds.bye("B")
        self.assertFalse(ds.ad_playing())
        ds.feed(None, {"title": "Advert", "ad": True})                     # an extension that names no tab
        self.assertTrue(ds.ad_playing())
        clock[0] += bc.AD_TTL + 1                                           # ...and vanished without a goodbye
        self.assertFalse(ds.ad_playing())

    def test_one_switch(self):
        ds = self.discord()
        self.assertTrue(ds.enabled)
        ds.set_enabled(False)
        self.assertFalse(ds.enabled)
        self.assertEqual(ds.status()["headline"], "off")
        self.assertEqual(ds.set_visibility({"action": "show"}), {"enabled": True, "snoozeSec": 0, "visible": True})
        self.assertTrue(ds.enabled)
        self.assertEqual(ds.set_visibility({"action": "hide"}), {"enabled": False, "snoozeSec": 0, "visible": False})
        self.assertFalse(ds.enabled)
        saved = json.loads((self.tmp / "ytm-starcast" / "discord.json").read_text())
        self.assertFalse(saved["enabled"])

    def test_timed_hide(self):
        ds = self.discord()
        r = ds.set_visibility({"action": "hide", "minutes": 30})
        self.assertEqual((r["enabled"], r["visible"]), (True, False))
        self.assertTrue(1795 <= r["snoozeSec"] <= 1800)
        self.assertEqual(ds.status()["headline"], "snoozed")
        for bad in ({"action": "toggle"}, {"action": "hide", "minutes": 0}, {}):
            with self.assertRaises(BadRequest):
                ds.set_visibility(bad)

    def test_settings(self):
        ds = self.discord()
        out = ds.set_settings({"privateMode": True, "hideMatching": [" Foo ", "foo", "BAR"], "statusDisplay": "state"})
        self.assertEqual((out["privateMode"], out["hideMatching"], out["statusDisplay"], out["clientId"]),
                         (True, ["foo", "bar"], "state", ""))
        saved = json.loads((self.tmp / "ytm-starcast" / "discord.json").read_text())
        self.assertEqual((saved["privateMode"], saved["hideMatching"]), (True, ["foo", "bar"]))
        for bad in ({"clientId": "123456789012345678"}, {"enabled": False}, {"hideMatching": [5]}, {}):
            with self.assertRaises(BadRequest):
                ds.set_settings(bad)

    @unittest.skipIf(os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0), "needs folder permissions")
    def test_settings_that_cannot_be_saved(self):
        ds = self.discord()
        ds.set_settings({"privateMode": False})
        folder = self.tmp / "ytm-starcast"
        folder.chmod(0o500)
        self.addCleanup(folder.chmod, 0o700)
        with self.assertRaises(BeaconError) as cm:
            ds.set_settings({"privateMode": True})
        self.assertEqual((cm.exception.code, cm.exception.http), ("SAVE_FAILED", 500))
        self.assertFalse(ds.status()["settings"]["privateMode"])        # nothing changed
        with self.assertRaises(BeaconError):
            ds.set_visibility({"action": "hide"})
        self.assertTrue(ds.enabled)

    def test_nothing_to_install(self):
        with self.assertRaises(BeaconError) as cm:
            self.discord().start_install({"action": "install"})
        self.assertEqual((cm.exception.code, cm.exception.http), ("built_in", 409))

    def test_dry_run_never_connects_and_writes_nothing(self):
        dial = FakeDiscord()
        ds = make_discord(self.tmp / "ytm-starcast", dry_run=True, dial=dial)
        self.addCleanup(ds.stop)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.status().get("detail")))
        st = ds.status()
        self.assertEqual((st["headline"], st["dryRun"]), ("discord-down", True))
        self.assertIn("Dry-run", st["detail"])
        ds.set_settings({"privateMode": True})
        self.assertTrue(ds.status()["settings"]["privateMode"])          # kept in memory
        self.assertEqual(dial.conns, [])
        self.assertFalse((self.tmp / "ytm-starcast" / "discord.json").exists())


class FirstStartTests(TempCase):
    """On the first start, YTM Beacon's own settings file is taken over (once)."""

    def setUp(self):
        super().setUp()
        env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.tmp / "config"),
                                           "APPDATA": str(self.tmp / "config")})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("YTM_BEACON_HOME", None)
        self.beacon_file = self.tmp / "config" / "ytm-beacon" / "config.json"
        self.beacon_file.parent.mkdir(parents=True)
        self.beacon_file.write_text(json.dumps({"privateMode": True, "statusDisplay": "state",
                                                "hideMatching": ["Secret"]}))     # an older, shorter file

    def make(self, folder, dry_run=False):
        ds = DiscordStatus.in_folder(folder, dry_run=dry_run,
                                     beacon_app=BeaconApp(timeout=SLOW_MACHINE, ports=(), binaries=[]))
        self.addCleanup(ds.stop)
        return ds

    def test_taken_over_once(self):
        folder = self.tmp / "config" / "ytm-starcast"
        ds = self.make(folder)
        cfg = ds.presence.get_config()
        self.assertEqual((cfg["privateMode"], cfg["statusDisplay"], cfg["hideMatching"], cfg["showButtons"]),
                         (True, "state", ["secret"], True))                       # missing keys: the defaults
        self.assertEqual(ds.beacon_config, str(self.beacon_file))
        self.assertTrue((folder / "discord.json").exists())
        # later changes win over the old file
        ds.set_settings({"privateMode": False})
        self.assertFalse(self.make(folder).presence.get_config()["privateMode"])
        self.assertTrue(json.loads(self.beacon_file.read_text())["privateMode"])  # the old file is left alone

    def test_trial_run_takes_it_over_in_memory_only(self):
        folder = self.tmp / "config" / "ytm-starcast"
        ds = self.make(folder, dry_run=True)
        self.assertTrue(ds.presence.get_config()["privateMode"])
        self.assertFalse((folder / "discord.json").exists())

    def test_the_app_builds_it_in_the_settings_folder(self):
        app = App(FakeAudio(), NullEngine(), FakeMedia(), prefs=JsonFile(self.tmp / "p.json"),
                  opener=Opener(dry_run=True), updater=Updater(cache=self.tmp / "updates"),
                  audio_fetcher=AudioFetcher(folder=self.tmp / "audio", ytdlp=""), dry_run=True)
        ds = app.beacon
        ds.beacon_app = BeaconApp(ports=(), binaries=[])      # before anything looks for the real one
        self.addCleanup(ds.stop)
        self.assertIsInstance(ds, DiscordStatus)
        self.assertIs(app.presence, ds)
        self.assertTrue(ds.dry_run and ds.presence.dry_run)
        self.assertEqual(Path(ds.presence.settings_path), self.tmp / "config" / "ytm-starcast" / "discord.json")
        self.assertTrue(ds.presence.get_config()["privateMode"])                 # YTM Beacon's settings
        self.assertEqual(app.parts()["discord"], True)


# ---------------------------------------------------------------- keeping out of the old app's way

class GateTests(TempCase):
    def test_never_on_discord_while_the_old_app_runs(self):
        b = self.old_app()
        dial = FakeDiscord()
        ds = self.discord(ports=(b.port,), dial=dial)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        ds.feed("A", {"title": "Song", "playing": True})
        time.sleep(0.3)
        self.assertFalse(ds.presence.running)
        self.assertEqual(dial.conns, [])
        st = ds.status()
        self.assertEqual(st["headline"], "beacon-running")
        self.assertEqual(st["beaconApp"], {"running": True, "installed": False, "canMigrate": False,
                                           "version": "v0.1.4"})
        self.assertEqual(st["discord"], {"state": "disconnected"})
        # it goes away: the built-in engine takes over
        b.close()
        ds.sync()
        self.assertTrue(wait_until(lambda: dial.conns and ds.status()["headline"] == "showing"))

    def test_steps_aside_when_the_old_app_starts(self):
        port = free_port()
        dial = FakeDiscord()
        ds = self.discord(ports=(port,), dial=dial)
        ds.start()
        ds.feed("A", {"title": "Song", "playing": True})
        self.assertTrue(wait_until(lambda: dial.conns and dial.conns[-1].last()))
        b = FakeBeaconApp()
        self.addCleanup(b.close)
        ds.beacon_app.ports = (b.port,)                    # the old app starts (on the port being watched)
        ds.sync()
        self.assertFalse(ds.presence.running)
        self.assertIsNone(dial.conns[-1].last())           # our card cleared, Discord left to the old app
        self.assertEqual(ds.status()["headline"], "beacon-running")

    def test_the_server_starts_and_stops_it(self):
        dial = FakeDiscord()
        ds = self.discord(dial=dial)
        app = App(FakeAudio(), NullEngine(), FakeMedia(), prefs=JsonFile(self.tmp / "p.json"), beacon=ds,
                  opener=Opener(dry_run=True), updater=Updater(cache=self.tmp / "updates"),
                  audio_fetcher=AudioFetcher(folder=self.tmp / "audio", ytdlp=""),
                  extension=BrowserExtension(None, folder=self.tmp / "extension"))
        app.start()
        self.addCleanup(app.stop)
        app.presence_feed("A", {"title": "Song", "playing": True})
        self.assertTrue(wait_until(lambda: dial.conns and dial.conns[-1].last()))
        app.stop()
        self.assertFalse(ds.presence.running)
        self.assertIsNone(dial.conns[-1].last())           # the card is cleared when the server stops
        self.assertTrue(dial.conns[-1].closed)

    def test_not_started_means_never_started(self):
        ds = self.discord()
        ds.sync()
        self.assertFalse(ds.presence.running)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.presence.running))
        ds.stop()
        self.assertFalse(ds.presence.running)
        ds.sync()
        self.assertFalse(ds.presence.running)


    def test_changes_are_refused_while_the_old_app_runs(self):
        b = self.old_app()
        ds = self.discord(ports=(b.port,))
        for change in (lambda: ds.set_settings({"privateMode": True}), lambda: ds.set_enabled(False),
                       lambda: ds.set_visibility({"action": "hide", "minutes": 5}),
                       lambda: ds.set_visibility({"action": "show"})):
            with self.assertRaises(BeaconError) as cm:
                change()
            self.assertEqual((cm.exception.code, cm.exception.http), ("beacon_running", 409))
        self.assertEqual(ds.set_enabled(True)["enabled"], True)        # switched to what it is: nothing changes
        with self.assertRaises(BadRequest):
            ds.set_settings({"bogus": 1})                               # a bad request is still a bad request
        self.assertEqual(ds.presence.visibility_state(), {"enabled": True, "snoozeSec": 0, "visible": True})
        self.assertFalse(ds.presence.get_config()["privateMode"])
        b.close()
        self.assertTrue(ds.set_settings({"privateMode": True})["privateMode"])


# ---------------------------------------------------------------- following the old app while it runs

class FollowTests(TempCase):
    """While the old app runs, its settings are the ones on Discord: the engine carries on with them (and with a
    timed hide it was told) whichever way it goes, not only when moving over."""

    def test_its_settings_are_followed_and_kept_when_it_is_removed(self):
        b = self.old_app(config={**BEACON_CONFIG, "privateMode": False, "hideMatching": []})
        dial = FakeDiscord()
        ds = self.discord(ports=(b.port,), dial=dial)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        ds.sync()
        self.assertEqual((ds.presence.get_config()["privateMode"], ds.presence.get_config()["hideMatching"]),
                         (False, []))
        # it is told something new (through its own popup or dashboard)
        b.config.update(privateMode=True, hideMatching=["Secret"])
        ds.sync()
        cfg = ds.presence.get_config()
        self.assertEqual((cfg["privateMode"], cfg["hideMatching"]), (True, ["secret"]))
        saved = json.loads((self.tmp / "ytm-starcast" / "discord.json").read_text())
        self.assertEqual((saved["privateMode"], saved["hideMatching"]), (True, ["secret"]))
        self.assertFalse(ds.presence.running)
        self.assertEqual(dial.conns, [])
        self.assertFalse([c for c in b.calls if c[0] == "POST"])            # it is only ever asked
        self.assertEqual({c[1] for c in b.calls}, {"/v1/ping", "/v1/config", "/v1/status"})
        # it is removed with its own uninstall, not by moving over: the engine carries on with what it was told
        b.close()
        ds.sync()
        self.assertTrue(wait_until(lambda: dial.conns))
        cfg = ds.presence.get_config()
        self.assertEqual((cfg["privateMode"], cfg["hideMatching"]), (True, ["secret"]))
        # ...and so does the next start
        again = Presence(self.tmp / "ytm-starcast" / "discord.json", dial=FakeDiscord(fail=True))
        self.assertEqual((again.get_config()["privateMode"], again.get_config()["hideMatching"]), (True, ["secret"]))

    def test_the_same_settings_are_not_saved_again(self):
        b = self.old_app()
        ds = self.discord(ports=(b.port,))
        ds.start()
        self.assertTrue(wait_until(lambda: ds.presence.get_config()["hideMatching"] == ["secret"]))
        with mock.patch.object(ds.presence, "import_beacon_config", wraps=ds.presence.import_beacon_config) as imp:
            ds.sync()
            ds.sync()
        imp.assert_not_called()

    def test_settings_it_cannot_give_change_nothing(self):
        b = self.old_app(config={"statusDisplay": "bogus"})
        ds = self.discord(ports=(b.port,))
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        ds.sync()
        self.assertEqual(ds.presence.get_config()["statusDisplay"], "name")
        self.assertFalse(ds.presence.running)

    def beacon_file(self, settings, saved_after_ours):
        conf = self.tmp / "ytm-beacon" / "config.json"
        conf.parent.mkdir(exist_ok=True)
        conf.write_text(json.dumps(settings))
        ours = (self.tmp / "ytm-starcast" / "discord.json").stat().st_mtime_ns
        when = ours + (10 if saved_after_ours else -10) * 10**9
        os.utime(conf, ns=(when, when))
        return conf

    def test_settings_it_saved_last_are_taken_over_at_the_next_start(self):
        # it was told something while YTM Starcast didn't run, and was then removed by another installer
        make_discord(self.tmp / "ytm-starcast").presence.set_config({"showPaused": False})
        conf = self.beacon_file({"privateMode": True, "hideMatching": ["Secret"]}, saved_after_ours=True)
        dial = FakeDiscord()
        ds = self.discord(dial=dial, beacon_config=str(conf))
        ds.start()
        self.assertTrue(wait_until(lambda: dial.conns))
        cfg = ds.presence.get_config()
        self.assertEqual((cfg["privateMode"], cfg["hideMatching"], cfg["showPaused"]), (True, ["secret"], True))
        # once only: settings changed here afterwards are kept
        ds.presence.set_config({"privateMode": False})
        ds.stop()
        ds.start()
        self.assertTrue(wait_until(lambda: ds.presence.running))
        self.assertFalse(ds.presence.get_config()["privateMode"])

    def test_settings_changed_here_later_are_kept(self):
        make_discord(self.tmp / "ytm-starcast").presence.set_config({"privateMode": False, "hideMatching": ["mine"]})
        conf = self.beacon_file({"privateMode": True, "hideMatching": ["theirs"]}, saved_after_ours=False)
        dial = FakeDiscord()
        ds = self.discord(dial=dial, beacon_config=str(conf))
        ds.start()
        self.assertTrue(wait_until(lambda: dial.conns))
        self.assertEqual(ds.presence.get_config()["hideMatching"], ["mine"])

    def test_a_timed_hide_it_was_told_carries_on(self):
        b = self.old_app(snooze=3600)
        dial = FakeDiscord()
        ds = self.discord(ports=(b.port,), dial=dial)
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        ds.sync()
        b.close()                                   # its hide ends with it
        ds.sync()
        self.assertTrue(wait_until(lambda: dial.conns))
        st = ds.status()
        self.assertTrue(3590 <= st["snoozeSec"] <= 3600, st["snoozeSec"])
        self.assertEqual(st["headline"], "snoozed")
        ds.feed("A", {"title": "Song", "playing": True})
        time.sleep(0.3)
        self.assertTrue(all(c.last() is None for c in dial.conns))        # nothing on Discord

    def test_a_hide_it_ended_is_not_carried_on(self):
        b = self.old_app(snooze=3600)
        ds = self.discord(ports=(b.port,))
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        ds.sync()
        b.snooze = 0                                # shown again in it
        ds.sync()
        b.close()
        ds.sync()
        self.assertTrue(wait_until(lambda: ds.presence.running))
        self.assertEqual(ds.status()["snoozeSec"], 0)


# ---------------------------------------------------------------- moving over

class MigrateTests(TempCase):
    def setUp(self):
        super().setUp()
        self.ran = []

    def runner(self, code=0, out="", then=None):
        def run(cmd, timeout):
            self.ran.append((list(cmd), timeout))
            if then:
                then()
            return code, out
        return run

    def removes(self, b, prog):
        def then():
            prog.unlink()
            b.close()
        return then

    def test_moves_over(self):
        b = self.old_app()
        prog = self.fake_program()
        dial = FakeDiscord()
        ds = self.discord(ports=(b.port,), binaries=[prog], dial=dial, runner=self.runner(then=self.removes(b, prog)))
        ds.start()
        self.assertTrue(wait_until(lambda: ds.app_info()["running"]))
        self.assertEqual(ds.status()["beaconApp"], {"running": True, "installed": True, "canMigrate": True,
                                                    "version": "v0.1.4"})
        code, r = ds.migrate()
        self.assertEqual((code, r["ok"]), (200, True), r)
        self.assertIn("extension", r["message"])                 # the one step left: the browser extension
        self.assertEqual(self.ran, [([str(prog), "uninstall"], bc.UNINSTALL_TIMEOUT)])    # no --purge
        cfg = ds.presence.get_config()
        self.assertEqual({k: cfg[k] for k in BEACON_CONFIG}, BEACON_CONFIG)            # its live settings
        self.assertIn(("GET", "/v1/config"), [c[:2] for c in b.calls])
        self.assertFalse([c for c in b.calls if c[0] == "POST"])
        self.assertTrue(wait_until(lambda: dial.conns))         # the built-in engine took over
        st = ds.status()
        self.assertEqual(st["beaconApp"], {"running": False, "installed": False, "canMigrate": False, "version": None})
        self.assertNotEqual(st["headline"], "beacon-running")
        self.assertEqual(ds.migrate()[0], 409)                  # and there is nothing left to move

    def test_a_timed_hide_carries_on(self):
        b = self.old_app(snooze=600)
        prog = self.fake_program()
        dial = FakeDiscord()
        ds = self.discord(ports=(b.port,), binaries=[prog], dial=dial, runner=self.runner(then=self.removes(b, prog)))
        code, r = ds.migrate()
        self.assertEqual(code, 200, r)
        self.assertIn(("GET", "/v1/status"), [c[:2] for c in b.calls])
        self.assertTrue(595 <= ds.presence.visibility_state()["snoozeSec"] <= 600)
        ds.start()
        self.assertTrue(wait_until(lambda: dial.conns))
        ds.feed("A", {"title": "Song", "playing": True})
        time.sleep(0.3)
        self.assertTrue(all(c.last() is None for c in dial.conns))        # nothing on Discord until it ends
        self.assertEqual(ds.status()["headline"], "snoozed")

    def test_installed_but_stopped_uses_its_file(self):
        prog = self.fake_program()
        conf = self.tmp / "ytm-beacon" / "config.json"
        conf.parent.mkdir()
        conf.write_text(json.dumps({"showPaused": False, "hideMatching": ["X"]}))
        ds = self.discord(binaries=[prog], beacon_config=str(conf), runner=self.runner(then=prog.unlink))
        code, r = ds.migrate()
        self.assertEqual(code, 200, r)
        cfg = ds.presence.get_config()
        self.assertEqual((cfg["showPaused"], cfg["hideMatching"]), (False, ["x"]))
        self.assertEqual(len(self.ran), 1)

    def test_nothing_to_move(self):
        conf = self.tmp / "config.json"
        conf.write_text("{}")                                   # its settings alone are not something to move
        ds = self.discord(ports=(free_port(),), beacon_config=str(conf))
        self.assertEqual(ds.migrate(), (409, {"error": "nothing_to_move", "message": mock.ANY}))

    def test_running_but_its_program_is_elsewhere(self):
        b = self.old_app()
        ds = self.discord(ports=(b.port,), binaries=[self.tmp / "not-there"])
        code, r = ds.migrate()
        self.assertEqual((code, r["error"]), (409, "beacon_not_found"))
        self.assertIn("ytm-beacon uninstall", r["message"])
        self.assertFalse(b.closed)

    def test_its_uninstall_fails(self):
        b = self.old_app()
        prog = self.fake_program()
        home = str(Path.home())
        ds = self.discord(ports=(b.port,), binaries=[prog],
                          runner=self.runner(code=1, out=f"working\nerror: can't remove {home}/.local/bin/ytm-beacon\n"))
        ds.start()
        code, r = ds.migrate()
        self.assertEqual((code, r["error"]), (502, "uninstall_failed"))
        self.assertIn("can't remove ~/.local/bin/ytm-beacon", r["message"])
        self.assertNotIn(home + "/", r["message"])
        self.assertFalse(ds.presence.running)                   # still the old app's turn

    def test_still_running_after_its_uninstall(self):
        b = self.old_app()
        prog = self.fake_program()
        ds = self.discord(ports=(b.port,), binaries=[prog], runner=self.runner(), gone_wait=0.3)
        ds.start()
        code, r = ds.migrate()
        self.assertEqual((code, r["error"]), (502, "still_running"))
        self.assertFalse(ds.presence.running)

    def test_settings_that_cannot_be_taken_over_stop_it(self):
        b = self.old_app(config={"statusDisplay": "bogus"})
        prog = self.fake_program()
        ds = self.discord(ports=(b.port,), binaries=[prog], beacon_config=str(self.tmp / "missing.json"))
        ds.presence.set_config({"privateMode": True})
        code, r = ds.migrate()
        self.assertEqual((code, r["error"]), (502, "import_failed"))
        self.assertEqual(self.ran, [])                          # nothing removed
        self.assertTrue(ds.presence.get_config()["privateMode"])    # nothing changed

    def test_a_trial_run_does_nothing(self):
        b = self.old_app()
        prog = self.fake_program()
        ds = self.discord(ports=(b.port,), binaries=[prog], dry_run=True)
        code, r = ds.migrate()
        self.assertEqual((code, r["ok"], r["dryRun"]), (200, True, True))
        self.assertIn("nothing", r["message"])
        self.assertEqual(b.calls, [])
        self.assertTrue(prog.exists())

    def test_one_at_a_time(self):
        prog = self.fake_program()
        ds = self.discord(binaries=[prog])
        with ds._moving:
            self.assertEqual(ds.migrate(), (409, {"error": "busy", "message": mock.ANY}))


# ---------------------------------------------------------------- the routes

class RouteTests(TempCase):
    """The apps' routes, through the real request handler."""
    dry_run = False

    def setUp(self):
        super().setUp()
        env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.tmp), "APPDATA": str(self.tmp)})
        env.start()
        self.addCleanup(env.stop)
        self.old = self.old_app()
        self.prog = self.fake_program()
        self.ran = []

        def run(cmd, timeout):
            self.ran.append(cmd)
            self.prog.unlink()
            self.old.close()
            return 0, "done"
        self.ds = self.discord(ports=(self.old.port,), binaries=[self.prog], runner=run, dry_run=self.dry_run)
        self.app = App(FakeAudio(), NullEngine(), FakeMedia(True), prefs=JsonFile(self.tmp / "p.json"),
                       beacon=self.ds, opener=Opener(dry_run=True), updater=Updater(cache=self.tmp / "updates"),
                       audio_fetcher=AudioFetcher(folder=self.tmp / "audio", ytdlp=""))
        # (the guard refuses YTM Beacon's ports, so this server must not be handed one either)
        self.httpd = bind_away_from_beacon(lambda: ThreadingHTTPServer(("127.0.0.1", 0), Handler),
                                           lambda h: h.server_address[1], lambda h: h.server_close())
        self.httpd.app = self.app
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

    def req(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = dict(headers or {})
        if body is not None:
            h.setdefault("Content-Type", "application/json")
        c.request(method, path, json.dumps(body) if body is not None else None, h)
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)

    def test_status_route_and_capabilities(self):
        code, st = self.req("GET", "/api/beacon")
        self.assertEqual((code, st["reachable"], st["headline"]), (200, True, "beacon-running"))
        self.assertEqual(st["beaconApp"]["canMigrate"], True)
        self.assertFalse(st["install"]["canInstall"])
        caps = self.req("GET", "/api/state")[1]["capabilities"]
        self.assertTrue(caps["beacon"] and caps["discord"])
        self.assertEqual(self.req("GET", "/ext/status")[1]["discord"]["headline"], "beacon-running")

    def test_visibility_and_the_discord_part_are_one_switch(self):
        self.old.close()                      # (while it runs, nothing here may change: see the test below)
        code, r = self.req("POST", "/api/beacon/visibility", {"action": "hide", "minutes": 30})
        self.assertEqual((code, r["visible"], r["enabled"]), (200, False, True))
        self.assertTrue(1795 <= r["snoozeSec"] <= 1800)
        self.assertEqual(self.req("POST", "/api/beacon/visibility", {"action": "hide"})[1]["enabled"], False)
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"]["discord"], False)
        self.assertEqual(self.req("POST", "/api/beacon/visibility", {"action": "show"})[1],
                         {"enabled": True, "snoozeSec": 0, "visible": True})
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"]["discord"], True)
        self.req("POST", "/api/parts", {"discord": False})
        self.assertFalse(self.ds.enabled)
        self.assertFalse(self.req("GET", "/api/beacon")[1]["visible"])
        self.assertFalse(json.loads((self.tmp / "ytm-starcast" / "discord.json").read_text())["enabled"])
        prefs = self.tmp / "p.json"           # the switch lives in discord.json only, never in the server prefs
        self.assertNotIn("discord", json.loads(prefs.read_text()).get("parts", {}) if prefs.exists() else {})

    def test_showing_again_reaches_waiting_tabs_at_once(self):
        self.old.close()
        self.ds.set_enabled(False)
        self.app.bridge.push_state({"instance": "A", "now": {}})     # the tab has reported: its polls wait
        got = {}

        def poll():
            got["r"] = self.req("GET", "/ext/poll?i=A")[1]
            got["at"] = time.time()
        t = threading.Thread(target=poll)
        t.start()
        time.sleep(0.2)
        start = time.time()
        self.assertEqual(self.req("POST", "/api/beacon/visibility", {"action": "show"})[0], 200)
        t.join(5)
        self.assertLess(got["at"] - start, 2.0)
        self.assertEqual(got["r"]["cfg"]["discord"], True)

    def test_settings_route(self):
        self.old.close()
        code, r = self.req("POST", "/api/beacon/settings", {"hideMatching": ["Foo"], "pauseTimeoutMinutes": 0})
        self.assertEqual((code, r["hideMatching"], r["pauseTimeoutMinutes"], r["clientId"]), (200, ["foo"], 0, ""))
        self.assertEqual(self.req("GET", "/api/beacon")[1]["settings"]["hideMatching"], ["foo"])

    def test_bad_bodies_change_nothing(self):
        before = self.ds.presence.get_config()
        for path, body in (("/api/beacon/visibility", {"action": "toggle"}),
                           ("/api/beacon/visibility", {"action": "hide", "minutes": 0}),
                           ("/api/beacon/visibility", {"action": "hide", "minutes": 99999}),
                           ("/api/beacon/visibility", ["show"]),
                           ("/api/beacon/settings", {"clientId": "123456789012345678"}),
                           ("/api/beacon/settings", {"enabled": False}),
                           ("/api/beacon/settings", {"hideMatching": "x"}),
                           ("/api/beacon/settings", {"statusDisplay": "bogus"})):
            code, r = self.req("POST", path, body)
            self.assertEqual(code, 400, (path, body))
            self.assertIn("error", r)
        self.assertEqual(self.ds.presence.get_config(), before)

    def test_nothing_changes_while_the_old_app_runs(self):
        before = self.ds.presence.get_config()
        for path, body in (("/api/beacon/visibility", {"action": "hide", "minutes": 60}),
                           ("/api/beacon/visibility", {"action": "hide"}),
                           ("/api/beacon/visibility", {"action": "show"}),
                           ("/api/beacon/settings", {"hideMatching": ["mysong"]}),
                           ("/api/beacon/settings", {"privateMode": True}),
                           ("/api/parts", {"discord": False}),
                           ("/ext/parts", {"discord": False})):
            code, r = self.req("POST", path, body)
            self.assertEqual((code, r["error"]), (409, "beacon_running"), (path, body))
            self.assertIn("YTM Beacon", r["message"])
        self.assertEqual(self.ds.presence.get_config(), before)
        self.assertEqual(self.ds.presence.visibility_state(), {"enabled": True, "snoozeSec": 0, "visible": True})
        # what changes nothing on Discord still works: the other parts, and the switch set to what it is
        self.assertEqual(self.req("POST", "/api/parts", {"discord": True, "refine": False})[1]["parts"]["refine"], False)
        self.assertEqual(self.req("GET", "/api/beacon")[1]["headline"], "beacon-running")
        # once it has gone, changes are made again
        self.old.close()
        self.assertEqual(self.req("POST", "/api/beacon/settings", {"privateMode": True})[0], 200)

    def test_install_is_gone(self):
        for body in ({}, {"action": "install"}, {"action": "update"}, {"action": "start"}):
            code, r = self.req("POST", "/api/beacon/install", body)
            self.assertEqual((code, r["error"]), (409, "built_in"))
            self.assertIn("built into YTM Starcast", r["message"])
        self.assertEqual(self.ran, [])

    def test_nothing_else_is_reachable(self):
        for path in ("/api/beacon/update", "/api/beacon/clear", "/api/beacon/quit", "/api/beacon/logs",
                     "/api/beacon/config", "/api/beacon/test", "/api/beacon/v1/status"):
            self.assertEqual(self.req("POST", path, {})[0], 404, path)
            self.assertEqual(self.req("GET", path)[0], 404, path)
        self.assertFalse([c for c in self.old.calls if c[1] != "/v1/ping"])

    def test_migrate_route(self):
        code, r = self.req("POST", "/api/beacon/migrate", {})
        self.assertEqual((code, r["ok"]), (200, True), r)
        self.assertEqual(len(self.ran), 1)
        self.assertEqual(self.req("GET", "/api/beacon")[1]["beaconApp"]["running"], False)
        code, r = self.req("POST", "/api/beacon/migrate")
        self.assertEqual((code, r["error"]), (409, "nothing_to_move"))
        # moving over needs the same checks as everything else
        self.assertEqual(self.req("POST", "/api/beacon/migrate", {}, {"Origin": "https://evil.example"})[0], 403)

    def test_the_extension_feeds_it(self):
        ua = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"
        body = {"instance": "tab-1", "now": {"title": "Song", "artist": "Band - Topic", "playing": True,
                                            "position": 5, "duration": 100}, "ts": int(time.time() * 1000)}
        self.assertEqual(self.req("POST", "/ext/state", body, {"User-Agent": ua})[0], 200)
        st = self.req("GET", "/api/beacon")[1]
        self.assertEqual((st["nowPlaying"]["title"], st["nowPlaying"]["artist"]), ("Song", "Band"))
        self.assertEqual(self.ds.presence.status()["extension"]["sources"][0]["agent"], "Firefox")
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "tab-1"})[0], 200)
        self.assertIsNone(self.req("GET", "/api/beacon")[1]["nowPlaying"])

    def test_polls_count_as_the_extension_being_there(self):
        self.app.bridge.poll = lambda timeout=None, instance=None: None
        self.assertIsNone(self.req("GET", "/api/beacon")[1]["extension"]["lastSeenAgoSec"])
        self.assertEqual(self.req("GET", "/ext/poll?i=tab-1")[0], 200)
        self.assertEqual(self.req("GET", "/api/beacon")[1]["extension"]["lastSeenAgoSec"], 0)


class DryRunRouteTests(RouteTests):
    dry_run = True

    def test_migrate_route(self):
        code, r = self.req("POST", "/api/beacon/migrate", {})
        self.assertEqual((code, r["ok"], r["dryRun"]), (200, True, True))
        self.assertEqual(self.ran, [])
        self.assertFalse(self.old.closed)

    def test_visibility_and_the_discord_part_are_one_switch(self):
        self.old.close()
        self.assertEqual(self.req("POST", "/api/beacon/visibility", {"action": "hide"})[1]["enabled"], False)
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"]["discord"], False)
        self.assertFalse((self.tmp / "ytm-starcast" / "discord.json").exists())     # a trial run writes nothing


if __name__ == "__main__":
    unittest.main()
