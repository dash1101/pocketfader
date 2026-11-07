"""Does audio sent to the Windows DEFAULT playback device land on Voicemeeter Strip[2]?

winsound plays to the default render endpoint. If VAIO is default, the tone shows up on
the VAIO input meter. Uses mode 0 (PRE-fader) because Strip[2] is currently muted at
-60dB -- a post-fader meter would read zero even on success.
"""
import ctypes
import math
import struct
import tempfile
import threading
import time
import wave
import winsound
from pathlib import Path

wav = Path(tempfile.gettempdir()) / "vm_probe_tone.wav"
with wave.open(str(wav), "w") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(44100)
    frames = b"".join(
        struct.pack("<hh", *((int(20000 * math.sin(2 * math.pi * 440 * t / 44100)),) * 2))
        for t in range(44100 * 2)
    )
    w.writeframes(frames)

vmr = ctypes.cdll.LoadLibrary(r"C:\Program Files (x86)\VB\Voicemeeter\VoicemeeterRemote64.dll")
vmr.VBVMR_GetLevel.argtypes = [ctypes.c_long, ctypes.c_long, ctypes.POINTER(ctypes.c_float)]
vmr.VBVMR_Login()
time.sleep(0.5)


def level(mode, ch):
    v = ctypes.c_float()
    return v.value if vmr.VBVMR_GetLevel(mode, ch, ctypes.byref(v)) == 0 else None


threading.Timer(0.2, lambda: winsound.PlaySound(str(wav), winsound.SND_FILENAME | winsound.SND_ASYNC)).start()

peak = {}
end = time.time() + 2.5
while time.time() < end:
    for ch in range(12):
        v = level(0, ch)
        if v is not None:
            peak[ch] = max(peak.get(ch, 0.0), v)
    time.sleep(0.02)

winsound.PlaySound(None, 0)
wav.unlink(missing_ok=True)

print("PRE-FADER input meters (mode 0) while a tone played to the DEFAULT device:\n")
for ch in sorted(peak):
    strip = "Strip[0] HW" if ch < 2 else "Strip[1] HW" if ch < 4 else "Strip[2] VAIO"
    flag = "  <-- SIGNAL" if peak[ch] > 0.001 else ""
    print(f"  ch{ch:2} {strip:14} peak={peak[ch]:.4f}{flag}")

vaio = max((peak.get(c, 0.0) for c in range(4, 12)), default=0.0)
print("\n=>", "VAIO IS the default playback device - Bluetooth audio will land on Strip[2]."
      if vaio > 0.001 else
      "NO signal on VAIO - the default playback device is NOT Voicemeeter Input. "
      "Bluetooth audio would bypass Voicemeeter entirely.")
vmr.VBVMR_Logout()
