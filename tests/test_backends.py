import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from starcast.backends.eq_pipewire import SINK_NAME, BuiltinEQ, chain_conf
from starcast.config import JsonFile
from starcast.backends.mpris import Mpris, app_id
from starcast.backends.pulse import SINK, PulseAudio, classify_sink, parse_sink

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
        from starcast.runner import Runner
        marker = os.path.join(tempfile.mkdtemp(), "should-not-exist")
        r = Runner(dry_run=True)
        self.assertTrue(r.write(["touch", marker]).ok)
        self.assertFalse(os.path.exists(marker))
        self.assertEqual(r.log, [["touch", marker]])

    def test_reads_still_execute_in_dry_run(self):
        from starcast.runner import Runner
        self.assertEqual(Runner(dry_run=True).read(["echo", "hi"]).out.strip(), "hi")

    def test_real_write_executes_when_not_dry_run(self):
        import os, tempfile
        from starcast.runner import Runner
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

    def test_eq_sink_is_named_after_the_device_it_feeds(self):
        eq = {**SINK_JSON, "name": "pocketfader_eq", "description": "YTM Starcast EQ"}
        r = FakeRunner({"get-default-sink": "pocketfader_eq\n", "list sinks": json.dumps([SINK_JSON, eq])})
        a = PulseAudio(r)
        a.hidden_sinks.add("pocketfader_eq")
        self.assertEqual(a._read()["device"], "YTM Starcast EQ")           # no alias yet: honest fallback
        a.aliases["pocketfader_eq"] = "alsa_output.x"
        self.assertEqual(a._read()["device"], "Desk Speakers")
        outs = a.outputs()
        self.assertEqual([(o["name"], o["default"]) for o in outs], [("alsa_output.x", True)])

    def test_eq_output_is_called_starcast_but_keeps_its_node_name(self):
        conf = chain_conf("alsa_output.x", {"bass": 3})
        self.assertIn('node.description = "YTM Starcast EQ"', conf)
        self.assertIn('node.name = "pocketfader_eq"', conf)          # kept, so an EQ left running stays recognised
        self.assertNotIn("Pocketfader", conf)
        self.assertEqual(classify_sink({"name": "pocketfader_eq_out", "properties": {}}), "virtual")

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



class _Proc:
    """A stand-in for the filter-chain process: nothing is started."""

    def __init__(self):
        self.alive = True
        self.pid = 999999

    def poll(self):
        return None if self.alive else 0

    def terminate(self):
        self.alive = False

    kill = terminate

    def wait(self, timeout=None):
        return 0


class _EQAudio:
    """The audio backend the EQ talks to, in memory: a real device "speakers" and, once built, the EQ sink."""

    def __init__(self):
        self.default, self.calls, self.eq_up = "speakers", [], False
        self.hidden_sinks, self.aliases = set(), {}

    def default_name(self):
        return self.default

    def outputs(self, include_hidden=False):
        return [{"name": "speakers"}] + ([{"name": SINK_NAME}] if self.eq_up else [])

    def set_output(self, name):
        self.calls.append(("set_output", name))
        self.default = name
        return True

    def set_output_volume(self, name, p):
        self.calls.append(("set_output_volume", name, p))

    def set_output_mute(self, name, m):
        self.calls.append(("set_output_mute", name, m))


class _EQ(BuiltinEQ):
    """The built-in EQ with the PipeWire parts replaced: builds of the chain are counted, nothing is run."""
    restore_delay = watch_every = 0.05

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.spawns = []
        self._spawn_lock = threading.Lock()

    def available(self):
        return True, ""

    def _kill_stale(self):
        pass

    def _spawn(self, target):
        with self._spawn_lock:
            self._kill_chain()
            self.spawns.append(target)
            self._proc = _Proc()
            self.audio.eq_up = True
            return True

    def _wait_sink(self, timeout=4.0):
        return True


class BuiltinEQStartTests(unittest.TestCase):
    """An EQ that was on before a restart is put back once: building the chain twice would kill the first one (a
    gap in the sound)."""

    def make(self, enabled=True):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = JsonFile(Path(tmp.name) / "eq.json")
        store.merge({"enabled": enabled, "target": "speakers"})
        runner = FakeRunner()
        runner.dry_run = False          # the watchdog only acts for real; FakeRunner still never starts a process
        eq = _EQ(runner, _EQAudio(), store, tmp.name)
        self.addCleanup(eq.stop)
        return eq

    def test_put_back_once_after_a_start(self):
        eq = self.make()
        eq.start()
        time.sleep(0.6)                 # the restore, and several watchdog checks after it
        self.assertEqual(eq.spawns, ["speakers"])
        self.assertEqual([c for c in eq.audio.calls if c[0] == "set_output"], [("set_output", SINK_NAME)])
        self.assertEqual(eq.audio.default, SINK_NAME)
        self.assertEqual(eq.snapshot()["running"], True)
        # the chain dies later: the watchdog still brings it back
        eq._proc.alive = False
        time.sleep(0.4)
        self.assertEqual(eq.spawns, ["speakers", "speakers"])

    def test_off_means_nothing_is_built(self):
        eq = self.make(enabled=False)
        eq.start()
        time.sleep(0.3)
        self.assertEqual((eq.spawns, eq.audio.calls), ([], []))

    def test_stopped_before_the_restore_builds_nothing(self):
        eq = self.make()
        eq.restore_delay = 0.3
        eq.start()
        eq.stop()
        time.sleep(0.5)
        self.assertEqual(eq.spawns, [])

    def test_switched_off_before_the_restore_stays_off(self):
        eq = self.make()
        eq.restore_delay = 0.3
        eq.start()
        eq.disable()
        time.sleep(0.5)
        self.assertEqual(eq.spawns, [])
        self.assertFalse(eq.enabled)


if __name__ == "__main__":
    unittest.main()
