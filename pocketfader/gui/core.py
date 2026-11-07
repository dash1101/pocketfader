"""What the desktop window does, separate from how it looks."""
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from ..config import DEFAULT_PORT, JsonFile, config_dir, get_or_create_token

SERVICE = "pocketfader.service"
DESKTOP_NAME = "pocketfader.desktop"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _http(url, headers=None, timeout=1.2):
    """GET -> parsed JSON or None. Replaceable in tests."""
    try:
        req = urllib.request.Request(url, headers=headers or {})
        with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 (loopback only)
            return json.loads(r.read().decode("utf-8", "replace"))
    except Exception:
        return None


def _sh(argv, timeout=6):
    """Run a command -> (returncode, stdout+stderr). Replaceable in tests."""
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except (OSError, subprocess.SubprocessError) as e:
        return 127, str(e)


class Controller:
    def __init__(self, sh=_sh, http=_http, platform=None, home=None, which=shutil.which, cfg_dir=None,
                 tailscale_ip=None, lan_ip=None):
        self.sh, self.http, self.which = sh, http, which
        self.platform = platform or sys.platform
        self.home = Path(home) if home else Path.home()
        self.dir = Path(cfg_dir) if cfg_dir else config_dir()
        self.dir.mkdir(parents=True, exist_ok=True)
        self.store = JsonFile(self.dir / "gui.json")
        self.cfg = JsonFile(self.dir / "config.json")
        if tailscale_ip is None or lan_ip is None:
            from ..server import lan_ip as _lan, tailscale_ip as _ts
            tailscale_ip, lan_ip = tailscale_ip or _ts, lan_ip or _lan
        self._ts, self._lan = tailscale_ip, lan_ip

    # ------------------------------------------------------------------ settings
    def settings(self):
        d = self.store.load()
        try:
            port = int(d.get("port", DEFAULT_PORT))
        except (TypeError, ValueError):
            port = DEFAULT_PORT
        return {"port": port if 1 <= port <= 65535 else DEFAULT_PORT, "lan": bool(d.get("lan")),
                "token": bool(d.get("token")), "allow_host": str(d.get("allow_host", "")).strip()}

    def update(self, **changes):
        self.store.merge(changes)

    def server_args(self):
        s = self.settings()
        args = []
        if s["port"] != DEFAULT_PORT:
            args += ["--port", str(s["port"])]
        if s["lan"]:
            args.append("--lan")
        elif s["token"]:
            args.append("--token")
        for h in [x.strip() for x in s["allow_host"].replace(";", ",").split(",") if x.strip()]:
            args += ["--allow-host", h]
        return args

    def token(self):
        s = self.settings()
        return get_or_create_token(self.cfg) if (s["lan"] or s["token"] or s["allow_host"]) else None

    # ------------------------------------------------------------------ what's running
    def probe(self):
        return self.http(f"http://127.0.0.1:{self.settings()['port']}/api/info")

    def details(self):
        """A short summary of the live server, or None when it isn't running."""
        info = self.probe()
        if not info:
            return None
        s = self.settings()
        headers = {}
        if info.get("auth"):
            headers["Authorization"] = f"Bearer {get_or_create_token(self.cfg)}"
        base = f"http://127.0.0.1:{s['port']}"
        st = self.http(base + "/api/state", headers) or {}
        yt = self.http(base + "/api/ytm/state", headers) or {}
        vol = st.get("volume") or {}
        caps = st.get("capabilities") or {}
        eng = st.get("engine") or {}
        return {
            "version": info.get("version", ""),
            "device": vol.get("device") or "",
            "volume": vol.get("percent"),
            "ytm": bool(yt.get("connected")) and yt.get("source") == "ytm",
            "eq": (eng.get("enabled") if caps.get("eq_toggle") else None),
            "now": ((yt.get("now") or {}).get("title") or ""),
        }

    def addresses(self):
        """[(label, url)] the phone can use, best first."""
        s = self.settings()
        port, tok = s["port"], self.token()
        suffix = f"/?token={tok}" if tok and s["lan"] else "/"
        out = []
        dns = self._tailscale_name()
        if dns:
            out.append(("Tailscale name", f"http://{dns}:{port}/"))
        ip = self._ts()
        if ip:
            out.append(("Tailscale address", f"http://{ip}:{port}/"))
        if s["lan"]:
            lan = self._lan()
            if lan:
                out.append(("Home network", f"http://{lan}:{port}{suffix}"))
        for h in [x.strip() for x in s["allow_host"].replace(";", ",").split(",") if x.strip()]:
            out.append(("Cloudflare", f"https://{h}/"))
        out.append(("This computer", f"http://127.0.0.1:{port}/"))
        return out

    def _tailscale_name(self):
        exe = self.which("tailscale")
        if not exe:
            return None
        rc, out = self.sh([exe, "status", "--json"], timeout=4)
        if rc != 0:
            return None
        try:
            name = (json.loads(out).get("Self") or {}).get("DNSName", "")
        except ValueError:
            return None
        return name.rstrip(".") or None

    # ------------------------------------------------------------------ starting and stopping
    def has_systemd(self):
        return self.platform.startswith("linux") and bool(self.which("systemctl"))

    def launch_command(self):
        if getattr(sys, "frozen", False):
            # the packaged window sits next to the server program; the server binary runs itself
            exe = Path(sys.executable)
            sibling = exe.with_name("pocketfader.exe" if exe.suffix.lower() == ".exe" else "pocketfader")
            return [str(sibling if sibling.exists() else exe)]
        exe = self.which("pocketfader")
        return [exe] if exe else [sys.executable, "-m", "pocketfader"]

    def unit_path(self):
        return self.home / ".config" / "systemd" / "user" / SERVICE

    def unit_text(self):
        cmd = " ".join(_quote(a) for a in self.launch_command() + self.server_args())
        return ("[Unit]\nDescription=Pocketfader - phone remote for volume and music\n"
                "After=network-online.target sound.target\n\n[Service]\n"
                f"ExecStart={cmd}\nRestart=on-failure\nRestartSec=3\n\n[Install]\nWantedBy=default.target\n")

    def write_unit(self):
        p = self.unit_path()
        p.parent.mkdir(parents=True, exist_ok=True)
        text = self.unit_text()
        if not p.exists() or p.read_text() != text:
            p.write_text(text)
            self.sh(["systemctl", "--user", "daemon-reload"])

    def is_running(self):
        return self.probe() is not None

    def start(self):
        if self.has_systemd():
            self.write_unit()
            return self.sh(["systemctl", "--user", "start", SERVICE], timeout=15)[0] == 0
        return self._spawn()

    def stop(self):
        if self.has_systemd():
            return self.sh(["systemctl", "--user", "stop", SERVICE], timeout=15)[0] == 0
        return self._kill()

    def restart(self):
        if self.has_systemd():
            self.write_unit()
            return self.sh(["systemctl", "--user", "restart", SERVICE], timeout=20)[0] == 0
        self._kill()
        time.sleep(0.5)
        return self._spawn()

    # non-systemd systems (Windows, other desktops): the window starts the server itself
    def _pidfile(self):
        return self.dir / "server.pid"

    def log_path(self):
        return self.dir / "server.log"

    def _spawn(self):
        log = open(self.log_path(), "ab")
        kwargs = {}
        if self.platform == "win32":
            kwargs["creationflags"] = 0x00000008 | 0x00000200 | 0x08000000   # detached, new group, no console
        else:
            kwargs["start_new_session"] = True
        try:
            p = subprocess.Popen(self.launch_command() + self.server_args(), stdout=log, stderr=log,
                                 stdin=subprocess.DEVNULL, **kwargs)
        except OSError:
            return False
        self._pidfile().write_text(str(p.pid))
        return True

    def _kill(self):
        try:
            pid = int(self._pidfile().read_text().strip())
        except (OSError, ValueError):
            return False
        if self.platform == "win32":
            self.sh(["taskkill", "/PID", str(pid), "/T", "/F"])
        else:
            try:
                os.kill(pid, 15)
            except OSError:
                pass
        try:
            self._pidfile().unlink()
        except OSError:
            pass
        return True

    # ------------------------------------------------------------------ start at login
    def autostart_path(self):
        return self.home / ".config" / "autostart" / DESKTOP_NAME

    def autostart_enabled(self):
        if self.platform == "win32":
            return self._win_run_value() is not None
        if self.has_systemd():
            return self.sh(["systemctl", "--user", "is-enabled", SERVICE])[0] == 0
        return self.autostart_path().exists()

    def set_autostart(self, on):
        if self.platform == "win32":
            return self._win_set_run(on)
        if self.has_systemd():
            self.write_unit()
            return self.sh(["systemctl", "--user", "enable" if on else "disable", SERVICE])[0] == 0
        p = self.autostart_path()
        if on:
            p.parent.mkdir(parents=True, exist_ok=True)
            cmd = " ".join(_quote(a) for a in self.launch_command() + self.server_args())
            p.write_text(f"[Desktop Entry]\nType=Application\nName=Pocketfader\nExec={cmd}\nTerminal=false\n")
        else:
            try:
                p.unlink()
            except OSError:
                pass
        return True

    def _win_run_value(self):
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
                return winreg.QueryValueEx(k, "Pocketfader")[0]
        except (ImportError, OSError):
            return None

    def _win_set_run(self, on):
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
                if on:
                    exe, *args = self.launch_command() + self.server_args()
                    # start it hidden, so no console window opens at every login
                    arglist = " ".join(args).replace("'", "''")
                    cmd = ("powershell.exe -NoProfile -WindowStyle Hidden -Command "
                           f"\"Start-Process -WindowStyle Hidden -FilePath '{exe}'"
                           + (f" -ArgumentList '{arglist}'" if args else "") + "\"")
                    winreg.SetValueEx(k, "Pocketfader", 0, winreg.REG_SZ, cmd)
                else:
                    try:
                        winreg.DeleteValue(k, "Pocketfader")
                    except OSError:
                        pass
            return True
        except (ImportError, OSError):
            return False

    # ------------------------------------------------------------------ updates
    def update_info(self, refresh=False):
        if not hasattr(self, "_updater"):
            from ..updater import Updater
            self._updater = Updater()
        info = dict(self._updater.latest(refresh=refresh))
        info["self_update"] = self._updater.can_update_self()
        return info

    def update_now(self):
        """Install the newest release into this installation and restart the server -> (ok, message)."""
        self.update_info()
        ok, msg = self._updater.update_self()
        if ok and self.is_running():
            self.restart()
            msg = "Updated and restarted."
        return ok, msg

    # ------------------------------------------------------------------ log
    def log_text(self, lines=150):
        if self.has_systemd():
            rc, out = self.sh(["journalctl", "--user", "-u", SERVICE, "-n", str(lines), "--no-pager"], timeout=8)
            return out if rc == 0 else out or "No log yet."
        try:
            return "\n".join(self.log_path().read_text(errors="replace").splitlines()[-lines:]) or "No log yet."
        except OSError:
            return "No log yet."

    def hostname(self):
        return socket.gethostname()


def _quote(arg):
    return f'"{arg}"' if (" " in arg or not arg) else arg
