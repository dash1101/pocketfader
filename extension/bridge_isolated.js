/* Pocketfader — YouTube Music bridge (ISOLATED world).
 *
 * The page's CSP blocks the MAIN-world script from reaching http://localhost, so this
 * content script owns the server link (extension privilege via host_permissions) and
 * relays over window.postMessage:
 *   server  --GET /ext/poll (long-poll)-->  here  --postMessage {__vmr:cmd}-->  main world
 *   main world  --postMessage {__vmr:result|state}-->  here  --POST /ext/*-->  server
 *
 * No background service worker: the always-open YTM tab is the durable anchor.
 */
(() => {
  "use strict";
  const BASE = "http://127.0.0.1:8777";
  // This tab's name, so the server can follow one tab when several YouTube Music tabs are open.
  const INST = (crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)).slice(0, 36);

  // main world -> server
  window.addEventListener("message", (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || typeof d !== "object") return;
    if (d.__vmr === "result") {
      post("/ext/result", { id: d.id, data: d.data });
    } else if (d.__vmr === "state") {
      if (d.state && typeof d.state === "object") post("/ext/state", { ...d.state, instance: INST });
    } else if (d.__vmr === "lyrics" && typeof d.id === "string" && d.q && typeof d.q === "object") {
      lyrics(d.id.slice(0, 40), d.q);
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
      data = await r.json();
    } catch (e) {
      data = { error: "offline" };
    }
    window.postMessage({ __vmr: "lyrics-result", id, data }, "*");
  }

  function post(path, obj) {
    return fetch(BASE + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(obj),
    }).catch(() => {});
  }

  // server -> main world (long-poll loop, self-healing)
  async function loop() {
    for (;;) {
      try {
        const r = await fetch(BASE + "/ext/poll?i=" + encodeURIComponent(INST), { method: "GET" });
        const cmd = await r.json();
        if (cmd && cmd.id) window.postMessage({ __vmr: "cmd", command: cmd }, "*");
      } catch (e) {
        // server not up yet / restarting — back off and retry
        await new Promise((res) => setTimeout(res, 2000));
      }
    }
  }
  loop();
  console.log("[Pocketfader] YTM isolated bridge → " + BASE);
})();
