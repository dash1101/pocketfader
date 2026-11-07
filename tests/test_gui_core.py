import json
import tempfile
import unittest
from pathlib import Path

from pocketfader.gui.core import Controller


class Rig:
    """A Controller wired to fakes: no processes, no network, a temp home and config folder."""

    def __init__(self, platform="linux", systemd=True, http=None, tailscale=True):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.calls = []
        self.enabled = False
        self.http_map = http or {}

        def sh(argv, timeout=6):
            self.calls.append(list(argv))
            if argv[:3] == ["systemctl", "--user", "is-enabled"]:
                return (0 if self.enabled else 1), ""
            if argv[:3] == ["systemctl", "--user", "enable"]:
                self.enabled = True
            if argv[:3] == ["systemctl", "--user", "disable"]:
                self.enabled = False
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

        self.c = Controller(sh=sh, http=http, platform=platform, home=root / "home", which=which,
                            cfg_dir=root / "cfg", tailscale_ip=lambda: "100.64.0.9", lan_ip=lambda: "192.168.1.20")
        self.root = root

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
        unit = (r.root / "home/.config/systemd/user/pocketfader.service").read_text()
        self.assertIn("--lan", unit)
        self.assertIn(["systemctl", "--user", "start", "pocketfader.service"], r.calls)
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
        entry = r.root / "home/.config/autostart/pocketfader.desktop"
        self.assertTrue(entry.exists() and r.c.autostart_enabled())
        r.c.set_autostart(False)
        self.assertFalse(entry.exists())
        r.close()

    def test_changing_settings_rewrites_the_unit_on_restart(self):
        r = Rig()
        r.c.start()
        r.c.update(lan=True)
        r.c.restart()
        self.assertIn("--lan", (r.root / "home/.config/systemd/user/pocketfader.service").read_text())
        r.close()


class DetailsTests(unittest.TestCase):
    def test_not_running(self):
        r = Rig()
        self.assertFalse(r.c.is_running())
        self.assertIsNone(r.c.details())
        r.close()

    def test_summary_sends_the_token_when_the_server_wants_one(self):
        r = Rig(http={"api/info": {"name": "pocketfader", "version": "0.1.0", "auth": True},
                      "api/state": {"volume": {"device": "Speakers", "percent": 40},
                                    "capabilities": {"eq_toggle": True}, "engine": {"enabled": False}},
                      "api/ytm/state": {"connected": True, "source": "ytm", "now": {"title": "Song"}}})
        d = r.c.details()
        self.assertEqual((d["device"], d["volume"], d["ytm"], d["eq"], d["now"]), ("Speakers", 40, True, False, "Song"))
        self.assertTrue(r.last_headers["Authorization"].startswith("Bearer "))
        r.close()


if __name__ == "__main__":
    unittest.main()
