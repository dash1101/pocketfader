"""Subprocess wrapper used by every command-line backend.

Two jobs:
  * one place for timeouts / error handling, so a hung tool can never hang the server;
  * a DRY-RUN mode. In dry-run, *reads* still execute (so state is real) but *writes* are
    recorded in `.log` and never executed. This is how the test-suite and
    `pocketfader --dry-run` exercise the whole stack without ever changing the volume,
    pausing music or switching outputs.
"""
import os
import subprocess
import threading


class Result:
    __slots__ = ("ok", "out", "err")

    def __init__(self, ok, out="", err=""):
        self.ok, self.out, self.err = ok, out, err


class Runner:
    def __init__(self, dry_run=False, timeout=3.0, env=None):
        self.dry_run = dry_run
        self.timeout = timeout
        # Extra environment for child processes (used by the integration tests to aim every
        # command at an isolated sandbox instead of the real session).
        self.env = ({**os.environ, **env} if env else None)
        self.log = []  # writes recorded in dry-run mode: list of argv lists
        self._lock = threading.Lock()

    def read(self, argv, timeout=None) -> Result:
        return self._exec(argv, timeout)

    def write(self, argv, timeout=None) -> Result:
        if self.dry_run:
            with self._lock:
                self.log.append(list(argv))
            return Result(True, "", "")
        return self._exec(argv, timeout)

    def _exec(self, argv, timeout) -> Result:
        try:
            p = subprocess.run(argv, capture_output=True, text=True,
                               timeout=timeout or self.timeout, env=self.env)
            return Result(p.returncode == 0, p.stdout, p.stderr)
        except (OSError, subprocess.SubprocessError) as e:
            return Result(False, "", str(e))
