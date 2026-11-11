"""Presence: the one object the server uses for Discord status. See the package docstring for the API."""
import logging
import os
import platform
import sys
import threading
import time

from .. import __version__
from . import ipc
from . import settings as st
from .build import DEFAULT_ASSETS, AssetURLs
from .engine import MIN_BACKOFF, Engine
from .settings import DEFAULT_CLIENT_ID, SettingsError
from .tracker import STATE_PAUSED, STATE_PLAYING, TEST_SOURCE, SanitizeError, Tracker, Update, quiet_after, truncate

log = logging.getLogger("starcast.discord")

MAX_HIDE_MINUTES = 24 * 60
MAX_BODY = 64 << 10
MARKER_NAME = "discord-extension-seen"
LEGACY_SOURCE = "extension"        # the tab of an old extension that does not name itself
SAMPLE_SKEW = 5.0                  # trust the page's own sample time when it is this close to ours
DRY_RUN_MESSAGE = "Dry-run mode: Discord is not contacted."


def go_os(system=None):
    s = system or sys.platform
    if s.startswith("win"):
        return "windows"
    if s.startswith("linux"):
        return "linux"
    return s


def go_arch(machine=None):
    m = (machine or platform.machine() or "").lower()
    return {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64", "i386": "386",
            "i686": "386", "x86": "386", "armv7l": "arm", "armv6l": "arm"}.get(m, m)


def agent_from_user_agent(ua):
    """A short browser name from a User-Agent header (Brave reports itself as Chrome)."""
    ua = ua or ""
    if "Firefox/" in ua:
        return "Firefox"
    if "Edg/" in ua:
        return "Edge"
    if "OPR/" in ua:
        return "Opera"
    if "Chrome/" in ua:
        return "Chrome"
    return "Browser" if ua else ""


def _text(v):
    return v if isinstance(v, str) else ""


def _number(v):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else 0.0


class Presence:
    def __init__(self, settings_path, client_id=None, asset_base=None, dial=None, clock=None, dry_run=False,
                 import_beacon_from=None, marker_path=None):
        self.settings_path = os.fspath(settings_path) if settings_path else ""
        self.clock = clock or time.time
        self.dry_run = bool(dry_run)
        self.started_at = self.clock()
        self.settings_error = None
        self.imported_from = None

        if self.settings_path:
            self.store, err = st.load(self.settings_path)
            if self.dry_run:
                self.store.path = ""        # a trial run writes nothing: changes are kept in memory only
        else:
            self.store, err = st.Store(st.default_config()), None
        if err is not None:
            log.warning("Discord status settings problem: %s", err)
            self.settings_error = str(err)

        self.tracker = Tracker(self.clock)
        self.assets = AssetURLs(asset_base) if asset_base else DEFAULT_ASSETS
        if self.dry_run:
            dial_fn = self._dry_dial
        elif dial is not None:
            dial_fn = dial
        else:
            dial_fn = self._real_dial
        self.engine = Engine(self.store, self.tracker, dial_fn, self.clock, self.assets,
                             default_client_id=client_id or DEFAULT_CLIENT_ID)

        if marker_path is not None:
            self._marker = os.fspath(marker_path)
        elif self.settings_path:
            self._marker = os.path.join(os.path.dirname(os.path.abspath(self.settings_path)), MARKER_NAME)
        else:
            self._marker = ""
        self._ever = False

        if self.settings_path and import_beacon_from and not os.path.lexists(self.settings_path):
            src = os.fspath(import_beacon_from)
            if os.path.isfile(src):
                try:
                    self.import_beacon_config(src)
                    log.info("Discord status: imported the settings of YTM Beacon")
                except (SettingsError, OSError) as e:
                    log.warning("Discord status: could not import YTM Beacon's settings: %s", e)

        self._run_lock = threading.Lock()
        self._thread = None
        self._stop = None

    # ---- dialers ----
    def _real_dial(self, client_id):
        return ipc.connect(client_id, stop=self._stop)

    def _dry_dial(self, client_id):
        raise ipc.IPCError(DRY_RUN_MESSAGE)

    # ---- life cycle ----
    def start(self):
        """Starts the engine thread (connects to Discord and keeps it up to date). Calling it again is harmless."""
        with self._run_lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop = threading.Event()
            self.engine.next_dial = None
            self.engine.backoff = MIN_BACKOFF
            self._thread = threading.Thread(target=self.engine.run, args=(self._stop,), name="ytm-starcast-discord",
                                            daemon=True)
            self._thread.start()

    def stop(self, timeout=15.0):
        """Clears the card, disconnects and waits for the engine thread. Can be started again later."""
        with self._run_lock:
            t, s = self._thread, self._stop
            self._thread = None
        if s is not None:
            s.set()
            self.engine.wake()
        if t is not None:
            t.join(timeout)

    @property
    def running(self):
        t = self._thread
        return t is not None and t.is_alive()

    # ---- what the browser reports ----
    def update_from_bridge(self, instance, now, agent=None, ts=None):
        """Feeds one report from the extension bridge (POST /ext/state: body["instance"], body["now"]). Call it
        for every tab's push, not only the tab the remote follows: choosing between tabs is done here.

        now uses the bridge's fields: playing / paused / buffering (bools), title, artist, album, art, videoId,
        position, duration (seconds) and ad (bool). playing or buffering counts as playing, anything else as
        paused. An advert (ad true), or nothing loaded (no title), clears that tab; now=None (a push without a
        "now") changes nothing. ts is the page's sample time in milliseconds (body["ts"]); it is used when it is
        within 5 s of this computer's clock. Returns True when a song was recorded, False when the tab was
        cleared or the report ignored."""
        source = instance if isinstance(instance, str) and st.trim(instance) else LEGACY_SOURCE
        source = truncate(source, 64)
        if source == TEST_SOURCE:
            return False                                # reserved for the self-test
        if now is None:
            return False                                # this push says nothing about the song
        if not isinstance(now, dict) or now.get("ad") is True:
            self.clear(source)
            return False
        playing = now.get("playing") is True or now.get("buffering") is True
        artist = _text(now.get("artist"))
        if artist.endswith(" - Topic"):
            artist = artist[:-len(" - Topic")]
        u = Update(source=source, agent=_text(agent), state=STATE_PLAYING if playing else STATE_PAUSED,
                   title=_text(now.get("title")), artist=artist, album=_text(now.get("album")),
                   artwork=_text(now.get("art")), video_id=_text(now.get("videoId")),
                   position=_number(now.get("position")), duration=_number(now.get("duration")))
        try:
            u.sanitize()
        except SanitizeError:
            self.clear(source)                          # nothing loaded
            return False
        received_at = None
        if isinstance(ts, (int, float)) and not isinstance(ts, bool):
            t = ts / 1000.0
            if abs(t - self.clock()) <= SAMPLE_SKEW:
                received_at = t
        if self.tracker.put(u, received_at=received_at):
            log.info("browser extension connected (browser=%s)", u.agent)   # no song details
            self._mark_connected()
        self.engine.wake()
        return True

    def feed(self, instance, now, agent=None, ts=None):
        """The same as update_from_bridge()."""
        return self.update_from_bridge(instance, now, agent=agent, ts=ts)

    def touch(self, instance):
        """The tab is still there and nothing changed (call it on every /ext/poll): the extension counts as seen,
        and the tab's song is kept from expiring. Paused tabs push no state, so without this a paused song would
        vanish after 90 seconds. Returns True when the tab has a song."""
        source = instance if isinstance(instance, str) and st.trim(instance) else LEGACY_SOURCE
        return self.tracker.touch(truncate(source, 64))

    def clear(self, source):
        """Forgets one tab (the tab closed: POST /ext/bye). The card is cleared if it was the one shown."""
        s = source if isinstance(source, str) and st.trim(source) else LEGACY_SOURCE
        self.tracker.clear(truncate(s, 64))
        self.engine.wake()

    bye = clear

    def _mark_connected(self):
        self._ever = True
        if self.dry_run or not self._marker or os.path.exists(self._marker):
            return
        try:
            os.makedirs(os.path.dirname(os.path.abspath(self._marker)), mode=0o700, exist_ok=True)
            with open(self._marker, "a"):
                pass
        except OSError:
            pass

    def ever_connected(self):
        return self._ever or bool(self._marker and os.path.exists(self._marker))

    # ---- status ----
    def effective_client_id(self):
        return self.engine.client_id()

    @property
    def enabled(self):
        """The Discord status switch (the server's parts.discord): the "enabled" setting. Read from memory."""
        return bool(self.store.get().get("enabled"))

    def set_enabled(self, on):
        """Switches Discord status on or off and saves it: the same switch as visibility("show") / ("hide"), except
        that a timed hide is left as it is. Returns visibility_state(). Raises SettingsError when on is not a bool,
        OSError when the file cannot be saved (nothing changes)."""
        if not isinstance(on, bool):
            raise SettingsError("enabled must be true or false")
        self.store.update(lambda c: c.__setitem__("enabled", on))
        self.engine.wake()
        return self.visibility_state()

    def visibility_state(self):
        enabled = bool(self.store.get().get("enabled"))
        left = self.engine.snooze_sec()
        return {"enabled": enabled, "snoozeSec": left, "visible": enabled and left == 0}

    def status(self):
        """YTM Beacon's /v1/status shape (same keys, same order), so beacon.headline() / normalize() work on it.
        It includes paths and the application ID: do not send it to the apps as is."""
        cfg = self.store.get()
        snap = self.engine.snapshot()
        now = self.clock()
        last = self.tracker.last_extension_seen()
        return {
            "app": "ytm-starcast",
            "version": __version__,
            "commit": "",
            "os": go_os(),
            "arch": go_arch(),
            "pid": os.getpid(),
            "port": None,
            "uptimeSec": int(now - self.started_at),
            "clientIdSet": self.engine.client_id(cfg) != "",
            "discord": snap["discord"],
            "extension": {
                "lastSeenAgoSec": int(now - last) if last is not None else None,
                "sources": self.tracker.sources(),
                "everConnected": self.ever_connected(),
                "quietAfterSec": int(quiet_after()),
            },
            "nowPlaying": snap["nowPlaying"],
            "presenceActive": snap["presenceActive"],
            "visible": bool(cfg.get("enabled")) and snap["snoozeSec"] == 0,
            "snoozeSec": snap["snoozeSec"],
            "hidden": snap["hidden"],
            "config": cfg,
            "configPath": self.settings_path,
            "logPath": "",
            "extensionBuild": "",
            "extensionDirs": {},
            "dryRun": self.dry_run,
        }

    def preview(self):
        """The card Discord would show now (a dict in Discord's activity format), or None."""
        return self.engine.preview()

    # ---- settings ----
    def get_config(self):
        return self.store.get()

    def set_config(self, partial):
        """Changes only the settings given (a dict, or the raw JSON text of one; blank text changes nothing).
        All or nothing, with YTM Beacon's validation. Returns the new settings. Raises SettingsError for bad
        input (nothing changes) and OSError when the file cannot be saved (nothing changes either)."""
        if isinstance(partial, (bytes, bytearray)):
            if len(partial) > MAX_BODY:
                raise SettingsError("request too large")
            partial = bytes(partial).decode("utf-8", "replace")
        if isinstance(partial, str):
            if len(partial.encode("utf-8", "surrogatepass")) > MAX_BODY:
                raise SettingsError("request too large")
            if not st.trim(partial):
                obj = None
            else:
                try:
                    obj = st.parse_json(partial)
                except SettingsError as e:
                    raise SettingsError("invalid JSON: %s" % e) from None
        elif partial is None or isinstance(partial, dict):
            obj = partial
        else:
            raise SettingsError("invalid JSON: expected an object")

        def apply(c):
            if obj is None:
                return None
            try:
                return st.decode_onto(c, obj)
            except SettingsError as e:
                raise SettingsError("invalid JSON: %s" % e) from None

        new = self.store.update(apply)
        self.engine.wake()
        return new

    def visibility(self, action, minutes=0):
        """The single hide/show entry point.

        "hide" with minutes > 0 hides for that long (at most a day; not saved, so a restart ends it);
        "hide" without minutes switches Discord status off (saved); "show" switches it on and ends a timed
        hide; "toggle" hides if visible, else shows (never a timed hide). Returns visibility_state().
        Raises SettingsError for a bad action/minutes (nothing changes), OSError when saving fails."""
        if minutes is None:
            minutes = 0
        if isinstance(minutes, bool) or not isinstance(minutes, int):
            raise SettingsError("invalid JSON: minutes must be a whole number")
        if action == "toggle":
            action, minutes = ("hide" if self.visibility_state()["visible"] else "show"), 0
        enable = None
        if action == "hide":
            if minutes > 0:
                self.engine.hide_for(min(minutes, MAX_HIDE_MINUTES) * 60.0)
            else:
                enable = False
        elif action == "show":
            enable = True
            self.engine.cancel_hide()
        else:
            raise SettingsError('action must be "hide", "show" or "toggle"')

        def apply(c):
            if enable is not None:
                c["enabled"] = enable

        self.store.update(apply)
        self.engine.wake()
        return self.visibility_state()

    def test(self):
        """Shows a test song on Discord for 30 seconds."""
        self.engine.inject_test()
        return {"ok": True}

    def import_beacon_config(self, src):
        """Takes over YTM Beacon's settings: a dict (Beacon's GET /v1/config) or the path of its config.json.
        Missing keys get the defaults (old Beacon versions wrote only six). Replaces all settings; returns them.
        Raises SettingsError when they are not valid (nothing changes), OSError when saving fails."""
        if isinstance(src, dict):
            cfg = st.normalize(st.decode_onto(st.default_config(), src))
        elif isinstance(src, (str, os.PathLike)):
            cfg = st.read_file(os.fspath(src))
            self.imported_from = os.fspath(src)
        else:
            raise SettingsError("expected YTM Beacon's settings as an object or a file path")
        new = self.store.set(cfg)
        self.engine.wake()
        return new
