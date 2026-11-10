import threading
import time
import unittest

from pocketfader.bridge import Bridge


class BridgeStateTests(unittest.TestCase):
    def test_position_is_brought_up_to_date_while_playing(self):
        b = Bridge()
        b.push_state({"now": {"playing": True, "position": 10.0}, "queue": []})
        b._state_at -= 0.6
        self.assertAlmostEqual(b.get_state()["now"]["position"], 10.6, delta=0.1)

    def test_paused_position_is_left_alone_and_age_is_capped(self):
        b = Bridge()
        b.push_state({"now": {"playing": False, "position": 10.0}})
        b._state_at -= 30
        self.assertEqual(b.get_state()["now"]["position"], 10.0)
        b.push_state({"now": {"playing": True, "position": 10.0}})
        b._state_at -= 30
        self.assertAlmostEqual(b.get_state()["now"]["position"], 15.0, delta=0.1)

    def test_a_quick_update_keeps_the_queue(self):
        b = Bridge()
        b.push_state({"now": {"playing": True, "position": 1.0}, "queue": [{"videoId": "a"}]})
        b.push_state({"now": {"playing": True, "position": 1.5}, "ts": 1, "partial": True})
        st = b.get_state()
        self.assertEqual(st["queue"], [{"videoId": "a"}])
        self.assertGreaterEqual(st["now"]["position"], 1.5)


class BridgeTabsTests(unittest.TestCase):
    """Several YouTube Music tabs: the bridge follows one, and commands only go to that one."""

    def test_the_first_tab_is_followed_and_a_paused_second_tab_is_ignored(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": True}})
        b.push_state({"instance": "B", "now": {"title": "b", "playing": False}})
        self.assertEqual(b.get_state()["now"]["title"], "a")
        self.assertNotIn("instance", b.get_state())

    def test_a_tab_that_starts_playing_takes_over(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": False}})
        b.push_state({"instance": "B", "now": {"title": "b", "playing": True}})
        self.assertEqual(b.get_state()["now"]["title"], "b")
        # and the old one, still paused, can't take it back
        b.push_state({"instance": "A", "now": {"title": "a", "playing": False}})
        self.assertEqual(b.get_state()["now"]["title"], "b")

    def test_a_closed_tab_hands_over_to_the_one_still_open(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": True}})
        b._seen["A"] -= 100           # tab A went quiet long ago (closed)
        b.push_state({"instance": "B", "now": {"title": "b", "playing": False}})
        self.assertEqual(b.get_state()["now"]["title"], "b")

    def test_commands_go_only_to_the_followed_tab(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"playing": True}})
        self.assertIsNone(b.poll(timeout=0.05, instance="B"))   # nothing pending
        got = {}
        t = threading.Thread(target=lambda: got.update(r=b.request({"type": "x"}, timeout=2)))
        t.start()
        time.sleep(0.05)
        self.assertIsNone(b.poll(timeout=0.2, instance="B"))    # the other tab never gets it
        cmd = b.poll(timeout=1, instance="A")
        self.assertEqual(cmd["type"], "x")
        b.deliver_result(cmd["id"], {"ok": True})
        t.join(2)
        self.assertEqual(got["r"], {"ok": True})

    def test_a_command_nobody_picked_up_is_dropped_when_it_times_out(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {}})
        self.assertEqual(b.request({"type": "transport", "action": "next"}, timeout=0.05), {"error": "extension_timeout"})
        self.assertIsNone(b.poll(timeout=0.05, instance="A"))   # it doesn't run later, when the tab comes back

    def test_older_extensions_without_a_tab_name_still_work(self):
        b = Bridge()
        b.push_state({"now": {"title": "a", "playing": True}})
        got = {}
        t = threading.Thread(target=lambda: got.update(r=b.request({"type": "x"}, timeout=2)))
        t.start()
        cmd = b.poll(timeout=1)
        b.deliver_result(cmd["id"], "done")
        t.join(2)
        self.assertEqual(got["r"], "done")


if __name__ == "__main__":
    unittest.main()
