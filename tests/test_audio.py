"""The computer fetching songs for the phone (yt-dlp is faked; nothing is downloaded)."""
import tempfile
import threading
import unittest
from pathlib import Path

import time

from starcast.audio import AudioFetcher, Saver, parse_range

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


class FakeSaveYtdlp:
    """yt-dlp saving a song: writes "Artist - Title.m4a" where the output template says, prints the path."""
    def __init__(self, fail=False, gate=None):
        self.fail, self.gate, self.calls = fail, gate, []

    def __call__(self, cmd, timeout):
        self.calls.append(cmd)
        if self.gate:
            self.gate.wait(5)
        if self.fail:
            return 1, "", "ERROR: [youtube] abc: Video unavailable"
        out = Path(cmd[cmd.index("-o") + 1].replace("%(artist,creator,uploader)s - %(track,title)s.%(ext)s", "Artist - Title.m4a"))
        out.write_bytes(b"x")
        return 0, str(out) + "\n", ""


def wait_done(saver, jid):
    for _ in range(200):
        st = saver.status(jid)
        if st["state"] != "working":
            return st
        time.sleep(0.01)
    raise AssertionError("still working")


class SaverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_saves_into_the_folder_with_details_and_cover(self):
        y = FakeSaveYtdlp()
        s = Saver(folder=self.tmp.name, ytdlp="/x", ffmpeg="/usr/bin/ffmpeg", js="", runner=y)
        st = wait_done(s, s.start("dQw4w9WgXcQ"))
        self.assertEqual(st["state"], "done")
        self.assertEqual(st["name"], "Artist - Title.m4a")
        self.assertTrue((Path(self.tmp.name) / "Artist - Title.m4a").is_file())
        cmd = y.calls[0]
        self.assertEqual(cmd[-2:], ["--", "https://music.youtube.com/watch?v=dQw4w9WgXcQ"])
        for flag in ("--no-overwrites", "--embed-metadata", "--embed-thumbnail", "--no-playlist"):
            self.assertIn(flag, cmd)

    def test_without_ffmpeg_it_saves_the_plain_file(self):
        y = FakeSaveYtdlp()
        s = Saver(folder=self.tmp.name, ytdlp="/x", ffmpeg="", js="", runner=y)
        self.assertEqual(wait_done(s, s.start("dQw4w9WgXcQ"))["state"], "done")
        self.assertNotIn("--embed-thumbnail", y.calls[0])

    def test_failures_bad_ids_and_no_ytdlp(self):
        s = Saver(folder=self.tmp.name, ytdlp="/x", ffmpeg="", js="", runner=FakeSaveYtdlp(fail=True))
        st = wait_done(s, s.start("dQw4w9WgXcQ"))
        self.assertEqual((st["state"], st["error"]), ("failed", "[youtube] abc: Video unavailable"))
        with self.assertRaises(ValueError):
            s.start("../../etc")
        with self.assertRaises(ValueError):
            s.start("-o /tmp/x")
        with self.assertRaises(RuntimeError):
            Saver(folder=self.tmp.name, ytdlp="", ffmpeg="").start("dQw4w9WgXcQ")
        self.assertIsNone(s.status("nope"))

    def test_the_same_song_twice_at_once_is_one_save(self):
        gate = threading.Event()
        y = FakeSaveYtdlp(gate=gate)
        s = Saver(folder=self.tmp.name, ytdlp="/x", ffmpeg="", js="", runner=y)
        a, b = s.start("dQw4w9WgXcQ"), s.start("dQw4w9WgXcQ")
        self.assertEqual(a, b)
        gate.set()
        wait_done(s, a)
        self.assertEqual(len(y.calls), 1)
        self.assertNotEqual(s.start("dQw4w9WgXcQ"), a)   # once it's done, a new press saves again

    def test_dry_run_runs_nothing(self):
        y = FakeSaveYtdlp()
        s = Saver(folder=self.tmp.name, ytdlp="/x", ffmpeg="", runner=y, dry_run=True)
        self.assertEqual(wait_done(s, s.start("dQw4w9WgXcQ"))["state"], "done")
        self.assertEqual(y.calls, [])


class SaveRouteTests(Base):
    def setUp(self):
        super().setUp()
        self.fake = FakeSaveYtdlp()
        self.app.saver = Saver(folder=Path(self.tmp.name) / "m", ytdlp="/x", ffmpeg="", js="", runner=self.fake)

    def test_start_then_status(self):
        code, body, _ = self.req("POST", "/ext/save", {"videoId": "dQw4w9WgXcQ"})
        self.assertEqual(code, 202)
        jid = body["id"]
        for _ in range(200):
            code, st, _ = self.req("GET", "/ext/save?id=" + jid)
            if st.get("state") != "working":
                break
            time.sleep(0.01)
        self.assertEqual((code, st["state"], st["name"]), (200, "done", "Artist - Title.m4a"))
        self.assertEqual(self.req("GET", "/ext/save?id=s999")[0], 404)
        self.assertTrue(self.req("GET", "/api/state")[1]["capabilities"]["save"])

    def test_refuses_bad_ids_and_websites(self):
        self.assertEqual(self.req("POST", "/ext/save", {"videoId": "x"})[0], 400)
        self.assertEqual(self.req("POST", "/ext/save", {"videoId": "dQw4w9WgXcQ"}, headers={"Origin": "https://evil.example"})[0], 403)
        self.app.saver = Saver(folder=self.tmp.name, ytdlp="", ffmpeg="")
        self.assertEqual(self.req("POST", "/ext/save", {"videoId": "dQw4w9WgXcQ"})[0], 503)
