import re
import unittest
from pathlib import Path

from pocketfader import __version__


class VersionTests(unittest.TestCase):
    def test_package_and_module_versions_agree(self):
        text = (Path(__file__).resolve().parent.parent / "pyproject.toml").read_text()
        self.assertEqual(re.search(r'^version = "([^"]+)"', text, re.M).group(1), __version__)


if __name__ == "__main__":
    unittest.main()
