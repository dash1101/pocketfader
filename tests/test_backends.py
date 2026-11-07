import json
import unittest

from pocketfader.backends.mpris import Mpris, app_id
from pocketfader.backends.pulse import SINK, PulseAudio, parse_sink

from .fakes import FakeRunner

SINK_JSON = {
    "name": "alsa_output.x", "description": "Desk Speakers", "mute": False,
    "volume": {"front-left": {"value": 52429, "value_percent": "80%"},
               "front-right": {"value": 40000, "value_percent": "61%"}},
}

NAMES = {"type": "as", "data": [["org.freedesktop.DBus", "org.mpris.MediaPlayer2.brave.instance123",
                                 "org.mpris.MediaPlayer2.spotify"]]}


def props(status="Playing", title="Song", pos=30_000_000, tid="/t/1"):
    v = lambda t, d: {"type": t, "data": d}  # noqa: E731
    return {"type": "a{sv}", "data": [{
        "PlaybackStatus": v("s", status), "Position": v("x", pos), "Rate": v("d", 1.0),
        "CanPlay": v("b", True), "CanPause": v("b", True), "CanGoNext": v("b", True),
        "CanGoPrevious": v("b", False), "CanSeek": v("b", True),
        "Metadata": v("a{sv}", {"xesam:title": v("s", title), "xesam:artist": v("as", ["Ann", "Bob"]),
                                "mpris:length": v("x", 200_000_000), "mpris:trackid": v("o", tid)}),
    }]}


class RunnerTests(unittest.TestCase):
    def test_dry_run_write_never_executes(self):
        import os, tempfile
        from pocketfader.runner import Runner
        marker = os.path.join(tempfile.mkdtemp(), "should-not-exist")
        r = Runner(dry_run=True)
        self.assertTrue(r.write(["touch", marker]).ok)
        self.assertFalse(os.path.exists(marker))
        self.assertEqual(r.log, [["touch", marker]])

    def test_reads_still_execute_in_dry_run(self):
        from pocketfader.runner import Runner
        self.assertEqual(Runner(dry_run=True).read(["echo", "hi"]).out.strip(), "hi")

    def test_real_write_executes_when_not_dry_run(self):
        import os, tempfile
        from pocketfader.runner import Runner
        marker = os.path.join(tempfile.mkdtemp(), "created")
        Runner().write(["touch", marker])
        self.assertTrue(os.path.exists(marker))


class PulseTests(unittest.TestCase):
    def test_parse_uses_loudest_channel(self):
        st = parse_sink(SINK_JSON)
        self.assertEqual((st["percent"], st["muted"], st["device"]), (80, False, "Desk Speakers"))

    def test_read_follows_default_sink(self):
        r = FakeRunner({"get-default-sink": "alsa_output.x\n", "list sinks": json.dumps([SINK_JSON])})
        st = PulseAudio(r)._read()
        self.assertTrue(st["available"])
        self.assertEqual(st["percent"], 80)

    def test_unavailable_when_no_sink(self):
        self.assertFalse(PulseAudio(FakeRunner())._read()["available"])

    def test_set_volume_targets_default_sink_only_and_clamps(self):
        r = FakeRunner()
        a = PulseAudio(r)
        a.set_percent(42.4)
        a.set_percent(250)
        a.set_percent(-5)
        self.assertEqual(r.log, [["pactl", "set-sink-volume", SINK, "42%"],
                                 ["pactl", "set-sink-volume", SINK, "100%"],
                                 ["pactl", "set-sink-volume", SINK, "0%"]])

    def test_mute(self):
        r = FakeRunner()
        PulseAudio(r).set_mute(True)
        self.assertEqual(r.log, [["pactl", "set-sink-mute", SINK, "1"]])

    def test_never_issues_default_sink_or_stream_moves(self):
        r = FakeRunner()
        a = PulseAudio(r)
        a.set_percent(10); a.set_mute(False)
        forbidden = ("set-default-sink", "move-sink-input", "set-default-source", "load-module")
        self.assertFalse([c for c in r.log if any(f in c for f in forbidden)])


class MprisTests(unittest.TestCase):
    def make(self, canned=None):
        c = {"ListNames": json.dumps(NAMES),
             "brave.instance123 /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties GetAll": json.dumps(props()),
             "spotify /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties GetAll": json.dumps(props("Paused", "Other")),
             "Identity": json.dumps({"type": "v", "data": [{"type": "s", "data": "Pretty Name"}]})}
        c.update(canned or {})
        r = FakeRunner(c)
        m = Mpris(r)
        m.refresh()
        return m, r

    def test_app_id_strips_instance_suffix(self):
        self.assertEqual(app_id("org.mpris.MediaPlayer2.brave.instance3061"), "brave")
        self.assertEqual(app_id("org.mpris.MediaPlayer2.spotify"), "spotify")

    def test_snapshot_prefers_playing_and_parses(self):
        m, _ = self.make()
        s = m.snapshot()
        self.assertEqual((s["app"], s["title"], s["artist"], s["duration"]), ("brave", "Song", "Ann, Bob", 200.0))
        self.assertTrue(s["can"]["next"] and not s["can"]["prev"])
        self.assertEqual({x["app"] for x in s["sessions"]}, {"brave", "spotify"})
        self.assertGreaterEqual(s["position"], 30.0)

    def test_pin_wins_over_playing(self):
        m, _ = self.make()
        m.pin("spotify")
        self.assertEqual(m.snapshot()["app"], "spotify")
        m.pin(None)
        self.assertEqual(m.snapshot()["app"], "brave")

    def test_polling_is_read_only(self):
        m, r = self.make()
        self.assertEqual(r.log, [])  # refresh() must not have written anything

    def test_commands_are_only_sent_on_request_and_hit_the_right_player(self):
        m, r = self.make()
        m.play_pause()
        m.next()
        m.prev()
        members = [c[c.index("org.mpris.MediaPlayer2.Player") + 1] for c in r.log]
        self.assertEqual(members, ["PlayPause", "Next", "Previous"])
        self.assertTrue(all(c[4] == "org.mpris.MediaPlayer2.brave.instance123" for c in r.log))

    def test_seek_uses_set_position_with_trackid(self):
        m, r = self.make()
        self.assertTrue(m.seek(12.5))
        self.assertEqual(r.log[-1][-4:], ["SetPosition", "ox", "/t/1", "12500000"])

    def test_seek_falls_back_to_relative_without_trackid(self):
        c = {"brave.instance123 /org/mpris/MediaPlayer2 org.freedesktop.DBus.Properties GetAll":
             json.dumps(props(tid="/org/mpris/MediaPlayer2/TrackList/NoTrack", pos=10_000_000))}
        m, r = self.make(c)
        m.seek(50)
        self.assertEqual(r.log[-1][-3:-1], ["Seek", "x"])
        self.assertGreater(int(r.log[-1][-1]), 30_000_000)

    def test_no_players(self):
        m, r = self.make({"ListNames": json.dumps({"type": "as", "data": [["org.freedesktop.DBus"]]})})
        self.assertFalse(m.snapshot()["available"])
        self.assertFalse(m.play_pause())
        self.assertEqual(r.log, [])


if __name__ == "__main__":
    unittest.main()
