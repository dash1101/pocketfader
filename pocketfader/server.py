"""HTTP server: the web app, a small JSON API, and the relay to the YT Music extension.

SECURITY MODEL (read before changing bind logic):
  * Default: bind loopback + the Tailscale address only. The tailnet is the auth boundary.
  * Anything else (--lan / --bind) REQUIRES an access token, because a LAN or the open
    internet is not an auth boundary. Never bind 0.0.0.0 without a token.
  * Browsers on the same machine can be tricked into talking to localhost, so every request
    is checked for a sane Host header (anti DNS-rebinding) and every POST for a same-origin
    (or allow-listed) Origin (anti CSRF).
"""
import hmac
import ipaddress
import json
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from . import __version__
from .beacon import Beacon, BadRequest, BeaconError
from .bridge import Bridge
from .lyrics import LyricsService
from .updater import Updater
from .config import DEFAULT_PORT, JsonFile, config_dir
from .opener import Opener, music_url, valid_id
from .audio import AudioFetcher, TYPES as AUDIO_TYPES, parse_range

STATIC = Path(__file__).parent / "static"
EQ_MIN, EQ_MAX = -12.0, 12.0
EQ_BANDS = ("bass", "mid", "high")
# The YT Music extension runs in a music.youtube.com page, so its requests carry that Origin.
CORS_ORIGINS = {"https://music.youtube.com"}
PUBLIC_PATHS = {"/manifest.json", "/icon.svg", "/icon-192.png", "/icon-512.png", "/api/info"}
COOKIE = "pf_token"


class App:
    """Everything a request handler needs. One instance per server (tests make their own)."""

    def __init__(self, audio, engine, media, prefs=None, token=None, allowed_hosts=(), lyrics=None, updater=None, beacon=None, opener=None, audio_fetcher=None):
        self.audio, self.engine, self.media = audio, engine, media
        self.bridge = Bridge()
        self.lyrics = lyrics or LyricsService(ytm_lyrics=self._ytm_lyrics)
        self.updater = updater or Updater()
        self.beacon = beacon or Beacon()
        self.opener = opener or Opener()
        self.fetcher = audio_fetcher or AudioFetcher()
        self._stop_watch = threading.Event()
        self._was_playing = False
        self.prefs = prefs or JsonFile(config_dir() / "prefs.json")
        self.token = token
        self.allowed_hosts = {h.lower() for h in allowed_hosts}
        self._sleep_lock = threading.Lock()
        self._sleep_timer = None
        self._sleep_at = None

    def start(self):
        for b in (self.audio, self.engine, self.media):
            b.start()
        threading.Thread(target=self._watch_others, name="pause-others", daemon=True).start()

    def stop(self):
        self._stop_watch.set()
        for b in (self.audio, self.engine):
            b.stop()

    # ---- merged state ----
    def capabilities(self):
        return {
            "volume": bool(self.audio.snapshot().get("available")),
            "eq": bool(self.engine.capabilities.get("eq")),
            "eq_toggle": bool(self.engine.capabilities.get("eq_toggle")),
            "meter": bool(self.engine.capabilities.get("meter")),
            "outputs": hasattr(self.audio, "outputs"),
            "engine_actions": sorted(self.engine.actions),
            "engine_label": self.engine.label,
            "media": bool(self.media.snapshot().get("available")),
            "ytm_bridge": self.bridge.connected,
            "lyrics": True,
            "beacon": True,
            "sleep": True,
            "audio": self.fetcher.available,
        }

    def state(self):
        eng = self.engine.snapshot()
        return {
            "version": __version__,
            "volume": self.audio.snapshot(),
            "media": self.media.snapshot(),
            "engine": {**eng, "connected": eng.get("connected", False), "level": eng.get("level", 0.0),
                       "eq": eng.get("eq", {}), "routing": eng.get("routing", {})},
            "capabilities": self.capabilities(),
            "limits": {"eq_min": EQ_MIN, "eq_max": EQ_MAX},
            "sleep": {"remainingSec": self.sleep_left()},
        }

    def ytm_state(self):
        """YT Music extension state when it's connected; otherwise whatever the host's
        media player reports, shaped the same way so the UI needs no special case."""
        if self.bridge.connected:
            return {**self.bridge.get_state(), "source": "ytm"}
        m = self.media.snapshot()
        if not m.get("available"):
            return {"connected": False, "source": None}
        art = m.get("art") or ""
        return {
            "connected": True, "source": "media", "queue": [],
            "now": {"title": m.get("title", ""), "artist": m.get("artist", ""),
                    "album": m.get("album", ""), "art": art if art.startswith(("http://", "https://")) else "",
                    "playing": bool(m.get("playing")), "position": m.get("position", 0),
                    "duration": m.get("duration", 0), "app": m.get("name") or m.get("app")},
        }

    # ---- sleep timer: pause playback after a while (or at the end of the current song) ----
    def sleep_left(self):
        """Seconds until the sleep timer pauses playback; 0 when none is set."""
        at = self._sleep_at
        return max(0, int(round(at - time.time()))) if at else 0

    def set_sleep(self, seconds):
        """seconds: how long until playback is paused; None or 0 cancels the timer."""
        with self._sleep_lock:
            if self._sleep_timer:
                self._sleep_timer.cancel()
            self._sleep_timer, self._sleep_at = None, None
            if seconds:
                self._sleep_at = time.time() + seconds
                t = threading.Timer(seconds, self._sleep_fire)
                t.daemon = True
                t.start()
                self._sleep_timer = t

    def _sleep_fire(self):
        with self._sleep_lock:
            self._sleep_timer, self._sleep_at = None, None
        try:
            if self.bridge.connected:
                if (self.bridge.get_state().get("now") or {}).get("playing"):
                    self.bridge.request({"type": "transport", "action": "pause"})
            elif self.media.snapshot().get("playing"):
                self.media.play_pause()
        except Exception:
            pass

    def sleep_for_track_end(self):
        """Seconds left in the current song, or None when that isn't known."""
        now = (self.ytm_state().get("now") or {})
        dur, pos = now.get("duration") or 0, now.get("position") or 0
        left = dur - pos
        return int(left) + 1 if dur and left > 0 else None

    def save_diag(self, diag):
        """The extension's one-off description of how the YouTube Music page is built (element names, never your data),
        kept next to the config so a missing control can be diagnosed. Overwritten at each page load."""
        try:
            f = config_dir() / "ytm-layout.json"
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(json.dumps(diag, indent=1)[:400_000])
            f.chmod(0o600)
        except OSError:
            pass

    def warm_lyrics(self, state):
        """When the track changes, look its lyrics (and the next couple of tracks') up in the background,
        so they are already there when someone opens the lyrics."""
        try:
            now = state.get("now") or {}
            vid = now.get("videoId")
            if not vid or vid == getattr(self, "_warm_vid", None):
                return
            self._warm_vid = vid
            self.lyrics.prefetch(now.get("title", ""), now.get("artist", ""), "", now.get("duration") or 0, vid)
            rows = [q for q in (state.get("queue") or []) if q.get("section", "queue") == "queue"]
            cur = next((i for i, q in enumerate(rows) if q.get("selected")), -1)
            for q in rows[cur + 1:cur + 3] if cur >= 0 else []:
                secs = 0
                for part in str(q.get("duration") or "0").split(":"):
                    secs = secs * 60 + (int(part) if part.isdigit() else 0)
                self.lyrics.prefetch(q.get("title", ""), q.get("artist", ""), "", secs, q.get("videoId", ""))
        except Exception:
            pass

    def _ytm_lyrics(self, video_id):
        """The YouTube Music page's own lyrics, via the extension (None when it isn't connected)."""
        if not self.bridge.connected:
            return None
        return self.bridge.request({"type": "lyrics", "videoId": video_id}, timeout=8.0)

    # ---- other players ----
    def pause_others(self):
        """Pause every other media player that is playing on this computer (not the YouTube Music song that is playing)."""
        fn = getattr(self.media, "pause_others", None)
        if not fn:
            return {"paused": [], "supported": False}
        now = (self.bridge.get_state().get("now") or {}) if self.bridge.connected else {}
        return {"paused": fn(now.get("title") or "", now.get("artist") or ""), "supported": True}

    def _watch_others(self):
        """With the 'pauseOthers' preference on, other players are paused when YouTube Music starts playing."""
        while not self._stop_watch.wait(1.0):
            try:
                playing = bool(self.bridge.connected and (self.bridge.get_state().get("now") or {}).get("playing"))
                if playing and not self._was_playing and self.prefs.load().get("pauseOthers"):
                    self.pause_others()
                self._was_playing = playing
            except Exception:
                pass

    def ytm_command(self, body):
        """Extension first (full YT Music control). Transport-only fallback to the host player."""
        if self.bridge.connected:
            return self.bridge.request(body)
        # No YouTube Music tab: opening the song (or the home page) in the browser starts one.
        t = body.get("type")
        try:
            if t in ("play", "startMix") and valid_id(body.get("videoId")):
                url = music_url(body["videoId"], body.get("playlistId") if valid_id(body.get("playlistId")) else None)
            elif t == "startPlaylist" and valid_id(body.get("playlistId")):
                url = music_url(playlist_id=body["playlistId"])
            elif t == "open":
                url = music_url()
            else:
                url = None
        except ValueError:
            url = None
        if url:
            return {"ok": self.opener.open(url), "opened": True}
        if body.get("type") == "transport":
            act = body.get("action")
            if act == "playpause":
                return {"ok": self.media.play_pause()}
            if act == "next":
                return {"ok": self.media.next()}
            if act == "prev":
                return {"ok": self.media.prev()}
            if act == "seek" and isinstance(body.get("position"), (int, float)):
                return {"ok": self.media.seek(body["position"])}
        return {"error": "extension_offline"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    @property
    def app(self) -> App:
        return self.server.app

    def log_message(self, *args):
        pass

    # ---- plumbing ----
    def _cors(self):
        origin = self.headers.get("Origin")
        if origin in CORS_ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")

    def _send(self, code, body, ctype, headers=()):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self._cors()
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj, headers=()):
        self._send(code, json.dumps(obj), "application/json", headers)

    def _guard(self, fn):
        """Never drop a connection on an unexpected error -- a phone waiting on a reply just
        hangs, which reads as 'the app froze'. Always answer with something."""
        try:
            fn()
        except Exception as e:  # noqa: BLE001 - deliberate: outermost boundary
            try:
                self._json(500, {"error": "server error", "detail": str(e)[:200]})
            except Exception:
                pass

    def do_OPTIONS(self):
        self.send_response(204)
        if self.headers.get("Origin") in CORS_ORIGINS:
            self._cors()
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._guard(lambda: self._dispatch(self._get))

    def do_POST(self):
        self._guard(lambda: self._dispatch(self._post))

    # ---- security gates ----
    def _host_ok(self):
        host = (self.headers.get("Host") or "").strip().lower()
        if not host:
            return True
        if host.startswith("["):                 # [::1]:8777
            name = host[1:host.index("]")] if "]" in host else host
        elif host.count(":") == 1:               # name:port or 1.2.3.4:port
            name = host.split(":")[0]
        else:                                    # bare name, or a bare IPv6 literal
            name = host
        try:
            ipaddress.ip_address(name)
            return True
        except ValueError:
            pass
        return (name == "localhost" or name.endswith((".ts.net", ".local", ".lan", ".home.arpa"))
                or name == socket.gethostname().lower() or name in self.app.allowed_hosts)

    def _origin_ok(self):
        origin = self.headers.get("Origin")
        if not origin:  # non-browser clients (curl, the native app) send none
            return True
        if origin in CORS_ORIGINS:
            return True
        host = self.headers.get("Host", "")
        return origin in (f"http://{host}", f"https://{host}")

    def _token_from_request(self, query):
        auth = self.headers.get("Authorization", "")
        if auth.lower().startswith("bearer "):
            return auth[7:].strip()
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == COOKIE:
                return v
        return (query.get("token") or [""])[0]

    FORWARD_HEADERS = ("X-Forwarded-For", "Forwarded", "X-Real-IP", "CF-Connecting-IP", "CF-Ray", "CF-Visitor")

    def _proxied(self):
        """True when the request came through a reverse proxy / tunnel (cloudflared, nginx...).
        Those arrive from 127.0.0.1 too, so loopback alone must not be trusted."""
        return any(self.headers.get(h) for h in self.FORWARD_HEADERS)

    def _https(self):
        return (self.headers.get("X-Forwarded-Proto", "").lower() == "https"
                or '"https"' in (self.headers.get("CF-Visitor") or ""))

    def _authorized(self, path, query):
        tok = self.app.token
        if not tok or path in PUBLIC_PATHS:
            return True
        if path.startswith("/ext/") and self.client_address[0] in ("127.0.0.1", "::1") \
                and not self._proxied():
            return True  # the extension runs on this machine, outside any browser session
        return hmac.compare_digest(self._token_from_request(query), tok)

    def _dispatch(self, handler):
        if not self._host_ok():
            return self._json(421, {"error": "unexpected Host header"})
        if self.command == "POST" and not self._origin_ok():
            return self._json(403, {"error": "cross-origin request refused"})
        u = urlsplit(self.path)
        query = parse_qs(u.query)
        tok = self.app.token
        # Link with ?token=... on a page load: set the cookie and redirect to the clean URL,
        # so the secret doesn't linger in the address bar or browser history.
        if tok and self.command == "GET" and "token" in query and \
                hmac.compare_digest(query["token"][0], tok):
            cookie = f"{COOKIE}={tok}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000" + ("; Secure" if self._https() else "")
            return self._send(302, "", "text/plain", [("Location", u.path or "/"), ("Set-Cookie", cookie)])
        if not self._authorized(u.path, query):
            if "text/html" in (self.headers.get("Accept") or ""):
                return self._send(401, LOGIN_PAGE, "text/html; charset=utf-8")
            return self._json(401, {"error": "token required"})
        return handler(u.path)

    def _lyrics(self, q):
        one = lambda k, d="": (q.get(k) or [d])[0]  # noqa: E731
        title, artist, album = one("title").strip(), one("artist").strip(), one("album").strip()
        if not title or max(len(title), len(artist), len(album)) > 300:
            return self._json(400, {"error": "title is required (and everything under 300 characters)"})
        try:
            dur = float(one("duration", "0") or 0)
        except ValueError:
            return self._json(400, {"error": "duration must be a number of seconds"})
        if not 0 <= dur <= 36000:
            return self._json(400, {"error": "duration out of range"})
        out = self.app.lyrics.lookup(title, artist, album, dur, video_id=one("videoId")[:32],
                                     source=one("source") or None, refresh=one("refresh") == "1")
        return self._json(200, out)

    def _audio(self, vid):
        """A song's audio for the phone, with Range support so it can start playing before the end arrives."""
        try:
            f = self.app.fetcher.get(vid)
        except ValueError:
            return self._json(400, {"error": "bad video id"})
        except RuntimeError as e:
            return self._json(502, {"error": str(e)})
        size = f.stat().st_size
        ctype = AUDIO_TYPES.get(f.suffix, "application/octet-stream")
        rng = parse_range(self.headers.get("Range"), size)
        start, end = rng if rng else (0, size - 1)
        self.send_response(206 if rng else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if rng:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        with open(f, "rb") as fh:
            fh.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = fh.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    # ---- GET ----
    def _get(self, path):
        app = self.app
        if path == "/ext/poll":  # extension long-poll: blocks until a command (or ~25s idle)
            inst = (parse_qs(urlsplit(self.path).query).get("i") or [None])[0]
            return self._json(200, app.bridge.poll(instance=inst[:64] if inst else None) or {})
        if path == "/api/ytm/state":
            return self._json(200, app.ytm_state())
        if path == "/api/lyrics":
            return self._lyrics(parse_qs(urlsplit(self.path).query))
        if path == "/api/update":
            q = parse_qs(urlsplit(self.path).query)
            info = dict(app.updater.latest(refresh=(q.get("refresh") or [""])[0] == "1"))
            info["self_update"] = app.updater.can_update_self()
            return self._json(200, info)
        if path.startswith("/api/media/audio/"):
            return self._audio(path.rsplit("/", 1)[-1])
        if path == "/api/update/apk":
            try:
                f = app.updater.apk()
            except Exception as e:
                return self._json(502, {"error": str(e)})
            return self._send(200, f.read_bytes(), "application/vnd.android.package-archive",
                              [("Content-Disposition", f'attachment; filename="{f.name}"')])
        if path == "/api/beacon":
            try:
                return self._json(200, app.beacon.status_with_install())
            except BeaconError as e:
                return self._json(502, {"error": e.message})
        if path == "/api/prefs":
            return self._json(200, app.prefs.load())
        if path == "/api/state":
            return self._json(200, app.state())
        if path == "/api/outputs":
            if not hasattr(app.audio, "outputs"):
                return self._json(501, {"error": "output switching is not available with this audio backend"})
            outs = app.audio.outputs()
            return self._json(200, {"outputs": outs, "default": next((o["name"] for o in outs if o["default"]), None)})
        if path == "/api/info":
            return self._json(200, {"name": "pocketfader", "version": __version__,
                                    "api": 1, "auth": bool(app.token)})

        name = {"/": "index.html"}.get(path, path.lstrip("/"))
        f = (STATIC / name).resolve()
        if not f.is_relative_to(STATIC.resolve()) or not f.is_file():
            return self._send(404, "not found", "text/plain")
        ctype = {".html": "text/html; charset=utf-8", ".json": "application/manifest+json",
                 ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml",
                 ".png": "image/png"}.get(f.suffix, "application/octet-stream")
        return self._send(200, f.read_bytes(), ctype)

    # ---- POST ----
    def _post(self, path):
        app = self.app
        try:
            n = int(self.headers.get("Content-Length", 0))
            if n > 1_000_000:
                return self._json(413, {"error": "body too large"})
            body = json.loads(self.rfile.read(n) or "{}")
        except ValueError:
            return self._json(400, {"error": "bad json"})
        if not isinstance(body, dict):
            return self._json(400, {"error": "body must be a JSON object"})

        # ---- extension -> server ----
        if path == "/ext/state":
            diag = body.pop("diag", None)
            if isinstance(diag, dict):
                app.save_diag(diag)
            app.bridge.push_state(body)
            app.warm_lyrics(body)
            return self._json(200, {"ok": True})
        if path == "/ext/result":
            app.bridge.deliver_result(body.get("id"), body.get("data"))
            return self._json(200, {"ok": True})

        # ---- client -> (extension | host player) ----
        # e.g. {"type":"play","videoId":".."}, {"type":"search","query":".."},
        # {"type":"transport","action":"next"}; see docs/API.md.
        if path == "/api/ytm":
            if not isinstance(body.get("type"), str):
                return self._json(400, {"error": "type must be a string"})
            return self._json(200, app.ytm_command(body))

        if path in ("/api/beacon/visibility", "/api/beacon/settings", "/api/beacon/install"):
            try:
                if path.endswith("visibility"):
                    return self._json(200, app.beacon.set_visibility(body))
                if path.endswith("install"):
                    return self._json(200, app.beacon.start_install(body))
                return self._json(200, app.beacon.set_settings(body))
            except BadRequest as e:
                return self._json(400, {"error": str(e)})
            except BeaconError as e:
                if e.code == "NOT_RUNNING":
                    return self._json(503, {"error": "YTM Beacon isn't running on the computer"})
                return self._json(502, {"error": e.message})

        if path == "/api/media/pause-others":
            return self._json(200, app.pause_others())

        if path == "/api/sleep":
            # {"minutes": 1..720} pauses playback after that long; {"minutes": "track"} at the end of this song;
            # {"minutes": 0} (or null) cancels
            m = body.get("minutes")
            if m in (0, None):
                app.set_sleep(None)
            elif m == "track":
                left = app.sleep_for_track_end()
                if left is None:
                    return self._json(409, {"error": "the length of the current song isn't known"})
                app.set_sleep(left)
            elif isinstance(m, int) and not isinstance(m, bool) and 1 <= m <= 720:
                app.set_sleep(m * 60)
            else:
                return self._json(400, {"error": "minutes must be 1 to 720, \"track\", or 0 to cancel"})
            return self._json(200, {"remainingSec": app.sleep_left()})

        if path == "/api/volume":
            if not app.audio.snapshot().get("available"):
                return self._json(503, {"error": "no audio output available"})
            if "delta" in body:  # relative bump, used by hardware volume keys
                d = body["delta"]
                if not isinstance(d, (int, float)) or isinstance(d, bool):
                    return self._json(400, {"error": "delta must be a number"})
                cur = app.audio.snapshot().get("percent") or 0
                app.audio.set_percent(max(0, min(100, cur + d)))
            elif "percent" in body:
                p = body["percent"]
                if not isinstance(p, (int, float)) or isinstance(p, bool) or not 0 <= p <= 100:
                    return self._json(400, {"error": "percent must be 0-100"})
                app.audio.set_percent(p)
            if "muted" in body:
                app.audio.set_mute(bool(body["muted"]))
            return self._json(200, {"ok": True})

        if path == "/api/prefs":
            return self._json(200, app.prefs.merge(body))

        if path == "/api/engine":
            fn = app.engine.actions.get(body.get("action"))
            if fn is None:
                return self._json(400, {"error": "unknown action"})
            return self._json(200, fn() or {"ok": True})

        if path in ("/api/output", "/api/output/volume"):
            if not hasattr(app.audio, "set_output"):
                return self._json(501, {"error": "output switching is not available with this audio backend"})
            name = body.get("name")
            if not isinstance(name, str) or not name:
                return self._json(400, {"error": "name must be a string"})
            if path == "/api/output":
                # With the EQ on, move the EQ in front of the new device instead of bypassing it.
                if getattr(app.engine, "enabled", False) and hasattr(app.engine, "retarget"):
                    ok = app.engine.retarget(name)
                else:
                    ok = app.audio.set_output(name)
                return self._json(200 if ok else 404, {"ok": bool(ok)} if ok else {"error": "unknown output"})
            ok = True
            if "percent" in body:
                p = body["percent"]
                if not isinstance(p, (int, float)) or isinstance(p, bool) or not 0 <= p <= 100:
                    return self._json(400, {"error": "percent must be 0-100"})
                ok = app.audio.set_output_volume(name, p) and ok
            if "muted" in body:
                ok = app.audio.set_output_mute(name, bool(body["muted"])) and ok
            return self._json(200 if ok else 404, {"ok": bool(ok)} if ok else {"error": "unknown output"})

        if path == "/api/eq":
            if not app.engine.capabilities.get("eq"):
                return self._json(501, {"error": "EQ is not available with this audio backend"})
            if "enabled" in body:
                if not hasattr(app.engine, "enable"):
                    return self._json(400, {"error": "this EQ has no on/off switch"})
                ok, why = app.engine.enable() if body["enabled"] else app.engine.disable()
                if not ok:
                    return self._json(409, {"error": why or "could not change the EQ"})
                if "band" not in body and "preset" not in body:
                    return self._json(200, {"ok": True})
            if "preset" in body:
                if not hasattr(app.engine, "set_preset") or not app.engine.set_preset(body["preset"]):
                    return self._json(400, {"error": "unknown preset"})
                return self._json(200, {"ok": True})
            band, db = body.get("band"), body.get("db")
            if band not in EQ_BANDS:
                return self._json(400, {"error": f"band must be one of {sorted(EQ_BANDS)}"})
            if not isinstance(db, (int, float)) or isinstance(db, bool) or not EQ_MIN <= db <= EQ_MAX:
                return self._json(400, {"error": f"db must be {EQ_MIN}..{EQ_MAX}"})
            app.engine.set_eq(band, db)
            return self._json(200, {"ok": True})

        if path == "/api/session":
            sess = body.get("app")  # null / omitted => back to auto-pick
            if sess is not None and not isinstance(sess, str):
                return self._json(400, {"error": "app must be a string or null"})
            app.media.pin(sess)
            return self._json(200, {"ok": True, "pinned": sess})

        if path == "/api/media":
            action = body.get("action")
            fn = {"playpause": app.media.play_pause, "next": app.media.next,
                  "prev": app.media.prev}.get(action)
            if fn:
                return self._json(200, {"ok": fn()})
            if action == "seek":
                pos = body.get("position")
                if not isinstance(pos, (int, float)) or isinstance(pos, bool) or pos < 0:
                    return self._json(400, {"error": "position must be >= 0"})
                return self._json(200, {"ok": app.media.seek(pos)})
            return self._json(400, {"error": f"unknown action: {action}"})

        return self._send(404, "not found", "text/plain")


LOGIN_PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Pocketfader</title><body style="font:16px system-ui;background:#0d1117;color:#e6edf3;
display:grid;place-items:center;min-height:100vh;margin:0"><form style="max-width:320px;padding:24px">
<h2 style="margin:0 0 8px">Pocketfader</h2><p style="color:#9aa4b2">Enter the access token printed by
the server (or open the full link it shows).</p>
<input name=token autofocus autocomplete=off style="width:100%;padding:12px;border-radius:8px;border:1px solid #30363d;
background:#161b22;color:inherit;box-sizing:border-box"><button style="margin-top:12px;width:100%;padding:12px;
border:0;border-radius:8px;background:#58a6ff;color:#08101a;font-weight:600">Connect</button></form>"""


# ---------------------------------------------------------------- networking helpers

def _tailscale_exe():
    exe = shutil.which("tailscale")
    if not exe and sys.platform == "win32":
        win = r"C:\Program Files\Tailscale\tailscale.exe"
        exe = win if Path(win).exists() else None
    return exe


def tailscale_installed():
    return _tailscale_exe() is not None


def tailscale_ip():
    """This machine's Tailscale IPv4 address, or None (not installed, or not connected yet)."""
    exe = _tailscale_exe()
    if not exe:
        return None
    try:
        out = subprocess.run([exe, "ip", "-4"], capture_output=True, text=True, timeout=4)
    except (OSError, subprocess.SubprocessError):
        return None
    lines = [l.strip() for l in out.stdout.splitlines() if l.strip()]
    return lines[0] if lines else None


def bind_when_tailscale_up(app, port, announce, attempts=100, delay=3.0):
    """Tailscale often isn't up yet at boot. Rather than delaying the whole server, serve on
    loopback right away and join the tailnet address in the background once it appears."""
    def run():
        for _ in range(attempts):
            time.sleep(delay)
            ip = tailscale_ip()
            if ip:
                try:
                    serve_on(app, ip, port)
                    announce(ip)
                except OSError as e:
                    print(f"could not bind {ip}:{port} -- {e}")
                return
    threading.Thread(target=run, name="tailscale-wait", daemon=True).start()


def lan_ip():
    """This machine's primary private address, or None. UDP connect() sends no packets; it
    just asks the OS which interface it would route through."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
        return ip if ipaddress.ip_address(ip).is_private else None
    except OSError:
        return None


def serve_on(app, host, port):
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.app = app
    threading.Thread(target=httpd.serve_forever, name=f"http-{host}", daemon=True).start()
    return httpd


def new_token():
    return secrets.token_urlsafe(18)
