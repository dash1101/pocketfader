/* YTM Starcast — YouTube Music bridge (ISOLATED world).
 *
 * The page's CSP blocks the MAIN-world script from reaching http://localhost, so this
 * content script owns the server link (extension privilege via host_permissions) and
 * relays over window.postMessage:
 *   server  --GET /ext/poll (long-poll)-->  here  --postMessage {__vmr:"starcast-cmd"}-->  main world
 *   main world  --postMessage {__vmr:result|state}-->  here  --POST /ext/*-->  server
 * Commands go out as "starcast-cmd", not the older "cmd": with an older copy of the extension still loaded beside this
 * one, each copy's page script runs only what its own bridge sends, so no command runs twice.
 * The app's replies to /ext/poll and /ext/state also say which parts of YTM Starcast are switched on ({cfg}); that goes on to
 * the page as {__vmr:"cfg"}. An app from before the switches sends none: then everything is on.
 * The toolbar popup and Refine's panel reach the app through here as well (fixed routes only), so their requests carry
 * this page's origin like everything else.
 *
 * No background service worker: the always-open YTM tab is the durable anchor.
 */
(() => {
  "use strict";
  const BASE = "http://127.0.0.1:8777";
  // This tab's name, so the server can follow one tab when several YouTube Music tabs are open.
  const INST = (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)).slice(0, 36);
  const PARTS = ["remote", "discord", "refine"];   // the switches this extension may show and change
  const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

  // Which parts are on, as the app last said; null until it has answered once.
  let cfg = null;
  function takeCfg(reply) {
    if (!reply || typeof reply !== "object") return;
    const c = reply.cfg && typeof reply.cfg === "object" ? reply.cfg : {};
    const next = {};
    for (const k of PARTS) next[k] = c[k] !== false;   // missing = on
    if (cfg && PARTS.every((k) => cfg[k] === next[k])) return;
    cfg = next;
    window.postMessage({ __vmr: "cfg", cfg }, "*");
  }

  // main world -> server
  window.addEventListener("message", (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || typeof d !== "object") return;
    if (d.__vmr === "result") {
      post("/ext/result", { id: d.id, data: d.data });
    } else if (d.__vmr === "state") {
      if (d.state && typeof d.state === "object") pushState({ ...d.state, instance: INST });
    } else if (d.__vmr === "lyrics" && typeof d.id === "string" && d.q && typeof d.q === "object") {
      lyrics(d.id.slice(0, 40), d.q);
    } else if (d.__vmr === "ready" || d.__vmr === "cfg-get") {
      // a page script that started after the last change asks what is on
      if (cfg) window.postMessage({ __vmr: "cfg", cfg }, "*");
    } else if (d.__vmr === "starcast" && typeof d.id === "string") {
      starcastForPage(d.id.slice(0, 40), d);
    } else if (d.__vmr === "palette") {
      keepPalette(d.palette);
    } else if (d.__vmr === "asset" && typeof d.id === "string") {
      // the address of the clear-slowed-sound processor (the only file the page may ask for)
      let url = null;
      try { if (d.name === "stretch.js") url = chrome.runtime.getURL("stretch.js"); } catch (e) {}
      window.postMessage({ __vmr: "asset-result", id: d.id.slice(0, 40), url }, "*");
    }
  });

  // Live lyrics inside YouTube Music (refine.js): only song details go out, to this one fixed route, and the address
  // is built here, so nothing on the page can make this script fetch anything else.
  async function lyrics(id, q) {
    const str = (v, n) => (typeof v === "string" ? v.slice(0, n) : "");
    const params = new URLSearchParams({ title: str(q.title, 300), artist: str(q.artist, 300), album: str(q.album, 300),
      videoId: /^[\w-]{11}$/.test(q.videoId || "") ? q.videoId : "", duration: String(Math.max(0, Math.min(36000, Number(q.duration) || 0))) });
    if (/^[a-z]{2,12}$/.test(q.source || "")) params.set("source", q.source);
    let data;
    try {
      const r = await fetch(BASE + "/ext/lyrics?" + params, { method: "GET" });
      // an app from before live lyrics answers "not found"
      data = r.status === 404 ? { error: "old_app" } : await r.json();
    } catch (e) {
      data = { error: "offline" };
    }
    window.postMessage({ __vmr: "lyrics-result", id, data }, "*");
  }

  // Refine's colours (a hue and how vivid the cover is), kept for the toolbar popup, which takes on the same look.
  let paletteT = 0;
  function keepPalette(p) {
    if (!p || typeof p !== "object") return;
    const h = Number(p.h), s = Number(p.s);
    if (!isFinite(h) || !isFinite(s)) return;
    clearTimeout(paletteT);
    paletteT = setTimeout(() => {
      try { chrome.storage.local.set({ palette: { h: ((Math.round(h) % 360) + 360) % 360, s: Math.max(0, Math.min(1, s)), at: Date.now() } }); } catch (e) {}
    }, 300);
  }

  function post(path, obj) {
    return fetch(BASE + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(obj),
    }).catch(() => {});
  }
  // this extension's version, so the app can tell when the browser still runs an older copy (it then asks for a reload)
  let EXT_VERSION = null;
  try { EXT_VERSION = chrome.runtime.getManifest().version; } catch (e) { /* the extension was reloaded under us */ }
  // what plays -> server; its answer carries the switches
  function pushState(obj) {
    return fetch(BASE + "/ext/state", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(EXT_VERSION ? { ...obj, ext: EXT_VERSION } : obj),
    }).then((r) => (r.ok ? r.json().then(takeCfg) : null)).catch(() => {});
  }

  // ------------------------------------------------------------ YTM Starcast's switches (toolbar popup, Refine's panel)
  // A request with a time limit, so a stuck app never leaves the popup or the panel waiting.
  async function call(path, init, ms = 5000) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), ms);
    try { return await fetch(BASE + path, { ...init, signal: ctl.signal }); } finally { clearTimeout(t); }
  }
  // What an app from before YTM Starcast's switches calls itself (it has no /ext/status).
  async function appInfo() {
    try {
      const r = await call("/api/info", { method: "GET" }, 3000);
      const j = r.ok ? await r.json() : null;
      const s = (v) => (typeof v === "string" ? v.slice(0, 40) : "");
      return j && typeof j === "object" ? { name: s(j.name), version: s(j.version) } : null;
    } catch (e) {
      return null;
    }
  }
  async function status() {
    try {
      const r = await call("/ext/status", { method: "GET" });
      if (r.status === 404) return { error: "old_app", app: await appInfo() };
      const s = await r.json().catch(() => null);
      if (!r.ok || !s || typeof s !== "object") return { error: "failed" };
      if (s.parts && typeof s.parts === "object") takeCfg({ cfg: s.parts });
      return { status: s, twin: twin() };
    } catch (e) {
      return { error: "offline" };
    }
  }
  // The page script marks the page when another copy of the extension is loaded as well (see ytm_main.js).
  function twin() {
    try { return !!document.documentElement.getAttribute("data-starcast-twin"); } catch (e) { return false; }
  }
  // Only the three switches, only as true/false (the "Computer audio" switch itself can't be changed from here).
  async function setParts(parts) {
    const body = {};
    if (parts && typeof parts === "object") for (const k of PARTS) if (typeof parts[k] === "boolean") body[k] = parts[k];
    if (!Object.keys(body).length) return { error: "bad_request" };
    try {
      const r = await call("/ext/parts", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      if (r.status === 404) return { error: "old_app", app: await appInfo() };
      const j = await r.json().catch(() => null);
      if (!r.ok || !j || !j.parts || typeof j.parts !== "object") return { error: "failed" };
      takeCfg({ cfg: j.parts });   // this tab follows at once; the others hear it on their next reply
      return { parts: j.parts };
    } catch (e) {
      return { error: "offline" };
    }
  }
  // Refine's panel (page world) asks through postMessage, like live lyrics. Any script in the page can post the same
  // message, so a part is switched ON from the page only right after a real press on that part's switch: a pointer or
  // Space key event the browser itself made (isTrusted), which no page script can fake. Switching a part off needs no
  // press (it only ever shows less), and the toolbar popup and the apps switch parts on as before.
  const pressed = new Map();   // part -> { input, at }: the last real press on its switch in Refine's panel
  function notePress(ev) {
    if (!ev.isTrusted || (ev.type === "keyup" && ev.key !== " " && ev.key !== "Spacebar")) return;
    try {
      const row = ev.target && ev.target.closest ? ev.target.closest("label") : null;
      const input = row && row.querySelector("input[data-pf-part]");
      const part = input && input.getAttribute("data-pf-part");
      if (PARTS.includes(part)) pressed.set(part, { input, at: performance.now() });
    } catch (e) {}
  }
  document.addEventListener("pointerup", notePress, true);
  document.addEventListener("keyup", notePress, true);
  function pressedFor(parts) {
    if (!parts || typeof parts !== "object") return true;   // setParts turns it away
    let ok = true;
    for (const k of PARTS) {
      if (!(k in parts)) continue;
      const p = pressed.get(k);
      pressed.delete(k);                                     // one press, one change
      if (parts[k] !== true) continue;                       // off (or not true/false, which setParts drops)
      if (!p || performance.now() - p.at > 3000 || !p.input.isConnected || !p.input.checked) ok = false;
    }
    return ok;
  }
  // ---- Discord status: every option, for Refine's panel and the toolbar popup ----
  async function discord() {
    try {
      const r = await call("/ext/discord", { method: "GET" });
      if (r.status === 404) return { error: "old_app", app: await appInfo() };
      const j = await r.json().catch(() => null);
      return r.ok && j && typeof j === "object" ? { discord: j } : { error: "failed" };
    } catch (e) {
      return { error: "offline" };
    }
  }
  // kind: "settings" (any of the Discord options), "visibility" ({action, minutes}) or "test". The app checks every value.
  async function discordSet(kind, body) {
    if (!["settings", "visibility", "test"].includes(kind)) return { error: "bad_request" };
    const b = body && typeof body === "object" && !Array.isArray(body) ? body : {};
    try {
      const r = await call("/ext/discord/" + kind, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) });
      if (r.status === 404) return { error: "old_app", app: await appInfo() };
      const j = await r.json().catch(() => null);
      if (!r.ok) return { error: (j && (j.message || j.error)) || "failed" };
      return { ok: true, reply: j };
    } catch (e) {
      return { error: "offline" };
    }
  }
  // From the page, a Discord change only goes through right after a real press inside Refine's Discord options
  // (the same rule as the switches above): a script in the page can't change what Discord shows on its own.
  let dcPressAt = 0;
  function noteDcPress(ev) {
    if (!ev.isTrusted) return;
    if (ev.type === "keyup" && !["Enter", " ", "Spacebar"].includes(ev.key)) return;
    try { if (ev.target && ev.target.closest && ev.target.closest("[data-pf-dc]")) dcPressAt = performance.now(); } catch (e) {}
  }
  document.addEventListener("pointerup", noteDcPress, true);
  document.addEventListener("keyup", noteDcPress, true);
  function dcPressed() {
    const ok = dcPressAt && performance.now() - dcPressAt < 3000;
    dcPressAt = 0;
    return !!ok;
  }
  // ---- the computer's sound (volume, output, equalizer), for Refine's panel ----
  async function sound() {
    try {
      const r = await call("/ext/sound", { method: "GET" });
      if (r.status === 404) return { error: "old_app", app: await appInfo() };
      const j = await r.json().catch(() => null);
      return r.ok && j && typeof j === "object" ? { sound: j } : { error: "failed" };
    } catch (e) {
      return { error: "offline" };
    }
  }
  const SOUND_ROUTES = { volume: "volume", output: "output", outputVolume: "output/volume", eq: "eq" };
  async function soundSet(kind, body) {
    if (!Object.prototype.hasOwnProperty.call(SOUND_ROUTES, kind)) return { error: "bad_request" };
    const b = body && typeof body === "object" && !Array.isArray(body) ? body : {};
    try {
      const r = await call("/ext/sound/" + SOUND_ROUTES[kind], { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) });
      if (r.status === 404 && kind !== "output") return { error: "old_app", app: await appInfo() };
      const j = await r.json().catch(() => null);
      if (!r.ok) return { error: (j && (j.message || j.error)) || "failed" };
      return { ok: true };
    } catch (e) {
      return { error: "offline" };
    }
  }
  // From the page, the computer's sound changes only while someone is really using Refine's sound controls: a press, a
  // drag or a key the browser itself made (isTrusted) inside them in the last moment. Sliders send many changes per drag,
  // so a press isn't used up by one.
  let sndPressAt = 0;
  function noteSndPress(ev) {
    if (!ev.isTrusted) return;
    try { if (ev.target && ev.target.closest && ev.target.closest("[data-pf-snd]")) sndPressAt = performance.now(); } catch (e) {}
  }
  for (const type of ["pointerdown", "pointermove", "pointerup", "keydown", "input", "change"]) document.addEventListener(type, noteSndPress, true);
  const sndPressed = () => !!sndPressAt && performance.now() - sndPressAt < 1500;

  async function starcastForPage(id, d) {
    const data = d.op === "status" ? await status()
      : d.op === "parts" ? (pressedFor(d.parts) ? await setParts(d.parts) : { error: "needs_press" })
      : d.op === "discord" ? await discord()
      : d.op === "discordSet" ? (dcPressed() ? await discordSet(d.kind, d.body) : { error: "needs_press" })
      : d.op === "sound" ? await sound()
      : d.op === "soundSet" ? (sndPressed() ? await soundSet(d.kind, d.body) : { error: "needs_press" })
      : { error: "bad_request" };
    window.postMessage({ __vmr: "starcast-result", id, data }, "*");
  }
  // The toolbar popup asks through the extension's own messages.
  const rt = typeof chrome !== "undefined" && chrome.runtime && chrome.runtime.onMessage ? chrome.runtime : null;
  if (rt) {
    rt.onMessage.addListener((msg, sender, reply) => {
      if (!msg || typeof msg !== "object" || (sender && sender.id && sender.id !== rt.id)) return;
      if (msg.starcast === "status") { status().then(reply, () => reply({ error: "failed" })); return true; }
      if (msg.starcast === "parts") { setParts(msg.parts).then(reply, () => reply({ error: "failed" })); return true; }
      if (msg.starcast === "discord") { discord().then(reply, () => reply({ error: "failed" })); return true; }
      if (msg.starcast === "discordSet") { discordSet(msg.kind, msg.body).then(reply, () => reply({ error: "failed" })); return true; }
    });
  }

  // The tab is closing or navigating away: say goodbye, so the app drops it (and its Discord status) at once.
  // A plain-text body keeps this a simple request that needs no preflight; keepalive lets it outlive the page.
  addEventListener("pagehide", () => {
    try {
      fetch(BASE + "/ext/bye", { method: "POST", keepalive: true, headers: { "Content-Type": "text/plain" },
        body: JSON.stringify({ instance: INST }) }).catch(() => {});
    } catch (e) {}
  });
  // Back from the browser's back/forward cache after that goodbye: the page sends everything again.
  addEventListener("pageshow", (ev) => { if (ev.persisted) window.postMessage({ __vmr: "resend" }, "*"); });

  // "focus": this YouTube Music tab comes to the front (the extension's background script does that); "settings": and
  // Refine's All settings open in it.
  async function bringForward(command) {
    let ok = false;
    try { const r = await chrome.runtime.sendMessage({ starcast: "focusTab" }); ok = !!(r && r.ok); } catch (e) {}
    if (command.type === "settings") window.postMessage({ __vmr: "refine-open", what: "settings" }, "*");
    post("/ext/result", { id: command.id, data: { ok, refine: !cfg || cfg.refine !== false } });
  }

  // server -> main world (long-poll loop, self-healing)
  async function loop() {
    for (;;) {
      try {
        const r = await fetch(BASE + "/ext/poll?i=" + encodeURIComponent(INST), { method: "GET" });
        const reply = await r.json();
        if (!r.ok) { await sleep(2000); continue; }   // refused or failed: don't ask again straight away
        takeCfg(reply);
        // the app started after this tab last reported (it was restarted): the page sends everything again
        if (reply && reply.resend === true) window.postMessage({ __vmr: "resend" }, "*");
        if (reply && reply.id) {
          const { cfg: _cfg, resend: _resend, ...command } = reply;
          if (command.type === "focus" || command.type === "settings") bringForward(command);
          else window.postMessage({ __vmr: "starcast-cmd", command }, "*");
        }
      } catch (e) {
        // server not up yet / restarting — back off and retry
        await sleep(2000);
      }
    }
  }
  loop();
  console.log("[YTM Starcast] YouTube Music bridge → " + BASE);
})();
