import json
import unittest
from urllib.parse import parse_qs, urlsplit

from pocketfader.lyrics import LyricsService, _norm, _score, parse_lrc
from pocketfader.romanize import romanize

from .test_server import Base

LRC = "[ar:Someone]\n[00:01.50]Hello\n[00:05.00]World\n[01:02.5][02:00.00]Again\n[03:00.00]\n"


class FakeNet:
    """Stands in for the internet: serves canned JSON by URL and records every call."""

    def __init__(self, lrclib=None, netease=None):
        self.calls = []
        self.lrclib = lrclib
        self.netease = netease

    def __call__(self, url, headers=None, timeout=6.0):
        self.calls.append(url)
        u = urlsplit(url)
        if u.netloc == "lrclib.net" and u.path == "/api/get":
            return json.dumps(self.lrclib) if self.lrclib else None
        if u.netloc == "lrclib.net":
            return "[]"
        if u.path == "/api/search/get":
            if not self.netease:
                return json.dumps({"result": {"songs": []}})
            return json.dumps({"result": {"songs": [{"id": 7, "name": self.netease["name"],
                                                     "artists": [{"name": "Artist"}], "duration": 200000}]}})
        if u.path == "/api/song/lyric":
            assert parse_qs(u.query)["id"] == ["7"]
            return json.dumps(self.netease["lyric"])
        return None


class RomanizeTests(unittest.TestCase):
    def test_korean(self):
        self.assertEqual(romanize("사랑해"), "saranghae")
        self.assertEqual(romanize("안녕하세요"), "annyeonghaseyo")
        self.assertEqual(romanize("있어"), "isseo")        # a final consonant moves onto a vowel-initial syllable

    def test_japanese_kana(self):
        self.assertEqual(romanize("ありがとう"), "arigatou")
        self.assertEqual(romanize("がっこう"), "gakkou")
        self.assertEqual(romanize("ラーメン"), "raamen")
        self.assertEqual(romanize("きゃく"), "kyaku")

    def test_cyrillic_and_greek(self):
        self.assertEqual(romanize("Привет мир"), "Privet mir")
        self.assertEqual(romanize("αγάπη"), "agapi")

    def test_latin_and_han_are_left_alone(self):
        self.assertIsNone(romanize("Hello, world"))
        self.assertIsNone(romanize("夢"))
        self.assertIsNone(romanize(""))


class LrcTests(unittest.TestCase):
    def test_parse_handles_tags_multiple_stamps_and_order(self):
        got = parse_lrc(LRC)
        self.assertEqual([t for t, _ in got], [1.5, 5.0, 62.5, 120.0, 180.0])
        self.assertEqual(got[0][1], "Hello")
        self.assertEqual(got[3][1], "Again")
        self.assertEqual(got[4][1], "")                      # blank lines are kept as gaps

    def test_word_level_stamps_are_dropped(self):
        self.assertEqual(parse_lrc("[00:01.00]<00:01.00>Hel<00:01.40>lo")[0][1], "Hello")

    def test_title_matching(self):
        self.assertEqual(_norm("Song (feat. Someone) [Remastered]"), "song")
        self.assertGreater(_score("Song", ["A"], 200, "Song", "A", 201), _score("Song", ["B"], 200, "Song", "A", 201))
        self.assertLess(_score("Other", ["A"], 200, "Song", "A", 200), 0)


class ServiceTests(unittest.TestCase):
    def test_prefers_synced_and_lists_every_source(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi", "plainLyrics": "Hi"},
                      netease={"name": "Song", "lyric": {"lrc": {"lyric": "[00:01.00]你好"}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertTrue(r["found"])
        self.assertEqual(r["source"], "lrclib")
        self.assertEqual({s["id"]: s["found"] for s in r["sources"]}, {"lrclib": True, "netease": True, "ytm": False})
        self.assertEqual(r["lyrics"]["lines"][0], {"t": 1.0, "text": "Hi"})

    def test_switching_source_uses_the_cache_and_refresh_refetches(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"},
                      netease={"name": "Song", "lyric": {"lrc": {"lyric": "[00:01.00]Yo"}}})
        svc = LyricsService(fetch=net)
        svc.lookup("Song", "Artist", "", 200)
        n = len(net.calls)
        other = svc.lookup("Song", "Artist", "", 200, source="netease")
        self.assertEqual(other["source"], "netease")
        self.assertEqual(len(net.calls), n)                  # no new requests
        svc.lookup("Song", "Artist", "", 200, refresh=True)
        self.assertGreater(len(net.calls), n)

    def test_translation_and_provider_romanization_are_borrowed(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]夢ならば"},
                      netease={"name": "Song", "lyric": {"lrc": {"lyric": "[00:01.10]夢ならば"},
                                                         "tlyric": {"lyric": "[00:01.10]If only"},
                                                         "romalrc": {"lyric": "[00:01.10]yume naraba"}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        line = r["lyrics"]["lines"][0]
        self.assertEqual(r["source"], "lrclib")
        self.assertEqual((line["tr"], line["rom"]), ("If only", "yume naraba"))
        self.assertNotIn("_d", line)

    def test_kana_lines_get_a_derived_romanization_but_kanji_lines_do_not(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]ありがとう\n[00:03.00]夢をみる"})
        lines = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)["lyrics"]["lines"]
        self.assertEqual(lines[0]["rom"], "arigatou")
        self.assertNotIn("rom", lines[1])

    def test_plain_text_fallback_and_nothing_found(self):
        svc = LyricsService(fetch=FakeNet(lrclib={"plainLyrics": "a\nb"}))
        r = svc.lookup("Song", "Artist", "", 200)
        self.assertFalse(r["lyrics"]["synced"])
        self.assertEqual([ln["t"] for ln in r["lyrics"]["lines"]], [None, None])
        none = LyricsService(fetch=FakeNet()).lookup("Nope", "Nobody", "", 100)
        self.assertEqual((none["found"], none["lyrics"]), (False, None))

    def test_extension_provider(self):
        svc = LyricsService(fetch=FakeNet(), ytm_lyrics=lambda vid: {"text": "la la\nlo", "credit": "Source: X"})
        r = svc.lookup("Song", "Artist", "", 100, video_id="abc")
        self.assertEqual((r["source"], r["lyrics"]["credit"]), ("ytm", "Source: X"))

    def test_a_failing_provider_does_not_break_the_others(self):
        def flaky(url, headers=None, timeout=6.0):
            if "lrclib" in url:
                raise RuntimeError("boom")
            return None
        self.assertFalse(LyricsService(fetch=flaky).lookup("Song", "A", "", 100)["found"])


class LyricsApiTests(Base):
    def setUp(self):
        super().setUp()
        self.app.lyrics = LyricsService(fetch=FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"}))

    def test_endpoint(self):
        st, body, _ = self.req("GET", "/api/lyrics?title=Song&artist=Artist&duration=200")
        self.assertEqual(st, 200)
        self.assertEqual(body["source"], "lrclib")
        self.assertTrue(self.req("GET", "/api/state")[1]["capabilities"]["lyrics"])

    def test_validation(self):
        self.assertEqual(self.req("GET", "/api/lyrics")[0], 400)
        self.assertEqual(self.req("GET", "/api/lyrics?title=x&duration=abc")[0], 400)
        self.assertEqual(self.req("GET", "/api/lyrics?title=" + "x" * 400)[0], 400)


class LyricsAuthTests(Base):
    token = "s3cret"

    def test_requires_the_token(self):
        self.assertEqual(self.req("GET", "/api/lyrics?title=Song")[0], 401)


if __name__ == "__main__":
    unittest.main()
