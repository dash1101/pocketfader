"""Integration test against a PRIVATE PipeWire instance (never the real session).

Start the sandbox first (see docs/DEVELOPMENT.md), export its environment, then:
    python3 tests/integration/sandbox_pipewire.py
It refuses to run unless PIPEWIRE_REMOTE/PULSE_SERVER point at a sandbox socket.
"""
import json, os, sys, tempfile, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from starcast.backends.eq_pipewire import SINK_NAME, BuiltinEQ
from starcast.backends.pulse import PulseAudio
from starcast.config import JsonFile
from starcast.runner import Runner

if "pfs" not in os.environ.get("PULSE_SERVER", "") or "pfs" not in os.environ.get("XDG_RUNTIME_DIR", ""):
    sys.exit("refusing to run: environment does not point at the sandbox")

def check(label, cond, extra=""):
    print(("PASS " if cond else "FAIL ") + label + (f"  [{extra}]" if extra else ""))
    if not cond:
        check.failed = True
check.failed = False

r = Runner()
audio = PulseAudio(r)
tmp = Path(tempfile.mkdtemp())
eq = BuiltinEQ(r, audio, JsonFile(tmp / "eq.json"), tmp)

outs = audio.outputs()
check("lists sandbox outputs", {o["name"] for o in outs} >= {"sandbox_speakers", "sandbox_headset"}, str([o["name"] for o in outs]))
check("EQ sink hidden before it exists", SINK_NAME not in [o["name"] for o in outs])

# --- per-device level, on a device that is NOT the current output
check("set level on non-default device", audio.set_output_volume("sandbox_headset", 37))
vol = {o["name"]: o["percent"] for o in audio.outputs()}
check("level readback", vol.get("sandbox_headset") == 37 and vol.get("sandbox_speakers") == 100, str(vol))
check("unknown device rejected", not audio.set_output_volume("nope", 10))

# --- switching the default output
check("switch default output", audio.set_output("sandbox_speakers") and audio.default_name() == "sandbox_speakers")

# --- EQ
ok, why = eq.enable()
check("EQ enable", ok, why)
time.sleep(0.5)
names = [s["name"] for s in audio.outputs(include_hidden=True)]
check("EQ sink exists", SINK_NAME in names, str(names))
check("EQ sink is the default output", audio.default_name() == SINK_NAME)
check("EQ sink hidden from the user's list", SINK_NAME not in [o["name"] for o in audio.outputs()])
check("hardware level untouched", {o["name"]: o["percent"] for o in audio.outputs()}["sandbox_speakers"] == 100)
snap = eq.snapshot()
check("snapshot enabled+running+target", snap["enabled"] and snap["running"] and snap["target"] == "sandbox_speakers", str(snap))

check("live band change", eq.set_band("bass", 6.0))
nid = eq._find_node_id()
res = r.read(["pw-cli", "enum-params", str(nid), "Props"])
check("gain visible in node Props", "bass:Gain" in res.out and "6.0" in res.out, res.out[-300:].replace("\n", " "))

check("retarget to headset", eq.retarget("sandbox_headset") and eq.target == "sandbox_headset")
time.sleep(0.5)
check("still default after retarget", audio.default_name() == SINK_NAME)

check("disable restores previous output", eq.disable()[0] and audio.default_name() == "sandbox_headset", audio.default_name())
time.sleep(0.5)
check("EQ sink gone after disable", SINK_NAME not in [s["name"] for s in audio.outputs(include_hidden=True)])
sys.exit(1 if check.failed else 0)
