"""Discord status routes added for the extension's popup and Refine's panel (/ext/discord...), the card preview in
GET /api/beacon, and POST /api/beacon/test. The built-in engine runs as a trial run (never contacts Discord)."""
import unittest

from .test_server import Base, setUpModule, tearDownModule  # noqa: F401  (the same guards as the server tests)

YTM = "https://music.youtube.com"


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


if __name__ == "__main__":
    unittest.main()
