"""The desktop window on Windows (and anywhere else without GTK): Tk, drawn in Material You's style like the browser
extension's popup. It is dark or light as the system is (and follows a change), and carries YTM Starcast's icon."""
import sys
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import font as tkfont

from .core import PART_ROWS, Controller, prefers_dark

ICON = Path(__file__).resolve().parent.parent / "static" / "icon-192.png"

# Material 3's violet tones, as in the extension's popup and the apps (dark, then light)
DARK = dict(bg="#141218", card="#1d1b20", card2="#2b2930", line="#49454f", text="#e6e0e9", muted="#cac4d0", faint="#938f99",
            accent="#d0bcff", on_accent="#381e72", cont="#4f378b", on_cont="#eaddff", ok="#7ee0a1", off="#938f99",
            hover="#36343b")
LIGHT = dict(bg="#fef7ff", card="#f3edf7", card2="#e6e0e9", line="#cac4d0", text="#1d1b20", muted="#49454f", faint="#79747e",
             accent="#6750a4", on_accent="#ffffff", cont="#eaddff", on_cont="#21005d", ok="#1e7a3d", off="#79747e",
             hover="#ece6f0")


SCALE = 1.0       # the display's scaling (1.5 at 150 %): Tk scales fonts by itself, pixel sizes are scaled here


def px(n):
    return int(round(n * SCALE))


def blend(a, b, t):
    """Colour a mixed with t of colour b (Tk has no transparency)."""
    ca = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    cb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02x}" for x, y in zip(ca, cb))


def rrect(cv, x1, y1, x2, y2, r, **kw):
    """A rounded rectangle on a canvas."""
    r = max(0, min(r, (x2 - x1) / 2, (y2 - y1) / 2))
    pts = [x1 + r, y1, x2 - r, y1, x2, y1, x2, y1 + r, x2, y2 - r, x2, y2, x2 - r, y2, x1 + r, y2, x1, y2, x1, y2 - r,
           x1, y1 + r, x1, y1]
    return cv.create_polygon(pts, smooth=True, splinesteps=24, **kw)


class Fonts:
    def __init__(self, root):
        families = set(tkfont.families(root))
        body = next((f for f in ("Segoe UI Variable Text", "Segoe UI", "Cantarell", "Noto Sans", "DejaVu Sans") if f in families),
                    "TkDefaultFont")
        head = next((f for f in ("Segoe UI Variable Display", "Segoe UI Semibold", body) if f in families), body)
        self.body = (body, 10)
        self.small = (body, 9)
        self.strong = (body, 10, "bold") if head == body else (head, 10)
        self.title = (head, 17, "bold") if head == body else (head, 17)
        self.big = (head, 13, "bold") if head == body else (head, 13)
        self.section = (body, 9, "bold")
        self.mono = ("Cascadia Mono" if "Cascadia Mono" in families else "Consolas" if "Consolas" in families else "Courier", 9)


class Card(tk.Canvas):
    """A rounded card; put widgets in .body."""

    def __init__(self, parent, P, pad=16):
        super().__init__(parent, bg=P["bg"], highlightthickness=0, bd=0, height=px(40))
        self.P, self.pad = P, px(pad)
        self.body = tk.Frame(self, bg=P["card"])
        self.win = self.create_window(self.pad, self.pad, window=self.body, anchor="nw")
        self.body.bind("<Configure>", self._fit)
        self.bind("<Configure>", self._draw)

    def _fit(self, _e=None):
        h = self.body.winfo_reqheight() + 2 * self.pad
        if int(self.cget("height")) != h:
            self.configure(height=h)

    def _draw(self, e):
        self.delete("shape")
        rrect(self, 0, 0, e.width, e.height, px(22), fill=self.P["card"], outline="", tags="shape")
        self.tag_lower("shape")
        self.itemconfigure(self.win, width=max(1, e.width - 2 * self.pad))


class Switch(tk.Canvas):
    """Material 3's switch."""

    def __init__(self, parent, P, command, bg):
        self.W, self.H = px(52), px(32)
        super().__init__(parent, width=self.W, height=self.H, bg=bg, highlightthickness=0, bd=0, cursor="hand2",
                         takefocus=True)
        self.P, self.command, self.on, self.enabled = P, command, False, True
        self.bind("<Button-1>", self._click)
        self.bind("<space>", self._click)
        self.draw()

    def get(self):
        return self.on

    def set(self, on):
        if bool(on) != self.on:
            self.on = bool(on)
            self.draw()

    def enable(self, yes):
        if yes != self.enabled:
            self.enabled = yes
            self.configure(cursor="hand2" if yes else "")
            self.draw()

    def _click(self, _e=None):
        if self.enabled:
            self.on = not self.on
            self.draw()
            self.command()

    def draw(self):
        P = self.P
        self.delete("all")
        if self.on:
            track, edge, thumb, r, cx = P["accent"], P["accent"], P["on_accent"], px(12), self.W - px(16)
        else:
            track, edge, thumb, r, cx = P["card2"], P["off"], P["off"], px(8), px(16)
        if not self.enabled:
            track, edge, thumb = (P["line"], P["line"], P["card"]) if self.on else (P["card2"], P["line"], P["line"])
        rrect(self, 1, 1, self.W - 1, self.H - 1, self.H / 2 - 1, fill=track, outline=edge, width=max(2, px(2)))
        self.create_oval(cx - r, self.H / 2 - r, cx + r, self.H / 2 + r, fill=thumb, outline="")


class Pill(tk.Canvas):
    """A rounded button: "filled" (the accent), "tonal" (a soft container) or "text"."""

    def __init__(self, parent, P, fonts, text, command, kind="tonal", bg=None):
        self.P, self.fonts, self.kind, self.command = P, fonts, kind, command
        self.font = tkfont.Font(font=fonts.strong)
        self.hover = self.down = False
        super().__init__(parent, height=px(40), bg=bg or P["card"], highlightthickness=0, bd=0, cursor="hand2", takefocus=True)
        self.set_text(text)
        self.bind("<Enter>", lambda _e: self._state(hover=True))
        self.bind("<Leave>", lambda _e: self._state(hover=False, down=False))
        self.bind("<ButtonPress-1>", lambda _e: self._state(down=True))
        self.bind("<ButtonRelease-1>", self._release)
        self.bind("<Return>", lambda _e: self.command())
        self.bind("<Configure>", lambda _e: self.draw())

    def set_text(self, text):
        self.text = text
        self.configure(width=self.font.measure(text) + px(44))
        self.draw()

    def _state(self, **kw):
        for k, v in kw.items():
            setattr(self, k, v)
        self.draw()

    def _release(self, e):
        inside = 0 <= e.x <= self.winfo_width() and 0 <= e.y <= self.winfo_height()
        self._state(down=False)
        if inside:
            self.command()

    def draw(self):
        P = self.P
        self.delete("all")
        w, h = max(self.winfo_width(), int(self.cget("width"))), int(self.cget("height"))
        fill, fg = {"filled": (P["accent"], P["on_accent"]), "tonal": (P["cont"], P["on_cont"]),
                    "text": (None, P["accent"])}[self.kind]
        if self.hover or self.down:          # a little of the text colour over it, as Material's state layers do
            fill = blend(fill or self.cget("bg"), P["text"], .12 if self.down else .08)
        r = px(12) if self.down else h / 2    # pressed, it squares off a little, as in the apps
        if fill:
            rrect(self, 0, 0, w, h, r, fill=fill, outline="")
        self.create_text(w / 2, h / 2, text=self.text, fill=fg, font=self.font)


class Scroller(tk.Frame):
    """A page that scrolls (wheel and touchpad), with the scroll bar left out."""

    def __init__(self, parent, P):
        super().__init__(parent, bg=P["bg"])
        self.canvas = tk.Canvas(self, bg=P["bg"], highlightthickness=0, bd=0)
        self.canvas.pack(fill="both", expand=True)
        self.inner = tk.Frame(self.canvas, bg=P["bg"])
        self.win = self.canvas.create_window(0, 0, window=self.inner, anchor="nw")
        self.inner.bind("<Configure>", lambda _e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(self.win, width=e.width))
        self.bind_all("<MouseWheel>", self._wheel)
        self.bind_all("<Button-4>", lambda _e: self.canvas.yview_scroll(-3, "units"))
        self.bind_all("<Button-5>", lambda _e: self.canvas.yview_scroll(3, "units"))

    def _wheel(self, e):
        if self.inner.winfo_reqheight() > self.canvas.winfo_height():
            self.canvas.yview_scroll(int(-e.delta / 120 * 3) or (-1 if e.delta > 0 else 1), "units")


class Window:
    def __init__(self, root, ctl, rebuild=None):
        self.root, self.ctl, self.rebuild = root, ctl, rebuild
        self.dark = prefers_dark()
        self.P = P = DARK if self.dark else LIGHT
        self.f = Fonts(root)
        self.busy = False
        self.loading = True
        root.configure(bg=P["bg"])
        dark_title_bar(root, self.dark)

        page = Scroller(root, P)
        page.pack(fill="both", expand=True)
        self.page = page
        col = tk.Frame(page.inner, bg=P["bg"], padx=px(20), pady=px(18))
        col.pack(fill="both", expand=True)

        # --- the header: the icon, the name, the version
        head = tk.Frame(col, bg=P["bg"])
        head.pack(fill="x", pady=(0, px(16)))
        self.logo = None
        if ICON.exists():
            try:
                img = tk.PhotoImage(file=str(ICON))
                self.logo = img.subsample(max(1, round(img.width() / px(46))))
                tk.Label(head, image=self.logo, bg=P["bg"]).pack(side="left", padx=(0, px(12)))
            except tk.TclError:
                pass
        names = tk.Frame(head, bg=P["bg"])
        names.pack(side="left", fill="x", expand=True)
        tk.Label(names, text="YTM Starcast", font=self.f.title, fg=P["text"], bg=P["bg"]).pack(anchor="w")
        tk.Label(names, text="YouTube Music companion", font=self.f.small, fg=P["muted"], bg=P["bg"]).pack(anchor="w")
        self.version = tk.Label(head, text="", font=self.f.section, fg=P["on_cont"], bg=P["cont"], padx=px(10), pady=px(3))

        # --- running or not, and how it starts
        card = self.card(col)
        top = tk.Frame(card, bg=P["card"])
        top.pack(fill="x")
        self.dot = tk.Canvas(top, width=px(14), height=px(14), bg=P["card"], highlightthickness=0)
        self.dot.pack(side="left", padx=(0, px(12)), pady=(px(5), 0), anchor="n")
        words = tk.Frame(top, bg=P["card"])
        words.pack(side="left", fill="x", expand=True)
        self.title = tk.Label(words, text="Checking…", font=self.f.big, fg=P["text"], bg=P["card"], anchor="w")
        self.title.pack(fill="x")
        self.sub = tk.Label(words, text="", font=self.f.small, fg=P["muted"], bg=P["card"], anchor="w", justify="left")
        self.sub.pack(fill="x")
        self.run_sw = Switch(top, P, self.on_run, P["card"])
        self.run_sw.pack(side="right")
        self.divider(card)
        self.login_sw = self.switch_row(card, "Start when I log in", "YTM Starcast runs quietly in the background after you sign in.",
                                        self.on_login)
        self.lan_sw = self.switch_row(card, "Use on my home Wi-Fi", "Lets phones on your network connect. Turns on an access token.",
                                      self.on_lan)
        row, _ = self.row(card, "Cloudflare hostname", "Only if you put the server behind a Cloudflare Tunnel.")
        self.host_var = tk.StringVar()
        box = tk.Frame(row, bg=P["card2"], padx=px(10), pady=px(7))
        box.pack(side="right")
        entry = tk.Entry(box, textvariable=self.host_var, width=22, bg=P["card2"], fg=P["text"], insertbackground=P["text"],
                         relief="flat", bd=0, font=self.f.body, highlightthickness=0)
        entry.pack()
        entry.bind("<Return>", lambda _e: self.on_host())
        entry.bind("<FocusOut>", lambda _e: self.on_host())
        self.entry = entry

        # --- updates
        card = self.card(col)
        row = tk.Frame(card, bg=P["card"])
        row.pack(fill="x")
        texts = tk.Frame(row, bg=P["card"])
        texts.pack(side="left", fill="x", expand=True)
        tk.Label(texts, text="Updates", font=self.f.strong, fg=P["text"], bg=P["card"], anchor="w").pack(fill="x")
        self.upd_text = tk.Label(texts, text="Checking for updates…", font=self.f.small, fg=P["muted"], bg=P["card"],
                                 anchor="w", justify="left")
        self.upd_text.pack(fill="x")
        self.upd_btn = Pill(row, P, self.f, "Check", self.on_update, "tonal")
        self.upd_btn.pack(side="right")
        self.upd_url = None

        # --- parts (kept by the server, so they only change while it runs)
        card = self.card(col, "Parts")
        self.part_sw = {}
        for key, title, sub in PART_ROWS:
            self.part_sw[key] = self.switch_row(card, title, sub, lambda k=key: self.on_part(k))
            self.part_sw[key].enable(False)
        self.parts_note = tk.Label(card, text="", font=self.f.small, fg=P["muted"], bg=P["card"], anchor="w", justify="left")
        self.parts_note.pack(fill="x")

        # --- the addresses for a phone
        card = self.card(col, "Open on your phone")
        self.addr_box = tk.Frame(card, bg=P["card"])
        self.addr_box.pack(fill="x")
        self.addr_sig = None

        # --- status
        card = self.card(col, "Status")
        self.info = {}
        for key, label in (("device", "Sound output"), ("ytm", "YouTube Music extension"), ("now", "Now playing"),
                           ("version", "Version")):
            r = tk.Frame(card, bg=P["card"])
            r.pack(fill="x", pady=3)
            tk.Label(r, text=label, font=self.f.body, fg=P["text"], bg=P["card"]).pack(side="left")
            v = tk.Label(r, text="—", font=self.f.body, fg=P["muted"], bg=P["card"], anchor="e")
            v.pack(side="right")
            self.info[key] = v

        # --- what else there is
        btns = tk.Frame(col, bg=P["bg"])
        btns.pack(fill="x", pady=(6, 0))
        Pill(btns, P, self.f, "Open the web app", self.on_open, "filled", bg=P["bg"]).pack(side="left")
        Pill(btns, P, self.f, "Browser extension", self.on_extension, "tonal", bg=P["bg"]).pack(side="left", padx=(px(8), 0))
        Pill(btns, P, self.f, "Show log", self.on_log, "text", bg=P["bg"]).pack(side="left", padx=(px(8), 0))
        self.msg = tk.Label(col, text="", font=self.f.small, fg=P["muted"], bg=P["bg"], anchor="w")
        self.msg.pack(fill="x", pady=(10, 0))

        self.loading = False
        self.refresh()
        self.check_updates()
        self.after_id = root.after(3000, self.tick)

    # ------------------------------------------------------------------ building blocks
    def card(self, parent, heading=None):
        if heading:
            tk.Label(parent, text=heading.upper(), font=self.f.section, fg=self.P["accent"], bg=self.P["bg"],
                     anchor="w").pack(fill="x", padx=px(6), pady=(px(6), px(6)))
        c = Card(parent, self.P)
        c.pack(fill="x", pady=(0, px(14)))
        return c.body

    def divider(self, parent):
        tk.Frame(parent, bg=self.P["card2"], height=max(1, px(1))).pack(fill="x", pady=px(12))

    def row(self, parent, title, sub):
        P = self.P
        row = tk.Frame(parent, bg=P["card"])
        row.pack(fill="x", pady=px(6))
        texts = tk.Frame(row, bg=P["card"])
        texts.pack(side="left", fill="x", expand=True, padx=(0, px(12)))
        tk.Label(texts, text=title, font=self.f.body, fg=P["text"], bg=P["card"], anchor="w").pack(fill="x")
        s = tk.Label(texts, text=sub, font=self.f.small, fg=P["muted"], bg=P["card"], anchor="w", justify="left", wraplength=300)
        s.pack(fill="x")
        texts.bind("<Configure>", lambda e: s.configure(wraplength=max(120, e.width - 4)))
        return row, s

    def switch_row(self, parent, title, sub, command):
        row, _ = self.row(parent, title, sub)
        sw = Switch(row, self.P, command, self.P["card"])
        sw.pack(side="right")
        return sw

    # ------------------------------------------------------------------ live
    def tick(self):
        if self.rebuild and prefers_dark() != self.dark:
            self.rebuild()            # the system went dark or light: draw the window again in its colours
            return
        if not self.busy:
            self.refresh()
        self.after_id = self.root.after(3000, self.tick)

    def refresh(self):
        def work():
            snap = {"details": self.ctl.details(), "settings": self.ctl.settings(),
                    "autostart": self.ctl.autostart_enabled(), "addresses": self.ctl.addresses()}
            try:
                self.root.after(0, lambda: self.apply(snap))
            except RuntimeError:
                pass
        threading.Thread(target=work, daemon=True).start()

    def apply(self, snap):
        if not self.title.winfo_exists():
            return
        P = self.P
        self.loading = True
        d, s = snap["details"], snap["settings"]
        running = d is not None
        self.title.config(text="YTM Starcast is running" if running else "YTM Starcast is stopped")
        self.sub.config(text="Your phone can control this computer." if running else "Turn it on to use your phone as a remote.")
        self.dot.delete("all")
        self.dot.create_oval(px(2), px(2), px(12), px(12), fill=P["ok"] if running else P["off"], outline="")
        self.run_sw.set(running)
        self.login_sw.set(snap["autostart"])
        self.lan_sw.set(s["lan"])
        parts = (d or {}).get("parts")
        for key, sw in self.part_sw.items():
            sw.enable(parts is not None and not self.busy)
            if parts is not None:
                sw.set(parts[key])
        self.parts_note.config(text="" if parts is not None else
                               "Turn YTM Starcast on to change these." if not running else
                               "The running server is an older version without these switches.")
        if self.root.focus_get() is not self.entry:
            self.host_var.set(s["allow_host"])
        sig = tuple(snap["addresses"])
        if sig != self.addr_sig:          # rebuilt only when they change (the window asks every few seconds)
            self.addr_sig = sig
            for w in self.addr_box.winfo_children():
                w.destroy()
            for label, url in snap["addresses"]:
                row = tk.Frame(self.addr_box, bg=P["card"])
                row.pack(fill="x", pady=5)
                texts = tk.Frame(row, bg=P["card"])
                texts.pack(side="left", fill="x", expand=True)
                tk.Label(texts, text=label, font=self.f.body, fg=P["text"], bg=P["card"], anchor="w").pack(fill="x")
                tk.Label(texts, text=url, font=self.f.small, fg=P["muted"], bg=P["card"], anchor="w").pack(fill="x")
                Pill(row, P, self.f, "Copy", lambda u=url: self.copy(u), "text").pack(side="right")
        d = d or {}
        ver = d.get("version") or ""
        if ver:
            self.version.config(text=ver)
            self.version.pack(side="right")
        else:
            self.version.pack_forget()
        self.info["device"].config(text=d.get("device") or "—")
        self.info["ytm"].config(text="Connected" if d.get("ytm") else ("Not connected" if d else "—"))
        self.info["now"].config(text=(d.get("now") or "—")[:40])
        self.info["version"].config(text=ver or "—")
        self.loading = False

    def _do(self, fn, text):
        def work():
            ok = fn()
            self.root.after(0, lambda: self._done(ok))
        self.busy = True
        self.msg.config(text=text)
        threading.Thread(target=work, daemon=True).start()

    def _done(self, ok):
        self.busy = False
        self.msg.config(text="" if ok else "That didn't work. Use “Show log” to see why.")
        self.refresh()

    def on_run(self):
        if not self.loading:
            on = self.run_sw.get()
            self._do(self.ctl.start if on else self.ctl.stop, "Starting…" if on else "Stopping…")

    def on_part(self, key):
        if not self.loading and not self.busy:
            on = self.part_sw[key].get()
            self._do(lambda: self.ctl.set_part(key, on) is not None, "Saving…")

    def on_login(self):
        if not self.loading:
            on = self.login_sw.get()
            self._do(lambda: self.ctl.set_autostart(on), "Saving…")

    def on_lan(self):
        if not self.loading:
            self.ctl.update(lan=bool(self.lan_sw.get()))
            self._do(self._restart_if_running, "Applying…")

    def on_host(self):
        if self.loading:
            return
        text = self.host_var.get().strip()
        if text != self.ctl.settings()["allow_host"]:
            self.ctl.update(allow_host=text)
            self._do(self._restart_if_running, "Applying…")

    def _restart_if_running(self):
        if self.ctl.is_running():
            return self.ctl.restart()
        if self.ctl.autostart_enabled():
            return self.ctl.set_autostart(True)
        return True

    def check_updates(self):
        def work():
            info = self.ctl.update_info(refresh=True)
            try:
                self.root.after(0, lambda: self.apply_update_info(info))
            except RuntimeError:
                pass
        threading.Thread(target=work, daemon=True).start()

    def apply_update_info(self, info):
        if not self.upd_text.winfo_exists():
            return
        self.upd_url = info.get("url")
        if info.get("error"):
            self.upd_text.config(text=info["error"].capitalize() + ".")
            self.upd_btn.set_text("Check")
        elif info.get("available"):
            can = info.get("self_update")
            self.upd_text.config(text=f"{info['latest']} is available (you have {info['current']}).")
            self.upd_btn.set_text("Update now" if can else "Open page")
        else:
            self.upd_text.config(text=f"You're up to date ({info['current']}).")
            self.upd_btn.set_text("Check")

    def on_update(self):
        label = self.upd_btn.text
        if label == "Open page" and self.upd_url:
            webbrowser.open(self.upd_url)
        elif label == "Update now":
            self._do(lambda: self.ctl.update_now()[0], "Updating…")
            self.root.after(2500, self.check_updates)
        else:
            self.upd_text.config(text="Checking…")
            self.check_updates()

    def copy(self, url):
        self.root.clipboard_clear()
        self.root.clipboard_append(url)
        self.msg.config(text="Copied.")

    def on_open(self):
        webbrowser.open(f"http://127.0.0.1:{self.ctl.settings()['port']}/")

    def on_extension(self):
        """The web app's step-by-step setup of the browser extension."""
        webbrowser.open(f"http://127.0.0.1:{self.ctl.settings()['port']}/#extension")

    def on_log(self):
        P = self.P
        win = tk.Toplevel(self.root, bg=P["bg"])
        win.title("YTM Starcast log")
        win.geometry("760x460")
        dark_title_bar(win, self.dark)
        text = tk.Text(win, wrap="word", font=self.f.mono, bg=P["card"], fg=P["text"], insertbackground=P["text"],
                       relief="flat", bd=0, padx=14, pady=12, highlightthickness=0)
        text.insert("1.0", self.ctl.log_text())
        text.config(state="disabled")
        text.pack(fill="both", expand=True, padx=12, pady=12)
        text.see("end")


def dark_title_bar(win, dark):
    """Windows 10 and 11 draw the title bar dark too when asked."""
    if sys.platform != "win32":
        return
    try:
        import ctypes
        win.update_idletasks()
        hwnd = ctypes.windll.user32.GetParent(win.winfo_id())
        value = ctypes.c_int(1 if dark else 0)
        for attr in (20, 19):             # DWMWA_USE_IMMERSIVE_DARK_MODE (and its number before Windows 10 20H1)
            if ctypes.windll.dwmapi.DwmSetWindowAttribute(hwnd, attr, ctypes.byref(value), ctypes.sizeof(value)) == 0:
                break
    except (AttributeError, OSError):
        pass


def run():
    if sys.platform == "win32":
        try:
            import ctypes
            # its own taskbar entry and icon (not Python's), and sharp text at any display scaling
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("NovaLabs.YTMStarcast")
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (AttributeError, OSError):
            pass
    global SCALE
    root = tk.Tk(className="YTMStarcast")
    SCALE = max(1.0, root.winfo_fpixels("1i") / 96.0)
    root.title("YTM Starcast")
    root.geometry(f"{px(500)}x{px(820)}")
    root.minsize(px(440), px(560))
    if ICON.exists():
        try:
            root.iconphoto(True, tk.PhotoImage(file=str(ICON)))
        except tk.TclError:
            pass
    ctl = Controller()
    holder = {}

    def build():
        old = holder.get("win")
        if old is not None:
            try:
                root.after_cancel(old.after_id)
            except (tk.TclError, AttributeError):
                pass
        for w in root.winfo_children():
            w.destroy()
        holder["win"] = Window(root, ctl, rebuild=build)

    build()
    root.mainloop()
