#!/usr/bin/env python3
"""Capture serial fresh-JVM baselines, or compare captures. Python standard library only."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def aggregate(reports: list[dict]) -> list[dict]:
    if not reports:
        raise ValueError("At least one report is required")
    cases: dict[tuple[str, int], list[dict]] = {}
    expected = {(case["name"], case["units"]) for case in reports[0]["cases"]}
    for report in reports:
        keys = [(case["name"], case["units"]) for case in report["cases"]]
        if len(keys) != len(set(keys)) or set(keys) != expected:
            raise ValueError("Workload sets differ between forks")
        for case in report["cases"]:
            cases.setdefault((case["name"], case["units"]), []).append(case)
    result = []
    for (name, units), forks in sorted(cases.items()):
        if len({case["fixtureSha256"] for case in forks}) != 1:
            raise ValueError(f"Fixture changed between forks: {name}/{units}")
        medians = [case["medianNanos"] / 1_000_000 for case in forks]
        value = {
            "name": name, "units": units, "fixtureSha256": forks[0]["fixtureSha256"],
            "medianMs": statistics.median(medians), "forkMedianMs": medians,
            "forkP95BatchAverageMs": [case["p95BatchAverageNanos"] / 1_000_000 for case in forks],
        }
        if all("medianAllocatedBytes" in case for case in forks):
            value["medianAllocatedBytes"] = statistics.median(case["medianAllocatedBytes"] for case in forks)
        result.append(value)
    return result


def run_logged(command: list[str], log: Path, env: dict[str, str]) -> None:
    print(f"Running {log.name}", flush=True)
    with log.open("w") as output:
        completed = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    if completed.returncode:
        raise RuntimeError(f"Command failed ({completed.returncode}); see {log}")


def capture(output: Path, forks: int, ink: bool) -> None:
    if forks < 1:
        raise ValueError("Fork count must be positive")
    if output.exists() and any(output.iterdir()):
        raise ValueError(f"Refusing to overwrite nonempty capture: {output}")
    output.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.pop("WAYLAND_DISPLAY", None)
    env["XDG_SESSION_TYPE"] = "x11"
    sources = sorted(set(path for directory in ("shared/src/commonMain", "shared/src/jvmMain", "desktopApp/src/main", "shared/schemas")
                         for path in (ROOT / directory).rglob("*") if path.is_file()))
    harness = sorted((ROOT / "shared/src/jvmTest/kotlin/com/vivenotes/performance").glob("*.kt"))
    harness += [ROOT / "shared/src/jvmTest/kotlin/com/vivenotes/ui/canvas/InkLayerPerformanceScenario.kt",
                ROOT / "benchmarks/capture.py", ROOT / "shared/build.gradle.kts"]
    cpu_info = Path("/proc/cpuinfo")
    cpu = next((line.split(":", 1)[1].strip() for line in cpu_info.read_text().splitlines()
                if line.startswith("model name")), platform.processor()) if cpu_info.exists() else platform.processor()
    metadata = {
        "schema": "vive-desktop-capture/1", "startedAt": datetime.now(timezone.utc).isoformat(),
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "workingTree": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True),
        "cpu": cpu, "logicalCpus": os.cpu_count(), "platform": platform.platform(),
        "affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
        "javaHome": env.get("JAVA_HOME"), "forks": forks, "inkScenario": ink,
        "productionFiles": {str(path.relative_to(ROOT)): sha256(path) for path in sources},
        "harnessFiles": {str(path.relative_to(ROOT)): sha256(path) for path in harness},
    }
    (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    reports = []
    for fork in range(1, forks + 1):
        report = output / f"app-fork-{fork}.json"
        command = ["./gradlew", ":shared:performanceBaseline", f"-PperformanceBaselineOutput={report}",
                   f"-PperformanceBaselineFork={fork}", "--offline", "--max-workers=1", "--console=plain"]
        run_logged(command, output / f"app-fork-{fork}.log", env)
        reports.append(json.loads(report.read_text()))
        if ink:
            ink_report = output / f"ink-fork-{fork}.json"
            run_logged(["xvfb-run", "-a", "-s", "-screen 0 1280x1024x24", "./gradlew", ":shared:inkPhase2Scenario",
                        f"-PbyteinkInkScenarioOutput={ink_report}", "--offline", "--max-workers=1", "--console=plain"],
                       output / f"ink-fork-{fork}.log", env)
    for path in sources + harness:
        original = metadata["productionFiles" if path in sources else "harnessFiles"][str(path.relative_to(ROOT))]
        if sha256(path) != original:
            raise RuntimeError(f"Source changed during capture: {path}")
    summary = {"schema": "vive-desktop-summary/1", "sourceCommit": metadata["sourceCommit"],
               "forks": forks, "cases": aggregate(reports)}
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    lines = ["| Workload | Units | Median ms | Fork medians ms | Caller allocation bytes/op |",
             "|---|---:|---:|---|---:|"]
    for case in summary["cases"]:
        allocation = f'{case["medianAllocatedBytes"]:,.0f}' if "medianAllocatedBytes" in case else "unavailable"
        medians = ", ".join(f"{value:.3f}" for value in case["forkMedianMs"])
        lines.append(f'| {case["name"]} | {case["units"]:,} | {case["medianMs"]:.3f} | {medians} | {allocation} |')
    (output / "summary.md").write_text("\n".join(lines) + "\n")
    print(f"Saved {len(summary['cases'])} workloads × {forks} forks to {output}", flush=True)


def compare(before: Path, after: Path) -> None:
    prior = json.loads((before / "summary.json").read_text())
    current = json.loads((after / "summary.json").read_text())
    # Exact runtime, CPU and harness agreement makes these an engineering comparison; OS load
    # and CPU frequency still require the caller to control and assess.
    for key in ("cpu", "platform", "affinity", "harnessFiles"):
        if json.loads((before / "environment.json").read_text())[key] != json.loads((after / "environment.json").read_text())[key]:
            raise ValueError(f"Capture environments differ in {key}; results require a separate baseline")
    first = json.loads((before / "app-fork-1.json").read_text())
    second = json.loads((after / "app-fork-1.json").read_text())
    for key in ("runtime", "vendor", "vm", "maxHeapBytes", "vmArgs"):
        if first[key] != second[key]:
            raise ValueError(f"Capture runtime differs in {key}; results require a separate baseline")
    external = lambda report: [item for item in report["runtimeArtifacts"] if not item["projectArtifact"]]
    if external(first) != external(second):
        raise ValueError("External runtime dependencies differ; results require a separate baseline")
    old = {(case["name"], case["units"]): case for case in prior["cases"]}
    new = {(case["name"], case["units"]): case for case in current["cases"]}
    if old.keys() != new.keys():
        raise ValueError("Capture workload sets differ")
    print("| Workload | Units | Before ms | After ms | Change |")
    print("|---|---:|---:|---:|---:|")
    for key, previous in sorted(old.items()):
        case = new[key]
        if case["fixtureSha256"] != previous["fixtureSha256"]:
            raise ValueError(f"Fixture changed: {key}")
        change = (case["medianMs"] / previous["medianMs"] - 1) * 100
        print(f'| {key[0]} | {key[1]:,} | {previous["medianMs"]:.3f} | {case["medianMs"]:.3f} | {change:+.1f}% |')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--include-ink", action="store_true")
    parser.add_argument("--compare", nargs=2, type=Path, metavar=("BEFORE", "AFTER"))
    args = parser.parse_args()
    if args.compare:
        if args.output:
            parser.error("Use --compare or --output")
        compare(*args.compare)
    elif args.output:
        capture(args.output.resolve(), args.forks, args.include_ink)
    else:
        parser.error("Supply --output or --compare BEFORE AFTER")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
