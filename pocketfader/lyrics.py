"""Lyrics for the track that's playing.

Several providers are asked at the same time and the answers are kept together, so a client can
switch between them instantly. Time-synced lyrics (LRC) are preferred; plain text is the fallback.
Romanization and translations are included when a provider supplies them.

Providers are third-party services and are best-effort: any of them can be slow, change, or have
nothing for a song. Only the track's title, artist, album and length are ever sent to them.
"""
import json
import re
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from . import __version__
from .romanize import romanize

PROVIDERS = (("lrclib", "LRCLIB"), ("netease", "NetEase"), ("ytm", "YouTube Music"))
_BROWSER_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def http_get(url, headers=None, timeout=6.0):
    """GET `url` and return the body text, or None on any failure."""
    h = {"User-Agent": f"Pocketfader/{__version__}", "Accept": "application/json"}
    h.update(headers or {})
    try:
        with urlopen(Request(url, headers=h), timeout=timeout) as r:  # noqa: S310 (fixed https hosts only)
            return r.read(2_000_000).decode("utf-8", "replace")
    except Exception:
        return None


def _json(text):
    try:
        return json.loads(text) if text else None
    except ValueError:
        return None


# ------------------------------------------------------------------ LRC
_STAMP = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
_TAG = re.compile(r"^\[[A-Za-z]+:[^\]]*\]$")
_WORD = re.compile(r"<\d+:\d+(?:[.:]\d+)?>")
_HAN = re.compile("[\u3400-\u9fff\uf900-\ufaff]")


def parse_lrc(text):
    """[(seconds, line)] sorted by time. Metadata tags and word-level stamps are dropped."""
    out = []
    for raw in (text or "").splitlines():
        raw = raw.strip()
        if not raw or _TAG.match(raw):
            continue
        stamps = _STAMP.findall(raw)
        if not stamps:
            continue
        body = _WORD.sub("", _STAMP.sub("", raw)).strip()
        for m, s, frac in stamps:
            t = int(m) * 60 + int(s) + (int(frac.ljust(3, "0")[:3]) / 1000.0 if frac else 0.0)
            out.append((t, body))
    out.sort(key=lambda x: x[0])
    return out


def _attach(lines, extra, key):
    """Add the lines of a second LRC (translation / romaji) to matching timestamps."""
    if not extra:
        return
    pool = [(t, s) for t, s in parse_lrc(extra) if s]
    if not pool:
        return
    for ln in lines:
        if ln["t"] is None:
            continue
        best = min(pool, key=lambda p: abs(p[0] - ln["t"]))
        if abs(best[0] - ln["t"]) <= 0.6 and best[1] != ln["text"]:
            ln[key] = best[1]


def _plain_lines(text):
    return [{"t": None, "text": ln.rstrip()} for ln in (text or "").splitlines()]


def _result(source, label, synced_text=None, plain_text=None, translation=None, roman=None, credit=None):
    lines = []
    if synced_text:
        lines = [{"t": round(t, 2), "text": s} for t, s in parse_lrc(synced_text)]
    synced = bool(lines)
    if not synced and plain_text:
        lines = _plain_lines(plain_text)
    if not any(ln["text"].strip() for ln in lines):
        return None
    _attach(lines, translation, "tr")
    _attach(lines, roman, "rom")
    for ln in lines:
        if "rom" not in ln and not _HAN.search(ln["text"]):
            r = romanize(ln["text"])
            if r:
                ln["rom"] = r
                ln["_d"] = True      # derived here, so a provider's own romanization may replace it
    out = {"source": source, "label": label, "synced": synced, "lines": lines}
    if credit:
        out["credit"] = credit
    return out


# ------------------------------------------------------------------ matching
def _norm(s):
    s = unicodedata.normalize("NFKC", s or "").casefold()
    s = re.sub(r"[\(\[\{].*?[\)\]\}]", " ", s)
    s = re.sub(r"\b(feat|ft|featuring|with)\b.*$", " ", s)
    return re.sub(r"[\W_]+", " ", s).strip()


def _score(cand_title, cand_artists, cand_dur, title, artist, dur):
    ct, t = _norm(cand_title), _norm(title)
    if not ct or not t:
        return -1.0
    if ct == t:
        score = 1.0
    elif ct in t or t in ct:
        score = 0.6
    else:
        return -1.0
    a = set(_norm(artist).split())
    ca = set(_norm(" ".join(cand_artists or [])).split())
    if a and ca and a & ca:
        score += 0.5
    elif a and ca:
        score -= 0.2
    if dur and cand_dur:
        d = abs(cand_dur - dur)
        score += 0.4 if d <= 3 else 0.2 if d <= 8 else (-0.5 if d > 20 else 0.0)
    return score


# ------------------------------------------------------------------ providers
def lrclib(fetch, title, artist, album, duration, **_):
    q = {"track_name": title, "artist_name": artist}
    if album:
        q["album_name"] = album
    if duration:
        q["duration"] = int(round(duration))
    rec = _json(fetch("https://lrclib.net/api/get?" + urlencode(q)))
    if not isinstance(rec, dict) or not (rec.get("syncedLyrics") or rec.get("plainLyrics")):
        found = _json(fetch("https://lrclib.net/api/search?" + urlencode({"track_name": title, "artist_name": artist})))
        best, best_score = None, 0.5
        for c in (found if isinstance(found, list) else []):
            if not (c.get("syncedLyrics") or c.get("plainLyrics")):
                continue
            sc = _score(c.get("trackName", ""), [c.get("artistName", "")], c.get("duration"), title, artist, duration)
            if c.get("syncedLyrics"):
                sc += 0.1
            if sc > best_score:
                best, best_score = c, sc
        rec = best
    if not rec:
        return None
    return _result("lrclib", "LRCLIB", rec.get("syncedLyrics"), rec.get("plainLyrics"))


_NE_HEADERS = {"User-Agent": _BROWSER_UA, "Referer": "https://music.163.com/", "Cookie": "appver=2.0.2"}


def netease(fetch, title, artist, album, duration, **_):
    s = _json(fetch("https://music.163.com/api/search/get?" +
                    urlencode({"s": f"{title} {artist}".strip(), "type": 1, "limit": 8, "offset": 0}), _NE_HEADERS))
    songs = ((s or {}).get("result") or {}).get("songs") or []
    best, best_score = None, 0.5
    for c in songs:
        artists = [a.get("name", "") for a in (c.get("artists") or c.get("ar") or [])]
        dur = (c.get("duration") or c.get("dt") or 0) / 1000.0
        sc = _score(c.get("name", ""), artists, dur, title, artist, duration)
        if sc > best_score:
            best, best_score = c, sc
    if not best:
        return None
    lyr = _json(fetch("https://music.163.com/api/song/lyric?" +
                      urlencode({"id": best["id"], "lv": 1, "kv": 1, "tv": -1, "rv": 1}), _NE_HEADERS))
    if not isinstance(lyr, dict):
        return None
    pick = lambda k: ((lyr.get(k) or {}).get("lyric") or "")  # noqa: E731
    main = pick("lrc")
    if not main:
        return None
    return _result("netease", "NetEase", synced_text=main if _STAMP.search(main) else None,
                   plain_text=None if _STAMP.search(main) else main,
                   translation=pick("tlyric"), roman=pick("romalrc"))


def ytm(fetch, title, artist, album, duration, video_id="", ytm_lyrics=None, **_):
    """Lyrics shown on the YouTube Music page itself, fetched by the browser extension."""
    if not (video_id and ytm_lyrics):
        return None
    r = ytm_lyrics(video_id)
    if not isinstance(r, dict) or r.get("error"):
        return None
    return _result("ytm", "YouTube Music", plain_text=r.get("text"), credit=r.get("credit"))


_FUNCS = {"lrclib": lrclib, "netease": netease, "ytm": ytm}


# ------------------------------------------------------------------ service
class LyricsService:
    def __init__(self, fetch=None, ytm_lyrics=None, ttl=6 * 3600, max_entries=300):
        self.fetch = fetch or http_get
        self.ytm_lyrics = ytm_lyrics
        self.ttl, self.max = ttl, max_entries
        self._cache = {}
        self._lock = threading.Lock()

    def _gather(self, title, artist, album, duration, video_id):
        results = {}

        def run(pid):
            try:
                return pid, _FUNCS[pid](self.fetch, title, artist, album, duration,
                                        video_id=video_id, ytm_lyrics=self.ytm_lyrics)
            except Exception:
                return pid, None

        pool = ThreadPoolExecutor(max_workers=len(PROVIDERS))
        futures = [pool.submit(run, pid) for pid, _ in PROVIDERS]
        deadline = time.time() + 9.0
        for f in futures:
            try:
                pid, res = f.result(timeout=max(0.1, deadline - time.time()))
                results[pid] = res
            except (FutureTimeout, Exception):
                pass
        pool.shutdown(wait=False)
        return results

    def lookup(self, title, artist="", album="", duration=0.0, video_id="", source=None, refresh=False):
        key = (_norm(title), _norm(artist), int(round(duration or 0)) // 3)
        now = time.time()
        with self._lock:
            hit = self._cache.get(key)
            if hit and not refresh and now - hit[0] < self.ttl:
                results = hit[1]
            else:
                results = None
        if results is None:
            results = self._gather(title, artist, album, duration or 0, video_id)
            with self._lock:
                if len(self._cache) >= self.max:
                    self._cache.pop(min(self._cache, key=lambda k: self._cache[k][0]), None)
                self._cache[key] = (now, results)
        sources = [{"id": pid, "label": label, "found": bool(results.get(pid)),
                    "synced": bool(results.get(pid) and results[pid]["synced"])} for pid, label in PROVIDERS]
        chosen = results.get(source) if source else None
        if not chosen:
            found = [results[p] for p, _ in PROVIDERS if results.get(p)]
            chosen = next((r for r in found if r["synced"]), found[0] if found else None)
        return {"found": bool(chosen), "source": chosen["source"] if chosen else None,
                "sources": sources, "lyrics": self._finish(chosen, results)}

    @staticmethod
    def _finish(chosen, results):
        """A copy of the chosen lyrics, borrowing real romanization / translations from the other
        providers when this one has none (matched by line timing)."""
        if not chosen:
            return None
        lines = [dict(ln) for ln in chosen["lines"]]
        if chosen["synced"]:
            for other in results.values():
                if not other or other is chosen or not other["synced"]:
                    continue
                pool = [ln for ln in other["lines"] if ln["t"] is not None]
                for ln in lines:
                    if ln["t"] is None or not ln["text"].strip():
                        continue
                    need_rom = "rom" not in ln or ln.get("_d")
                    if not (need_rom or "tr" not in ln):
                        continue
                    near = min(pool, key=lambda p: abs(p["t"] - ln["t"]), default=None)
                    if near is None or abs(near["t"] - ln["t"]) > 0.8:
                        continue
                    if need_rom and near.get("rom") and not near.get("_d"):
                        ln["rom"] = near["rom"]
                        ln.pop("_d", None)
                    if "tr" not in ln and near.get("tr"):
                        ln["tr"] = near["tr"]
        for ln in lines:
            ln.pop("_d", None)
        return {**chosen, "lines": lines}
