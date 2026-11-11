"""Settings: CLI flags > environment > config file > defaults.

Everything user-specific lives outside the source tree:
    Linux/macOS  ~/.config/ytm-starcast/   (or $XDG_CONFIG_HOME)
    Windows      %APPDATA%\\ytm-starcast\\

YTM Starcast used to be called Pocketfader. The first time YTM Starcast starts, an existing `pocketfader` settings
folder is moved to the new name (same parent folder, so the move is a single rename), and on Linux/macOS the old name
is left behind as a link to the new folder, so older tools still find it. If the move fails for any reason,
YTM Starcast keeps using the old folder: nothing is copied, nothing is lost.
"""
import json
import os
import secrets
import sys
import threading
from pathlib import Path

DEFAULT_PORT = 8777
NAME = "ytm-starcast"
LEGACY_NAME = "pocketfader"


def _config_base(platform=None) -> Path:
    if (platform or sys.platform) == "win32":
        return Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")


def _moving_allowed() -> bool:
    """Only a real start moves folders. A trial run (--dry-run, which promises to write nothing), a test run, or
    STARCAST_NO_SETTINGS_MOVE=1 keeps using an old folder where it is, so none of them can move a real settings folder."""
    return (not os.environ.get("STARCAST_NO_SETTINGS_MOVE") and "unittest" not in sys.modules
            and "--dry-run" not in getattr(sys, "argv", ()))


def adopt_legacy_dir(new: Path, old: Path, link=True, allow=None) -> Path:
    """The folder to use: `new`, after moving `old` there if only `old` exists.

    `old` is moved only when it is a real folder (not a link) and `new` doesn't exist yet (an empty `new` folder,
    left by something that ran before the move, doesn't count). With `link`, `old` then becomes a link to `new`.
    Any failure leaves the old folder where it was, and that folder is used instead.
    """
    allow = _moving_allowed() if allow is None else allow
    try:
        old_real = _is_real_dir(old)
        if new.is_symlink() or new.exists():
            if not (old_real and _is_real_dir(new) and not any(new.iterdir())):
                return new
            if not allow:
                return old
            new.rmdir()                     # empty: make room for the real thing (rmdir refuses anything else)
        elif old.is_symlink():
            # someone's own link (dotfiles, another disk): keep using it rather than starting over empty
            return old if old.is_dir() else new
        elif not old_real:
            return new
        elif not allow:
            return old
        old.rename(new)                     # same parent folder: atomic, and it can't half-happen
    except OSError:
        # another process may have moved it a moment ago; otherwise stay on the old folder
        if new.is_dir():
            return new
        return old if old.is_dir() else new
    if link:
        try:
            old.symlink_to(new.name, target_is_directory=True)     # relative, so it survives a moved home
        except (OSError, NotImplementedError):
            pass
    return new


def _is_real_dir(p: Path) -> bool:
    return p.is_dir() and not p.is_symlink()


def settings_dir(base: Path, platform=None, allow=None) -> Path:
    """`<base>/ytm-starcast`, taking over `<base>/pocketfader` first if needed (no link on Windows)."""
    windows = (platform or sys.platform) == "win32"
    return adopt_legacy_dir(Path(base) / NAME, Path(base) / LEGACY_NAME, link=not windows, allow=allow)


def config_dir() -> Path:
    d = settings_dir(_config_base())
    d.mkdir(parents=True, exist_ok=True)
    return d


def take_over_beacon_settings(folder=None, source=None) -> bool:
    """Copies the separate YTM Beacon app's Discord status settings (its config.json) to <settings folder>/discord.json,
    replacing what is there, as "Move over" in the app does.

    The installers run this just before they remove YTM Beacon. The server takes Beacon's settings over by itself only
    when discord.json doesn't exist yet, so settings changed in Beacon after that (words never to show, private mode)
    would otherwise be lost. No settings folder is moved here (that stays the server's job on its next start).
    Returns True when they were copied, False when YTM Beacon has no settings file. Raises ValueError (settings that
    can't be read or aren't valid) or OSError (discord.json can't be written); then nothing changes."""
    from .discord import settings as st        # here, so importing config stays light
    src = source if source is not None else st.beacon_config_path()
    if not src or not os.path.isfile(src):
        return False
    cfg = st.read_file(os.fspath(src))
    folder = Path(folder) if folder is not None else settings_dir(_config_base(), allow=False)
    st.save(os.fspath(folder / "discord.json"), cfg)
    return True


def take_over_beacon_main() -> int:
    """For the installers: exit code 0 when YTM Beacon's settings were copied (or it has none), 1 when they couldn't be."""
    try:
        copied = take_over_beacon_settings()
    except (ValueError, OSError) as e:
        print(f"YTM Beacon's settings couldn't be copied to YTM Starcast: {e}", file=sys.stderr)
        return 1
    print("YTM Beacon's settings were copied to YTM Starcast." if copied else
          "YTM Beacon has no settings file, so there was nothing to copy.")
    return 0


class JsonFile:
    """Tiny thread-safe JSON dict on disk. Corrupt/missing files read as empty."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()

    def load(self) -> dict:
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def merge(self, patch: dict) -> dict:
        with self._lock:
            cur = self.load()
            cur.update(patch)
            tmp = self.path.with_suffix(".tmp")
            try:
                tmp.write_text(json.dumps(cur), encoding="utf-8")
                tmp.replace(self.path)  # atomic: a crash can't leave half a file
            except OSError:
                pass
            return cur


def get_or_create_token(cfg: JsonFile) -> str:
    """The access token is generated once and persisted, so links/bookmarks keep working."""
    tok = cfg.load().get("token")
    if not isinstance(tok, str) or len(tok) < 16:
        tok = secrets.token_urlsafe(18)
        cfg.merge({"token": tok})
        try:
            os.chmod(cfg.path, 0o600)
        except OSError:
            pass
    return tok
