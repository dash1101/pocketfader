/*
 * YTM Starcast extension: Discord status options, shared by Refine's panel (in the YouTube Music page) and the
 * toolbar popup. Everything YTM Beacon had (show or hide, hide for a while, private mode, paused songs, progress bar,
 * play/pause icon, album art, the Listen button, the name in the member list, never-show words, a test song) plus a
 * live preview of the card.
 *
 * The caller hands in how to ask the app: ask("discord") -> {discord} | {error}, and
 * ask("discordSet", {kind, body}) -> {ok, reply} | {error}, with kind "settings", "visibility" or "test".
 * The panel marks itself with data-pf-dc: from the page, the extension's own script lets a change through only right
 * after a real press inside it.
 */
(() => {
  const g = globalThis;
  if (g.__starcastDiscordPanel) return;

  const TEXT = {
    showing: ["Showing on your Discord profile.", "ok"], waiting: ["Getting ready to show…", "wait"],
    idle: ["Ready. Nothing is playing.", "wait"], off: ["Off. Nothing is shown on Discord.", "off"],
    snoozed: ["Hidden for a while. It comes back by itself.", "off"],
    "paused-hidden": ["Not shown while the music is paused.", "wait"],
    "paused-timeout": ["Hidden after a long pause. It comes back when you press play.", "wait"],
    filtered: ["Not shown: this song matches one of your never-show words.", "off"],
    ad: ["Hidden while an advert plays.", "wait"],
    "discord-down": ["Can't reach Discord. Open the Discord app on this computer.", "bad"],
    "extension-quiet": ["Waiting to hear from YouTube Music.", "wait"], "extension-missing": ["Waiting to hear from YouTube Music.", "wait"],
    "beacon-running": ["The old YTM Beacon app is still running. Open YTM Starcast to move over from it.", "bad"],
    unreachable: ["Discord status isn't answering right now.", "bad"],
  };
  const OPTIONS = [
    ["privateMode", "Private mode", "Only “Listening to YouTube Music”, without the song"],
    ["showAlbumArt", "Album art", "The song's cover instead of the YouTube Music logo"],
    ["showProgress", "Progress bar", "Time played and time left"],
    ["showStateIcon", "Play and pause icon", "A small badge on the cover"],
    ["showButtons", "“Listen on YouTube Music” button", "Lets friends open the song"],
    ["showPaused", "Show paused songs", "Keep the card while the music is paused"],
  ];
  const TIMEOUTS = [[0, "Never"], [5, "5 min"], [15, "15 min"], [30, "30 min"], [60, "1 hour"]];
  const NAMES = [["name", "YouTube Music"], ["state", "The artist"], ["details", "The song"]];

  const CSS = `
  .pfd { display: flex; flex-direction: column; gap: 10px; font: 14px/1.4 inherit; color: var(--pf-text, #e8e2ea); }
  .pfd * { box-sizing: border-box; }
  .pfd-status { display: flex; align-items: flex-start; gap: 8px; color: var(--pf-muted, #b7afba); font-size: 13px; }
  .pfd-dot { flex: none; width: 9px; height: 9px; border-radius: 50%; margin-top: 5px; background: #8d8693; }
  .pfd-dot[data-t=ok] { background: #3ecf8e; } .pfd-dot[data-t=bad] { background: #ff6b6b; } .pfd-dot[data-t=off] { background: #8d8693; }
  .pfd-dot[data-t=wait] { background: #f5b14c; }
  .pfd-card { background: #232428; border-radius: 12px; padding: 12px; color: #dbdee1; font-family: "gg sans", "Noto Sans", sans-serif; }
  .pfd-card .pfd-h { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .02em; color: #b5bac1; margin-bottom: 8px; }
  .pfd-card .pfd-b { display: flex; gap: 12px; align-items: center; }
  .pfd-art { position: relative; flex: none; width: 64px; height: 64px; }
  .pfd-art img { width: 64px; height: 64px; border-radius: 8px; object-fit: cover; background: #111; display: block; }
  .pfd-art .pfd-badge { position: absolute; right: -4px; bottom: -4px; width: 22px; height: 22px; border-radius: 50%;
    border: 3px solid #232428; background: #111 center / cover no-repeat; }
  .pfd-lines { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 1px; font-size: 13px; }
  .pfd-lines .pfd-t { font-weight: 600; color: #f2f3f5; }
  .pfd-lines div { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .pfd-prog { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #b5bac1; margin-top: 4px; }
  .pfd-prog i { flex: 1; height: 4px; border-radius: 2px; background: #4e5058; overflow: hidden; }
  .pfd-prog i b { display: block; height: 100%; background: #f2f3f5; }
  .pfd-btn-preview { margin-top: 10px; text-align: center; font-size: 13px; font-weight: 500; padding: 6px; border-radius: 6px; background: #4e5058; color: #f2f3f5; }
  .pfd-list { font-size: 12px; color: #b5bac1; margin-top: 8px; }
  .pfd-none { font-size: 12.5px; color: var(--pf-muted, #b7afba); padding: 2px 0; }
  .pfd-row { display: flex; align-items: center; gap: 12px; padding: 6px 0; cursor: pointer; }
  .pfd-row .pfd-txt { flex: 1; min-width: 0; } .pfd-row small { display: block; font-size: 12px; color: var(--pf-muted, #b7afba); }
  .pfd-sw { position: relative; flex: none; width: 46px; height: 28px; }
  .pfd-sw input { position: absolute; inset: 0; opacity: 0; margin: 0; cursor: pointer; }
  .pfd-sw i { position: absolute; inset: 0; border-radius: 14px; border: 2px solid var(--pf-outline, #938f99); background: transparent; transition: background .2s, border-color .2s; }
  .pfd-sw i::after { content: ""; position: absolute; left: 4px; top: 50%; width: 14px; height: 14px; margin-top: -7px; border-radius: 50%;
    background: var(--pf-outline, #938f99); transition: transform .3s cubic-bezier(.34,1.3,.64,1), width .2s, height .2s, margin .2s, background .2s; }
  .pfd-sw input:checked + i { background: var(--pf-accent, #b69cff); border-color: var(--pf-accent, #b69cff); }
  .pfd-sw input:checked + i::after { transform: translateX(16px); width: 20px; height: 20px; margin-top: -10px; left: 2px; background: var(--pf-on-accent, #1d1430); }
  .pfd-sw input:disabled + i { opacity: .4; }
  .pfd-chips { display: flex; flex-wrap: wrap; gap: 6px; }
  .pfd-chip { height: 32px; padding: 0 12px; border-radius: 8px; border: 1px solid var(--pf-outline, #938f99); background: transparent; color: inherit;
    font: inherit; font-size: 13px; cursor: pointer; transition: border-radius .2s cubic-bezier(.2,0,0,1), background .2s; }
  .pfd-chip[aria-pressed=true] { border-radius: 16px; border-color: transparent; background: var(--pf-container, #4f378b); color: var(--pf-on-container, #eaddff); }
  .pfd-chip:disabled { opacity: .45; cursor: default; }
  .pfd-chip:active:not(:disabled) { transform: scale(.96); }
  .pfd-sec { font-size: 12px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: var(--pf-accent, #b69cff); margin-top: 6px; }
  .pfd-words { display: flex; flex-wrap: wrap; gap: 6px; }
  .pfd-word { display: inline-flex; align-items: center; gap: 4px; height: 28px; padding: 0 6px 0 10px; border-radius: 14px; background: var(--pf-surface2, #2b2930); font-size: 12.5px; }
  .pfd-word button { border: 0; background: none; color: inherit; cursor: pointer; font-size: 15px; line-height: 1; padding: 2px 4px; border-radius: 50%; }
  .pfd-add { display: flex; gap: 6px; }
  .pfd-add input { flex: 1; min-width: 0; height: 34px; border-radius: 12px; border: 1px solid var(--pf-outline, #938f99); background: transparent; color: inherit; padding: 0 10px; font: inherit; font-size: 13px; }
  .pfd-msg { font-size: 12.5px; color: #ff8a8a; min-height: 0; }
  .pfd-msg:empty { display: none; }
  `;

  function el(tag, attrs, ...kids) {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === "text") e.textContent = v;
      else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? "" : String(v));
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) e.append(kid.nodeType ? kid : String(kid));
    return e;
  }
  function ensureCss(doc) {
    if (doc.getElementById("pfd-css")) return;
    const s = doc.createElement("style");
    s.id = "pfd-css";
    s.textContent = CSS;
    (doc.head || doc.documentElement).append(s);
  }
  const clock = (ms) => { const s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };
  const safeImg = (u) => (typeof u === "string" && /^https:\/\//.test(u) ? u : "");

  // The card as Discord draws it in a profile, from the activity the app would send now (null: no card).
  function cardPreview(act, settings) {
    if (!act) return el("div", { class: "pfd-none", text: "No card right now: nothing would be shown on Discord at this moment." });
    const a = act.assets || {};
    const big = safeImg(a.large_image);
    const small = safeImg(a.small_image);
    const art = el("div", { class: "pfd-art" }, el("img", { alt: "", src: big || null }));
    if (small) { const b = el("i", { class: "pfd-badge" }); b.style.backgroundImage = `url("${small}")`; art.append(b); }
    const lines = el("div", { class: "pfd-lines" });
    if (act.details) lines.append(el("div", { class: "pfd-t", text: act.details }));
    if (act.state) lines.append(el("div", { text: act.state }));
    if (a.large_text && a.large_text !== act.details) lines.append(el("div", { text: a.large_text }));
    if (!act.details && !act.state) lines.append(el("div", { class: "pfd-t", text: "YouTube Music" }));
    const ts = act.timestamps || {};
    if (ts.start && ts.end && ts.end > ts.start) {
      const now = Date.now(), tot = ts.end - ts.start, done = Math.min(tot, Math.max(0, now - ts.start));
      const bar = el("i", {}, el("b", {}));
      bar.firstChild.style.width = (done / tot) * 100 + "%";
      lines.append(el("div", { class: "pfd-prog" }, el("span", { text: clock(done) }), bar, el("span", { text: clock(tot) })));
    }
    const card = el("div", { class: "pfd-card", "aria-label": "Preview of the Discord card" },
      el("div", { class: "pfd-h", text: "Listening to YouTube Music" }), el("div", { class: "pfd-b" }, art, lines));
    for (const b of act.buttons || []) card.append(el("div", { class: "pfd-btn-preview", text: b.label || "Listen" }));
    const shown = act.status_display_type === 1 ? (act.state || "YouTube Music") : act.status_display_type === 2 ? (act.details || "YouTube Music") : "YouTube Music";
    card.append(el("div", { class: "pfd-list", text: "In the member list: Listening to " + shown }));
    return card;
  }

  g.__starcastDiscordPanel = function (opts) {
    const ask = opts.ask;
    const doc = opts.document || document;
    ensureCss(doc);
    const root = el("div", { class: "pfd", "data-pf-dc": "" });
    const msg = el("div", { class: "pfd-msg", role: "status" });
    let st = null, busy = false, timer = 0, sig = "";
    // only what the panel shows: a redraw that changes nothing would swallow a tap that is under way
    const sigOf = (d) => d ? JSON.stringify([d.headline, d.visible, Math.ceil((d.snoozeSec || 0) / 60), d.settings, d.preview,
      d.beaconApp && d.beaconApp.running, d.discord && d.discord.user, d.reachable]) : "";

    async function refresh() {
      const r = await ask("discord");
      const before = sig + "|" + msg.textContent;
      if (r && r.discord) { st = r.discord; msg.textContent = ""; }
      else if (r && r.error) {
        st = null;
        msg.textContent = r.error === "old_app" ? "The app on this computer is too old for these options. Update YTM Starcast."
          : r.error === "offline" ? "YTM Starcast isn't answering on this computer." : "Couldn't ask YTM Starcast. Try again in a moment.";
      }
      sig = sigOf(st);
      if (sig + "|" + msg.textContent !== before) paint();
    }
    async function change(kind, body) {
      if (busy) return;
      busy = true; paint();
      const r = await ask("discordSet", { kind, body });
      busy = false;
      sig = "";   // draw again whatever comes back: the switches were greyed out while it was busy
      if (r && r.error) msg.textContent = r.error === "needs_press" ? "That didn't go through. Click or tap the option itself." : String(r.error);
      else msg.textContent = "";
      await refresh();
    }
    function sw(checked, disabled, onchange) {
      const input = el("input", { type: "checkbox", role: "switch" });
      input.checked = !!checked; input.disabled = !!disabled;
      input.addEventListener("change", () => onchange(input.checked));
      return el("span", { class: "pfd-sw" }, input, el("i", {}));
    }
    function row(label, sub, control) {
      return el("label", { class: "pfd-row" }, el("span", { class: "pfd-txt" }, label, sub ? el("small", { text: sub }) : null), control);
    }
    function chips(list, cur, disabled, pick) {
      return el("div", { class: "pfd-chips" }, list.map(([v, l]) => el("button", { class: "pfd-chip", type: "button", "aria-pressed": String(v === cur),
        disabled: disabled || null, onclick: () => { if (v !== cur) pick(v); } , text: l })));
    }
    function paint() {
      root.textContent = "";
      if (!st) { root.append(el("div", { class: "pfd-status" }, el("i", { class: "pfd-dot", "data-t": "wait" }), el("span", { text: "Asking YTM Starcast…" })), msg); return; }
      const s = st.settings || {};
      const oldApp = st.beaconApp && st.beaconApp.running;
      const off = busy || oldApp || st.reachable === false;
      const [txt, tone] = TEXT[st.headline] || ["Discord status isn't ready yet.", "wait"];
      root.append(el("div", { class: "pfd-status" }, el("i", { class: "pfd-dot", "data-t": tone }), el("span", { text: txt + (st.discord && st.discord.user ? " (as " + st.discord.user + ")" : "") })));
      root.append(cardPreview(st.preview || null, s));
      root.append(row("Show on Discord", null, sw(st.visible, off, (on) => change("visibility", { action: on ? "show" : "hide" }))));
      if (st.visible) {
        root.append(el("div", { class: "pfd-txt" }, el("small", { text: "Hide for a while (it comes back by itself)" })),
          chips([[30, "30 min"], [60, "1 hour"], [180, "3 hours"]], null, off, (m) => change("visibility", { action: "hide", minutes: m })));
      } else if (st.snoozeSec > 0) {
        const left = Math.max(1, Math.ceil(st.snoozeSec / 60));
        root.append(row("Hidden for " + left + " more min", null, el("button", { class: "pfd-chip", type: "button", disabled: off || null,
          onclick: () => change("visibility", { action: "show" }), text: "Show now" })));
      }
      root.append(el("div", { class: "pfd-sec", text: "What it shows" }));
      for (const [k, l, sub] of OPTIONS) root.append(row(l, sub, sw(s[k], off || (k !== "privateMode" && s.privateMode && k !== "showPaused"), (on) => change("settings", { [k]: on }))));
      if (s.showPaused) {
        root.append(el("div", { class: "pfd-txt" }, el("small", { text: "Hide a paused song after" })),
          chips(TIMEOUTS, Number(s.pauseTimeoutMinutes) || 0, off, (m) => change("settings", { pauseTimeoutMinutes: m })));
      }
      root.append(el("div", { class: "pfd-txt" }, el("small", { text: "In the member list, after “Listening to”" })),
        chips(NAMES, s.statusDisplay || "name", off || s.privateMode, (v) => change("settings", { statusDisplay: v })));
      root.append(el("div", { class: "pfd-sec", text: "Never show songs matching" }));
      const words = Array.isArray(s.hideMatching) ? s.hideMatching : [];
      const list = el("div", { class: "pfd-words" }, words.map((w) => el("span", { class: "pfd-word" }, w,
        el("button", { type: "button", "aria-label": "Remove " + w, disabled: off || null,
          onclick: () => change("settings", { hideMatching: words.filter((x) => x !== w) }), text: "×" }))));
      if (words.length) root.append(list);
      const input = el("input", { type: "text", maxlength: "80", placeholder: "A word, an artist, a title…", "aria-label": "A word to never show" });
      const add = () => { const v = input.value.trim(); if (v && !words.includes(v.toLowerCase())) change("settings", { hideMatching: [...words, v] }); };
      input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); add(); } });
      root.append(el("div", { class: "pfd-add" }, input, el("button", { class: "pfd-chip", type: "button", disabled: off || null, onclick: add, text: "Add" })));
      root.append(el("div", { class: "pfd-chips" }, el("button", { class: "pfd-chip", type: "button", disabled: off || null,
        onclick: () => change("test", {}), text: "Show a test song for 30 s" })));
      root.append(msg);
    }
    paint();
    refresh();
    // keeps the status and the preview current while it is open
    timer = setInterval(() => {
      if (!root.isConnected) { clearInterval(timer); return; }
      const typing = doc.activeElement && doc.activeElement.tagName === "INPUT" && root.contains(doc.activeElement);
      if (!busy && !typing) refresh();
    }, 4000);
    root.refresh = refresh;
    return root;
  };
})();
