import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("native_analysis", Path(__file__).with_name("analyze.py"))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


def fixture():
    events = []
    for method, start, duration in zip(analysis.METHODS, (30, 10, 20), (10, 50, 30)):
        owner, name = method.split("::")
        events.append({"type": "jdk.MethodTrace", "values": {
            "startTime": f"2026-10-23T00:00:00.{start:09}Z", "duration": f"PT0.{duration:09}S",
            "eventThread": {"javaThreadId": 8}, "method": {"type": {"name": owner.replace('.', '/')}, "name": name},
            "stackTrace": {"frames": [{"method": {"name": "caller_is_not_the_timed_method"}}]}}})
    ledger = {"schema": "vive-native-wayland-method-observation/1", "closed": True, "errors": [],
              "dataLossEvents": 0, "edtThreadId": 8, "components": ["ComposeSkiaSubclass"],
              "methodCounts": dict.fromkeys(analysis.METHODS, 1)}
    return {"recording": {"events": events}}, ledger


class NativeAnalysisTest(unittest.TestCase):
    def test_nested_scopes_exact_nanos_and_method_field(self):
        document, ledger = fixture()
        result = analysis.analyze(document, ledger, [])
        self.assertEqual(1, result["nestedIntervals"])
        self.assertEqual([10, 50, 30], [result["methods"][m]["all"]["sumNanos"] for m in analysis.METHODS])

    def test_rejects_incomplete_or_failed_ledger(self):
        for key, value in (("closed", False), ("errors", ["other window"]), ("dataLossEvents", 1),
                           ("edtThreadId", 0), ("components", []), ("schema", "legacy")):
            with self.subTest(key=key):
                document, ledger = fixture()
                ledger[key] = value
                with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_data_loss_and_unknown_events(self):
        for name in ("jdk.DataLoss", "jdk.Unexpected"):
            document, ledger = fixture()
            document["recording"]["events"].append({"type": name, "values": {}})
            with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_count_mismatch_even_with_valid_intervals(self):
        document, ledger = fixture()
        ledger["methodCounts"][analysis.METHODS[0]] = 2
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_foreign_thread(self):
        document, ledger = fixture()
        document["recording"]["events"][0]["values"]["eventThread"]["javaThreadId"] = 9
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_missing_or_duplicate_intervals_even_with_matching_ledger(self):
        for duplicate in (True, False):
            document, ledger = fixture()
            if duplicate:
                document["recording"]["events"] *= 2
                ledger["methodCounts"] = dict.fromkeys(analysis.METHODS, 2)
            else:
                document["recording"]["events"].pop()
                ledger["methodCounts"][analysis.METHODS[2]] = 0
            with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_crossed_boundaries(self):
        document, ledger = fixture()
        document["recording"]["events"][0]["values"]["duration"] = "PT0.000000060S"
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_rejects_negative_duration(self):
        document, ledger = fixture()
        document["recording"]["events"][0]["values"]["duration"] = "-PT0.1S"
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])

    def test_phase_uses_method_entry_and_half_open_boundary(self):
        document, ledger = fixture()
        document["recording"]["events"].append({"type": analysis.common.PHASE_EVENT, "values": {
            "startTime": "2026-10-23T00:00:00.000000020Z", "duration": "PT0.000000010S",
            "phase": "work", "outcome": "complete"}})
        result = analysis.analyze(document, ledger, ["work"])
        self.assertEqual(1, result["methods"][analysis.METHODS[2]]["phases"]["work"]["count"])
        self.assertEqual(0, result["methods"][analysis.METHODS[0]]["phases"]["work"]["count"])
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, [])
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, ["missing"])

    def test_batched_live_count_cannot_substitute_for_robot_phase_render(self):
        document, ledger = fixture()
        document["recording"]["events"].append({"type": analysis.common.PHASE_EVENT, "values": {
            "startTime": "2026-10-23T00:00:00.000000020Z", "duration": "PT0.000000010S",
            "phase": "robot_work", "outcome": "complete"}})
        with self.assertRaises(ValueError): analysis.analyze(document, ledger, ["robot_work"])


if __name__ == "__main__":
    unittest.main()
