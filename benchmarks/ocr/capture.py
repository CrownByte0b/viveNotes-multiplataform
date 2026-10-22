#!/usr/bin/env python3
"""Capture paired OCR diagnostics serially without altering the frozen benchmark harness."""
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
import statistics
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SIDES = ("before", "after")
FORKS = (1, 2, 3)
CONFIGS = ((1, 1, 320), (1, 1, 3200), (2, 4, 320), (2, 4, 3200))
DIAGNOSTICS = tuple("shared/src/jvmTest/kotlin/com/vivenotes/ai/" + name for name in
                    ("OcrCtcProfile.kt", "OcrGeometryProfileTest.kt"))
HARNESS = tuple("shared/src/jvmTest/kotlin/com/vivenotes/performance/" + name for name in
                ("ComputePerformanceCases.kt", "ComputePerformanceCasesTest.kt", "InkPerformanceCases.kt",
                 "InkPerformanceCasesTest.kt", "PerformanceBaseline.kt", "PerformanceBaselineTest.kt")) + (
    "shared/src/jvmTest/kotlin/com/vivenotes/ui/canvas/InkLayerPerformanceScenario.kt",
    "benchmarks/capture.py", "shared/build.gradle.kts")
RUNTIME_FIELDS = ("java", "vendor", "vm", "ort", "os", "arch", "processors", "maxHeapBytes")
CTC_IDENTITY = ("inputShape", "outputShape", "inputSha256", "outputSha256", "modelSha256", "modelBytes",
                "dictionarySha256", "dictionaryBytes", "readingSha256", "confidenceBits", "sessionOpenScope",
                "timingScope", "allocationScope", "summaryScope", "memoryScope", "collectionScope")
MEMORY_STAGES = ("process-start", "fixture-ready", "onnx-environment-ready", "recognizer-session-open",
    "cold-inference-and-decode-complete", "pipeline-warmup-complete", "pipeline-samples-complete",
    "pipeline-idle-before-close", "kernel-warmup-complete", "kernel-samples-complete",
    "kernel-idle-output-still-open", "all-result-tensors-closed", "sessions-and-lanes-closed",
    "closed-idle", "closed-after-explicit-gc")
PINS = {
    "modelSha256": "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    "modelBytes": 21_159_378,
    "dictionarySha256": "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
    "dictionaryBytes": 74_947,
}
DETECTOR = {"modelSha256": "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
            "modelBytes": 9_880_512}
GEOMETRY_NAMES = tuple(name + suffix for name in ("seven-lines", "dense-page", "slanted-page")
                       for suffix in ("-rectangles", "-postprocess")) + (
    "hull-16", "hull-64", "hull-256", "hull-1024", "rows-40", "rows-400")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fields(value, names, label):
    require(isinstance(value, dict) and all(name in value for name in names), f"{label}: missing fields")
    return {name: value[name] for name in names}


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def hashes(project, paths):
    return [{"path": str(path), "sha256": digest(project / path)} for path in paths]


def read_json(path):
    value = json.loads(Path(path).read_text())
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def stamp():
    return datetime.now(timezone.utc).isoformat()


def git(project, *args):
    return subprocess.check_output(["git", "-c", "log.showSignature=false", *args], cwd=project, text=True).strip()


def source_snapshot(project):
    directories = ("shared/src/commonMain/kotlin", "shared/src/jvmMain/kotlin", "shared/src/commonTest/kotlin",
                   "shared/src/jvmTest/kotlin", "desktopApp/src/main", "desktopApp/src/test", "shared/schemas")
    paths = {path.relative_to(project).as_posix() for directory in directories
             for path in (project / directory).rglob("*") if path.is_file()}
    paths.update(path for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
        "gradle/libs.versions.toml", "shared/build.gradle.kts", "desktopApp/build.gradle.kts",
        "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar") if (project / path).is_file())
    return hashes(project, sorted(paths))


def tool_hashes():
    return hashes(ROOT, [str((HERE / name).relative_to(ROOT)) for name in
                         ("capture.py", "test_capture.py", "USAGE.md", "profile.init.gradle")])


def schedule(phase):
    require(phase in ("ctc", "geometry"), "Choose ctc or geometry")
    return [{"fork": fork, "side": side, "phase": phase,
             **({"lanes": lanes, "threads": threads, "width": width, "mode": "flat" if side == "before" else "native"}
                if phase == "ctc" else {})}
            for fork in FORKS for lanes, threads, width in (CONFIGS if phase == "ctc" else ((0, 0, 0),))
            for side in (SIDES if fork % 2 else tuple(reversed(SIDES)))]


def basename(row):
    config = f'-l{row["lanes"]}-t{row["threads"]}-w{row["width"]}' if row["phase"] == "ctc" else ""
    return f'{row["phase"]}{config}-fork-{row["fork"]}'


def report_path(directory, row):
    return directory / row["side"] / (basename(row) + ".json")


def new_output(output, before, after):
    require(not output.exists() and not output.is_symlink(), "Output already exists; use a new capture directory")
    output = output.resolve()
    require(not output.is_relative_to(before), "Output cannot be inside the baseline checkout")
    for project in (before, after):
        results = project / "benchmarks/results"
        if results.is_dir():
            for existing in results.iterdir():
                require(not existing.is_dir() or not output.is_relative_to(existing.resolve()),
                        "Output must be a new sibling, never nested inside an existing capture")
    return output


def normalized_args(arguments, project, report):
    require(isinstance(arguments, list) and all(isinstance(value, str) for value in arguments), "Invalid JVM arguments")
    result = []
    for value in arguments:
        if value.startswith("-Dvivenotes.ocr.geometry.report="):
            require(value.split("=", 1)[1] == str(report), "Unexpected geometry report property")
            result.append("-Dvivenotes.ocr.geometry.report=<report>")
        else:
            result.append(re.sub(re.escape(str(project)) + r"(?=$|[/\\])", "<project>", value))
    return result


def number(value, label, minimum=0):
    require(type(value) in (int, float) and math.isfinite(value) and value >= minimum, f"{label}: invalid number")
    return value


def sha(value, label):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), f"{label}: invalid SHA256")


def upper_median(values):
    return sorted(values)[len(values) // 2]


def check_batch(batch, lanes):
    number(batch.get("wallNanos"), "batch wall", 1)
    samples = batch.get("lanes")
    require(isinstance(samples, list) and len(samples) == lanes, "Wrong number of measured lane samples")
    for sample in samples:
        number(sample.get("wallNanos"), "lane wall", 1)
        for name in ("callerCpuNanos", "allocatedBytes"):
            value = number(sample.get(name), name, -1)
            require(value == -1 or value >= 0, f"{name}: unsupported must be -1")


def ctc_identity(report, row):
    require(report.get("schema") == "vive-ocr-ctc-profile/1" and report.get("mode") == row["mode"] and
            report.get("lanes") == row["lanes"] and report.get("inferenceThreads") == row["threads"], "Wrong CTC schema/configuration")
    identity = fields(report, CTC_IDENTITY, "CTC fixture/scopes")
    require(fields(report, tuple(PINS), "CTC model pins") == PINS, "Pinned production v6 model/dictionary differ")
    require(report["inputShape"] == [1, 3, 48, row["width"]], "CTC input shape differs")
    shape = report["outputShape"]
    require(isinstance(shape, list) and len(shape) == 3 and shape[0] == 1 and shape[2] == 18_710 and
            type(shape[1]) is int and shape[1] > 0, "CTC output shape differs")
    for key in ("inputSha256", "outputSha256", "readingSha256"):
        sha(report[key], key)
    require(type(report["confidenceBits"]) is int and -(2 ** 31) <= report["confidenceBits"] < 2 ** 31,
            "Invalid exact confidence bits")
    for key in ("fixturePreparationNanos", "sessionOpenNanos"):
        number(report.get(key), key, 1)
    require(report.get("nativeReductionOpens") == (0 if row["mode"] == "flat" else 1), "Unexpected native reduction lifetime")
    events = report.get("nativeEvents")
    require(isinstance(events, list) and all(isinstance(event, str) and "unavailable" not in event.lower() for event in events),
            "Native profile fell back or events are invalid")
    check_batch(report["cold"], row["lanes"])
    for phase, warmups, count in (("pipeline", 8, 20), ("kernel", 40, 80)):
        require(report.get(phase + "WarmupBatches") == warmups, "CTC warmup count differs")
        measured = report[phase + "Warm"]
        batches = measured.get("batches")
        require(isinstance(batches, list) and len(batches) == count, "CTC measured batch count differs")
        for batch in batches:
            check_batch(batch, row["lanes"])
        process_cpu = measured.get("processCpuNanos")
        if process_cpu is not None:
            number(process_cpu, "process CPU", -1)
        samples = [sample for batch in batches for sample in batch["lanes"]]
        statistics_fields = {"medianBatchWallNanos": [batch["wallNanos"] for batch in batches],
            "medianLaneWallNanos": [sample["wallNanos"] for sample in samples],
            "medianLaneCpuNanos": [sample["callerCpuNanos"] for sample in samples],
            "medianLaneAllocatedBytes": [sample["allocatedBytes"] for sample in samples]}
        for name, values in statistics_fields.items():
            require(measured.get(name) == upper_median(values), f"{name} disagrees with raw upper median")
    memory = report.get("memory")
    require(isinstance(memory, list), "Missing lifecycle memory snapshots")
    stages = [point.get("stage") for point in memory]
    require(len(stages) == len(set(stages)) and [stage for stage in stages if stage in MEMORY_STAGES] == list(MEMORY_STAGES),
            "Memory lifecycle stages are missing/reordered/duplicated")
    previous = None
    for point in memory:
        at = point.get("atNanos")
        require(type(at) is int and (previous is None or at > previous), "Memory snapshot clocks are not monotone")
        previous = at
        for name in ("rssBytes", "peakRssBytes"):
            if point.get(name) is not None:
                number(point[name], name)
        for name in ("heapUsedBytes", "heapCommittedBytes", "nonHeapUsedBytes", "gcCount", "gcMillis"):
            number(point.get(name), name)
        require(isinstance(point.get("bufferPools"), list), "Missing direct-buffer pool observations")
    return {**identity, "memoryStages": stages}


def geometry_identity(report):
    require(report.get("schema") == "vive-ocr-geometry-profile/1", "Wrong geometry schema")
    require(fields(report, tuple(DETECTOR), "detector pins") == DETECTOR, "Pinned production v6 detector differs")
    cases = report.get("cases")
    require(isinstance(cases, list) and len(cases) == 12 and
            [case.get("name") for case in cases] == list(GEOMETRY_NAMES), "Geometry case coverage/order differs")
    result = []
    seen = set()
    for case in cases:
        identity = fields(case, ("name", "units", "fixtureSha256", "outputSha256", "sampleCount", "batchOperations"), "geometry case")
        require(isinstance(case["name"], str) and case["name"] and case["name"] not in seen, "Duplicate/missing geometry case name")
        seen.add(case["name"])
        number(case["units"], "geometry units", 1)
        sha(case["fixtureSha256"], "geometry fixture")
        sha(case["outputSha256"], "geometry output")
        require(case["sampleCount"] == 31 and case["batchOperations"] == 10, "Geometry sample/batch count differs")
        number(case.get("warmupNanos"), "geometry warmup", 400_000_000)
        values = case.get("sampleNanosPerOperation")
        require(isinstance(values, list) and len(values) == 31, "Geometry raw sample count differs")
        for value in values:
            number(value, "geometry timing")
        require(case.get("medianNanos") == upper_median(values), "Geometry median disagrees with raw samples")
        result.append(identity)
    images = report.get("images")
    require(isinstance(images, list) and [image.get("name") for image in images] == ["seven-lines", "dense-page", "slanted-page"],
            "Real detector image coverage/order differs")
    for index, image in enumerate(images):
        fields(image, ("name", "mapWidth", "mapHeight", "probabilitySha256", "hullsSha256", "orderedQuadsSha256", "regions", "hullSizes"), "detector image")
        for key in ("mapWidth", "mapHeight", "regions"):
            number(image[key], key, 1)
        for key in ("probabilitySha256", "hullsSha256", "orderedQuadsSha256"):
            sha(image[key], key)
        require(isinstance(image["hullSizes"], list) and image["hullSizes"] and all(type(size) is int and size > 0 for size in image["hullSizes"]),
                "Missing real detector hull sizes")
        rectangle, postprocess = cases[index * 2:index * 2 + 2]
        require(rectangle["fixtureSha256"] == image["hullsSha256"] and rectangle["units"] == len(image["hullSizes"]) and
                postprocess["fixtureSha256"] == image["probabilitySha256"] and postprocess["outputSha256"] == image["orderedQuadsSha256"] and
                postprocess["units"] == image["mapWidth"] * image["mapHeight"], "Image metadata disagrees with geometry cases")
    return {"cases": result, "images": images, **fields(report, ("timingScope", "fixtureScope"), "geometry scopes")}


def task_identity(runtime, project, report, row):
    phase = row["phase"]
    require(runtime.get("schema") == "vive-ocr-task-runtime/1" and
            runtime.get("task") == f':shared:ocr{"Ctc" if phase == "ctc" else "Geometry"}Profile', "Wrong task runtime metadata")
    expected_main = "com.vivenotes.ai.OcrCtcProfile" if phase == "ctc" else "org.junit.runner.JUnitCore"
    require(runtime.get("mainClass") == expected_main, "Wrong diagnostic main class")
    expected_args = [str(report), row["mode"], str(row["lanes"]), str(row["threads"]), str(row["width"])] \
        if phase == "ctc" else ["com.vivenotes.ai.OcrGeometryProfileTest"]
    require(runtime.get("programArgs") == expected_args, "Wrong diagnostic program arguments")
    require(Path(runtime.get("workingDirectory", "")) == project, "Diagnostic working directory differs")
    sha(runtime.get("javaExecutableSha256"), "Java executable")
    classpath = runtime.get("classpath")
    require(isinstance(classpath, list) and classpath, "Missing runtime classpath")
    entries = []
    for entry in classpath:
        require(type(entry.get("projectArtifact")) is bool and entry.get("kind") in ("file", "directory", "missing"), "Invalid classpath entry")
        if entry["kind"] != "missing":
            sha(entry.get("sha256"), "runtime artifact")
        if entry["projectArtifact"]:
            path = Path(entry["path"])
            require(path.is_relative_to(project), "Project artifact outside checkout")
            entries.append({"path": "<project>/" + path.relative_to(project).as_posix(), "kind": entry["kind"], "projectArtifact": True})
        else:
            entries.append(fields(entry, ("name", "kind", "sha256", "projectArtifact"), "external artifact"))
    return {**fields(runtime, ("javaExecutableSha256", "javaRuntimeVersion", "vendor", "vmVersion"), "launcher identity"),
            "vmArgs": normalized_args(runtime.get("vmArgs"), project, report), "classpath": entries}


def validate(directory, environment, completed=None):
    rows = schedule(environment["phase"]) if completed is None else completed
    reports = {side: {} for side in SIDES}
    anchor = None
    fixtures = {}
    project_artifacts = {}
    for row in rows:
        path = report_path(directory, row)
        report = read_json(path)
        runtime = read_json(path.with_name(path.stem + "-runtime.json"))
        project = Path(environment["variants"][row["side"]]["project"])
        task = task_identity(runtime, project, path, row)
        project_bytes = [entry for entry in runtime["classpath"] if entry["projectArtifact"]]
        if row["side"] not in project_artifacts:
            project_artifacts[row["side"]] = project_bytes
        require(project_bytes == project_artifacts[row["side"]], "Compiled project artifact bytes changed within one variant")
        actual = {**fields(report.get("runtime"), RUNTIME_FIELDS, "actual runtime"),
                  "vmArgs": normalized_args(report["runtime"].get("vmArgs"), project, path)}
        identity = {"task": task, "actual": actual}
        if anchor is None:
            anchor = identity
        require(identity == anchor, "Actual runtime/VM flags/launcher/classpath bytes differ")
        config = (row["lanes"], row["threads"], row["width"]) if row["phase"] == "ctc" else "geometry"
        fixture = ctc_identity(report, row) if row["phase"] == "ctc" else geometry_identity(report)
        if config not in fixtures:
            fixtures[config] = fixture
        require(fixture == fixtures[config], "Fixture/output/confidence/lifecycle identity differs")
        reports[row["side"]].setdefault(config, []).append((row["fork"], report))
    for side in SIDES:
        reports[side] = {config: [report for _, report in sorted(values)] for config, values in reports[side].items()}
    return reports, anchor


def change(before, after):
    return (after / before - 1) * 100 if before is not None and after is not None and before > 0 else None


def metric(before, after):
    return {"beforeByFork": before, "afterByFork": after,
            "beforeMedian": statistics.median(before) if all(value is not None and value >= 0 for value in before) else None,
            "afterMedian": statistics.median(after) if all(value is not None and value >= 0 for value in after) else None}


def with_change(values):
    return {**values, "changePercent": change(values["beforeMedian"], values["afterMedian"])}


def comparison(directory, environment):
    reports, runtime = validate(directory, environment)
    phase = environment["phase"]
    cases = []
    if phase == "ctc":
        for config in CONFIGS:
            old, new = (reports[side][config] for side in SIDES)
            measures = {"sessionOpenNanos": lambda report: report["sessionOpenNanos"],
                        "coldBatchWallNanos": lambda report: report["cold"]["wallNanos"]}
            for prefix in ("pipeline", "kernel"):
                for key in ("medianBatchWallNanos", "medianLaneWallNanos", "medianLaneCpuNanos", "medianLaneAllocatedBytes", "processCpuNanos"):
                    measures[prefix + "." + key] = lambda report, prefix=prefix, key=key: report[prefix + "Warm"][key]
                measures[prefix + ".upperMedianBatchAllocatedBytes"] = lambda report, prefix=prefix: upper_median([
                    sum(sample["allocatedBytes"] for sample in batch["lanes"]) if all(sample["allocatedBytes"] >= 0 for sample in batch["lanes"]) else -1
                    for batch in report[prefix + "Warm"]["batches"]])
            memory = []
            for index, point in enumerate(old[0]["memory"]):
                for key in ("rssBytes", "peakRssBytes", "heapUsedBytes", "heapCommittedBytes", "nonHeapUsedBytes", "gcCount", "gcMillis"):
                    memory.append({"stage": point["stage"], "field": key, **with_change(metric(
                        [report["memory"][index][key] for report in old], [report["memory"][index][key] for report in new]))})
            cases.append({"lanes": config[0], "inferenceThreads": config[1], "width": config[2],
                "fixtureIdentity": fields(old[0], CTC_IDENTITY, "fixture"),
                "metrics": [{"name": name, **with_change(metric([operation(report) for report in old], [operation(report) for report in new]))}
                            for name, operation in measures.items()], "memory": memory})
    else:
        old, new = (reports[side]["geometry"] for side in SIDES)
        for index, case in enumerate(old[0]["cases"]):
            cases.append({**fields(case, ("name", "units", "fixtureSha256", "outputSha256"), "geometry"),
                **with_change(metric([report["cases"][index]["medianNanos"] for report in old], [report["cases"][index]["medianNanos"] for report in new])),
                "beforeSamples": [report["cases"][index]["sampleNanosPerOperation"] for report in old],
                "afterSamples": [report["cases"][index]["sampleNanosPerOperation"] for report in new]})
    result = {"schema": "vive-paired-ocr-comparison/1", "phase": phase, "forks": 3, "compatibilityPassed": True,
              "runtimeIdentity": runtime, "cases": cases, "notes": [
        "CTC compares isolated pinned v6 baseline flat versus current native decoding; engine lifecycle/whole-app latency is not measured.",
        "Model/dictionary/input/output/reading hashes, confidence bits and configured lane/thread/width identity must match exactly.",
        "CTC summaries retain the diagnostic's upper medians; between forks, the ordinary median of three summaries is used.",
        "Lane allocations exclude native arenas and main/ORT threads; summed batch allocations are derived separately from raw lane observations.",
        "RSS/HWM are whole-process observations including JIT, heap, libraries and ORT arenas; Java allocation reductions do not establish native RSS savings.",
        "Explicit GC is requested only at the recorded common-output validation and final closed phases; collection is not guaranteed.",
        "Cold inference includes executor startup and lazy native reducer opening; image/preprocessing setup is separate, session open is reported separately.",
        "Each task is a fresh JVM with warm OS caches; samples include scheduling/GC. No latency-tail, throughput, confidence-interval or FPS claim.",
        "All raw reports remain intact; positive change percentages are regressions and are never suppressed."]}
    if phase == "geometry":
        result["realDetectorImages"] = reports["before"]["geometry"][0]["images"]
        result["geometryScopes"] = fields(reports["before"]["geometry"][0], ("timingScope", "fixtureScope"), "geometry scopes")
    lines = ["# Paired OCR capture", "", f"Phase: {phase}; three serial fresh JVM forks per side and configuration.", ""]
    if phase == "ctc":
        lines += ["| Lanes | Threads | Width | Metric | Before | After | Change |", "|---:|---:|---:|---|---:|---:|---:|"]
        for case in cases:
            for row in case["metrics"]:
                lines.append(f'| {case["lanes"]} | {case["inferenceThreads"]} | {case["width"]} | {row["name"]} | '
                             f'{display(row["beforeMedian"])} | {display(row["afterMedian"])} | {percent(row["changePercent"])} |')
        lines += ["", "| Lanes | Threads | Width | Lifecycle stage | Before RSS MiB | After RSS MiB | Change |",
                  "|---:|---:|---:|---|---:|---:|---:|"]
        for case in cases:
            for row in case["memory"]:
                if row["field"] == "rssBytes" and row["stage"] in ("pipeline-idle-before-close", "kernel-idle-output-still-open", "closed-after-explicit-gc"):
                    a = row["beforeMedian"] / 2 ** 20 if row["beforeMedian"] is not None else None
                    b = row["afterMedian"] / 2 ** 20 if row["afterMedian"] is not None else None
                    lines.append(f'| {case["lanes"]} | {case["inferenceThreads"]} | {case["width"]} | {row["stage"]} | {display(a)} | {display(b)} | {percent(row["changePercent"])} |')
        lines += ["", "All lifecycle RSS/HWM/heap/GC observations and raw fork values are retained in comparison.json and the raw reports."]
    else:
        lines += ["| Geometry case | Units | Before ns | After ns | Change |", "|---|---:|---:|---:|---:|"]
        for case in cases:
            lines.append(f'| {case["name"]} | {case["units"]} | {display(case["beforeMedian"])} | {display(case["afterMedian"])} | {percent(case["changePercent"])} |')
    lines += [""] + ["- " + note for note in result["notes"]]
    return result, "\n".join(lines) + "\n"


def display(value):
    return "unavailable" if value is None else f"{value:.3f}"


def percent(value):
    return "unavailable" if value is None else f"{value:+.1f}%"


def command(row, report, runtime):
    args = ["./gradlew", ":shared:ocrCtcProfile" if row["phase"] == "ctc" else ":shared:ocrGeometryProfile",
            "--offline", "--max-workers=1", "--console=plain", "--no-configuration-cache",
            "-I", str(HERE / "profile.init.gradle"), f"-PocrRuntimeOutput={runtime}"]
    if row["phase"] == "ctc":
        args += [f"-PocrCtcOutput={report}", f'-PocrCtcMode={row["mode"]}', f'-PocrCtcLanes={row["lanes"]}',
                 f'-PocrCtcThreads={row["threads"]}', f'-PocrCtcWidth={row["width"]}']
    else:
        args.append(f"-PocrGeometryOutput={report}")
    return args


def assert_frozen(projects, environment):
    for side, project in projects.items():
        require(source_snapshot(project) == environment["variants"][side]["sourceFiles"], f"{side}: source changed during capture")
        require(hashes(project, HARNESS) == environment["harnessFiles"], f"{side}: frozen harness changed")
    require(tool_hashes() == environment["toolFiles"], "OCR tooling changed during capture")


def capture(before, after, phase, output, baseline_commit="e120e01"):
    projects = {"before": before.resolve(), "after": after.resolve()}
    before, after = projects.values()
    require(before != after, "Baseline and current checkouts must differ")
    output = new_output(output, before, after)
    require(git(before, "rev-parse", "HEAD") == git(before, "rev-parse", baseline_commit + "^{commit}"), "Wrong baseline commit")
    require(not git(before, "diff", "HEAD", "--name-only"), "Baseline tracked files must match committed source")
    untracked = set(filter(None, git(before, "ls-files", "--others", "--exclude-standard").splitlines()))
    require(untracked <= set(DIAGNOSTICS), "Baseline permits only the two declared test-only diagnostics")
    require(hashes(before, DIAGNOSTICS) == hashes(after, DIAGNOSTICS), "Before/after diagnostic source bytes differ")
    reference = read_json(before / "benchmarks/results/2026-10-21/environment.json")["harnessFiles"]
    require(set(reference) == set(HARNESS), "Original nine-file harness manifest differs")
    harness = [{"path": path, "sha256": reference[path]} for path in HARNESS]
    variants = {}
    for side, project in projects.items():
        require(hashes(project, HARNESS) == harness, f"{side}: frozen harness differs")
        variants[side] = {"project": str(project), "commit": git(project, "rev-parse", "HEAD"),
                          "workingTree": git(project, "status", "--short"), "sourceFiles": source_snapshot(project)}
    environment = {"schema": "vive-paired-ocr-environment/1", "complete": False, "phase": phase, "forks": 3,
        "startedAt": stamp(), "platform": platform.platform(), "cpu": platform.processor(), "logicalCpus": os.cpu_count(),
        "affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "javaHome": os.environ.get("JAVA_HOME"), "variants": variants, "harnessFiles": harness,
        "diagnosticFiles": hashes(after, DIAGNOSTICS), "toolFiles": tool_hashes(), "schedule": schedule(phase)}
    output.mkdir(parents=True)
    for side in SIDES:
        (output / side).mkdir()
    write_json(output / "environment.json", environment)
    execution = []
    durations = []
    started = time.monotonic()
    rows = schedule(phase)
    for index, row in enumerate(rows, 1):
        report = report_path(output, row)
        runtime = report.with_name(report.stem + "-runtime.json")
        log = report.with_name(report.stem + "-log.txt")
        args = command(row, report, runtime)
        eta = f"{statistics.mean(durations) * (len(rows) - index + 1):.0f}s" if durations else "pending first run"
        print(f'[{phase} {index}/{len(rows)}] {row["side"]} {basename(row)} elapsed={time.monotonic() - started:.0f}s ETA={eta}', flush=True)
        execution.append({**row, "command": args, "status": "running", "startedAt": stamp()})
        write_json(output / "execution.json", {"runs": execution})
        run_started = time.monotonic()
        try:
            with log.open("w") as target:
                subprocess.run(args, cwd=projects[row["side"]], stdout=target, stderr=subprocess.STDOUT, check=True)
            require(report.is_file() and runtime.is_file(), "Task did not produce fresh report/runtime evidence")
            validate(output, environment, execution)
            assert_frozen(projects, environment)
            execution[-1]["status"] = "validated"
        except BaseException as failure:
            execution[-1].update(status="failed", errorType=type(failure).__name__, error=str(failure))
            environment["failure"] = {**row, "errorType": type(failure).__name__, "error": str(failure)}
            write_json(output / "environment.json", environment)
            raise
        finally:
            elapsed = time.monotonic() - run_started
            execution[-1].update(finishedAt=stamp(), elapsedSeconds=elapsed)
            write_json(output / "execution.json", {"runs": execution})
        durations.append(elapsed)
        print(f'  validated in {elapsed:.1f}s; rolling remaining estimate={statistics.mean(durations) * (len(rows) - index):.0f}s', flush=True)
    result, markdown = comparison(output, environment)
    a, b = ({row["path"]: row["sha256"] for row in variants[side]["sourceFiles"]} for side in SIDES)
    environment["changedSourceFiles"] = [{"path": path, "beforeSha256": a.get(path), "afterSha256": b.get(path)}
        for path in sorted(a.keys() | b.keys()) if a.get(path) != b.get(path)]
    environment["rawFiles"] = [{"path": path.relative_to(output).as_posix(), "sha256": digest(path)}
        for path in sorted(output.rglob("*")) if path.is_file() and path.name != "environment.json"]
    environment.update(complete=True, completedAt=stamp(), elapsedSeconds=time.monotonic() - started)
    write_json(output / "environment.json", environment)
    write_json(output / "comparison.json", result)
    (output / "comparison.md").write_text(markdown)
    return result


def analyze(output):
    environment = read_json(output / "environment.json")
    require(environment.get("complete") is True, "Capture did not complete validation")
    require(tool_hashes() == environment["toolFiles"], "OCR tooling differs from capture")
    for row in environment["rawFiles"]:
        require(digest(output / row["path"]) == row["sha256"], "Raw capture evidence changed")
    result, markdown = comparison(output, environment)
    write_json(output / "comparison.json", result)
    (output / "comparison.md").write_text(markdown)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--phase", choices=("ctc", "geometry"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline-commit", default="e120e01")
    parser.add_argument("--analyze", type=Path)
    args = parser.parse_args()
    if args.analyze:
        require(not any((args.before, args.after, args.phase, args.output)), "Use --analyze or a new paired capture")
        result = analyze(args.analyze.resolve())
    else:
        require(all((args.before, args.after, args.phase, args.output)), "Supply --before/--after/--phase/--output")
        result = capture(args.before, args.after, args.phase, args.output, args.baseline_commit)
    print(f'Validated {result["phase"]}: {len(result["cases"])} matched workloads ×3forks; inspect every regression.', flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as failure:
        print(f"OCR capture refused: {failure}", file=sys.stderr)
        sys.exit(1)
