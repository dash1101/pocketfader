"""Desktop window for the server: start/stop, start at login, phone addresses, status.

`pocketfader-gui` (or `pocketfader --gui`) opens it. The window is GTK on Linux and Tk elsewhere, so it
looks like the rest of the desktop; both are thin layers over `core.Controller`.
"""


def main(argv=None):
    import sys
    if sys.platform.startswith("linux"):
        try:
            from .gtk_ui import run
            return run()
        except ImportError:
            pass
    try:
        from .tk_ui import run
    except ImportError:
        sys.exit("No desktop toolkit found. On Linux install python3-gi (GTK); on other systems Python's tkinter.\n"
                 "You can still use Pocketfader without a window: run `pocketfader` and open the address it prints.")
    return run()
