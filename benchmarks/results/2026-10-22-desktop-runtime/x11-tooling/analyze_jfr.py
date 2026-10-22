#!/usr/bin/env python3
"""Read-only, descriptive JFR analysis of one verified isolated desktop capture."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("_vive_runtime_jfr_validation", HERE / "desktop_runtime.py")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)
SCHEMA = "vive-desktop-runtime-jfr-analysis/1"
PHASE_EVENT = "com.vivenotes.RuntimeProbePhase"
FRAME_EVENT = "com.vivenotes.RuntimeComposeRender"
INPUT_EVENT = "com.vivenotes.RuntimeInputToDraw"
SAMPLE_EVENTS = ("jdk.ExecutionSample", "jdk.NativeMethodSample")
EVENTS = (*SAMPLE_EVENTS, PHASE_EVENT, FRAME_EVENT, INPUT_EVENT)
UNASSIGNED = ("unassigned_startup", "unassigned_interphase", "unassigned_after_phases")
SOURCES = (Path(__file__).resolve(), HERE / "test_analyze_jfr.py", HERE / "desktop_runtime.py")
TIME = re.compile(r"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})\Z")
DURATION = re.compile(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?)?\Z")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text())


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def timestamp_nanos(value):
    """Parse JFR's ISO timestamp without dropping its nanosecond precision."""
    require(isinstance(value, str), "JFR timestamp must be an ISO string")
    match = TIME.fullmatch(value)
    require(match is not None, f"Unsupported JFR timestamp: {value!r}")
    year, month, day, hour, minute, second = map(int, match.groups()[:6])
    fraction, offset = match.groups()[6:]
    if offset == "Z":
        zone = timezone.utc
    else:
        hours, minutes = map(int, offset[1:].split(":"))
        require(hours < 24 and minutes < 60, "Invalid JFR timestamp offset")
        zone = timezone(timedelta(minutes=(hours * 60 + minutes) * (1 if offset[0] == "+" else -1)))
    instant = datetime(year, month, day, hour, minute, second, tzinfo=zone)
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    delta = instant - epoch
    seconds = delta.days * 86400 + delta.seconds
    return seconds * 1_000_000_000 + int((fraction or "").ljust(9, "0") or "0")


def duration_nanos(value):
    require(isinstance(value, str), "JFR duration must be an ISO string")
    match = DURATION.fullmatch(value)
    require(match is not None and any(part is not None for part in match.groups()),
            f"Unsupported or negative JFR duration: {value!r}")
    days, hours, minutes, seconds, fraction = match.groups()
    require("T" not in value or any(part is not None for part in (hours, minutes, seconds)),
            f"Unsupported JFR duration with an empty time component: {value!r}")
    total = int(days or 0) * 86400 + int(hours or 0) * 3600 + int(minutes or 0) * 60 + int(seconds or 0)
    return total * 1_000_000_000 + int((fraction or "").ljust(9, "0") or "0")


def integer(value, name, minimum=0):
    require(isinstance(value, int) and not isinstance(value, bool) and value >= minimum,
            f"Invalid {name}: {value!r}")
    return value


def nearest_rank(values, percentile):
    require(isinstance(percentile, int) and not isinstance(percentile, bool) and 1 <= percentile <= 100,
            "Percentile must be 1..100")
    if not values:
        return None
    ordered = sorted(values)
    return ordered[(percentile * len(ordered) + 99) // 100 - 1]


def distribution(values):
    return {"count": len(values), "sumNanos": sum(values), "minNanos": min(values) if values else None,
            "p50Nanos": nearest_rank(values, 50), "p95Nanos": nearest_rank(values, 95),
            "maxNanos": max(values) if values else None}


def parse_events(document):
    require(isinstance(document, dict) and isinstance(document.get("recording"), dict), "Missing JFR recording object")
    events = document["recording"].get("events")
    require(isinstance(events, list), "Missing JFR event array")
    for event in events:
        require(isinstance(event, dict) and event.get("type") in EVENTS and isinstance(event.get("values"), dict),
                "Unexpected or malformed extracted JFR event")
        timestamp_nanos(event["values"].get("startTime"))
    return events


def phase_intervals(events, required_phases):
    phases = []
    for event in events:
        if event["type"] != PHASE_EVENT:
            continue
        value = event["values"]
        require(value.get("outcome") == "complete" and value.get("phase") in required_phases,
                "JFR contains an unexpected or failed scripted phase")
        start = timestamp_nanos(value["startTime"])
        duration = duration_nanos(value.get("duration"))
        phases.append({"name": value["phase"], "startEpochNanos": start, "endEpochNanos": start + duration,
                       "durationNanos": duration})
    phases.sort(key=lambda phase: phase["startEpochNanos"])
    require([phase["name"] for phase in phases] == list(required_phases),
            "JFR phases are missing, duplicated or out of scripted order")
    require(all(a["endEpochNanos"] <= b["startEpochNanos"] for a, b in zip(phases, phases[1:])),
            "Scripted JFR phases overlap")
    require(phases, "A complete capture must contain scripted phases")
    return phases


def interval_name(at, phases):
    for phase in phases:
        if phase["startEpochNanos"] <= at < phase["endEpochNanos"]:
            return phase["name"]
    if at < phases[0]["startEpochNanos"]:
        return UNASSIGNED[0]
    if at >= phases[-1]["endEpochNanos"]:
        return UNASSIGNED[2]
    return UNASSIGNED[1]


def method_name(frame):
    method = frame.get("method") if isinstance(frame, dict) else None
    if not isinstance(method, dict):
        return "<unresolved frame>"
    owner = method.get("type")
    name = owner.get("name") if isinstance(owner, dict) else None
    return f"{(name or '<unknown class>').replace('/', '.')}.{method.get('name') or '<unknown method>'}{method.get('descriptor') or ''}"


def sample_thread(values):
    thread = values.get("sampledThread")
    if not isinstance(thread, dict):
        return "unknown", {"javaThreadId": None, "osThreadId": None, "name": "<unknown thread>"}
    java_id, os_id = thread.get("javaThreadId"), thread.get("osThreadId")
    name = thread.get("javaName") or thread.get("osName") or "<unnamed thread>"
    key = f"java:{java_id}" if isinstance(java_id, int) and java_id > 0 else f"os:{os_id}:{name}"
    return key, {"javaThreadId": java_id, "osThreadId": os_id, "name": name}


def ranked(counter):
    return [{"frame": name, "sampleHits": count} for name, count in sorted(counter.items(), key=lambda row: (-row[1], row[0]))]


def sampling_summary(events, phases):
    buckets = {}

    def bucket(name):
        return buckets.setdefault(name, {"types": Counter(), "leaf": Counter(), "application": Counter(),
                                         "threads": {}, "missingStack": 0, "truncatedStack": 0})

    for name in ("all", *(phase["name"] for phase in phases), *UNASSIGNED):
        bucket(name)
    for event in events:
        if event["type"] not in SAMPLE_EVENTS:
            continue
        value = event["values"]
        phase = interval_name(timestamp_nanos(value["startTime"]), phases)
        trace = value.get("stackTrace")
        frames = trace.get("frames") if isinstance(trace, dict) else None
        names = [method_name(frame) for frame in frames] if isinstance(frames, list) else []
        leaf = names[0] if names else None
        applications = {name for name in names if name.startswith("com.vivenotes.")}
        key, identity = sample_thread(value)
        for target in (bucket("all"), bucket(phase)):
            target["types"][event["type"]] += 1
            if leaf is None:
                target["missingStack"] += 1
            else:
                target["leaf"][leaf] += 1
            if isinstance(trace, dict) and trace.get("truncated") is True:
                target["truncatedStack"] += 1
            target["application"].update(applications)
            thread = target["threads"].setdefault(key, {**identity, "types": Counter(), "leaf": Counter(), "application": Counter()})
            thread["types"][event["type"]] += 1
            if leaf is not None:
                thread["leaf"][leaf] += 1
            thread["application"].update(applications)
    result = {}
    for name, target in buckets.items():
        result[name] = {"samples": sum(target["types"].values()), "eventTypes": dict(sorted(target["types"].items())),
                        "missingStackSamples": target["missingStack"], "truncatedStackSamples": target["truncatedStack"],
                        "leafHits": ranked(target["leaf"]), "applicationInclusiveHits": ranked(target["application"]),
                        "threads": [{"key": key, "name": thread["name"], "javaThreadId": thread["javaThreadId"],
                                     "osThreadId": thread["osThreadId"], "samples": sum(thread["types"].values()),
                                     "eventTypes": dict(sorted(thread["types"].items())), "leafHits": ranked(thread["leaf"]),
                                     "applicationInclusiveHits": ranked(thread["application"])}
                                    for key, thread in sorted(target["threads"].items())]}
    return result


def validate_render_counts(events, rendering):
    require(isinstance(rendering, dict) and rendering.get("schema") == "vive-runtime-render-observation/1",
            "Missing render observer summary")
    observed = rendering.get("observations")
    require(isinstance(observed, dict) and observed.get("closed") is True and observed.get("inFlightCallbacks") == 0,
            "Render observer is not completely closed")
    for key in ("startedCallbacks", "completedCallbacks", "failedCallbacks", "inputObservations", "pendingInputs",
                "droppedPendingInputs", "unmatchedInputsAtClose"):
        integer(observed.get(key), f"observer {key}")
    frames = [event["values"] for event in events if event["type"] == FRAME_EVENT]
    inputs = [event["values"] for event in events if event["type"] == INPUT_EVENT]
    by_id = {}
    by_phase = Counter()
    for frame in frames:
        sequence = integer(frame.get("frameSequence"), "frame sequence", 1)
        require(sequence not in by_id, "Duplicate JFR frame sequence")
        require(isinstance(frame.get("returnedNormally"), bool), "Missing callback outcome")
        require(isinstance(frame.get("phase"), str), "Missing callback phase label")
        integer(frame.get("callbackElapsedNanos"), "callback elapsed")
        integer(frame.get("inputObservations"), "callback input count")
        duration_nanos(frame.get("duration"))
        by_id[sequence] = frame
        if frame["returnedNormally"]:
            by_phase[frame.get("phase")] += 1
    require(sorted(by_id) == list(range(1, len(frames) + 1)), "JFR frame sequence has gaps")
    require(len(frames) == observed.get("startedCallbacks") and sum(by_phase.values()) == observed.get("completedCallbacks")
            and len(frames) - sum(by_phase.values()) == observed.get("failedCallbacks"),
            "JFR callbacks disagree with independent observer counts (possible incomplete extraction or dropped events)")
    phase_rows = observed.get("phases")
    require(isinstance(phase_rows, list) and len({row.get("name") for row in phase_rows}) == len(phase_rows),
            "Malformed observer phases")
    require(all(by_phase[row["name"]] == row.get("completedCallbacks") for row in phase_rows)
            and all(name in {row["name"] for row in phase_rows} for name in by_phase),
            "JFR callback phase labels disagree with observer")
    sequences = set()
    input_phase = Counter()
    input_frames = Counter()
    for value in inputs:
        sequence = integer(value.get("inputSequence"), "input sequence", 1)
        require(sequence not in sequences and sequence <= observed.get("inputObservations", -1), "Duplicate or invalid input observation")
        sequences.add(sequence)
        start = integer(value.get("dispatchToCallbackStartNanos"), "dispatch to callback start")
        end = integer(value.get("dispatchToCallbackEndNanos"), "dispatch to callback end")
        frame = by_id.get(value.get("frameSequence"))
        require(frame is not None and frame["returnedNormally"] and end >= start
                and end - start == frame["callbackElapsedNanos"], "Input observation does not match its completed callback")
        require(isinstance(value.get("phase"), str), "Missing input dispatch phase label")
        input_phase[value.get("phase")] += 1
        input_frames[value["frameSequence"]] += 1
    require(all(input_frames[sequence] == frame["inputObservations"] for sequence, frame in by_id.items()),
            "JFR per-callback matched input counts disagree")
    require(len(inputs) == sum(row.get("inputsFollowedByCallback", 0) for row in phase_rows),
            "JFR input observations disagree with observer matched-input count")
    require(all(input_phase[row["name"]] == row.get("inputsFollowedByCallback", 0) for row in phase_rows),
            "JFR input dispatch phase labels disagree with observer")
    require(len(inputs) + observed.get("pendingInputs", 0) + observed.get("droppedPendingInputs", 0)
            + observed.get("unmatchedInputsAtClose", 0) == observed.get("inputObservations"),
            "Observer input accounting is incomplete")
    return {"callbackEvents": len(frames), "completedCallbackEvents": sum(by_phase.values()),
            "inputObservationEvents": len(inputs), "observerCountParity": True}


def analyze_document(document, rendering, required_phases):
    events = parse_events(document)
    phases = phase_intervals(events, required_phases)
    count_parity = validate_render_counts(events, rendering)
    buckets = {name: {"durations": [], "failed": 0, "crossing": 0, "inputStart": [], "inputEnd": [], "inputPhases": Counter()}
               for name in ("all", *(phase["name"] for phase in phases), *UNASSIGNED)}
    for event in events:
        if event["type"] not in (FRAME_EVENT, INPUT_EVENT):
            continue
        value = event["values"]
        start = timestamp_nanos(value["startTime"])
        name = interval_name(start, phases)
        for bucket_name in ("all", name):
            target = buckets[bucket_name]
            if event["type"] == FRAME_EVENT:
                duration = duration_nanos(value["duration"])
                if value["returnedNormally"]:
                    target["durations"].append(duration)
                else:
                    target["failed"] += 1
                if duration and interval_name(start + duration - 1, phases) != name:
                    target["crossing"] += 1
            else:
                target["inputStart"].append(value["dispatchToCallbackStartNanos"])
                target["inputEnd"].append(value["dispatchToCallbackEndNanos"])
                target["inputPhases"][value["phase"]] += 1
    return {"schema": SCHEMA, "countParity": count_parity, "phaseIntervals": phases,
            "phaseAssociation": "half-open JFR RuntimeProbePhase startTime/duration intervals; callbacks and samples by event start; input events are emitted at draw completion, with original dispatch-phase labels retained separately",
            "quantileDefinition": "nearest rank: sorted[ceil(percentile * count / 100) - 1]; empty distributions have null quantiles",
            "callbackScope": "successfully returned CPU recording callbacks; JFR duration attributed whole to start interval, crossing callbacks explicit; not GPU/presentation/FPS or exclusive thread CPU",
            "inputScope": "delivered/coalesced AWT dispatch to next later returned callback; timing is an observation, not causal or physical input latency; unmatched/dropped inputs remain in observer summary",
            "samplingScope": "event/sample counts, not CPU time; leaf-first printed stacks; application-inclusive methods counted at most once per sample; missing/truncated stacks explicit; forwarding probe ancestors are preserved, with no observer-overhead classification inferred from stack ancestry",
            "limits": ["Descriptive observations from this instrumented capture, no statistical tail-latency, throughput or FPS claim.",
                       "Input-event interval is its emission at callback completion, not reconstructed dispatch time.",
                       "Sampling frequency/state and native visibility limit stack attribution; no conversion of hits to milliseconds.",
                       "Observer/JFR overhead, Robot pacing, scheduling and GC remain included."],
            "framesAndInputs": {name: {"completedCallbacks": distribution(target["durations"]),
                                      "failedCallbacks": target["failed"], "boundaryCrossingCallbacks": target["crossing"],
                                      "inputToCallbackStart": distribution(target["inputStart"]),
                                      "inputToCallbackEnd": distribution(target["inputEnd"]),
                                      "originalInputDispatchPhaseLabels": dict(sorted(target["inputPhases"].items()))}
                                for name, target in buckets.items()},
            "sampling": sampling_summary(events, phases), "observer": rendering}


def verify_jfr_executable(manifest, executable):
    executable = Path(executable).resolve()
    roles = {row["role"]: row for row in manifest["provenance"]["executables"]}
    require("java" in roles and "jfr" in roles, "Capture does not identify its Java and JFR executables")
    java, recorded = roles["java"], roles["jfr"]
    require(executable.is_file() and os.access(executable, os.X_OK), "JFR executable is unavailable")
    require(executable == Path(recorded["path"]).resolve() and executable.parent == Path(java["path"]).resolve().parent,
            "Use the exact captured JDK's JFR executable")
    require(sha256(executable) == recorded["sha256"] and sha256(java["path"]) == java["sha256"],
            "Captured Java/JFR executable bytes changed")
    return [{"role": "jfr", "path": str(executable), "sha256": sha256(executable)}, java]


def source_manifest():
    return [{"path": path.name, "sha256": sha256(path)} for path in SOURCES]


def markdown(result):
    rows = ["# Desktop runtime JFR observations", "", result["callbackScope"], "", result["inputScope"], "",
            "Frame durations use JFR event elapsed time and nearest-rank p50/p95; these are descriptive recorded callbacks, not a latency-tail guarantee.", "",
            "| JFR interval | Completed callbacks | p50 ms | p95 ms | Max ms | Sum ms | Samples |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for name, values in result["framesAndInputs"].items():
        frame = values["completedCallbacks"]
        number = lambda value: "—" if value is None else f"{value / 1_000_000:.3f}"
        rows.append(f"| {name} | {frame['count']} | {number(frame['p50Nanos'])} | {number(frame['p95Nanos'])} | {number(frame['maxNanos'])} | {number(frame['sumNanos'])} | {result['sampling'][name]['samples']} |")
    rows += ["", "Input dispatch observations grouped by their JFR emission interval at callback completion:", "",
             "| JFR interval | Matched inputs | Start p50 ms | Start p95 ms | Start max ms | End p50 ms | End p95 ms | End max ms |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for name, values in result["framesAndInputs"].items():
        start, end = values["inputToCallbackStart"], values["inputToCallbackEnd"]
        rows.append(f"| {name} | {start['count']} | {number(start['p50Nanos'])} | {number(start['p95Nanos'])} | {number(start['maxNanos'])} | {number(end['p50Nanos'])} | {number(end['p95Nanos'])} | {number(end['maxNanos'])} |")
    rows += ["", result["phaseAssociation"], "", result["samplingScope"], "", "Application-inclusive sampled hits (all intervals):", ""]
    for row in result["sampling"]["all"]["applicationInclusiveHits"][:15]:
        rows.append(f"- {row['sampleHits']}: `{row['frame'].replace('`', '')}`")
    rows += ["", "Input distributions, per-thread/leaf stacks, crossing callbacks, unmatched inputs and raw counts are preserved in analysis.json.", ""]
    rows += [f"- {limit}" for limit in result["limits"]]
    return "\n".join(rows) + "\n"


def analyze_capture(capture, executable, output):
    capture = Path(capture).resolve()
    manifest = runtime.validate_complete_capture(capture)
    require(manifest.get("contractOnly") is not True, "A headless fixture contract is not a real-app capture")
    executables = verify_jfr_executable(manifest, executable)
    destination = runtime.check_new_output(Path(output), runtime.PROJECT)
    runtime.check_new_output(destination, Path(manifest["provenance"]["projectRoot"]))
    require(not destination.is_relative_to(capture), "Analysis output cannot be inside immutable raw capture")
    recording = capture / "runtime.jfr"
    recording_hash, capture_hash = sha256(recording), sha256(capture / "environment.json")
    sources = source_manifest()
    command = [str(Path(executable).resolve()), "print", "--json", "--stack-depth", "256", "--events", ",".join(EVENTS), str(recording)]
    environment = {"schema": SCHEMA, "complete": False, "capture": str(capture), "captureManifestSha256": capture_hash,
                   "recordingSha256": recording_hash, "executables": executables, "sources": sources,
                   "command": command, "stackDepthRequested": 256}
    destination.mkdir(parents=True, mode=0o700)
    write_json(destination / "environment.json", environment)
    try:
        with (destination / "jfr-events.json").open("wb") as stdout, (destination / "jfr-print-stderr.txt").open("wb") as stderr:
            process = subprocess.run(command, stdout=stdout, stderr=stderr, cwd=destination, timeout=120, check=False)
        require(process.returncode == 0, f"JFR extraction failed with exit {process.returncode}; raw logs retained")
        require(runtime.validate_complete_capture(capture) == manifest and sha256(recording) == recording_hash
                and sha256(capture / "environment.json") == capture_hash, "Raw capture changed during analysis")
        require(source_manifest() == sources and verify_jfr_executable(manifest, executable) == executables,
                "Analyzer source or JDK bytes changed during extraction")
        result = analyze_document(read_json(destination / "jfr-events.json"),
                                  read_json(capture / "isolated-profile" / "rendering.json"), runtime.PHASES)
        result["provenance"] = {"capture": str(capture), "captureManifestSha256": capture_hash,
                                "recordingSha256": recording_hash, "sources": sources, "executables": executables,
                                "extractedEventsSha256": sha256(destination / "jfr-events.json")}
        write_json(destination / "analysis.json", result)
        (destination / "analysis.md").write_text(markdown(result))
        environment.update(complete=True, rawFiles=[{"path": path.name, "sha256": sha256(path)} for path in (
            destination / "jfr-events.json", destination / "jfr-print-stderr.txt", destination / "analysis.json", destination / "analysis.md")])
        write_json(destination / "environment.json", environment)
        return destination
    except BaseException as error:
        environment["failure"] = f"{type(error).__name__}: {error}"
        write_json(destination / "environment.json", environment)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--jfr", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(analyze_capture(args.capture, args.jfr, args.output))
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"JFR analysis failed: {error}\n")


if __name__ == "__main__":
    main()
