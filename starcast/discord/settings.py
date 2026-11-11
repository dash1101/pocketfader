"""Discord status settings: the same keys, defaults and rules as YTM Beacon's config.json.

The file is a small JSON object (see KEYS). Missing keys keep their defaults, so a file written by an older
version (YTM Beacon 0.1.0 wrote only six keys) comes out with the newer settings switched on, not off.
Changes go through Store.update(), which validates, saves and swaps in one step: nothing changes on any
failure, and two changes arriving together never undo each other.
"""
import json
import os
import re
import sys
import threading

DEFAULT_CLIENT_ID = "1567329407901634670"   # public Discord application id (not a secret); never change it

# Environment variables that override the application id, checked in this order. The second is YTM Beacon's.
CLIENT_ID_ENV = ("STARCAST_DISCORD_CLIENT_ID", "YTM_BEACON_CLIENT_ID")

STATUS_NAME, STATUS_STATE, STATUS_DETAILS = "name", "state", "details"
STATUS_DISPLAYS = (STATUS_NAME, STATUS_STATE, STATUS_DETAILS)

MAX_HIDE_WORDS = 50
MAX_HIDE_WORD_LEN = 80
MAX_PAUSE_TIMEOUT = 24 * 60

# (key, type) in the order the file is written.
_FIELDS = (
    ("enabled", bool),
    ("clientId", str),
    ("showButtons", bool),
    ("showPaused", bool),
    ("pauseTimeoutMinutes", int),
    ("showAlbumArt", bool),
    ("statusDisplay", str),
    ("showProgress", bool),
    ("showStateIcon", bool),
    ("privateMode", bool),
    ("hideMatching", list),
)
KEYS = tuple(k for k, _ in _FIELDS)
_TYPES = dict(_FIELDS)
_FOLDED = {k.casefold(): k for k in KEYS}

_CLIENT_ID = re.compile(r"[0-9]{15,25}")
_INT64 = 1 << 63

# Go's unicode.IsSpace set (Python's str.strip() also strips \x1c-\x1f, which Go keeps).
GO_SPACE = ("\t\n\v\f\r \x85\xa0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a"
            "\u2028\u2029\u202f\u205f\u3000")


class SettingsError(ValueError):
    """A setting that is not valid, or a settings file that cannot be used. The text is plain language."""


def trim(s):
    return s.strip(GO_SPACE)


def clean_text(s):
    """Replaces lone UTF-16 surrogates (they can arrive through JSON escapes) with U+FFFD, as Go's decoder does."""
    try:
        s.encode("utf-8")
        return s
    except UnicodeEncodeError:
        return "".join("\ufffd" if "\ud800" <= ch <= "\udfff" else ch for ch in s)


def default_config():
    return {
        "enabled": True,
        "clientId": "",
        "showButtons": True,
        "showPaused": True,
        "pauseTimeoutMinutes": 5,
        "showAlbumArt": True,
        "statusDisplay": STATUS_NAME,
        "showProgress": True,
        "showStateIcon": True,
        "privateMode": False,
        "hideMatching": [],
    }


def copy_config(c):
    """A copy that shares nothing changeable with c (the word list is copied too)."""
    out = dict(c)
    words = c.get("hideMatching")
    out["hideMatching"] = list(words) if isinstance(words, (list, tuple)) else ([] if words is None else words)
    return out


def clean_words(words):
    """Trims, lower-cases and de-duplicates the "never show" words, dropping blanks and anything past the limits.

    Always returns a list (never None), so the file and the API always show one."""
    out, seen = [], set()
    for w in words or []:
        w = clean_text(trim(w or "").lower())
        if len(w) > MAX_HIDE_WORD_LEN:
            w = w[:MAX_HIDE_WORD_LEN]
        if not w or w in seen:
            continue
        seen.add(w)
        out.append(w)
        if len(out) == MAX_HIDE_WORDS:
            break
    return out


_ZERO = {bool: False, int: 0, str: "", list: None}


def _typed(c):
    """Exactly the settings keys, each of the right type. A missing key or None is the zero value (as in a Go
    struct); a value of the wrong type is refused."""
    out = {}
    for k, want in _FIELDS:
        v = c.get(k)
        if v is None:
            v = _ZERO[want]
        elif want is int:
            if isinstance(v, bool) or not isinstance(v, int):
                raise SettingsError("%s must be a whole number" % k)
        elif want is list:
            if not isinstance(v, (list, tuple)) or not all(isinstance(w, str) or w is None for w in v):
                raise SettingsError("%s must be a list of words" % k)
            v = [w or "" for w in v]
        elif not isinstance(v, want):
            raise SettingsError("%s must be %s" % (k, "true or false" if want is bool else "text"))
        out[k] = v
    return out


def normalize(c):
    """Validates a config and clamps out-of-range numbers. Returns a new dict with exactly the settings keys;
    raises SettingsError."""
    c = _typed(c)
    cid = trim(c.get("clientId") or "")
    if cid and not _CLIENT_ID.fullmatch(cid):
        raise SettingsError("the Discord application ID should be a long number (17-19 digits), "
                            "copied from the Discord Developer Portal")
    c["clientId"] = cid
    sd = trim(c.get("statusDisplay") or "").lower()
    if sd == "":
        sd = STATUS_NAME
    if sd not in STATUS_DISPLAYS:
        raise SettingsError('statusDisplay must be "name", "state" or "details"')
    c["statusDisplay"] = sd
    n = c.get("pauseTimeoutMinutes") or 0
    c["pauseTimeoutMinutes"] = max(0, min(MAX_PAUSE_TIMEOUT, n))
    c["hideMatching"] = clean_words(c.get("hideMatching"))
    return c


def effective_client_id(c, default=DEFAULT_CLIENT_ID, environ=None):
    """The application id to use: environment variable, then the settings, then the built-in one."""
    env = os.environ if environ is None else environ
    for name in CLIENT_ID_ENV:
        v = trim(env.get(name) or "")
        if v:
            return v
    if c.get("clientId"):
        return c["clientId"]
    return default or ""


def _reject_constant(name):
    raise ValueError("invalid number " + name)


def parse_json(text):
    """Strict parse of a whole JSON text (trailing data is an error, NaN/Infinity are refused)."""
    if isinstance(text, (bytes, bytearray)):
        text = bytes(text).decode("utf-8", "replace")
    try:
        return json.loads(text, parse_constant=_reject_constant)
    except (ValueError, RecursionError) as e:
        raise SettingsError(str(e)) from None


def _json_type(v):
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "bool"
    if isinstance(v, (int, float)):
        return "number"
    if isinstance(v, str):
        return "string"
    if isinstance(v, list):
        return "array"
    return "object"


def decode_onto(base, obj):
    """Applies a decoded JSON object onto a config the way Go's json.Unmarshal fills a struct.

    Only the keys present change; keys match case-insensitively; unknown keys are ignored; null leaves a
    setting unchanged (null for hideMatching empties it, and a JSON null instead of an object changes
    nothing); a value of the wrong type is an error."""
    if obj is None:
        return copy_config(base)
    if not isinstance(obj, dict):
        raise SettingsError("cannot use a JSON %s as the settings (expected an object)" % _json_type(obj))
    c = copy_config(base)
    for raw_key, v in obj.items():
        key = raw_key if raw_key in _TYPES else _FOLDED.get(str(raw_key).casefold())
        if key is None:
            continue
        want = _TYPES[key]
        if want is list:
            if v is None:
                c[key] = []
                continue
            if not isinstance(v, list):
                raise SettingsError("%s must be a list of words, not a %s" % (key, _json_type(v)))
            words = []
            for w in v:
                if w is None:
                    w = ""
                if not isinstance(w, str):
                    raise SettingsError("%s must be a list of words, not a list of %s" % (key, _json_type(w)))
                words.append(clean_text(w))
            c[key] = words
            continue
        if v is None:
            continue
        if want is bool:
            if not isinstance(v, bool):
                raise SettingsError("%s must be true or false, not a %s" % (key, _json_type(v)))
        elif want is int:
            if isinstance(v, bool) or not isinstance(v, int):
                raise SettingsError("%s must be a whole number, not a %s" % (
                    key, "fraction" if isinstance(v, float) else _json_type(v)))
            if not -_INT64 <= v < _INT64:
                raise SettingsError("%s is out of range" % key)
        elif want is str:
            if not isinstance(v, str):
                raise SettingsError("%s must be text, not a %s" % (key, _json_type(v)))
            v = clean_text(v)
        c[key] = v
    return c


def save(path, c):
    """Writes the settings atomically: a temporary file next to it, then a rename."""
    parent = os.path.dirname(os.path.abspath(path))
    os.makedirs(parent, mode=0o700, exist_ok=True)
    data = json.dumps({k: c[k] for k in KEYS}, indent=2, ensure_ascii=False) + "\n"
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


class Store:
    """Settings shared between threads. A Store without a path never touches the disk (for tests)."""

    def __init__(self, cfg=None, path=""):
        self.path = path or ""
        self._cfg = copy_config(cfg if cfg is not None else default_config())
        self._lock = threading.RLock()

    def get(self):
        """A copy of the settings; changing it does not change the Store."""
        with self._lock:
            return copy_config(self._cfg)

    def update(self, fn):
        """fn gets a copy of the current settings to change in place (or returns a new dict). The result is
        validated, saved and stored before any other change can start. Raises (and changes nothing) when fn,
        the validation or the save fails. It saves even when nothing changed."""
        with self._lock:
            c = copy_config(self._cfg)
            out = fn(c)
            if out is not None:
                c = out
            c = normalize(c)
            if self.path:
                save(self.path, c)
            self._cfg = c
            return copy_config(c)

    def set(self, c):
        new = copy_config(c)
        return self.update(lambda _cur: new)


def load(path):
    """Reads the settings file. Returns (store, error or None); the store always works.

    A missing file gives the defaults and no error. A broken file gives the defaults and an error naming the
    file, so the caller can warn and carry on."""
    s = Store(default_config(), path)
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except FileNotFoundError:
        return s, None
    except OSError as e:
        return s, SettingsError("cannot read %s: %s" % (path, e))
    try:
        c = decode_onto(default_config(), parse_json(raw))
    except SettingsError as e:
        return s, SettingsError("%s is not valid JSON (%s); using defaults" % (path, e))
    try:
        c = normalize(c)
    except SettingsError as e:
        return s, SettingsError("%s: %s; using defaults" % (path, e))
    s._cfg = c
    return s, None


def read_file(path):
    """Reads and validates a settings file (e.g. YTM Beacon's). Missing keys get the defaults. Raises SettingsError."""
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except OSError as e:
        raise SettingsError("cannot read %s: %s" % (path, e)) from None
    try:
        c = decode_onto(default_config(), parse_json(raw))
    except SettingsError as e:
        raise SettingsError("%s is not valid JSON (%s)" % (path, e)) from None
    try:
        return normalize(c)
    except SettingsError as e:
        raise SettingsError("%s: %s" % (path, e)) from None


def user_config_dir(environ=None, system=None, home=None):
    """Go's os.UserConfigDir(), or None when it cannot be worked out."""
    env = os.environ if environ is None else environ
    system = system or sys.platform
    home = home if home is not None else os.path.expanduser("~")
    if system.startswith("win"):
        return env.get("APPDATA") or None
    if system == "darwin":
        return os.path.join(home, "Library", "Application Support") if home else None
    d = env.get("XDG_CONFIG_HOME") or ""
    if d:
        return d if os.path.isabs(d) else None
    h = env.get("HOME", home) or ""
    return os.path.join(h, ".config") if h else None


def beacon_config_path(environ=None, system=None, home=None):
    """Where YTM Beacon keeps its settings (config.json), or None when it cannot be worked out."""
    env = os.environ if environ is None else environ
    if env.get("YTM_BEACON_HOME"):
        return os.path.join(env["YTM_BEACON_HOME"], "config", "config.json")
    base = user_config_dir(env, system, home)
    return os.path.join(base, "ytm-beacon", "config.json") if base else None
