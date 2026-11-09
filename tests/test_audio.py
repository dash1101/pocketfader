"""The computer fetching songs for the phone (yt-dlp is faked; nothing is downloaded)."""
import tempfile
import threading
import unittest
from pathlib import Path

from pocketfader.audio import AudioFetcher, parse_range

from .test_server import Base


class FakeYtdlp:
    def __init__(self, folder, data=b"x" * 1000, fail=False):
        self.folder, self.data, self.fail, self.calls = Path(folder), data, fail, []

    def __call__(self, cmd, timeout):
        self.calls.append(cmd)
        if self.fail:
            return 1, "ERROR: Sign in to confirm you're not a bot"
        out = cmd[cmd.index("-o") + 1].replace("%(ext)s", "m4a")
        Path(out).write_bytes(self.data)
        return 0, ""


class FetcherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_fetches_once_then_uses_the_cache(self):
        y = FakeYtdlp(self.tmp.name)
        f = AudioFetcher(folder=self.tmp.name, ytdlp="/usr/bin/yt-dlp", runner=y)
        a = f.get("dQw4w9WgXcQ")
        b = f.get("dQw4w9WgXcQ")
        self.assertEqual(a, b)
        self.assertEqual(len(y.calls), 1)
        self.assertEqual(y.calls[0][-1], "https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        self.assertEqual(y.calls[0][-2], "--")          # the address can never be read as an option

    def test_bad_ids_never_reach_ytdlp(self):
        y = FakeYtdlp(self.tmp.name)
        f = AudioFetcher(folder=self.tmp.name, ytdlp="/usr/bin/yt-dlp", runner=y)
        for bad in ("../etc/passw", "--exec=rm", "short", "dQw4w9WgXcQ/../x", None, 5):
            with self.assertRaises(ValueError):
                f.get(bad)
        self.assertEqual(y.calls, [])

    def test_failure_is_reported(self):
        f = AudioFetcher(folder=self.tmp.name, ytdlp="/usr/bin/yt-dlp", runner=FakeYtdlp(self.tmp.name, fail=True))
        with self.assertRaises(RuntimeError) as cm:
            f.get("dQw4w9WgXcQ")
        self.assertIn("not a bot", str(cm.exception))

    def test_without_ytdlp(self):
        f = AudioFetcher(folder=self.tmp.name, ytdlp="")
        self.assertFalse(f.available)
        with self.assertRaises(RuntimeError):
            f.get("dQw4w9WgXcQ")

    def test_cache_limit(self):
        y = FakeYtdlp(self.tmp.name, data=b"y" * 600)
        f = AudioFetcher(folder=self.tmp.name, ytdlp="/x", runner=y, limit_bytes=1000)
        f.get("aaaaaaaaaaa")
        import os, time
        os.utime(Path(self.tmp.name) / "aaaaaaaaaaa.m4a", (time.time() - 100, time.time() - 100))
        f.get("bbbbbbbbbbb")
        self.assertFalse((Path(self.tmp.name) / "aaaaaaaaaaa.m4a").exists())
        self.assertTrue((Path(self.tmp.name) / "bbbbbbbbbbb.m4a").exists())

    def test_one_fetch_for_parallel_requests(self):
        y = FakeYtdlp(self.tmp.name)
        f = AudioFetcher(folder=self.tmp.name, ytdlp="/x", runner=y)
        ts = [threading.Thread(target=f.get, args=("ccccccccccc",)) for _ in range(5)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(len(y.calls), 1)

    def test_parse_range(self):
        self.assertEqual(parse_range("bytes=0-99", 1000), (0, 99))
        self.assertEqual(parse_range("bytes=900-", 1000), (900, 999))
        self.assertEqual(parse_range("bytes=-100", 1000), (900, 999))
        self.assertEqual(parse_range("bytes=0-5000", 1000), (0, 999))
        self.assertIsNone(parse_range("bytes=2000-", 1000))
        self.assertIsNone(parse_range(None, 1000))
        self.assertIsNone(parse_range("items=0-1", 1000))


class AudioRouteTests(Base):
    def setUp(self):
        super().setUp()
        self.fake = FakeYtdlp(Path(self.tmp.name) / "a", data=bytes(range(256)) * 4)
        self.app.fetcher = AudioFetcher(folder=Path(self.tmp.name) / "a", ytdlp="/x", runner=self.fake)

    def test_whole_file_and_range(self):
        code, body, r = self.req("GET", "/api/media/audio/dQw4w9WgXcQ")
        self.assertEqual((code, len(body)), (200, 1024))
        self.assertEqual(r.getheader("Content-Type"), "audio/mp4")
        code, body, r = self.req("GET", "/api/media/audio/dQw4w9WgXcQ", headers={"Range": "bytes=10-19"})
        self.assertEqual((code, body), (206, bytes(range(10, 20))))
        self.assertEqual(r.getheader("Content-Range"), "bytes 10-19/1024")

    def test_bad_id_and_capability(self):
        self.assertEqual(self.req("GET", "/api/media/audio/bad")[0], 400)
        self.assertTrue(self.req("GET", "/api/state")[1]["capabilities"]["audio"])
