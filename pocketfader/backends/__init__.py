"""Backend selection. Imports are lazy so a Linux box never touches Windows-only modules
(and vice versa): `--audio voicemeeter` is the only thing that loads the Voicemeeter DLL."""
import sys

from ..runner import Runner
from .base import NullAudio, NullEngine, NullMedia

AUDIO_CHOICES = ("auto", "pulse", "windows", "voicemeeter", "none")
MEDIA_CHOICES = ("auto", "mpris", "gsmtc", "none")


def make_audio_and_engine(kind, runner: Runner):
    """Returns (audio, engine). Voicemeeter = Windows volume + the Voicemeeter engine."""
    if kind == "auto":
        kind = "windows" if sys.platform == "win32" else "pulse"
    if kind == "none":
        return NullAudio(), NullEngine()
    if kind == "pulse":
        from .pulse import PulseAudio
        return PulseAudio(runner), NullEngine()
    if kind in ("windows", "voicemeeter"):
        if sys.platform != "win32":
            raise SystemExit(f"--audio {kind} only works on Windows.")
        from .windows.winaudio import WinVolume
        if kind == "windows":
            return WinVolume(), NullEngine()
        from .windows.voicemeeter import Voicemeeter
        return WinVolume(), Voicemeeter()
    raise SystemExit(f"unknown --audio {kind!r}")


def make_media(kind, runner: Runner):
    if kind == "auto":
        kind = "gsmtc" if sys.platform == "win32" else "mpris"
    if kind == "none":
        return NullMedia()
    if kind == "mpris":
        from .mpris import Mpris
        return Mpris(Runner(dry_run=runner.dry_run, timeout=2.0))
    if kind == "gsmtc":
        if sys.platform != "win32":
            raise SystemExit("--media gsmtc only works on Windows.")
        from .windows.gsmtc import Media
        return Media()
    raise SystemExit(f"unknown --media {kind!r}")
