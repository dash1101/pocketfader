#!/usr/bin/env bash
# Pocketfader installer for Linux (systemd user service).
#
#   ./install.sh              install + start at login
#   ./install.sh --lan        also listen on your home network (turns on an access token)
#   ./install.sh --token --allow-host=music.example.com   behind a reverse proxy / Cloudflare Tunnel
#   ./install.sh --uninstall  remove the service and the app (your settings are kept)
#
# Everything lives in your home directory; no root needed. It never touches your audio
# settings: the server only moves the volume slider when you do, from the app.
set -euo pipefail

APP_DIR="${PF_APP_DIR:-$HOME/.local/share/pocketfader}"
BIN_DIR="${PF_BIN_DIR:-$HOME/.local/bin}"
UNIT_DIR="${PF_UNIT_DIR:-$HOME/.config/systemd/user}"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTRA_ARGS=()
NO_SYSTEMD="${PF_NO_SYSTEMD:-0}"
UNINSTALL=0

for a in "$@"; do
  case "$a" in
    --lan) EXTRA_ARGS+=(--lan) ;;
    --token) EXTRA_ARGS+=(--token) ;;
    --allow-host=*) EXTRA_ARGS+=(--allow-host "${a#--allow-host=}") ;;
    --no-systemd) NO_SYSTEMD=1 ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option: $a" >&2; exit 2 ;;
  esac
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

if [ "$UNINSTALL" = 1 ]; then
  if [ "$NO_SYSTEMD" != 1 ] && command -v systemctl >/dev/null; then
    systemctl --user disable --now pocketfader.service 2>/dev/null || true
  fi
  rm -f "$UNIT_DIR/pocketfader.service" "$BIN_DIR/pocketfader" "$BIN_DIR/pocketfader-gui" \
        "${PF_APPS_DIR:-$HOME/.local/share/applications}/pocketfader.desktop" \
        "${PF_ICON_DIR:-$HOME/.local/share/icons/hicolor/scalable/apps}/pocketfader.svg"
  rm -rf "$APP_DIR"
  [ "$NO_SYSTEMD" != 1 ] && systemctl --user daemon-reload 2>/dev/null || true
  say "Removed. Settings kept in ~/.config/pocketfader (delete it if you want a clean slate)."
  exit 0
fi

# ---- checks ----
command -v python3 >/dev/null || die "python3 is required (try: sudo apt install python3 python3-venv)"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' || die "Python 3.9+ is required"
python3 -c 'import venv, ensurepip' 2>/dev/null || die "python3-venv is required (try: sudo apt install python3-venv)"
command -v pactl >/dev/null || die "pactl not found. Install pulseaudio-utils (works with PipeWire too)."
command -v busctl >/dev/null || echo "note: busctl (systemd) not found; generic player control will be unavailable."
command -v tailscale >/dev/null || echo "note: Tailscale not found. It's the easiest safe way to reach this from your phone away from home;"
command -v tailscale >/dev/null || echo "      otherwise re-run with --lan to use your home Wi-Fi."

# ---- install into a private virtualenv ----
say "Installing to $APP_DIR"
mkdir -p "$APP_DIR" "$BIN_DIR"
python3 -m venv --system-site-packages "$APP_DIR/venv"   # lets the window use the system GTK
"$APP_DIR/venv/bin/pip" install --quiet --upgrade pip
"$APP_DIR/venv/bin/pip" install --quiet "$SRC"
# a current yt-dlp lets the computer fetch songs for the phone when the phone can't (the distro's copy is often too old)
"$APP_DIR/venv/bin/pip" install --quiet --upgrade yt-dlp || echo "Note: couldn't install yt-dlp; the phone will get songs on its own only."
ln -sf "$APP_DIR/venv/bin/pocketfader" "$BIN_DIR/pocketfader"
[ -e "$APP_DIR/venv/bin/pocketfader-gui" ] && ln -sf "$APP_DIR/venv/bin/pocketfader-gui" "$BIN_DIR/pocketfader-gui"

# Launcher entry so "Pocketfader" shows up in the application menu (the desktop window).
APPS_DIR="${PF_APPS_DIR:-$HOME/.local/share/applications}"
ICON_DIR="${PF_ICON_DIR:-$HOME/.local/share/icons/hicolor/scalable/apps}"
mkdir -p "$APPS_DIR" "$ICON_DIR"
cp "$SRC/pocketfader/static/icon.svg" "$ICON_DIR/pocketfader.svg" 2>/dev/null || true
cat > "$APPS_DIR/pocketfader.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Pocketfader
Comment=Phone remote for your computer's volume and music
Exec=$BIN_DIR/pocketfader-gui
Icon=pocketfader
Terminal=false
Categories=AudioVideo;Audio;Utility;
DESKTOP

# ---- service ----
if [ "$NO_SYSTEMD" = 1 ] || ! command -v systemctl >/dev/null; then
  say "Installed. Start it with:  pocketfader ${EXTRA_ARGS[*]:-}"
  exit 0
fi

mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/pocketfader.service" <<EOF
[Unit]
Description=Pocketfader - phone remote for volume and music
After=network-online.target sound.target

[Service]
ExecStart=$APP_DIR/venv/bin/pocketfader ${EXTRA_ARGS[*]:-}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now pocketfader.service
say "Running. Show the address to open on your phone with:  journalctl --user -u pocketfader -n 20 --no-pager"
sleep 2
journalctl --user -u pocketfader -n 12 --no-pager 2>/dev/null | grep -E "listening|token|note|audio:" || true
