"""Relay between the web/native client and the Brave extension that drives YouTube Music.

The extension can't accept inbound connections (it's a content script), so it is the
*client*: it long-polls `poll()` for commands and pushes now-playing/queue snapshots via
`push_state()`. The phone/tablet app is the other client: `request()` enqueues a command and blocks
until the extension posts a result (or times out).

More than one YouTube Music tab can be open. Each tab names itself (an "instance" id) and the bridge
follows one of them: the tab that is playing, or the one it already follows while that one is alive.
Only that tab's state is shown and only that tab receives commands, so two tabs never fight.

All same-machine loopback -- this does NOT touch the Tailscale/public-IP posture; the
extension and this server run on the same PC as Brave.
"""
import threading
import time
import uuid


class Bridge:
    # Extension re-polls at least this often even when idle (see POLL_HOLD), so a longer
    # gap than this means the extension/Brave is gone.
    POLL_HOLD = 25.0
    STALE_AFTER = 40.0

    def __init__(self):
        self._lock = threading.Lock()
        self._cond = threading.Condition(self._lock)
        self._pending = []      # commands waiting for the extension to pick up
        self._results = {}      # cmd_id -> {"event": Event, "data": ...}
        self._state = {}        # latest now-playing / queue snapshot from the followed tab
        self._state_at = 0.0
        self._last_seen = 0.0   # last time the followed tab polled or pushed
        self._seen = {}         # instance -> last time it polled or pushed
        self._active = None     # the tab being followed (None: older extensions, which don't name themselves)
        self._active_playing = False
        self._wake = 0          # bumped to answer every waiting poll at once (with no command)
        self._reported = set()  # tabs that pushed their state since this server started
        self._asked = set()     # tabs asked to send everything again (once each)

    @property
    def followed(self):
        """The id of the tab being followed (None: none yet, or an older extension that doesn't name its tab)."""
        return self._active

    def _alive(self, inst, now):
        return now - self._seen.get(inst, 0.0) < self.STALE_AFTER

    def _follow(self, inst, playing, now):
        """Decide whether this tab becomes (or stays) the one followed. Caller holds the lock."""
        if inst == self._active:
            return True
        cur_alive = self._active is not None and self._alive(self._active, now)
        if self._active is None and not self._state:
            take = True
        elif not cur_alive:
            take = True
        else:
            take = bool(playing) and not self._active_playing   # a tab that starts playing wins
        if take:
            self._active = inst
            self._cond.notify_all()       # its poll may now pick up waiting commands
        self._prune(now)
        return take

    def _prune(self, now):
        """Forget tabs not heard of for a while. Caller holds the lock."""
        for k in [k for k in self._seen if not self._alive(k, now)]:
            del self._seen[k]
        self._reported.intersection_update(self._seen)
        self._asked.intersection_update(self._seen)

    # ---- extension side -------------------------------------------------
    def poll(self, timeout=POLL_HOLD, instance=None):
        """Long-poll: block until a command is queued for this tab, or return None on timeout
        (or sooner when wake_polls() is called, so the tab hears about changed switches at once)."""
        deadline = time.time() + timeout
        with self._cond:
            now = time.time()
            self._seen[instance] = now
            wake = self._wake
            if instance == self._active or self._active is None or not self._alive(self._active, now):
                self._last_seen = now
            while True:
                # (a tab that said goodbye while its poll was waiting takes nothing: it would never run it)
                if self._pending and (instance == self._active or self._active is None) and instance in self._seen:
                    return self._pending.pop(0)
                remaining = deadline - time.time()
                if remaining <= 0 or self._wake != wake:
                    return None
                self._cond.wait(remaining)
                if instance in self._seen:          # not if the tab said goodbye meanwhile
                    self._seen[instance] = time.time()

    def claim_resend(self, instance):
        """True once for a tab that hasn't pushed its state since this server started: the server was restarted while
        the tab stayed open. A paused tab pushes nothing until something changes, so it is asked (once) to send
        everything again. Always False for extensions that don't name their tab (they don't know the request)."""
        if not isinstance(instance, str) or not instance:
            return False
        with self._lock:
            self._prune(time.time())
            if instance in self._reported or instance in self._asked:
                return False
            self._asked.add(instance)
            return True

    def wake_polls(self):
        """Answer every waiting poll now, without a command."""
        with self._cond:
            self._wake += 1
            self._cond.notify_all()

    def bye(self, instance):
        """The tab is closing or navigating away: forget it at once. When it was the followed tab, its state goes too
        and nothing is followed until a tab reports again (the next one that does is followed). The bridge stays
        connected only while another tab is still around; otherwise it is offline right away instead of 40 s later.
        Commands that are already queued stay queued (and still expire as usual). True if it was the followed tab."""
        with self._cond:
            self._seen.pop(instance, None)
            self._reported.discard(instance)
            self._asked.discard(instance)
            if instance is None or instance != self._active:
                return False
            now = time.time()
            self._active, self._active_playing = None, False
            self._state, self._state_at = {}, 0.0
            self._last_seen = max((t for t in self._seen.values() if now - t < self.STALE_AFTER), default=0.0)
            self._wake += 1                 # other tabs: answer their polls, so they can report themselves now
            self._cond.notify_all()
            return True

    def deliver_result(self, cmd_id, data):
        with self._lock:
            self._last_seen = time.time()
            slot = self._results.get(cmd_id)
            if slot is not None:
                slot["data"] = data
                slot["event"].set()

    def push_state(self, state):
        """Keep a tab's now-playing/queue snapshot when it is the followed tab. True if it was."""
        if not isinstance(state, dict):
            return False
        with self._lock:
            now = time.time()
            inst = state.get("instance")
            self._seen[inst] = now
            self._reported.add(inst)
            playing = bool((state.get("now") or {}).get("playing"))
            if not self._follow(inst, playing, now):
                return False               # another tab's state: not the one being followed
            self._last_seen = now
            if state.get("partial") and self._state:      # a quick "now playing" update: keep the queue we have
                state = {**self._state, "now": state.get("now", self._state.get("now")), "ts": state.get("ts")}
            if "now" in state:
                self._active_playing = bool((state.get("now") or {}).get("playing"))
            self._state = state
            self._state_at = now
            return True

    # ---- client side ----------------------------------------------------
    def request(self, command, timeout=10.0):
        """Enqueue a command for the extension and wait for its result."""
        if not self.connected:
            return {"error": "extension_offline"}
        cmd_id = uuid.uuid4().hex
        event = threading.Event()
        with self._cond:
            self._results[cmd_id] = {"event": event, "data": None}
            self._pending.append({"id": cmd_id, **command})
            self._cond.notify_all()
        if event.wait(timeout):
            with self._lock:
                return self._results.pop(cmd_id, {}).get("data")
        with self._lock:
            self._results.pop(cmd_id, None)
            # nobody is waiting for it any more: it must not run later, when the tab comes back
            # (three taps on "next" during a reload would otherwise skip three songs a minute later)
            self._pending = [c for c in self._pending if c.get("id") != cmd_id]
        return {"error": "extension_timeout"}

    def get_state(self):
        with self._lock:
            snap = dict(self._state)
            at = self._state_at
        snap.pop("instance", None)
        now = snap.get("now")
        if isinstance(now, dict) and now.get("playing") and isinstance(now.get("position"), (int, float)) and at:
            # the position was sampled when the extension last pushed; bring it up to date so
            # clients (and the lyrics) aren't up to a second behind
            snap["now"] = {**now, "position": now["position"] + max(0.0, min(5.0, time.time() - at))}
        snap["connected"] = self.connected
        return snap

    @property
    def connected(self):
        return (time.time() - self._last_seen) < self.STALE_AFTER
