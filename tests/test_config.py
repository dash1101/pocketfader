"""The settings folder: ~/.config/ytm-starcast, taking over Pocketfader's old folder once. Temp homes only."""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from starcast import audio, config
from starcast.config import adopt_legacy_dir, settings_dir


class Home:
    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name) / "config"
        self.base.mkdir()
        self.new, self.old = self.base / "ytm-starcast", self.base / "pocketfader"

    def old_install(self):
        self.old.mkdir()
        (self.old / "config.json").write_text(json.dumps({"token": "t" * 24}))
        (self.old / "updates").mkdir()
        (self.old / "updates" / "pocketfader-0.1.0a15-py3-none-any.whl").write_bytes(b"x")

    def close(self):
        self.tmp.cleanup()


class MoveTests(unittest.TestCase):
    def setUp(self):
        self.h = Home()
        self.addCleanup(self.h.close)

    def test_old_folder_is_moved_and_linked(self):
        self.h.old_install()
        d = settings_dir(self.h.base, platform="linux", allow=True)
        self.assertEqual(d, self.h.new)
        self.assertEqual(json.loads((d / "config.json").read_text())["token"], "t" * 24)
        self.assertTrue((d / "updates" / "pocketfader-0.1.0a15-py3-none-any.whl").exists())
        self.assertTrue(self.h.old.is_symlink())
        self.assertEqual(os.readlink(self.h.old), "ytm-starcast")          # relative link
        self.assertEqual(self.h.old.resolve(), self.h.new.resolve())
        self.assertTrue((self.h.old / "config.json").exists())       # older tools still find their files
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)   # second start: no-op

    def test_windows_moves_without_a_link(self):
        self.h.old_install()
        d = settings_dir(self.h.base, platform="win32", allow=True)
        self.assertEqual(d, self.h.new)
        self.assertFalse(self.h.old.exists() or self.h.old.is_symlink())

    def test_fresh_install_uses_the_new_name(self):
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)
        self.assertFalse(self.h.old.exists() or self.h.old.is_symlink())

    def test_new_folder_with_files_wins_and_the_old_one_is_left_alone(self):
        self.h.old_install()
        self.h.new.mkdir()
        (self.h.new / "prefs.json").write_text("{}")
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)
        self.assertFalse(self.h.old.is_symlink())
        self.assertTrue((self.h.old / "config.json").exists())

    def test_an_empty_new_folder_does_not_hide_the_old_settings(self):
        self.h.old_install()
        self.h.new.mkdir()
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)
        self.assertTrue((self.h.new / "config.json").exists())
        self.assertTrue(self.h.old.is_symlink())

    def test_a_failed_move_keeps_using_the_old_folder(self):
        self.h.old_install()
        with mock.patch.object(Path, "rename", side_effect=PermissionError("busy")):
            d = settings_dir(self.h.base, platform="linux", allow=True)
        self.assertEqual(d, self.h.old)
        self.assertFalse(self.h.new.exists())
        self.assertTrue((self.h.old / "config.json").exists())

    def test_someone_else_moved_it_first(self):
        self.h.old_install()

        def race(src, dst):
            os.rename(src, self.h.new)        # the other process wins...
            raise FileNotFoundError("gone")   # ...so ours finds nothing to move
        with mock.patch.object(Path, "rename", race):
            self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)

    def test_a_failed_link_still_uses_the_moved_folder(self):
        self.h.old_install()
        with mock.patch.object(Path, "symlink_to", side_effect=OSError("no links here")):
            self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)
        self.assertTrue((self.h.new / "config.json").exists())

    def test_an_old_name_that_is_a_link_is_used_where_it_points(self):
        elsewhere = Path(self.h.tmp.name) / "dotfiles"
        elsewhere.mkdir()
        (elsewhere / "config.json").write_text("{}")
        self.h.old.symlink_to(elsewhere)
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.old)
        self.assertTrue(elsewhere.is_dir() and not self.h.new.exists())

    def test_a_dangling_old_link_is_ignored(self):
        self.h.old.symlink_to(Path(self.h.tmp.name) / "missing")
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=True), self.h.new)

    def test_not_allowed_keeps_the_old_folder_untouched(self):
        self.h.old_install()
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=False), self.h.old)
        self.assertFalse(self.h.new.exists())
        self.h.new.mkdir()                     # an empty new folder is not removed either
        self.assertEqual(settings_dir(self.h.base, platform="linux", allow=False), self.h.old)
        self.assertTrue(self.h.new.is_dir())

    def test_trial_and_test_runs_never_move(self):
        self.assertIn("unittest", sys.modules)
        self.assertFalse(config._moving_allowed())
        with mock.patch.dict(sys.modules), mock.patch.object(sys, "argv", ["ytm-starcast"]):
            sys.modules.pop("unittest", None)
            with mock.patch.dict(os.environ, {"STARCAST_NO_SETTINGS_MOVE": ""}):
                self.assertTrue(config._moving_allowed())
            with mock.patch.dict(os.environ, {"STARCAST_NO_SETTINGS_MOVE": "1"}):
                self.assertFalse(config._moving_allowed())
            with mock.patch.object(sys, "argv", ["ytm-starcast", "--port", "8796", "--dry-run"]):
                self.assertFalse(config._moving_allowed())


class ConfigDirTests(unittest.TestCase):
    """config_dir() itself, pointed at a temp folder through XDG_CONFIG_HOME / APPDATA."""

    def setUp(self):
        self.h = Home()
        self.addCleanup(self.h.close)

    def test_linux_moves_on_first_use(self):
        self.h.old_install()
        with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.h.base)}), \
                mock.patch.object(sys, "platform", "linux"), mock.patch.object(config, "_moving_allowed", lambda: True):
            d = config.config_dir()
        self.assertEqual(d, self.h.new)
        self.assertTrue(self.h.old.is_symlink() and (d / "config.json").exists())

    def test_windows_uses_appdata(self):
        self.h.old_install()
        with mock.patch.dict(os.environ, {"APPDATA": str(self.h.base)}), \
                mock.patch.object(sys, "platform", "win32"), mock.patch.object(config, "_moving_allowed", lambda: True):
            d = config.config_dir()
        self.assertEqual(d, self.h.new)
        self.assertFalse(self.h.old.exists())

    def test_fresh_folder_is_created(self):
        with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.h.base)}), mock.patch.object(sys, "platform", "linux"):
            d = config.config_dir()
        self.assertEqual(d, self.h.new)
        self.assertTrue(d.is_dir())

    def test_under_test_the_old_folder_is_used_in_place(self):
        self.h.old_install()
        with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.h.base)}), mock.patch.object(sys, "platform", "linux"):
            self.assertEqual(config.config_dir(), self.h.old)
        self.assertFalse(self.h.new.exists())


class TakeOverBeaconTests(unittest.TestCase):
    """What the installers run just before they remove the YTM Beacon app. Temp folders only; nothing reaches Discord."""

    def setUp(self):
        self.h = Home()
        self.addCleanup(self.h.close)
        self.beacon = self.h.base / "ytm-beacon" / "config.json"
        self.beacon.parent.mkdir()
        env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": str(self.h.base), "YTM_BEACON_HOME": ""})
        env.start()
        self.addCleanup(env.stop)
        plat = mock.patch.object(sys, "platform", "linux")
        plat.start()
        self.addCleanup(plat.stop)

    def discord(self, folder):
        return json.loads((folder / "discord.json").read_text())

    def test_replaces_settings_copied_earlier(self):
        # first start under --keep-ytm-beacon copied ["secret band"]; a word was added in Beacon afterwards
        self.h.new.mkdir()
        (self.h.new / "discord.json").write_text(json.dumps({"hideMatching": ["secret band"]}))
        self.beacon.write_text(json.dumps({"privateMode": True, "hideMatching": ["secret band", "therapy podcast"]}))
        self.assertTrue(config.take_over_beacon_settings())
        got = self.discord(self.h.new)
        self.assertEqual(got["hideMatching"], ["secret band", "therapy podcast"])
        self.assertIs(got["privateMode"], True)
        self.assertIn("enabled", got)                         # missing keys get the defaults
        if sys.platform != "win32":
            self.assertEqual(os.stat(self.h.new / "discord.json").st_mode & 0o777, 0o600)

    def test_old_settings_folder_is_used_where_it_is(self):
        self.h.old_install()
        self.beacon.write_text(json.dumps({"privateMode": True}))
        self.assertTrue(config.take_over_beacon_settings())
        self.assertIs(self.discord(self.h.old)["privateMode"], True)
        self.assertFalse(self.h.new.exists())                 # moving the folder stays the server's job
        self.assertFalse(self.h.old.is_symlink())

    def test_fresh_settings_folder(self):
        self.beacon.write_text("{}")
        self.assertTrue(config.take_over_beacon_settings())
        self.assertTrue((self.h.new / "discord.json").is_file())

    def test_no_beacon_settings_file(self):
        self.assertFalse(config.take_over_beacon_settings())
        self.assertFalse(self.h.new.exists())

    def test_bad_beacon_settings_change_nothing(self):
        self.h.new.mkdir()
        (self.h.new / "discord.json").write_text('{"hideMatching": ["secret band"]}')
        for bad in ("{not json", '{"hideMatching": 5}'):
            self.beacon.write_text(bad)
            with self.assertRaises(ValueError):
                config.take_over_beacon_settings()
            self.assertEqual(self.discord(self.h.new), {"hideMatching": ["secret band"]})

    def test_main_exit_codes(self):
        with mock.patch("sys.stdout"), mock.patch("sys.stderr"):
            self.assertEqual(config.take_over_beacon_main(), 0)             # nothing to copy
            self.beacon.write_text("{}")
            self.assertEqual(config.take_over_beacon_main(), 0)
            self.beacon.write_text("{not json")
            self.assertEqual(config.take_over_beacon_main(), 1)

    def test_packaged_program_runs_it(self):
        entry = (Path(__file__).resolve().parent.parent / "packaging" / "entry.py").read_text()
        self.assertIn('sys.argv[1:] == ["--take-over-ytm-beacon"]', entry)
        self.assertIn("take_over_beacon_main()", entry)
        for ps1 in ("install-exe.ps1", "install.ps1"):
            text = (Path(__file__).resolve().parent.parent / "packaging" / ps1).read_text()
            self.assertLess(text.index("take_over_beacon" if ps1 == "install.ps1" else "--take-over-ytm-beacon"),
                            text.index('-ArgumentList "uninstall"'), ps1)


class CacheTests(unittest.TestCase):
    def test_cached_songs_move_to_the_new_name(self):
        with tempfile.TemporaryDirectory() as t:
            old = Path(t) / "pocketfader" / "audio"
            old.mkdir(parents=True)
            (old / "dQw4w9WgXcQ.m4a").write_bytes(b"song")
            with mock.patch.dict(os.environ, {"XDG_CACHE_HOME": t}), mock.patch.object(config, "_moving_allowed", lambda: True):
                d = audio.cache_dir()
            self.assertEqual(d, Path(t) / "ytm-starcast" / "audio")
            self.assertTrue((d / "dQw4w9WgXcQ.m4a").exists())
            self.assertFalse((Path(t) / "pocketfader").exists())      # a cache needs no link back

    def test_fresh_cache_path(self):
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {"XDG_CACHE_HOME": t}):
            self.assertEqual(audio.cache_dir(), Path(t) / "ytm-starcast" / "audio")


if __name__ == "__main__":
    unittest.main()
