"""The server window on Windows (and anywhere else without GTK): Tk with the system's native theme."""
import threading
import tkinter as tk
import webbrowser
from tkinter import ttk

from .core import Controller


class Window:
    def __init__(self, root, ctl):
        self.root, self.ctl = root, ctl
        self.busy = False
        self.loading = True
        root.title("Pocketfader")
        root.geometry("480x640")
        root.minsize(420, 560)
        style = ttk.Style()
        for theme in ("vista", "aqua", "winnative", "clam"):
            if theme in style.theme_names():
                style.theme_use(theme)
                break
        outer = ttk.Frame(root, padding=18)
        outer.pack(fill="both", expand=True)

        self.title = ttk.Label(outer, text="Checking…", font=("TkDefaultFont", 15, "bold"))
        self.title.pack(anchor="w")
        self.sub = ttk.Label(outer, text="", foreground="#666")
        self.sub.pack(anchor="w", pady=(0, 10))
        self.run_var = tk.BooleanVar()
        ttk.Checkbutton(outer, text="Run Pocketfader", variable=self.run_var, command=self.on_run).pack(anchor="w")
        self.login_var = tk.BooleanVar()
        ttk.Checkbutton(outer, text="Start when I log in", variable=self.login_var, command=self.on_login).pack(anchor="w", pady=(6, 0))
        self.lan_var = tk.BooleanVar()
        ttk.Checkbutton(outer, text="Use on my home Wi-Fi (turns on an access token)", variable=self.lan_var,
                        command=self.on_lan).pack(anchor="w", pady=(6, 0))
        host = ttk.Frame(outer)
        host.pack(fill="x", pady=(8, 0))
        ttk.Label(host, text="Cloudflare hostname:").pack(side="left")
        self.host_var = tk.StringVar()
        entry = ttk.Entry(host, textvariable=self.host_var)
        entry.pack(side="left", fill="x", expand=True, padx=(8, 0))
        entry.bind("<Return>", lambda _e: self.on_host())
        entry.bind("<FocusOut>", lambda _e: self.on_host())

        ttk.Label(outer, text="Open on your phone", font=("TkDefaultFont", 10, "bold")).pack(anchor="w", pady=(16, 4))
        self.addr_box = ttk.Frame(outer)
        self.addr_box.pack(fill="x")

        ttk.Label(outer, text="Status", font=("TkDefaultFont", 10, "bold")).pack(anchor="w", pady=(16, 4))
        self.info = {}
        grid = ttk.Frame(outer)
        grid.pack(fill="x")
        for i, (key, label) in enumerate((("device", "Sound output"), ("ytm", "YouTube Music extension"),
                                          ("now", "Now playing"), ("version", "Version"))):
            ttk.Label(grid, text=label).grid(row=i, column=0, sticky="w", pady=2)
            v = ttk.Label(grid, text="—", foreground="#666")
            v.grid(row=i, column=1, sticky="e", padx=(12, 0))
            self.info[key] = v
        grid.columnconfigure(1, weight=1)

        btns = ttk.Frame(outer)
        btns.pack(fill="x", pady=(18, 0))
        ttk.Button(btns, text="Open the web app", command=self.on_open).pack(side="left", expand=True, fill="x")
        ttk.Button(btns, text="Show log", command=self.on_log).pack(side="left", expand=True, fill="x", padx=(8, 0))
        self.msg = ttk.Label(outer, text="", foreground="#666")
        self.msg.pack(anchor="w", pady=(10, 0))

        self.loading = False
        self.refresh()
        root.after(3000, self.tick)

    def tick(self):
        if not self.busy:
            self.refresh()
        self.root.after(3000, self.tick)

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
        self.loading = True
        d, s = snap["details"], snap["settings"]
        running = d is not None
        self.title.config(text="Pocketfader is running" if running else "Pocketfader is stopped")
        self.sub.config(text="Your phone can control this computer." if running else "Turn it on to use your phone as a remote.")
        self.run_var.set(running)
        self.login_var.set(snap["autostart"])
        self.lan_var.set(s["lan"])
        if self.root.focus_get() is None or str(self.root.focus_get().winfo_class()) != "TEntry":
            self.host_var.set(s["allow_host"])
        for w in self.addr_box.winfo_children():
            w.destroy()
        for label, url in snap["addresses"]:
            row = ttk.Frame(self.addr_box)
            row.pack(fill="x", pady=2)
            col = ttk.Frame(row)
            col.pack(side="left", fill="x", expand=True)
            ttk.Label(col, text=label).pack(anchor="w")
            ttk.Label(col, text=url, foreground="#666").pack(anchor="w")
            ttk.Button(row, text="Copy", command=lambda u=url: self.copy(u)).pack(side="right")
        d = d or {}
        self.info["device"].config(text=d.get("device") or "—")
        self.info["ytm"].config(text="Connected" if d.get("ytm") else ("Not connected" if d else "—"))
        self.info["now"].config(text=d.get("now") or "—")
        self.info["version"].config(text=d.get("version") or "—")
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
            on = self.run_var.get()
            self._do(self.ctl.start if on else self.ctl.stop, "Starting…" if on else "Stopping…")

    def on_login(self):
        if not self.loading:
            on = self.login_var.get()
            self._do(lambda: self.ctl.set_autostart(on), "Saving…")

    def on_lan(self):
        if not self.loading:
            self.ctl.update(lan=bool(self.lan_var.get()))
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

    def copy(self, url):
        self.root.clipboard_clear()
        self.root.clipboard_append(url)
        self.msg.config(text="Copied.")

    def on_open(self):
        webbrowser.open(f"http://127.0.0.1:{self.ctl.settings()['port']}/")

    def on_log(self):
        win = tk.Toplevel(self.root)
        win.title("Pocketfader log")
        win.geometry("700x420")
        text = tk.Text(win, wrap="word", font=("Courier", 9))
        text.insert("1.0", self.ctl.log_text())
        text.config(state="disabled")
        text.pack(fill="both", expand=True)


def run():
    root = tk.Tk()
    Window(root, Controller())
    root.mainloop()
