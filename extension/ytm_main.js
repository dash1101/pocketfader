/* YTM Starcast — YouTube Music bridge (MAIN world).
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
 *
 * The page-world names below (Symbol.for("pocketfader.api"), the __vmr message tag) are kept from before the rename on
 * purpose: Refine and older copies of these scripts still look for them.
 */
(() => {
  "use strict";
  const EXT_VERSION = "2.5.0";   // keep in step with manifest.json (tests/test_version.py checks)

  // One copy of this script answers commands per page. With two copies of the extension loaded (an older Pocketfader one
  // kept beside this one, or this one twice) every command would otherwise run twice: "next" would skip two songs. Older
  // copies only act on the old "cmd" tag, which this version's bridge no longer sends; a second copy of this version stays
  // out of the way. Either way the page is marked, so the toolbar button can say that two copies are loaded.
  let twinSeen = false;
  function twin(version) {
    if (twinSeen) return;
    twinSeen = true;
    try { document.documentElement.setAttribute("data-starcast-twin", String(version || "older").slice(0, 24)); } catch (e) {}
    console.warn("[YTM Starcast] another copy of the extension is loaded in this page (" + (version || "older") + "); keep only one");
  }
  const MAIN_KEY = Symbol.for("starcast.main");
  if (window[MAIN_KEY]) { twin(window[MAIN_KEY].version); return; }
  try { Object.defineProperty(window, MAIN_KEY, { value: Object.freeze({ version: EXT_VERSION }) }); } catch (e) {}
  const olderApi = window[Symbol.for("pocketfader.api")];   // an older copy that started first
  if (olderApi) twin(olderApi.version);

  const mp = () => document.querySelector("#movie_player");
  const app = () => document.querySelector("ytmusic-app");
  const media = () => document.querySelector("#movie_player video, video.html5-main-video");
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
    else if (browseId) kind = browseId.startsWith("MPRE") ? "album" : /^(UC|MPLA)/.test(browseId) ? "artist" : "browse";
    const setVideoId = r.playlistItemData?.playlistSetVideoId || null;
    return { title, subtitle, thumb, videoId, browseId, playlistId, kind, setVideoId };
  }

  // ------------------------------------------------------------------ readers
  // Some YouTube Music builds put the player bar inside shadow roots, where document.querySelector can't see it.
  let barCache = null, barAt = 0;
  const bar = () => {
    // a "not here" answer is remembered too, so a page without the bar isn't searched end to end on every state push
    if (barCache ? (barCache.isConnected && Date.now() - barAt < 4000) : Date.now() - barAt < 60000) return barCache;
    barCache = document.querySelector("ytmusic-player-bar") ||
      deepAll(document, (el) => el.tagName === "YTMUSIC-PLAYER-BAR")[0] || null;
    barAt = Date.now();
    return barCache;
  };
  const BTN = /^(button|tp-yt-paper-icon-button|yt-icon-button|ytmusic-toggle-button-renderer|yt-button-shape)$/i;
  // The control for "shuffle" / "repeat", found by what it's called when the bar's own markup isn't recognised.
  const ctlCache = {};
  function labelControl(word) {
    const c = ctlCache[word];
    // a found control is re-checked every few seconds; "not found" is remembered for 30 s (a whole-page scan is costly)
    if (c && (c.el ? c.el.isConnected && Date.now() - c.at < 4000 : Date.now() - c.at < 30000)) return c.el;
    const host = document.querySelector("ytmusic-wiz-player-controls, ytmusic-player-bar");
    const el = (host && findLabelControl(word, host)) || findLabelControl(word, document);
    ctlCache[word] = { el, at: Date.now() };
    return el;
  }
  function findLabelControl(word, root) {
    const w = word.toLowerCase();
    const label = (el) => ((el.getAttribute("aria-label") || el.getAttribute("title") || "") + " " + (el.className && el.className.baseVal === undefined ? el.className : "")).toLowerCase();
    const hits = deepAll(root, (el) => BTN.test(el.tagName) && label(el).includes(w) && !el.closest("ytmusic-player-queue-item, ytmusic-menu-renderer"));
    return hits.find((el) => el.tagName === "BUTTON") || hits[0] || null;
  }
  const pressed = (el) => el && (el.getAttribute("aria-pressed") === "true" || el.getAttribute("aria-checked") === "true" ||
    el.hasAttribute("active") || el.classList.contains("style-default-active"));
  const queueEl = () => document.querySelector("ytmusic-player-queue");
  // Loop / shuffle / autoplay state (verified live):
  //   repeat  -> attribute repeat-mode = NONE | ALL | ONE on <ytmusic-player-bar>
  //   shuffle -> attribute shuffle-on present on <ytmusic-player-bar>
  //   autoplay-> <tp-yt-paper-toggle-button id="automix">.checked  (queue.autoplayEnabled mirrors it)
  // With no shuffle / repeat control on the page these just read as off (see apiShuffle below).
  const apiModes = { repeat: "NONE", shuffle: false };
  function repeatMode() {
    const m = bar() && bar().getAttribute("repeat-mode");
    if (m) return m === "ALL" || m === "ONE" ? m : "NONE";
    const c = labelControl("repeat");                                   // fall back to the button's own label
    if (!c) return apiModes.repeat;                                     // no button at all: what we last set through the player
    const t = c ? ((c.getAttribute("aria-label") || c.getAttribute("title") || "")).toLowerCase() : "";
    return /\bone\b|single|track/.test(t) ? "ONE" : /\ball\b|queue|playlist/.test(t) || pressed(c) ? "ALL" : "NONE";
  }
  const shuffleOn = () => {
    if (bar()) return bar().hasAttribute("shuffle-on");
    const c = labelControl("shuffle");
    return c ? pressed(c) : apiModes.shuffle;
  };
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
  // The browser's media session, only when it describes the same song (right after a song change it can still hold the
  // last one). The album and the artist come from there.
  function sessionFor(title) {
    try {
      const md = navigator.mediaSession && navigator.mediaSession.metadata;
      return md && title && md.title === title ? md : null;
    } catch (e) {
      return null;
    }
  }
  function currentAlbum(md) {
    try { return md && md.album ? String(md.album).slice(0, 300) : null; } catch (e) { return null; }
  }
  // The artist as YouTube Music shows it (every artist of a song by several), from the same media session. Without it,
  // the channel the video is on, without YouTube's " - Topic" and "VEVO" endings.
  function currentArtist(md, vd) {
    try { if (md && md.artist) return String(md.artist).slice(0, 300); } catch (e) {}
    const author = typeof vd.author === "string" ? vd.author.trim() : "";
    return author.replace(/ - Topic$/, "").replace(/(\S)VEVO$/, "$1") || author || null;
  }
  // An advert is playing: the player carries YouTube's "ad-showing" class while one runs. Looking through the whole page
  // as well costs more, so that is done at most once a second.
  let adSeen = false, adAt = 0;
  function adShowing(p) {
    try { if (p.classList && p.classList.contains("ad-showing")) return true; } catch (e) {}
    const now = Date.now();
    if (now - adAt >= 1000) { adAt = now; adSeen = !!document.querySelector(".ad-showing"); }
    return adSeen;
  }
  function nowPlaying() {
    const t0 = performance.now();
    const p = mp();
    if (!p || !p.getVideoData) return { playing: false, paused: false, videoId: null };
    const vd = p.getVideoData() || {};
    const ps = p.getProgressState ? p.getProgressState() : {};
    const state = p.getPlayerState ? p.getPlayerState() : -1;
    const md = sessionFor(vd.title);                // the browser's media session, when it describes this song
    return {
      playing: state === 1,
      paused: state === 2,
      buffering: state === 3,
      videoId: vd.video_id || null,
      title: vd.title || null,
      artist: currentArtist(md, vd),                // as YouTube Music shows it; string | null
      album: currentAlbum(md),                      // string | null
      ad: adShowing(p),                             // an advert is playing (Discord status is cleared meanwhile)
      art: currentArt(vd.video_id),
      position: ps.currentTime ?? (p.getCurrentTime ? p.getCurrentTime() : 0),
      duration: ps.duration ?? (p.getDuration ? p.getDuration() : 0),
      volume: p.getVolume ? p.getVolume() : null,   // YTM's own 0-100, independent of the computer's
      liked: likeStatus(),                          // 'LIKE' | 'DISLIKE' | 'INDIFFERENT' | null
      repeat: repeatMode(),                         // 'NONE' | 'ALL' | 'ONE'
      shuffle: shuffleOn(),
      autoplay: autoplayOn(),                       // true | false | null (unknown)
      playlistId: (p.getPlaylistId && p.getPlaylistId()) || null,
      speed: (media() && Math.round(media().playbackRate * 100) / 100) || (p.getPlaybackRate && p.getPlaybackRate()) || 1,   // what it really plays at
      ext: EXT_VERSION,                             // which version of this script the page is running
      tickMs: Math.round(performance.now() - t0),   // how long reading this state took
    };
  }
  // When the page has no player-bar like button, fall back to YouTube Music's own like endpoints (the same calls its
  // page makes, with your login), and read the state back from the song's `next` data.
  // Newer builds of YouTube Music build the player controls as <ytmusic-wiz-player-controls> (no <ytmusic-player-bar>),
  // so the like control is looked up there, or anywhere on the page that isn't part of a list row or menu.
  let vmCache = { v: null, at: 0 };
  const vmButton = (vm) => vm && (vm.querySelector("button") || deepAll(vm, (e) => e.tagName === "BUTTON")[0] || null);
  // The newest builds draw like / dislike as <like-button-view-model> and <dislike-button-view-model>.
  function likeVm() {
    const c = vmCache;
    if (c.v ? c.v.like.isConnected && Date.now() - c.at < 4000 : Date.now() - c.at < 15000) return c.v;
    const find = (tag) => document.querySelector(tag.toLowerCase()) || deepAll(document, (e) => e.tagName === tag)[0] || null;
    const like = find("LIKE-BUTTON-VIEW-MODEL");
    vmCache = { v: like ? { like, dislike: find("DISLIKE-BUTTON-VIEW-MODEL") } : null, at: Date.now() };
    return vmCache.v;
  }
  const isPressed = (b) => !!b && (b.getAttribute("aria-pressed") === "true" || b.getAttribute("aria-checked") === "true");
  let lrCache = { el: null, at: 0 };
  function likeRenderer() {
    if (lrCache.el ? lrCache.el.isConnected && Date.now() - lrCache.at < 4000 : Date.now() - lrCache.at < 15000) return lrCache.el;
    const host = document.querySelector("ytmusic-wiz-player-controls, ytmusic-player-bar");
    let el = host && (host.querySelector("ytmusic-like-button-renderer") || deepAll(host, (e) => e.tagName === "YTMUSIC-LIKE-BUTTON-RENDERER")[0]);
    if (!el) {
      el = deepAll(document, (e) => e.tagName === "YTMUSIC-LIKE-BUTTON-RENDERER" &&
        !e.closest("ytmusic-player-queue-item, ytmusic-responsive-list-item-renderer, ytmusic-two-row-item-renderer, ytmusic-menu-renderer, ytmusic-shelf-renderer"))[0] || null;
    }
    lrCache = { el, at: Date.now() };
    return el;
  }
  // The like button only exists on the player page. Everywhere else the state comes from the song's `next` data: newer
  // builds keep it in frameworkUpdates as a likeStatusEntity, older ones in a likeButtonRenderer. Re-read every 45 s so a
  // like made in the page itself shows up too.
  const likeApi = { vid: null, status: null, busy: false, at: 0 };
  async function apiLikeStatus(vid) {
    const nx = await innertube("next", { videoId: vid, isAudioOnly: true }, true);
    const ent = deepFind(nx, "likeStatusEntity", []).find((e) => e && e.likeStatus);
    if (ent) return ent.likeStatus;
    const r = deepFind(nx, "likeButtonRenderer", [])[0] || deepFind(nx, "musicLikeButtonRenderer", [])[0];
    return (r && r.likeStatus) || null;
  }
  function ensureApiLike(vid) {
    if (!vid || likeApi.busy || (likeApi.vid === vid && Date.now() - likeApi.at < 45000)) return;
    likeApi.busy = true; likeApi.at = Date.now();
    if (likeApi.vid !== vid) { likeApi.vid = vid; likeApi.status = null; }
    apiLikeStatus(vid).then((st) => { if (likeApi.vid === vid) likeApi.status = st; }).catch(() => {}).finally(() => { likeApi.busy = false; });
  }
  // Read the current song's like state from the player-bar like button.
  function likeStatus() {
    const vm = likeVm();
    if (vm) {
      const lbtn = vmButton(vm.like), dbtn = vmButton(vm.dislike);
      if (lbtn) return isPressed(lbtn) ? "LIKE" : isPressed(dbtn) ? "DISLIKE" : "INDIFFERENT";
    }
    const lb = likeRenderer();
    if (!lb) {
      const vid = mp() && mp().getVideoData && mp().getVideoData().video_id;
      ensureApiLike(vid);
      return likeApi.vid === vid ? likeApi.status : null;
    }
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

  // What YouTube Music's own search chips (Songs, Videos, Albums, Artists, Community playlists) send with the search.
  const SEARCH_FILTERS = {
    songs: "EgWKAQIIAWoMEA4QChADEAQQCRAF",
    videos: "EgWKAQIQAWoMEA4QChADEAQQCRAF",
    albums: "EgWKAQIYAWoMEA4QChADEAQQCRAF",
    artists: "EgWKAQIgAWoMEA4QChADEAQQCRAF",
    playlists: "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D",
  };
  const FILTER_KIND = { songs: "song", videos: "song", albums: "album", artists: "artist", playlists: "playlist" };
  function searchItems(j) {
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
  // filter: "songs" | "videos" | "albums" | "artists" | "playlists"; anything else searches everything.
  async function search(query, filter) {
    const params = Object.prototype.hasOwnProperty.call(SEARCH_FILTERS, filter) ? SEARCH_FILTERS[filter] : null;
    if (!params) return searchItems(await innertube("search", { query }));
    const want = FILTER_KIND[filter];
    const only = (list) => list.filter((it) => it.kind === want);
    let found = [];
    try { found = only(searchItems(await innertube("search", { query, params }))); } catch (_) {}
    // If YouTube Music changes those values, the filter still works: search everything and keep that kind.
    return found.length ? found : only(searchItems(await innertube("search", { query })));
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
  /* --- artists: the artist's page (about, top songs, albums, singles...) --- */
  async function artistFor(videoId) {
    const nx = await innertube("next", { videoId, isAudioOnly: true }, true);
    const rows = deepFind(nx, "playlistPanelVideoRenderer", []);
    const row = rows.find((r) => r.selected) || rows[0];
    const run = ((row && row.longBylineText && row.longBylineText.runs) || []).find((r) => /^(UC|MPLA)/.test((r.navigationEndpoint && r.navigationEndpoint.browseEndpoint && r.navigationEndpoint.browseEndpoint.browseId) || ""));
    return run ? { id: run.navigationEndpoint.browseEndpoint.browseId, name: run.text } : null;
  }
  async function artistPage(browseId) {
    const j = await innertube("browse", { browseId }, true);
    const h = deepFind(j, "musicImmersiveHeaderRenderer", [])[0] || deepFind(j, "musicVisualHeaderRenderer", [])[0] || {};
    const shelves = [];
    for (const sh of deepFind(j, "musicShelfRenderer", [])) {
      const items = (sh.contents || []).map((c) => extractItem(c.musicResponsiveListItemRenderer)).filter(Boolean);
      if (items.length) shelves.push({ title: runText(sh.title) || "Songs", items });
    }
    for (const sh of deepFind(j, "musicCarouselShelfRenderer", [])) {
      const items = (sh.contents || []).map((c) => extractItem(c.musicTwoRowItemRenderer || c.musicResponsiveListItemRenderer)).filter(Boolean);
      const title = runText(sh.header && sh.header.musicCarouselShelfBasicHeaderRenderer && sh.header.musicCarouselShelfBasicHeaderRenderer.title) || "More";
      if (items.length) shelves.push({ title, items });
    }
    const we = h.playButton && h.playButton.buttonRenderer && h.playButton.buttonRenderer.command && h.playButton.buttonRenderer.command.watchEndpoint;
    const re = h.startRadioButton && h.startRadioButton.buttonRenderer && h.startRadioButton.buttonRenderer.command && h.startRadioButton.buttonRenderer.command.watchPlaylistEndpoint;
    return {
      browseId,
      name: runText(h.title) || null,
      description: runText(h.description) || null,
      thumb: pickThumb((h.thumbnail && h.thumbnail.musicThumbnailRenderer && h.thumbnail.musicThumbnailRenderer.thumbnail) ||
        (h.foregroundThumbnail && h.foregroundThumbnail.musicThumbnailRenderer && h.foregroundThumbnail.musicThumbnailRenderer.thumbnail), 300),
      subscribers: runText(h.subscriptionButton && h.subscriptionButton.subscribeButtonRenderer && h.subscriptionButton.subscribeButtonRenderer.subscriberCountText) || null,
      play: we ? { videoId: we.videoId || null, playlistId: we.playlistId || null } : null,
      radio: re ? { playlistId: re.playlistId || null } : null,
      shelves,
    };
  }
  /* --- refinements: playback speed, a random song, and turning a list of "title - artist" into a playlist --- */
  // The player only knows a few speeds; anything in between is set on the media element itself (as Refine does).
  function setSpeed(rate) {
    const p = mp();
    const r = Math.round(Math.max(0.25, Math.min(3, Number(rate) || 1)) * 100) / 100;
    if (!p || !p.setPlaybackRate) return { ok: false, error: "no_player" };
    p.setPlaybackRate(r);
    const v = media();
    try { if (v && Math.abs(v.playbackRate - r) > 0.001) v.playbackRate = r; } catch (e) {}
    return { ok: true, speed: v ? Math.round(v.playbackRate * 100) / 100 : (p.getPlaybackRate ? p.getPlaybackRate() : r) };
  }
  // Something you might like, picked at random from your home page (or, failing that, from a search).
  async function lucky() {
    let songs = [];
    try {
      const secs = await home();
      songs = secs.flatMap((s) => s.items || []).filter((i) => i.kind === "song" && i.videoId);
      if (!songs.length) {
        const albums = secs.flatMap((s) => s.items || []).filter((i) => i.playlistId);
        if (albums.length) { const a = albums[Math.floor(Math.random() * albums.length)]; return { ...(await startPlaylist(a.playlistId, true)), picked: a.title }; }
      }
    } catch (e) {}
    if (!songs.length) songs = (await search("music mix").catch(() => [])).filter((i) => i.kind === "song" && i.videoId);
    if (!songs.length) return { ok: false, error: "nothing_to_pick" };
    const pick = songs[Math.floor(Math.random() * songs.length)];
    const r = await play(pick.videoId, null);
    // "Title · Artist", without the counts some shelves add ("• 1.5M plays", "• 30M views")
    const by = String(pick.subtitle || "").split(/\s*[•·]\s*/).filter((x) => x && !/\d[\d.,]*\s*[KMB]?\s+(plays|views|listeners)\b/i.test(x) &&
      !/^(song|video|album|single|ep|playlist|artist|episode|podcast)$/i.test(x))[0] || "";
    return { ...r, picked: pick.title + (by ? " · " + by : "") };
  }
  // "Title - Artist" lines -> the best matching song for each (one search each, kept gentle)
  async function resolveSongs(queries) {
    const out = [];
    for (const q of (queries || []).slice(0, 40)) {
      try {
        const hit = (await search(String(q).slice(0, 120))).find((i) => i.kind === "song" && i.videoId);
        out.push(hit ? { query: q, videoId: hit.videoId, title: hit.title, subtitle: hit.subtitle } : { query: q, videoId: null });
      } catch (e) { out.push({ query: q, videoId: null }); }
      await sleep(120);
    }
    return { results: out };
  }
  // A playlist's songs. Long playlists arrive in pages of about 100; `limit` says how many to gather (the first page only
  // when it isn't given). Pages are fetched one after another with a short pause, so a big library sync stays gentle.
  async function playlistItems(browseIdOrPl, limit) {
    let browseId = browseIdOrPl;
    if (!browseId.startsWith("VL") && !browseId.startsWith("MPRE")) browseId = "VL" + browseId;
    const want = Math.max(1, Math.min(5000, Number(limit) || 0));
    let j = await innertube("browse", { browseId }, true);
    const out = [], seen = new Set();
    const take = (resp) => {
      for (const it of deepFind(resp, "musicResponsiveListItemRenderer", []).map(extractItem)) {
        if (!it || !it.videoId) continue;
        const k = it.setVideoId || it.videoId;
        if (seen.has(k)) continue;
        seen.add(k); out.push(it);
      }
    };
    take(j);
    let hops = 0;
    while (limit && out.length < want && hops < 60) {
      const token = contToken(j);
      if (!token) break;
      await sleep(150);
      j = await innertube("browse", { continuation: token }, true);
      const before = out.length;
      take(j);
      if (out.length === before) break;
      hops++;
    }
    return limit ? out.slice(0, want) : out;
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
  const barButton = (cls) => {
    const b = bar();
    const direct = b && (b.querySelector(`yt-icon-button.${cls} button`) || b.querySelector(`.${cls} button`) ||
      deepAll(b, (el) => el.classList && el.classList.contains(cls) && BTN.test(el.tagName))[0]);
    return direct || labelControl(cls);
  };
  const missing = (what) => `no_${what}_button (player bar ${bar() ? "found" : "not found"}, ${deepAll(document, (el) => BTN.test(el.tagName)).length} buttons on the page; reload the extension, then use Settings to send the diagnostics)`;
  // This page has no shuffle / repeat buttons we can find. YouTube Music keeps its queue in its own store (the
  // player's own playlist list is empty), so the player's setShuffle/setLoop do nothing useful here: say so honestly
  // instead of pretending, and leave a description of the page (see the layout snapshot below) so it can be mapped.
  const apiRepeat = () => ({ ok: false, error: missing("repeat") });
  const apiShuffle = () => ({ ok: false, error: missing("shuffle") });
  async function setRepeat(mode) {
    if (!["NONE", "ALL", "ONE"].includes(mode)) return { ok: false, error: "bad_mode" };
    for (let i = 0; i < 3 && repeatMode() !== mode; i++) {
      const b = barButton("repeat");
      if (!b) return apiRepeat();
      const before = repeatMode();
      b.click();
      await waitFor(() => repeatMode() !== before, 1200);
    }
    return { ok: repeatMode() === mode, repeat: repeatMode() };
  }
  async function setShuffle(on) {
    if (shuffleOn() !== on) {
      const b = barButton("shuffle");
      if (!b) return apiShuffle();
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
    if (autoplayOn() !== on) {
      t.click();
      let ok = await waitFor(() => autoplayOn() === on, 900);
      if (!ok) {   // second try: the queue panel's own handler for the switch
        const q = queueEl();
        try { if (q && typeof q.onAutomixToggleTap === "function") q.onAutomixToggleTap({ target: t, currentTarget: t, stopPropagation() {}, preventDefault() {} }); } catch (e) {}
        ok = await waitFor(() => autoplayOn() === on, 900);
      }
      await sleep(200);
    }
    const now = autoplayOn();
    if (now === on) return { ok: true, autoplay: now };
    // YouTube Music hides and ignores the autoplay switch while the queue is looping
    return { ok: false, autoplay: now, error: repeatMode() !== "NONE" ? "autoplay_off_while_looping" : "autoplay_not_applied" };
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
  const upcoming = () => {
    const rows = queueRows().filter((r) => sectionOf(r) === "queue");
    return { rows, cur: rows.findIndex((r) => r.hasAttribute("selected")) };
  };
  const upcomingIds = () => { const { rows, cur } = upcoming(); return rows.slice(cur + 1).map((r) => r.data && r.data.videoId); };
  const sameList = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
  // Wait until the queue has stopped changing (row count and order steady for a moment). A slow or busy tab can
  // take a second or more to redraw after a menu action, and acting on a half-drawn queue is what scrambles a move.
  async function settleQueue(ms = 3500) {
    const end = Date.now() + ms;
    let prev = null, since = Date.now();
    for (;;) {
      const sig = upcomingIds().join(",");
      if (sig !== prev) { prev = sig; since = Date.now(); }
      else if (Date.now() - since >= 400) return;
      if (Date.now() > end) return;
      await sleep(80);
    }
  }
  // Make the upcoming tracks play in the order `want` (a list of videoIds), using only menu actions.
  async function buildOrder(want) {
    const ids = upcomingIds();
    const U = ids.map((v) => ({ videoId: v }));
    const used = new Set(), target = [];
    for (const v of want) {
      const k = U.findIndex((u, i) => u.videoId === v && !used.has(i));
      if (k < 0) return { ok: false, error: "queue_changed" };
      used.add(k);
      target.push(U[k]);
    }
    if (target.length !== U.length) return { ok: false, error: "queue_changed" };
    const len = U.length;
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
    let dup = null;   // does an insert duplicate the row (true) or move it (false)?
    for (const entry of seq) {
      const before = upcoming().rows.length;
      const r = await rowMenu(queueRows().find((x) => x.data && x.data.videoId === entry.videoId),
        useFront ? "Play next" : "Add to queue");
      if (!r.ok) return { ok: false, error: r.error, step: "insert" };
      const changed = await waitFor(() => upcoming().rows.length !== before, 2500);
      if (dup === null) dup = !!changed && upcoming().rows.length > before;
      if (dup) await waitFor(() => upcoming().rows.length === before + 1, 1500);
      await settleQueue(1500);
    }
    if (dup) {
      // Old rows now sit after the front-inserted copies, or before the end-appended ones.
      const idx = useFront ? seq.map((e) => seq.length + U.indexOf(e)) : seq.map((e) => U.indexOf(e));
      for (const i of idx.sort((a, b) => b - a)) {
        const { rows, cur: c } = upcoming();
        const before = rows.length;
        const r = await rowMenu(rows[c + 1 + i], "Remove from queue");
        if (!r.ok) return { ok: false, error: r.error, step: "remove" };
        await waitFor(() => upcoming().rows.length < before, 2500);
        await settleQueue(1500);
      }
    }
    return { ok: true, steps: seq.length * (dup ? 2 : 1), via: useFront ? "front" : "end" };
  }

  async function queueMove(spec) {
    const { rows: rows0, cur } = upcoming();
    const src = findRow({ ...spec, section: "queue" });
    if (!src) return { ok: false, error: "not_in_queue" };
    const fromAbs = rows0.indexOf(src);
    if (fromAbs <= cur) return { ok: false, error: "cannot_move_played_or_current" };
    const base = cur + 1;
    const ids = rows0.slice(base).map((r) => r.data.videoId);
    const len = ids.length;
    const from = fromAbs - base;
    const dest = Math.max(0, Math.min(len - 1, Number(spec.to) - base));
    if (from === dest) return { ok: true, noop: true };
    const want = ids.slice();
    want.splice(dest, 0, want.splice(from, 1)[0]);

    const built = await buildOrder(want);
    if (!built.ok) { await settleQueue(); return built; }
    await settleQueue();
    let got = upcomingIds();
    // If the queue didn't end up exactly right (a leftover copy, or a row that landed one place off), correct it from
    // what's really there instead of giving up.
    for (let attempt = 0; !sameList(got, want) && attempt < 3; attempt++) {
      if (got.length > want.length) {
        let i = got.findIndex((v, k) => v !== want[k]);
        if (i < 0) i = got.length - 1;
        const { rows, cur: c } = upcoming();
        const r = await rowMenu(rows[c + 1 + i], "Remove from queue");
        if (!r.ok) break;
      } else if (got.length === want.length) {
        const r = await buildOrder(want);
        if (!r.ok) break;
      } else break;
      await settleQueue();
      got = upcomingIds();
    }
    const ok = sameList(got, want);
    return { ok, steps: built.steps, via: built.via, ...(ok ? {} : { error: "order_mismatch", got }) };
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
    const bar = document.querySelector("ytmusic-player-bar") || deepAll(document, (el) => el.tagName === "YTMUSIC-PLAYER-BAR")[0] || null;
    out.ext = EXT_VERSION;
    out.playerBar = bar ? { attrs: attrs(bar), props: interestingProps(bar) } : null;
    const q = document.querySelector("ytmusic-player-queue");
    out.queuePanel = q ? { attrs: attrs(q), props: interestingProps(q) } : null;
    const btnPred = (el) => /^(button|tp-yt-paper-icon-button|ytmusic-toggle-button-renderer|tp-yt-paper-toggle-button|yt-button-shape)$/i.test(el.tagName);
    const describe = (el) => ({ tag: el.tagName.toLowerCase(), id: el.id || undefined, cls: [...(el.classList || [])].join(" ") || undefined,
      aria: el.getAttribute("aria-label") || undefined, title: el.getAttribute("title") || undefined,
      pressed: el.getAttribute("aria-pressed") || el.getAttribute("aria-checked") || el.getAttribute("active") || undefined,
      in: chain(el).slice(1, 4).join(" < ") });
    out.barButtons = bar ? deepAll(bar, btnPred).map(describe).slice(0, 40) : [];
    out.foundBar = !!document.querySelector("ytmusic-player-bar") ? "light-dom" : (deepAll(document, (el) => el.tagName === "YTMUSIC-PLAYER-BAR").length ? "shadow-dom" : "missing");
    out.controls = deepAll(document, (el) => btnPred(el) && /shuffle|repeat|loop|autoplay|automix/i.test((el.getAttribute("aria-label") || "") + " " + (el.getAttribute("title") || "") + " " + (el.className || "") + " " + (el.id || ""))).map(describe).slice(0, 30);
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
    // What this page is built from: every custom element present (so a layout without the usual player bar can be mapped),
    // and anything docked to the bottom of the window.
    const tags = {};
    deepAll(document, (el) => { if (el.tagName.includes("-")) tags[el.tagName.toLowerCase()] = (tags[el.tagName.toLowerCase()] || 0) + 1; return false; });
    out.tags = Object.fromEntries(Object.entries(tags).sort((x, y) => y[1] - x[1]).slice(0, 150));
    out.viewport = [innerWidth, innerHeight];
    const wiz = document.querySelector("ytmusic-wiz-player-controls");
    out.wiz = wiz ? deepAll(wiz, (el) => /^(button|tp-yt-paper-icon-button|ytmusic-like-button-renderer|yt-button-shape|toggle-button-view-model|button-view-model)$/i.test(el.tagName)).map(describe).slice(0, 40) : null;
    const lr = likeRenderer();
    out.likeRenderer = lr ? { attrs: attrs(lr), in: chain(lr).slice(0, 5).join(" < "), buttons: deepAll(lr, (e) => e.tagName === "BUTTON").map(describe) } : null;
    const at = autoplayToggle();
    out.autoplayToggle = at ? { attrs: attrs(at), checked: at.checked, disabled: at.disabled, visible: !!at.offsetParent, in: chain(at).slice(0, 4).join(" < ") } : null;
    out.state = { repeat: repeatMode(), shuffle: shuffleOn(), autoplay: autoplayOn(), liked: likeStatus() };
    // only the few elements that could be a player bar are measured (measuring every custom element is slow on a big page)
    out.docked = deepAll(document, (el) => { try {
      if (!/player|bar|mini|control/i.test(el.tagName)) return false;
      const cs = getComputedStyle(el); const r = el.getBoundingClientRect();
      return cs.position === "fixed" && r.bottom >= innerHeight - 4 && r.height > 20 && r.height < 200; } catch (e) { return false; } })
      .map((el) => el.tagName.toLowerCase() + (el.id ? "#" + el.id : "")).slice(0, 12);
    const vmx = likeVm();
    out.likeVm = vmx ? { like: vmButton(vmx.like) ? describe(vmButton(vmx.like)) : null, dislike: vmButton(vmx.dislike) ? describe(vmButton(vmx.dislike)) : null,
      chain: chain(vmx.like).slice(0, 6).join(" < ") } : null;
    // For Refine's restyling: the progress slider, the docked player, the top bar and the page's colour variables.
    const box = (el) => { const r = el.getBoundingClientRect(); return [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]; };
    const kids = (el) => [...el.children].slice(0, 10).map((c) => c.tagName.toLowerCase() + (c.id ? "#" + c.id : "") +
      (c.classList && c.classList.length ? "." + [...c.classList].slice(0, 2).join(".") : ""));
    out.sliders = deepAll(document, (el) => /^(SLIDER-SHAPE|TP-YT-PAPER-SLIDER)$/.test(el.tagName) || el.getAttribute("role") === "slider")
      .slice(0, 8).map((el) => ({ tag: el.tagName.toLowerCase(), attrs: attrs(el), box: box(el), kids: kids(el), shadow: !!el.shadowRoot,
        in: chain(el).slice(1, 6).join(" < ") }));
    const mini = document.querySelector("ytmusic-miniplayer");
    out.miniplayer = mini ? { attrs: attrs(mini), box: box(mini), kids: kids(mini), z: getComputedStyle(mini).zIndex } : null;
    // the newer bar's progress line (no slider element): its first levels, to see where Refine's wave can sit
    const pw = mini && mini.querySelector("[class*='ProgressBarWrapper']");
    const tree = (el, d) => d > 3 ? null : { tag: el.tagName.toLowerCase(), cls: (typeof el.className === "string" ? el.className : "").slice(0, 120),
      role: el.getAttribute("role"), box: box(el), kids: [...el.children].slice(0, 6).map((c) => tree(c, d + 1)) };
    out.progressWrap = pw ? tree(pw, 0) : null;
    const nav = document.querySelector("ytmusic-nav-bar");
    out.navBar = nav ? { kids: kids(nav), right: (nav.querySelector("#right-content, .right-content") ? kids(nav.querySelector("#right-content, .right-content")) : null) } : null;
    try {
      const cs = getComputedStyle(document.documentElement), vars = [];
      for (let i = 0; i < cs.length && vars.length < 300; i++) if (/^--(ytmusic|yt-spec)/.test(cs[i])) vars.push(cs[i]);
      out.cssVars = vars;
    } catch (e) { out.cssVars = "err"; }
    try { const rf = window[Symbol.for("pocketfader.refine")]; out.refine = rf ? rf.report() : null; } catch (e) { out.refine = "err"; }
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
        case "search": return { items: await search(cmd.query, cmd.filter) };
        case "speed": return setSpeed(cmd.rate);
        case "lucky": return await lucky();
        case "resolveSongs": return await resolveSongs(cmd.queries);
        case "artist": {
          let id = cmd.browseId;
          if (!id && cmd.videoId) { const a = await artistFor(cmd.videoId).catch(() => null); id = a && a.id; }
          if (!id && cmd.name) { const r = await search(cmd.name).catch(() => []); const hit = r.find((x) => x.kind === "artist"); id = hit && hit.browseId; }
          if (!id) return { error: "artist_not_found" };
          return await artistPage(id);
        }
        case "suggest": return { suggestions: await searchSuggestions(cmd.query) };
        case "like": {
          // The InnerTube like endpoint returns 200 but doesn't actually register, so for the
          // currently-playing song we click the real player-bar like/dislike button (proven to work).
          const cur = mp() && mp().getVideoData && mp().getVideoData().video_id;
          const vm = likeVm();
          if (vm && vmButton(vm.like) && (!cmd.videoId || cmd.videoId === cur)) {
            const lbtn = vmButton(vm.like), dbtn = vmButton(vm.dislike);
            const want = cmd.action === "dislike" ? "DISLIKE" : cmd.action === "remove" ? "INDIFFERENT" : "LIKE";
            const cur2 = () => isPressed(lbtn) ? "LIKE" : isPressed(dbtn) ? "DISLIKE" : "INDIFFERENT";
            if (cur2() !== want) {
              if (want === "LIKE") lbtn.click();
              else if (want === "DISLIKE") (dbtn || lbtn).click();
              else if (cur2() === "LIKE") lbtn.click();
              else if (dbtn) dbtn.click();
              await waitFor(() => cur2() === want, 1500);
            }
            return { ok: cur2() === want, liked: cur2(), via: "view-model" };
          }
          const lb = likeRenderer();
          if (!lb || (cmd.videoId && cmd.videoId !== cur)) {
            const vid = cmd.videoId || cur;
            if (!vid) return { ok: false, error: "no_like_button" };
            const ep = cmd.action === "dislike" ? "like/dislike" : cmd.action === "remove" ? "like/removelike" : "like/like";
            const want = cmd.action === "dislike" ? "DISLIKE" : cmd.action === "remove" ? "INDIFFERENT" : "LIKE";
            const r = await innertube(ep, { target: { videoId: vid } }, true);
            if (r && r.error) return { ok: false, error: "like_failed: " + (r.error.message || r.error.code || "") };
            await sleep(500);
            const st = await apiLikeStatus(vid).catch(() => null);
            likeApi.vid = vid; likeApi.status = st; likeApi.at = Date.now();
            return { ok: st === want, liked: st, via: "api", ...(st === want ? {} : { error: "like_not_applied" }) };
          }
          if (lb && (!cmd.videoId || cmd.videoId === cur)) {
            const status = () => lb.getAttribute("like-status") || (lb.data && lb.data.likeStatus) || "INDIFFERENT";
            let btns = lb.querySelectorAll("button");   // [0] = like, [1] = dislike
            if (btns.length < 2) btns = deepAll(lb, (e) => e.tagName === "BUTTON");
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
        case "playlistItems": return { items: await playlistItems(cmd.playlistId || cmd.browseId, cmd.limit) };
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
  // Which parts of YTM Starcast are switched on, as the app last said (an app from before the switches says nothing: all on).
  const cfg = { remote: true, discord: true, refine: true };
  window.addEventListener("message", async (ev) => {
    if (ev.source !== window) return;
    const d = ev.data;
    if (!d || typeof d !== "object") return;
    if (d.__vmr === "cfg" && d.cfg && typeof d.cfg === "object") {
      for (const k of Object.keys(cfg)) cfg[k] = d.cfg[k] !== false;
      return;
    }
    // the tab came back from the browser's back/forward cache after saying goodbye: send everything again
    if (d.__vmr === "resend") { last = ""; lastFast = ""; return; }
    // an older copy's bridge (its own page script runs that command), or an older copy's state: two copies are loaded
    if (d.__vmr === "cmd") { twin(); return; }
    if (d.__vmr === "state") {
      const v = d.state && d.state.now && d.state.now.ext;
      if (v && v !== EXT_VERSION) twin(String(v));
      return;
    }
    if (d.__vmr !== "starcast-cmd" || !d.command) return;
    const data = await handle(d.command);
    window.postMessage({ __vmr: "result", id: d.command.id, data }, "*");
  });

  // How hard the page is working: long main-thread stalls (any script, YouTube's own included) and the longest of ours.
  // Reported with the state, so a frozen tab can be traced to us or ruled out.
  const perf = { longTasks: 0, longestMs: 0, ourMaxMs: 0, since: Date.now() };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) { perf.longTasks++; if (e.duration > perf.longestMs) perf.longestMs = Math.round(e.duration); }
    }).observe({ type: "longtask", buffered: true });
  } catch (e) {}
  const timed = (fn) => { const t0 = performance.now(); try { fn(); } finally { const d = performance.now() - t0; if (d > perf.ourMaxMs) perf.ourMaxMs = Math.round(d); } };

  // Push now-playing + queue on a heartbeat so connected clients stay live. While the tab is in the background this
  // slows to every 3 s and the fast beat stops: the page should be left alone when nobody is looking at it.
  let last = "", beat = 0;
  setInterval(() => timed(() => {
    if (document.hidden && (beat++ % 3) !== 0) return;
    const state = { now: nowPlaying(), queue: readQueue(), ts: Date.now(), perf };
    const n = state.now;
    const sig = JSON.stringify([n.videoId, n.title, n.artist, n.playing, n.liked, n.repeat, n.shuffle, n.autoplay, n.speed, n.art,
      n.album, n.ad, state.queue.map((q) => q.videoId + q.section[0] + (q.selected ? "*" : "")), Math.round(n.position)]);
    if (sig === last) return; // only push on change (position rounds to whole seconds)
    last = sig;
    window.postMessage({ __vmr: "state", state }, "*");
  }), 1000);

  // A faster, now-playing-only beat so the playback position the server hands out (and the lyrics that
  // follow it) is at most a quarter of a second old. The full state above still carries the queue.
  // With both the remote and Discord status switched off nobody needs that precision, so it rests.
  let lastFast = "";
  setInterval(() => timed(() => {
    if (document.hidden || (!cfg.remote && !cfg.discord)) return;
    const now = nowPlaying();
    const sig = JSON.stringify([now.videoId, now.playing, now.ad, Math.round(now.position * 4)]);
    if (sig === lastFast) return;
    lastFast = sig;
    window.postMessage({ __vmr: "state", state: { now, ts: Date.now(), partial: true } }, "*");
  }), 250);

  // A snapshot of how this page is laid out, sent once after load, so controls that can't be found can be diagnosed
  // without asking anyone to open developer tools.
  const sendLayout = () => {
    (async () => {
      try {
        const d = probe();
        const vid = curVideo();
        if (vid) {
          try {
            const nx = await innertube("next", { videoId: vid, isAudioOnly: true }, true);
            const lb = deepFind(nx, "likeButtonRenderer", [])[0];
            const ent = deepFind(nx, "likeStatusEntity", [])[0];
            d.likeApi = { keys: Object.keys(nx || {}).slice(0, 12), found: !!lb, likeStatus: lb && lb.likeStatus, lbKeys: lb ? Object.keys(lb) : null,
              entity: ent ? { likeStatus: ent.likeStatus, keys: Object.keys(ent) } : null,
              musicLike: deepFind(nx, "musicLikeButtonRenderer", []).length };
          } catch (e) { d.likeApi = { error: String(e && e.message || e) }; }
        }
        window.postMessage({ __vmr: "state", state: { diag: d, ts: Date.now(), partial: true, now: nowPlaying() } }, "*");
      } catch (e) {}
    })();
  };
  // once, a few seconds after load, and only while the tab is actually in front (a hidden tab shouldn't do page-wide work)
  const layoutSoon = () => setTimeout(() => { if (document.hidden) document.addEventListener("visibilitychange", layoutSoon, { once: true }); else sendLayout(); }, 12000);
  layoutSoon();

  // The page helpers Refine (refine.js, the same page world) builds on. Read-only; nothing here is new power for the page,
  // which can already reach the same endpoints with its own login.
  try {
    Object.defineProperty(window, Symbol.for("pocketfader.api"), { configurable: true, value: Object.freeze({
      innertube, deepFind, deepAll, runText, pickThumb, bigArt, extractItem, nowPlaying, readQueue, playlists, playlistItems,
      play, startPlaylist, playlistCreate, playlistEdit, lucky, mp, curVideo, curPlaylist, version: EXT_VERSION }) });
  } catch (e) {}

  if (window.__pfTest) window.__pfHandle = handle;   // lets the test-suite drive commands directly
  window.postMessage({ __vmr: "ready" }, "*");
  console.log("[YTM Starcast] YouTube Music page bridge ready");
})();
