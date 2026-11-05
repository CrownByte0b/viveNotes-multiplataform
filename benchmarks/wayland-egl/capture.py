#!/usr/bin/env python3
"""Profile continuous AWT wheel delivery through the real native Wayland EGL app."""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("egl_runtime_helpers", HERE.parent / "runtime/desktop_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def write(path, value):
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def distribution(values):
    require(values and all(isinstance(x, (int, float)) and math.isfinite(x) and x >= 0 for x in values),
            "Missing or invalid measurements")
    ordered = sorted(values)
    return {"count": len(values), "median": ordered[len(values) // 2],
            "p95": ordered[math.ceil(.95 * len(values)) - 1], "sum": sum(values)}


def pointer_measurements(interaction, phases):
    result = {}
    period = interaction["pointerPeriodNanos"]
    require(isinstance(period, int) and period > 0, "Invalid pointer input period")
    for name in ("pen", "erase"):
        rows = interaction[name + "Inputs"]
        count = interaction.get("expectedPenStrokes", 2) * 15 if name == "pen" else 30
        require(len(rows) == count and [row[0] for row in rows] == list(range(count)), "Lost or reordered pointer input")
        phase = next(p for p in phases if p["name"] == name)
        for index, row in enumerate(rows):
            require(len(row) == 6 and all(isinstance(x, (int, float)) and math.isfinite(x) for x in row), "Invalid pointer input")
            require(phase["startNanos"] <= row[1] <= row[2] <= row[3] <= row[4] <= phase["endNanos"], "Pointer timestamps outside phase")
            require(row[5] == (501 if index % 15 == 0 else 502 if index % 15 == 14 else 506), "Invalid pointer event sequence")
            if index % 15:
                require(row[1] - rows[index-1][1] == period, "Pointer producer changed its schedule")
        result[name] = {"queueNanos": distribution([r[3]-r[2] for r in rows]),
                        "dispatchNanos": distribution([r[4]-r[3] for r in rows]),
                        "producerLatenessNanos": distribution([r[2]-r[1] for r in rows])}
    return result


def flatpak_command(build, output, classes, work, java_command, env):
    split = java_command.index("-cp")
    return ["flatpak", "build", "--runtime", "--readonly", "--die-with-parent", "--unshare=network",
            "--socket=wayland", "--nosocket=x11", "--nosocket=fallback-x11", "--nofilesystem=host",
            "--filesystem=" + str(output), "--build-dir=" + str(work), "--unset-env=DISPLAY",
            "--env=WAYLAND_DISPLAY=" + Path(env["WAYLAND_DISPLAY"]).name, "--env=XDG_SESSION_TYPE=wayland",
            *["--env=" + key + "=" + env[key] for key in ("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME")],
            str(build), "/app/jbr/bin/java", *java_command[1:split], "-cp", str(classes) + ":/app/lib/*",
            *java_command[split+2:]]


def summarize(raw, count):
    require(raw.get("schema") == "vive-egl-scroll/2" and raw.get("toolkit") == "sun.awt.wl.WLToolkit",
            "Not a native Wayland capture")
    require(isinstance(raw.get("gpu"), str) and raw["gpu"] and not any(
        term in raw["gpu"].lower() for term in ("llvmpipe", "softpipe", "swiftshader")), "Not a hardware renderer")
    require(raw.get("renderApis") == ["OPENGL"], "Renderer fell back or is ambiguous")
    phases = raw.get("phases", [])
    names = ["idle", "scroll_down", "scroll_up"]
    if raw.get("interactions") is not None:
        names += ["zoom", "pen", "erase", "resize", "popup"]
        interaction = raw["interactions"]
        require(interaction["zoomPeak"] > interaction["zoomBefore"] and
                abs(interaction["zoomAfter"] - interaction["zoomBefore"]) < .0001, "Zoom interaction failed")
        expected_strokes = interaction.get("expectedPenStrokes", 2)
        require(isinstance(expected_strokes, int) and expected_strokes in range(2, 41, 2), "Invalid pen fixture size")
        require(interaction["persistedPenStrokes"] == expected_strokes and interaction["remainingAuthoredStrokes"] == 0 and
                interaction["resizeReusedContext"] is True and interaction["popupSurfaces"] > 0 and
                interaction["popupClosed"] is True, "Incomplete interaction contracts")
    require([p.get("name") for p in phases] == names + ["idle_final"], "Incomplete phases")
    pointer = pointer_measurements(raw["interactions"], phases) if raw.get("interactions") is not None else None
    require(all(p["endNanos"] > p["startNanos"] and p["cpuNanos"] >= 0 and p["submittedFrames"] >= 0 for p in phases),
            "Invalid phase measurement")
    for phase in phases:
        for key in ("memoryBefore", "memoryAfter"):
            memory = phase[key]
            require(0 < memory["rssBytes"] <= memory["highWaterRssBytes"] and memory["heapUsedBytes"] > 0,
                    "Invalid process memory measurement")
    if raw.get("interactions") is not None:
        require(all(p["submittedFrames"] > 0 for p in phases if p["name"] in ("zoom", "pen", "erase", "popup")),
                "Interaction has no rendered frame")
    require(all(a["endNanos"] <= b["startNanos"] for a, b in zip(phases, phases[1:])), "Overlapping phases")
    rows = raw.get("inputs", [])
    require(len(rows) == count * 2 and [row[0] for row in rows] == list(range(count * 2)), "Lost or reordered input")
    period = raw["inputPeriodNanos"]
    require(isinstance(period, int) and period > 0, "Invalid input period")
    for index, row in enumerate(rows):
        require(len(row) == 6 and all(isinstance(x, (int, float)) and math.isfinite(x) for x in row), "Invalid input row")
        require(row[1] <= row[2] <= row[3] <= row[4], "Invalid input timestamps")
        require(row[5] == (1 if index < count else -1), "Input direction changed")
        if index % count:
            require(row[1] - rows[index - 1][1] == period, "Input producer changed its schedule")
    scroll = raw["scroll"]
    require(len(scroll) == 3 and all(math.isfinite(x) for x in scroll) and scroll[1] > scroll[0] and scroll[2] < scroll[1],
            "Canvas did not scroll down and back")
    require(abs(scroll[2] - scroll[0]) <= 1, "Canvas did not return to its starting viewport")
    marks = raw.get("drawCompletions", [])
    require(marks and all(len(m) == 3 and m[1] > 0 and m[2] > 0 for m in marks), "Missing GPU draw completions")
    require(all(a[0] <= b[0] for a, b in zip(marks, marks[1:])), "Nonmonotone draw completions")
    active = [p for p in phases if p["name"].startswith("scroll_")]
    require(all(p["submittedFrames"] > 0 and any(p["startNanos"] <= m[0] < p["endNanos"] for m in marks) for p in active),
            "Scrolling has no rendered frames")
    require(math.isfinite(raw["awtScale"]) and raw["awtScale"] > 0, "Invalid AWT scale")
    return {
        "schema": "vive-egl-scroll-summary/2", "toolkit": raw["toolkit"], "gpu": raw["gpu"],
        "javaRuntime": raw["javaRuntime"], "trace": raw["trace"], "logicalSize": raw["logicalSize"],
        "variant": raw["variant"],
        "appLocation": raw.get("appLocation"),
        "awtScale": raw["awtScale"],
        "interactions": {key: value for key, value in raw["interactions"].items()
                         if key not in ("penInputs", "eraseInputs")} if raw.get("interactions") is not None else None,
        "pointerMeasurements": pointer,
        "bufferSizes": sorted({tuple(m[1:]) for m in marks}), "scroll": scroll,
        "scrollBufferSizes": sorted({tuple(m[1:]) for m in marks
            if any(p["startNanos"] <= m[0] < p["endNanos"] for p in active)}),
        "queueNanos": distribution([r[3] - r[2] for r in rows]),
        "scheduledToDispatchNanos": distribution([r[3] - r[1] for r in rows]),
        "producerLatenessNanos": distribution([r[2] - r[1] for r in rows]),
        "dispatchNanos": distribution([r[4] - r[3] for r in rows]),
        "activeCpuNanos": sum(p["cpuNanos"] for p in active), "phases": phases,
        "drawCompletionIntervalsNanos": {p["name"]: distribution([
            b[0]-a[0] for a, b in zip(marks, marks[1:]) if p["startNanos"] <= a[0] <= b[0] < p["endNanos"]])
            for p in active},
        "limits": [
            "Synthetic AWT wheel delivery, not native pointer/pen/Robot coverage or physical input latency.",
            "Draw completions precede EGL swap; submitted frames are not compositor presentations or displayed FPS.",
            "JFR, the timestamp observer and uncontrolled desktop load perturb execution.",
            "GPU timings and presentation feedback are not collected; CPU/queue changes do not prove equivalent displayed cadence.",
        ],
    }


def capture(args):
    output = runtime.check_new_output(args.output)
    require(args.forks in range(1, 11) and args.events in range(10, 1001), "Invalid fork/event count")
    require(args.strokes in range(1, 40001) and args.columns in range(1, 201), "Invalid fixture dimensions")
    require(args.points in range(3, 129) and args.period_ms in range(1, 101), "Invalid samples/input period")
    require(args.ink_repetitions in range(1, 21), "Invalid ink repetitions")
    require(not args.bundle or args.flatpak_build, "A bundle hash must accompany a Flatpak artifact capture")
    require(not args.bundle or args.bundle.is_file(), "Bundle must be an existing file")
    classpath = runtime.read_classpath(args.classpath_file)
    compiler = runtime.read_classpath(args.compiler_classpath_file)
    sources = [HERE / "Fixture.kt", HERE / "ScrollProbe.java", HERE / "InteractionProbe.java", HERE / "capture.py",
               HERE.parent / "runtime/desktop_runtime.py"]
    identity = {"sourceCommit": runtime.git(PROJECT, "rev-parse", "HEAD"),
                "sourceFiles": runtime.project_snapshot(PROJECT),
                "harness": [{"file": p.name, "sha256": runtime.sha256(p)} for p in sources],
                "classpath": [runtime.artifact(p, PROJECT) for p in classpath],
                "javaModulesSha256": runtime.sha256(args.java.resolve().parent.parent / "lib/modules")}
    if args.flatpak_build:
        build = args.flatpak_build.resolve()
        require(set(classpath) == {str(p) for p in (build / "files/lib").glob("*.jar")},
                "Packaged capture requires exactly the artifact's lib jars")
        require(args.java.resolve() == build / "files/jbr/bin/java", "Packaged JBR must come from the same artifact")
        identity["flatpak"] = {"build": str(build), "metadataSha256": runtime.sha256(build / "metadata"),
                              "bundle": runtime.artifact(str(args.bundle.resolve()), PROJECT) if args.bundle else None}
    output.mkdir(mode=0o700)
    harness = output / "harness"
    harness.mkdir()
    for source in sources:
        shutil.copy2(source, harness / source.name)
    environment = {"kernel": platform.release(), "waylandDisplay": os.environ.get("WAYLAND_DISPLAY")}
    for key, command in (("compositor", ["hyprctl", "-j", "version"]), ("monitors", ["hyprctl", "-j", "monitors"]),
                         ("mesaPackages", ["rpm", "-q", "mesa-dri-drivers", "mesa-libEGL"])):
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=5)
            environment[key] = {"command": command, "exitCode": result.returncode, "output": result.stdout.strip()}
        except (OSError, subprocess.TimeoutExpired) as error:
            environment[key] = {"unavailable": str(error)}
    manifest = {"schema": "vive-egl-scroll-capture/1", "complete": False, "identity": identity,
                "environment": environment,
                "parameters": {k: v for k, v in vars(args).items() if isinstance(v, (int, bool, str))}, "commands": []}
    write(output / "capture.json", manifest)
    env = {k: v for k, v in os.environ.items() if k not in (
        "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "DISPLAY")}
    require(args.contract_only or env.get("WAYLAND_DISPLAY"), "An explicitly selected Wayland display is required")
    classes = output / "probe-classes"
    classes.mkdir()

    def run(role, command, work=output, local_env=env):
        manifest["commands"].append({"role": role, "command": command})
        write(output / "capture.json", manifest)
        runtime.run_logged(command, output / f"{role}.log", work, local_env, 180)

    run("compile-fixture", [str(args.java), "-cp", os.pathsep.join(compiler),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "25",
        "-classpath", os.pathsep.join(classpath), "-d", str(classes), str(HERE / "Fixture.kt")])
    run("compile-driver", [str(args.javac), "--release", "25", "-cp", os.pathsep.join([str(classes), *classpath]),
        "-d", str(classes), str(HERE / "ScrollProbe.java"), str(HERE / "InteractionProbe.java")])
    run("driver-contract", [str(args.java), "-cp", os.pathsep.join([str(classes), *classpath]),
        "com.vivenotes.desktop.rendering.ScrollProbe", "--contract"])
    snapshots = output / "app-classpath"
    snapshots.mkdir()
    frozen_classpath = []
    for index, value in enumerate(classpath):
        path = Path(value)
        if path.is_relative_to(PROJECT):
            destination = snapshots / f"{index}-{path.name}"
            if path.is_dir(): shutil.copytree(path, destination)
            else: shutil.copy2(path, destination)
            frozen_classpath.append(str(destination))
        else: frozen_classpath.append(value)
    require([runtime.artifact(p, PROJECT) for p in classpath] == identity["classpath"], "Build outputs changed while freezing the classpath")
    manifest["frozenClasspath"] = [runtime.artifact(p, output) for p in frozen_classpath]
    command = [str(args.java), "-Xmx2g", "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED", "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "-Dvivenotes.profile=dev", "-Duser.language=en", "-Duser.country=US",
        "-cp", os.pathsep.join([str(classes), *frozen_classpath])]
    reports = []
    try:
        runs = [(fork, variant) for fork in range(args.forks) for variant in (
            (["raster", args.candidate] if fork % 2 == 0 else [args.candidate, "raster"]) if args.variant == "paired" else [args.variant])]
        for fork, variant in runs:
            label = f"{fork + 1}-{variant}"
            work = output / f"fork-{label}"
            work.mkdir(mode=0o700)
            owner = str(uuid.uuid4())
            (work / ".egl-probe-owner").write_text(owner + "\n")
            local_env = env | {"XDG_DATA_HOME": str(work / "data"), "XDG_CONFIG_HOME": str(work / "config"),
                               "XDG_CACHE_HOME": str(work / "cache")}
            fork_command = [*command[:1], f"-Duser.home={work / 'home'}", "-Dvivenotes.ink.viewportRendering=" + variant, *command[1:]]
            fixture = ["com.vivenotes.eglprobe.Fixture", str(work), owner, str(args.strokes)]
            run(f"seed-{label}", [*fork_command, *fixture, "seed", str(args.columns), str(args.points)], work, local_env)
            if args.contract_only:
                report = {"contractOnly": True}
                run(f"fixture-contract-{label}", [*fork_command, *fixture, "contract", str(args.columns), str(args.points)], work, local_env)
            else:
                app_command = [*fork_command, "com.vivenotes.desktop.rendering.ScrollProbe", str(work), owner,
                    str(args.trace).lower(), str(args.events), str(args.period_ms), args.scenario, str(args.ink_repetitions)]
                if args.flatpak_build:
                    app_command = flatpak_command(args.flatpak_build.resolve(), output, classes, work, app_command, local_env)
                run(f"scroll-{label}", app_command, work, local_env)
                raw = json.loads((work / "raw.json").read_text())
                if args.flatpak_build:
                    require(raw.get("appLocation", "").startswith("file:/app/lib/"), "App classes did not load from the Flatpak artifact")
                require(raw["variant"] == variant, "The requested rendering experiment was not selected")
                require(json.loads((work / "closed.json").read_text()).get("normalWindowClose") is True, "App did not close normally")
                report = summarize(raw, args.events)
            report["fork"] = fork + 1
            report["fixture"] = json.loads((work / "fixture.json").read_text())
            run(f"verify-{label}", [*fork_command, *fixture, "verify", str(args.columns), str(args.points)], work, local_env)
            write(work / "summary.json", report)
            reports.append(report)
            if args.contract_only:
                print(f"fork {label}: fixture seeded and verified", flush=True)
            else:
                print(f"fork {label}: queue p95 {report['queueNanos']['p95']/1e6:.3f} ms; active CPU {report['activeCpuNanos']/1e9:.3f} s", flush=True)
        require(runtime.project_snapshot(PROJECT) == identity["sourceFiles"], "Application sources changed during capture")
        require([runtime.artifact(p, PROJECT) for p in classpath] == identity["classpath"], "Application binaries changed during capture")
        require(len({r["fixture"]["sha256"] for r in reports}) == 1, "Fork fixtures differ")
        if not args.contract_only:
            for key in ("logicalSize", "awtScale", "scrollBufferSizes"):
                require(all(report[key] == reports[0][key] for report in reports), "Fork geometry changed: " + key)
        require([{ "file": p.name, "sha256": runtime.sha256(p)} for p in sources] == identity["harness"], "Harness changed during capture")
        require([runtime.artifact(p, output) for p in frozen_classpath] == manifest["frozenClasspath"], "Frozen runtime changed during capture")
        manifest.update(complete=True, reports=reports,
            rawFiles=[{"path": str(p.relative_to(output)), "sha256": runtime.sha256(p)}
                      for p in sorted(output.rglob("*")) if p.is_file() and p.name in ("raw.json", "scroll.jfr", "fixture.json")])
        write(output / "capture.json", manifest)
    except BaseException as error:
        write(output / "failure.json", {"error": str(error)})
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--javac", type=Path, default=Path("/usr/bin/javac"))
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--compiler-classpath-file", required=True, type=Path)
    parser.add_argument("--flatpak-build", type=Path, help="Assembled or bundle-extracted Flatpak directory; app loads /app/lib")
    parser.add_argument("--bundle", type=Path, help="Record the tested bundle hash alongside an extracted Flatpak directory")
    parser.add_argument("--forks", type=int, default=5)
    parser.add_argument("--strokes", type=int, default=419)
    parser.add_argument("--columns", type=int, default=12)
    parser.add_argument("--points", type=int, default=24)
    parser.add_argument("--events", type=int, default=90)
    parser.add_argument("--period-ms", type=int, default=16)
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--variant", choices=("raster", "direct", "moving", "paired"), default="raster")
    parser.add_argument("--candidate", choices=("direct", "moving"), default="moving")
    parser.add_argument("--scenario", choices=("scroll", "interactions"), default="scroll")
    parser.add_argument("--ink-repetitions", type=int, default=1, help="Pairs of pen strokes per interaction phase")
    parser.add_argument("--contract-only", action="store_true")
    capture(parser.parse_args())
