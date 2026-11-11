"""Turns the song being followed into the Discord activity ("Listening to ..." card).

The logo and the play / pause badge are sent as web addresses served from this project's public repository
(assets/discord/). Discord fetches them itself, so nobody has to upload art to a Discord application. A release
points at its own tag, so the pictures it shows never change under it; other builds use the main branch.
"""
import json
import re
import urllib.parse

from .. import __version__
from .settings import STATUS_DETAILS, STATUS_STATE, trim
from .tracker import STATE_PAUSED, truncate

TYPE_PLAYING = 0
TYPE_LISTENING = 2

STATUS_DISPLAY_NAME = 0       # never sent: it is Discord's default
STATUS_DISPLAY_STATE = 1
STATUS_DISPLAY_DETAILS = 2

BUTTON_LABEL = "Listen on YouTube Music"
PRIVATE_DETAILS = "Listening to music"

# Why a song that is playing or paused is not on Discord. The engine adds OFF and SNOOZED (the user's own
# switches); the rest come from the settings.
HIDDEN_OFF = "off"
HIDDEN_SNOOZED = "snoozed"
HIDDEN_PAUSED = "paused"
HIDDEN_PAUSED_TIMEOUT = "paused-timeout"
HIDDEN_FILTERED = "filtered"

REPO_RAW = "https://raw.githubusercontent.com/dash1101/ytm-starcast/"
ASSET_DIR = "assets/discord/"

_RELEASE_TAG = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?")
_PEP440 = re.compile(r"([0-9]+)\.([0-9]+)\.([0-9]+)(?:(a|b|rc)([0-9]+))?")
_STAGES = {"a": "alpha", "b": "beta", "rc": "rc"}


def release_tag(version):
    """The git tag of a version: "0.2.0a1" -> "v0.2.0-alpha.1", "1.0.0" -> "v1.0.0". A version that already
    looks like a tag ("v0.1.5") is kept. Anything else (a development build) gives ""."""
    v = str(version or "")
    if _RELEASE_TAG.fullmatch(v):
        return v
    m = _PEP440.fullmatch(v)
    if not m:
        return ""
    tag = "v%s.%s.%s" % m.group(1, 2, 3)
    if m.group(4):
        tag += "-%s.%s" % (_STAGES[m.group(4)], m.group(5))
    return tag


def asset_base_for(version):
    tag = release_tag(version)
    ref = tag if tag and _RELEASE_TAG.fullmatch(tag) else "main"
    return REPO_RAW + ref + "/" + ASSET_DIR


class AssetURLs:
    """The three pictures the card uses, as web addresses under one base."""

    def __init__(self, base):
        base = str(base)
        if not base.endswith("/"):
            base += "/"
        self.base = base
        self.logo = base + "logo.png"
        self.play = base + "play.png"
        self.pause = base + "pause.png"

    def __repr__(self):
        return "AssetURLs(%r)" % self.base


DEFAULT_ASSETS = AssetURLs(asset_base_for(__version__))


def field(s):
    """Fits text into what Discord accepts (2-128 characters)."""
    s = trim(s or "")
    if not s:
        return ""
    if len(s) > 100:
        s = s[:99] + "\u2026"
    if len(s) < 2:
        s += "\u200b"    # zero-width space: Discord rejects one-character fields
    return s


def track_url(sel):
    if sel.video_id:
        return "https://music.youtube.com/watch?v=" + sel.video_id
    q = truncate((sel.artist or "") + " " + (sel.title or ""), 120)
    return "https://music.youtube.com/search?q=" + urllib.parse.quote_plus(q, safe="")


def matches_hidden(words, sel):
    if not words:
        return False
    hay = ((sel.title or "") + "\n" + (sel.artist or "") + "\n" + (sel.album or "")).lower()
    for w in words:
        w = trim(w or "").lower()
        if w and w in hay:
            return True
    return False


def hidden_reason(cfg, sel, now):
    """Why the settings keep this song off Discord ("" = show it)."""
    if matches_hidden(cfg.get("hideMatching"), sel):
        return HIDDEN_FILTERED
    if sel.state == STATE_PAUSED:
        if not cfg.get("showPaused"):
            return HIDDEN_PAUSED
        limit = cfg.get("pauseTimeoutMinutes") or 0
        since = sel.paused_since if sel.paused_since is not None else 0.0
        if limit > 0 and now - since > limit * 60:
            return HIDDEN_PAUSED_TIMEOUT
    return ""


def _start_ms(received_at, position):
    """Unix milliseconds of (received_at - position), like Go's ReceivedAt.Add(-position).UnixMilli()."""
    recv_ns = round((received_at or 0.0) * 1e9)
    pos_ns = int(position * 1e9)            # truncated toward zero, like time.Duration(float)
    return (recv_ns - pos_ns) // 1000000


def _add_state_icon(cfg, assets, paused, basic, art):
    if basic or not cfg.get("showStateIcon"):
        return
    if paused:
        assets["small_image"], assets["small_text"] = art.pause, "Paused"
    else:
        assets["small_image"], assets["small_text"] = art.play, "Playing"


def _private(cfg, paused, basic, art):
    act = {"type": TYPE_LISTENING, "details": PRIVATE_DETAILS}
    assets = {}
    if not basic:
        assets["large_image"] = art.logo
    _add_state_icon(cfg, assets, paused, basic, art)
    if assets:
        act["assets"] = assets
    act["instance"] = False
    return act


def build_activity(cfg, sel, now, basic=False, assets=None):
    """The Discord activity for the selected song, or None when nothing should be shown. Returns
    (activity, start) where start is the start timestamp in milliseconds (0 when none), which the engine uses
    to notice seeks. basic=True leaves out the newer, optional fields (for Discord clients that reject them).

    The activity is a dict in Discord's field order; empty fields are left out and "instance" is always false.
    """
    art = assets or DEFAULT_ASSETS
    if hidden_reason(cfg, sel, now):
        return None, 0
    paused = sel.state == STATE_PAUSED
    if cfg.get("privateMode"):
        return _private(cfg, paused, basic, art), 0

    act = {"type": TYPE_LISTENING}
    if not basic:
        if cfg.get("statusDisplay") == STATUS_STATE:
            act["status_display_type"] = STATUS_DISPLAY_STATE
        elif cfg.get("statusDisplay") == STATUS_DETAILS:
            act["status_display_type"] = STATUS_DISPLAY_DETAILS
    details, state = field(sel.title), field(sel.artist)
    if details:
        act["details"] = details
    if state:
        act["state"] = state

    start = 0
    if not paused and cfg.get("showProgress"):
        start = _start_ms(sel.received_at, sel.position)
        ts = {}
        if start:
            ts["start"] = start
        if sel.duration > 0 and sel.position <= sel.duration + 1:
            end = start + int(sel.duration * 1000)
            if end:
                ts["end"] = end
        act["timestamps"] = ts

    a = {}
    if cfg.get("showAlbumArt") and sel.artwork:
        a["large_image"] = sel.artwork
    elif not basic:
        a["large_image"] = art.logo
    if a.get("large_image"):
        text = field(sel.album or sel.title)
        if text:
            a["large_text"] = text
    _add_state_icon(cfg, a, paused, basic, art)
    if a:
        act["assets"] = a

    if cfg.get("showButtons"):
        act["buttons"] = [{"label": BUTTON_LABEL, "url": track_url(sel)}]
    act["instance"] = False
    return act, start


def activity_key(act):
    """Identifies what an activity shows, ignoring the exact start time (it drifts by a few milliseconds
    between reports): "" for None."""
    if act is None:
        return ""
    c = dict(act)
    ts = c.pop("timestamps", None)
    has_start = bool(ts and ts.get("start", 0) != 0)
    has_end = bool(ts and ts.get("end", 0) != 0)
    body = json.dumps(c, ensure_ascii=False, separators=(",", ":"))
    return "%s|%s|%s" % (body, "true" if has_start else "false", "true" if has_end else "false")
