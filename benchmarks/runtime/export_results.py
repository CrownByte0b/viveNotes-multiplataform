#!/usr/bin/env python3
"""Export verified synthetic runtime summaries without raw profiles, paths or traces."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("runtime_export_validation", HERE / "desktop_runtime.py")
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


def nmt_summary(text):
    result = {}
    for line in text.splitlines():
        total = re.fullmatch(r"Total: reserved=(\d+)KB, committed=(\d+)KB", line.strip())
        category = re.match(r"-\s+(.+?) \(reserved=(\d+)KB, committed=(\d+)KB\)", line.strip())
        if total:
            result["Total"] = {"reservedBytes": int(total[1]) * 1024, "committedBytes": int(total[2]) * 1024}
        elif category:
            result[category[1]] = {"reservedBytes": int(category[2]) * 1024, "committedBytes": int(category[3]) * 1024}
    if "Total" not in result or "Java Heap" not in result:
        raise ValueError("NMT summary lacks total or Java heap; do not invent native totals")
    return result


def public_vm_args(arguments):
    result = []
    for arg in arguments:
        if arg.startswith("-Duser.home="):
            arg = "-Duser.home=<profile>/home"
        elif arg.startswith("-Djava.util.prefs.userRoot="):
            arg = "-Djava.util.prefs.userRoot=<profile>/preferences"
        elif arg.startswith("-XX:StartFlightRecording="):
            arg = re.sub(r"filename=[^,]+", "filename=<capture>/runtime.jfr", arg)
        result.append(arg)
    return result


def export_capture(capture, analysis, output):
    capture, analysis, output = map(Path, (capture, analysis, output))
    if output.exists():
        raise ValueError("Use a new export directory")
    manifest = runtime.validate_complete_capture(capture)
    if manifest.get("contractOnly"):
        raise ValueError("A fixture contract is not a runtime result")
    analyzed = runtime.read_json(analysis / "environment.json")
    if analyzed.get("complete") is not True or analyzed.get("captureManifestSha256") != runtime.sha256(capture / "environment.json"):
        raise ValueError("Analysis does not bind to this completed capture")
    for row in analyzed["rawFiles"]:
        path = Path(row["path"])
        if path.is_absolute() or ".." in path.parts or runtime.sha256(analysis / path) != row["sha256"]:
            raise ValueError("Analysis evidence changed")
    result = runtime.read_json(analysis / "analysis.json")
    if result["countParity"].get("observerCountParity") is not True:
        raise ValueError("Missing independent JFR/observer count parity")
    result.pop("provenance")
    actual = runtime.read_json(capture / "isolated-profile/runtime.json")
    events = runtime.read_events(capture / "isolated-profile/events.jsonl", final=True)
    source = manifest["provenance"]
    metadata = {
        "schema": "vive-runtime-public-results/1", "label": manifest["label"],
        "sourceCommit": source["sourceCommit"], "sourceIdentity": source.get("sourceIdentity", "local Git checkout"),
        "sourceFiles": source["sourceFiles"], "backend": manifest["backend"],
        "platform": manifest["platform"], "cpu": manifest["cpu"], "logicalCpus": manifest["logicalCpus"],
        "captureManifestSha256": runtime.sha256(capture / "environment.json"),
        "recordingSha256": runtime.sha256(capture / "runtime.jfr"),
        "analysisManifestSha256": runtime.sha256(analysis / "environment.json"),
        "toolFiles": source["toolFiles"], "probeSources": source["probeSources"],
        "probeClasses": manifest["probeClasses"]["files"],
        "runtimeArtifacts": [{key: row[key] for key in ("name", "sha256", "kind", "projectArtifact")}
                             for row in source["runtimeArtifacts"]],
        "javaRuntimeArtifacts": [{key: row[key] for key in ("name", "sha256", "kind")}
                                 for row in source["javaRuntimeArtifacts"]],
        "executables": [{"role": row["role"], "sha256": row["sha256"]} for row in source["executables"]],
        "runtime": {key: value for key, value in actual.items() if key not in ("javaHome", "vmArgs", "classPath", "userDir")},
        "vmArgs": public_vm_args(actual["vmArgs"]),
        "scope": "Sanitized descriptive summaries. Raw synthetic profiles, logs, accessibility dumps and JFR remain outside the repository."
    }
    resources = {
        "eventSnapshots": [{key: event[key] for key in ("phase", "state", "processCpuNanos", "heapUsed", "heapCommitted", "threadCount")}
                           for event in events],
        "nmt": {name: nmt_summary((capture / f"nmt-{name}.txt").read_text())
                for name in runtime.read_json(capture / "summary.json")["nmtSnapshots"]},
        "limits": ["Java used and committed heap are distinct from process RSS.",
                   "NMT accounts for HotSpot-owned categories, not all third-party native allocations.",
                   "Snapshots retain natural unequal GC states; no forced-GC or leak conclusion."]
    }
    output.mkdir(parents=True)
    for name, value in (("provenance.json", metadata), ("summary.json", runtime.read_json(capture / "summary.json")),
                        ("analysis.json", result), ("resources.json", resources)):
        runtime.write_json(output / name, value)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--analysis", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(export_capture(args.capture, args.analysis, args.output))


if __name__ == "__main__":
    main()
