"""Media transport via GSMTC (Windows.Media.Control).

A phone connected as a Bluetooth A2DP source shows up here as an ordinary media session,
and transport commands travel back to it over AVRCP. Same API as any local player.

UNVERIFIED UNTIL THE PHONE IS PAIRED: this was proven against a local Brave session.
Whether the phone surfaces a *controllable* session, and which commands AVRCP actually
honours, can only be confirmed with the phone connected -- see tools/check-media.py.
Expect play/pause/next/prev to work and seek to be the flaky one; the UI drives itself
off the session's own can-* flags rather than assuming.

WinRT is apartment-bound, so one thread owns an asyncio loop and all calls go through it.
"""
import asyncio
import threading
import time

from winrt.windows.media.control import (
    GlobalSystemMediaTransportControlsSessionManager as MediaManager,
)

# GlobalSystemMediaTransportControlsSessionPlaybackStatus
PLAYING, PAUSED = 4, 5

_METADATA_INTERVAL = 1.0  # try_get_media_properties_async is expensive; keep it off the fast loop


class Media:
    def __init__(self):
        self._lock = threading.Lock()
        self._state = {"available": False}
        self._loop = None
        self._mgr = None
        self._session = None
        # App id the user pinned, or None to auto-pick. Survives the session vanishing
        # and coming back (phone reconnects), which is the normal case here.
        self._pinned = None
        self._sessions = []
        self._meta = {"title": "", "artist": "", "album": ""}
        self._meta_key = None
        self._meta_at = 0.0
        # position is a snapshot with a timestamp, not a ticking clock -- store the base
        # and interpolate, or the bar freezes and then jumps.
        self._pos_base = 0.0
        self._pos_at = 0.0
        self._rate = 1.0
        self._end = 0.0
        self._status = 0

    def start(self):
        threading.Thread(target=self._run, name="media-winrt", daemon=True).start()

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._main())

    def _pick(self, sessions):
        """Pinned session wins; otherwise prefer whatever is actually Playing.

        Auto-pick is inherently arbitrary when two sessions play at once (a browser and
        the phone -- the normal case here) and can flicker between them poll to poll.
        get_current_session() has the same problem, which is why it's only a last resort.
        Pinning is the actual fix, hence the picker in the UI.
        """
        if self._pinned:
            for s in sessions:
                if s.source_app_user_model_id == self._pinned:
                    return s
            # Pinned app isn't running right now -- fall through to auto rather than
            # showing nothing, but keep the pin so it re-attaches when it returns.
        playing = [s for s in sessions if s.get_playback_info().playback_status == PLAYING]
        if playing:
            return playing[0]
        try:
            cur = self._mgr.get_current_session()
        except OSError:
            cur = None
        return cur or (sessions[0] if sessions else None)

    async def _main(self):
        while True:
            try:
                if self._mgr is None:
                    self._mgr = await MediaManager.request_async()
                sessions = list(self._mgr.get_sessions())
                self._sessions = [
                    {"app": s.source_app_user_model_id,
                     "playing": s.get_playback_info().playback_status == PLAYING}
                    for s in sessions
                ]
                self._session = self._pick(sessions)
                await self._sample()
            except OSError:
                self._mgr = None
                with self._lock:
                    self._state = {"available": False}
            await asyncio.sleep(0.1)

    async def _sample(self):
        s = self._session
        if s is None:
            with self._lock:
                self._state = {"available": False, "sessions": list(self._sessions),
                               "pinned": self._pinned}
            return

        pb = s.get_playback_info()
        self._status = pb.playback_status
        self._rate = pb.playback_rate if pb.playback_rate else 1.0
        c = pb.controls

        tl = s.get_timeline_properties()
        self._end = tl.end_time.total_seconds() if tl.end_time else 0.0
        pos = tl.position.total_seconds() if tl.position else 0.0
        try:
            # last_updated_time is when the app last reported position; anchoring to it
            # (not to now) is what keeps interpolation honest.
            updated = tl.last_updated_time.timestamp()
            if updated < 1_000_000:  # unset clocks come back as year 1601
                updated = time.time()
        except (OSError, AttributeError, ValueError, OverflowError):
            updated = time.time()
        self._pos_base, self._pos_at = pos, updated

        key = s.source_app_user_model_id
        if key != self._meta_key or (time.time() - self._meta_at) > _METADATA_INTERVAL:
            try:
                p = await s.try_get_media_properties_async()
                self._meta = {
                    "title": p.title or "",
                    "artist": p.artist or "",
                    "album": p.album_title or "",
                }
            except OSError:
                self._meta = {"title": "", "artist": "", "album": ""}
            self._meta_key = key
            self._meta_at = time.time()

        with self._lock:
            self._state = {
                "available": True,
                "app": key,
                "playing": self._status == PLAYING,
                "duration": self._end,
                "sessions": list(self._sessions),
                "pinned": self._pinned,
                "can": {
                    "play": c.is_play_enabled,
                    "pause": c.is_pause_enabled,
                    "next": c.is_next_enabled,
                    "prev": c.is_previous_enabled,
                    "seek": c.is_playback_position_enabled,
                },
                **self._meta,
            }

    def snapshot(self):
        with self._lock:
            st = dict(self._state)
        if not st.get("available"):
            return st
        pos = self._pos_base
        if self._status == PLAYING:
            pos += (time.time() - self._pos_at) * self._rate
        st["position"] = max(0.0, min(pos, self._end) if self._end else max(0.0, pos))
        return st

    # ---- commands (called from HTTP threads; marshalled onto the WinRT loop) ----

    def _call(self, fn):
        if self._loop is None or self._session is None:
            return False
        try:
            fut = asyncio.run_coroutine_threadsafe(fn(self._session), self._loop)
            return bool(fut.result(timeout=4))
        except (OSError, asyncio.TimeoutError, TypeError):
            return False

    def play_pause(self):
        return self._call(lambda s: s.try_toggle_play_pause_async())

    def next(self):
        return self._call(lambda s: s.try_skip_next_async())

    def prev(self):
        return self._call(lambda s: s.try_skip_previous_async())

    def seek(self, seconds):
        ticks = int(max(0.0, seconds) * 10_000_000)  # WinRT time is 100ns units
        return self._call(lambda s: s.try_change_playback_position_async(ticks))

    def pin(self, app):
        """app=None returns to auto-pick. Metadata is force-refreshed on next sample."""
        self._pinned = app or None
        self._meta_key = None
        return True
