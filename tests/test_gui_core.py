import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from starcast.gui import core
from starcast.gui.core import PART_ROWS, PARTS, Controller


class Rig:
    """A Controller wired to fakes: no processes, no network, a temp home and config folder."""

    def __init__(self, platform="linux", systemd=True, http=None, tailscale=True):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.calls = []
        self.enabled = set()                 # units that start at login
        self.http_map = http or {}
        self.posts = []
        self.post_reply = None

        def sh(argv, timeout=6):
            self.calls.append(list(argv))
            if argv[:3] == ["systemctl", "--user", "is-enabled"]:
                return (0 if argv[3] in self.enabled else 1), ""
            if argv[:3] == ["systemctl", "--user", "enable"]:
                self.enabled.add(argv[-1])
            if argv[:3] == ["systemctl", "--user", "disable"]:
                self.enabled.discard(argv[-1])
            if argv[-2:] == ["status", "--json"]:
                return 0, json.dumps({"Self": {"DNSName": "my-pc.tail1234.ts.net."}})
            return 0, ""

        def which(name):
            if name == "systemctl":
                return "/bin/systemctl" if systemd else None
            if name == "tailscale":
                return "/bin/tailscale" if tailscale else None
            return None

        def http(url, headers=None, timeout=1.2):
            self.last_headers = headers
            return self.http_map.get(url.split("127.0.0.1:")[1].split("/", 1)[1] if "127.0.0.1" in url else url)

        def post(url, body, headers=None, timeout=4):
            self.posts.append((url.split("127.0.0.1:")[1].split("/", 1)[1], body, headers))
            return self.post_reply

        self.c = Controller(sh=sh, http=http, platform=platform, home=root / "home", which=which,
                            cfg_dir=root / "cfg", tailscale_ip=lambda: "100.64.0.9", lan_ip=lambda: "192.168.1.20",
                            post=post)
        self.root = root
        self.units = root / "home/.config/systemd/user"

    def close(self):
        self.tmp.cleanup()


class SettingsTests(unittest.TestCase):
    def test_defaults_and_args(self):
        r = Rig()
        self.assertEqual(r.c.server_args(), [])
        r.c.update(lan=True, port=9000, allow_host="music.example.com, b.example.com")
        self.assertEqual(r.c.server_args(),
                         ["--port", "9000", "--lan", "--allow-host", "music.example.com", "--allow-host", "b.example.com"])
        r.close()

    def test_bad_port_falls_back(self):
        r = Rig()
        r.c.update(port="nope")
        self.assertEqual(r.c.settings()["port"], 8777)
        r.c.update(port=99999)
        self.assertEqual(r.c.settings()["port"], 8777)
        r.close()

    def test_token_only_when_exposed(self):
        r = Rig()
        self.assertIsNone(r.c.token())
        r.c.update(lan=True)
        tok = r.c.token()
        self.assertGreaterEqual(len(tok), 16)
        self.assertEqual(r.c.token(), tok)           # stable
        r.close()


class AddressTests(unittest.TestCase):
    def test_addresses_follow_the_settings(self):
        r = Rig()
        urls = dict(r.c.addresses())
        self.assertEqual(urls["Tailscale name"], "http://my-pc.tail1234.ts.net:8777/")
        self.assertEqual(urls["Tailscale address"], "http://100.64.0.9:8777/")
        self.assertNotIn("Home network", urls)
        r.c.update(lan=True, allow_host="music.example.com")
        urls = dict(r.c.addresses())
        self.assertIn("?token=", urls["Home network"])
        self.assertEqual(urls["Cloudflare"], "https://music.example.com/")
        r.close()

    def test_without_tailscale(self):
        r = Rig(tailscale=False)
        r.c._ts = lambda: None
        self.assertEqual([lbl for lbl, _ in r.c.addresses()], ["This computer"])
        r.close()


class ServiceTests(unittest.TestCase):
    def test_start_writes_the_unit_and_starts_it(self):
        r = Rig()
        r.c.update(lan=True)
        self.assertTrue(r.c.start())
        unit = (r.units / "ytm-starcast.service").read_text()
        self.assertIn("--lan", unit)
        self.assertIn("Description=YTM Starcast - YouTube Music companion", unit)
        self.assertIn(["systemctl", "--user", "start", "ytm-starcast.service"], r.calls)
        self.assertFalse([c for c in r.calls if "pocketfader.service" in c])     # nothing old to take over
        r.close()

    def test_autostart_toggles_via_systemd(self):
        r = Rig()
        self.assertFalse(r.c.autostart_enabled())
        r.c.set_autostart(True)
        self.assertTrue(r.c.autostart_enabled())
        r.c.set_autostart(False)
        self.assertFalse(r.c.autostart_enabled())
        r.close()

    def test_autostart_without_systemd_uses_an_xdg_entry(self):
        r = Rig(systemd=False)
        r.c.set_autostart(True)
        entry = r.root / "home/.config/autostart/ytm-starcast.desktop"
        self.assertTrue(entry.exists() and r.c.autostart_enabled())
        self.assertIn("Name=YTM Starcast\n", entry.read_text())
        r.c.set_autostart(False)
        self.assertFalse(entry.exists())
        r.close()

    def test_changing_settings_rewrites_the_unit_on_restart(self):
        r = Rig()
        r.c.start()
        r.c.update(lan=True)
        r.c.restart()
        self.assertIn("--lan", (r.units / "ytm-starcast.service").read_text())
        r.close()


class LegacyTests(unittest.TestCase):
    """YTM Starcast used to be called Pocketfader: its service, autostart entry and Windows Run value are taken over."""

    def rig_with_old_service(self, enabled=True, **kw):
        r = Rig(**kw)
        r.units.mkdir(parents=True)
        (r.units / "pocketfader.service").write_text("[Service]\nExecStart=/old/venv/bin/pocketfader\n")
        if enabled:
            r.enabled.add("pocketfader.service")
        self.addCleanup(r.close)
        return r

    def test_start_takes_over_an_old_service_that_started_at_login(self):
        r = self.rig_with_old_service()
        self.assertTrue(r.c.autostart_enabled())                  # before the move, the old setting counts
        self.assertTrue(r.c.start())
        sc = [c[2:] for c in r.calls if c[:2] == ["systemctl", "--user"]]
        self.assertLess(sc.index(["is-enabled", "pocketfader.service"]), sc.index(["disable", "--now", "pocketfader.service"]))
        self.assertLess(sc.index(["disable", "--now", "pocketfader.service"]), sc.index(["daemon-reload"]))
        self.assertLess(sc.index(["daemon-reload"]), sc.index(["enable", "ytm-starcast.service"]))
        self.assertLess(sc.index(["enable", "ytm-starcast.service"]), sc.index(["start", "ytm-starcast.service"]))
        self.assertFalse((r.units / "pocketfader.service").exists())
        self.assertTrue((r.units / "ytm-starcast.service").exists())
        self.assertEqual(r.enabled, {"ytm-starcast.service"})
        self.assertTrue(r.c.autostart_enabled())
        n = len(r.calls)
        r.c.start()                                                  # second time: nothing old left to do
        self.assertFalse([c for c in r.calls[n:] if "pocketfader.service" in c])

    def test_an_old_service_that_did_not_start_at_login_stays_that_way(self):
        r = self.rig_with_old_service(enabled=False)
        self.assertFalse(r.c.autostart_enabled())
        r.c.start()
        self.assertIn(["systemctl", "--user", "disable", "--now", "pocketfader.service"], r.calls)
        self.assertNotIn(["systemctl", "--user", "enable", "ytm-starcast.service"], r.calls)
        self.assertFalse(r.c.autostart_enabled())

    def test_stop_stops_the_old_service_too(self):
        r = self.rig_with_old_service()
        r.c.stop()
        self.assertIn(["systemctl", "--user", "disable", "--now", "pocketfader.service"], r.calls)
        self.assertIn(["systemctl", "--user", "stop", "ytm-starcast.service"], r.calls)
        self.assertTrue(r.c.autostart_enabled())

    def test_switching_start_at_login_off_also_moves_over(self):
        r = self.rig_with_old_service()
        r.c.set_autostart(False)
        self.assertFalse((r.units / "pocketfader.service").exists())
        self.assertFalse(r.c.autostart_enabled())

    def test_old_xdg_autostart_entry(self):
        r = Rig(systemd=False)
        self.addCleanup(r.close)
        auto = r.root / "home/.config/autostart"
        auto.mkdir(parents=True)
        (auto / "pocketfader.desktop").write_text("[Desktop Entry]\nName=Pocketfader\nExec=pocketfader\n")
        self.assertTrue(r.c.autostart_enabled())
        r.c.set_autostart(True)
        self.assertFalse((auto / "pocketfader.desktop").exists())
        self.assertTrue((auto / "ytm-starcast.desktop").exists())
        (auto / "pocketfader.desktop").write_text("[Desktop Entry]\n")
        r.c.set_autostart(False)
        self.assertFalse((auto / "pocketfader.desktop").exists() or (auto / "ytm-starcast.desktop").exists())
        self.assertFalse(r.c.autostart_enabled())

    def fake_winreg(self, values):
        reg = types.ModuleType("winreg")
        reg.HKEY_CURRENT_USER, reg.KEY_SET_VALUE, reg.REG_SZ = "HKCU", 2, 1

        class Key:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def query(_k, name):
            if name not in values:
                raise FileNotFoundError(name)
            return values[name], reg.REG_SZ

        def delete(_k, name):
            if name not in values:
                raise FileNotFoundError(name)
            del values[name]

        reg.OpenKey = lambda *a: Key()
        reg.QueryValueEx = query
        reg.DeleteValue = delete
        reg.SetValueEx = lambda _k, name, _r, _t, data: values.__setitem__(name, data)
        return mock.patch.dict(sys.modules, {"winreg": reg})

    def test_windows_run_value(self):
        r = Rig(platform="win32")
        self.addCleanup(r.close)
        values = {"Pocketfader": "old command", "Other": "someone else's"}
        with self.fake_winreg(values):
            self.assertTrue(r.c.autostart_enabled())               # the old value still counts
            self.assertTrue(r.c.set_autostart(True))
            self.assertEqual(set(values), {"YTM Starcast", "Other"})
            self.assertIn("Start-Process -WindowStyle Hidden", values["YTM Starcast"])
            values["Pocketfader"] = "came back"
            self.assertTrue(r.c.set_autostart(False))
            self.assertEqual(set(values), {"Other"})
            self.assertFalse(r.c.autostart_enabled())

    def test_windows_run_value_quotes_a_folder_with_a_space(self):
        r = Rig(platform="win32")
        self.addCleanup(r.close)
        d = r.root / "Programs" / "YTM Starcast"
        d.mkdir(parents=True)
        (d / "ytm-starcast.exe").write_bytes(b"")
        values = {}
        with self.fake_winreg(values), mock.patch.object(sys, "frozen", True, create=True), \
                mock.patch.object(sys, "executable", str(d / "ytm-starcast-gui.exe")):
            self.assertTrue(r.c.set_autostart(True))
        self.assertIn(f"-FilePath '{d / 'ytm-starcast.exe'}'", values["YTM Starcast"])

    def test_packaged_window_finds_the_server_next_to_it(self):
        r = Rig(platform="win32")
        self.addCleanup(r.close)
        d = r.root / "app"
        d.mkdir()
        gui = d / "ytm-starcast-gui.exe"
        gui.write_bytes(b"")
        with mock.patch.object(sys, "frozen", True, create=True), mock.patch.object(sys, "executable", str(gui)):
            self.assertEqual(r.c.launch_command(), [str(gui)])          # nothing next to it: itself
            (d / "pocketfader.exe").write_bytes(b"")
            self.assertEqual(r.c.launch_command(), [str(d / "pocketfader.exe")])
            (d / "ytm-starcast.exe").write_bytes(b"")
            self.assertEqual(r.c.launch_command(), [str(d / "ytm-starcast.exe")])

    def test_window_id_matches_the_menu_entry(self):
        self.assertEqual(core.APP_ID, "app.novalabs.starcast")
        install_sh = (Path(__file__).resolve().parent.parent / "install.sh").read_text()
        self.assertIn(f"StartupWMClass={core.APP_ID}\n", install_sh)       # so the desktop shows YTM Starcast's icon

    def test_installed_command_is_starcast(self):
        r = Rig()
        self.addCleanup(r.close)
        venv = r.root / "venv" / "bin"
        venv.mkdir(parents=True)
        python = venv / "python3"
        with mock.patch.object(sys, "executable", str(python)):
            self.assertEqual(r.c.launch_command(), [str(python), "-m", "starcast"])
            r.c.which = lambda name: {"ytm-starcast": "/home/u/.local/bin/ytm-starcast", "pocketfader": "/old"}.get(name)
            self.assertEqual(r.c.launch_command(), ["/home/u/.local/bin/ytm-starcast"])
            # the program next to this window's Python wins: it is the same installation, and is found even when
            # ~/.local/bin isn't on the window's PATH
            (venv / "ytm-starcast").write_text("#!/bin/sh\n")
            self.assertEqual(r.c.launch_command(), [str(venv / "ytm-starcast")])
            r.c.which = lambda name: None
            self.assertEqual(r.c.launch_command(), [str(venv / "ytm-starcast")])
            self.assertIn(f"ExecStart={venv / 'ytm-starcast'}\n", r.c.unit_text())


class DetailsTests(unittest.TestCase):
    def test_not_running(self):
        r = Rig()
        self.assertFalse(r.c.is_running())
        self.assertIsNone(r.c.details())
        r.close()

    def test_summary_sends_the_token_when_the_server_wants_one(self):
        r = Rig(http={"api/info": {"name": "YTM Starcast", "version": "0.2.0a1", "auth": True},
                      "api/state": {"volume": {"device": "Speakers", "percent": 40},
                                    "capabilities": {"eq_toggle": True}, "engine": {"enabled": False}},
                      "api/ytm/state": {"connected": True, "source": "ytm", "now": {"title": "Song"}}})
        d = r.c.details()
        self.assertEqual((d["device"], d["volume"], d["ytm"], d["eq"], d["now"]), ("Speakers", 40, True, False, "Song"))
        self.assertTrue(r.last_headers["Authorization"].startswith("Bearer "))
        self.assertIsNone(d["parts"])                                # an older server: no switches
        r.close()


ALL_ON = {"remote": True, "discord": True, "refine": True, "audio": True}


class PartsTests(unittest.TestCase):
    def rig(self, auth=False, parts=ALL_ON):
        r = Rig(http={"api/info": {"name": "YTM Starcast", "version": "0.2.0a1", "auth": auth},
                      "api/state": {"parts": parts}, "api/parts": {"parts": parts}})
        self.addCleanup(r.close)
        return r

    def test_the_four_switches(self):
        self.assertEqual(PARTS, ("remote", "discord", "refine", "audio"))
        self.assertEqual([t for _, t, _ in PART_ROWS], ["YouTube Music remote", "Discord status", "Refine in YouTube Music",
                                                       "Computer audio"])   # the same words as the web and Android apps

    def test_read_from_state_and_from_the_parts_route(self):
        r = self.rig(parts={"remote": True, "discord": False, "refine": True, "audio": True})
        self.assertEqual(r.c.details()["parts"]["discord"], False)
        self.assertEqual(r.c.parts(), {"remote": True, "discord": False, "refine": True, "audio": True})

    def test_missing_keys_count_as_on(self):
        r = self.rig(parts={"discord": False})
        self.assertEqual(r.c.parts(), {"remote": True, "discord": False, "refine": True, "audio": True})

    def test_set_part_posts_one_switch_with_the_token(self):
        r = self.rig(auth=True)
        r.post_reply = {"parts": {**ALL_ON, "refine": False}}
        self.assertEqual(r.c.set_part("refine", False), {**ALL_ON, "refine": False})
        path, body, headers = r.posts[0]
        self.assertEqual((path, body), ("api/parts", {"refine": False}))
        self.assertTrue(headers["Authorization"].startswith("Bearer "))

    def test_no_token_when_the_server_wants_none(self):
        r = self.rig()
        r.post_reply = {"parts": ALL_ON}
        r.c.set_part("audio", True)
        self.assertEqual(r.posts[0][2], {})

    def test_failures_and_old_servers_give_none(self):
        r = self.rig()
        self.assertIsNone(r.c.set_part("remote", False))             # no answer
        r.post_reply = {"error": "not found"}
        self.assertIsNone(r.c.set_part("remote", False))
        with self.assertRaises(ValueError):
            r.c.set_part("volume", True)
        old = Rig(http={"api/info": {"name": "pocketfader", "version": "0.1.0a15"}})
        self.addCleanup(old.close)
        self.assertIsNone(old.c.parts())
        stopped = Rig()
        self.addCleanup(stopped.close)
        self.assertIsNone(stopped.c.parts())
        self.assertIsNone(stopped.c.set_part("discord", True))
        self.assertEqual(stopped.posts, [])

    def test_real_post_helper_sends_json(self):
        seen = {}

        class Resp:
            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

            def read(self):
                return b'{"parts": {"remote": false}}'

        def fake_open(req, timeout=None):
            seen.update(url=req.full_url, method=req.get_method(), body=req.data, ctype=req.get_header("Content-type"))
            return Resp()

        with mock.patch.object(core.urllib.request, "urlopen", fake_open):
            got = core._post("http://127.0.0.1:8777/api/parts", {"remote": False}, {"Authorization": "Bearer x"})
        self.assertEqual(got, {"parts": {"remote": False}})
        self.assertEqual((seen["method"], json.loads(seen["body"]), seen["ctype"]), ("POST", {"remote": False}, "application/json"))


if __name__ == "__main__":
    unittest.main()
