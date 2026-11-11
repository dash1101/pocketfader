"""Discord status: shows the song playing in YouTube Music as your Discord "Listening to" card.

A standard-library port of YTM Beacon's presence engine and Discord IPC client, fed by the browser extension's
existing bridge. Nothing here opens a network port; it only talks to the Discord desktop app on this computer
through Discord's local IPC socket (a named pipe on Windows).

The server's API is one object:

    from starcast.discord import Presence, SettingsError, beacon_config_path

    p = Presence(settings_path,             # <settings dir>/discord.json (same keys as YTM Beacon's config.json)
                 client_id=None,            # built-in Discord application id 1567329407901634670 when None
                 asset_base=None,           # base URL of logo.png / play.png / pause.png (default: this
                                            #   release's tag, or main, under dash1101/ytm-starcast assets/discord/)
                 dial=None,                 # dial(client_id) -> connection; tests pass a fake. None = real Discord
                 clock=None,                # () -> seconds since the epoch (time.time); tests pass a fake
                 dry_run=False,             # True: never connects to Discord (status still works) and writes
                                            #   no file (settings changes are kept in memory only)
                 import_beacon_from=None)   # path of YTM Beacon's config.json: imported once, when
                                            #   settings_path does not exist yet (pass beacon_config_path())
    p.start()                               # engine thread: connect, keep Discord up to date, reconnect
    p.stop()                                # clears the card, disconnects, joins the thread (restartable)
    p.running                               # whether the engine thread runs

    # fed by the extension bridge (every tab, not only the one the remote follows)
    p.update_from_bridge(instance, now, agent=None, ts=None)   # every POST /ext/state (alias: p.feed); now=None
                                            #   is ignored; agent: agent_from_user_agent(User-Agent); ts: body["ts"]
    p.touch(instance)                       # every GET /ext/poll (?i=): the tab is alive. Needed: a paused tab
                                            #   pushes nothing, and a song not heard of for 90 s is dropped
    p.clear(instance)                       # POST /ext/bye (alias: p.bye)

    # status and switches
    p.status()          # dict with YTM Beacon's raw /v1/status keys: feed it to starcast.beacon.normalize()
                        #   (contains paths and the application id: never send it to the apps as is)
    p.enabled                               # the on/off switch (parts.discord), from memory
    p.set_enabled(on)                       # switch it (saved); a timed hide is left alone
    p.visibility_state()                    # {"enabled", "snoozeSec", "visible"}
    p.visibility(action, minutes=0)         # "hide" (minutes > 0: timed, not saved) | "show" | "toggle"
    p.get_config()                          # all 11 settings
    p.set_config(partial)                   # dict or raw JSON text; only the keys given change; all or nothing
    p.import_beacon_config(dict_or_path)    # take over YTM Beacon's settings (missing keys -> defaults)
    p.test()                                # a test song on Discord for 30 s
    p.preview()                             # the card Discord would show now (dict) or None
    p.effective_client_id()
    p.settings_error                        # why the settings file could not be used (defaults in use), or None
    p.imported_from                         # the YTM Beacon file imported at start, or None

Errors: SettingsError (a ValueError; plain-language text) for invalid input, nothing changed; OSError when the
settings file cannot be written, nothing changed either. Settings `enabled` is the one on/off switch
(the server's parts.discord): visibility("show") turns it on, visibility("hide") turns it off.
The settings file is written atomically (a temporary file, then a rename) with owner-only permissions.
Every method is safe to call from any thread.
"""
from .build import AssetURLs, asset_base_for, release_tag
from .ipc import APIError, HandshakeError, IPCError, NotFoundError
from .presence import Presence, agent_from_user_agent
from .settings import (DEFAULT_CLIENT_ID, KEYS, SettingsError, beacon_config_path, default_config)

__all__ = [
    "Presence", "SettingsError", "beacon_config_path", "default_config", "DEFAULT_CLIENT_ID", "KEYS",
    "agent_from_user_agent", "AssetURLs", "asset_base_for", "release_tag",
    "IPCError", "APIError", "HandshakeError", "NotFoundError",
]
