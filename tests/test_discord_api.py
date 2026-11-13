"""Discord status, the server-facing Presence object: reports from the extension bridge, status in YTM Beacon's
shape, settings, hide/show, the self-test, importing YTM Beacon's settings, dry run, and the engine thread.
Ports YTM Beacon's server tests that concern these operations. Every Discord connection is a fake (or none)."""
import json
import os
import tempfile
import threading
import time
import unittest

from starcast.discord import Presence, SettingsError, agent_from_user_agent, ipc
from starcast.discord import settings as st
from starcast.discord.presence import DRY_RUN_MESSAGE, MARKER_NAME

from .test_discord_fakes import (GUARD, REAL_DIAL_UNIX, T0, T0_MS, FakeClock, FakeConn, UnixFakeDiscord,
                                 no_client_id_env)

BEACON_STATUS_KEYS = ["app", "version", "commit", "os", "arch", "pid", "port", "uptimeSec", "clientIdSet", "discord",
                      "extension", "nowPlaying", "presenceActive", "visible", "snoozeSec", "hidden", "config",
                      "configPath", "logPath", "extensionBuild", "extensionDirs"]


def setUpModule():
    GUARD.install()


def tearDownModule():
    GUARD.uninstall()
    GUARD.check()


def now_obj(title="Song", playing=True, paused=False, buffering=False, **kw):
    n = {"playing": playing, "paused": paused, "buffering": buffering, "videoId": "dQw4w9WgXcQ", "title": title,
         "artist": "Artist", "album": "Album", "art": "https://lh3.googleusercontent.com/x=w544-h544",
         "position": 12.5, "duration": 200, "volume": 80, "liked": "LIKE", "ext": "1.16"}
    n.update(kw)
    return n


class Base(unittest.TestCase):
    def setUp(self):
        p = no_client_id_env()
        p.start()
        self.addCleanup(p.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.clock = FakeClock()
        self.conns = []
        self.dial_err = RuntimeError("no Discord in tests")

    def path(self, *parts):
        return os.path.join(self.tmp.name, *parts)

    def dial(self, client_id):
        if self.dial_err is not None:
            raise self.dial_err
        c = FakeConn()
        self.conns.append(c)
        return c

    def presence(self, **kw):
        kw.setdefault("clock", self.clock)
        kw.setdefault("dial", self.dial)
        p = Presence(kw.pop("settings_path", self.path("discord.json")), **kw)
        self.addCleanup(p.stop)
        return p


class BridgeTests(Base):
    def test_update_from_extension(self):
        p = self.presence()
        self.assertTrue(p.update_from_bridge("tab1", now_obj(), agent="Brave"))
        s = p.status()
        self.assertEqual(list(s)[:len(BEACON_STATUS_KEYS)], BEACON_STATUS_KEYS)
        self.assertEqual(s["app"], "ytm-starcast")
        self.assertEqual(s["nowPlaying"], {"title": "Song", "artist": "Artist", "album": "Album",
                                           "artwork": "https://lh3.googleusercontent.com/x=w544-h544",
                                           "videoId": "dQw4w9WgXcQ", "state": "playing", "position": 12.5,
                                           "duration": 200.0})
        self.assertEqual(s["extension"]["lastSeenAgoSec"], 0)
        self.assertEqual(s["extension"]["sources"], [{"id": "tab1", "agent": "Brave", "state": "playing", "ageSec": 0}])
        self.assertEqual(s["discord"], {"state": "connecting"})      # the engine is not running here
        self.assertEqual(s["extension"]["quietAfterSec"], 90)

    def test_bad_updates(self):
        p = self.presence()
        self.assertFalse(p.update_from_bridge("a", now_obj(title=None)), "no title")
        self.assertFalse(p.update_from_bridge("a", now_obj(title="   ")), "a blank title")
        self.assertFalse(p.update_from_bridge("a", "hello"), "not an object")
        self.assertFalse(p.update_from_bridge("test", now_obj()), "reserved for the self-test")
        self.assertFalse(p.update_from_bridge("a", {"playing": False, "paused": False, "videoId": None}),
                         "nothing loaded (the extension's empty state)")
        self.assertIsNone(p.status()["nowPlaying"])
        self.assertIsNone(p.status()["extension"]["lastSeenAgoSec"])

    def test_clear(self):
        p = self.presence()
        p.update_from_bridge("tab1", now_obj())
        p.clear("tab1")
        self.assertIsNone(p.status()["nowPlaying"])
        p.update_from_bridge("tab1", now_obj())
        p.bye("tab1")
        self.assertIsNone(p.status()["nowPlaying"])

    def test_field_mapping(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj(playing=False, buffering=True, artist="Some Band - Topic",
                                          art="http://insecure/x.jpg", videoId="bad", album=None))
        np = p.status()["nowPlaying"]
        self.assertEqual(np["state"], "playing", "buffering counts as playing")
        self.assertEqual(np["artist"], "Some Band", 'a trailing " - Topic" is removed')
        self.assertEqual((np["artwork"], np["videoId"], np["album"]), ("", "", ""))
        p.update_from_bridge("t", now_obj(playing=False, paused=True))
        self.assertEqual(p.status()["nowPlaying"]["state"], "paused")
        p.update_from_bridge("t", now_obj(playing=False, paused=False, position=200, duration=200))
        self.assertEqual(p.status()["nowPlaying"]["state"], "paused", "ended / unstarted counts as paused")
        p.update_from_bridge("t", now_obj(position="12", duration=None))
        np = p.status()["nowPlaying"]
        self.assertEqual((np["position"], np["duration"]), (0.0, 0.0), "numbers of the wrong type become 0")

    def test_times_follow_the_playback_speed(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj(position=40, duration=200, speed=0.8))
        np = p.status()["nowPlaying"]
        self.assertEqual((np["position"], np["duration"]), (50.0, 250.0), "slowed to 0.8x: 200 s of song take 250 s")
        for odd in (0, -1, 9, "2", True, None):
            p.update_from_bridge("t", now_obj(position=40, duration=200, speed=odd))
            np = p.status()["nowPlaying"]
            self.assertEqual((np["position"], np["duration"]), (40.0, 200.0), "a speed that makes no sense counts as 1: %r" % (odd,))

    def test_advert_clears_its_tab(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj())
        self.assertFalse(p.update_from_bridge("t", now_obj(title="Advert", ad=True)))
        self.assertIsNone(p.status()["nowPlaying"])
        self.assertIsNone(p.preview())
        self.assertTrue(p.update_from_bridge("t", now_obj(ad=False)))
        self.assertEqual(p.status()["nowPlaying"]["title"], "Song")

    def test_old_extension_without_instance(self):
        p = self.presence()
        p.update_from_bridge(None, now_obj())
        self.assertEqual(p.status()["extension"]["sources"][0]["id"], "extensio")
        p.clear(None)
        self.assertIsNone(p.status()["nowPlaying"])

    def test_page_sample_time_is_used_when_close(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj(position=30), ts=(T0 - 2) * 1000)
        self.assertEqual(p.preview()["timestamps"]["start"], T0_MS - 2000 - 30000)
        p.update_from_bridge("t", now_obj(position=30), ts=(T0 - 60) * 1000)   # a clock far off: ignored
        self.assertEqual(p.preview()["timestamps"]["start"], T0_MS - 30000)
        p.update_from_bridge("t", now_obj(position=30), ts="x")
        self.assertEqual(p.preview()["timestamps"]["start"], T0_MS - 30000)

    def test_touch_keeps_a_paused_tab(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj(playing=False, paused=True))
        for _ in range(6):              # a paused tab pushes nothing; its poll comes back every 25 s
            self.clock.advance(25)
            self.assertTrue(p.touch("t"))
        self.assertEqual(p.status()["nowPlaying"]["state"], "paused")
        self.assertEqual(p.status()["extension"]["lastSeenAgoSec"], 0)
        self.clock.advance(91)
        self.assertIsNone(p.status()["nowPlaying"], "without polls it goes after 90 s")
        self.assertFalse(p.touch("t"))
        self.assertFalse(p.touch("never-seen"))

    def test_push_without_now_changes_nothing(self):
        p = self.presence()
        p.update_from_bridge("t", now_obj())
        self.assertFalse(p.update_from_bridge("t", None))
        self.assertEqual(p.status()["nowPlaying"]["title"], "Song")

    def test_idle_tab_that_keeps_polling_is_idle_not_quiet(self):
        from starcast import beacon
        self.dial_err = None
        p = self.presence()
        p.engine.step()
        p.update_from_bridge("t", {"playing": False, "paused": False, "videoId": None})   # nothing loaded
        for _ in range(8):
            self.clock.advance(25)
            self.assertFalse(p.touch("t"))
        self.assertEqual(p.status()["extension"]["lastSeenAgoSec"], 0)
        self.assertEqual(beacon.headline(p.status()), "idle")

    def test_ever_connected_marker(self):
        p = self.presence()
        self.assertFalse(p.status()["extension"]["everConnected"])
        p.update_from_bridge("t", now_obj())
        self.assertTrue(p.status()["extension"]["everConnected"])
        self.assertTrue(os.path.exists(self.path(MARKER_NAME)))
        self.assertTrue(self.presence().status()["extension"]["everConnected"], "remembered across restarts")

    def test_agent_from_user_agent(self):
        chrome = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        self.assertEqual(agent_from_user_agent(chrome), "Chrome")
        self.assertEqual(agent_from_user_agent(chrome + " Edg/130.0"), "Edge")
        self.assertEqual(agent_from_user_agent("Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Firefox/132.0"), "Firefox")
        self.assertEqual(agent_from_user_agent(""), "")


class ConfigTests(Base):
    def test_config_endpoint(self):
        p = self.presence()
        with self.assertRaises(SettingsError):
            p.set_config({"clientId": "not-a-number"})
        got = p.set_config({"clientId": "123456789012345678", "showButtons": False, "pauseTimeoutMinutes": 9,
                            "statusDisplay": "details", "showPaused": True, "showAlbumArt": True})
        self.assertEqual((got["clientId"], got["showButtons"], got["pauseTimeoutMinutes"], got["statusDisplay"]),
                         ("123456789012345678", False, 9, "details"))
        self.assertEqual(p.get_config(), got)
        with open(self.path("discord.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f), got, "saved to the settings file")
        self.assertEqual(p.effective_client_id(), "123456789012345678")

    def test_config_post_keeps_untouched_settings(self):
        p = self.presence()
        p.visibility("hide")
        p.set_config({"privateMode": True, "hideMatching": ["  Secret ", "secret", "x"]})
        got = p.get_config()
        self.assertFalse(got["enabled"], "saving other settings must not switch Discord status back on")
        self.assertTrue(got["privateMode"])
        self.assertEqual(got["hideMatching"], ["secret", "x"])
        self.assertTrue(got["showProgress"])

    def test_concurrent_config_posts(self):
        p = self.presence()
        t = threading.Thread(target=p.set_config, args=('{"showPaused":false}',))
        t.start()
        p.set_config({"privateMode": True})
        t.join()
        c = p.get_config()
        self.assertFalse(c["showPaused"])
        self.assertTrue(c["privateMode"])
        with self.assertRaisesRegex(SettingsError, "^invalid JSON"):
            p.set_config('{"showPaused":')
        with self.assertRaisesRegex(SettingsError, "^invalid JSON"):
            p.set_config('{"showPaused": "no"}')
        with self.assertRaisesRegex(SettingsError, "^invalid JSON"):
            p.set_config("[1]")
        self.assertEqual(p.set_config(""), c, "an empty body changes nothing and is fine")
        self.assertEqual(p.set_config(b"  \n"), c)
        with self.assertRaisesRegex(SettingsError, "too large"):
            p.set_config(b" " * (65 << 10))

    def test_broken_settings_file_is_reported_and_defaults_used(self):
        with open(self.path("discord.json"), "w") as f:
            f.write("{not json")
        p = self.presence()
        self.assertIn("discord.json", p.settings_error)
        self.assertEqual(p.get_config(), st.default_config())

    def test_failed_save_changes_nothing(self):
        blocker = self.path("file")
        open(blocker, "w").close()
        p = self.presence(settings_path=os.path.join(blocker, "discord.json"))
        with self.assertRaises(OSError):
            p.set_config({"privateMode": True})
        with self.assertRaises(OSError):
            p.visibility("hide")
        self.assertEqual(p.get_config(), st.default_config())


class VisibilityTests(Base):
    def test_hide_show_toggle(self):
        p = self.presence()
        v = p.visibility("hide")
        self.assertEqual(v, {"enabled": False, "snoozeSec": 0, "visible": False})
        reloaded, _ = st.load(self.path("discord.json"))
        self.assertFalse(reloaded.get()["enabled"], "a hide is saved, so it survives a restart")

        self.assertEqual(p.visibility("toggle"), {"enabled": True, "snoozeSec": 0, "visible": True})
        self.assertFalse(p.visibility("toggle")["visible"])

        p.visibility("show")
        v = p.visibility("hide", 60)            # a timed hide leaves the saved switch alone
        self.assertTrue(v["enabled"])
        self.assertFalse(v["visible"])
        self.assertTrue(59 * 60 <= v["snoozeSec"] <= 60 * 60)
        self.assertTrue(p.get_config()["enabled"])
        v = p.visibility("toggle")
        self.assertTrue(v["visible"])
        self.assertEqual(v["snoozeSec"], 0)

        self.assertLessEqual(p.visibility("hide", 99999)["snoozeSec"], 24 * 3600, "capped at a day")
        p.visibility("show")
        with self.assertRaisesRegex(SettingsError, 'action must be "hide", "show" or "toggle"'):
            p.visibility("explode")
        for bad in ("5", 1.5, True):
            with self.assertRaises(SettingsError):
                p.visibility("hide", bad)
        self.assertTrue(p.visibility_state()["visible"], "a refused request changes nothing")

    def test_enabled_switch_for_the_server_parts(self):
        p = self.presence()
        self.assertTrue(p.enabled)
        p.visibility("hide", 10)
        v = p.set_enabled(False)
        self.assertEqual((v["enabled"], v["visible"]), (False, False))
        self.assertFalse(p.enabled)
        self.assertFalse(st.load(self.path("discord.json"))[0].get()["enabled"], "saved")
        v = p.set_enabled(True)
        self.assertTrue(p.enabled)
        self.assertGreater(v["snoozeSec"], 0, "switching on leaves a timed hide alone (show ends it)")
        self.assertFalse(v["visible"])
        with self.assertRaises(SettingsError):
            p.set_enabled("yes")
        self.assertTrue(p.feed("t", now_obj()), "feed is update_from_bridge")
        self.assertEqual(p.status()["nowPlaying"]["title"], "Song")

    def test_permanent_hide_keeps_a_running_snooze(self):
        p = self.presence()
        p.visibility("hide", 30)
        v = p.visibility("hide")
        self.assertFalse(v["enabled"])
        self.assertGreater(v["snoozeSec"], 0, "only show ends a timed hide")
        self.assertTrue(p.visibility("hide", -5)["enabled"] is False, "negative minutes switch it off")

    def test_status_shows_visibility(self):
        p = self.presence()
        p.visibility("hide")
        p.update_from_bridge("tab1", now_obj())
        s = p.status()
        self.assertFalse(s["visible"])
        self.assertFalse(s["config"]["enabled"])
        self.assertEqual(s["hidden"], "off")
        self.assertFalse(p.visibility_state()["visible"])

    def test_snooze_ends_by_itself(self):
        p = self.presence()
        p.visibility("hide", 1)
        self.assertEqual(p.status()["snoozeSec"], 60)
        self.clock.advance(61)
        self.assertTrue(p.status()["visible"])


class EngineIntegrationTests(Base):
    """The Presence object driving its engine one step at a time (no thread), with fake connections."""

    def test_headlines_through_beacon_normalize(self):
        from starcast import beacon        # its headline() / normalize() read the status unchanged
        self.dial_err = None
        p = self.presence()
        p.engine.step()
        self.assertEqual(beacon.headline(p.status()), "extension-missing")
        p.update_from_bridge("t", now_obj())
        self.assertEqual(beacon.headline(p.status()), "waiting")
        p.engine.step()
        out = beacon.normalize(p.status())
        self.assertEqual(out["headline"], "showing")
        self.assertTrue(out["reachable"])
        self.assertEqual(out["discord"], {"state": "connected", "user": "Tester"})
        self.assertEqual(out["nowPlaying"]["title"], "Song")
        self.assertNotIn("configPath", out)
        p.set_config({"hideMatching": ["song"]})
        self.assertEqual(beacon.headline(p.status()), "filtered")
        p.set_config({"hideMatching": []})
        p.visibility("hide", 30)
        self.assertEqual(beacon.headline(p.status()), "snoozed")
        p.visibility("show")
        p.visibility("hide")            # (a permanent hide alone would leave the timed one running)
        self.assertEqual(beacon.headline(p.status()), "off")
        p.visibility("show")
        p.clear("t")
        self.assertEqual(beacon.headline(p.status()), "idle")
        self.clock.advance(120)
        self.assertEqual(beacon.headline(p.status()), "extension-quiet")

    def test_card_follows_the_bridge(self):
        self.dial_err = None
        p = self.presence()
        p.update_from_bridge("t", now_obj(position=30))
        p.engine.step()
        c = self.conns[-1]
        self.assertEqual(c.last()["details"], "Song")
        self.assertEqual(c.last()["timestamps"], {"start": T0_MS - 30000, "end": T0_MS + 170000})
        self.clock.advance(3)
        p.update_from_bridge("t", now_obj(title="Advert", ad=True))
        p.engine.step()
        self.assertIsNone(c.last(), "an advert clears the card")
        self.assertFalse(p.status()["presenceActive"])

    def test_self_test(self):
        self.dial_err = None
        p = self.presence()
        self.assertEqual(p.test(), {"ok": True})
        self.assertEqual(p.preview()["details"], "Test song")
        p.engine.step()
        self.assertEqual(self.conns[-1].last()["state"], "YTM Starcast self-test")
        self.assertIsNone(p.status()["extension"]["lastSeenAgoSec"], "the self-test is not an extension")

    def test_dry_run_never_dials(self):
        def never(_cid):
            self.fail("dry run must not dial")
        p = self.presence(dry_run=True, dial=never)
        p.update_from_bridge("t", now_obj())
        p.engine.step()
        s = p.status()
        self.assertEqual(s["discord"], {"state": "disconnected", "lastError": DRY_RUN_MESSAGE})
        self.assertTrue(s["dryRun"])
        self.assertEqual(s["nowPlaying"]["title"], "Song", "the status still works")
        self.assertIsNotNone(p.preview())

    def test_dry_run_writes_nothing(self):
        src = self.path("ytm-beacon", "config.json")
        os.makedirs(os.path.dirname(src))
        with open(src, "w") as f:
            json.dump({"showButtons": False}, f)
        p = self.presence(dry_run=True, import_beacon_from=src)
        self.assertFalse(p.get_config()["showButtons"], "YTM Beacon's settings are used...")
        p.set_config({"privateMode": True})
        p.visibility("hide")
        p.update_from_bridge("t", now_obj())
        self.assertEqual((p.get_config()["privateMode"], p.get_config()["enabled"]), (True, False))
        self.assertTrue(p.status()["extension"]["everConnected"])
        self.assertEqual(sorted(os.listdir(self.tmp.name)), ["ytm-beacon"], "...but no file is written")

    def test_default_dialer_goes_through_the_guard(self):
        before = list(GUARD.calls)
        p = self.presence(dial=None)
        p.engine.step()
        s = p.status()["discord"]
        self.assertEqual(s["state"], "disconnected")
        self.assertIn("real Discord", s["lastError"])
        self.assertEqual([c[0] for c in GUARD.calls[len(before):]], ["discover_endpoints"])
        GUARD.calls[:] = before         # this test tripped the guard on purpose

    def test_client_id_default_and_override(self):
        self.assertEqual(self.presence().effective_client_id(), "1567329407901634670")
        self.assertEqual(self.presence(client_id="111111111111111111").effective_client_id(), "111111111111111111")
        p = self.presence(asset_base="https://example.com/art")
        self.assertEqual(p.assets.logo, "https://example.com/art/logo.png")


class ThreadTests(Base):
    def wait_for(self, cond, timeout=5.0):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if cond():
                return True
            time.sleep(0.01)
        return False

    def test_start_stop_clears_and_closes(self):
        self.dial_err = None
        p = self.presence(clock=time.time)
        p.update_from_bridge("t", now_obj())
        p.start()
        p.start()                       # harmless
        self.assertTrue(self.wait_for(lambda: self.conns and self.conns[-1].count() >= 1))
        c = self.conns[-1]
        self.assertEqual(c.calls[0]["details"], "Song")
        self.assertTrue(p.running)
        p.stop()
        self.assertFalse(p.running)
        self.assertIsNone(c.last(), "stopping clears the card")
        self.assertTrue(c.closed)
        self.assertFalse(p.status()["presenceActive"])
        p.start()                       # and it can start again
        self.assertTrue(self.wait_for(lambda: len(self.conns) == 2 and self.conns[1].count() >= 1))
        p.stop()

    def test_wakes_on_a_new_report_and_on_a_lost_connection(self):
        self.dial_err = None
        p = self.presence(clock=time.time)
        p.start()
        self.assertTrue(self.wait_for(lambda: len(self.conns) == 1))
        p.update_from_bridge("t", now_obj())
        self.assertTrue(self.wait_for(lambda: self.conns[0].count() == 1))
        self.conns[0].close()
        self.assertTrue(self.wait_for(lambda: len(self.conns) == 2 and self.conns[1].count() == 1))
        p.stop()

    def test_end_to_end_over_a_unix_socket(self):
        f = UnixFakeDiscord(self)
        p = self.presence(clock=time.time,
                          dial=lambda cid: ipc.connect(cid, endpoints=[f.path], dial=REAL_DIAL_UNIX, timeout=2.0))
        p.update_from_bridge("t", now_obj(), agent="Firefox")
        p.start()
        self.assertEqual(f.hello.get(timeout=5)["client_id"], "1567329407901634670", "the built-in application id")
        req = f.frames.get(timeout=5)
        self.assertEqual(req["cmd"], "SET_ACTIVITY")
        act = req["args"]["activity"]
        self.assertEqual((act["details"], act["state"], act["assets"]["large_text"]), ("Song", "Artist", "Album"))
        self.assertTrue(self.wait_for(lambda: p.status()["presenceActive"]))
        self.assertEqual(p.status()["discord"], {"state": "connected", "user": "Tester", "endpoint": f.path})
        p.stop()
        self.assertNotIn("activity", f.frames.get(timeout=5)["args"], "stopping clears the card")
        self.assertFalse(p.status()["presenceActive"])

    def test_dry_run_thread(self):
        before = list(GUARD.calls)
        p = self.presence(dry_run=True, dial=None, clock=time.time)
        p.start()
        self.assertTrue(self.wait_for(lambda: p.status()["discord"].get("lastError") == DRY_RUN_MESSAGE))
        p.stop()
        self.assertEqual(GUARD.calls, before, "dry run never reaches the real dialer")


class ImportTests(Base):
    def write_beacon(self, cfg, name="config.json"):
        path = self.path("ytm-beacon", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(cfg, f)
        return path

    def test_first_start_imports_beacon_settings(self):
        # YTM Beacon 0.1.0 wrote only six keys; the newer settings come out on
        old = {"clientId": "", "showButtons": False, "showPaused": True, "pauseTimeoutMinutes": 9,
               "showAlbumArt": False, "statusDisplay": "state"}
        src = self.write_beacon(old)
        p = self.presence(import_beacon_from=src)
        want = {**st.default_config(), "showButtons": False, "pauseTimeoutMinutes": 9, "showAlbumArt": False,
                "statusDisplay": "state"}
        self.assertEqual(p.get_config(), want)
        self.assertEqual(p.imported_from, src)
        with open(self.path("discord.json")) as f:
            self.assertEqual(json.load(f), want)
        # once the settings file exists, Beacon's file is not read again
        self.write_beacon({**old, "showButtons": True})
        self.assertFalse(self.presence(import_beacon_from=src).get_config()["showButtons"])

    def test_no_beacon_file_means_defaults_and_no_file(self):
        p = self.presence(import_beacon_from=self.path("nope", "config.json"))
        self.assertEqual(p.get_config(), st.default_config())
        self.assertFalse(os.path.exists(self.path("discord.json")))

    def test_broken_beacon_file_is_skipped(self):
        src = self.write_beacon({"clientId": "abc"})
        p = self.presence(import_beacon_from=src)
        self.assertEqual(p.get_config(), st.default_config())

    def test_import_live_config(self):
        p = self.presence()
        live = {"enabled": False, "clientId": "222222222222222222", "showButtons": True, "showPaused": False,
                "pauseTimeoutMinutes": 0, "showAlbumArt": True, "statusDisplay": "details", "showProgress": False,
                "showStateIcon": True, "privateMode": True, "hideMatching": ["  Secret "]}
        got = p.import_beacon_config(live)
        self.assertEqual(got, {**live, "hideMatching": ["secret"]})
        self.assertEqual(p.effective_client_id(), "222222222222222222")
        before = p.get_config()
        with self.assertRaises(SettingsError):
            p.import_beacon_config({"statusDisplay": "sideways"})
        with self.assertRaises(SettingsError):
            p.import_beacon_config(["not", "a", "dict"])
        self.assertEqual(p.get_config(), before, "a refused import changes nothing")


if __name__ == "__main__":
    unittest.main()
