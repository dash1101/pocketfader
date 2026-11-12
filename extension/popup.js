// YTM Starcast's toolbar popup: is the app on this computer answering, what Discord status shows, and the three main switches.
// The popup never calls the app itself. It asks the extension's script in an open YouTube Music tab, which talks to the
// app the same way the rest of the extension does (and with the same origin).
"use strict";
const ext = globalThis.browser ?? globalThis.chrome;
const YTM = "https://music.youtube.com/";
const $ = (id) => document.getElementById(id);

// What the app says about Discord status, in plain words (the same wording as Refine's menu).
const DISCORD_TEXT = {
  showing: "Showing on your Discord profile.",
  waiting: "Getting ready to show…",
  idle: "Ready. Nothing is playing.",
  off: "Off. Nothing is shown on Discord.",
  snoozed: "Hidden for a while. It comes back by itself.",
  "paused-hidden": "Not shown while the music is paused.",
  "paused-timeout": "Hidden after a long pause. It comes back when you press play.",
  filtered: "Not shown: this song matches one of your never-show words.",
  ad: "Hidden while an advert plays.",
  "discord-down": "Can't reach Discord. Open the Discord app on this computer.",
  "extension-quiet": "Waiting to hear from YouTube Music.",
  "extension-missing": "Waiting to hear from YouTube Music.",
  "beacon-running": "The old YTM Beacon app is still running. Open YTM Starcast to move over from it.",
  unreachable: "Discord status isn't answering right now. Try again in a moment.",
};
const DISCORD_DEFAULT = $("discord").textContent;

let lastTab = null;          // the tab that answered last time is asked first
let parts = null;            // the switches as the app last said (null: not known, or an older app = all on)
let ok = false;              // the switches can be changed right now
let busy = false;            // a change is on its way
let timer = 0;

// ------------------------------------------------------------------ talking to a YouTube Music tab
async function ytmTabs() {
  let tabs = [];
  try { tabs = await ext.tabs.query({ url: YTM + "*" }); } catch (e) {}
  // a tab's address is only visible with access to YouTube Music, so a tab without one is not ours to ask
  tabs = (tabs || []).filter((t) => typeof t.url === "string" && t.url.startsWith(YTM));
  const score = (t) => (t.id === lastTab ? 8 : 0) + (t.audible ? 4 : 0) + (t.active ? 2 : 0);
  return tabs.sort((a, b) => score(b) - score(a) || (b.lastAccessed || 0) - (a.lastAccessed || 0));
}
function limit(p, ms) {
  return Promise.race([p, new Promise((_, no) => setTimeout(() => no(new Error("timeout")), ms))]);
}
async function allowed() {
  try { return await ext.permissions.contains({ origins: [YTM + "*"] }); } catch (e) { return true; }
}
// Ask the first YouTube Music tab where YTM Starcast's script answers.
async function ask(msg) {
  const tabs = await ytmTabs();
  if (!tabs.length) return (await allowed()) ? { noTab: true } : { noAccess: true };
  for (const t of tabs) {
    try {
      const r = await limit(ext.tabs.sendMessage(t.id, msg), 9000);
      if (r && typeof r === "object") { lastTab = t.id; return r; }
    } catch (e) {
      // no YTM Starcast script in that tab: it was open before YTM Starcast was added or updated
    }
  }
  return { noScript: true, tab: tabs[0].id };
}

// ------------------------------------------------------------------ drawing
function setState(tone, head, detail) {
  $("dot").className = "dot " + tone;
  $("headline").textContent = head;
  $("detail").textContent = detail || "";
  $("detail").hidden = !detail;
}
function setAction(label, fn) {
  const b = $("act");
  b.hidden = !label;
  b.disabled = false;
  b.textContent = label || "";
  b.onclick = fn || null;
}
function paintParts() {
  for (const input of document.querySelectorAll("input[data-part]")) {
    input.disabled = busy || !ok;
    input.checked = parts ? parts[input.dataset.part] !== false : true;
  }
}
function show(r) {
  const s = r.status && typeof r.status === "object" ? r.status : null;
  const version = s && typeof s.version === "string" ? s.version.slice(0, 24) : "";
  $("version").textContent = version;
  $("version").hidden = !version;
  setAction(null);
  let showParts = false;
  if (s) {
    parts = s.parts && typeof s.parts === "object" ? s.parts : null;
    ok = !!parts;
    showParts = true;
    setState("ok", "YTM Starcast is running", ok ? null : "This version can't switch parts from here.");
    if (r.twin) {
      setState("warn", "Two copies of the extension are loaded", "Keep only one: remove the older one (Pocketfader or " +
        "YTM Starcast) on the browser's extensions page, then reload YouTube Music.");
    }
    const dc = s.discord && typeof s.discord === "object" ? s.discord : null;
    $("discord").textContent = (dc && DISCORD_TEXT[dc.headline]) || DISCORD_DEFAULT;
  } else {
    ok = false;
    $("discord").textContent = DISCORD_DEFAULT;
    if (r.noTab) {
      setState("idle", "Open YouTube Music", "YTM Starcast reaches the app on this computer through a YouTube Music tab in this browser.");
      setAction("Open YouTube Music", () => { Promise.resolve(ext.tabs.create({ url: YTM })).catch(() => {}); });
    } else if (r.noAccess) {
      setState("warn", "Allow YTM Starcast on YouTube Music", "The browser hasn't let YTM Starcast into YouTube Music yet, so it can't connect.");
      setAction("Allow", () => {
        // asked straight from the click: browsers only show this question in answer to one
        let asked;
        try { asked = ext.permissions.request({ origins: [YTM + "*"] }); } catch (e) {}
        Promise.resolve(asked).catch(() => {}).finally(() => refresh());
      });
    } else if (r.noScript) {
      setState("warn", "Reload YouTube Music", "Your YouTube Music tab was open before YTM Starcast was added or updated. Reload it to connect it (the song starts again).");
      setAction("Reload the tab", () => {
        $("act").disabled = true;
        Promise.resolve(ext.tabs.reload(r.tab)).catch(() => {});
        clearTimeout(timer);
        timer = setTimeout(refresh, 3000);
      });
    } else if (r.error === "old_app") {
      parts = null;   // an app from before these switches has everything on
      showParts = true;
      const v = r.app && typeof r.app.version === "string" && r.app.version ? " (" + r.app.version + ")" : "";
      setState("warn", "An older app is running", "The app on this computer is an older version" + v +
        ". Update it to YTM Starcast to switch parts on and off here. Until then everything stays on.");
    } else if (r.error === "offline") {
      setState("bad", "YTM Starcast isn't answering", "Start YTM Starcast on this computer, then open this again.");
    } else {
      setState("bad", "YTM Starcast didn't answer properly", "Try again in a moment.");
    }
  }
  $("parts").hidden = !showParts;
  $("dc").hidden = !(s && ok);
  paintParts();
}

// ------------------------------------------------------------------ live
let gen = 0;                 // an answer that arrives after a newer question (or a change) is dropped
async function refresh() {
  clearTimeout(timer);
  const mine = ++gen;
  const r = await ask({ starcast: "status" });
  if (mine !== gen || busy) return;
  show(r);
  clearTimeout(timer);
  timer = setTimeout(refresh, 4000);
}
for (const input of document.querySelectorAll("input[data-part]")) {
  input.addEventListener("change", async () => {
    busy = true;
    gen++;
    clearTimeout(timer);
    for (const other of document.querySelectorAll("input[data-part]")) other.disabled = true;
    const r = await ask({ starcast: "parts", parts: { [input.dataset.part]: input.checked } });
    busy = false;
    if (r.parts && typeof r.parts === "object") {
      parts = r.parts;
      ok = true;
      paintParts();
      timer = setTimeout(refresh, 900);   // the Discord line catches up a moment later
    } else if (r.error === "failed" || r.error === "bad_request") {
      setState("bad", "That change didn't go through", "Try again in a moment.");
      paintParts();                       // the switch flips back to what the app last said
      timer = setTimeout(refresh, 4000);
    } else {
      show(r);                            // the app or the tab went away: say so
      timer = setTimeout(refresh, 4000);
    }
  });
}
// Discord status options: built when first opened, through the same YouTube Music tab as the switches
const dc = $("dc");
dc.addEventListener("toggle", () => {
  if (!dc.open || $("dcPanel").firstChild || !globalThis.__starcastDiscordPanel) return;
  $("dcPanel").append(globalThis.__starcastDiscordPanel({
    ask: (op, extra) => ask({ starcast: op, ...(extra || {}) }).then((r) =>
      r && (r.noTab || r.noAccess || r.noScript) ? { error: "offline" } : r),
  }));
});
refresh();
