#!/usr/bin/env python3
"""Serial fresh-JVM GC controls following the frozen real recognition workload."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time

HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runtime = load("gc_runtime", HERE.parent / "runtime/desktop_runtime.py")
jfr = load("gc_jfr", HERE.parent / "runtime/analyze_jfr.py")
exports = load("gc_exports", HERE.parent / "runtime/export_results.py")
require = runtime.require
MODES = ("normal", "periodic", "explicit", "explicit-concurrent")
GC_EVENTS = "jdk.GarbageCollection,jdk.GCPhasePause,jdk.DataLoss,jdk.GCConfiguration"


def policy_flags(mode):
    require(mode in MODES, "Unknown GC mode")
    if mode == "periodic":
        return ["-XX:G1PeriodicGCInterval=5000", "-XX:G1PeriodicGCSystemLoadThreshold=0", "-XX:+G1PeriodicGCInvokesConcurrent"]
    if mode == "explicit-concurrent":
        return ["-XX:+ExplicitGCInvokesConcurrent"]
    return []


def schedule(forks):
    require(type(forks) is int and 1 <= forks <= 10, "Choose 1–10 fresh forks")
    return [(fork, mode) for fork in range(1, forks + 1) for mode in (MODES if fork % 2 else MODES[::-1])]


def validate(report, mode, events):
    require(report.get("schema") == "vive-idle-gc-component/1" and report.get("mode") == mode and
            report.get("outputParity") is True and report.get("nextWorkNanos", 0) > 0, "Invalid recognition/GC result")
    require(report.get("collectors") == ["G1 Young Generation", "G1 Concurrent GC", "G1 Old Generation"] or
            report.get("collectors") == ["G1 Young Generation", "G1 Old Generation"], "Unexpected collector")
    stages = ["before_work", "resources_closed"]
    for tick in range(1, 13):
        if tick == 5 and mode.startswith("explicit"): stages += ["before_request", "after_request"]
        stages.append(f"idle_{tick}")
    stages += ["before_next_work", "after_next_work"]
    rows = report["snapshots"]
    require([r["stage"] for r in rows] == stages, "Missing/duplicate/out-of-order snapshots")
    require(all(a["nanoTime"] < b["nanoTime"] for a, b in zip(rows, rows[1:])), "Non-monotone snapshots")
    require(all(0 <= r["used"] <= r["committed"] for r in rows), "Invalid heap measurements")
    require((report.get("requestNanos") is not None) == mode.startswith("explicit"), "Wrong request count")
    require(report.get("requestNanos") is None or type(report["requestNanos"]) is int and report["requestNanos"] > 0,
            "Invalid GC request duration")
    require(not any(e["type"] == "jdk.DataLoss" for e in events), "JFR data loss")
    require(all(e["type"] in GC_EVENTS.split(",") for e in events), "Unexpected JFR event")
    gc = [e["values"] for e in events if e["type"] == "jdk.GarbageCollection"]
    require(gc, "No GC evidence in JFR")
    if mode == "periodic": require(any("Periodic" in e["cause"] for e in gc), "Periodic GC never ran")
    if mode.startswith("explicit"): require(any(e["cause"] == "System.gc()" for e in gc), "Explicit request had no observed collection")
    by_name = {r["stage"]: r for r in rows}
    start, end = (by_name[name]["epochMillis"] * 1_000_000 for name in ("resources_closed", "before_next_work"))
    pauses = [jfr.duration_nanos(e["values"]["duration"]) for e in events if e["type"] == "jdk.GCPhasePause" and
              start <= jfr.timestamp_nanos(e["values"]["startTime"]) < end]
    return {"heapAfterWork": by_name["resources_closed"], "heapAfterIdle": by_name["before_next_work"],
            "idlePauses": jfr.distribution(pauses), "gcCauses": [e["cause"] for e in gc],
            "nextWorkNanos": report["nextWorkNanos"], "requestNanos": report.get("requestNanos")}


def capture(args):
    output = args.output.resolve()
    require(not output.exists(), "Use a new output directory")
    output.mkdir(mode=0o700, parents=True)
    cp = runtime.read_classpath(args.classpath)
    compiler = runtime.read_classpath(args.compiler_classpath)
    paths = [HERE / "IdleGcProbe.kt", HERE / "idle_gc.py", HERE / "test_idle_gc.py", args.fixture,
             HERE.parent / "runtime/desktop_runtime.py", HERE.parent / "runtime/analyze_jfr.py",
             HERE.parent / "runtime/export_results.py", HERE.parent / "startup/StartupRecognitionProbe.kt",
             args.recognition_classes, args.java, args.java.with_name("jfr"), args.java.parent.parent / "release", args.java.parent.parent / "lib/modules",
             args.java.parent.parent / "lib/server/libjvm.so", *map(Path, cp), *map(Path, compiler)]
    def provenance(): return [runtime.artifact(str(p.resolve()), args.project.resolve()) for p in paths]
    initial = provenance()
    source = runtime.source_identity(args.project)
    env = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"): env.pop(key, None)
    classes = output / "classes"
    command = [str(args.java), "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(cp), str(HERE / "IdleGcProbe.kt"),
               "-jvm-target", "25", "-d", str(classes)]
    runtime.run_logged(command, output / "compile.log", output, env, 120)
    frozen_classes = runtime.artifact(str(classes), args.project)
    metadata = {"schema": "vive-idle-gc-capture/1", "complete": False, "source": source, "inputs": initial,
                "classes": frozen_classes, "commands": [command], "rows": [],
                "scope": "Component experiment; 5s interval is diagnostic, not a proposed production setting"}
    runtime.write_json(output / "capture.json", metadata)
    for fork, mode in schedule(args.forks):
        target = output / f"{mode}-{fork}"
        target.mkdir(mode=0o700)
        env.update(XDG_DATA_HOME=str(target / "data"), XDG_CONFIG_HOME=str(target / "config"), XDG_CACHE_HOME=str(target / "cache"))
        command = [str(args.java), "-Xmx2g", "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                   "-Duser.home=" + str(target / "home"), "-XX:NativeMemoryTracking=summary", *policy_flags(mode),
                   f"-XX:StartFlightRecording=filename={target / 'gc.jfr'},settings=profile,dumponexit=true",
                   "-Xlog:gc*=info:file=" + str(target / "gc.log"), "-cp",
                   os.pathsep.join([str(classes), str(args.recognition_classes), *cp]),
                   "com.vivenotes.gcprobe.IdleGcProbe", mode, str(target), str(args.fixture)]
        metadata["commands"].append(command)
        samples = []
        with (target / "process.log").open("w") as log:
            process = subprocess.Popen(command, cwd=target, env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 90
            try:
                while process.poll() is None:
                    require(time.monotonic() < deadline, "GC control timed out")
                    try: samples.append({"epochNanos": time.time_ns(), **runtime.linux_process_sample(process.pid)})
                    except (OSError, ValueError): pass
                    time.sleep(.1)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=5)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        require(process.returncode == 0, f"GC control failed; inspect {target / 'process.log'}")
        runtime.write_json(target / "process.json", {"samples": samples})
        command = [str(args.java.with_name("jfr")), "print", "--json", "--events", GC_EVENTS, str(target / "gc.jfr")]
        runtime.run_logged(command, target / "events.json", target, env, 30)
        report = runtime.read_json(target / "result.json")
        row = validate(report, mode, runtime.read_json(target / "events.json")["recording"]["events"])
        row["nmtAfterWork"] = exports.nmt_summary((target / "nmt-after-work.txt").read_text())
        row["nmtAfterIdle"] = exports.nmt_summary((target / "nmt-after-idle.txt").read_text())
        for field, stage in (("rssAfterWork", "heapAfterWork"), ("rssAfterIdle", "heapAfterIdle")):
            at = row[stage]["epochMillis"] * 1_000_000
            nearby = min(samples, key=lambda sample: abs(sample["epochNanos"] - at))
            require(abs(nearby["epochNanos"] - at) < 500_000_000, "No nearby RSS sample")
            row[field] = nearby["rssBytes"]
        metadata["rows"].append({"fork": fork, "mode": mode, **row})
        require(provenance() == initial and runtime.source_identity(args.project) == source and
                runtime.artifact(str(classes), args.project) == frozen_classes, "Inputs changed during GC capture")
        runtime.write_json(output / "capture.json", metadata)
        print(mode, fork, "idle used/committed", row["heapAfterIdle"]["used"], row["heapAfterIdle"]["committed"],
              "max pause ns", row["idlePauses"]["maxNanos"], flush=True)
    metadata["complete"] = True
    metadata["rawFiles"] = [{"path": p.relative_to(output).as_posix(), "sha256": runtime.sha256(p)}
                            for p in sorted(output.rglob("*")) if p.is_file() and p.name != "capture.json"]
    runtime.write_json(output / "capture.json", metadata)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("output", "java", "classpath", "compiler-classpath", "project", "recognition-classes", "fixture"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--forks", type=int, default=3)
    capture(parser.parse_args())
