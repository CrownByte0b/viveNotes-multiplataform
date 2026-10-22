"""Deterministic tooling contracts; no Gradle, JVM, model inference or user profile access."""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("paired_ocr_capture", Path(__file__).with_name("capture.py"))
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


def fingerprint(value):
    return hashlib.sha256(str(value).encode()).hexdigest()


class OcrCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="vive-ocr-tool-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.projects = {side: self.base / side for side in capture.SIDES}
        self.directory = self.base / "capture"
        for side in capture.SIDES:
            (self.directory / side).mkdir(parents=True)

    def runtime(self, side):
        return {"java": "27+35", "vendor": "synthetic vendor", "vm": "synthetic VM", "ort": "1.31.0",
            "os": "Linux", "arch": "amd64", "processors": 4, "maxHeapBytes": 2 ** 31,
            "vmArgs": ["-Xmx2g", f'-Dvivenotes.performance.scratch={self.projects[side]}/shared/build/performance-tmp']}

    def batch(self, lanes, factor, index):
        return {"wallNanos": factor * (1000 + index), "lanes": [
            {"wallNanos": factor * (100 + index), "callerCpuNanos": factor * (10 + index),
             "allocatedBytes": factor * (128 + index)} for _ in range(lanes)]}

    def samples(self, lanes, factor, count):
        batches = [self.batch(lanes, factor, index) for index in range(count)]
        samples = [sample for batch in batches for sample in batch["lanes"]]
        return {"batches": batches, "processCpuNanos": factor * 5000,
            "medianBatchWallNanos": capture.upper_median([batch["wallNanos"] for batch in batches]),
            "medianLaneWallNanos": capture.upper_median([sample["wallNanos"] for sample in samples]),
            "medianLaneCpuNanos": capture.upper_median([sample["callerCpuNanos"] for sample in samples]),
            "medianLaneAllocatedBytes": capture.upper_median([sample["allocatedBytes"] for sample in samples])}

    def ctc(self, row):
        side = row["side"]
        factor = 1 if side == "before" else 2
        stages = list(capture.MEMORY_STAGES)
        stages.insert(stages.index("kernel-warmup-complete"), "frozen-output-validation-collected")
        return {"schema": "vive-ocr-ctc-profile/1", "mode": row["mode"], "lanes": row["lanes"],
            "inferenceThreads": row["threads"], "inputShape": [1, 3, 48, row["width"]],
            "outputShape": [1, row["width"] // 8, 18_710], "inputSha256": fingerprint(row["width"]),
            "outputSha256": fingerprint((row["width"], row["threads"])), "readingSha256": fingerprint("synthetic reading"),
            "confidenceBits": 1060320051, **capture.PINS, "fixturePreparationNanos": 100,
            "sessionOpenNanos": factor * 200, "nativeReductionOpens": 0 if side == "before" else 1,
            "nativeEvents": [], "cold": self.batch(row["lanes"], factor, 0),
            "pipelineWarmupBatches": 8, "pipelineWarm": self.samples(row["lanes"], factor, 20),
            "kernelWarmupBatches": 40, "kernelWarm": self.samples(row["lanes"], factor, 80),
            "runtime": self.runtime(side), "sessionOpenScope": "same-session-open-scope",
            "timingScope": "same-timing-scope", "allocationScope": "same-lane-allocation-scope",
            "summaryScope": "same-upper-median-scope", "memoryScope": "same-whole-process-scope",
            "collectionScope": "same-requested-gc-scope", "memory": [{"stage": stage, "atNanos": index,
                "rssBytes": factor * (1024 + index), "peakRssBytes": factor * (2048 + index),
                "heapUsedBytes": 512, "heapCommittedBytes": 4096, "nonHeapUsedBytes": 128,
                "gcCount": 1, "gcMillis": 0, "bufferPools": []} for index, stage in enumerate(stages)]}

    def geometry(self, row):
        factor = 1 if row["side"] == "before" else 2
        cases = [{"name": name, "units": 1, "fixtureSha256": fingerprint(name + "-fixture"),
            "outputSha256": fingerprint(name + "-output"), "sampleCount": 31, "batchOperations": 10,
            "warmupNanos": 400_000_001, "sampleNanosPerOperation": [factor * value for value in range(1, 32)],
            "medianNanos": factor * 16} for name in capture.GEOMETRY_NAMES]
        images = []
        for index, name in enumerate(("seven-lines", "dense-page", "slanted-page")):
            image = {"name": name, "mapWidth": 32, "mapHeight": 32, "regions": 1, "hullSizes": [4],
                     "probabilitySha256": cases[index * 2 + 1]["fixtureSha256"],
                     "hullsSha256": cases[index * 2]["fixtureSha256"],
                     "orderedQuadsSha256": cases[index * 2 + 1]["outputSha256"]}
            cases[index * 2 + 1]["units"] = 32 * 32
            images.append(image)
        return {"schema": "vive-ocr-geometry-profile/1", **capture.DETECTOR, "cases": cases, "images": images,
                "runtime": self.runtime(row["side"]), "timingScope": "same-geometry-scope", "fixtureScope": "same-fixture-scope"}

    def task(self, row, path):
        side, phase = row["side"], row["phase"]
        project = self.projects[side]
        arguments = [str(path), row["mode"], str(row["lanes"]), str(row["threads"]), str(row["width"])] \
            if phase == "ctc" else ["com.vivenotes.ai.OcrGeometryProfileTest"]
        vm_args = self.runtime(side)["vmArgs"]
        if phase == "geometry":
            vm_args += [f"-Dvivenotes.ocr.geometry.report={path}"]
        return {"schema": "vive-ocr-task-runtime/1", "task": f':shared:ocr{"Ctc" if phase == "ctc" else "Geometry"}Profile',
            "mainClass": "com.vivenotes.ai.OcrCtcProfile" if phase == "ctc" else "org.junit.runner.JUnitCore",
            "javaExecutableSha256": fingerprint("java"), "javaRuntimeVersion": "27+35", "vendor": "synthetic vendor",
            "vmVersion": "27+35", "vmArgs": vm_args, "programArgs": arguments, "workingDirectory": str(project),
            "classpath": [{"name": "test", "path": str(project / "shared/build/classes/kotlin/jvm/test"),
                "kind": "directory", "projectArtifact": True, "sha256": fingerprint(side)},
                {"name": "onnxruntime.jar", "path": str(self.base / "external/onnxruntime.jar"), "kind": "file",
                 "projectArtifact": False, "sha256": fingerprint("same-external-native-library")} ]}

    def populate(self, phase):
        environment = {"phase": phase, "forks": 3, "variants": {side: {"project": str(project)} for side, project in self.projects.items()}}
        for row in capture.schedule(phase):
            path = capture.report_path(self.directory, row)
            report = self.ctc(row) if phase == "ctc" else self.geometry(row)
            if phase == "geometry":
                report["runtime"]["vmArgs"].append(f"-Dvivenotes.ocr.geometry.report={path}")
            capture.write_json(path, report)
            capture.write_json(path.with_name(path.stem + "-runtime.json"), self.task(row, path))
        return environment

    def reject_mutation(self, environment, path, change):
        original = path.read_bytes()
        value = capture.read_json(path)
        change(value)
        capture.write_json(path, value)
        try:
            with self.assertRaises((ValueError, KeyError, TypeError)):
                capture.validate(self.directory, environment)
        finally:
            path.write_bytes(original)

    def prepare_projects(self):
        for project in self.projects.values():
            for relative in (*capture.HARNESS, *capture.DIAGNOSTICS):
                path = project / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"synthetic frozen {relative}\n")
        reference = self.projects["before"] / "benchmarks/results/2026-10-21"
        reference.mkdir(parents=True)
        capture.write_json(reference / "environment.json", {"harnessFiles": {
            entry["path"]: entry["sha256"] for entry in capture.hashes(self.projects["before"], capture.HARNESS)}})

    @staticmethod
    def fake_git(project, *args):
        return "e" * 40 if args[0] == "rev-parse" else ""

    def test_fixed_matrix_covers_every_pair_and_reverses_order_on_second_fork(self):
        rows = capture.schedule("ctc")
        self.assertEqual(24, len(rows))
        self.assertEqual(6, len(capture.schedule("geometry")))
        for fork in capture.FORKS:
            group = [row for row in rows if row["fork"] == fork]
            expected = ["after", "before"] if fork == 2 else ["before", "after"]
            self.assertEqual(expected * 4, [row["side"] for row in group])
            self.assertEqual(list(capture.CONFIGS), [(group[index]["lanes"], group[index]["threads"], group[index]["width"]) for index in range(0, 8, 2)])
        self.assertTrue(all(row["mode"] == ("flat" if row["side"] == "before" else "native") for row in rows))

    def test_ctc_comparison_keeps_time_allocation_and_rss_regressions_and_raw_files(self):
        environment = self.populate("ctc")
        original = {path: path.read_bytes() for path in self.directory.rglob("*.json")}
        result, markdown = capture.comparison(self.directory, environment)
        self.assertEqual(4, len(result["cases"]))
        for case in result["cases"]:
            self.assertTrue(all(metric["changePercent"] == 100 for metric in case["metrics"]))
            allocation = next(metric for metric in case["metrics"] if metric["name"] == "pipeline.upperMedianBatchAllocatedBytes")
            self.assertEqual(3, len(allocation["beforeByFork"]))
            self.assertEqual((128 + 10) * case["lanes"], allocation["beforeMedian"])
            self.assertEqual(100, next(point for point in case["memory"] if point["field"] == "rssBytes")["changePercent"])
        self.assertIn("+100.0%", markdown)
        self.assertIn("RSS MiB", markdown)
        self.assertTrue(all(path.read_bytes() == value for path, value in original.items()))

    def test_ctc_exact_output_confidence_pins_config_and_lifetimes_are_required(self):
        environment = self.populate("ctc")
        row = capture.schedule("ctc")[-1]
        path = capture.report_path(self.directory, row)
        changes = [lambda value: value.update(outputSha256=fingerprint("changed output")),
            lambda value: value.update(confidenceBits=1), lambda value: value.update(dictionaryBytes=123),
            lambda value: value.update(modelSha256=fingerprint("other model")),
            lambda value: value.update(mode="flat"), lambda value: value.update(inferenceThreads=1),
            lambda value: value.update(nativeReductionOpens=2),
            lambda value: value["nativeEvents"].append("native CTC reduction unavailable"),
            lambda value: value["pipelineWarm"]["batches"].pop(),
            lambda value: value["kernelWarm"].update(medianLaneAllocatedBytes=0),
            lambda value: value["memory"].reverse()]
        for change in changes:
            with self.subTest(change=change):
                self.reject_mutation(environment, path, change)

    def test_launcher_external_bytes_actual_flags_and_per_variant_project_bytes_are_checked(self):
        environment = self.populate("ctc")
        path = capture.report_path(self.directory, capture.schedule("ctc")[-1])
        runtime = path.with_name(path.stem + "-runtime.json")
        for change in (lambda value: value.update(javaExecutableSha256=fingerprint("other executable")),
                       lambda value: value["classpath"][1].update(sha256=fingerprint("other ORT")),
                       lambda value: value["classpath"][0].update(sha256=fingerprint("changed compiled output")),
                       lambda value: value["programArgs"].__setitem__(1, "flat"),
                       lambda value: value["vmArgs"].append("-Dchanged=true")):
            with self.subTest(change=change):
                self.reject_mutation(environment, runtime, change)
        self.reject_mutation(environment, path, lambda value: value["runtime"]["vmArgs"].append("-Dchanged=true"))

    def test_geometry_has_all_cases_real_hull_metadata_exact_outputs_and_raw_samples(self):
        environment = self.populate("geometry")
        result, markdown = capture.comparison(self.directory, environment)
        self.assertEqual(list(capture.GEOMETRY_NAMES), [case["name"] for case in result["cases"]])
        self.assertTrue(all(case["changePercent"] == 100 for case in result["cases"]))
        self.assertEqual(3, len(result["realDetectorImages"]))
        self.assertEqual([31] * 3, list(map(len, result["cases"][0]["afterSamples"])))
        self.assertIn("+100.0%", markdown)
        path = capture.report_path(self.directory, capture.schedule("geometry")[-1])
        for change in (lambda value: value["cases"].pop(), lambda value: value["cases"].reverse(),
                       lambda value: value["cases"][0].update(outputSha256=fingerprint("changed rectangle")),
                       lambda value: value["images"][0].update(regions=2),
                       lambda value: value["cases"][0].update(medianNanos=1)):
            with self.subTest(change=change):
                self.reject_mutation(environment, path, change)

    def test_vm_normalization_checks_exact_roots_and_report_property(self):
        actual = capture.normalized_args(["-Droot=/source/current/shared", "-Dother=/source/current-other",
            "-Dvivenotes.ocr.geometry.report=/out/report.json"], Path("/source/current"), Path("/out/report.json"))
        self.assertEqual(["-Droot=<project>/shared", "-Dother=/source/current-other", "-Dvivenotes.ocr.geometry.report=<report>"], actual)
        with self.assertRaisesRegex(ValueError, "report property"):
            capture.normalized_args(["-Dvivenotes.ocr.geometry.report=/wrong.json"], Path("/source/current"), Path("/out/report.json"))

    def test_output_refusal_precedes_any_subprocess_and_preserves_existing_capture(self):
        marker = self.directory / "keep.txt"
        marker.write_text("retained")
        with patch.object(capture, "git") as git, patch.object(capture.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "already exists"):
                capture.capture(self.projects["before"], self.projects["after"], "ctc", self.directory)
            git.assert_not_called()
            run.assert_not_called()
        self.assertEqual("retained", marker.read_text())
        old = self.projects["after"] / "benchmarks/results/prior"
        old.mkdir(parents=True)
        with self.assertRaisesRegex(ValueError, "new sibling"):
            capture.new_output(old / "nested", *self.projects.values())
        with self.assertRaisesRegex(ValueError, "baseline checkout"):
            capture.new_output(self.projects["before"] / "new", *self.projects.values())

    def test_baseline_tracked_or_extra_untracked_changes_are_rejected_before_launch(self):
        for kind in ("tracked", "untracked"):
            def fake_git(project, *args):
                if args[0] == "rev-parse":
                    return "e" * 40
                if args[0] == "diff" and kind == "tracked":
                    return "shared/src/jvmMain/changed.kt"
                if args[0] == "ls-files" and kind == "untracked":
                    return "unapproved-new-test.kt"
                return ""
            output = self.base / ("rejected-" + kind)
            with self.subTest(kind=kind), patch.object(capture, "git", side_effect=fake_git), patch.object(capture.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "Baseline"):
                    capture.capture(self.projects["before"], self.projects["after"], "ctc", output)
                run.assert_not_called()
            self.assertFalse(output.exists())

    def test_orchestration_stops_after_first_mismatch_and_keeps_partial_evidence(self):
        self.prepare_projects()
        output = self.base / "partial"
        calls = []

        def fake_run(args, *, cwd, stdout, stderr, check):
            row = capture.schedule("ctc")[len(calls)]
            calls.append(args)
            self.assertLessEqual(len(calls), 2)
            target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PocrCtcOutput=")))
            runtime_target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PocrRuntimeOutput=")))
            report = self.ctc(row)
            if len(calls) == 2:
                report["confidenceBits"] = 1
            capture.write_json(target, report)
            capture.write_json(runtime_target, self.task(row, target))
            stdout.write("retained partial log\n")
            return subprocess.CompletedProcess(args, 0)

        with patch.object(capture, "git", side_effect=self.fake_git), \
                patch.object(capture.platform, "processor", return_value="test CPU"), \
                patch.object(capture.platform, "platform", return_value="test OS"), \
                patch.object(capture.subprocess, "run", side_effect=fake_run):
            with self.assertRaisesRegex(ValueError, "confidence/lifecycle identity differs"):
                capture.capture(self.projects["before"], self.projects["after"], "ctc", output)
        self.assertEqual(2, len(calls))
        self.assertEqual(["validated", "failed"], [row["status"] for row in capture.read_json(output / "execution.json")["runs"]])
        self.assertFalse(capture.read_json(output / "environment.json")["complete"])
        self.assertTrue(list((output / "after").glob("*-log.txt")))
        self.assertTrue(list((output / "after").glob("*.json")))
        self.assertFalse((output / "comparison.json").exists())
        with self.assertRaisesRegex(ValueError, "already exists"):
            capture.capture(self.projects["before"], self.projects["after"], "ctc", output)
        with self.assertRaisesRegex(ValueError, "did not complete"):
            capture.analyze(output)

    def test_source_drift_after_task_is_rejected_before_second_launch(self):
        self.prepare_projects()
        output = self.base / "source-drift"

        def fake_run(args, *, cwd, stdout, stderr, check):
            row = capture.schedule("ctc")[0]
            target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PocrCtcOutput=")))
            runtime_target = Path(next(value.split("=", 1)[1] for value in args if value.startswith("-PocrRuntimeOutput=")))
            capture.write_json(target, self.ctc(row))
            capture.write_json(runtime_target, self.task(row, target))
            (self.projects["after"] / capture.DIAGNOSTICS[0]).write_text("changed source during run")
            return subprocess.CompletedProcess(args, 0)

        with patch.object(capture, "git", side_effect=self.fake_git), \
                patch.object(capture.platform, "processor", return_value="test CPU"), \
                patch.object(capture.platform, "platform", return_value="test OS"), \
                patch.object(capture.subprocess, "run", side_effect=fake_run) as run:
            with self.assertRaisesRegex(ValueError, "source changed during capture"):
                capture.capture(self.projects["before"], self.projects["after"], "ctc", output)
            self.assertEqual(1, run.call_count)
        self.assertFalse(capture.read_json(output / "environment.json")["complete"])

    def test_unsupported_metrics_stay_unavailable_and_tampered_raw_is_not_analyzed(self):
        environment = self.populate("ctc")
        for row in capture.schedule("ctc"):
            path = capture.report_path(self.directory, row)
            report = capture.read_json(path)
            for phase in ("pipelineWarm", "kernelWarm"):
                for batch in report[phase]["batches"]:
                    for sample in batch["lanes"]:
                        sample["allocatedBytes"] = -1
                report[phase]["medianLaneAllocatedBytes"] = -1
            capture.write_json(path, report)
        result, markdown = capture.comparison(self.directory, environment)
        self.assertIn("unavailable", markdown)
        self.assertTrue(all(metric["changePercent"] is None for case in result["cases"] for metric in case["metrics"] if "AllocatedBytes" in metric["name"]))
        environment.update(complete=True, toolFiles=capture.tool_hashes(), rawFiles=[
            {"path": path.relative_to(self.directory).as_posix(), "sha256": capture.digest(path)}
            for path in self.directory.rglob("*.json")])
        capture.write_json(self.directory / "environment.json", environment)
        path.write_text(path.read_text() + " ")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            capture.analyze(self.directory)

    def test_source_hashes_use_array_records_and_commands_keep_task_scope(self):
        path = self.base / "Tokenizer.kt"
        path.write_text("synthetic source")
        rows = capture.hashes(self.base, ["Tokenizer.kt"])
        self.assertIsInstance(rows, list)
        self.assertEqual({"path", "sha256"}, set(rows[0]))
        self.assertNotIn('"Tokenizer.kt":', json.dumps(rows, indent=2))
        for phase in ("ctc", "geometry"):
            command = capture.command(capture.schedule(phase)[0], Path("/report.json"), Path("/runtime.json"))
            self.assertIn("--offline", command)
            self.assertIn("--no-configuration-cache", command)
            self.assertIn("--max-workers=1", command)
            self.assertNotIn("--rerun-tasks", command)


if __name__ == "__main__":
    unittest.main()
