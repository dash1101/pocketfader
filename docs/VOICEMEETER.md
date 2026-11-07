# Voicemeeter (optional, Windows only)

Voicemeeter is **not** used by default. Pocketfader controls the host's normal system volume.
Choose it explicitly if you already run VB-Audio Voicemeeter and want its extras:

```powershell
python -m pocketfader --audio voicemeeter
# or at logon:  .\packaging\install.ps1 -Audio voicemeeter
```

It adds: a **3-band EQ** (Strip[2] EQGain1/2/3 = Bass/Mid/High, ±12 dB), a **level meter**
(pre-fader VAIO strip, powers the visualizer), and the Settings actions *Restart audio engine*
and *Relaunch Windows Volume sync*. The EQ/meter UI is hidden on every other backend.

## Design notes (hard-won, keep)

- **Volume is never written to Voicemeeter.** VMWV (Voicemeeter Windows Volume) force-syncs
  `Strip[2].Gain` from the Windows endpoint volume about every 100 ms, so a direct write is stomped.
  Pocketfader drives the *Windows* endpoint volume (pycaw) and lets VMWV propagate it. The mapping
  is linear: 0–100 % ≙ −60…+12 dB. EQ is safe to write directly (VMWV only owns Gain and Mute).
- **Voicemeeter Standard has one hardware bus** feeding both A1 and A2 device slots identically.
  `Bus[1].Gain` and `Strip[2].A2` exist only because the API is shared with Banana/Potato; on Standard
  they are inert. Independent per-device volume needs Banana.
- `VBVMR_GetLevel` does **not** bounds-check: an invalid channel returns rc=0 with garbage (~1e33).
  Only mode 0, channels 4/5 (VAIO L/R) are confirmed meaningful.
- The Remote API's getters only refresh while `IsParametersDirty()` is pumped, and the DLL is not
  thread-safe, so a single thread owns it and publishes snapshots.

## Status

These backends are carried over from the original Windows build. They were refactored onto the new
backend interface but **have not been re-run since**; the Linux build is the tested path.
