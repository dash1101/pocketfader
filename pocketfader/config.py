"""Settings: CLI flags > environment > config file > defaults.

Everything user-specific lives outside the source tree:
    Linux/macOS  ~/.config/pocketfader/   (or $XDG_CONFIG_HOME)
    Windows      %APPDATA%\\pocketfader\\
"""
import json
import os
import secrets
import sys
import threading
from pathlib import Path

DEFAULT_PORT = 8777


def config_dir() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    d = base / "pocketfader"
    d.mkdir(parents=True, exist_ok=True)
    return d


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
