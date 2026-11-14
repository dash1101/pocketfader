"""What each YouTube Music tab reports, and which tab Discord follows.

A tab ("source") reports the song it plays. The tracker keeps the latest report from every tab and picks
the one to show: a playing tab beats a paused one; between two playing tabs the one where play was pressed
last wins, between two paused ones the one paused last. Those moments only change when someone presses
something, so two tabs reporting in turn never make Discord flip between them.

A phone playing music itself (the Android app) is a source too, named "phone:" + its device id. It takes part in
the same choice, but it is not the browser extension: it never counts as the extension being seen.
"""
import math
import re
import threading
import time

from .settings import clean_text, trim

STATE_PLAYING = "playing"
STATE_PAUSED = "paused"

# The fake source of the "send a test song" button.
TEST_SOURCE = "test"

# Sources named like this are phones (POST /api/phone/playing), never browser tabs.
PHONE_PREFIX = "phone:"

# A source that stops reporting is forgotten after this long (browser closed, tab crashed...). Browsers slow a
# background tab that isn't playing sound down to about one report a minute, so this is well over 60 seconds.
SOURCE_TTL = 90.0
TEST_TTL = 30.0

# At most this many phones are kept at once (the one heard from longest ago goes first), so a stream of made-up
# phone ids can't make the list grow; a real household has a few phones at most.
MAX_PHONES = 8

_VIDEO_ID = re.compile(r"[A-Za-z0-9_-]{11}")


class SanitizeError(ValueError):
    pass


def is_phone(source):
    """Whether a source is a phone rather than a browser tab."""
    return isinstance(source, str) and source.startswith(PHONE_PREFIX)


def quiet_after():
    """How long without any report before the extension counts as gone quiet (the same as SOURCE_TTL)."""
    return SOURCE_TTL


def truncate(s, n):
    """Trims whitespace, then keeps the first n characters (no ellipsis)."""
    s = clean_text(trim(s or ""))
    return s[:n] if len(s) > n else s


def clean_number(f):
    try:
        f = float(f)
    except (TypeError, ValueError):
        return 0.0
    if math.isnan(f) or math.isinf(f) or f < 0:
        return 0.0
    return 1e6 if f > 1e6 else f


class Update:
    """One report about one tab. position and duration are seconds (duration 0 = unknown)."""

    __slots__ = ("source", "agent", "state", "title", "artist", "album", "artwork", "video_id", "position", "duration")

    def __init__(self, source="", agent="", state="", title="", artist="", album="", artwork="", video_id="",
                 position=0.0, duration=0.0):
        self.source, self.agent, self.state = source, agent, state
        self.title, self.artist, self.album = title, artist, album
        self.artwork, self.video_id = artwork, video_id
        self.position, self.duration = position, duration

    def copy(self):
        return Update(**{k: getattr(self, k) for k in Update.__slots__})

    def sanitize(self):
        """Makes an update safe to use (lengths, https-only art, a valid video id, sane numbers).
        Raises SanitizeError("missing source") / ("missing title")."""
        self.source = truncate(self.source, 64)
        if not self.source:
            raise SanitizeError("missing source")
        self.agent = truncate(self.agent, 32)
        self.title = truncate(self.title, 300)
        if not self.title:
            raise SanitizeError("missing title")
        self.artist = truncate(self.artist, 300)
        self.album = truncate(self.album, 300)
        if self.state != STATE_PAUSED:
            self.state = STATE_PLAYING
        art = clean_text(trim(self.artwork or ""))
        if not art.startswith("https://") or len(art.encode("utf-8")) > 500:
            art = ""
        self.artwork = art
        if not isinstance(self.video_id, str) or not _VIDEO_ID.fullmatch(self.video_id):
            self.video_id = ""
        self.position = clean_number(self.position)
        self.duration = clean_number(self.duration)
        return self

    def __repr__(self):
        return "Update(%s)" % ", ".join("%s=%r" % (k, getattr(self, k)) for k in Update.__slots__)


class Selected(Update):
    """The source being followed: its last update, when it was measured, and since when it is paused."""

    __slots__ = ("received_at", "paused_since")

    def __init__(self, update=None, received_at=None, paused_since=None, **fields):
        u = update or Update(**fields)
        super().__init__(**{k: getattr(u, k) for k in Update.__slots__})
        self.received_at = received_at      # seconds (clock time) the position was measured
        self.paused_since = paused_since    # None while playing


class _Entry:
    __slots__ = ("u", "at", "expires", "paused_since", "playing_since")

    def __init__(self):
        self.u = Update()
        self.at = self.expires = 0.0
        self.paused_since = None
        self.playing_since = None    # when this tab last went from paused (or new) to playing

    def beats(self, o):
        """Whether self should be shown rather than o. The source name breaks exact ties."""
        ep, op = self.u.state == STATE_PLAYING, o.u.state == STATE_PLAYING
        if ep != op:
            return ep
        a, b = (self.playing_since, o.playing_since) if ep else (self.paused_since, o.paused_since)
        a, b = (a if a is not None else float("-inf")), (b if b is not None else float("-inf"))
        if a != b:
            return a > b
        return self.u.source < o.u.source


class Tracker:
    def __init__(self, clock=None):
        self._now = clock or time.time
        self._lock = threading.Lock()
        self._m = {}
        self._last_ext = None
        self._last_phone = None

    def put(self, u, received_at=None):
        """Records an update that has already been sanitized. received_at (clock seconds) is when the position
        was measured, if not now. Returns True the first time this source is heard from."""
        with self._lock:
            now = self._now()
            # forget what expired here too, not only when a song is chosen: nothing may choose for a while (Discord
            # closed, the old YTM Beacon app running) while reports keep coming
            self._prune(now)
            if u.source == TEST_SOURCE:
                ttl = TEST_TTL
            elif is_phone(u.source):
                ttl = SOURCE_TTL        # a phone reports every 30 s while it plays or is paused
                self._last_phone = now
            else:
                ttl = SOURCE_TTL
                self._last_ext = now
            e = self._m.get(u.source)
            is_new = e is None
            if is_new:
                if is_phone(u.source):
                    phones = [k for k in self._m if is_phone(k)]
                    if len(phones) >= MAX_PHONES:
                        del self._m[min(phones, key=lambda k: self._m[k].expires)]
                e = self._m[u.source] = _Entry()
            if u.state == STATE_PAUSED:
                # keep the original pause time across repeated "still paused" reports
                if e.u.state != STATE_PAUSED or e.u.title != u.title or e.paused_since is None:
                    e.paused_since = now
                e.playing_since = None
            else:
                e.paused_since = None
                if e.playing_since is None:
                    e.playing_since = now
            e.u = u.copy()
            e.at = now if received_at is None else received_at
            e.expires = now + ttl
            return is_new

    def touch(self, source):
        """A tab checked in without anything new to report (it is still there, nothing changed). The extension
        counts as seen, and the tab's song is kept from expiring. Returns False when the source has no song
        (unknown or already expired). A phone is kept the same way, but never counts as the extension."""
        with self._lock:
            now = self._now()
            if source == TEST_SOURCE:
                return False
            if is_phone(source):
                self._last_phone = now
            else:
                self._last_ext = now
            e = self._m.get(source)
            if e is None or now > e.expires:
                return False
            e.expires = now + SOURCE_TTL
            return True

    def clear(self, source):
        with self._lock:
            self._m.pop(source, None)

    def _prune(self, now):
        for k in [k for k, e in self._m.items() if now > e.expires]:
            del self._m[k]

    def select(self):
        """The source to show as a Selected, or None."""
        with self._lock:
            self._prune(self._now())
            best = None
            for e in self._m.values():
                if best is None or e.beats(best):
                    best = e
            if best is None:
                return None
            return Selected(best.u, received_at=best.at, paused_since=best.paused_since)

    def last_extension_seen(self):
        """When any browser tab (not the self-test, not a phone) last reported, or None. Survives expiry."""
        with self._lock:
            return self._last_ext

    def last_phone_seen(self):
        """When any phone last reported, or None. Survives expiry."""
        with self._lock:
            return self._last_phone

    def sources(self):
        """The browser tabs (and the self-test) with a song: short ids only. Phones are left out (they are not
        the extension)."""
        with self._lock:
            now = self._now()
            self._prune(now)
            out = []
            for k, e in self._m.items():
                if is_phone(k):
                    continue
                sid = k.encode("utf-8")[:8].decode("utf-8", "ignore")
                out.append({"id": sid, "agent": e.u.agent, "state": e.u.state, "ageSec": max(0, int(now - e.at))})
            out.sort(key=lambda s: s["id"])
            return out
