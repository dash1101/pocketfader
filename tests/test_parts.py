"""The on/off switches ("parts") and the extension routes that go with them: /api/parts, /ext/parts, /ext/status,
/ext/bye, the switches in /ext/poll and /ext/state replies, and the hook the Discord status engine plugs into.
Fakes only: nothing here plays, pauses or changes anything on the computer, and nothing talks to Discord."""
import http.client
import json
import os
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

from starcast import __version__
from starcast.audio import AudioFetcher
from starcast.backends.base import NullEngine
from starcast.config import JsonFile
from starcast.opener import Opener
from starcast.server import App, Handler, validate_parts
from starcast.updater import Updater

from .fakes import FakeAudio, FakeMedia
from .test_beacon import BeaconGuard, bind_away_from_beacon, make_discord
from .test_discord_fakes import DialGuard

ALL_ON = {"remote": True, "discord": True, "refine": True, "audio": True}
YTM = "https://music.youtube.com"

# Nothing here may reach the real YTM Beacon app or the real Discord: the guards fail the run if anything does.
GUARDS = (BeaconGuard(), DialGuard())


def setUpModule():
    for g in GUARDS:
        g.install()


def tearDownModule():
    for g in GUARDS:
        g.uninstall()
    for g in GUARDS:
        g.check()


class FakeBeacon:
    """Stands in for whatever serves /api/beacon. status() is what the phone sees."""

    def __init__(self, status=None, fail=False):
        self._status = status or {"reachable": True, "headline": "showing", "visible": True,
                                  "discord": {"state": "connected", "user": "someone"}}
        self.fail = fail

    def status(self, fresh=False):
        if self.fail:
            raise RuntimeError("broken")
        return self._status


class FakeLyrics:
    def __init__(self):
        self.prefetched = []

    def lookup(self, title, artist, album, dur, video_id="", source=None, refresh=False):
        return {"found": True, "title": title}

    def prefetch(self, *args):
        self.prefetched.append(args)


class FakePresence:
    """Records what the Discord status engine would be handed."""

    def __init__(self, enabled=True, fail=False):
        self.enabled = enabled
        self.fail = fail
        self.fed, self.byes, self.switched, self.touched, self.extra = [], [], [], [], []

    def feed(self, instance, now, agent=None, ts=None):
        if self.fail:
            raise RuntimeError("engine bug")
        self.fed.append((instance, now))
        self.extra.append((agent, ts))

    def touch(self, instance):
        if self.fail:
            raise RuntimeError("engine bug")
        self.touched.append(instance)

    def bye(self, instance):
        if self.fail:
            raise RuntimeError("engine bug")
        self.byes.append(instance)

    def set_enabled(self, on):
        self.switched.append(on)
        self.enabled = on


class Base(unittest.TestCase):
    token = None

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": self.tmp.name, "APPDATA": self.tmp.name})
        env.start()
        self.addCleanup(env.stop)
        self.prefs_path = Path(self.tmp.name) / "prefs.json"
        self.audio, self.media = FakeAudio(), FakeMedia()
        self.beacon, self.lyrics = FakeBeacon(), FakeLyrics()
        self.app = self.make_app()
        # (the guard refuses YTM Beacon's ports, so this server must not be handed one either)
        self.httpd = bind_away_from_beacon(lambda: ThreadingHTTPServer(("127.0.0.1", 0), Handler),
                                           lambda h: h.server_address[1], lambda h: h.server_close())
        self.httpd.app = self.app
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]
        self.addCleanup(self.httpd.server_close)
        self.addCleanup(self.httpd.shutdown)

    def make_app(self, presence=None):
        return App(self.audio, NullEngine(), self.media, prefs=JsonFile(self.prefs_path), token=self.token,
                   lyrics=self.lyrics, beacon=self.beacon, opener=Opener(dry_run=True), presence=presence,
                   updater=Updater(cache=Path(self.tmp.name) / "updates"),
                   audio_fetcher=AudioFetcher(folder=Path(self.tmp.name) / "audio", ytdlp=""))

    def short_polls(self, seconds=0.1):
        """The real long-poll waits 25 s; here an idle poll gives up quickly."""
        orig = self.app.bridge.poll
        self.app.bridge.poll = lambda timeout=None, instance=None: orig(timeout=seconds, instance=instance)

    def req(self, method, path, body=None, headers=None, raw=None, timeout=5):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=timeout)
        h = dict(headers or {})
        data = raw
        if body is not None:
            data = json.dumps(body)
            h.setdefault("Content-Type", "application/json")
        c.request(method, path, data, h)
        r = c.getresponse()
        out = r.read()
        c.close()
        try:
            return r.status, json.loads(out), r
        except ValueError:
            return r.status, out, r

    def stored(self):
        return json.loads(self.prefs_path.read_text()).get("parts") if self.prefs_path.exists() else None


class PartsApiTests(Base):
    def test_all_on_by_default(self):
        self.assertEqual(self.req("GET", "/api/parts")[1], {"parts": ALL_ON})
        st = self.req("GET", "/api/state")[1]
        self.assertEqual(st["parts"], ALL_ON)
        self.assertTrue(st["capabilities"]["discord"])
        self.assertTrue(st["capabilities"]["beacon"])        # older apps look for this one
        self.assertIsNone(self.stored())                      # nothing written just by reading

    def test_switching_some_parts_keeps_the_others_and_is_saved(self):
        code, r, _ = self.req("POST", "/api/parts", {"remote": False, "audio": False})
        self.assertEqual((code, r), (200, {"parts": {**ALL_ON, "remote": False, "audio": False}}))
        code, r, _ = self.req("POST", "/api/parts", {"audio": True})
        self.assertEqual(r["parts"], {**ALL_ON, "remote": False})
        self.assertEqual(self.stored(), {**ALL_ON, "remote": False})
        self.assertEqual(self.req("GET", "/api/state")[1]["parts"]["remote"], False)
        # survives a restart (a new server reading the same file)
        self.assertEqual(self.make_app().parts(), {**ALL_ON, "remote": False})

    def test_an_empty_change_changes_nothing(self):
        self.assertEqual(self.req("POST", "/api/parts", {}), (200, {"parts": ALL_ON}, mock.ANY))

    def test_bad_bodies_are_refused_and_change_nothing(self):
        for bad in ([1], {"remote": "off"}, {"remote": 0}, {"remote": None}, {"remote": 1}, {"volume": True},
                    {"Remote": False}, {"remote": False, "nope": False}, {"parts": {"remote": False}}):
            code, r, _ = self.req("POST", "/api/parts", bad)
            self.assertEqual(code, 400, bad)
            self.assertIn("error", r, bad)
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        c.request("POST", "/api/parts", "{nope", {"Content-Type": "application/json"})
        r = c.getresponse(); r.read(); c.close()
        self.assertEqual(r.status, 400)
        self.assertEqual(self.req("GET", "/api/parts")[1], {"parts": ALL_ON})
        self.assertIsNone(self.stored())

    def test_damaged_saved_switches_read_as_on(self):
        self.app.prefs.merge({"parts": {"remote": "x", "audio": False, "zzz": 1}})
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], {**ALL_ON, "audio": False})
        self.app.prefs.merge({"parts": "garbage"})
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], ALL_ON)
        self.prefs_path.write_text("{broken")
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], ALL_ON)

    def test_validate_parts(self):
        self.assertEqual(validate_parts({"refine": False}), {"refine": False})
        with self.assertRaises(ValueError):
            validate_parts({"audio": False}, ("remote", "discord", "refine"))
        with self.assertRaises(ValueError):
            validate_parts("remote")

    def test_prefs_route_shows_the_parts_and_checks_them(self):
        self.assertEqual(self.req("GET", "/api/prefs")[1], {"parts": ALL_ON})
        code, r, _ = self.req("POST", "/api/prefs", {"pauseOthers": True, "parts": {"refine": False}})
        self.assertEqual(code, 200)
        self.assertEqual((r["pauseOthers"], r["parts"]), (True, {**ALL_ON, "refine": False}))
        # only the switches sent change (refine stays off)
        r = self.req("POST", "/api/prefs", {"parts": {"remote": False}})[1]
        self.assertEqual(r["parts"], {**ALL_ON, "refine": False, "remote": False})
        # a bad "parts" refuses the whole request
        code, r, _ = self.req("POST", "/api/prefs", {"pauseOthers": False, "parts": {"refine": "yes"}})
        self.assertEqual(code, 400)
        self.assertTrue(self.req("GET", "/api/prefs")[1]["pauseOthers"])
        self.assertEqual(self.req("POST", "/api/prefs", {"parts": ["remote"]})[0], 400)
        self.assertEqual(self.stored(), {**ALL_ON, "refine": False, "remote": False})

    def test_cross_origin_changes_are_refused(self):
        for origin in ("https://evil.example", "chrome-extension://abcdefghijklmnop", "moz-extension://1234"):
            for path in ("/api/parts", "/ext/parts"):
                code, _, _ = self.req("POST", path, {"remote": False}, {"Origin": origin})
                self.assertEqual(code, 403, (origin, path))
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], ALL_ON)
        same = f"http://127.0.0.1:{self.port}"
        self.assertEqual(self.req("POST", "/api/parts", {"remote": False}, {"Origin": same})[0], 200)

    def test_rebinding_host_is_refused(self):
        for path in ("/api/parts", "/ext/status"):
            self.assertEqual(self.req("GET", path, headers={"Host": "evil.example.com"})[0], 421, path)


class RemoteOffTests(Base):
    def setUp(self):
        super().setUp()
        self.assertEqual(self.req("POST", "/api/parts", {"remote": False})[0], 200)

    def test_remote_commands_are_refused(self):
        for body in ({"type": "transport", "action": "playpause"}, {"type": "play", "videoId": "dQw4w9WgXcQ"},
                     {"type": "open"}, {"type": "search", "query": "x"}, {"type": 5}, {}):
            code, r, _ = self.req("POST", "/api/ytm", body)
            self.assertEqual((code, r), (409, {"error": "part_off"}), body)
        self.assertEqual((self.media.calls, self.app.opener.opened), ([], []))   # no fallback either

    def test_nothing_is_sent_to_a_connected_tab(self):
        self.app.bridge.push_state({"instance": "A", "now": {"title": "t", "playing": True}})
        code, r, _ = self.req("POST", "/api/ytm", {"type": "transport", "action": "next"})
        self.assertEqual((code, r), (409, {"error": "part_off"}))
        self.assertEqual(self.app.bridge._pending, [])

    def test_the_extensions_reports_and_lyrics_keep_working(self):
        code, r, _ = self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "Song", "playing": True}},
                              {"Origin": YTM})
        self.assertEqual((code, r), (200, {"ok": True, "cfg": {"remote": False, "discord": True, "refine": True}}))
        st = self.req("GET", "/api/ytm/state")[1]
        self.assertEqual((st["source"], st["now"]["title"]), ("ytm", "Song"))
        code, r, _ = self.req("GET", "/ext/lyrics?title=Song&artist=A")
        self.assertEqual((code, r), (200, {"found": True, "title": "Song"}))
        # the page's own lyrics are still asked for through the extension
        got = {}
        t = threading.Thread(target=lambda: got.update(r=self.app._ytm_lyrics("dQw4w9WgXcQ")))
        t.start()
        cmd = self.app.bridge.poll(timeout=2, instance="A")
        self.assertEqual(cmd["type"], "lyrics")
        self.app.bridge.deliver_result(cmd["id"], {"lines": []})
        t.join(3)
        self.assertEqual(got["r"], {"lines": []})

    def test_switching_it_back_on(self):
        self.assertEqual(self.req("POST", "/api/parts", {"remote": True})[1]["parts"], ALL_ON)
        code, r, _ = self.req("POST", "/api/ytm", {"type": "transport", "action": "playpause"})
        self.assertEqual((code, r), (200, {"ok": True}))
        self.assertEqual(self.media.calls, ["playpause"])      # the fake player

    def test_sleep_timer_pauses_through_the_computers_player_instead(self):
        self.app.bridge.push_state({"instance": "A", "now": {"title": "t", "playing": True}})
        self.app._sleep_fire()
        self.assertEqual(self.app.bridge._pending, [])
        self.assertEqual(self.media.calls, ["playpause"])      # the fake player; nothing real is paused


class ExtensionWireTests(Base):
    def test_idle_poll_carries_the_switches_and_no_id(self):
        self.short_polls()
        self.app.bridge.push_state({"instance": "tab-1", "now": {}})     # the tab has reported since the start
        for path in ("/ext/poll?i=tab-1", "/ext/poll"):     # with and without a tab name (extensions before 1.14)
            code, r, res = self.req("GET", path, headers={"Origin": YTM})
            self.assertEqual((code, r), (200, {"cfg": {"remote": True, "discord": True, "refine": True}}), path)
            self.assertNotIn("id", r)          # older extensions only act on a reply with an id: they ignore this
            self.assertEqual(res.getheader("Access-Control-Allow-Origin"), YTM)

    def test_a_tab_open_across_a_restart_is_asked_to_send_everything_again(self):
        # a paused tab reports nothing until something changes: its first poll after a start is answered at once
        t0 = time.time()
        code, r, _ = self.req("GET", "/ext/poll?i=A", headers={"Origin": YTM})
        self.assertLess(time.time() - t0, 2.0)                  # not 25 s later
        self.assertEqual((code, r), (200, {"cfg": {"remote": True, "discord": True, "refine": True}, "resend": True}))
        self.assertTrue(self.app.bridge.connected)
        self.short_polls()
        self.assertNotIn("resend", self.req("GET", "/ext/poll?i=A")[1])      # once
        self.assertNotIn("resend", self.req("GET", "/ext/poll")[1])          # extensions before 1.14: never

    def test_a_command_carries_the_switches_too(self):
        self.app.bridge.push_state({"instance": "A", "now": {"playing": True}})
        got = {}
        t = threading.Thread(target=lambda: got.update(r=self.req("POST", "/api/ytm", {"type": "transport", "action": "next"})))
        t.start()
        code, cmd, _ = self.req("GET", "/ext/poll?i=A")
        self.assertEqual(code, 200)
        self.assertEqual((cmd["type"], cmd["action"]), ("transport", "next"))
        self.assertTrue(cmd["id"])
        self.assertEqual(cmd["cfg"], {"remote": True, "discord": True, "refine": True})
        self.assertEqual(self.req("POST", "/ext/result", {"id": cmd["id"], "data": {"ok": True}}, {"Origin": YTM})[0], 200)
        t.join(5)
        self.assertEqual(got["r"][:2], (200, {"ok": True}))

    def test_a_switch_change_reaches_a_waiting_poll_at_once(self):
        self.app.bridge.push_state({"instance": "A", "now": {}})     # the tab has reported: its polls wait
        got = {}
        def poll():
            got["r"] = self.req("GET", "/ext/poll?i=A", timeout=30)[1]
            got["at"] = time.time()
        t = threading.Thread(target=poll)
        t.start()
        time.sleep(0.2)
        start = time.time()
        self.assertEqual(self.req("POST", "/ext/parts", {"refine": False}, {"Origin": YTM})[0], 200)
        t.join(5)
        self.assertFalse(t.is_alive())
        self.assertLess(got["at"] - start, 2.0)                 # not 25 s later
        self.assertEqual(got["r"], {"cfg": {"remote": True, "discord": True, "refine": False}})

    def test_audio_changes_do_not_wake_the_tabs(self):
        before = self.app.bridge._wake
        self.req("POST", "/api/parts", {"audio": False})
        self.req("POST", "/api/parts", {"remote": True})        # no change: no wake
        self.assertEqual(self.app.bridge._wake, before)
        self.req("POST", "/api/parts", {"remote": False})
        self.assertEqual(self.app.bridge._wake, before + 1)

    def test_state_reply_carries_the_switches(self):
        body = {"instance": "A", "now": {"title": "t", "playing": True}, "queue": [], "ts": 1}
        self.assertEqual(self.req("POST", "/ext/state", body, {"Origin": YTM})[1],
                         {"ok": True, "cfg": {"remote": True, "discord": True, "refine": True}})
        self.req("POST", "/api/parts", {"discord": False, "audio": False})
        self.assertEqual(self.req("POST", "/ext/state", body, {"Origin": YTM})[1]["cfg"],
                         {"remote": True, "discord": False, "refine": True})    # never "audio"

    def test_odd_tab_ids_in_state_are_tolerated(self):
        for inst in ({"x": 1}, ["a"], 5, "", None):
            code, r, _ = self.req("POST", "/ext/state", {"instance": inst, "now": {"title": "t"}})
            self.assertEqual((code, r["ok"]), (200, True), inst)
        code, _, _ = self.req("POST", "/ext/state", {"instance": "x" * 200, "now": {"title": "long"}})
        self.assertEqual(code, 200)

    def test_ext_parts(self):
        code, r, res = self.req("POST", "/ext/parts", {"discord": False, "refine": False}, {"Origin": YTM})
        self.assertEqual((code, r), (200, {"parts": {**ALL_ON, "discord": False, "refine": False}}))
        self.assertEqual(res.getheader("Access-Control-Allow-Origin"), YTM)
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], {**ALL_ON, "discord": False, "refine": False})
        # the extension may not change the computer's audio controls
        for bad in ({"audio": False}, {"remote": False, "audio": False}, {"remote": "no"}, {"nope": True}, [], "x"):
            code, r, _ = self.req("POST", "/ext/parts", bad, {"Origin": YTM})
            self.assertEqual(code, 400, bad)
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], {**ALL_ON, "discord": False, "refine": False})
        self.assertEqual(self.req("POST", "/ext/parts", {"remote": False})[0], 200)     # no Origin: a local program

    def test_status(self):
        code, r, _ = self.req("GET", "/ext/status", headers={"Origin": "chrome-extension://abcdefghijklmnop"})
        self.assertEqual(code, 200)
        self.assertEqual(r, {"app": "YTM Starcast", "version": __version__, "parts": ALL_ON,
                             "discord": {"headline": "showing", "visible": True, "state": "connected"}})
        self.req("POST", "/api/parts", {"discord": False, "remote": False})
        r = self.req("GET", "/ext/status")[1]
        self.assertEqual(r["parts"], {**ALL_ON, "discord": False, "remote": False})
        self.assertEqual(r["discord"], {"headline": "off", "visible": False, "state": "connected"})

    def test_status_when_discord_status_cannot_be_read(self):
        self.beacon.fail = True
        r = self.req("GET", "/ext/status")[1]
        self.assertEqual(r["discord"], {"headline": "unreachable", "visible": False, "state": "disconnected"})
        # the built-in Discord status, not running: nothing talks to Discord
        self.app.beacon = make_discord(Path(self.tmp.name) / "ytm-starcast", dry_run=True)
        r = self.req("GET", "/ext/status")[1]
        self.assertEqual(r["discord"], {"headline": "discord-down", "visible": True, "state": "disconnected"})

    def test_status_while_the_old_ytm_beacon_app_runs(self):
        self.beacon._status = {"reachable": True, "headline": "beacon-running", "visible": True,
                               "discord": {"state": "disconnected"}}
        self.req("POST", "/api/parts", {"discord": False})
        # switched off here, but the old app still shows the status: that is what the popup says
        self.assertEqual(self.req("GET", "/ext/status")[1]["discord"],
                         {"headline": "beacon-running", "visible": True, "state": "disconnected"})

    def test_bye(self):
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "Song", "playing": True}})
        self.assertTrue(self.app.bridge.connected)
        # sent on pagehide as a simple request: text/plain, no preflight
        code, r, res = self.req("POST", "/ext/bye", raw=json.dumps({"instance": "A"}),
                                headers={"Content-Type": "text/plain;charset=UTF-8", "Origin": YTM})
        self.assertEqual((code, r), (200, {"ok": True}))
        self.assertEqual(res.getheader("Access-Control-Allow-Origin"), YTM)
        self.assertFalse(self.app.bridge.connected)
        st = self.req("GET", "/api/ytm/state")[1]
        self.assertEqual((st["source"], st["now"]["title"]), ("media", "T"))    # back to the computer's player

    def test_bye_from_another_tab_keeps_the_followed_one(self):
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a", "playing": True}})
        self.req("POST", "/ext/state", {"instance": "B", "now": {"title": "b", "playing": False}})
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "B"})[0], 200)
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "never-seen"})[0], 200)
        st = self.req("GET", "/api/ytm/state")[1]
        self.assertEqual((st["source"], st["now"]["title"]), ("ytm", "a"))

    def test_bad_byes(self):
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a", "playing": True}})
        for bad in ({}, {"instance": 5}, {"instance": ""}, {"instance": None}, {"instance": ["A"]}, ["A"]):
            code, _, _ = self.req("POST", "/ext/bye", bad, {"Origin": YTM})
            self.assertEqual(code, 400, bad)
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "A"}, {"Origin": "https://evil.example"})[0], 403)
        self.assertTrue(self.app.bridge.connected)      # none of those dropped the tab
        self.assertEqual(self.app.bridge.followed, "A")


class ExtensionTokenTests(Base):
    """With an access token (--lan / --bind) the extension's routes need none from this computer, but they do
    through a tunnel or from anywhere else; the app routes always need it."""
    token = "s3cret-token-value-123"

    def test_local_extension_needs_no_token(self):
        self.short_polls()
        self.assertEqual(self.req("GET", "/ext/status")[0], 200)
        self.assertEqual(self.req("GET", "/ext/poll?i=A")[0], 200)
        self.assertEqual(self.req("POST", "/ext/parts", {"refine": False}, {"Origin": YTM})[0], 200)
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "A"}, {"Origin": YTM})[0], 200)

    def test_tunnelled_requests_need_the_token(self):
        for h in ({"X-Forwarded-For": "203.0.113.9"}, {"CF-Connecting-IP": "203.0.113.9"}):
            self.assertEqual(self.req("GET", "/ext/status", headers=h)[0], 401, h)
            self.assertEqual(self.req("POST", "/ext/parts", {"remote": False}, h)[0], 401, h)
            self.assertEqual(self.req("POST", "/ext/bye", {"instance": "A"}, h)[0], 401, h)
        self.assertEqual(self.app.parts(), ALL_ON)

    def test_app_routes_need_the_token(self):
        self.assertEqual(self.req("GET", "/api/parts")[0], 401)
        self.assertEqual(self.req("POST", "/api/parts", {"remote": False})[0], 401)
        self.assertEqual(self.app.parts(), ALL_ON)
        auth = {"Authorization": "Bearer " + self.token}
        self.assertEqual(self.req("POST", "/api/parts", {"remote": False}, auth)[1]["parts"]["remote"], False)


class PresenceHookTests(Base):
    """What the Discord status engine is handed, once it is plugged into App.presence."""

    def setUp(self):
        super().setUp()
        self.presence = FakePresence()
        self.app.presence = self.presence

    def test_every_tab_is_reported_with_its_id(self):
        a = {"title": "a", "playing": True, "album": "Al", "ad": False}
        self.req("POST", "/ext/state", {"instance": "A", "now": a, "queue": []})
        self.req("POST", "/ext/state", {"instance": "B", "now": {"title": "b", "playing": False}})   # not followed
        self.req("POST", "/ext/state", {"instance": "A", "now": {**a, "position": 3}, "partial": True})
        self.req("POST", "/ext/state", {"now": {"title": "old extension"}})                       # no tab id
        self.req("POST", "/ext/state", {"instance": 7, "now": {"title": "odd id"}})
        self.req("POST", "/ext/state", {"instance": "A", "queue": []})                             # no now: nothing
        self.req("POST", "/ext/state", {"instance": "A", "now": "x"})
        self.assertEqual([i for i, _ in self.presence.fed], ["A", "B", "A", None, None])
        self.assertEqual(self.presence.fed[0][1], a)
        self.assertEqual(self.presence.fed[2][1]["position"], 3)
        self.assertEqual(self.app.bridge.followed, "A")

    def test_the_engine_gets_a_copy(self):
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a", "playing": True}})
        self.presence.fed[0][1]["title"] = "changed by the engine"
        self.assertEqual(self.app.bridge.get_state()["now"]["title"], "a")

    def test_goodbye_reaches_the_engine(self):
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a", "playing": True}})
        self.req("POST", "/ext/bye", {"instance": "A"})
        self.req("POST", "/ext/bye", {"instance": "B"})
        self.req("POST", "/ext/bye", {"instance": 5})            # refused: not passed on
        self.assertEqual(self.presence.byes, ["A", "B"])

    def test_a_failing_engine_never_breaks_the_extension(self):
        self.presence.fail = True
        code, r, _ = self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a", "playing": True}})
        self.assertEqual((code, r["ok"]), (200, True))
        self.assertEqual(self.app.bridge.get_state()["now"]["title"], "a")
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "A"}), (200, {"ok": True}, mock.ANY))
        self.assertFalse(self.app.bridge.connected)

    def test_discord_switch_is_the_engines_own(self):
        r = self.req("POST", "/api/parts", {"discord": False})[1]
        self.assertEqual(r["parts"]["discord"], False)
        self.assertEqual(self.presence.switched, [False])
        self.assertIsNone(self.stored())                         # saved by the engine, not in the server prefs
        # switched back on elsewhere (e.g. "show" in the Discord page): every view follows
        self.presence.enabled = True
        self.assertEqual(self.req("GET", "/api/parts")[1]["parts"], ALL_ON)
        self.assertEqual(self.req("POST", "/ext/state", {"instance": "A", "now": {}})[1]["cfg"]["discord"], True)
        self.req("POST", "/ext/parts", {"discord": False, "refine": False})
        self.assertEqual(self.presence.switched, [False, False])
        self.assertEqual(self.stored(), {**ALL_ON, "refine": False})

    def test_the_browser_and_sample_time_are_passed_on(self):
        ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a"}, "ts": 1234}, {"User-Agent": ua})
        self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a"}})
        self.assertEqual(self.presence.extra, [("Chrome", 1234), ("", None)])

    def test_polls_tell_the_engine_the_tab_is_still_there(self):
        self.short_polls(0.05)
        self.req("GET", "/ext/poll?i=A")
        self.req("GET", "/ext/poll")                             # extensions before 1.14 name no tab
        self.req("GET", "/ext/poll?i=" + "x" * 100)
        self.assertEqual(self.presence.touched, ["A", None, "x" * 64])
        self.presence.fail = True
        self.assertEqual(self.req("GET", "/ext/poll?i=A")[0], 200)     # a failing engine never breaks the poll

    def test_the_built_in_discord_status_is_the_hook(self):
        ds = make_discord(Path(self.tmp.name) / "ytm-starcast")
        app = App(self.audio, NullEngine(), self.media, prefs=JsonFile(self.prefs_path), beacon=ds,
                  opener=Opener(dry_run=True), updater=Updater(cache=Path(self.tmp.name) / "updates"),
                  audio_fetcher=AudioFetcher(folder=Path(self.tmp.name) / "audio", ytdlp=""))
        self.assertIs(app.presence, ds)
        app.set_parts({"discord": False})
        self.assertEqual((ds.enabled, app.parts()["discord"]), (False, False))
        ds.set_visibility({"action": "show"})
        self.assertEqual(app.parts()["discord"], True)
        self.assertIsNone(self.stored())

    def test_no_engine_is_fine(self):
        self.app.presence = None
        self.assertEqual(self.req("POST", "/ext/state", {"instance": "A", "now": {"title": "a"}})[0], 200)
        self.assertEqual(self.req("POST", "/ext/bye", {"instance": "A"})[0], 200)
        self.assertEqual(self.req("POST", "/api/parts", {"discord": False})[1]["parts"]["discord"], False)
        self.assertEqual(self.stored()["discord"], False)


if __name__ == "__main__":
    unittest.main()
