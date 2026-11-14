"""Songs for the phone: the computer fetches a YouTube Music song's audio with yt-dlp and hands the file to the phone.

The phone normally gets audio on its own; this is the fallback for when its lookup fails. Only a validated video id is
ever accepted (the address is built here), files are cached and the cache is kept under a size limit.
"""
import os
import re
import shutil
import sys
import subprocess
import threading
import time
from pathlib import Path

from .proc import no_window

_ID = re.compile(r"^[A-Za-z0-9_-]{11}$")
TYPES = {".m4a": "audio/mp4", ".webm": "audio/webm", ".opus": "audio/ogg", ".mp3": "audio/mpeg"}


def valid_id(v):
    return isinstance(v, str) and bool(_ID.match(v))


def _find_ytdlp():
    """Prefer a yt-dlp installed next to this Python (pip, kept current) over the system one, which is often too old."""
    import sys
    local = Path(sys.executable).parent / "yt-dlp"
    return str(local) if local.is_file() else shutil.which("yt-dlp")


def _is_venv_copy(path):
    import sys
    return Path(path).parent == Path(sys.executable).parent


def cache_dir():
    """~/.cache/ytm-starcast/audio. Songs cached under the old name (pocketfader) are moved over once and kept."""
    from .config import LEGACY_NAME, NAME, adopt_legacy_dir
    base = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    return adopt_legacy_dir(base / NAME, base / LEGACY_NAME, link=False) / "audio"


class AudioFetcher:
    def __init__(self, folder=None, ytdlp=None, runner=None, limit_bytes=1 << 30, dry_run=False, js=None):
        self.dir = Path(folder) if folder else cache_dir()
        self.ytdlp = ytdlp if ytdlp is not None else _find_ytdlp()
        # current yt-dlp needs a JavaScript runtime for YouTube; it only looks for deno unless told about node
        self.js = js if js is not None else (shutil.which("node") if self.ytdlp and _is_venv_copy(self.ytdlp) else None)
        self._run = runner or self._default_run
        self.limit = limit_bytes
        self.dry_run = dry_run
        self._locks = {}
        self._guard = threading.Lock()

    @property
    def available(self):
        return bool(self.ytdlp)

    def _cached(self, vid):
        for ext in TYPES:
            f = self.dir / (vid + ext)
            if f.is_file() and f.stat().st_size > 0:
                return f
        return None

    def get(self, vid):
        """The song's audio file (fetched now if it isn't cached). Raises ValueError for a bad id, RuntimeError if it fails."""
        if not valid_id(vid):
            raise ValueError("bad video id")
        hit = self._cached(vid)
        if hit:
            os.utime(hit)                        # recently used: keep it
            return hit
        if not self.available:
            raise RuntimeError("yt-dlp isn't installed on this computer")
        with self._guard:
            lock = self._locks.setdefault(vid, threading.Lock())
        with lock:                               # one fetch per song, however many requests ask
            hit = self._cached(vid)
            if hit:
                return hit
            self.dir.mkdir(parents=True, exist_ok=True)
            out = str(self.dir / (vid + ".%(ext)s"))
            cmd = [self.ytdlp, "--no-playlist", "--no-progress", "--quiet", "--no-warnings",
                   "-f", "bestaudio[ext=m4a]/bestaudio", "-o", out]
            if self.js:
                cmd += ["--js-runtimes", "node:" + self.js]
            cmd += ["--", f"https://music.youtube.com/watch?v={vid}"]
            code, err = self._run(cmd, 180)
            hit = self._cached(vid)
            if code != 0 or not hit:
                raise RuntimeError("yt-dlp couldn't fetch it: " + (err.strip().splitlines() or ["unknown error"])[-1][:200])
        self._trim()
        return hit

    def _trim(self):
        """Drop the least recently used songs while the cache is over its limit (and leftovers of fetches that died)."""
        old = time.time() - 3600
        for f in self.dir.iterdir():
            if f.suffix in (".part", ".ytdl") or ".part-Frag" in f.name:
                try:
                    if f.stat().st_mtime < old:
                        f.unlink()
                except OSError:
                    pass
        files = sorted((f for f in self.dir.iterdir() if f.suffix in TYPES), key=lambda f: f.stat().st_mtime)
        total = sum(f.stat().st_size for f in files)
        while files and total > self.limit:
            f = files.pop(0)
            total -= f.stat().st_size
            f.unlink(missing_ok=True)

    @staticmethod
    def _default_run(cmd, timeout):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, **no_window())
            return p.returncode, (p.stderr or "")
        except subprocess.TimeoutExpired:
            return 1, "timed out"
        except OSError as e:
            return 1, str(e)


def parse_range(header, size):
    """'bytes=a-b' -> (start, end) inclusive, clamped; None when absent or unusable (send the whole file)."""
    m = re.match(r"^bytes=(\d*)-(\d*)$", (header or "").strip())
    if not m or size <= 0:
        return None
    a, b = m.groups()
    if a == "" and b == "":
        return None
    if a == "":                                  # the last b bytes
        start, end = max(0, size - int(b)), size - 1
    else:
        start, end = int(a), (int(b) if b else size - 1)
    end = min(end, size - 1)
    if start > end:
        return None
    return start, end


def music_dir():
    """The computer's Music folder (the one the desktop names, on Linux), with a folder of YTM Starcast's own in it."""
    base = None
    if sys.platform.startswith("linux"):
        try:
            p = subprocess.run(["xdg-user-dir", "MUSIC"], capture_output=True, text=True, timeout=3, stdin=subprocess.DEVNULL)
            out = (p.stdout or "").strip()
            # it answers with the home folder itself when no Music folder is set up
            if p.returncode == 0 and out and Path(out) != Path.home():
                base = Path(out)
        except (OSError, subprocess.TimeoutExpired):
            pass
    return (base or Path.home() / "Music") / "YTM Starcast"


class Saver:
    """Saves a YouTube Music song as an audio file in the Music folder (the extension's "Save to this computer").

    yt-dlp fetches it and names it "Artist - Title"; with ffmpeg on the computer the file also gets the song's details
    and cover. It runs in the background: start() answers at once with an id that status() reports on."""
    KEEP = 50

    def __init__(self, folder=None, ytdlp=None, ffmpeg=None, runner=None, js=None, dry_run=False):
        self._folder = Path(folder) if folder else None
        self.ytdlp = ytdlp if ytdlp is not None else _find_ytdlp()
        self.ffmpeg = ffmpeg if ffmpeg is not None else shutil.which("ffmpeg")
        self.js = js if js is not None else (shutil.which("node") if self.ytdlp and _is_venv_copy(self.ytdlp) else None)
        self._run = runner or self._default_run
        self.dry_run = dry_run
        self._jobs = {}
        self._busy = {}
        self._lock = threading.Lock()
        self._seq = 0

    @property
    def available(self):
        return bool(self.ytdlp)

    @property
    def folder(self):
        if self._folder is None:
            self._folder = music_dir()
        return self._folder

    def command(self, vid):
        out = str(self.folder / "%(artist,creator,uploader)s - %(track,title)s.%(ext)s")
        cmd = [self.ytdlp, "--no-playlist", "--no-progress", "--no-warnings", "--no-overwrites",
               "-f", "bestaudio[ext=m4a]/bestaudio", "-o", out, "--print", "after_move:filepath"]
        if self.ffmpeg:
            cmd += ["--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg"]
        if self.js:
            cmd += ["--js-runtimes", "node:" + self.js]
        return cmd + ["--", f"https://music.youtube.com/watch?v={vid}"]

    def start(self, vid):
        """Begins saving the song; returns the job's id (the same one while that song is still being saved)."""
        if not valid_id(vid):
            raise ValueError("bad video id")
        if not self.available:
            raise RuntimeError("yt-dlp isn't installed on this computer")
        with self._lock:
            if vid in self._busy:
                return self._busy[vid]
            self._seq += 1
            jid = f"s{self._seq}"
            self._jobs[jid] = {"state": "working", "videoId": vid}
            self._busy[vid] = jid
            while len(self._jobs) > self.KEEP:
                old = next(iter(self._jobs))
                if self._jobs[old]["state"] == "working":
                    break
                del self._jobs[old]
        threading.Thread(target=self._work, args=(jid, vid), daemon=True, name="starcast-save").start()
        return jid

    def _work(self, jid, vid):
        res = {"state": "failed", "error": "unknown error"}
        try:
            if self.dry_run:
                res = {"state": "done", "name": "(dry run) " + vid + ".m4a", "folder": str(self.folder)}
            else:
                self.folder.mkdir(parents=True, exist_ok=True)
                code, out, err = self._run(self.command(vid), 300)
                lines = [ln.strip() for ln in (out or "").splitlines() if ln.strip()]
                path = Path(lines[-1]) if lines else None
                if code == 0 and path and path.is_file():
                    res = {"state": "done", "name": path.name, "folder": str(path.parent)}
                elif code == 0 and "has already been downloaded" in (out + err):
                    res = {"state": "done", "name": "", "folder": str(self.folder), "already": True}
                else:
                    msg = (err.strip().splitlines() or ["unknown error"])[-1]
                    res = {"state": "failed", "error": msg.replace("ERROR: ", "")[:200]}
        except Exception as e:  # noqa: BLE001 - reported to the page, never raised in a thread
            res = {"state": "failed", "error": str(e)[:200]}
        with self._lock:
            self._jobs[jid] = {"videoId": vid, **res}
            self._busy.pop(vid, None)

    def status(self, jid):
        with self._lock:
            j = self._jobs.get(jid)
            return dict(j) if j else None

    @staticmethod
    def _default_run(cmd, timeout):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, **no_window())
            return p.returncode, (p.stdout or ""), (p.stderr or "")
        except subprocess.TimeoutExpired:
            return 1, "", "timed out"
        except OSError as e:
            return 1, "", str(e)
