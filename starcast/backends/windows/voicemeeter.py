"""Voicemeeter via VoicemeeterRemote64.dll: the VAIO meter plus the 3-band EQ.

Deliberately NOT here:
- Volume. VMWV owns Strip[2].Gain and force-syncs it from the Windows volume every
  100ms, so a direct write is stomped. Volume goes through winvolume.py instead.
- Bus gains / A1-A2 routing. Voicemeeter *Standard* has a single hardware bus: A1 and
  A2 are two device slots fed by the same bus A, both carrying identical audio (per
  VB-Audio's manual). Bus[1].Gain and Strip[2].A2 exist only because the API is shared
  with Banana/Potato, which do have independent buses. On Standard they're inert -- which
  is exactly why the A2 slider does nothing at any value.

The DLL is not thread-safe and its getters serve a snapshot that only refreshes when
IsParametersDirty() is pumped, so a single thread owns it and publishes a snapshot.

Also note VBVMR_GetLevel does NOT bounds-check: invalid channels return rc=0 with
garbage memory (peaks around 1e33), so channel maps must be confirmed by tone test, not
by trusting the return code. Only mode 0 / ch4-5 (VAIO L/R) is confirmed by tone test.
"""
import ctypes
import queue
import subprocess
import threading
import time

from ...proc import no_window

DLL_PATH = r"C:\Program Files (x86)\VB\Voicemeeter\VoicemeeterRemote64.dll"

STRIP_VAIO = 2

# The Bass/Med/High knobs. Voicemeeter clamps these to +-12dB itself (verified: writing
# 30 reads back 12), and VMWV leaves them alone -- it only owns Gain and Mute.
EQ_MIN, EQ_MAX = -12.0, 12.0
EQ_BANDS = {"bass": f"Strip[{STRIP_VAIO}].EQGain1",
            "mid": f"Strip[{STRIP_VAIO}].EQGain2",
            "high": f"Strip[{STRIP_VAIO}].EQGain3"}


def relaunch_vmwv():
    """Kill and restart the VMWV (Voicemeeter Windows Volume) helper. Runs as the logged-in
    user, which owns the process, so it can read its path where an unelevated query can't."""
    ps = (
        "$p=(Get-CimInstance Win32_Process -Filter \"Name='VMWV.exe'\").ExecutablePath;"
        "if(-not $p){try{$p=(Get-Process VMWV -ErrorAction Stop).Path}catch{}};"
        "Stop-Process -Name VMWV -Force -ErrorAction SilentlyContinue;"
        "Start-Sleep -Milliseconds 500;"
        "if($p){Start-Process $p; 'ok'}else{'nopath'}"
    )
    try:
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                             capture_output=True, text=True, timeout=20, **no_window())
        return {"ok": True} if out.stdout.strip().startswith("ok") else {"ok": False, "error": "VMWV not found — is it running?"}
    except (OSError, subprocess.SubprocessError) as e:
        return {"ok": False, "error": str(e)}


class Voicemeeter:
    label = "Voicemeeter"
    capabilities = {"eq": True, "meter": True}

    @property
    def actions(self):
        return {
            "restart": lambda: {"ok": self.restart_engine()},
            "relaunch_vmwv": relaunch_vmwv,
        }

    def __init__(self):
        self._dll = ctypes.cdll.LoadLibrary(DLL_PATH)
        self._bind()
        self._lock = threading.Lock()
        self._cmds = queue.Queue()
        self._state = {"connected": False}
        self._stop = threading.Event()

    def _bind(self):
        d = self._dll
        d.VBVMR_Login.restype = ctypes.c_long
        d.VBVMR_Logout.restype = ctypes.c_long
        d.VBVMR_IsParametersDirty.restype = ctypes.c_long
        d.VBVMR_GetParameterFloat.restype = ctypes.c_long
        d.VBVMR_GetParameterFloat.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_float)]
        d.VBVMR_SetParameterFloat.restype = ctypes.c_long
        d.VBVMR_SetParameterFloat.argtypes = [ctypes.c_char_p, ctypes.c_float]
        d.VBVMR_GetLevel.restype = ctypes.c_long
        d.VBVMR_GetLevel.argtypes = [ctypes.c_long, ctypes.c_long, ctypes.POINTER(ctypes.c_float)]

    def _pump(self):
        for _ in range(50):
            if self._dll.VBVMR_IsParametersDirty() == 0:
                return
            time.sleep(0.002)

    def _getf(self, name):
        v = ctypes.c_float()
        if self._dll.VBVMR_GetParameterFloat(name.encode(), ctypes.byref(v)) == 0:
            return v.value
        return None

    def _level(self, mode, ch):
        v = ctypes.c_float()
        if self._dll.VBVMR_GetLevel(mode, ch, ctypes.byref(v)) == 0:
            return v.value
        return 0.0

    def _read_all(self):
        self._pump()
        return {
            "connected": True,
            # Mode 0 is PRE-fader, deliberately: it answers "is the phone streaming?" even
            # at zero volume. A post-fader meter reads 0 and looks identical to "nothing
            # connected". ch4/ch5 are the VAIO L/R pair -- confirmed by tone test.
            "level": max(self._level(0, 4), self._level(0, 5)),
            "eq": {band: self._getf(param) for band, param in EQ_BANDS.items()},
            "routing": {
                "A1": bool(self._getf(f"Strip[{STRIP_VAIO}].A1")),
                "A2": bool(self._getf(f"Strip[{STRIP_VAIO}].A2")),
            },
        }

    def start(self):
        threading.Thread(target=self._run, name="vm-dll", daemon=True).start()

    def stop(self):
        self._stop.set()

    def _run(self):
        # rc 1 means Voicemeeter isn't up yet; the login still registers and the engine
        # picks us up when it launches, so just poll on regardless.
        self._dll.VBVMR_Login()
        time.sleep(0.5)
        try:
            while not self._stop.is_set():
                while True:
                    try:
                        param, value = self._cmds.get_nowait()
                    except queue.Empty:
                        break
                    self._dll.VBVMR_SetParameterFloat(param.encode(), ctypes.c_float(value))
                try:
                    st = self._read_all()
                except OSError:
                    st = {"connected": False}
                with self._lock:
                    self._state = st
                time.sleep(0.1)
        finally:
            self._dll.VBVMR_Logout()

    def snapshot(self):
        with self._lock:
            return dict(self._state)

    def set_eq(self, band, db):
        """Only EQ is writable here; Gain/Mute belong to VMWV and would be stomped."""
        param = EQ_BANDS.get(band)
        if param is None:
            return False
        self._cmds.put((param, max(EQ_MIN, min(EQ_MAX, float(db)))))
        return True

    def restart_engine(self):
        """Restart the Voicemeeter audio engine (VB-Audio's Command.Restart)."""
        self._cmds.put(("Command.Restart", 1.0))
        return True
