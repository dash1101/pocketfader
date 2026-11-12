"""The browser extension: a copy of it in a fixed folder, whether one is connected, and opening things for the setup.

Browsers only take an extension like this one by hand ("Load unpacked" on their extensions page), so the apps walk
the user through it step by step. To make that easy this module:

- keeps a copy of the extension that ships with YTM Starcast in a folder that never moves (and refreshes it when
  YTM Starcast is updated, so a browser that loaded it gets the new version on its next reload);
- says whether an extension is connected, and whether it is an older one that should be reloaded;
- opens, on the computer and only on request, the browser's extensions page or that folder. Nothing else can be
  opened: the folder is this module's own and the pages are a fixed list per browser.
"""
import hashlib
import json
import os
import platform
import shutil
import subprocess
import threading
from pathlib import Path

HERE = Path(__file__).resolve().parent

# The browsers the guide knows: what the user sees, the address of its extensions page, and how to start it.
BROWSERS = [
    {"id": "brave", "name": "Brave", "page": "brave://extensions",
     "linux": ["brave-browser", "brave", "brave-browser-stable"],
     "windows": [r"BraveSoftware\Brave-Browser\Application\brave.exe"], "mac": "Brave Browser"},
    {"id": "chrome", "name": "Google Chrome", "page": "chrome://extensions",
     "linux": ["google-chrome", "google-chrome-stable"],
     "windows": [r"Google\Chrome\Application\chrome.exe"], "mac": "Google Chrome"},
    {"id": "chromium", "name": "Chromium", "page": "chrome://extensions",
     "linux": ["chromium", "chromium-browser"], "windows": [], "mac": "Chromium"},
    {"id": "edge", "name": "Microsoft Edge", "page": "edge://extensions",
     "linux": ["microsoft-edge", "microsoft-edge-stable"],
     "windows": [r"Microsoft\Edge\Application\msedge.exe"], "mac": "Microsoft Edge"},
    {"id": "vivaldi", "name": "Vivaldi", "page": "vivaldi://extensions",
     "linux": ["vivaldi", "vivaldi-stable"], "windows": [r"Vivaldi\Application\vivaldi.exe"], "mac": "Vivaldi"},
    {"id": "opera", "name": "Opera", "page": "opera://extensions",
     "linux": ["opera"], "windows": [r"Programs\Opera\opera.exe"], "mac": "Opera"},
]


def bundled_dir():
    """The extension that ships with this copy of YTM Starcast: inside the package (installed copies), else the
    repository's extension/ folder (running from a source checkout). None when neither is there."""
    for d in (HERE / "extension", HERE.parent / "extension"):
        if (d / "manifest.json").is_file():
            return d
    return None


def manifest_version(folder):
    try:
        return str(json.loads((Path(folder) / "manifest.json").read_text(encoding="utf-8")).get("version", "")) or None
    except (OSError, ValueError, AttributeError):
        return None


def _system(system=None):
    s = (system or platform.system()).lower()
    return "mac" if s == "darwin" else s


def install_dir(system=None, env=None, home=None):
    """Where the copy the browser loads lives. Never moves, so the browser keeps finding it."""
    env = os.environ if env is None else env
    home = Path(home) if home else Path.home()
    s = _system(system)
    if s == "windows":
        base = Path(env.get("LOCALAPPDATA") or home / "AppData" / "Local") / "ytm-starcast"
    elif s == "mac":
        base = home / "Library" / "Application Support" / "YTM Starcast"
    else:
        base = Path(env.get("XDG_DATA_HOME") or home / ".local" / "share") / "ytm-starcast"
    return base / "extension"


def _fingerprint(folder):
    """One hash over every file's path and contents, so any change (not only the version) is noticed."""
    h = hashlib.sha256()
    for p in sorted(Path(folder).rglob("*")):
        if p.is_file() and "__pycache__" not in p.parts:
            h.update(p.relative_to(folder).as_posix().encode() + b"\0" + p.read_bytes() + b"\0")
    return h.hexdigest()


def sync(src=None, dest=None):
    """Make the fixed folder an exact copy of the bundled extension. Returns True when something was copied.

    The copy is built next to the folder and swapped in, so a browser never sees half a folder."""
    src = Path(src) if src else bundled_dir()
    dest = Path(dest) if dest else install_dir()
    if not src or not (src / "manifest.json").is_file():
        return False
    if src.resolve() == dest.resolve():
        return False
    if dest.is_dir() and _fingerprint(dest) == _fingerprint(src):
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".new")
    old = dest.with_name(dest.name + ".old")
    for p in (tmp, old):
        if p.exists():
            shutil.rmtree(p, ignore_errors=True)
    shutil.copytree(src, tmp, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "README.md"))
    if dest.exists():
        os.replace(dest, old)
    os.replace(tmp, dest)
    shutil.rmtree(old, ignore_errors=True)
    return True


class BrowserExtension:
    """Status of the extension and the setup actions, for /api/extension."""

    def __init__(self, bridge, seen_file=None, folder=None, source=None, dry_run=False, system=None, env=None,
                 which=None, popen=None, exists=None):
        self.bridge = bridge
        self.seen_file = Path(seen_file) if seen_file else None
        self.folder = Path(folder) if folder else install_dir(system, env)
        self.source = source
        self.dry_run = dry_run
        self.system = _system(system)
        self.env = os.environ if env is None else env
        self._which = which or shutil.which
        self._popen = popen or subprocess.Popen
        self._exists = exists or os.path.exists
        self.opened = []            # what was opened (dry run / tests)
        self._lock = threading.Lock()

    # ---- the folder ----
    def refresh_copy(self):
        """Bring the fixed folder up to date with the extension this version ships (not in a dry run)."""
        if self.dry_run:
            return False
        try:
            return sync(self.source, self.folder)
        except OSError:
            return False

    @property
    def expected_version(self):
        src = Path(self.source) if self.source else bundled_dir()
        return manifest_version(src) if src else None

    # ---- status ----
    def _ever_connected(self, connected):
        if not self.seen_file:
            return connected
        if connected and not self.seen_file.exists():
            try:
                self.seen_file.parent.mkdir(parents=True, exist_ok=True)
                self.seen_file.touch()
            except OSError:
                pass
        return connected or self.seen_file.exists()

    def status(self):
        state = self.bridge.get_state() if self.bridge else {}
        connected = bool(state.get("connected"))
        reported = state.get("ext") if isinstance(state.get("ext"), str) else None
        expected = self.expected_version
        # an extension from before 2.0 doesn't say its version: it still works, but should be reloaded
        outdated = bool(connected and expected and reported != expected)
        return {"connected": connected, "everConnected": self._ever_connected(connected), "version": reported,
                "expected": expected, "outdated": outdated, "folder": str(self.folder),
                "folderReady": (self.folder / "manifest.json").is_file(), "browsers": self.browsers()}

    # ---- browsers on this computer ----
    def _find(self, b):
        """The command that starts this browser here, or None."""
        if self.system == "windows":
            for root in (self.env.get("ProgramFiles"), self.env.get("ProgramFiles(x86)"), self.env.get("LOCALAPPDATA")):
                for rel in b["windows"]:
                    if root and self._exists(os.path.join(root, rel)):
                        return [os.path.join(root, rel)]
            return None
        if self.system == "mac":
            app = f"/Applications/{b['mac']}.app"
            return ["open", "-a", b["mac"]] if self._exists(app) else None
        for name in b["linux"]:
            exe = self._which(name)
            if exe:
                return [exe]
        return None

    def browsers(self):
        return [{"id": b["id"], "name": b["name"], "page": b["page"]} for b in BROWSERS if self._find(b)]

    # ---- opening things on the computer ----
    def _start(self, cmd):
        self.opened.append(cmd)
        if self.dry_run:
            return True
        try:
            if self.system == "windows":
                flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
                self._popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            creationflags=flags)
            else:
                self._popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)
            return True
        except OSError:
            return False

    def open_folder(self):
        """Show the extension's folder in the file manager (it is made first if it isn't there yet)."""
        with self._lock:
            if not (self.folder / "manifest.json").is_file():
                self.refresh_copy()
            if not self.dry_run and not self.folder.is_dir():
                return False
            if self.system == "windows":
                return self._start(["explorer", str(self.folder)])
            exe = "open" if self.system == "mac" else self._which("xdg-open")
            return bool(exe) and self._start([exe, str(self.folder)])

    def open_extensions_page(self, browser_id):
        """Open the extensions page of one of the browsers found here. Anything else: ValueError."""
        b = next((b for b in BROWSERS if b["id"] == browser_id), None)
        if not b:
            raise ValueError("unknown browser")
        cmd = self._find(b)
        if not cmd:
            raise ValueError("that browser isn't on this computer")
        if self.system == "mac":
            return self._start(cmd + ["--args", b["page"]])
        return self._start(cmd + [b["page"]])
