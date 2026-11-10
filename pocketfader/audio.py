"""Songs for the phone: the computer fetches a YouTube Music song's audio with yt-dlp and hands the file to the phone.

The phone normally gets audio on its own; this is the fallback for when its lookup fails. Only a validated video id is
ever accepted (the address is built here), files are cached and the cache is kept under a size limit.
"""
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path

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
    base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    return Path(base) / "pocketfader" / "audio"


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
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
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
