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
from .bridge import Bridge
from .config import DEFAULT_PORT, JsonFile, config_dir

STATIC = Path(__file__).parent / "static"
EQ_MIN, EQ_MAX = -12.0, 12.0
EQ_BANDS = ("bass", "mid", "high")
# The YT Music extension runs in a music.youtube.com page, so its requests carry that Origin.
CORS_ORIGINS = {"https://music.youtube.com"}
PUBLIC_PATHS = {"/manifest.json", "/icon.svg", "/api/info"}
COOKIE = "pf_token"


class App:
    """Everything a request handler needs. One instance per server (tests make their own)."""

    def __init__(self, audio, engine, media, prefs=None, token=None, allowed_hosts=()):
        self.audio, self.engine, self.media = audio, engine, media
        self.bridge = Bridge()
        self.prefs = prefs or JsonFile(config_dir() / "prefs.json")
        self.token = token
        self.allowed_hosts = {h.lower() for h in allowed_hosts}

    def start(self):
        for b in (self.audio, self.engine, self.media):
            b.start()

    def stop(self):
        for b in (self.audio, self.engine):
            b.stop()

    # ---- merged state ----
    def capabilities(self):
        return {
            "volume": bool(self.audio.snapshot().get("available")),
            "eq": bool(self.engine.capabilities.get("eq")),
            "meter": bool(self.engine.capabilities.get("meter")),
            "engine_actions": sorted(self.engine.actions),
            "engine_label": self.engine.label,
            "media": bool(self.media.snapshot().get("available")),
            "ytm_bridge": self.bridge.connected,
        }

    def state(self):
        eng = self.engine.snapshot()
        return {
            "version": __version__,
            "volume": self.audio.snapshot(),
            "media": self.media.snapshot(),
            "engine": {"connected": eng.get("connected", False), "level": eng.get("level", 0.0),
                       "eq": eng.get("eq", {}), "routing": eng.get("routing", {})},
            "capabilities": self.capabilities(),
            "limits": {"eq_min": EQ_MIN, "eq_max": EQ_MAX},
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

    def ytm_command(self, body):
        """Extension first (full YT Music control). Transport-only fallback to the host player."""
        if self.bridge.connected:
            return self.bridge.request(body)
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

    def _authorized(self, path, query):
        tok = self.app.token
        if not tok or path in PUBLIC_PATHS:
            return True
        if path.startswith("/ext/") and self.client_address[0] in ("127.0.0.1", "::1"):
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
            cookie = f"{COOKIE}={tok}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000"
            return self._send(302, "", "text/plain", [("Location", u.path or "/"), ("Set-Cookie", cookie)])
        if not self._authorized(u.path, query):
            if "text/html" in (self.headers.get("Accept") or ""):
                return self._send(401, LOGIN_PAGE, "text/html; charset=utf-8")
            return self._json(401, {"error": "token required"})
        return handler(u.path)

    # ---- GET ----
    def _get(self, path):
        app = self.app
        if path == "/ext/poll":  # extension long-poll: blocks until a command (or ~25s idle)
            return self._json(200, app.bridge.poll() or {})
        if path == "/api/ytm/state":
            return self._json(200, app.ytm_state())
        if path == "/api/prefs":
            return self._json(200, app.prefs.load())
        if path == "/api/state":
            return self._json(200, app.state())
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
            app.bridge.push_state(body)
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

        if path == "/api/eq":
            if not app.engine.capabilities.get("eq"):
                return self._json(501, {"error": "EQ is not available with this audio backend"})
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
