#!/usr/bin/env python3
"""Serial fresh-JVM recognition startup controls; reuses the frozen runtime provenance helpers."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("startup_runtime", HERE.parent / "runtime/desktop_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
MAIN = "com.vivenotes.startupprobe.StartupRecognitionProbe"
SOURCE = HERE / "StartupRecognitionProbe.kt"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def schedule(forks, sides):
    require(1 <= forks <= 20, "Choose 1–20 fresh forks")
    return [(fork, mode, side) for fork in range(1, forks + 1) for mode in ("unused", "ocr")
            for side in (sides if fork % 2 else list(reversed(sides)))]


def initialization(log):
    return sorted(set(re.findall(r"Initializing '([^']+)'", log)))


def validate(report, mode, initialized):
    require(report.get("schema") == "vive-startup-recognition/1" and report.get("mode") == mode, "Wrong probe report")
    require("com/vivenotes/ai/OnnxInkRecognitionEngine" in initialized, "JVM initialization evidence is missing")
    stages = report["stagesNs"]
    expected = {"construct", "close"} | ({"firstOcr", "warmOcr0", "warmOcr1", "warmOcr2"} if mode == "ocr" else set())
    require(set(stages) == expected and all(type(v) is int and v > 0 for v in stages.values()), "Missing or invalid timing")
    if mode == "ocr":
        for key in ("fixtureSha256", "readingSha256"):
            require(re.fullmatch(r"[0-9a-f]{64}", report[key]) is not None, "Missing fixture/output identity")
        require(type(report["confidenceBits"]) is int, "Missing exact confidence")
        require("ai/onnxruntime/OnnxRuntime" in initialized, "OCR did not initialize its native runtime")
        for event in ("opened Text session", "closed Text session", "opened native CTC reduction session",
                      "closed native CTC reduction session"):
            require(sum(event in line for line in report["events"]) == 1, "Missing native lifecycle: " + event)
    else:
        require(report["fixtureSha256"] is None and report["readingSha256"] is None and
                report["confidenceBits"] is None, "Unused engine performed recognition")
        require(not any("session" in line for line in report["events"]), "Unused engine opened native sessions")


def compare_rows(rows):
    result = {}
    for mode in ("unused", "ocr"):
        before = [r for r in rows if r["mode"] == mode and r["side"] == "before"]
        after = [r for r in rows if r["mode"] == mode and r["side"] == "after"]
        require(before and len(before) == len(after), "Incomplete paired matrix")
        for b, a in zip(before, after):
            require(b["fork"] == a["fork"], "Mismatched fork")
            for field in ("fixtureSha256", "readingSha256", "confidenceBits", "java", "vm", "os", "arch", "processors", "collectors"):
                require(b["report"][field] == a["report"][field], "Changed input/output/runtime: " + field)
            require(b["onnxInitialized"] and (a["onnxInitialized"] == (mode == "ocr")), "Unexpected initialization policy")
        metrics = {}
        for stage in before[0]["report"]["stagesNs"]:
            b = statistics.median(r["report"]["stagesNs"][stage] for r in before)
            a = statistics.median(r["report"]["stagesNs"][stage] for r in after)
            metrics[stage] = {"beforeNs": b, "afterNs": a, "changePercent": (a / b - 1) * 100}
        if mode == "ocr":
            b = statistics.median(r["report"]["stagesNs"]["construct"] + r["report"]["stagesNs"]["firstOcr"] for r in before)
            a = statistics.median(r["report"]["stagesNs"]["construct"] + r["report"]["stagesNs"]["firstOcr"] for r in after)
            metrics["constructPlusFirstOcr"] = {"beforeNs": b, "afterNs": a, "changePercent": (a / b - 1) * 100}
        result[mode] = metrics
    return result


def provenance(project, classpath):
    inventory = project / "source-inventory.json"
    return {"source": runtime.source_identity(project, source_manifest=inventory if inventory.exists() else None),
            "artifacts": [runtime.artifact(p, project) for p in classpath]}


def capture(args):
    output = args.output.resolve()
    require(not output.exists(), "Use a new output directory")
    sides = ["before", "after"] if args.after_classpath else ["before"]
    projects = {s: getattr(args, s + "_project").resolve() for s in sides}
    require(all(not output.is_relative_to(p) for p in projects.values()) and not output.is_relative_to(ROOT),
            "Raw output must stay outside the source checkouts")
    classpaths = {s: runtime.read_classpath(getattr(args, s + "_classpath")) for s in sides}
    initial = {s: provenance(projects[s], classpaths[s]) for s in sides}
    if len(sides) == 2:
        external = lambda s: [(a["name"], a["sha256"]) for a in initial[s]["artifacts"] if not a["projectArtifact"]]
        require(external("before") == external("after"), "External dependencies differ")
    output.mkdir(parents=True)
    (output / "home").mkdir()
    environment = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(key, None)
    classes = args.probe_classes.resolve() if args.probe_classes else output / "probe-classes"
    if args.probe_classes:
        require((classes / "source.sha256").read_text().strip() == runtime.sha256(SOURCE), "Stale compiled probe")
    else:
        require(args.compiler_classpath is not None, "Supply compiler or compiled probe")
        classes.mkdir()
        compiler = runtime.read_classpath(args.compiler_classpath)
        command = [str(args.java), "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", str(SOURCE), "-classpath", os.pathsep.join(classpaths["before"]),
                   "-jvm-target", "25", "-d", str(classes)]
        runtime.run_logged(command, output / "compile.log", output, environment, 120)
        (classes / "source.sha256").write_text(runtime.sha256(SOURCE) + "\n")
    require((classes / (MAIN.replace(".", "/") + ".class")).is_file(), "Missing compiled probe main")
    fixture = args.fixture.resolve() if args.fixture else output / "hello.png"
    command = lambda s: [str(args.java), "-Xms128m", "-Xmx512m", "--enable-native-access=ALL-UNNAMED",
                         "-Djava.awt.headless=true", "-Duser.home=" + str(output / "home"), "-Xlog:class+init=info", "-cp",
                         os.pathsep.join([str(classes), *classpaths[s]]), MAIN]
    if not args.fixture:
        runtime.run_logged([*command("before"), "fixture", str(fixture),
            str(ROOT / "shared/src/commonMain/composeResources/font/inter.ttf")], output / "fixture.log", output, environment, 60)
    frozen_probe = runtime.artifact(str(classes), ROOT)
    frozen_fixture = runtime.sha256(fixture)
    metadata = {"schema": "vive-startup-capture/1", "complete": False, "inputs": initial,
        "probeSourceSha256": runtime.sha256(SOURCE), "probe": frozen_probe, "fixtureSha256": frozen_fixture,
        "toolSha256": runtime.sha256(Path(__file__)), "java": runtime.artifact(str(args.java), ROOT),
        "runtime": [runtime.artifact(str(p), ROOT) for suffix in ("release", "lib/modules", "lib/server/libjvm.so", "bin/server/jvm.dll")
                    if (p := args.java.parent.parent / suffix).is_file()],
        "cpuAffinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "rows": []}
    runtime.write_json(output / "capture.json", metadata)
    for fork, mode, side in schedule(args.forks, sides):
        target = output / f"{mode}-{side}-{fork}"
        target.mkdir()
        start = time.monotonic_ns()
        runtime.run_logged([*command(side), mode, str(target / "report.json"), str(fixture)],
                           target / "process.log", target, environment, 90)
        elapsed = time.monotonic_ns() - start
        report = runtime.read_json(target / "report.json")
        initialized = initialization((target / "process.log").read_text(errors="replace"))
        validate(report, mode, initialized)
        row = {"fork": fork, "mode": mode, "side": side, "report": report, "processElapsedNs": elapsed,
               "onnxInitialized": "ai/onnxruntime/OnnxRuntime" in initialized,
               "reportSha256": runtime.sha256(target / "report.json"), "logSha256": runtime.sha256(target / "process.log")}
        metadata["rows"].append(row)
        runtime.write_json(output / "capture.json", metadata)
        print(mode, side, fork, report["stagesNs"], flush=True)
    require({s: provenance(projects[s], classpaths[s]) for s in sides} == initial, "Sources/binaries changed during capture")
    require(runtime.artifact(str(classes), ROOT) == frozen_probe and runtime.sha256(fixture) == frozen_fixture,
            "Probe or fixture changed during capture")
    if len(sides) == 2:
        metadata["comparison"] = compare_rows(metadata["rows"])
    metadata["complete"] = True
    runtime.write_json(output / "capture.json", metadata)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    for side in ("before", "after"):
        parser.add_argument(f"--{side}-classpath", type=Path, required=side == "before")
        parser.add_argument(f"--{side}-project", type=Path, required=side == "before")
    parser.add_argument("--compiler-classpath", type=Path)
    parser.add_argument("--probe-classes", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--forks", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(bool(args.after_classpath) == bool(args.after_project), "After classpath and project must be paired")
    capture(args)


if __name__ == "__main__":
    main()
