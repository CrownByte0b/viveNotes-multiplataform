#!/usr/bin/env python3
"""Serial paired capture of the unchanged app/renderer harness; compare ink workloads only."""
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
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
import compare_ink  # Reuse the existing transparent renderer-counter/timing definitions.

FORKS = (1, 2, 3)
VARIANTS = ("before", "after")
FROZEN = Path("benchmarks/results/2026-10-21")
HARNESS = (
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/ComputePerformanceCases.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/ComputePerformanceCasesTest.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/InkPerformanceCases.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/InkPerformanceCasesTest.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/PerformanceBaseline.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/performance/PerformanceBaselineTest.kt",
    "shared/src/jvmTest/kotlin/com/vivenotes/ui/canvas/InkLayerPerformanceScenario.kt",
    "benchmarks/capture.py", "shared/build.gradle.kts",
)
TASKS = {"app": ":shared:performanceBaseline", "ink": ":shared:inkPhase2Scenario"}
INK_ORDER = [(view, density, additions) for density in (1.0, 2.0)
             for view in ("fit", "sparse") for additions in (1, 32)]
APP_IDENTITY = ("schema", "runtime", "vendor", "vm", "os", "osVersion", "arch", "processors",
                "maxHeapBytes", "timingScope", "allocationScope", "storageScope")
INK_XML = Path("shared/build/test-results/inkPhase2Scenario/TEST-com.vivenotes.ui.canvas.InkLayerPerformanceScenario.xml")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_json(path):
    value = json.loads(Path(path).read_text())
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")


def hashes(project, paths):
    # Never publish source-path dictionary keys: token/key filenames once confused gitleaks.
    return [{"path": str(path), "sha256": digest(project / path)} for path in paths]


def source_snapshot(project):
    directories = ("shared/src/commonMain", "shared/src/jvmMain", "shared/src/commonTest",
                   "shared/src/jvmTest", "desktopApp/src/main", "desktopApp/src/test", "shared/schemas")
    paths = {path.relative_to(project).as_posix() for directory in directories
             for path in (project / directory).rglob("*") if path.is_file()}
    paths.update(path for path in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                 "gradle/libs.versions.toml", "desktopApp/build.gradle.kts", "shared/build.gradle.kts",
                 "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar")
                 if (project / path).is_file())
    return hashes(project, sorted(paths))


def tool_hashes():
    return [{"path": str(path.relative_to(ROOT)), "sha256": digest(path)} for path in
            (HERE / "capture.py", HERE / "test_capture.py", HERE / "USAGE.md", HERE.parent / "compare_ink.py")]


def git(project, *args):
    return subprocess.check_output(["git", "-c", "log.showSignature=false", *args], cwd=project, text=True).strip()


def schedule():
    return [{"fork": fork, "kind": kind, "variant": variant} for fork in FORKS
            for kind in ("app", "ink")
            for variant in (VARIANTS if fork % 2 else tuple(reversed(VARIANTS)))]


def new_output(output, before, after):
    require(not output.exists() and not output.is_symlink(), "Output already exists; use a new capture directory")
    output = output.resolve()
    require(not output.is_relative_to(before), "Output cannot be inside the baseline checkout")
    for project in (before, after):
        results = project / "benchmarks/results"
        if results.is_dir():
            for existing in results.iterdir():
                require(not existing.is_dir() or not output.is_relative_to(existing.resolve()),
                        "Output must be a new sibling capture, never inside an existing capture")
    return output


def normalized_args(arguments, project, report):
    require(isinstance(arguments, list) and all(isinstance(value, str) for value in arguments), "Missing JVM arguments")
    result = []
    for argument in arguments:
        if argument.startswith("-Dvivenotes.ink.phase2Report="):
            require(argument.split("=", 1)[1] == str(report), "Unexpected renderer report VM property")
            result.append("-Dvivenotes.ink.phase2Report=<report>")
        else:
            result.append(re.sub(re.escape(str(project)) + r"(?=$|[/\\])", "<project>", argument))
    return result


def numbers(values, label, count=None):
    require(isinstance(values, list) and bool(values), f"{label}: missing raw samples")
    require(count is None or len(values) == count, f"{label}: sample count differs")
    require(all(type(value) in (int, float) and math.isfinite(value) and value >= 0 for value in values),
            f"{label}: invalid raw samples")
    return values


def app_cases(report, expected, fork):
    require(report.get("schema") == "vive-desktop-performance/1" and report.get("fork") == fork,
            "Wrong app schema/fork identity")
    cases = report.get("cases")
    require(isinstance(cases, list) and len(cases) == 48, "Exactly48 app cases required")
    keys = [(case.get("name"), case.get("units")) for case in cases]
    require(len(set(keys)) == 48 and keys == [(case["name"], case["units"]) for case in expected],
            "App workload coverage/order differs from the frozen harness")
    for case, fixture in zip(cases, expected):
        require(case.get("fixtureSha256") == fixture["fixtureSha256"], "App workload fixture hash differs")
        require(case.get("sampleCount") == 15, "Frozen app sample count differs")
        samples = numbers(case.get("sampleNanosPerOperation"), "app timing", 15)
        require(type(case.get("operationsPerSample")) is int and case["operationsPerSample"] > 0 and
                type(case.get("warmupOps")) is int and case["warmupOps"] >= 8, "App operation/warmup counts differ")
        require(math.isclose(case.get("medianNanos", -1), statistics.median(samples), rel_tol=1e-12),
                "App median disagrees with raw samples")
        require(math.isclose(case.get("p95BatchAverageNanos", -1), max(samples), rel_tol=1e-12),
                "App p95 batch average disagrees with raw samples")
        if "sampleAllocatedBytesPerOperation" in case:
            allocation = numbers(case["sampleAllocatedBytesPerOperation"], "caller allocation", 15)
            require(math.isclose(case.get("medianAllocatedBytes", -1), statistics.median(allocation), rel_tol=1e-12),
                    "App allocation disagrees with raw samples")
    return cases


def ink_cases(report):
    require(report.get("schema") == "vive-ink-phase2-ui/1", "Wrong renderer schema")
    cases = report.get("cases")
    require(isinstance(cases, list) and len(cases) == 8 and
            [compare_ink.case_key(case) for case in cases] == INK_ORDER,
            "Renderer workload coverage/order differs from the frozen harness")
    for case in cases:
        require(case["finishedPageStrokes"] == 40_000 and case["viewportPx"] == 512 and
                case["scrollXPx"] == 17.25 and case["scrollYPx"] == 29.75, "Renderer fixture dimensions differ")
        compare_ink.fields(case, compare_ink.CASE_IDENTITY, "renderer fixture")
        compare_ink.structural(case, "renderer counters")
        require(type(case.get("eraseReleaseToCommittedDrawNanos")) is int and case["eraseReleaseToCommittedDrawNanos"] > 0,
                "Missing renderer erase-commit observation")
    return cases


def task_identity(runtime, project, report):
    require(runtime.get("schema") == "vive-ink-task-runtime/1", "Missing task runtime metadata")
    artifacts = runtime.get("classpath")
    require(isinstance(artifacts, list) and artifacts, "Task classpath is missing")
    normalized = []
    for entry in artifacts:
        require(type(entry.get("projectArtifact")) is bool and entry.get("kind") in ("file", "directory", "missing"),
                "Invalid classpath artifact")
        require(entry["kind"] == "missing" or re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")),
                "Classpath artifact hash is missing")
        if entry["projectArtifact"]:
            path = Path(entry["path"])
            require(path.is_relative_to(project), "Project artifact lies outside its checkout")
            normalized.append({"path": "<project>/" + path.relative_to(project).as_posix(), "kind": entry["kind"],
                               "projectArtifact": True})
        else:
            normalized.append({key: entry[key] for key in ("name", "kind", "sha256", "projectArtifact")})
    return {**compare_ink.fields(runtime, ("task", "javaExecutableSha256", "javaRuntimeVersion", "vendor", "vmVersion"), "task JVM"),
            "vmArgs": normalized_args(runtime["vmArgs"], project, report), "classpath": normalized}


def validate_capture(directory, environment, completed=None):
    require(environment.get("forks") == 3 and len(environment.get("harnessFiles", [])) == 9, "Three forks/nine frozen files required")
    expected = environment["appFixtures"]
    reports = {variant: {kind: [] for kind in TASKS} for variant in VARIANTS}
    app_anchor = None
    ink_anchor = None
    case_anchor = None
    task_anchors = {}
    external_anchor = None
    for row in schedule() if completed is None else completed:
        variant, kind, fork = row["variant"], row["kind"], row["fork"]
        path = directory / variant / f"{kind}-fork-{fork}.json"
        report = read_json(path)
        runtime = read_json(path.with_name(f"{kind}-fork-{fork}-runtime.json"))
        project = Path(environment["variants"][variant]["project"])
        actual_task = task_identity(runtime, project, path)
        require(actual_task["task"] == TASKS[kind], "Runtime metadata belongs to another task")
        if kind not in task_anchors:
            task_anchors[kind] = actual_task
        require(actual_task == task_anchors[kind], f"{variant}/{kind}/fork{fork}: task JVM flags/launcher/external classpath differ")
        external = [entry for entry in actual_task["classpath"] if not entry["projectArtifact"]]
        if external_anchor is None:
            external_anchor = external
        require(external == external_anchor, "External classpath bytes/order differ between app/renderer JVMs")
        if kind == "app":
            app_cases(report, expected, fork)
            identity = {**compare_ink.fields(report, APP_IDENTITY, "app runtime"),
                        "vmArgs": normalized_args(report.get("vmArgs"), project, path),
                        "externalArtifacts": [entry for entry in report.get("runtimeArtifacts", []) if not entry["projectArtifact"]]}
            task_jars = sorted((entry["name"], entry["sha256"]) for entry in external if entry["kind"] == "file" and entry["name"].endswith(".jar"))
            require(task_jars == sorted((entry["name"], entry["sha256"]) for entry in identity["externalArtifacts"]),
                    "Actual app external jars disagree with task metadata")
            if app_anchor is None:
                app_anchor = identity
            require(identity == app_anchor, f"{variant}/app/fork{fork}: actual runtime/VM flags/external bytes differ")
        else:
            cases = ink_cases(report)
            identity = compare_ink.fields(report, compare_ink.REPORT_IDENTITY, "renderer/native/fixture")
            if ink_anchor is None:
                ink_anchor = identity
                case_anchor = [compare_ink.fields(case, compare_ink.CASE_IDENTITY, "renderer") for case in cases]
            require(identity == ink_anchor, "Renderer actual runtime/native/fixture/timing identity differs")
            require([compare_ink.fields(case, compare_ink.CASE_IDENTITY, "renderer") for case in cases] == case_anchor,
                    "Renderer workload fixture identity differs")
        reports[variant][kind].append((fork, report))
    if app_anchor is not None and ink_anchor is not None:
        require(ink_anchor["runtime"] == app_anchor["runtime"] and ink_anchor["runtimeVendor"] == app_anchor["vendor"] and
                ink_anchor["os"] == app_anchor["os"], "App and renderer actual runtimes differ")
    for variant in VARIANTS:
        for kind in TASKS:
            reports[variant][kind] = [report for _, report in sorted(reports[variant][kind])]
    return reports, {"app": app_anchor, "renderer": ink_anchor, "taskJvms": task_anchors}


def change_percent(before, after):
    return (after / before - 1) * 100 if before > 0 else None


def comparison(directory, environment):
    reports, identity = validate_capture(directory, environment)
    algorithms = []
    for index, fixture in enumerate(environment["appFixtures"]):
        name = fixture["name"]
        if not (name.startswith("ink.") or name.startswith("storage.append_ink_")):
            continue
        sides = {}
        for variant in VARIANTS:
            rows = [report["cases"][index] for report in reports[variant]["app"]]
            medians = [row["medianNanos"] for row in rows]
            sides[variant] = {"forkMedianNanos": medians, "medianOfForkMediansNanos": statistics.median(medians),
                              "forkP95BatchAverageNanos": [row["p95BatchAverageNanos"] for row in rows],
                              "forkSampleNanosPerOperation": [row["sampleNanosPerOperation"] for row in rows],
                              "operationsPerSample": [row["operationsPerSample"] for row in rows],
                              "warmupOps": [row["warmupOps"] for row in rows]}
            if all("medianAllocatedBytes" in row for row in rows):
                sides[variant].update(forkMedianAllocatedBytes=[row["medianAllocatedBytes"] for row in rows],
                    medianOfForkMedianAllocatedBytes=statistics.median(row["medianAllocatedBytes"] for row in rows))
        algorithms.append({**fixture, **sides, "changePercent": change_percent(
            sides["before"]["medianOfForkMediansNanos"], sides["after"]["medianOfForkMediansNanos"])})
    require(len(algorithms) == 12, "Expected all twelve ink algorithm/storage controls")
    renderer = []
    for index in range(8):
        old = [report["cases"][index] for report in reports["before"]["ink"]]
        new = [report["cases"][index] for report in reports["after"]["ink"]]
        prior, current = compare_ink.timings(old), compare_ink.timings(new)
        old_counts = [compare_ink.structural(case, "before") for case in old]
        new_counts = [compare_ink.structural(case, "after") for case in new]
        deviations = [{"fork": fork, "counter": counter, "before": a[counter], "after": b[counter],
                       "delta": b[counter] - a[counter]} for fork, a, b in zip(FORKS, old_counts, new_counts)
                      for counter in sorted(a) if a[counter] != b[counter]]
        checks = [{"fork": fork, **check} for fork, a, b in zip(FORKS, old, new)
                  for check in compare_ink.preservation_checks(b, a)]
        renderer.append({"workload": compare_ink.fields(old[0], compare_ink.CASE_IDENTITY, "renderer"),
            "before": prior, "after": current,
            "handlerChangePercent": change_percent(prior["pooledHandlerMedianMs"], current["pooledHandlerMedianMs"]),
            "eraseCommitChangePercent": change_percent(prior["medianEraseCommitMs"], current["medianEraseCommitMs"]),
            "beforeStructuralByFork": old_counts, "afterStructuralByFork": new_counts,
            "structuralDeviations": deviations, "preservationChecks": checks})
    result = {"schema": "vive-paired-desktop-ink/1", "forks": 3, "compatibilityPassed": True,
        "appCasesValidatedPerFork": 48, "inkAppCasesCompared": 12, "rendererCasesCompared": 8,
        "runtimeIdentity": identity, "appCases": algorithms, "rendererCases": renderer,
        "structuralDeviationCount": sum(len(row["structuralDeviations"]) for row in renderer),
        "failedPreservationCheckCount": sum(not check["passed"] for row in renderer for check in row["preservationChecks"]),
        "notes": ["All48 app cases are retained raw; the comparison includes six ink append cases and six ink storage controls only.",
            "App figures are medians of three fork medians; p95 values describe batch averages, not individual-event tail latency.",
            "Renderer handler medians pool raw observations; all fork medians/counts and three raw commit observations are retained.",
            "Frozen renderer JSON has no independent fixture SHA: exact nine-file harness, native hash and case/fixture metadata establish identity.",
            "Renderer counters and every deviation are retained; preservation failures are review findings, never silently discarded.",
            "No timing thresholds, physical input latency, FPS, native RSS bound or whole-app performance claim is made.",
            "Configured renderer JVM flags/classpath are captured immediately before launch; actual runtime/vendor/native identity comes from its unchanged JSON.",
            "Normal-erasing/dirty-raster complementary references are outside this frozen-harness capture."]}
    lines = ["# Paired desktop ink capture", "", "Three serial fresh app and renderer JVMs per variant; variant order reverses on fork2.", "",
             "| Ink app workload | Units | Before ms | After ms | Change | Before fork ms | After fork ms |",
             "|---|---:|---:|---:|---:|---|---|"]
    fmt = lambda values: ", ".join(f"{value / 1e6:.3f}" for value in values)
    for row in algorithms:
        a, b = row["before"], row["after"]
        lines.append(f'| {row["name"]} | {row["units"]} | {a["medianOfForkMediansNanos"] / 1e6:.3f} | '
            f'{b["medianOfForkMediansNanos"] / 1e6:.3f} | {row["changePercent"]:+.1f}% | '
            f'{fmt(a["forkMedianNanos"])} | {fmt(b["forkMedianNanos"])} |')
    lines += ["", "| View | Density | Additions | Before handler ms | After ms | Change | Before commit ms | After ms | Change |",
              "|---|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for row in renderer:
        w, a, b = row["workload"], row["before"], row["after"]
        lines.append(f'| {w["view"]} | {w["density"]} | {w["pendingAdditions"]} | {a["pooledHandlerMedianMs"]:.3f} | '
            f'{b["pooledHandlerMedianMs"]:.3f} | {row["handlerChangePercent"]:+.1f}% | {a["medianEraseCommitMs"]:.3f} | '
            f'{b["medianEraseCommitMs"]:.3f} | {row["eraseCommitChangePercent"]:+.1f}% |')
    lines += ["", f'Structural deviations: {result["structuralDeviationCount"]}. Failed preservation checks: {result["failedPreservationCheckCount"]}.', "",
              "| View | Density | Additions | Fork | Counter | Before | After | Delta |",
              "|---|---:|---:|---:|---|---:|---:|---:|"]
    for row in renderer:
        w = row["workload"]
        for delta in row["structuralDeviations"]:
            lines.append(f'| {w["view"]} | {w["density"]} | {w["pendingAdditions"]} | {delta["fork"]} | '
                f'{delta["counter"]} | {delta["before"]} | {delta["after"]} | {delta["delta"]:+} |')
    for row in renderer:
        for check in row["preservationChecks"]:
            if not check["passed"]:
                lines.append(f'\nFailed preservation: {row["workload"]}: {check}')
    lines += ["", ""] + [f"- {note}" for note in result["notes"]]
    return result, "\n".join(lines) + "\n"


def init_script():
    return r'''import groovy.json.JsonOutput
import java.nio.file.Files
import java.security.MessageDigest
def hashFile = { File file ->
    def digest = MessageDigest.getInstance('SHA-256')
    file.withInputStream { input ->
        byte[] buffer = new byte[65536]
        int count
        while ((count = input.read(buffer)) >= 0) { if (count > 0) digest.update(buffer, 0, count) }
    }
    digest.digest().encodeHex().toString()
}
def hashDirectory = { File file ->
    def digest = MessageDigest.getInstance('SHA-256')
    Files.walk(file.toPath()).withCloseable { paths ->
        paths.filter { Files.isRegularFile(it) }.sorted().forEach { child ->
            digest.update(file.toPath().relativize(child).toString().replace((char)92, (char)47).getBytes('UTF-8'))
            digest.update((byte)0)
            digest.update(hashFile(child.toFile()).getBytes('UTF-8'))
        }
    }
    digest.digest().encodeHex().toString()
}
gradle.projectsEvaluated {
    def shared = gradle.rootProject.project(':shared')
    ['performanceBaseline', 'inkPhase2Scenario'].each { name ->
        shared.tasks.named(name).configure { task ->
            outputs.upToDateWhen { false }
            doFirst {
                def launcher = task.javaLauncher.get()
                def executable = launcher.executablePath.asFile.canonicalFile
                def project = gradle.rootProject.projectDir.canonicalFile.toPath()
                def entries = task.classpath.files.collect { candidate ->
                    def file = candidate.canonicalFile
                    def kind = file.isFile() ? 'file' : file.isDirectory() ? 'directory' : 'missing'
                    [name: file.name, path: file.path, kind: kind,
                     projectArtifact: file.toPath().startsWith(project),
                     sha256: kind == 'file' ? hashFile(file) : kind == 'directory' ? hashDirectory(file) : null]
                }
                def report = [schema: 'vive-ink-task-runtime/1', task: task.path,
                    javaExecutable: executable.path, javaExecutableSha256: hashFile(executable),
                    javaRuntimeVersion: launcher.metadata.javaRuntimeVersion, vendor: launcher.metadata.vendor,
                    vmVersion: launcher.metadata.jvmVersion, vmArgs: task.allJvmArgs,
                    workingDirectory: task.workingDir.canonicalPath, classpath: entries]
                new File(gradle.startParameter.projectProperties.get('pairedInkRuntimeOutput')).text =
                    JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n'
            }
        }
    }
}
'''


def command(kind, fork, report, runtime, init):
    args = ["./gradlew", TASKS[kind], "--offline", "--max-workers=1", "--console=plain", "--no-configuration-cache",
            "-I", str(init), f"-PpairedInkRuntimeOutput={runtime}"]
    if kind == "app":
        args += [f"-PperformanceBaselineOutput={report}", f"-PperformanceBaselineFork={fork}"]
    else:
        args = ["xvfb-run", "-a", "-s", "-screen 0 1280x1024x24", *args,
                f"-PbyteinkInkScenarioOutput={report}", "-PbyteinkInkScenarioRequireReuse=false"]
    return args


def capture(before, after, output, baseline_commit="602ff15"):
    before, after = before.resolve(), after.resolve()
    require(before != after, "Baseline and current checkouts must differ")
    output = new_output(output, before, after)
    require(git(before, "rev-parse", "HEAD") == git(before, "rev-parse", f"{baseline_commit}^{{commit}}"), "Baseline commit differs")
    require(not git(before, "status", "--porcelain", "--untracked-files=all"), "Baseline checkout must be clean")
    reference = read_json(before / FROZEN / "environment.json")["harnessFiles"]
    require(set(reference) == set(HARNESS), "Original frozen nine-file manifest differs")
    harness = [{"path": path, "sha256": reference[path]} for path in HARNESS]
    expected = [{key: case[key] for key in ("name", "units", "fixtureSha256")}
                for case in read_json(before / FROZEN / "app-fork-1.json")["cases"]]
    variants = {}
    for variant, project in (("before", before), ("after", after)):
        require(hashes(project, HARNESS) == harness, f"{variant}: frozen harness differs")
        variants[variant] = {"project": str(project), "commit": git(project, "rev-parse", "HEAD"),
            "workingTree": git(project, "status", "--short"), "sourceFiles": source_snapshot(project)}
    env = os.environ.copy()
    env.pop("WAYLAND_DISPLAY", None)
    env["XDG_SESSION_TYPE"] = "x11"
    cpu = platform.processor()
    if Path("/proc/cpuinfo").is_file():
        cpu = next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                    if line.startswith("model name")), cpu)
    environment = {"schema": "vive-paired-ink-environment/1", "complete": False, "forks": 3,
        "startedAt": datetime.now(timezone.utc).isoformat(), "cpu": cpu, "platform": platform.platform(),
        "logicalCpus": os.cpu_count(), "affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "javaHome": env.get("JAVA_HOME"), "variants": variants, "harnessFiles": harness, "toolFiles": tool_hashes(),
        "appFixtures": expected, "schedule": schedule()}
    output.mkdir(parents=True)
    for variant in VARIANTS:
        (output / variant).mkdir()
    init = output / "capture.init.gradle"
    init.write_text(init_script())
    write_json(output / "environment.json", environment)
    execution = []
    for row in schedule():
        variant, kind, fork = row["variant"], row["kind"], row["fork"]
        project = before if variant == "before" else after
        report = output / variant / f"{kind}-fork-{fork}.json"
        runtime = report.with_name(f"{kind}-fork-{fork}-runtime.json")
        log = report.with_name(f"{kind}-fork-{fork}-log.txt")
        args = command(kind, fork, report, runtime, init)
        print(f"{variant} fork{fork} {kind}", flush=True)
        execution.append({**row, "command": args, "status": "running", "startedAt": datetime.now(timezone.utc).isoformat()})
        write_json(output / "execution.json", {"runs": execution})
        try:
            with log.open("w") as target:
                subprocess.run(args, cwd=project, env=env, stdout=target, stderr=subprocess.STDOUT, check=True)
            require(report.is_file() and runtime.is_file(), f"Task did not emit fresh raw evidence: {report}")
            if kind == "ink":
                xml = ET.parse(project / INK_XML).getroot()
                require(int(xml.attrib.get("tests", 0)) == 1 and
                        all(int(xml.attrib.get(key, 0)) == 0 for key in ("failures", "errors", "skipped")), "Renderer test did not execute successfully")
                shutil.copyfile(project / INK_XML, report.with_name(f"ink-fork-{fork}-result.xml"))
            validate_capture(output, environment, execution)
            execution[-1]["status"] = "validated"
        except BaseException as failure:
            execution[-1].update(status="failed", errorType=type(failure).__name__, error=str(failure))
            environment["failure"] = {**row, "errorType": type(failure).__name__, "error": str(failure)}
            write_json(output / "environment.json", environment)
            raise
        finally:
            execution[-1]["finishedAt"] = datetime.now(timezone.utc).isoformat()
            write_json(output / "execution.json", {"runs": execution})
    for variant, project in (("before", before), ("after", after)):
        require(source_snapshot(project) == variants[variant]["sourceFiles"] and hashes(project, HARNESS) == harness,
                f"{variant}: source changed during capture")
    require(tool_hashes() == environment["toolFiles"], "Capture/comparison tooling changed during capture")
    result, markdown = comparison(output, environment)
    a = {row["path"]: row["sha256"] for row in variants["before"]["sourceFiles"]}
    b = {row["path"]: row["sha256"] for row in variants["after"]["sourceFiles"]}
    environment["changedSourceFiles"] = [{"path": path, "beforeSha256": a.get(path), "afterSha256": b.get(path)}
        for path in sorted(a.keys() | b.keys()) if a.get(path) != b.get(path)]
    environment["rawFiles"] = [{"path": path.relative_to(output).as_posix(), "sha256": digest(path)}
        for path in sorted(output.rglob("*")) if path.is_file() and path.name != "environment.json"]
    environment["complete"] = True
    environment["completedAt"] = datetime.now(timezone.utc).isoformat()
    write_json(output / "environment.json", environment)
    write_json(output / "comparison.json", result)
    (output / "comparison.md").write_text(markdown)
    return result


def analyze(output):
    environment = read_json(output / "environment.json")
    require(environment.get("complete") is True, "Capture did not complete its source/runtime validation")
    for entry in environment["rawFiles"]:
        require(digest(output / entry["path"]) == entry["sha256"], "Raw capture evidence changed")
    result, markdown = comparison(output, environment)
    write_json(output / "comparison.json", result)
    (output / "comparison.md").write_text(markdown)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path)
    parser.add_argument("--after", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline-commit", default="602ff15")
    parser.add_argument("--analyze", type=Path)
    args = parser.parse_args()
    if args.analyze:
        require(not any((args.before, args.after, args.output)), "Use --analyze or a new paired capture")
        result = analyze(args.analyze.resolve())
    else:
        require(all((args.before, args.after, args.output)), "Supply --before, --after and a new --output")
        result = capture(args.before, args.after, args.output, args.baseline_commit)
    print(f'Compared12 ink app cases and8 renderer cases; {result["structuralDeviationCount"]} counter deviations, '
          f'{result["failedPreservationCheckCount"]} preservation-check failures. Inspect every regression.', flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, ET.ParseError) as failure:
        print(f"Paired ink capture refused: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
