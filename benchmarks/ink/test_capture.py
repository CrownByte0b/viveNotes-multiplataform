"""Lightweight contracts for the paired runner; never launches Gradle or a JVM."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("paired_ink_capture", Path(__file__).with_name("capture.py"))
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


class PairedInkCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="vive-ink-capture-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.directory = self.base / "paired"
        self.projects = {side: self.base / f"source-{side}" for side in capture.VARIANTS}
        app = capture.read_json(capture.ROOT / capture.FROZEN / "app-fork-1.json")
        ink = capture.read_json(capture.ROOT / capture.FROZEN / "ink-fork-1.json")
        self.environment = {"forks": 3, "harnessFiles": [{"path": path, "sha256": "a" * 64} for path in capture.HARNESS],
            "appFixtures": [{key: row[key] for key in ("name", "units", "fixtureSha256")} for row in app["cases"]],
            "variants": {side: {"project": str(project)} for side, project in self.projects.items()}}
        for side, factor in (("before", 1), ("after", 2)):
            (self.directory / side).mkdir(parents=True)
            for fork in capture.FORKS:
                app_report = deepcopy(app)
                app_report["fork"] = fork
                app_report["vmArgs"] = ["--enable-native-access=ALL-UNNAMED", "-Xmx2g",
                    f"-Dvivenotes.performance.scratch={self.projects[side]}/shared/build/performance-tmp"]
                app_report["runtimeArtifacts"] = [
                    {"name": "library.jar", "sha256": "c" * 64, "projectArtifact": False},
                    {"name": "shared.jar", "sha256": str(factor) * 64, "projectArtifact": True}]
                for row in app_report["cases"]:
                    samples = [factor * (fork * 100 + index) for index in range(15)]
                    row.update(sampleNanosPerOperation=samples, medianNanos=samples[7], p95BatchAverageNanos=samples[-1])
                    if "sampleAllocatedBytesPerOperation" in row:
                        row.update(sampleAllocatedBytesPerOperation=[factor * 128] * 15, medianAllocatedBytes=factor * 128)
                self.write(side, "app", fork, app_report)
                ink_report = deepcopy(ink)
                for row in ink_report["cases"]:
                    row.update(inputHandlerToDrawNanos=[factor * fork * 100, factor * fork * 200],
                        eraseReleaseToCommittedDrawNanos=factor * fork * 1_000_000,
                        pageSnapshotIdentities=row["pendingAdditions"] + 2, fullPageSceneBuilds=1,
                        retainedRasterPixelBytes=2 * 512 * 512 * 4, peakRetainedRasterPixelBytes=2 * 512 * 512 * 4)
                    for phase in capture.compare_ink.PHASES:
                        row[phase] = {name: 0 for name in capture.compare_ink.COUNTERS}
                    row["initial"].update(sceneAndSceneIndexBuilds=1, rasterBuilds=1)
                    row["heldWet"].update(observations=6, shapeAdvances=6)
                    row["total"] = {name: sum(row[phase][name] for phase in capture.compare_ink.PHASES if phase != "total")
                                    for name in capture.compare_ink.COUNTERS}
                self.write(side, "ink", fork, ink_report)
                for kind in capture.TASKS:
                    vm_args = app_report["vmArgs"] if kind == "app" else ["--enable-native-access=ALL-UNNAMED", "-Xmx4g",
                        f"-Dvivenotes.ink.phase2Report={self.path(side, kind, fork)}", "-Dvivenotes.ink.phase2Optimized=false"]
                    self.write(side, kind, fork, {"schema": "vive-ink-task-runtime/1", "task": capture.TASKS[kind],
                        "javaExecutableSha256": "d" * 64, "javaRuntimeVersion": app["runtime"],
                        "vendor": app["vendor"], "vmVersion": app["runtime"], "vmArgs": vm_args,
                        "classpath": [{"name": "test", "path": str(self.projects[side] / "shared/build/classes/kotlin/jvm/test"),
                            "kind": "directory", "sha256": str(factor) * 64, "projectArtifact": True},
                            {"name": "library.jar", "path": str(self.base / "external/library.jar"),
                             "kind": "file", "sha256": "c" * 64, "projectArtifact": False}]}, runtime=True)

    def path(self, side, kind, fork, runtime=False):
        suffix = "-runtime" if runtime else ""
        return self.directory / side / f"{kind}-fork-{fork}{suffix}.json"

    def write(self, side, kind, fork, report, runtime=False):
        capture.write_json(self.path(side, kind, fork, runtime), report)

    def mutate_and_reject(self, path, change):
        original = path.read_bytes()
        report = capture.read_json(path)
        change(report)
        capture.write_json(path, report)
        try:
            with self.assertRaises((ValueError, KeyError, TypeError)):
                capture.validate_capture(self.directory, self.environment)
        finally:
            path.write_bytes(original)

    def prepare_projects(self):
        for project in self.projects.values():
            for relative in capture.HARNESS:
                target = project / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(f"frozen fixture {relative}\n")
        reference = self.projects["before"] / capture.FROZEN
        reference.mkdir(parents=True)
        capture.write_json(reference / "environment.json", {"harnessFiles":
            {row["path"]: row["sha256"] for row in capture.hashes(self.projects["before"], capture.HARNESS)}})
        capture.write_json(reference / "app-fork-1.json", capture.read_json(self.path("before", "app", 1)))

    @staticmethod
    def fake_git(project, *args):
        return "6" * 40 if args[0] == "rev-parse" else ""

    def test_schedule_alternates_variants_with_serial_fresh_task_invocations(self):
        rows = capture.schedule()
        self.assertEqual(12, len(rows))
        for fork in capture.FORKS:
            group = [row for row in rows if row["fork"] == fork]
            sides = ["after", "before"] if fork == 2 else ["before", "after"]
            self.assertEqual(["app", "app", "ink", "ink"], [row["kind"] for row in group])
            self.assertEqual(sides * 2, [row["variant"] for row in group])
        for kind in capture.TASKS:
            args = capture.command(kind, 2, Path("/report.json"), Path("/runtime.json"), Path("/init.gradle"))
            self.assertIn(capture.TASKS[kind], args)
            self.assertIn("--no-configuration-cache", args)
            self.assertIn("--offline", args)
            self.assertNotIn("--rerun-tasks", args)
            self.assertEqual(kind == "ink", args[0] == "xvfb-run")

    def test_normalization_only_replaces_checkout_boundaries_and_exact_report_property(self):
        root = Path("/checkouts/current")
        actual = capture.normalized_args(["-Droot=/checkouts/current/shared/build", "-Dother=/checkouts/current-other",
            "-Dvivenotes.ink.phase2Report=/captures/one.json"], root, Path("/captures/one.json"))
        self.assertEqual(["-Droot=<project>/shared/build", "-Dother=/checkouts/current-other",
                          "-Dvivenotes.ink.phase2Report=<report>"], actual)
        with self.assertRaisesRegex(ValueError, "report VM property"):
            capture.normalized_args(["-Dvivenotes.ink.phase2Report=/wrong.json"], root, Path("/captures/one.json"))

    def test_full_coverage_and_regressions_keep_raw_samples_and_every_counter_deviation(self):
        original = {path: path.read_bytes() for path in self.directory.rglob("*.json")}
        result, markdown = capture.comparison(self.directory, self.environment)
        self.assertEqual((48, 12, 8), (result["appCasesValidatedPerFork"], result["inkAppCasesCompared"], result["rendererCasesCompared"]))
        self.assertEqual(0, result["failedPreservationCheckCount"])
        self.assertEqual(0, result["structuralDeviationCount"])
        self.assertTrue(all(row["changePercent"] == 100 for row in result["appCases"]))
        self.assertTrue(all(row["handlerChangePercent"] == 100 for row in result["rendererCases"]))
        self.assertIn("+100.0%", markdown)
        self.assertEqual([15, 15, 15], list(map(len, result["appCases"][0]["after"]["forkSampleNanosPerOperation"])))
        self.assertEqual([2, 2, 2], result["rendererCases"][0]["after"]["forkHandlerSampleCounts"])
        self.assertEqual([2_000_000, 4_000_000, 6_000_000], result["rendererCases"][0]["after"]["forkEraseCommitNanos"])
        self.assertTrue(all(path.read_bytes() == value for path, value in original.items()))
        report = capture.read_json(self.path("after", "ink", 3))
        report["cases"][0]["heldWet"]["rasterBuilds"] = 1
        self.write("after", "ink", 3, report)
        result, markdown = capture.comparison(self.directory, self.environment)
        self.assertEqual(1, result["structuralDeviationCount"])
        self.assertEqual(1, result["failedPreservationCheckCount"])
        self.assertIn("heldWet.rasterBuilds", markdown)
        self.assertIn("Failed preservation", markdown)
        self.assertEqual({"fork": 3, "counter": "heldWet.rasterBuilds", "before": 0, "after": 1, "delta": 1},
                         result["rendererCases"][0]["structuralDeviations"][0])

    def test_runtime_launcher_dependencies_flags_and_native_must_match_even_last_fork(self):
        app = self.path("after", "app", 3)
        ink = self.path("after", "ink", 3)
        runtime = self.path("after", "ink", 3, runtime=True)
        changes = [(app, lambda value: value.update(runtime="different")),
            (app, lambda value: value.update(maxHeapBytes=123)),
            (app, lambda value: value["vmArgs"].append("-Dchanged=true")),
            (app, lambda value: value["runtimeArtifacts"][0].update(sha256="e" * 64)),
            (ink, lambda value: value.update(nativeSha256="e" * 64)),
            (ink, lambda value: value.update(timingScope="changed scope")),
            (runtime, lambda value: value.update(javaExecutableSha256="e" * 64)),
            (runtime, lambda value: value.update(task=":shared:other")),
            (runtime, lambda value: value["vmArgs"].append("-Dchanged=true")),
            (runtime, lambda value: value["classpath"][1].update(sha256="e" * 64))]
        for path, change in changes:
            with self.subTest(path=path.name, change=change):
                self.mutate_and_reject(path, change)

    def test_case_coverage_order_fixture_hashes_and_raw_statistics_must_match(self):
        app = self.path("after", "app", 2)
        ink = self.path("after", "ink", 2)
        changes = [(app, lambda value: value["cases"].pop()),
            (app, lambda value: value["cases"].__setitem__(1, deepcopy(value["cases"][0]))),
            (app, lambda value: value["cases"].reverse()),
            (app, lambda value: value["cases"][0].update(fixtureSha256="e" * 64)),
            (app, lambda value: value["cases"][0].update(medianNanos=1)),
            (app, lambda value: value["cases"][0].update(p95BatchAverageNanos=1)),
            (ink, lambda value: value["cases"].pop()),
            (ink, lambda value: value["cases"].reverse()),
            (ink, lambda value: value["cases"][0].update(zoom=99))]
        for path, change in changes:
            with self.subTest(path=path.name, change=change):
                self.mutate_and_reject(path, change)

    def test_output_must_be_new_and_never_nested_in_an_existing_capture(self):
        marker = self.directory / "keep.txt"
        marker.write_text("untouched")
        with patch.object(capture, "git") as git, patch.object(capture.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "already exists"):
                capture.capture(self.projects["before"], self.projects["after"], self.directory)
            git.assert_not_called()
            run.assert_not_called()
        self.assertEqual("untouched", marker.read_text())
        with self.assertRaisesRegex(ValueError, "baseline checkout"):
            capture.new_output(self.projects["before"] / "new", *self.projects.values())
        old = self.projects["after"] / "benchmarks/results/prior"
        old.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "new sibling"):
            capture.new_output(old / "nested", *self.projects.values())

    def test_frozen_harness_drift_is_rejected_before_output_creation_or_gradle(self):
        self.prepare_projects()
        (self.projects["after"] / capture.HARNESS[-1]).write_text("changed build")
        output = self.base / "drift-output"
        with patch.object(capture, "git", side_effect=self.fake_git), patch.object(capture.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "frozen harness differs"):
                capture.capture(self.projects["before"], self.projects["after"], output)
            run.assert_not_called()
        self.assertFalse(output.exists())

    def test_partial_capture_validates_each_task_and_retains_mismatched_raw_evidence(self):
        self.prepare_projects()
        output = self.base / "partial-output"
        calls = []

        def fake_run(args, *, cwd, env, stdout, stderr, check):
            calls.append(args)
            self.assertLessEqual(len(calls), 2)
            side = "before" if cwd == self.projects["before"] else "after"
            target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PperformanceBaselineOutput=")))
            runtime_target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PpairedInkRuntimeOutput=")))
            target.write_bytes(self.path(side, "app", 1).read_bytes())
            metadata = capture.read_json(self.path(side, "app", 1, runtime=True))
            if side == "after":
                metadata["classpath"][1]["sha256"] = "e" * 64
            capture.write_json(runtime_target, metadata)
            stdout.write(f"retained-run-{len(calls)}\n")
            return subprocess.CompletedProcess(args, 0)

        with patch.object(capture, "git", side_effect=self.fake_git), \
                patch.object(capture.platform, "processor", return_value="synthetic CPU"), \
                patch.object(capture.platform, "platform", return_value="synthetic OS"), \
                patch.object(capture.subprocess, "run", side_effect=fake_run):
            with self.assertRaisesRegex(ValueError, "external classpath differ"):
                capture.capture(self.projects["before"], self.projects["after"], output)
        self.assertEqual(2, len(calls))
        self.assertEqual(["validated", "failed"], [row["status"] for row in capture.read_json(output / "execution.json")["runs"]])
        environment = capture.read_json(output / "environment.json")
        self.assertFalse(environment["complete"])
        self.assertEqual("after", environment["failure"]["variant"])
        self.assertTrue((output / "after/app-fork-1.json").is_file())
        self.assertIn("retained-run-2", (output / "after/app-fork-1-log.txt").read_text())
        self.assertFalse((output / "comparison.json").exists())
        with self.assertRaisesRegex(ValueError, "already exists"):
            capture.capture(self.projects["before"], self.projects["after"], output)

    def test_failed_process_keeps_partial_output_and_does_not_start_another_jvm(self):
        self.prepare_projects()
        output = self.base / "process-failed-output"

        def fail(args, **kwargs):
            kwargs["stdout"].write("deliberate process failure\n")
            raise subprocess.CalledProcessError(23, args)

        with patch.object(capture, "git", side_effect=self.fake_git), \
                patch.object(capture.platform, "processor", return_value="synthetic CPU"), \
                patch.object(capture.platform, "platform", return_value="synthetic OS"), \
                patch.object(capture.subprocess, "run", side_effect=fail) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                capture.capture(self.projects["before"], self.projects["after"], output)
            self.assertEqual(1, run.call_count)
        self.assertFalse(capture.read_json(output / "environment.json")["complete"])
        self.assertEqual("failed", capture.read_json(output / "execution.json")["runs"][0]["status"])
        self.assertIn("deliberate process failure", (output / "before/app-fork-1-log.txt").read_text())
        with self.assertRaisesRegex(ValueError, "did not complete"):
            capture.analyze(output)

    def test_analyze_refuses_partial_or_tampered_raw_without_overwriting_derived_results(self):
        sentinel = self.directory / "comparison.json"
        sentinel.write_text("keep derived output")
        capture.write_json(self.directory / "environment.json", {**self.environment, "complete": False})
        with self.assertRaisesRegex(ValueError, "did not complete"):
            capture.analyze(self.directory)
        raw = self.path("before", "app", 1)
        environment = {**self.environment, "complete": True, "rawFiles": [{"path": raw.relative_to(self.directory).as_posix(),
                                                                          "sha256": capture.digest(raw)}]}
        capture.write_json(self.directory / "environment.json", environment)
        raw.write_text(raw.read_text() + " ")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            capture.analyze(self.directory)
        self.assertEqual("keep derived output", sentinel.read_text())

    def test_published_hashes_are_array_records_with_separate_path_and_sha_fields(self):
        source = self.base / "KeyBindings.kt"
        source.write_text("synthetic source\n")
        rows = capture.hashes(self.base, ["KeyBindings.kt"])
        self.assertEqual([{"path": "KeyBindings.kt", "sha256": capture.digest(source)}], rows)
        encoded = json.dumps(rows, indent=2)
        self.assertIn('"path": "KeyBindings.kt",\n', encoded)
        self.assertNotIn('"KeyBindings.kt":', encoded)


if __name__ == "__main__":
    unittest.main()
