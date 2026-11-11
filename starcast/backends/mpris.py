"""Now-playing + transport for any MPRIS player on the host (Spotify, Brave/Chrome, VLC,
mpv, Firefox, KDE Connect / GSConnect phone sessions, ...), via `busctl` -- no Python
D-Bus bindings needed, so the server stays dependency-free.

Never sends a command on its own: the polling path is strictly read-only (ListNames +
Properties.GetAll). Play/Pause/Next/... are only issued when a client asks.
"""
import json
import re
import threading
import time

from ..runner import Runner

PREFIX = "org.mpris.MediaPlayer2."
PATH = "/org/mpris/MediaPlayer2"
PLAYER = "org.mpris.MediaPlayer2.Player"
ROOT = "org.mpris.MediaPlayer2"
NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"
POLL = 1.0


def app_id(bus_name):
    """Stable id for pinning: browsers append '.instanceNNNN' that changes every launch."""
    return re.sub(r"\.instance\d+$", "", bus_name[len(PREFIX):] if bus_name.startswith(PREFIX) else bus_name)


def _unwrap(v):
    """busctl --json wraps every value as {"type":..,"data":..}; peel it recursively."""
    if isinstance(v, dict) and set(v) == {"type", "data"}:
        return _unwrap(v["data"])
    if isinstance(v, dict):
        return {k: _unwrap(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_unwrap(x) for x in v]
    return v


class Mpris:
    label = "MPRIS"

    def __init__(self, runner=None, busctl="busctl"):
        self.r = runner or Runner(timeout=2.0)
        self.bus = busctl
        self._lock = threading.Lock()
        self._players = {}          # app id -> parsed state
        self._bus_of = {}           # app id -> current bus name
        self._pinned = None
        self._last_active = None
        self._wake = threading.Event()
        self._stop = threading.Event()

    # ---- dbus helpers ----
    def _call(self, dest, iface, member, sig="", *args, write=False):
        argv = [self.bus, "--user", "--json=short", "call", dest, PATH, iface, member]
        if sig:
            argv += [sig, *map(str, args)]
        res = self.r.write(argv) if write else self.r.read(argv)
        if not res.ok:
            return None
        try:
            return _unwrap(json.loads(res.out)) if res.out.strip() else {}
        except ValueError:
            return None

    def _names(self):
        res = self.r.read([self.bus, "--user", "--json=short", "call", "org.freedesktop.DBus",
                           "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames"])
        if not res.ok:
            return []
        try:
            names = _unwrap(json.loads(res.out))[0]
        except (ValueError, IndexError, TypeError):
            return []
        return sorted(n for n in names if n.startswith(PREFIX))

    def _identity(self, name):
        out = self._call(name, "org.freedesktop.DBus.Properties", "Get", "ss", ROOT, "Identity")
        return out[0] if isinstance(out, list) and out and isinstance(out[0], str) else None

    def _read_player(self, name, prev):
        props = self._call(name, "org.freedesktop.DBus.Properties", "GetAll", "s", PLAYER)
        if not props:
            return None
        p = props[0]
        md = p.get("Metadata") or {}
        artist = md.get("xesam:artist")
        if isinstance(artist, list):
            artist = ", ".join(a for a in artist if a)
        length = md.get("mpris:length") or 0
        return {
            "app": app_id(name),
            "name": (prev or {}).get("name") or self._identity(name) or app_id(name),
            "playing": p.get("PlaybackStatus") == "Playing",
            "status": p.get("PlaybackStatus") or "Stopped",
            "title": md.get("xesam:title") or "",
            "artist": artist or "",
            "album": md.get("xesam:album") or "",
            "art": md.get("mpris:artUrl") or "",
            "trackid": md.get("mpris:trackid") or "",
            "duration": float(length) / 1e6 if length else 0.0,
            "pos_base": float(p.get("Position") or 0) / 1e6,
            "pos_at": time.time(),
            "rate": float(p.get("Rate") or 1.0),
            "can": {"play": bool(p.get("CanPlay")), "pause": bool(p.get("CanPause")),
                    "next": bool(p.get("CanGoNext")), "prev": bool(p.get("CanGoPrevious")),
                    "seek": bool(p.get("CanSeek"))},
        }

    # ---- polling ----
    def start(self):
        threading.Thread(target=self._loop, name="mpris-poll", daemon=True).start()

    def stop(self):
        self._stop.set()
        self._wake.set()

    def _loop(self):
        while not self._stop.is_set():
            self.refresh()
            self._wake.wait(POLL)
            self._wake.clear()

    def refresh(self):
        with self._lock:
            prev = dict(self._players)
        players, bus_of = {}, {}
        for name in self._names():
            st = self._read_player(name, prev.get(app_id(name)))
            if st:
                players[st["app"]] = st
                bus_of[st["app"]] = name
        with self._lock:
            self._players, self._bus_of = players, bus_of

    # ---- selection ----
    def _pick(self, players):
        if self._pinned in players:
            return players[self._pinned]
        playing = [p for p in players.values() if p["playing"]]
        if playing:
            self._last_active = playing[0]["app"]
            return playing[0]
        if self._last_active in players:
            return players[self._last_active]
        return next(iter(players.values()), None)

    def snapshot(self):
        with self._lock:
            players = dict(self._players)
        sessions = [{"app": p["app"], "name": p["name"], "playing": p["playing"]}
                    for p in players.values()]
        cur = self._pick(players)
        if cur is None:
            return {"available": False, "sessions": sessions, "pinned": self._pinned}
        pos = cur["pos_base"]
        if cur["playing"]:
            pos += (time.time() - cur["pos_at"]) * cur["rate"]
        if cur["duration"]:
            pos = min(pos, cur["duration"])
        return {
            "available": True, "app": cur["app"], "name": cur["name"],
            "playing": cur["playing"], "title": cur["title"], "artist": cur["artist"],
            "album": cur["album"], "art": cur["art"], "duration": cur["duration"],
            "position": max(0.0, pos), "can": cur["can"],
            "sessions": sessions, "pinned": self._pinned,
        }

    # ---- commands (only on explicit client request) ----
    def _target(self):
        with self._lock:
            players, bus_of = dict(self._players), dict(self._bus_of)
        cur = self._pick(players)
        return (bus_of.get(cur["app"]), cur) if cur else (None, None)

    def _send(self, member, sig="", *args):
        bus, cur = self._target()
        if not bus:
            return False
        ok = self._call(bus, PLAYER, member, sig, *args, write=True) is not None
        self._wake.set()  # re-read right away so the UI reflects it
        return ok

    def play_pause(self):
        return self._send("PlayPause")

    def next(self):
        return self._send("Next")

    def prev(self):
        return self._send("Previous")

    def seek(self, seconds):
        bus, cur = self._target()
        if not bus:
            return False
        target_us = int(max(0.0, seconds) * 1e6)
        tid = cur.get("trackid")
        if tid and tid != NO_TRACK and tid.startswith("/"):
            ok = self._send("SetPosition", "ox", tid, target_us)
            if ok:
                return True
        # Some players don't implement SetPosition: fall back to a relative Seek.
        now_us = int(self.snapshot().get("position", 0) * 1e6)
        return self._send("Seek", "x", target_us - now_us)

    def pause_others(self, keep_title="", keep_artist=""):
        """Pause every player that is playing right now, except the one showing (keep_title, keep_artist). Only on request."""
        with self._lock:
            players, bus_of = dict(self._players), dict(self._bus_of)
        paused = []
        for app, p in players.items():
            if not p.get("playing"):
                continue
            if keep_title and p.get("title") == keep_title and (not keep_artist or p.get("artist") == keep_artist):
                continue
            bus = bus_of.get(app)
            if bus and self._call(bus, PLAYER, "Pause", write=True) is not None:
                paused.append(p.get("name") or app)
        if paused:
            self._wake.set()
        return paused

    def pin(self, app):
        self._pinned = app or None
        self._wake.set()
        return True
