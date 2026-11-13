"""The browser extension updating itself in place (extension/background.js), run with Node against a stand-in browser:
YTM Starcast keeps the extension's folder up to date, and the extension reloads into the new version."""
import json
import shutil
import subprocess
import unittest
from pathlib import Path

BACKGROUND = Path(__file__).resolve().parent.parent / "extension" / "background.js"

# A stand-in browser: the calls the script makes are written down; the app answers /api/info with `shipped`.
SCRIPT = r"""
const fs = require("fs"), vm = require("vm");
const [file, scenario] = [process.argv[1], JSON.parse(process.argv[2])];
const log = [];
const listeners = { installed: [], startup: [], message: [], alarm: [] };
const store = Object.assign({}, scenario.storage || {});
const ev = (name) => ({ addListener: (f) => listeners[name].push(f) });
const chrome = {
  runtime: {
    id: "me",
    getManifest: () => ({ version: scenario.running }),
    reload: () => log.push("reload"),
    onInstalled: ev("installed"), onStartup: ev("startup"), onMessage: ev("message"),
  },
  commands: { onCommand: { addListener() {} } },
  action: {
    setBadgeBackgroundColor() {},
    setBadgeText: (o) => log.push("badge:" + o.text),
    setTitle() {},
  },
  alarms: { create: (n, o) => log.push("alarm:" + n + ":" + o.periodInMinutes), onAlarm: ev("alarm") },
  storage: { local: {
    get: async (keys) => Object.fromEntries(keys.filter((k) => k in store).map((k) => [k, store[k]])),
    set: async (o) => { Object.assign(store, o); },
    remove: async (k) => { delete store[k]; },
  } },
  tabs: {
    query: async () => (scenario.tabs || []).map((id) => ({ id, url: "https://music.youtube.com/watch?v=x" })),
    reload: async (id) => log.push("tabReload:" + id),
    update: async () => {}, sendMessage: async () => null,
  },
  windows: { update: async () => {} },
};
const fetch = async (url) => {
  if (scenario.offline) throw new Error("offline");
  log.push("fetch:" + url.replace("http://127.0.0.1:8777", ""));
  return { json: async () => ({ name: "YTM Starcast", extension: scenario.shipped }) };
};
const ctx = { chrome, fetch, console, setTimeout, clearTimeout, AbortController, Promise };
ctx.globalThis = ctx;
vm.runInNewContext(fs.readFileSync(file, "utf8"), ctx);
const settle = () => new Promise((r) => setTimeout(r, 30));
(async () => {
  for (const step of scenario.steps) {
    if (step === "startup") listeners.startup.forEach((f) => f());
    if (step === "installed") listeners.installed.forEach((f) => f({ reason: "update" }));
    if (step === "alarm") listeners.alarm.forEach((f) => f({ name: "starcast-update" }));
    if (step === "state" || step === "now") {
      const msg = { starcast: step === "state" ? "updateState" : "updateNow" };
      await new Promise((done) => {
        let answered = false;
        for (const f of listeners.message) {
          const keep = f(msg, { id: "me" }, (r) => { log.push("reply:" + JSON.stringify(r)); answered = true; done(); });
          if (keep === true) return;
        }
        if (!answered) done();
      });
    }
    await settle();
  }
  console.log(JSON.stringify({ log, store }));
})();
"""


@unittest.skipUnless(shutil.which("node"), "needs Node.js")
class ExtensionUpdateTests(unittest.TestCase):
    def run_bg(self, **scenario):
        scenario.setdefault("running", "2.3.3")
        scenario.setdefault("shipped", "2.4.0")
        p = subprocess.run(["node", "-e", SCRIPT, str(BACKGROUND), json.dumps(scenario)],
                           capture_output=True, text=True, timeout=60)
        if p.returncode:
            raise AssertionError(p.stderr)
        return json.loads(p.stdout)

    def test_reloads_by_itself_when_no_youtube_music_tab_is_open(self):
        out = self.run_bg(steps=["startup"])
        self.assertIn("reload", out["log"])
        self.assertIn("badge:↑", out["log"])
        self.assertEqual(out["store"]["reloadedFor"], "2.4.0")
        self.assertIn("alarm:starcast-update:30", out["log"])

    def test_waits_while_a_youtube_music_tab_is_open(self):
        out = self.run_bg(steps=["startup", "alarm"], tabs=[7])
        self.assertNotIn("reload", out["log"])
        self.assertIn("badge:↑", out["log"])

    def test_one_try_per_version(self):
        # reloaded once already and still the old version: the browser loads it from a folder the app doesn't update
        out = self.run_bg(steps=["startup", "state"], storage={"reloadedFor": "2.4.0"})
        self.assertNotIn("reload", out["log"])
        self.assertIn('reply:{"running":"2.3.3","ready":"2.4.0","tried":true}', out["log"])

    def test_nothing_when_up_to_date_or_the_app_is_away(self):
        for scenario in ({"shipped": "2.3.3"}, {"shipped": "2.3.0"}, {"offline": True}, {"shipped": "../evil"}):
            out = self.run_bg(steps=["startup", "state"], **scenario)
            self.assertNotIn("reload", out["log"], scenario)
            self.assertIn('reply:{"running":"2.3.3","ready":null,"tried":false}', out["log"], scenario)

    def test_update_now_reloads_and_then_the_tabs(self):
        out = self.run_bg(steps=["now"], tabs=[3, 9])
        self.assertIn("reload", out["log"])
        self.assertEqual(out["store"]["reloadTabs"], [3, 9])
        # after the reload, the browser starts the new version: its first act is to reload those tabs
        after = self.run_bg(steps=["installed"], running="2.4.0", storage=out["store"])
        self.assertEqual([x for x in after["log"] if x.startswith("tabReload")], ["tabReload:3", "tabReload:9"])
        self.assertNotIn("reloadTabs", after["store"])
        self.assertNotIn("reload", after["log"])
        self.assertIn("badge:", after["log"])          # up to date: the badge goes


if __name__ == "__main__":
    unittest.main()
