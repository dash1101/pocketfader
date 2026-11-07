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


if __name__ == "__main__":
    unittest.main()
