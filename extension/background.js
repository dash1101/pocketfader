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
