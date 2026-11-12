"""The browser extension's setup: its copy in a fixed folder, its status, and opening the folder or a browser's
extensions page on the computer. Temp folders and fakes only: nothing is opened, nothing in the real home changes."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from starcast import browser_ext as bx
from starcast.browser_ext import BrowserExtension, install_dir, sync

from .test_parts import Base

REAL_EXT_DIR = Path.home() / ".local" / "share" / "ytm-starcast" / "extension"


def make_ext(folder, version="2.0.0", extra="x"):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "manifest.json").write_text(json.dumps({"name": "YTM Starcast", "version": version}))
    (folder / "a.js").write_text(extra)
    (folder / "icons").mkdir(exist_ok=True)
    (folder / "icons" / "i.png").write_bytes(b"\x89PNG")
    return folder


class FakeBridge:
    def __init__(self, state=None):
        self.state = state or {}

    def get_state(self):
        return dict(self.state)


class Popen:
    def __init__(self):
        self.calls = []

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)


class TmpCase(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory()
        self.addCleanup(t.cleanup)
        self.tmp = Path(t.name)


class CopyTests(TmpCase):
    def test_copies_the_extension_into_the_fixed_folder(self):
        src, dest = make_ext(self.tmp / "src"), self.tmp / "data" / "extension"
        self.assertTrue(sync(src, dest))
        self.assertEqual(json.loads((dest / "manifest.json").read_text())["version"], "2.0.0")
        self.assertTrue((dest / "icons" / "i.png").is_file())
        self.assertFalse((dest.parent / "extension.new").exists())
        self.assertFalse((dest.parent / "extension.old").exists())

    def test_nothing_happens_when_it_is_already_current(self):
        src, dest = make_ext(self.tmp / "src"), self.tmp / "extension"
        sync(src, dest)
        self.assertFalse(sync(src, dest))

    def test_any_change_is_copied_even_with_the_same_version(self):
        src, dest = make_ext(self.tmp / "src"), self.tmp / "extension"
        sync(src, dest)
        (src / "a.js").write_text("changed")
        self.assertTrue(sync(src, dest))
        self.assertEqual((dest / "a.js").read_text(), "changed")

    def test_files_that_left_the_extension_leave_the_copy(self):
        src, dest = make_ext(self.tmp / "src"), self.tmp / "extension"
        sync(src, dest)
        (src / "a.js").unlink()
        sync(src, dest)
        self.assertFalse((dest / "a.js").exists())

    def test_no_source_means_no_copy(self):
        self.assertFalse(sync(self.tmp / "missing", self.tmp / "extension"))
        self.assertFalse((self.tmp / "extension").exists())

    def test_the_repository_extension_is_found_from_a_source_checkout(self):
        d = bx.bundled_dir()
        self.assertIsNotNone(d)
        self.assertTrue((d / "manifest.json").is_file())
        self.assertEqual(bx.manifest_version(d), json.loads((d / "manifest.json").read_text())["version"])

    def test_dry_run_copies_nothing(self):
        e = BrowserExtension(FakeBridge(), folder=self.tmp / "extension", source=make_ext(self.tmp / "src"),
                             dry_run=True)
        self.assertFalse(e.refresh_copy())
        self.assertFalse((self.tmp / "extension").exists())


class FolderTests(unittest.TestCase):
    def test_folder_per_system(self):
        self.assertEqual(install_dir("Linux", env={}, home="/h"), Path("/h/.local/share/ytm-starcast/extension"))
        self.assertEqual(install_dir("Linux", env={"XDG_DATA_HOME": "/x"}, home="/h"),
                         Path("/x/ytm-starcast/extension"))
        self.assertEqual(install_dir("Windows", env={"LOCALAPPDATA": r"C:\L"}, home="/h"),
                         Path(r"C:\L") / "ytm-starcast" / "extension")
        self.assertEqual(install_dir("Darwin", env={}, home="/h"),
                         Path("/h/Library/Application Support/YTM Starcast/extension"))


class StatusTests(TmpCase):
    def ext(self, state, **kw):
        return BrowserExtension(FakeBridge(state), seen_file=self.tmp / "seen", folder=self.tmp / "extension",
                                source=make_ext(self.tmp / "src", "2.0.0"), which=lambda n: None, **kw)

    def test_not_set_up_yet(self):
        s = self.ext({"connected": False}).status()
        self.assertFalse(s["connected"])
        self.assertFalse(s["everConnected"])
        self.assertFalse(s["outdated"])
        self.assertEqual(s["expected"], "2.0.0")
        self.assertEqual(s["folder"], str(self.tmp / "extension"))

    def test_connected_with_the_current_version(self):
        s = self.ext({"connected": True, "ext": "2.0.0"}).status()
        self.assertTrue(s["connected"])
        self.assertFalse(s["outdated"])
        self.assertTrue(s["everConnected"])

    def test_an_older_extension_that_doesnt_say_its_version_should_be_reloaded(self):
        self.assertTrue(self.ext({"connected": True}).status()["outdated"])
        self.assertTrue(self.ext({"connected": True, "ext": "1.15.0"}).status()["outdated"])

    def test_once_connected_is_remembered(self):
        self.ext({"connected": True, "ext": "2.0.0"}).status()
        self.assertTrue(self.ext({"connected": False}).status()["everConnected"])


class OpenTests(TmpCase):
    def make(self, system="Linux", found=("brave-browser", "xdg-open"), env=None, exists=None, dry_run=False):
        self.popen = Popen()
        which = lambda n: f"/usr/bin/{n}" if n in found else None
        return BrowserExtension(FakeBridge(), folder=self.tmp / "extension", source=make_ext(self.tmp / "src"),
                                system=system, env=env or {}, which=which, popen=self.popen,
                                exists=exists or (lambda p: False), dry_run=dry_run)

    def test_only_installed_browsers_are_offered(self):
        e = self.make(found=("google-chrome", "brave-browser"))
        self.assertEqual([b["id"] for b in e.browsers()], ["brave", "chrome"])

    def test_opens_a_browsers_extensions_page(self):
        e = self.make()
        self.assertTrue(e.open_extensions_page("brave"))
        self.assertEqual(self.popen.calls, [["/usr/bin/brave-browser", "brave://extensions"]])

    def test_unknown_or_missing_browsers_are_refused(self):
        e = self.make()
        for bad in ("chrome", "firefox", "../../bin/sh", ""):
            with self.assertRaises(ValueError):
                e.open_extensions_page(bad)
        self.assertEqual(self.popen.calls, [])

    def test_opens_the_folder_making_the_copy_first(self):
        e = self.make()
        self.assertTrue(e.open_folder())
        self.assertTrue((self.tmp / "extension" / "manifest.json").is_file())
        self.assertEqual(self.popen.calls, [["/usr/bin/xdg-open", str(self.tmp / "extension")]])

    def test_windows_uses_explorer_and_program_files(self):
        root = r"C:\Program Files"
        exe = os.path.join(root, r"BraveSoftware\Brave-Browser\Application\brave.exe")
        e = self.make(system="Windows", env={"ProgramFiles": root}, exists=lambda p: p == exe)
        self.assertEqual([b["id"] for b in e.browsers()], ["brave"])
        with mock.patch.object(bx.subprocess, "DETACHED_PROCESS", 8, create=True), \
                mock.patch.object(bx.subprocess, "CREATE_NEW_PROCESS_GROUP", 512, create=True):
            e.open_extensions_page("brave")
            e.open_folder()
        self.assertEqual(self.popen.calls, [[exe, "brave://extensions"], ["explorer", str(self.tmp / "extension")]])

    def test_mac_uses_open(self):
        e = self.make(system="Darwin", exists=lambda p: p == "/Applications/Google Chrome.app")
        e.open_extensions_page("chrome")
        self.assertEqual(self.popen.calls, [["open", "-a", "Google Chrome", "--args", "chrome://extensions"]])

    def test_dry_run_opens_nothing(self):
        e = self.make(dry_run=True)
        self.assertTrue(e.open_extensions_page("brave"))
        self.assertEqual(self.popen.calls, [])
        self.assertEqual(e.opened, [["/usr/bin/brave-browser", "brave://extensions"]])


class RouteTests(Base):
    def make_app(self, presence=None):
        app = super().make_app(presence)
        self.popen = Popen()
        app.extension = BrowserExtension(app.bridge, seen_file=Path(self.tmp.name) / "seen",
                                         folder=Path(self.tmp.name) / "extension",
                                         which=lambda n: f"/usr/bin/{n}" if n in ("brave-browser", "xdg-open") else None,
                                         popen=self.popen, system="Linux")
        return app

    def test_status(self):
        code, s, _ = self.req("GET", "/api/extension")
        self.assertEqual(code, 200)
        self.assertFalse(s["connected"])
        self.assertEqual([b["id"] for b in s["browsers"]], ["brave"])
        self.assertTrue(s["expected"])

    def test_state_carries_a_short_summary(self):
        _, st, _ = self.req("GET", "/api/state")
        self.assertEqual(set(st["extension"]), {"connected", "everConnected", "outdated", "version", "expected"})

    def test_the_version_the_extension_reports_is_used(self):
        expected = bx.manifest_version(bx.bundled_dir())
        self.req("POST", "/ext/state", {"instance": "t1", "now": {"title": "S", "playing": True}, "ext": expected},
                 headers={"Origin": "https://music.youtube.com"})
        _, s, _ = self.req("GET", "/api/extension")
        self.assertTrue(s["connected"])
        self.assertEqual(s["version"], expected)
        self.assertFalse(s["outdated"])

    def test_open_page_and_folder(self):
        code, r, _ = self.req("POST", "/api/extension/open", {"what": "page", "browser": "brave"})
        self.assertEqual((code, r), (200, {"ok": True}))
        code, r, _ = self.req("POST", "/api/extension/open", {"what": "folder"})
        self.assertEqual(code, 200)
        self.assertEqual(self.popen.calls[0], ["/usr/bin/brave-browser", "brave://extensions"])
        self.assertEqual(self.popen.calls[1][0], "/usr/bin/xdg-open")

    def test_bad_requests_open_nothing(self):
        for body in ({}, {"what": "page"}, {"what": "page", "browser": "chrome"}, {"what": "page", "browser": 3},
                     {"what": "url", "url": "file:///etc"}, {"what": "folder ; rm"}):
            code, _, _ = self.req("POST", "/api/extension/open", body)
            self.assertEqual(code, 400, body)
        self.assertEqual(self.popen.calls, [])

    def test_a_web_page_cannot_open_things(self):
        code, _, _ = self.req("POST", "/api/extension/open", {"what": "folder"},
                              headers={"Origin": "https://evil.example"})
        self.assertNotEqual(code, 200)
        self.assertEqual(self.popen.calls, [])


class TokenRouteTests(RouteTests):
    token = "secret-token"

    def req(self, method, path, body=None, headers=None, raw=None, timeout=5):
        h = dict(headers or {})
        if path.startswith("/api/"):
            h.setdefault("Authorization", f"Bearer {self.token}")
        return super().req(method, path, body, h, raw, timeout)

    def test_without_the_token_nothing_opens(self):
        code, _, _ = Base.req(self, "POST", "/api/extension/open", {"what": "folder"})
        self.assertEqual(code, 401)
        self.assertEqual(self.popen.calls, [])


class RealHomeUntouched(unittest.TestCase):
    def test_tests_never_wrote_the_real_extension_folder(self):
        # the real folder may exist (the installed app makes it), but never from a test run: nothing in it is newer
        # than the moment this test module was imported
        if REAL_EXT_DIR.exists():
            newest = max((p.stat().st_mtime for p in REAL_EXT_DIR.rglob("*")), default=0)
            self.assertLess(newest, IMPORTED_AT)


IMPORTED_AT = __import__("time").time()

if __name__ == "__main__":
    unittest.main()
