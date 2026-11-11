import threading
import time
import unittest

from starcast.bridge import Bridge


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


class BridgeByeTests(unittest.TestCase):
    """A tab says goodbye when it closes or navigates away (POST /ext/bye), so it is forgotten at once."""

    def test_push_state_says_whether_the_tab_is_followed(self):
        b = Bridge()
        self.assertTrue(b.push_state({"instance": "A", "now": {"playing": True}}))
        self.assertFalse(b.push_state({"instance": "B", "now": {"playing": False}}))
        self.assertFalse(b.push_state("nope"))
        self.assertEqual(b.followed, "A")

    def test_goodbye_from_the_followed_tab_disconnects_at_once(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": True}, "queue": [{"videoId": "x"}]})
        self.assertTrue(b.connected)
        self.assertTrue(b.bye("A"))
        self.assertFalse(b.connected)                      # not 40 s later
        self.assertIsNone(b.followed)
        st = b.get_state()
        self.assertNotIn("now", st)
        self.assertNotIn("queue", st)
        self.assertEqual(b.request({"type": "x"}, timeout=0.05), {"error": "extension_offline"})
        # the next tab that reports is followed
        self.assertTrue(b.push_state({"instance": "B", "now": {"title": "b", "playing": False}}))
        self.assertEqual((b.followed, b.get_state()["now"]["title"], b.connected), ("B", "b", True))

    def test_another_tab_still_open_takes_over(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": True}})
        self.assertIsNone(b.poll(timeout=0.01, instance="B"))    # tab B is open (it polls) but paused
        self.assertTrue(b.bye("A"))
        self.assertTrue(b.connected)                       # B is still around
        got = {}
        t = threading.Thread(target=lambda: got.update(r=b.request({"type": "x"}, timeout=2)))
        t.start()
        cmd = b.poll(timeout=1, instance="B")              # commands now reach B
        self.assertEqual(cmd["type"], "x")
        b.deliver_result(cmd["id"], {"ok": True})
        t.join(2)
        self.assertEqual(got["r"], {"ok": True})

    def test_goodbye_from_a_tab_that_isnt_followed_changes_nothing(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": True}})
        b.push_state({"instance": "B", "now": {"title": "b", "playing": False}})
        self.assertFalse(b.bye("B"))
        self.assertFalse(b.bye("never-seen"))
        self.assertFalse(b.bye(None))
        self.assertNotIn("B", b._seen)
        self.assertEqual((b.followed, b.get_state()["now"]["title"], b.connected), ("A", "a", True))

    def test_goodbye_answers_the_other_tabs_waiting_polls(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"playing": True}})
        got = {}
        t = threading.Thread(target=lambda: got.update(r=b.poll(timeout=5, instance="B"), at=time.time()))
        start = time.time()
        t.start()
        time.sleep(0.05)
        b.bye("A")
        t.join(2)
        self.assertFalse(t.is_alive())
        self.assertIsNone(got["r"])                        # no command, just "report yourself now"
        self.assertLess(got["at"] - start, 1.0)

    def test_a_tab_that_said_goodbye_takes_no_command_while_its_poll_still_waits(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"playing": True}})
        self.assertIsNone(b.poll(timeout=0.01, instance="B"))
        got = {}
        t = threading.Thread(target=lambda: got.update(r=b.poll(timeout=0.4, instance="A")))
        t.start()
        time.sleep(0.05)
        # A says goodbye and a command is queued in the same moment, before A's waiting poll runs again
        with b._cond:
            b._seen.pop("A")
            b._active, b._state = None, {}
            b._pending.append({"id": "c1", "type": "x"})
            b._cond.notify_all()
        t.join(2)
        self.assertIsNone(got["r"])                        # A's dead poll didn't swallow it
        self.assertEqual(b.poll(timeout=0.2, instance="B")["id"], "c1")
        self.assertNotIn("A", b._seen)                     # and it wasn't counted as alive again

    def test_wake_polls_answers_waiting_polls_without_a_command(self):
        b = Bridge()
        got = []
        ts = [threading.Thread(target=lambda i=i: got.append(b.poll(timeout=5, instance=i))) for i in ("A", "B")]
        start = time.time()
        for t in ts:
            t.start()
        time.sleep(0.05)
        b.wake_polls()
        for t in ts:
            t.join(2)
        self.assertEqual(got, [None, None])
        self.assertLess(time.time() - start, 1.5)
        # a poll that starts afterwards waits as usual
        t0 = time.time()
        self.assertIsNone(b.poll(timeout=0.2, instance="A"))
        self.assertGreaterEqual(time.time() - t0, 0.18)


class ResendTests(unittest.TestCase):
    """After a restart, a tab still open (a paused one reports nothing until something changes) is asked once to send
    everything again."""

    def test_a_tab_not_heard_of_since_the_start_is_asked_once(self):
        b = Bridge()
        self.assertTrue(b.claim_resend("A"))
        self.assertIsNone(b.poll(timeout=0, instance="A"))
        self.assertFalse(b.claim_resend("A"))           # once
        self.assertFalse(b.claim_resend("A"))

    def test_a_tab_that_reported_is_not_asked(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": False}})
        b.push_state({"instance": "B", "now": {"title": "b", "playing": False}})      # not followed, but reported
        self.assertFalse(b.claim_resend("A"))
        self.assertFalse(b.claim_resend("B"))

    def test_extensions_that_name_no_tab_are_never_asked(self):
        b = Bridge()
        self.assertFalse(b.claim_resend(None))
        self.assertFalse(b.claim_resend(""))

    def test_a_tab_forgotten_is_asked_again(self):
        b = Bridge()
        b.push_state({"instance": "A", "now": {"title": "a", "playing": False}})
        b.bye("A")                                      # closed or navigated away; back from the back/forward cache
        self.assertTrue(b.claim_resend("A"))
        b.poll(timeout=0, instance="A")
        b._seen["A"] -= 100                             # not heard of for a long time
        self.assertTrue(b.claim_resend("A"))
        self.assertEqual((b._asked, b._reported), ({"A"}, set()))


class SlowCommandTests(unittest.TestCase):
    def test_paging_commands_get_more_time_than_a_button_press(self):
        from starcast.server import App
        seen = []

        class B:
            connected = True
            def request(self, body, timeout=10.0):
                seen.append((body["type"], timeout))
                return {"ok": True}

        app = App.__new__(App)
        app.bridge = B()
        app.ytm_command({"type": "transport", "action": "next"})
        app.ytm_command({"type": "playlistItems", "playlistId": "PLx"})
        app.ytm_command({"type": "playlistItems", "playlistId": "PLx", "limit": 3000})
        app.ytm_command({"type": "home"})
        d = dict(seen[:2])
        self.assertEqual(d["transport"], 10.0)
        self.assertEqual(seen[1][1], 10.0)              # the first page only: quick
        self.assertGreater(seen[2][1], 60.0)            # a whole playlist: long
        self.assertGreater(seen[3][1], 30.0)


if __name__ == "__main__":
    unittest.main()
