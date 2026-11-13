"""The extension's clear-slowed-sound processor (extension/stretch.js), run with Node on made-up tones: nothing is played.

What the browser hands it at speed r, with the pitch let go, is the song slower and lower by r; the processor has to bring
the pitch back to where it was, keep the volume steady, never run dry, and leave the sound alone when it is off."""
import json
import shutil
import subprocess
import unittest
from pathlib import Path

STRETCH = Path(__file__).resolve().parent.parent / "extension" / "stretch.js"

SCRIPT = r"""
const { PitchShifter, FFT } = require(process.argv[1]);
const SR = 48000;
function slowed(r, secs, tones) {
  const n = Math.floor(SR * secs), L = new Float32Array(n), R = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    let s = 0;
    tones.forEach((f, j) => { s += 0.2 / (j + 1) * Math.sin(2 * Math.PI * f * r * i / SR + j); });
    L[i] = s; R[i] = s * 0.6;
  }
  return { L, R };
}
function run(ps, L, R, change) {
  const n = L.length, oL = new Float32Array(n), oR = new Float32Array(n);
  for (let i = 0, b = 0; i < n; i += 128, b++) {
    if (change && b % 150 === 0) ps.set(true, 1.1 + (b % 600) / 1000);
    const m = Math.min(128, n - i);
    ps.process(L.subarray(i, i + m), R.subarray(i, i + m), oL.subarray(i, i + m), oR.subarray(i, i + m), m);
  }
  return oL;
}
function topPeaks(x, start, count) {
  const N = 32768, fft = new FFT(N), re = new Float64Array(N), im = new Float64Array(N);
  for (let i = 0; i < N; i++) re[i] = x[start + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
  fft.run(re, im, false);
  const mag = []; for (let k = 0; k < N / 2; k++) mag.push(Math.hypot(re[k], im[k]));
  const peaks = [];
  for (let k = 2; k < N / 2 - 2; k++) if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1]) peaks.push([k * SR / N, mag[k]]);
  return peaks.sort((a, b) => b[1] - a[1]).slice(0, count).map((p) => p[0]).sort((a, b) => a - b);
}
function envelope(x) {
  const e = [];
  for (let i = SR; i < x.length - 960; i += 960) { let s = 0; for (let j = 0; j < 960; j++) s += x[i + j] ** 2; e.push(Math.sqrt(s / 960)); }
  return [Math.min(...e), Math.max(...e)];
}
const out = {};
for (const r of [0.8, 0.5]) {
  const { L, R } = slowed(r, 5, [440, 660]);
  const ps = new PitchShifter(SR); ps.set(true, 1 / r);
  const y = run(ps, L, R);
  out["r" + r] = { peaks: topPeaks(y, SR * 2, 2), env: envelope(y), underruns: ps.underruns };
}
{
  const { L, R } = slowed(0.8, 6, [440]);
  const ps = new PitchShifter(SR); ps.set(true, 1.25);
  const y = run(ps, L, R, true);
  let silent = 0; for (let i = SR; i < y.length; i++) if (y[i] === 0) silent++;
  out.changing = { underruns: ps.underruns, silent, nan: y.some(Number.isNaN) };
}
{
  const { L, R } = slowed(0.8, 1, [440]);
  const ps = new PitchShifter(SR); ps.set(false, 1.25);
  const y = run(ps, L, R);
  out.off = y.every((v, i) => v === L[i]);
}
console.log(JSON.stringify(out));
"""


@unittest.skipUnless(shutil.which("node"), "needs Node.js")
class StretchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        p = subprocess.run(["node", "-e", SCRIPT, str(STRETCH)], capture_output=True, text=True, timeout=120)
        if p.returncode:
            raise AssertionError(p.stderr)
        cls.out = json.loads(p.stdout)

    def test_the_pitch_comes_back(self):
        for key in ("r0.8", "r0.5"):
            low, high = self.out[key]["peaks"]
            self.assertAlmostEqual(low, 440, delta=3, msg=key)
            self.assertAlmostEqual(high, 660, delta=3, msg=key)

    def test_the_volume_stays_steady(self):
        for key in ("r0.8", "r0.5"):
            lo, hi = self.out[key]["env"]
            self.assertGreater(lo, hi * 0.85, key)        # no wobble (frames out of step)
            self.assertEqual(self.out[key]["underruns"], 0, key)

    def test_speed_changes_never_leave_a_gap(self):
        c = self.out["changing"]
        self.assertEqual((c["underruns"], c["silent"], c["nan"]), (0, 0, False))

    def test_off_leaves_the_sound_alone(self):
        self.assertTrue(self.out["off"])


if __name__ == "__main__":
    unittest.main()
