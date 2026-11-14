"""Discord status: building the card, cleaning reports, choosing the tab, and the engine (send only on change,
throttle, seek, pause timeout, switches, reconnect, fallback). A port of YTM Beacon's presence tests; every
Discord connection here is a fake."""
import os
import unittest
from pathlib import Path

from starcast.discord import build, ipc
from starcast.discord import settings as st
from starcast.discord.build import (DEFAULT_ASSETS, HIDDEN_FILTERED, HIDDEN_OFF, HIDDEN_PAUSED, HIDDEN_SNOOZED,
                                  activity_key, asset_base_for, build_activity, field, track_url)
from starcast.discord.engine import STATE_CONNECTED, STATE_DISCONNECTED, STATE_NO_CLIENT_ID
from starcast.discord.tracker import (MAX_PHONES, PHONE_PREFIX, SOURCE_TTL, STATE_PAUSED, STATE_PLAYING, TEST_SOURCE,
                                    TEST_TTL, SanitizeError, Selected, Tracker, Update, is_phone, quiet_after)

from .test_discord_fakes import (GUARD, T0, T0_MS, FakeClock, FakeConn, Harness, no_client_id_env, song,
                                 config_with_id)

ART = DEFAULT_ASSETS
REPO = Path(__file__).resolve().parent.parent
PHONE = PHONE_PREFIX + "pixel-7a"      # a phone that plays music itself (POST /api/phone/playing)


def setUpModule():
    GUARD.install()


def tearDownModule():
    GUARD.uninstall()
    GUARD.check()


def sel_of(u, received_at=T0, paused_since=None):
    return Selected(u, received_at=received_at, paused_since=paused_since)


def small(act):
    return (act.get("assets") or {}).get("small_image", "")


# ---- BuildActivity ----

class BuildTests(unittest.TestCase):
    def test_build_playing(self):
        now = T0
        sel = sel_of(song("a", "Song", STATE_PLAYING, 30, 200), received_at=now)
        act, start = build_activity(st.default_config(), sel, now, False)
        self.assertIsNotNone(act)
        self.assertEqual((act["type"], act["details"], act["state"]), (2, "Song", "Artist"))
        want = T0_MS - 30000
        self.assertEqual(want, 1793707170000)
        self.assertEqual(start, want)
        self.assertEqual(act["timestamps"], {"start": want, "end": want + 200000})
        self.assertEqual(act["assets"]["large_image"], sel.artwork)
        self.assertEqual(act["assets"]["large_text"], "Album")
        self.assertEqual(act["assets"]["small_image"], ART.play)
        self.assertEqual(act["buttons"], [{"label": "Listen on YouTube Music",
                                           "url": "https://music.youtube.com/watch?v=dQw4w9WgXcQ"}])
        self.assertNotIn("status_display_type", act)
        self.assertIs(act["instance"], False)
        self.assertEqual(list(act), ["type", "details", "state", "timestamps", "assets", "buttons", "instance"])

    def test_build_paused_and_timeout(self):
        sel = sel_of(song("a", "Song", STATE_PAUSED, 30, 200), received_at=T0, paused_since=T0)
        act, start = build_activity(st.default_config(), sel, T0 + 60, False)
        self.assertIsNotNone(act)
        self.assertNotIn("timestamps", act)
        self.assertEqual(start, 0)
        self.assertEqual(small(act), ART.pause)
        self.assertIsNone(build_activity(st.default_config(), sel, T0 + 6 * 60, False)[0],
                          "paused longer than the timeout must be hidden")
        cfg = st.default_config()
        cfg["showPaused"] = False
        self.assertIsNone(build_activity(cfg, sel, T0, False)[0], "showPaused off must hide at once")

    def test_build_options(self):
        u = song("a", "S", STATE_PLAYING, 0, 0)
        u.artist, u.video_id, u.artwork = "", "", ""
        sel = sel_of(u)
        cfg = st.default_config()
        cfg["showButtons"] = False
        cfg["statusDisplay"] = st.STATUS_DETAILS
        act, _ = build_activity(cfg, sel, T0, False)
        self.assertEqual(act["details"], "S\u200b", "one-character fields must be padded")
        self.assertNotIn("state", act)
        self.assertNotIn("buttons", act)
        self.assertIn("timestamps", act)
        self.assertEqual(act["timestamps"].get("end", 0), 0, "unknown duration: no end time")
        self.assertEqual(act["assets"]["large_image"], ART.logo)
        self.assertEqual(act["status_display_type"], 2)
        self.assertEqual(list(act)[:2], ["type", "status_display_type"])
        basic, _ = build_activity(cfg, sel, T0, True)
        self.assertNotIn("status_display_type", basic)
        self.assertNotIn("assets", basic, "basic mode must drop optional fields")

    def test_search_link_when_no_video_id(self):
        sel = sel_of(Update(artist="Daft Punk", title="One More Time"))
        self.assertTrue(track_url(sel).startswith("https://music.youtube.com/search?q=Daft+Punk+One+More+Time"))

    def test_field_truncates(self):
        self.assertEqual(len(field("\u3042" * 300)), 100)
        self.assertTrue(field("\u3042" * 300).endswith("\u2026"))

    def test_build_progress_and_icon_settings(self):
        sel = sel_of(song("a", "Song", STATE_PLAYING, 30, 200))
        cfg = st.default_config()
        cfg["showProgress"] = False
        act, start = build_activity(cfg, sel, T0, False)
        self.assertNotIn("timestamps", act)
        self.assertEqual(start, 0)
        cfg = st.default_config()
        cfg["showStateIcon"] = False
        act, _ = build_activity(cfg, sel, T0, False)
        self.assertNotIn("small_image", act["assets"])
        self.assertNotIn("small_text", act["assets"])
        self.assertTrue(act["assets"]["large_image"])

    def test_build_hides_matching_words(self):
        u = song("a", "A Very Secret Song", STATE_PLAYING, 0, 100)
        sel = sel_of(u)
        cfg = st.default_config()
        cfg["hideMatching"] = ["nothing here", "SECRET"]       # capitalisation is ignored
        self.assertIsNone(build_activity(cfg, sel, T0, False)[0])
        sel.title, sel.artist = "Fine", "The Secret Band"
        self.assertIsNone(build_activity(cfg, sel, T0, False)[0], "the artist is checked too")
        sel.artist, sel.album = "Fine", "Secret Album"
        self.assertIsNone(build_activity(cfg, sel, T0, False)[0], "the album is checked too")
        sel.album = "Fine"
        self.assertIsNotNone(build_activity(cfg, sel, T0, False)[0])

    def test_build_private_mode(self):
        sel = sel_of(song("a", "My Guilty Pleasure", STATE_PLAYING, 30, 200))
        cfg = st.default_config()
        cfg["privateMode"] = True
        cfg["statusDisplay"] = st.STATUS_DETAILS
        act, start = build_activity(cfg, sel, T0, False)
        self.assertIsNotNone(act)
        self.assertEqual(start, 0)
        for k in ("timestamps", "buttons", "state", "status_display_type"):
            self.assertNotIn(k, act)
        self.assertEqual(act["assets"]["large_image"], ART.logo)
        self.assertNotIn("Album", act["assets"].get("large_text", ""))
        raw = act["details"] + act.get("state", "") + act["assets"]["large_image"] + act["assets"].get("large_text", "")
        for secret in ("Guilty", "Artist", "Album", "googleusercontent"):
            self.assertNotIn(secret, raw)
        self.assertEqual(act, {"type": 2, "details": "Listening to music",
                               "assets": {"large_image": ART.logo, "small_image": ART.play, "small_text": "Playing"},
                               "instance": False})
        act2, _ = build_activity(cfg, sel_of(song("a", "Another Song", STATE_PLAYING, 5, 100)), T0, False)
        self.assertEqual(activity_key(act), activity_key(act2), "every song must look the same")
        paused = sel_of(song("a", "S", STATE_PAUSED, 0, 0), paused_since=T0)
        self.assertIsNone(build_activity(cfg, paused, T0 + 3600, False)[0], "a long pause still hides it")
        basic, _ = build_activity(cfg, sel, T0, True)
        self.assertEqual(basic, {"type": 2, "details": "Listening to music", "instance": False})

    def test_show_when_paused(self):
        sel = sel_of(song("a", "Song", STATE_PAUSED, 30, 200), paused_since=T0)
        cfg = st.default_config()
        act, _ = build_activity(cfg, sel, T0 + 60, False)
        self.assertEqual(act["details"], "Song")
        self.assertNotIn("timestamps", act)
        self.assertEqual(act["assets"]["small_image"], ART.pause)
        self.assertEqual(act["assets"]["small_text"], "Paused")
        cfg["showPaused"] = False
        self.assertIsNone(build_activity(cfg, sel, T0, False)[0])

    def test_pause_timeout_zero_never_hides(self):
        sel = sel_of(song("a", "Song", STATE_PAUSED, 30, 200), paused_since=T0)
        cfg = st.default_config()
        cfg["pauseTimeoutMinutes"] = 0
        self.assertIsNotNone(build_activity(cfg, sel, T0 + 48 * 3600, False)[0])
        cfg["pauseTimeoutMinutes"] = 10
        self.assertIsNone(build_activity(cfg, sel, T0 + 11 * 60, False)[0])
        self.assertIsNotNone(build_activity(cfg, sel, T0 + 9 * 60, False)[0])

    def test_badge_images_are_web_addresses(self):
        base = asset_base_for("dev")
        self.assertEqual(base, "https://raw.githubusercontent.com/dash1101/ytm-starcast/main/assets/discord/")
        art = build.AssetURLs(base)
        for state in (STATE_PLAYING, STATE_PAUSED):
            u = song("a", "Song", state, 5, 100)
            u.artwork = ""
            act, _ = build_activity(st.default_config(), sel_of(u, paused_since=T0), T0, False, art)
            for img in (act["assets"]["large_image"], act["assets"]["small_image"]):
                self.assertTrue(img.startswith(base) and img.endswith(".png"), img)
        # the default (this release) points at the same folder, under the release tag or main
        for img in (ART.logo, ART.play, ART.pause):
            self.assertRegex(img, r"^https://raw\.githubusercontent\.com/dash1101/ytm-starcast/[^/]+/assets/discord/\w+\.png$")

    def test_badge_image_files_exist(self):
        for img in (ART.logo, ART.play, ART.pause):
            name = img.rsplit("/", 1)[1]
            self.assertTrue((REPO / "assets" / "discord" / name).is_file(), name)

    def test_assets_point_at_the_release_tag(self):
        raw = "https://raw.githubusercontent.com/dash1101/ytm-starcast/"
        cases = {
            "v0.1.5": raw + "v0.1.5/assets/discord/",
            "v1.2.3-rc1": raw + "v1.2.3-rc1/assets/discord/",
            "dev": raw + "main/assets/discord/",
            "ci": raw + "main/assets/discord/",
            "v1/../x": raw + "main/assets/discord/",
            "0.2.0.dev3": raw + "main/assets/discord/",
            # YTM Starcast's own versions are PEP 440; they map to their git tags (Beacon's "0.1.5" -> main differs)
            "0.1.5": raw + "v0.1.5/assets/discord/",
            "0.2.0a1": raw + "v0.2.0-alpha.1/assets/discord/",
            "1.0.0b2": raw + "v1.0.0-beta.2/assets/discord/",
            "1.0.0rc1": raw + "v1.0.0-rc.1/assets/discord/",
        }
        for ver, want in cases.items():
            self.assertEqual(asset_base_for(ver), want, ver)

    def test_activity_key_ignores_start_only(self):
        sel = sel_of(song("a", "Song", STATE_PLAYING, 30, 200))
        a, _ = build_activity(st.default_config(), sel, T0, False)
        sel.position = 31
        b, _ = build_activity(st.default_config(), sel, T0, False)
        self.assertEqual(activity_key(a), activity_key(b))
        self.assertEqual(activity_key(None), "")
        self.assertTrue(activity_key(a).endswith("|true|true"))


# ---- Update.sanitize ----

class SanitizeTests(unittest.TestCase):
    def test_sanitize(self):
        u = Update(source="s", title="  T  ", state="weird", artwork="http://insecure/x.png", video_id="nope",
                   position=-5, duration=1e12)
        u.sanitize()
        self.assertEqual((u.title, u.state, u.artwork, u.video_id, u.position, u.duration),
                         ("T", STATE_PLAYING, "", "", 0.0, 1e6))
        with self.assertRaisesRegex(SanitizeError, "missing title"):
            Update(source="s").sanitize()
        with self.assertRaisesRegex(SanitizeError, "missing source"):
            Update(title="t").sanitize()

    def test_sanitize_edges(self):
        u = Update(source="s", title="t", state="Paused", video_id="abcdefghijk\n", position=float("nan"),
                   duration=float("inf"), artwork="https://x/" + "a" * 500)
        u.sanitize()
        self.assertEqual(u.state, STATE_PLAYING, "state is case-sensitive")
        self.assertEqual(u.video_id, "", "the id must match fully (no trailing newline)")
        self.assertEqual((u.position, u.duration), (0.0, 0.0))
        self.assertEqual(u.artwork, "", "art longer than 500 bytes is dropped")
        u = Update(source="s" * 100, agent="a" * 40, title="x" * 400, artist="\ud83d lone", video_id="dQw4w9WgXcQ")
        u.sanitize()
        self.assertEqual((len(u.source), len(u.agent), len(u.title)), (64, 32, 300))
        self.assertEqual(u.artist, "\ufffd lone", "a lone surrogate becomes U+FFFD, like Go")
        self.assertEqual(u.video_id, "dQw4w9WgXcQ")


# ---- Tracker ----

class TrackerTests(unittest.TestCase):
    def setUp(self):
        self.clk = FakeClock()
        self.tr = Tracker(self.clk)

    def title(self):
        s = self.tr.select()
        return s.title if s else None

    def test_prefers_playing_then_newest(self):
        self.tr.put(song("a", "A", STATE_PAUSED, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("b", "B", STATE_PLAYING, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("c", "C", STATE_PAUSED, 0, 0))
        self.assertEqual(self.title(), "B", "playing must beat paused")
        self.clk.advance(1)
        self.tr.put(song("a", "A", STATE_PLAYING, 0, 0))
        self.assertEqual(self.title(), "A", "newest play-press must win")

    def test_expiry_and_clear(self):
        self.tr.put(song("a", "A", STATE_PLAYING, 0, 0))
        self.assertIsNotNone(self.tr.last_extension_seen())
        self.clk.advance(SOURCE_TTL + 1)
        self.assertIsNone(self.tr.select(), "a stale source must expire")
        self.assertIsNotNone(self.tr.last_extension_seen(), "last-seen must survive expiry")
        self.tr.put(song("a", "A", STATE_PLAYING, 0, 0))
        self.tr.clear("a")
        self.assertIsNone(self.tr.select())

    def test_kept_exactly_at_expiry(self):
        self.tr.put(song("a", "A", STATE_PLAYING, 0, 0))
        self.clk.advance(SOURCE_TTL)
        self.assertIsNotNone(self.tr.select(), "kept at exactly the expiry time")

    def test_keeps_original_pause_time(self):
        self.tr.put(song("a", "A", STATE_PAUSED, 5, 100))
        first = self.clk()
        self.clk.advance(20)
        self.tr.put(song("a", "A", STATE_PAUSED, 5, 100))
        self.assertEqual(self.tr.select().paused_since, first)
        self.tr.put(song("a", "A", STATE_PLAYING, 6, 100))
        self.assertIsNone(self.tr.select().paused_since)

    def test_test_source_does_not_count_as_extension(self):
        self.tr.put(Update(source=TEST_SOURCE, state=STATE_PLAYING, title="T"))
        self.assertIsNone(self.tr.last_extension_seen())

    def test_does_not_flip_between_two_playing_tabs(self):
        self.tr.put(song("a", "A", STATE_PLAYING, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("b", "B", STATE_PLAYING, 0, 0))   # play pressed in b last
        for i in range(6):
            self.clk.advance(2)
            src = "a" if i % 2 == 0 else "b"
            self.tr.put(song(src, src.upper(), STATE_PLAYING, i, 0))
            self.assertEqual(self.title(), "B", "report %d from %s flipped the card" % (i, src))
        self.tr.put(song("b", "B", STATE_PAUSED, 0, 0))
        self.assertEqual(self.title(), "A", "the tab still playing must win")
        self.clk.advance(1)
        self.tr.put(song("b", "B", STATE_PLAYING, 0, 0))
        self.assertEqual(self.title(), "B", "pressing play again must bring b back")

    def test_picks_the_tab_paused_last(self):
        self.tr.put(song("a", "A", STATE_PAUSED, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("b", "B", STATE_PAUSED, 0, 0))
        self.clk.advance(30)
        self.tr.put(song("a", "A", STATE_PAUSED, 0, 0))     # a "still paused" report from the older one
        self.assertEqual(self.title(), "B")

    def test_sources_and_touch(self):
        self.assertEqual(self.tr.sources(), [])
        self.tr.put(song("zzzzzzzzzz-long", "A", STATE_PLAYING, 0, 0))
        self.tr.put(song("b", "B", STATE_PAUSED, 0, 0))
        self.clk.advance(3.7)
        self.assertEqual(self.tr.sources(), [
            {"id": "b", "agent": "Test", "state": "paused", "ageSec": 3},
            {"id": "zzzzzzzz", "agent": "Test", "state": "playing", "ageSec": 3}])
        # touch keeps a source alive without changing it (a paused tab pushes nothing, only polls)
        self.clk.advance(80)
        self.assertTrue(self.tr.touch("b"))
        self.clk.advance(80)
        self.assertEqual([s["id"] for s in self.tr.sources()], ["b"])
        sel = self.tr.select()
        self.assertEqual((sel.title, sel.paused_since, sel.received_at), ("B", T0, T0))

    def test_touch_counts_the_extension_as_seen(self):
        self.assertIsNone(self.tr.last_extension_seen())
        self.assertFalse(self.tr.touch(TEST_SOURCE), "the self-test is not an extension")
        self.assertIsNone(self.tr.last_extension_seen())
        self.clk.advance(7)
        self.assertFalse(self.tr.touch("idle-tab"), "a tab with nothing loaded has no song to keep")
        self.assertEqual(self.tr.last_extension_seen(), T0 + 7, "but the extension is alive")
        self.assertIsNone(self.tr.select())

    def test_received_at_is_the_measuring_time(self):
        self.tr.put(song("a", "A", STATE_PLAYING, 30, 200), received_at=T0 - 1.5)
        self.assertEqual(self.tr.select().received_at, T0 - 1.5)
        self.clk.advance(SOURCE_TTL)
        self.assertIsNotNone(self.tr.select(), "expiry still counts from when the report arrived")

    def test_quiet_after_is_over_one_minute(self):
        self.assertGreater(quiet_after(), 65)

    # ---- a phone playing music itself ----
    def test_phone_is_not_the_extension(self):
        self.assertTrue(is_phone(PHONE))
        self.assertFalse(is_phone("tab-1") or is_phone(TEST_SOURCE) or is_phone(None))
        self.tr.put(song(PHONE, "P", STATE_PLAYING, 0, 0))
        self.assertIsNone(self.tr.last_extension_seen(), "a phone is not the browser extension")
        self.assertEqual(self.tr.last_phone_seen(), T0)
        self.assertEqual(self.title(), "P")
        self.assertEqual(self.tr.sources(), [], "the extension's tab list leaves phones out")
        self.clk.advance(10)
        self.assertTrue(self.tr.touch(PHONE))
        self.assertIsNone(self.tr.last_extension_seen(), "nor does keeping a phone alive count as the extension")
        self.assertEqual(self.tr.last_phone_seen(), T0 + 10)
        self.tr.put(song("tab-1", "T", STATE_PAUSED, 0, 0))
        self.assertEqual(self.tr.last_extension_seen(), T0 + 10)
        self.assertEqual([s["id"] for s in self.tr.sources()], ["tab-1"])

    def test_phone_against_a_tab_both_ways(self):
        self.tr.put(song("tab-1", "Tab", STATE_PLAYING, 0, 0))
        self.clk.advance(1)
        self.tr.put(song(PHONE, "Phone", STATE_PLAYING, 0, 0))
        self.assertEqual(self.title(), "Phone", "play pressed on the phone last: the phone wins")
        self.clk.advance(1)
        self.tr.put(song("tab-1", "Tab", STATE_PAUSED, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("tab-1", "Tab", STATE_PLAYING, 0, 0))
        self.assertEqual(self.title(), "Tab", "play pressed in the tab last: the tab wins")
        self.tr.put(song("tab-1", "Tab", STATE_PAUSED, 0, 0))
        self.assertEqual(self.title(), "Phone", "a playing phone beats a paused tab")
        self.clk.advance(1)
        self.tr.put(song(PHONE, "Phone", STATE_PAUSED, 0, 0))
        self.tr.put(song("tab-1", "Tab", STATE_PLAYING, 0, 0))
        self.assertEqual(self.title(), "Tab", "a playing tab beats a paused phone")

    def test_phone_expires_without_heartbeats(self):
        self.tr.put(song(PHONE, "P", STATE_PAUSED, 10, 200))
        for _ in range(20):         # paused, the phone still reports every 30 s: kept for as long as it does
            self.clk.advance(30)
            self.tr.put(song(PHONE, "P", STATE_PAUSED, 10, 200))
        self.assertEqual(self.title(), "P")
        self.assertEqual(self.tr.select().paused_since, T0, "heartbeats keep the time it was paused")
        self.clk.advance(SOURCE_TTL)
        self.assertEqual(self.title(), "P", "kept for 90 s after the last heartbeat")
        self.clk.advance(1)
        self.assertIsNone(self.tr.select(), "gone 90 s after the last heartbeat")
        self.assertIsNotNone(self.tr.last_phone_seen(), "last-seen survives expiry")

    def test_phone_heartbeats_do_not_flip(self):
        self.tr.put(song(PHONE, "Phone", STATE_PLAYING, 0, 0))
        self.clk.advance(1)
        self.tr.put(song("tab-1", "Tab", STATE_PLAYING, 0, 0))     # play pressed in the tab last
        for i in range(1, 31):
            self.clk.advance(2)
            self.tr.put(song("tab-1", "Tab", STATE_PLAYING, 2 * i, 0))
            if i % 15 == 0:                                         # the phone's heartbeat, every 30 s
                self.tr.put(song(PHONE, "Phone", STATE_PLAYING, 30 * i, 0))
            self.assertEqual(self.title(), "Tab", "report %d flipped the card" % i)
        self.tr.put(song("tab-1", "Tab", STATE_PAUSED, 0, 0))
        self.assertEqual(self.title(), "Phone")
        for _ in range(4):
            self.clk.advance(30)
            self.tr.put(song(PHONE, "Phone", STATE_PLAYING, 0, 0))
            self.tr.put(song("tab-1", "Tab", STATE_PAUSED, 0, 0))
            self.assertEqual(self.title(), "Phone")

    def test_made_up_phone_ids_cannot_pile_up(self):
        # nothing calls select() here (as when Discord is closed): reports alone must keep the list small
        self.tr.put(song(PHONE, "Mine", STATE_PAUSED, 0, 0))
        for i in range(MAX_PHONES * 5):
            self.clk.advance(0.01)
            self.tr.put(song(PHONE_PREFIX + "fake%04d" % i, "Fake", STATE_PAUSED, 0, 0))
        self.assertEqual(len(self.tr._m), MAX_PHONES)
        self.assertNotIn(PHONE, self.tr._m, "the phone heard from longest ago goes first")
        self.tr.put(song("tab-1", "Tab", STATE_PAUSED, 0, 0))
        self.assertIn("tab-1", self.tr._m, "browser tabs don't count against the phones")
        self.clk.advance(SOURCE_TTL + 1)
        self.tr.put(song(PHONE, "Mine", STATE_PLAYING, 0, 0))
        self.assertEqual(list(self.tr._m), [PHONE], "what expired is forgotten as new reports arrive")


# ---- Engine ----

class EngineTests(unittest.TestCase):
    def setUp(self):
        p = no_client_id_env()
        p.start()
        self.addCleanup(p.stop)

    def test_no_client_id(self):
        h = Harness(st.default_config())
        h.engine.default_client_id = ""     # and no built-in default either
        h.step()
        self.assertEqual(h.dials, 0)
        self.assertEqual(h.engine.snapshot()["discord"], {"state": STATE_NO_CLIENT_ID})

    def test_sends_only_on_change(self):
        h = Harness(config_with_id())
        h.step()
        snap = h.engine.snapshot()
        self.assertEqual(snap["discord"]["state"], STATE_CONNECTED)
        self.assertEqual(snap["discord"]["user"], "Tester")
        self.assertEqual(h.conn().count(), 0, "nothing is playing, nothing is sent")

        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        self.assertEqual(h.conn().count(), 1)
        self.assertEqual(h.conn().last()["details"], "One")
        self.assertTrue(h.engine.snapshot()["presenceActive"])

        for i in range(4):          # heartbeats with matching positions
            h.clock.advance(5)
            h.tracker.put(song("a", "One", STATE_PLAYING, 10 + 5 * (i + 1), 200))
            h.step()
        self.assertEqual(h.conn().count(), 1, "heartbeats must not cause updates")

        h.clock.advance(5)          # a seek forward
        h.tracker.put(song("a", "One", STATE_PLAYING, 120, 200))
        h.step()
        self.assertEqual(h.conn().count(), 2, "a seek must update the progress bar")

        h.clock.advance(5)
        h.tracker.put(song("a", "Two", STATE_PLAYING, 0, 180))
        h.step()
        self.assertEqual(h.conn().count(), 3)
        self.assertEqual(h.conn().last()["details"], "Two")

    def test_throttles_rapid_changes(self):
        h = Harness(config_with_id())
        h.tracker.put(song("a", "One", STATE_PLAYING, 0, 100))
        h.step()
        h.clock.advance(0.5)
        h.tracker.put(song("a", "Two", STATE_PLAYING, 0, 100))
        h.step()
        self.assertEqual(h.conn().count(), 1, "a second update within 2 s must wait")
        h.clock.advance(2)
        h.tracker.put(song("a", "Two", STATE_PLAYING, 2, 100))
        h.step()
        self.assertEqual(h.conn().count(), 2)
        self.assertEqual(h.conn().last()["details"], "Two")

    def test_pause_then_clear(self):
        h = Harness(config_with_id())
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        h.clock.advance(3)
        h.tracker.put(song("a", "One", STATE_PAUSED, 13, 200))
        h.step()
        last = h.conn().last()
        self.assertIsNotNone(last)
        self.assertNotIn("timestamps", last)
        self.assertEqual(small(last), DEFAULT_ASSETS.pause)
        for _ in range(12):
            h.clock.advance(30)
            h.tracker.put(song("a", "One", STATE_PAUSED, 13, 200))
            h.step()
        self.assertIsNone(h.conn().last(), "cleared after the pause timeout")
        self.assertFalse(h.engine.snapshot()["presenceActive"])

    def test_card_shows_the_phones_song(self):
        h = Harness(config_with_id())
        h.tracker.put(song(PHONE, "From the phone", STATE_PLAYING, 30, 200))
        h.step()
        last = h.conn().last()
        self.assertEqual((last["details"], last["state"]), ("From the phone", "Artist"))
        self.assertEqual(last["timestamps"], {"start": T0_MS - 30000, "end": T0_MS + 170000})
        self.assertEqual(last["buttons"][0]["url"], "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        snap = h.engine.snapshot()
        self.assertEqual((snap["nowPlaying"]["title"], snap["nowPlaying"]["from"]), ("From the phone", "phone"))
        self.assertEqual(h.engine.showing(PHONE), (True, ""))
        self.assertEqual(h.engine.showing("tab-1"), (False, ""), "a source that isn't chosen is not shown")
        h.clock.advance(5)
        h.tracker.put(song("tab-1", "From the tab", STATE_PLAYING, 0, 200))
        h.step()
        self.assertEqual(h.conn().last()["details"], "From the tab")
        self.assertEqual(h.engine.snapshot()["nowPlaying"]["from"], "browser")
        self.assertEqual(h.engine.showing(PHONE), (False, ""), "another song was chosen: no reason of its own")
        h.clock.advance(1)
        h.engine.inject_test()
        self.assertEqual(h.engine.snapshot()["nowPlaying"]["from"], "test")

    def test_phone_song_follows_the_settings(self):
        h = Harness(config_with_id())
        h.store.update(lambda c: c.__setitem__("privateMode", True))
        h.tracker.put(song(PHONE, "Secret", STATE_PLAYING, 30, 200))
        h.step()
        self.assertEqual(h.conn().last()["details"], build.PRIVATE_DETAILS)
        self.assertNotIn("Secret", str(h.conn().last()), "private mode: no song details")
        h.store.update(lambda c: c.update(privateMode=False, showPaused=False))
        h.clock.advance(3)
        h.tracker.put(song(PHONE, "Secret", STATE_PAUSED, 33, 200))
        h.step()
        self.assertIsNone(h.conn().last(), "paused, and show when paused is off: cleared")
        self.assertEqual(h.engine.showing(PHONE), (False, HIDDEN_PAUSED))
        self.assertEqual(h.engine.snapshot()["hidden"], HIDDEN_PAUSED)
        h.store.update(lambda c: c.__setitem__("enabled", False))
        self.assertEqual(h.engine.showing(PHONE), (False, HIDDEN_OFF))

    def test_clears_when_source_vanishes(self):
        h = Harness(config_with_id())
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        h.clock.advance(SOURCE_TTL + 5)
        h.step()
        self.assertEqual(h.conn().count(), 2)
        self.assertIsNone(h.conn().last())

    def test_falls_back_to_basic_on_api_error(self):
        h = Harness(config_with_id())

        def on_new(c):
            c.fail = lambda a: ipc.APIError(4000, "nope") if a and small(a) else None
        h.on_new = on_new
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        last = h.conn().last()
        self.assertIsNotNone(last)
        self.assertIn("assets", last)
        self.assertEqual(small(last), "")
        self.assertTrue(h.engine.basic)
        st_ = h.engine.snapshot()["discord"]
        self.assertEqual(st_["state"], STATE_CONNECTED)
        self.assertNotIn("lastError", st_)

    def test_api_error_even_in_basic_mode_is_reported_not_retried(self):
        h = Harness(config_with_id())

        def on_new(c):
            c.fail = lambda a: ipc.APIError(4002, "never") if a else None
        h.on_new = on_new
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        snap = h.engine.snapshot()
        self.assertEqual(snap["discord"]["state"], STATE_CONNECTED)
        self.assertEqual(snap["discord"]["lastError"],
                         "Discord would not accept the presence: Discord rejected the request (4002): never")
        self.assertFalse(snap["presenceActive"])
        h.clock.advance(5)
        h.step()
        self.assertEqual(h.dials, 1, "an API error does not drop the connection")

    def test_reconnects(self):
        h = Harness(config_with_id())
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        h.conn().close()            # Discord was closed
        h.clock.advance(1)
        h.step()                    # notices the loss and reconnects at once
        self.assertEqual(h.dials, 2)
        self.assertEqual(h.conn().count(), 1)
        self.assertEqual(h.conn().last()["details"], "One")

    def test_lost_connection_while_sending(self):
        h = Harness(config_with_id())
        h.step()

        def broken(a):
            return OSError("broken pipe")
        h.conn().fail = broken
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        snap = h.engine.snapshot()["discord"]
        self.assertEqual(snap, {"state": STATE_DISCONNECTED, "lastError": "broken pipe"})
        self.assertEqual(h.conns[0].close_count, 1)
        h.step()                    # redials at once
        self.assertEqual(h.dials, 2)
        self.assertEqual(h.conn().last()["details"], "One")

    def test_backs_off_while_discord_is_down(self):
        h = Harness(config_with_id())
        h.dial_err = RuntimeError("Discord is not running")
        h.step()
        h.step()
        self.assertEqual(h.dials, 1, "must not hammer Discord")
        s = h.engine.snapshot()["discord"]
        self.assertEqual(s["state"], STATE_DISCONNECTED)
        self.assertTrue(s["lastError"])
        h.clock.advance(4)
        h.step()
        self.assertEqual(h.dials, 2, "must retry after the backoff")
        h.dial_err = None
        h.clock.advance(20)
        h.step()
        self.assertEqual(h.engine.snapshot()["discord"]["state"], STATE_CONNECTED)

    def test_backoff_doubles_up_to_15s(self):
        h = Harness(config_with_id())
        h.dial_err = RuntimeError("down")
        waits = []
        for _ in range(6):
            h.step()
            waits.append(h.engine.next_dial - h.clock())
            h.clock.t = h.engine.next_dial
        self.assertEqual(waits, [3, 6, 12, 15, 15, 15])

    def test_client_id_change_reconnects(self):
        h = Harness(config_with_id())
        h.step()
        c = config_with_id()
        c["clientId"] = "999999999999999999"
        h.store.set(c)
        h.step()
        self.assertEqual(h.dials, 2)
        self.assertEqual(h.conns[0].close_count, 1)

    def test_inject_test(self):
        h = Harness(config_with_id())
        h.engine.inject_test()
        h.step()
        last = h.conn().last()
        self.assertEqual(last["details"], "Test song")
        self.assertEqual(last["state"], "YTM Starcast self-test")
        self.assertEqual(last["timestamps"], {"start": T0_MS - 30000, "end": T0_MS + 170000})
        self.assertEqual(last["assets"]["large_text"], "If you can see this, Discord works")
        h.clock.advance(TEST_TTL + 3)
        h.step()
        self.assertIsNone(h.conn().last(), "the self-test must disappear on its own")

    def test_master_switch(self):
        h = Harness(config_with_id())
        h.step()
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        self.assertIsNotNone(h.conn().last())
        self.assertTrue(h.engine.snapshot()["presenceActive"])

        c = h.store.get()
        c["enabled"] = False
        h.store.set(c)
        h.clock.advance(5)
        h.tracker.put(song("a", "One", STATE_PLAYING, 15, 200))
        h.step()
        snap = h.engine.snapshot()
        self.assertIsNone(h.conn().last())
        self.assertFalse(snap["presenceActive"])
        self.assertEqual(snap["hidden"], HIDDEN_OFF)
        self.assertIsNotNone(snap["nowPlaying"])

        n = h.conn().count()
        h.clock.advance(5)
        h.tracker.put(song("a", "Two", STATE_PLAYING, 0, 100))
        h.step()
        self.assertEqual(h.conn().count(), n, "nothing may be sent while switched off")

        c["enabled"] = True
        h.store.set(c)
        h.clock.advance(5)
        h.step()
        self.assertEqual(h.conn().last()["details"], "Two")
        self.assertEqual(h.engine.snapshot()["hidden"], "")

    def test_timed_hide(self):
        h = Harness(config_with_id())
        h.step()
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()

        h.engine.hide_for(30 * 60)
        h.clock.advance(5)
        h.tracker.put(song("a", "One", STATE_PLAYING, 15, 200))
        h.step()
        snap = h.engine.snapshot()
        self.assertIsNone(h.conn().last())
        self.assertEqual(snap["hidden"], HIDDEN_SNOOZED)
        self.assertTrue(29 * 60 <= snap["snoozeSec"] <= 30 * 60, snap["snoozeSec"])

        h.clock.advance(31 * 60)        # ends by itself
        h.tracker.put(song("a", "One", STATE_PLAYING, 31 * 60, 3600))
        h.step()
        self.assertIsNotNone(h.conn().last())
        self.assertEqual(h.engine.snapshot()["snoozeSec"], 0)

        h.engine.hide_for(3600)         # and can be ended early
        h.clock.advance(5)
        h.tracker.put(song("a", "One", STATE_PLAYING, 31 * 60 + 5, 3600))
        h.step()
        self.assertIsNone(h.conn().last())
        h.engine.cancel_hide()
        h.clock.advance(5)
        h.step()
        self.assertIsNotNone(h.conn().last())

    def test_snooze_rounds_half_away_from_zero(self):
        h = Harness(config_with_id())
        h.engine.hide_for(10.5)
        self.assertEqual(h.engine.snapshot()["snoozeSec"], 11)
        h.engine.hide_for(2.4)
        self.assertEqual(h.engine.snapshot()["snoozeSec"], 2)

    def test_snapshot_explains_settings_hiding(self):
        cfg = config_with_id()
        cfg["hideMatching"] = ["artist"]
        h = Harness(cfg)
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        self.assertEqual(h.engine.snapshot()["hidden"], HIDDEN_FILTERED)
        cfg["hideMatching"] = None
        cfg["showPaused"] = False
        h.store.set(cfg)
        h.tracker.put(song("a", "One", STATE_PAUSED, 10, 200))
        self.assertEqual(h.engine.snapshot()["hidden"], HIDDEN_PAUSED)

    def test_throttled_background_tab_does_not_flicker(self):
        cfg = config_with_id()
        cfg["pauseTimeoutMinutes"] = 30
        h = Harness(cfg)
        h.step()
        h.tracker.put(song("a", "Paused Song", STATE_PAUSED, 40, 200))
        h.step()
        self.assertIsNotNone(h.conn().last())
        n = h.conn().count()
        for minute in range(3):         # one report a minute, with a little jitter
            for _ in range(0, 62, 2):
                h.clock.advance(2)
                h.step()
            self.assertEqual(h.conn().count(), n, "minute %d: the card was cleared between reports" % (minute + 1))
            self.assertIsNotNone(h.conn().last())
            h.tracker.put(song("a", "Paused Song", STATE_PAUSED, 40, 200))
            h.step()
        h.clock.advance(quiet_after() + 5)
        h.step()
        self.assertIsNone(h.conn().last(), "a source that stopped reporting for good still clears the card")

    def test_retries_full_card_after_reconnect(self):
        h = Harness(config_with_id())
        state = {"reject": True}

        def on_new(c):
            c.fail = lambda a: ipc.APIError(4000, "nope") if state["reject"] and a and small(a) else None
        h.on_new = on_new
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        self.assertTrue(h.engine.basic)
        state["reject"] = False
        h.conn().close()
        h.clock.advance(1)
        h.step()
        self.assertFalse(h.engine.basic, "a new connection must try the full card again")
        self.assertTrue(small(h.conn().last()))

    def test_shutdown_clears_and_closes(self):
        h = Harness(config_with_id())
        h.tracker.put(song("a", "One", STATE_PLAYING, 10, 200))
        h.step()
        c = h.conn()
        h.engine.shutdown()
        self.assertIsNone(c.last())
        self.assertTrue(c.closed)
        self.assertIsNone(h.engine.conn)
        self.assertFalse(h.engine.snapshot()["presenceActive"])

    def test_env_overrides_client_id(self):
        h = Harness(config_with_id())
        seen = []
        h.engine.dial = lambda cid: seen.append(cid) or FakeConn()
        os.environ["YTM_BEACON_CLIENT_ID"] = "333333333333333333"      # restored by the env patch
        h.step()
        self.assertEqual(seen, ["333333333333333333"])


if __name__ == "__main__":
    unittest.main()
