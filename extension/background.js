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
