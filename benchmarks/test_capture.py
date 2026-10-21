import unittest
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile

from capture import aggregate, compare


def report(value=1000000, fixture="same", name="example"):
    return {"cases": [{"name": name, "units": 10, "fixtureSha256": fixture,
                       "medianNanos": value, "p95BatchAverageNanos": value * 2,
                       "medianAllocatedBytes": 128}]}


class CaptureTest(unittest.TestCase):
    def test_aggregates_fork_medians_and_keeps_samples_separate(self):
        value = aggregate([report(1000000), report(3000000), report(2000000)])[0]
        self.assertEqual(2.0, value["medianMs"])
        self.assertEqual([1.0, 3.0, 2.0], value["forkMedianMs"])
        self.assertEqual([2.0, 6.0, 4.0], value["forkP95BatchAverageMs"])
        self.assertEqual(128, value["medianAllocatedBytes"])

    def test_refuses_different_workloads_or_fixtures(self):
        with self.assertRaises(ValueError):
            aggregate([])
        with self.assertRaises(ValueError):
            aggregate([report(), report(name="different")])
        with self.assertRaises(ValueError):
            aggregate([report(), report(fixture="different")])
        duplicate = report()
        duplicate["cases"] *= 2
        with self.assertRaises(ValueError):
            aggregate([duplicate])

    def test_comparison_allows_changed_app_and_rejects_changed_dependencies(self):
        with tempfile.TemporaryDirectory() as temporary:
            before, after = Path(temporary) / "before", Path(temporary) / "after"
            for directory, duration, app_hash in ((before, 1000000, "original"), (after, 500000, "optimized")):
                directory.mkdir()
                (directory / "environment.json").write_text(json.dumps({
                    "cpu": "fixture", "platform": "fixture", "affinity": [0], "harnessFiles": {"capture.py": "same"},
                }))
                (directory / "summary.json").write_text(json.dumps({"cases": aggregate([report(duration)])}))
                (directory / "app-fork-1.json").write_text(json.dumps({
                    "runtime": "fixture", "vendor": "fixture", "vm": "fixture", "maxHeapBytes": 1, "vmArgs": [],
                    "runtimeArtifacts": [{"name": "app.jar", "sha256": app_hash, "projectArtifact": True},
                                         {"name": "library.jar", "sha256": "same", "projectArtifact": False}],
                }))
            output = io.StringIO()
            with redirect_stdout(output):
                compare(before, after)
            self.assertIn("-50.0%", output.getvalue())
            runtime = json.loads((after / "app-fork-1.json").read_text())
            runtime["runtimeArtifacts"][1]["sha256"] = "changed"
            (after / "app-fork-1.json").write_text(json.dumps(runtime))
            with self.assertRaisesRegex(ValueError, "External runtime dependencies"):
                compare(before, after)


if __name__ == "__main__":
    unittest.main()
