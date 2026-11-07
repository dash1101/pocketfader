"""Backend contracts. Duck-typed; this file is the documentation.

Audio (master volume of the *current default output*):
    start() / stop()
    snapshot() -> {"available": bool, "percent": 0-100, "muted": bool, "device": str}
    set_percent(p) / set_mute(m)

    The backend must follow the default output as it changes and must NEVER change which
    output is the default.

Engine (optional extras: EQ, level meter, mixer actions -- e.g. Voicemeeter):
    start() / stop()
    snapshot() -> {"connected": bool, "level": 0-1, "eq": {band: dB}, "routing": {...}}
    set_eq(band, db)
    capabilities -> {"eq": bool, "meter": bool}
    actions -> {name: callable() -> dict}      # surfaced in Settings
    label -> str                               # shown in the UI

Media (transport + now-playing for whatever is playing on the host):
    start()
    snapshot() -> {"available": bool, "app", "name", "playing", "title", "artist", "album",
                   "position", "duration", "can": {play,pause,next,prev,seek},
                   "sessions": [{"app","name","playing"}], "pinned"}
    play_pause() / next() / prev() / seek(seconds) -> bool
    pin(app_or_None)
"""


class NullEngine:
    """Default engine: no EQ, no meter, no extra actions. The UI hides what's missing."""

    label = ""
    capabilities = {"eq": False, "meter": False}
    actions = {}

    def start(self):
        pass

    def stop(self):
        pass

    def snapshot(self):
        return {"connected": False, "level": 0.0, "eq": {}, "routing": {}}

    def set_eq(self, band, db):
        return False


class NullAudio:
    def start(self):
        pass

    def stop(self):
        pass

    def snapshot(self):
        return {"available": False, "percent": 0, "muted": False, "device": ""}

    def set_percent(self, percent):
        pass

    def set_mute(self, muted):
        pass


class NullMedia:
    def start(self):
        pass

    def snapshot(self):
        return {"available": False, "sessions": [], "pinned": None}

    def play_pause(self):
        return False

    next = prev = play_pause

    def seek(self, seconds):
        return False

    def pin(self, app):
        return True
