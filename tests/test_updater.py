import hashlib
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from starcast import updater as updater_mod
from starcast.updater import DEFAULT_REPO, LEGACY_REPO, Updater, parse_version

from .test_server import Base

APK = b"apk-bytes" * 10
SUMS = (hashlib.sha256(APK).hexdigest() + "  pocketfader-android-v0.1.0-alpha.4.apk\n").encode()


def release(tag, assets=(), pre=True):
    return {"tag_name": tag, "draft": False, "prerelease": pre, "html_url": "https://example/" + tag, "body": "notes",
            "assets": [{"name": n, "size": s, "id": i} for i, (n, s) in enumerate(assets, 10)]}


class Net:
    def __init__(self, releases, status=200):
        self.releases, self.status, self.calls = releases, status, []

    def __call__(self, url, headers=None, timeout=20):
        self.calls.append((url, dict(headers or {})))
        if url.endswith("per_page=50"):
            return self.status, {}, json.dumps(self.releases).encode()
        if url.endswith("/assets/10"):                 # the apk: GitHub redirects to a pre-signed CDN link
            return 302, {"location": "https://cdn.example/apk?sig=1"}, b""
        if url.startswith("https://cdn.example/apk"):
            return 200, {}, APK
        if url.endswith("/assets/11"):
            return 200, {}, SUMS
        return 404, {}, b""


class VersionTests(unittest.TestCase):
    def test_parse_and_order(self):
        self.assertEqual(parse_version("v0.1.0-alpha.4"), parse_version("0.1.0a4"))
        order = ["0.1.0a2", "v0.1.0-alpha.10", "0.1.0b1", "0.1.0rc1", "0.1.0", "0.2.0a1"]
        self.assertEqual(sorted(order, key=parse_version), order)
        self.assertIsNone(parse_version("nightly"))

    def test_names_without_a_last_number(self):
        # from 0.2.1-alpha on, versions have no number after "alpha"
        self.assertEqual(parse_version("v0.2.1-alpha"), parse_version("0.2.1a0"))
        order = ["v0.2.0-alpha.4", "v0.2.0-alpha.10", "v0.2.1-alpha", "0.2.2-alpha", "v0.2.2-beta", "v0.2.2", "v0.3.0-alpha"]
        self.assertEqual(sorted(reversed(order), key=parse_version), order)

    def test_names_for_old_android_apps(self):
        self.assertEqual(updater_mod.for_old_apps("v0.2.1-alpha"), "v0.2.1-alpha.0")
        for same in ("v0.2.0-alpha.10", "v0.2.1", "nightly", None):
            self.assertEqual(updater_mod.for_old_apps(same), same)

    def test_the_newest_is_found_whatever_order_github_lists_them_in(self):
        u = Updater(repo="o/r", current="0.2.0-alpha.10", fetch=Net([release("v0.2.0-alpha.9"), release("v0.2.1-alpha"),
                                                                       release("v0.2.0-alpha.10")]), token="", cache="/nonexistent")
        info = u.latest()
        self.assertEqual((info["latest"], info["available"]), ("v0.2.1-alpha", True))


class UpdaterTests(unittest.TestCase):
    def make(self, releases, current="0.1.0a3", status=200):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        net = Net(releases, status)
        return Updater(repo="o/r", current=current, fetch=net, token="tok", cache=Path(tmp.name)), net

    def files(self):
        return [("pocketfader-android-v0.1.0-alpha.4.apk", len(APK)), ("SHA256SUMS.txt", len(SUMS)),
                ("pocketfader-android-unshrunk-v0.1.0-alpha.4.apk", 5), ("pocketfader-0.1.0a4-py3-none-any.whl", 5)]

    def test_finds_the_newest_release_and_compares(self):
        u, _ = self.make([release("v0.1.0-alpha.3"), release("v0.1.0-alpha.4", self.files()), {"tag_name": "junk", "assets": []}])
        info = u.latest()
        self.assertEqual((info["latest"], info["available"], info["apk"], info["wheel"]), ("v0.1.0-alpha.4", True, True, True))
        u2, _ = self.make([release("v0.1.0-alpha.4", self.files())], current="0.1.0a4")
        self.assertFalse(u2.latest()["available"])

    def test_ignores_drafts(self):
        r = release("v0.1.0-alpha.9")
        r["draft"] = True
        u, _ = self.make([r, release("v0.1.0-alpha.4")])
        self.assertEqual(u.latest()["latest"], "v0.1.0-alpha.4")

    def test_errors_are_explained(self):
        u, _ = self.make([], status=404)
        self.assertIn("no access", u.latest()["error"])
        u, _ = self.make([], status=0)
        self.assertIn("couldn't reach", u.latest()["error"])

    def test_results_are_cached(self):
        u, net = self.make([release("v0.1.0-alpha.4")])
        u.latest(); u.latest()
        self.assertEqual(len(net.calls), 1)
        u.latest(refresh=True)
        self.assertEqual(len(net.calls), 2)

    def test_apk_download_follows_the_redirect_without_the_token_and_verifies(self):
        u, net = self.make([release("v0.1.0-alpha.4", self.files())])
        path = u.apk()
        self.assertEqual(path.read_bytes(), APK)
        self.assertEqual(path.name, "pocketfader-android-v0.1.0-alpha.4.apk")
        api = [h for url, h in net.calls if url.endswith("/assets/10")][0]
        cdn = [h for url, h in net.calls if url.startswith("https://cdn.example")][0]
        self.assertEqual(api["Authorization"], "Bearer tok")
        self.assertNotIn("Authorization", cdn)
        n = len(net.calls)
        self.assertEqual(u.apk(), path)                  # second call: already downloaded and verified
        self.assertEqual(len(net.calls), n)

    def test_a_bad_checksum_is_refused(self):
        u, net = self.make([release("v0.1.0-alpha.4", self.files())])
        bad = Net(net.releases)
        bad.__class__ = type("Bad", (Net,), {"__call__": lambda self, url, headers=None, timeout=20:
                                             (200, {}, b"tampered") if url.startswith("https://cdn") else Net.__call__(self, url, headers, timeout)})
        u.fetch = bad
        with self.assertRaises(RuntimeError):
            u.apk()


class UpdateApiTests(Base):
    token = "s3cret"

    def setUp(self):
        super().setUp()
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        net = Net([release("v0.1.0-alpha.4", [("pocketfader-android-v0.1.0-alpha.4.apk", len(APK)), ("SHA256SUMS.txt", len(SUMS))])])
        self.app.updater = Updater(repo="o/r", current="0.1.0a3", fetch=net, token="tok", cache=Path(tmp.name))

    def auth(self):
        return {"Authorization": "Bearer s3cret"}

    def test_info_and_apk_need_the_token(self):
        self.assertEqual(self.req("GET", "/api/update")[0], 401)
        self.assertEqual(self.req("GET", "/api/update/apk")[0], 401)

    def test_info(self):
        st, body, _ = self.req("GET", "/api/update", headers=self.auth())
        self.assertEqual((st, body["available"], body["latest"], body["apk"]), (200, True, "v0.1.0-alpha.4", True))

    def test_old_android_apps_get_a_version_name_they_can_read(self):
        self.app.updater = Updater(repo="o/r", current="0.2.1-alpha", fetch=Net([release("v0.2.1-alpha")]), token="tok",
                                   cache=self.app.updater.dir)
        got = lambda ua: self.req("GET", "/api/update", headers={**self.auth(), **({"User-Agent": ua} if ua else {})})[1]["latest"]  # noqa: E731
        self.assertEqual(got("ytm-starcast-android"), "v0.2.1-alpha.0")          # apps up to 0.2.0-alpha.10
        self.assertEqual(got("ytm-starcast-android/0.2.1-alpha"), "v0.2.1-alpha")
        self.assertEqual(got(None), "v0.2.1-alpha")

    def test_apk_is_served_to_the_phone(self):
        st, body, r = self.req("GET", "/api/update/apk", headers=self.auth())
        self.assertEqual((st, body), (200, APK))
        self.assertEqual(r.getheader("Content-Type"), "application/vnd.android.package-archive")


LISTING = "https://api.github.com/repos/{}/releases?per_page=50"


class Routes:
    """A fake GitHub: url -> (status, headers, body). Unknown addresses answer 404. Every call is recorded."""

    def __init__(self, routes):
        self.routes, self.calls = routes, []

    def __call__(self, url, headers=None, timeout=20):
        self.calls.append((url, dict(headers or {})))
        st, h, body = self.routes.get(url, (404, {}, b""))
        return st, h, (json.dumps(body).encode() if isinstance(body, (list, dict)) else body)


WHEEL = b"wheel-bytes" * 7
WHEEL_NAME = "ytm_starcast-0.2.0a2-py3-none-any.whl"
WHEEL_SUMS = (hashlib.sha256(WHEEL).hexdigest() + "  " + WHEEL_NAME + "\n").encode()


class RenameTests(unittest.TestCase):
    """YTM Starcast's releases moved from dash1101/pocketfader to dash1101/ytm-starcast."""

    def make(self, routes, repo=None, current="0.2.0a1"):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        net = Routes(routes)
        with mock.patch.dict(os.environ, {}):
            os.environ.pop("STARCAST_REPO", None)
            os.environ.pop("POCKETFADER_REPO", None)
            u = Updater(repo=repo, current=current, fetch=net, token="tok", cache=Path(tmp.name))
        return u, net, Path(tmp.name)

    def test_the_new_repo_is_asked_first(self):
        u, net, _ = self.make({LISTING.format(DEFAULT_REPO): (200, {}, [release("v0.2.0-alpha.2")])})
        info = u.latest()
        self.assertEqual(DEFAULT_REPO, "dash1101/ytm-starcast")
        self.assertEqual((info["latest"], info["available"], info["repo"]), ("v0.2.0-alpha.2", True, DEFAULT_REPO))
        self.assertEqual([c[0] for c in net.calls], [LISTING.format(DEFAULT_REPO)])
        self.assertEqual(net.calls[0][1]["User-Agent"], "ytm-starcast/0.2.0a1")

    def test_env_names_new_first_then_old(self):
        with mock.patch.dict(os.environ, {"STARCAST_REPO": "new/one", "POCKETFADER_REPO": "old/one"}):
            self.assertEqual(Updater(fetch=Routes({}), token="").repo, "new/one")
        with mock.patch.dict(os.environ, {"POCKETFADER_REPO": "old/one"}):
            os.environ.pop("STARCAST_REPO", None)
            self.assertEqual(Updater(fetch=Routes({}), token="").repo, "old/one")
        env = {"STARCAST_GITHUB_TOKEN": "new-tok", "POCKETFADER_GITHUB_TOKEN": "old-tok", "GITHUB_TOKEN": "gh-tok"}
        with mock.patch.dict(os.environ, env):
            self.assertEqual(Updater(fetch=Routes({})).token(), "new-tok")
        with mock.patch.dict(os.environ, {"POCKETFADER_GITHUB_TOKEN": "old-tok", "GITHUB_TOKEN": "gh-tok"}):
            os.environ.pop("STARCAST_GITHUB_TOKEN", None)
            self.assertEqual(Updater(fetch=Routes({})).token(), "old-tok")

    def test_a_renamed_repo_is_followed_on_the_api_host(self):
        moved = "https://api.github.com/repositories/123/releases?per_page=50"
        u, net, _ = self.make({LISTING.format("o/old"): (301, {"location": moved}, b""),
                               moved: (200, {}, [release("v0.2.0-alpha.2")])}, repo="o/old")
        self.assertEqual(u.latest()["latest"], "v0.2.0-alpha.2")
        self.assertEqual([c[0] for c in net.calls], [LISTING.format("o/old"), moved])
        self.assertEqual(net.calls[1][1]["Authorization"], "Bearer tok")      # same host: the token may go along

    def test_302_and_307_are_followed_too(self):
        for code in (302, 307):
            hop = f"https://api.github.com/repositories/9/releases?per_page=50&c={code}"
            u, _, _ = self.make({LISTING.format("o/r"): (code, {"location": hop}, b""),
                                 hop: (200, {}, [release("v0.2.0-alpha.3")])}, repo="o/r")
            self.assertEqual(u.latest()["latest"], "v0.2.0-alpha.3")

    def test_a_redirect_off_the_api_is_not_followed(self):
        for where in ("https://evil.example/releases", "http://api.github.com/repos/o/r/releases",
                      "https://api.github.com.evil.example/x", "https://user@api.github.com:444/x"):
            u, net, _ = self.make({LISTING.format("o/r"): (301, {"location": where}, b"")}, repo="o/r")
            info = u.latest()
            self.assertEqual(info["error"], "GitHub answered 301")
            self.assertEqual([c[0] for c in net.calls], [LISTING.format("o/r")], where)

    def test_redirect_loops_end(self):
        loop = "https://api.github.com/loop"
        u, net, _ = self.make({LISTING.format("o/r"): (301, {"location": loop}, b""),
                               loop: (301, {"location": loop}, b"")}, repo="o/r")
        self.assertIn("error", u.latest())
        self.assertLessEqual(len(net.calls), 4)

    def test_falls_back_to_the_old_repo_while_the_new_one_is_missing(self):
        files = [("pocketfader-android-v0.1.0-alpha.16.apk", len(APK)), ("SHA256SUMS.txt", len(SUMS))]
        sums = (hashlib.sha256(APK).hexdigest() + "  pocketfader-android-v0.1.0-alpha.16.apk\n").encode()
        old = "https://api.github.com/repos/dash1101/pocketfader/releases/assets/"
        u, net, _ = self.make({LISTING.format(LEGACY_REPO): (200, {}, [release("v0.1.0-alpha.16", files)])},
                              current="0.1.0a15")
        self.assertEqual(LEGACY_REPO, "dash1101/pocketfader")
        info = u.latest()
        self.assertEqual((info["repo"], info["latest"], info["available"]), (LEGACY_REPO, "v0.1.0-alpha.16", True))
        net.routes[old + "10"] = (200, {}, APK)
        net.routes[old + "11"] = (200, {}, sums)
        self.assertEqual(u.apk().read_bytes(), APK)                         # downloads come from the repo that answered
        self.assertTrue(all("/repos/dash1101/ytm-starcast/releases/assets" not in c[0] for c in net.calls))

    def test_an_empty_new_repo_also_falls_back(self):
        u, _, _ = self.make({LISTING.format(DEFAULT_REPO): (200, {}, []),
                             LISTING.format(LEGACY_REPO): (200, {}, [release("v0.1.0-alpha.16")])}, current="0.1.0a15")
        self.assertEqual(u.latest()["repo"], LEGACY_REPO)

    def test_no_fallback_for_a_repo_somebody_chose(self):
        u, net, _ = self.make({}, repo="someone/fork")
        self.assertIn("no access", u.latest()["error"])
        self.assertEqual(len(net.calls), 1)

    def test_both_missing_reports_the_new_one(self):
        u, net, _ = self.make({})
        info = u.latest()
        self.assertEqual((info["repo"], "no access" in info["error"]), (DEFAULT_REPO, True))
        self.assertEqual(len(net.calls), 2)

    def test_old_downloads_of_either_name_are_cleaned_up(self):
        files = [(WHEEL_NAME, len(WHEEL)), ("SHA256SUMS.txt", len(WHEEL_SUMS))]
        base = "https://api.github.com/repos/o/r/releases/assets/"
        u, _, d = self.make({LISTING.format("o/r"): (200, {}, [release("v0.2.0-alpha.2", files)]),
                             base + "10": (200, {}, WHEEL), base + "11": (200, {}, WHEEL_SUMS)}, repo="o/r")
        old = time.time() - 1000
        names = ["pocketfader-0.1.0a1-py3-none-any.whl", "pocketfader-android-v0.1.0-alpha.1.apk",
                 "ytm_starcast-0.2.0a0-py3-none-any.whl", "pocketfader-0.1.0a2-py3-none-any.whl",
                 "ytm-starcast-android-v0.2.0-alpha.0.apk"]
        for i, n in enumerate(names):
            (d / n).write_bytes(b"x")
            os.utime(d / n, (old + i, old + i))
        (d / "notes.txt").write_bytes(b"not ours")
        u.download(lambda n: n.endswith(".whl"))
        left = sorted(p.name for p in d.iterdir())
        self.assertEqual(left, sorted([WHEEL_NAME, "ytm-starcast-android-v0.2.0-alpha.0.apk", "ytm_starcast-0.2.0a0-py3-none-any.whl",
                                       "pocketfader-0.1.0a2-py3-none-any.whl", "notes.txt"]))

    def test_self_update_says_restart_starcast(self):
        files = [(WHEEL_NAME, len(WHEEL)), ("SHA256SUMS.txt", len(WHEEL_SUMS))]
        base = "https://api.github.com/repos/o/r/releases/assets/"
        u, _, _ = self.make({LISTING.format("o/r"): (200, {}, [release("v0.2.0-alpha.2", files)]),
                             base + "10": (200, {}, WHEEL), base + "11": (200, {}, WHEEL_SUMS)}, repo="o/r")
        ran = []

        def fake_run(argv, **kw):
            ran.append(argv)
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(updater_mod.subprocess, "run", fake_run):     # never a real pip
            ok, msg = u.update_self()
        self.assertTrue(ok)
        self.assertEqual(msg, "Updated to v0.2.0-alpha.2. Restart YTM Starcast to use it.")
        self.assertEqual(ran[0][1:6], ["-m", "pip", "install", "--no-deps", "--force-reinstall"])
        self.assertTrue(ran[0][-1].endswith(WHEEL_NAME))

    def test_making_an_updater_touches_nothing_on_disk(self):
        def boom():
            raise AssertionError("config_dir() called while creating an Updater")
        with mock.patch.object(updater_mod, "config_dir", boom):
            Updater(fetch=Routes({}), token="")


if __name__ == "__main__":
    unittest.main()
