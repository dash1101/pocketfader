"""PyInstaller entry point (the packaged binaries run this).

On Windows the server is built without a console window, so nothing pops up when it starts at logon. With no console,
it has nowhere to write: a one-off command typed in a terminal (--version, --help ...) writes to that terminal, and the
server itself writes to server.log in its settings folder (the desktop window shows that log)."""
import os
import sys
import time

from starcast.cli import main
from starcast.config import config_dir, take_over_beacon_main

LOG_LIMIT = 2 << 20                 # a log bigger than this starts over when the server starts (the last one is kept as .1)
ONE_OFF = {"--version", "-h", "--help", "--check-update", "--update", "--take-over-ytm-beacon"}


def _parent_console():
    """The terminal this was started from, if there is one (opened for writing), else None."""
    if sys.platform != "win32":
        return None
    try:
        import ctypes
        if not ctypes.windll.kernel32.AttachConsole(-1):       # ATTACH_PARENT_PROCESS
            return None
        con = open("CONOUT$", "w", encoding="utf-8", errors="replace", buffering=1)
    except (OSError, AttributeError):
        return None
    con.write("\n")                 # the terminal's prompt is already showing: start on a line of its own
    return con


def _log_file():
    path = config_dir() / "server.log"
    try:
        if path.stat().st_size > LOG_LIMIT:
            os.replace(path, path.with_name("server.log.1"))
    except OSError:
        pass
    log = open(path, "a", encoding="utf-8", errors="replace", buffering=1)
    log.write(time.strftime("\n--- %Y-%m-%d %H:%M:%S ---\n"))
    return log


def _somewhere_to_write():
    if sys.stdout is not None and sys.stderr is not None:
        return                      # a terminal, a redirect, or the desktop window's log
    con = _parent_console()
    if con and ONE_OFF & set(sys.argv[1:]):
        sys.stdout, sys.stderr = sys.stdout or con, sys.stderr or con
        return
    try:
        log = _log_file()
    except OSError:
        log = None                  # it still runs; it just can't say anything
    if con:
        # the server, started from a terminal: it keeps running after the terminal gets its prompt back
        con.write(f"YTM Starcast runs in the background. Its messages go to {log.name if log else 'nowhere'}.\n")
        con.close()
        try:
            import ctypes
            ctypes.windll.kernel32.FreeConsole()
        except (OSError, AttributeError):
            pass
    if log:
        sys.stdout, sys.stderr = sys.stdout or log, sys.stderr or log


if __name__ == "__main__":
    _somewhere_to_write()
    if sys.argv[1:] == ["--take-over-ytm-beacon"]:
        # install-exe.ps1, just before it removes the YTM Beacon app: copy its settings to YTM Starcast
        sys.exit(take_over_beacon_main())
    main()
