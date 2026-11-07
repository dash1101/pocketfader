"""Relay between the web/native client and the Brave extension that drives YouTube Music.

The extension can't accept inbound connections (it's a content script), so it is the
*client*: it long-polls `poll()` for commands and pushes now-playing/queue snapshots via
`push_state()`. The phone/tablet app is the other client: `request()` enqueues a command and blocks
until the extension posts a result (or times out).

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
        self._state = {}        # latest now-playing / queue snapshot from the extension
        self._last_seen = 0.0   # last time the extension polled or pushed

    # ---- extension side -------------------------------------------------
    def poll(self, timeout=POLL_HOLD):
        """Long-poll: block until a command is queued, or return None on timeout."""
        deadline = time.time() + timeout
        with self._cond:
            self._last_seen = time.time()
            while not self._pending:
                remaining = deadline - time.time()
                if remaining <= 0:
                    return None
                self._cond.wait(remaining)
            return self._pending.pop(0)

    def deliver_result(self, cmd_id, data):
        with self._lock:
            self._last_seen = time.time()
            slot = self._results.get(cmd_id)
            if slot is not None:
                slot["data"] = data
                slot["event"].set()

    def push_state(self, state):
        with self._lock:
            self._last_seen = time.time()
            if isinstance(state, dict):
                self._state = state

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
            self._cond.notify()
        if event.wait(timeout):
            with self._lock:
                return self._results.pop(cmd_id, {}).get("data")
        with self._lock:
            self._results.pop(cmd_id, None)
        return {"error": "extension_timeout"}

    def get_state(self):
        with self._lock:
            snap = dict(self._state)
        snap["connected"] = self.connected
        return snap

    @property
    def connected(self):
        return (time.time() - self._last_seen) < self.STALE_AFTER
