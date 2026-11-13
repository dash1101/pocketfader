/* YTM Starcast Refine: clear slowed sound.
 *
 * When a song is slowed down, the browser can either keep the pitch (its own time-stretching, which smears and warbles
 * on music) or let the pitch drop (plain resampling: smooth and clear, but lower). "Clear" takes the clear one and puts
 * the pitch back: a phase vocoder with identity phase locking (Laroche & Dolson) stretches the resampled sound in time,
 * a resampler reads it back at the original pitch, and drum hits get their phases reset so they stay sharp. Both
 * channels are turned by the same amount at every frequency, so the stereo picture stays where it was.
 *
 * One file, two uses: YouTube Music's page loads it as an AudioWorklet module (it then registers "pf-stretch"), and it
 * is also a content script, so Refine can fall back to running the same code on the page itself.
 */
(function (scope) {
  "use strict";
  const TAU = Math.PI * 2;
  const wrap = (x) => x - TAU * Math.round(x / TAU);

  // Radix-2 complex FFT in place (re, im: Float64Array of length n, a power of two).
  class FFT {
    constructor(n) {
      this.n = n;
      this.rev = new Uint32Array(n);
      const bits = Math.log2(n);
      for (let i = 0; i < n; i++) {
        let r = 0;
        for (let b = 0; b < bits; b++) r |= ((i >> b) & 1) << (bits - 1 - b);
        this.rev[i] = r;
      }
      this.cos = new Float64Array(n / 2);
      this.sin = new Float64Array(n / 2);
      for (let i = 0; i < n / 2; i++) { this.cos[i] = Math.cos(TAU * i / n); this.sin[i] = Math.sin(TAU * i / n); }
    }
    run(re, im, inverse) {
      const n = this.n, rev = this.rev;
      for (let i = 0; i < n; i++) {
        const j = rev[i];
        if (j > i) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
      }
      const sgn = inverse ? 1 : -1;
      for (let size = 2; size <= n; size <<= 1) {
        const half = size >> 1, step = n / size;
        for (let start = 0; start < n; start += size) {
          for (let k = 0, t = 0; k < half; k++, t += step) {
            const wr = this.cos[t], wi = sgn * this.sin[t];
            const a = start + k, b = a + half;
            const xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
            re[b] = re[a] - xr; im[b] = im[a] - xi;
            re[a] += xr; im[a] += xi;
          }
        }
      }
      if (inverse) for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }
  }

  // Shifts the pitch of a stereo stream by `alpha` (1.25 = up by a factor 1.25) without changing its length.
  class PitchShifter {
    constructor(sampleRate) {
      const N = sampleRate > 30000 ? 4096 : 2048;
      this.N = N; this.H = N >> 2; this.half = N >> 1;
      this.fft = new FFT(N);
      this.win = new Float64Array(N);
      for (let i = 0; i < N; i++) this.win[i] = 0.5 - 0.5 * Math.cos(TAU * i / N);
      this.scale = 1 / 1.5;                       // a Hann window twice over, four frames deep, adds up to 1.5
      this.cap = N * 6 + 16384;
      this.inL = new Float32Array(this.cap); this.inR = new Float32Array(this.cap);
      this.strL = new Float32Array(this.cap); this.strR = new Float32Array(this.cap);
      this.olaL = new Float64Array(N); this.olaR = new Float64Array(N);
      this.re = new Float64Array(N); this.im = new Float64Array(N);
      const B = this.half + 1;
      this.Lr = new Float64Array(B); this.Li = new Float64Array(B); this.Rr = new Float64Array(B); this.Ri = new Float64Array(B);
      this.mag = new Float64Array(B); this.ph = new Float64Array(B); this.prevPh = new Float64Array(B);
      this.syn = new Float64Array(B); this.prevSyn = new Float64Array(B);
      this.peaks = new Int32Array(B);
      this.alpha = 1; this.on = false;
      this.underruns = 0; this.frames = 0; this.transients = 0;
      this.reset();
    }
    reset() {
      this.inLen = 0; this.aPos = 0; this.prevA = null;   // null: no frame yet (after compacting, it can be below 0)
      this.strLen = 0; this.rPos = 1; this.primed = false; this.fresh = true;
      this.hfcPrev = 0; this.hfcAvg = 0;
      this.olaL.fill(0); this.olaR.fill(0);
    }
    // How many samples the sound comes out later than it goes in, while it is being shifted.
    get latency() { return this.on && this.alpha !== 1 ? this.N + 2 * this.H : 0; }
    set(on, alpha) {
      alpha = Math.max(0.25, Math.min(4, Number(alpha) || 1));
      const active = !!on && Math.abs(alpha - 1) > 0.001;
      if (active && !(this.on && this.alpha !== 1)) this.reset();
      this.on = !!on;
      this.alpha = active ? alpha : 1;
    }
    // inL/inR: this block's input (inR may be inL for mono); outL/outR receive n samples.
    process(inL, inR, outL, outR, n) {
      if (!this.on || this.alpha === 1) {
        outL.set(inL.subarray(0, n)); outR.set(inR.subarray(0, n));
        return;
      }
      if (this.inLen + n > this.cap) this.compactInput(true);
      this.inL.set(inL.subarray(0, n), this.inLen); this.inR.set(inR.subarray(0, n), this.inLen);
      this.inLen += n;
      const N = this.N, step = this.H / this.alpha;
      for (;;) {
        const a = Math.round(this.aPos);
        if (a + N > this.inLen || this.strLen + this.H > this.cap) break;
        this.frame(a);
        this.aPos += step;
      }
      this.compactInput(false);
      const target = 2 * this.H + 4, alpha = this.alpha, sL = this.strL, sR = this.strR;
      for (let i = 0; i < n; i++) {
        if (!this.primed) {
          if (this.strLen - this.rPos >= target) this.primed = true;
          else { outL[i] = 0; outR[i] = 0; continue; }
        }
        const k = Math.floor(this.rPos);
        if (k + 2 >= this.strLen) { this.primed = false; this.underruns++; outL[i] = 0; outR[i] = 0; continue; }
        const f = this.rPos - k;
        outL[i] = hermite(sL[k - 1], sL[k], sL[k + 1], sL[k + 2], f);
        outR[i] = hermite(sR[k - 1], sR[k], sR[k + 1], sR[k + 2], f);
        this.rPos += alpha;
      }
      const drop = Math.floor(this.rPos) - 1;
      if (drop > 4096) {
        this.strL.copyWithin(0, drop, this.strLen); this.strR.copyWithin(0, drop, this.strLen);
        this.strLen -= drop; this.rPos -= drop;
      }
    }
    compactInput(force) {
      const drop = Math.max(0, Math.floor(this.aPos) - 1);
      if (!drop || (!force && drop < 8192)) {
        if (force && this.inLen > this.cap - 4096) this.reset();   // never happens in use: the input can't run that far ahead
        return;
      }
      this.inL.copyWithin(0, drop, this.inLen); this.inR.copyWithin(0, drop, this.inLen);
      this.inLen -= drop; this.aPos -= drop;
      if (this.prevA !== null) this.prevA -= drop;
    }
    frame(a) {
      const N = this.N, half = this.half, H = this.H, re = this.re, im = this.im, w = this.win;
      const inL = this.inL, inR = this.inR;
      for (let i = 0; i < N; i++) { re[i] = inL[a + i] * w[i]; im[i] = inR[a + i] * w[i]; }
      this.fft.run(re, im, false);
      // one transform carries both channels: L = (Z[k] + conj Z[N-k]) / 2, R = (Z[k] - conj Z[N-k]) / 2i
      const Lr = this.Lr, Li = this.Li, Rr = this.Rr, Ri = this.Ri, mag = this.mag, ph = this.ph;
      let hfc = 0, peak = 0;
      for (let k = 0; k <= half; k++) {
        const j = (N - k) & (N - 1);
        const zr = re[k], zi = im[k], cr = re[j], ci = -im[j];
        const lr = (zr + cr) / 2, li = (zi + ci) / 2, rr = (zi - ci) / 2, ri = -(zr - cr) / 2;
        Lr[k] = lr; Li[k] = li; Rr[k] = rr; Ri[k] = ri;
        const mr = lr + rr, mi = li + ri;   // the phases follow the mid (L + R)
        const m = Math.sqrt(mr * mr + mi * mi);
        mag[k] = m; ph[k] = Math.atan2(mi, mr);
        hfc += k * m * m;
        if (m > peak) peak = m;
      }
      const hopA = this.prevA === null ? H : a - this.prevA;
      this.prevA = a;
      // a sudden jump in high-frequency energy is a hit: its phases are taken as they are, which keeps it sharp
      const hit = this.fresh || (hfc > 2.5 * this.hfcPrev && hfc > 1.5 * this.hfcAvg && peak > 1e-4);
      this.hfcAvg = this.hfcAvg * 0.9 + hfc * 0.1;
      this.hfcPrev = hfc;
      this.fresh = false;
      const syn = this.syn, prevSyn = this.prevSyn, prevPh = this.prevPh;
      if (hit) {
        this.transients++;
        for (let k = 0; k <= half; k++) syn[k] = ph[k];
      } else {
        // peaks, each with its own true frequency; every other bin keeps its phase offset from its peak
        const peaks = this.peaks, floor = peak * 1e-5;
        let np = 0;
        for (let k = 1; k < half; k++) {
          const m = mag[k];
          if (m > floor && m > mag[k - 1] && m >= mag[k + 1] && (k < 2 || m > mag[k - 2]) && (k > half - 2 || m >= mag[k + 2])) peaks[np++] = k;
        }
        for (let i = 0; i < np; i++) {
          const k = peaks[i], om = TAU * k / N;
          const d = wrap(ph[k] - prevPh[k] - om * hopA);
          syn[k] = prevSyn[k] + (om + d / hopA) * H;
        }
        if (!np) {
          for (let k = 0; k <= half; k++) {
            const om = TAU * k / N;
            syn[k] = prevSyn[k] + (om + wrap(ph[k] - prevPh[k] - om * hopA) / hopA) * H;
          }
        } else {
          // each peak rules the bins up to the quietest bin between it and the next peak
          let from = 0;
          for (let i = 0; i < np; i++) {
            const p = peaks[i];
            let to = half;
            if (i + 1 < np) {
              const q = peaks[i + 1];
              to = p;
              for (let k = p + 1; k < q; k++) if (mag[k] < mag[to]) to = k;
            }
            const base = syn[p] - ph[p];
            for (let k = from; k <= to; k++) if (k !== p) syn[k] = base + ph[k];
            from = to + 1;
          }
        }
      }
      for (let k = 0; k <= half; k++) { prevPh[k] = ph[k]; prevSyn[k] = wrap(syn[k]); }
      // turn both channels by the same angle at each frequency, then back to sound
      for (let k = 1; k < half; k++) {
        const d = syn[k] - ph[k], c = Math.cos(d), s = Math.sin(d);
        const ylr = Lr[k] * c - Li[k] * s, yli = Lr[k] * s + Li[k] * c;
        const yrr = Rr[k] * c - Ri[k] * s, yri = Rr[k] * s + Ri[k] * c;
        re[k] = ylr - yri; im[k] = yli + yrr;
        re[N - k] = ylr + yri; im[N - k] = yrr - yli;
      }
      re[0] = Lr[0]; im[0] = Rr[0]; re[half] = Lr[half]; im[half] = Rr[half];
      this.fft.run(re, im, true);
      const oL = this.olaL, oR = this.olaR, sc = this.scale;
      for (let i = 0; i < N; i++) { oL[i] += re[i] * w[i] * sc; oR[i] += im[i] * w[i] * sc; }
      // the first hop is complete now: no later frame reaches back that far
      const s = this.strLen;
      for (let i = 0; i < H; i++) { this.strL[s + i] = oL[i]; this.strR[s + i] = oR[i]; }
      this.strLen += H;
      oL.copyWithin(0, H); oR.copyWithin(0, H);
      oL.fill(0, N - H); oR.fill(0, N - H);
      this.frames++;
    }
  }
  function hermite(xm1, x0, x1, x2, f) {
    const c1 = 0.5 * (x1 - xm1), c2 = xm1 - 2.5 * x0 + 2 * x1 - 0.5 * x2, c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1);
    return ((c3 * f + c2) * f + c1) * f + x0;
  }

  const api = { PitchShifter, FFT };
  try { Object.defineProperty(scope, Symbol.for("pocketfader.stretch"), { configurable: true, value: api }); } catch (e) {}
  if (typeof module === "object" && module && module.exports) module.exports = api;

  // As an AudioWorklet module: one processor per page, told the speed and whether to work by Refine.
  if (typeof registerProcessor === "function" && typeof AudioWorkletProcessor === "function") {
    class StretchProcessor extends AudioWorkletProcessor {
      constructor(options) {
        super();
        this.ps = new PitchShifter(sampleRate);
        const start = (options && options.processorOptions) || {};
        if ("on" in start) this.ps.set(start.on, start.alpha);
        this.port.onmessage = (e) => {
          const d = e.data || {};
          if ("on" in d || "alpha" in d) this.ps.set(d.on, d.alpha);
          if (d.report) this.port.postMessage({ frames: this.ps.frames, underruns: this.ps.underruns, transients: this.ps.transients, latency: this.ps.latency });
        };
      }
      process(inputs, outputs) {
        const inp = inputs[0], out = outputs[0];
        if (!out || !out.length) return true;
        const n = out[0].length;
        if (!inp || !inp.length) { for (const ch of out) ch.fill(0); return true; }
        const l = inp[0], r = inp[1] || inp[0];
        this.ps.process(l, r, out[0], out[1] || new Float32Array(n), n);
        for (let c = 2; c < out.length; c++) out[c].set(out[0]);
        return true;
      }
    }
    registerProcessor("pf-stretch", StretchProcessor);
  }
})(typeof globalThis !== "undefined" ? globalThis : this);
