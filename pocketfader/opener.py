"""Open YouTube Music in the computer's browser (so a phone can start music when no tab is open).

Only ever opens music.youtube.com: the address is built here from validated ids, never taken from the caller.
"""
import os
import platform
import re
import shutil
import subprocess

_ID = re.compile(r"^[A-Za-z0-9_-]{5,80}$")
HOME = "https://music.youtube.com/"


def valid_id(v):
    return isinstance(v, str) and bool(_ID.match(v))


def music_url(video_id=None, playlist_id=None):
    """The music.youtube.com address for a song (optionally inside a playlist), a playlist, or the home page."""
    if video_id is not None and not valid_id(video_id):
        raise ValueError("bad video id")
    if playlist_id is not None and not valid_id(playlist_id):
        raise ValueError("bad playlist id")
    if video_id:
        return f"{HOME}watch?v={video_id}" + (f"&list={playlist_id}" if playlist_id else "")
    if playlist_id:
        return f"{HOME}playlist?list={playlist_id}"
    return HOME


class Opener:
    def __init__(self, dry_run=False, system=None, popen=None):
        self.dry_run = dry_run
        self.system = (system or platform.system()).lower()
        self._popen = popen or subprocess.Popen
        self.opened = []            # what was opened (dry run / tests)

    def open(self, url):
        if not url.startswith(HOME):
            return False            # belt and braces: nothing else is ever opened
        self.opened.append(url)
        if self.dry_run:
            return True
        try:
            if self.system == "windows":
                os.startfile(url)    # noqa: S606  (Windows only; the url was built from validated ids)
            else:
                exe = shutil.which("open" if self.system == "darwin" else "xdg-open")
                if not exe:
                    return False
                self._popen([exe, url], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)
            return True
        except OSError:
            return False
