import json
import unittest
from urllib.parse import parse_qs, urlsplit

import base64
import time
import zlib

from starcast.lyrics import _KRC_KEY, LyricsService, _norm, _score, lrc_words, parse_krc, parse_lrc, parse_yrc
from starcast.romanize import romanize

from .test_server import Base

LRC = "[ar:Someone]\n[00:01.50]Hello\n[00:05.00]World\n[01:02.5][02:00.00]Again\n[03:00.00]\n"


class FakeNet:
    """Stands in for the internet: serves canned JSON by URL and records every call."""

    def __init__(self, lrclib=None, netease=None, krc=None):
        self.calls = []
        self.krc = krc
        self.lrclib = lrclib
        self.netease = netease

    def __call__(self, url, headers=None, timeout=6.0):
        self.calls.append(url)
        u = urlsplit(url)
        if u.netloc == "mobileservice.kugou.com":
            if not self.krc:
                return json.dumps({"data": {"info": []}})
            return json.dumps({"data": {"info": [{"songname": "Song", "singername": "Artist", "duration": 200, "hash": "h"}]}})
        if u.netloc == "lyrics.kugou.com" and u.path == "/search":
            return json.dumps({"candidates": [{"id": "1", "accesskey": "k"}]})
        if u.netloc == "lyrics.kugou.com" and u.path == "/download":
            raw = zlib.compress(self.krc.encode())
            blob = b"krc1" + bytes(b ^ _KRC_KEY[i % 16] for i, b in enumerate(raw))
            return json.dumps({"content": base64.b64encode(blob).decode()})
        if u.netloc == "lrclib.net" and u.path == "/api/get":
            return json.dumps(self.lrclib) if self.lrclib else None
        if u.netloc == "lrclib.net":
            return "[]"
        if u.path == "/api/search/get":
            if not self.netease:
                return json.dumps({"result": {"songs": []}})
            return json.dumps({"result": {"songs": [{"id": 7, "name": self.netease["name"],
                                                     "artists": [{"name": "Artist"}], "duration": 200000}]}})
        if u.path in ("/api/song/lyric", "/api/song/lyric/v1"):
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
        self.assertEqual({s["id"]: s["found"] for s in r["sources"]}, {"kugou": False, "lrclib": True, "netease": True, "ytm": False})
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


KRC = "[id:x]\n[ti:Song]\n[1000,2000]<0,500,0>Hel<500,700,0>lo <1200,800,0>there\n[20000,1000]<0,1000,0>Again\n"


class WordSyncTests(unittest.TestCase):
    def test_parse_krc_words_and_gaps(self):
        lines = parse_krc(KRC)
        self.assertEqual(lines[0], {"t": 1.0, "text": "Hello there", "w": [[1.0, 1.5, "Hel"], [1.5, 2.2, "lo "], [2.2, 3.0, "there"]]})
        self.assertEqual(lines[1], {"t": 3.5, "text": ""})                  # a ♪ line for the long instrumental break
        self.assertEqual(lines[2]["t"], 20.0)

    def test_kugou_is_preferred_when_it_has_words(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hello there"}, krc=KRC)
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["source"], "kugou")
        self.assertTrue(r["sources"][0]["words"])
        self.assertIn("w", r["lyrics"]["lines"][0])

    def test_words_are_lent_to_a_line_synced_source_when_the_text_matches(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hello there"}, krc=KRC)
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200, source="lrclib")
        self.assertEqual(r["source"], "lrclib")
        self.assertEqual(len(r["lyrics"]["lines"][0]["w"]), 3)

    def test_answers_before_slow_sources_finish_and_flags_them_pending(self):
        def slow(url, headers=None, timeout=6.0):
            if "netease" in url or "163.com" in url:
                time.sleep(1.5)
                return None
            return FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"})(url, headers, timeout)
        svc = LyricsService(fetch=slow, grace=0.2)
        t0 = time.time()
        r = svc.lookup("Song", "Artist", "", 200)
        self.assertLess(time.time() - t0, 1.2)
        self.assertEqual(r["source"], "lrclib")
        self.assertTrue(r["pending"])

    def test_prefetch_warms_the_cache(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"})
        svc = LyricsService(fetch=net, grace=0.2)
        svc.prefetch("Song", "Artist", "", 200)
        time.sleep(0.8)
        n = len(net.calls)
        t0 = time.time()
        r = svc.lookup("Song", "Artist", "", 200)
        self.assertEqual(len(net.calls), n)
        self.assertLess(time.time() - t0, 0.3)
        self.assertTrue(r["found"])


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

    def setUp(self):
        super().setUp()
        self.app.lyrics = LyricsService(fetch=FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"}))

    def test_requires_the_token(self):
        self.assertEqual(self.req("GET", "/api/lyrics?title=Song")[0], 401)

    def test_extension_route(self):
        # the extension on this computer (live lyrics inside YouTube Music) has no token, like its other routes
        st, body, _ = self.req("GET", "/ext/lyrics?title=Song&artist=Artist&duration=200", headers={"Origin": "https://music.youtube.com"})
        self.assertEqual(st, 200)
        self.assertEqual(body["source"], "lrclib")
        # ...but not when the request came through a proxy or tunnel
        self.assertEqual(self.req("GET", "/ext/lyrics?title=Song", headers={"X-Forwarded-For": "1.2.3.4"})[0], 401)
        self.assertEqual(self.req("GET", "/ext/lyrics")[0], 400)


if __name__ == "__main__":
    unittest.main()


YRC = ('{"t":0,"c":[{"tx":"作词: "},{"tx":"Someone"}]}\n'
       "[14100,3570](14100,420,0)White (14520,480,0)shirt(15000,240,0), (15240,480,0)now\n"
       "[30000,1000](30000,1000,0)Later\n")


class MoreWordSourcesTests(unittest.TestCase):
    def test_parse_yrc_skips_credits_and_marks_gaps(self):
        lines = parse_yrc(YRC)
        self.assertEqual(lines[0], {"t": 0.5, "text": ""})                  # the long intro before the singing
        self.assertEqual(lines[1]["text"], "White shirt, now")
        self.assertEqual(lines[1]["w"][0], [14.1, 14.52, "White "])
        self.assertEqual(lines[-1]["t"], 30.0)

    def test_netease_word_by_word_with_its_translation(self):
        net = FakeNet(netease={"name": "Song", "lyric": {"lrc": {"lyric": "[00:14.10]White shirt, now"}, "yrc": {"lyric": YRC},
                                                         "tlyric": {"lyric": "[00:14.10]Camisa blanca"}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["source"], "netease")
        line = next(ln for ln in r["lyrics"]["lines"] if ln["text"])
        self.assertEqual((len(line["w"]), line["tr"]), (4, "Camisa blanca"))   # the comma is timed on its own

    def test_enhanced_lrc_keeps_its_word_timings(self):
        text = "[00:01.00] <00:01.00>Hel <00:01.50>lo <00:02.00>you\n[00:04.00]Next"
        self.assertEqual(lrc_words(text), {1.0: [[1.0, 1.5, "Hel "], [1.5, 2.0, "lo "], [2.0, 4.0, "you"]]})
        r = LyricsService(fetch=FakeNet(lrclib={"syncedLyrics": text})).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["lyrics"]["lines"][0]["text"], "Hel lo you")
        self.assertEqual(len(r["lyrics"]["lines"][0]["w"]), 3)
        self.assertNotIn("w", r["lyrics"]["lines"][1])

    def test_credit_lines_are_not_lyrics(self):
        net = FakeNet(netease={"name": "Song", "lyric": {"lrc": {"lyric": "[00:00.00]作词 : Someone\n[00:00.50]Composer: X\n[00:01.00]Real line"}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200, source="netease")
        self.assertEqual([ln["text"] for ln in r["lyrics"]["lines"]], ["Real line"])

    def test_kugou_instrumental_placeholder_is_nothing(self):
        net = FakeNet(lrclib={"syncedLyrics": "[00:01.00]Hi"}, krc="[1000,2000]<0,500,0>纯音乐，请欣赏\n")
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertFalse(next(s for s in r["sources"] if s["id"] == "kugou")["found"])
        self.assertEqual(r["source"], "lrclib")

    def test_a_word_source_with_far_fewer_lines_does_not_win(self):
        many = "\n".join("[00:%02d.00]Line %d" % (i, i) for i in range(1, 21))
        net = FakeNet(lrclib={"syncedLyrics": many}, krc="[1000,2000]<0,500,0>Line <500,500,0>1\n")
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["source"], "lrclib")


SONG_A = ["Shine a light on me tonight", "Every river runs to sea", "Hold the line until the morning", "We are golden, we are free"]
SONG_B = ["Paper planes across the city", "Coffee cold and running late", "Tell me why the rain is pretty", "Nothing ever seems to wait"]


def lrc(lines, step=10.0, start=5.0):
    return "\n".join("[%02d:%05.2f]%s" % (int((start + i * step) // 60), (start + i * step) % 60, t) for i, t in enumerate(lines))


def krc(lines, step=10000, start=5000):
    out = []
    for i, t in enumerate(lines):
        words = t.split(" ")
        out.append("[%d,%d]" % (start + i * step, 4000) + "".join("<%d,400,0>%s " % (k * 400, w) for k, w in enumerate(words)))
    return "\n".join(out)


class WrongSongTests(unittest.TestCase):
    """Searches go by title: a source can find another song of the same name. Its word timings must not win."""

    def test_another_song_with_word_timings_loses_to_two_that_agree(self):
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A)}, krc=krc(SONG_B),
                      netease={"name": "Song", "lyric": {"lrc": {"lyric": lrc(SONG_A, start=5.2)}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertIn(r["source"], ("lrclib", "netease"))
        self.assertTrue(r["agreed"])
        self.assertEqual(r["lyrics"]["lines"][0]["text"], SONG_A[0])

    def test_youtube_musics_own_lyrics_settle_it(self):
        ytm = lambda vid: {"text": "\n".join(SONG_A)}
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A)}, krc=krc(SONG_B))
        r = LyricsService(fetch=net, ytm_lyrics=ytm).lookup("Song", "Artist", "", 200, video_id="dQw4w9WgXcQ")
        self.assertEqual(r["source"], "lrclib")
        self.assertTrue(r["agreed"])

    def test_the_right_words_with_timings_still_win(self):
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A)}, krc=krc(SONG_A))
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["source"], "kugou")
        self.assertTrue(r["agreed"])

    def test_lyrics_that_run_past_the_end_are_another_recording(self):
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A, step=10)}, krc=krc(SONG_A, step=60000))
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 60)
        self.assertEqual(r["source"], "lrclib")

    def test_nothing_is_lent_from_another_song(self):
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A)},
                      netease={"name": "Song", "lyric": {"lrc": {"lyric": lrc(SONG_B)}, "tlyric": {"lyric": lrc(["Traducción"] * 4)}}})
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200, source="lrclib")
        self.assertFalse(any("tr" in ln for ln in r["lyrics"]["lines"]))
        self.assertFalse(r["agreed"])

    def test_stricter_search_matches(self):
        self.assertLess(_score("Song", ["Somebody Else"], 200, "Song", "Artist", 200), 0, "another artist")
        self.assertLess(_score("Song", ["Artist"], 260, "Song", "Artist", 200), 0, "a minute longer: another recording")
        self.assertLess(_score("Love", ["Artist"], 200, "Love Story Forever", "Artist", 200), 0, "a word of the title")
        self.assertGreater(_score("Song (Remastered)", ["Artist"], 201, "Song", "Artist", 200), 1)

    def test_two_that_disagree_the_surer_search_wins(self):
        # LRCLIB found it exactly (title, artist and length); KuGou only by searching: its other song loses
        net = FakeNet(lrclib={"syncedLyrics": lrc(SONG_A)}, krc=krc(SONG_B))
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200)
        self.assertEqual(r["source"], "lrclib")
        self.assertFalse(r["agreed"])

    def test_kugous_title_line_is_not_a_lyric(self):
        net = FakeNet(krc="[0,3000]<0,1000,0>Song <1000,1000,0>- <2000,1000,0>Artist\n" + krc(SONG_A))
        r = LyricsService(fetch=net).lookup("Song", "Artist", "", 200, source="kugou")
        self.assertEqual(next(ln["text"] for ln in r["lyrics"]["lines"] if ln["text"]), SONG_A[0])
