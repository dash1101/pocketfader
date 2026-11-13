/* YTM Starcast Refine: improvements to YouTube Music itself, built into its own page (page world, after ytm_main.js).
 *
 *  - A Material 3 Expressive look in separate layers, each with its own switch: rounder shapes, colours taken from the
 *    album art, expressive player controls (a bigger play button that changes shape), a wavy progress bar and a glow
 *    behind the cover.
 *  - A richer menu on YouTube Music's own playback-speed button: any speed from 0.25x to 3x, keep or drop the pitch,
 *    and remember a speed for one song or for a whole playlist.
 *  - A "play something you might like" button among the player controls.
 *  - A playlist maker: start from the song playing, the queue, one of your playlists or your liked songs; it asks
 *    YouTube Music for the radio of each starting song, ranks what they have in common, and shows the result to
 *    review before saving it as a new playlist or adding it to one of yours.
 *  - Its settings open from a button in YouTube Music's top bar, together with YTM Starcast's three main switches.
 *
 * Everything here depends on YouTube Music's page, which changes now and then, so each part fails quietly on its own
 * and can be switched off from that menu. Opening music.youtube.com/?pf-refine=off starts a page without any of it.
 * YTM Starcast's "Refine" switch (in its apps, its toolbar button and that menu) takes all of it away at once, live.
 * Besides the page and YouTube Music's own endpoints (with your own login), it reaches only the YTM Starcast app on this
 * computer, and only through the extension's own script (live lyrics, YTM Starcast's switches); settings stay in this browser.
 *
 * Page-world names from before the rename are kept on purpose (Symbol.for("pocketfader.api" / "pocketfader.refine"),
 * __pfRefineLoaded, the pf- / --pf- CSS names and the pocketfader.refine.* keys, which hold people's settings).
 */
(() => {
  "use strict";
  if (window.__pfRefineLoaded) return;
  window.__pfRefineLoaded = true;
  const API = window[Symbol.for("pocketfader.api")];
  if (!API) return;
  const VERSION = API.version;
  const KILL = /[?&]pf-refine=off\b/.test(location.search);
  const REDUCED = matchMedia("(prefers-reduced-motion: reduce)");
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

  // ------------------------------------------------------------------ settings
  const PREFS_KEY = "pocketfader.refine.v1";
  const SPEEDS_KEY = "pocketfader.refine.speeds";
  const DEFAULTS = { shapes: true, colors: true, controls: true, wavy: true, glow: true, motion: true, material: true, logo: true, sidebar: true,
    speedMenu: true, keepPitch: true, clearSlow: false, speedTimes: true, dice: true, lyrics: true, lyricsFollow: true, lyricsFloat: true, songMenu: true };
  const readJSON = (k, d) => {
    try { const v = JSON.parse(localStorage.getItem(k) || "null"); return v && typeof v === "object" ? v : d; } catch (e) { return d; }
  };
  const writeJSON = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} };
  const prefs = { ...DEFAULTS, ...readJSON(PREFS_KEY, {}) };
  // YTM Starcast's "Refine" switch, as the app last said (see setPart). Remembered, so a page that opens while Refine is off
  // doesn't show it for a moment first. The detailed switches above are kept as they are either way.
  const PART_KEY = "pocketfader.refine.part";
  let partOn = readJSON(PART_KEY, {}).on !== false;
  // ?pf-refine=off: this page starts with everything off (the saved choices are kept for next time)
  const live = () => !KILL && partOn;
  const on = (k) => live() && !!prefs[k];
  function setPref(k, v) {
    prefs[k] = !!v;
    writeJSON(PREFS_KEY, prefs);
    applyAll();
  }

  // ------------------------------------------------------------------ small DOM helpers (no innerHTML: the page uses Trusted Types)
  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(props || {})) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "text") el.textContent = v;
      else if (k === "style") el.style.cssText = v;
      else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2), v);
      else el.setAttribute(k, v === true ? "" : String(v));
    }
    for (const c of kids.flat(Infinity)) if (c != null && c !== false) el.append(c.nodeType ? c : String(c));   // lists inside lists too
    return el;
  }
  const SVGNS = "http://www.w3.org/2000/svg";
  function icon(d, size = 24) {
    const s = document.createElementNS(SVGNS, "svg");
    s.setAttribute("viewBox", "0 0 24 24");
    s.setAttribute("width", String(size));
    s.setAttribute("height", String(size));
    s.setAttribute("aria-hidden", "true");
    const p = document.createElementNS(SVGNS, "path");
    if (d.startsWith("stroke:")) {   // a line drawing rather than a filled shape
      p.setAttribute("d", d.slice(7));
      p.setAttribute("fill", "none");
      p.setAttribute("stroke", "currentColor");
      p.setAttribute("stroke-width", "2.2");
      p.setAttribute("stroke-linecap", "round");
      p.setAttribute("stroke-linejoin", "round");
    } else {
      p.setAttribute("d", d);
      p.setAttribute("fill", "currentColor");
    }
    s.append(p);
    return s;
  }
  // Nova's four-point star, as in YTM Starcast's logo
  function sparklePath(cx, cy, r) {
    const P = (x, y) => Math.round(x * 100) / 100 + "," + Math.round(y * 100) / 100;
    const a = 0.07 * r, b = 0.32 * r, c = 0.29 * r, d = 0.09 * r;
    return "M" + P(cx, cy - r) + "C" + P(cx + a, cy - b) + " " + P(cx + c, cy - d) + " " + P(cx + r, cy) + "C" + P(cx + c, cy + d) + " " + P(cx + a, cy + b) + " " + P(cx, cy + r) +
      "C" + P(cx - a, cy + b) + " " + P(cx - c, cy + d) + " " + P(cx - r, cy) + "C" + P(cx - c, cy - d) + " " + P(cx - a, cy - b) + " " + P(cx, cy - r) + "Z";
  }
  const LOGO_STAR = sparklePath(176, 336, 118);
  const ICON = {
    sparkle: "M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z",
    dice: "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM7.5 18c-.83 0-1.5-.67-1.5-1.5S6.67 15 7.5 15s1.5.67 1.5 1.5S8.33 18 7.5 18zm0-9C6.67 9 6 8.33 6 7.5S6.67 6 7.5 6 9 6.67 9 7.5 8.33 9 7.5 9zm4.5 4.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm4.5 4.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm0-9c-.83 0-1.5-.67-1.5-1.5S15.67 6 16.5 6s1.5.67 1.5 1.5S17.33 9 16.5 9z",
    close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    playlistAdd: "M14 10H3v2h11v-2zm0-4H3v2h11V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM3 16h7v-2H3v2z",
    refresh: "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
    play: "M8 5v14l11-7z",
    minus: "M19 13H5v-2h14v2z",
    plus: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    lyrics: "M20 2H4a2 2 0 0 0-2 2v18l4-4h14a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zM6 6h12v2H6V6zm0 3h12v2H6V9zm0 3h8v2H6v-2z",
    speed: "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
    tune: "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    popOut: "M19 19H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
    popIn: "M19 5v14H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-2V5zM17.59 3 11 9.59V6H9v7h7v-2h-3.59L19 4.41 17.59 3z",
    pin: "M16 9V4h1a1 1 0 0 0 0-2H7a1 1 0 0 0 0 2h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
    pip: "M19 11h-8v6h8v-6zm4 8V4.98C23 3.88 22.1 3 21 3H3c-1.1 0-2 .88-2 1.98V19c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2zm-2 .02H3V4.97h18v14.05z",
    discord: "M20.317 4.37a19.79 19.79 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.865-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.74 19.74 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994a.076.076 0 0 0-.041-.106 13.1 13.1 0 0 1-1.872-.892.077.077 0 0 1-.008-.128c.126-.094.252-.192.372-.291a.074.074 0 0 1 .078-.01c3.928 1.793 8.18 1.793 12.062 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.3 12.3 0 0 1-1.873.892.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.84 19.84 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.03zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z",
    palette: "M12 3a9 9 0 0 0 0 18c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z",
    motion: "stroke:M10 19a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM16.5 6.2a8.5 8.5 0 0 1 0 11.6M20 8.4a8 8 0 0 1 0 7.2",
    wave: "stroke:M2.5 12c1.6-3.4 3.2-3.4 4.75 0s3.15 3.4 4.75 0 3.2-3.4 4.75 0 3.15 3.4 4.75 0",
    eq: "M7 18h2V6H7v12zm4 4h2V2h-2v20zm-8-8h2v-4H3v4zm12 4h2V6h-2v12zm4-8v4h2v-4h-2z",
    viz: "stroke:M7 7h10v10H7zM4 9.5V8M4 16v-2.5M20 9.5V8M20 16v-2.5M9.5 4H8M16 4h-1.5M9.5 20H8M16 20h-1.5",
    material: "stroke:M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17zM12 3.5v17M3.5 12h17",
    volume: "M3 9v6h4l5 5V4L7 9H3zm13.5 3A4.5 4.5 0 0 0 14 7.97v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    mute: "M16.5 12A4.5 4.5 0 0 0 14 7.97v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51A8.8 8.8 0 0 0 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3 3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06a8.99 8.99 0 0 0 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4 9.91 6.09 12 8.18V4z",
  };

  // ------------------------------------------------------------------ colours (Material You style tones from one hue)
  const hsl = (hh, s, l, a) => `hsl(${Math.round(hh)} ${Math.round(s)}% ${Math.round(l)}%${a != null ? " / " + a : ""})`;
  const DEFAULT_HUE = 352;
  function palette(hue, vivid) {
    const c = vivid < 0.12 ? 0.3 : 1; // a grey cover gives nearly neutral tones
    return {
      accent: hsl(hue, 78 * c, 76), onAccent: hsl(hue, 62 * c, 15), track: hsl(hue, 40 * c, 76, 0.28),
      container: hsl(hue, 42 * c, 27), onContainer: hsl(hue, 78 * c, 90),
      surface: hsl(hue, 14 * c, 7), surface1: hsl(hue, 14 * c, 10), surface2: hsl(hue, 15 * c, 13), surface3: hsl(hue, 16 * c, 17),
      outline: hsl(hue, 10 * c, 34), text: hsl(hue, 18 * c, 93), muted: hsl(hue, 10 * c, 72), glow: hsl(hue, 85 * c, 55, 0.55),
    };
  }
  const COLOR_KEY = "pocketfader.refine.color";
  let colors = palette(DEFAULT_HUE, 1);
  let lastColor = null, colorFor = "", colorBusy = false, colorNote = "";
  let paletteSent = "";
  function setPalette(p, hue, vivid) {
    colors = p;
    const st = document.documentElement.style;
    for (const [k, v] of Object.entries(p)) st.setProperty("--pf-" + k.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase()), v);
    if (lyr.pip) copyPalette(lyr.pip.document.documentElement);   // first called after everything below is set up
    kickWave();
    // the toolbar popup follows the same colours (the extension's script keeps the last ones)
    const msg = JSON.stringify({ h: Math.round(hue == null ? DEFAULT_HUE : hue), s: Math.round((vivid == null ? 1 : vivid) * 100) / 100 });
    if (msg !== paletteSent) { paletteSent = msg; window.postMessage({ __vmr: "palette", palette: JSON.parse(msg) }, "*"); }
  }
  // The most vivid colour of a small copy of the cover: hues in 10-degree bins, weighted by how colourful each pixel is.
  function dominant(data) {
    const bins = new Float64Array(36), hueSum = new Float64Array(36);
    let total = 0, vividPx = 0, px = 0;
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      px++;
      const r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn, s = mx ? d / mx : 0;
      if (mx < 0.18 || s < 0.22) continue;
      let hh = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
      hh = (hh * 60 + 360) % 360;
      const w = s * mx * (0.6 + 0.4 * s);
      const bin = Math.floor(hh / 10) % 36;
      bins[bin] += w; hueSum[bin] += hh * w; total += w; vividPx++;
    }
    if (!px || total < 0.5) return null;
    const around = (i) => bins[i] + 0.5 * (bins[(i + 35) % 36] + bins[(i + 1) % 36]);
    let best = 0;
    for (let i = 1; i < 36; i++) if (around(i) > around(best)) best = i;
    return { h: hueSum[best] / bins[best], s: vividPx / px };
  }
  function artUrl() {
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      const list = md && md.artwork ? [...md.artwork] : [];
      list.sort((a, b) => (parseInt(a.sizes, 10) || 0) - (parseInt(b.sizes, 10) || 0));
      const pick = list.find((x) => (parseInt(x.sizes, 10) || 0) >= 60) || list[list.length - 1];
      if (pick && pick.src) return pick.src;
    } catch (e) {}
    const vid = API.curVideo();
    return vid ? `https://i.ytimg.com/vi/${vid}/default.jpg` : null;
  }
  function sampleArt(url) {
    return new Promise((res) => {
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.referrerPolicy = "no-referrer";
      const t = setTimeout(() => res({ err: "timeout" }), 6000);
      img.onload = () => {
        clearTimeout(t);
        try {
          const c = document.createElement("canvas");
          c.width = c.height = 32;
          const x = c.getContext("2d", { willReadFrequently: true });
          x.drawImage(img, 0, 0, 32, 32);
          res({ c: dominant(x.getImageData(0, 0, 32, 32).data) });
        } catch (e) { res({ err: "blocked" }); } // the image host didn't allow reading it
      };
      img.onerror = () => { clearTimeout(t); res({ err: "load" }); };
      img.src = url;
    });
  }
  async function updateColors(force) {
    if (!partOn) return;   // Refine is switched off in YTM Starcast: its colours aren't in the page at all
    if (!on("colors")) { if (colorFor !== "-") { colorFor = "-"; setPalette(palette(DEFAULT_HUE, 1), DEFAULT_HUE, 1); } return; }
    const url = artUrl();
    if (!url || colorBusy || (!force && url === colorFor)) return;
    colorBusy = true;
    try {
      const r = await sampleArt(url);
      if (!on("colors")) return; // switched off while the cover was being read
      colorFor = url;
      colorNote = r.err || "";
      lastColor = r.c || null;
      setPalette(r.c ? palette(r.c.h, r.c.s) : palette(DEFAULT_HUE, 1), r.c ? r.c.h : DEFAULT_HUE, r.c ? r.c.s : 1);
      if (r.c) writeJSON(COLOR_KEY, { h: r.c.h, s: r.c.s });
    } finally { colorBusy = false; }
  }

  // ------------------------------------------------------------------ style layers
  const CSS_UI = `
  /* the shared scale (docs/DESIGN.md): how things move, how long they take, how round they are */
  :root { --pf-ease: cubic-bezier(.2,0,0,1); --pf-spring: cubic-bezier(.34,1.3,.64,1);
    --pf-t-quick: .12s; --pf-t-short: .2s; --pf-t-medium: .3s; --pf-t-long: .45s;
    --pf-r-xs: 8px; --pf-r-sm: 12px; --pf-r-md: 16px; --pf-r-lg: 24px; --pf-r-xl: 28px; --pf-r-full: 999px; }
  .pf-ui, .pf-ui * { box-sizing: border-box; }
  /* its own text size: the page's is small and differs between its layouts */
  .pf-ui { font-family: "YouTube Sans", Roboto, "Noto Sans", system-ui, sans-serif; font-size: 14px; line-height: 1.35; color: var(--pf-text); -webkit-font-smoothing: antialiased; }
  /* :where keeps this weaker than the button classes below, whose sizes and colours must win */
  :where(.pf-ui) button { font: inherit; color: inherit; }
  .pf-nav-btn { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; margin: 0 6px; flex: none;
    border: 0; border-radius: 50%; background: transparent; color: #fff; cursor: pointer; transition: background-color .2s, border-radius var(--pf-t-medium) var(--pf-ease), color .2s; }
  .pf-nav-btn:hover { background: rgba(255,255,255,.1); border-radius: var(--pf-r-sm); }
  .pf-nav-btn[aria-expanded="true"] { background: var(--pf-container); color: var(--pf-on-container); border-radius: var(--pf-r-sm); }
  .pf-nav-btn.pf-float { position: absolute; right: 120px; top: 50%; transform: translateY(-50%); }
  .pf-sheet, .pf-pop { position: fixed; z-index: 2300; background: var(--pf-surface3); border-radius: var(--pf-r-xl); box-shadow: 0 12px 40px rgba(0,0,0,.45);
    opacity: 0; transform: scale(.94); transition: opacity var(--pf-t-short) var(--pf-ease), transform var(--pf-t-medium) var(--pf-spring); }
  .pf-sheet.pf-open, .pf-pop.pf-open { opacity: 1; transform: none; }
  .pf-sheet { width: 360px; max-width: calc(100vw - 16px); box-sizing: border-box; max-height: calc(100vh - 152px); overflow: auto; padding: 18px 8px 14px; transform-origin: top right; scrollbar-width: thin; scrollbar-color: var(--pf-outline) transparent; }
  .pf-head { padding: 0 16px 6px; }
  .pf-title { font-size: 22px; font-weight: 600; line-height: 1.25; }
  .pf-sub { font-size: 13px; color: var(--pf-muted); margin-top: 2px; }
  .pf-sec { margin: 12px 0 2px; padding: 0 16px; font-size: 12px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--pf-accent); }
  .pf-row { display: flex; align-items: center; gap: 12px; padding: 10px 16px; border-radius: var(--pf-r-md); cursor: pointer; transition: background-color .2s; }
  .pf-row:hover { background: rgba(255,255,255,.05); }
  .pf-row-text { flex: 1; min-width: 0; font-size: 15px; }
  .pf-row-text small { display: block; font-size: 12.5px; color: var(--pf-muted); margin-top: 1px; }
  .pf-switch { position: relative; flex: none; width: 52px; height: 32px; }
  .pf-switch input { position: absolute; inset: 0; opacity: 0; margin: 0; cursor: pointer; }
  .pf-switch i { position: absolute; inset: 0; border-radius: 16px; border: 2px solid var(--pf-outline); background: var(--pf-surface2); transition: background-color .2s, border-color .2s; pointer-events: none; }
  .pf-switch i::after { content: ""; position: absolute; left: 6px; top: 6px; width: 16px; height: 16px; border-radius: 50%; background: var(--pf-outline);
    transition: left var(--pf-t-medium) var(--pf-spring), top var(--pf-t-medium) var(--pf-spring), width var(--pf-t-medium) var(--pf-spring), height var(--pf-t-medium) var(--pf-spring), background-color .2s; }
  .pf-switch input:checked + i { background: var(--pf-accent); border-color: var(--pf-accent); }
  .pf-switch input:checked + i::after { left: 22px; top: 2px; width: 24px; height: 24px; background: var(--pf-on-accent); }
  .pf-switch input:focus-visible + i { outline: 2px solid var(--pf-accent); outline-offset: 2px; }
  .pf-switch input:disabled { cursor: default; }
  .pf-switch input:disabled + i { opacity: .45; }
  .pf-row:has(input:disabled) { cursor: default; }
  .pf-row:has(input:disabled):hover { background: none; }
  .pf-btn { display: inline-flex; align-items: center; justify-content: center; gap: 8px; height: 40px; padding: 0 20px; border-radius: 20px; border: 0;
    cursor: pointer; font-size: 14px; font-weight: 600; background: transparent; color: var(--pf-accent); transition: background-color .2s, border-radius var(--pf-t-short) var(--pf-ease), transform .15s; }
  .pf-btn:hover { background: rgba(255,255,255,.07); }
  .pf-btn:active { transform: scale(.96); border-radius: var(--pf-r-sm); }
  .pf-btn:disabled { opacity: .45; cursor: default; transform: none; }
  .pf-ui button:focus-visible, .pf-ui select:focus-visible, .pf-nav-btn:focus-visible, .pf-ui .pf-range:focus-visible { outline: 2px solid var(--pf-accent); outline-offset: 2px; }
  .pf-btn.pf-filled { background: var(--pf-accent); color: var(--pf-on-accent); }
  .pf-btn.pf-filled:hover { filter: brightness(1.06); }
  .pf-btn.pf-tonal { background: var(--pf-container); color: var(--pf-on-container); }
  .pf-btn.pf-wide { display: flex; width: calc(100% - 32px); margin: 14px 16px 4px; height: 48px; border-radius: 24px; font-size: 15px; }
  .pf-btn svg { flex: none; }
  .pf-icon-btn { width: 40px; padding: 0; color: var(--pf-text); }
  .pf-foot { margin: 12px 16px 0; font-size: 12px; line-height: 1.45; color: var(--pf-muted); }
  .pf-chips { display: flex; flex-wrap: wrap; gap: 8px; }
  .pf-chip { height: 32px; padding: 0 14px; border-radius: var(--pf-r-xs); border: 1px solid var(--pf-outline); background: transparent; cursor: pointer; font-size: 13.5px;
    transition: background-color .2s, border-radius var(--pf-t-short) var(--pf-ease), border-color .2s; }
  .pf-chip:hover { background: rgba(255,255,255,.06); }
  .pf-chip[aria-pressed="true"] { background: var(--pf-container); color: var(--pf-on-container); border-color: transparent; border-radius: 16px; }
  .pf-chip:disabled { opacity: .4; cursor: default; }
  /* speed menu */
  .pf-pop { width: 340px; max-width: calc(100vw - 16px); box-sizing: border-box; padding: 18px 18px 14px; transform-origin: bottom center; }
  .pf-pop .pf-chips { flex-wrap: nowrap; gap: 6px; }
  .pf-pop .pf-chips .pf-chip { flex: 1; padding: 0; font-variant-numeric: tabular-nums; }
  .pf-speed-top { display: flex; align-items: center; gap: 8px; }
  .pf-speed-val { flex: 1; text-align: center; font-size: 34px; font-weight: 600; font-variant-numeric: tabular-nums; letter-spacing: -.01em; }
  .pf-step { width: 44px; height: 44px; border-radius: 22px; border: 0; background: var(--pf-surface2); color: var(--pf-text); cursor: pointer; display: grid; place-items: center;
    transition: border-radius var(--pf-t-short) var(--pf-ease), background-color .2s; }
  .pf-step:hover { background: var(--pf-container); color: var(--pf-on-container); }
  .pf-step:active { border-radius: var(--pf-r-sm); }
  .pf-range { -webkit-appearance: none; appearance: none; width: 100%; height: 44px; margin: 8px 0 10px; background: transparent; cursor: pointer; --pf-fill: 27%; }
  .pf-range::-webkit-slider-runnable-track { height: 16px; border-radius: 8px;
    background: linear-gradient(90deg, var(--pf-accent) 0 var(--pf-fill), var(--pf-surface2) var(--pf-fill) 100%); }
  .pf-range::-webkit-slider-thumb { -webkit-appearance: none; width: 4px; height: 44px; margin-top: -14px; border-radius: 2px; background: var(--pf-accent);
    box-shadow: 0 0 0 4px var(--pf-surface3); }
  .pf-range::-moz-range-track { height: 16px; border-radius: 8px; background: var(--pf-surface2); }
  .pf-range::-moz-range-progress { height: 16px; border-radius: 8px; background: var(--pf-accent); }
  .pf-range::-moz-range-thumb { width: 4px; height: 44px; border: 0; border-radius: 2px; background: var(--pf-accent); }
  .pf-pop .pf-row { padding: 8px 4px; }
  .pf-label { font-size: 12px; color: var(--pf-muted); margin: 12px 0 6px; }
  .pf-seg { display: flex; gap: 2px; }
  .pf-seg .pf-chip { flex: 1; border-radius: 4px; padding: 0 6px; white-space: nowrap; font-size: 13px; }
  .pf-seg .pf-chip:first-child { border-radius: 16px 4px 4px 16px; }
  .pf-seg .pf-chip:last-child { border-radius: 4px 16px 16px 4px; }
  .pf-seg .pf-chip[aria-pressed="true"] { border-radius: 16px; }
  /* dialog */
  .pf-scrim { position: fixed; inset: 0; z-index: 2400; background: rgba(0,0,0,.55); display: grid; place-items: center; opacity: 0; transition: opacity .2s; }
  .pf-scrim.pf-open { opacity: 1; }
  .pf-dialog { width: min(600px, 94vw); max-height: 88vh; display: flex; flex-direction: column; background: var(--pf-surface3); border-radius: var(--pf-r-xl);
    box-shadow: 0 24px 60px rgba(0,0,0,.5); transform: translateY(16px) scale(.97); transition: transform var(--pf-t-long) var(--pf-spring); overflow: hidden; }
  .pf-scrim.pf-open .pf-dialog { transform: none; }
  .pf-dlg-head { display: flex; align-items: flex-start; gap: 8px; padding: 24px 20px 8px 24px; }
  .pf-dlg-head .pf-head { padding: 0; flex: 1; }
  .pf-dlg-body { padding: 8px 24px; overflow: auto; flex: 1; scrollbar-width: thin; }
  .pf-dlg-foot { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 8px; padding: 14px 20px 20px; }
  .pf-dlg-foot .pf-grow { flex: 1; }
  .pf-choice { display: flex; align-items: center; gap: 14px; width: 100%; padding: 12px 16px; margin: 0 0 6px; border-radius: var(--pf-r-md); border: 0; text-align: left;
    background: var(--pf-surface2); cursor: pointer; transition: background-color .2s, border-radius var(--pf-t-short) var(--pf-ease); }
  .pf-choice:hover { background: rgba(255,255,255,.08); }
  .pf-choice[aria-pressed="true"] { background: var(--pf-container); color: var(--pf-on-container); border-radius: var(--pf-r-lg); }
  .pf-choice:disabled { opacity: .45; cursor: default; }
  .pf-choice b { display: block; font-size: 15px; font-weight: 600; }
  .pf-choice span { display: block; font-size: 12.5px; opacity: .78; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pf-choice .pf-dot { flex: none; width: 20px; height: 20px; border-radius: 50%; border: 2px solid currentColor; display: grid; place-items: center; }
  .pf-choice[aria-pressed="true"] .pf-dot::after { content: ""; width: 10px; height: 10px; border-radius: 50%; background: currentColor; }
  .pf-choice .pf-txt { flex: 1; min-width: 0; }
  .pf-select, .pf-field { width: 100%; height: 48px; padding: 0 14px; border-radius: var(--pf-r-sm); border: 1px solid var(--pf-outline); background: var(--pf-surface2);
    color: var(--pf-text); font: inherit; font-size: 15px; }
  .pf-select:focus, .pf-field:focus { outline: 2px solid var(--pf-accent); outline-offset: -1px; border-color: transparent; }
  .pf-progress { height: 6px; border-radius: 3px; background: var(--pf-surface2); overflow: hidden; margin: 18px 0 10px; }
  .pf-progress i { display: block; height: 100%; width: 0; border-radius: 3px; background: var(--pf-accent); transition: width var(--pf-t-long) var(--pf-ease); }
  .pf-status { font-size: 14px; color: var(--pf-muted); }
  .pf-list { list-style: none; margin: 10px 0 0; padding: 0; }
  .pf-song { display: flex; align-items: center; gap: 12px; padding: 6px 8px; border-radius: var(--pf-r-sm); cursor: pointer; }
  .pf-song:hover { background: rgba(255,255,255,.05); }
  .pf-song img { width: 44px; height: 44px; border-radius: var(--pf-r-xs); object-fit: cover; flex: none; background: var(--pf-surface2); }
  .pf-song .pf-txt { flex: 1; min-width: 0; }
  .pf-song .pf-txt div { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pf-song .pf-t { font-size: 14.5px; }
  .pf-song .pf-a { font-size: 12.5px; color: var(--pf-muted); }
  .pf-song.pf-off { opacity: .45; }
  .pf-check { flex: none; width: 20px; height: 20px; accent-color: var(--pf-accent); cursor: pointer; }
  .pf-summary { font-size: 13px; color: var(--pf-muted); margin-top: 6px; }
  .pf-toast { position: fixed; left: 50%; bottom: 104px; z-index: 2500; max-width: min(520px, 90vw); padding: 12px 18px; border-radius: var(--pf-r-sm);
    background: var(--pf-on-container); color: var(--pf-surface); font-size: 14px; box-shadow: 0 8px 24px rgba(0,0,0,.4);
    transform: translate(-50%, 20px); opacity: 0; transition: transform var(--pf-t-medium) var(--pf-spring), opacity .2s; pointer-events: none; }
  .pf-toast.pf-open { transform: translate(-50%, 0); opacity: 1; }
  /* live lyrics: one box, in YouTube Music's Lyrics tab, in a window on the page, or in a floating window */
  .pf-ui [hidden] { display: none !important; }
  .pf-lyr-box { display: flex; flex-direction: column; flex: 1; min-height: 0; height: 100%; }
  .pf-lyr { position: fixed; z-index: 2200; display: flex; flex-direction: column; background: var(--pf-surface3); border-radius: var(--pf-r-xl);
    box-shadow: 0 16px 48px rgba(0,0,0,.5); overflow: hidden; opacity: 0; transform: translateY(14px) scale(.97); transform-origin: bottom right;
    transition: opacity var(--pf-t-short) var(--pf-ease), transform var(--pf-t-medium) var(--pf-spring), box-shadow var(--pf-t-short); }
  .pf-lyr.pf-open { opacity: 1; transform: none; }
  .pf-lyr.pf-dragging { transition: none; box-shadow: 0 28px 72px rgba(0,0,0,.6); user-select: none; }
  .pf-lyr .pf-lyr-head { cursor: grab; touch-action: none; }
  .pf-lyr.pf-dragging .pf-lyr-head { cursor: grabbing; }
  .pf-lyr.pf-pinned .pf-lyr-head { cursor: default; }
  .pf-resize { position: absolute; right: 0; bottom: 0; width: 26px; height: 26px; cursor: nwse-resize; touch-action: none; z-index: 1; }
  .pf-resize::after { content: ""; position: absolute; right: 8px; bottom: 8px; width: 9px; height: 9px; border-right: 2px solid var(--pf-outline);
    border-bottom: 2px solid var(--pf-outline); border-radius: 0 0 4px 0; transition: border-color .2s; }
  .pf-resize:hover::after { border-color: var(--pf-accent); }
  .pf-lyr.pf-pinned .pf-resize { display: none; }
  .pf-lyr-pip { position: fixed; inset: 0; display: flex; background: var(--pf-surface3); }
  /* YouTube Music's menus (z-index about 103) and dialogs open above the lyrics window, never behind it */
  ytmusic-popup-container tp-yt-iron-dropdown, tp-yt-iron-dropdown.ytmusic-popup-container { z-index: 2250 !important; }
  tp-yt-iron-overlay-backdrop { z-index: 2239 !important; } tp-yt-paper-dialog { z-index: 2240 !important; }
  /* in the Lyrics tab, Refine's lyrics take the place of YouTube Music's own */
  ytmusic-tab-renderer[data-pf-lyrics] > :not(.pf-lyr-tab) { display: none !important; }
  ytmusic-tab-renderer[data-pf-lyrics] { overflow: hidden !important; }
  .pf-lyr-tab { display: flex; flex-direction: column; min-height: 240px; animation: pf-in .35s var(--pf-ease) both; }
  ytmusic-player-page tp-yt-paper-tab[data-pf-enabled] { opacity: 1 !important; pointer-events: auto !important; cursor: pointer; }
  .pf-lyr-note { margin: auto; text-align: center; color: var(--pf-muted); font-size: 15px; display: grid; gap: 12px; justify-items: center; }
  .pf-lyr-head { display: flex; align-items: center; gap: 2px; padding: 14px 10px 8px 22px; }
  .pf-lyr-head .pf-head { flex: 1; padding: 0; min-width: 0; }
  .pf-lyr-head .pf-sub { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pf-in-tab .pf-lyr-head { padding: 6px 0 4px 8px; }
  .pf-in-tab .pf-lyr-head .pf-title { display: none; }
  .pf-lyr-opts { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 8px 14px; padding: 2px 18px 12px 22px; max-height: 46%; overflow-y: auto;
    scrollbar-width: thin; animation: pf-in .25s var(--pf-ease) both; }
  .pf-in-tab .pf-lyr-opts { padding: 2px 4px 12px 8px; }
  .pf-lyr-opts .pf-label { margin: 0; }
  .pf-lyr-opts .pf-chips { gap: 6px; }
  .pf-src .pf-chip.pf-words::after { content: ""; display: inline-block; width: 6px; height: 6px; margin-left: 6px; border-radius: 50%; background: var(--pf-accent); vertical-align: middle; }
  .pf-off { align-items: center; }
  .pf-off-val { flex: 1.2; text-align: center; font-size: 13px; font-variant-numeric: tabular-nums; color: var(--pf-text); }
  .pf-lyr-body { position: relative; flex: 1; min-height: 0; box-sizing: border-box; overflow-y: auto; padding: min(16vh, 140px) 18px min(30vh, 260px);
    scrollbar-width: none; mask-image: linear-gradient(transparent, #000 9%, #000 88%, transparent); }
  .pf-in-tab .pf-lyr-body { padding-left: 2px; padding-right: 22px; }   /* room for the line being sung, which grows a little */
  .pf-lyr-body::-webkit-scrollbar { display: none; }
  .pf-lyr-box.pf-lyr-s { --pf-ln: 18px; }
  .pf-lyr-box.pf-lyr-l { --pf-ln: 28px; }
  .pf-ln { font-size: var(--pf-ln, 23px); font-weight: 700; line-height: 1.3; padding: 7px 8px; margin: 2px 0; border-radius: var(--pf-r-sm); color: var(--pf-text);
    opacity: .34; cursor: pointer; filter: blur(.4px); transition: opacity .35s, filter .35s, transform var(--pf-t-long) var(--pf-spring), background-color .2s;
    transform-origin: left center; }
  .pf-ln:hover { background: rgba(255,255,255,.05); opacity: .7; filter: none; }
  .pf-ln.pf-now { opacity: 1; filter: none; transform: scale(1.035); }
  .pf-ln.pf-plain { cursor: default; opacity: .9; filter: none; font-size: calc(var(--pf-ln, 23px) * .82); }
  .pf-ln.pf-plain:hover { background: none; }
  .pf-ln small { display: block; font-size: calc(var(--pf-ln, 23px) * .62); font-weight: 500; color: var(--pf-muted); margin-top: 2px; }
  .pf-lyr-center .pf-ln { text-align: center; transform-origin: center; }
  .pf-lyr-norom .pf-ln small.pf-rom { display: none; }
  .pf-lyr-notr .pf-ln small.pf-tr { display: none; }
  /* word by word: each word fills with the accent as it is sung, with a soft edge, and glows once it has been; the rest of
     the line stays readable (not "currentColor": that is the transparent fill itself, which hid the unsung part) */
  .pf-ln.pf-now .pf-wd { background: linear-gradient(90deg, var(--pf-accent) calc(var(--p, 0%) - 6%), color-mix(in srgb, var(--pf-text) 62%, transparent) calc(var(--p, 0%) + 6%));
    -webkit-background-clip: text; background-clip: text; color: transparent; transition: text-shadow .4s; }
  .pf-ln.pf-now .pf-wd.pf-sung { text-shadow: 0 0 18px color-mix(in srgb, var(--pf-accent) 45%, transparent); }
  .pf-ln.pf-now.pf-est .pf-wd { background: linear-gradient(90deg, var(--pf-accent) calc(var(--p, 0%) - 14%), color-mix(in srgb, var(--pf-text) 62%, transparent) calc(var(--p, 0%) + 14%));
    -webkit-background-clip: text; background-clip: text; }
  /* only the line being sung shows how far the singing is: after a jump back, later lines must not stay coloured */
  .pf-ln:not(.pf-now) .pf-wd { background: none; color: inherit; text-shadow: none; }
  .pf-ln .pf-gap { letter-spacing: .3em; }
  .pf-ln.pf-now .pf-gap { animation: pf-breathe 1.6s ease-in-out infinite; display: inline-block; }
  @keyframes pf-breathe { 50% { opacity: .45; transform: scale(.94); } }
  @keyframes pf-in { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
  .pf-lyr-msg { color: var(--pf-muted); font-size: 15px; line-height: 1.5; text-align: center; padding: 0 12px; }
  /* small sliders and their values (room, the computer's sound) */
  .pf-range.pf-range-sm { height: 32px; margin: 2px 0 6px; }
  .pf-range.pf-range-sm::-webkit-slider-runnable-track { height: 10px; border-radius: 5px; }
  .pf-range.pf-range-sm::-webkit-slider-thumb { height: 28px; margin-top: -9px; }
  .pf-range:disabled { opacity: .45; cursor: default; }
  .pf-label-row { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
  .pf-mini-val { font-size: 12px; color: var(--pf-text); font-variant-numeric: tabular-nums; }
  .pf-snd .pf-snd-part { padding: 4px 0 8px; }
  .pf-snd .pf-row { padding: 8px 0; }
  .pf-snd-vol { display: flex; align-items: center; gap: 6px; }
  .pf-snd-vol .pf-range { flex: 1; }
  .pf-snd .pf-chips { margin: 2px 0 8px; }
  .pf-snd .pf-chip { text-transform: capitalize; }
  .pf-snd-foot { display: flex; justify-content: flex-end; }
  /* the times in the player bar, at the speed the song plays (YouTube Music's own stay in the page, hidden) */
  [data-pf-time-src] { display: none !important; }
  .pf-time { font-variant-numeric: tabular-nums; }
  /* Refine's own menus: their rows arrive one after another */
  .pf-sheet.pf-open > *, .pf-pop.pf-open > * { animation: pf-in .32s var(--pf-ease) both; }
  .pf-sheet.pf-open > :nth-child(n+2) { animation-delay: 20ms; } .pf-sheet.pf-open > :nth-child(n+4) { animation-delay: 40ms; }
  .pf-sheet.pf-open > :nth-child(n+6) { animation-delay: 60ms; } .pf-sheet.pf-open > :nth-child(n+8) { animation-delay: 80ms; }
  .pf-sheet.pf-open > :nth-child(n+10) { animation-delay: 100ms; } .pf-sheet.pf-open > :nth-child(n+12) { animation-delay: 120ms; }
  .pf-sheet.pf-open > :nth-child(n+14) { animation-delay: 140ms; }
  .pf-dc:not([hidden]) { animation: pf-in .28s var(--pf-ease) both; }
  /* the wavy progress bar is drawn over YouTube Music's own, which stays underneath (invisible) for seeking */
  .pf-wave { position: fixed; left: 0; top: 0; pointer-events: none; }
  .pf-tip { position: fixed; left: 0; top: 0; z-index: 2300; pointer-events: none; padding: 4px 9px; border-radius: var(--pf-r-xs); font-size: 12px; font-weight: 600;
    font-variant-numeric: tabular-nums; background: var(--pf-on-container); color: var(--pf-surface); opacity: 0; transition: opacity .12s; }
  .pf-tip.pf-open { opacity: 1; }
  [data-pf-wave] { opacity: 0 !important; }
  .pf-dice .pf-ico, .pf-bar-btn .pf-ico { display: grid; place-items: center; }
  /* Refine's buttons in the classic player bar, sized and spaced like its own round ones (the newer bar lends its button looks instead) */
  .pf-bar-btn { flex: none; margin: 4px 8px 4px 4px; }
  /* the dice, beside the home page's first shelf: a tonal round button like the shelf's own arrows (Material You) */
  .pf-shelf-btn { display: inline-flex; flex: none; margin-right: 8px; }
  .pf-dc-toggle { all: unset; box-sizing: border-box; width: 100%; cursor: pointer; display: flex; align-items: center; gap: 12px;
    padding: 10px 16px; border-radius: var(--pf-r-md); transition: background-color .2s; }
  .pf-dc-toggle:hover { background: rgba(255,255,255,.06); } .pf-dc-toggle:focus-visible { outline: 2px solid var(--pf-accent); }
  .pf-dc-toggle .pf-chev { font-size: 22px; color: var(--pf-muted); transition: transform var(--pf-t-short) var(--pf-ease); }
  .pf-dc-toggle[aria-expanded="true"] .pf-chev { transform: rotate(90deg); }
  .pf-dc { padding: 4px 16px 12px; } .pf-dc[hidden] { display: none; }
  .pf-shelf-btn button { all: unset; box-sizing: border-box; display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%;
    cursor: pointer; color: var(--pf-on-container, #eaddff); background: var(--pf-container, #4f378b);
    transition: border-radius var(--pf-t-medium) var(--pf-ease), transform var(--pf-t-quick), background-color var(--pf-t-short); }
  .pf-shelf-btn button:hover { border-radius: var(--pf-r-sm); }
  .pf-shelf-btn button:active { transform: scale(.9); }
  .pf-shelf-btn button:focus-visible { outline: 2px solid var(--pf-accent); outline-offset: 2px; }
  .pf-bar-btn button { all: unset; box-sizing: border-box; display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%; cursor: pointer;
    color: var(--ytmusic-text-secondary, #909090); transition: border-radius var(--pf-t-medium) var(--pf-ease), background-color .2s, color .2s, transform .15s; }
  .pf-bar-btn button:hover { color: var(--ytmusic-text-primary, #fff); background-color: color-mix(in srgb, var(--pf-accent) 16%, transparent); border-radius: var(--pf-r-sm); }
  .pf-bar-btn button:active { transform: scale(.9); }
  .pf-bar-btn button:focus-visible { outline: 2px solid var(--pf-accent); outline-offset: 2px; }
  .pf-bar-btn button[aria-pressed="true"] { background-color: var(--pf-container); color: var(--pf-on-container); border-radius: var(--pf-r-sm); }
  .pf-menu-item { display: flex; align-items: center; gap: 16px; height: 48px; padding: 0 16px; cursor: pointer; font-size: 14px;
    color: var(--ytmusic-text-primary, #fff); font-family: inherit; border-top: 1px solid rgba(255,255,255,.08); }
  .pf-menu-item:hover, .pf-menu-item:focus-visible { background: rgba(255,255,255,.1); outline: none; }
  .pf-menu-item .pf-ico { display: grid; place-items: center; color: var(--pf-accent); }
  /* one more row can make a long menu scroll; its scrollbar must not start a sideways one too */
  ytmusic-menu-popup-renderer:has(.pf-menu-item) { overflow-x: hidden !important; scrollbar-width: thin; scrollbar-color: var(--pf-outline) transparent; }
  .pf-dice button.pf-busy .pf-ico, .pf-bar-btn button.pf-busy .pf-ico { animation: pf-spin .8s linear infinite; }
  @keyframes pf-spin { to { transform: rotate(360deg); } }
  /* the Discord button beside ✦, with a dot for what Discord status is doing */
  .pf-dc-nav { position: relative; }
  .pf-dc-nav .pf-dot { position: absolute; right: 7px; top: 8px; width: 9px; height: 9px; border-radius: 50%; background: transparent;
    box-shadow: 0 0 0 2px var(--pf-surface, #030303); transition: background-color .3s; }
  .pf-dc-nav[data-state="on"] .pf-dot { background: #3fb950; }
  .pf-dc-nav[data-state="idle"] .pf-dot { background: var(--pf-outline); }
  .pf-dc-nav[data-state="warn"] .pf-dot { background: #d29922; }
  .pf-nav-btn.pf-float-2 { right: 168px; }
  /* the quick menu: tiles, two actions, All settings */
  .pf-head-logo { display: flex; align-items: center; gap: 12px; }
  .pf-head-logo .pf-logo-tile { flex: none; filter: drop-shadow(0 6px 16px var(--pf-glow)); }
  .pf-tiles { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 12px 12px 4px; }
  .pf-tile { display: flex; align-items: center; gap: 10px; min-height: 64px; padding: 10px 12px; border: 0; border-radius: var(--pf-r-lg); cursor: pointer; text-align: left;
    background: var(--pf-surface2); color: var(--pf-text); transition: background-color .25s, color .25s, border-radius var(--pf-t-medium) var(--pf-spring), transform .15s; }
  .pf-tile:hover { background: color-mix(in srgb, var(--pf-surface2) 80%, var(--pf-text)); }
  .pf-tile:active { transform: scale(.96); border-radius: var(--pf-r-md); }
  .pf-tile[aria-checked="true"] { background: var(--pf-container); color: var(--pf-on-container); border-radius: var(--pf-r-md); }
  .pf-tile-ico { flex: none; width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; background: color-mix(in srgb, currentColor 12%, transparent); }
  .pf-tile[aria-checked="true"] .pf-tile-ico { background: var(--pf-accent); color: var(--pf-on-accent); }
  .pf-tile-text { min-width: 0; font-size: 13.5px; font-weight: 600; line-height: 1.2; }
  .pf-tile-text small { display: block; font-weight: 500; font-size: 12px; opacity: .75; margin-top: 2px; }
  .pf-quick-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 8px 12px 0; }
  .pf-quick-actions .pf-btn { height: 44px; padding: 0 12px; font-size: 13.5px; border-radius: var(--pf-r-md); }
  .pf-sheet-dc { width: 400px; }
  .pf-dc-badge { flex: none; width: 40px; height: 40px; border-radius: 30%; display: grid; place-items: center; background: #5865F2; color: #fff; }
  .pf-dc-panel { padding: 0 16px 4px; }
  /* All settings: one large dialog, the sections down the side */
  .pf-all { width: min(940px, 96vw); height: min(84vh, 760px); max-height: none; }
  .pf-all .pf-dlg-head { align-items: center; padding: 20px 20px 12px 24px; }
  .pf-all .pf-head-logo { flex: 1; }
  .pf-all-main { flex: 1; min-height: 0; display: grid; grid-template-columns: 210px 1fr; }
  .pf-all-nav { display: flex; flex-direction: column; gap: 2px; padding: 4px 8px 16px 12px; overflow-y: auto; }
  .pf-all-link { all: unset; box-sizing: border-box; cursor: pointer; padding: 11px 16px; border-radius: var(--pf-r-full); font-size: 14.5px; font-weight: 500;
    color: var(--pf-muted); transition: background-color .2s, color .2s; }
  .pf-all-link:hover { background: rgba(255,255,255,.06); color: var(--pf-text); }
  .pf-all-link[aria-current="true"] { background: var(--pf-container); color: var(--pf-on-container); }
  .pf-all-link:focus-visible { outline: 2px solid var(--pf-accent); }
  .pf-all-body { overflow-y: auto; padding: 0 20px 40vh 8px; scrollbar-width: thin; scrollbar-color: var(--pf-outline) transparent; }
  .pf-all-sec { padding: 8px 0 18px; border-bottom: 1px solid color-mix(in srgb, var(--pf-outline) 45%, transparent); }
  .pf-all-sec h3 { margin: 10px 16px 6px; font-size: 13px; font-weight: 600; letter-spacing: .07em; text-transform: uppercase; color: var(--pf-accent); }
  .pf-all-sec .pf-dc, .pf-all-sec [data-pf-dc] { padding-left: 16px; padding-right: 16px; }
  .pf-all-sec .pf-snd { padding: 4px 16px 0; }
  .pf-btn.pf-inline { margin: 8px 16px 0; }
  .pf-room { padding: 6px 16px 0; }
  @media (max-width: 700px) {
    .pf-all-main { grid-template-columns: 1fr; grid-template-rows: auto 1fr; }
    .pf-all-nav { flex-direction: row; overflow-x: auto; padding: 0 12px 8px; }
    .pf-all-link { white-space: nowrap; }
  }
  /* in YouTube Music's sidebar, looking like its own entries (full, and narrow with the name under the icon) */
  .pf-guide { display: flex; flex-direction: column; gap: 2px; margin-top: 4px; }
  .pf-guide-entry { all: unset; box-sizing: border-box; display: flex; align-items: center; gap: 24px; width: 100%; height: 48px; padding: 0 16px;
    border-radius: var(--pf-r-full); cursor: pointer; color: var(--ytmusic-text-primary, #fff); font-family: "YouTube Sans", Roboto, sans-serif; font-size: 16px; font-weight: 500;
    transition: background-color .2s, transform .2s var(--pf-spring); }
  .pf-guide-entry:hover { background: rgba(255,255,255,.1); }
  .pf-guide-entry:active { transform: scale(.97); }
  .pf-guide-entry:focus-visible { outline: 2px solid var(--pf-accent); }
  .pf-guide-entry:disabled { opacity: .45; cursor: default; }
  .pf-guide-ico { display: grid; place-items: center; color: var(--pf-accent); }
  .pf-guide-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pf-guide.pf-guide-mini .pf-guide-entry { flex-direction: column; justify-content: center; gap: 6px; height: 72px; padding: 0 4px; border-radius: var(--pf-r-md); font-size: 10px; }
  .pf-guide-short { display: none; }
  .pf-guide.pf-guide-mini .pf-guide-label { display: none; }
  .pf-guide.pf-guide-mini .pf-guide-short { display: block; max-width: 100%; text-align: center; }
  /* YTM Starcast's logo in YouTube Music's top bar */
  [data-pf-logo-box] > :not(.pf-logo) { display: none !important; }
  ytmusic-logo[data-pf-logo] { width: auto !important; min-width: max-content; }
  ytmusic-logo[data-pf-logo] a { width: auto !important; }
  .pf-logo { display: inline-flex; align-items: center; gap: 8px; height: 100%; vertical-align: middle; }
  .pf-logo .pf-logo-tile { flex: none; filter: drop-shadow(0 3px 10px color-mix(in srgb, var(--pf-glow) 70%, transparent)); transition: transform .4s var(--pf-spring); }
  .pf-logo:hover .pf-logo-tile { transform: rotate(-8deg) scale(1.06); }
  .pf-logo-word { font-family: "YouTube Sans", Roboto, sans-serif; font-size: 21px; font-weight: 600; letter-spacing: -.03em; color: var(--ytmusic-text-primary, #fff); }
  /* cover visualizers (experimental): behind the big cover, inside its frame's own layer */
  ytmusic-player[data-pf-viz] { isolation: isolate; overflow: visible !important; }
  .pf-viz { position: absolute; inset: 0; z-index: -1; pointer-events: none; --pf-level: 0; }
  .pf-viz-art { position: absolute; left: -8%; top: -8%; width: 116%; height: 116%; object-fit: cover; border-radius: 34%;
    filter: blur(48px) saturate(1.8); opacity: .8; animation: pf-viz-spin 38s linear infinite; will-change: rotate; }
  .pf-viz-art.pf-b { left: -16%; top: -16%; width: 132%; height: 132%; opacity: .45; filter: blur(66px) saturate(2) brightness(1.1);
    animation: pf-viz-spin 24s linear infinite reverse, pf-viz-breathe 8s ease-in-out infinite; }
  .pf-viz[data-mode="pulse"] .pf-viz-art { scale: calc(1 + var(--pf-level) * .24); opacity: calc(.4 + var(--pf-level) * .6); }
  .pf-viz[data-mode="pulse"] .pf-viz-art.pf-b { animation: pf-viz-spin 24s linear infinite reverse; }
  .pf-viz[data-mode="bars"] .pf-viz-art { opacity: .32; }
  @keyframes pf-viz-spin { to { rotate: 360deg; } }
  @keyframes pf-viz-breathe { 50% { scale: 1.1; } }
  html:not([data-pf-playing]) .pf-viz * { animation-play-state: paused; }
  @property --pf-viz-a { syntax: "<angle>"; inherits: false; initial-value: 0deg; }
  .pf-viz-ring { position: absolute; inset: -18px; border-radius: calc(var(--pf-r-xl) + 18px); opacity: .95; filter: blur(20px);
    background: conic-gradient(from var(--pf-viz-a), transparent 0 8%, var(--pf-accent) 18%, var(--pf-glow) 26%, transparent 38% 58%,
      var(--pf-on-container) 68%, var(--pf-accent) 76%, transparent 88%);
    animation: pf-viz-turn 7s linear infinite; }
  .pf-viz-ring.pf-sharp { inset: -4px; border-radius: calc(var(--pf-r-xl) + 4px); filter: none; padding: 3px; opacity: 1;
    -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0); -webkit-mask-composite: xor; mask-composite: exclude; }
  @keyframes pf-viz-turn { to { --pf-viz-a: 360deg; } }
  .pf-viz-bars { position: absolute; pointer-events: none; }
  .pf-viz-pick { display: grid; gap: 10px; padding: 10px 16px; }
  .pf-viz-pick .pf-chips { gap: 6px; }
  @media (prefers-reduced-motion: reduce) { .pf-viz * { animation: none !important; } }
  @media (prefers-reduced-motion: reduce) { .pf-ui, .pf-ui *, .pf-nav-btn { transition: none !important; animation: none !important; } }
  `;
  // animation delays, one after another: prefix:nth-child(1..n)
  const stagger = (prefix, n, step, base = 0) => Array.from({ length: n }, (_, i) => `${prefix}:nth-child(${i + 1}) { animation-delay: ${base + i * step}ms; }`).join("\n");
  const LAYERS = {
    shapes: `
    ytmusic-two-row-item-renderer .image-wrapper, ytmusic-two-row-item-renderer ytmusic-thumbnail-renderer,
    ytmusic-two-row-item-renderer #thumbnail, ytmusic-two-row-item-renderer img,
    ytmusic-two-row-item-renderer ytmusic-item-thumbnail-overlay-renderer { border-radius: var(--pf-r-md) !important; }
    ytmusic-two-row-item-renderer[has-circle-cropped-thumbnail] img, ytmusic-two-row-item-renderer[has-circle-cropped-thumbnail] ytmusic-thumbnail-renderer { border-radius: 50% !important; }
    ytmusic-responsive-list-item-renderer ytmusic-thumbnail-renderer, ytmusic-responsive-list-item-renderer .left-items img,
    ytmusic-responsive-list-item-renderer ytmusic-item-thumbnail-overlay-renderer { border-radius: var(--pf-r-xs) !important; }
    ytmusic-responsive-list-item-renderer { border-radius: var(--pf-r-sm); }
    ytmusic-player-queue-item { border-radius: var(--pf-r-sm); overflow: hidden; }
    ytmusic-player-queue-item img, ytmusic-player-queue-item .thumbnail { border-radius: var(--pf-r-xs) !important; }
    ytmusic-player #song-image, ytmusic-player #thumbnail, ytmusic-player #song-image img, ytmusic-player .image { border-radius: var(--pf-r-xl) !important; overflow: hidden; }
    /* the frame around the cover had its own grey background and smaller corners, which showed as grey corners
       around the picture (in the player page and in the floating mini player) */
    ytmusic-player#player, ytmusic-player { border-radius: var(--pf-r-xl) !important; background-color: transparent !important; }
    ytmusic-player #song-media-window, ytmusic-player .html5-video-player { border-radius: inherit; }
    ytmusic-miniplayer img, ytmusic-player-bar .image, ytmusic-player-bar img.image { border-radius: var(--pf-r-xs) !important; }
    ytmusic-search-box #input-box, ytmusic-search-box .search-box, ytmusic-search-box #suggestion-list { border-radius: var(--pf-r-lg) !important; }
    ytmusic-chip-cloud-chip-renderer a, ytmusic-chip-cloud-chip-renderer .gradient-box, ytmusic-chip-cloud-chip-renderer { border-radius: var(--pf-r-xs) !important; }
    tp-yt-iron-dropdown ytmusic-menu-popup-renderer, ytmusic-menu-popup-renderer, ytmusic-multi-page-menu-renderer, tp-yt-paper-listbox { border-radius: var(--pf-r-md) !important; overflow: hidden; }
    tp-yt-paper-dialog, ytmusic-dialog-renderer, ytmusic-add-to-playlist-renderer { border-radius: var(--pf-r-xl) !important; overflow: hidden; }
    ytmusic-carousel-shelf-renderer yt-button-shape button, ytmusic-shelf-renderer yt-button-shape button { border-radius: var(--pf-r-full) !important; }`,
    colors: `
    html, ytmusic-app, ytmusic-app-layout {
      --ytmusic-general-background-a: var(--pf-surface) !important; --ytmusic-general-background-b: var(--pf-surface1) !important;
      --ytmusic-general-background-c: var(--pf-surface2) !important; --ytmusic-nav-bar-background: var(--pf-surface) !important;
      /* the page fades its header picture into this colour: it has to be the page's own background, or the fade ends in a hard edge */
      --ytmusic-background: var(--pf-surface) !important;
      --ytmusic-player-bar-background: var(--pf-surface2) !important; --ytmusic-brand-background-solid: var(--pf-surface) !important;
      --yt-spec-base-background: var(--pf-surface) !important; --yt-spec-raised-background: var(--pf-surface2) !important;
      --yt-spec-menu-background: var(--pf-surface3) !important; --yt-spec-call-to-action: var(--pf-accent) !important;
      --yt-spec-themed-blue: var(--pf-accent) !important; --ytmusic-color-red: var(--pf-accent) !important; }
    html, body, ytmusic-app-layout, ytmusic-app-layout > #layout { background-color: var(--pf-surface) !important; }
    /* that fade is half a screen tall and repeats down the page: unseen on black, a dark band on any other colour */
    .background-gradient.ytmusic-browse-response { background-repeat: no-repeat !important; }
    ytmusic-nav-bar #nav-bar-background { background: var(--pf-surface) !important; }
    ytmusic-miniplayer, ytmusic-player-bar { background-color: var(--pf-surface2) !important; transition: background-color .6s; }
    ytmusic-player-page { background: linear-gradient(180deg, var(--pf-surface2), var(--pf-surface) 60%) !important; }
    ytmusic-menu-popup-renderer, ytmusic-multi-page-menu-renderer, tp-yt-paper-listbox { background-color: var(--pf-surface3) !important; }
    tp-yt-paper-dialog, ytmusic-dialog-renderer { background-color: var(--pf-surface3) !important; }
    ytmusic-player-queue-item[selected] { background-color: var(--pf-container) !important; }
    ytmusic-player-queue-item[selected] .song-title, ytmusic-player-queue-item[selected] yt-formatted-string { color: var(--pf-on-container) !important; }
    ytmusic-chip-cloud-chip-renderer[is-selected] a, ytmusic-chip-cloud-chip-renderer[chip-style="STYLE_PRIMARY"] a { background-color: var(--pf-container) !important; color: var(--pf-on-container) !important; }
    ytmusic-search-box #input-box, ytmusic-search-box .search-box { background-color: var(--pf-surface2) !important; }
    /* Material You buttons: outlined pills ("More", "Play all", "Shuffle"...) are tonal, filled ones take the accent */
    button.ytSpecButtonShapeNextOutline, button.yt-spec-button-shape-next--outline {
      background-color: var(--pf-container) !important; color: var(--pf-on-container) !important; border-color: transparent !important; }
    button.ytSpecButtonShapeNextOutline:hover, button.yt-spec-button-shape-next--outline:hover {
      background-color: color-mix(in srgb, var(--pf-container) 82%, var(--pf-on-container)) !important; }
    button.ytSpecButtonShapeNextFilled, button.yt-spec-button-shape-next--filled {
      background-color: var(--pf-accent) !important; color: var(--pf-on-accent) !important; }
    button.ytSpecButtonShapeNextOutline *, button.yt-spec-button-shape-next--outline * { color: inherit !important; fill: currentColor !important; }
    button.ytSpecButtonShapeNextFilled *, button.yt-spec-button-shape-next--filled * { color: inherit !important; fill: currentColor !important; }
    ytmusic-carousel-shelf-renderer #previous-items-button, ytmusic-carousel-shelf-renderer #next-items-button {
      background-color: var(--pf-surface3) !important; border-radius: 50%; color: var(--pf-text); --iron-icon-fill-color: var(--pf-text);
      transition: border-radius var(--pf-t-medium) var(--pf-ease), background-color var(--pf-t-short) !important; }
    ytmusic-carousel-shelf-renderer #previous-items-button:not([disabled]):hover, ytmusic-carousel-shelf-renderer #next-items-button:not([disabled]):hover {
      background-color: var(--pf-container) !important; border-radius: var(--pf-r-sm); }
    ytmusic-carousel-shelf-renderer #previous-items-button[disabled], ytmusic-carousel-shelf-renderer #next-items-button[disabled] { opacity: .45; }
    ytmusic-carousel-shelf-renderer #previous-items-button yt-interaction, ytmusic-carousel-shelf-renderer #next-items-button yt-interaction { display: none; }`,
    controls: `
    .ytmusicPlayerControlsPlayPauseButton button, ytmusic-player-bar #play-pause-button {
      width: 56px !important; height: 56px !important; min-width: 56px !important; padding: 0 !important;
      background: var(--pf-accent) !important; color: var(--pf-on-accent) !important; --yt-spec-icon-inactive: var(--pf-on-accent);
      border-radius: 30% !important; transition: border-radius var(--pf-t-long) var(--pf-spring), transform .15s, background-color .5s !important; }
    .ytmusicPlayerControlsPlayPauseButton button *, ytmusic-player-bar #play-pause-button * { color: var(--pf-on-accent) !important; fill: var(--pf-on-accent) !important; }
    html[data-pf-playing] .ytmusicPlayerControlsPlayPauseButton button, html[data-pf-playing] ytmusic-player-bar #play-pause-button { border-radius: 50% !important; }
    .ytmusicPlayerControlsPlayPauseButton button:active, ytmusic-player-bar #play-pause-button:active { transform: scale(.9); }
    /* the newer player bar's round buttons (the play button keeps its own shape, above) */
    .ytmusicPlayerControlsControlButton:not(.ytmusicPlayerControlsPlayPauseButton) button { border-radius: 50% !important; transition: border-radius var(--pf-t-medium) var(--pf-ease), background-color .2s, transform .15s !important; }
    .ytmusicPlayerControlsControlButton:not(.ytmusicPlayerControlsPlayPauseButton) button:hover { border-radius: var(--pf-r-sm) !important; background-color: color-mix(in srgb, var(--pf-accent) 16%, transparent) !important; }
    .ytmusicPlayerControlsControlButton:not(.ytmusicPlayerControlsPlayPauseButton) button:active { transform: scale(.9); }
    .ytmusicPlayerControlsControlButton:not(.ytmusicPlayerControlsPlayPauseButton) button[aria-pressed="true"] { background-color: var(--pf-container) !important; color: var(--pf-on-container) !important; border-radius: var(--pf-r-sm) !important; }
    .ytmusicPlayerControlsControlButton:not(.ytmusicPlayerControlsPlayPauseButton) button[aria-pressed="true"] * { color: var(--pf-on-container) !important; fill: var(--pf-on-container) !important; }
    /* the classic player bar's round buttons */
    ytmusic-player-bar .left-controls-buttons yt-icon-button:not(#play-pause-button), ytmusic-player-bar .right-controls-buttons yt-icon-button,
    ytmusic-player-bar yt-icon-button.toggle-player-page-button {
      border-radius: 50%; transition: border-radius var(--pf-t-medium) var(--pf-ease), background-color .2s, transform .15s; }
    ytmusic-player-bar .left-controls-buttons yt-icon-button:not(#play-pause-button):hover, ytmusic-player-bar .right-controls-buttons yt-icon-button:hover,
    ytmusic-player-bar yt-icon-button.toggle-player-page-button:hover {
      border-radius: var(--pf-r-sm) !important; background-color: color-mix(in srgb, var(--pf-accent) 16%, transparent) !important; }
    ytmusic-player-bar .left-controls-buttons yt-icon-button:not(#play-pause-button):active, ytmusic-player-bar .right-controls-buttons yt-icon-button:active { transform: scale(.9); }`,
    glow: `
    ytmusic-player#player, ytmusic-player { box-shadow: 0 30px 110px -16px var(--pf-glow) !important; transition: box-shadow .9s; }
    ytmusic-miniplayer img, ytmusic-player-bar .image { box-shadow: 0 6px 22px -6px var(--pf-glow); }`,
    // Material You page parts: the tabs beside the player as a pill bar (not underlined capitals), the queue's header and
    // its Save button tonal, YouTube Music's switches in the Material 3 shape, quiet scrollbars, pill-shaped sidebar entries.
    material: `
    ytmusic-player-page tp-yt-paper-tabs { height: auto !important; margin: 10px 10px 6px !important; padding: 4px !important; border-radius: var(--pf-r-full) !important;
      background: var(--pf-surface2) !important; border: 0 !important; box-shadow: none !important; }
    ytmusic-player-page tp-yt-paper-tabs #selectionBar { display: none !important; }
    ytmusic-player-page tp-yt-paper-tabs #tabsContainer, ytmusic-player-page tp-yt-paper-tabs #tabsContent { height: auto !important; }
    ytmusic-player-page tp-yt-paper-tab { height: 40px !important; min-height: 40px; padding: 0 12px !important; margin: 0 1px; border-radius: var(--pf-r-full) !important;
      color: var(--pf-muted) !important; transition: background-color .3s var(--pf-ease), color .3s !important; }
    ytmusic-player-page tp-yt-paper-tab, ytmusic-player-page tp-yt-paper-tab * { text-transform: none !important; letter-spacing: 0 !important; font-size: 14px !important; font-weight: 500 !important; }
    ytmusic-player-page tp-yt-paper-tab:hover { background-color: color-mix(in srgb, var(--pf-text) 7%, transparent); color: var(--pf-text) !important; }
    ytmusic-player-page tp-yt-paper-tab[aria-selected="true"], ytmusic-player-page tp-yt-paper-tab.iron-selected { background-color: var(--pf-container) !important; color: var(--pf-on-container) !important; }
    ytmusic-player-page tp-yt-paper-tab[aria-selected="true"] *, ytmusic-player-page tp-yt-paper-tab.iron-selected * { font-weight: 600 !important; }
    ytmusic-player-page tp-yt-paper-tab tp-yt-paper-ripple, ytmusic-player-page tp-yt-paper-tab paper-ripple { border-radius: inherit; }
    ytmusic-queue-header-renderer { margin: 4px 10px 8px !important; padding: 12px 12px 12px 16px !important; border-radius: var(--pf-r-lg); background: var(--pf-surface1) !important; }
    ytmusic-queue-header-renderer button { border-radius: var(--pf-r-full) !important; background: var(--pf-container) !important; color: var(--pf-on-container) !important;
      border: 0 !important; box-shadow: none !important; transition: border-radius var(--pf-t-medium) var(--pf-ease), filter .2s !important; }
    ytmusic-queue-header-renderer button:hover { filter: brightness(1.1); }
    ytmusic-queue-header-renderer button:active { border-radius: var(--pf-r-sm) !important; }
    ytmusic-queue-header-renderer button * { color: inherit !important; fill: currentColor !important; }
    tp-yt-paper-toggle-button #toggleContainer { width: 52px !important; height: 32px !important; }
    tp-yt-paper-toggle-button #toggleBar { box-sizing: border-box; top: 0 !important; height: 32px !important; border-radius: 16px !important; opacity: 1 !important;
      background: var(--pf-surface2) !important; border: 2px solid var(--pf-outline); transition: background-color .2s, border-color .2s; }
    tp-yt-paper-toggle-button #toggleButton { top: 8px !important; left: 8px !important; width: 16px !important; height: 16px !important; box-shadow: none !important;
      background: var(--pf-outline) !important; transition: transform var(--pf-t-medium) var(--pf-spring), width var(--pf-t-medium) var(--pf-spring),
      height var(--pf-t-medium) var(--pf-spring), top var(--pf-t-medium) var(--pf-spring), left var(--pf-t-medium) var(--pf-spring), background-color .2s !important; }
    tp-yt-paper-toggle-button[checked] #toggleBar { background: var(--pf-accent) !important; border-color: var(--pf-accent); }
    tp-yt-paper-toggle-button[checked] #toggleButton { transform: translate(20px, 0) !important; top: 4px !important; left: 4px !important; width: 24px !important; height: 24px !important;
      background: var(--pf-on-accent) !important; }
    ytmusic-app *::-webkit-scrollbar-thumb { background-color: color-mix(in srgb, var(--pf-text) 22%, transparent) !important; border-radius: 8px !important;
      border: 3px solid transparent !important; background-clip: content-box !important; }
    ytmusic-app *::-webkit-scrollbar-track { background: transparent !important; }
    ytmusic-guide-entry-renderer tp-yt-paper-item { border-radius: var(--pf-r-full) !important; transition: background-color .25s !important; }
    ytmusic-guide-renderer[is-collapsed] ytmusic-guide-entry-renderer tp-yt-paper-item, #mini-guide ytmusic-guide-entry-renderer tp-yt-paper-item { border-radius: var(--pf-r-md) !important; }
    ytmusic-guide-entry-renderer[active] tp-yt-paper-item { background-color: var(--pf-container) !important; }
    ytmusic-guide-entry-renderer[active] tp-yt-paper-item, ytmusic-guide-entry-renderer[active] tp-yt-paper-item * { color: var(--pf-on-container) !important; fill: currentColor; }`,
    // Animations: what arrives moves into place, what is pressed gives a little. Nothing moves for anyone who asked their
    // system for less motion.
    motion: `
    @media (prefers-reduced-motion: no-preference) {
    @keyframes pf-rise { from { opacity: 0; transform: translateY(18px); } to { opacity: 1; transform: none; } }
    @keyframes pf-drop { from { opacity: 0; transform: translateY(-8px) scale(.97); } to { opacity: 1; transform: none; } }
    @keyframes pf-swap { from { opacity: 0; transform: translateX(16px); } to { opacity: 1; transform: none; } }
    @keyframes pf-cover { from { opacity: .35; transform: scale(.94); } to { opacity: 1; transform: none; } }
    @keyframes pf-fade { from { opacity: 0; } to { opacity: 1; } }
    /* a new page (or new results): its header, then its shelves and rows, rise in one after another */
    html.pf-nav ytmusic-browse-response #header > *, html.pf-nav ytmusic-responsive-header-renderer, html.pf-nav ytmusic-immersive-header-renderer,
    html.pf-nav ytmusic-detail-header-renderer, html.pf-nav ytmusic-editable-playlist-detail-header-renderer,
    html.pf-nav ytmusic-section-list-renderer > #contents > *, html.pf-nav ytmusic-search-page #contents > *,
    html.pf-nav ytmusic-tabbed-search-results-renderer #contents > *, html.pf-nav ytmusic-chip-cloud-renderer { animation: pf-rise .55s var(--pf-ease) both; }
    ${stagger("html.pf-nav ytmusic-section-list-renderer > #contents > *", 8, 55, 60)}
    html.pf-nav ytmusic-playlist-shelf-renderer #contents > :nth-child(-n+18), html.pf-nav ytmusic-shelf-renderer #contents > :nth-child(-n+18),
    html.pf-nav ytmusic-carousel #items > :nth-child(-n+10), html.pf-nav ytmusic-grid-renderer #items > :nth-child(-n+18) { animation: pf-rise .5s var(--pf-ease) both; }
    ${stagger("html.pf-nav ytmusic-playlist-shelf-renderer #contents > *", 18, 22, 140)}
    ${stagger("html.pf-nav ytmusic-shelf-renderer #contents > *", 18, 22, 100)}
    ${stagger("html.pf-nav ytmusic-carousel #items > *", 10, 35, 80)}
    ${stagger("html.pf-nav ytmusic-grid-renderer #items > *", 18, 25, 80)}
    /* cards, rows, chips and buttons answer the pointer */
    ytmusic-two-row-item-renderer { transition: transform .32s var(--pf-spring); }
    ytmusic-two-row-item-renderer:active { transform: scale(.96); transition-duration: .12s; }
    ytmusic-two-row-item-renderer .image-wrapper { overflow: hidden; }
    ytmusic-two-row-item-renderer .image-wrapper img { transition: transform .5s var(--pf-ease), filter .3s; }
    ytmusic-two-row-item-renderer:hover .image-wrapper img { transform: scale(1.06); }
    ytmusic-responsive-list-item-renderer { transition: background-color .2s, transform .28s var(--pf-spring); }
    ytmusic-responsive-list-item-renderer:active { transform: scale(.985); transition-duration: .1s; }
    ytmusic-responsive-list-item-renderer ytmusic-thumbnail-renderer img { transition: transform .4s var(--pf-ease); }
    ytmusic-responsive-list-item-renderer:hover ytmusic-thumbnail-renderer img { transform: scale(1.08); }
    ytmusic-chip-cloud-chip-renderer { transition: transform .28s var(--pf-spring); }
    ytmusic-chip-cloud-chip-renderer:active { transform: scale(.92); transition-duration: .1s; }
    button.ytSpecButtonShapeNextHost, button.yt-spec-button-shape-next, tp-yt-paper-icon-button, yt-icon-button {
      transition: transform .22s var(--pf-spring), background-color .2s, border-radius var(--pf-t-medium) var(--pf-ease); }
    button.ytSpecButtonShapeNextHost:active, button.yt-spec-button-shape-next:active, tp-yt-paper-icon-button:active, yt-icon-button:active { transform: scale(.9); }
    ytmusic-guide-entry-renderer tp-yt-paper-item { transition: background-color .25s, transform .28s var(--pf-spring); }
    ytmusic-guide-entry-renderer:active tp-yt-paper-item { transform: scale(.96); }
    ytmusic-like-button-renderer button, like-button-view-model button { transition: transform .35s var(--pf-spring); }
    ytmusic-like-button-renderer button:active, like-button-view-model button:active { transform: scale(.78); }
    /* menus, dialogs and the search box's suggestions open with a little drop */
    ytmusic-menu-popup-renderer, ytmusic-multi-page-menu-renderer { animation: pf-drop .24s var(--pf-ease) both; transform-origin: top center; }
    tp-yt-paper-dialog { animation: pf-drop .34s var(--pf-spring) both; }
    ytmusic-search-suggestions-section, tp-yt-paper-toast { animation: pf-fade .22s both; }
    /* a new song: the cover and the title in the player bar slide in, the big cover settles */
    html.pf-song ytmusic-player-bar .middle-controls .content-info-wrapper, html.pf-song ytmusic-player-bar .middle-controls .thumbnail-image-wrapper,
    html.pf-song ytmusic-player-bar img.image, html.pf-song ytmusic-miniplayer img { animation: pf-swap .5s var(--pf-ease) both; }
    html.pf-song ytmusic-player #song-image { animation: pf-cover .75s var(--pf-ease) both; }
    /* the player page opens: the cover grows into place, the panel beside it rises */
    html.pf-page ytmusic-player-page ytmusic-player { animation: pf-cover .65s var(--pf-ease) both; }
    html.pf-page ytmusic-player-page #side-panel { animation: pf-rise .6s .08s var(--pf-ease) both; }
    /* Up next, Lyrics, Related: what's under the tabs slides in */
    html.pf-tab ytmusic-player-page #tab-renderer > * { animation: pf-swap .4s var(--pf-ease) both; }
    html.pf-tab ytmusic-player-queue #contents > ytmusic-player-queue-item:nth-child(-n+12) { animation: pf-swap .4s var(--pf-ease) both; }
    ${stagger("html.pf-tab ytmusic-player-queue #contents > ytmusic-player-queue-item", 12, 25, 40)}
    ytmusic-player-page tp-yt-paper-tab { transition: color .25s; }
    ytmusic-player-queue-item { transition: background-color .35s var(--pf-ease); }
    }`,
  };
  const styleEls = {};
  function ensureStyles() {
    const add = (id, css) => {
      let el = document.getElementById(id);
      if (!el) { el = h("style", { id }); el.textContent = css; (document.head || document.documentElement).append(el); }
      return el;
    };
    add("pf-refine-ui", CSS_UI);
    for (const [k, css] of Object.entries(LAYERS)) styleEls[k] = add("pf-refine-" + k, css);
  }
  // YTM Starcast's Refine switch is off: none of Refine's styles stay in the page.
  function removeStyles() {
    for (const id of ["pf-refine-ui", ...Object.keys(LAYERS).map((k) => "pf-refine-" + k)]) {
      const el = document.getElementById(id);
      if (el) el.remove();
    }
    for (const k of Object.keys(styleEls)) delete styleEls[k];
  }

  // ------------------------------------------------------------------ the player
  const video = () => document.querySelector("#movie_player video, video.html5-main-video, video");
  function playerState() {
    const p = API.mp();
    try { return p && p.getPlayerState ? p.getPlayerState() : -1; } catch (e) { return -1; }
  }
  function times() {
    const p = API.mp();
    try { return { pos: p.getCurrentTime() || 0, dur: p.getDuration() || 0 }; } catch (e) { return { pos: 0, dur: 0 }; }
  }
  let boundVideo = null;
  function syncPlaying() {
    if (!partOn) return;
    const playing = playerState() === 1;
    if (document.documentElement.hasAttribute("data-pf-playing") !== playing) {
      document.documentElement.toggleAttribute("data-pf-playing", playing);
      kickWave();
    }
    const v = video();
    if (v && v !== boundVideo) {
      boundVideo = v;
      for (const ev of ["play", "playing", "pause", "seeked", "ratechange"]) v.addEventListener(ev, () => setTimeout(syncPlaying, 30));
      v.addEventListener("ratechange", onRateChange);
      v.addEventListener("durationchange", () => setTimeout(songChanged, 0));   // a new song has loaded
      setTimeout(() => ensureSound(false), 0);   // a new media element: its sound joins the same path, when that is in use
    }
  }

  // ------------------------------------------------------------------ wavy progress bar
  const overlays = new Map(); // YouTube Music's slider -> { canvas, ctx, ... }
  const wave = { phase: 0, amp: 0, last: 0, raf: 0, drawn: 0 };
  let drag = null; // { host, frac, until }
  function isVolume(el) {
    const s = (el.id || "") + " " + (el.getAttribute("aria-label") || "") + " " + (typeof el.className === "string" ? el.className : "");
    return /volume/i.test(s) || !!el.closest("#volume-slider, [class*='olume'], ytmusic-player-expanding-menu");
  }
  function findSliders() {
    const { dur } = times();
    const out = [];
    for (const el of document.querySelectorAll("slider-shape, tp-yt-paper-slider#progress-bar, #progress-bar, [role='slider']")) {
      if (el.closest(".pf-ui")) continue;
      const host = el.closest("slider-shape, tp-yt-paper-slider") || el;
      if (out.includes(host) || isVolume(host)) continue;
      const r = host.getBoundingClientRect();
      if (r.width < 120 || r.height === 0 || r.height > 64) continue;
      const maxEl = host.hasAttribute("aria-valuemax") ? host : host.querySelector("[aria-valuemax]");
      const max = parseFloat(maxEl ? maxEl.getAttribute("aria-valuemax") : "NaN");
      const known = host.matches("slider-shape, #progress-bar") ||
        !!host.closest("ytmusic-player-bar, ytmusic-miniplayer, ytmusic-player-page, ytmusic-wiz-player-controls");
      if (known || (dur > 0 && Math.abs(max - dur) < 3)) out.push(host);
    }
    // The newer bar (ytmusic-miniplayer) draws its progress line in a plain wrapper, with no slider element at all.
    if (!out.length) {
      for (const w of document.querySelectorAll("ytmusic-miniplayer .ytMusicMiniPlayerProgressBarWrapper, ytmusic-miniplayer [class*='ProgressBarWrapper']")) {
        const r = w.getBoundingClientRect();
        if (r.width >= 120 && r.height <= 64 && !out.includes(w)) { w.__pfLoose = true; out.push(w); }
      }
    }
    return out.slice(0, 3);
  }
  // The canvas sits in the page's top layer just above the player it belongs to (not above menus and dialogs).
  function zFor(host) {
    let z = 0;
    for (let n = host; n && n !== document.body && n !== document.documentElement;) {
      const cs = getComputedStyle(n);
      if (cs.position !== "static" && cs.zIndex !== "auto") z = parseInt(cs.zIndex, 10) || z;
      n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
    }
    return z + 1;
  }
  function syncWave() {
    if (!on("wavy")) { if (overlays.size) detachWave(); return; }
    const hosts = findSliders();
    for (const [host, o] of overlays) {
      if (!hosts.includes(host) || !host.isConnected) { host.removeAttribute("data-pf-wave"); o.canvas.remove(); overlays.delete(host); }
    }
    for (const host of hosts) {
      let o = overlays.get(host);
      if (!o) {
        const canvas = h("canvas", { class: "pf-wave pf-ui", "aria-hidden": "true" });
        document.body.append(canvas);
        o = { canvas, ctx: canvas.getContext("2d"), W: 0, dpr: 0, hidden: false, hover: false };
        overlays.set(host, o);
      }
      o.canvas.style.zIndex = String(zFor(host));
      // Covered by something (the full player page opening over the docked one, a dialog)? Then the wave isn't drawn,
      // and YouTube Music's own bar is left visible, so there is never no bar at all.
      const r = host.getBoundingClientRect();
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + Math.max(1, r.height / 2));
      // the newer bar's wrapper can be a hairline: anything of the same player at that point counts as itself
      const home = host.__pfLoose ? host.closest("ytmusic-miniplayer") : null;
      o.hidden = !!top && !(top === host || host.contains(top) || top.contains(host) || (home && home.contains(top)));
      host.toggleAttribute("data-pf-wave", !o.hidden);
    }
    kickWave();
  }
  function detachWave() {
    for (const [host, o] of overlays) { host.removeAttribute("data-pf-wave"); o.canvas.remove(); }
    overlays.clear();
    if (wave.raf) cancelAnimationFrame(wave.raf);
    wave.raf = 0;
  }
  function kickWave() {
    if (!wave.raf && overlays.size && !document.hidden) { wave.last = 0; wave.raf = requestAnimationFrame(waveFrame); }
  }
  function waveFrame(t) {
    wave.raf = 0;
    if (!overlays.size || document.hidden) return;
    const playing = document.documentElement.hasAttribute("data-pf-playing");
    const dt = wave.last ? Math.min(100, t - wave.last) : 16;
    wave.last = t;
    const target = playing ? 1 : 0;
    wave.amp += (target - wave.amp) * Math.min(1, dt / 240);
    if (Math.abs(wave.amp - target) < 0.005) wave.amp = target;
    if (playing && !REDUCED.matches) wave.phase = (wave.phase + dt * 0.0045) % (Math.PI * 2);
    if (drag && drag.until && Date.now() > drag.until) drag = null;
    const moving = (playing && !REDUCED.matches) || wave.amp !== target || !!drag;
    if (!moving || t - wave.drawn >= 32) { // about 30 frames a second is plenty for this
      wave.drawn = t;
      const tm = times();
      for (const [host, o] of overlays) drawWave(host, o, tm);
    }
    if (moving) wave.raf = requestAnimationFrame(waveFrame);
  }
  // The bar's position is measured about twice a second (and on resize or scroll), not on every frame: measuring
  // can make the browser lay the whole page out again, and YouTube Music's page is big.
  let rectEpoch = 0;
  let resizeT = 0;
  window.addEventListener("resize", () => { rectEpoch++; kickWave(); clearTimeout(resizeT); resizeT = setTimeout(syncWave, 200); }, { passive: true });
  window.addEventListener("scroll", () => { rectEpoch++; }, { capture: true, passive: true });
  function hostRect(host, o) {
    const now = performance.now();
    if (!o.rect || o.rectEpoch !== rectEpoch || now - o.rectAt > 500) { o.rect = host.getBoundingClientRect(); o.rectAt = now; o.rectEpoch = rectEpoch; }
    return o.rect;
  }
  function drawWave(host, o, tm) {
    const r = hostRect(host, o);
    if (!r.width || o.hidden || !on("wavy")) { o.canvas.style.display = "none"; return; }
    const H = 26, dpr = devicePixelRatio || 1, W = Math.round(r.width);
    if (o.W !== W || o.dpr !== dpr) {
      o.canvas.width = W * dpr; o.canvas.height = H * dpr;
      o.canvas.style.width = W + "px"; o.canvas.style.height = H + "px";
      o.W = W; o.dpr = dpr;
    }
    o.canvas.style.transform = `translate(${Math.round(r.left)}px, ${Math.round(r.top + r.height / 2 - H / 2)}px)`;
    o.canvas.style.display = "";
    const c = o.ctx;
    c.setTransform(dpr, 0, 0, dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    let frac = tm.dur > 0 ? clamp(tm.pos / tm.dur, 0, 1) : 0;
    if (drag && drag.host === host) frac = drag.frac;
    const pad = 4, mid = H / 2, x = pad + frac * (W - pad * 2);
    const amp = 3.2 * wave.amp, k = (Math.PI * 2) / 30;
    c.lineCap = "round";
    c.lineWidth = 4;
    if (x + 8 < W - pad) { // the part still to play: flat, with a dot where it ends
      c.strokeStyle = colors.track;
      c.beginPath(); c.moveTo(x + 8, mid); c.lineTo(W - pad, mid); c.stroke();
      c.fillStyle = colors.accent;
      c.beginPath(); c.arc(W - pad, mid, 2, 0, Math.PI * 2); c.fill();
    }
    if (x - 6 > pad) { // the part played: a wave while playing, flattening out when paused
      c.strokeStyle = colors.accent;
      c.beginPath();
      // the wave eases in from the start so the line doesn't begin mid-swing
      for (let px = pad; px <= x - 6; px += 1.5) {
        const ramp = Math.min(1, (px - pad) / 12);
        const y = mid + amp * ramp * Math.sin(px * k - wave.phase);
        if (px === pad) c.moveTo(px, y); else c.lineTo(px, y);
      }
      c.stroke();
    }
    const hh = drag && drag.host === host ? 24 : o.hover ? 22 : 18;
    c.fillStyle = colors.accent;
    c.beginPath();
    if (c.roundRect) c.roundRect(x - 2, mid - hh / 2, 4, hh, 2); else c.rect(x - 2, mid - hh / 2, 4, hh);
    c.fill();
  }
  const hitHost = (e, pad) => {
    for (const host of overlays.keys()) {
      const r = host.getBoundingClientRect();
      if (e.clientX >= r.left - 2 && e.clientX <= r.right + 2 && e.clientY >= r.top - pad && e.clientY <= r.bottom + pad) return { host, r };
    }
    return null;
  };
  const fracAt = (r, x) => clamp((x - r.left) / (r.width || 1), 0, 1);
  const clock = (sec) => { sec = Math.max(0, Math.floor(sec || 0)); const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, x = String(sec % 60).padStart(2, "0");
    return h ? `${h}:${String(m).padStart(2, "0")}:${x}` : `${m}:${x}`; };
  let tip = null;
  function showTip(r, x) {
    const { dur } = times();
    if (!dur) return hideTip();
    if (!tip || !tip.isConnected) { tip = h("div", { class: "pf-tip pf-ui", "aria-hidden": "true" }); document.body.append(tip); }
    tip.textContent = clock((fracAt(r, x) * dur) / rateNow());
    const w = tip.offsetWidth || 44;
    tip.style.transform = `translate(${Math.round(clamp(x - w / 2, 4, innerWidth - w - 4))}px, ${Math.round(r.top + r.height / 2 - 40)}px)`;
    tip.classList.add("pf-open");
  }
  function hideTip() { if (tip) tip.classList.remove("pf-open"); }
  window.addEventListener("pointerdown", (e) => {
    const hit = overlays.size && hitHost(e, 8);
    if (hit) { drag = { host: hit.host, frac: fracAt(hit.r, e.clientX), until: 0 }; showTip(hit.r, e.clientX); kickWave(); }
  }, true);
  window.addEventListener("pointermove", (e) => {
    if (!overlays.size) return;
    if (drag && !drag.until) { const r = drag.host.getBoundingClientRect(); drag.frac = fracAt(r, e.clientX); showTip(r, e.clientX); kickWave(); return; }
    const hit = hitHost(e, 10);
    for (const [host, o] of overlays) {
      const now = !!hit && hit.host === host;
      if (o.hover !== now) { o.hover = now; kickWave(); }
    }
    if (hit && !overlays.get(hit.host).hidden) showTip(hit.r, e.clientX); else hideTip();
  }, { capture: true, passive: true });
  window.addEventListener("pointerup", () => { if (drag) { drag.until = Date.now() + 500; hideTip(); kickWave(); } }, true);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) kickWave(); });

  // ------------------------------------------------------------------ playback speed
  const speeds = readJSON(SPEEDS_KEY, { s: {}, p: {} });
  if (!speeds.s || typeof speeds.s !== "object") speeds.s = {};
  if (!speeds.p || typeof speeds.p !== "object") speeds.p = {};
  const MAX_RULES = 400;
  function saveSpeeds() {
    for (const k of ["s", "p"]) {
      const keys = Object.keys(speeds[k]);
      if (keys.length > MAX_RULES) for (const old of keys.slice(0, keys.length - MAX_RULES)) delete speeds[k][old];
    }
    writeJSON(SPEEDS_KEY, speeds);
  }
  // The speed chosen by hand (outside a song's or playlist's remembered one). It stays from song to song and after a
  // reload, until it is changed: YouTube Music's player puts its own speed back whenever a song loads, so it is set again.
  const RATE_KEY = "pocketfader.refine.rate";
  let baseRate = clamp(Number(readJSON(RATE_KEY, {}).r) || 1, 0.25, 3);
  const fmtRate = (r) => (Math.round(r * 100) / 100).toString() + "×";
  // What the music really plays at: the media element's rate (the player rounds to the few speeds it knows).
  function getRate() {
    const v = video();
    if (v && v.playbackRate) return Math.round(v.playbackRate * 100) / 100;
    const p = API.mp();
    try { if (p && p.getPlaybackRate) return p.getPlaybackRate() || 1; } catch (e) {}
    return 1;
  }
  const adShowing = () => !!document.querySelector("#movie_player.ad-showing, .html5-video-player.ad-showing");
  // YouTube Music's player only knows a few speeds; anything in between is set on the media element itself.
  let ownRateAt = 0;
  function setRateRaw(r) {
    r = Math.round(clamp(r, 0.25, 3) * 100) / 100;
    const p = API.mp(), v = video();
    ownRateAt = Date.now();
    try { if (p && p.setPlaybackRate) p.setPlaybackRate(r); } catch (e) {}
    try { if (v && Math.abs(v.playbackRate - r) > 0.001) v.playbackRate = r; } catch (e) {}
    applyPitch();
    if (r < 0.99) ensureSound(false);
    setTimeout(syncSpeedButton, 0); // the classic bar's speed button shows when the speed isn't the usual one
    setTimeout(syncTimes, 0);
    return r;
  }
  function ruleFor(vid, pl) {
    if (vid && speeds.s[vid] != null) return { kind: "song", rate: speeds.s[vid] };
    if (pl && speeds.p[pl] != null) return { kind: "playlist", rate: speeds.p[pl] };
    return null;
  }
  function setRate(r) {
    r = setRateRaw(r);
    const vid = API.curVideo(), pl = API.curPlaylist(), rule = ruleFor(vid, pl);
    if (rule && rule.kind === "song") speeds.s[vid] = r;
    if (rule && rule.kind === "playlist") speeds.p[pl] = r;
    if (rule) saveSpeeds();
    else { baseRate = r; writeJSON(RATE_KEY, { r }); }
    return r;
  }
  function setRemember(kind) {
    const vid = API.curVideo(), pl = API.curPlaylist(), r = getRate();
    if (vid) delete speeds.s[vid];
    if (pl) delete speeds.p[pl];
    if (kind === "song" && vid) speeds.s[vid] = r;
    if (kind === "playlist" && pl) speeds.p[pl] = r;
    saveSpeeds();
    ruleApplied = kind !== "off";
    if (!ruleApplied) { baseRate = r; writeJSON(RATE_KEY, { r }); }
  }
  // The speed that should be playing: a remembered one for this song or playlist, else the one chosen by hand (with
  // the richer speed menu on; without it YouTube Music's own menu decides, and a remembered speed steps back to 1x).
  let ruleApplied = false;
  function wantRate() {
    const rule = ruleFor(API.curVideo(), API.curPlaylist());
    if (rule) { ruleApplied = true; return rule.rate; }
    if (on("speedMenu")) { ruleApplied = false; return baseRate; }
    if (ruleApplied) { ruleApplied = false; return 1; }
    return null;
  }
  let fixes = [];
  function enforceRate() {
    if (!live() || adShowing()) return applyPitch();   // adverts play at their own speed
    const want = wantRate();
    if (want != null && Math.abs(getRate() - want) > 0.01) {
      // never a tug of war with the player: at most a few corrections in ten seconds
      const now = Date.now();
      fixes = fixes.filter((t) => now - t < 10000);
      if (fixes.length < 6) { fixes.push(now); setRateRaw(want); return; }
    }
    applyPitch();
  }
  // a speed change the player made by itself (a new song loading, a quality switch) is put right at once
  function onRateChange() {
    if (Date.now() - ownRateAt < 400) { applyPitch(); return; }
    setTimeout(enforceRate, 30);
  }

  // ------------------------------------------------------------------ clear slowed sound, and a room around it
  // With "Clear" on, a slowed song is played the clear way (the pitch drops, as with a record) and stretch.js puts the
  // pitch back. That needs the page's sound to run through Web Audio, which is set up the first time it's needed, after a
  // press on the page (the browser's rule), and then stays: the browser lets a page do it only once per media element.
  const SOUND_KEY = "pocketfader.refine.sound";
  const soundOpts = (() => { const o = readJSON(SOUND_KEY, {}); return { room: clamp(Number(o.room) || 0, 0, 1) }; })();
  const snd = { ctx: null, node: null, ps: null, el: null, kind: "", err: "", dry: null, wet: null, busy: false, lagSec: 0, clear: false };
  // the cover visualizer (its own section below); Pulse and Bars listen to the music
  const VIZ_KEY = "pocketfader.refine.viz";
  const VIZ_MODES = [["off", "Off"], ["halo", "Halo"], ["orbit", "Orbit"], ["pulse", "Pulse"], ["bars", "Bars"]];
  let vizMode = (() => { const m = readJSON(VIZ_KEY, {}).mode; return VIZ_MODES.some((x) => x[0] === m) ? m : "halo"; })();
  const vizHears = () => vizMode === "pulse" || vizMode === "bars";
  const needSound = () => live() && (soundOpts.room > 0 || vizHears() || (!!prefs.clearSlow && !!prefs.keepPitch && getRate() < 0.99));
  function clearActive() {
    const v = video();
    return !!(snd.node && snd.kind !== "none" && snd.el === v && snd.ctx.state === "running" && live() && prefs.keepPitch && prefs.clearSlow &&
      v && v.playbackRate < 0.99 && !adShowing());
  }
  function applyPitch() {
    const v = video();
    if (!v) return;
    const clear = clearActive();
    if ("preservesPitch" in v) v.preservesPitch = !live() || (!!prefs.keepPitch && !clear);   // Refine off: the browser's own way
    if (clear !== snd.clear || clear) {
      snd.clear = clear;
      const alpha = clear ? 1 / v.playbackRate : 1;
      if (snd.kind === "worklet") snd.node.port.postMessage({ on: clear, alpha });
      else if (snd.ps) snd.ps.set(clear, alpha);
      // the stretched sound comes out a little later: the lyrics wait for it
      snd.lagSec = clear && snd.ctx ? (snd.kind === "worklet" ? 6144 : 6144 + 8192) / snd.ctx.sampleRate : 0;
    }
  }
  function applyRoom() {
    if (!snd.ctx) return;
    const room = live() ? soundOpts.room : 0, t = snd.ctx.currentTime;
    snd.wet.gain.setTargetAtTime(room * 0.9, t, 0.08);
    snd.dry.gain.setTargetAtTime(1 - room * 0.3, t, 0.08);
  }
  // A soft, dark room: stereo noise that dies away over two seconds, getting duller as it fades.
  function roomImpulse(ctx) {
    const sr = ctx.sampleRate, len = Math.floor(sr * 2.4), buf = ctx.createBuffer(2, len, sr);
    for (let c = 0; c < 2; c++) {
      const d = buf.getChannelData(c);
      let lp = 0, seed = 12345 + c * 7919;
      for (let i = 0; i < len; i++) {
        seed = (seed * 16807) % 2147483647;
        const t = i / sr, n = seed / 1073741823.5 - 1;
        lp += (n - lp) * (0.6 - 0.45 * Math.min(1, t / 2.4));
        d[i] = t < 0.015 ? 0 : lp * Math.exp(-t * 2.4);
      }
    }
    return buf;
  }
  // The address of one of the extension's own files, from its script (this page can't know it).
  let assetSeq = 0;
  const assetWaiting = new Map();
  function assetUrl(name) {
    return new Promise((res) => {
      const id = "pfas" + ++assetSeq;
      assetWaiting.set(id, res);
      window.postMessage({ __vmr: "asset", id, name }, "*");
      setTimeout(() => { if (assetWaiting.delete(id)) res(null); }, 3000);
    });
  }
  async function ensureSound(gesture) {
    if (snd.busy) return;
    const v = video();
    if (!v || !needSound()) { applyRoom(); applyPitch(); return; }
    if (snd.ctx && snd.el === v) { applyRoom(); applyPitch(); return; }
    // YouTube Music streams through Media Source (a blob: address on this page). Anything else can't be routed
    // through Web Audio without going silent, so it is left alone.
    if (!/^blob:/.test(v.currentSrc || v.src || "")) { snd.err = "not a stream this can use"; return; }
    if (!snd.ctx && !gesture && !(navigator.userActivation && navigator.userActivation.hasBeenActive)) return;
    snd.busy = true;
    try {
      if (!snd.ctx) {
        const AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) { snd.err = "no Web Audio"; return; }
        const ctx = new AC({ latencyHint: "playback" });
        if (ctx.state !== "running") { try { await ctx.resume(); } catch (e) {} }
        if (ctx.state !== "running") { ctx.close(); snd.err = "waiting for a press on the page"; return; }
        let node = null, kind = "none";
        const url = await assetUrl("stretch.js");
        if (url && ctx.audioWorklet) {
          try {
            await ctx.audioWorklet.addModule(url);
            node = new AudioWorkletNode(ctx, "pf-stretch", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2] });
            kind = "worklet";
          } catch (e) { snd.err = "worklet: " + String((e && e.message) || e).slice(0, 100); node = null; }
        }
        if (!node) {   // the same code on the page itself
          const S = window[Symbol.for("pocketfader.stretch")];
          if (S && ctx.createScriptProcessor) {
            const ps = new S.PitchShifter(ctx.sampleRate);
            node = ctx.createScriptProcessor(4096, 2, 2);
            node.onaudioprocess = (e) => {
              const ib = e.inputBuffer, ob = e.outputBuffer, l = ib.getChannelData(0), r = ib.numberOfChannels > 1 ? ib.getChannelData(1) : l;
              ps.process(l, r, ob.getChannelData(0), ob.getChannelData(1), ob.length);
            };
            snd.ps = ps; kind = "page";
          }
        }
        const head = node || ctx.createGain(), dry = ctx.createGain(), wet = ctx.createGain(), conv = ctx.createConvolver();
        conv.buffer = roomImpulse(ctx);
        wet.gain.value = 0;
        head.connect(dry); dry.connect(ctx.destination);
        head.connect(conv); conv.connect(wet); wet.connect(ctx.destination);
        const analyser = ctx.createAnalyser();   // for the cover visualizers: listens only, plays nothing
        analyser.fftSize = 2048;
        analyser.smoothingTimeConstant = 0.8;
        head.connect(analyser);
        // a context the system suspends (a device change) is woken again: the music runs through it now
        ctx.onstatechange = () => { if (ctx.state === "suspended") ctx.resume().catch(() => {}); applyPitch(); };
        Object.assign(snd, { ctx, node: head, kind, dry, wet, analyser });
      }
      if (snd.el !== v) {
        const src = snd.ctx.createMediaElementSource(v);   // from now on this element's sound runs through here
        src.connect(snd.node);
        snd.el = v;
      }
    } catch (e) {
      snd.err = String((e && e.message) || e).slice(0, 120);
    } finally { snd.busy = false; }
    applyRoom();
    applyPitch();
  }
  // the first press on the page lets the sound be set up, when it's wanted (Clear with a slowed song, or a room)
  for (const type of ["pointerdown", "keydown"]) window.addEventListener(type, () => { if (needSound() && !(snd.ctx && snd.el === video())) ensureSound(true); }, { capture: true, passive: true });

  // ------------------------------------------------------------------ times that follow the speed
  // At 0.8x a three-minute song takes 3:45 to hear: the player bar's times say so (and so does Discord, from the app).
  // YouTube Music's own time text stays in the page, hidden, and a copy next to it shows the times at this speed.
  const TIME_TEXT = /^\s*-?\d{1,2}:\d{2}(?::\d{2})?(?:\s*\/\s*-?\d{1,2}:\d{2}(?::\d{2})?)?\s*$/;
  const TIME_TOKEN = /-?\d{1,2}:\d{2}(?::\d{2})?/g;
  let timeEls = [], timeScanAt = 0, timeTimer = 0;
  function timeSources() {
    if (Date.now() - timeScanAt < 3000 && timeEls.length && timeEls.every((e) => e.isConnected)) return timeEls;
    timeScanAt = Date.now();
    const out = [];
    for (const root of document.querySelectorAll("ytmusic-player-bar, ytmusic-miniplayer")) {
      for (const el of root.querySelectorAll(".time-info, span, div, yt-formatted-string")) {
        if (el.firstElementChild || el.classList.contains("pf-time") || el.closest(".pf-ui") || out.includes(el)) continue;
        if (TIME_TEXT.test(el.textContent || "")) out.push(el);
      }
    }
    return (timeEls = out);
  }
  function scaleTimes(text, pos, dur, r) {
    return text.replace(TIME_TOKEN, (tok) => {
      const neg = tok[0] === "-";
      const secs = tok.replace("-", "").split(":").reduce((a, x) => a * 60 + Number(x), 0);
      let real;
      if (neg) real = Math.max(0, dur - pos) / r;
      else if (dur > 0 && Math.abs(secs - dur) <= 1.5 && Math.abs(secs - pos) > 1.5) real = dur / r;
      else if (Math.abs(secs - pos) <= 1.5) real = pos / r;
      else real = secs / r;
      return (neg ? "-" : "") + clock(real);
    });
  }
  function syncTimes() {
    const r = getRate();
    const els = on("speedTimes") && Math.abs(r - 1) > 0.001 ? timeSources() : [];
    for (const el of document.querySelectorAll("[data-pf-time-src]")) {
      if (els.includes(el)) continue;
      el.removeAttribute("data-pf-time-src");
      if (el.__pfMirror) { el.__pfMirror.remove(); el.__pfMirror = null; }
    }
    if (!els.length) { clearInterval(timeTimer); timeTimer = 0; return; }
    const { pos, dur } = times();
    for (const el of els) {
      let m = el.__pfMirror;
      if (!m || !m.isConnected) {
        m = document.createElement(el.tagName.includes("-") ? "span" : el.tagName);
        m.className = (typeof el.className === "string" ? el.className + " " : "") + "pf-time";
        el.after(m);
        el.__pfMirror = m;
        el.setAttribute("data-pf-time-src", "");
      }
      const txt = scaleTimes(el.textContent || "", pos, dur, r);
      if (m.textContent !== txt) m.textContent = txt;
    }
    if (!timeTimer) timeTimer = setInterval(syncTimes, 250);
  }
  const rateNow = () => (on("speedTimes") ? getRate() || 1 : 1);

  let speedPop = null;
  let speedFrom = null;
  function closeSpeed() {
    if (!speedPop) return;
    const el = speedPop;
    speedPop = null;
    if (el.contains(document.activeElement) && speedFrom && speedFrom.isConnected) speedFrom.focus();
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 200);
    document.removeEventListener("pointerdown", speedOutside, true);
  }
  function speedOutside(e) { if (speedPop && !speedPop.contains(e.target) && !(e.composedPath && e.composedPath().some(isSpeedButton))) closeSpeed(); }
  function openSpeed(anchor) {
    closeSpeed();
    const vid = API.curVideo(), pl = API.curPlaylist();
    const val = h("div", { class: "pf-speed-val", "aria-live": "polite" });
    const range = h("input", { type: "range", min: "0.25", max: "3", step: "0.05", class: "pf-range", "aria-label": "Playback speed" });
    const presets = [0.5, 0.75, 0.8, 1, 1.25, 1.5, 2];
    const chips = presets.map((r) => h("button", { class: "pf-chip", text: fmtRate(r), onclick: () => show(setRate(r)) }));
    const rule = ruleFor(vid, pl);
    const segs = [["off", "Any song"], ["song", "This song"], ["playlist", "This playlist"]].map(([kind, label]) =>
      h("button", { class: "pf-chip", text: label, "aria-pressed": String((rule ? rule.kind : "off") === kind),
        disabled: (kind === "song" && !vid) || (kind === "playlist" && !pl), onclick: (e) => {
          setRemember(kind);
          for (const s of segs) s.setAttribute("aria-pressed", String(s === e.currentTarget));
        } }));
    const sw2 = (checked, label, sub, onChange) => {
      const input = h("input", { type: "checkbox", role: "switch" });
      input.checked = !!checked;
      input.addEventListener("change", () => onChange(input.checked));
      return { input, row: h("label", { class: "pf-row" }, h("span", { class: "pf-row-text" }, label, h("small", { text: sub })), h("span", { class: "pf-switch" }, input, h("i"))) };
    };
    const pitch = sw2(prefs.keepPitch, "Keep the pitch", "When off, slower also sounds lower", (v) => { setPref("keepPitch", v); clear.input.disabled = !v; ensureSound(true); });
    const clear = sw2(prefs.clearSlow, "Clear slowed sound", "Slowed songs stay smooth and clear instead of smeared", (v) => { setPref("clearSlow", v); ensureSound(true); });
    clear.input.disabled = !prefs.keepPitch;
    const room = h("input", { type: "range", min: "0", max: "100", step: "5", class: "pf-range pf-range-sm", "aria-label": "Room" });
    const roomVal = h("span", { class: "pf-mini-val" });
    const showRoom = () => { room.value = String(Math.round(soundOpts.room * 100)); room.style.setProperty("--pf-fill", soundOpts.room * 100 + "%"); roomVal.textContent = soundOpts.room ? Math.round(soundOpts.room * 100) + "%" : "Off"; };
    room.addEventListener("input", () => { soundOpts.room = clamp(Number(room.value) / 100, 0, 1); writeJSON(SOUND_KEY, soundOpts); showRoom(); ensureSound(true); applyRoom(); });
    showRoom();
    function show(r) {
      val.textContent = fmtRate(r);
      range.value = String(r);
      range.style.setProperty("--pf-fill", ((r - 0.25) / 2.75) * 100 + "%");
      chips.forEach((c, i) => c.setAttribute("aria-pressed", String(Math.abs(presets[i] - r) < 0.001)));
    }
    range.addEventListener("input", () => show(setRate(parseFloat(range.value))));
    speedPop = h("div", { class: "pf-pop pf-ui", role: "dialog", "aria-label": "Playback speed" },
      h("div", { class: "pf-speed-top" },
        h("button", { class: "pf-step", "aria-label": "Slower", onclick: () => show(setRate(getRate() - 0.05)) }, icon(ICON.minus)),
        val,
        h("button", { class: "pf-step", "aria-label": "Faster", onclick: () => show(setRate(getRate() + 0.05)) }, icon(ICON.plus))),
      range,
      h("div", { class: "pf-chips" }, chips),
      pitch.row, clear.row,
      h("div", { class: "pf-label pf-label-row" }, h("span", { text: "Room (a slowed + reverb sound)" }), roomVal), room,
      h("div", { class: "pf-label", text: "This speed is for" }),
      h("div", { class: "pf-seg" }, segs));
    show(getRate());
    document.body.append(speedPop);
    const r = anchor.getBoundingClientRect();
    speedPop.style.left = clamp(r.left + r.width / 2 - 180, 8, innerWidth - 368) + "px";
    if (r.top > 420) speedPop.style.bottom = innerHeight - r.top + 10 + "px";
    else speedPop.style.top = r.bottom + 10 + "px";
    requestAnimationFrame(() => speedPop && speedPop.classList.add("pf-open"));
    setTimeout(() => document.addEventListener("pointerdown", speedOutside, true), 0);
    speedFrom = anchor;
    range.focus({ preventScroll: true });
  }
  const isSpeedButton = (n) => !!n && n.nodeType === 1 && ((n.classList && n.classList.contains("ytmusicPlayerControlsPlaybackRateButton")) ||
    (n.tagName === "BUTTON" && /^playback speed/i.test(n.getAttribute("aria-label") || "")));
  // YouTube Music's own speed button opens this menu instead of its own.
  for (const type of ["pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
    window.addEventListener(type, (e) => {
      if (!on("speedMenu")) return;
      const path = e.composedPath ? e.composedPath() : [];
      const hit = path.find(isSpeedButton);
      if (!hit) return;
      e.stopImmediatePropagation();
      if (type !== "click") return;
      e.preventDefault();
      if (speedPop) closeSpeed();
      else openSpeed(hit.tagName === "BUTTON" ? hit : hit.querySelector("button") || hit);
    }, true);
  }

  // Shift+. and Shift+, change the speed in steps (as on YouTube), when not typing somewhere.
  const STEPS = [0.25, 0.5, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3];
  window.addEventListener("keydown", (e) => {
    if (!on("speedMenu") || !e.shiftKey || e.ctrlKey || e.altKey || e.metaKey || (e.key !== ">" && e.key !== "<")) return;
    const t = e.composedPath ? e.composedPath()[0] : e.target;
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName || ""))) return;
    const cur = getRate();
    const next = e.key === ">" ? STEPS.find((x) => x > cur + 0.001) : [...STEPS].reverse().find((x) => x < cur - 0.001);
    if (next == null) return;
    e.preventDefault(); e.stopImmediatePropagation();
    toast("Speed " + fmtRate(setRate(next)));
  }, true);

  // ------------------------------------------------------------------ "something you might like" button
  // Where Refine's own player buttons go. YouTube Music has two player bars: the newer one keeps its controls in one
  // group and lends its button looks; the classic one (still shown to many people) has a row of round buttons on the right.
  function barHost() {
    const main = document.querySelector(".ytmusicPlayerControlsMainControls");
    if (main) {
      const look = main.querySelector(".ytmusicPlayerControlsRepeatButton button, .ytmusicPlayerControlsShuffleButton button");
      return { el: main, classic: false, look: look ? look.className + " " : "", repeat: main.querySelector(".ytmusicPlayerControlsRepeatButton") };
    }
    const row = document.querySelector("ytmusic-player-bar .right-controls-buttons");
    return row ? { el: row, classic: true, look: "", repeat: row.querySelector("yt-icon-button.repeat") } : null;
  }
  const barWrap = (host, cls, btn) => h("div", { class: (host.classic ? "pf-bar-btn " : "ytmusicPlayerControlsControlButton ") + cls }, btn);

  // On the home page, next to the first shelf's arrows ("Listen again" when signed in): starting something new is a
  // choice made there, not a button next to the player where a slip would replace the song that plays.
  function diceShelf() {
    if (location.pathname !== "/") return null;
    const shelves = [...document.querySelectorAll("ytmusic-browse-response ytmusic-carousel-shelf-renderer, ytmusic-section-list-renderer ytmusic-carousel-shelf-renderer")]
      .filter((s) => s.getClientRects().length);
    const title = (s) => ((s.querySelector("ytmusic-carousel-shelf-basic-header-renderer .title") || {}).textContent || "").trim();
    const s = shelves.find((x) => /listen again/i.test(title(x))) || shelves[0];
    return s ? s.querySelector(":scope .button-group.ytmusic-carousel-shelf-renderer, :scope #header-group .button-group:last-child") : null;
  }
  let dice = null;
  function syncDice() {
    const where = on("dice") ? diceShelf() : null;
    if (!where) { if (dice) { dice.remove(); dice = null; } return; }
    if (dice && dice.isConnected && dice.parentElement === where) return;
    if (dice) dice.remove();
    const btn = h("button", { class: "pf-dice-btn", "aria-label": "Play something you might like",
      title: "Play something you might like" }, h("span", { class: "pf-ico" }, icon(ICON.dice)));
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      if (btn.classList.contains("pf-busy")) return;
      btn.classList.add("pf-busy");
      try {
        const r = await API.lucky();
        toast(r && r.ok ? "Playing " + (r.picked || "something new") : "Couldn't find something to play right now");
      } catch (err) { toast("Couldn't find something to play right now"); } finally { btn.classList.remove("pf-busy"); }
    });
    dice = h("div", { class: "pf-dice pf-shelf-btn" }, btn);
    where.prepend(dice);
  }

  // The classic player bar shows no speed button for music: Refine adds one there. (The newer bar has its own, which opens this menu.)
  let speedBtn = null;
  function syncSpeedButton() {
    const host = on("speedMenu") ? barHost() : null;
    if (!host || !host.classic) { if (speedBtn) { speedBtn.remove(); speedBtn = null; } return; }
    if (!speedBtn || !speedBtn.isConnected) {
      // its label marks it as a speed button: the clicks are picked up where YouTube Music's own speed button is
      speedBtn = barWrap(host, "pf-speed-wrap", h("button", { class: "pf-speed-btn", "aria-label": "Playback speed" }, h("span", { class: "pf-ico" }, icon(ICON.speed))));
      if (host.repeat) host.repeat.before(speedBtn); else host.el.prepend(speedBtn);
    }
    const r = getRate(), changed = Math.abs(r - 1) > 0.001, b = speedBtn.firstChild;
    const label = "Playback speed" + (changed ? ": " + fmtRate(r) : "");
    if (b.title !== label) { b.title = label; b.setAttribute("aria-label", label); b.setAttribute("aria-pressed", String(changed)); }
  }

  // ------------------------------------------------------------------ live lyrics
  // Time-synced lyrics from the YTM Starcast app on this computer (the same ones its apps show), relayed by the
  // extension's own script: this page can't reach the app itself. They show in YouTube Music's own Lyrics tab (next
  // to "Up next"), and can move out into a window of their own that can be moved, resized, pinned, or kept above
  // other windows.
  let lyrSeq = 0;
  const lyrWaiting = new Map();
  window.addEventListener("message", (ev) => {
    const d = ev.source === window && ev.data;
    if (d && d.__vmr === "lyrics-result" && lyrWaiting.has(d.id)) { lyrWaiting.get(d.id)(d.data || {}); lyrWaiting.delete(d.id); }
    else if (d && d.__vmr === "asset-result" && assetWaiting.has(d.id)) { assetWaiting.get(d.id)(typeof d.url === "string" ? d.url : null); assetWaiting.delete(d.id); }
  });
  function fetchLyrics(q) {
    return new Promise((res) => {
      const id = "pfly" + ++lyrSeq;
      lyrWaiting.set(id, res);
      window.postMessage({ __vmr: "lyrics", id, q }, "*");
      setTimeout(() => { if (lyrWaiting.delete(id)) res({ error: "timeout" }); }, 20000);
    });
  }
  // how the lyrics look: text size, alignment, the romanized and translated lines, and whether words are timed by
  // estimate when a source only has line timings. Kept apart from the on/off settings.
  const LYR_KEY = "pocketfader.refine.lyrics";
  const LYR_SIZES = ["s", "m", "l"], LYR_ALIGNS = ["left", "center"];
  const lyrOpts = (() => {
    const o = readJSON(LYR_KEY, {});
    return { size: LYR_SIZES.includes(o.size) ? o.size : "m", align: LYR_ALIGNS.includes(o.align) ? o.align : "left", rom: o.rom !== false,
      tr: o.tr === true, est: o.est !== false };
  })();
  // where the lyrics window is, its size, and whether it is pinned (a pinned window stays put and comes back after a reload)
  const LYRW_KEY = "pocketfader.refine.lyricsWindow";
  const lyrWin = (() => {
    const o = readJSON(LYRW_KEY, {});
    const n = (v, d) => (typeof v === "number" && isFinite(v) ? v : d);
    return { x: n(o.x, null), y: n(o.y, null), w: n(o.w, 400), h: n(o.h, 560), pinned: o.pinned === true, open: o.open === true };
  })();
  const saveWin = () => writeJSON(LYRW_KEY, lyrWin);
  // per song: the source picked and how far the lyrics are moved in time (seconds; more = later), the newest 300 of each
  const LYR_SONG_KEY = "pocketfader.refine.lyricsSongs";
  const lyrSongs = (() => {
    const o = readJSON(LYR_SONG_KEY, {});
    const obj = (v) => (v && typeof v === "object" && !Array.isArray(v) ? v : {});
    return { src: obj(o.src), off: obj(o.off) };
  })();
  function saveSongs() {
    for (const k of ["src", "off"]) {
      const keys = Object.keys(lyrSongs[k]);
      if (keys.length > 300) for (const old of keys.slice(0, keys.length - 300)) delete lyrSongs[k][old];
    }
    writeJSON(LYR_SONG_KEY, lyrSongs);
  }
  const lyr = { key: "", L: null, sources: [], source: "", retry: 0, timer: 0, run: 0, sig: "", lines: [], els: [], idx: -1, synced: false,
    userAt: 0, hasRom: false, hasTr: false, est: false,
    box: null, head: null, sub: null, body: null, opts: null, optBtns: [], srcRow: null, offVal: null, btn: null,
    where: "", tabHost: null, tabSlot: null, tabNote: null, frame: null, pip: null,
    auto: false, pipAuto: false, pipFrom: "" };   // auto: the window (or floating window) was opened for you, and goes again by itself
  const lyrOffset = () => Number(lyrSongs.off[lyr.key]) || 0;
  // the song's time the lyrics follow: a touch early (the eye reads ahead), moved by the song's offset, and later by
  // however long the clear slowed sound takes to come out
  const lyrPos = () => times().pos + 0.15 - lyrOffset() - (snd.lagSec || 0) * getRate();

  function applyLyrOpts() {
    if (!lyr.box) return;
    const c = lyr.box.classList;
    c.toggle("pf-lyr-s", lyrOpts.size === "s");
    c.toggle("pf-lyr-l", lyrOpts.size === "l");
    c.toggle("pf-lyr-center", lyrOpts.align === "center");
    c.toggle("pf-lyr-norom", !lyrOpts.rom);
    c.toggle("pf-lyr-notr", !lyrOpts.tr);
    for (const [btn, k, v] of lyr.optBtns) {
      btn.setAttribute("aria-pressed", String(lyrOpts[k] === v));
      if (k === "rom") { btn.disabled = !lyr.hasRom; btn.title = lyr.hasRom ? "" : "This song's lyrics have no romanized line"; }
      if (k === "tr") { btn.disabled = !lyr.hasTr; btn.title = lyr.hasTr ? "" : "This song's lyrics have no translation"; }
    }
    if (lyr.offVal) { const o = lyrOffset(); lyr.offVal.textContent = (o > 0 ? "+" : "") + o.toFixed(2).replace(/0$/, "") + " s"; }
  }
  function setLyrOpt(k, v) {
    lyrOpts[k] = v;
    writeJSON(LYR_KEY, lyrOpts);
    if (k === "est") { lyr.sig = ""; if (lyr.L) renderLyrics(lyr.L); }
    applyLyrOpts();
    // the lines changed height: bring the one being sung back to its place
    const el = lyr.els[lyr.idx];
    if (el && lyr.body) lyr.body.scrollTop = el.offsetTop - lyr.body.clientHeight * 0.28;
  }
  function moveLyrics(d) {
    if (!lyr.key) return;
    const v = Math.round(clamp(lyrOffset() + d, -10, 10) * 100) / 100;
    if (v) lyrSongs.off[lyr.key] = v; else delete lyrSongs.off[lyr.key];
    saveSongs();
    applyLyrOpts();
  }
  function lyrOptions() {
    lyr.optBtns = [];
    const seg = (k, choices) => h("div", { class: "pf-seg" }, choices.map(([v, label]) => {
      const btn = h("button", { class: "pf-chip", text: label, onclick: () => setLyrOpt(k, v) });
      lyr.optBtns.push([btn, k, v]);
      return btn;
    }));
    lyr.offVal = h("span", { class: "pf-off-val" });
    lyr.srcRow = h("div", { class: "pf-chips pf-src" });
    return h("div", { class: "pf-lyr-opts", hidden: true },
      h("div", { class: "pf-label", text: "Source" }), lyr.srcRow,
      h("div", { class: "pf-label", text: "Timing" }), h("div", { class: "pf-seg pf-off" },
        h("button", { class: "pf-chip", text: "Earlier", title: "Show the lyrics a quarter of a second earlier (this song)", onclick: () => moveLyrics(-0.25) }),
        lyr.offVal,
        h("button", { class: "pf-chip", text: "Later", title: "Show the lyrics a quarter of a second later (this song)", onclick: () => moveLyrics(0.25) })),
      h("div", { class: "pf-label", text: "Words" }), seg("est", [[true, "Estimate"], [false, "Lines only"]]),
      h("div", { class: "pf-label", text: "Size" }), seg("size", [["s", "Small"], ["m", "Medium"], ["l", "Large"]]),
      h("div", { class: "pf-label", text: "Align" }), seg("align", [["left", "Left"], ["center", "Centre"]]),
      h("div", { class: "pf-label", text: "Romanized" }), seg("rom", [[true, "Show"], [false, "Hide"]]),
      h("div", { class: "pf-label", text: "Translation" }), seg("tr", [[true, "Show"], [false, "Hide"]]));
  }
  const KIND = (s) => (s.words ? "word by word" : s.synced ? "line by line" : s.found ? "not time-synced" : s.pending ? "still looking" : "nothing found");
  function renderSources() {
    if (!lyr.srcRow) return;
    lyr.srcRow.textContent = "";
    const chosen = lyrSongs.src[lyr.key] || "";
    const chip = (id, label, ok, note, words) => h("button", { class: "pf-chip" + (words ? " pf-words" : ""), "aria-pressed": String(chosen === id),
      disabled: !ok, title: note, onclick: () => setSource(id) }, label);
    lyr.srcRow.append(chip("", "Best", true, "The best one found: word by word when there is one"));
    for (const s of lyr.sources) {
      if (!s || typeof s.id !== "string") continue;
      lyr.srcRow.append(chip(s.id, String(s.label || s.id), !!s.found, KIND(s), !!s.words));
    }
  }
  function setSource(id) {
    if (!lyr.key) return;
    if (id) lyrSongs.src[lyr.key] = id; else delete lyrSongs.src[lyr.key];
    saveSongs();
    lyr.sig = ""; lyr.retry = 0; lyr.force = true;   // picked by hand: shown whatever it says
    renderSources();
    loadLyrics();
  }
  function updateSub() {
    if (!lyr.sub) return;
    const np = API.nowPlaying() || {};
    const s = lyr.sources.find((x) => x && x.id === lyr.source);
    const what = !lyr.L ? "" : (s ? s.label : lyr.L.label || "") + " · " +
      (lyr.L.synced ? (lyr.lines.some((ln) => ln.w && ln.w.length) ? "word by word" : lyr.est ? "words estimated" : "line by line") : "not time-synced");
    lyr.sub.textContent = [np.title, what].filter(Boolean).join(" · ");
  }
  function lyrBox() {
    if (lyr.box) return lyr.box;
    lyr.sub = h("div", { class: "pf-sub" });
    lyr.body = h("div", { class: "pf-lyr-body", "aria-live": "off" });
    lyr.opts = lyrOptions();
    const btn = (label, ico, fn, extra) => h("button", { class: "pf-btn pf-icon-btn", "aria-label": label, title: label, onclick: fn, ...(extra || {}) }, icon(ico, 20));
    lyr.btn = {
      opts: btn("Lyrics options", ICON.tune, () => {
        lyr.opts.hidden = !lyr.opts.hidden;
        lyr.btn.opts.setAttribute("aria-expanded", String(!lyr.opts.hidden));
        if (!lyr.opts.hidden) renderSources();
      }, { "aria-expanded": "false" }),
      out: btn("Open in a window", ICON.popOut, () => openLyricsWindow()),
      pin: btn("Pin: keep it here, also after a reload", ICON.pin, () => setPinned(!lyrWin.pinned), { "aria-pressed": "false" }),
      float: btn("Keep above other windows", ICON.pip, () => floatLyrics()),
      dock: btn("Back to the Lyrics tab", ICON.popIn, () => closeLyricsWindow()),
    };
    lyr.head = h("div", { class: "pf-lyr-head" }, h("div", { class: "pf-head" }, h("div", { class: "pf-title", text: "Lyrics" }), lyr.sub),
      Object.values(lyr.btn));
    lyr.box = h("div", { class: "pf-lyr-box" }, lyr.head, lyr.opts, lyr.body);
    for (const ev of ["wheel", "touchmove", "pointerdown"]) lyr.body.addEventListener(ev, () => { lyr.userAt = Date.now(); }, { passive: true });
    lyr.head.addEventListener("pointerdown", startDrag);
    applyLyrOpts();
    return lyr.box;
  }
  // which buttons fit where the lyrics are
  function lyrButtons() {
    if (!lyr.btn) return;
    const w = lyr.where;
    lyr.btn.out.hidden = w !== "tab";
    lyr.btn.pin.hidden = w !== "window";
    lyr.btn.float.hidden = w !== "window" || !window.documentPictureInPicture;
    lyr.btn.dock.hidden = w !== "window" && w !== "pip";
    const dockLabel = w === "pip" ? "Back into the page" : "Close (back to the Lyrics tab)";
    lyr.btn.dock.title = dockLabel;
    lyr.btn.dock.setAttribute("aria-label", dockLabel);
    lyr.btn.pin.setAttribute("aria-pressed", String(lyrWin.pinned));
    lyr.box.classList.toggle("pf-in-tab", w === "tab");
  }
  function mountLyrics(where, parent, before) {
    const box = lyrBox();
    if (box.parentNode !== parent) { if (before) parent.insertBefore(box, before); else parent.append(box); }
    const moved = lyr.where !== where;
    lyr.where = where;
    lyrButtons();
    const np = API.nowPlaying() || {};
    if ((np.videoId || "") !== lyr.key || !lyr.L) loadLyrics();
    else if (moved) { lyr.idx = -1; startTick(); }
  }
  function unmountLyrics() {
    if (lyr.box && lyr.box.parentNode) lyr.box.remove();
    lyr.where = "";
    lyr.run++;
  }

  // ---- in YouTube Music's Lyrics tab
  const LYRICS_TAB = /lyric|letra|paroles|songtext|liedtext|testo|歌詞|歌词|가사|lirik|текст|sözler/i;
  function lyricsTab() {
    const tabs = [...document.querySelectorAll("ytmusic-player-page tp-yt-paper-tab")];
    return tabs.find((t) => LYRICS_TAB.test(t.textContent || "")) || (tabs.length === 3 ? tabs[1] : null);
  }
  const tabSelected = (t) => !!t && (t.getAttribute("aria-selected") === "true" || t.classList.contains("iron-selected"));
  function syncLyricsTab() {
    const tab = on("lyrics") ? lyricsTab() : null;
    // YouTube Music greys the tab out when it has no lyrics of its own; there may well be some here
    if (tab && (tab.hasAttribute("disabled") || tab.getAttribute("aria-disabled") === "true")) {
      tab.removeAttribute("disabled");
      tab.setAttribute("aria-disabled", "false");
      tab.setAttribute("data-pf-enabled", "");
    }
    const host = tab && tabSelected(tab) ? document.querySelector("ytmusic-player-page #tab-renderer") : null;
    if (lyr.tabHost && lyr.tabHost !== host) lyr.tabHost.removeAttribute("data-pf-lyrics");
    if (!host) {
      if (lyr.tabSlot) lyr.tabSlot.remove();
      lyr.tabHost = null;
      if (lyr.where === "tab") unmountLyrics();
      return;
    }
    lyr.tabHost = host;
    host.setAttribute("data-pf-lyrics", "");
    if (!lyr.tabSlot) lyr.tabSlot = h("div", { class: "pf-lyr-tab pf-ui" });
    if (lyr.tabSlot.parentNode !== host) host.append(lyr.tabSlot);
    sizeTabSlot();
    if (lyr.frame || lyr.pip) {   // the lyrics are in their own window: the tab says so
      if (!lyr.tabNote) {
        lyr.tabNote = h("div", { class: "pf-lyr-note" }, h("p", { text: "The lyrics are in their own window." }),
          h("button", { class: "pf-btn pf-tonal", text: "Bring them back here", onclick: () => closeLyricsWindow() }));
      }
      if (lyr.tabNote.parentNode !== lyr.tabSlot) lyr.tabSlot.append(lyr.tabNote);
    } else {
      if (lyr.tabNote) lyr.tabNote.remove();
      mountLyrics("tab", lyr.tabSlot);
    }
  }
  // as tall as the room under it, down to the player bar
  function sizeTabSlot() {
    const s = lyr.tabSlot;
    if (!s || !s.isConnected) return;
    const r = s.getBoundingClientRect();
    if (!r.width) return;
    const bar = document.querySelector("ytmusic-player-bar, ytmusic-miniplayer");
    const br = bar && bar.getBoundingClientRect();
    const bottom = br && br.height && br.top > r.top + 100 ? br.top : innerHeight;
    s.style.height = Math.max(240, Math.round(bottom - r.top - 8)) + "px";
  }
  let tabsObs = null, tabsEl = null, lastTabSel = "";
  function watchTabs() {
    const el = document.querySelector("ytmusic-player-page tp-yt-paper-tabs");
    if (el === tabsEl) return;
    if (tabsObs) tabsObs.disconnect();
    tabsEl = el;
    if (!el) return;
    tabsObs = new MutationObserver(() => {
      const sel = [...el.querySelectorAll("tp-yt-paper-tab")].map(tabSelected).join();
      if (sel !== lastTabSel) { if (lastTabSel) pulse("pf-tab", 700); lastTabSel = sel; }
      syncLyricsTab();
    });
    tabsObs.observe(el, { attributes: true, subtree: true, attributeFilter: ["aria-selected", "class", "disabled", "aria-disabled"] });
  }

  // ---- in a window of its own
  function openLyricsWindow(auto) {
    if (lyr.frame || lyr.pip || !on("lyrics")) return;
    lyr.auto = auto === true;
    const handle = h("div", { class: "pf-resize", "aria-hidden": "true" });
    handle.addEventListener("pointerdown", startResize);
    lyr.frame = h("div", { class: "pf-lyr pf-ui", role: "dialog", "aria-label": "Lyrics" }, handle);
    document.body.append(lyr.frame);
    placeWindow();
    mountLyrics("window", lyr.frame, handle);
    lyrWin.open = true; saveWin();
    syncLyricsTab();
    requestAnimationFrame(() => lyr.frame && lyr.frame.classList.add("pf-open"));
  }
  function closeLyricsWindow() {
    if (lyr.pip) { const pw = lyr.pip; lyr.pip = null; try { pw.close(); } catch (e) {} }
    const el = lyr.frame;
    lyr.frame = null;
    lyr.auto = false;
    if (lyr.where === "window" || lyr.where === "pip") lyr.where = "closing";
    lyrWin.open = false; saveWin();
    const done = () => {
      if (el) el.remove();
      if (lyr.where === "closing") unmountLyrics();
      syncLyricsTab();
    };
    if (el) { el.classList.remove("pf-open"); setTimeout(done, 200); } else done();
  }
  function placeWindow() {
    const f = lyr.frame;
    if (!f) return;
    const w = Math.round(clamp(lyrWin.w, 280, Math.max(280, innerWidth - 16))), hh = Math.round(clamp(lyrWin.h, 260, Math.max(260, innerHeight - 16)));
    let x = lyrWin.x, y = lyrWin.y;
    if (x == null || y == null) {   // first time: bottom right, above the player bar
      const bar = document.querySelector("ytmusic-miniplayer, ytmusic-player-bar");
      const r = bar && bar.getBoundingClientRect();
      const bottom = r && r.height && r.top > innerHeight / 2 ? r.top - 12 : innerHeight - 24;
      x = innerWidth - w - 16; y = bottom - hh;
    }
    x = Math.round(clamp(x, 8, Math.max(8, innerWidth - w - 8)));
    y = Math.round(clamp(y, 8, Math.max(8, innerHeight - hh - 8)));
    f.style.left = x + "px"; f.style.top = y + "px"; f.style.width = w + "px"; f.style.height = hh + "px";
    f.classList.toggle("pf-pinned", lyrWin.pinned);
  }
  function setPinned(v) {
    lyr.auto = false;   // looked after by hand now: it stays
    lyrWin.pinned = !!v;
    saveWin();
    placeWindow();
    lyrButtons();
    toast(v ? "Pinned: the lyrics stay here, also after a reload" : "Unpinned: drag the lyrics anywhere");
  }
  function dragWith(e, onMove) {
    e.preventDefault();
    lyr.auto = false;
    const f = lyr.frame;
    f.classList.add("pf-dragging");
    const move = (ev) => onMove(ev);
    const up = () => {
      window.removeEventListener("pointermove", move, true);
      window.removeEventListener("pointerup", up, true);
      window.removeEventListener("pointercancel", up, true);
      if (lyr.frame) lyr.frame.classList.remove("pf-dragging");
      saveWin();
      sizeTabSlot();
    };
    window.addEventListener("pointermove", move, true);
    window.addEventListener("pointerup", up, true);
    window.addEventListener("pointercancel", up, true);
  }
  function startDrag(e) {
    if (lyr.where !== "window" || !lyr.frame || lyrWin.pinned || e.button !== 0 || (e.target.closest && e.target.closest("button, input, select"))) return;
    const r = lyr.frame.getBoundingClientRect(), dx = e.clientX - r.left, dy = e.clientY - r.top;
    dragWith(e, (ev) => {
      lyrWin.x = Math.round(clamp(ev.clientX - dx, 8, innerWidth - r.width - 8));
      lyrWin.y = Math.round(clamp(ev.clientY - dy, 8, innerHeight - r.height - 8));
      lyr.frame.style.left = lyrWin.x + "px"; lyr.frame.style.top = lyrWin.y + "px";
    });
  }
  function startResize(e) {
    if (!lyr.frame || lyrWin.pinned || e.button !== 0) return;
    const r = lyr.frame.getBoundingClientRect(), sx = e.clientX, sy = e.clientY;
    lyrWin.x = Math.round(r.left); lyrWin.y = Math.round(r.top);
    dragWith(e, (ev) => {
      lyrWin.w = Math.round(clamp(r.width + ev.clientX - sx, 280, innerWidth - r.left - 8));
      lyrWin.h = Math.round(clamp(r.height + ev.clientY - sy, 260, innerHeight - r.top - 8));
      lyr.frame.style.width = lyrWin.w + "px"; lyr.frame.style.height = lyrWin.h + "px";
    });
  }
  // Above every other window (the browser's floating window for a page, where it has one).
  async function floatLyrics(auto) {
    const dpip = window.documentPictureInPicture;
    if (!dpip || lyr.pip || !lyr.where || lyr.where === "closing") return;
    lyr.pipAuto = auto === true;
    lyr.pipFrom = lyr.where === "window" && !lyr.auto ? "window" : lyr.where === "window" ? "auto-window" : "tab";
    const r = lyr.frame ? lyr.frame.getBoundingClientRect() : { width: lyrWin.w, height: lyrWin.h };
    let pw;
    try { pw = await dpip.requestWindow({ width: Math.round(r.width), height: Math.round(r.height) }); } catch (e) { toast("The browser didn't open a floating window"); return; }
    lyr.pip = pw;
    const d = pw.document;
    d.title = "Lyrics";
    const st = d.createElement("style");
    st.textContent = CSS_UI + "\nhtml, body { margin: 0; height: 100%; background: var(--pf-surface3); overflow: hidden; }";
    d.head.append(st);
    copyPalette(d.documentElement);
    const holder = d.createElement("div");
    holder.className = "pf-ui pf-lyr-pip";
    d.body.append(holder);
    const from = lyr.frame;
    lyr.frame = null;
    mountLyrics("pip", holder);
    if (from) from.remove();
    syncLyricsTab();   // the Lyrics tab says where they went
    pw.addEventListener("pagehide", () => {
      if (lyr.pip !== pw) return;
      lyr.pip = null;
      if (lyr.where !== "pip") return;
      unmountLyrics();
      // closed by hand: back into the page as a window; opened by itself: back where they were
      if (!lyr.pipAuto || lyr.pipFrom === "window") openLyricsWindow();
      else if (lyr.pipFrom === "auto-window") openLyricsWindow(true);
      else syncLyricsTab();
      lyr.pipAuto = false;
    }, { once: true });
  }

  // ---- lyrics that follow you
  // Leaving the player page while its Lyrics tab shows the lyrics brings them along in a window, which goes again when
  // you come back to the Lyrics tab (a window opened, moved or pinned by hand stays).
  function lyricsAway() {
    if (on("lyrics") && prefs.lyricsFollow && lyr.where === "tab" && !lyr.frame && !lyr.pip && lyr.L) openLyricsWindow(true);
  }
  function lyricsBack() {
    if (lyr.frame && lyr.auto && tabSelected(lyricsTab())) closeLyricsWindow();
  }
  // Switching to another tab while the lyrics are on screen and music plays: the browser may put them in a window above
  // everything else (its "automatic picture-in-picture", where it has one); they come back when you do.
  let floatSet = null;
  function lyricsOnScreen() {
    if (lyr.where === "window") return true;
    return lyr.where === "tab" && !!pageOpen && !!lyr.body && lyr.body.getClientRects().length > 0;
  }
  function syncAutoFloat() {
    const want = !!window.documentPictureInPicture && on("lyrics") && !!prefs.lyricsFloat && !lyr.pip && lyricsOnScreen() &&
      document.documentElement.hasAttribute("data-pf-playing");
    if (want === floatSet) return;
    floatSet = want;
    try { navigator.mediaSession.setActionHandler("enterpictureinpicture", want ? () => floatLyrics(true) : null); } catch (e) { /* not in this browser */ }
  }
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && lyr.pip && lyr.pipAuto) { try { lyr.pip.close(); } catch (e) {} }   // back in the tab: so are the lyrics
  });

  function copyPalette(root) {
    const src = document.documentElement.style;
    for (const k of Object.keys(colors)) {
      const name = "--pf-" + k.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase());
      root.style.setProperty(name, src.getPropertyValue(name));
    }
  }

  // ---- what is shown
  function lyrMessage(text) {
    if (!lyr.body) return;
    lyr.body.textContent = "";
    lyr.body.append(h("div", { class: "pf-lyr-msg", text }));
    lyr.lines = []; lyr.els = []; lyr.idx = -1; lyr.synced = false;
    lyr.hasRom = lyr.hasTr = false; applyLyrOpts();
  }
  async function loadLyrics() {
    if (!lyr.where || lyr.where === "closing") return;
    const np = API.nowPlaying() || {};
    if (!np.videoId || !np.title) {
      // nothing to look up yet (also for a moment between songs): looked at again as soon as a song is there
      lyr.key = ""; lyr.L = null; lyr.sources = [];
      updateSub();
      lyrMessage("Play a song to see its lyrics.");
      return;
    }
    const key = np.videoId;
    if (key !== lyr.key) {
      lyr.key = key; lyr.retry = 0; lyr.L = null; lyr.sources = []; lyr.source = ""; lyr.sig = ""; lyr.agreed = false;
      clearTimeout(lyr.timer);
      updateSub();
      renderSources();
      lyrMessage("Looking for lyrics…");
    }
    const r = await fetchLyrics({ videoId: np.videoId, title: np.title, artist: np.artist || "", duration: Math.round(np.duration || 0),
      source: lyrSongs.src[key] || "" });
    if (lyr.key !== key || !lyr.where) return;   // the song changed meanwhile, or the lyrics were closed
    if (r.error) {
      if (!lyr.L) lyrMessage(r.error === "offline" ? "Live lyrics come from the YTM Starcast app on this computer. Start it, and they appear here."
        : r.error === "old_app" ? "Live lyrics need a newer YTM Starcast app on this computer. Update it, and they appear here."
        : "The YTM Starcast app didn't answer in time. Trying again…");
      if (r.error !== "old_app" && lyr.retry++ < 20) { clearTimeout(lyr.timer); lyr.timer = setTimeout(() => loadLyrics(), 5000); }
      return;
    }
    lyr.sources = Array.isArray(r.sources) ? r.sources : [];
    lyr.source = typeof r.source === "string" ? r.source : "";
    renderSources();
    const L = r.lyrics;
    const words = !!(L && L.synced && Array.isArray(L.lines) && L.lines.some((ln) => Array.isArray(ln.w) && ln.w.length));
    // other sources may still be searching: ask again shortly while what's here isn't word by word yet
    if (r.pending && !words && lyr.retry++ < 6) { clearTimeout(lyr.timer); lyr.timer = setTimeout(() => loadLyrics(), 2500); }
    if (!r.found || !L || !Array.isArray(L.lines) || !L.lines.length) {
      if (!r.pending) { lyr.L = null; lyr.sig = ""; updateSub(); lyrMessage("No lyrics found for this song."); }
      return;
    }
    const sig = [r.source, L.lines.length, words, lyrOpts.est].join("|");
    if (sig === lyr.sig) return;   // the same as on screen: left as it is
    // Lyrics already on screen are only swapped for the same words (better timed), never for another song's, unless
    // the app is now sure (two sources, or YouTube Music's own lyrics, agree) where it wasn't before.
    if (lyr.L && lyr.source && r.source !== lyr.source && !lyr.force && !lyrSongs.src[key] && sameWords(lyr.L, L) < 0.4 && !(r.agreed && !lyr.agreed)) return;
    lyr.agreed = !!r.agreed;
    lyr.force = false;
    lyr.sig = sig;
    renderLyrics(L);
  }
  // How many of their words two sets of lyrics share (0 to 1): the same song from two sources shares most of them.
  function sameWords(a, b) {
    const bag = (L) => {
      const out = new Set();
      for (const ln of L.lines || []) for (const w of String(ln.text || "").toLowerCase().match(/[\p{L}\p{N}]+/gu) || []) if (w.length > 1) out.add(w);
      return out;
    };
    const x = bag(a), y = bag(b);
    if (!x.size || !y.size) return 0;
    let both = 0;
    for (const w of x) if (y.has(w)) both++;
    return both / (x.size + y.size - both);
  }
  // Words timed by estimate, for lyrics timed only line by line: the line's time is shared out by word length.
  function splitWords(text) {
    const out = text.match(/\S+\s*/g) || [text];
    if (out.length === 1 && text.length > 3 && /[぀-ヿ㐀-鿿가-힯]/.test(text)) return [...text];   // no spaces: each character
    return out;
  }
  function estimateWords(lines, i) {
    const ln = lines[i];
    let next = null;
    for (let k = i + 1; k < lines.length; k++) if (lines[k].t != null) { next = lines[k].t; break; }
    const gap = next != null ? next - ln.t : 4;
    const parts = splitWords(ln.text);
    const weight = (s) => s.replace(/\s+/g, "").length + 1;
    const total = parts.reduce((a, p) => a + weight(p), 0);
    const dur = Math.max(0.3, Math.min(gap * 0.9, Math.max(0.8, total * 0.11)));
    let t = ln.t;
    return parts.map((p) => { const d = (dur * weight(p)) / total, w = [t, t + d, p]; t += d; return w; });
  }
  function lineAt(pos) {
    let i = -1;
    for (let k = 0; k < lyr.lines.length; k++) { const t = lyr.lines[k].t; if (t != null && t <= pos) i = k; else if (t > pos) break; }
    return i;
  }
  function renderLyrics(L) {
    lyr.L = L;
    lyr.synced = !!L.synced;
    lyr.lines = L.lines;
    const words = L.synced && L.lines.some((ln) => Array.isArray(ln.w) && ln.w.length);
    lyr.est = !!L.synced && !words && lyrOpts.est;
    lyr.body.textContent = "";
    lyr.els = L.lines.map((ln, i) => {
      const el = h("div", { class: "pf-ln" + (L.synced ? "" : " pf-plain") + (lyr.est ? " pf-est" : "") });
      let w = Array.isArray(ln.w) && ln.w.length ? ln.w : null;
      if (!w && lyr.est && ln.text && ln.t != null) w = estimateWords(L.lines, i);
      ln._w = w;
      if (!ln.text) el.append(h("span", { class: "pf-gap", text: "♪ ♪ ♪" }));
      else if (L.synced && w) for (const [, , word] of w) el.append(h("span", { class: "pf-wd", text: word }));
      else el.append(ln.text);
      el.__spans = el.querySelectorAll(".pf-wd");
      if (ln.rom) el.append(h("small", { class: "pf-rom", text: ln.rom }));
      if (ln.tr) el.append(h("small", { class: "pf-tr", text: ln.tr }));
      if (L.synced && ln.t != null) el.addEventListener("click", () => { try { API.mp().seekTo(Math.max(0, ln.t + lyrOffset()), true); lyr.userAt = 0; } catch (e) {} });
      lyr.body.append(el);
      return el;
    });
    lyr.idx = -1;
    lyr.hasRom = L.lines.some((ln) => !!ln.rom);
    lyr.hasTr = L.lines.some((ln) => !!ln.tr);
    applyLyrOpts();
    updateSub();
    if (L.synced) {
      // the line being sung goes straight to its place (no sweep down from the top)
      const el = lyr.els[lineAt(lyrPos())];
      if (el) lyr.body.scrollTop = el.offsetTop - lyr.body.clientHeight * 0.28;
      startTick();
    } else { lyr.run++; lyr.body.scrollTop = 0; }
  }
  // one loop at a time, on the frames of the window the lyrics are in (a floating window keeps drawing while this tab
  // is in the background)
  function startTick() {
    const run = ++lyr.run;
    const win = lyr.pip || window;
    win.requestAnimationFrame(() => lyrTick(run));
  }
  function lyrTick(run) {
    if (run !== lyr.run || !lyr.where || !lyr.synced || !lyr.lines.length || !lyr.body) return;
    const win = lyr.pip || window;
    const shown = lyr.pip ? true : !document.hidden && lyr.body.getClientRects().length > 0;
    if (!shown) { setTimeout(() => lyrTick(run), 400); return; }
    const pos = lyrPos();
    const i = lineAt(pos);
    if (i !== lyr.idx) {
      const old = lyr.els[lyr.idx];
      if (old) { old.classList.remove("pf-now"); for (const s of old.__spans || []) s.classList.remove("pf-sung"); }
      lyr.idx = i;
      const el = lyr.els[i];
      if (el) {
        el.classList.add("pf-now");
        if (Date.now() - lyr.userAt > 4000) lyr.body.scrollTo({ top: el.offsetTop - lyr.body.clientHeight * 0.28, behavior: REDUCED.matches ? "auto" : "smooth" });
      }
    }
    const ln = lyr.lines[i], el = lyr.els[i];
    if (ln && el && ln._w) {
      const spans = el.__spans || [];
      ln._w.forEach(([a, b], k) => {
        const sp = spans[k];
        if (!sp) return;
        const p = b > a ? clamp((pos - a) / (b - a), 0, 1) : pos >= a ? 1 : 0;
        const v = Math.round(p * 1000) / 10 + "%";
        if (sp.__p !== v) { sp.__p = v; sp.style.setProperty("--p", v); sp.classList.toggle("pf-sung", p > 0); }
      });
    }
    win.requestAnimationFrame(() => lyrTick(run));
  }
  // looked at with every turn of the main loop: a new song (or one whose details weren't there yet) gets its lyrics
  function lyrSync() {
    if (!lyr.where || lyr.where === "closing") return;
    const np = API.nowPlaying() || {};
    if ((np.videoId || "") !== lyr.key) loadLyrics();
  }
  function syncLyrics() {
    if (!on("lyrics")) {
      if (lyr.frame || lyr.pip) closeLyricsWindow();
      syncLyricsTab();
      return;
    }
    watchTabs();
    syncLyricsTab();
    if (lyr.frame && !lyr.frame.classList.contains("pf-dragging")) placeWindow();
    lyrSync();
  }

  // ------------------------------------------------------------------ YouTube Music's own song menu
  // When YouTube Music opens a song's menu, it gets a "Playlist from this song" item. The song is the one the
  // menu's own "Start radio" item points at, so menus that aren't about a song are left alone.
  function menuSong(popup) {
    for (const it of popup.querySelectorAll("ytmusic-menu-navigation-item-renderer, ytmusic-menu-service-item-renderer")) {
      const d = it.data || {};
      const ep = d.navigationEndpoint || d.serviceEndpoint || {};
      const we = ep.watchEndpoint || ep.watchPlaylistEndpoint || {};
      const m = /^RDAMVM([\w-]{11})$/.exec(we.playlistId || "");
      if (m) return m[1];
      if (we.videoId && /^RDAMVM/.test(we.playlistId || "")) return we.videoId;
    }
    return null;
  }
  function addMenuItem() {
    if (!on("songMenu")) return;
    for (const popup of document.querySelectorAll("ytmusic-menu-popup-renderer")) {
      if (!popup.getClientRects().length || popup.querySelector(".pf-menu-item")) continue; // hidden, or done already
      const vid = menuSong(popup);
      if (!vid) continue;
      const list = popup.querySelector("tp-yt-paper-listbox, #items") || popup;
      const go = () => {
        document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", keyCode: 27, bubbles: true }));
        setTimeout(() => openMaker({ videoId: vid }), 60);
      };
      const item = h("div", { class: "pf-menu-item", role: "menuitem", tabindex: "0", onclick: go,
        onkeydown: (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); go(); } } },
        h("span", { class: "pf-ico" }, icon(ICON.playlistAdd)), h("span", { text: "Playlist from this song" }));
      list.append(item);
    }
  }
  // A second click on the ⋮ that opened YouTube Music's menu closes it. On its own, YouTube Music closes the menu
  // as the button is pressed and then opens it again on the click, so it never closes.
  let menuBtn = null, closing = null;
  const menuButtonOf = (t) => {
    const r = t && t.closest ? t.closest("ytmusic-menu-renderer") : null;
    return r ? (t.closest("button, yt-icon-button") || null) : null;
  };
  const openMenu = () => [...document.querySelectorAll("ytmusic-popup-container tp-yt-iron-dropdown")]
    .find((d) => d.opened !== false && d.getClientRects().length && getComputedStyle(d).display !== "none");
  window.addEventListener("pointerdown", (e) => {
    closing = null;
    if (!partOn || e.button !== 0) return;
    const b = menuButtonOf(e.target);
    const dd = b && menuBtn && (b === menuBtn || b.contains(menuBtn) || menuBtn.contains(b)) ? openMenu() : null;
    if (dd) { closing = { b, dd }; e.stopImmediatePropagation(); }
  }, true);
  for (const type of ["pointerup", "mousedown", "mouseup"]) {
    window.addEventListener(type, (e) => { if (closing && menuButtonOf(e.target) === closing.b) e.stopImmediatePropagation(); }, true);
  }
  window.addEventListener("click", (e) => {
    const b = menuButtonOf(e.target);
    if (closing && b === closing.b) {
      e.stopImmediatePropagation(); e.preventDefault();
      const dd = closing.dd; closing = null; menuBtn = null;
      try { if (typeof dd.close === "function") dd.close(); else document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", keyCode: 27, bubbles: true })); } catch (err) {}
      return;
    }
    closing = null;
    menuBtn = b || (openMenu() ? menuBtn : null);
  }, true);
  // a menu opens after a click (or Enter); look for one a moment later, which costs nothing when there isn't one
  for (const type of ["click", "keyup"]) {
    window.addEventListener(type, () => { if (on("songMenu")) { setTimeout(addMenuItem, 250); setTimeout(addMenuItem, 700); } }, { capture: true, passive: true });
  }

  // ------------------------------------------------------------------ toast
  let toastEl = null, toastTimer = 0;
  function toast(text) {
    if (!toastEl || !toastEl.isConnected) { toastEl = h("div", { class: "pf-toast pf-ui", role: "status" }); document.body.append(toastEl); }
    toastEl.textContent = text;
    requestAnimationFrame(() => toastEl.classList.add("pf-open"));
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastEl && toastEl.classList.remove("pf-open"), 3600);
  }

  // ------------------------------------------------------------------ Refine's buttons in the top bar
  // ✦ opens a short menu of what is changed most; the Discord button opens Discord status; "All settings" shows
  // everything together in one large dialog (also from the sidebar).
  let navBtn = null, dcNavBtn = null, sheet = null, sheetKind = "", sheetFrom = null;
  function syncNav() {
    if (!partOn) return;
    const nav = document.querySelector("ytmusic-nav-bar");
    if (!nav) return;
    const right = nav.querySelector("#right-content, .right-content");
    if (!navBtn || !navBtn.isConnected) {
      navBtn = h("button", { class: "pf-nav-btn", "aria-label": "Refine", title: "Refine", "aria-haspopup": "dialog", "aria-expanded": "false",
        onclick: (e) => { e.stopPropagation(); toggleSheet("quick", navBtn); } }, icon(ICON.sparkle));
      if (right) right.prepend(navBtn);
      else { navBtn.classList.add("pf-float"); nav.append(navBtn); }
    }
    if (!dcNavBtn || !dcNavBtn.isConnected) {
      dcNavBtn = h("button", { class: "pf-nav-btn pf-dc-nav", "aria-label": "Discord status", title: "Discord status", "aria-haspopup": "dialog",
        "aria-expanded": "false", onclick: (e) => { e.stopPropagation(); toggleSheet("discord", dcNavBtn); } }, icon(ICON.discord), h("i", { class: "pf-dot", "aria-hidden": "true" }));
      if (navBtn.classList.contains("pf-float")) dcNavBtn.classList.add("pf-float", "pf-float-2");
      navBtn.before(dcNavBtn);
    }
  }
  // the Discord button's dot: green while your music shows on Discord, grey while it doesn't, amber when something is wrong
  const DOT = { showing: "on", waiting: "on", idle: "idle", off: "off", snoozed: "off", "paused-hidden": "idle", "paused-timeout": "idle",
    filtered: "idle", ad: "idle", "discord-down": "warn", "extension-quiet": "idle", "extension-missing": "idle", "beacon-running": "warn" };
  let dotAt = 0;
  async function syncDiscordDot(force) {
    if (!dcNavBtn || !dcNavBtn.isConnected || document.hidden || (!force && Date.now() - dotAt < 20000)) return;
    dotAt = Date.now();
    const r = await askStarcast("status");
    const st = r && r.status;
    const dc = st && st.discord && typeof st.discord === "object" ? st.discord : null;
    const state = !st ? "warn" : st.parts && st.parts.discord === false ? "off" : (dc && DOT[dc.headline]) || "idle";
    dcNavBtn.dataset.state = state;
    const words = (dc && DISCORD_TEXT[dc.headline]) || (st ? "" : "YTM Starcast isn't answering on this computer.");
    dcNavBtn.title = "Discord status" + (words ? ": " + words : "");
  }
  function sw(key, label, sub) {
    const input = h("input", { type: "checkbox", role: "switch" });
    input.checked = !!prefs[key];
    input.addEventListener("change", () => setPref(key, input.checked));
    return h("label", { class: "pf-row" },
      h("span", { class: "pf-row-text" }, label, sub ? h("small", { text: sub }) : null),
      h("span", { class: "pf-switch" }, input, h("i")));
  }
  // A quick-settings tile: tonal while on, with its icon, its name and On or Off.
  function tile(key, label, ico) {
    const state = h("small");
    const b = h("button", { class: "pf-tile", type: "button", role: "switch" }, h("span", { class: "pf-tile-ico" }, icon(ico, 22)),
      h("span", { class: "pf-tile-text" }, label, state));
    const paint = () => { b.setAttribute("aria-checked", String(!!prefs[key])); state.textContent = prefs[key] ? "On" : "Off"; };
    b.addEventListener("click", () => { setPref(key, !prefs[key]); paint(); if (key === "clearSlow" || key === "keepPitch") ensureSound(true); });
    paint();
    return b;
  }
  // YTM Starcast's three main switches, kept by the app on this computer. Asked through the extension's own script, like live
  // lyrics: this page can't reach the app itself.
  let scSeq = 0;
  const scWaiting = new Map();
  function askStarcast(op, extra) {
    return new Promise((res) => {
      const id = "pfsc" + ++scSeq;
      scWaiting.set(id, res);
      window.postMessage({ __vmr: "starcast", id, op, ...extra }, "*");
      setTimeout(() => { if (scWaiting.delete(id)) res({ error: "timeout" }); }, 9000);
    });
  }
  // What the app says about Discord status, in plain words (the same wording as the toolbar button's).
  const DISCORD_TEXT = {
    showing: "Showing on your Discord profile.", waiting: "Getting ready to show…", idle: "Ready. Nothing is playing.",
    off: "Off. Nothing is shown on Discord.", snoozed: "Hidden for a while. It comes back by itself.",
    "paused-hidden": "Not shown while the music is paused.", "paused-timeout": "Hidden after a long pause. It comes back when you press play.",
    filtered: "Not shown: this song matches one of your never-show words.", ad: "Hidden while an advert plays.",
    "discord-down": "Can't reach Discord. Open the Discord app on this computer.",
    "extension-quiet": "Waiting to hear from YouTube Music.", "extension-missing": "Waiting to hear from YouTube Music.",
    "beacon-running": "The old YTM Beacon app is still running. Open YTM Starcast to move over from it.",
    unreachable: "Discord status isn't answering right now. Try again in a moment.",
  };
  const PART_ROWS = [
    ["remote", "YouTube Music remote", "Search, your library and this tab's queue from your phone"],
    ["discord", "Discord status", "Show what you play on your Discord profile"],
    ["refine", "Refine", "Everything here. Once it's off, turn it back on from YTM Starcast's button in the browser toolbar or in the YTM Starcast app"],
  ];
  // YTM Starcast's switches for the given parts, with a line saying what the app says.
  function partsBlock(keys) {
    const note = h("p", { class: "pf-foot", role: "status", text: "Asking YTM Starcast on this computer\u2026" });
    const rows = {};
    // what the app last said (null: not known, or an app from before these switches, which has them all on)
    let parts = null, ok = false, busy = false;
    function show(r) {
      if (r.parts || r.status) {
        const st = r.status || {};
        const p = r.parts || st.parts;
        parts = p && typeof p === "object" ? p : null;
        ok = !!parts;
        if (r.status) {
          const v = typeof st.version === "string" ? st.version.slice(0, 24) : "";
          note.textContent = "YTM Starcast" + (v ? " " + v : "") + " is running on this computer.";
          const dc = st.discord && typeof st.discord === "object" ? st.discord : null;
          if (rows.discord) rows.discord.small.textContent = (dc && DISCORD_TEXT[dc.headline]) || rows.discord.sub;
        }
      } else {
        ok = false;
        if (r.error === "old_app") {
          parts = null;
          note.textContent = "The app on this computer is an older version" + (r.app && r.app.version ? " (" + r.app.version + ")" : "") +
            ". Update it to YTM Starcast to change these here.";
        } else if (r.error === "offline") note.textContent = "YTM Starcast isn't answering on this computer. Start it to change these.";
        else if (r.error === "timeout") note.textContent = "YTM Starcast didn't answer in time. Try again in a moment.";
        else if (r.error === "needs_press") note.textContent = "That didn't go through. Switch it on with a click or the Space key, " +
          "or from YTM Starcast's button in the browser toolbar.";
        else note.textContent = "That didn't go through. Try again in a moment.";
      }
      // a change that didn't go through flips back here, to what the app last said
      for (const [key, row] of Object.entries(rows)) {
        row.input.disabled = busy || !ok;
        row.input.checked = parts ? parts[key] !== false : true;
      }
    }
    let gen = 0;   // an answer that arrives after a newer question (or a change) is dropped
    async function refresh() {
      const mine = ++gen;
      const r = await askStarcast("status");
      if (mine === gen && !busy) show(r);
    }
    for (const key of keys) {
      const [, , sub] = PART_ROWS.find((x) => x[0] === key);
      // data-pf-part: the extension's own script switches a part on only after a real press on this switch
      const input = h("input", { type: "checkbox", role: "switch", disabled: true, "data-pf-part": key });
      input.checked = true;
      const small = h("small", { text: sub });
      input.addEventListener("change", async () => {
        busy = true;
        gen++;
        for (const row of Object.values(rows)) row.input.disabled = true;
        const r = await askStarcast("parts", { parts: { [key]: input.checked } });
        busy = false;
        show(r);
        setTimeout(() => { refresh(); syncDiscordDot(true); }, 900);   // the Discord line catches up a moment later
      });
      rows[key] = { input, small, sub };
    }
    refresh();
    return { note, rows: keys.map((key) => h("label", { class: "pf-row" },
      h("span", { class: "pf-row-text" }, PART_ROWS.find((x) => x[0] === key)[1], rows[key].small), h("span", { class: "pf-switch" }, rows[key].input, h("i")))) };
  }
  // The computer's sound: the same volume, output and equalizer as YTM Starcast's mixer, through the app on this
  // computer. The extension's script passes a change on only right after a real press or drag in here.
  const BAND_NAMES = [["bass", "Bass"], ["mid", "Mid"], ["high", "Treble"]];
  function soundSection(open) {
    const box = h("div", { class: "pf-dc pf-snd", hidden: !open, "data-pf-snd": "" });
    const btn = h("button", { class: "pf-row pf-dc-toggle", type: "button", "aria-expanded": "false" },
      h("span", { class: "pf-row-text" }, "Computer sound", h("small", { text: "Volume, output and equalizer, as in YTM Starcast's mixer" })),
      h("span", { class: "pf-chev", "aria-hidden": "true", text: "›" }));
    const note = h("p", { class: "pf-foot", role: "status", text: "Asking YTM Starcast on this computer…" });
    const held = new Set();       // sliders being dragged right now aren't moved by what the app says
    const slider = (min, max, step, label) => {
      const el = h("input", { type: "range", min: String(min), max: String(max), step: String(step), class: "pf-range pf-range-sm", "aria-label": label });
      el.addEventListener("pointerdown", () => held.add(el));
      el.addEventListener("pointerup", () => setTimeout(() => held.delete(el), 600));
      el.addEventListener("keydown", () => { held.add(el); setTimeout(() => held.delete(el), 800); });
      return el;
    };
    const fill = (el) => el.style.setProperty("--pf-fill", ((Number(el.value) - Number(el.min)) / (Number(el.max) - Number(el.min))) * 100 + "%");
    // changes go out at most every 80 ms per control, the last one always
    const waiting = {}, timers = {};
    function send(key, kind, body) {
      waiting[key] = [kind, body];
      if (timers[key]) return;
      timers[key] = setTimeout(async () => {
        timers[key] = 0;
        const [k, b] = waiting[key];
        delete waiting[key];
        const r = await askStarcast("soundSet", { kind: k, body: b });
        if (r && r.error) say(r);
      }, 80);
    }
    function say(r) {
      note.textContent = r.error === "needs_press" ? "That didn't go through. Change it with a click, a drag or the keyboard."
        : r.error === "offline" ? "YTM Starcast isn't answering on this computer. Start it to change the sound here."
        : r.error === "old_app" ? "The app on this computer is an older version. Update YTM Starcast to change the sound here."
        : r.error === "part_off" ? "Computer audio is switched off in YTM Starcast. Switch it on in the YTM Starcast app."
        : r.error === "timeout" ? "YTM Starcast didn't answer in time. Try again in a moment."
        : typeof r.error === "string" && r.error.length < 120 ? "That didn't go through: " + r.error : "That didn't go through. Try again in a moment.";
    }
    const vol = slider(0, 100, 1, "Volume");
    const volVal = h("span", { class: "pf-mini-val" });
    const muteBtn = h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Mute", "aria-pressed": "false" }, icon(ICON.volume, 20));
    vol.addEventListener("input", () => { fill(vol); volVal.textContent = vol.value + "%"; send("vol", "volume", { percent: Number(vol.value) }); });
    muteBtn.addEventListener("click", () => {
      const m = muteBtn.getAttribute("aria-pressed") !== "true";
      muteBtn.setAttribute("aria-pressed", String(m));
      muteBtn.replaceChildren(icon(m ? ICON.mute : ICON.volume, 20));
      send("mute", "volume", { muted: m });
    });
    const out = h("select", { class: "pf-select", "aria-label": "Output" });
    out.addEventListener("change", () => send("out", "output", { name: out.value }));
    const eqIn = h("input", { type: "checkbox", role: "switch" });
    eqIn.addEventListener("change", () => send("eqon", "eq", { enabled: eqIn.checked }));
    const eqRow = h("label", { class: "pf-row" }, h("span", { class: "pf-row-text" }, "Equalizer", h("small", { text: "Shapes all of the computer's sound" })),
      h("span", { class: "pf-switch" }, eqIn, h("i")));
    const presets = h("div", { class: "pf-chips" });
    const bands = {};
    const bandRows = BAND_NAMES.map(([band, label]) => {
      const el = slider(-12, 12, 0.5, label), v = h("span", { class: "pf-mini-val" });
      el.addEventListener("input", () => { fill(el); v.textContent = (Number(el.value) > 0 ? "+" : "") + el.value + " dB"; send("b-" + band, "eq", { band, db: Number(el.value) }); });
      bands[band] = { el, v };
      return h("div", { class: "pf-band" }, h("div", { class: "pf-label pf-label-row" }, h("span", { text: label }), v), el);
    });
    const reset = h("button", { class: "pf-btn", text: "Flat", onclick: () => {
      for (const [band] of BAND_NAMES) { bands[band].el.value = "0"; bands[band].el.dispatchEvent(new Event("input")); }
    } });
    const volPart = h("div", { class: "pf-snd-part" }, h("div", { class: "pf-label pf-label-row" }, h("span", { text: "Volume" }), volVal),
      h("div", { class: "pf-snd-vol" }, muteBtn, vol));
    const outPart = h("div", { class: "pf-snd-part" }, h("div", { class: "pf-label", text: "Output" }), out);
    const eqPart = h("div", { class: "pf-snd-part" }, eqRow, presets, bandRows, h("div", { class: "pf-snd-foot" }, reset));
    box.append(volPart, outPart, eqPart, note);
    let timer = 0;
    function show(r) {
      const s = r && r.sound;
      const ok = !!(s && s.audio);
      if (!s) say(r || {});
      else if (!s.audio) say({ error: "part_off" });
      else note.textContent = "";
      volPart.hidden = !(ok && s.capabilities && s.capabilities.volume && s.volume && s.volume.available !== false);
      outPart.hidden = !(ok && Array.isArray(s.outputs) && s.outputs.length > 1);
      eqPart.hidden = !(ok && s.capabilities && s.capabilities.eq);
      if (!ok) return;
      if (!volPart.hidden && !held.has(vol)) {
        vol.value = String(Math.round(Number(s.volume.percent) || 0)); fill(vol); volVal.textContent = vol.value + "%";
        const m = !!s.volume.muted;
        if (muteBtn.getAttribute("aria-pressed") !== String(m)) { muteBtn.setAttribute("aria-pressed", String(m)); muteBtn.replaceChildren(icon(m ? ICON.mute : ICON.volume, 20)); }
      }
      if (!outPart.hidden && document.activeElement !== out) {
        const names = s.outputs.map((o) => String(o.name));
        if ([...out.options].map((o) => o.value).join("\n") !== names.join("\n")) {
          out.replaceChildren(...s.outputs.map((o) => h("option", { value: String(o.name), text: String(o.description || o.name) })));
        }
        const def = s.outputs.find((o) => o.default);
        if (def) out.value = String(def.name);
      }
      if (!eqPart.hidden) {
        const e = s.engine || {}, lim = s.limits || {};
        const toggle = !!s.capabilities.eq_toggle;
        eqRow.hidden = !toggle;
        eqIn.checked = !toggle || !!e.enabled;
        const live = !toggle || !!e.enabled;
        const list = Array.isArray(e.presets) ? e.presets.filter((x) => typeof x === "string") : [];
        if (presets.dataset.list !== list.join("|")) {
          presets.dataset.list = list.join("|");
          presets.replaceChildren(...list.map((name) => h("button", { class: "pf-chip", text: name.replace(/-/g, " "), onclick: () => send("preset", "eq", { preset: name }) })));
        }
        presets.hidden = !list.length;
        for (const b of presets.children) b.disabled = !live;
        for (const [band] of BAND_NAMES) {
          const { el, v } = bands[band];
          el.min = String(lim.eq_min != null ? lim.eq_min : -12); el.max = String(lim.eq_max != null ? lim.eq_max : 12);
          el.disabled = !live;
          const db = e.eq && typeof e.eq[band] === "number" ? e.eq[band] : 0;
          if (!held.has(el)) { el.value = String(db); fill(el); v.textContent = (db > 0 ? "+" : "") + db + " dB"; }
        }
        reset.disabled = !live;
      }
    }
    async function refresh() {
      if (!box.isConnected || box.hidden) { clearInterval(timer); timer = 0; return; }
      show(await askStarcast("sound"));
    }
    btn.addEventListener("click", () => {
      const show = box.hidden;
      box.hidden = !show;
      btn.setAttribute("aria-expanded", String(show));
      clearInterval(timer); timer = 0;
      if (show) { refresh(); timer = setInterval(refresh, 2000); }
    });
    if (open) { setTimeout(refresh, 0); timer = setInterval(refresh, 2000); return box; }
    return [btn, box];
  }
  // ---- the quick menu (✦) and Discord status (the Discord button): small panels under their buttons
  function quickSheet() {
    return [
      h("div", { class: "pf-head pf-head-logo" }, logoTile(36), h("div", {},
        h("div", { class: "pf-title", text: "Refine" }),
        h("div", { class: "pf-sub", text: KILL ? "Off for this page (?pf-refine=off). Your choices are kept for next time." : "YTM Starcast's additions to YouTube Music" }))),
      h("div", { class: "pf-tiles" },
        tile("colors", "Album colours", ICON.palette), tile("motion", "Animations", ICON.motion),
        tile("wavy", "Wavy bar", ICON.wave), tile("clearSlow", "Clear slowed sound", ICON.eq),
        tile("material", "Material You page", ICON.material), vizTile(),
        tile("lyrics", "Live lyrics", ICON.lyrics), tile("lyricsFollow", "Lyrics follow you", ICON.pip)),
      h("div", { class: "pf-quick-actions" },
        h("button", { class: "pf-btn pf-tonal", disabled: !on("lyrics"), onclick: () => { closeSheet(); openLyricsWindow(); } }, icon(ICON.popOut, 18), "Lyrics window"),
        h("button", { class: "pf-btn pf-tonal", onclick: () => { closeSheet(); openMaker(); } }, icon(ICON.playlistAdd, 18), "Make a playlist")),
      h("button", { class: "pf-btn pf-filled pf-wide", onclick: () => { closeSheet(); openAll(); } }, icon(ICON.tune, 20), "All settings"),
    ];
  }
  function discordSheet() {
    const { rows, note } = partsBlock(["discord"]);
    const panel = window.__starcastDiscordPanel ? window.__starcastDiscordPanel({ ask: (op, extra) => askStarcast(op, extra) })
      : h("p", { class: "pf-foot", text: "Discord status options need the extension's discord_ui.js." });
    return [h("div", { class: "pf-head pf-head-logo" }, h("span", { class: "pf-dc-badge" }, icon(ICON.discord, 22)), h("div", {},
      h("div", { class: "pf-title", text: "Discord status" }), h("div", { class: "pf-sub", text: "Your music on your Discord profile" }))),
      rows, h("div", { class: "pf-dc-panel" }, panel), note];
  }
  function sheetOutside(e) {
    if (!sheet || sheet.contains(e.target)) return;
    const path = e.composedPath ? e.composedPath() : [];
    if (path.includes(sheetFrom)) return;   // the button that opened it toggles it itself
    closeSheet();
  }
  function toggleSheet(kind, anchor) {
    if (sheet && sheetKind === kind) { closeSheet(); return; }
    closeSheet();
    sheetKind = kind; sheetFrom = anchor;
    sheet = h("div", { class: "pf-sheet pf-ui" + (kind === "discord" ? " pf-sheet-dc" : ""), role: "dialog", "aria-label": kind === "discord" ? "Discord status" : "Refine" },
      kind === "discord" ? discordSheet() : quickSheet());
    const r = anchor.getBoundingClientRect();
    sheet.style.top = r.bottom + 8 + "px";
    sheet.style.right = Math.max(8, innerWidth - r.right - 4) + "px";
    document.body.append(sheet);
    anchor.setAttribute("aria-expanded", "true");
    requestAnimationFrame(() => sheet && sheet.classList.add("pf-open"));
    setTimeout(() => document.addEventListener("pointerdown", sheetOutside, true), 0);
    const first = sheet.querySelector("button, input");
    if (first) first.focus({ preventScroll: true });
    if (kind === "discord") syncDiscordDot(true);
  }
  function closeSheet() {
    if (!sheet) return;
    const el = sheet, from = sheetFrom;
    sheet = null; sheetKind = ""; sheetFrom = null;
    if (from) from.setAttribute("aria-expanded", "false");
    if (el.contains(document.activeElement) && from && from.isConnected) from.focus();
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 200);
    document.removeEventListener("pointerdown", sheetOutside, true);
  }

  // ---- All settings: everything together, with the sections listed down the side
  let allDlg = null;
  function openAll(section) {
    closeSheet();
    if (allDlg) { if (section) jumpTo(section); return; }
    const sections = [
      ["look", "Look", [
        sw("colors", "Colours from the album art", "The page takes on the colour of the cover"),
        sw("shapes", "Rounder shapes", "Covers, lists, menus and the search box"),
        sw("material", "Material You page parts", "Pill tabs beside the player, tonal buttons, Material switches and scrollbars"),
        sw("controls", "Expressive player controls", "A bigger play button that changes shape"),
        sw("wavy", "Wavy progress bar", "Ripples while the song plays, flat when paused"),
        sw("glow", "Glow behind the cover", null),
        sw("motion", "Animations", "Pages, menus, covers and buttons move into place"),
        vizChooser(),
        sw("logo", "YTM Starcast logo", "In place of YouTube Music's, to show Refine is on"),
        sw("sidebar", "In the sidebar", "Lyrics, Make a playlist and these settings, under Home, Explore and Library")]],
      ["playback", "Playback", [
        sw("speedMenu", "Richer speed menu", "Opens from the player's own speed button; the speed stays from song to song"),
        sw("keepPitch", "Keep the pitch at other speeds", null),
        sw("clearSlow", "Clear slowed sound", "Slowed songs stay smooth and clear instead of smeared"),
        sw("speedTimes", "Times follow the speed", "At 0.8x a 3:00 song shows 3:45, here and on Discord"),
        roomControl()]],
      ["lyrics", "Lyrics", [
        sw("lyrics", "Live lyrics", "Word by word, from the YTM Starcast app, in YouTube Music's Lyrics tab"),
        sw("lyricsFollow", "Lyrics follow you", "Leaving the player with the Lyrics tab open brings them along in a window"),
        sw("lyricsFloat", "Above other windows when you switch tabs", "While lyrics are on screen and music plays (where the browser allows it)"),
        h("button", { class: "pf-btn pf-tonal pf-inline", disabled: !on("lyrics"), onclick: () => { closeAll(); openLyricsWindow(); } }, icon(ICON.popOut, 18), "Open lyrics in a window")]],
      ["controls", "Controls", [
        sw("dice", "Something-you-might-like button", "On the home page, next to the first shelf (Listen again)"),
        sw("songMenu", "In song menus", "“Playlist from this song” in YouTube Music's own ⋮ menus"),
        h("button", { class: "pf-btn pf-tonal pf-inline", onclick: () => { closeAll(); openMaker(); } }, icon(ICON.playlistAdd, 18), "Make a playlist")]],
      ["discord", "Discord status", (() => {
        const { rows, note } = partsBlock(["discord"]);
        const panel = window.__starcastDiscordPanel ? window.__starcastDiscordPanel({ ask: (op, extra) => askStarcast(op, extra) }) : null;
        return [rows, panel, note];
      })()],
      ["sound", "Computer sound", soundSection(true)],
      ["starcast", "YTM Starcast", (() => { const { rows, note } = partsBlock(["remote", "discord", "refine"]); return [rows, note]; })()],
      ["about", "About", [h("p", { class: "pf-foot", text: "These rely on how YouTube Music's page is built, so a change on its side can break one; " +
        "switch off anything that misbehaves. Settings stay in this browser. Opening music.youtube.com/?pf-refine=off starts a page without " +
        "any of it. YTM Starcast extension " + VERSION + "." })]],
    ];
    const nav = h("nav", { class: "pf-all-nav", "aria-label": "Sections" });
    const body = h("div", { class: "pf-all-body" });
    const navBtns = {};
    for (const [id, label, kids] of sections) {
      navBtns[id] = h("button", { class: "pf-all-link", type: "button", text: label, onclick: () => jumpTo(id) });
      nav.append(navBtns[id]);
      body.append(h("section", { class: "pf-all-sec", id: "pf-all-" + id, "aria-label": label }, h("h3", { text: label }), kids));
    }
    const dlg = h("div", { class: "pf-dialog pf-all", role: "dialog", "aria-modal": "true", "aria-label": "Refine settings" },
      h("div", { class: "pf-dlg-head" }, h("div", { class: "pf-head pf-head-logo" }, logoTile(40), h("div", {},
        h("div", { class: "pf-title", text: "Refine settings" }), h("div", { class: "pf-sub", text: "Everything YTM Starcast adds to YouTube Music, in one place" }))),
        h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Close", onclick: closeAll }, icon(ICON.close))),
      h("div", { class: "pf-all-main" }, nav, body));
    allDlg = h("div", { class: "pf-scrim pf-ui" }, dlg);
    allDlg.addEventListener("pointerdown", (e) => { if (e.target === allDlg) closeAll(); });
    document.body.append(allDlg);
    requestAnimationFrame(() => allDlg && allDlg.classList.add("pf-open"));
    // the section in view is marked in the list
    const mark = () => {
      const top = body.getBoundingClientRect().top + 40;
      let cur = sections[0][0];
      for (const [id] of sections) { const s = body.querySelector("#pf-all-" + id); if (s && s.getBoundingClientRect().top <= top) cur = id; }
      for (const [id, b] of Object.entries(navBtns)) b.setAttribute("aria-current", String(id === cur));
    };
    body.addEventListener("scroll", mark, { passive: true });
    mark();
    allDlg.__jump = (id) => {
      const s = body.querySelector("#pf-all-" + id);
      if (s) body.scrollTo({ top: body.scrollTop + s.getBoundingClientRect().top - body.getBoundingClientRect().top - 8, behavior: REDUCED.matches ? "auto" : "smooth" });
    };
    if (section) jumpTo(section);
    (navBtns.look || dlg).focus({ preventScroll: true });
  }
  function jumpTo(id) { if (allDlg && allDlg.__jump) allDlg.__jump(id); }
  function closeAll() {
    if (!allDlg) return;
    const el = allDlg;
    allDlg = null;
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 250);
  }
  // Room (a soft reverb for a "slowed + reverb" sound), as in the speed menu
  function roomControl() {
    const room = h("input", { type: "range", min: "0", max: "100", step: "5", class: "pf-range pf-range-sm", "aria-label": "Room" });
    const val = h("span", { class: "pf-mini-val" });
    const show = () => { room.value = String(Math.round(soundOpts.room * 100)); room.style.setProperty("--pf-fill", soundOpts.room * 100 + "%"); val.textContent = soundOpts.room ? Math.round(soundOpts.room * 100) + "%" : "Off"; };
    room.addEventListener("input", () => { soundOpts.room = clamp(Number(room.value) / 100, 0, 1); writeJSON(SOUND_KEY, soundOpts); show(); ensureSound(true); applyRoom(); });
    show();
    return h("div", { class: "pf-room" }, h("div", { class: "pf-label pf-label-row" }, h("span", { text: "Room (a slowed + reverb sound)" }), val), room);
  }
  function sheetKey(e) {
    if (e.key !== "Escape") return;
    if (allDlg) { closeAll(); return; }
    closeSheet(); closeSpeed();
    if (lyr.frame && !lyrWin.pinned && lyr.frame.contains(document.activeElement)) closeLyricsWindow();
  }
  document.addEventListener("keydown", sheetKey, true);

  // ------------------------------------------------------------------ the sidebar and the logo
  // Lyrics, Make a playlist and All settings join Home, Explore and Library in YouTube Music's sidebar (the full one and
  // the narrow one), looking like its own entries.
  const GUIDE = [["Lyrics", "Lyrics", () => ICON.lyrics, () => openLyricsWindow()], ["Make a playlist", "Playlist", () => ICON.playlistAdd, () => openMaker()],
    ["Refine", "Refine", () => ICON.sparkle, () => openAll()]];
  function syncGuide() {
    const lists = [];
    if (on("sidebar")) {
      for (const guide of document.querySelectorAll("ytmusic-guide-renderer")) {
        const first = guide.querySelector("ytmusic-guide-entry-renderer");
        if (first && first.parentElement && !lists.includes(first.parentElement)) lists.push(first.parentElement);
      }
    }
    for (const g of document.querySelectorAll(".pf-guide")) if (!lists.includes(g.parentElement)) g.remove();
    for (const list of lists) {
      let g = list.querySelector(":scope > .pf-guide");
      if (!g) {
        g = h("div", { class: "pf-guide pf-ui", role: "group", "aria-label": "YTM Starcast" }, GUIDE.map(([label, short, ico, go]) =>
          h("button", { class: "pf-guide-entry", type: "button", title: label, "aria-label": label, disabled: label === "Lyrics" && !on("lyrics"),
            onclick: (e) => { e.stopPropagation(); go(); } }, h("span", { class: "pf-guide-ico" }, icon(ico(), 24)),
            h("span", { class: "pf-guide-label", text: label }), h("span", { class: "pf-guide-short", text: short }))));
        list.append(g);
      }
      const w = list.getBoundingClientRect().width;
      if (w) g.classList.toggle("pf-guide-mini", w < 140);
      const lyricsEntry = g.firstChild;
      if (lyricsEntry) lyricsEntry.disabled = !on("lyrics");
    }
  }
  // YTM Starcast's logo in place of YouTube Music's, while Refine is on (the link to Home stays).
  function logoTile(size) {
    const s = document.createElementNS(SVGNS, "svg");
    s.setAttribute("viewBox", "0 0 512 512");
    s.setAttribute("width", String(size));
    s.setAttribute("height", String(size));
    s.setAttribute("aria-hidden", "true");
    s.setAttribute("class", "pf-logo-tile");
    const P = (d, attrs) => { const p = document.createElementNS(SVGNS, "path"); p.setAttribute("d", d); for (const [k, v] of Object.entries(attrs)) p.setAttribute(k, v); s.append(p); };
    const defs = document.createElementNS(SVGNS, "defs"), g = document.createElementNS(SVGNS, "linearGradient");
    for (const [k, v] of Object.entries({ id: "pf-logo-bg", x1: "0", y1: "0", x2: "0.35", y2: "1" })) g.setAttribute(k, v);
    for (const [o, c] of [["0", "#2A3A9A"], [".55", "#111A52"], ["1", "#070B2A"]]) {
      const st = document.createElementNS(SVGNS, "stop"); st.setAttribute("offset", o); st.setAttribute("stop-color", c); g.append(st);
    }
    defs.append(g); s.append(defs);
    const r = document.createElementNS(SVGNS, "rect");
    for (const [k, v] of Object.entries({ x: 16, y: 16, width: 480, height: 480, rx: 112, fill: "url(#pf-logo-bg)" })) r.setAttribute(k, v);
    s.append(r);
    for (const [rad, w, o] of [[132, 38, 1], [200, 36, 0.7]]) {
      P(`M176,${336 - rad} A${rad},${rad} 0 0 1 ${176 + rad},336`, { fill: "none", stroke: "#8CC8FF", "stroke-width": w, "stroke-linecap": "round", opacity: o });
    }
    P(LOGO_STAR, { fill: "#F2F6FF" });
    return s;
  }
  function syncLogo() {
    const want = on("logo");
    for (const lg of document.querySelectorAll("ytmusic-logo")) {
      const box = lg.querySelector("a") || lg;
      const mine = box.querySelector(":scope > .pf-logo");
      if (want && !mine) {
        box.append(h("span", { class: "pf-logo pf-ui", title: "YouTube Music, with YTM Starcast" }, logoTile(30), h("span", { class: "pf-logo-word", text: "Starcast" })));
        box.setAttribute("data-pf-logo-box", "");
        lg.setAttribute("data-pf-logo", "");
      } else if (!want && mine) {
        mine.remove();
        box.removeAttribute("data-pf-logo-box");
        lg.removeAttribute("data-pf-logo");
      }
    }
  }

  // ------------------------------------------------------------------ playlist maker
  const cleanTitle = (t) => String(t || "").toLowerCase()
    .replace(/\s*[([](official|lyric|lyrics|audio|video|visualizer|music video|hd|4k|remaster(ed)?( \d{4})?)[^)\]]*[)\]]/g, "")
    .replace(/\s*[([]?(feat\.?|ft\.?|featuring) [^)\]]*[)\]]?/g, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
  const artistKey = (a) => String(a || "").toLowerCase().split(/\s*(?:,|&| x | and | feat\.? | ft\.? |•)\s*/)[0]
    .replace(/\s*-\s*topic$/, "").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
  const songKey = (r) => cleanTitle(r.title) + "|" + artistKey(r.artist);
  const firstRun = (o) => (o && o.runs && o.runs[0] && o.runs[0].text) || null;
  async function radio(videoId) {
    const j = await API.innertube("next", { videoId, playlistId: "RDAMVM" + videoId, params: "wAEB", isAudioOnly: true, enablePersistentPlaylistPanel: true }, true);
    const out = [], seen = new Set();
    for (const r of API.deepFind(j, "playlistPanelVideoRenderer", [])) {
      const id = r.videoId || (r.navigationEndpoint && r.navigationEndpoint.watchEndpoint && r.navigationEndpoint.watchEndpoint.videoId);
      const title = API.runText(r.title);
      if (!id || !title || seen.has(id)) continue;
      seen.add(id);
      out.push({ videoId: id, title, artist: firstRun(r.shortBylineText) || firstRun(r.longBylineText) || "",
        thumb: API.pickThumb(r.thumbnail, 60), length: API.runText(r.lengthText) || "" });
    }
    return out;
  }
  // Starting songs: spread evenly over the whole list, or (for your likes, newest first) drawn favouring the top.
  function pickSeeds(all, n, weighted) {
    if (n <= 0 || !all.length) return [];
    if (!weighted) {
      const step = all.length / n, out = [];
      for (let i = 0; i < n && i < all.length; i++) out.push(all[Math.min(all.length - 1, Math.floor(i * step + Math.random() * step))]);
      return [...new Set(out)];
    }
    const list = [...all], out = [];
    while (out.length < n && list.length) {
      let x = Math.random() * list.reduce((a, s) => a + (s.w || 1), 0), i = 0;
      while (i < list.length - 1 && (x -= list[i].w || 1) > 0) i++;
      out.push(list.splice(i, 1)[0]);
    }
    return out;
  }
  // Each radio votes for its songs, more for the ones near its top; songs several radios agree on rise. Then no artist
  // gets more than a fair share, and the same artist never plays twice in a row where that can be avoided.
  function rank(lists, seeds, opt) {
    const score = new Map(), info = new Map(), hits = new Map();
    for (const { w, rows } of lists) {
      rows.forEach((row, i) => {
        const key = songKey(row);
        if (!info.has(key)) info.set(key, row);
        score.set(key, (score.get(key) || 0) + (w || 1) / (i + 4));
        hits.set(key, (hits.get(key) || 0) + 1);
      });
    }
    const from = opt.from || seeds;
    const seedKeys = new Set(from.map(songKey)), seedIds = new Set(from.map((s) => s.videoId));
    const exclude = new Set(opt.exclude || []);
    let entries = [...score].filter(([k]) => {
      const row = info.get(k);
      if (exclude.has(row.videoId)) return false;
      return !(opt.fresh && (seedKeys.has(k) || seedIds.has(row.videoId)));
    });
    const jitter = opt.round ? () => 0.8 + Math.random() * 0.4 : () => 1;
    entries = entries.map(([k, sc]) => [k, sc * (1 + (hits.get(k) - 1) * 0.2) * jitter()]).sort((a, b) => b[1] - a[1]);
    const cap = Math.max(2, Math.round(opt.length / 12));
    const per = new Map(), out = [], over = [];
    for (const [k] of entries) {
      const row = info.get(k), a = artistKey(row.artist);
      if ((per.get(a) || 0) >= cap) { over.push(row); continue; }
      per.set(a, (per.get(a) || 0) + 1);
      out.push(row);
      if (out.length >= opt.length) break;
    }
    // not enough different artists to fill it fairly: top up with the best of the rest
    for (const row of over) { if (out.length >= opt.length) break; out.push(row); }
    return spread(out);
  }
  function spread(list) {
    const rest = [...list], out = [];
    while (rest.length) {
      const prev = out.length ? artistKey(out[out.length - 1].artist) : null;
      let i = rest.findIndex((r) => artistKey(r.artist) !== prev);
      if (i < 0) i = 0;
      out.push(rest.splice(i, 1)[0]);
    }
    return out;
  }
  const secs = (len) => { const p = String(len || "").split(":").map(Number); return p.some(isNaN) ? 0 : p.reduce((a, b) => a * 60 + b, 0); };
  const dur = (s) => { const m = Math.round(s / 60); return m < 60 ? m + " min" : Math.floor(m / 60) + " h " + (m % 60) + " min"; };

  let maker = null;
  let makerFrom = null;
  function closeMaker() {
    if (!maker) return;
    const el = maker;
    maker = null;
    if (makerFrom && makerFrom.isConnected) makerFrom.focus();
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 220);
    document.removeEventListener("keydown", makerKey, true);
  }
  function makerKey(e) {
    if (e.key === "Escape") { e.stopPropagation(); closeMaker(); return; }
    if (e.key !== "Tab" || !maker) return;
    // keep Tab inside the dialog
    const items = [...maker.querySelectorAll("button, input, select, [tabindex]")].filter((x) => !x.disabled && x.offsetParent);
    if (!items.length) return;
    const first = items[0], last = items[items.length - 1];
    if (!maker.contains(document.activeElement)) { e.preventDefault(); first.focus(); }
    else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }
  // `song` ({ videoId }) starts it from that song instead of the one playing (YouTube Music's song menu).
  function openMaker(song) {
    closeMaker();
    makerFrom = song ? null : navBtn;
    const picked = !!(song && song.videoId);
    const np = picked ? { videoId: song.videoId, title: song.title || "", artist: song.artist || "" } : API.nowPlaying() || {};
    const queue = API.readQueue().filter((q) => q.section === "queue");
    const st = { source: np.videoId ? "now" : queue.length ? "queue" : "liked", playlist: null, length: 50, fresh: true,
      results: [], keep: new Set(), round: 0, shown: [], lists: null, seeds: null, mine: null, gen: 0 };
    const dlg = h("div", { class: "pf-dialog", role: "dialog", "aria-modal": "true", "aria-label": "Make a playlist" });
    maker = h("div", { class: "pf-scrim pf-ui", onclick: (e) => { if (e.target === maker) closeMaker(); } }, dlg);
    document.body.append(maker);
    requestAnimationFrame(() => maker && maker.classList.add("pf-open"));
    document.addEventListener("keydown", makerKey, true);

    const frame = (title, sub, body, foot) => {
      const hadFocus = dlg.contains(document.activeElement);
      dlg.textContent = "";
      dlg.append(
        h("div", { class: "pf-dlg-head" }, h("div", { class: "pf-head" }, h("div", { class: "pf-title", text: title }), sub ? h("div", { class: "pf-sub", text: sub }) : null),
          h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Close", onclick: closeMaker }, icon(ICON.close))),
        h("div", { class: "pf-dlg-body" }, body),
        h("div", { class: "pf-dlg-foot" }, foot));
      // each step starts with its main button focused (the dialog is rebuilt, so focus would otherwise be lost)
      const main = dlg.querySelector(".pf-dlg-foot .pf-filled") || dlg.querySelector(".pf-dlg-foot button");
      if (main && (hadFocus || !dlg.contains(document.activeElement))) main.focus({ preventScroll: true });
    };
    const choice = (key, title, sub, disabled) => h("button", { class: "pf-choice", "aria-pressed": String(st.source === key), disabled: !!disabled,
      onclick: () => { st.source = key; start(); } }, h("span", { class: "pf-dot" }), h("span", { class: "pf-txt" }, h("b", { text: title }), sub ? h("span", { text: sub }) : null));

    async function loadMine() {
      if (st.mine) return st.mine;
      try { st.mine = (await API.playlists()).filter((p) => p.playlistId && p.playlistId !== "LM" && p.playlistId !== "SE"); } catch (e) { st.mine = []; }
      return st.mine;
    }
    function start() {
      const pickList = h("select", { class: "pf-select", "aria-label": "Playlist" }, h("option", { value: "", text: "Loading your playlists…" }));
      if (st.source === "playlist") {
        loadMine().then((list) => {
          pickList.textContent = "";
          if (!list.length) pickList.append(h("option", { value: "", text: "No playlists found" }));
          for (const p of list) pickList.append(h("option", { value: p.playlistId, text: p.title }));
          if (st.playlist) pickList.value = st.playlist.playlistId;
          const sel = list.find((p) => p.playlistId === pickList.value) || list[0];
          st.playlist = sel || null;
        });
        pickList.addEventListener("change", () => { st.playlist = (st.mine || []).find((p) => p.playlistId === pickList.value) || null; });
      }
      const lens = [25, 50, 100].map((n) => h("button", { class: "pf-chip", text: n + " songs", "aria-pressed": String(st.length === n),
        onclick: (e) => { st.length = n; for (const c of lens) c.setAttribute("aria-pressed", String(c === e.currentTarget)); } }));
      const fresh = h("input", { type: "checkbox", role: "switch" });
      fresh.checked = st.fresh;
      fresh.addEventListener("change", () => { st.fresh = fresh.checked; });
      frame("Make a playlist", "Built from YouTube Music's own radio for each song it starts from, ranked by what they have in common.",
        [h("div", { class: "pf-label", text: "Start from" }),
          choice("now", picked ? "The song you picked" : "The song playing now",
            np.title ? np.title + (np.artist ? " · " + np.artist : "") : picked ? "From YouTube Music's menu" : "Nothing is playing", !np.videoId),
          choice("queue", "Your queue", queue.length ? queue.length + " songs" : "The queue is empty", !queue.length),
          choice("playlist", "One of your playlists", null),
          st.source === "playlist" ? pickList : null,
          choice("liked", "Your liked songs", "Leaning towards the ones you liked most recently"),
          h("div", { class: "pf-label", text: "Length" }), h("div", { class: "pf-chips" }, lens),
          h("label", { class: "pf-row", style: "padding-left:0;padding-right:0" },
            h("span", { class: "pf-row-text" }, "Only new songs", h("small", { text: "Leave out the songs it starts from" })),
            h("span", { class: "pf-switch" }, fresh, h("i")))],
        [h("button", { class: "pf-btn", text: "Cancel", onclick: closeMaker }),
          h("button", { class: "pf-btn pf-filled", onclick: () => generate(false) }, icon(ICON.sparkle, 18), "Make it")]);
    }
    function progress(text, frac) {
      const bar = h("i", { style: "width:" + Math.round(frac * 100) + "%" });
      frame("Making your playlist", null, [h("div", { class: "pf-progress" }, bar), h("div", { class: "pf-status", text })],
        [h("button", { class: "pf-btn", text: "Cancel", onclick: () => { st.gen++; start(); } })]);
    }
    async function seedsFor() {
      if (st.source === "now") return np.videoId ? [{ videoId: np.videoId, title: np.title, artist: np.artist, w: 1 }] : [];
      if (st.source === "queue") return queue.map((q) => ({ videoId: q.videoId, title: q.title, artist: q.artist, w: 1 }));
      const id = st.source === "liked" ? "LM" : st.playlist && st.playlist.playlistId;
      if (!id) return [];
      const items = await API.playlistItems(id, 400);
      return items.filter((i) => i.videoId).map((i, n) => ({ videoId: i.videoId, title: i.title, artist: String(i.subtitle || "").split(" • ")[0],
        w: st.source === "liked" ? 1 / (1 + n / 60) : 1 }));
    }
    async function generate(again) {
      const gen = ++st.gen;
      const gone = () => gen !== st.gen || !maker;
      try {
        if (!again || !st.lists) {
          progress("Looking at what it starts from…", 0.05);
          const all = await seedsFor();
          if (gone()) return;
          if (!all.length) { start(); toast("There's nothing to start from there"); return; }
          const n = st.length >= 100 ? 10 : 8;
          let seeds = st.source === "now" ? all : pickSeeds(all, Math.min(n, all.length), st.source === "liked");
          const lists = [];
          let done = 0;
          const fetchOne = async (s, w) => {
            try { const rows = await radio(s.videoId); if (rows.length) lists.push({ w, rows }); } catch (e) {}
            done++;
            if (!gone()) progress(`Listening to the radio of ${done} of ${seeds.length} songs…`, 0.1 + 0.8 * (done / seeds.length));
          };
          if (st.source === "now") {
            // one song: its radio, then the radios of a few songs from it, for a wider mix
            await fetchOne(seeds[0], 1);
            if (!np.title && lists[0]) { // a song picked from a menu: its name comes from its own radio
              const me = lists[0].rows.find((r) => r.videoId === np.videoId);
              if (me) { np.title = me.title; np.artist = me.artist; seeds[0].title = me.title; seeds[0].artist = me.artist; }
            }
            const top = (lists[0] ? lists[0].rows : []).filter((r) => r.videoId !== np.videoId).slice(0, 15);
            const more = pickSeeds(top, Math.min(st.length >= 100 ? 6 : 4, top.length), false);
            seeds = seeds.concat(more);
            for (const s of more) { if (gone()) return; await fetchOne(s, 0.55); await sleep(120); }
          } else {
            for (let i = 0; i < seeds.length; i += 3) {
              if (gone()) return;
              await Promise.all(seeds.slice(i, i + 3).map((s) => fetchOne(s, s.w || 1)));
              await sleep(150);
            }
          }
          if (gone()) return;
          st.lists = lists; st.seeds = seeds; st.from = all; st.round = 0;
        } else st.round++;
        const results = rank(st.lists, st.seeds, { length: st.length, fresh: st.fresh, round: st.round, from: st.from,
          exclude: again ? st.shown.slice(-st.length * 2) : [] });
        if (gone()) return;
        if (!results.length) { start(); toast("YouTube Music didn't suggest anything for that; try another starting point"); return; }
        st.results = results;
        st.keep = new Set(results.map((r) => r.videoId));
        st.shown.push(...results.map((r) => r.videoId));
        review();
      } catch (e) {
        if (gone()) return;
        start();
        toast("Couldn't make it: " + (e && e.message ? e.message : "YouTube Music didn't answer"));
      }
    }
    function defaultName() {
      if (st.source === "now") return "Mix from " + (np.title || "this song");
      if (st.source === "queue") return "Mix from my queue";
      if (st.source === "playlist") return "More like " + ((st.playlist && st.playlist.title) || "my playlist");
      return "More of what I like";
    }
    function review() {
      const name = h("input", { class: "pf-field", type: "text", maxlength: "150", "aria-label": "Name" });
      name.value = st.name || defaultName();
      name.addEventListener("input", () => { st.name = name.value; });
      const summary = h("div", { class: "pf-summary" });
      const count = () => {
        const kept = st.results.filter((r) => st.keep.has(r.videoId));
        const total = kept.reduce((a, r) => a + secs(r.length), 0);
        summary.textContent = kept.length + " songs" + (total ? " · about " + dur(total) : "");
        save.disabled = addBtn.disabled = !kept.length;
      };
      const rows = st.results.map((r) => {
        const box = h("input", { type: "checkbox", class: "pf-check", "aria-label": "Keep " + r.title });
        box.checked = st.keep.has(r.videoId);
        const li = h("li", { class: "pf-song" + (box.checked ? "" : " pf-off") },
          box, h("img", { alt: "", loading: "lazy", referrerpolicy: "no-referrer", src: r.thumb || "" }),
          h("div", { class: "pf-txt" }, h("div", { class: "pf-t", text: r.title }), h("div", { class: "pf-a", text: [r.artist, r.length].filter(Boolean).join(" · ") })));
        const flip = (v) => { if (v) st.keep.add(r.videoId); else st.keep.delete(r.videoId); box.checked = v; li.classList.toggle("pf-off", !v); count(); };
        box.addEventListener("change", () => flip(box.checked));
        li.addEventListener("click", (e) => { if (e.target !== box) flip(!box.checked); });
        return li;
      });
      const kept = () => st.results.filter((r) => st.keep.has(r.videoId)).map((r) => r.videoId);
      const save = h("button", { class: "pf-btn pf-filled", onclick: async () => {
        save.disabled = true;
        const title = (name.value || defaultName()).trim().slice(0, 150);
        const r = await API.playlistCreate({ title, description: "Made with YTM Starcast", privacy: "PRIVATE", videoIds: kept() }).catch(() => null);
        if (r && r.ok) saved(r.playlistId, title); else { save.disabled = false; toast("YouTube Music didn't save it. Are you signed in?"); }
      } }, "Save as a playlist");
      const addBtn = h("button", { class: "pf-btn pf-tonal", onclick: () => addTo() }, "Add to one of mine…");
      frame("Your new playlist", "Untick anything you don't want. Again gives a different mix from the same start.",
        [name, summary, h("ul", { class: "pf-list" }, rows)],
        [h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Back", title: "Back", onclick: start }, icon(ICON.back)),
          h("button", { class: "pf-btn", onclick: () => generate(true) }, icon(ICON.refresh, 18), "Again"),
          h("span", { class: "pf-grow" }), addBtn, save]);
      count();
      async function addTo() {
        const list = await loadMine();
        const sel = h("select", { class: "pf-select", "aria-label": "Playlist" });
        for (const p of list) sel.append(h("option", { value: p.playlistId, text: p.title }));
        const go = h("button", { class: "pf-btn pf-filled", disabled: !list.length, onclick: async () => {
          go.disabled = true;
          const ids = kept(), pl = sel.value;
          let ok = true;
          for (let i = 0; i < ids.length && ok; i += 50) {
            const r = await API.playlistEdit(pl, ids.slice(i, i + 50).map((v) => ({ action: "ACTION_ADD_VIDEO", addedVideoId: v, dedupeOption: "DEDUPE_OPTION_SKIP" }))).catch(() => null);
            ok = !!(r && r.ok);
          }
          const p = list.find((x) => x.playlistId === pl);
          if (ok) saved(pl, p ? p.title : "your playlist", true);
          else { go.disabled = false; toast("YouTube Music didn't add them. You can only add to your own playlists."); }
        } }, "Add " + kept().length + " songs");
        frame("Add to one of your playlists", "Songs already in it are skipped.",
          [list.length ? sel : h("div", { class: "pf-status", text: "No playlists of yours were found." })],
          [h("button", { class: "pf-btn", text: "Back", onclick: review }), go]);
      }
    }
    function saved(playlistId, title, added) {
      frame(added ? "Added" : "Saved", (added ? "The songs are in “" : "“") + title + (added ? "”." : "” is in your library."),
        [h("div", { class: "pf-status", text: "It's private; change that in YouTube Music if you want to share it." })],
        [h("button", { class: "pf-btn", text: "Done", onclick: closeMaker }),
          h("button", { class: "pf-btn pf-filled", onclick: async () => { closeMaker(); const r = await API.startPlaylist(playlistId, false); if (!r || !r.ok) toast("Couldn't start it; find it in your library"); } },
            icon(ICON.play, 18), "Play it")]);
    }
    start();
  }

  // Typing in Refine's own fields must not reach YouTube Music's single-key shortcuts (space, j, k, l...).
  for (const type of ["keydown", "keypress", "keyup"]) {
    window.addEventListener(type, (e) => {
      const t = e.target;
      if (t && t.closest && t.closest(".pf-ui") && /^(INPUT|SELECT|TEXTAREA)$/.test(t.tagName) && e.key !== "Escape") e.stopImmediatePropagation();
    }, true);
  }

  // ------------------------------------------------------------------ cover visualizers (experimental)
  // Behind the big cover in the player page: Halo (the cover itself, blurred, slowly turning around it), Orbit (a ring of
  // the album's colours running round its edge), Pulse (the halo breathing with the music) and Bars (the music's
  // frequencies all round the cover). Pulse and Bars listen to the page's sound through the same path as the clear
  // slowed sound (after a press on the page); until then they breathe gently on their own.
  const viz = { host: null, el: null, arts: [], canvas: null, cx: null, raf: 0, src: "", data: null, level: 0, rect: null, rectAt: 0 };
  function setViz(m) {
    vizMode = VIZ_MODES.some((x) => x[0] === m) ? m : "off";
    writeJSON(VIZ_KEY, { mode: vizMode });
    if (vizHears()) ensureSound(true);
    syncViz();
  }
  // the cover at its largest
  function bigArt() {
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      const list = md && md.artwork ? [...md.artwork] : [];
      list.sort((a, b) => (parseInt(b.sizes, 10) || 0) - (parseInt(a.sizes, 10) || 0));
      if (list[0] && list[0].src) return list[0].src;
    } catch (e) {}
    const vid = API.curVideo();
    return vid ? `https://i.ytimg.com/vi/${vid}/hqdefault.jpg` : "";
  }
  function vizHost() {
    if (!pageOpen) return null;
    const p = document.querySelector("ytmusic-player-page ytmusic-player");
    return p && p.getClientRects().length ? p : null;
  }
  function removeViz() {
    cancelAnimationFrame(viz.raf);
    if (viz.el) viz.el.remove();
    if (viz.host) viz.host.removeAttribute("data-pf-viz");
    Object.assign(viz, { raf: 0, host: null, el: null, arts: [], canvas: null, cx: null, rect: null });
  }
  function syncViz() {
    const host = live() && vizMode !== "off" ? vizHost() : null;
    if (!host) { if (viz.el || viz.host) removeViz(); return; }
    if (!(viz.el && viz.host === host && viz.el.isConnected && viz.el.dataset.mode === vizMode)) {
      removeViz();
      viz.host = host;
      const art = (cls) => h("img", { class: "pf-viz-art" + (cls ? " " + cls : ""), alt: "", referrerpolicy: "no-referrer", decoding: "async" });
      const kids = [];
      if (vizMode !== "orbit") { viz.arts = vizMode === "bars" ? [art()] : [art(), art("pf-b")]; kids.push(...viz.arts); }
      if (vizMode === "orbit") kids.push(h("div", { class: "pf-viz-ring" }), h("div", { class: "pf-viz-ring pf-sharp" }));
      if (vizMode === "bars") { viz.canvas = h("canvas", { class: "pf-viz-bars" }); viz.cx = viz.canvas.getContext("2d"); kids.push(viz.canvas); }
      viz.el = h("div", { class: "pf-viz pf-ui", "data-mode": vizMode, "aria-hidden": "true" }, kids);
      host.prepend(viz.el);
      host.setAttribute("data-pf-viz", "");
      viz.src = "";
    }
    const src = bigArt();
    if (src && src !== viz.src) { viz.src = src; for (const im of viz.arts) im.src = src; }
    if (vizHears() && !viz.raf) viz.raf = requestAnimationFrame(vizFrame);
  }
  function vizFrame(t) {
    viz.raf = 0;
    if (!viz.el || !viz.el.isConnected || document.hidden) return;
    const an = snd.analyser && snd.el === video() ? snd.analyser : null;
    const playing = document.documentElement.hasAttribute("data-pf-playing");
    let level = 0, bins = null;
    if (an && playing) {
      if (!viz.data || viz.data.length !== an.frequencyBinCount) viz.data = new Uint8Array(an.frequencyBinCount);
      an.getByteFrequencyData(viz.data);
      bins = viz.data;
      let bass = 0;
      for (let i = 1; i <= 12; i++) bass += bins[i];
      level = Math.pow(bass / (12 * 255), 1.6);
    } else if (playing) level = 0.3 + 0.12 * Math.sin(t / 650);
    viz.level += (level - viz.level) * (level > viz.level ? 0.45 : 0.12);   // quick to rise, slow to fall
    viz.el.style.setProperty("--pf-level", viz.level.toFixed(3));
    if (vizMode === "bars") drawBars(bins, playing, t);
    if (playing || viz.level > 0.01) viz.raf = requestAnimationFrame(vizFrame);   // paused: it settles, then rests
  }
  // a point on the cover's rounded edge, s from 0 to 1 clockwise from the top left, and the way out from it
  function edgePoint(x, y, w, hh, rad, s) {
    const straight = [w - 2 * rad, hh - 2 * rad, w - 2 * rad, hh - 2 * rad], arc = (Math.PI / 2) * rad;
    const total = straight.reduce((a, b) => a + b, 0) + 4 * arc;
    let d = s * total;
    const corners = [[x + w - rad, y + rad, -Math.PI / 2], [x + w - rad, y + hh - rad, 0], [x + rad, y + hh - rad, Math.PI / 2], [x + rad, y + rad, Math.PI]];
    const starts = [[x + rad, y, 1, 0, 0, -1], [x + w, y + rad, 0, 1, 1, 0], [x + w - rad, y + hh, -1, 0, 0, 1], [x, y + hh - rad, 0, -1, -1, 0]];
    for (let k = 0; k < 4; k++) {
      const [sx, sy, dx, dy, nx, ny] = starts[k];
      if (d <= straight[k]) return [sx + dx * d, sy + dy * d, nx, ny];
      d -= straight[k];
      if (d <= arc) { const [cx, cy, a0] = corners[k], a = a0 + d / rad; return [cx + rad * Math.cos(a), cy + rad * Math.sin(a), Math.cos(a), Math.sin(a)]; }
      d -= arc;
    }
    return [x + rad, y, 0, -1];
  }
  function drawBars(bins, playing, t) {
    const c = viz.canvas, cx = viz.cx, now = performance.now();
    if (!viz.rect || now - viz.rectAt > 500) { viz.rect = viz.host.getBoundingClientRect(); viz.rectAt = now; }
    const r = viz.rect, M = 72, dpr = devicePixelRatio || 1, W = Math.round(r.width + 2 * M), H = Math.round(r.height + 2 * M);
    if (c.width !== Math.round(W * dpr) || c.height !== Math.round(H * dpr)) {
      c.width = Math.round(W * dpr); c.height = Math.round(H * dpr);
      c.style.cssText = `left:${-M}px;top:${-M}px;width:${W}px;height:${H}px`;
    }
    cx.setTransform(dpr, 0, 0, dpr, 0, 0);
    cx.clearRect(0, 0, W, H);
    const N = 112, rad = Math.min(36, r.width / 4);
    cx.lineCap = "round";
    cx.lineWidth = 4;
    for (let i = 0; i < N; i++) {
      const [px, py, nx, ny] = edgePoint(M, M, r.width, r.height, rad, i / N);
      const k = Math.min(i, N - i) / (N / 2);              // mirrored: low notes at the top, high ones at the bottom
      let v;
      if (bins) v = bins[Math.min(bins.length - 1, 2 + Math.floor(Math.pow(k, 1.7) * 260))] / 255;
      else v = playing ? 0.12 + 0.08 * Math.sin(t / 380 + i * 0.45) : 0.03;
      const len = 3 + v * (M - 14);
      cx.globalAlpha = 0.3 + 0.7 * v;
      cx.strokeStyle = colors.accent;
      cx.beginPath();
      cx.moveTo(px + nx * 8, py + ny * 8);
      cx.lineTo(px + nx * (8 + len), py + ny * (8 + len));
      cx.stroke();
    }
    cx.globalAlpha = 1;
  }
  // the quick menu's tile: one press for the next one
  function vizTile() {
    const state = h("small");
    const b = h("button", { class: "pf-tile", type: "button", title: "Cover visualizer (experimental): press for the next one" },
      h("span", { class: "pf-tile-ico" }, icon(ICON.viz, 22)), h("span", { class: "pf-tile-text" }, "Visualizer", state));
    const paint = () => { b.setAttribute("aria-checked", String(vizMode !== "off")); b.setAttribute("aria-label", "Cover visualizer: " + vizMode); state.textContent = VIZ_MODES.find((x) => x[0] === vizMode)[1]; };
    b.addEventListener("click", () => {
      const i = VIZ_MODES.findIndex((x) => x[0] === vizMode);
      setViz(VIZ_MODES[(i + 1) % VIZ_MODES.length][0]);
      paint();
    });
    paint();
    return b;
  }
  function vizChooser() {
    const chips = VIZ_MODES.map(([m, label]) => h("button", { class: "pf-chip", text: label, "aria-pressed": String(vizMode === m),
      onclick: () => { setViz(m); for (const c of chips) c.setAttribute("aria-pressed", String(c.textContent === label)); } }));
    return h("div", { class: "pf-viz-pick" }, h("div", { class: "pf-row-text" }, "Cover visualizer (experimental)",
      h("small", { text: "Around the big cover in the player page. Pulse and Bars follow the music once a press on the page lets it be heard here" })),
      h("div", { class: "pf-chips" }, chips));
  }

  // ------------------------------------------------------------------ animations
  // The "motion" layer's CSS does the moving; these marks on the page say when: a new page (or a new set of
  // results) has arrived, a new song started, the player page opened, a tab in it changed.
  const pulses = {};
  function pulse(cls, ms) {
    if (!on("motion") || REDUCED.matches) return;
    const de = document.documentElement;
    de.classList.remove(cls);
    clearTimeout(pulses[cls]);
    // added again two frames later, so the animation starts over (without making the page lay itself out now)
    requestAnimationFrame(() => requestAnimationFrame(() => {
      de.classList.add(cls);
      pulses[cls] = setTimeout(() => de.classList.remove(cls), ms);
    }));
  }
  // A page's content arriving is seen by the page element's data changing, shortly after an address change or a
  // click on something that leads somewhere (filter chips change the content without changing the address).
  let pageData = null, pageUrl = location.href, watchUntil = 0, dataAt = 0;
  function pageEl() {
    for (const el of document.querySelectorAll("ytmusic-browse-response, ytmusic-search-page")) if (el.getClientRects().length) return el;
    return null;
  }
  function watchPage() {
    if (!on("motion") || document.hidden) return;
    const now = Date.now();
    if (location.href !== pageUrl) {
      pageUrl = location.href;
      if (now - dataAt < 900) { dataAt = 0; pulse("pf-nav", 1300); } else watchUntil = now + 6000;
    }
    const el = pageEl(), d = el && el.data;
    if (d && d !== pageData) {
      const first = !pageData;
      pageData = d;
      if (first) return;
      if (now < watchUntil) { watchUntil = 0; pulse("pf-nav", 1300); } else dataAt = now;
    }
  }
  setInterval(watchPage, 120);
  window.addEventListener("click", (e) => {
    const t = e.target;
    if (t && t.closest && t.closest("a[href], ytmusic-two-row-item-renderer, ytmusic-responsive-list-item-renderer, ytmusic-guide-entry-renderer, " +
      "ytmusic-chip-cloud-chip-renderer, ytmusic-pivot-bar-item-renderer, ytmusic-search-suggestion, ytmusic-tab-renderer, tp-yt-paper-tab")) watchUntil = Date.now() + 6000;
  }, { capture: true, passive: true });
  let animVid = null;
  function songChanged() {
    const vid = API.curVideo();
    if (vid && vid !== animVid) { const first = !animVid; animVid = vid; if (!first) pulse("pf-song", 1000); }
  }
  let pageOpen = null, pageObs = null, pageObsOn = null;
  function watchPlayerPage() {
    const open = !!document.querySelector("ytmusic-player-bar[player-page-open], ytmusic-app-layout[player-page-open]");
    if (pageOpen !== null && open !== pageOpen) {
      if (open) { pulse("pf-page", 1100); setTimeout(lyricsBack, 60); } else lyricsAway();
    }
    pageOpen = open;
    const el = document.querySelector("ytmusic-app-layout") || document.querySelector("ytmusic-player-bar");
    if (el && el !== pageObsOn) {
      if (pageObs) pageObs.disconnect();
      pageObsOn = el;
      pageObs = new MutationObserver(watchPlayerPage);
      pageObs.observe(el, { attributes: true, attributeFilter: ["player-page-open"] });
    }
  }

  // ------------------------------------------------------------------ putting it together
  function applyAll() {
    if (partOn) ensureStyles(); else removeStyles();
    for (const [k, el] of Object.entries(styleEls)) el.media = on(k) ? "all" : "not all";
    if (partOn) updateColors(true);
    syncWave();
    syncDice();
    syncLyrics();
    syncSpeedButton();
    syncTimes();
    syncGuide();
    syncLogo();
    syncViz();
    applyRoom();
    applyPitch();
  }
  function restorePalette() {
    const saved = on("colors") && readJSON(COLOR_KEY, null);
    if (saved && isFinite(saved.h)) setPalette(palette(saved.h, saved.s), saved.h, saved.s);
    else setPalette(palette(DEFAULT_HUE, 1), DEFAULT_HUE, 1);
  }
  // YTM Starcast's "Refine" switch went off: everything Refine added leaves the page at once (open menus and dialogs too),
  // and a speed Refine remembered for this song steps back. Speeds set by hand stay.
  function takeDown() {
    closeSheet(); closeAll(); closeSpeed(); closeLyricsWindow(); closeMaker();
    applyAll();                                   // styles, the wave, the player buttons and the pitch (all follow on())
    for (const el of document.querySelectorAll(".pf-ui, .pf-menu-item, .pf-time")) el.remove();   // closing ones too: no styles are left for them
    for (const el of document.querySelectorAll("[data-pf-lyrics], [data-pf-time-src]")) { el.removeAttribute("data-pf-lyrics"); el.removeAttribute("data-pf-time-src"); }
    if (lyr.where) unmountLyrics();
    if (navBtn) { navBtn.remove(); navBtn = null; }
    if (dcNavBtn) { dcNavBtn.remove(); dcNavBtn = null; }
    removeViz();
    for (const el of document.querySelectorAll("[data-pf-logo], [data-pf-logo-box]")) { el.removeAttribute("data-pf-logo"); el.removeAttribute("data-pf-logo-box"); }
    if (floatSet) { floatSet = null; try { navigator.mediaSession.setActionHandler("enterpictureinpicture", null); } catch (e) {} }
    document.documentElement.removeAttribute("data-pf-playing");
    const st = document.documentElement.style;
    for (const k of Object.keys(colors)) st.removeProperty("--pf-" + k.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase()));
    if (ruleApplied) { ruleApplied = false; if (Math.abs(getRate() - 1) > 0.01) setRateRaw(1); }
  }
  function setPart(v) {
    if (v === partOn) return;
    partOn = v;
    writeJSON(PART_KEY, { on: v });
    if (!v) { takeDown(); return; }
    colorFor = "";                                // read the cover again
    restorePalette();
    lastVid = null;                               // the loop applies this song's remembered speed again
    applyAll();
    syncPlaying();
    syncNav();
  }
  // The extension's own script passes on what the YTM Starcast app says ({cfg}: an app from before the switches says
  // nothing, which means on), and answers Refine's panel.
  window.addEventListener("message", (ev) => {
    const d = ev.source === window && ev.data;
    if (!d || typeof d !== "object") return;
    if (d.__vmr === "cfg" && d.cfg && typeof d.cfg === "object") setPart(d.cfg.refine !== false);
    else if (d.__vmr === "refine-open" && d.what === "settings" && live()) openAll();   // asked for from YTM Starcast (Orbit's card)
    else if (d.__vmr === "starcast-result" && scWaiting.has(d.id)) { scWaiting.get(d.id)(d.data && typeof d.data === "object" ? d.data : {}); scWaiting.delete(d.id); }
  });
  let lastVid = null, tick = 0;
  function loop() {
    try {
      if (partOn) {
        tick++;
        syncPlaying();
        const vid = API.curVideo();
        if (vid !== lastVid) {
          lastVid = vid;
          enforceRate();
          setTimeout(enforceRate, 1500); // the player can reset its speed while the new song loads
          updateColors(false);
          songChanged();
        } else if (tick % 4 === 0) updateColors(false); // the cover can arrive a moment after the song
        syncLyrics();
        watchPlayerPage();
        syncAutoFloat();
        syncViz();
        if (tick % 3 === 0) { syncNav(); syncDice(); syncSpeedButton(); syncWave(); syncTimes(); enforceRate(); syncGuide(); syncLogo(); syncDiscordDot(); }
        kickWave();
      }
    } catch (e) {}
    setTimeout(loop, document.hidden || !partOn ? 2500 : 700);
  }

  Object.defineProperty(window, Symbol.for("pocketfader.refine"), { configurable: true, value: Object.freeze({
    report: () => ({
      version: VERSION, killed: KILL, part: partOn, prefs: { ...prefs },
      nav: navBtn ? (navBtn.isConnected ? (navBtn.classList.contains("pf-float") ? "floating" : "top bar") : "removed") : "not placed",
      dice: !!(dice && dice.isConnected), speedButton: !!document.querySelector(".ytmusicPlayerControlsPlaybackRateButton") || !!(speedBtn && speedBtn.isConnected),
      bar: (() => { const b = barHost(); return b ? (b.classic ? "classic" : "newer") : "none"; })(),
      lyrics: { where: lyr.where, tab: !!lyricsTab(), tabHost: !!document.querySelector("ytmusic-player-page #tab-renderer"), window: !!lyr.frame, pip: !!lyr.pip,
        pinned: lyrWin.pinned, source: lyr.source, words: lyr.lines.some((ln) => ln.w && ln.w.length), estimated: lyr.est },
      speed: { rate: getRate(), base: baseRate, times: (timeEls || []).map((el) => el.tagName.toLowerCase() + "." + String(el.className).split(" ")[0]) },
      sound: { kind: snd.kind, state: snd.ctx ? snd.ctx.state : "none", err: snd.err, clear: clearActive(), room: soundOpts.room },
      discordButton: !!(dcNavBtn && dcNavBtn.isConnected), logo: document.querySelectorAll("ytmusic-logo[data-pf-logo]").length,
      sidebar: [...document.querySelectorAll(".pf-guide")].map((g) => (g.classList.contains("pf-guide-mini") ? "mini" : "full") + ":" + (g.parentElement ? g.parentElement.tagName.toLowerCase() + (g.parentElement.id ? "#" + g.parentElement.id : "") : "")),
      tabs: [...document.querySelectorAll("ytmusic-player-page tp-yt-paper-tab")].map((t) => (t.textContent || "").trim().slice(0, 20)),
      autoFloat: !!floatSet, follow: { auto: lyr.auto, pipAuto: lyr.pipAuto },
      viz: { mode: vizMode, shown: !!(viz.el && viz.el.isConnected), hears: !!(snd.analyser && snd.el === video()) },
      sliders: [...overlays.keys()].map((el) => el.tagName.toLowerCase() + (el.id ? "#" + el.id : "")),
      color: lastColor ? { h: Math.round(lastColor.h), vivid: Math.round(lastColor.s * 100) / 100 } : null, colorNote,
      speedRules: { songs: Object.keys(speeds.s).length, playlists: Object.keys(speeds.p).length },
    }),
  }) });
  if (window.__pfTest) window.__pfRefineTest = { dominant, palette, rank, spread, pickSeeds, songKey, cleanTitle, artistKey, findSliders, scaleTimes, estimateWords,
    splitWords, lyricsTab, syncLyricsTab, openLyricsWindow, closeLyricsWindow, renderLyrics, lyr, lyrWin, lyrSongs, openAll, closeAll, toggleSheet,
    syncGuide, syncLogo, watchPlayerPage, setViz, edgePoint, vizFrame, report: () => window[Symbol.for("pocketfader.refine")].report() };

  if (partOn) restorePalette();
  applyAll();
  loop();
  // a pinned lyrics window comes back with the page
  if (lyrWin.open && lyrWin.pinned && on("lyrics")) setTimeout(openLyricsWindow, 1200);
  else if (lyrWin.open) { lyrWin.open = false; saveWin(); }
  window.postMessage({ __vmr: "cfg-get" }, "*");   // what the YTM Starcast app said, if the extension's script already knows
  console.log("[YTM Starcast] Refine ready" + (KILL ? " (off for this page)" : !partOn ? " (switched off in YTM Starcast)" : ""));
})();
