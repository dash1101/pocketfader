"""Test doubles. Nothing in the test-suite may execute a real write: FakeRunner never
spawns a process, and the server tests use in-memory backends."""
from starcast.runner import Result, Runner


class FakeRunner(Runner):
    """Serves canned stdout for reads (matched by substring of the joined argv) and records
    every write. No subprocess is ever started."""

    def __init__(self, canned=None):
        super().__init__(dry_run=True)
        self.canned = canned or {}
        self.reads = []

    def _exec(self, argv, timeout):
        self.reads.append(list(argv))
        joined = " ".join(argv)
        for needle, out in self.canned.items():
            if needle in joined:
                return Result(True, out, "")
        return Result(False, "", "no canned response")


class FakeAudio:
    label = "fake"

    def __init__(self, available=True):
        self.state = {"available": available, "percent": 50, "muted": False, "device": "Test Out"}
        self.levels = {"speakers": 80, "headset": 40}
        self.default = "speakers"
        self.calls = []

    def start(self): pass
    def stop(self): pass
    def snapshot(self): return dict(self.state)

    def set_percent(self, p):
        self.calls.append(("percent", p)); self.state["percent"] = p

    def set_mute(self, m):
        self.calls.append(("mute", m)); self.state["muted"] = m

    # output devices
    def outputs(self):
        return [{"name": n, "description": n.title(), "percent": v, "muted": False, "default": n == self.default,
                 "kind": "usb", "form_factor": "", "state": "idle"} for n, v in self.levels.items()]
    def set_output(self, name):
        if name not in self.levels: return False
        self.calls.append(("output", name)); self.default = name; return True
    def set_output_volume(self, name, p):
        if name not in self.levels: return False
        self.calls.append(("outvol", name, p)); self.levels[name] = p; return True
    def set_output_mute(self, name, m):
        if name not in self.levels: return False
        self.calls.append(("outmute", name, m)); return True


class FakeMedia:
    label = "fake"

    def __init__(self, available=True):
        self.avail = available
        self.calls = []
        self.pinned = None

    def start(self): pass

    def snapshot(self):
        if not self.avail:
            return {"available": False, "sessions": [], "pinned": None}
        return {"available": True, "app": "fake", "name": "Fake", "playing": True, "title": "T",
                "artist": "A", "album": "", "art": "https://x/y.jpg", "duration": 100.0,
                "position": 5.0, "can": {}, "sessions": [], "pinned": self.pinned}

    def play_pause(self): self.calls.append("playpause"); return True
    def next(self): self.calls.append("next"); return True
    def prev(self): self.calls.append("prev"); return True
    def seek(self, s): self.calls.append(("seek", s)); return True
    def pin(self, a): self.pinned = a; return True
    def pause_others(self, title="", artist=""): self.calls.append(("pause_others", title, artist)); return ["Other"]


class FakeEngine:
    label = "FakeEngine"
    capabilities = {"eq": True, "meter": True}

    def __init__(self):
        self.eq = {}
        self.ran = []

    @property
    def actions(self):
        return {"restart": lambda: self.ran.append("restart") or {"ok": True}}

    def start(self): pass
    def stop(self): pass
    def snapshot(self): return {"connected": True, "level": 0.5, "eq": dict(self.eq), "routing": {}}
    def set_eq(self, band, db): self.eq[band] = db; return True


class FakeToggleEQ(FakeEngine):
    """Like the built-in EQ: on/off switch, presets, follows the chosen output."""
    label = "Built-in EQ"
    capabilities = {"eq": True, "meter": False, "eq_toggle": True}

    def __init__(self):
        super().__init__()
        self.enabled = False
        self.targets = []

    def enable(self, target=None):
        self.enabled = True; return True, ""
    def disable(self):
        self.enabled = False; return True, ""
    def retarget(self, name):
        self.targets.append(name); return True
    def set_preset(self, name):
        if name != "bass-boost": return False
        self.eq = {"bass": 6.0, "mid": 0.0, "high": 0.0}; return True
    def snapshot(self):
        return {**super().snapshot(), "enabled": self.enabled, "presets": ["bass-boost"]}
