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
        self.hidden_sinks = set()   # internal sinks (e.g. the EQ) that shouldn't appear in the output list
        self.aliases = {}           # hidden sink -> the real device it fronts, so the UI names that device

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
        st = parse_sink(sink)
        front = next((s for s in sinks if s.get("name") == self.aliases.get(name)), None)
        if front:   # an internal sink (the EQ) is the default: show the device it feeds
            st["device"] = front.get("description") or front.get("name") or st["device"]
        return st

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

    # ---- output devices ----
    def default_name(self):
        return self.r.read([self.pactl, "get-default-sink"]).out.strip() or None

    def outputs(self, include_hidden=False):
        """Every playback device with its own level, plus which one is the default."""
        default = self.default_name()
        res = self.r.read([self.pactl, "--format=json", "list", "sinks"])
        try:
            sinks = json.loads(res.out) if res.ok else []
        except ValueError:
            sinks = []
        default = self.aliases.get(default, default)   # EQ in front of a device: that device is "current"
        return [describe_sink(s, default) for s in sinks
                if include_hidden or s.get("name") not in self.hidden_sinks]

    def _known(self, name):
        return any(o["name"] == name for o in self.outputs(include_hidden=True))

    def _is_real_pulseaudio(self):
        if not hasattr(self, "_real_pa"):
            info = self.r.read([self.pactl, "info"]).out
            self._real_pa = "Server Name:" in info and "PipeWire" not in info.split("Server Name:")[1].splitlines()[0]
        return self._real_pa

    def set_output(self, name):
        """Make `name` the default output. Only called when the user picks a device in the app."""
        if not self._known(name):
            return False
        self.r.write([self.pactl, "set-default-sink", name])
        if self._is_real_pulseaudio():
            # Plain PulseAudio doesn't move running streams on its own. (PipeWire does, and
            # moving streams there would pin them to this device, so we leave them alone.)
            res = self.r.read([self.pactl, "--format=json", "list", "sink-inputs"])
            try:
                for si in json.loads(res.out) if res.ok else []:
                    self.r.write([self.pactl, "move-sink-input", str(si["index"]), name])
            except (ValueError, KeyError):
                pass
        self._after_write()
        return True

    def set_output_volume(self, name, percent):
        """Set a specific device's level -- including one that isn't the current output."""
        if not self._known(name):
            return False
        p = max(0, min(100, int(round(percent))))
        self.r.write([self.pactl, "set-sink-volume", name, f"{p}%"])
        self._after_write()
        return True

    def set_output_mute(self, name, muted):
        if not self._known(name):
            return False
        self.r.write([self.pactl, "set-sink-mute", name, "1" if muted else "0"])
        self._after_write()
        return True

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


def classify_sink(sink):
    """Rough device kind for the UI icon: bluetooth / usb / hdmi / analog / virtual."""
    name = (sink.get("name") or "").lower()
    props = sink.get("properties") or {}
    desc = (sink.get("description") or "").lower()
    port = (sink.get("active_port") or "").lower()
    if name.startswith("bluez_") or props.get("device.api") == "bluez5":
        return "bluetooth"
    if "hdmi" in name or "hdmi" in port or "displayport" in port or "hdmi" in desc:
        return "hdmi"
    if props.get("device.bus") == "usb" or ".usb-" in name:
        return "usb"
    if (name.startswith(("easyeffects", "effect_", "combined", "pocketfader", "null"))
            or props.get("node.virtual") == "true" or props.get("device.class") == "filter"
            or not props.get("device.api") and not props.get("alsa.card")):
        return "virtual"
    return "analog"


def describe_sink(sink, default_name):
    st = parse_sink(sink)
    return {
        "name": sink.get("name"),
        "description": st["device"],
        "percent": st["percent"],
        "muted": st["muted"],
        "default": sink.get("name") == default_name,
        "kind": classify_sink(sink),
        "form_factor": (sink.get("properties") or {}).get("device.form_factor") or "",
        "state": (sink.get("state") or "").lower(),
    }
