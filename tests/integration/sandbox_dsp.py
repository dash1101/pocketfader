"""Measures the REAL effect of the built-in EQ: tones go through the filter-chain in a private
PipeWire sandbox and the recorded output level is compared with the EQ flat vs boosted."""
import array, math, os, signal, subprocess, sys, tempfile, time, wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from starcast.backends.eq_pipewire import SINK_NAME, BuiltinEQ
from starcast.backends.pulse import PulseAudio
from starcast.config import JsonFile
from starcast.runner import Runner

if "pfs" not in os.environ.get("PULSE_SERVER", ""):
    sys.exit("refusing to run: environment does not point at the sandbox")

tmp = Path(tempfile.mkdtemp())
RATE = 48000

def make_tone(path, hz, secs=2.0, amp=0.1):
    n = int(RATE * secs)
    data = array.array("h")
    for i in range(n):
        v = int(32767 * amp * math.sin(2 * math.pi * hz * i / RATE))
        data.extend((v, v))
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data.tobytes())

def rms_db(path):
    with wave.open(str(path), "rb") as w:
        raw = w.readframes(w.getnframes())
    s = array.array("h"); s.frombytes(raw)
    s = s[len(s) // 4: 3 * len(s) // 4]            # middle half: skips ramp-in/out
    if not s: return -999.0
    r = math.sqrt(sum(x * x for x in s) / len(s)) / 32768
    return 20 * math.log10(r) if r > 0 else -999.0

def measure(hz):
    tone, rec = tmp / f"t{hz}.wav", tmp / f"r{hz}.wav"
    make_tone(tone, hz)
    rec.unlink(missing_ok=True)
    rp = subprocess.Popen(["pw-cat", "-r", "--target", "sandbox_speakers", "-P", "stream.capture.sink=true",
                           "--rate", str(RATE), "--channels", "2", "--format", "s16", str(rec)],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.6)
    subprocess.run(["pw-cat", "-p", "--target", SINK_NAME, str(tone)], capture_output=True, timeout=20)
    time.sleep(0.3)
    rp.send_signal(signal.SIGINT); rp.wait(5)
    return rms_db(rec)

r = Runner(); audio = PulseAudio(r)
eq = BuiltinEQ(r, audio, JsonFile(tmp / "eq.json"), tmp)
audio.set_output("sandbox_speakers")
ok, why = eq.enable(target="sandbox_speakers")
print("enable:", ok, why); time.sleep(1.0)
failed = False

# (band, test frequency, expected minimum boost in dB for a +12 dB setting)
cases = [("bass", 50, 8.0), ("mid", 1000, 9.0), ("high", 12000, 8.0)]
for band, hz, need in cases:
    for b in ("bass", "mid", "high"): eq.set_band(b, 0.0)
    time.sleep(0.4)
    flat = measure(hz)
    eq.set_band(band, 12.0)                         # live update path
    time.sleep(0.4)
    boosted = measure(hz)
    eq.set_band(band, -12.0)
    time.sleep(0.4)
    cut = measure(hz)
    up, down = boosted - flat, cut - flat
    good = up >= need and down <= -need
    failed |= not good
    print(f"{'PASS' if good else 'FAIL'} {band:5} @ {hz:5} Hz: flat {flat:6.1f} dBFS | +12 -> {up:+5.1f} dB | -12 -> {down:+5.1f} dB")

# a band must NOT leak into a distant frequency
for b in ("bass", "mid", "high"): eq.set_band(b, 0.0)
eq.set_band("bass", 12.0); time.sleep(0.4)
lk_flat_ref = measure(12000)
eq.set_band("bass", 0.0); time.sleep(0.4)
lk_flat = measure(12000)
leak = lk_flat_ref - lk_flat
good = abs(leak) < 1.0
failed |= not good
print(f"{'PASS' if good else 'FAIL'} bass +12 barely affects 12 kHz (leak {leak:+.2f} dB)")

eq.disable()
sys.exit(1 if failed else 0)
