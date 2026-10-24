#!/usr/bin/env python3
"""Validate native SwingGraphics JFR method intervals against the drained live ledger."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("_native_timing_common", HERE.parent / "runtime/analyze_jfr.py")
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
METHODS = (
    "androidx.compose.ui.scene.ComposeSceneMediator::onRender",
    "org.jetbrains.skiko.swing.SkiaSwingLayer::paint",
    "org.jetbrains.skiko.swing.SwingRendererBase::redraw",
)
EVENTS = "jdk.MethodTrace,jdk.DataLoss,com.vivenotes.RuntimeProbePhase"
require = common.require


def analyze(document, ledger, required_phases):
    require(ledger.get("schema") == "vive-native-wayland-method-observation/1", "Wrong observer schema")
    require(ledger.get("closed") is True and ledger.get("errors") == [] and ledger.get("dataLossEvents") == 0,
            "Observer incomplete or failed")
    require(isinstance(ledger.get("edtThreadId"), int) and ledger["edtThreadId"] > 0, "Missing owner thread")
    require(ledger.get("components"), "Missing owned SwingGraphics component")
    events = document.get("recording", {}).get("events")
    require(isinstance(events, list), "Missing JFR event list")
    require(all(e.get("type") in EVENTS.split(",") for e in events), "Unexpected JFR event")
    require(not any(e["type"] == "jdk.DataLoss" for e in events), "JFR data loss")
    phases = common.phase_intervals(events, required_phases) if required_phases else []
    require(bool(required_phases) or not any(e["type"] == common.PHASE_EVENT for e in events),
            "Unvalidated scripted phases")
    rows = {method: [] for method in METHODS}
    for event in events:
        if event["type"] != "jdk.MethodTrace":
            continue
        value = event["values"]
        method = value["method"]
        key = method["type"]["name"].replace("/", ".") + "::" + method["name"]
        require(key in METHODS, "Unexpected traced method")
        require(value["eventThread"]["javaThreadId"] == ledger["edtThreadId"], "Foreign render thread")
        start = common.timestamp_nanos(value["startTime"])
        duration = common.duration_nanos(value["duration"])
        rows[key].append((start, start + duration))
    counts = {method: len(values) for method, values in rows.items()}
    require(counts == ledger.get("methodCounts"), "Drained stream / raw JFR count mismatch")
    require(min(counts.values()) > 0 and len(set(counts.values())) == 1, "Missing render layer invocation")
    for values in rows.values():
        values.sort()
        require(len(set(values)) == len(values), "Duplicate method interval")
        require(all(a[1] <= b[0] for a, b in zip(values, values[1:])), "Overlapping method intervals")
    for compose, paint, redraw in zip(*(rows[method] for method in METHODS)):
        require(paint[0] <= redraw[0] <= compose[0] <= compose[1] <= redraw[1] <= paint[1],
                "Render intervals do not nest paint / redraw / Compose")
    result = {}
    for method, values in rows.items():
        bins = {name: [] for name in (*required_phases, *common.UNASSIGNED)} if phases else {"unphased": []}
        for start, end in values:
            name = common.interval_name(start, phases) if phases else "unphased"
            bins[name].append(end - start)
        result[method] = {"all": common.distribution([end - start for start, end in values]),
                          "phases": {name: common.distribution(times) for name, times in bins.items()}}
    require(all(result[METHODS[0]]["phases"][name]["count"] > 0 for name in required_phases if name.startswith("robot_")),
            "Robot phase has no invocation starting inside its timestamps; a delayed live count is insufficient")
    return {"schema": "vive-native-wayland-method-analysis/1", "validated": True,
            "countParity": True, "nestedIntervals": counts[METHODS[0]], "methods": result,
            "phases": phases, "dataLossEvents": 0,
            "limits": ["Elapsed method invocations, including scheduling pauses; nested scopes must not be added together.",
                       "JFR exposes neither receiver identity nor successful return; ownership requires a sole visible AWT window and EDT.",
                       "No input latency, GPU time, compositor presentation or displayed FPS measurement.",
                       "Live JFR delivery batches alter workload waits; compare only identically instrumented captures."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", required=True, type=Path)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--unphased-smoke", action="store_true")
    args = parser.parse_args()
    result = analyze(json.loads(args.events.read_text()), json.loads(args.ledger.read_text()),
                     [] if args.unphased_smoke else common.runtime.PHASES)
    with args.output.open("x") as target:
        json.dump(result, target, indent=2, allow_nan=False)
        target.write("\n")
