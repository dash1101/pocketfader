#!/usr/bin/env bash
# YTM Starcast installer for Linux (runs as a systemd user service).
#
#   ./install.sh                    install, start it now and every time you log in
#   ./install.sh --lan              also listen on your home network (turns on an access token)
#   ./install.sh --port=8790        another port than 8777
#   ./install.sh --token --allow-host=music.example.com   behind a reverse proxy / Cloudflare Tunnel
#   ./install.sh --no-desktop-app   leave out the desktop app (the window in the application menu)
#   ./install.sh --no-systemd       install only; start it yourself with `ytm-starcast`
#   ./install.sh --keep-ytm-beacon  leave the separate YTM Beacon app installed
#   ./install.sh --uninstall        remove YTM Starcast (your settings are kept)
#
# Everything lives in your home directory; no root needed. It never changes your sound settings.
# Installing again (an update) keeps the server options it was started with, and leaves the desktop app out if it was
# left out before (--desktop-app brings it back).
# YTM Starcast used to be called Pocketfader: an installed Pocketfader is replaced, and its settings carry over.
# YTM Starcast also shows your music as your Discord status, which the separate YTM Beacon app used to do. If that app is
# installed, its settings are copied to YTM Starcast and it is removed with its own uninstaller.
set -euo pipefail

CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CACHE_HOME="${XDG_CACHE_HOME:-$HOME/.cache}"
APP_DIR="${PF_APP_DIR:-$HOME/.local/share/ytm-starcast}"
BIN_DIR="${PF_BIN_DIR:-$HOME/.local/bin}"
HOME_UNIT_DIR="$HOME/.config/systemd/user"   # where the desktop window, and Pocketfader before it, put their units
APPS_DIR="${PF_APPS_DIR:-$HOME/.local/share/applications}"
ICON_DIR="${PF_ICON_DIR:-$HOME/.local/share/icons/hicolor/scalable/apps}"
AUTOSTART_DIR="$CONFIG_HOME/autostart"
OLD_APP_DIR="${PF_OLD_APP_DIR:-$HOME/.local/share/pocketfader}"   # where Pocketfader (the old name) lived
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXTRA_ARGS=()
OPTIONS_GIVEN=0
NO_SYSTEMD="${PF_NO_SYSTEMD:-0}"
UNINSTALL=0
KEEP_BEACON=0
DESKTOP_APP=""                       # yes / no; empty: as the installation that's there has it (yes for a new one)

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; }

for a in "$@"; do
  case "$a" in
    --lan) EXTRA_ARGS+=(--lan); OPTIONS_GIVEN=1 ;;
    --token) EXTRA_ARGS+=(--token); OPTIONS_GIVEN=1 ;;
    --allow-host=*) EXTRA_ARGS+=(--allow-host "${a#--allow-host=}"); OPTIONS_GIVEN=1 ;;
    --port=*)
      p="${a#--port=}"
      if ! [[ $p =~ ^[0-9]{1,5}$ ]] || [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then
        echo "--port needs a number from 1 to 65535" >&2; exit 2
      fi
      EXTRA_ARGS+=(--port "$p"); OPTIONS_GIVEN=1 ;;
    --no-desktop-app) DESKTOP_APP=no ;;
    --desktop-app) DESKTOP_APP=yes ;;
    --no-systemd) NO_SYSTEMD=1 ;;
    --keep-ytm-beacon) KEEP_BEACON=1 ;;
    --uninstall) UNINSTALL=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $a (see ./install.sh --help)" >&2; exit 2 ;;
  esac
done

say()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
tilde() { case "$1" in "$HOME"/*) printf '~/%s' "${1#"$HOME"/}" ;; *) printf '%s' "$1" ;; esac; }

# YTM Starcast's own service only when systemd is wanted; an old service is retired whenever systemd is there.
# "There" means systemctl reaches your own service manager (SYSTEMD_OK, worked out below), not only that it exists.
use_systemd()    { [ "$NO_SYSTEMD" != 1 ] && [ "$SYSTEMD_OK" = 1 ]; }
have_systemctl() { [ "$SYSTEMD_OK" = 1 ]; }

# Remove a folder of ours, never something that could hold other things.
remove_dir() {
  case "$1" in
    ""|/|"$HOME"|"$HOME/"|"$HOME/.local"|"$HOME/.local/share"|"$HOME/.config"|"$HOME/.cache"|"$DATA_HOME"|"$CACHE_HOME") return 0 ;;
  esac
  rm -rf -- "$1"
}

# Command links are removed only when they are links; a file of the same name that you made stays.
remove_links() {
  local n
  for n in "$@"; do
    if [ -L "$BIN_DIR/$n" ]; then rm -f -- "$BIN_DIR/$n"; fi
  done
}

# The server options an old start entry used, and only those. An entry the desktop window wrote can start the program
# as "python -m <module>" (when the window didn't find the program on its PATH); that part, and anything else that
# isn't one of the server's options, is left out. Nothing is taken when the entry has anything but plain words.
start_options() {   # <file> <key: ExecStart or Exec>
  local line w v i=0 words=() out=() plain='^[A-Za-z0-9 ._:,=/@-]*$' value='^[A-Za-z0-9._:,/@-]+$'
  line="$(sed -n "s/^$2=//p" "$1" | head -n 1)"
  case "$line" in
    \"*) line="${line#\"}"; line="${line#*\"}" ;;
    *" "*) line="${line#* }" ;;
    *) line="" ;;
  esac
  [[ $line =~ $plain ]] || return 0
  read -r -a words <<< "$line"
  while [ "$i" -lt "${#words[@]}" ]; do
    w="${words[$i]}"; v=""
    case "$w" in --*=*) v="${w#*=}"; w="${w%%=*}" ;; esac
    case "$w" in
      --lan|--token) if [ -z "$v" ]; then out+=("$w"); fi ;;
      --port|--bind|--allow-host|--audio|--eq|--media)
        if [ -z "$v" ] && [ $((i + 1)) -lt "${#words[@]}" ] && [[ ${words[$((i + 1))]} != -* ]]; then
          i=$((i + 1)); v="${words[$i]}"
        fi
        if [[ $v =~ $value ]] && [[ $v != -* ]]; then out+=("$w" "$v"); fi ;;
      -m) i=$((i + 1)) ;;      # "-m <module>": how Python was told to run the program, not one of its options
    esac
    i=$((i + 1))
  done
  printf '%s' "${out[*]}"
}

carry_options() {   # <file> <key>
  local opts
  if [ "$OPTIONS_GIVEN" = 1 ] || [ ${#EXTRA_ARGS[@]} -gt 0 ]; then return 0; fi
  opts="$(start_options "$1" "$2")"
  [ -n "$opts" ] || return 0
  # the program just installed must accept them (a value it no longer knows would stop it at every start)
  # shellcheck disable=SC2086   # plain words only (start_options)
  if ! "$APP_DIR/venv/bin/python" -I -c 'import sys
from starcast.cli import build_parser
build_parser().parse_args(sys.argv[1:])' $opts >/dev/null 2>&1; then
    note "The options it was started with ($opts) don't work with this version, so it starts without them."
    return 0
  fi
  read -r -a EXTRA_ARGS <<< "$opts"
  note "Keeping the options it was started with: $opts"
}

# A server that the desktop window started by itself (computers without systemd), found by its pid file.
stop_window_server() {   # <pid file> <program name> [<Python module name>, when it differs from the program's]
  local pid argv=()
  [ -f "$1" ] || return 0
  pid="$(tr -dc '0-9' < "$1")"
  [ -n "$pid" ] && [ -r "/proc/$pid/cmdline" ] || return 0
  mapfile -d '' -t argv < "/proc/$pid/cmdline" || return 0
  if [ "$(basename -- "${argv[0]:-x}")" = "$2" ] || [ "$(basename -- "${argv[1]:-x}")" = "$2" ] \
     || { [ "${argv[1]:-}" = -m ] && [ "${argv[2]:-}" = "${3:-$2}" ]; }; then
    kill "$pid" 2>/dev/null || true
  fi
  return 0
}

CUR_ENABLED=""        # yes / no: whether an earlier YTM Starcast service started at login (empty = there was none)
OLD_ENABLED=""        # yes / no: whether Pocketfader started at login (empty = there was no Pocketfader service)
OLD_ACTIVE=""         # yes / no: whether Pocketfader's service was running
OLD_AUTOSTART=0       # Pocketfader started at login through a desktop autostart entry
POCKETFADER=0         # Pocketfader was found and stopped in this run; its files go once YTM Starcast runs
OLD_UNIT=""           # Pocketfader's service file, wherever it is

# The old installer and the desktop window always wrote it to ~/.config/systemd/user, even with XDG_CONFIG_HOME set.
find_old_unit() {
  local d f
  for d in "$UNIT_DIR" "$CONFIG_HOME/systemd/user" "$HOME_UNIT_DIR"; do
    if [ -f "$d/pocketfader.service" ]; then OLD_UNIT="$d/pocketfader.service"; return 0; fi
  done
  # anywhere else in your home that systemd loads it from
  if have_systemctl && { [ -d "$OLD_APP_DIR" ] || [ -L "$BIN_DIR/pocketfader" ]; }; then
    f="$(systemctl --user show -p FragmentPath --value pocketfader.service 2>/dev/null || true)"
    case "$f" in "$HOME"/*/pocketfader.service) if [ -f "$f" ]; then OLD_UNIT="$f"; fi ;; esac
  fi
  return 0
}

is_enabled() {   # <unit> -> yes / no
  case "$(systemctl --user is-enabled "$1" 2>/dev/null || true)" in
    enabled*) echo yes ;;
    *) echo no ;;
  esac
}

# Installing again over YTM Starcast: keep the options its service was started with, and whether it starts at login.
remember_starcast() {
  [ -f "$UNIT" ] || return 0
  carry_options "$UNIT" ExecStart
  if use_systemd; then CUR_ENABLED="$(is_enabled ytm-starcast.service)"; fi
}

old_pocketfader_present() {
  [ -n "$OLD_UNIT" ] || [ -d "$OLD_APP_DIR" ] || [ -L "$BIN_DIR/pocketfader" ] || [ -L "$BIN_DIR/pocketfader-gui" ] \
    || [ -e "$APPS_DIR/pocketfader.desktop" ] || [ -e "$ICON_DIR/pocketfader.svg" ] \
    || [ -e "$AUTOSTART_DIR/pocketfader.desktop" ]
}

remove_old_pocketfader_files() {
  rm -f -- "$APPS_DIR/pocketfader.desktop" "$ICON_DIR/pocketfader.svg" "$AUTOSTART_DIR/pocketfader.desktop"
  remove_links pocketfader pocketfader-gui
  if [ "$OLD_APP_DIR" != "$APP_DIR" ]; then
    case "$APP_DIR/" in "$OLD_APP_DIR"/*) ;; *) remove_dir "$OLD_APP_DIR" ;; esac
  fi
}

# Before YTM Starcast starts: stop Pocketfader and take it out of start-at-login. Its files stay until YTM Starcast runs.
stop_pocketfader() {
  old_pocketfader_present || return 0
  POCKETFADER=1
  say "Replacing Pocketfader (YTM Starcast's old name). Your settings and paired phones carry over."
  if [ -n "$OLD_UNIT" ]; then
    carry_options "$OLD_UNIT" ExecStart
    if have_systemctl; then
      OLD_ENABLED="$(is_enabled pocketfader.service)"
      if systemctl --user is-active --quiet pocketfader.service 2>/dev/null; then OLD_ACTIVE=yes; else OLD_ACTIVE=no; fi
      systemctl --user disable --now pocketfader.service >/dev/null 2>&1 || true
    fi
  fi
  if [ -f "$AUTOSTART_DIR/pocketfader.desktop" ]; then
    OLD_AUTOSTART=1
    carry_options "$AUTOSTART_DIR/pocketfader.desktop" Exec
  fi
  stop_window_server "$CONFIG_HOME/pocketfader/server.pid" pocketfader
}

# Once YTM Starcast runs (or, without systemd, is installed).
remove_pocketfader() {
  [ "$POCKETFADER" = 1 ] || return 0
  if [ -n "$OLD_UNIT" ]; then
    rm -f -- "$OLD_UNIT"
    if have_systemctl; then systemctl --user daemon-reload >/dev/null 2>&1 || true; fi
  fi
  remove_old_pocketfader_files
  note "Pocketfader is removed."
}

# YTM Starcast didn't start: Pocketfader goes back to how it was.
restore_pocketfader() {
  if [ "$POCKETFADER" != 1 ] || [ -z "$OLD_UNIT" ] || ! have_systemctl; then return 0; fi
  if [ "$OLD_ENABLED" = yes ]; then systemctl --user enable pocketfader.service >/dev/null 2>&1 || true; fi
  if [ "$OLD_ACTIVE" = yes ]; then systemctl --user start pocketfader.service >/dev/null 2>&1 || true; fi
  note "Pocketfader is left as it was."
}

# Copy YTM Beacon's settings (words never to show, private mode ...) to YTM Starcast, replacing its Discord status
# settings. YTM Starcast copies them by itself only the first time it starts, so anything changed in YTM Beacon since
# then would otherwise be lost.
take_over_beacon_settings() {
  "$APP_DIR/venv/bin/python" -I -c 'import sys
from starcast.config import take_over_beacon_main
sys.exit(take_over_beacon_main())' 2>&1 | sed 's/^/    /'
}

retire_ytm_beacon() {
  local unit="$CONFIG_HOME/systemd/user/ytm-beacon.service" xdg="$AUTOSTART_DIR/ytm-beacon.desktop" exe="" cand=""
  # Look only where the YTM Beacon app installs itself (never a search of PATH).
  if [ -n "${YTM_BEACON_HOME:-}" ] && [ -x "$YTM_BEACON_HOME/bin/ytm-beacon" ]; then
    exe="$YTM_BEACON_HOME/bin/ytm-beacon"
  fi
  if [ -z "$exe" ] && [ -f "$unit" ]; then
    cand="$(sed -n 's/^ExecStart="\{0,1\}\([^"]*\)"\{0,1\} run$/\1/p' "$unit" | head -n 1)"
    if [ -n "$cand" ] && [ -x "$cand" ] && [ "$(basename -- "$cand")" = ytm-beacon ]; then exe="$cand"; fi
  fi
  if [ -z "$exe" ] && [ -x "$HOME/.local/bin/ytm-beacon" ]; then exe="$HOME/.local/bin/ytm-beacon"; fi
  if [ -z "$exe" ] && [ ! -f "$unit" ] && [ ! -f "$xdg" ]; then return 0; fi

  if [ "$KEEP_BEACON" = 1 ]; then
    say "The YTM Beacon app stays installed (--keep-ytm-beacon). YTM Starcast's Discord status waits while it runs."
    return 0
  fi
  say "Replacing the YTM Beacon app: YTM Starcast shows your music as your Discord status itself now."
  if ! take_over_beacon_settings; then
    # carrying on with other settings could show songs it was told to keep private
    note "So the YTM Beacon app stays installed for now. Fix or remove that settings file, then run this again."
    return 0
  fi
  local by_hand=1
  if [ -n "$exe" ]; then
    note "Running YTM Beacon's own uninstaller (it leaves its own settings file in place)."
    local run=("$exe" uninstall)
    if command -v timeout >/dev/null 2>&1; then run=(timeout 120 "${run[@]}"); fi
    if "${run[@]}" </dev/null; then
      by_hand=0
    else
      note "YTM Beacon's uninstaller didn't finish, so it is stopped and taken out of start-at-login instead."
    fi
  fi
  # Whatever is left (no program to ask, or its uninstaller failed): stop it and remove its start-at-login entries.
  if [ -f "$unit" ]; then
    if have_systemctl; then systemctl --user disable --now ytm-beacon.service >/dev/null 2>&1 || true; fi
    rm -f -- "$unit"
    if have_systemctl; then systemctl --user daemon-reload >/dev/null 2>&1 || true; fi
  fi
  rm -f -- "$xdg"
  if [ "$by_hand" = 1 ]; then
    # what its uninstaller would also have removed: the copy of its browser extension. Its settings and log stay.
    local data="$DATA_HOME/ytm-beacon"
    if [ -n "${YTM_BEACON_HOME:-}" ]; then data="$YTM_BEACON_HOME/data"; fi
    rm -rf -- "$data/extension"
    rm -f -- "$data/extension-connected"
  fi
  note "If your browser has the \"YTM Beacon\" extension, remove it on the browser's extensions page;"
  note "YTM Starcast's extension does its job now."
}

settings_folder() {
  if [ -d "$CONFIG_HOME/ytm-starcast" ] || [ ! -d "$CONFIG_HOME/pocketfader" ]; then
    tilde "$CONFIG_HOME/ytm-starcast"
  else
    tilde "$CONFIG_HOME/pocketfader"
  fi
}

# ---- systemd ----
HAVE_SYSTEMCTL=0
SYSTEMD_OK=0
if command -v systemctl >/dev/null 2>&1; then
  HAVE_SYSTEMCTL=1
  # under sudo or su, or in a container, systemctl is there but can't reach your own service manager
  if systemctl --user show-environment >/dev/null 2>&1; then SYSTEMD_OK=1; fi
fi
# Where your service manager reads units from: $XDG_CONFIG_HOME/systemd/user only when it uses that folder too.
if [ -n "${PF_UNIT_DIR:-}" ]; then
  UNIT_DIR="$PF_UNIT_DIR"
else
  UNIT_DIR="$HOME_UNIT_DIR"
  if have_systemctl && [ "$CONFIG_HOME/systemd/user" != "$HOME_UNIT_DIR" ]; then
    case " $(systemctl --user show -p UnitPath --value 2>/dev/null || true) " in
      *" $CONFIG_HOME/systemd/user "*) UNIT_DIR="$CONFIG_HOME/systemd/user" ;;
    esac
  fi
fi
UNIT="$UNIT_DIR/ytm-starcast.service"
find_old_unit

# Nothing could be switched on or off: stop before anything is changed.
if [ "$HAVE_SYSTEMCTL" = 1 ] && [ "$SYSTEMD_OK" = 0 ]; then
  why=""
  if [ "$UNINSTALL" != 1 ] && [ "$NO_SYSTEMD" != 1 ]; then
    why="YTM Starcast can't be set up to start by itself (to install it without that, add --no-systemd)"
  elif [ -n "$OLD_UNIT" ]; then
    why="Pocketfader's service can't be switched off"
  elif [ "$UNINSTALL" = 1 ] && [ -f "$UNIT" ]; then
    why="YTM Starcast's service can't be switched off"
  fi
  if [ -n "$why" ]; then
    die "systemctl can't reach your user's service manager (this happens under sudo or su, or in a container), so $why. Nothing was changed. Run ./install.sh as yourself, in your own login session."
  fi
fi

# ---- uninstall ----
if [ "$UNINSTALL" = 1 ]; then
  reload=0
  # a service that exists is switched off even with --no-systemd, so nothing is left to start at login
  if have_systemctl && { use_systemd || [ -f "$UNIT" ]; }; then
    systemctl --user disable --now ytm-starcast.service >/dev/null 2>&1 || true
    reload=1
  fi
  if [ -n "$OLD_UNIT" ] && have_systemctl; then
    systemctl --user disable --now pocketfader.service >/dev/null 2>&1 || true
    reload=1
  fi
  stop_window_server "$CONFIG_HOME/ytm-starcast/server.pid" ytm-starcast starcast
  stop_window_server "$CONFIG_HOME/pocketfader/server.pid" pocketfader
  if [ -n "$OLD_UNIT" ]; then rm -f -- "$OLD_UNIT"; fi
  rm -f -- "$UNIT" "$HOME_UNIT_DIR/ytm-starcast.service" "$APPS_DIR/ytm-starcast.desktop" "$ICON_DIR/ytm-starcast.svg" \
    "$AUTOSTART_DIR/ytm-starcast.desktop"
  remove_links ytm-starcast ytm-starcast-gui
  remove_dir "$APP_DIR"
  remove_old_pocketfader_files
  # songs kept for the phone (under either name); they are fetched again if YTM Starcast comes back
  remove_dir "$CACHE_HOME/ytm-starcast"
  remove_dir "$CACHE_HOME/pocketfader"
  # the extension's folder (the browser loads it from there)
  remove_dir "${XDG_DATA_HOME:-$HOME/.local/share}/ytm-starcast/extension"
  rmdir -- "${XDG_DATA_HOME:-$HOME/.local/share}/ytm-starcast" 2>/dev/null || true
  if [ "$reload" = 1 ]; then systemctl --user daemon-reload >/dev/null 2>&1 || true; fi
  say "YTM Starcast is removed. Your settings are kept in $(settings_folder) (delete that folder for a clean slate)."
  note "Remove the YTM Starcast extension from your browser too: open its extensions page and click Remove."
  exit 0
fi

# ---- checks ----
command -v python3 >/dev/null || die "Python 3 is needed (try: sudo apt install python3 python3-venv)"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' || die "Python 3.9 or newer is needed"
python3 -c 'import venv, ensurepip' 2>/dev/null || die "python3-venv is needed (try: sudo apt install python3-venv)"
command -v pactl >/dev/null || die "pactl wasn't found. Install the pulseaudio-utils package (it works with PipeWire too)."
command -v busctl >/dev/null || echo "Note: busctl (part of systemd) wasn't found, so controlling other music players won't work."
if ! command -v tailscale >/dev/null; then
  echo "Note: Tailscale wasn't found. It's the easiest safe way to reach YTM Starcast from your phone away from home;"
  echo "      otherwise run this again with --lan to use it on your home Wi-Fi."
fi

# ---- install into a private virtualenv ----
say "Installing YTM Starcast to $(tilde "$APP_DIR")"
mkdir -p "$APP_DIR" "$BIN_DIR"
python3 -m venv --system-site-packages "$APP_DIR/venv"   # lets the window use the system's GTK
"$APP_DIR/venv/bin/pip" install --quiet --upgrade pip
"$APP_DIR/venv/bin/pip" install --quiet "$SRC"
# a current yt-dlp lets the computer fetch songs for the phone when the phone can't (the distro's copy is often too old)
"$APP_DIR/venv/bin/pip" install --quiet --upgrade yt-dlp || echo "Note: couldn't install yt-dlp; the phone will get songs on its own only."
ln -sfn "$APP_DIR/venv/bin/ytm-starcast" "$BIN_DIR/ytm-starcast"

# The desktop app (a window with the addresses for your phone, the switches, start/stop and the log): its command and
# its entry in the application menu. Left out with --no-desktop-app, and on updates of an installation without it.
NO_APP_MARK="$APP_DIR/no-desktop-app"
if [ -z "$DESKTOP_APP" ]; then
  if [ -f "$NO_APP_MARK" ]; then DESKTOP_APP=no; else DESKTOP_APP=yes; fi
fi
if [ "$DESKTOP_APP" = no ]; then
  remove_links ytm-starcast-gui
  rm -f -- "$APPS_DIR/ytm-starcast.desktop" "$ICON_DIR/ytm-starcast.svg"
  echo "Installed with --no-desktop-app: updates leave the desktop app out too." > "$NO_APP_MARK"
  note "The desktop app is left out (--no-desktop-app)."
else
  rm -f -- "$NO_APP_MARK"
  if [ -e "$APP_DIR/venv/bin/ytm-starcast-gui" ]; then ln -sfn "$APP_DIR/venv/bin/ytm-starcast-gui" "$BIN_DIR/ytm-starcast-gui"; fi
  # Menu entry, so "YTM Starcast" shows up in the application menu (it opens the desktop window).
  mkdir -p "$APPS_DIR" "$ICON_DIR"
  cp "$SRC/starcast/static/icon.svg" "$ICON_DIR/ytm-starcast.svg" 2>/dev/null || true
  cat > "$APPS_DIR/ytm-starcast.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=YTM Starcast
GenericName=YouTube Music companion
Comment=A remote for your phone, your music as your Discord status, and a better YouTube Music
Exec=$BIN_DIR/ytm-starcast-gui
Icon=ytm-starcast
Terminal=false
Categories=AudioVideo;Audio;Utility;
Keywords=YouTube Music;Discord;remote;Pocketfader;YTM Beacon;
StartupWMClass=app.novalabs.starcast
DESKTOP
fi

# ---- replace what YTM Starcast takes over ----
remember_starcast
stop_pocketfader
retire_ytm_beacon

# ---- the browser extension: a copy in a folder that never moves (the server keeps it up to date after updates) ----
EXT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/ytm-starcast/extension"
HAVE_EXT=0
if [ -f "$SRC/extension/manifest.json" ]; then
  mkdir -p "$(dirname "$EXT_DIR")"
  rm -rf -- "$EXT_DIR.new"
  cp -R -- "$SRC/extension" "$EXT_DIR.new"
  rm -f -- "$EXT_DIR.new/README.md"
  rm -rf -- "$EXT_DIR.old"
  if [ -d "$EXT_DIR" ]; then mv -- "$EXT_DIR" "$EXT_DIR.old"; fi
  mv -- "$EXT_DIR.new" "$EXT_DIR"
  rm -rf -- "$EXT_DIR.old"
  HAVE_EXT=1
fi
# The browsers the steps can open (the first one found is offered first).
ext_browsers() {
  local b
  for b in brave-browser brave google-chrome google-chrome-stable chromium chromium-browser microsoft-edge vivaldi; do
    if command -v "$b" >/dev/null 2>&1; then printf '%s\n' "$b"; fi
  done
  return 0
}
ext_page() {
  case "$1" in
    brave*) echo "brave://extensions" ;; microsoft-edge*) echo "edge://extensions" ;;
    vivaldi*) echo "vivaldi://extensions" ;; *) echo "chrome://extensions" ;;
  esac
}
# One time, by hand: browsers only take an extension like this from their extensions page.
extension_steps() {
  [ "$HAVE_EXT" = 1 ] || return 0
  local port=8777 i
  for ((i = 0; i + 1 < ${#EXTRA_ARGS[@]}; i++)); do
    if [ "${EXTRA_ARGS[$i]}" = --port ]; then port="${EXTRA_ARGS[$((i + 1))]}"; fi
  done
  echo
  say "One more step: add the YTM Starcast extension to your browser (one time)"
  note "1. Open the browser's extensions page: brave://extensions, chrome://extensions or edge://extensions"
  note "2. Turn on \"Developer mode\" (top-right corner)."
  note "3. Click \"Load unpacked\" and choose this folder:"
  note "     $(tilde "$EXT_DIR")"
  note "4. Open music.youtube.com in that browser (or refresh its tab)."
  note "The same steps, with buttons that open the page and the folder: Settings > Browser extension in the app"
  note "(http://127.0.0.1:$port/#extension)."
  # Offer to open the page and the folder now, when someone is at the keyboard
  local first
  first="$(ext_browsers | head -n 1 || true)"
  if [ -t 0 ] && [ -t 1 ] && [ -n "$first" ] && [ "${STARCAST_NO_OPEN:-0}" != 1 ]; then
    printf '    Open the extensions page in %s and the folder now? [Y/n] ' "$first"
    local ans=""
    read -r ans || ans=n
    case "$ans" in
      ""|y|Y|yes|Yes)
        ("$first" "$(ext_page "$first")" >/dev/null 2>&1 &)
        if command -v xdg-open >/dev/null 2>&1; then (xdg-open "$EXT_DIR" >/dev/null 2>&1 &); fi ;;
    esac
  fi
}

# ---- service ----
if ! use_systemd; then
  if [ "$OLD_AUTOSTART" = 1 ]; then
    mkdir -p "$AUTOSTART_DIR"
    cat > "$AUTOSTART_DIR/ytm-starcast.desktop" <<ENTRY
[Desktop Entry]
Type=Application
Name=YTM Starcast
Exec=$APP_DIR/venv/bin/ytm-starcast${EXTRA_ARGS[*]:+ ${EXTRA_ARGS[*]}}
Terminal=false
ENTRY
    note "YTM Starcast starts when you log in, as Pocketfader did."
  fi
  remove_pocketfader
  say "Installed. Start it with:  ytm-starcast${EXTRA_ARGS[*]:+ ${EXTRA_ARGS[*]}}"
  extension_steps
  exit 0
fi

PROGRAM="$APP_DIR/venv/bin/ytm-starcast"
case "$PROGRAM" in *" "*) PROGRAM="\"$PROGRAM\"" ;; esac
mkdir -p "$UNIT_DIR"
cat > "$UNIT" <<EOF
[Unit]
Description=YTM Starcast - YouTube Music companion
After=network-online.target sound.target

[Service]
ExecStart=$PROGRAM${EXTRA_ARGS[*]:+ ${EXTRA_ARGS[*]}}
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
ENABLED_NOW=0
if [ "$CUR_ENABLED" = no ]; then
  note "Start at login stays off, as you had it. To turn it on:  systemctl --user enable ytm-starcast.service"
elif [ -z "$CUR_ENABLED" ] && [ "$OLD_ENABLED" = no ]; then
  note "Start at login stays off, as it was for Pocketfader. To turn it on:  systemctl --user enable ytm-starcast.service"
else
  systemctl --user enable ytm-starcast.service
  ENABLED_NOW=1
fi
# (re)start it on the version just installed. A server that can't start (say, an option it doesn't know) stops within
# a moment, and systemd waits 3 seconds before it tries again: so it has to be running still a few seconds later.
STARTED=0
if systemctl --user restart ytm-starcast.service; then
  sleep 3
  if systemctl --user is-active --quiet ytm-starcast.service; then STARTED=1; fi
fi
if [ "$STARTED" = 0 ]; then
  systemctl --user stop ytm-starcast.service >/dev/null 2>&1 || true      # no endless retries
  if [ "$POCKETFADER" = 1 ]; then
    if [ "$ENABLED_NOW" = 1 ] && [ -z "$CUR_ENABLED" ]; then
      systemctl --user disable ytm-starcast.service >/dev/null 2>&1 || true
    fi
    restore_pocketfader
  fi
  journalctl --user -u ytm-starcast -n 15 --no-pager 2>/dev/null || true
  die "YTM Starcast didn't start (its log is above). Fix what it says, then run this again."
fi
remove_pocketfader                                  # only now that YTM Starcast runs
say "YTM Starcast is running. To see the address to open on your phone:  journalctl --user -u ytm-starcast -n 20 --no-pager"
extension_steps
journalctl --user -u ytm-starcast -n 12 --no-pager 2>/dev/null | grep -E "listening|token|note|audio:" || true
