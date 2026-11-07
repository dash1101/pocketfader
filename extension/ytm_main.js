/* Pocketfader — YouTube Music bridge (MAIN world).
 *
 * Runs in the PAGE context so it can call music.youtube.com internals directly:
 *   - #movie_player (transport, now-playing)
 *   - ytmusic-app.resolveCommand (play a song, start a mix/playlist)
 *   - same-origin /youtubei/v1/* fetches (search, home, library)
 * It CANNOT talk to the local server (page CSP blocks localhost); the ISOLATED
 * content script does that and relays commands here via window.postMessage.
 *
 * Every primitive here was verified live against a logged-in tab first
 * (see docs/ytm-control-probe.md).
 */
(() => {
  "use strict";
  const mp = () => document.querySelector("#movie_player");
  const app = () => document.querySelector("ytmusic-app");
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------------------------------------------------------------- InnerTube
  async function sha1hex(str) {
    const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(str));
    return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }
  function cookie(name) {
    const m = document.cookie.match(new RegExp("(?:^|; )" + name + "=([^;]+)"));
    return m ? decodeURIComponent(m[1]) : null;
  }
  // Library endpoints (playlists) need a SAPISIDHASH header; search/home do not.
  async function authHeaders() {
    const sapisid =
      cookie("SAPISID") || cookie("__Secure-3PAPISID") || cookie("__Secure-1PAPISID");
    if (!sapisid) return {};
    const origin = "https://music.youtube.com";
    const ts = Math.floor(Date.now() / 1000);
    const hash = await sha1hex(`${ts} ${sapisid} ${origin}`);
    return { Authorization: `SAPISIDHASH ${ts}_${hash}`, "X-Origin": origin, "X-Goog-AuthUser": "0" };
  }
  async function innertube(endpoint, extraBody, auth) {
    const key = window.ytcfg.get("INNERTUBE_API_KEY");
    const ctx = window.ytcfg.get("INNERTUBE_CONTEXT");
    const headers = { "Content-Type": "application/json" };
    if (auth) Object.assign(headers, await authHeaders());
    const r = await fetch(`/youtubei/v1/${endpoint}?key=${key}&prettyPrint=false`, {
      method: "POST",
      credentials: "include",
      headers,
      body: JSON.stringify({ context: ctx, ...extraBody }),
    });
    return r.json();
  }

  // ------------------------------------------------------------ parse helpers
  const runText = (o) => o?.runs?.map((r) => r.text).join("") || o?.simpleText || null;
  // The player-bar <img> is a tiny (~60px) upscaled thumb. Google image URLs carry a size
  // suffix (=w60-h60… or =s60…) we can rewrite to pull the real high-res album cover.
  function bigArt(url) {
    if (!url || !/googleusercontent|ytimg|ggpht|lh\d/.test(url)) return null;
    return url.replace(/=w\d+-h\d+/, "=w544-h544").replace(/=s\d+/, "=s544");
  }
  function pickThumb(thumbObj, minW = 160) {
    const arr =
      thumbObj?.thumbnails ||
      thumbObj?.musicThumbnailRenderer?.thumbnail?.thumbnails ||
      thumbObj?.croppedSquareThumbnailRenderer?.thumbnail?.thumbnails ||
      [];
    if (!arr.length) return null;
    const sorted = [...arr].sort((a, b) => (a.width || 0) - (b.width || 0));
    const chosen = sorted.find((t) => (t.width || 0) >= minW) || sorted[sorted.length - 1];
    return chosen.url || null;
  }
  // YTM nests renderers deeply (carousels ~10 levels, search rows deeper), so the depth
  // cap has to be generous — 8 was too shallow and silently found nothing.
  function deepFind(obj, key, out, depth = 0) {
    if (!obj || typeof obj !== "object" || depth > 24) return out;
    if (Array.isArray(obj)) {
      for (const v of obj) deepFind(v, key, out, depth + 1);
    } else {
      for (const k in obj) {
        if (k === key) out.push(obj[k]);
        else deepFind(obj[k], key, out, depth + 1);
      }
    }
    return out;
  }

  // A responsive-list item (search rows, playlist tracks) or two-row item (cards).
  function extractItem(r) {
    if (!r) return null;
    const nav = r.navigationEndpoint || r.title?.runs?.[0]?.navigationEndpoint;
    const flex = r.flexColumns;
    const title =
      runText(r.title) ||
      runText(flex?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text);
    if (!title) return null;
    const subtitle =
      runText(r.subtitle) ||
      runText(flex?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text);
    const playNav =
      r.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
        ?.playNavigationEndpoint;
    const videoId =
      r.playlistItemData?.videoId ||
      nav?.watchEndpoint?.videoId ||
      playNav?.watchEndpoint?.videoId ||
      null;
    const browseId = nav?.browseEndpoint?.browseId || null;
    const playlistId =
      nav?.watchEndpoint?.playlistId ||
      playNav?.watchEndpoint?.playlistId ||
      (browseId && browseId.startsWith("VL") ? browseId.slice(2) : null);
    const thumb = pickThumb(
      r.thumbnail?.musicThumbnailRenderer?.thumbnail ||
        r.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail ||
        r.thumbnail
    );
    let kind = "unknown";
    if (videoId) kind = "song";
    else if (playlistId) kind = "playlist";
    else if (browseId) kind = browseId.startsWith("MPRE") ? "album" : "browse";
    return { title, subtitle, thumb, videoId, browseId, playlistId, kind };
  }

  // ------------------------------------------------------------------ readers
  function nowPlaying() {
    const p = mp();
    if (!p || !p.getVideoData) return { playing: false, paused: false, videoId: null };
    const vd = p.getVideoData() || {};
    const ps = p.getProgressState ? p.getProgressState() : {};
    const state = p.getPlayerState ? p.getPlayerState() : -1;
    const img = document.querySelector(
      "ytmusic-player-bar img.image, ytmusic-player-bar .thumbnail img, .middle-controls img"
    );
    return {
      playing: state === 1,
      paused: state === 2,
      buffering: state === 3,
      videoId: vd.video_id || null,
      title: vd.title || null,
      artist: vd.author || null,
      art: bigArt(img && img.src),
      position: ps.currentTime ?? (p.getCurrentTime ? p.getCurrentTime() : 0),
      duration: ps.duration ?? (p.getDuration ? p.getDuration() : 0),
      volume: p.getVolume ? p.getVolume() : null,   // YTM's own 0-100, independent of Windows
      liked: likeStatus(),                          // 'LIKE' | 'DISLIKE' | 'INDIFFERENT' | null
    };
  }
  // Read the current song's like state from the player-bar like button.
  function likeStatus() {
    const lb = document.querySelector("ytmusic-player-bar ytmusic-like-button-renderer");
    if (!lb) return null;
    const s = lb.getAttribute("like-status") || (lb.data && lb.data.likeStatus);
    if (s) return s;
    // fallback: the thumbs-up control's pressed state
    const up = lb.querySelector('button[aria-pressed]');
    if (up) return up.getAttribute("aria-pressed") === "true" ? "LIKE" : "INDIFFERENT";
    return null;
  }
  function readQueue() {
    const items = [];
    const scan = (root) =>
      root.querySelectorAll &&
      root.querySelectorAll("ytmusic-player-queue-item").forEach((i) => items.push(i));
    scan(document);
    document.querySelectorAll("*").forEach((el) => el.shadowRoot && scan(el.shadowRoot));
    const seen = new Set();
    const out = [];
    for (const it of items) {
      const d = it.data || {};
      if (!d.videoId || seen.has(it)) continue;
      seen.add(it);
      out.push({
        videoId: d.videoId,
        title: runText(d.title),
        artist: runText(d.longBylineText) || runText(d.shortBylineText),
        art: pickThumb(d.thumbnail),
        selected: it.hasAttribute && it.hasAttribute("selected"),
      });
    }
    return out;
  }

  async function search(query) {
    const j = await innertube("search", { query });
    const rows = deepFind(j, "musicResponsiveListItemRenderer", []);
    const cards = deepFind(j, "musicTwoRowItemRenderer", []);
    const items = [...rows, ...cards].map(extractItem).filter(Boolean);
    // de-dupe by videoId/playlistId/browseId
    const seen = new Set();
    return items.filter((it) => {
      const id = it.videoId || it.playlistId || it.browseId || it.title;
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    });
  }
  // The sections worth having (Speed dial / Quick picks / Listen again) load via
  // continuations, not in the first browse — so follow a few hops, then float those to
  // the top ahead of the generic genre shelves.
  const HOME_PRIORITY = ["speed dial", "quick picks", "listen again", "mixed for you",
    "forgotten favorites", "from your library", "your favorites"];
  function contToken(j) {
    const nc = deepFind(j, "nextContinuationData", []);
    if (nc.length && nc[0].continuation) return nc[0].continuation;
    const cc = deepFind(j, "continuationCommand", []);
    return cc.length && cc[0].token ? cc[0].token : null;
  }
  async function home() {
    const sections = [];
    let token = null, hops = 0;
    do {
      // MUST be authenticated (SAPISIDHASH) — cookie-only returns generic, unpersonalized
      // shelves ("Trending…") instead of the user's real Listen again / Quick picks.
      const j = await innertube("browse", token ? { continuation: token } : { browseId: "FEmusic_home" }, true);
      for (const c of deepFind(j, "musicCarouselShelfRenderer", [])) {
        const title = runText(c.header?.musicCarouselShelfBasicHeaderRenderer?.title) || "More";
        const items = (c.contents || [])
          .map((x) => extractItem(x.musicTwoRowItemRenderer || x.musicResponsiveListItemRenderer))
          .filter(Boolean);
        if (items.length) sections.push({ title, items });
      }
      token = contToken(j);
      hops++;
    } while (token && hops < 6);
    const seen = new Set();
    const uniq = sections.filter((s) => (seen.has(s.title) ? false : seen.add(s.title)));
    const rank = (t) => { const i = HOME_PRIORITY.findIndex((p) => t.toLowerCase().includes(p)); return i < 0 ? 99 : i; };
    return uniq.map((s, i) => ({ s, i })).sort((a, b) => rank(a.s.title) - rank(b.s.title) || a.i - b.i).map((x) => x.s);
  }
  async function searchSuggestions(input) {
    if (!input || input.length < 1) return [];
    const j = await innertube("music/get_search_suggestions", { input });
    const out = [];
    for (const r of deepFind(j, "searchSuggestionRenderer", [])) {
      const text = r.navigationEndpoint?.searchEndpoint?.query || runText(r.suggestion);
      if (text) out.push(text);
    }
    return [...new Set(out)].slice(0, 8);
  }
  async function playlists() {
    const j = await innertube("browse", { browseId: "FEmusic_liked_playlists" }, true);
    const cards = deepFind(j, "musicTwoRowItemRenderer", []);
    return cards
      .map(extractItem)
      .filter((it) => it && (it.playlistId || it.browseId));
  }
  async function playlistItems(browseIdOrPl) {
    let browseId = browseIdOrPl;
    if (!browseId.startsWith("VL") && !browseId.startsWith("MPRE")) browseId = "VL" + browseId;
    const j = await innertube("browse", { browseId }, true);
    const rows = deepFind(j, "musicResponsiveListItemRenderer", []);
    return rows.map(extractItem).filter((it) => it && it.videoId);
  }

  // ------------------------------------------------------------------ actions
  function transport(action, position) {
    const p = mp();
    if (!p) return { ok: false, error: "no_player" };
    switch (action) {
      case "play": p.playVideo(); break;
      case "pause": p.pauseVideo(); break;
      case "playpause":
        p.getPlayerState() === 1 ? p.pauseVideo() : p.playVideo();
        break;
      case "next": p.nextVideo(); break;
      case "prev": p.previousVideo(); break;
      case "seek": p.seekTo(Number(position) || 0, true); break;
      default: return { ok: false, error: "unknown_action" };
    }
    return { ok: true };
  }
  function play(videoId, playlistId) {
    const ep = { watchEndpoint: { videoId } };
    if (playlistId) ep.watchEndpoint.playlistId = playlistId;
    app().resolveCommand(ep);
    return { ok: true };
  }
  function startPlaylist(playlistId, shuffle) {
    // Shuffle uses the same endpoint with a known shuffle params token.
    const ep = shuffle
      ? { watchPlaylistEndpoint: { playlistId, params: "wAEB8gECKAE%3D" } }
      : { watchPlaylistEndpoint: { playlistId } };
    app().resolveCommand(ep);
    return { ok: true, shuffle: !!shuffle };
  }

  // Row lookup + menu-simulation. resolveCommand(queueAddEndpoint) silently no-ops
  // (verified), so we click the real menu item, which is proven to insert.
  function findRowByVideoId(videoId) {
    return [...document.querySelectorAll("ytmusic-responsive-list-item-renderer")].find(
      (r) => r.data?.playlistItemData?.videoId === videoId
    );
  }
  async function ensureRows(videoId, query, timeoutMs = 5000) {
    if (findRowByVideoId(videoId)) return true;
    app().resolveCommand({ searchEndpoint: { query: query || videoId } });
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      await sleep(250);
      if (findRowByVideoId(videoId)) return true;
    }
    return !!findRowByVideoId(videoId);
  }
  async function queueAdd(videoId, query, mode) {
    const label = mode === "next" ? "Play next" : "Add to queue";
    if (!(await ensureRows(videoId, query))) return { ok: false, error: "row_not_found" };
    const row = findRowByVideoId(videoId);
    const btn = row.querySelector(
      "ytmusic-menu-renderer button, ytmusic-menu-renderer tp-yt-paper-icon-button"
    );
    if (!btn) return { ok: false, error: "no_menu_button" };
    btn.click();
    await sleep(450);
    const item = [...document.querySelectorAll("ytmusic-menu-service-item-renderer")].find(
      (it) => it.textContent.trim().startsWith(label)
    );
    if (!item) {
      document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      return { ok: false, error: "menu_item_missing" };
    }
    item.click();
    await sleep(250);
    return { ok: true, mode: mode === "next" ? "next" : "end" };
  }
  /* --- per-queue-item actions -----------------------------------------------------------
     A queue row only stamps its "Action menu" when it's the current track, so the menu you
     need for any other row simply doesn't exist in the DOM — and synthetic hover can't
     create it (untrusted events). The way in: force that row's <dom-if> templates to stamp,
     which renders its real menu, then click the real menu item.
     Verified live: Remove took the queue 51 → 50 with the target gone; Play next moved a
     row from index 8 → 1 (straight after the current track). */
  function queueItems() {
    const out = [];
    const scan = (root) => root.querySelectorAll &&
      root.querySelectorAll("ytmusic-player-queue-item").forEach((i) => out.push(i));
    scan(document);
    document.querySelectorAll("*").forEach((el) => el.shadowRoot && scan(el.shadowRoot));
    return out;
  }
  async function queueItemMenu(videoId, label) {
    const row = queueItems().find((i) => i.data && i.data.videoId === videoId);
    if (!row) return { ok: false, error: "not_in_queue" };
    // stamp the row's hidden templates so its real action menu exists
    [...row.querySelectorAll("dom-if")].forEach((d) => {
      try { d.if = true; if (d._render) d._render(); } catch (e) {}
    });
    await sleep(450);
    let btn = null;
    const walk = (r) => {
      if (btn || !r.querySelectorAll) return;
      r.querySelectorAll("button, tp-yt-paper-icon-button").forEach((b) => {
        if (!btn && /action menu/i.test(b.getAttribute("aria-label") || "")) btn = b;
      });
      r.querySelectorAll("*").forEach((el) => el.shadowRoot && walk(el.shadowRoot));
    };
    walk(row);
    if (!btn) return { ok: false, error: "no_menu" };
    btn.click();
    await sleep(500);
    const item = [...document.querySelectorAll("ytmusic-menu-service-item-renderer")]
      .find((x) => (x.textContent || "").trim().startsWith(label));
    if (!item) {
      document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
      return { ok: false, error: "menu_item_missing" };
    }
    item.click();
    await sleep(600);
    return { ok: true };
  }

  /* Clears the queue by driving YTM's own queue Action menu → "Dismiss queue".
     Verified live: queue went 51 → 0. */
  async function dismissQueue() {
    const q = document.querySelector("ytmusic-player-queue");
    if (!q) return false;
    let btn = null;
    const walk = (root) => {
      if (btn || !root.querySelectorAll) return;
      root.querySelectorAll("button").forEach((b) => {
        if (!btn && (b.getAttribute("aria-label") || "") === "Action menu") btn = b;
      });
      root.querySelectorAll("*").forEach((el) => el.shadowRoot && walk(el.shadowRoot));
    };
    walk(q);
    if (!btn) return false;
    btn.click();
    await sleep(500);
    const items = [...document.querySelectorAll(
      "ytmusic-menu-service-item-renderer, ytmusic-menu-navigation-item-renderer")];
    const dis = items.find((i) => /dismiss queue|clear queue/i.test(i.textContent || ""));
    if (!dis) { document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" })); return false; }
    dis.click();
    await sleep(400);
    return true;
  }

  function startMix(videoId) {
    // A bare watchEndpoint auto-starts the song's radio (RDAMVM…) — that IS "start mix".
    app().resolveCommand({ watchEndpoint: { videoId } });
    return { ok: true };
  }

  // -------------------------------------------------------------- dispatch
  async function handle(cmd) {
    try {
      switch (cmd.type) {
        case "transport": return transport(cmd.action, cmd.position);
        case "play": return play(cmd.videoId, cmd.playlistId);
        case "startMix": return startMix(cmd.videoId);
        case "startPlaylist": return startPlaylist(cmd.playlistId, cmd.shuffle);
        case "queueAdd": return await queueAdd(cmd.videoId, cmd.query, cmd.mode);
        case "stop": {
          // Pause/stop, then empty the queue. player.clearQueue() reports success but does
          // nothing (same false-success as the other YTM APIs) — the queue's own "Action
          // menu" → "Dismiss queue" is the only thing that actually works, and unlike the
          // per-item menus that button IS rendered, so we can click it.
          const p = mp(); if (!p) return { ok: false, error: "no_player" };
          try { p.pauseVideo(); } catch (e) {}
          try { if (p.stopVideo) p.stopVideo(); } catch (e) {}
          const cleared = await dismissQueue();
          return { ok: true, queueCleared: cleared };
        }
        case "queueRemove": return await queueItemMenu(cmd.videoId, "Remove from queue");
        case "queueMoveNext": return await queueItemMenu(cmd.videoId, "Play next");
        case "queueClear": return { ok: await dismissQueue() };
        case "search": return { items: await search(cmd.query) };
        case "suggest": return { suggestions: await searchSuggestions(cmd.query) };
        case "like": {
          // The InnerTube like endpoint returns 200 but doesn't actually register, so for the
          // currently-playing song we click the real player-bar like/dislike button (proven to work).
          const cur = mp() && mp().getVideoData && mp().getVideoData().video_id;
          const lb = document.querySelector("ytmusic-player-bar ytmusic-like-button-renderer");
          if (lb && (!cmd.videoId || cmd.videoId === cur)) {
            const status = () => lb.getAttribute("like-status") || (lb.data && lb.data.likeStatus) || "INDIFFERENT";
            const btns = lb.querySelectorAll("button");   // [0] = like, [1] = dislike
            const likeBtn = btns[0], dislikeBtn = btns[1];
            const want = cmd.action === "dislike" ? "DISLIKE" : cmd.action === "remove" ? "INDIFFERENT" : "LIKE";
            const now = status();
            if (want === "LIKE" && now !== "LIKE") likeBtn && likeBtn.click();
            else if (want === "INDIFFERENT" && now === "LIKE") likeBtn && likeBtn.click();
            else if (want === "INDIFFERENT" && now === "DISLIKE") dislikeBtn && dislikeBtn.click();
            else if (want === "DISLIKE" && now !== "DISLIKE") dislikeBtn && dislikeBtn.click();
            return { ok: true, liked: status() };
          }
          return { ok: false, error: "no_like_button" };
        }
        case "setVolume": {
          const p = mp(); if (!p || !p.setVolume) return { ok: false, error: "no_player" };
          const lvl = Math.max(0, Math.min(100, Math.round(Number(cmd.level) || 0)));
          if (lvl === 0) p.mute(); else if (p.isMuted && p.isMuted()) p.unMute();
          p.setVolume(lvl);
          return { ok: true, volume: lvl };
        }
        case "home": return { sections: await home() };
        case "playlists": return { playlists: await playlists() };
        case "playlistItems": return { items: await playlistItems(cmd.playlistId || cmd.browseId) };
        case "nowplaying": return { now: nowPlaying(), queue: readQueue() };
        default: return { ok: false, error: "unknown_type", type: cmd.type };
      }
    } catch (e) {
      return { ok: false, error: String(e && e.message ? e.message : e) };
    }
  }

  // -------------------------------------------------------- bridge messaging
  window.addEventListener("message", async (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || d.__vmr !== "cmd") return;
    const data = await handle(d.command);
    window.postMessage({ __vmr: "result", id: d.command.id, data }, "*");
  });

  // Push now-playing + queue on a heartbeat so connected clients stay live.
  let last = "";
  setInterval(() => {
    const state = { now: nowPlaying(), queue: readQueue(), ts: Date.now() };
    const sig = JSON.stringify([state.now, state.queue.map((q) => q.videoId), Math.round(state.now.position)]);
    if (sig === last) return; // only push on change (position rounds to whole seconds)
    last = sig;
    window.postMessage({ __vmr: "state", state }, "*");
  }, 1000);

  window.postMessage({ __vmr: "ready" }, "*");
  console.log("[Pocketfader] YTM main-world bridge ready");
})();
