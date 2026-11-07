"""YTM Beacon bridge: show whether Discord presence is working, and switch it on or off, from the phone.

YTM Beacon is a separate program that shows the song playing in YouTube Music as a Discord "Listening to"
card. It listens on loopback only (ports 41713-41717), has no login of its own, and refuses anything that is
not a local program. So the phone never talks to it directly: the phone asks Pocketfader (behind its usual
security checks) and Pocketfader asks Beacon, here.

This module is a whitelist, not a proxy: exactly three operations (status, visibility, a few settings), every
input validated, and nothing Beacon returns about paths, its process or its Discord application id is
passed on. Playback information is never sent *into* Beacon; its browser extension already does that.
"""
import http.client
import json
import re
import threading
import time

PORTS = (41713, 41714, 41715, 41716, 41717)
HOST = "127.0.0.1"          # literal address: Beacon listens on IPv4 only, "localhost" may try ::1 first
TIMEOUT = 1.5               # Beacon is local, so slow means broken
MIN_VERSION = (0, 1, 4)
MAX_HIDE_MINUTES = 1440

# The only settings the phone may change, and what each accepts.
BOOL_SETTINGS = ("privateMode", "showPaused", "showProgress", "showStateIcon", "showButtons", "showAlbumArt")
STATUS_DISPLAY = ("name", "state", "details")


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


class Beacon:
    """Talks to a YTM Beacon on this computer. Thread safe; remembers which port answered."""

    def __init__(self, ports=PORTS, host=HOST, timeout=TIMEOUT, dry_run=False, cache_s=1.0):
        self.ports, self.host, self.timeout = tuple(ports), host, timeout
        self.dry_run = dry_run
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
