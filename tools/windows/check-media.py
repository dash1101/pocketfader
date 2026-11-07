"""Windows diagnostic: list the media sessions Windows exposes and what each one allows.

Use it to check whether a Bluetooth-connected phone shows up as a controllable media
session (it must surface through GSMTC and honour AVRCP commands), and to find its app id
for pinning in Pocketfader's session picker. Start playback on the phone first.

    python tools/windows/check-media.py            # report only
    python tools/windows/check-media.py --skip     # also try skipping a track (audible!)

Play/pause/next/prev are normally supported. Seek is the least-supported AVRCP command and
may be missing; the app hides the seek bar when the session says it is unavailable.
"""
import asyncio
import sys

from winrt.windows.media.control import (
    GlobalSystemMediaTransportControlsSessionManager as MediaManager,
)

STATUS = {0: "Closed", 1: "Opened", 2: "Changing", 3: "Stopped", 4: "Playing", 5: "Paused"}


async def main():
    mgr = await MediaManager.request_async()
    sessions = list(mgr.get_sessions())

    if not sessions:
        print("No media sessions at all.")
        print("If the phone is paired and playing, it is NOT surfacing as a media session")
        print("-- transport controls cannot work; volume and the meter still will.")
        return

    print(f"{len(sessions)} media session(s):\n")
    for s in sessions:
        pb = s.get_playback_info()
        c = pb.controls
        try:
            p = await s.try_get_media_properties_async()
            title, artist = p.title, p.artist
        except OSError:
            title = artist = "(unavailable)"
        tl = s.get_timeline_properties()
        print(f"  app    : {s.source_app_user_model_id}")
        print(f"  status : {STATUS.get(pb.playback_status, pb.playback_status)}")
        print(f"  track  : {title!r} — {artist!r}")
        print(f"  pos    : {tl.position} / {tl.end_time}")
        print(f"  can    : play={c.is_play_enabled} pause={c.is_pause_enabled} "
              f"next={c.is_next_enabled} prev={c.is_previous_enabled} "
              f"seek={c.is_playback_position_enabled}")
        print()

    print("Is one of the above your PHONE (not a browser/desktop app)?")
    print("  yes -> transport controls will work for it.")
    print("  no  -> the phone isn't exposing AVRCP metadata to Windows.")

    if "--skip" in sys.argv:
        playing = [s for s in sessions if s.get_playback_info().playback_status == 4]
        target = playing[0] if playing else sessions[0]
        print(f"\nSkipping next on {target.source_app_user_model_id} ...")
        await target.try_skip_next_async()
        await asyncio.sleep(1.5)
        try:
            p = await target.try_get_media_properties_async()
            print(f"  now playing: {p.title!r} — {p.artist!r}")
        except OSError:
            print("  (metadata unavailable)")
        print("  If the track changed ON THE PHONE, AVRCP transport is confirmed working.")


asyncio.run(main())
