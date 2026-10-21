"""Deterministic contracts for renderer comparison; these tests run no app/benchmark process."""
from __future__ import annotations

import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import compare_ink


class InkComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.before = self.root / "before"
        self.after = self.root / "after"
        self.write_capture(self.before, factor=1)
        self.write_capture(self.after, factor=2)

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value, indent=2) + "\n")

    @staticmethod
    def snapshot(directory):
        return {str(path.relative_to(directory)): path.read_bytes() for path in directory.rglob("*") if path.is_file()}

    def write_capture(self, directory, factor):
        directory.mkdir()
        self.write_json(directory / "environment.json", {
            "forks": 3, "inkScenario": True, "cpu": "fixture CPU", "logicalCpus": 4,
            "platform": "fixture OS", "affinity": [0], "harnessFiles": {"scenario.kt": "frozen"},
        })
        self.write_json(directory / "summary.json", {"forks": 3})
        for fork in compare_ink.FORKS:
            cases = []
            for view in ("fit", "sparse"):
                for density in (1.0, 2.0):
                    for additions in (1, 32):
                        case = {
                            "view": view, "density": density, "pendingAdditions": additions,
                            "zoom": (.21333334 / density) if view == "fit" else 1.25,
                            "viewportPx": 512, "scrollXPx": 17.25, "scrollYPx": 29.75,
                            "finishedPageStrokes": 40_000, "pageSnapshotIdentities": additions + 2,
                            "erasedRows": 310 if view == "fit" else (56 if density == 1 else 30),
                            "retainedRasterPixelBytes": 2 * 512 * 512 * 4,
                            "peakRetainedRasterPixelBytes": 2 * 512 * 512 * 4,
                            "fullPageSceneBuilds": 1,
                            "eraseReleaseToCommittedDrawNanos": fork * 1_000_000 * factor,
                            "eraseCommitTimingScope": "release through worker commit and draw",
                            "inputHandlerToDrawNanos": [value * factor for value in {
                                1: [10, 30], 2: [20, 40], 3: [50, 60],
                            }[fork]],
                        }
                        for phase in compare_ink.PHASES:
                            case[phase] = dict.fromkeys(compare_ink.COUNTERS, 0)
                        case["initial"].update(sceneAndSceneIndexBuilds=1, rasterBuilds=1)
                        case["heldWet"].update(observations=6, shapeAdvances=6, pathBuilds=6)
                        cases.append(case)
            self.write_json(directory / f"ink-fork-{fork}.json", {
                "schema": "vive-ink-phase2-ui/1", "runtime": "fixture runtime",
                "runtimeVendor": "fixture vendor", "os": "fixture OS", "nativeSha256": "a" * 64,
                "nativeOrigin": "BUNDLED", "fixture": "40k distinct meshes",
                "timingScope": "handler through next draw", "pageCountScope": "snapshot identities",
                "sceneCountScope": "scene and index builds", "pendingAdditionScope": "unacknowledged rows",
                "cases": cases,
            })
            self.write_json(directory / f"app-fork-{fork}.json", {
                "runtime": "fixture runtime", "vendor": "fixture vendor", "vm": "fixture VM",
                "maxHeapBytes": 2048, "vmArgs": ["fixture argument"],
                "runtimeArtifacts": [
                    {"name": "external.jar", "sha256": "same", "projectArtifact": False},
                    {"name": "project.jar", "sha256": f"changed-{factor}", "projectArtifact": True},
                ],
            })

    def mutate(self, directory, filename, change):
        path = directory / filename
        value = json.loads(path.read_text())
        change(value)
        self.write_json(path, value)

    def assert_refused_without_writes(self, message):
        before, after = self.snapshot(self.before), self.snapshot(self.after)
        with self.assertRaisesRegex(ValueError, message):
            with patch.object(sys, "argv", ["compare_ink.py", str(self.before), str(self.after)]):
                compare_ink.main()
        self.assertEqual(before, self.snapshot(self.before))
        self.assertEqual(after, self.snapshot(self.after))

    def test_pools_samples_keeps_every_commit_and_writes_only_after(self):
        before = self.snapshot(self.before)
        after_inputs = self.snapshot(self.after)
        with patch.object(sys, "argv", ["compare_ink.py", str(self.before), str(self.after)]):
            with redirect_stdout(io.StringIO()):
                compare_ink.main()
        self.assertEqual(before, self.snapshot(self.before))
        after = self.snapshot(self.after)
        self.assertEqual(set(after_inputs) | {"ink-comparison.json", "ink-comparison.md"}, set(after))
        self.assertTrue(all(after[name] == value for name, value in after_inputs.items()))
        report = json.loads(after["ink-comparison.json"])
        self.assertEqual(8, report["caseCount"])
        self.assertEqual(0, report["failedPreservationCheckCount"])
        case = report["cases"][0]
        self.assertAlmostEqual(35 / 1_000_000, case["before"]["pooledHandlerMedianMs"])
        self.assertEqual([20 / 1_000_000, 30 / 1_000_000, 55 / 1_000_000], case["before"]["forkHandlerMedianMs"])
        self.assertEqual([2, 2, 2], case["before"]["forkHandlerSampleCounts"])
        self.assertEqual([1_000_000, 2_000_000, 3_000_000], case["before"]["forkEraseCommitNanos"])
        self.assertEqual(2.0, case["before"]["medianEraseCommitMs"])
        self.assertEqual(100.0, case["handlerChangePercent"])

    def test_changed_case_fixture_is_rejected_in_the_third_fork(self):
        self.mutate(self.after, "ink-fork-3.json", lambda value: value["cases"][0].update(zoom=1.01))
        self.assert_refused_without_writes("workload")

    def test_changed_native_or_runtime_identity_is_rejected(self):
        for field in ("nativeSha256", "runtime", "timingScope"):
            with self.subTest(field=field):
                path = self.after / "ink-fork-2.json"
                original = path.read_text()
                self.mutate(self.after, path.name, lambda value: value.update({field: "different"}))
                self.assert_refused_without_writes("runtime/native/fixture/timing scopes")
                path.write_text(original)

    def test_changed_external_dependency_bytes_are_rejected_but_project_bytes_can_change(self):
        report, _ = compare_ink.compare(self.before, self.after)
        self.assertTrue(report["appRuntimeAndExternalArtifactsCheckedAcrossAllForks"])
        self.mutate(self.after, "app-fork-3.json", lambda value: value["runtimeArtifacts"][0].update(sha256="changed"))
        self.assert_refused_without_writes("external runtime artifact")

    def test_missing_or_extra_fork_and_missing_completion_summary_are_rejected(self):
        target = self.after / "ink-fork-2.json"
        contents = target.read_bytes()
        target.unlink()
        self.assert_refused_without_writes("exactly three ink fork files")
        target.write_bytes(contents)
        extra = self.after / "ink-fork-4.json"
        extra.write_bytes(contents)
        self.assert_refused_without_writes("exactly three ink fork files")
        extra.unlink()
        (self.after / "summary.json").unlink()
        self.assert_refused_without_writes("Missing completed capture file")

    def test_duplicate_case_cannot_hide_a_missing_workload(self):
        self.mutate(self.after, "ink-fork-1.json", lambda value: value["cases"].__setitem__(-1, copy.deepcopy(value["cases"][0])))
        self.assert_refused_without_writes("duplicate case")

    def test_held_regression_and_additional_preview_work_remain_visible_with_faster_times(self):
        self.mutate(self.after, "ink-fork-2.json", lambda value: value["cases"][0]["heldWet"].update(rasterBuilds=1))
        self.mutate(self.after, "ink-fork-2.json", lambda value: value["cases"][0]["total"].update(rasterBuilds=1))
        self.mutate(self.after, "ink-fork-1.json", lambda value: value["cases"][0]["erasePreview"].update(
            finishedStrokeVectorDraws=100, rasterBuilds=1))
        # Synthetic timings improve. Structural regressions must still be reported independently.
        for fork in compare_ink.FORKS:
            self.mutate(self.after, f"ink-fork-{fork}.json", lambda value: [case.update(inputHandlerToDrawNanos=[1, 2])
                                                                          for case in value["cases"]])
        before, after = self.snapshot(self.before), self.snapshot(self.after)
        report, markdown = compare_ink.compare(self.before, self.after)
        self.assertEqual(before, self.snapshot(self.before))
        self.assertEqual(after, self.snapshot(self.after), "pure comparison must write nothing")
        self.assertEqual(1, report["failedPreservationCheckCount"])
        case = report["cases"][0]
        self.assertLess(case["handlerChangePercent"], 0)
        deviations = {(change["fork"], change["counter"]): change for change in case["structuralDeviations"]}
        self.assertEqual(1, deviations[(2, "heldWet.rasterBuilds")]["after"])
        self.assertEqual(100, deviations[(1, "erasePreview.finishedStrokeVectorDraws")]["delta"])
        self.assertEqual(0, case["beforeStructuralByFork"][1]["heldWet.rasterBuilds"])
        self.assertEqual(1, case["afterStructuralByFork"][1]["heldWet.rasterBuilds"])
        self.assertIn("erasePreview.finishedStrokeVectorDraws", markdown)
        self.assertIn("Failed preserved-work checks: 1", markdown)

    def test_baseline_cannot_be_used_as_the_output_directory(self):
        before = self.snapshot(self.before)
        with patch.object(sys, "argv", ["compare_ink.py", str(self.before), str(self.before)]):
            with self.assertRaisesRegex(ValueError, "separate from the baseline"):
                compare_ink.main()
        self.assertEqual(before, self.snapshot(self.before))


if __name__ == "__main__":
    unittest.main()
