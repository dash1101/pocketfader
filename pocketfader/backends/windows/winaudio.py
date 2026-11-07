"""Windows master volume on the default render endpoint (= Voicemeeter Input / VAIO).

Why this instead of writing Strip[2].Gain directly: VMWV polls every 100ms and force-
syncs the Windows volume onto Strip[2].Gain, so a direct write is stomped within ~100ms.
Driving the Windows volume instead makes VMWV do the work for us, which also means the
keyboard volume keys and this app move the same thing and can never disagree.

Measured with Voicemeeter Standard: VMWV maps 0-100% linearly onto -60..+12 dB
(25% -> -42dB, 50% -> -24dB, 75% -> -6dB), so the app's 0-100 slider is 1:1 with the
Windows volume and needs no curve of its own.

COM is apartment-bound, so one dedicated thread owns the endpoint and everything else
goes through snapshot()/set().
"""
import queue
import threading
import time

import comtypes
from pycaw.pycaw import AudioUtilities


class WinVolume:
    label = "Windows"

    def __init__(self):
        self._cmds = queue.Queue()
        self._lock = threading.Lock()
        self._state = {"available": False, "percent": 0, "muted": False, "device": ""}
        self._stop = threading.Event()
        self._dev = None
        self._vol = None

    def _acquire(self):
        """(Re)bind to whatever is currently the default endpoint."""
        self._dev = AudioUtilities.GetSpeakers()
        self._vol = self._dev.EndpointVolume

    def start(self):
        threading.Thread(target=self._run, name="win-volume", daemon=True).start()

    def stop(self):
        self._stop.set()

    def _run(self):
        comtypes.CoInitialize()
        try:
            while not self._stop.is_set():
                try:
                    if self._vol is None:
                        self._acquire()

                    while True:
                        try:
                            kind, value = self._cmds.get_nowait()
                        except queue.Empty:
                            break
                        if kind == "percent":
                            self._vol.SetMasterVolumeLevelScalar(
                                max(0.0, min(1.0, value / 100.0)), None
                            )
                        elif kind == "mute":
                            self._vol.SetMute(1 if value else 0, None)

                    st = {
                        "available": True,
                        "percent": round(self._vol.GetMasterVolumeLevelScalar() * 100),
                        "muted": bool(self._vol.GetMute()),
                        "device": self._dev.FriendlyName,
                    }
                except (comtypes.COMError, OSError, AttributeError):
                    # Default device changed or the endpoint went stale -- drop it and
                    # re-acquire next tick rather than silently driving a dead device.
                    self._vol = None
                    self._dev = None
                    st = {"available": False, "percent": 0, "muted": False, "device": ""}

                with self._lock:
                    self._state = st
                time.sleep(0.1)
        finally:
            comtypes.CoUninitialize()

    def snapshot(self):
        with self._lock:
            return dict(self._state)

    def set_percent(self, percent):
        self._cmds.put(("percent", float(percent)))

    def set_mute(self, muted):
        self._cmds.put(("mute", bool(muted)))
