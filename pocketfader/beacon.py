"""YTM Beacon bridge: show whether Discord presence is working, and switch it on or off, from the phone.

YTM Beacon is a separate program that shows the song playing in YouTube Music as a Discord "Listening to"
card. It listens on loopback only (ports 41713-41717), has no login of its own, and refuses anything that is
not a local program. So the phone never talks to it directly: the phone asks Pocketfader (behind its usual
security checks) and Pocketfader asks Beacon, here.

This module is a whitelist, not a proxy: exactly three operations (status, visibility, a few settings), every
input validated, and nothing Beacon returns about paths, its process or its Discord application id is
passed on. Playback information is never sent *into* Beacon; its browser extension already does that.
"""
import hashlib
import http.client
import io
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

PORTS = (41713, 41714, 41715, 41716, 41717)
HOST = "127.0.0.1"          # literal address: Beacon listens on IPv4 only, "localhost" may try ::1 first
TIMEOUT = 1.5               # Beacon is local, so slow means broken
MIN_VERSION = (0, 1, 4)
MAX_HIDE_MINUTES = 1440

# The only settings the phone may change, and what each accepts.
BOOL_SETTINGS = ("privateMode", "showPaused", "showProgress", "showStateIcon", "showButtons", "showAlbumArt")
STATUS_DISPLAY = ("name", "state", "details")


REPO = "dash1101/ytm-beacon"
RELEASE_BASE = f"https://github.com/{REPO}/releases/latest/download"
LATEST_API = f"https://api.github.com/repos/{REPO}/releases/latest"
LATEST_TTL = 6 * 3600
INSTALL_ACTIONS = ("install", "update", "start")


class BeaconError(Exception):
    """code: NOT_RUNNING (nothing answers), REJECTED (Beacon said no; message is plain language)."""

    def __init__(self, code, message=""):
        super().__init__(message or code)
        self.code, self.message = code, message or code


class BadRequest(ValueError):
    """A phone request that fails validation (nothing is sent to Beacon)."""


def parse_version(text):
    """'v0.1.4' -> (0, 1, 4); None for 'dev' or anything unparsable."""
    m = re.match(r"v?(\d+)\.(\d+)\.(\d+)", str(text or ""))
    return tuple(int(x) for x in m.groups()) if m else None


def version_ok(text):
    v = parse_version(text)
    return True if v is None else v >= MIN_VERSION     # local "dev" builds are assumed to be new enough


# ---- validation (phone -> Beacon) ----

def validate_install(body):
    if body in (None, {}):
        return {"action": "auto"}
    if not isinstance(body, dict) or set(body) - {"action"}:
        raise BadRequest("expected {\"action\": \"install\"|\"update\"|\"start\"}")
    if body.get("action") not in INSTALL_ACTIONS:
        raise BadRequest("action must be one of: " + ", ".join(INSTALL_ACTIONS))
    return {"action": body["action"]}


def _is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def validate_visibility(body):
    if not isinstance(body, dict):
        raise BadRequest("expected a JSON object")
    extra = set(body) - {"action", "minutes"}
    if extra:
        raise BadRequest("unknown field: " + sorted(extra)[0])
    action = body.get("action")
    if action not in ("show", "hide"):
        raise BadRequest("action must be \"show\" or \"hide\"")
    out = {"action": action}
    if "minutes" in body:
        if action != "hide":
            raise BadRequest("minutes only applies to hide")
        m = body["minutes"]
        if not _is_int(m) or not 1 <= m <= MAX_HIDE_MINUTES:
            raise BadRequest(f"minutes must be a whole number from 1 to {MAX_HIDE_MINUTES}")
        out["minutes"] = m
    return out


def validate_settings(body):
    if not isinstance(body, dict) or not body:
        raise BadRequest("send at least one setting")
    out = {}
    for k, v in body.items():
        if k in BOOL_SETTINGS:
            if not isinstance(v, bool):
                raise BadRequest(f"{k} must be true or false")
        elif k == "pauseTimeoutMinutes":
            if not _is_int(v) or not 0 <= v <= 1440:
                raise BadRequest("pauseTimeoutMinutes must be a whole number from 0 to 1440")
        elif k == "statusDisplay":
            if v not in STATUS_DISPLAY:
                raise BadRequest("statusDisplay must be one of: " + ", ".join(STATUS_DISPLAY))
        else:
            raise BadRequest("unknown setting: " + str(k)[:40])
        out[k] = v
    return out


# ---- Beacon -> phone ----

def headline(s, version_is_ok=True):
    """Mirrors YTM Beacon's own dashboard, in the same order. s is Beacon's raw /v1/status (or None)."""
    if not s:
        return "unreachable"
    if not version_is_ok:
        return "unsupported"
    cfg = s.get("config") or {}
    visible = s.get("visible")
    if visible is None:
        visible = bool(cfg.get("enabled", True))
    if visible is False:
        return "snoozed" if (s.get("snoozeSec") or 0) > 0 else "off"
    if (s.get("discord") or {}).get("state") != "connected":
        return "discord-down"
    ext = s.get("extension") or {}
    seen = ext.get("lastSeenAgoSec")
    if seen is None:
        return "extension-quiet" if ext.get("everConnected") else "extension-missing"
    if seen > (ext.get("quietAfterSec") or 90):
        return "extension-quiet"
    if not s.get("nowPlaying"):
        return "idle"
    hidden = s.get("hidden")
    if hidden == "filtered":
        return "filtered"
    if hidden == "paused":
        return "paused-hidden"
    if hidden == "paused-timeout":
        return "paused-timeout"
    return "showing" if s.get("presenceActive") else "waiting"


def _num(v, default=0.0):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else default


def normalize(s, now=None):
    """Beacon's raw status -> the small, stable shape the phone uses. No paths, pid or Discord application id."""
    now = int((now if now is not None else time.time()) * 1000)
    if not s:
        return {"reachable": False, "headline": "unreachable", "fetchedAt": now}
    ok = version_ok(s.get("version"))
    cfg = s.get("config") or {}
    ext = s.get("extension") or {}
    dc = s.get("discord") or {}
    np = s.get("nowPlaying")
    visible = s.get("visible")
    if visible is None:
        visible = bool(cfg.get("enabled", True))
    out = {
        "reachable": True,
        "version": str(s.get("version") or ""),
        "headline": headline(s, ok),
        "visible": bool(visible),
        "snoozeSec": int(_num(s.get("snoozeSec"))),
        "discord": {"state": dc.get("state") or "disconnected"},
        "extension": {
            "lastSeenAgoSec": ext.get("lastSeenAgoSec"),
            "quietAfterSec": int(_num(ext.get("quietAfterSec"), 90)),
            "everConnected": bool(ext.get("everConnected")),
        },
        "nowPlaying": None,
        "presenceActive": bool(s.get("presenceActive")),
        "settings": {
            **{k: bool(cfg.get(k, d)) for k, d in (("privateMode", False), ("showPaused", True), ("showProgress", True),
                                                    ("showStateIcon", True), ("showButtons", True), ("showAlbumArt", True))},
            "pauseTimeoutMinutes": int(_num(cfg.get("pauseTimeoutMinutes"), 5)),
            "statusDisplay": cfg.get("statusDisplay") if cfg.get("statusDisplay") in STATUS_DISPLAY else "name",
        },
        "fetchedAt": now,
    }
    if dc.get("user"):
        out["discord"]["user"] = str(dc["user"])
    if dc.get("lastError"):
        out["detail"] = str(dc["lastError"])
    if np:
        out["nowPlaying"] = {
            "title": str(np.get("title") or ""), "artist": str(np.get("artist") or ""),
            "album": str(np.get("album") or ""), "artwork": str(np.get("artwork") or ""),
            "state": "paused" if np.get("state") == "paused" else "playing",
            "positionSec": _num(np.get("position")), "durationSec": _num(np.get("duration")),
        }
    return out


class Installer:
    """Installs, updates or starts YTM Beacon on this computer, always from its latest GitHub release.

    Nothing the phone sends reaches a shell: the actions are a fixed list, the download address is fixed, the
    archive's SHA-256 is checked against the release's checksums.txt (the same checks as Beacon's own install
    script), and the commands run are Beacon's own `install` / `update` and, on Linux, starting its user service.
    """

    def __init__(self, base_url=None, latest_api=LATEST_API, runner=None, system=None, machine=None, home=None):
        self.base_url = base_url or os.environ.get("POCKETFADER_BEACON_BASE_URL") or RELEASE_BASE
        self.latest_api = latest_api
        self._run = runner or self._default_run
        self.system = (system or platform.system()).lower()
        self.machine = (machine or platform.machine()).lower()
        self.home = Path(home) if home else Path.home()
        self._lock = threading.Lock()
        self._job = {"state": "idle", "message": ""}
        self._latest = (0.0, None)
        self._fetching = False

    # -- what's installed, what's out there --
    def binary(self):
        """Where Beacon's program is, or None."""
        cands = []
        if self.system == "windows":
            cands.append(Path(os.environ.get("LOCALAPPDATA", self.home / "AppData" / "Local")) / "ytm-beacon" / "ytm-beacon.exe")
        else:
            cands.append(self.home / ".local" / "bin" / "ytm-beacon")
        for c in cands:
            if c.is_file():
                return c
        found = shutil.which("ytm-beacon")
        return Path(found) if found else None

    def can_install(self):
        return self.system in ("linux", "darwin", "windows") and self._arch() is not None

    def can_start(self):
        return self.system == "linux" and shutil.which("systemctl") is not None

    def _arch(self):
        return {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(self.machine)

    def latest(self):
        """The newest released version (e.g. "v0.1.4"), looked up at most every few hours and never blocking."""
        at, val = self._latest
        if self.latest_api and time.time() - at > LATEST_TTL and not self._fetching:
            self._fetching = True
            threading.Thread(target=self._fetch_latest, daemon=True).start()
        return val

    def _fetch_latest(self):
        try:
            req = urllib.request.Request(self.latest_api, headers={"User-Agent": "pocketfader", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(req, timeout=6) as r:
                tag = json.loads(r.read(1 << 20)).get("tag_name")
            self._latest = (time.time(), tag if parse_version(tag) else None)
        except Exception:
            self._latest = (time.time() - LATEST_TTL + 300, self._latest[1])   # try again in five minutes
        finally:
            self._fetching = False

    def info(self, running_version=None):
        latest = self.latest()
        lv, rv = parse_version(latest), parse_version(running_version)
        return {
            "installed": self.binary() is not None or running_version is not None,
            "canInstall": self.can_install(),
            "canStart": self.can_start(),
            "latest": latest,
            "updateAvailable": bool(lv and rv and lv > rv),
            "job": dict(self._job),
        }

    # -- doing it --
    def start(self, action, running):
        """Begins the job in the background. action: install | update | start | auto. running: Beacon answers now."""
        with self._lock:
            if self._job["state"] == "running":
                raise BeaconError("REJECTED", "Already working on it.")
            if action == "auto":
                action = "update" if (running or self.binary()) else "install"
            if action == "start" and not self.can_start():
                raise BeaconError("REJECTED", "Starting it from here isn't supported on this computer. Start YTM Beacon there.")
            if action in ("install", "update") and not self.can_install():
                raise BeaconError("REJECTED", "This computer type isn't supported by YTM Beacon's installer.")
            self._job = {"state": "running", "message": {"install": "Downloading YTM Beacon…", "update": "Updating YTM Beacon…",
                                                         "start": "Starting YTM Beacon…"}[action]}
        threading.Thread(target=self._work, args=(action,), daemon=True).start()
        return dict(self._job)

    def _set(self, state, message):
        with self._lock:
            self._job = {"state": state, "message": message}

    def _scrub(self, text):
        text = (text or "").strip().splitlines()[-1:] or [""]
        return text[0].replace(str(self.home), "~")[:200]

    def _work(self, action):
        try:
            if action == "start":
                code, out = self._run(["systemctl", "--user", "start", "ytm-beacon.service"], 30)
                self._set("done" if code == 0 else "failed", "Started YTM Beacon." if code == 0 else "Couldn't start it: " + self._scrub(out))
            elif action == "update" and self.binary():
                code, out = self._run([str(self.binary()), "update"], 180)
                self._set("done" if code == 0 else "failed", self._scrub(out) or "Updated." if code == 0 else "Update failed: " + self._scrub(out))
            else:
                self._download_and_install()
        except Exception as e:  # never let a thread die silently: the phone would wait forever
            self._set("failed", "Couldn't finish: " + self._scrub(str(e)))

    def _fetch(self, name, limit=64 << 20):
        with urllib.request.urlopen(urllib.request.Request(f"{self.base_url.rstrip('/')}/{name}", headers={"User-Agent": "pocketfader"}), timeout=60) as r:
            return r.read(limit)

    def _download_and_install(self):
        osname, arch = {"linux": "linux", "darwin": "darwin", "windows": "windows"}[self.system], self._arch()
        ext = "zip" if osname == "windows" else "tar.gz"
        name = f"ytm-beacon_{osname}_{arch}.{ext}"
        try:
            blob = self._fetch(name)
            sums = self._fetch("checksums.txt").decode("utf-8", "replace")
        except Exception:
            return self._set("failed", "Couldn't download YTM Beacon. Check the computer's internet connection.")
        want = next((ln.split()[0] for ln in sums.splitlines() if ln.strip().endswith(" " + name) or ln.strip().endswith("*" + name)), "")
        if not want or want != hashlib.sha256(blob).hexdigest():
            return self._set("failed", "The download didn't match its checksum, so it was not installed.")
        self._set("running", "Installing YTM Beacon…")
        with tempfile.TemporaryDirectory(prefix="pf-beacon-") as tmp:
            exe = Path(tmp) / ("ytm-beacon.exe" if osname == "windows" else "ytm-beacon")
            if ext == "zip":
                with zipfile.ZipFile(io.BytesIO(blob)) as z:
                    z.extract(exe.name, tmp)
            else:
                with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as t:
                    member = next((m for m in t.getmembers() if m.isfile() and Path(m.name).name == exe.name), None)
                    if member is None:
                        return self._set("failed", "The download didn't contain the program.")
                    exe.write_bytes(t.extractfile(member).read())
            exe.chmod(0o755)
            code, out = self._run([str(exe), "install"], 180)
        self._set("done" if code == 0 else "failed",
                  "Installed YTM Beacon. Next, add its browser extension: a setup page opens on the computer." if code == 0
                  else "The installer stopped: " + self._scrub(out))

    @staticmethod
    def _default_run(cmd, timeout):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
            return p.returncode, (p.stdout or "") + (p.stderr or "")
        except subprocess.TimeoutExpired:
            return 1, "timed out"
        except OSError as e:
            return 1, str(e)


class Beacon:
    """Talks to a YTM Beacon on this computer. Thread safe; remembers which port answered."""

    def __init__(self, ports=PORTS, host=HOST, timeout=TIMEOUT, dry_run=False, cache_s=1.0, installer=None):
        self.ports, self.host, self.timeout = tuple(ports), host, timeout
        self.dry_run = dry_run
        self.installer = installer or Installer()
        self._port = None
        self._lock = threading.Lock()
        self._cache = (0.0, None)
        self._cache_s = cache_s

    # -- plumbing --
    def _call(self, port, method, path, body=None):
        conn = http.client.HTTPConnection(self.host, port, timeout=self.timeout)
        try:
            data = json.dumps(body).encode() if body is not None else None
            # No Origin header (server-side clients have none); Host stays 127.0.0.1:<port>.
            conn.request(method, path, body=data, headers={"Content-Type": "application/json"} if data else {})
            resp = conn.getresponse()
            raw = resp.read(1 << 20)
            try:
                payload = json.loads(raw or b"{}")
            except ValueError:
                payload = {}
            return resp.status, payload
        finally:
            conn.close()

    def _ping(self, port):
        try:
            code, body = self._call(port, "GET", "/v1/ping")
        except (OSError, http.client.HTTPException):
            return False
        return code == 200 and isinstance(body, dict) and body.get("app") == "ytm-beacon"

    def find_port(self):
        """The port Beacon answers on, or None. Other programs may sit on these ports, so ping is checked."""
        with self._lock:
            order = ([self._port] if self._port else []) + [p for p in self.ports if p != self._port]
            for p in order:
                if self._ping(p):
                    self._port = p
                    return p
            self._port = None
            return None

    def _request(self, method, path, body=None):
        port = self._port or self.find_port()
        if port is None:
            raise BeaconError("NOT_RUNNING")
        try:
            code, payload = self._call(port, method, path, body)
        except (OSError, http.client.HTTPException):
            # the cached port went stale (Beacon restarted on another port?): rescan once
            self._port = None
            port = self.find_port()
            if port is None:
                raise BeaconError("NOT_RUNNING") from None
            try:
                code, payload = self._call(port, method, path, body)
            except (OSError, http.client.HTTPException):
                self._port = None
                raise BeaconError("NOT_RUNNING") from None
        if code >= 400:
            msg = payload.get("error") if isinstance(payload, dict) else None
            raise BeaconError("REJECTED", str(msg or f"Beacon answered HTTP {code}")[:200])
        return payload

    # -- the three operations --
    def raw_status(self):
        s = self._request("GET", "/v1/status")
        return s if isinstance(s, dict) and s.get("app") == "ytm-beacon" else None

    def status(self, fresh=False):
        """The phone-ready status (cached for a second so several phones don't multiply the polling)."""
        at, cached = self._cache
        if not fresh and cached is not None and time.monotonic() - at < self._cache_s:
            return cached
        try:
            out = normalize(self.raw_status())
        except BeaconError as e:
            if e.code != "NOT_RUNNING":
                raise
            out = normalize(None)
        self._cache = (time.monotonic(), out)
        return out

    def status_with_install(self, fresh=False):
        """status() plus what the installer knows (always current, never cached)."""
        out = dict(self.status(fresh))
        out["install"] = self.installer.info(out.get("version") if out.get("reachable") else None)
        return out

    def start_install(self, body):
        req = validate_install(body)
        if self.dry_run:
            return {"ok": True, "dryRun": True}
        running = bool(self.status(fresh=True).get("reachable"))
        return self.installer.start(req["action"], running)

    def set_visibility(self, body):
        req = validate_visibility(body)
        if self.dry_run:
            return {"ok": True, "dryRun": True}
        r = self._request("POST", "/v1/visibility", req)
        self._cache = (0.0, None)
        return {"enabled": bool(r.get("enabled")), "snoozeSec": int(_num(r.get("snoozeSec"))),
                "visible": bool(r.get("visible", r.get("enabled")))}

    def set_settings(self, body):
        req = validate_settings(body)
        if self.dry_run:
            return {"ok": True, "dryRun": True}
        cfg = self._request("POST", "/v1/config", req)
        self._cache = (0.0, None)
        return normalize({"app": "ytm-beacon", "version": "dev", "config": cfg, "visible": True})["settings"]
