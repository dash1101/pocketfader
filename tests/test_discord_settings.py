"""Discord status settings: defaults, validation, the file (missing, partial, broken), atomic saves, concurrent
changes, and finding YTM Beacon's settings. A port of YTM Beacon's config tests; files live in temp folders."""
import json
import os
import stat
import tempfile
import threading
import unittest

from starcast.discord import settings as st
from starcast.discord.settings import SettingsError

from .test_discord_fakes import GUARD


def setUpModule():
    GUARD.install()


def tearDownModule():
    GUARD.uninstall()
    GUARD.check()


class SettingsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def path(self, *parts):
        return os.path.join(self.tmp.name, *parts)

    def write(self, path, text):
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def test_missing_file_gives_defaults(self):
        s, err = st.load(self.path("nope", "config.json"))
        self.assertIsNone(err)
        self.assertEqual(s.get(), st.default_config())

    def test_defaults(self):
        self.assertEqual(st.default_config(), {
            "enabled": True, "clientId": "", "showButtons": True, "showPaused": True, "pauseTimeoutMinutes": 5,
            "showAlbumArt": True, "statusDisplay": "name", "showProgress": True, "showStateIcon": True,
            "privateMode": False, "hideMatching": []})
        self.assertEqual(list(st.default_config()), list(st.KEYS))
        self.assertEqual(st.DEFAULT_CLIENT_ID, "1567329407901634670")

    def test_save_and_reload(self):
        path = self.path("sub", "config.json")
        s, _ = st.load(path)
        c = s.get()
        c["clientId"] = " 123456789012345678 "
        c["showButtons"] = False
        c["pauseTimeoutMinutes"] = -3       # clamped to 0 (no limit)
        s.set(c)
        s2, err = st.load(path)
        self.assertIsNone(err)
        got = s2.get()
        self.assertEqual((got["clientId"], got["showButtons"], got["pauseTimeoutMinutes"]),
                         ("123456789012345678", False, 0))
        with open(path, encoding="utf-8") as f:
            text = f.read()
        self.assertTrue(text.endswith("}\n"))
        self.assertEqual(list(json.loads(text)), list(st.KEYS), "keys are written in Beacon's order")
        self.assertFalse(os.path.exists(path + ".tmp"), "the temporary file is renamed into place")
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)

    def test_partial_file_keeps_defaults(self):
        path = self.path("config.json")
        self.write(path, '{"showButtons": false}')
        s, err = st.load(path)
        self.assertIsNone(err)
        c = s.get()
        self.assertFalse(c["showButtons"])
        self.assertTrue(c["showPaused"] and c["showAlbumArt"])
        self.assertEqual(c["pauseTimeoutMinutes"], 5)
        # a file written by an older version has none of the newer fields: they come out on (or empty), not off
        self.assertTrue(c["enabled"] and c["showProgress"] and c["showStateIcon"])
        self.assertFalse(c["privateMode"])
        self.assertEqual(c["hideMatching"], [])

    def test_hide_matching_is_cleaned(self):
        c = st.default_config()
        c["hideMatching"] = ["  Taylor Swift ", "", "taylor swift", "LIVE", "   "] + ["word" + "x" * i for i in range(80)]
        got = st.normalize(c)["hideMatching"]
        self.assertEqual(len(got), st.MAX_HIDE_WORDS)
        self.assertEqual(got[:2], ["taylor swift", "live"])
        self.assertTrue(all(len(w) <= st.MAX_HIDE_WORD_LEN for w in got))
        c["hideMatching"] = None
        self.assertEqual(st.normalize(c)["hideMatching"], [], "an empty list stays a list (not null)")

    def test_get_returns_a_copy(self):
        s = st.Store(st.default_config())
        c = st.default_config()
        c["hideMatching"] = ["a", "b"]
        s.set(c)
        c["hideMatching"][0] = "changed by the caller"
        got = s.get()
        got["hideMatching"][0] = "changed"
        self.assertEqual(s.get()["hideMatching"][0], "a")

    def test_broken_file_falls_back_with_error(self):
        path = self.path("config.json")
        for content in ('{not json', '{"clientId":"abc"}', '{"statusDisplay":"sideways"}', '[]',
                        '{"showButtons":"no"}', '{"pauseTimeoutMinutes":5.5}', '{"pauseTimeoutMinutes":true}',
                        '{"hideMatching":"x"}', '{"showButtons":false} trailing', '{"pauseTimeoutMinutes":NaN}'):
            self.write(path, content)
            s, err = st.load(path)
            self.assertIsNotNone(err, content)
            self.assertIn(path, str(err), content)
            self.assertEqual(s.get(), st.default_config(), content)

    def test_go_decoding_rules(self):
        c = st.decode_onto(st.default_config(), {"ShowButtons": False, "PAUSETIMEOUTMINUTES": 7, "unknown": 1,
                                                 "showPaused": None, "hideMatching": [None, "x"]})
        self.assertFalse(c["showButtons"], "keys match case-insensitively")
        self.assertEqual(c["pauseTimeoutMinutes"], 7)
        self.assertTrue(c["showPaused"], "null leaves a setting unchanged")
        self.assertEqual(st.normalize(c)["hideMatching"], ["x"])
        c = st.decode_onto({**st.default_config(), "hideMatching": ["a"]}, {"hideMatching": None})
        self.assertEqual(c["hideMatching"], [], "null empties the word list")

    def test_effective_client_id(self):
        c = st.default_config()
        self.assertEqual(st.effective_client_id(c, "111111111111111111", environ={}), "111111111111111111")
        self.assertEqual(st.effective_client_id(c, "111111111111111111", environ={"YTM_BEACON_CLIENT_ID": ""}),
                         "111111111111111111", "an empty variable counts as unset")
        c["clientId"] = "222222222222222222"
        self.assertEqual(st.effective_client_id(c, "111111111111111111", environ={}), "222222222222222222")
        self.assertEqual(st.effective_client_id(c, "1", environ={"YTM_BEACON_CLIENT_ID": " 333333333333333333 "}),
                         "333333333333333333", "the environment wins")
        self.assertEqual(st.effective_client_id(c, "1", environ={"YTM_BEACON_CLIENT_ID": "3",
                                                                 "STARCAST_DISCORD_CLIENT_ID": "4"}), "4")
        self.assertEqual(st.effective_client_id(st.default_config(), environ={}), st.DEFAULT_CLIENT_ID)

    def test_concurrent_updates_keep_every_change(self):
        path = self.path("config.json")
        s, err = st.load(path)
        self.assertIsNone(err)
        words = ["word%02d" % i for i in range(20)]

        def add(w):
            s.update(lambda c: c["hideMatching"].append(w))
        threads = [threading.Thread(target=add, args=(w,)) for w in words]
        threads.append(threading.Thread(target=lambda: s.update(lambda c: c.__setitem__("showPaused", False))))
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        got = s.get()
        self.assertEqual(sorted(got["hideMatching"]), words)
        self.assertFalse(got["showPaused"])
        again, err = st.load(path)
        self.assertIsNone(err)
        self.assertEqual(again.get(), got, "the file matches what is in use")

    def test_update_rejects_bad_values_without_changing_anything(self):
        s = st.Store(st.default_config())

        def bad(c):
            c["showButtons"] = False
            c["clientId"] = "abc"
        with self.assertRaisesRegex(SettingsError, "Discord application ID"):
            s.update(bad)
        self.assertTrue(s.get()["showButtons"])

    def test_null_changes_nothing(self):
        # Go's json.Unmarshal of null into a struct leaves it as it is (no error)
        self.assertEqual(st.decode_onto(st.default_config(), None), st.default_config())
        path = self.path("config.json")
        self.write(path, "null")
        s, err = st.load(path)
        self.assertIsNone(err)
        self.assertEqual(s.get(), st.default_config())

    def test_store_keeps_exactly_the_settings_with_the_right_types(self):
        s = st.Store(st.default_config())
        for bad in ({"showButtons": "no"}, {"pauseTimeoutMinutes": 5.5}, {"pauseTimeoutMinutes": True},
                    {"clientId": 123}, {"hideMatching": "x"}, {"hideMatching": [1]}, {"enabled": 1}):
            with self.assertRaises(SettingsError, msg=repr(bad)):
                s.update(lambda c, bad=bad: c.update(bad))
        with self.assertRaises(SettingsError):
            s.set({**st.default_config(), "hideMatching": "abc"})       # not quietly split into letters
        self.assertEqual(s.get(), st.default_config(), "a refused change leaves everything as it was")
        got = s.set({"showButtons": True, "surprise": 1, "hideMatching": ("A", None)})
        self.assertEqual(list(got), list(st.KEYS), "unknown keys are dropped, missing ones get zero values")
        self.assertEqual((got["enabled"], got["pauseTimeoutMinutes"], got["statusDisplay"], got["hideMatching"]),
                         (False, 0, "name", ["a"]))

    def test_failed_save_changes_nothing(self):
        blocker = self.path("file")
        self.write(blocker, "")
        s = st.Store(st.default_config(), path=os.path.join(blocker, "config.json"))   # parent is a file
        with self.assertRaises(OSError):
            s.update(lambda c: c.__setitem__("privateMode", True))
        self.assertFalse(s.get()["privateMode"])

    def test_status_display_and_timeout_rules(self):
        c = st.default_config()
        c["statusDisplay"] = "  DETAILS "
        c["pauseTimeoutMinutes"] = 99999
        n = st.normalize(c)
        self.assertEqual((n["statusDisplay"], n["pauseTimeoutMinutes"]), ("details", 1440))
        c["statusDisplay"] = ""
        self.assertEqual(st.normalize(c)["statusDisplay"], "name")
        c["statusDisplay"] = "sideways"
        with self.assertRaisesRegex(SettingsError, 'statusDisplay must be "name", "state" or "details"'):
            st.normalize(c)

    def test_read_file_for_import(self):
        path = self.path("config.json")
        # YTM Beacon 0.1.0 wrote only these six keys
        self.write(path, json.dumps({"clientId": "", "showButtons": False, "showPaused": True,
                                     "pauseTimeoutMinutes": 9, "showAlbumArt": False, "statusDisplay": "state"}))
        c = st.read_file(path)
        self.assertEqual(c, {**st.default_config(), "showButtons": False, "pauseTimeoutMinutes": 9,
                             "showAlbumArt": False, "statusDisplay": "state"})
        self.write(path, '{"clientId":"abc"}')
        with self.assertRaises(SettingsError):
            st.read_file(path)
        with self.assertRaises(SettingsError):
            st.read_file(self.path("missing.json"))

    def test_beacon_config_path(self):
        self.assertEqual(st.beacon_config_path({"YTM_BEACON_HOME": "/x"}, "linux", "/home/u"),
                         os.path.join("/x", "config", "config.json"))
        self.assertEqual(st.beacon_config_path({}, "linux", "/home/u"),
                         os.path.join("/home/u", ".config", "ytm-beacon", "config.json"))
        self.assertEqual(st.beacon_config_path({"HOME": "/h"}, "linux", "/home/u"),
                         os.path.join("/h", ".config", "ytm-beacon", "config.json"))
        self.assertEqual(st.beacon_config_path({"XDG_CONFIG_HOME": "/cfg"}, "linux", "/home/u"),
                         os.path.join("/cfg", "ytm-beacon", "config.json"))
        self.assertIsNone(st.beacon_config_path({"XDG_CONFIG_HOME": "relative"}, "linux", "/home/u"))
        self.assertEqual(st.beacon_config_path({}, "darwin", "/Users/u"),
                         os.path.join("/Users/u", "Library", "Application Support", "ytm-beacon", "config.json"))
        self.assertEqual(st.beacon_config_path({"APPDATA": "C:\\Users\\u\\AppData\\Roaming"}, "win32", "C:\\Users\\u"),
                         os.path.join("C:\\Users\\u\\AppData\\Roaming", "ytm-beacon", "config.json"))
        self.assertIsNone(st.beacon_config_path({}, "win32", "C:\\Users\\u"))


if __name__ == "__main__":
    unittest.main()
