#!/usr/bin/env python3
"""Opt-in real-desktop diagnostic on a newly owned synthetic profile. Standard library only."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import time
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
SOURCE = HERE / "DesktopRuntimeProbe.kt"
PROBE_SOURCES = (SOURCE, HERE / "RuntimeRenderProbe.kt")
MAIN = "com.vivenotes.runtimeprobe.DesktopRuntimeProbe"
SCHEMA = "vive-desktop-runtime-capture/1"
PROBE_SCHEMA = "vive-desktop-runtime-probe/1"
PHASES = ("idle_initial", "robot_ink", "autosave_settle", "robot_viewport", "robot_lasso",
          "robot_text_page", "text_autosave_settle", "robot_return_ink", "idle_final")
MAIN_CLASSES = ("com/vivenotes/desktop/MainKt.class", "com/vivenotes/data/db/NotesDatabase.class")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    value = json.loads(Path(path).read_text())
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def source_hashes():
    return [{"path": path.name, "sha256": sha256(path)} for path in PROBE_SOURCES]


def probe_stamp():
    return hashlib.sha256(json.dumps(source_hashes(), sort_keys=True).encode()).hexdigest()


def compiled_probe(classes):
    required = (classes / (MAIN.replace(".", "/") + ".class"),
                classes / "com/vivenotes/runtimeprobe/ProbePhase.class")
    require(classes.is_dir() and all(path.is_file() for path in required), "Precompiled probe is missing real Main/Phase classes")
    files = sorted(classes.rglob("*.class"))
    for path in files:
        header = path.read_bytes()[:8]
        require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe" and struct.unpack(">H", header[6:8])[0] == 69,
                "Probe classes must be valid Kotlin JVM-target25 class payloads")
    require(any("RuntimeRenderProbe" in path.name for path in files), "Precompiled probe lacks the render observer")
    return [{"path": path.relative_to(classes).as_posix(), "sha256": sha256(path)} for path in files]


def project_snapshot(project):
    paths = {path.relative_to(project).as_posix() for directory in (
        "shared/src", "desktopApp/src", "shared/schemas") for path in (project / directory).rglob("*") if path.is_file()}
    paths.update(path for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "gradle/libs.versions.toml", "shared/build.gradle.kts", "desktopApp/build.gradle.kts",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties") if (project / path).is_file())
    return [{"path": path, "sha256": sha256(project / path)} for path in sorted(paths)]


def git(project, *args):
    return subprocess.check_output(["git", "-c", "log.showSignature=false", *args], cwd=project, text=True).strip()


def source_identity(project, expected_commit=None, source_manifest=None):
    files = project_snapshot(project)
    if source_manifest:
        inventory = read_json(source_manifest)
        commit = inventory.get("sourceCommit", "")
        require(inventory.get("schema") == "vive-runtime-source-inventory/1" and
                re.fullmatch(r"[0-9a-f]{40}", commit) is not None,
                "Invalid exported source inventory")
        require(inventory.get("sourceFiles") == files, "Exported source bytes differ from their inventory")
        require(not expected_commit or (re.fullmatch(r"[0-9a-f]{7,40}", expected_commit) is not None and
                commit.startswith(expected_commit)), "Declared source commit differs from inventory")
        return {"sourceCommit": commit, "sourceFiles": files, "workingTree": None,
                "sourceIdentity": "caller-exported inventory; no Git checkout on capture host",
                "sourceInventorySha256": sha256(source_manifest)}
    commit = git(project, "rev-parse", "HEAD")
    if expected_commit:
        require(commit == git(project, "rev-parse", expected_commit + "^{commit}"),
                "Declared source commit differs from checkout HEAD")
    return {"sourceCommit": commit, "sourceFiles": files, "workingTree": git(project, "status", "--short"),
            "sourceIdentity": "local Git checkout"}


def entry_points(classpath, project):
    result = []
    for name in MAIN_CLASSES:
        provider = None
        for value in classpath:
            path = Path(value)
            if path.is_dir():
                found = (path / name).is_file()
            else:
                with zipfile.ZipFile(path) as archive:
                    found = name in archive.namelist()
            if found:
                provider = path
                break
        require(provider is not None and provider.is_relative_to(project), f"App class must resolve from the declared project: {name}")
        result.append({"class": name, "provider": str(provider)})
    return result


def provenance(args, classpath):
    project = args.project_root.resolve()
    source = source_identity(project, args.expected_commit, getattr(args, "source_manifest", None))
    tool_files = [HERE / name for name in ("desktop_runtime.py", "test_desktop_runtime.py", "USAGE.md",
                 "classpath.init.gradle", "DesktopRuntimeProbe.kt", "RuntimeRenderProbe.kt")]
    tools = [{"path": path.name, "sha256": sha256(path)} for path in tool_files]
    executables = [{"role": name, "path": str(path.resolve()), "sha256": sha256(path.resolve())}
                   for name, path in (("java", args.java), ("jcmd", args.jcmd), ("kotlinc", args.kotlinc), ("jfr", args.jfr_tool)) if path]
    compiler = read_classpath(args.compiler_classpath_file) if args.compiler_classpath_file else []
    return {**source, "projectRoot": str(project), "toolFiles": tools, "executables": executables,
        "classpathFile": {"path": str(args.classpath_file), "sha256": sha256(args.classpath_file)},
        "runtimeArtifacts": [artifact(path, project) for path in classpath],
        "compilerArtifacts": [artifact(path, project) for path in compiler], "projectEntryPoints": entry_points(classpath, project),
        "javaRuntimeArtifacts": [artifact(str(path), project) for relative in (
            "release", "lib/modules", "lib/server/libjvm.so", "lib/server/libjvm.dylib", "bin/server/jvm.dll",
            "lib/libawt.so", "lib/libawt_xawt.so", "lib/libawt_wl.so", "bin/awt.dll")
            if (path := args.java.parent.parent / relative).is_file()],
        "probeSources": source_hashes(),
        "suppliedProbeClasses": compiled_probe(args.probe_classes.resolve()) if args.probe_classes else None,
        "bindingScope": "caller-built classpath: source and binary bytes are frozen independently; build execution is verified separately"}


def check_frozen(args, classpath, initial, classes=None, compiled=None):
    require(provenance(args, classpath) == initial, "Source/tool/Java/compiler/classpath provenance changed during capture")
    if classes:
        require(compiled_probe(classes) == compiled and (classes / "probe-source.sha256").read_text().strip() == probe_stamp(),
                "Compiled probe changed during capture")


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_classpath(path: Path) -> list[str]:
    content = path.read_text().strip()
    values = json.loads(content) if content.startswith("[") else [
        item for line in content.splitlines() for item in line.split(os.pathsep) if item]
    if not values or not all(isinstance(item, str) for item in values):
        raise ValueError("Classpath must be a JSON string array, one path per line, or a platform classpath")
    result = []
    for value in values:
        entry = Path(value).expanduser()
        entry = (entry if entry.is_absolute() else path.parent / entry).resolve()
        if not entry.is_file() and not entry.is_dir():
            raise ValueError(f"Classpath entry is absent: {entry}")
        result.append(str(entry))
    return result


def check_new_output(path: Path, project: Path = PROJECT) -> Path:
    if path.expanduser().exists() or path.expanduser().is_symlink():
        raise ValueError("Output must be a new directory; existing profiles are never reused or overwritten")
    output = path.expanduser().resolve()
    if any(output.is_relative_to(root.resolve()) for root in (PROJECT, project)):
        raise ValueError("Raw JFR/profile output must stay outside the repository")
    if output.exists() or output.is_symlink():
        raise ValueError("Output must be a new directory; existing profiles are never reused or overwritten")
    for parent in output.parents:
        existing = parent / "environment.json"
        if existing.is_file():
            try:
                previous = read_json(existing)
            except (ValueError, OSError):
                continue
            require(previous.get("schema") != SCHEMA, "Output must be a new sibling, never nested in an existing capture")
    if any(character in str(output) for character in (",", "\n", "\r")):
        raise ValueError("Output path cannot contain JFR option separators or newlines")
    return output


def artifact(path: str, project: Path) -> dict:
    target = Path(path)
    if target.is_file():
        digest = sha256(target)
    else:
        contents = hashlib.sha256()
        for item in sorted(p for p in target.rglob("*") if p.is_file()):
            contents.update(item.relative_to(target).as_posix().encode() + b"\0")
            contents.update(bytes.fromhex(sha256(item)))
        digest = contents.hexdigest()
    return {"name": target.name, "sha256": digest, "kind": "file" if target.is_file() else "directory",
            "projectArtifact": target.is_relative_to(project.resolve()), "path": str(target)}


def linux_process_sample(pid: int, proc_root: Path = Path("/proc")) -> dict:
    status = {}
    for line in (proc_root / str(pid) / "status").read_text().splitlines():
        key, _, value = line.partition(":")
        status[key] = value.strip()
    # A process name may itself contain spaces or parentheses.
    fields = (proc_root / str(pid) / "stat").read_text().rpartition(")")[2].split()
    ticks = os.sysconf("SC_CLK_TCK")
    size = lambda key: int(status[key].split()[0]) * 1024 if key in status else None
    return {"rssBytes": size("VmRSS"), "highWaterRssBytes": size("VmHWM"),
            "virtualBytes": size("VmSize"), "threads": int(status["Threads"]),
            "processCpuSeconds": (int(fields[11]) + int(fields[12])) / ticks}


def windows_process_sample(pid: int) -> dict:
    import ctypes
    from ctypes import wintypes

    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize",
                "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    psapi = ctypes.WinDLL("psapi", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    handle = kernel.OpenProcess(0x0400 | 0x0010, False, pid)
    if not handle:
        raise OSError(ctypes.get_last_error(), "OpenProcess failed")
    try:
        memory = Counters()
        memory.cb = ctypes.sizeof(memory)
        if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb):
            raise OSError(ctypes.get_last_error(), "GetProcessMemoryInfo failed")
        creation, end, system, user = (wintypes.FILETIME() for _ in range(4))
        if not kernel.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(end),
                                     ctypes.byref(system), ctypes.byref(user)):
            raise OSError(ctypes.get_last_error(), "GetProcessTimes failed")
        ticks = lambda value: (value.dwHighDateTime << 32) | value.dwLowDateTime
        return {"rssBytes": memory.WorkingSetSize, "highWaterRssBytes": memory.PeakWorkingSetSize,
                "processCpuSeconds": (ticks(system) + ticks(user)) / 10_000_000}
    finally:
        kernel.CloseHandle(handle)


def read_events(path: Path, final: bool = False) -> list[dict]:
    if not path.exists():
        return []
    result = []
    lines = path.read_text().splitlines(keepends=True)
    require(not final or not lines or lines[-1].endswith("\n"), "Final event record is truncated")
    for index, line in enumerate(lines):
        try:
            result.append(json.loads(line))
        except json.JSONDecodeError:
            # The process may currently be appending the last line.
            if index != len(lines) - 1 or line.endswith("\n"):
                raise
    return result


def run_logged(command: list[str], output: Path, work: Path, env: dict, timeout: float) -> None:
    with output.open("w") as log:
        result = subprocess.run(command, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT,
                                timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {output}")


def compile_probe(args: argparse.Namespace, output: Path, classpath: list[str], env: dict) -> Path:
    source_hash = probe_stamp()
    if args.probe_classes:
        classes = args.probe_classes.resolve()
        marker = classes / "probe-source.sha256"
        if not classes.is_dir() or not marker.is_file() or marker.read_text().strip() != source_hash:
            raise ValueError("Precompiled --probe-classes needs probe-source.sha256 matching this exact Kotlin source")
        original = compiled_probe(classes)
        owned = output / "probe-classes"
        shutil.copytree(classes, owned)
        require(compiled_probe(owned) == original and compiled_probe(classes) == original,
                "Supplied probe changed while copying its immutable snapshot")
        return owned
    classes = output / "probe-classes"
    classes.mkdir()
    options = [*[str(path) for path in PROBE_SOURCES], "-classpath", os.pathsep.join(classpath), "-jvm-target", "25", "-d", str(classes)]
    if args.kotlinc:
        command = [str(args.kotlinc), *options]
    elif args.compiler_classpath_file:
        compiler = read_classpath(args.compiler_classpath_file.resolve())
        command = [str(args.java), "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", *options]
    else:
        raise ValueError("Supply --probe-classes, --kotlinc, or --compiler-classpath-file")
    run_logged(command, output / "compile-log.txt", output, env, 120)
    require(probe_stamp() == source_hash, "Probe source changed during compilation")
    compiled_probe(classes)
    (classes / "probe-source.sha256").write_text(source_hash + "\n")
    return classes


def validate_events(events, nmt, strokes=None):
    expected = [("main_enter", "instant"), ("frame_visible", "instant"), ("workspace_ready", "instant")]
    for phase in PHASES:
        if nmt and phase in ("idle_initial", "idle_final"):
            suffix = "initial" if phase == "idle_initial" else "final"
            expected += [(f"nmt_{suffix}_request", "instant"), (f"nmt_{suffix}_complete", "instant")]
        expected += [(phase, "begin"), (phase, "end")]
    expected += [("driver_complete", "instant"), ("window_close_request", "instant"), ("process_shutdown", "instant")]
    require([(event.get("phase"), event.get("state")) for event in events] == expected,
            "Event phases must be complete, unique and ordered, without driver failures")
    previous = None
    beginnings = {}
    for event in events:
        require(event.get("schema") == PROBE_SCHEMA, "Unexpected event schema")
        clock = event.get("nanoTime")
        require(type(clock) is int and (previous is None or clock > previous), "Event clocks are not monotone")
        previous = clock
        require(type(event.get("processCpuNanos")) is int and event["processCpuNanos"] >= 0, "Process CPU event measurement is unavailable")
        if event["state"] == "begin":
            beginnings[event["phase"]] = event
        if event["state"] == "end":
            elapsed = event.get("elapsedNanos")
            require(type(elapsed) is int and 0 <= elapsed <= clock - beginnings[event["phase"]]["nanoTime"], "Invalid phase duration")
            require(isinstance(event.get("evidence"), dict), "Phase completion lacks interaction evidence")
    ends = {event["phase"]: event for event in events if event["state"] == "end"}
    for name, event in ends.items():
        proof = event["evidence"]
        before, after = proof.get("completedFramesBefore"), proof.get("completedFramesAfter")
        require(type(before) is int and type(after) is int and after >= before >= 0, "Missing completed-render callback observations")
        require(not name.startswith("robot_") or after > before, "Robot phase has no completed render callback")
    ink = ends["robot_ink"]["evidence"]
    count = ink.get("injectedStrokes") if strokes is None else strokes
    require(type(count) is int and count > 0 and ink.get("injectedStrokes") == count and ink.get("penSelected") is True and
            ends["autosave_settle"]["evidence"].get("persistedRobotStrokes") == count, "Ink injection/save evidence differs")
    viewport = ends["robot_viewport"]["evidence"]
    for key in ("scrollBefore", "scrollDown", "scrollRestored", "zoomBefore", "zoomChanged", "zoomRestored"):
        require(type(viewport.get(key)) in (int, float) and math.isfinite(viewport[key]), "Missing finite viewport change evidence")
    require(viewport["scrollDown"] > viewport["scrollBefore"] and abs(viewport["scrollRestored"] - viewport["scrollBefore"]) < 1 and
            viewport["zoomBefore"] > 0 and viewport["zoomChanged"] > viewport["zoomBefore"] and
            abs(viewport["zoomRestored"] - viewport["zoomBefore"]) < .0001, "Scroll/zoom did not change and restore")
    require(all(ends["robot_lasso"]["evidence"].get(key) is True for key in ("lassoSelectionObserved", "selectionCleared")), "Lasso interaction lacks proof")
    require(ends["robot_text_page"]["evidence"].get("selectedPage") == "runtime-probe-text" and
            ends["robot_text_page"]["evidence"].get("editorTokenObserved") is True and
            ends["text_autosave_settle"]["evidence"].get("persistedTextToken") is True and
            ends["robot_return_ink"]["evidence"].get("selectedPage") == "runtime-probe-ink" and
            ends["robot_return_ink"]["evidence"].get("inkReady") is True, "Page/text interaction lacks proof")
    ready = next(event for event in events if event["phase"] == "workspace_ready")
    require(ready.get("evidence", {}).get("selectedPage") == "runtime-probe-ink" and
            ready["evidence"].get("inkReady") is True and ready["evidence"].get("completedFrames", 0) > 0,
            "Workspace readiness lacks ink/render evidence")
    return ends


def validate_sample(sample):
    for name in ("rssBytes", "highWaterRssBytes", "processCpuSeconds", "elapsedSeconds"):
        value = sample.get(name)
        require(type(value) in (int, float) and value >= 0 and value < float("inf"), "Invalid process sampler observation")


def record_process_sample(sample, valid_samples, sample_log):
    """Retain partial /proc exit observations, but never include them in numeric summaries."""
    raw = dict(sample)
    try:
        validate_sample(sample)
    except ValueError as failure:
        raw["unavailable"] = str(failure)
        available = False
    else:
        valid_samples.append(sample)
        available = True
    sample_log.write(json.dumps(raw) + "\n")
    sample_log.flush()
    return available


def validate_samples(samples):
    require(len(samples) >= 2, "At least two valid process CPU/RSS samples are required")
    previous_cpu = -1
    previous_elapsed = -1
    for sample in samples:
        validate_sample(sample)
        require(sample["processCpuSeconds"] >= previous_cpu and sample["elapsedSeconds"] > previous_elapsed,
                "Process CPU/sample clocks are not monotone")
        previous_cpu, previous_elapsed = sample["processCpuSeconds"], sample["elapsedSeconds"]


def validate_fixture(work, args, contract=False):
    fixture = read_json(work / "fixture.json")
    counts = (4, 2, 64) if contract else (args.ink_count, args.text_outlines, args.characters)
    require(fixture.get("schema") == PROBE_SCHEMA and
            (fixture.get("inkRows"), fixture.get("textOutlines"), fixture.get("charactersPerOutline")) == counts,
            "Seeded fixture counts/schema differ")
    for key in ("fixtureSha256", "inkCorpusSha256"):
        require(isinstance(fixture.get(key), str) and re.fullmatch(r"[0-9a-f]{64}", fixture[key]), "Missing canonical fixture hash")
    require(fixture.get("automaticModelDownload") is False and fixture.get("automaticMathInstall") is False and
            fixture.get("account") == "absent", "Fixture must suppress automatic installs and contain no account")
    return fixture


def validate_verification(work, fixture, strokes):
    verified = read_json(work / "verification.json")
    require(verified.get("schema") == PROBE_SCHEMA and verified.get("passed") is True and
            verified.get("fixtureSha256") == fixture["fixtureSha256"] and verified.get("originalInkRows") == fixture["inkRows"] and
            verified.get("persistedRobotStrokes") == strokes and verified.get("persistedTextToken") is (strokes > 0) and
            verified.get("originalTextIntact") is True and verified.get("tokenOccurrences") == (1 if strokes else 0),
            "Final verification JSON does not prove exact original data and requested edits")
    require(verified.get("addedNativeInkValid") is True and verified.get("unexpectedEraseOrMove") is False and
            (verified.get("notebooks"), verified.get("sections"), verified.get("pages")) == (1, 1, 2),
            "Final native ink/page/tree verification differs")
    return verified


def validate_jfr(path):
    require(path.is_file() and path.stat().st_size >= 68, "Missing/non-finalized JFR recording")
    with path.open("rb") as source:
        offset = 0
        size = path.stat().st_size
        while offset < size:
            source.seek(offset)
            header = source.read(68)
            require(len(header) == 68 and header[:4] == b"FLR\0", "Invalid JFR chunk header")
            chunk, pool, metadata = struct.unpack(">qqq", header[8:32])
            require(chunk >= 68 and offset + chunk <= size and 0 <= pool < chunk and 0 < metadata < chunk,
                    "JFR chunk is incomplete or missing metadata")
            offset += chunk


def read_jfr_phases(path):
    data = read_json(path)
    events = data.get("recording", {}).get("events")
    require(isinstance(events, list) and [event.get("values", {}).get("phase") for event in events] == list(PHASES) and
            all(event.get("type") == "com.vivenotes.RuntimeProbePhase" and event.get("values", {}).get("outcome") == "complete" for event in events),
            "JFR must parse and contain every successful scripted phase exactly once")


def normalized(value, project, work, output):
    if isinstance(value, str):
        for path, label in ((work, "<profile>"), (output, "<output>"), (project, "<project>")):
            value = re.sub(re.escape(str(path)) + r"(?=$|[/\\,])", label, value)
        return value
    if isinstance(value, list):
        return [normalized(item, project, work, output) for item in value]
    return value


def validate_complete_capture(directory):
    """Read-only integrity gate for comparison and external analysis; returns the environment dict."""
    directory = Path(directory).resolve()
    manifest = read_json(directory / "environment.json")
    require(manifest.get("schema") == SCHEMA and manifest.get("complete") is True, "Capture is incomplete")
    require((directory / "capture-manifest.sha256").is_file() and
            (directory / "capture-manifest.sha256").read_text().strip() == sha256(directory / "environment.json"),
            "Capture manifest changed after completion")
    raw = manifest.get("rawFiles")
    require(isinstance(raw, list) and raw, "Capture lacks immutable raw-file hashes")
    seen = set()
    for row in raw:
        path = Path(row["path"])
        require(not path.is_absolute() and ".." not in path.parts and str(path) not in seen,
                "Invalid/duplicate raw evidence path")
        seen.add(str(path))
        require((directory / path).resolve().is_relative_to(directory) and sha256(directory / path) == row["sha256"],
                "Raw evidence changed after capture")
    summary = read_json(directory / "summary.json")
    require(summary.get("schema") == SCHEMA and summary.get("verified") is True and "summary.json" in seen,
            "Capture lacks a verified immutable summary")
    if not summary.get("contractOnly"):
        require({"runtime.jfr", "jfr-phases-log.txt", "process.jsonl", "isolated-profile/events.jsonl",
                 "isolated-profile/runtime.json", "isolated-profile/rendering.json", "isolated-profile/fixture.json",
                 "isolated-profile/verification.json"} <= seen, "Capture is missing required raw evidence")
        validate_events(read_events(directory / "isolated-profile/events.jsonl", final=True), manifest["nmtSnapshotsRequested"], manifest["strokes"])
        validate_jfr(directory / "runtime.jfr")
        read_jfr_phases(directory / "jfr-phases-log.txt")
    return manifest


def compare_captures(before, after, output):
    before, after = before.resolve(), after.resolve()
    destination = check_new_output(output)
    require(not destination.is_relative_to(before) and not destination.is_relative_to(after), "Comparison cannot be nested in raw captures")
    records = []
    for directory in (before, after):
        manifest = validate_complete_capture(directory)
        summary = read_json(directory / "summary.json")
        require(manifest.get("complete") is True and summary.get("verified") is True and summary.get("contractOnly") is False,
                "Only complete verified real-app captures can be compared")
        for row in manifest["rawFiles"]:
            require(sha256(directory / row["path"]) == row["sha256"], "Raw evidence changed after capture")
        work = directory / "isolated-profile"
        fixture = read_json(work / "fixture.json")
        verify = read_json(work / "verification.json")
        events = read_events(work / "events.jsonl", final=True)
        validate_events(events, bool(manifest["nmtSnapshotsRequested"]), manifest["strokes"])
        runtime = read_json(work / "runtime.json")
        config = {key: manifest[key] for key in ("backend", "strokes", "heap", "sampleSeconds", "jfrEnabled", "nmtEnabled", "nmtSnapshotsRequested")}
        config.update(probeSources=manifest["provenance"]["probeSources"], toolFiles=manifest["provenance"]["toolFiles"],
            fixture=fixture, externalArtifacts=[{key: item[key] for key in ("name", "sha256", "kind")}
                for item in manifest["provenance"]["runtimeArtifacts"] if not item["projectArtifact"]],
            executables=[{key: item[key] for key in ("role", "sha256")} for item in manifest["provenance"]["executables"] if item["role"] in ("java", "jcmd", "jfr")],
            vmArgs=normalized(runtime["vmArgs"], Path(manifest["provenance"]["projectRoot"]), work, directory),
            runtime={key: value for key, value in runtime.items() if key not in ("vmArgs", "classPath", "userDir")},
            javaRuntimeArtifacts=[{key: item[key] for key in ("name", "sha256", "kind")} for item in manifest["provenance"]["javaRuntimeArtifacts"]],
            host={key: manifest[key] for key in ("platform", "cpu", "logicalCpus", "affinity")}, nmtOutcomes=summary["nmtSnapshots"],
            originalRows=verify.get("inkCorpusSha256", fixture["inkCorpusSha256"]),
            phaseCoverage=[(event["phase"], event["state"]) for event in events])
        records.append((manifest, summary, config))
    require(records[0][2] == records[1][2], "Runtime/flags/dependencies/instrumentation/fixture/backend/phase identity differs")
    result = {"schema": "vive-desktop-runtime-comparison/1", "compatibilityPassed": True,
        "before": {"label": records[0][0]["label"], "sourceCommit": records[0][0]["provenance"]["sourceCommit"], "summary": records[0][1]},
        "after": {"label": records[1][0]["label"], "sourceCommit": records[1][0]["provenance"]["sourceCommit"], "summary": records[1][1]},
        "scope": "Paired scripted real-app diagnostics; source revisions/labels explicitly reported. Frame counts may differ and remain visible; no physical latency/FPS claim."}
    destination.mkdir(parents=True, mode=0o700)
    write_json(destination / "comparison.json", result)
    return destination


def capture(args: argparse.Namespace) -> Path:
    output = check_new_output(args.output, args.project_root)
    args.project_root = args.project_root.expanduser().resolve()
    for name in ("java", "jcmd", "kotlinc", "classpath_file", "compiler_classpath_file", "jfr_tool", "source_manifest"):
        value = getattr(args, name, None)
        if value:
            setattr(args, name, value.expanduser().resolve())
    if not args.contract_only and args.jfr_tool is None:
        args.jfr_tool = args.java.with_name("jfr.exe" if os.name == "nt" else "jfr")
    for name in ("java", "jcmd", "kotlinc", "jfr_tool"):
        value = getattr(args, name)
        require(value is None or value.is_file(), f"Missing executable: {name}")
    require(args.contract_only or args.owned_display, "Real input requires --owned-display")
    require(args.contract_only or args.backend != "x11" or args.display, "X11 input requires --display")
    require(1 <= args.strokes <= 256 and .1 <= args.sample_seconds <= 10 and args.timeout_seconds > 0, "Invalid operation/sampling/timeout configuration")
    reserved = ("@", "-cp", "-classpath", "--class-path", "-jar", "-javaagent", "-agentlib", "-agentpath", "-Xmx",
                "-Duser.dir", "-Duser.home", "-Dvivenotes.profile", "-Djava.util.prefs", "-XX:StartFlightRecording", "-XX:NativeMemoryTracking")
    require(all(not value.startswith(reserved) for value in args.jvm_arg), "JVM argument overrides controlled isolation/instrumentation")
    classpath = read_classpath(args.classpath_file)
    initial = provenance(args, classpath)
    output.mkdir(parents=True, mode=0o700)
    manifest = {"schema": SCHEMA, "complete": False, "startedAt": datetime.now(timezone.utc).isoformat(),
        "label": args.label or initial["sourceCommit"], "provenance": initial, "platform": platform.platform(),
        "cpu": platform.processor(), "logicalCpus": os.cpu_count(),
        "affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "heap": args.heap, "jvmArgs": args.jvm_arg, "backend": args.backend, "sampleSeconds": args.sample_seconds,
        "display": args.display, "contractOnly": args.contract_only, "strokes": args.strokes,
        "jfrEnabled": not args.contract_only, "nmtEnabled": not args.contract_only,
        "nmtSnapshotsRequested": bool(args.jcmd) and not args.contract_only,
        "measurement": "Scripted process/JVM/render-callback diagnostics; no physical input latency, FPS or presentation timestamp"}
    write_json(output / "environment.json", manifest)
    try:
        result = capture_owned(args, output, classpath, initial, manifest)
        check_frozen(args, classpath, initial)
        manifest["rawFiles"] = [{"path": path.relative_to(output).as_posix(), "sha256": sha256(path)}
            for path in sorted(output.rglob("*")) if path.is_file() and path.name != "environment.json"]
        manifest.update(complete=True, completedAt=datetime.now(timezone.utc).isoformat())
        write_json(output / "environment.json", manifest)
        (output / "capture-manifest.sha256").write_text(sha256(output / "environment.json") + "\n")
        return result
    except BaseException as failure:
        manifest["failure"] = {"type": type(failure).__name__, "message": str(failure)}
        write_json(output / "environment.json", manifest)
        raise


def capture_owned(args, output, classpath, initial, manifest):
    work = output / "isolated-profile"
    work.mkdir(mode=0o700)
    owner = str(uuid.uuid4())
    (work / ".vive-runtime-probe-owner").write_text(owner + "\n")
    env = os.environ.copy()
    for variable in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(variable, None)
    env.update({"XDG_DATA_HOME": str(work / "xdg-data"), "XDG_CONFIG_HOME": str(work / "xdg-config"),
                "XDG_CACHE_HOME": str(work / "xdg-cache")})
    if args.backend == "x11":
        env.pop("WAYLAND_DISPLAY", None)
        env["XDG_SESSION_TYPE"] = "x11"
        if args.display:
            env["DISPLAY"] = args.display
    classes = compile_probe(args, output, classpath, env)
    compiled = compiled_probe(classes)
    check_frozen(args, classpath, initial, classes, compiled)
    manifest.update(probeClasses={"path": str(classes), "files": compiled},
                    javaVersion=subprocess.check_output([str(args.java), "-version"], stderr=subprocess.STDOUT, text=True, env=env).strip())
    check_frozen(args, classpath, initial, classes, compiled)
    write_json(output / "environment.json", manifest)
    command = [str(args.java), f"-Xmx{args.heap}", *args.jvm_arg,
        "-Dvivenotes.profile=dev", "-Duser.language=en", "-Duser.country=US",
        f"-Duser.home={work / 'home'}", f"-Djava.util.prefs.userRoot={work / 'preferences'}",
        "-cp", os.pathsep.join([str(classes), *classpath]), MAIN]
    options = ["--work-dir", str(work), "--owner-token", owner, "--backend", args.backend,
        "--strokes", str(args.strokes), "--nmt-handshake", str(bool(args.jcmd)).lower()]
    fixture_options = ["--ink-count", str(4 if args.contract_only else args.ink_count),
        "--text-outlines", str(2 if args.contract_only else args.text_outlines),
        "--characters", str(64 if args.contract_only else args.characters)]

    def child(role, cmd, timeout=args.timeout_seconds):
        manifest.setdefault("commands", []).append({"role": role, "command": cmd})
        write_json(output / "environment.json", manifest)
        print(f"runtime probe: {role}", flush=True)
        run_logged(cmd, output / f"{role}-log.txt", work, env, timeout)
        check_frozen(args, classpath, initial, classes, compiled)

    for role, key, count in (("probe-contract", "textValidationCases", 6), ("render-contract", "checks", 17)):
        child(role, [*command, "--mode", role, *options])
        outcome = read_json(work / (role + ".json"))
        require(outcome.get("passed") is True and outcome.get(key) == count, "Probe-only deterministic contracts failed")
        if role == "probe-contract":
            require(outcome.get("pollingCases") == 2, "Bounded control-republication polling contracts failed")
    child("seed", [*command, "--mode", "seed", *options, *fixture_options])
    fixture = validate_fixture(work, args, args.contract_only)
    if args.contract_only:
        verify_options = ["--work-dir", str(work), "--owner-token", owner, "--backend", args.backend, "--strokes", "0"]
        child("verify", [*command, "--mode", "verify", *verify_options])
        verification = validate_verification(work, fixture, 0)
        write_json(output / "summary.json", {"schema": SCHEMA, "contractOnly": True, "verified": True,
            "fixtureSha256": fixture["fixtureSha256"], "verification": verification})
        return output
    app_command = [command[0], "-XX:NativeMemoryTracking=summary",
        f"-XX:StartFlightRecording=filename={output / 'runtime.jfr'},settings=profile,dumponexit=true",
        *command[1:], "--mode", "app", *options]
    manifest["commands"].append({"role": "app", "command": app_command})
    write_json(output / "environment.json", manifest)
    samples, observed_nmt, successful_nmt = [], set(), set()
    unavailable_samples = 0
    launch_epoch, launch = time.time_ns(), time.monotonic()
    with (output / "app-log.txt").open("w") as log, (output / "process.jsonl").open("w") as sample_log:
        process = subprocess.Popen(app_command, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                require(time.monotonic() - launch <= args.timeout_seconds, "Owned app exceeded timeout; diagnostic incomplete")
                try:
                    sample = windows_process_sample(process.pid) if os.name == "nt" else linux_process_sample(process.pid)
                    sample.update(elapsedSeconds=time.monotonic() - launch, epochNanos=time.time_ns())
                    unavailable_samples += int(not record_process_sample(sample, samples, sample_log))
                except (OSError, ValueError, KeyError) as failure:
                    unavailable_samples += 1
                    sample_log.write(json.dumps({"elapsedSeconds": time.monotonic() - launch, "unavailable": str(failure)}) + "\n")
                    sample_log.flush()
                if args.jcmd:
                    for event in read_events(work / "events.jsonl"):
                        name = {"nmt_initial_request": "idle_initial", "nmt_final_request": "idle_final"}.get(event.get("phase"))
                        if name and name not in observed_nmt:
                            observed_nmt.add(name)
                            status = "unavailable"
                            try:
                                run_logged([str(args.jcmd), str(process.pid), "VM.native_memory", "summary", "scale=KB"], output / f"nmt-{name}.txt", work, env, 8)
                                require("Native Memory Tracking" in (output / f"nmt-{name}.txt").read_text(), "NMT attach did not return a memory report")
                                successful_nmt.add(name)
                                status = "captured"
                            except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as failure:
                                (output / f"nmt-{name}-unavailable.txt").write_text(str(failure) + "\n")
                            finally:
                                check_frozen(args, classpath, initial, classes, compiled)
                                write_json(work / f"nmt-{name}.done", {"status": status})
                time.sleep(args.sample_seconds)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    exited = time.monotonic()
    require(process.returncode == 0, f"Owned app failed ({process.returncode}); inspect retained app log")
    check_frozen(args, classpath, initial, classes, compiled)
    events = read_events(work / "events.jsonl", final=True)
    ends = validate_events(events, bool(args.jcmd), args.strokes)
    validate_samples(samples)
    runtime = read_json(work / "runtime.json")
    require(runtime.get("userDir") == str(work) and runtime.get("classPath") == os.pathsep.join([str(classes), *classpath]),
            "Actual JVM working directory/classpath differs from isolated launch")
    require(runtime.get("schema") == PROBE_SCHEMA and runtime.get("profile") == "dev" and
            runtime.get("requestedBackend") == args.backend and isinstance(runtime.get("vmArgs"), list), "Actual runtime identity is missing")
    required_toolkit = {"x11": "XToolkit", "native": "WLToolkit", "windows": "WToolkit"}[args.backend]
    require(required_toolkit in runtime.get("awtToolkit", ""), "Actual toolkit differs from requested backend")
    rendering = read_json(work / "rendering.json")
    observations = rendering.get("observations", {})
    require(rendering.get("schema") == "vive-runtime-render-observation/1" and observations.get("closed") is True and
            observations.get("inFlightCallbacks") == 0 and observations.get("completedCallbacks", 0) > 0 and
            observations.get("failedCallbacks") == 0,
            "Render observer did not close after completed callbacks")
    validate_jfr(output / "runtime.jfr")
    child("jfr-phases", [str(args.jfr_tool), "print", "--json", "--events", "com.vivenotes.RuntimeProbePhase", str(output / "runtime.jfr")], timeout=30)
    read_jfr_phases(output / "jfr-phases-log.txt")
    child("verify", [*command, "--mode", "verify", *options])
    require(read_json(work / "fixture.json") == fixture, "Seed fixture metadata changed during application run")
    verification = validate_verification(work, fixture, args.strokes)
    ready = next(event for event in events if event["phase"] == "workspace_ready")
    closing = next(event for event in events if event["phase"] == "window_close_request")
    shutdown = events[-1]
    summary = {"schema": SCHEMA, "contractOnly": False, "verified": True, "processExitCode": process.returncode,
        "fixtureSha256": fixture["fixtureSha256"], "verification": verification, "rendering": rendering,
        "jvmMainToWorkspaceNanos": ready["elapsedNanos"],
        "launchToWorkspaceEpochMillis": ready["epochMillis"] - launch_epoch // 1_000_000,
        "processLifetimeSeconds": exited - launch,
        "phaseNanos": {name: event["elapsedNanos"] for name, event in ends.items()},
        "phaseEvidence": [{"phase": name, "evidence": event["evidence"]} for name, event in ends.items()],
        "closeRequestToShutdownHookNanos": shutdown["nanoTime"] - closing["nanoTime"],
        "sampledPeakRssBytes": max(sample["rssBytes"] for sample in samples),
        "highWaterRssBytes": max(sample["highWaterRssBytes"] for sample in samples),
        "lastSampledProcessCpuSeconds": samples[-1]["processCpuSeconds"],
        "validProcessSamples": len(samples), "unavailableProcessSamples": unavailable_samples,
        "shutdownHookProcessCpuNanos": shutdown["processCpuNanos"],
        "nmtSnapshots": sorted(successful_nmt), "nmtAttemptedAt": sorted(observed_nmt),
        "limits": ["Completed render callbacks are not GPU/display presentation, physical input latency or FPS.",
                   "Input pacing, settle waits and accessibility traversal contribute to scripted phases.",
                   "JFR profile, render observer and NMT attach perturb execution; comparison requires identical instrumentation.",
                   "Normal startup maintenance and warm filesystem/native/OS caches remain part of this diagnostic.",
                   "Process samples can miss instantaneous peaks and final CPU; shutdown-hook CPU is not final process CPU."]}
    write_json(output / "summary.json", summary)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--project-root", type=Path, default=PROJECT)
    parser.add_argument("--source-manifest", type=Path,
                        help="Verified exported source inventory, when the capture host has no Git checkout")
    parser.add_argument("--java", type=Path)
    parser.add_argument("--classpath-file", type=Path)
    compiler = parser.add_mutually_exclusive_group()
    compiler.add_argument("--probe-classes", type=Path)
    compiler.add_argument("--kotlinc", type=Path)
    compiler.add_argument("--compiler-classpath-file", type=Path)
    parser.add_argument("--jcmd", type=Path)
    parser.add_argument("--jfr-tool", type=Path)
    parser.add_argument("--expected-commit")
    parser.add_argument("--label")
    parser.add_argument("--compare", type=Path, nargs=2)
    parser.add_argument("--comparison-output", type=Path)
    parser.add_argument("--heap", default="2g")
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--backend", choices=("x11", "native", "windows"), default="x11")
    parser.add_argument("--display")
    parser.add_argument("--owned-display", action="store_true")
    parser.add_argument("--ink-count", type=int, default=40_000)
    parser.add_argument("--text-outlines", type=int, default=1_000)
    parser.add_argument("--characters", type=int, default=1_000)
    parser.add_argument("--strokes", type=int, default=8)
    parser.add_argument("--sample-seconds", type=float, default=.25)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    parser.add_argument("--contract-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.compare:
            require(args.comparison_output is not None and not any((args.output, args.java, args.classpath_file)),
                    "Use --compare BEFORE AFTER --comparison-output NEW_DIRECTORY")
            print(compare_captures(*args.compare, args.comparison_output), flush=True)
        else:
            require(all((args.output, args.java, args.classpath_file)) and
                    any((args.probe_classes, args.kotlinc, args.compiler_classpath_file)),
                    "Supply --output/--java/--classpath-file and a probe compiler or precompiled classes")
            print(capture(args), flush=True)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as failure:
        print(f"Runtime diagnostic failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure


if __name__ == "__main__":
    main()
