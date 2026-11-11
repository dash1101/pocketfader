import http.client
import json
import os
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock

import threading

from starcast.opener import Opener
from starcast.audio import AudioFetcher
from starcast.config import JsonFile
from starcast.server import App, Handler
from starcast.updater import Updater

from .fakes import FakeAudio, FakeEngine, FakeMedia, FakeToggleEQ
from .test_beacon import BeaconGuard, make_discord
from .test_discord_fakes import DialGuard
from starcast.backends.base import NullEngine
import tempfile
from pathlib import Path

# Nothing here may reach the real YTM Beacon app or the real Discord: the guards fail the run if anything does.
GUARDS = (BeaconGuard(), DialGuard())


def setUpModule():
    for g in GUARDS:
        g.install()


def tearDownModule():
    for g in GUARDS:
        g.uninstall()
    for g in GUARDS:
        g.check()


class Base(unittest.TestCase):
    token = None
    engine_cls = NullEngine
    media_avail = True

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        # anything that asks for the settings folder gets a temporary one, never the real one
        env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": self.tmp.name, "APPDATA": self.tmp.name})
        env.start()
        self.addCleanup(env.stop)
        self.audio, self.media, self.engine = FakeAudio(), FakeMedia(self.media_avail), self.engine_cls()
        self.app = App(self.audio, self.engine, self.media,
                       prefs=JsonFile(Path(self.tmp.name) / "prefs.json"), token=self.token,
                       beacon=make_discord(Path(self.tmp.name) / "ytm-starcast", dry_run=True),
                       opener=Opener(dry_run=True), updater=Updater(cache=Path(self.tmp.name) / "updates"),
                       audio_fetcher=AudioFetcher(folder=Path(self.tmp.name) / 'audio', ytdlp=''))
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.app = self.app
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def req(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        h = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body)
            h.setdefault("Content-Type", "application/json")
        c.request(method, path, data, h)
        r = c.getresponse()
        raw = r.read()
        c.close()
        try:
            return r.status, json.loads(raw), r
        except ValueError:
            return r.status, raw, r


class ApiTests(Base):
    def test_state_shape_and_capabilities_without_engine(self):
        _, st, _ = self.req("GET", "/api/state")
        self.assertEqual(st["volume"]["percent"], 50)
        self.assertFalse(st["capabilities"]["eq"])
        self.assertFalse(st["capabilities"]["meter"])
        self.assertEqual(st["capabilities"]["engine_actions"], [])

    def test_volume_validation_and_effect(self):
        self.assertEqual(self.req("POST", "/api/volume", {"percent": 30})[0], 200)
        self.assertEqual(self.audio.state["percent"], 30)
        self.assertEqual(self.req("POST", "/api/volume", {"delta": 5})[0], 200)
        self.assertEqual(self.audio.state["percent"], 35)
        self.assertEqual(self.req("POST", "/api/volume", {"delta": -100})[0], 200)
        self.assertEqual(self.audio.state["percent"], 0)
        for bad in ({"percent": 101}, {"percent": -1}, {"percent": "x"}, {"percent": True}, {"delta": "a"}):
            self.assertEqual(self.req("POST", "/api/volume", bad)[0], 400, bad)

    def test_volume_503_without_output(self):
        self.audio.state["available"] = False
        self.assertEqual(self.req("POST", "/api/volume", {"percent": 10})[0], 503)

    def test_eq_not_supported_by_default(self):
        self.assertEqual(self.req("POST", "/api/eq", {"band": "bass", "db": 3})[0], 501)

    def test_bad_bodies(self):
        self.assertEqual(self.req("POST", "/api/volume", [1])[0], 400)
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("POST", "/api/volume", "{nope", {"Content-Type": "application/json"})
        r = c.getresponse(); r.read(); c.close()
        self.assertEqual(r.status, 400)

    def test_unknown_engine_action(self):
        self.assertEqual(self.req("POST", "/api/engine", {"action": "x"})[0], 400)

    def test_path_traversal_blocked(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("GET", "/../server.py")
        r = c.getresponse(); r.read(); c.close()
        self.assertEqual(r.status, 404)
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("GET", "/%2e%2e/server.py")
        r = c.getresponse(); r.read(); c.close()
        self.assertEqual(r.status, 404)

    def test_index_served(self):
        s, body, _ = self.req("GET", "/")
        self.assertEqual(s, 200)
        self.assertIn(b"<title>YTM Starcast</title>", body)

    def test_media_fallback_when_extension_offline(self):
        _, st, _ = self.req("GET", "/api/ytm/state")
        self.assertEqual((st["connected"], st["source"], st["now"]["title"]), (True, "media", "T"))
        self.req("POST", "/api/ytm", {"type": "transport", "action": "playpause"})
        self.req("POST", "/api/ytm", {"type": "transport", "action": "seek", "position": 9})
        self.assertEqual(self.media.calls, ["playpause", ("seek", 9)])
        # non-transport commands need the extension
        _, r, _ = self.req("POST", "/api/ytm", {"type": "search", "query": "x"})
        self.assertEqual(r, {"error": "extension_offline"})

    def test_extension_takes_priority_when_connected(self):
        self.app.bridge.push_state({"now": {"title": "FromYTM"}, "queue": []})
        _, st, _ = self.req("GET", "/api/ytm/state")
        self.assertEqual((st["source"], st["now"]["title"]), ("ytm", "FromYTM"))
        # transport must go to the extension, not the host player
        t = threading.Thread(target=lambda: self.req("POST", "/api/ytm", {"type": "transport", "action": "next"}))
        t.start()
        cmd = self.app.bridge.poll(timeout=3)
        self.assertEqual((cmd or {}).get("action"), "next")
        self.app.bridge.deliver_result(cmd["id"], {"ok": True})
        t.join(3)
        self.assertEqual(self.media.calls, [])

    def test_no_media_at_all(self):
        self.media.avail = False
        _, st, _ = self.req("GET", "/api/ytm/state")
        self.assertFalse(st["connected"])


class EngineTests(Base):
    engine_cls = FakeEngine

    def test_eq_roundtrip_and_validation(self):
        self.assertEqual(self.req("POST", "/api/eq", {"band": "bass", "db": 3})[0], 200)
        self.assertEqual(self.engine.eq["bass"], 3)
        self.assertEqual(self.req("POST", "/api/eq", {"band": "nope", "db": 3})[0], 400)
        self.assertEqual(self.req("POST", "/api/eq", {"band": "mid", "db": 99})[0], 400)
        _, st, _ = self.req("GET", "/api/state")
        self.assertTrue(st["capabilities"]["eq"])
        self.assertEqual(st["capabilities"]["engine_label"], "FakeEngine")

    def test_engine_action(self):
        self.assertEqual(self.req("POST", "/api/engine", {"action": "restart"})[0], 200)
        self.assertEqual(self.engine.ran, ["restart"])


class SecurityTests(Base):
    token = "s3cret-token-value-123"

    def test_api_requires_token(self):
        self.assertEqual(self.req("GET", "/api/state")[0], 401)
        self.assertEqual(self.req("POST", "/api/volume", {"percent": 5})[0], 401)
        self.assertEqual(self.audio.calls, [])

    def test_bearer_and_cookie_work(self):
        self.assertEqual(self.req("GET", "/api/state", headers={"Authorization": "Bearer " + self.token})[0], 200)
        self.assertEqual(self.req("GET", "/api/state", headers={"Cookie": "pf_token=" + self.token})[0], 200)
        self.assertEqual(self.req("GET", "/api/state", headers={"Authorization": "Bearer wrong"})[0], 401)

    def test_token_link_sets_cookie_and_redirects(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("GET", "/?token=" + self.token)
        r = c.getresponse(); r.read(); c.close()
        self.assertEqual(r.status, 302)
        self.assertEqual(r.getheader("Location"), "/")
        self.assertIn("pf_token=" + self.token, r.getheader("Set-Cookie"))
        self.assertIn("HttpOnly", r.getheader("Set-Cookie"))

    def test_html_navigation_gets_login_page(self):
        s, body, _ = self.req("GET", "/", headers={"Accept": "text/html"})
        self.assertEqual(s, 401)
        self.assertIn(b"token", body)

    def test_public_paths_and_info(self):
        self.assertEqual(self.req("GET", "/manifest.json")[0], 200)
        s, info, _ = self.req("GET", "/api/info")
        self.assertEqual((s, info["auth"], info["name"]), (200, True, "YTM Starcast"))

    def test_login_page_names_the_app(self):
        s, body, _ = self.req("GET", "/", headers={"Accept": "text/html"})
        self.assertEqual(s, 401)
        self.assertIn(b"<title>YTM Starcast</title>", body)
        self.assertNotIn(b"Pocketfader", body)

    def test_loopback_extension_endpoints_exempt(self):
        self.assertEqual(self.req("POST", "/ext/state", {"now": {}})[0], 200)


class HostOriginTests(Base):
    def test_rebinding_host_rejected(self):
        self.assertEqual(self.req("GET", "/api/state", headers={"Host": "evil.example.com"})[0], 421)

    def test_known_hosts_allowed(self):
        for h in ("localhost:8777", "127.0.0.1:8777", "[::1]:8777", "box.tail1234.ts.net:8777", "192.168.1.5"):
            self.assertEqual(self.req("GET", "/api/info", headers={"Host": h})[0], 200, h)

    def test_cross_origin_post_rejected(self):
        s, _, _ = self.req("POST", "/api/volume", {"percent": 1}, {"Origin": "https://evil.example"})
        self.assertEqual(s, 403)
        self.assertEqual(self.audio.calls, [])

    def test_same_origin_and_extension_origin_allowed(self):
        host = f"127.0.0.1:{self.port}"
        self.assertEqual(self.req("POST", "/api/volume", {"percent": 1}, {"Origin": f"http://{host}"})[0], 200)
        s, _, r = self.req("POST", "/ext/state", {"now": {}}, {"Origin": "https://music.youtube.com"})
        self.assertEqual(s, 200)
        self.assertEqual(r.getheader("Access-Control-Allow-Origin"), "https://music.youtube.com")

    def test_no_wildcard_cors(self):
        _, _, r = self.req("GET", "/api/state", headers={"Origin": "https://evil.example"})
        self.assertIsNone(r.getheader("Access-Control-Allow-Origin"))




class OutputsTests(Base):
    engine_cls = FakeToggleEQ

    def test_list_switch_and_levels(self):
        _, d, _ = self.req("GET", "/api/outputs")
        self.assertEqual((d["default"], [o["name"] for o in d["outputs"]]), ("speakers", ["speakers", "headset"]))
        # set a level on a device that is NOT the current output, then switch to it
        self.assertEqual(self.req("POST", "/api/output/volume", {"name": "headset", "percent": 25})[0], 200)
        self.assertEqual(self.audio.levels["headset"], 25)
        self.assertEqual(self.audio.default, "speakers")
        self.assertEqual(self.req("POST", "/api/output", {"name": "headset"})[0], 200)
        self.assertEqual(self.audio.default, "headset")

    def test_validation(self):
        self.assertEqual(self.req("POST", "/api/output", {"name": "nope"})[0], 404)
        self.assertEqual(self.req("POST", "/api/output", {})[0], 400)
        self.assertEqual(self.req("POST", "/api/output/volume", {"name": "headset", "percent": 101})[0], 400)
        self.assertEqual(self.req("POST", "/api/output/volume", {"name": "nope", "percent": 5})[0], 404)

    def test_switching_with_eq_on_moves_the_eq_instead(self):
        self.req("POST", "/api/eq", {"enabled": True})
        self.assertTrue(self.engine.enabled)
        self.req("POST", "/api/output", {"name": "headset"})
        self.assertEqual(self.engine.targets, ["headset"])
        self.assertNotIn(("output", "headset"), self.audio.calls)   # EQ stays the default output

    def test_eq_toggle_and_presets(self):
        _, st, _ = self.req("GET", "/api/state")
        self.assertTrue(st["capabilities"]["eq_toggle"] and st["capabilities"]["outputs"])
        self.assertEqual(self.req("POST", "/api/eq", {"enabled": True})[0], 200)
        self.assertEqual(self.req("GET", "/api/state")[1]["engine"]["enabled"], True)
        self.assertEqual(self.req("POST", "/api/eq", {"preset": "bass-boost"})[0], 200)
        self.assertEqual(self.engine.eq["bass"], 6.0)
        self.assertEqual(self.req("POST", "/api/eq", {"preset": "nope"})[0], 400)
        self.assertEqual(self.req("POST", "/api/eq", {"enabled": False})[0], 200)


class NoOutputsTests(Base):
    def test_voicemeeter_style_backend_has_no_switching(self):
        orig = FakeAudio.outputs
        del FakeAudio.outputs
        try:
            self.assertEqual(self.req("GET", "/api/outputs")[0], 501)
            self.assertFalse(self.req("GET", "/api/state")[1]["capabilities"]["outputs"])
        finally:
            FakeAudio.outputs = orig


class ProxySafetyTests(Base):
    token = "s3cret-token-value-123"

    def test_tunnelled_requests_do_not_get_the_loopback_exemption(self):
        # cloudflared connects from 127.0.0.1 and adds forwarding headers
        for h in ({"X-Forwarded-For": "203.0.113.9"}, {"CF-Connecting-IP": "203.0.113.9"}, {"CF-Ray": "abc"}):
            self.assertEqual(self.req("POST", "/ext/state", {"now": {}}, h)[0], 401, h)
        self.assertEqual(self.req("POST", "/ext/state", {"now": {}})[0], 200)   # genuine local extension

    def test_secure_cookie_over_https_proxy(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("GET", "/?token=" + self.token, headers={"X-Forwarded-Proto": "https"})
        r = c.getresponse(); r.read(); c.close()
        self.assertIn("Secure", r.getheader("Set-Cookie"))


class CommandLineTests(unittest.TestCase):
    def test_named_starcast(self):
        from starcast.cli import build_parser
        p = build_parser()
        self.assertEqual(p.prog, "ytm-starcast")
        self.assertIn("Discord", p.format_help())
        self.assertTrue(p.parse_args(["--dry-run"]).dry_run)


class TailscaleTests(unittest.TestCase):
    def test_missing_binary_returns_immediately(self):
        import time
        from unittest import mock
        from starcast import server
        with mock.patch.object(server.shutil, "which", return_value=None), \
                mock.patch.object(server.sys, "platform", "linux"):
            t = time.time()
            self.assertIsNone(server.tailscale_ip())
            self.assertFalse(server.tailscale_installed())
            self.assertLess(time.time() - t, 1)


if __name__ == "__main__":
    unittest.main()


class SleepTimerTests(Base):
    def test_set_and_cancel(self):
        code, r, _ = self.req("POST", "/api/sleep", {"minutes": 30})
        self.assertEqual(code, 200)
        self.assertTrue(1795 <= r["remainingSec"] <= 1800)
        self.assertEqual(self.req("GET", "/api/state")[1]["sleep"]["remainingSec"] > 1700, True)
        self.assertEqual(self.req("POST", "/api/sleep", {"minutes": 0})[1], {"remainingSec": 0})
        self.assertEqual(self.req("GET", "/api/state")[1]["sleep"]["remainingSec"], 0)

    def test_rejects_bad_values(self):
        for bad in (-5, 721, "soon", True, 2.5, [1]):
            self.assertEqual(self.req("POST", "/api/sleep", {"minutes": bad})[0], 400, bad)

    def test_end_of_song_uses_the_remaining_time(self):
        # FakeMedia reports 100s long, 5s in: ~96s left
        code, r, _ = self.req("POST", "/api/sleep", {"minutes": "track"})
        self.assertEqual(code, 200)
        self.assertTrue(90 <= r["remainingSec"] <= 100, r)

    def test_firing_pauses_through_the_host_player(self):
        self.app.set_sleep(0.05)
        import time
        time.sleep(0.4)
        self.assertEqual(self.media.calls, ["playpause"])    # the fake: nothing real is paused
        self.assertEqual(self.app.sleep_left(), 0)

    def test_cancel_stops_the_pause(self):
        self.app.set_sleep(0.2)
        self.app.set_sleep(None)
        import time
        time.sleep(0.5)
        self.assertEqual(self.media.calls, [])


class OpenAndPauseTests(Base):
    def test_play_without_a_tab_opens_the_song(self):
        code, r, _ = self.req("POST", "/api/ytm", {"type": "play", "videoId": "dQw4w9WgXcQ", "playlistId": "PLabc12345"})
        self.assertEqual((code, r), (200, {"ok": True, "opened": True}))
        self.assertEqual(self.app.opener.opened, ["https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc12345"])

    def test_open_home_and_playlist(self):
        self.req("POST", "/api/ytm", {"type": "open"})
        self.req("POST", "/api/ytm", {"type": "startPlaylist", "playlistId": "PLabc12345"})
        self.assertEqual(self.app.opener.opened, ["https://music.youtube.com/", "https://music.youtube.com/playlist?list=PLabc12345"])

    def test_bad_ids_open_nothing(self):
        for body in ({"type": "play", "videoId": "x; rm -rf /"}, {"type": "play", "videoId": "https://evil.example/"},
                     {"type": "startPlaylist", "playlistId": "../../x"}, {"type": "play", "videoId": 5}):
            code, r, _ = self.req("POST", "/api/ytm", body)
            self.assertEqual(r.get("error"), "extension_offline", body)
        self.assertEqual(self.app.opener.opened, [])

    def test_opener_only_opens_music_youtube(self):
        o = Opener(dry_run=True)
        self.assertFalse(o.open("https://evil.example/"))
        self.assertTrue(o.open("https://music.youtube.com/watch?v=abcde"))

    def test_pause_others_asks_the_player_backend(self):
        code, r, _ = self.req("POST", "/api/media/pause-others", {})
        self.assertEqual((code, r), (200, {"paused": ["Other"], "supported": True}))
        self.assertEqual(self.media.calls, [("pause_others", "", "")])
