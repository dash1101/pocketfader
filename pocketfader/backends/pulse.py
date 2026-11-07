"""Master volume via `pactl` -- works on PipeWire (pipewire-pulse) and PulseAudio alike.

Targets the symbolic `@DEFAULT_SINK@`, so it always controls whatever output is currently
default and follows it when it changes. It only ever sets volume/mute on that sink; it
never changes the default sink, moves streams, or touches any other device.

State is refreshed from `pactl subscribe` events (instant, ~zero idle CPU) with a slow
poll as a safety net, so the slider tracks keyboard volume keys and other mixers.
"""
import json
import subprocess
import threading

from ..runner import Runner

SINK = "@DEFAULT_SINK@"


class PulseAudio:
    label = "PipeWire / PulseAudio"

    def __init__(self, runner=None, pactl="pactl"):
        self.r = runner or Runner()
        self.pactl = pactl
        self._lock = threading.Lock()
        self._state = {"available": False, "percent": 0, "muted": False, "device": ""}
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._sub = None

    # ---- lifecycle ----
    def start(self):
        threading.Thread(target=self._poll_loop, name="pulse-poll", daemon=True).start()
        threading.Thread(target=self._subscribe_loop, name="pulse-sub", daemon=True).start()

    def stop(self):
        self._stop.set()
        self._wake.set()
        if self._sub:
            try:
                self._sub.terminate()
            except OSError:
                pass

    # ---- reading ----
    def _read(self):
        name = self.r.read([self.pactl, "get-default-sink"]).out.strip()
        if not name:
            return {"available": False, "percent": 0, "muted": False, "device": ""}
        res = self.r.read([self.pactl, "--format=json", "list", "sinks"])
        if not res.ok:
            return {"available": False, "percent": 0, "muted": False, "device": ""}
        try:
            sinks = json.loads(res.out)
        except ValueError:
            return {"available": False, "percent": 0, "muted": False, "device": ""}
        sink = next((s for s in sinks if s.get("name") == name), None)
        if not sink:
            return {"available": False, "percent": 0, "muted": False, "device": ""}
        return parse_sink(sink)

    def _poll_loop(self):
        while not self._stop.is_set():
            st = self._read()
            with self._lock:
                self._state = st
            self._wake.wait(timeout=3.0)  # event-driven; the timeout is just a safety net
            self._wake.clear()
            self._stop.wait(0.05)         # coalesce bursts of events

    def _subscribe_loop(self):
        while not self._stop.is_set():
            try:
                self._sub = subprocess.Popen([self.pactl, "subscribe"], stdout=subprocess.PIPE,
                                             stderr=subprocess.DEVNULL, text=True)
                for line in self._sub.stdout:
                    if "sink" in line or "server" in line:
                        self._wake.set()
            except OSError:
                pass
            self._stop.wait(2.0)  # pactl missing / server restarted: retry quietly

    def snapshot(self):
        with self._lock:
            return dict(self._state)

    # ---- writing ----
    def _after_write(self):
        self._wake.set()

    def set_percent(self, percent):
        p = max(0, min(100, int(round(percent))))
        self.r.write([self.pactl, "set-sink-volume", SINK, f"{p}%"])
        with self._lock:  # optimistic, so the UI doesn't flicker back before the event lands
            self._state = {**self._state, "percent": p}
        self._after_write()

    def set_mute(self, muted):
        self.r.write([self.pactl, "set-sink-mute", SINK, "1" if muted else "0"])
        with self._lock:
            self._state = {**self._state, "muted": bool(muted)}
        self._after_write()


def parse_sink(sink):
    """pactl JSON sink -> our volume dict. Uses the loudest channel as 'the' volume, which
    matches what pavucontrol and desktop volume keys show."""
    vols = []
    for ch in (sink.get("volume") or {}).values():
        pct = str(ch.get("value_percent", "")).rstrip("%")
        try:
            vols.append(int(pct))
        except ValueError:
            pass
    return {
        "available": True,
        "percent": max(vols) if vols else 0,
        "muted": bool(sink.get("mute")),
        "device": sink.get("description") or sink.get("name") or "",
    }
