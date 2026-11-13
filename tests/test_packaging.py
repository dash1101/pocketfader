"""The packaged program's entry point (packaging/entry.py): started with nowhere to write, as the Windows program is at
logon (it has no console window), its messages go to server.log in the settings folder."""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

SCRIPT = r"""
import importlib.util, sys
spec = importlib.util.spec_from_file_location("entry", sys.argv[1])
entry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entry)
sys.stdout = sys.stderr = None              # what a program without a console gets
sys.argv = ["ytm-starcast"] + sys.argv[2:]
entry._somewhere_to_write()
print("listening on http://127.0.0.1:8777/")
print("a problem", file=sys.stderr)
"""


class EntryLogTests(unittest.TestCase):
    def run_entry(self, cfg, *args):
        env = {**os.environ, "XDG_CONFIG_HOME": str(cfg), "APPDATA": str(cfg), "STARCAST_NO_SETTINGS_MOVE": "1",
               "PYTHONPATH": str(REPO)}
        p = subprocess.run([sys.executable, "-c", SCRIPT, str(REPO / "packaging" / "entry.py"), *args], env=env,
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p

    def test_messages_go_to_the_log(self):
        with tempfile.TemporaryDirectory() as d:
            cfg = Path(d)
            self.run_entry(cfg, "--port", "8777")
            log = (cfg / "ytm-starcast" / "server.log").read_text()
            self.assertIn("listening on http://127.0.0.1:8777/\n", log)
            self.assertIn("a problem\n", log)
            self.assertRegex(log, r"--- \d{4}-\d\d-\d\d \d\d:\d\d:\d\d ---")
            # each start adds to it; a big one starts over and the last one is kept
            self.run_entry(cfg)
            self.assertEqual((cfg / "ytm-starcast" / "server.log").read_text().count("listening on"), 2)
            (cfg / "ytm-starcast" / "server.log").write_text("x" * (3 << 20))
            self.run_entry(cfg)
            self.assertEqual((cfg / "ytm-starcast" / "server.log.1").stat().st_size, 3 << 20)
            self.assertEqual((cfg / "ytm-starcast" / "server.log").read_text().count("listening on"), 1)

    def test_a_program_with_somewhere_to_write_keeps_it(self):
        with tempfile.TemporaryDirectory() as d:
            env = {**os.environ, "XDG_CONFIG_HOME": d, "APPDATA": d, "PYTHONPATH": str(REPO)}
            code = ("import importlib.util, sys; spec = importlib.util.spec_from_file_location('e', sys.argv[1]); "
                    "e = importlib.util.module_from_spec(spec); spec.loader.exec_module(e); e._somewhere_to_write(); "
                    "print('here')")
            p = subprocess.run([sys.executable, "-c", code, str(REPO / "packaging" / "entry.py")], env=env,
                               capture_output=True, text=True, timeout=60)
            self.assertEqual(p.stdout, "here\n")
            self.assertFalse((Path(d) / "ytm-starcast" / "server.log").exists())


if __name__ == "__main__":
    unittest.main()
