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
    const setVideoId = r.playlistItemData?.playlistSetVideoId || null;
    return { title, subtitle, thumb, videoId, browseId, playlistId, kind, setVideoId };
  }

  // ------------------------------------------------------------------ readers
  const bar = () => document.querySelector("ytmusic-player-bar");
  const queueEl = () => document.querySelector("ytmusic-player-queue");
  // Loop / shuffle / autoplay state (verified live):
  //   repeat  -> attribute repeat-mode = NONE | ALL | ONE on <ytmusic-player-bar>
  //   shuffle -> attribute shuffle-on present on <ytmusic-player-bar>
  //   autoplay-> <tp-yt-paper-toggle-button id="automix">.checked  (queue.autoplayEnabled mirrors it)
  function repeatMode() {
    const m = bar() && bar().getAttribute("repeat-mode");
    return m === "ALL" || m === "ONE" ? m : "NONE";
  }
  const shuffleOn = () => !!(bar() && bar().hasAttribute("shuffle-on"));
  const autoplayToggle = () => document.querySelector("tp-yt-paper-toggle-button#automix");
  function autoplayOn() {
    const t = autoplayToggle();
    if (t && typeof t.checked === "boolean") return t.checked;
    const q = queueEl();
    return q && typeof q.autoplayEnabled === "boolean" ? q.autoplayEnabled : null;
  }
  // Album art. The player-bar <img> is often a blank placeholder (its src is just the page
  // URL), which is why art used to come back empty. Prefer the browser's own media session
  // artwork (the real cover), then the queue's current row, then the bar image.
  function currentArt(videoId) {
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      const list = md && md.artwork ? [...md.artwork] : [];
      list.sort((a, b) => (parseInt(b.sizes, 10) || 0) - (parseInt(a.sizes, 10) || 0));
      if (list[0] && list[0].src) return bigArt(list[0].src) || list[0].src;
    } catch (e) {}
    const sel = queueRows().find((r) => r.hasAttribute && r.hasAttribute("selected"));
    const qt = sel && sel.data && pickThumb(sel.data.thumbnail);
    if (qt) return bigArt(qt) || qt;
    const img = document.querySelector("ytmusic-player-bar img.image, ytmusic-player-bar .thumbnail img, .middle-controls img");
    if (img && /^https?:\/\/(lh\d|i\.ytimg|yt3)/.test(img.src || "")) return bigArt(img.src) || img.src;
    return videoId ? `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg` : null;
  }
  function nowPlaying() {
    const p = mp();
    if (!p || !p.getVideoData) return { playing: false, paused: false, videoId: null };
    const vd = p.getVideoData() || {};
    const ps = p.getProgressState ? p.getProgressState() : {};
    const state = p.getPlayerState ? p.getPlayerState() : -1;
    return {
      playing: state === 1,
      paused: state === 2,
      buffering: state === 3,
      videoId: vd.video_id || null,
      title: vd.title || null,
      artist: vd.author || null,
      art: currentArt(vd.video_id),
      position: ps.currentTime ?? (p.getCurrentTime ? p.getCurrentTime() : 0),
      duration: ps.duration ?? (p.getDuration ? p.getDuration() : 0),
      volume: p.getVolume ? p.getVolume() : null,   // YTM's own 0-100, independent of the computer's
      liked: likeStatus(),                          // 'LIKE' | 'DISLIKE' | 'INDIFFERENT' | null
      repeat: repeatMode(),                         // 'NONE' | 'ALL' | 'ONE'
      shuffle: shuffleOn(),
      autoplay: autoplayOn(),                       // true | false | null (unknown)
      playlistId: (p.getPlaylistId && p.getPlaylistId()) || null,
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
  // The queue panel has TWO containers: #contents (the playlist/queue that will actually
  // play) and #automix-contents (autoplay suggestions). Rows are tagged with their section.
  function queueRows() {
    const q = queueEl();
    let rows = q ? [...q.querySelectorAll("ytmusic-player-queue-item")] : [];
    return rows.length ? rows : queueItems();
  }
  const sectionOf = (row) => (row.parentElement && row.parentElement.id === "automix-contents" ? "autoplay" : "queue");
  function readQueue() {
    const out = [];
    const counters = { queue: 0, autoplay: 0 };
    for (const it of queueRows()) {
      const d = it.data || {};
      if (!d.videoId) continue;
      const section = sectionOf(it);
      const art = pickThumb(d.thumbnail);
      out.push({
        videoId: d.videoId,
        title: runText(d.title),
        artist: runText(d.longBylineText) || runText(d.shortBylineText),
        art: art ? bigArt(art) || art : null,
        duration: runText(d.lengthText),
        selected: it.hasAttribute && it.hasAttribute("selected"),
        section,                       // 'queue' | 'autoplay'
        index: counters[section]++,    // position inside its section
        setVideoId: d.playlistSetVideoId || null,
        canReorder: !!d.canReorder,
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
  /* Run a YouTube Music navigation endpoint. Older builds expose ytmusic-app.resolveCommand;
     newer ones don't, but dispatching a `yt-navigate` event on the app works (verified live).
     We try what exists, then check the player actually moved before reporting success. */
  async function runEndpoint(ep, matches) {
    const a = app();
    if (!a) return { ok: false, error: "no_app" };
    const attempts = [];
    if (typeof a.resolveCommand === "function") attempts.push(["resolveCommand", () => a.resolveCommand(ep)]);
    attempts.push(["yt-navigate", () => a.dispatchEvent(new CustomEvent("yt-navigate", { detail: { endpoint: ep }, bubbles: true, composed: true }))]);
    for (const [via, fn] of attempts) {
      try { fn(); } catch (e) { continue; }
      const deadline = Date.now() + 4500;
      while (Date.now() < deadline) {
        await sleep(150);
        if (matches()) return { ok: true, via };
      }
    }
    return { ok: false, error: "endpoint_not_accepted" };
  }
  const curVideo = () => (mp() && mp().getVideoData && (mp().getVideoData() || {}).video_id) || null;
  const curPlaylist = () => (mp() && mp().getPlaylistId && mp().getPlaylistId()) || null;
  function play(videoId, playlistId) {
    const ep = { watchEndpoint: { videoId } };
    if (playlistId) ep.watchEndpoint.playlistId = playlistId;
    return runEndpoint(ep, () => curVideo() === videoId);
  }
  function startPlaylist(playlistId, shuffle) {
    playlistId = String(playlistId || "").replace(/^VL/, "");
    // Shuffle uses the same endpoint with a known shuffle params token.
    const ep = shuffle
      ? { watchPlaylistEndpoint: { playlistId, params: "wAEB8gECKAE%3D" } }
      : { watchPlaylistEndpoint: { playlistId } };
    return runEndpoint(ep, () => curPlaylist() === playlistId).then((r) => ({ ...r, shuffle: !!shuffle }));
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
  /* --- player modes ---------------------------------------------------------------------
     Loop / shuffle / autoplay are driven through the real controls (verified live):
     repeat cycles NONE -> ALL -> ONE; shuffle toggles and physically reorders the playlist
     (toggling off restores the order); autoplay is a switch that shows/hides the suggestions. */
  const waitFor = async (fn, ms = 1500, step = 60) => {
    const end = Date.now() + ms;
    for (;;) { const v = fn(); if (v) return v; if (Date.now() > end) return null; await sleep(step); }
  };
  const barButton = (cls) => bar() && (bar().querySelector(`yt-icon-button.${cls} button`) || bar().querySelector(`.${cls} button`));
  async function setRepeat(mode) {
    if (!["NONE", "ALL", "ONE"].includes(mode)) return { ok: false, error: "bad_mode" };
    for (let i = 0; i < 3 && repeatMode() !== mode; i++) {
      const b = barButton("repeat");
      if (!b) return { ok: false, error: "no_repeat_button" };
      const before = repeatMode();
      b.click();
      await waitFor(() => repeatMode() !== before, 1200);
    }
    return { ok: repeatMode() === mode, repeat: repeatMode() };
  }
  async function setShuffle(on) {
    if (shuffleOn() !== on) {
      const b = barButton("shuffle");
      if (!b) return { ok: false, error: "no_shuffle_button" };
      b.click();
      await waitFor(() => shuffleOn() === on, 1500);
      await sleep(350);   // the queue reorders just after the flag flips
    }
    return { ok: shuffleOn() === on, shuffle: shuffleOn() };
  }
  async function reshuffle() {
    if (shuffleOn()) await setShuffle(false);
    return setShuffle(true);
  }
  async function setAutoplay(on) {
    const t = autoplayToggle();
    if (!t) return { ok: false, error: "no_autoplay_toggle" };
    if (autoplayOn() !== on) { t.click(); await waitFor(() => autoplayOn() === on, 1500); await sleep(200); }
    return { ok: autoplayOn() === on, autoplay: autoplayOn() };
  }

  /* --- queue rows & per-row actions -----------------------------------------------------
     A queue row only stamps its "Action menu" when it's the current track, so the menu for
     any other row doesn't exist until we force its <dom-if> templates to stamp; then we click
     the real menu item. (Synthetic drag-and-drop does NOT reorder -- verified -- so reorder is
     composed from these menu actions, see queueMove.) */
  function queueItems() {
    const out = [];
    const scan = (root) => root.querySelectorAll &&
      root.querySelectorAll("ytmusic-player-queue-item").forEach((i) => out.push(i));
    scan(document);
    document.querySelectorAll("*").forEach((el) => el.shadowRoot && scan(el.shadowRoot));
    return out;
  }
  function findRow(spec) {
    const rows = queueRows();
    if (spec.section != null && spec.index != null) {
      const r = rows.filter((x) => sectionOf(x) === spec.section)[spec.index];
      if (r && (!spec.videoId || (r.data && r.data.videoId === spec.videoId))) return r;
    }
    if (spec.setVideoId) {
      const r = rows.find((x) => x.data && x.data.playlistSetVideoId === spec.setVideoId);
      if (r) return r;
    }
    return rows.find((x) => x.data && x.data.videoId === spec.videoId) || null;
  }
  function findMenuButton(row) {
    let btn = null;
    const walk = (r) => {
      if (btn || !r.querySelectorAll) return;
      r.querySelectorAll("button, tp-yt-paper-icon-button").forEach((b) => {
        if (!btn && /action menu/i.test(b.getAttribute("aria-label") || "")) btn = b;
      });
      r.querySelectorAll("*").forEach((el) => el.shadowRoot && walk(el.shadowRoot));
    };
    walk(row);
    return btn;
  }
  const escapeMenu = () => document.body.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
  async function rowMenu(row, label) {
    if (!row) return { ok: false, error: "not_in_queue" };
    [...row.querySelectorAll("dom-if")].forEach((d) => {
      try { d.if = true; if (d._render) d._render(); } catch (e) {}
    });
    const btn = await waitFor(() => findMenuButton(row), 1500);
    if (!btn) return { ok: false, error: "no_menu" };
    const vid = row.data && row.data.videoId;
    btn.click();
    await sleep(120);
    // The popup is one shared, reused element, so a leftover item from the previous menu can
    // still be in the DOM. Only accept an item whose own endpoint mentions THIS row's video.
    const item = await waitFor(() => [...document.querySelectorAll("ytmusic-menu-service-item-renderer")]
      .filter((x) => (x.textContent || "").trim().startsWith(label) &&
        (!vid || JSON.stringify(x.data || {}).includes(vid)))[0], 1800);
    if (!item) { escapeMenu(); return { ok: false, error: "menu_item_missing" }; }
    item.click();
    await sleep(260);
    return { ok: true };
  }
  const queueRemove = (spec) => rowMenu(findRow(spec), "Remove from queue");
  const queueMoveNext = (spec) => rowMenu(findRow(spec), "Play next");
  const queueAddEnd = (spec) => rowMenu(findRow(spec), "Add to queue");

  async function queuePlayAt(spec) {
    const row = findRow(spec);
    if (!row) return { ok: false, error: "not_in_queue" };
    const d = row.data || {};
    const ep = d.navigationEndpoint || { watchEndpoint: { videoId: d.videoId } };
    return runEndpoint(ep, () => curVideo() === d.videoId);
  }

  /* Reorder the upcoming tracks. YouTube Music only offers drag handles that ignore scripted
     input, so a move is built from the menu actions: insert fresh copies where they belong
     ("Play next" = front of upcoming, "Add to queue" = end), then remove the old rows.
     We pick whichever of the two directions needs fewer steps, and verify the final order.
     If a build of YTM *moves* an existing row instead of duplicating it, the removals are
     skipped automatically (detected from whether the row count grew). */
  async function queueMove(spec) {
    const upcomingRows = () => {
      const rows = queueRows().filter((r) => sectionOf(r) === "queue");
      return { rows, cur: rows.findIndex((r) => r.hasAttribute("selected")) };
    };
    const { rows: rows0, cur } = upcomingRows();
    const src = findRow({ ...spec, section: "queue" });
    if (!src) return { ok: false, error: "not_in_queue" };
    const fromAbs = rows0.indexOf(src);
    if (fromAbs <= cur) return { ok: false, error: "cannot_move_played_or_current" };
    const base = cur + 1;
    const U = rows0.slice(base).map((r) => ({ videoId: r.data.videoId }));
    const len = U.length;
    const from = fromAbs - base;
    const dest = Math.max(0, Math.min(len - 1, Number(spec.to) - base));
    if (from === dest) return { ok: true, noop: true };
    const target = U.slice();
    target.splice(dest, 0, target.splice(from, 1)[0]);
    // Two ways to build the target, each as few menu actions as possible:
    //   front: "Play next" the first m+1 target rows, in reverse  -> [target[0..m]] ++ (U minus those)
    //   end:   "Add to queue" the target rows from m on, in order -> (U minus those) ++ [target[m..]]
    // so pick the smallest set whose leftover rows already sit in the right relative order.
    const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
    let mP = len - 1;
    for (let m = 0; m < len; m++) {
      const S = new Set(target.slice(0, m + 1));
      if (same(U.filter((u) => !S.has(u)), target.slice(m + 1))) { mP = m; break; }
    }
    let mE = 0;
    for (let m = len - 1; m >= 0; m--) {
      const S = new Set(target.slice(m));
      if (same(U.filter((u) => !S.has(u)), target.slice(0, m))) { mE = m; break; }
    }
    const useFront = mP + 1 <= len - mE;
    const seq = useFront ? target.slice(0, mP + 1).reverse() : target.slice(mE);
    if (seq.length > 40) return { ok: false, error: "move_too_large" };
    const want = target.map((t) => t.videoId);

    let dup = null;   // does an insert duplicate the row (true) or move it (false)?
    for (const entry of seq) {
      const before = upcomingRows().rows.length;
      const r = await rowMenu(queueRows().find((x) => x.data && x.data.videoId === entry.videoId),
        useFront ? "Play next" : "Add to queue");
      if (!r.ok) return { ok: false, error: r.error, step: "insert" };
      if (dup === null) { await sleep(300); dup = upcomingRows().rows.length > before; }
    }
    if (dup) {
      // Old rows now sit after the front-inserted copies, or before the end-appended ones.
      const idx = useFront ? seq.map((e) => seq.length + U.indexOf(e)) : seq.map((e) => U.indexOf(e));
      for (const i of idx.sort((a, b) => b - a)) {
        const { rows, cur: c } = upcomingRows();
        const r = await rowMenu(rows[c + 1 + i], "Remove from queue");
        if (!r.ok) return { ok: false, error: r.error, step: "remove" };
      }
    }
    await sleep(300);
    const { rows: rowsN, cur: curN } = upcomingRows();
    const got = rowsN.slice(curN + 1).map((r) => r.data.videoId);
    const ok = got.length === want.length && got.every((v, i) => v === want[i]);
    return { ok, steps: seq.length * (dup ? 2 : 1), via: useFront ? "front" : "end", ...(ok ? {} : { error: "order_mismatch", got }) };
  }

  // Legacy name used by older clients.
  async function queueItemMenu(videoId, label) { return rowMenu(findRow({ videoId }), label); }

  /* --- lyrics: the text YouTube Music itself shows in its Lyrics tab (plain, not time-synced) --- */
  async function lyrics(videoId) {
    if (!videoId) return { error: "no_video" };
    const nx = await innertube("next", { videoId, isAudioOnly: true });
    const tabs = deepFind(nx, "tabRenderer", []);
    const tab = tabs.find((t) => /^MPLY/.test(t?.endpoint?.browseEndpoint?.browseId || ""));
    const browseId = tab?.endpoint?.browseEndpoint?.browseId;
    if (!browseId) return { error: "no_lyrics" };
    const j = await innertube("browse", { browseId });
    const shelf = deepFind(j, "musicDescriptionShelfRenderer", [])[0];
    const text = runText(shelf?.description);
    if (!text) return { error: "no_lyrics" };
    return { text, credit: runText(shelf?.footer) || undefined };
  }

  /* --- saved playlists (authenticated InnerTube; same endpoints the YTM site itself uses) --- */
  const plId = (id) => String(id || "").replace(/^VL/, "");
  async function playlistEdit(playlistId, actions) {
    const j = await innertube("browse/edit_playlist", { playlistId: plId(playlistId), actions }, true);
    return { ok: !!j && j.status === "STATUS_SUCCEEDED", status: (j && j.status) || null,
      error: j && j.error && j.error.message ? j.error.message : undefined };
  }
  const playlistAdd = (c) => playlistEdit(c.playlistId, [{ action: "ACTION_ADD_VIDEO", addedVideoId: c.videoId,
    dedupeOption: c.allowDuplicate ? "DEDUPE_OPTION_CHECK" : "DEDUPE_OPTION_SKIP" }]);
  const playlistRemove = (c) => playlistEdit(c.playlistId, [{ action: "ACTION_REMOVE_VIDEO",
    setVideoId: c.setVideoId, removedVideoId: c.videoId }]);
  // Move a track so it sits immediately BEFORE `before` (a setVideoId). Omit `before` to send it to the end.
  const playlistMove = (c) => playlistEdit(c.playlistId, [{ action: "ACTION_MOVE_VIDEO_BEFORE", setVideoId: c.setVideoId,
    ...(c.before ? { movedSetVideoIdSuccessor: c.before } : {}) }]);
  const playlistRename = (c) => playlistEdit(c.playlistId, [{ action: "ACTION_SET_PLAYLIST_NAME", playlistName: c.name }]);
  async function playlistCreate(c) {
    const j = await innertube("playlist/create", { title: c.title, description: c.description || "",
      privacyStatus: c.privacy || "PRIVATE", videoIds: c.videoIds || [] }, true);
    return { ok: !!(j && j.playlistId), playlistId: j && j.playlistId };
  }
  async function playlistDelete(c) {
    const j = await innertube("playlist/delete", { playlistId: plId(c.playlistId) }, true);
    return { ok: !!j && j.status === "STATUS_SUCCEEDED", status: (j && j.status) || null };
  }
  const queueSave = (c) => playlistCreate({ title: c.title || "Saved queue",
    videoIds: readQueue().filter((q) => q.section === "queue").map((q) => q.videoId) });

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
    return runEndpoint({ watchEndpoint: { videoId } }, () => curVideo() === videoId);
  }

  // ------------------------------------------------------------------- probe
  /* Read-only structural dump of the player bar / queue, used to write selectors against the
     real page. Never clicks anything, never changes state. */
  function deepAll(root, pred, out = [], depth = 0) {
    if (!root || depth > 40 || !root.querySelectorAll) return out;
    root.querySelectorAll("*").forEach((el) => {
      if (pred(el)) out.push(el);
      if (el.shadowRoot) deepAll(el.shadowRoot, pred, out, depth + 1);
    });
    return out;
  }
  function attrs(el) {
    const o = {};
    for (const a of el.attributes || []) o[a.name] = String(a.value).slice(0, 80);
    return o;
  }
  function chain(el) {
    const out = [];
    let n = el;
    for (let i = 0; i < 8 && n; i++) {
      out.push((n.tagName || "?").toLowerCase() + (n.id ? "#" + n.id : "") +
        (n.classList && n.classList.length ? "." + [...n.classList].slice(0, 3).join(".") : ""));
      n = n.parentNode && n.parentNode.host ? n.parentNode.host : n.parentElement;
    }
    return out;
  }
  function interestingProps(obj) {
    const names = [];
    try { for (const k in obj) if (/queue|shuffle|repeat|loop|automix|autoplay|playlist|radio/i.test(k)) names.push(k); } catch (e) {}
    const vals = {};
    for (const k of names.slice(0, 40)) {
      try {
        const v = obj[k];
        vals[k] = typeof v === "function" ? "fn" : (typeof v === "object" && v) ? "obj:" + Object.keys(v).slice(0, 8).join(",") : v;
      } catch (e) { vals[k] = "err"; }
    }
    return vals;
  }
  function probe() {
    const out = {};
    const bar = document.querySelector("ytmusic-player-bar");
    out.playerBar = bar ? { attrs: attrs(bar), props: interestingProps(bar) } : null;
    const q = document.querySelector("ytmusic-player-queue");
    out.queuePanel = q ? { attrs: attrs(q), props: interestingProps(q) } : null;
    const btnPred = (el) => /^(button|tp-yt-paper-icon-button|ytmusic-toggle-button-renderer|tp-yt-paper-toggle-button|yt-button-shape)$/i.test(el.tagName);
    const describe = (el) => ({ tag: el.tagName.toLowerCase(), id: el.id || undefined, cls: [...(el.classList || [])].join(" ") || undefined,
      aria: el.getAttribute("aria-label") || undefined, title: el.getAttribute("title") || undefined,
      pressed: el.getAttribute("aria-pressed") || el.getAttribute("aria-checked") || el.getAttribute("active") || undefined,
      in: chain(el).slice(1, 4).join(" < ") });
    out.barButtons = bar ? deepAll(bar, btnPred).map(describe).slice(0, 40) : [];
    out.queueButtons = q ? deepAll(q, btnPred).map(describe).slice(0, 40) : [];
    const items = deepAll(document, (el) => el.tagName === "YTMUSIC-PLAYER-QUEUE-ITEM");
    out.queueItemCount = items.length;
    out.itemChains = {};
    items.forEach((it, i) => {
      const key = chain(it).slice(1, 5).join(" < ");
      (out.itemChains[key] = out.itemChains[key] || []).push(i);
    });
    for (const k in out.itemChains) {
      const a = out.itemChains[k];
      out.itemChains[k] = { count: a.length, first: a[0], last: a[a.length - 1] };
    }
    const first = items[0];
    out.firstItem = first ? { attrs: attrs(first), dataKeys: Object.keys(first.data || {}).slice(0, 40) } : null;
    const sel = items.findIndex((i) => i.hasAttribute && i.hasAttribute("selected"));
    out.selectedIndex = sel;
    out.queueContainers = q ? deepAll(q, (el) => el.id && /content|automix|queue|list|header|footer|tabs?/i.test(el.id)).map((el) =>
      el.tagName.toLowerCase() + "#" + el.id + " children=" + el.children.length).slice(0, 30) : [];
    const mpl = mp();
    out.player = {};
    for (const m of ["getPlaylistId", "getPlaylistIndex", "getPlaylist", "getVideoData", "getLoopMode", "getShuffle"]) {
      try { const v = mpl && mpl[m] && mpl[m](); out.player[m] = Array.isArray(v) ? "array(" + v.length + ")" : (v && typeof v === "object" ? Object.keys(v).slice(0, 12) : v); } catch (e) { out.player[m] = "err"; }
    }
    out.playerMethods = mpl ? Object.keys(mpl).filter((k) => /list|loop|shuffle|repeat|queue|next|prev/i.test(k) && typeof mpl[k] === "function").slice(0, 40) : [];
    const a = app();
    out.app = a ? interestingProps(a) : null;
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      out.mediaSession = md ? { title: md.title, artwork: (md.artwork || []).map((x) => x.sizes + " " + String(x.src).slice(0, 70)) } : null;
    } catch (e) { out.mediaSession = "err"; }
    out.barImgs = bar ? deepAll(bar, (el) => el.tagName === "IMG").map((i) => (i.className || "") + " " + String(i.src).slice(0, 90)).slice(0, 8) : [];
    out.url = location.pathname + location.search;
    return out;
  }

  // -------------------------------------------------------------- dispatch
  async function handle(cmd) {
    try {
      switch (cmd.type) {
        case "transport": return transport(cmd.action, cmd.position);
        case "play": return await play(cmd.videoId, cmd.playlistId);
        case "startMix": return await startMix(cmd.videoId);
        case "startPlaylist": return await startPlaylist(cmd.playlistId, cmd.shuffle);
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
        case "queueRemove": return await queueRemove(cmd);
        case "queueMoveNext": return await queueMoveNext(cmd);
        case "queueAddEnd": return await queueAddEnd(cmd);
        case "queuePlayAt": return await queuePlayAt(cmd);
        case "queueMove": return await queueMove(cmd);
        case "queueSave": return await queueSave(cmd);
        case "setRepeat": return await setRepeat(cmd.mode);
        case "setShuffle": return await setShuffle(!!cmd.on);
        case "reshuffle": return await reshuffle();
        case "setAutoplay": return await setAutoplay(!!cmd.on);
        case "playlistAdd": return await playlistAdd(cmd);
        case "playlistRemove": return await playlistRemove(cmd);
        case "playlistMove": return await playlistMove(cmd);
        case "playlistRename": return await playlistRename(cmd);
        case "playlistCreate": return await playlistCreate(cmd);
        case "playlistDelete": return await playlistDelete(cmd);
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
        case "lyrics": return await lyrics(cmd.videoId);
        case "nowplaying": return { now: nowPlaying(), queue: readQueue() };
        case "probe": return probe();
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
    const sig = JSON.stringify([state.now, state.queue.map((q) => q.videoId + q.section[0] + (q.selected ? "*" : "")), Math.round(state.now.position)]);
    if (sig === last) return; // only push on change (position rounds to whole seconds)
    last = sig;
    window.postMessage({ __vmr: "state", state }, "*");
  }, 1000);

  if (window.__pfTest) window.__pfHandle = handle;   // lets the test-suite drive commands directly
  window.postMessage({ __vmr: "ready" }, "*");
  console.log("[Pocketfader] YTM main-world bridge ready");
})();
