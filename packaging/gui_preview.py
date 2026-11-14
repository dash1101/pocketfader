"""Draws the desktop window (the Tk one, used on Windows) with made-up answers and saves a picture of it, so its look
can be checked on a machine without a screen of one's own (the release workflow runs it on Windows).

    python packaging/gui_preview.py dark|light <scaling, e.g. 1 or 1.5> <out.png>

Nothing real is asked or changed: the window talks to a stand-in, not to a running YTM Starcast."""
import sys
import tempfile
import tkinter as tk

from PIL import ImageGrab

from starcast.gui import core, tk_ui

ANSWERS = {
    "api/info": {"name": "YTM Starcast", "version": "0.2.6-alpha", "auth": False},
    "api/state": {"volume": {"percent": 62, "device": "Speakers (Realtek(R) Audio)"}, "capabilities": {"eq_toggle": True},
                  "engine": {"enabled": True}, "parts": {"remote": True, "discord": True, "refine": False, "audio": True}},
    "api/ytm/state": {"connected": True, "source": "ytm", "now": {"title": "Midnight Satellites"}},
    "api/parts": {"parts": {"remote": True, "discord": True, "refine": False, "audio": True}},
    "api/connect?qr=0": {"addresses": [{"label": "Tailscale name", "url": "http://my-pc.tail1234.ts.net:8777/"},
                                       {"label": "This computer", "url": "http://127.0.0.1:8777/"}]},
}


def stand_in():
    tmp = tempfile.mkdtemp()
    ctl = core.Controller(
        sh=lambda argv, timeout=6: (0, ""), platform="linux", home=tmp, cfg_dir=tmp, which=lambda n: None,
        http=lambda url, headers=None, timeout=1.2: ANSWERS.get(url.split("/", 3)[3] if url.count("/") >= 3 else url),
        tailscale_ip=lambda: None, lan_ip=lambda: None, post=lambda *a, **k: None,
    )
    ctl.update_info = lambda refresh=False: {"current": "0.2.6-alpha", "latest": "v0.2.6-alpha", "available": False}
    ctl.autostart_enabled = lambda: True
    return ctl


def main(mode, scaling, out):
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    tk_ui.prefers_dark = lambda: mode == "dark"
    root = tk.Tk(className="YTMStarcast")
    root.tk.call("tk", "scaling", float(scaling) * 96 / 72)
    tk_ui.SCALE = max(1.0, root.winfo_fpixels("1i") / 96.0)
    root.title("YTM Starcast")
    root.geometry(f"{tk_ui.px(500)}x{tk_ui.px(860)}+40+40")
    if tk_ui.ICON.exists():
        root.iconphoto(True, tk.PhotoImage(file=str(tk_ui.ICON)))
    tk_ui.Window(root, stand_in())

    def grab():
        root.lift()
        root.update()
        x, y = root.winfo_rootx(), root.winfo_rooty()
        ImageGrab.grab(bbox=(x, y, x + root.winfo_width(), y + root.winfo_height()), all_screens=True).save(out)
        root.destroy()

    root.after(3500, grab)
    root.mainloop()


if __name__ == "__main__":
    main(*sys.argv[1:4])
