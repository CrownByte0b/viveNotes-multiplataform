#!/usr/bin/env python3
"""Compare completed three-fork ink captures without changing the frozen harness or baseline."""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import statistics
import sys
import tempfile

FORKS = (1, 2, 3)
PHASES = ("initial", "wet", "finish", "heldWet", "erasePreview", "eraseFinish", "total")
COUNTERS = (
    "sceneAndSceneIndexBuilds", "draws", "pathBuilds", "finishedPathBuilds", "rasterBuilds",
    "finishedStrokeVectorDraws", "shapeAdvances", "observations",
)
SCALARS = (
    "pageSnapshotIdentities", "erasedRows", "retainedRasterPixelBytes",
    "peakRetainedRasterPixelBytes", "fullPageSceneBuilds",
)
REPORT_IDENTITY = (
    "schema", "runtime", "runtimeVendor", "os", "nativeSha256", "nativeOrigin", "fixture",
    "timingScope", "pageCountScope", "sceneCountScope", "pendingAdditionScope",
)
CASE_IDENTITY = (
    "view", "density", "zoom", "viewportPx", "scrollXPx", "scrollYPx", "finishedPageStrokes",
    "pendingAdditions", "eraseCommitTimingScope",
)
EXPECTED_CASES = {(view, density, additions) for view in ("fit", "sparse")
                  for density in (1.0, 2.0) for additions in (1, 32)}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_json(path: Path) -> dict:
    require(path.is_file(), f"Missing completed capture file: {path}")
    value = json.loads(path.read_text())
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def fields(value: dict, keys: tuple[str, ...], label: str) -> dict:
    missing = set(keys) - value.keys()
    require(not missing, f"{label}: missing fields {sorted(missing)}")
    return {key: value[key] for key in keys}


def positive_samples(value: object, label: str) -> list[int]:
    require(isinstance(value, list) and bool(value), f"{label}: missing samples")
    require(all(type(item) is int and item > 0 for item in value), f"{label}: samples must be positive integer ns")
    return value


def case_key(case: dict) -> tuple[str, float, int]:
    return case["view"], case["density"], case["pendingAdditions"]


def structural(case: dict, label: str) -> dict[str, int]:
    result = fields(case, SCALARS, label)
    for phase in PHASES:
        values = case.get(phase)
        require(isinstance(values, dict) and set(values) == set(COUNTERS), f"{label}/{phase}: counter fields differ")
        result.update({f"{phase}.{key}": values[key] for key in COUNTERS})
    result["inputHandlerSampleCount"] = len(positive_samples(case.get("inputHandlerToDrawNanos"), label))
    require(all(type(value) is int and value >= 0 for value in result.values()), f"{label}: invalid structural counter")
    return result


def load_capture(directory: Path) -> dict:
    environment = read_json(directory / "environment.json")
    summary = read_json(directory / "summary.json")
    # capture.py writes summary only after all app/ink forks and its source-integrity checks finish.
    require(environment.get("forks") == 3 and summary.get("forks") == 3,
            f"{directory}: exactly three completed forks required")
    require(environment.get("inkScenario") is True, f"{directory}: ink capture was not enabled")
    for prefix in ("ink", "app"):
        require({path.name for path in directory.glob(f"{prefix}-fork-*.json")} ==
                {f"{prefix}-fork-{fork}.json" for fork in FORKS},
                f"{directory}: exactly three {prefix} fork files required")
    reports = []
    apps = []
    for fork in FORKS:
        path = directory / f"ink-fork-{fork}.json"
        report = read_json(path)
        require(report.get("schema") == "vive-ink-phase2-ui/1", f"{path}: unsupported schema")
        identity = fields(report, REPORT_IDENTITY, str(path))
        require(all(isinstance(value, str) and value for value in identity.values()), f"{path}: invalid identity")
        raw_cases = report.get("cases")
        require(isinstance(raw_cases, list) and len(raw_cases) == 8, f"{path}: exactly eight cases required")
        cases = {}
        for case in raw_cases:
            fields(case, CASE_IDENTITY, str(path))
            key = case_key(case)
            require(key not in cases, f"{path}: duplicate case {key}")
            require(key in EXPECTED_CASES, f"{path}: unexpected case {key}")
            require(case["finishedPageStrokes"] == 40_000 and case["viewportPx"] == 512,
                    f"{path}/{key}: unexpected fixture dimensions")
            require(case["scrollXPx"] == 17.25 and case["scrollYPx"] == 29.75,
                    f"{path}/{key}: unexpected fractional scroll")
            require(type(case.get("eraseReleaseToCommittedDrawNanos")) is int and
                    case["eraseReleaseToCommittedDrawNanos"] > 0, f"{path}/{key}: invalid commit duration")
            require(isinstance(case["eraseCommitTimingScope"], str) and bool(case["eraseCommitTimingScope"]),
                    f"{path}/{key}: missing commit scope")
            require(isinstance(case["zoom"], (int, float)) and not isinstance(case["zoom"], bool) and
                    math.isfinite(case["zoom"]) and case["zoom"] > 0, f"{path}/{key}: invalid zoom")
            structural(case, f"{path}/{key}")
            cases[key] = case
        require(set(cases) == EXPECTED_CASES, f"{path}: workload set differs")
        reports.append({"identity": identity, "cases": cases, "path": str(path)})
        apps.append(read_json(directory / f"app-fork-{fork}.json"))
    return {"environment": environment, "reports": reports, "apps": apps}


def check_compatibility(before: dict, after: dict) -> None:
    for key in ("cpu", "logicalCpus", "platform", "affinity", "harnessFiles"):
        require(key in before["environment"] and key in after["environment"], f"Missing environment field {key}")
        require(before["environment"][key] == after["environment"][key], f"Capture environment differs: {key}")
    anchor = before["reports"][0]
    app_anchor = before["apps"][0]
    app_identity_keys = ("runtime", "vendor", "vm", "maxHeapBytes", "vmArgs")
    app_identity = fields(app_anchor, app_identity_keys, "baseline app runtime")
    external = lambda value: [item for item in value["runtimeArtifacts"] if not item["projectArtifact"]]
    artifacts = external(app_anchor)
    for side, capture in (("before", before), ("after", after)):
        for fork, report, app in zip(FORKS, capture["reports"], capture["apps"]):
            require(report["identity"] == anchor["identity"], f"{side} fork{fork}: runtime/native/fixture/timing scopes differ")
            require(fields(app, app_identity_keys, f"{side} app fork{fork}") == app_identity,
                    f"{side} app fork{fork}: runtime/VM arguments differ")
            require(external(app) == artifacts, f"{side} app fork{fork}: external runtime artifact bytes differ")
            for key in EXPECTED_CASES:
                require(fields(report["cases"][key], CASE_IDENTITY, f"{side}/{key}") ==
                        fields(anchor["cases"][key], CASE_IDENTITY, f"baseline/{key}"),
                        f"{side} fork{fork}/{key}: workload or commit timing scope differs")


def timings(cases: list[dict]) -> dict:
    samples = [positive_samples(case["inputHandlerToDrawNanos"], "handler timing") for case in cases]
    commits = [case["eraseReleaseToCommittedDrawNanos"] for case in cases]
    return {
        "pooledHandlerMedianMs": statistics.median([item for group in samples for item in group]) / 1_000_000,
        "forkHandlerMedianMs": [statistics.median(group) / 1_000_000 for group in samples],
        "forkHandlerSampleCounts": [len(group) for group in samples],
        "pooledHandlerSampleCount": sum(map(len, samples)),
        "medianEraseCommitMs": statistics.median(commits) / 1_000_000,
        "forkEraseCommitMs": [value / 1_000_000 for value in commits],
        "forkEraseCommitNanos": commits,
        "eraseCommitTimingScope": cases[0]["eraseCommitTimingScope"],
    }


def preservation_checks(case: dict, original: dict) -> list[dict]:
    values = structural(case, "preservation")
    expected = {
        "fullPageSceneBuilds": 1,
        "pageSnapshotIdentities": case["pendingAdditions"] + 2,
        "erasedRows": original["erasedRows"],
        "heldWet.sceneAndSceneIndexBuilds": 0,
        "heldWet.rasterBuilds": 0,
        "heldWet.finishedPathBuilds": 0,
        "heldWet.finishedStrokeVectorDraws": 0,
        "heldWet.observations": 6,
        "heldWet.shapeAdvances": 6,
        "erasePreview.sceneAndSceneIndexBuilds": 0,
    }
    result = [{"counter": key, "expected": wanted, "actual": values[key], "passed": values[key] == wanted}
              for key, wanted in expected.items()]
    actual = values["peakRetainedRasterPixelBytes"]
    result.append({"counter": "peakRetainedRasterPixelBytes", "expectedMin": 512 * 512 * 4,
                   "expectedMax": 2 * 513 * 513 * 4, "actual": actual,
                   "passed": 512 * 512 * 4 <= actual <= 2 * 513 * 513 * 4})
    return result


def compare(before_dir: Path, after_dir: Path) -> tuple[dict, str]:
    before = load_capture(before_dir)
    after = load_capture(after_dir)
    check_compatibility(before, after)
    comparisons = []
    deviation_count = 0
    for key in sorted(EXPECTED_CASES):
        old = [report["cases"][key] for report in before["reports"]]
        new = [report["cases"][key] for report in after["reports"]]
        old_timing, new_timing = timings(old), timings(new)
        old_counts = [structural(case, "before") for case in old]
        new_counts = [structural(case, "after") for case in new]
        deviations = [{"fork": fork, "counter": counter, "before": prior[counter], "after": current[counter],
                       "delta": current[counter] - prior[counter]}
                      for fork, prior, current in zip(FORKS, old_counts, new_counts)
                      for counter in sorted(prior) if current[counter] != prior[counter]]
        checks = [{"fork": fork, **check} for fork, original, current in zip(FORKS, old, new)
                  for check in preservation_checks(current, original)]
        deviation_count += len(deviations)
        comparisons.append({
            "workload": fields(old[0], CASE_IDENTITY, "case"),
            "before": old_timing, "after": new_timing,
            "handlerChangePercent": (new_timing["pooledHandlerMedianMs"] / old_timing["pooledHandlerMedianMs"] - 1) * 100,
            "eraseCommitChangePercent": (new_timing["medianEraseCommitMs"] / old_timing["medianEraseCommitMs"] - 1) * 100,
            "beforeStructuralByFork": old_counts, "afterStructuralByFork": new_counts,
            "structuralDeviations": deviations, "preservationChecks": checks,
        })
    failed_checks = sum(not check["passed"] for case in comparisons for check in case["preservationChecks"])
    notes = [
        "Handler median pools raw observations across three fresh JVMs, matching the frozen ink-summary.md; fork medians and sample counts are retained separately.",
        "Erase commit has one observation per fork: median of three, with all three raw ns/ms values and unchanged timing scope retained; no p95/confidence claim.",
        "Timings include test scheduling/GC; handler completion may precede processing queued samples. These are not physical input-to-photon, FPS or whole-app frame results.",
        "Structural deviations pair fork numbers for auditing; inspect every case/fork. Different draw/worker-publication attribution is not automatically a correctness failure.",
        "Raster counters measure retained physical N32 viewports, excluding transient old/new copy surfaces, native RSS and other heap/native objects.",
        "This harness uses Object erasing and at most32 appended strokes; it does not benchmark Normal erasing or the128th append boundary.",
        "Schema has no independent renderer fixture SHA: identical frozen harness SHA plus exact case/fixture/native/runtime fields establish workload compatibility.",
        "No timing thresholds are asserted. Structural preservation failures are reported for review, not hidden by averaging or treated as timing failures.",
    ]
    report = {
        "schema": "vive-ink-comparison/1", "beforeDirectory": str(before_dir), "afterDirectory": str(after_dir),
        "forks": 3, "caseCount": 8, "compatibilityPassed": True,
        "runtimeFixtureIdentity": before["reports"][0]["identity"],
        "environmentChecked": ["cpu", "logicalCpus", "platform", "affinity", "harnessFiles"],
        "appRuntimeAndExternalArtifactsCheckedAcrossAllForks": True,
        "inputFiles": {"before": [report["path"] for report in before["reports"]],
                       "after": [report["path"] for report in after["reports"]]},
        "structuralDeviationCount": deviation_count, "failedPreservationCheckCount": failed_checks,
        "notes": notes, "cases": comparisons,
    }
    lines = ["# Ink renderer comparison", "", "Three fresh JVMs; eight matched 40k-stroke cases. No timing assertions.", "",
             "| View | Density | Additions | Before pooled handler median ms | After ms | Change | Before fork medians ms | After fork medians ms |",
             "|---|---:|---:|---:|---:|---:|---|---|"]
    fmt = lambda values: ", ".join(f"{value:.3f}" for value in values)
    for case in comparisons:
        w, old, new = case["workload"], case["before"], case["after"]
        lines.append(f'| {w["view"]} | {w["density"]} | {w["pendingAdditions"]} | {old["pooledHandlerMedianMs"]:.3f} | '
                     f'{new["pooledHandlerMedianMs"]:.3f} | {case["handlerChangePercent"]:+.1f}% | '
                     f'{fmt(old["forkHandlerMedianMs"])} | {fmt(new["forkHandlerMedianMs"])} |')
    lines += ["", "| View | Density | Additions | Before release→committed draw median ms | After ms | Change | Before raw fork ms | After raw fork ms |",
              "|---|---:|---:|---:|---:|---:|---|---|"]
    for case in comparisons:
        w, old, new = case["workload"], case["before"], case["after"]
        lines.append(f'| {w["view"]} | {w["density"]} | {w["pendingAdditions"]} | {old["medianEraseCommitMs"]:.3f} | '
                     f'{new["medianEraseCommitMs"]:.3f} | {case["eraseCommitChangePercent"]:+.1f}% | '
                     f'{fmt(old["forkEraseCommitMs"])} | {fmt(new["forkEraseCommitMs"])} |')
    lines += ["", f"Structural deviations: {deviation_count}. Failed preserved-work checks: {failed_checks}.", "",
              "| View | Density | Additions | Fork | Counter | Before | After | Delta |",
              "|---|---:|---:|---:|---|---:|---:|---:|"]
    for case in comparisons:
        w = case["workload"]
        for change in case["structuralDeviations"]:
            lines.append(f'| {w["view"]} | {w["density"]} | {w["pendingAdditions"]} | {change["fork"]} | '
                         f'{change["counter"]} | {change["before"]} | {change["after"]} | {change["delta"]:+} |')
    if not deviation_count:
        lines.append("| All | | | | No structural deviations | | | |")
    failures = [(case["workload"], check) for case in comparisons for check in case["preservationChecks"] if not check["passed"]]
    if failures:
        lines += ["", "Failed preservation checks:", ""]
        for work, check in failures:
            lines.append(f'- {work["view"]}/density{work["density"]}/additions{work["pendingAdditions"]}/fork{check["fork"]}: '
                         f'{check["counter"]}: {json.dumps(check, sort_keys=True)}')
    lines += ["", "Notes:", ""] + [f"- {note}" for note in notes]
    return report, "\n".join(lines) + "\n"


def atomic_write(directory: Path, name: str, value: str) -> None:
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, prefix=f".{name}.", delete=False) as output:
            temporary = Path(output.name)
            output.write(value)
        os.replace(temporary, directory / name)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()
    before, after = args.before.resolve(), args.after.resolve()
    require(before.is_dir() and after.is_dir(), "Both captures must already exist")
    require(before != after and not before.is_relative_to(after), "AFTER must be separate from the baseline")
    report, markdown = compare(before, after)
    # Everything validates before either AFTER output is opened. Baseline paths are read-only.
    atomic_write(after, "ink-comparison.json", json.dumps(report, indent=2, allow_nan=False) + "\n")
    atomic_write(after, "ink-comparison.md", markdown)
    print(f'Compared 8 cases × 3 forks; {report["structuralDeviationCount"]} structural deviations; '
          f'{report["failedPreservationCheckCount"]} preservation-check failures.')
    print(f"Wrote {after / 'ink-comparison.json'} and {after / 'ink-comparison.md'}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as error:
        print(f"Ink comparison refused: {error}", file=sys.stderr)
        sys.exit(1)
