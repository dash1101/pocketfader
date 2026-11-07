"""Test doubles. Nothing in the test-suite may execute a real write: FakeRunner never
spawns a process, and the server tests use in-memory backends."""
from pocketfader.runner import Result, Runner


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
        self.calls = []

    def start(self): pass
    def stop(self): pass
    def snapshot(self): return dict(self.state)

    def set_percent(self, p):
        self.calls.append(("percent", p)); self.state["percent"] = p

    def set_mute(self, m):
        self.calls.append(("mute", m)); self.state["muted"] = m


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
