"""Build step: the browser extension ships inside the package (starcast/extension), so an installed YTM Starcast can
put an up-to-date copy in a fixed folder for the browser to load. Everything else is in pyproject.toml."""
import shutil
from pathlib import Path

from setuptools import setup
from setuptools.command.build_py import build_py

ROOT = Path(__file__).resolve().parent


class BuildPy(build_py):
    def run(self):
        super().run()
        src = ROOT / "extension"
        if (src / "manifest.json").is_file():
            shutil.copytree(src, Path(self.build_lib) / "starcast" / "extension", dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns("README.md", "__pycache__"))


setup(cmdclass={"build_py": BuildPy})
