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
        for k in [k for k in self._seen if not self._alive(k, now)]:
            del self._seen[k]
        return take

    # ---- extension side -------------------------------------------------
    def poll(self, timeout=POLL_HOLD, instance=None):
        """Long-poll: block until a command is queued for this tab, or return None on timeout."""
        deadline = time.time() + timeout
        with self._cond:
            now = time.time()
            self._seen[instance] = now
            if instance == self._active or self._active is None or not self._alive(self._active, now):
                self._last_seen = now
            while True:
                if self._pending and (instance == self._active or self._active is None):
                    return self._pending.pop(0)
                remaining = deadline - time.time()
                if remaining <= 0:
                    return None
                self._cond.wait(remaining)
                self._seen[instance] = time.time()

    def deliver_result(self, cmd_id, data):
        with self._lock:
            self._last_seen = time.time()
            slot = self._results.get(cmd_id)
            if slot is not None:
                slot["data"] = data
                slot["event"].set()

    def push_state(self, state):
        if not isinstance(state, dict):
            return
        with self._lock:
            now = time.time()
            inst = state.get("instance")
            self._seen[inst] = now
            playing = bool((state.get("now") or {}).get("playing"))
            if not self._follow(inst, playing, now):
                return                     # another tab's state: not the one being followed
            self._last_seen = now
            if state.get("partial") and self._state:      # a quick "now playing" update: keep the queue we have
                state = {**self._state, "now": state.get("now", self._state.get("now")), "ts": state.get("ts")}
            if "now" in state:
                self._active_playing = bool((state.get("now") or {}).get("playing"))
            self._state = state
            self._state_at = now

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
