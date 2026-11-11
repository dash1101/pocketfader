"""Command line entry point:  ytm-starcast [--lan] [--port N] [--audio ...] [--dry-run]"""
import argparse
import logging
import signal
import sys
import time

from . import __version__
from .opener import Opener
from .backends import AUDIO_CHOICES, MEDIA_CHOICES, make_audio_and_engine, make_media
from .config import DEFAULT_PORT, JsonFile, config_dir, get_or_create_token
from .runner import Runner
from .server import App, bind_when_tailscale_up, lan_ip, serve_on, tailscale_installed, tailscale_ip


def build_parser():
    p = argparse.ArgumentParser(
        prog="ytm-starcast",
        description="Your YouTube Music companion: a remote for your phone, your music as your Discord "
                    "status, and a better YouTube Music.")
    p.add_argument("--port", type=int, default=DEFAULT_PORT)
    p.add_argument("--audio", choices=AUDIO_CHOICES, default="auto",
                   help="volume backend (default: auto = PipeWire/PulseAudio on Linux, "
                        "Windows endpoint volume on Windows). 'voicemeeter' is an opt-in Windows extra.")
    p.add_argument("--eq", choices=("auto", "builtin", "none"), default="auto",
                   help="built-in equalizer on PipeWire (auto = offer it when PipeWire's filter-chain is installed). "
                        "Nothing is created until you switch it on in the app.")
    p.add_argument("--media", choices=MEDIA_CHOICES, default="auto",
                   help="fallback player control when the YT Music extension isn't connected")
    p.add_argument("--lan", action="store_true",
                   help="also listen on this machine's private LAN address. Turns on the access token.")
    p.add_argument("--bind", action="append", default=[], metavar="ADDR",
                   help="listen on an extra address (repeatable). Requires the access token.")
    p.add_argument("--token", action="store_true", help="require an access token (generated once, then remembered)")
    p.add_argument("--allow-host", action="append", default=[], metavar="NAME",
                   help="accept this extra Host header (e.g. a custom DNS name)")
    p.add_argument("--dry-run", action="store_true",
                   help="read real state but never change volume, mute, or playback, never contact Discord, "
                        "and keep Discord status settings in memory only")
    p.add_argument("--gui", action="store_true", help="open the desktop window instead of running the server here")
    p.add_argument("--check-update", action="store_true", help="look for a newer release and exit")
    p.add_argument("--update", action="store_true", help="install the newest release (pip installs only) and exit")
    p.add_argument("--version", action="version", version=__version__)
    return p


def main(argv=None):
    a = build_parser().parse_args(argv)
    if a.check_update or a.update:
        from .updater import Updater
        up = Updater()
        info = up.latest(refresh=True)
        if info.get("error"):
            sys.exit(f"Couldn't check for updates: {info['error']}")
        if not info.get("available"):
            print(f"You're up to date ({__version__}).")
            return
        print(f"New release: {info['latest']} (you have {__version__}).  {info.get('url', '')}")
        if a.update:
            ok, msg = up.update_self()
            print(msg)
            sys.exit(0 if ok else 1)
        print("Run `ytm-starcast --update` to install it.")
        return
    if a.gui:
        from .gui import main as gui_main
        return gui_main()
    if sys.stdout is not None:  # None under pythonw; line-buffer so journald/log files see output live
        try:
            sys.stdout.reconfigure(line_buffering=True)
        except (AttributeError, ValueError):
            pass
    cfg = JsonFile(config_dir() / "config.json")

    exposed = a.lan or bool(a.bind)
    token = get_or_create_token(cfg) if (a.token or exposed) else None

    runner = Runner(dry_run=a.dry_run)
    audio, engine = make_audio_and_engine(a.audio, runner, eq=a.eq)
    media = make_media(a.media, runner)
    # Discord status (built in): its messages ("connected to Discord", problems) go to the log, never song details.
    if sys.stderr is not None:      # None under pythonw
        logging.basicConfig(level=logging.INFO, format="%(name)s: %(message)s")
    app = App(audio, engine, media, prefs=JsonFile(config_dir() / "prefs.json"),
              token=token, allowed_hosts=a.allow_host, opener=Opener(dry_run=a.dry_run), dry_run=a.dry_run)

    hosts = ["127.0.0.1"]
    ts = tailscale_ip()
    if ts:
        hosts.append(ts)
    if a.lan:
        ip = lan_ip()
        if ip:
            hosts.append(ip)
        else:
            print("WARNING: --lan given but no private LAN address was found.")
    hosts += a.bind

    app.start()
    q = f"/?token={token}" if token else "/"
    bound = []
    for h in dict.fromkeys(hosts):
        try:
            serve_on(app, h, a.port)
            bound.append(h)
            print(f"listening on http://{h}:{a.port}{q}")
        except OSError as e:
            print(f"could not bind {h}:{a.port} -- {e}")
    if not bound:
        sys.exit("nothing to listen on; is the port already in use? (try --port)")

    q_for = lambda host: f"http://{host}:{a.port}{q}"  # noqa: E731
    if not ts and tailscale_installed():
        print("note: Tailscale is installed but not connected yet; will also listen on it once it is up.")
        bind_when_tailscale_up(app, a.port, lambda ip: print(f"listening on {q_for(ip)}"))
    elif not ts and not exposed:
        print("note: no Tailscale found, so only this computer can reach it.")
        print("      To use it from your phone, install Tailscale (recommended) or run with --lan.")
    if token:
        print("access token required -- open the full link above once per device.")
    if a.dry_run:
        print("DRY RUN: volume/mute/playback commands are logged, not executed; Discord is not contacted.")
    print(f"audio: {getattr(audio, 'label', 'none')}  media: {getattr(media, 'label', 'none')}"
          + (f"  engine: {engine.label}" if engine.label else ""))

    # systemd (and most process managers) stop a service with SIGTERM; treat it like Ctrl-C so the
    # built-in EQ is taken out of the audio path and the original output is restored, and the Discord card is cleared.
    def _terminate(*_):
        raise KeyboardInterrupt
    try:
        signal.signal(signal.SIGTERM, _terminate)
    except (ValueError, OSError):
        pass
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        app.stop()


if __name__ == "__main__":
    main()
