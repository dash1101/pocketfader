"""Discord status routes added for the extension's popup and Refine's panel (/ext/discord...), the card preview in
GET /api/beacon, POST /api/beacon/test, and a phone's song (POST /api/phone/playing). The built-in engine runs as a
trial run (never contacts Discord)."""
import time
import unittest

from starcast.server import EXTENSION_ORIGIN

from .test_server import Base, setUpModule, tearDownModule  # noqa: F401  (the same guards as the server tests)

YTM = "https://music.youtube.com"
PHONE = "pixel-7a_01"


def phone_body(title="Phone song", playing=True, device=PHONE, **kw):
    now = {"playing": playing, "title": title, "artist": "Artist", "album": "Album",
           "art": "https://lh3.googleusercontent.com/p=w544-h544", "videoId": "dQw4w9WgXcQ",
           "position": 30, "duration": 200, "speed": 1.0}
    now.update(kw)
    return {"device": device, "now": now, "ts": int(time.time() * 1000)}


class DiscordRouteTests(Base):
    def test_status_carries_the_preview(self):
        code, st, _ = self.req("GET", "/api/beacon")
        self.assertEqual(code, 200)
        self.assertIn("preview", st)                 # None while nothing plays
        self.assertIsNone(st["preview"])

    def test_the_extension_reads_the_same_status(self):
        code, st, _ = self.req("GET", "/ext/discord")
        self.assertEqual(code, 200)
        _, api, _ = self.req("GET", "/api/beacon")
        self.assertEqual(st["settings"], api["settings"])
        self.assertIn("preview", st)

    def test_the_extension_changes_settings_with_youtube_musics_origin(self):
        code, r, _ = self.req("POST", "/ext/discord/settings", {"showButtons": False}, headers={"Origin": YTM})
        self.assertEqual(code, 200, r)
        self.assertFalse(r["showButtons"])
        _, st, _ = self.req("GET", "/api/beacon")
        self.assertFalse(st["settings"]["showButtons"])

    def test_other_origins_are_refused(self):
        code, _, _ = self.req("POST", "/ext/discord/settings", {"privateMode": False}, headers={"Origin": "https://evil.example"})
        self.assertNotEqual(code, 200)

    def test_bad_values_are_refused(self):
        code, _, _ = self.req("POST", "/ext/discord/settings", {"clientId": "123"}, headers={"Origin": YTM})
        self.assertEqual(code, 400)
        code, _, _ = self.req("POST", "/ext/discord/visibility", {"action": "explode"}, headers={"Origin": YTM})
        self.assertEqual(code, 400)

    def test_visibility_from_the_extension(self):
        code, r, _ = self.req("POST", "/ext/discord/visibility", {"action": "hide", "minutes": 30}, headers={"Origin": YTM})
        self.assertEqual(code, 200, r)
        self.assertFalse(r["visible"])
        self.assertGreater(r["snoozeSec"], 0)

    def test_test_song_in_a_trial_run_sends_nothing(self):
        code, r, _ = self.req("POST", "/api/beacon/test", {})
        self.assertEqual(code, 200, r)
        self.assertTrue(r.get("dryRun"))
        code, r, _ = self.req("POST", "/ext/discord/test", {}, headers={"Origin": YTM})
        self.assertEqual(code, 200, r)

    def test_unknown_extension_route(self):
        code, _, _ = self.req("POST", "/ext/discord/quit", {}, headers={"Origin": YTM})
        self.assertEqual(code, 404)


class PhoneRouteTests(Base):
    """POST /api/phone/playing: the Android app playing music on the phone itself reports its song."""

    def status(self):
        code, st, _ = self.req("GET", "/api/beacon")
        self.assertEqual(code, 200)
        return st

    def test_the_phones_song_shows(self):
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())
        self.assertEqual((code, r), (200, {"ok": True, "shown": True, "why": "", "every": 30}))
        st = self.status()
        self.assertEqual((st["nowPlaying"]["title"], st["nowPlaying"]["from"]), ("Phone song", "phone"))
        self.assertEqual(st["nowPlaying"]["state"], "playing")
        self.assertEqual(st["preview"]["details"], "Phone song")
        self.assertIsNone(st["extension"]["lastSeenAgoSec"], "a phone is not the browser extension")
        self.assertNotIn(PHONE, str(st), "the phone's id is not passed on")
        code, ext, _ = self.req("GET", "/ext/discord")
        self.assertEqual(ext["nowPlaying"]["from"], "phone")

    def test_null_clears_at_once(self):
        self.req("POST", "/api/phone/playing", phone_body())
        code, r, _ = self.req("POST", "/api/phone/playing", {"device": PHONE, "now": None})
        self.assertEqual((code, r), (200, {"ok": True, "shown": False, "why": "", "every": 30}))
        self.assertIsNone(self.status()["nowPlaying"])
        self.assertIsNone(self.status()["preview"])

    def test_paused_and_hidden_reasons(self):
        self.req("POST", "/api/beacon/settings", {"showPaused": False})
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body(playing=False))
        self.assertEqual((code, r["shown"], r["why"]), (200, False, "paused"))
        self.req("POST", "/api/beacon/settings", {"showPaused": True})
        self.req("POST", "/api/beacon/visibility", {"action": "hide", "minutes": 30})
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body(playing=False))
        self.assertEqual((code, r["shown"], r["why"]), (200, False, "snoozed"))

    def test_bad_reports_are_refused(self):
        good = phone_body()
        bad = [
            {**good, "device": "abc"}, {**good, "device": "x" * 33}, {**good, "device": "has space"},
            {**good, "device": "phone:abcdef"}, {**good, "device": 1234567}, {"now": good["now"]},
            {"device": PHONE, "ts": good["ts"]},
            {**good, "now": "Phone song"}, {**good, "now": [1]},
            {**good, "now": {**good["now"], "title": None}}, {**good, "now": {**good["now"], "title": "   "}},
            {**good, "now": {k: v for k, v in good["now"].items() if k != "title"}},
            {**good, "now": {**good["now"], "playing": "yes"}},
        ]
        for body in bad:
            code, r, _ = self.req("POST", "/api/phone/playing", body)
            self.assertEqual(code, 400, body)
            self.assertIsInstance(r.get("error"), str, body)
        self.assertIsNone(self.status()["nowPlaying"], "nothing was recorded")
        # a ts that isn't a number is only left out
        code, _, _ = self.req("POST", "/api/phone/playing", {**good, "ts": "soon"})
        self.assertEqual(code, 200)

    def test_discord_switched_off_keeps_nothing(self):
        self.req("POST", "/api/phone/playing", phone_body(title="Before"))
        self.assertEqual(self.req("POST", "/api/parts", {"discord": False})[0], 200)
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())
        self.assertEqual((code, r), (200, {"ok": True, "shown": False, "why": "off", "every": 30}))
        self.assertIsNone(self.status()["nowPlaying"], "nothing recorded, and the earlier song is gone")
        code, r, _ = self.req("POST", "/api/phone/playing", {"device": PHONE, "now": None})
        self.assertEqual((code, r["why"]), (200, "off"))
        self.req("POST", "/api/parts", {"discord": True})
        self.assertIsNone(self.status()["nowPlaying"], "switched back on: nothing old comes back")
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())
        self.assertEqual((code, r["shown"]), (200, True))

    def test_the_extension_cannot_speak_for_a_phone(self):
        self.req("POST", "/api/phone/playing", phone_body())
        h = {"Origin": EXTENSION_ORIGIN}
        # a first poll with a phone's name (answered at once: a tab the server hasn't heard from is asked to resend)
        self.assertEqual(self.req("GET", "/ext/poll?i=phone:" + PHONE)[1].get("resend"), True)
        for inst in ("phone:" + PHONE, "  phone:" + PHONE):
            code, r, _ = self.req("POST", "/ext/state", {"instance": inst, "now": {"title": "Fake", "playing": True}},
                                  headers=h)
            self.assertEqual((code, r.get("error")), (400, "instance must be the tab's id"), inst)
            self.assertEqual(self.req("POST", "/ext/bye", {"instance": inst}, headers=h)[0], 400, inst)
        self.assertNotIn("Fake", str(self.req("GET", "/api/ytm/state")[1]), "the remote didn't take that state either")
        st = self.status()
        self.assertEqual((st["nowPlaying"]["title"], st["nowPlaying"]["from"]), ("Phone song", "phone"))
        self.assertIsNone(st["extension"]["lastSeenAgoSec"])
        # ...and the extension's origin is only for the extension's routes
        self.assertEqual(self.req("POST", "/api/phone/playing", phone_body(), headers=h)[0], 403)

    def test_a_tab_and_the_phone(self):
        self.req("POST", "/api/phone/playing", phone_body())
        time.sleep(0.01)
        self.req("POST", "/ext/state", {"instance": "tab-1", "now": {"title": "Tab song", "playing": True}})
        self.assertEqual(self.status()["nowPlaying"]["from"], "browser", "play pressed in the tab last")
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())     # a heartbeat changes nothing
        self.assertEqual((code, r["shown"], r["why"]), (200, False, ""))
        self.assertEqual(self.status()["nowPlaying"]["title"], "Tab song")
        self.req("POST", "/ext/state", {"instance": "tab-1", "now": {"title": "Tab song", "playing": False}})
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())
        self.assertEqual((code, r["shown"]), (200, True), "a playing phone beats a paused tab")

    def test_foreign_origin_refused(self):
        code, _, _ = self.req("POST", "/api/phone/playing", phone_body(), headers={"Origin": "https://evil.example"})
        self.assertEqual(code, 403)
        self.assertIsNone(self.status()["nowPlaying"])
        host = f"127.0.0.1:{self.port}"
        self.assertEqual(self.req("POST", "/api/phone/playing", phone_body(), headers={"Origin": f"http://{host}"})[0], 200)


class PhoneRouteTokenTests(Base):
    token = "s3cret-token-value-123"

    def test_token_required(self):
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body())
        self.assertEqual((code, r), (401, {"error": "token required"}))
        auth = {"Authorization": "Bearer " + self.token}
        _, st, _ = self.req("GET", "/api/beacon", headers=auth)
        self.assertIsNone(st["nowPlaying"], "nothing recorded without the token")
        code, r, _ = self.req("POST", "/api/phone/playing", phone_body(), headers=auth)
        self.assertEqual((code, r["shown"]), (200, True))
        self.assertEqual(self.req("POST", "/api/phone/playing", phone_body(), headers={"Authorization": "Bearer no"})[0], 401)


if __name__ == "__main__":
    unittest.main()
