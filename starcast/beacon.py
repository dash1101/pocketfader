"""Discord status for the apps (GET /api/beacon, POST /api/beacon/...), and moving over from the old YTM Beacon app.

Discord status shows the song playing in YouTube Music as your Discord "Listening to" card. It is built in: the
engine is starcast.discord.Presence, fed by the browser extension's bridge. This module is what the apps talk to. It
checks every request, answers in the same shape as when Discord status was the separate YTM Beacon app (so apps
already on phones keep working), and keeps the engine away from Discord while that old app still runs on this
computer: two programs showing a status for the same Discord application would fight over the card.

While that old app runs, its settings are the ones in effect on Discord: they are copied over on every check, so that
however it is removed later, the built-in engine carries on with them (and with a timed hide it was told), and a
change made here meanwhile is refused instead of looking as if it worked.

The old app is only ever asked GET /v1/ping, GET /v1/config and GET /v1/status on 127.0.0.1, and the only program
ever run is its own "uninstall", found where its installer puts it. Nothing the apps send reaches a shell or another
program, and nothing about paths or this computer's processes is passed on to them.
"""
import http.client
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

from .discord import Presence, SettingsError, beacon_config_path
from .discord import settings as discord_store

log = logging.getLogger("starcast.discord")

PORTS = (41713, 41714, 41715, 41716, 41717)     # where the old YTM Beacon app listens (first free one)
HOST = "127.0.0.1"          # literal address: Beacon listens on IPv4 only, "localhost" may try ::1 first
TIMEOUT = 1.5               # Beacon is local, so slow means broken
MIN_VERSION = (0, 1, 4)
MAX_HIDE_MINUTES = 1440

# The settings the apps may change, and what each accepts. (enabled: through visibility; clientId: read-only.)
BOOL_SETTINGS = ("privateMode", "showPaused", "showProgress", "showStateIcon", "showButtons", "showAlbumArt")
STATUS_DISPLAY = ("name", "state", "details")
MAX_WORDS_SENT = 200        # the engine keeps at most 50 words of 80 characters; this only refuses absurd requests
MAX_WORD_SENT = 400

WATCH_EVERY = 10.0          # how often to look for the old app (it may be started or removed at any time)
GONE_WAIT = 5.0             # after its uninstall, how long to wait for its ports to close
UNINSTALL_TIMEOUT = 60.0
AD_TTL = 90.0               # an advert report this old no longer counts (a tab that vanished without a goodbye)

# The old installer card, kept so apps from before the merge never offer to install or start anything.
INSTALL_INFO = {"installed": True, "canInstall": False, "canStart": False, "latest": None, "updateAvailable": False,
                "job": {"state": "idle", "message": ""}}

BUILT_IN_MESSAGE = "Discord status is built into YTM Starcast now, so there is nothing to install."
BEACON_RUNNING_MESSAGE = ("The separate YTM Beacon app still shows the Discord status, so this can't be changed here. "
                          "Change it in YTM Beacon, or move over to YTM Starcast first.")


class BeaconError(Exception):
    """A request that can't be done. code: a short name for programs; message: plain words for people;
    http: the status code the server answers with."""

    def __init__(self, code, message="", http=502):
        super().__init__(message or code)
        self.code, self.message, self.http = code, message or code, http


class BadRequest(ValueError):
    """A request from an app that fails validation (nothing changes)."""


def parse_version(text):
    """'v0.1.4' -> (0, 1, 4); None for 'dev' or anything unparsable."""
    m = re.match(r"v?(\d+)\.(\d+)\.(\d+)", str(text or ""))
    return tuple(int(x) for x in m.groups()) if m else None


def version_ok(text):
    v = parse_version(text)
    return True if v is None else v >= MIN_VERSION     # local "dev" builds are assumed to be new enough


# ---- validation (app -> Discord status) ----

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
        elif k == "hideMatching":
            if not isinstance(v, list) or not all(isinstance(w, str) for w in v):
                raise BadRequest("hideMatching must be a list of words")
            if len(v) > MAX_WORDS_SENT or any(len(w) > MAX_WORD_SENT for w in v):
                raise BadRequest("hideMatching is too long (at most 50 words of 80 characters are kept)")
            v = list(v)
        elif k in ("clientId", "enabled"):
            raise BadRequest(f"{k} can't be changed here")
        else:
            raise BadRequest("unknown setting: " + str(k)[:40])
        out[k] = v
    return out


# ---- engine status -> apps ----

def headline(s, version_is_ok=True):
    """The status in one word, in the same order as YTM Beacon's own dashboard. s has YTM Beacon's /v1/status keys
    (the built-in engine's Presence.status() does), or is None."""
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


def builtin_headline(s, beacon_running=False, ad=False):
    """headline() plus two states of the built-in engine: "beacon-running" (the old YTM Beacon app still runs, so
    YTM Starcast leaves Discord to it) and "ad" (nothing to show because an advert is playing)."""
    if beacon_running:
        return "beacon-running"
    h = headline(s)
    return "ad" if h == "idle" and ad else h


def _num(v, default=0.0):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else default


def normalize(s, now=None):
    """The engine's raw status -> the small, stable shape the apps use. No paths, pid, tab ids or settings beyond
    the eight below (discord_settings() adds the word list and the application id where they are wanted)."""
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


def discord_settings(cfg):
    """The settings the apps show: normalize()'s eight, plus the "never show" words and the Discord application id
    ("" = the built-in one; shown, never changed from the apps)."""
    out = normalize({"version": "dev", "config": cfg, "visible": True})["settings"]
    words = cfg.get("hideMatching")
    out["hideMatching"] = [w for w in words if isinstance(w, str)] if isinstance(words, (list, tuple)) else []
    cid = cfg.get("clientId")
    out["clientId"] = cid if isinstance(cid, str) else ""
    return out


# ---- the old YTM Beacon app ----

def beacon_binaries(system=None, environ=None, home=None):
    """Where YTM Beacon's installer puts its program on this computer (whether or not it is there)."""
    env = os.environ if environ is None else environ
    system = (system or sys.platform).lower()
    home = Path(home) if home is not None else Path.home()
    windows = system.startswith("win")
    exe = "ytm-beacon.exe" if windows else "ytm-beacon"
    if env.get("YTM_BEACON_HOME"):
        return [Path(env["YTM_BEACON_HOME"]) / "bin" / exe]
    if windows:
        d = Path(env.get("LOCALAPPDATA") or home / "AppData" / "Local") / "ytm-beacon"
        return [d / "ytm-beacon.exe", d / "ytm-beacon-bg.exe"]
    return [home / ".local" / "bin" / "ytm-beacon"]     # Linux and macOS alike


def run_command(cmd, timeout):
    """Runs a program without a window or any input, and returns (exit code, its last words). Output goes to a
    temporary file rather than a pipe, so a helper the program leaves behind can never keep us waiting."""
    kw = {}
    if sys.platform == "win32":
        kw["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
    try:
        with tempfile.TemporaryFile() as out:
            try:
                code = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                      timeout=timeout, **kw).returncode
            except subprocess.TimeoutExpired:
                return 1, "it did not finish within %g seconds" % timeout
            out.seek(0)
            return code, out.read(64 << 10).decode("utf-8", "replace")
    except OSError as e:
        return 1, str(e)


class BeaconApp:
    """The separate YTM Beacon app: is it running (it answers GET /v1/ping on 127.0.0.1:41713-41717), is it
    installed (its program is where its installer puts it), and the steps of moving over from it. Thread safe.

    ports, binaries and runner are there for tests, which must never reach the real app or run its program."""

    def __init__(self, ports=PORTS, host=HOST, timeout=TIMEOUT, binaries=None, runner=None):
        self.ports, self.host, self.timeout = tuple(ports), host, timeout
        self._binaries = None if binaries is None else [Path(b) for b in binaries]
        self._runner = runner
        self._port = None
        self._lock = threading.Lock()
        self._info = (None, None)       # (time.monotonic() it was found out, info)

    # -- talking to it --
    def _call(self, port, method, path):
        conn = http.client.HTTPConnection(self.host, port, timeout=self.timeout)
        try:
            # No Origin header (Beacon refuses browsers, not local programs); Host stays 127.0.0.1:<port>.
            conn.request(method, path)
            resp = conn.getresponse()
            raw = resp.read(1 << 20)
            try:
                payload = json.loads(raw or b"{}")
            except ValueError:
                payload = None
            return resp.status, payload
        finally:
            conn.close()

    def _ping(self, port):
        """Beacon's version string when YTM Beacon answers on this port ("" if it gives none), else None. Other
        programs may sit on these ports, so the answer is checked."""
        try:
            code, body = self._call(port, "GET", "/v1/ping")
        except (OSError, http.client.HTTPException):
            return None
        if code == 200 and isinstance(body, dict) and body.get("app") == "ytm-beacon":
            v = body.get("version")
            return str(v)[:40] if isinstance(v, str) else ""
        return None

    def find(self):
        """(port, version) of the running YTM Beacon app, or None."""
        with self._lock:
            order = ([self._port] if self._port else []) + [p for p in self.ports if p != self._port]
            for p in order:
                v = self._ping(p)
                if v is not None:
                    self._port = p
                    return p, v
            self._port = None
            return None

    def _live(self, path):
        found = self.find()
        if not found:
            return None
        try:
            code, body = self._call(found[0], "GET", path)
        except (OSError, http.client.HTTPException):
            return None
        return body if code == 200 and isinstance(body, dict) else None

    def live_config(self):
        """Its settings as it uses them now (GET /v1/config), or None when it doesn't answer."""
        return self._live("/v1/config")

    def live_hide(self):
        """How many seconds are left of a timed hide it was told (GET /v1/status "snoozeSec"; 0 = none), or None when
        it doesn't answer. It keeps that hide in memory only, so it ends when the app quits or is removed."""
        s = self._live("/v1/status")
        if s is None:
            return None
        return max(0.0, min(_num(s.get("snoozeSec")), MAX_HIDE_MINUTES * 60.0))

    # -- its program --
    def binaries(self):
        return list(self._binaries) if self._binaries is not None else beacon_binaries()

    def binary(self):
        """Where its program is, or None."""
        for b in self.binaries():
            try:
                if b.is_file():
                    return b
            except OSError:
                continue
        return None

    def uninstall(self, exe):
        """Runs "<its program> uninstall" (no --purge: its settings and log stay). Returns (exit code, output)."""
        run = self._runner or run_command
        return run([str(exe), "uninstall"], UNINSTALL_TIMEOUT)

    # -- summary --
    def info(self, max_age=0.0):
        """{"running", "installed", "canMigrate", "version"}, found out again when older than max_age seconds."""
        at, cached = self._info
        if cached is not None and at is not None and time.monotonic() - at <= max_age:
            return dict(cached)
        found = self.find()
        installed = self.binary() is not None
        out = {"running": found is not None, "installed": installed, "canMigrate": installed,
               "version": (found[1] or None) if found else None}
        self._info = (time.monotonic(), out)
        return dict(out)

    def forget(self):
        self._info = (None, None)

    def wait_gone(self, seconds=GONE_WAIT, step=0.2):
        """Waits until no port answers any more. Returns True when it is gone."""
        end = time.monotonic() + seconds
        while True:
            if self.find() is None:
                self.forget()
                return True
            if time.monotonic() >= end:
                return False
            time.sleep(step)


def _scrub(text, home=None):
    """The last line of a program's output, without the home folder's path, short."""
    lines = [ln.strip() for ln in (text or "").strip().splitlines() if ln.strip()]
    last = lines[-1] if lines else ""
    home = str(home if home is not None else Path.home())
    if home and home != os.sep:
        last = last.replace(home, "~")
    return last[:200]


class DiscordStatus:
    """Everything the server needs for Discord status: the engine (starcast.discord.Presence), the old app, and the
    shapes the apps expect. It is also the server's presence hook (feed, touch, bye, enabled, set_enabled)."""

    def __init__(self, presence, beacon_app=None, dry_run=False, beacon_config=None, watch_every=WATCH_EVERY,
                 gone_wait=GONE_WAIT, clock=None):
        self.presence = presence
        self.beacon_app = beacon_app if beacon_app is not None else BeaconApp()
        self.dry_run = bool(dry_run)
        self.beacon_config = beacon_config        # YTM Beacon's config.json, read when moving over while it is stopped
        self.watch_every, self.gone_wait = watch_every, gone_wait
        self._clock = clock or time.monotonic
        self._ads = {}                            # tab -> when it last said an advert is playing
        self._ads_lock = threading.Lock()
        self._gate = threading.RLock()            # starting/stopping the engine
        self._moving = threading.Lock()           # one move at a time
        self._running = False                     # between start() and stop()
        self._yielding = False                    # the old app ran at the last check (for the log)
        self._beacon_hide_until = None            # when a timed hide the old app was told ends (self._clock time)
        self._beacon_file_seen = None             # the time stamp of its settings file when last looked at
        self._stop_watch = None
        self._watcher = None

    @classmethod
    def in_folder(cls, folder, dry_run=False, **kw):
        """The usual set-up: settings in <folder>/discord.json, taken over from YTM Beacon's own settings file the
        first time (when discord.json doesn't exist yet)."""
        beacon_cfg = beacon_config_path()
        presence = Presence(Path(folder) / "discord.json", dry_run=dry_run, import_beacon_from=beacon_cfg)
        return cls(presence, dry_run=dry_run, beacon_config=beacon_cfg, **kw)

    # ---- life cycle: the engine runs only while the old app doesn't ----
    def start(self):
        with self._gate:
            if self._running:
                return
            self._running = True
            self._stop_watch = stop = threading.Event()
        self._watcher = threading.Thread(target=self._watch, args=(stop,), name="discord-watch", daemon=True)
        self._watcher.start()

    def stop(self):
        with self._gate:
            self._running = False
            stop, self._stop_watch = self._stop_watch, None
        if stop is not None:
            stop.set()
        w, self._watcher = self._watcher, None
        if w is not None and w is not threading.current_thread():
            w.join(5)
        self.presence.stop()            # clears the card and disconnects

    def _watch(self, stop):
        while not stop.is_set():
            try:
                self.sync()
            except Exception:           # keep watching; report the problem
                log.exception("Discord status: checking for YTM Beacon failed")
            if stop.wait(self.watch_every):
                break

    def sync(self):
        """One check: stop the engine while the old app runs, start it when it doesn't. While the old app runs, its
        settings (and a timed hide it was told) are copied over, because they are the ones in effect on Discord:
        however it is removed later (moving over, its own uninstall, another installer), the engine carries on with
        them. Returns the old app's info."""
        info = self.beacon_app.info()
        if info["running"] and self._running:
            self._follow_beacon()           # outside the gate: it asks the old app
        with self._gate:
            if not self._running:
                return info
            if info["running"] != self._yielding:
                self._yielding = info["running"]
                log.info("Discord status: %s", "the separate YTM Beacon app is running, so YTM Starcast leaves Discord to it"
                         if info["running"] else "the separate YTM Beacon app has stopped, so YTM Starcast shows your status now")
            if info["running"]:
                if self.presence.running:
                    self.presence.stop()
            elif not self.presence.running:
                self._catch_up_beacon_file()
                self._take_over_hide()
                self.presence.start()
        return info

    def _follow_beacon(self):
        """The old app runs: copy the settings it uses now when they differ from ours, and note a timed hide."""
        live = self.beacon_app.live_config()
        if live is not None:
            try:
                theirs = discord_store.normalize(discord_store.decode_onto(discord_store.default_config(), live))
                if theirs != self.presence.get_config():
                    self.presence.import_beacon_config(live)
                    log.info("Discord status: copied the settings the separate YTM Beacon app uses now")
            except (SettingsError, OSError) as e:
                log.warning("Discord status: could not copy the settings of the separate YTM Beacon app: %s", e)
        self._note_hide(self.beacon_app.live_hide())

    def _note_hide(self, left):
        if left is not None:            # None: it didn't answer, so what was noted before stands
            self._beacon_hide_until = self._clock() + left if left > 0 else None

    def _take_over_hide(self):
        """A timed hide the old app was told carries on in the engine for the time it has left."""
        until, self._beacon_hide_until = self._beacon_hide_until, None
        if until is not None:
            left = until - self._clock()
            if left > 0:
                self.presence.engine.hide_for(min(left, MAX_HIDE_MINUTES * 60.0))
                log.info("Discord status: carried on with a timed hide from YTM Beacon (%d s left)", left)

    def _catch_up_beacon_file(self):
        """Before the engine starts: when the old app's settings file was saved after YTM Starcast's own settings, it
        was told something since (while it ran and nothing copied it, or just before it was removed), so its settings
        are taken over again. The newer change wins: settings changed here later are kept."""
        src = self.beacon_config
        if not src:
            return
        try:
            theirs = os.stat(src).st_mtime_ns
        except OSError:
            return
        if self._beacon_file_seen is not None and theirs <= self._beacon_file_seen:
            return                      # looked at already (a trial run saves nothing, so this keeps it to once)
        self._beacon_file_seen = theirs
        ours = self.presence.settings_path
        try:
            if ours and os.stat(ours).st_mtime_ns >= theirs:
                return
        except OSError:
            pass                        # never saved here: its settings it is, as on the first start
        try:
            self.presence.import_beacon_config(os.fspath(src))
            log.info("Discord status: took over the settings the separate YTM Beacon app saved last")
        except (SettingsError, OSError) as e:
            log.warning("Discord status: could not take over the settings of the separate YTM Beacon app: %s", e)

    def app_info(self):
        """The old app's info for the apps: from the last check while watching, else found out now."""
        return self.beacon_app.info(max_age=3 * self.watch_every if self._running else 0.0)

    # ---- what the extension reports (the server's presence hook) ----
    def feed(self, instance, now, agent=None, ts=None):
        if isinstance(now, dict):
            key = instance if isinstance(instance, str) and instance else ""
            with self._ads_lock:
                if now.get("ad") is True:
                    self._ads[key] = self._clock()
                else:
                    self._ads.pop(key, None)
        return self.presence.feed(instance, now, agent=agent, ts=ts)

    def touch(self, instance):
        return self.presence.touch(instance)

    def bye(self, instance):
        with self._ads_lock:
            self._ads.pop(instance if isinstance(instance, str) and instance else "", None)
        return self.presence.bye(instance)

    def ad_playing(self):
        now = self._clock()
        with self._ads_lock:
            for k in [k for k, t in self._ads.items() if now - t > AD_TTL]:
                del self._ads[k]
            return bool(self._ads)

    @property
    def enabled(self):
        """The Discord status switch (the server's parts.discord) is the engine's own "enabled" setting."""
        return self.presence.enabled

    def _refuse_while_beacon_runs(self):
        """While the old app runs, it shows the status with its own settings: a change here would look as if it worked
        but change nothing on Discord (and the next copy of its settings would undo it), so it is refused."""
        if self.app_info()["running"]:
            raise BeaconError("beacon_running", BEACON_RUNNING_MESSAGE, 409)

    def set_enabled(self, on):
        if isinstance(on, bool) and on != self.presence.enabled:
            self._refuse_while_beacon_runs()        # (switching it to what it already is changes nothing)
        try:
            return self.presence.set_enabled(on)
        except OSError as e:
            raise BeaconError("SAVE_FAILED", "Couldn't save the Discord status settings: " + str(e)[:160], 500) from None

    # ---- GET /api/beacon ----
    def status(self):
        raw = dict(self.presence.status())
        if not self.presence.running:   # stopped (the old app runs) or not started: whatever it said last is stale
            raw["discord"] = {"state": "disconnected"}
            raw["presenceActive"] = False
        app = self.app_info()
        out = normalize(raw)
        out["headline"] = builtin_headline(raw, app["running"], self.ad_playing())
        out["settings"] = discord_settings(raw.get("config") or {})
        out["beaconApp"] = app
        out["install"] = {**INSTALL_INFO, "job": dict(INSTALL_INFO["job"])}
        if self.dry_run:
            out["dryRun"] = True
        return out

    # ---- POST /api/beacon/visibility and /settings ----
    def set_visibility(self, body):
        req = validate_visibility(body)
        self._refuse_while_beacon_runs()
        try:
            return self.presence.visibility(req["action"], req.get("minutes", 0))
        except SettingsError as e:
            raise BadRequest(str(e)) from None
        except OSError as e:
            raise BeaconError("SAVE_FAILED", "Couldn't save the Discord status settings: " + str(e)[:160], 500) from None

    def set_settings(self, body):
        req = validate_settings(body)
        self._refuse_while_beacon_runs()
        try:
            return discord_settings(self.presence.set_config(req))
        except SettingsError as e:
            raise BadRequest(str(e)) from None
        except OSError as e:
            raise BeaconError("SAVE_FAILED", "Couldn't save the Discord status settings: " + str(e)[:160], 500) from None

    # ---- POST /api/beacon/install: nothing to install any more ----
    def start_install(self, body=None):
        raise BeaconError("built_in", BUILT_IN_MESSAGE, 409)

    # ---- POST /api/beacon/migrate ----
    def migrate(self):
        """Moves over from the old YTM Beacon app: takes its settings (as it uses them now, else its file), runs its
        own uninstall, waits until it has stopped, then lets the built-in engine show the status.
        Returns (HTTP status, reply)."""
        if self.dry_run:
            return 200, {"ok": True, "dryRun": True,
                         "message": "Trial run (--dry-run): nothing was moved, removed or changed."}
        if not self._moving.acquire(blocking=False):
            return 409, {"error": "busy", "message": "Already moving over from YTM Beacon."}
        try:
            return self._migrate()
        finally:
            self._moving.release()

    def _migrate(self):
        info = self.beacon_app.info()
        exe = self.beacon_app.binary()
        if exe is None:
            if not info["running"]:
                return 409, {"error": "nothing_to_move",
                             "message": "The separate YTM Beacon app isn't on this computer, so there is nothing to "
                                        "move over."}
            return 409, {"error": "beacon_not_found",
                         "message": "YTM Beacon is running, but its program isn't where its installer puts it, so it "
                                    "can't be removed from here. Remove it with its own \"ytm-beacon uninstall\" "
                                    "command; YTM Starcast then shows your status by itself."}

        # 1. its settings: as it uses them now, else its file. If they exist but can't be read, stop here: carrying
        #    on with other settings could show songs it was told to keep private.
        sources = []
        live = self.beacon_app.live_config() if info["running"] else None
        if live is not None:
            sources.append(live)
        if self.beacon_config and os.path.isfile(self.beacon_config):
            sources.append(os.fspath(self.beacon_config))
        problem = None
        for src in sources:
            try:
                self.presence.import_beacon_config(src)
                problem = None
                break
            except (SettingsError, OSError) as e:
                problem = problem or e
        if problem is not None:
            return 502, {"error": "import_failed",
                         "message": "Couldn't take over YTM Beacon's settings (%s), so nothing was changed."
                                    % _scrub(str(problem))}
        # ...and a timed hide it was told: it keeps that in memory only, so its uninstall would end it at once
        if info["running"]:
            self._note_hide(self.beacon_app.live_hide())

        # 2. its own uninstall (it quits first, which clears its Discord card; its settings file and log stay)
        code, out = self.beacon_app.uninstall(exe)
        self.beacon_app.forget()
        if code != 0:
            why = _scrub(out)
            return 502, {"error": "uninstall_failed",
                         "message": "YTM Beacon's own uninstall didn't finish" + (f" ({why})" if why else "") +
                                    ". Your settings were already copied to YTM Starcast. Try again, or run "
                                    "\"ytm-beacon uninstall\" yourself."}

        # 3. never two programs on the same Discord card: wait until it has gone
        if not self.beacon_app.wait_gone(self.gone_wait):
            return 502, {"error": "still_running",
                         "message": "YTM Beacon was removed but is still running. Close it (or restart the "
                                    "computer); YTM Starcast then shows your status by itself."}

        # 4. the built-in engine takes over (a check that saw it go may have started it already, hide included)
        with self._gate:
            self._take_over_hide()
            if self._running and not self.presence.running:
                self.presence.start()
        log.info("Discord status: moved over from YTM Beacon")
        return 200, {"ok": True,
                     "message": "Moved over: YTM Starcast now shows your music on Discord, with your YTM Beacon settings. "
                                "One step is left: remove the YTM Beacon extension from your browser's extensions "
                                "page."}
