/* Pocketfader Refine: improvements to YouTube Music itself, built into its own page (page world, after ytm_main.js).
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
 *  - Its settings open from a button in YouTube Music's top bar.
 *
 * Everything here depends on YouTube Music's page, which changes now and then, so each part fails quietly on its own
 * and can be switched off from that menu. Opening music.youtube.com/?pf-refine=off starts a page without any of it.
 * It talks to nothing but the page and YouTube Music's own endpoints (with your own login); settings stay in this browser.
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
  const DEFAULTS = { shapes: true, colors: true, controls: true, wavy: true, glow: true, speedMenu: true, keepPitch: true, dice: true, lyrics: true,
    songMenu: true };
  const readJSON = (k, d) => {
    try { const v = JSON.parse(localStorage.getItem(k) || "null"); return v && typeof v === "object" ? v : d; } catch (e) { return d; }
  };
  const writeJSON = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} };
  const prefs = { ...DEFAULTS, ...readJSON(PREFS_KEY, {}) };
  // ?pf-refine=off: this page starts with everything off (the saved choices are kept for next time)
  const on = (k) => !KILL && !!prefs[k];
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
    for (const c of kids.flat()) if (c != null && c !== false) el.append(c.nodeType ? c : String(c));
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
    p.setAttribute("d", d);
    p.setAttribute("fill", "currentColor");
    s.append(p);
    return s;
  }
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
  function setPalette(p) {
    colors = p;
    const st = document.documentElement.style;
    for (const [k, v] of Object.entries(p)) st.setProperty("--pf-" + k.replace(/[A-Z]/g, (m) => "-" + m.toLowerCase()), v);
    kickWave();
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
    if (!on("colors")) { if (colorFor !== "-") { colorFor = "-"; setPalette(palette(DEFAULT_HUE, 1)); } return; }
    const url = artUrl();
    if (!url || colorBusy || (!force && url === colorFor)) return;
    colorBusy = true;
    try {
      const r = await sampleArt(url);
      if (!on("colors")) return; // switched off while the cover was being read
      colorFor = url;
      colorNote = r.err || "";
      lastColor = r.c || null;
      setPalette(r.c ? palette(r.c.h, r.c.s) : palette(DEFAULT_HUE, 1));
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
  /* live lyrics */
  .pf-lyr { position: fixed; right: 16px; bottom: 104px; z-index: 2200; width: min(400px, calc(100vw - 32px)); height: min(64vh, 580px);
    display: flex; flex-direction: column; background: var(--pf-surface3); border-radius: var(--pf-r-xl); box-shadow: 0 16px 48px rgba(0,0,0,.5);
    opacity: 0; transform: translateY(14px) scale(.97); transform-origin: bottom right; transition: opacity var(--pf-t-short) var(--pf-ease), transform var(--pf-t-medium) var(--pf-spring); }
  .pf-lyr.pf-open { opacity: 1; transform: none; }
  .pf-lyr-head { display: flex; align-items: center; gap: 8px; padding: 16px 12px 8px 22px; }
  .pf-lyr-head .pf-head { flex: 1; padding: 0; min-width: 0; }
  .pf-lyr-head .pf-sub { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pf-lyr-opts { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 8px 14px; padding: 2px 18px 12px 22px; }
  .pf-lyr-opts[hidden] { display: none; }
  .pf-lyr-opts .pf-label { margin: 0; }
  .pf-lyr-body { position: relative; flex: 1; min-height: 0; box-sizing: border-box; overflow-y: auto; padding: min(18vh, 150px) 18px min(22vh, 180px); scrollbar-width: none; mask-image: linear-gradient(transparent, #000 12%, #000 82%, transparent); }
  .pf-lyr-body::-webkit-scrollbar { display: none; }
  /* with the options open there is less room: the lines start right under them */
  .pf-lyr-opts:not([hidden]) + .pf-lyr-body { padding-top: 20px; padding-bottom: 64px; }
  .pf-lyr.pf-lyr-s { --pf-ln: 18px; }
  .pf-lyr.pf-lyr-l { --pf-ln: 27px; }
  .pf-ln { font-size: var(--pf-ln, 22px); font-weight: 700; line-height: 1.3; padding: 7px 8px; margin: 2px 0; border-radius: var(--pf-r-sm); color: var(--pf-text);
    opacity: .38; cursor: pointer; transition: opacity .3s, transform var(--pf-t-medium) var(--pf-spring), background-color .2s; transform-origin: left center; }
  .pf-ln:hover { background: rgba(255,255,255,.05); opacity: .7; }
  .pf-ln.pf-now { opacity: 1; transform: scale(1.03); }
  .pf-ln.pf-plain { cursor: default; opacity: .9; font-size: calc(var(--pf-ln, 22px) * .82); }
  .pf-ln.pf-plain:hover { background: none; }
  .pf-ln small { display: block; font-size: calc(var(--pf-ln, 22px) * .64); font-weight: 500; color: var(--pf-muted); margin-top: 2px; }
  .pf-lyr-center .pf-ln { text-align: center; transform-origin: center; }
  .pf-lyr-norom .pf-ln small.pf-rom { display: none; }
  .pf-lyr-notr .pf-ln small.pf-tr { display: none; }
  .pf-ln .pf-wd { background: linear-gradient(90deg, var(--pf-accent) var(--p, 0%), currentColor var(--p, 0%)); -webkit-background-clip: text;
    background-clip: text; color: transparent; }
  /* only the line being sung shows how far the singing is: after a jump back, later lines must not stay coloured */
  .pf-ln:not(.pf-now) .pf-wd { background: none; color: inherit; }
  .pf-ln .pf-gap { letter-spacing: .3em; }
  .pf-lyr-msg { color: var(--pf-muted); font-size: 15px; line-height: 1.5; text-align: center; padding: 0 12px; }
  /* the wavy progress bar is drawn over YouTube Music's own, which stays underneath (invisible) for seeking */
  .pf-wave { position: fixed; left: 0; top: 0; pointer-events: none; }
  .pf-tip { position: fixed; left: 0; top: 0; z-index: 2300; pointer-events: none; padding: 4px 9px; border-radius: var(--pf-r-xs); font-size: 12px; font-weight: 600;
    font-variant-numeric: tabular-nums; background: var(--pf-on-container); color: var(--pf-surface); opacity: 0; transition: opacity .12s; }
  .pf-tip.pf-open { opacity: 1; }
  [data-pf-wave] { opacity: 0 !important; }
  .pf-dice .pf-ico, .pf-lyr-wrap .pf-ico, .pf-bar-btn .pf-ico { display: grid; place-items: center; }
  /* Refine's buttons in the classic player bar, sized and spaced like its own round ones (the newer bar lends its button looks instead) */
  .pf-bar-btn { flex: none; margin: 4px 8px 4px 4px; }
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
  @media (prefers-reduced-motion: reduce) { .pf-ui, .pf-ui *, .pf-nav-btn { transition: none !important; animation: none !important; } }
  `;
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
    ytmusic-search-box #input-box, ytmusic-search-box .search-box { background-color: var(--pf-surface2) !important; }`,
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
    ytmusic-player #song-image, ytmusic-player #thumbnail { box-shadow: 0 30px 110px -16px var(--pf-glow) !important; transition: box-shadow .9s; }
    ytmusic-miniplayer img, ytmusic-player-bar .image { box-shadow: 0 6px 22px -6px var(--pf-glow); }`,
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
    const playing = playerState() === 1;
    if (document.documentElement.hasAttribute("data-pf-playing") !== playing) {
      document.documentElement.toggleAttribute("data-pf-playing", playing);
      kickWave();
    }
    const v = video();
    if (v && v !== boundVideo) {
      boundVideo = v;
      for (const ev of ["play", "playing", "pause", "seeked", "ratechange"]) v.addEventListener(ev, () => setTimeout(syncPlaying, 30));
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
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      o.hidden = !!top && !(top === host || host.contains(top) || top.contains(host));
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
    tip.textContent = clock(fracAt(r, x) * dur);
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
  const fmtRate = (r) => (Math.round(r * 100) / 100).toString() + "×";
  // What the music really plays at: the media element's rate (the player rounds to the few speeds it knows).
  function getRate() {
    const v = video();
    if (v && v.playbackRate) return Math.round(v.playbackRate * 100) / 100;
    const p = API.mp();
    try { if (p && p.getPlaybackRate) return p.getPlaybackRate() || 1; } catch (e) {}
    return 1;
  }
  function applyPitch() {
    const v = video();
    if (v && "preservesPitch" in v) v.preservesPitch = !!prefs.keepPitch;
  }
  // YouTube Music's player only knows a few speeds; anything in between is set on the media element itself.
  function setRateRaw(r) {
    r = Math.round(clamp(r, 0.25, 3) * 100) / 100;
    const p = API.mp(), v = video();
    try { if (p && p.setPlaybackRate) p.setPlaybackRate(r); } catch (e) {}
    try { if (v && Math.abs(v.playbackRate - r) > 0.001) v.playbackRate = r; } catch (e) {}
    applyPitch();
    setTimeout(syncSpeedButton, 0); // the classic bar's speed button shows when the speed isn't the usual one
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
  }
  // A remembered speed is put back when its song or playlist plays, and doesn't spill over onto other songs.
  let ruleApplied = false;
  function applySpeedRule() {
    const rule = ruleFor(API.curVideo(), API.curPlaylist());
    if (rule) {
      if (Math.abs(getRate() - rule.rate) > 0.01) setRateRaw(rule.rate);
      ruleApplied = true;
    } else if (ruleApplied) {
      ruleApplied = false;
      if (Math.abs(getRate() - 1) > 0.01) setRateRaw(1);
    }
    applyPitch();
  }
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
    const presets = [0.5, 0.75, 1, 1.25, 1.5, 2];
    const chips = presets.map((r) => h("button", { class: "pf-chip", text: fmtRate(r), onclick: () => show(setRate(r)) }));
    const rule = ruleFor(vid, pl);
    const segs = [["off", "Off"], ["song", "This song"], ["playlist", "This playlist"]].map(([kind, label]) =>
      h("button", { class: "pf-chip", text: label, "aria-pressed": String((rule ? rule.kind : "off") === kind),
        disabled: (kind === "song" && !vid) || (kind === "playlist" && !pl), onclick: (e) => {
          setRemember(kind);
          for (const s of segs) s.setAttribute("aria-pressed", String(s === e.currentTarget));
        } }));
    function show(r) {
      val.textContent = fmtRate(r);
      range.value = String(r);
      range.style.setProperty("--pf-fill", ((r - 0.25) / 2.75) * 100 + "%");
      chips.forEach((c, i) => c.setAttribute("aria-pressed", String(Math.abs(presets[i] - r) < 0.001)));
    }
    range.addEventListener("input", () => show(setRate(parseFloat(range.value))));
    const pitch = h("input", { type: "checkbox", role: "switch" });
    pitch.checked = !!prefs.keepPitch;
    pitch.addEventListener("change", () => setPref("keepPitch", pitch.checked));
    speedPop = h("div", { class: "pf-pop pf-ui", role: "dialog", "aria-label": "Playback speed" },
      h("div", { class: "pf-speed-top" },
        h("button", { class: "pf-step", "aria-label": "Slower", onclick: () => show(setRate(getRate() - 0.05)) }, icon(ICON.minus)),
        val,
        h("button", { class: "pf-step", "aria-label": "Faster", onclick: () => show(setRate(getRate() + 0.05)) }, icon(ICON.plus))),
      range,
      h("div", { class: "pf-chips" }, chips),
      h("label", { class: "pf-row" }, h("span", { class: "pf-row-text" }, "Keep the pitch", h("small", { text: "When off, faster also sounds higher" })),
        h("span", { class: "pf-switch" }, pitch, h("i"))),
      h("div", { class: "pf-label", text: "Remember this speed for" }),
      h("div", { class: "pf-seg" }, segs));
    show(getRate());
    document.body.append(speedPop);
    const r = anchor.getBoundingClientRect();
    speedPop.style.left = clamp(r.left + r.width / 2 - 170, 8, innerWidth - 348) + "px";
    if (r.top > 380) speedPop.style.bottom = innerHeight - r.top + 10 + "px";
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

  let dice = null;
  function syncDice() {
    if (!on("dice")) { if (dice) { dice.remove(); dice = null; } return; }
    if (dice && dice.isConnected) return;
    const host = barHost();
    if (!host) return;
    const btn = h("button", { class: host.look + "pf-dice-btn", "aria-label": "Play something you might like",
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
    dice = barWrap(host, "pf-dice", btn);
    if (host.repeat) host.repeat.after(dice); else host.el.append(dice);
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
  // Time-synced lyrics from the Pocketfader app on this computer (the same ones its apps show), relayed by the
  // extension's own script: this page can't reach the app itself.
  let lyrSeq = 0;
  const lyrWaiting = new Map();
  window.addEventListener("message", (ev) => {
    const d = ev.source === window && ev.data;
    if (d && d.__vmr === "lyrics-result" && lyrWaiting.has(d.id)) { lyrWaiting.get(d.id)(d.data || {}); lyrWaiting.delete(d.id); }
  });
  function fetchLyrics(q) {
    return new Promise((res) => {
      const id = "pfly" + ++lyrSeq;
      lyrWaiting.set(id, res);
      window.postMessage({ __vmr: "lyrics", id, q }, "*");
      setTimeout(() => { if (lyrWaiting.delete(id)) res({ error: "timeout" }); }, 20000);
    });
  }
  const lyr = { panel: null, body: null, sub: null, key: "", lines: [], els: [], idx: -1, synced: false, userAt: 0, raf: 0, retry: 0, timer: 0,
    optBtns: [], hasRom: false, hasTr: false, run: 0 };
  let lyrBtn = null;
  // how the lyrics look: text size, alignment, and whether the romanized line is shown. Kept apart from the on/off settings.
  const LYR_KEY = "pocketfader.refine.lyrics";
  const LYR_SIZES = ["s", "m", "l"], LYR_ALIGNS = ["left", "center"];
  const lyrOpts = (() => {
    const o = readJSON(LYR_KEY, {});
    return { size: LYR_SIZES.includes(o.size) ? o.size : "m", align: LYR_ALIGNS.includes(o.align) ? o.align : "left", rom: o.rom !== false, tr: o.tr === true };
  })();
  function applyLyrOpts() {
    if (!lyr.panel) return;
    const c = lyr.panel.classList;
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
  }
  function setLyrOpt(k, v) {
    lyrOpts[k] = v;
    writeJSON(LYR_KEY, lyrOpts);
    applyLyrOpts();
    // the lines changed height: bring the one being sung back to its place
    const el = lyr.els[lyr.idx];
    if (el && lyr.body) lyr.body.scrollTop = el.offsetTop - lyr.body.clientHeight * 0.4;
  }
  function lyrOptions() {
    lyr.optBtns = [];
    const seg = (k, choices) => h("div", { class: "pf-seg" }, choices.map(([v, label]) => {
      const btn = h("button", { class: "pf-chip", text: label, onclick: () => setLyrOpt(k, v) });
      lyr.optBtns.push([btn, k, v]);
      return btn;
    }));
    return h("div", { class: "pf-lyr-opts", id: "pf-lyr-opts", hidden: true },
      h("div", { class: "pf-label", text: "Size" }), seg("size", [["s", "Small"], ["m", "Medium"], ["l", "Large"]]),
      h("div", { class: "pf-label", text: "Align" }), seg("align", [["left", "Left"], ["center", "Centre"]]),
      h("div", { class: "pf-label", text: "Romanized" }), seg("rom", [[true, "Show"], [false, "Hide"]]),
      h("div", { class: "pf-label", text: "Translation" }), seg("tr", [[true, "Show"], [false, "Hide"]]));
  }
  function syncLyricsButton() {
    if (!on("lyrics")) { if (lyrBtn) { lyrBtn.remove(); lyrBtn = null; } if (lyr.panel) closeLyrics(); return; }
    if (lyrBtn && lyrBtn.isConnected) return;
    const host = barHost();
    if (!host) return;
    const btn = h("button", { class: host.look + "pf-lyr-btn", "aria-label": "Live lyrics", title: "Live lyrics",
      "aria-pressed": String(!!lyr.panel), onclick: (e) => { e.stopPropagation(); if (lyr.panel) closeLyrics(); else openLyrics(); } },
      h("span", { class: "pf-ico" }, icon(ICON.lyrics)));
    lyrBtn = barWrap(host, "pf-lyr-wrap", btn);
    const after = host.el.querySelector(".pf-dice") || host.repeat;
    if (after) after.after(lyrBtn); else host.el.append(lyrBtn);
  }
  function placeLyrics() {
    if (!lyr.panel) return;
    const bar = document.querySelector("ytmusic-miniplayer, ytmusic-player-bar");
    const r = bar && bar.getBoundingClientRect();
    lyr.panel.style.bottom = (r && r.height && r.top > innerHeight / 2 ? Math.round(innerHeight - r.top + 12) : 24) + "px";
  }
  function openLyrics() {
    closeLyrics();
    lyr.sub = h("div", { class: "pf-sub", text: "" });
    lyr.body = h("div", { class: "pf-lyr-body", "aria-live": "off" });
    const opts = lyrOptions();
    const optsBtn = h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Lyrics options", title: "Lyrics options", "aria-expanded": "false",
      "aria-controls": "pf-lyr-opts", onclick: () => { opts.hidden = !opts.hidden; optsBtn.setAttribute("aria-expanded", String(!opts.hidden)); } }, icon(ICON.tune));
    lyr.panel = h("div", { class: "pf-lyr pf-ui", role: "dialog", "aria-label": "Lyrics" },
      h("div", { class: "pf-lyr-head" }, h("div", { class: "pf-head" }, h("div", { class: "pf-title", text: "Lyrics" }), lyr.sub), optsBtn,
        h("button", { class: "pf-btn pf-icon-btn", "aria-label": "Close lyrics", onclick: closeLyrics }, icon(ICON.close))),
      opts, lyr.body);
    applyLyrOpts();
    for (const ev of ["wheel", "touchmove", "pointerdown"]) lyr.body.addEventListener(ev, () => { lyr.userAt = Date.now(); }, { passive: true });
    document.body.append(lyr.panel);
    placeLyrics();
    requestAnimationFrame(() => lyr.panel && lyr.panel.classList.add("pf-open"));
    if (lyrBtn) lyrBtn.querySelector("button").setAttribute("aria-pressed", "true");
    lyr.key = "";
    loadLyrics();
  }
  function closeLyrics() {
    if (!lyr.panel) return;
    const el = lyr.panel;
    lyr.panel = lyr.body = null;
    lyr.run++;
    clearTimeout(lyr.timer);
    cancelAnimationFrame(lyr.raf); lyr.raf = 0;
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 220);
    if (lyrBtn) lyrBtn.querySelector("button").setAttribute("aria-pressed", "false");
  }
  function lyrMessage(text) {
    if (!lyr.body) return;
    lyr.body.textContent = "";
    lyr.body.style.paddingTop = "30%";
    lyr.body.append(h("div", { class: "pf-lyr-msg", text }));
    lyr.lines = []; lyr.els = []; lyr.idx = -1;
    lyr.hasRom = lyr.hasTr = false; applyLyrOpts();
  }
  async function loadLyrics() {
    const np = API.nowPlaying() || {};
    if (!lyr.panel) return;
    if (!np.videoId || !np.title) { lyr.sub.textContent = ""; lyrMessage("Play a song to see its lyrics."); return; }
    const key = np.videoId;
    if (key !== lyr.key) { lyr.key = key; lyr.retry = 0; lyr.sub.textContent = np.title + (np.artist ? " \u00b7 " + np.artist : ""); lyrMessage("Looking for lyrics\u2026"); }
    const r = await fetchLyrics({ videoId: np.videoId, title: np.title, artist: np.artist || "", duration: Math.round(np.duration || 0) });
    if (!lyr.panel || lyr.key !== key) return; // closed, or the song changed meanwhile
    if (r.error) {
      lyrMessage(r.error === "offline" ? "Live lyrics come from the Pocketfader app on this computer. Start it, then open lyrics again."
        : r.error === "old_app" ? "Live lyrics need a newer Pocketfader app on this computer. Update it, then open lyrics again."
        : "The Pocketfader app didn't answer in time. Try again in a moment.");
      return;
    }
    const L = r.lyrics;
    // other sources may still be searching: ask again shortly if what's here isn't time-synced yet
    if (r.pending && (!L || !L.synced) && lyr.retry++ < 4) { clearTimeout(lyr.timer); lyr.timer = setTimeout(loadLyrics, 2500); }
    if (!r.found || !L || !L.lines || !L.lines.length) { if (!r.pending) lyrMessage("No lyrics found for this song."); return; }
    renderLyrics(L);
  }
  function renderLyrics(L) {
    lyr.synced = !!L.synced;
    lyr.lines = L.lines;
    lyr.body.textContent = "";
    lyr.body.style.paddingTop = "";
    const np = API.nowPlaying() || {};
    lyr.sub.textContent = [np.title, (L.label || "") + (L.synced ? "" : " (not time-synced)")].filter(Boolean).join(" \u00b7 ");
    lyr.els = L.lines.map((ln) => {
      const el = h("div", { class: "pf-ln" + (L.synced ? "" : " pf-plain") });
      if (!ln.text) el.append(h("span", { class: "pf-gap", text: "\u266a \u266a \u266a" }));
      else if (L.synced && Array.isArray(ln.w) && ln.w.length) {
        for (const [, , word] of ln.w) el.append(h("span", { class: "pf-wd", text: word }));
      } else el.append(ln.text);
      if (ln.rom) el.append(h("small", { class: "pf-rom", text: ln.rom }));
      if (ln.tr) el.append(h("small", { class: "pf-tr", text: ln.tr }));
      if (L.synced && ln.t != null) el.addEventListener("click", () => { try { API.mp().seekTo(ln.t, true); lyr.userAt = 0; } catch (e) {} });
      lyr.body.append(el);
      return el;
    });
    lyr.idx = -1;
    lyr.hasRom = L.lines.some((ln) => !!ln.rom);
    lyr.hasTr = L.lines.some((ln) => !!ln.tr);
    applyLyrOpts();
    // one loop at a time: lyrics can be drawn again (a better source arrived) while the last loop is still waiting
    if (L.synced) lyrTick(++lyr.run); else { lyr.run++; lyr.body.scrollTop = 0; }
  }
  function lyrTick(run) {
    lyr.raf = 0;
    if (run !== lyr.run || !lyr.panel || !lyr.synced || !lyr.lines.length) return;
    if (!document.hidden) {
      const pos = times().pos + 0.15; // a touch early, as the eye reads ahead
      let i = -1;
      for (let k = 0; k < lyr.lines.length; k++) { if (lyr.lines[k].t != null && lyr.lines[k].t <= pos) i = k; else if (lyr.lines[k].t > pos) break; }
      if (i !== lyr.idx) {
        if (lyr.els[lyr.idx]) lyr.els[lyr.idx].classList.remove("pf-now");
        lyr.idx = i;
        const el = lyr.els[i];
        if (el) {
          el.classList.add("pf-now");
          if (Date.now() - lyr.userAt > 4000) lyr.body.scrollTo({ top: el.offsetTop - lyr.body.clientHeight * 0.4, behavior: REDUCED.matches ? "auto" : "smooth" });
        }
      }
      const ln = lyr.lines[i], el = lyr.els[i];
      if (ln && el && Array.isArray(ln.w)) {
        const spans = el.querySelectorAll(".pf-wd");
        ln.w.forEach(([a, b], k) => { if (spans[k]) spans[k].style.setProperty("--p", (b > a ? clamp((pos - a) / (b - a), 0, 1) * 100 : pos >= a ? 100 : 0) + "%"); });
      }
    }
    lyr.raf = requestAnimationFrame(() => setTimeout(() => lyrTick(run), 60)); // about 15 times a second is plenty
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

  // ------------------------------------------------------------------ the ✦ menu in the top bar
  let navBtn = null, sheet = null;
  function syncNav() {
    if (navBtn && navBtn.isConnected) return;
    const nav = document.querySelector("ytmusic-nav-bar");
    if (!nav) return;
    navBtn = h("button", { class: "pf-nav-btn", "aria-label": "Pocketfader Refine", title: "Pocketfader Refine", "aria-expanded": "false",
      onclick: (e) => { e.stopPropagation(); if (sheet) closeSheet(); else openSheet(); } }, icon(ICON.sparkle));
    const right = nav.querySelector("#right-content, .right-content");
    if (right) right.prepend(navBtn);
    else { navBtn.classList.add("pf-float"); nav.append(navBtn); }
  }
  function sw(key, label, sub) {
    const input = h("input", { type: "checkbox", role: "switch" });
    input.checked = !!prefs[key];
    input.addEventListener("change", () => setPref(key, input.checked));
    return h("label", { class: "pf-row" },
      h("span", { class: "pf-row-text" }, label, sub ? h("small", { text: sub }) : null),
      h("span", { class: "pf-switch" }, input, h("i")));
  }
  function sheetOutside(e) { if (sheet && !sheet.contains(e.target) && e.target !== navBtn && !navBtn.contains(e.target)) closeSheet(); }
  function sheetKey(e) { if (e.key === "Escape") { closeSheet(); closeSpeed(); if (lyr.panel && lyr.panel.contains(document.activeElement)) closeLyrics(); } }
  function openSheet() {
    closeSheet();
    sheet = h("div", { class: "pf-sheet pf-ui", role: "dialog", "aria-label": "Pocketfader Refine" },
      h("div", { class: "pf-head" }, h("div", { class: "pf-title", text: "Refine" }),
        h("div", { class: "pf-sub", text: KILL ? "Off for this page (?pf-refine=off). Your choices below are kept for next time." : "Pocketfader's additions to YouTube Music" })),
      h("div", { class: "pf-sec", text: "Look" }),
      sw("shapes", "Rounder shapes", "Covers, lists, menus and the search box"),
      sw("colors", "Colours from the album art", "The page takes on the colour of the cover"),
      sw("controls", "Expressive player controls", "A bigger play button that changes shape"),
      sw("wavy", "Wavy progress bar", "Ripples while the song plays, flat when paused"),
      sw("glow", "Glow behind the cover", null),
      h("div", { class: "pf-sec", text: "Playback" }),
      sw("speedMenu", "Richer speed menu", "Opens from the player's own speed button"),
      sw("keepPitch", "Keep the pitch at other speeds", null),
      h("div", { class: "pf-sec", text: "Controls" }),
      sw("dice", "Something-you-might-like button", "In the player, next to repeat"),
      sw("lyrics", "Live lyrics button", "Time-synced lyrics from the Pocketfader app, next to the player"),
      sw("songMenu", "In song menus", "\u201cPlaylist from this song\u201d in YouTube Music's own \u22ee menus"),
      h("button", { class: "pf-btn pf-filled pf-wide", onclick: () => { closeSheet(); openMaker(); } }, icon(ICON.playlistAdd, 20), "Make a playlist…"),
      h("p", { class: "pf-foot", text: "These rely on how YouTube Music's page is built, so a change on its side can break one; " +
        "switch off anything that misbehaves. Settings stay in this browser. Pocketfader extension " + VERSION + "." }));
    const r = navBtn.getBoundingClientRect();
    sheet.style.top = r.bottom + 8 + "px";
    sheet.style.right = Math.max(8, innerWidth - r.right - 4) + "px";
    document.body.append(sheet);
    navBtn.setAttribute("aria-expanded", "true");
    requestAnimationFrame(() => sheet && sheet.classList.add("pf-open"));
    setTimeout(() => document.addEventListener("pointerdown", sheetOutside, true), 0);
    const first = sheet.querySelector("input, button");
    if (first) first.focus({ preventScroll: true });
  }
  function closeSheet() {
    if (!sheet) return;
    const el = sheet;
    sheet = null;
    if (navBtn) navBtn.setAttribute("aria-expanded", "false");
    if (el.contains(document.activeElement) && navBtn && navBtn.isConnected) navBtn.focus();
    el.classList.remove("pf-open");
    setTimeout(() => el.remove(), 200);
    document.removeEventListener("pointerdown", sheetOutside, true);
  }
  document.addEventListener("keydown", sheetKey, true);

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
        const r = await API.playlistCreate({ title, description: "Made with Pocketfader", privacy: "PRIVATE", videoIds: kept() }).catch(() => null);
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

  // ------------------------------------------------------------------ putting it together
  function applyAll() {
    ensureStyles();
    for (const [k, el] of Object.entries(styleEls)) el.media = on(k) ? "all" : "not all";
    updateColors(true);
    syncWave();
    syncDice();
    syncLyricsButton();
    syncSpeedButton();
    applyPitch();
  }
  let lastVid = null, tick = 0;
  function loop() {
    try {
      tick++;
      syncPlaying();
      const vid = API.curVideo();
      if (vid !== lastVid) {
        lastVid = vid;
        applySpeedRule();
        setTimeout(applySpeedRule, 1500); // the player can reset its speed while the new song loads
        updateColors(false);
        if (lyr.panel) loadLyrics();
      } else if (tick % 4 === 0) updateColors(false); // the cover can arrive a moment after the song
      if (tick % 3 === 0) { syncNav(); syncDice(); syncLyricsButton(); syncSpeedButton(); syncWave(); placeLyrics(); }
      kickWave();
    } catch (e) {}
    setTimeout(loop, document.hidden ? 2500 : 700);
  }

  Object.defineProperty(window, Symbol.for("pocketfader.refine"), { configurable: true, value: Object.freeze({
    report: () => ({
      version: VERSION, killed: KILL, prefs: { ...prefs },
      nav: navBtn ? (navBtn.isConnected ? (navBtn.classList.contains("pf-float") ? "floating" : "top bar") : "removed") : "not placed",
      dice: !!(dice && dice.isConnected), speedButton: !!document.querySelector(".ytmusicPlayerControlsPlaybackRateButton") || !!(speedBtn && speedBtn.isConnected),
      lyricsButton: !!(lyrBtn && lyrBtn.isConnected), bar: (() => { const b = barHost(); return b ? (b.classic ? "classic" : "newer") : "none"; })(),
      sliders: [...overlays.keys()].map((el) => el.tagName.toLowerCase() + (el.id ? "#" + el.id : "")),
      color: lastColor ? { h: Math.round(lastColor.h), vivid: Math.round(lastColor.s * 100) / 100 } : null, colorNote,
      speedRules: { songs: Object.keys(speeds.s).length, playlists: Object.keys(speeds.p).length },
    }),
  }) });
  if (window.__pfTest) window.__pfRefineTest = { dominant, palette, rank, spread, pickSeeds, songKey, cleanTitle, artistKey, findSliders, report: () => window[Symbol.for("pocketfader.refine")].report() };

  const saved = on("colors") && readJSON(COLOR_KEY, null);
  setPalette(saved && isFinite(saved.h) ? palette(saved.h, saved.s) : palette(DEFAULT_HUE, 1));
  applyAll();
  loop();
  console.log("[Pocketfader] Refine ready" + (KILL ? " (off for this page)" : ""));
})();
