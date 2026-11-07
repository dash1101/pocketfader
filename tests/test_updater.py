import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from pocketfader.updater import Updater, parse_version

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
        if url.endswith("per_page=15"):
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

    def test_apk_is_served_to_the_phone(self):
        st, body, r = self.req("GET", "/api/update/apk", headers=self.auth())
        self.assertEqual((st, body), (200, APK))
        self.assertEqual(r.getheader("Content-Type"), "application/vnd.android.package-archive")


if __name__ == "__main__":
    unittest.main()
