/*
 * YTM Starcast extension: the keyboard shortcut (Alt+Shift+M by default, changeable on the browser's shortcuts page)
 * that shows or stops showing your music on Discord, as YTM Beacon's did. It asks the app through an open YouTube Music
 * tab, the same way as the toolbar popup.
 */
const ext = globalThis.browser ?? globalThis.chrome;
const YTM = "https://music.youtube.com/";

async function ask(msg) {
  let tabs = [];
  try { tabs = await ext.tabs.query({ url: YTM + "*" }); } catch (e) {}
  tabs = (tabs || []).filter((t) => typeof t.url === "string" && t.url.startsWith(YTM));
  tabs.sort((a, b) => (b.audible ? 1 : 0) - (a.audible ? 1 : 0) || (b.lastAccessed || 0) - (a.lastAccessed || 0));
  for (const t of tabs) {
    try {
      const r = await ext.tabs.sendMessage(t.id, msg);
      if (r && typeof r === "object") return r;
    } catch (e) {
      // that tab has no YTM Starcast script (it was open before the extension was added or updated)
    }
  }
  return null;
}

function badge(text) {
  try {
    ext.action.setBadgeBackgroundColor({ color: "#8b5cf6" });
    ext.action.setBadgeText({ text });
    setTimeout(() => ext.action.setBadgeText({ text: "" }), 2500);
  } catch (e) {}
}

ext.commands.onCommand.addListener(async (command) => {
  if (command !== "toggle-discord") return;
  const r = await ask({ starcast: "discord" });
  if (!r || !r.discord) { badge("?"); return; }
  const showing = !!r.discord.visible;
  const s = await ask({ starcast: "discordSet", kind: "visibility", body: { action: showing ? "hide" : "show" } });
  badge(s && s.ok ? (showing ? "off" : "on") : "!");
});

// A YouTube Music tab asks to be brought to the front (YTM Starcast's "focus" and "settings" commands, from Orbit or the
// apps): that tab becomes the selected one, and its window comes forward.
ext.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg || msg.starcast !== "focusTab" || !sender || !sender.tab || sender.id !== ext.runtime.id) return;
  const tab = sender.tab;
  Promise.all([ext.tabs.update(tab.id, { active: true }), ext.windows.update(tab.windowId, { focused: true })])
    .then(() => reply({ ok: true }), () => reply({ ok: false }));
  return true;
});

// The extension's requests to YTM Starcast on this computer are made here, not in the YouTube Music page: a website (and
// the extension's scripts inside it) needs the browser's "apps and services on this device" permission to reach the
// computer, which people are asked for and may not give; the extension itself has the right through its host permission.
// Only YTM Starcast's own routes, only to the app on this computer.
const BASE = "http://127.0.0.1:8777";
const ROUTE = /^\/(?:ext\/[a-z/]+|api\/info)(?:\?[^#]*)?$/;
ext.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg || msg.starcast !== "http" || !sender || sender.id !== ext.runtime.id) return;
  if (typeof msg.path !== "string" || !ROUTE.test(msg.path)) { reply({ error: "bad_path" }); return; }
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), Math.min(Math.max(Number(msg.ms) || 10000, 1000), 60000));
  const init = { method: msg.method === "POST" ? "POST" : "GET", signal: ctl.signal };
  if (init.method === "POST") {
    init.headers = { "Content-Type": "application/json" };
    init.body = typeof msg.body === "string" ? msg.body : "";
  }
  fetch(BASE + msg.path, init)
    .then(async (r) => reply({ status: r.status, ok: r.ok, text: await r.text() }), () => reply({ error: "offline" }))
    .finally(() => clearTimeout(timer));
  return true;
});

// ------------------------------------------------------------------ updating in place
// YTM Starcast copies the new extension into its folder each time it is updated. A browser that loads YTM Starcast from
// that folder ("Load unpacked") runs the new version once the extension reloads, which happens here: by itself when no
// YouTube Music tab is open (a reload cuts an open tab's scripts off), or from "Update now" in the toolbar popup, which
// reloads the YouTube Music tabs as well. One try per version: a browser that loads YTM Starcast from a folder the app
// doesn't update would otherwise reload again and again.
const RUNNING = ext.runtime.getManifest().version;

function newer(a, b) {
  const pa = String(a).split(".").map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d > 0;
  }
  return false;
}

async function ytmTabIds() {
  let tabs = [];
  try { tabs = await ext.tabs.query({ url: YTM + "*" }); } catch (e) {}
  return (tabs || []).filter((t) => typeof t.url === "string" && t.url.startsWith(YTM)).map((t) => t.id);
}

/** The version in YTM Starcast's folder when it is newer than this one, else null. */
async function readyVersion() {
  try {
    const r = await fetch(BASE + "/api/info", { cache: "no-store" });
    const info = await r.json();
    const v = info && typeof info.extension === "string" ? info.extension : null;
    return v && /^\d+(\.\d+){0,3}$/.test(v) && newer(v, RUNNING) ? v : null;
  } catch (e) {
    return null;            // the app isn't running: nothing to compare with
  }
}

function markReady(v) {
  try {
    ext.action.setBadgeBackgroundColor({ color: "#8b5cf6" });
    ext.action.setBadgeText({ text: v ? "↑" : "" });
    ext.action.setTitle({ title: v ? "YTM Starcast: version " + v + " is ready. Open this to update." : "YTM Starcast" });
  } catch (e) {}
}

async function reloadInto(v, tabIds) {
  try { await ext.storage.local.set({ reloadedFor: v, reloadTabs: tabIds }); } catch (e) {}
  ext.runtime.reload();
}

/** -> {ready, tried}: the newer version waiting (or null), and whether reloading for it was tried already. [auto]: reload
 *  by itself when nothing would be cut off. */
async function checkUpdate(auto) {
  const v = await readyVersion();
  markReady(v);
  if (!v) return { ready: null, tried: false };
  let st = {};
  try { st = await ext.storage.local.get(["reloadedFor"]); } catch (e) {}
  const tried = st.reloadedFor === v;
  if (auto && !tried && !(await ytmTabIds()).length) await reloadInto(v, []);
  return { ready: v, tried };
}

ext.runtime.onInstalled.addListener(async () => {
  // just reloaded by "Update now": the YouTube Music tabs it cut off get the new scripts
  let st = {};
  try { st = await ext.storage.local.get(["reloadTabs"]); } catch (e) {}
  const ids = Array.isArray(st.reloadTabs) ? st.reloadTabs : [];
  if (ids.length) {
    try { await ext.storage.local.remove("reloadTabs"); } catch (e) {}
    for (const id of ids) { try { await ext.tabs.reload(id); } catch (e) {} }
  }
  checkUpdate(true);
});
ext.runtime.onStartup.addListener(() => { checkUpdate(true); });
try {
  ext.alarms.create("starcast-update", { periodInMinutes: 30 });
  ext.alarms.onAlarm.addListener((a) => { if (a.name === "starcast-update") checkUpdate(true); });
} catch (e) {}

ext.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg || !sender || sender.id !== ext.runtime.id) return;
  if (msg.starcast === "updateState") {
    checkUpdate(false).then((r) => reply({ running: RUNNING, ...r }), () => reply({ running: RUNNING, ready: null }));
    return true;
  }
  if (msg.starcast === "updateNow") {
    readyVersion().then(async (v) => {
      reply({ ok: !!v });
      if (v) await reloadInto(v, await ytmTabIds());
    });
    return true;
  }
});
