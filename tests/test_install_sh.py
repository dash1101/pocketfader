"""install.sh in a sandbox: a temp HOME (XDG_* and PF_* folders inside it), and stand-ins for systemctl, pactl, busctl,
tailscale, journalctl, sleep and python3's venv/pip that only write down how they were called. The sandbox can't reach
the real systemd user session (no stand-in calls the real one, and XDG_RUNTIME_DIR / DBUS_SESSION_BUS_ADDRESS point
into the temp folder), and PATH holds only the stand-ins and the system folders, never ~/.local/bin."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCRIPT = REPO / "install.sh"
BASH = shutil.which("bash")

LOGGER = '#!/bin/bash\necho "{name} $*" >> "$STUB_LOG"\nexit 0\n'

# STUB_NO_BUS=1: systemctl is there but can't reach the user's service manager (sudo, su, a container).
# STUB_FAILED: units that stop right after they start. STUB_UNITPATH / STUB_FRAGMENT: what `show -p ...` answers.
SYSTEMCTL = r'''#!/bin/bash
echo "systemctl $*" >> "$STUB_LOG"
if [ "${STUB_NO_BUS:-}" = 1 ]; then echo "Failed to connect to bus: No medium found" >&2; exit 1; fi
if [ "$1" = --user ] && [ "$2" = is-enabled ]; then
  for u in ${STUB_ENABLED:-}; do if [ "$u" = "$3" ]; then echo enabled; exit 0; fi; done
  echo disabled; exit 1
fi
if [ "$1" = --user ] && [ "$2" = is-active ]; then
  for u in ${STUB_FAILED:-}; do if [ "$u" = "${@: -1}" ]; then exit 3; fi; done
  exit 0
fi
if [ "$1" = --user ] && [ "$2" = show ] && [ "$4" = UnitPath ]; then echo "${STUB_UNITPATH:-}"; fi
if [ "$1" = --user ] && [ "$2" = show ] && [ "$4" = FragmentPath ]; then echo "${STUB_FRAGMENT:-}"; fi
exit 0
'''

# python3: a venv is faked (its pip writes down its arguments and makes the ytm-starcast commands; its python is the
# real one, running this source tree); anything else (the version checks) goes to the real Python.
PYTHON3 = r'''#!/bin/bash
if [ "$1" = -m ] && [ "$2" = venv ]; then
  echo "python3 $*" >> "$STUB_LOG"
  dir="${@: -1}"
  mkdir -p "$dir/bin"
  cp "$STUB_DIR/fake-pip" "$dir/bin/pip"
  cp "$STUB_DIR/venv-python" "$dir/bin/python"
  exit 0
fi
exec "$REAL_PYTHON" "$@"
'''

# -I would hide PYTHONPATH (the source tree): it is dropped here; -s still keeps the real user's site folder out.
VENV_PYTHON = r'''#!/bin/bash
args=()
for a in "$@"; do if [ "$a" != -I ]; then args+=("$a"); fi; done
PYTHONPATH="$STUB_SRC" exec "$REAL_PYTHON" -s "${args[@]}"
'''

PIP = r'''#!/bin/bash
echo "pip $*" >> "$STUB_LOG"
bin="$(cd "$(dirname "$0")" && pwd)"
for a in "$@"; do
  if [ "$a" = "$STUB_SRC" ]; then
    for n in ytm-starcast ytm-starcast-gui; do
      printf '#!/bin/sh\necho "%s $*" >> "$STUB_LOG"\n' "$n" > "$bin/$n"; chmod +x "$bin/$n"
    done
  fi
done
exit 0
'''

# The YTM Beacon app: `uninstall` removes its program and its unit (like the real one) but keeps its settings.
BEACON = r'''#!/bin/bash
echo "ytm-beacon $* @$0" >> "$STUB_LOG"
if [ "$1" = uninstall ]; then
  rm -f "${XDG_CONFIG_HOME:?}/systemd/user/ytm-beacon.service" "$0"
fi
exit ${STUB_BEACON_RC:-0}
'''


def _exe(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(0o755)


@unittest.skipUnless(sys.platform.startswith("linux") and BASH, "install.sh is for Linux")
class Sandbox(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name).resolve()
        self.home = self.root / "home"
        self.stubs = self.root / "stubs"
        self.log = self.root / "calls.log"
        self.home.mkdir()
        (self.root / "run").mkdir()
        self.cfg = self.home / ".config"
        self.share = self.home / ".local" / "share"
        self.bin = self.home / ".local" / "bin"
        self.units = self.cfg / "systemd" / "user"
        self.apps = self.share / "applications"
        self.icons = self.share / "icons" / "hicolor" / "scalable" / "apps"
        self.app = self.share / "ytm-starcast"
        self.old_app = self.share / "pocketfader"
        for name in ("pactl", "busctl", "tailscale", "journalctl", "sleep"):
            _exe(self.stubs / name, LOGGER.format(name=name))
        _exe(self.stubs / "systemctl", SYSTEMCTL)
        _exe(self.stubs / "python3", PYTHON3)
        _exe(self.stubs / "ytm-beacon", BEACON)      # on PATH on purpose: the installer must never use it
        _exe(self.root / "stub-files" / "fake-pip", PIP)
        _exe(self.root / "stub-files" / "venv-python", VENV_PYTHON)
        self.env = {
            "HOME": str(self.home), "XDG_CONFIG_HOME": str(self.cfg), "XDG_DATA_HOME": str(self.share),
            "XDG_CACHE_HOME": str(self.home / ".cache"), "XDG_RUNTIME_DIR": str(self.root / "run"),
            "DBUS_SESSION_BUS_ADDRESS": f"unix:path={self.root}/run/no-bus",
            "PF_APP_DIR": str(self.app), "PF_BIN_DIR": str(self.bin), "PF_UNIT_DIR": str(self.units),
            "PF_APPS_DIR": str(self.apps), "PF_ICON_DIR": str(self.icons), "PF_OLD_APP_DIR": str(self.old_app),
            "PATH": f"{self.stubs}:/usr/bin:/bin", "LANG": "C.UTF-8",
            "STUB_LOG": str(self.log), "STUB_SRC": str(REPO), "STUB_DIR": str(self.root / "stub-files"),
            "REAL_PYTHON": sys.executable,
        }

    # ---------------------------------------------------------------- helpers
    def run_install(self, *args, rc=0, **extra_env):
        env = {**self.env, **extra_env}
        real_home = str(Path.home())
        # the sandbox guard itself: nothing may point at the real home or its ~/.local/bin
        for k, v in env.items():
            if k in ("HOME",) or k.startswith(("XDG_", "PF_")):
                self.assertTrue(v.startswith(str(self.root)), k)
        self.assertNotIn(real_home + "/.local/bin", env["PATH"])
        p = subprocess.run([BASH, str(SCRIPT), *args], env=env, cwd=str(self.root), capture_output=True, text=True,
                           timeout=120)
        self.assertEqual(p.returncode, rc, p.stdout + p.stderr)
        self.out = p.stdout + p.stderr
        return self.calls()

    def calls(self):
        try:
            return self.log.read_text().splitlines()
        except FileNotFoundError:
            return []

    def systemctl(self):
        """systemctl calls, without the check that it reaches the service manager (made on every run)."""
        return [c[len("systemctl --user "):] for c in self.calls()
                if c.startswith("systemctl ") and c != "systemctl --user show-environment"]

    def fake_pocketfader(self, args="", enabled=True, exec_start=None, units=None):
        prog = self.old_app / "venv" / "bin" / "pocketfader"
        _exe(prog, "#!/bin/sh\n")
        _exe(self.old_app / "venv" / "bin" / "pocketfader-gui", "#!/bin/sh\n")
        self.bin.mkdir(parents=True, exist_ok=True)
        (self.bin / "pocketfader").symlink_to(prog)
        (self.bin / "pocketfader-gui").symlink_to(prog.with_name("pocketfader-gui"))
        units = units or self.units
        units.mkdir(parents=True, exist_ok=True)
        exec_start = exec_start or f"{prog} {args}"
        (units / "pocketfader.service").write_text(
            f"[Unit]\nDescription=Pocketfader\n\n[Service]\nExecStart={exec_start}\n\n[Install]\nWantedBy=default.target\n")
        self.apps.mkdir(parents=True, exist_ok=True)
        (self.apps / "pocketfader.desktop").write_text("[Desktop Entry]\nName=Pocketfader\n")
        self.icons.mkdir(parents=True, exist_ok=True)
        (self.icons / "pocketfader.svg").write_text("<svg/>")
        settings = self.cfg / "pocketfader"
        settings.mkdir(parents=True)
        (settings / "config.json").write_text('{"token": "keep-me-0123456789"}')
        if enabled:
            self.env["STUB_ENABLED"] = "pocketfader.service"

    def fake_beacon(self, with_program=True):
        prog = self.bin / "ytm-beacon"
        if with_program:
            _exe(prog, BEACON)
        self.units.mkdir(parents=True, exist_ok=True)
        (self.units / "ytm-beacon.service").write_text(
            f'[Unit]\nDescription=YTM Beacon\n\n[Service]\nExecStart="{prog}" run\n\n[Install]\nWantedBy=default.target\n')
        conf = self.cfg / "ytm-beacon"
        conf.mkdir(parents=True)
        (conf / "config.json").write_text('{"enabled": true, "privateMode": true}\n')
        return prog

    def assert_starcast_installed(self):
        self.assertTrue((self.app / "venv" / "bin" / "ytm-starcast").exists())
        self.assertEqual(os.readlink(self.bin / "ytm-starcast"), str(self.app / "venv" / "bin" / "ytm-starcast"))
        self.assertEqual(os.readlink(self.bin / "ytm-starcast-gui"), str(self.app / "venv" / "bin" / "ytm-starcast-gui"))
        desktop = (self.apps / "ytm-starcast.desktop").read_text()
        self.assertIn("Name=YTM Starcast\n", desktop)
        self.assertIn(f"Exec={self.bin}/ytm-starcast-gui\n", desktop)
        self.assertIn("Icon=ytm-starcast\n", desktop)
        self.assertIn("StartupWMClass=app.novalabs.starcast\n", desktop)     # the window's id (starcast/gui/core.py APP_ID)
        self.assertEqual((self.icons / "ytm-starcast.svg").read_bytes(), (REPO / "starcast" / "static" / "icon.svg").read_bytes())

    # ---------------------------------------------------------------- tests
    def test_fresh_install(self):
        calls = self.run_install()
        self.assert_starcast_installed()
        unit = (self.units / "ytm-starcast.service").read_text()
        self.assertIn(f"ExecStart={self.app}/venv/bin/ytm-starcast\n", unit)
        self.assertIn("Description=YTM Starcast - YouTube Music companion", unit)
        self.assertEqual(self.systemctl(), ["daemon-reload", "enable ytm-starcast.service", "restart ytm-starcast.service",
                                            "is-active --quiet ytm-starcast.service"])
        pip = [c for c in calls if c.startswith("pip ")]
        self.assertIn(f"pip install --quiet {REPO}", pip)
        self.assertIn("pip install --quiet --upgrade yt-dlp", pip)
        self.assertFalse([c for c in calls if "pocketfader" in c or c.startswith("ytm-beacon")])
        self.assertTrue((self.stubs / "ytm-beacon").exists())        # the PATH copy was never run
        self.assertFalse((self.cfg / "ytm-starcast").exists())             # settings are the server's business, not ours
        self.assertNotIn("Pocketfader", self.out)
        self.assertNotIn("YTM Beacon", self.out)

    def test_options_reach_the_service(self):
        self.run_install("--lan", "--allow-host=music.example.com")
        unit = (self.units / "ytm-starcast.service").read_text()
        self.assertIn("/venv/bin/ytm-starcast --lan --allow-host music.example.com\n", unit)

    def test_install_over_pocketfader_and_ytm_beacon(self):
        self.fake_pocketfader(args="--lan --port 9000")
        beacon = self.fake_beacon()
        calls = self.run_install()
        self.assert_starcast_installed()
        sc = self.systemctl()
        # Pocketfader: its start-at-login state is read, then it is stopped and switched off, before YTM Starcast starts
        self.assertLess(sc.index("is-enabled pocketfader.service"), sc.index("disable --now pocketfader.service"))
        self.assertLess(sc.index("disable --now pocketfader.service"), sc.index("restart ytm-starcast.service"))
        self.assertIn("enable ytm-starcast.service", sc)                     # it started at login, so YTM Starcast does too
        # YTM Beacon: its own uninstaller, without --purge, after Pocketfader stopped and before YTM Starcast starts
        b = [c for c in calls if c.startswith("ytm-beacon ")]
        self.assertEqual(b, [f"ytm-beacon uninstall @{beacon}"])
        order = [c for c in calls if c.startswith(("ytm-beacon", "systemctl"))]
        self.assertLess(order.index("systemctl --user disable --now pocketfader.service"), order.index(b[0]))
        self.assertLess(order.index(b[0]), order.index("systemctl --user restart ytm-starcast.service"))
        # its old files are gone ...
        for gone in (self.units / "pocketfader.service", self.apps / "pocketfader.desktop", self.icons / "pocketfader.svg",
                     self.bin / "pocketfader", self.bin / "pocketfader-gui", self.old_app,
                     self.units / "ytm-beacon.service", beacon):
            self.assertFalse(gone.exists() or gone.is_symlink(), gone)
        # ... and both apps' settings are kept for YTM Starcast to take over
        self.assertIn("keep-me", (self.cfg / "pocketfader" / "config.json").read_text())
        self.assertIn("privateMode", (self.cfg / "ytm-beacon" / "config.json").read_text())
        # the options Pocketfader was started with carry over
        self.assertIn("/venv/bin/ytm-starcast --lan --port 9000\n", (self.units / "ytm-starcast.service").read_text())
        self.assertIn("Replacing Pocketfader", self.out)
        self.assertIn("Replacing the YTM Beacon app", self.out)
        # Beacon's settings were copied before its uninstaller ran (into the settings folder still under the old
        # name: moving it is the server's job), and Pocketfader's files went only once YTM Starcast was running
        discord = json.loads((self.cfg / "pocketfader" / "discord.json").read_text())
        self.assertIs(discord["privateMode"], True)
        self.assertFalse((self.cfg / "ytm-starcast").exists())
        self.assertLess(self.out.index("settings were copied"), self.out.index("own uninstaller"))
        self.assertLess(self.out.index("Pocketfader is removed"), self.out.index("YTM Starcast is running"))
        self.assertLess(sc.index("is-active --quiet ytm-starcast.service"), len(sc) - 1 - sc[::-1].index("daemon-reload"))

    def test_pocketfader_that_did_not_start_at_login(self):
        self.fake_pocketfader(enabled=False)
        self.run_install()
        sc = self.systemctl()
        self.assertIn("disable --now pocketfader.service", sc)
        self.assertNotIn("enable ytm-starcast.service", sc)
        self.assertIn("restart ytm-starcast.service", sc)
        self.assertIn("Start at login stays off", self.out)

    def test_options_given_now_win_over_the_old_ones(self):
        self.fake_pocketfader(args="--lan")
        self.run_install("--token")
        self.assertIn("/venv/bin/ytm-starcast --token\n", (self.units / "ytm-starcast.service").read_text())

    def test_odd_old_options_are_not_copied(self):
        self.fake_pocketfader(args="--allow-host 'a b' ; rm -rf x")
        self.run_install()
        self.assertIn("/venv/bin/ytm-starcast\n", (self.units / "ytm-starcast.service").read_text())

    def test_a_unit_the_window_wrote_with_python_m_keeps_only_the_server_options(self):
        # The old window wrote `<python> -m pocketfader ...` when `pocketfader` wasn't on its PATH
        self.fake_pocketfader(exec_start=f"{self.old_app}/venv/bin/python3 -m pocketfader --port 8777 --lan")
        self.run_install()
        unit = (self.units / "ytm-starcast.service").read_text()
        self.assertIn(f"ExecStart={self.app}/venv/bin/ytm-starcast --port 8777 --lan\n", unit)
        self.assertIn("Keeping the options it was started with: --port 8777 --lan", self.out)
        self.assertNotIn("-m pocketfader", self.out + unit)
        # the new window's own fallback (`python -m starcast`), read back when installing again
        (self.units / "ytm-starcast.service").write_text(
            unit.replace(f"{self.app}/venv/bin/ytm-starcast --port 8777 --lan",
                         f'"{self.app}/venv/bin/python" -u -m starcast --allow-host=music.example.com --dry-run --token'))
        self.run_install()
        self.assertIn(f"ExecStart={self.app}/venv/bin/ytm-starcast --allow-host music.example.com --token\n",
                      (self.units / "ytm-starcast.service").read_text())

    def test_old_options_the_new_program_rejects_are_dropped(self):
        self.fake_pocketfader(args="--audio no-such-backend --lan")
        self.run_install()
        self.assertIn(f"ExecStart={self.app}/venv/bin/ytm-starcast\n", (self.units / "ytm-starcast.service").read_text())
        self.assertIn("don't work with this version", self.out)

    def test_without_systemd_the_autostart_entry_gets_only_server_options(self):
        auto = self.cfg / "autostart"
        auto.mkdir(parents=True)
        (auto / "pocketfader.desktop").write_text(
            "[Desktop Entry]\nType=Application\nName=Pocketfader\nExec=/usr/bin/python3 -m pocketfader --lan\n")
        self.run_install("--no-systemd")
        self.assertIn(f"Exec={self.app}/venv/bin/ytm-starcast --lan\n", (auto / "ytm-starcast.desktop").read_text())

    def test_a_service_that_does_not_start_leaves_pocketfader_as_it_was(self):
        self.fake_pocketfader(args="--lan")
        self.fake_beacon()
        self.run_install(rc=1, STUB_FAILED="ytm-starcast.service")
        sc = self.systemctl()
        self.assertIn("didn't start", self.out)
        self.assertNotIn("YTM Starcast is running", self.out)
        for kept in (self.units / "pocketfader.service", self.old_app / "venv" / "bin" / "pocketfader",
                     self.apps / "pocketfader.desktop"):
            self.assertTrue(kept.exists(), kept)
        self.assertTrue((self.bin / "pocketfader").is_symlink())
        # no endless retries, not started at login next to Pocketfader, and Pocketfader is back on
        after = sc[sc.index("is-active --quiet ytm-starcast.service"):]
        for c in ("stop ytm-starcast.service", "disable ytm-starcast.service", "enable pocketfader.service",
                  "start pocketfader.service"):
            self.assertIn(c, after)

    # ---- the YTM Beacon app's settings ----
    def test_ytm_beacon_settings_replace_ones_copied_earlier(self):
        # installed earlier with --keep-ytm-beacon: YTM Starcast copied Beacon's settings then; Beacon changed since
        self.fake_beacon()
        (self.cfg / "ytm-beacon" / "config.json").write_text(
            json.dumps({"enabled": True, "privateMode": True, "hideMatching": ["secret band", "therapy podcast"]}))
        mine = self.cfg / "ytm-starcast"
        mine.mkdir(parents=True)
        (mine / "discord.json").write_text(json.dumps({"enabled": True, "hideMatching": ["secret band"]}))
        calls = self.run_install()
        got = json.loads((mine / "discord.json").read_text())
        self.assertEqual(got["hideMatching"], ["secret band", "therapy podcast"])
        self.assertIs(got["privateMode"], True)
        self.assertTrue([c for c in calls if c.startswith("ytm-beacon uninstall")])

    def test_ytm_beacon_stays_when_its_settings_cannot_be_copied(self):
        beacon = self.fake_beacon()
        (self.cfg / "ytm-beacon" / "config.json").write_text("{not json")
        calls = self.run_install()
        self.assertFalse([c for c in calls if c.startswith("ytm-beacon")])
        self.assertTrue(beacon.exists() and (self.units / "ytm-beacon.service").exists())
        self.assertNotIn("disable --now ytm-beacon.service", self.systemctl())
        self.assertIn("couldn't be copied", self.out)
        self.assertIn("stays installed for now", self.out)
        self.assertFalse((self.cfg / "ytm-starcast" / "discord.json").exists())

    def test_ytm_beacon_without_a_settings_file(self):
        self.fake_beacon()
        (self.cfg / "ytm-beacon" / "config.json").unlink()
        calls = self.run_install()
        self.assertTrue([c for c in calls if c.startswith("ytm-beacon uninstall")])
        self.assertIn("nothing to copy", self.out)

    # ---- systemctl that can't reach the service manager ----
    def test_no_service_manager_changes_nothing(self):
        self.fake_pocketfader(args="--lan")
        beacon = self.fake_beacon()
        calls = self.run_install(rc=1, STUB_NO_BUS="1")
        self.assertIn("can't reach your user's service manager", self.out)
        self.assertIn("--no-systemd", self.out)
        self.assertEqual([c for c in calls if not c.startswith("systemctl")], [])      # no venv, pip or Beacon call
        for kept in (self.units / "pocketfader.service", self.old_app, self.bin / "pocketfader", beacon,
                     self.units / "ytm-beacon.service"):
            self.assertTrue(kept.exists(), kept)
        self.assertFalse(self.app.exists() or (self.units / "ytm-starcast.service").exists())

    def test_no_service_manager_with_no_systemd_still_needs_it_for_pocketfaders_service(self):
        self.fake_pocketfader()
        self.run_install("--no-systemd", rc=1, STUB_NO_BUS="1")
        self.assertIn("Pocketfader's service can't be switched off", self.out)
        self.assertTrue(self.old_app.exists())

    def test_no_service_manager_with_no_systemd_and_nothing_to_switch_off(self):
        self.run_install("--no-systemd", STUB_NO_BUS="1")
        self.assert_starcast_installed()
        self.assertEqual(self.systemctl(), [])

    def test_uninstall_without_a_service_manager_keeps_the_service(self):
        self.run_install()
        self.run_install("--uninstall", rc=1, STUB_NO_BUS="1")
        self.assertTrue((self.units / "ytm-starcast.service").exists())
        self.assertTrue(self.app.exists())

    # ---- where the units are ----
    def test_pocketfader_unit_in_dot_config_while_xdg_config_home_points_elsewhere(self):
        # the old installer and window always used ~/.config/systemd/user, whatever XDG_CONFIG_HOME said
        del self.env["PF_UNIT_DIR"]
        self.env["XDG_CONFIG_HOME"] = str(self.home / "xdgconf")
        self.fake_pocketfader(args="--token --allow-host music.example.com")
        self.run_install()
        sc = self.systemctl()
        self.assertIn("disable --now pocketfader.service", sc)
        self.assertFalse((self.units / "pocketfader.service").exists())
        self.assertFalse(self.old_app.exists())
        # this service manager doesn't read $XDG_CONFIG_HOME/systemd/user, so the new unit goes where it does
        unit = (self.units / "ytm-starcast.service").read_text()
        self.assertIn("/venv/bin/ytm-starcast --token --allow-host music.example.com\n", unit)
        self.assertFalse((self.home / "xdgconf" / "systemd" / "user" / "ytm-starcast.service").exists())

    def test_new_unit_goes_to_xdg_config_home_when_the_service_manager_reads_it(self):
        del self.env["PF_UNIT_DIR"]
        xdg_units = self.home / "xdgconf" / "systemd" / "user"
        self.env["XDG_CONFIG_HOME"] = str(self.home / "xdgconf")
        self.run_install(STUB_UNITPATH=f"/run/user/1/systemd/transient {xdg_units} /etc/systemd/user")
        self.assertTrue((xdg_units / "ytm-starcast.service").exists())
        self.assertFalse((self.units / "ytm-starcast.service").exists())

    def test_pocketfader_unit_found_through_systemd(self):
        elsewhere = self.home / "units"
        self.fake_pocketfader(args="--lan", units=elsewhere)
        self.run_install(STUB_FRAGMENT=str(elsewhere / "pocketfader.service"))
        self.assertIn("disable --now pocketfader.service", self.systemctl())
        self.assertFalse((elsewhere / "pocketfader.service").exists())
        self.assertIn("/venv/bin/ytm-starcast --lan\n", (self.units / "ytm-starcast.service").read_text())

    def test_a_pocketfader_command_you_made_yourself_stays(self):
        self.bin.mkdir(parents=True)
        _exe(self.bin / "pocketfader", "#!/bin/sh\necho mine\n")
        self.run_install()
        self.assertTrue((self.bin / "pocketfader").is_file())

    def beacon_data(self):
        data = self.share / "ytm-beacon"
        (data / "extension" / "chrome").mkdir(parents=True)
        (data / "extension-connected").write_text("")
        (data / "ytm-beacon.log").write_text("log\n")
        return data

    def test_ytm_beacon_service_without_its_program(self):
        self.fake_beacon(with_program=False)
        data = self.beacon_data()
        calls = self.run_install()
        self.assertFalse([c for c in calls if c.startswith("ytm-beacon")])
        sc = self.systemctl()
        self.assertIn("disable --now ytm-beacon.service", sc)
        self.assertLess(sc.index("disable --now ytm-beacon.service"), sc.index("restart ytm-starcast.service"))
        self.assertFalse((self.units / "ytm-beacon.service").exists())
        self.assertTrue((self.cfg / "ytm-beacon" / "config.json").exists())
        # by hand, the same as its uninstaller: the extension copy goes, its settings and log stay
        self.assertFalse((data / "extension").exists() or (data / "extension-connected").exists())
        self.assertTrue((data / "ytm-beacon.log").exists())

    def test_when_its_uninstaller_works_nothing_else_of_ytm_beacon_is_touched(self):
        self.fake_beacon()
        data = self.beacon_data()
        self.run_install()
        self.assertNotIn("disable --now ytm-beacon.service", self.systemctl())     # its uninstaller did that
        self.assertTrue((data / "extension" / "chrome").is_dir())                  # (this stand-in leaves it)

    def test_a_failing_ytm_beacon_uninstaller_is_cleaned_up_by_hand(self):
        beacon = self.fake_beacon()
        beacon.write_text("#!/bin/bash\necho \"ytm-beacon $*\" >> \"$STUB_LOG\"\nexit 3\n")
        self.run_install()
        self.assertIn("disable --now ytm-beacon.service", self.systemctl())
        self.assertFalse((self.units / "ytm-beacon.service").exists())
        self.assertIn("didn't finish", self.out)

    def test_keep_ytm_beacon(self):
        beacon = self.fake_beacon()
        calls = self.run_install("--keep-ytm-beacon")
        self.assertFalse([c for c in calls if c.startswith("ytm-beacon")])
        self.assertTrue(beacon.exists() and (self.units / "ytm-beacon.service").exists())
        self.assertNotIn("disable --now ytm-beacon.service", self.systemctl())

    def test_uninstall_keeps_the_settings(self):
        self.run_install()
        settings = self.cfg / "ytm-starcast"
        settings.mkdir(parents=True)
        (settings / "config.json").write_text("{}")
        self.log.unlink()
        self.run_install("--uninstall")
        self.assertIn("disable --now ytm-starcast.service", self.systemctl())
        for gone in (self.units / "ytm-starcast.service", self.apps / "ytm-starcast.desktop", self.icons / "ytm-starcast.svg",
                     self.bin / "ytm-starcast", self.bin / "ytm-starcast-gui", self.app):
            self.assertFalse(gone.exists() or gone.is_symlink(), gone)
        self.assertTrue((settings / "config.json").exists())
        self.assertIn("settings are kept in ~/.config/ytm-starcast", self.out)

    def test_installing_again_keeps_the_options_and_start_at_login_off(self):
        self.run_install("--lan")
        self.log.unlink()
        self.run_install()                                   # ytm-starcast.service now exists, and is not enabled
        sc = self.systemctl()
        self.assertEqual(sc, ["is-enabled ytm-starcast.service", "daemon-reload", "restart ytm-starcast.service",
                              "is-active --quiet ytm-starcast.service"])
        self.assertIn("/venv/bin/ytm-starcast --lan\n", (self.units / "ytm-starcast.service").read_text())
        self.assertIn("Keeping the options it was started with: --lan", self.out)
        self.assertIn("Start at login stays off, as you had it", self.out)

    def test_installing_again_keeps_start_at_login_on(self):
        self.run_install()
        self.log.unlink()
        self.run_install(STUB_ENABLED="ytm-starcast.service")
        self.assertEqual(self.systemctl(),
                         ["is-enabled ytm-starcast.service", "daemon-reload", "enable ytm-starcast.service", "restart ytm-starcast.service",
                          "is-active --quiet ytm-starcast.service"])
        self.assertIn("/venv/bin/ytm-starcast\n", (self.units / "ytm-starcast.service").read_text())

    def test_uninstall_removes_the_song_cache(self):
        self.run_install()
        cache = self.home / ".cache"
        for name in ("ytm-starcast", "pocketfader"):
            (cache / name / "audio").mkdir(parents=True)
            (cache / name / "audio" / "x.m4a").write_bytes(b"x")
        (cache / "other-app").mkdir()
        self.run_install("--uninstall")
        self.assertFalse((cache / "ytm-starcast").exists() or (cache / "pocketfader").exists())
        self.assertTrue((cache / "other-app").is_dir())

    def test_uninstall_stops_a_server_the_desktop_window_started(self):
        # Without systemd the desktop window starts `python -m starcast` itself and writes its pid. A stand-in module
        # that only waits plays the server; the pid file under the old name must not stop it (a different program).
        mod = self.root / "mod" / "starcast"
        mod.mkdir(parents=True)
        (mod / "__init__.py").write_text("")
        (mod / "__main__.py").write_text("import time\ntime.sleep(60)\n")
        procs = []
        for name in ("ytm-starcast", "pocketfader"):
            p = subprocess.Popen([sys.executable, "-m", "starcast"], cwd=str(mod.parent), stdin=subprocess.DEVNULL,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.addCleanup(p.wait)
            self.addCleanup(p.kill)
            (self.cfg / name).mkdir(parents=True)
            (self.cfg / name / "server.pid").write_text(str(p.pid))
            procs.append(p)
        self.run_install("--uninstall")
        self.assertIsNotNone(procs[0].wait(timeout=10))
        self.assertIsNone(procs[1].poll())

    def test_uninstall_with_no_systemd_still_switches_off_a_service_left_behind(self):
        self.run_install()
        self.log.unlink()
        self.run_install("--uninstall", "--no-systemd")
        self.assertEqual(self.systemctl(), ["disable --now ytm-starcast.service", "daemon-reload"])
        self.assertFalse((self.units / "ytm-starcast.service").exists())

    def test_uninstall_also_clears_pocketfader_leftovers(self):
        self.fake_pocketfader()
        self.run_install("--uninstall")
        self.assertIn("disable --now pocketfader.service", self.systemctl())
        self.assertFalse(self.old_app.exists() or (self.bin / "pocketfader").is_symlink())
        self.assertTrue((self.cfg / "pocketfader" / "config.json").exists())
        self.assertIn("settings are kept in ~/.config/pocketfader", self.out)

    def test_without_systemd(self):
        self.run_install("--no-systemd")
        self.assert_starcast_installed()
        self.assertEqual(self.systemctl(), [])
        self.assertFalse((self.units / "ytm-starcast.service").exists())
        self.assertIn("Start it with:  ytm-starcast", self.out)

    def test_without_systemd_a_pocketfader_autostart_entry_is_carried_over(self):
        auto = self.cfg / "autostart"
        auto.mkdir(parents=True)
        (auto / "pocketfader.desktop").write_text(
            "[Desktop Entry]\nType=Application\nName=Pocketfader\nExec=/old/bin/pocketfader --lan\nTerminal=false\n")
        self.run_install("--no-systemd")
        self.assertFalse((auto / "pocketfader.desktop").exists())
        entry = (auto / "ytm-starcast.desktop").read_text()
        self.assertIn(f"Exec={self.app}/venv/bin/ytm-starcast --lan\n", entry)

    def test_help(self):
        p = subprocess.run([BASH, str(SCRIPT), "--help"], env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(p.returncode, 0)
        self.assertIn("YTM Starcast installer for Linux", p.stdout)
        self.assertIn("--uninstall", p.stdout)
        self.assertEqual(self.calls(), [])


if __name__ == "__main__":
    unittest.main()
