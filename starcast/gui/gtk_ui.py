"""The server window on Linux: GTK 3 (python3-gi), so it matches GNOME, KDE, Cinnamon and friends."""
import threading
import webbrowser
from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

from .core import APP_ID, PART_ROWS, Controller  # noqa: E402

ICON = Path(__file__).resolve().parent.parent / "static" / "icon.svg"


def _row(title, subtitle=None):
    row = Gtk.ListBoxRow(activatable=False, selectable=False)
    box = Gtk.Box(spacing=12, margin=12)
    text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
    t = Gtk.Label(label=title, xalign=0)
    text.pack_start(t, False, False, 0)
    sub = Gtk.Label(label=subtitle or "", xalign=0, wrap=True, max_width_chars=48)
    sub.get_style_context().add_class("dim-label")
    if subtitle:
        text.pack_start(sub, False, False, 0)
    box.pack_start(text, True, True, 0)
    row.add(box)
    return row, box, t, sub


class Window(Gtk.Window):
    def __init__(self, ctl, live=True):
        super().__init__(title="YTM Starcast")
        self.ctl = ctl
        self.busy = False
        self.set_default_size(460, 620)
        if ICON.exists():
            try:
                self.set_icon_from_file(str(ICON))
            except GLib.Error:
                pass
        bar = Gtk.HeaderBar(show_close_button=True, title="YTM Starcast")
        self.set_titlebar(bar)

        scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        page = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18, margin=18)
        scroller.add(page)
        self.add(scroller)

        # --- status + on/off
        self.status_title = Gtk.Label(xalign=0)
        self.status_title.set_markup("<span size='x-large' weight='bold'>Checking…</span>")
        self.status_sub = Gtk.Label(xalign=0, wrap=True)
        self.status_sub.get_style_context().add_class("dim-label")
        head = Gtk.Box(spacing=12)
        col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
        col.pack_start(self.status_title, False, False, 0)
        col.pack_start(self.status_sub, False, False, 0)
        head.pack_start(col, True, True, 0)
        self.run_switch = Gtk.Switch(valign=Gtk.Align.CENTER)
        self.run_switch.connect("state-set", self.on_run)
        head.pack_start(self.run_switch, False, False, 0)
        page.pack_start(head, False, False, 0)

        # --- settings
        self.box1 = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.box1.get_style_context().add_class("frame")
        page.pack_start(self._section("Settings", self.box1), False, False, 0)

        r, b, _, _ = _row("Start when I log in", "YTM Starcast runs quietly in the background after you sign in.")
        self.login_switch = Gtk.Switch(valign=Gtk.Align.CENTER)
        self.login_switch.connect("state-set", self.on_login)
        b.pack_end(self.login_switch, False, False, 0)
        self.box1.add(r)

        r, b, _, _ = _row("Use on my home Wi-Fi", "Lets phones on your network connect. Turns on an access token.")
        self.lan_switch = Gtk.Switch(valign=Gtk.Align.CENTER)
        self.lan_switch.connect("state-set", self.on_lan)
        b.pack_end(self.lan_switch, False, False, 0)
        self.box1.add(r)

        r, b, _, _ = _row("Cloudflare hostname", "Only if you put the server behind a Cloudflare Tunnel.")
        self.host_entry = Gtk.Entry(placeholder_text="music.example.com", width_chars=22, valign=Gtk.Align.CENTER)
        self.host_entry.connect("activate", self.on_host)
        self.host_entry.connect("focus-out-event", lambda *_: self.on_host())
        b.pack_end(self.host_entry, False, False, 0)
        self.box1.add(r)

        r, b, _, self.upd_sub = _row("Updates", "Checking…")
        self.upd_btn = Gtk.Button(label="Check", valign=Gtk.Align.CENTER)
        self.upd_btn.connect("clicked", self.on_update)
        b.pack_end(self.upd_btn, False, False, 0)
        self.box1.add(r)
        self.update_available = False

        # --- parts (kept by the server, so they only change while it runs)
        self.box_parts = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.box_parts.get_style_context().add_class("frame")
        parts = self._section("Parts", self.box_parts)
        self.parts_note = Gtk.Label(xalign=0, wrap=True)
        self.parts_note.get_style_context().add_class("dim-label")
        parts.pack_start(self.parts_note, False, False, 0)
        page.pack_start(parts, False, False, 0)
        self.part_switches = {}
        for key, title, sub in PART_ROWS:
            r, b, _, _ = _row(title, sub)
            sw = Gtk.Switch(valign=Gtk.Align.CENTER, sensitive=False)
            sw.connect("state-set", self.on_part, key)
            b.pack_end(sw, False, False, 0)
            self.part_switches[key] = sw
            self.box_parts.add(r)

        # --- addresses
        self.box2 = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.box2.get_style_context().add_class("frame")
        self.addr_section = self._section("Open on your phone", self.box2)
        page.pack_start(self.addr_section, False, False, 0)

        # --- details
        self.box3 = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.box3.get_style_context().add_class("frame")
        page.pack_start(self._section("Status", self.box3), False, False, 0)
        self.detail_rows = {}
        for key, title in (("device", "Sound output"), ("ytm", "YouTube Music extension"), ("now", "Now playing"), ("version", "Version")):
            r, b, _, _ = _row(title)
            val = Gtk.Label(xalign=1, ellipsize=3, max_width_chars=28)
            val.get_style_context().add_class("dim-label")
            b.pack_end(val, False, False, 0)
            self.detail_rows[key] = val
            self.box3.add(r)

        # --- buttons
        btns = Gtk.Box(spacing=10)
        for label, fn in (("Open the web app", self.on_open), ("Browser extension", self.on_extension), ("Show log", self.on_log)):
            btn = Gtk.Button(label=label)
            btn.connect("clicked", fn)
            btns.pack_start(btn, True, True, 0)
        page.pack_start(btns, False, False, 0)
        self.msg = Gtk.Label(xalign=0, wrap=True)
        self.msg.get_style_context().add_class("dim-label")
        page.pack_start(self.msg, False, False, 0)

        self._loading = True
        if live:                       # tests build the window without showing it or polling
            self.show_all()
            self.refresh()
            self.check_updates()
            GLib.timeout_add_seconds(3, self._tick)

    def _section(self, title, widget):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        lbl = Gtk.Label(xalign=0)
        lbl.set_markup(f"<b>{title}</b>")
        box.pack_start(lbl, False, False, 0)
        box.pack_start(widget, False, False, 0)
        return box

    # ---------------------------------------------------------------- refresh (never blocks the window)
    def _tick(self):
        if not self.busy:
            self.refresh()
        return True

    def refresh(self):
        def work():
            d = self.ctl.details()
            snap = {"details": d, "settings": self.ctl.settings(), "autostart": self.ctl.autostart_enabled(),
                    "addresses": self.ctl.addresses()}
            GLib.idle_add(self.apply, snap)
        threading.Thread(target=work, daemon=True).start()

    def apply(self, snap):
        self._loading = True
        d, s = snap["details"], snap["settings"]
        running = d is not None
        word = "YTM Starcast is running" if running else "YTM Starcast is stopped"
        self.status_title.set_markup(f"<span size='x-large' weight='bold'>{word}</span>")
        self.status_sub.set_text("Your phone can control this computer." if running else "Switch it on to use your phone as a remote.")
        self.run_switch.set_active(running)
        self.login_switch.set_active(snap["autostart"])
        self.lan_switch.set_active(s["lan"])
        parts = (d or {}).get("parts")
        for key, sw in self.part_switches.items():
            sw.set_sensitive(parts is not None and not self.busy)
            if parts is not None:
                sw.set_active(parts[key])
        self.parts_note.set_text("" if parts is not None else
                                 "Switch YTM Starcast on to change these." if not running else
                                 "The running server is an older version without these switches.")
        self.parts_note.set_visible(parts is None)
        if not self.host_entry.has_focus():
            self.host_entry.set_text(s["allow_host"])
        for row in self.box2.get_children():
            self.box2.remove(row)
        for label, url in snap["addresses"]:
            r, b, _, sub = _row(label, url)
            sub.set_selectable(True)
            copy = Gtk.Button(label="Copy", valign=Gtk.Align.CENTER)
            copy.connect("clicked", self.on_copy, url)
            b.pack_end(copy, False, False, 0)
            self.box2.add(r)
        self.box2.show_all()
        d = d or {}
        self.detail_rows["device"].set_text(d.get("device") or "—")
        self.detail_rows["ytm"].set_text("Connected" if d.get("ytm") else "Not connected" if d else "—")
        self.detail_rows["now"].set_text(d.get("now") or "—")
        self.detail_rows["version"].set_text(d.get("version") or "—")
        self._loading = False

    # ---------------------------------------------------------------- actions
    def _do(self, fn, text):
        def work():
            ok = fn()
            GLib.idle_add(self._done, ok, text)
        self.busy = True
        self.msg.set_text(text)
        threading.Thread(target=work, daemon=True).start()

    def _done(self, ok, text):
        self.busy = False
        self.msg.set_text("" if ok else "That didn't work. Use “Show log” to see why.")
        self.refresh()

    def on_run(self, _sw, state):
        if self._loading or self.busy:
            return False
        self._do(self.ctl.start if state else self.ctl.stop, "Starting…" if state else "Stopping…")
        return False

    def on_part(self, _sw, state, key):
        if self._loading or self.busy:
            return False
        self._do(lambda: self.ctl.set_part(key, state) is not None, "Saving…")
        return False

    def on_login(self, _sw, state):
        if not self._loading:
            self._do(lambda: self.ctl.set_autostart(state), "Saving…")
        return False

    def on_lan(self, _sw, state):
        if self._loading:
            return False
        self.ctl.update(lan=bool(state))
        self._do(self._restart_if_running, "Applying…")
        return False

    def on_host(self, *_):
        if self._loading:
            return False
        text = self.host_entry.get_text().strip()
        if text != self.ctl.settings()["allow_host"]:
            self.ctl.update(allow_host=text)
            self._do(self._restart_if_running, "Applying…")
        return False

    def _restart_if_running(self):
        if self.ctl.is_running():
            return self.ctl.restart()
        if self.ctl.autostart_enabled():
            return self.ctl.set_autostart(True)      # refresh the saved command with the new options
        return True

    def check_updates(self):
        def work():
            info = self.ctl.update_info(refresh=True)
            GLib.idle_add(self.apply_update_info, info)
        threading.Thread(target=work, daemon=True).start()

    def apply_update_info(self, info):
        self.update_available = bool(info.get("available"))
        if info.get("error"):
            text, label = info["error"].capitalize() + ".", "Check"
        elif self.update_available:
            can = info.get("self_update")
            text = f"{info['latest']} is available (you have {info['current']})." + ("" if can else " Download it from the release page.")
            label = "Update now" if can else "Open page"
        else:
            text, label = f"You're up to date ({info['current']}).", "Check"
        self.upd_sub.set_text(text)
        self.upd_btn.set_label(label)
        self._upd_url = info.get("url")

    def on_update(self, _btn):
        label = self.upd_btn.get_label()
        if label == "Open page" and getattr(self, "_upd_url", None):
            webbrowser.open(self._upd_url)
        elif label == "Update now":
            def run():
                return self.ctl.update_now()[0]
            self._do(run, "Updating…")
            GLib.timeout_add_seconds(2, lambda: (self.check_updates(), False)[1])
        else:
            self.upd_sub.set_text("Checking…")
            self.check_updates()

    def on_copy(self, _btn, url):
        Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD).set_text(url, -1)
        self.msg.set_text("Copied.")

    def on_open(self, _btn):
        webbrowser.open(f"http://127.0.0.1:{self.ctl.settings()['port']}/")

    def on_extension(self, _btn):
        """The web app's step-by-step setup of the browser extension."""
        webbrowser.open(f"http://127.0.0.1:{self.ctl.settings()['port']}/#extension")

    def on_log(self, _btn):
        dlg = Gtk.Dialog(title="YTM Starcast log", transient_for=self, modal=True)
        dlg.set_default_size(640, 420)
        view = Gtk.TextView(editable=False, monospace=True, wrap_mode=Gtk.WrapMode.WORD_CHAR)
        view.get_buffer().set_text(self.ctl.log_text())
        sc = Gtk.ScrolledWindow()
        sc.add(view)
        dlg.get_content_area().pack_start(sc, True, True, 0)
        dlg.add_button("Close", Gtk.ResponseType.CLOSE)
        dlg.show_all()
        dlg.run()
        dlg.destroy()


class StarcastApp(Gtk.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID)

    def do_activate(self):
        win = self.props.active_window
        if win is None:
            win = Window(Controller())
            self.add_window(win)
        win.present()


def run():
    # The window carries the same name on X11 (its class) as on Wayland (its app id), and ytm-starcast.desktop names it
    # as StartupWMClass, so the desktop matches the window to YTM Starcast's menu entry and icon either way.
    GLib.set_prgname(APP_ID)
    GLib.set_application_name("YTM Starcast")
    return StarcastApp().run([])
