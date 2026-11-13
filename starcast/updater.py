"""Finding and fetching new releases.

Releases are published on GitHub. The computer running YTM Starcast does the asking (so a phone never needs a
GitHub login, and it works for a private repository too, using the `gh` login or a GITHUB_TOKEN on this
computer). It can update itself, and it hands the new Android app to the phone.

YTM Starcast used to be called Pocketfader. Its releases moved to dash1101/ytm-starcast; GitHub forwards the old
name, and while the new name has no releases yet, the old repository is asked instead.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urljoin, urlsplit

from . import __version__
from .config import config_dir
from .proc import no_window

DEFAULT_REPO = "dash1101/ytm-starcast"
LEGACY_REPO = "dash1101/pocketfader"
API = "https://api.github.com"
_FOLLOW = (301, 302, 307, 308)
_PREFIXES = ("ytm-starcast", "ytm_starcast", "pocketfader")   # downloaded release files, under the new and the old name
_VER = re.compile(r"v?(\d+)\.(\d+)\.(\d+)(?:[-._]?(alpha|beta|rc|a|b)[-._]?(\d+))?", re.I)
_STAGE = {"a": 0, "alpha": 0, "b": 1, "beta": 1, "rc": 2}


def parse_version(text):
    """'v0.1.0-alpha.4' / '0.1.0a4' -> comparable tuple; None if it isn't a version. Final releases sort last."""
    m = _VER.fullmatch((text or "").strip())
    if not m:
        return None
    major, minor, patch, stage, n = m.groups()
    return (int(major), int(minor), int(patch), _STAGE[stage.lower()] if stage else 9, int(n) if n else 0)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def _fetch(url, headers=None, timeout=20):
    """-> (status, headers dict, body bytes). Redirects are returned, not followed."""
    opener = urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with opener.open(req, timeout=timeout) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read() or b""
    except (urllib.error.URLError, OSError) as e:
        return 0, {}, str(e).encode()


def _env(name):
    """STARCAST_<name>, else the old POCKETFADER_<name>."""
    return os.environ.get("STARCAST_" + name) or os.environ.get("POCKETFADER_" + name)


def _nothing_there(status, body):
    """The repository doesn't exist (yet), or has no releases: worth asking the old one."""
    if status == 404:
        return True
    try:
        return status == 200 and json.loads(body) == []
    except ValueError:
        return False


def _same_api_host(url):
    """Only the GitHub API itself may send the release listing elsewhere (a renamed repository answers 301)."""
    u = urlsplit(url)
    return u.scheme == "https" and u.netloc == "api.github.com"


class Updater:
    def __init__(self, repo=None, current=None, fetch=_fetch, token=None, cache=None, ttl=900):
        self.repo = repo or _env("REPO") or DEFAULT_REPO
        # the old repository is only a fallback for the built-in name, never for one somebody chose
        self.fallback = LEGACY_REPO if self.repo == DEFAULT_REPO else None
        self.source = self.repo           # the repository that answered last (downloads come from it)
        self.current = current or __version__
        self.fetch = fetch
        self._token = token
        self._dir = Path(cache) if cache else None
        self.ttl = ttl
        self._lock = threading.Lock()
        self._info = None
        self._at = 0.0

    @property
    def dir(self):
        """Where downloads go. Worked out on first use, so creating an Updater touches nothing on disk."""
        if self._dir is None:
            self._dir = config_dir() / "updates"
        return self._dir

    # ---------------------------------------------------------------- access
    def token(self):
        if self._token is not None:
            return self._token or None
        for var in ("STARCAST_GITHUB_TOKEN", "POCKETFADER_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"):
            if os.environ.get(var):
                self._token = os.environ[var]
                return self._token
        exe = shutil.which("gh")
        if exe:
            try:
                out = subprocess.run([exe, "auth", "token"], capture_output=True, text=True, timeout=5, **no_window())
                if out.returncode == 0 and out.stdout.strip():
                    self._token = out.stdout.strip()
                    return self._token
            except (OSError, subprocess.SubprocessError):
                pass
        self._token = ""
        return None

    def _headers(self, accept="application/vnd.github+json"):
        h = {"Accept": accept, "User-Agent": f"ytm-starcast/{self.current}", "X-GitHub-Api-Version": "2022-11-28"}
        t = self.token()
        if t:
            h["Authorization"] = f"Bearer {t}"
        return h

    # ---------------------------------------------------------------- what's new
    def latest(self, refresh=False):
        with self._lock:
            if self._info and not refresh and time.time() - self._at < self.ttl:
                return self._info
        info = self._query()
        with self._lock:
            self._info, self._at = info, time.time()
        return info

    def _list(self, repo):
        """GET the release listing -> (status, body). Follows a redirect only while it stays on the GitHub API."""
        url = f"{API}/repos/{repo}/releases?per_page=15"
        headers = self._headers()
        for _ in range(4):
            status, h, body = self.fetch(url, headers)
            if status not in _FOLLOW or not h.get("location"):
                return status, body
            nxt = urljoin(url, h["location"])
            if not _same_api_host(nxt):
                return status, body
            url = nxt
        return status, body

    def _query(self):
        status, body = self._list(self.repo)
        source = self.repo
        if self.fallback and _nothing_there(status, body):
            old_status, old_body = self._list(self.fallback)
            if old_status == 200:
                status, body, source = old_status, old_body, self.fallback
        self.source = source
        base = {"current": self.current, "available": False, "checked": int(time.time()), "repo": source}
        if status != 200:
            why = "couldn't reach GitHub" if status == 0 else (
                "no access to the releases (sign in with `gh auth login`, or set GITHUB_TOKEN)" if status in (401, 403, 404)
                else f"GitHub answered {status}")
            return {**base, "error": why}
        try:
            releases = json.loads(body)
        except ValueError:
            releases = None
        if not isinstance(releases, list):
            return {**base, "error": "unexpected answer from GitHub"}
        mine = parse_version(self.current)
        best = None
        for r in releases:
            if not isinstance(r, dict):
                continue
            v = parse_version(r.get("tag_name", ""))
            if r.get("draft") or v is None:
                continue
            if best is None or v > best[0]:
                best = (v, r)
        if best is None:
            return {**base, "latest": None}
        v, r = best
        assets = [{"name": a["name"], "size": a.get("size", 0), "id": a["id"]} for a in r.get("assets", [])]
        return {**base, "latest": r["tag_name"], "available": bool(mine is None or v > mine), "notes": r.get("body") or "",
                "url": r.get("html_url", ""), "prerelease": bool(r.get("prerelease")), "assets": assets,
                "apk": any(_is_apk(a["name"]) for a in assets), "wheel": any(a["name"].endswith(".whl") for a in assets)}

    # ---------------------------------------------------------------- downloading
    def _asset(self, pick):
        info = self.latest()
        return next((a for a in info.get("assets", []) if pick(a["name"])), None)

    def download(self, pick):
        """Download the matching release file (checked against the release's SHA256SUMS) -> Path, or raises."""
        a = self._asset(pick)
        if not a:
            raise RuntimeError("that file isn't part of the latest release")
        self.dir.mkdir(parents=True, exist_ok=True)
        dest = self.dir / a["name"]
        want = self._checksum(a["name"])
        if dest.exists() and dest.stat().st_size == a["size"] and (not want or _sha256(dest) == want):
            return dest
        data = self._get_asset(a["id"])
        if want and hashlib.sha256(data).hexdigest() != want:
            raise RuntimeError("the download didn't match its checksum; try again")
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(dest)
        mine = {p for prefix in _PREFIXES for p in self.dir.glob(prefix + "*") if not p.name.endswith(".part")}
        for old in sorted(mine, key=lambda p: p.stat().st_mtime)[:-4]:
            old.unlink(missing_ok=True)
        return dest

    def _get_asset(self, asset_id):
        url = f"{API}/repos/{self.source}/releases/assets/{asset_id}"
        headers = self._headers("application/octet-stream")
        for _ in range(4):
            status, h, body = self.fetch(url, headers, 120)
            if status in (301, 302, 303, 307, 308) and h.get("location"):
                if urlsplit(h["location"]).netloc != urlsplit(url).netloc:
                    headers = {k: v for k, v in headers.items() if k != "Authorization"}   # the CDN link is pre-signed
                url = h["location"]
                continue
            if status == 200:
                return body
            raise RuntimeError(f"download failed ({status})")
        raise RuntimeError("too many redirects")

    def _checksum(self, name):
        a = self._asset(lambda n: n == "SHA256SUMS.txt")
        if not a:
            return None
        try:
            if getattr(self, "_sums_id", None) != a["id"]:
                self._sums, self._sums_id = self._get_asset(a["id"]).decode(), a["id"]
            for line in self._sums.splitlines():
                digest, _, fname = line.strip().partition("  ")
                if fname.strip().lstrip("*") == name:
                    return digest.lower()
        except Exception:
            return None
        return None

    def apk(self):
        return self.download(_is_apk)

    # ---------------------------------------------------------------- updating this computer
    def can_update_self(self):
        return not getattr(sys, "frozen", False) and self.latest().get("wheel", False)

    def update_self(self):
        """Install the newest wheel into the environment this program runs from -> (ok, message)."""
        if getattr(sys, "frozen", False):
            return False, "This is the packaged program: download the new version from the release page."
        try:
            wheel = self.download(lambda n: n.endswith(".whl"))
        except Exception as e:
            return False, str(e)
        out = subprocess.run([sys.executable, "-m", "pip", "install", "--no-deps", "--force-reinstall", "--quiet", str(wheel)],
                             capture_output=True, text=True, timeout=300, **no_window())
        if out.returncode != 0:
            lines = (out.stderr or out.stdout).strip().splitlines()
            return False, lines[-1] if lines else "pip failed"
        return True, f"Updated to {self.latest().get('latest')}. Restart YTM Starcast to use it."


def _is_apk(name):
    return name.endswith(".apk") and "unshrunk" not in name


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
