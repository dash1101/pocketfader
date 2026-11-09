/* Pocketfader — Refine panel for YouTube Music (ISOLATED world).
 *
 * A small floating panel inside music.youtube.com with things YouTube Music doesn't offer: playback speed, an
 * "I'm feeling lucky" button, a colour theme, playlists made by Gemini from a description, YTM Beacon (Discord presence)
 * controls and a few Pocketfader options. It talks to the page's player through ytm_main.js (window messages) and to the
 * Pocketfader server on this computer. Your Gemini key is kept in this browser only and is sent to Google only.
 */
(() => {
  "use strict";
  if (window.__pfRefine) return;
  window.__pfRefine = true;
  const BASE = "http://127.0.0.1:8777";

  // ---------------------------------------------------------------- plumbing
  let seq = 0;
  const pending = new Map();
  window.addEventListener("message", (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (d && d.__vmr === "ui-result" && pending.has(d.id)) { pending.get(d.id)(d.data); pending.delete(d.id); }
  });
  const page = (command, ms = 45000) => new Promise((res) => {
    const id = "ui" + (++seq);
    pending.set(id, res);
    window.postMessage({ __vmr: "ui", id, command }, "*");
    setTimeout(() => { if (pending.delete(id)) res({ error: "timeout" }); }, ms);
  });
  const api = async (path, body) => {
    try {
      const r = await fetch(BASE + path, { method: body ? "POST" : "GET", headers: body ? { "Content-Type": "application/json" } : undefined, body: body ? JSON.stringify(body) : undefined });
      return await r.json();
    } catch (e) { return null; }
  };
  const store = {
    get: (k, d) => new Promise((res) => { try { chrome.storage.local.get({ [k]: d }, (v) => res(v[k])); } catch (e) { res(d); } }),
    set: (k, v) => new Promise((res) => { try { chrome.storage.local.set({ [k]: v }, res); } catch (e) { res(); } }),
  };

  // ---------------------------------------------------------------- theme
  // Surfaces are derived from one accent colour (hue-tinted dark tones, like Material You), and written over YouTube Music's
  // own colour variables. Experimental: YouTube Music changes these names now and then.
  function hexToHsl(hex) {
    const m = /^#?([0-9a-f]{6})$/i.exec(hex || ""); if (!m) return [262, 80, 60];
    const n = parseInt(m[1], 16), r = (n >> 16) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2; let h = 0, s = 0;
    if (mx !== mn) { const d = mx - mn; s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4; h *= 60; }
    return [Math.round(h), Math.round(s * 100), Math.round(l * 100)];
  }
  function themeCss(t) {
    if (!t || t.name === "off") return "";
    const [h] = hexToHsl(t.accent);
    const acc = t.accent || "#8b5cf6";
    const tones = t.name === "amoled" ? { a: "#000", b: "#050505", c: "#0d0d0d", txt: "#f2f2f2" }
      : { a: `hsl(${h} 14% 8%)`, b: `hsl(${h} 14% 11%)`, c: `hsl(${h} 15% 15%)`, txt: `hsl(${h} 25% 94%)` };
    return `html, ytmusic-app, ytmusic-app-layout { --ytmusic-general-background-a:${tones.a} !important; --ytmusic-general-background-b:${tones.b} !important;
      --ytmusic-general-background-c:${tones.c} !important; --ytmusic-nav-bar-background:${tones.a} !important; --ytmusic-player-bar-background:${tones.c} !important;
      --ytmusic-brand-background-solid:${tones.a} !important; --ytmusic-color-red:${acc} !important; --yt-spec-call-to-action:${acc} !important;
      --yt-spec-themed-blue:${acc} !important; --yt-spec-static-brand-red:${acc} !important; --yt-spec-text-primary:${tones.txt} !important; }
      ytmusic-app-layout, ytmusic-app { background:${tones.a} !important; }
      ytmusic-player-page, ytmusic-nav-bar { background:${tones.a} !important; }
      ${t.css || ""}`;
  }
  function applyTheme(t) {
    let st = document.getElementById("pf-theme");
    if (!st) { st = document.createElement("style"); st.id = "pf-theme"; document.documentElement.appendChild(st); }
    st.textContent = themeCss(t);
  }

  // ---------------------------------------------------------------- Gemini playlists
  const GEMINI = "https://generativelanguage.googleapis.com/v1beta/models/";
  function parseTracks(text) {
    // the answer should be JSON, but tolerate a code fence or a bit of chatter around it
    const clean = String(text || "").replace(/^```(?:json)?/i, "").replace(/```$/, "").trim();
    let obj; try { obj = JSON.parse(clean); } catch (e) { const m = /[\[{][\s\S]*[\]}]/.exec(clean); if (!m) return null; try { obj = JSON.parse(m[0]); } catch (e2) { return null; } }
    const arr = Array.isArray(obj) ? obj : (obj && (obj.tracks || obj.songs || obj.playlist)) || [];
    const tracks = arr.map((t) => (typeof t === "string" ? { title: t, artist: "" } : { title: String(t.title || t.song || ""), artist: String(t.artist || t.artists || "") }))
      .filter((t) => t.title);
    return { title: (obj && !Array.isArray(obj) && obj.title) || "", tracks };
  }
  async function askGemini(key, model, prompt, count) {
    const text = `You are a music curator. Make a playlist of ${count} real songs for this request: "${prompt}".\n` +
      `Reply with ONLY JSON: {"title": "short playlist name", "tracks": [{"title": "song title", "artist": "main artist"}]}. Only songs that exist on YouTube Music; no duplicates.`;
    const r = await fetch(GEMINI + encodeURIComponent(model) + ":generateContent", {
      method: "POST", headers: { "Content-Type": "application/json", "x-goog-api-key": key },
      body: JSON.stringify({ contents: [{ parts: [{ text }] }], generationConfig: { responseMimeType: "application/json", temperature: 0.9 } }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error((j.error && j.error.message) || ("Gemini answered " + r.status));
    const out = j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts && j.candidates[0].content.parts[0] && j.candidates[0].content.parts[0].text;
    const parsed = parseTracks(out);
    if (!parsed || !parsed.tracks.length) throw new Error("Gemini's answer had no songs in it");
    return parsed;
  }

  // ---------------------------------------------------------------- panel
  const STYLE = `
    :host { all:initial; }
    * { box-sizing:border-box; font-family:Roboto,system-ui,sans-serif; }
    .fab { position:fixed; left:18px; bottom:104px; z-index:2147483000; width:48px; height:48px; border-radius:16px; border:0; cursor:pointer;
           background:var(--acc,#8b5cf6); color:#fff; font-size:22px; box-shadow:0 6px 18px rgba(0,0,0,.45); transition:border-radius .2s, transform .12s; }
    .fab:hover { border-radius:50%; } .fab:active { transform:scale(.92); }
    .panel { position:fixed; left:18px; bottom:162px; z-index:2147483000; width:340px; max-height:min(70vh,620px); overflow:auto; padding:14px;
             background:#1b1b22; color:#f0f0f6; border:1px solid #33333f; border-radius:26px; box-shadow:0 18px 46px rgba(0,0,0,.55); }
    .panel[hidden] { display:none; }
    h2 { margin:2px 4px 10px; font-size:16px; } h3 { margin:14px 4px 6px; font-size:12px; letter-spacing:.06em; text-transform:uppercase; color:#a9a9bd; }
    .card { background:#24242d; border-radius:18px; padding:10px; display:flex; flex-direction:column; gap:8px; }
    .row { display:flex; gap:6px; flex-wrap:wrap; align-items:center; }
    button.c { border:0; border-radius:999px; padding:7px 13px; background:#33333f; color:inherit; cursor:pointer; font-size:13px; }
    button.c.on, button.c.p { background:var(--acc,#8b5cf6); color:#fff; } button.c:disabled { opacity:.5; cursor:default; }
    input, textarea, select { width:100%; background:#15151b; color:inherit; border:1px solid #3a3a48; border-radius:12px; padding:8px 10px; font-size:13px; }
    textarea { resize:vertical; min-height:56px; } input[type=color] { width:44px; height:34px; padding:2px; }
    .m { font-size:12.5px; color:#b9b9cc; line-height:1.4; } .err { color:#ff8a80; }
    .tracks { max-height:150px; overflow:auto; font-size:12.5px; color:#cfcfe0; padding-left:18px; margin:0; }
  `;
  async function build() {
    const t = await store.get("theme", { name: "off", accent: "#8b5cf6", css: "" });
    const speedPref = await store.get("speed", 1);
    applyTheme(t);
    const host = document.createElement("div"); host.id = "pf-refine";
    document.documentElement.appendChild(host);
    const root = host.attachShadow({ mode: "open" });
    root.innerHTML = `<style>${STYLE}</style><button class="fab" title="Pocketfader · Refine">✦</button>
      <div class="panel" hidden>
        <h2>Pocketfader · Refine</h2>
        <h3>Playback</h3><div class="card"><div class="row" id="speeds"></div><div class="row"><button class="c p" id="lucky">🎲 I'm feeling lucky</button><span class="m" id="luckymsg"></span></div></div>
        <h3>Theme <span class="m">(experimental)</span></h3><div class="card">
          <select id="tname"><option value="off">YouTube Music default</option><option value="material">Material You (from your colour)</option><option value="amoled">AMOLED black</option></select>
          <div class="row"><span class="m">Accent</span><input type="color" id="tacc"><button class="c" id="tapply">Apply</button></div></div>
        <h3>Playlist from a description (Gemini)</h3><div class="card">
          <input id="gkey" type="password" placeholder="Gemini API key (kept in this browser)" autocomplete="off">
          <input id="gmodel" placeholder="Model" value="gemini-flash-latest">
          <textarea id="gprompt" placeholder="e.g. mellow indie for a rainy study session, no vocals in English"></textarea>
          <div class="row"><select id="gcount" style="width:auto"><option>10</option><option selected>15</option><option>20</option><option>30</option></select><span class="m">songs</span><button class="c p" id="ggo">Generate</button></div>
          <div class="m" id="gmsg"></div><ol class="tracks" id="gtracks" hidden></ol><div class="row" id="gacts" hidden><button class="c p" id="gcreate">Save as playlist</button><button class="c" id="gplay">Save and play</button></div></div>
        <h3>Discord presence (YTM Beacon)</h3><div class="card"><div class="m" id="bstat">Checking…</div><div class="row" id="bacts"></div></div>
        <h3>Pocketfader</h3><div class="card"><div class="m" id="pfstat">Checking…</div>
          <div class="row"><span class="m">Sleep timer</span><button class="c" data-sl="15">15m</button><button class="c" data-sl="30">30m</button><button class="c" data-sl="60">1h</button><button class="c" data-sl="track">Song end</button><button class="c" data-sl="0">Off</button></div>
          <div class="row"><button class="c" id="pauseoth">Pause other players</button></div></div>
      </div>`;
    const $ = (s) => root.querySelector(s);
    const fab = $(".fab"), panel = $(".panel");
    const accent = () => (t.accent || "#8b5cf6");
    host.style.setProperty("--acc", accent());
    fab.style.setProperty("--acc", accent()); panel.style.setProperty("--acc", accent());

    // open / close; the live parts refresh only while it's open
    let timer = null;
    fab.addEventListener("click", () => { panel.hidden = !panel.hidden; if (!panel.hidden) { refreshLive(); timer = setInterval(refreshLive, 5000); } else clearInterval(timer); });

    // speed
    const sp = $("#speeds");
    for (const r of [0.75, 1, 1.25, 1.5, 1.75, 2]) {
      const b = document.createElement("button"); b.className = "c" + (r === speedPref ? " on" : ""); b.textContent = r + "×";
      b.addEventListener("click", async () => { const out = await page({ type: "speed", rate: r }); if (out && out.ok) { await store.set("speed", r); sp.querySelectorAll("button").forEach((x) => x.classList.toggle("on", x === b)); } });
      sp.appendChild(b);
    }
    $("#lucky").addEventListener("click", async () => { const m = $("#luckymsg"); m.textContent = "Picking…"; const r = await page({ type: "lucky" }); m.textContent = r && r.ok ? "Playing: " + (r.picked || "") : "Couldn't pick (" + ((r && r.error) || "no answer") + ")"; });

    // theme
    $("#tname").value = t.name; $("#tacc").value = accent();
    $("#tapply").addEventListener("click", async () => { t.name = $("#tname").value; t.accent = $("#tacc").value; await store.set("theme", t); applyTheme(t); host.style.setProperty("--acc", t.accent); fab.style.setProperty("--acc", t.accent); panel.style.setProperty("--acc", t.accent); });

    // Gemini
    store.get("gemini", { key: "", model: "gemini-flash-latest" }).then((g) => { $("#gkey").value = g.key || ""; $("#gmodel").value = g.model || "gemini-flash-latest"; });
    let plan = null;
    $("#ggo").addEventListener("click", async () => {
      const key = $("#gkey").value.trim(), model = $("#gmodel").value.trim() || "gemini-flash-latest", prompt = $("#gprompt").value.trim(), msg = $("#gmsg");
      if (!key || !prompt) { msg.textContent = "Add your Gemini API key and a description."; msg.className = "m err"; return; }
      await store.set("gemini", { key, model }); msg.className = "m"; msg.textContent = "Asking Gemini…"; $("#ggo").disabled = true; $("#gacts").hidden = true; $("#gtracks").hidden = true;
      try {
        const g = await askGemini(key, model, prompt, +$("#gcount").value);
        msg.textContent = "Finding the songs on YouTube Music…";
        const found = await page({ type: "resolveSongs", queries: g.tracks.map((x) => (x.title + " " + x.artist).trim()) }, 120000);
        const ok = ((found && found.results) || []).map((r, i) => ({ ...r, want: g.tracks[i] })).filter((r) => r.videoId);
        if (!ok.length) throw new Error("None of the songs were found on YouTube Music");
        plan = { title: g.title || prompt.slice(0, 60), ids: ok.map((r) => r.videoId) };
        const ol = $("#gtracks"); ol.textContent = ""; ok.forEach((r) => { const li = document.createElement("li"); li.textContent = (r.title || r.want.title) + (r.subtitle ? " — " + r.subtitle : ""); ol.appendChild(li); });
        ol.hidden = false; $("#gacts").hidden = false; msg.textContent = ok.length + " of " + g.tracks.length + " songs found: “" + plan.title + "”";
      } catch (e) { msg.className = "m err"; msg.textContent = String(e.message || e); }
      $("#ggo").disabled = false;
    });
    const save = async (play) => {
      if (!plan) return; const msg = $("#gmsg"); msg.className = "m"; msg.textContent = "Saving…";
      const r = await page({ type: "playlistCreate", title: plan.title, videoIds: plan.ids });
      if (!r || !r.ok) { msg.className = "m err"; msg.textContent = "Couldn't save the playlist (are you signed in to YouTube Music?)"; return; }
      msg.textContent = "Saved “" + plan.title + "” to your library."; if (play && r.playlistId) await page({ type: "startPlaylist", playlistId: r.playlistId });
    };
    $("#gcreate").addEventListener("click", () => save(false)); $("#gplay").addEventListener("click", () => save(true));

    // Beacon + Pocketfader (live)
    const TEXT = { showing: "Showing on Discord", waiting: "Getting ready…", idle: "Ready", off: "Switched off", snoozed: "Hidden for a while", "paused-hidden": "Not shown while paused",
      "paused-timeout": "Hidden (paused a while)", filtered: "Hidden (never-show word)", "extension-quiet": "Beacon's extension went quiet", "extension-missing": "Beacon's browser extension isn't set up",
      "discord-down": "Can't reach Discord", unsupported: "YTM Beacon is out of date", unreachable: "YTM Beacon isn't running on this computer" };
    async function refreshLive() {
      const b = await api("/api/beacon"), bs = $("#bstat"), ba = $("#bacts"); ba.textContent = "";
      if (!b) { bs.textContent = "Pocketfader isn't reachable on this computer."; } else {
        bs.textContent = (TEXT[b.headline] || b.headline) + (b.nowPlaying ? " · " + b.nowPlaying.title : "");
        if (b.reachable && b.headline !== "unsupported") {
          const mk = (label, fn) => { const x = document.createElement("button"); x.className = "c"; x.textContent = label; x.addEventListener("click", async () => { await fn(); refreshLive(); }); ba.appendChild(x); };
          if (b.visible) { mk("Hide for 1 hour", () => api("/api/beacon/visibility", { action: "hide", minutes: 60 })); mk("Switch off", () => api("/api/beacon/visibility", { action: "hide" })); }
          else mk("Show on Discord", () => api("/api/beacon/visibility", { action: "show" }));
        }
      }
      const i = await api("/api/info"); $("#pfstat").textContent = i ? "Connected to Pocketfader " + i.version : "Pocketfader isn't reachable on this computer.";
    }
    root.querySelectorAll("[data-sl]").forEach((x) => x.addEventListener("click", async () => { const v = x.dataset.sl; await api("/api/sleep", { minutes: v === "track" ? "track" : +v }); }));
    $("#pauseoth").addEventListener("click", async () => { const r = await api("/api/media/pause-others", {}); $("#pfstat").textContent = r && r.paused && r.paused.length ? "Paused " + r.paused.join(", ") : "Nothing else was playing"; });
  }
  if (window.__pfTest) window.__pfRefineTest = { parseTracks, themeCss, hexToHsl };
  // the theme is applied as early as possible so the page doesn't flash its own colours
  (async () => { try { applyTheme(await store.get("theme", { name: "off" })); } catch (e) {} })();
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", build); else build();
})();
