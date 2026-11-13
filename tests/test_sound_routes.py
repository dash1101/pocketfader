"""The computer's sound in Refine's panel (/ext/sound...): the same volume, outputs and EQ as the remote's mixer,
only while the "Computer audio" part is on. Everything here runs against the fakes (no real audio)."""
import unittest

from .fakes import FakeToggleEQ
from .test_server import Base, setUpModule, tearDownModule  # noqa: F401  (the same guards as the server tests)

YTM = "https://music.youtube.com"


class SoundRouteTests(Base):
    engine_cls = FakeToggleEQ     # like the built-in EQ: an on/off switch and presets
    def test_reads_what_the_mixer_shows(self):
        code, st, _ = self.req("GET", "/ext/sound")
        self.assertEqual(code, 200)
        self.assertTrue(st["audio"])
        self.assertEqual(st["volume"]["percent"], 50)
        self.assertEqual({o["name"] for o in st["outputs"]}, {"speakers", "headset"})
        self.assertTrue(st["capabilities"]["eq"])
        self.assertEqual(st["limits"], {"eq_min": -12.0, "eq_max": 12.0})

    def test_changes_with_youtube_musics_origin(self):
        h = {"Origin": YTM}
        self.assertEqual(self.req("POST", "/ext/sound/volume", {"percent": 30}, headers=h)[0], 200)
        self.assertEqual(self.audio.state["percent"], 30)
        self.assertEqual(self.req("POST", "/ext/sound/eq", {"band": "bass", "db": 4}, headers=h)[0], 200)
        self.assertEqual(self.engine.eq["bass"], 4)
        self.assertEqual(self.req("POST", "/ext/sound/output", {"name": "headset"}, headers=h)[0], 200)
        self.assertEqual(self.audio.default, "headset")
        self.assertEqual(self.req("POST", "/ext/sound/output/volume", {"name": "headset", "percent": 10}, headers=h)[0], 200)
        self.assertEqual(self.audio.levels["headset"], 10)

    def test_same_checks_as_the_mixer(self):
        h = {"Origin": YTM}
        self.assertEqual(self.req("POST", "/ext/sound/volume", {"percent": 300}, headers=h)[0], 400)
        self.assertEqual(self.req("POST", "/ext/sound/eq", {"band": "sub", "db": 1}, headers=h)[0], 400)
        self.assertEqual(self.req("POST", "/ext/sound/eq", {"band": "bass", "db": 40}, headers=h)[0], 400)
        self.assertEqual(self.req("POST", "/ext/sound/output", {"name": "nowhere"}, headers=h)[0], 404)
        self.assertEqual(self.req("POST", "/ext/sound/mute", {}, headers=h)[0], 404)
        self.assertNotEqual(self.req("POST", "/ext/sound/volume", {"percent": 1}, headers={"Origin": "https://evil.example"})[0], 200)
        self.assertEqual(self.audio.state["percent"], 50)

    def test_nothing_while_computer_audio_is_off(self):
        self.req("POST", "/api/parts", {"audio": False})
        self.assertEqual(self.req("GET", "/ext/sound")[1], {"audio": False})
        code, r, _ = self.req("POST", "/ext/sound/volume", {"percent": 10}, headers={"Origin": YTM})
        self.assertEqual((code, r["error"]), (409, "part_off"))
        self.assertEqual(self.audio.state["percent"], 50)
        # the extension can't switch it back on
        self.assertEqual(self.req("POST", "/ext/parts", {"audio": True}, headers={"Origin": YTM})[0], 400)

    def test_eq_switch_and_presets(self):
        h = {"Origin": YTM}
        self.assertEqual(self.req("POST", "/ext/sound/eq", {"enabled": True}, headers=h)[0], 200)
        self.assertTrue(self.engine.enabled)
        self.assertEqual(self.req("POST", "/ext/sound/eq", {"preset": "bass-boost"}, headers=h)[0], 200)
        st = self.req("GET", "/ext/sound")[1]
        self.assertEqual((st["engine"]["enabled"], st["engine"]["presets"]), (True, ["bass-boost"]))
        self.assertTrue(st["capabilities"]["eq_toggle"])


if __name__ == "__main__":
    unittest.main()
