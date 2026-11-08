"""YTM Beacon bridge: normalizer + headline fixtures, validation, port discovery, and the whitelisted routes.

Everything runs against a tiny stub of Beacon's local API; the real Beacon is never contacted.
"""
import copy
import time
import http.client
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from pocketfader import beacon as bc
from pocketfader.beacon import BadRequest, Beacon, BeaconError, Installer
from pocketfader.config import JsonFile
from pocketfader.server import App, Handler

from .fakes import FakeAudio, FakeMedia
from pocketfader.backends.base import NullEngine

PLAYING = {
    "app": "ytm-beacon", "version": "v0.1.4", "port": 41713, "uptimeSec": 600, "pid": 4242,
    "configPath": "/home/someone/.config/ytm-beacon/config.json", "logPath": "/home/someone/log",
    "extensionDirs": ["/home/someone/ext"], "clientIdSet": True,
    "discord": {"state": "connected", "user": "ExampleUser"},
    "extension": {"lastSeenAgoSec": 2, "everConnected": True, "quietAfterSec": 90,
                  "sources": [{"id": "tab-1", "agent": "Brave", "state": "playing", "ageSec": 2}]},
    "nowPlaying": {"title": "Song", "artist": "Artist", "album": "Album", "artwork": "https://example.com/a.jpg",
                   "videoId": "abc123", "state": "playing", "position": 42.5, "duration": 201},
    "presenceActive": True, "visible": True, "snoozeSec": 0, "hidden": "",
    "config": {"enabled": True, "clientId": "123456789012345678", "showButtons": True, "showPaused": True,
               "pauseTimeoutMinutes": 5, "showAlbumArt": True, "statusDisplay": "name", "showProgress": True,
               "showStateIcon": True, "privateMode": False, "hideMatching": ["secret word"]},
}


def variant(**edits):
    s = copy.deepcopy(PLAYING)
    for k, v in edits.items():
        if k in ("discord", "extension"):
            s[k].update(v)
        else:
            s[k] = v
    return s


class StubBeacon:
    """A fake Beacon on a random port; records what it was asked."""

    def __init__(self, status=None, app="ytm-beacon"):
        self.status = copy.deepcopy(status or PLAYING)
        self.app, self.calls, self.fail_next = app, [], False
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _reply(self, code, obj):
                raw = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                outer.calls.append(("GET", self.path, None))
                if self.path == "/v1/ping":
                    return self._reply(200, {"app": outer.app, "version": "v0.1.4"})
                if self.path == "/v1/status":
                    return self._reply(200, outer.status)
                self._reply(404, {"error": "no such thing"})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                outer.calls.append(("POST", self.path, body))
                if self.path == "/v1/visibility":
                    if body.get("action") == "hide":
                        mins = body.get("minutes")
                        outer.status.update(visible=False, snoozeSec=(mins or 0) * 60)
                        outer.status["config"]["enabled"] = mins is not None
                    elif body.get("action") == "show":
                        outer.status.update(visible=True, snoozeSec=0)
                        outer.status["config"]["enabled"] = True
                    else:
                        return self._reply(400, {"error": "unknown action"})
                    return self._reply(200, {"enabled": outer.status["config"]["enabled"],
                                             "snoozeSec": outer.status["snoozeSec"], "visible": outer.status["visible"]})
                if self.path == "/v1/config":
                    if body.get("statusDisplay") == "bogus":
                        return self._reply(400, {"error": "statusDisplay must be name, state or details"})
                    outer.status["config"].update(body)
                    return self._reply(200, outer.status["config"])
                self._reply(404, {"error": "no such thing"})

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class HeadlineTests(unittest.TestCase):
    def h(self, s, ok=True):
        return bc.headline(s, ok)

    def test_fixtures(self):
        cases = [
            (PLAYING, "showing"),
            (variant(visible=False, snoozeSec=0, hidden="off", presenceActive=False), "off"),
            (variant(visible=False, snoozeSec=3120, hidden="snoozed", presenceActive=False), "snoozed"),
            (variant(discord={"state": "disconnected", "lastError": "Discord isn't running"}), "discord-down"),
            (variant(extension={"lastSeenAgoSec": None, "everConnected": False, "sources": []}), "extension-missing"),
            (variant(extension={"lastSeenAgoSec": None, "everConnected": True, "sources": []}), "extension-quiet"),
            (variant(extension={"lastSeenAgoSec": 300}), "extension-quiet"),
            (variant(nowPlaying=None, hidden="", presenceActive=False), "idle"),
            (variant(hidden="paused", presenceActive=False), "paused-hidden"),
            (variant(hidden="paused-timeout", presenceActive=False), "paused-timeout"),
            (variant(hidden="filtered", presenceActive=False), "filtered"),
            (variant(presenceActive=False), "waiting"),
        ]
        for status, want in cases:
            self.assertEqual(self.h(status), want, want)

    def test_unreachable_and_unsupported(self):
        self.assertEqual(self.h(None), "unreachable")
        self.assertEqual(self.h(PLAYING, ok=False), "unsupported")

    def test_old_beacon_without_visible_uses_enabled(self):
        old = variant()
        del old["visible"]
        old["config"]["enabled"] = False
        self.assertEqual(self.h(old), "off")

    def test_versions(self):
        self.assertTrue(bc.version_ok("v0.1.4"))
        self.assertTrue(bc.version_ok("v0.2.0"))
        self.assertTrue(bc.version_ok("dev"))
        self.assertFalse(bc.version_ok("v0.1.3"))
        self.assertEqual(bc.normalize(variant(version="v0.1.1"))["headline"], "unsupported")


class NormalizeTests(unittest.TestCase):
    def test_private_fields_never_leave(self):
        out = json.dumps(bc.normalize(PLAYING))
        for secret in ("123456789012345678", "/home/someone", "4242", "secret word", "clientId", "hideMatching", "tab-1"):
            self.assertNotIn(secret, out)

    def test_shape(self):
        n = bc.normalize(PLAYING, now=1000)
        self.assertEqual(n["headline"], "showing")
        self.assertEqual(n["fetchedAt"], 1_000_000)
        self.assertEqual(n["discord"], {"state": "connected", "user": "ExampleUser"})
        self.assertEqual(n["nowPlaying"]["positionSec"], 42.5)
        self.assertEqual(n["settings"]["statusDisplay"], "name")
        self.assertEqual(bc.normalize(None, now=1)["reachable"], False)

    def test_detail_from_discord_error(self):
        n = bc.normalize(variant(discord={"state": "disconnected", "lastError": "Discord isn't running"}))
        self.assertEqual(n["detail"], "Discord isn't running")


class ValidationTests(unittest.TestCase):
    def test_visibility(self):
        self.assertEqual(bc.validate_visibility({"action": "show"}), {"action": "show"})
        self.assertEqual(bc.validate_visibility({"action": "hide", "minutes": 60}), {"action": "hide", "minutes": 60})
        for bad in ({"action": "toggle"}, {"action": "hide", "minutes": 0}, {"action": "hide", "minutes": 99999},
                    {"action": "hide", "minutes": True}, {"action": "hide", "minutes": 1.5}, {"action": "hide", "minutes": "5"},
                    {"action": "show", "minutes": 5}, {"action": "hide", "extra": 1}, {}, [], None):
            with self.assertRaises(BadRequest, msg=str(bad)):
                bc.validate_visibility(bad)

    def test_settings(self):
        ok = {"privateMode": True, "pauseTimeoutMinutes": 0, "statusDisplay": "details"}
        self.assertEqual(bc.validate_settings(ok), ok)
        for bad in ({}, {"clientId": "1"}, {"enabled": False}, {"hideMatching": ["x"]}, {"privateMode": "yes"},
                    {"privateMode": 1}, {"pauseTimeoutMinutes": -1}, {"pauseTimeoutMinutes": 1441},
                    {"pauseTimeoutMinutes": True}, {"statusDisplay": "bogus"}, {"privateMode": True, "nope": 1}):
            with self.assertRaises(BadRequest, msg=str(bad)):
                bc.validate_settings(bad)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.stub = StubBeacon()
        self.addCleanup(self.stub.close)

    def client(self, **kw):
        return Beacon(ports=(self.stub.port,), cache_s=0, installer=Installer(latest_api=None), **kw)

    def test_status_and_port_check(self):
        c = self.client()
        self.assertEqual(c.status()["headline"], "showing")
        # another program on the port must not be mistaken for Beacon
        other = StubBeacon(app="something-else")
        self.addCleanup(other.close)
        self.assertEqual(Beacon(ports=(other.port,), cache_s=0).status()["headline"], "unreachable")

    def test_not_running(self):
        s = StubBeacon()
        port = s.port
        s.close()
        c = Beacon(ports=(port,), cache_s=0)
        st = c.status()
        self.assertEqual((st["reachable"], st["headline"]), (False, "unreachable"))
        with self.assertRaises(BeaconError) as cm:
            c.set_visibility({"action": "show"})
        self.assertEqual(cm.exception.code, "NOT_RUNNING")

    def test_rescan_when_cached_port_dies(self):
        first = StubBeacon()
        second = StubBeacon()
        self.addCleanup(second.close)
        c = Beacon(ports=(first.port, second.port), cache_s=0)
        self.assertEqual(c.status()["headline"], "showing")
        first.close()
        self.assertEqual(c.status()["headline"], "showing")   # found again on the second port
        self.assertEqual(c._port, second.port)

    def test_visibility_round_trip(self):
        c = self.client()
        self.assertEqual(c.set_visibility({"action": "hide", "minutes": 60}),
                         {"enabled": True, "snoozeSec": 3600, "visible": False})
        self.assertEqual(c.status()["headline"], "snoozed")
        self.assertEqual(c.set_visibility({"action": "hide"})["visible"], False)
        self.assertEqual(c.status()["headline"], "off")
        self.assertEqual(c.set_visibility({"action": "show"})["visible"], True)
        sent = [b for m, p, b in self.stub.calls if p == "/v1/visibility"]
        self.assertEqual(sent, [{"action": "hide", "minutes": 60}, {"action": "hide"}, {"action": "show"}])

    def test_settings_merge_sends_only_changed_key(self):
        c = self.client()
        out = c.set_settings({"privateMode": True})
        self.assertTrue(out["privateMode"])
        self.assertEqual([b for m, p, b in self.stub.calls if p == "/v1/config"], [{"privateMode": True}])
        self.assertNotIn("clientId", out)

    def test_beacon_rejection_is_reported(self):
        c = self.client()
        with self.assertRaises(BeaconError) as cm:
            c._request("POST", "/v1/config", {"statusDisplay": "bogus"})
        self.assertEqual(cm.exception.code, "REJECTED")
        self.assertIn("statusDisplay", cm.exception.message)

    def test_dry_run_sends_nothing(self):
        c = self.client(dry_run=True)
        self.assertEqual(c.set_visibility({"action": "hide"}), {"ok": True, "dryRun": True})
        self.assertEqual(c.set_settings({"privateMode": True}), {"ok": True, "dryRun": True})
        self.assertFalse([1 for m, p, b in self.stub.calls if m == "POST"])

    def test_requests_carry_no_origin_and_loopback_host(self):
        seen = {}

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                seen.update(origin=self.headers.get("Origin"), host=self.headers.get("Host"))
                raw = json.dumps({"app": "ytm-beacon"}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        Beacon(ports=(httpd.server_address[1],)).find_port()
        self.assertIsNone(seen["origin"])
        self.assertTrue(seen["host"].startswith("127.0.0.1:"))


class RouteTests(unittest.TestCase):
    """The phone-facing routes, through the real request handler."""

    def setUp(self):
        self.stub = StubBeacon()
        self.tmp = tempfile.TemporaryDirectory()
        self.app = App(FakeAudio(), NullEngine(), FakeMedia(True), prefs=JsonFile(Path(self.tmp.name) / "p.json"),
                       beacon=Beacon(ports=(self.stub.port,), cache_s=0, installer=Installer(latest_api=None)))
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.app = self.app
        threading.Thread(target=lambda: self.httpd.serve_forever(poll_interval=0.02), daemon=True).start()
        self.port = self.httpd.server_address[1]

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.stub.close()
        self.tmp.cleanup()

    def req(self, method, path, body=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        c.request(method, path, json.dumps(body) if body is not None else None,
                  {"Content-Type": "application/json"} if body is not None else {})
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, (json.loads(raw) if raw[:1] in (b"{", b"[") else raw)

    def test_status_route_and_capability(self):
        code, st = self.req("GET", "/api/beacon")
        self.assertEqual((code, st["headline"]), (200, "showing"))
        self.assertTrue(self.req("GET", "/api/state")[1]["capabilities"]["beacon"])
        self.assertNotIn("123456789012345678", json.dumps(st))

    def test_visibility_route(self):
        code, r = self.req("POST", "/api/beacon/visibility", {"action": "hide", "minutes": 30})
        self.assertEqual((code, r["snoozeSec"], r["visible"]), (200, 1800, False))
        self.assertEqual(self.req("POST", "/api/beacon/visibility", {"action": "show"})[1]["visible"], True)

    def test_bad_bodies_never_reach_beacon(self):
        for path, body in (("/api/beacon/visibility", {"action": "toggle"}),
                           ("/api/beacon/visibility", {"action": "hide", "minutes": 0}),
                           ("/api/beacon/visibility", {"action": "hide", "minutes": 99999}),
                           ("/api/beacon/settings", {"clientId": "1"}),
                           ("/api/beacon/settings", {"hideMatching": ["x"]}),
                           ("/api/beacon/settings", {"statusDisplay": "bogus"})):
            code, r = self.req("POST", path, body)
            self.assertEqual(code, 400, (path, body))
        self.assertFalse([c for c in self.stub.calls if c[0] == "POST"])

    def test_nothing_else_is_reachable(self):
        for path in ("/api/beacon/update", "/api/beacon/clear", "/api/beacon/quit", "/api/beacon/logs", "/api/beacon/config",
                     "/api/beacon/v1/status"):
            self.assertEqual(self.req("POST", path, {})[0], 404, path)
            self.assertEqual(self.req("GET", path)[0], 404, path)
        self.assertFalse([c for c in self.stub.calls if c[1] != "/v1/ping"])

    def test_not_running(self):
        self.stub.close()
        code, st = self.req("GET", "/api/beacon")
        self.assertEqual((code, st["headline"], st["reachable"]), (200, "unreachable", False))
        code, r = self.req("POST", "/api/beacon/visibility", {"action": "show"})
        self.assertEqual(code, 503)


# ---- installing / updating Beacon from the phone ----

def make_release(tmp, name="ytm-beacon_linux_amd64.tar.gz", good_sum=True):
    """A fake GitHub 'latest release' folder served over HTTP: an archive holding a program, and checksums.txt."""
    import hashlib
    import io
    import tarfile
    d = Path(tmp)
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as t:
        data = b"#!/bin/sh\necho installed\n"
        ti = tarfile.TarInfo("./ytm-beacon")
        ti.size, ti.mode = len(data), 0o755
        t.addfile(ti, io.BytesIO(data))
    blob = buf.getvalue()
    (d / name).write_bytes(blob)
    digest = hashlib.sha256(blob).hexdigest() if good_sum else "0" * 64
    (d / "checksums.txt").write_text(f"{digest}  {name}\n")
    from http.server import SimpleHTTPRequestHandler

    class Quiet(SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=str(d), **k)

        def log_message(self, *a):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Quiet)
    threading.Thread(target=lambda: httpd.serve_forever(poll_interval=0.02), daemon=True).start()
    return httpd, f"http://127.0.0.1:{httpd.server_address[1]}"


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / "home"
        self.home.mkdir()
        self.calls = []

    def runner(self, code=0, out=""):
        def run(cmd, timeout):
            self.calls.append(cmd)
            return code, out
        return run

    def wait(self, inst, secs=5):
        end = time.time() + secs
        while inst._job["state"] == "running" and time.time() < end:
            time.sleep(0.02)
        return inst._job

    def make(self, base, **kw):
        return Installer(base_url=base, latest_api=None, system="linux", machine="x86_64", home=self.home,
                         runner=kw.pop("runner", None) or self.runner(), **kw)

    def test_install_checks_checksum_then_runs_the_installer(self):
        rel = Path(self.tmp.name) / "rel"
        rel.mkdir()
        httpd, base = make_release(rel)
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        inst = self.make(base)
        inst.start("install", running=False)
        job = self.wait(inst)
        self.assertEqual(job["state"], "done", job)
        self.assertEqual(len(self.calls), 1)
        self.assertTrue(self.calls[0][0].endswith("ytm-beacon") and self.calls[0][1:] == ["install"])
        self.assertNotIn(str(self.home), job["message"])

    def test_bad_checksum_is_never_run(self):
        rel = Path(self.tmp.name) / "rel"
        rel.mkdir()
        httpd, base = make_release(rel, good_sum=False)
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        inst = self.make(base)
        inst.start("install", running=False)
        job = self.wait(inst)
        self.assertEqual(job["state"], "failed")
        self.assertIn("checksum", job["message"])
        self.assertEqual(self.calls, [])

    def test_download_failure_is_reported(self):
        inst = self.make("http://127.0.0.1:1")
        inst.start("install", running=False)
        job = self.wait(inst)
        self.assertEqual(job["state"], "failed")
        self.assertEqual(self.calls, [])

    def test_auto_updates_when_already_installed(self):
        (self.home / ".local" / "bin").mkdir(parents=True)
        exe = self.home / ".local" / "bin" / "ytm-beacon"
        exe.write_text("#!/bin/sh\n")
        inst = self.make("http://127.0.0.1:1", runner=self.runner(0, "Updated to v0.1.5"))
        inst.start("auto", running=False)
        job = self.wait(inst)
        self.assertEqual(self.calls, [[str(exe), "update"]])
        self.assertEqual((job["state"], job["message"]), ("done", "Updated to v0.1.5"))

    def test_only_one_job_at_a_time(self):
        gate = threading.Event()
        inst = self.make("http://127.0.0.1:1", runner=lambda c, t: (gate.wait(5), (0, ""))[1])
        (self.home / ".local" / "bin").mkdir(parents=True)
        (self.home / ".local" / "bin" / "ytm-beacon").write_text("x")
        inst.start("update", running=True)
        with self.assertRaises(BeaconError):
            inst.start("update", running=True)
        gate.set()
        self.wait(inst)

    def test_unsupported_computer(self):
        inst = Installer(latest_api=None, system="linux", machine="riscv64", home=self.home, runner=self.runner())
        with self.assertRaises(BeaconError):
            inst.start("install", running=False)

    def test_start_is_a_fixed_command(self):
        inst = self.make("http://127.0.0.1:1")
        if not inst.can_start():
            self.skipTest("no systemctl here")
        inst.start("start", running=False)
        self.wait(inst)
        self.assertEqual(self.calls, [["systemctl", "--user", "start", "ytm-beacon.service"]])

    def test_info_reports_updates(self):
        inst = self.make("http://127.0.0.1:1")
        inst._latest = (time.time(), "v0.1.5")
        i = inst.info("v0.1.4")
        self.assertEqual((i["installed"], i["latest"], i["updateAvailable"]), (True, "v0.1.5", True))
        self.assertFalse(inst.info("v0.1.5")["updateAvailable"])
        self.assertFalse(inst.info(None)["updateAvailable"])

    def test_validation(self):
        self.assertEqual(bc.validate_install({}), {"action": "auto"})
        self.assertEqual(bc.validate_install({"action": "update"}), {"action": "update"})
        for bad in ({"action": "rm -rf"}, {"action": "install", "url": "http://x"}, {"url": "x"}, [], "install"):
            with self.assertRaises(BadRequest, msg=str(bad)):
                bc.validate_install(bad)

    def test_route_and_dry_run(self):
        stub = StubBeacon()
        self.addCleanup(stub.close)
        b = Beacon(ports=(stub.port,), cache_s=0, dry_run=True, installer=self.make("http://127.0.0.1:1"))
        self.assertEqual(b.start_install({"action": "update"}), {"ok": True, "dryRun": True})
        self.assertEqual(self.calls, [])
        st = b.status_with_install()
        self.assertTrue(st["install"]["installed"])
        self.assertIn("job", st["install"])


if __name__ == "__main__":
    unittest.main()
