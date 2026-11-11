"""Keeps a Discord connection alive and makes Discord show whatever the tracker says is playing.

It reconnects by itself when Discord is closed and reopened, sends at most one update every 2 seconds, sends
only when what Discord shows would change (or the song was seeked), falls back to a simpler card when a Discord
app rejects the full one, and clears the card when it stops.
"""
import logging
import threading
import time

from . import ipc
from .build import (DEFAULT_ASSETS, HIDDEN_OFF, HIDDEN_SNOOZED, activity_key, build_activity, hidden_reason)
from .settings import DEFAULT_CLIENT_ID, effective_client_id
from .tracker import STATE_PLAYING, TEST_SOURCE, Update

log = logging.getLogger("starcast.discord")

STATE_NO_CLIENT_ID = "no-client-id"
STATE_CONNECTING = "connecting"
STATE_CONNECTED = "connected"
STATE_DISCONNECTED = "disconnected"

MIN_SEND_INTERVAL = 2.0     # Discord rate-limits presence updates
SEEK_TOLERANCE_MS = 2500    # start-time drift that counts as a seek
MIN_BACKOFF = 3.0
MAX_BACKOFF = 15.0
TICK = 1.0

TEST_TITLE = "Test song"
TEST_ARTIST = "YTM Starcast self-test"
TEST_ALBUM = "If you can see this, Discord works"


def round_half_away(x):
    """Go's Duration.Round for whole seconds (Python's round() rounds halves to even)."""
    return int(x + 0.5) if x >= 0 else -int(-x + 0.5)


def _status(state, user="", endpoint="", last_error=""):
    return {"state": state, "user": user, "endpoint": endpoint, "lastError": last_error}


class Engine:
    """One engine per Discord status. step() is only ever called from one thread (run(), or a test).

    dial(client_id) returns a connection with set_activity(activity_or_None) (raises ipc.APIError when Discord
    rejects it, anything else when the connection is gone), close(), a `closed` property, info() ->
    {"endpoint", "user"}, and optionally add_done_callback(fn) to wake the engine the moment it is lost."""

    def __init__(self, store, tracker, dial, clock=None, assets=None, default_client_id=DEFAULT_CLIENT_ID):
        self.store = store
        self.tracker = tracker
        self.dial = dial                    # dial(client_id) -> connection; raises when Discord can't be reached
        self.now = clock or time.time
        self.assets = assets or DEFAULT_ASSETS
        self.default_client_id = default_client_id
        self._wake = threading.Event()
        self._stop = None

        self._lock = threading.Lock()       # guards status, active, snooze_until
        self._status = _status(STATE_CONNECTING)
        self._active = False
        self._snooze_until = None           # a timed hide; kept in memory only, so a restart ends it

        # only touched from the engine thread
        self.conn = None
        self.conn_id = ""
        self.next_dial = None
        self.backoff = MIN_BACKOFF
        self.sent = ("", 0, None)           # (key, start, at): what Discord shows ("" = nothing)
        self.basic = False

    # ---- called from any thread ----
    def wake(self):
        """Re-evaluate now (a new report arrived, a setting changed)."""
        self._wake.set()

    def client_id(self, cfg=None):
        return effective_client_id(cfg if cfg is not None else self.store.get(), self.default_client_id)

    def inject_test(self):
        """Shows a fake song for 30 seconds, to check the Discord side without YouTube Music."""
        self.tracker.put(Update(source=TEST_SOURCE, agent="self-test", state=STATE_PLAYING, title=TEST_TITLE,
                                artist=TEST_ARTIST, album=TEST_ALBUM, position=30.0, duration=200.0))
        self.wake()

    def hide_for(self, seconds):
        """Keeps everything off Discord for a while, then shows it again by itself (replaces any earlier one)."""
        with self._lock:
            self._snooze_until = self.now() + seconds
        self.wake()

    def cancel_hide(self):
        with self._lock:
            self._snooze_until = None
        self.wake()

    def snooze_left(self, now=None):
        now = self.now() if now is None else now
        with self._lock:
            if self._snooze_until is not None and self._snooze_until - now > 0:
                return self._snooze_until - now
            return 0.0

    def snooze_sec(self, now=None):
        return round_half_away(self.snooze_left(now))

    def hidden_for(self, cfg, sel, now):
        """Why the settings or the user's switches keep this song off Discord ("" = show it)."""
        if not cfg.get("enabled"):
            return HIDDEN_OFF
        if self.snooze_left(now) > 0:
            return HIDDEN_SNOOZED
        return hidden_reason(cfg, sel, now)

    def discord_status(self):
        with self._lock:
            st = dict(self._status)
        return {k: v for k, v in st.items() if k == "state" or v}

    def snapshot(self):
        """{discord, presenceActive, nowPlaying, hidden, snoozeSec} for the status page."""
        now = self.now()
        with self._lock:
            active = self._active
        out = {"discord": self.discord_status(), "presenceActive": active, "nowPlaying": None, "hidden": "",
               "snoozeSec": self.snooze_sec(now)}
        sel = self.tracker.select()
        if sel is not None:
            out["nowPlaying"] = {"title": sel.title, "artist": sel.artist, "album": sel.album, "artwork": sel.artwork,
                                 "videoId": sel.video_id, "state": sel.state, "position": sel.position,
                                 "duration": sel.duration}
            out["hidden"] = self.hidden_for(self.store.get(), sel, now)
        return out

    def preview(self):
        """The activity Discord would show right now (None = nothing), whatever the connection does."""
        now = self.now()
        cfg = self.store.get()
        sel = self.tracker.select()
        if sel is None or self.hidden_for(cfg, sel, now):
            return None
        return build_activity(cfg, sel, now, self.basic, self.assets)[0]

    def _set_status(self, st):
        with self._lock:
            self._status = st

    def _set_active(self, v):
        with self._lock:
            self._active = v

    # ---- the engine thread ----
    def _stopping(self):
        return self._stop is not None and self._stop.is_set()

    def run(self, stop):
        """Runs until stop (a threading.Event) is set, then clears the card and disconnects."""
        self._stop = stop
        last_crash = None
        try:
            while not stop.is_set():
                try:
                    self.step()
                    last_crash = None
                except Exception as e:      # keep Discord status alive; report each new problem once
                    if repr(e) != last_crash:
                        log.exception("Discord status: unexpected error")
                    last_crash = repr(e)
                self._wake.wait(TICK)
                self._wake.clear()
        finally:
            self.shutdown()

    def shutdown(self):
        if self.conn is not None:
            try:
                self.conn.set_activity(None)
            except Exception:
                pass
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = None
        self._set_active(False)

    def drop_conn(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = None
        self._set_active(False)

    def step(self):
        """One round: make sure Discord is connected, work out what it should show, send it if it changed."""
        now = self.now()
        cfg = self.store.get()
        cid = self.client_id(cfg)

        if not cid:
            self.drop_conn()
            self._set_status(_status(STATE_NO_CLIENT_ID))
            return
        if self.conn is not None and self.conn_id != cid:
            self.drop_conn()            # the application ID changed in the settings
        if self.conn is not None and self.conn.closed:
            log.info("lost connection to Discord; will reconnect")
            self.drop_conn()
            self._set_status(_status(STATE_DISCONNECTED, last_error="the connection to Discord was closed"))
            self.next_dial = now

        if self.conn is None:
            if self.next_dial is not None and now < self.next_dial:
                return
            try:
                c = self.dial(cid)
            except Exception as err:
                if self._stopping():
                    return
                self.next_dial = now + self.backoff
                self.backoff = min(self.backoff * 2, MAX_BACKOFF)
                msg = str(err) or err.__class__.__name__
                with self._lock:
                    if self._status.get("lastError") != msg:
                        log.warning("cannot connect to Discord: %s", msg)
                    self._status = _status(STATE_DISCONNECTED, last_error=msg)
                return
            self.conn, self.conn_id = c, cid
            self.backoff = MIN_BACKOFF
            self.sent = ("", 0, None)
            # a newer Discord may have been started since an older one made us fall back to the plain card
            self.basic = False
            add_cb = getattr(c, "add_done_callback", None)
            if add_cb is not None:
                add_cb(self.wake)
            info = c.info() or {}
            user, endpoint = info.get("user") or "", info.get("endpoint") or ""
            log.info("connected to Discord (user=%s, endpoint=%s)", user, endpoint)
            self._set_status(_status(STATE_CONNECTED, user=user, endpoint=endpoint))

        sel = self.tracker.select()
        act, start = None, 0
        if sel is not None and self.hidden_for(cfg, sel, now) == "":
            act, start = build_activity(cfg, sel, now, self.basic, self.assets)
        key = activity_key(act)
        if not self.needs_send(key, start, now):
            return
        self.send(cfg, sel, act, key, start, now)

    def needs_send(self, key, start, now):
        skey, sstart, sat = self.sent
        changed = key != skey
        if not changed and key != "" and start != 0 and sstart != 0:
            changed = abs(start - sstart) > SEEK_TOLERANCE_MS
        if not changed:
            return False
        return sat is None or now - sat >= MIN_SEND_INTERVAL     # throttled changes wait for a later tick

    def send(self, cfg, sel, act, key, start, now):
        try:
            self.conn.set_activity(act)
            err = None
        except Exception as e:
            err = e
        if isinstance(err, ipc.APIError) and act is not None and not self.basic:
            # some Discord apps reject newer optional fields: retry once with the plain card and keep using it
            log.warning("Discord rejected the presence; switching to a simpler one: %s", err)
            self.basic = True
            if sel is not None:
                act, start = build_activity(cfg, sel, now, True, self.assets)
            key = activity_key(act)
            try:
                self.conn.set_activity(act)
                err = None
            except Exception as e:
                err = e
        if err is not None:
            if isinstance(err, ipc.APIError):
                msg = "Discord would not accept the presence: " + str(err)
                log.error(msg)
                with self._lock:
                    self._status["lastError"] = msg
                self.sent = (key, start, now)       # do not hammer Discord
                return
            log.warning("lost connection while updating presence: %s", err)
            self.drop_conn()
            self._set_status(_status(STATE_DISCONNECTED, last_error=str(err) or err.__class__.__name__))
            self.next_dial = now
            return
        self.sent = (key, start, now)
        self._set_active(act is not None)
        with self._lock:
            self._status["lastError"] = ""
        if act is not None:
            # song details only at debug level: the log may be shared in bug reports
            log.debug("presence updated: %s - %s (%s)", sel.title if sel else "", sel.artist if sel else "",
                      sel.state if sel else "")
        else:
            log.debug("presence cleared")
