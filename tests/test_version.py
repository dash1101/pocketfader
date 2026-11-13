import json
import re
import unittest
from pathlib import Path

from starcast import __version__


class VersionTests(unittest.TestCase):
    def test_package_and_module_versions_agree(self):
        text = (Path(__file__).resolve().parent.parent / "pyproject.toml").read_text()
        self.assertEqual(re.search(r'^version = "([^"]+)"', text, re.M).group(1), __version__)

    def test_extension_scripts_say_the_manifest_version(self):
        ext = Path(__file__).resolve().parent.parent / "extension"
        manifest = json.loads((ext / "manifest.json").read_text())["version"]
        page = re.search(r'const EXT_VERSION = "([^"]+)"', (ext / "ytm_main.js").read_text()).group(1)
        self.assertEqual(page, manifest, "ytm_main.js reports its version to the app and to Refine's menu")


if __name__ == "__main__":
    unittest.main()
