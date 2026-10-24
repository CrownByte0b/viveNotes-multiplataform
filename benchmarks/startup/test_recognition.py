import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("recognition", Path(__file__).with_name("recognition.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def report(mode):
    return dict(schema="vive-startup-recognition/1", mode=mode,
        stagesNs={"construct": 100, "close": 10, **({"firstOcr": 200, "warmOcr0": 20,
            "warmOcr1": 20, "warmOcr2": 20} if mode == "ocr" else {})},
        fixtureSha256="a" * 64 if mode == "ocr" else None,
        readingSha256="b" * 64 if mode == "ocr" else None,
        confidenceBits=123 if mode == "ocr" else None, java="27", vm="VM", os="OS", arch="x64", processors=4,
        collectors=["G1 Young Generation", "G1 Concurrent GC"],
        events=["opened Text session", "closed Text session", "opened native CTC reduction session",
                "closed native CTC reduction session"] if mode == "ocr" else [])


def rows():
    return [dict(fork=1, side=s, mode=m, report=report(m), onnxInitialized=s == "before" or m == "ocr")
            for m in ("unused", "ocr") for s in ("before", "after")]


class RecognitionCaptureTest(unittest.TestCase):
    def test_alternates_fresh_pairs_without_discarding_a_fork(self):
        values = capture.schedule(3, ["before", "after"])
        self.assertEqual(12, len(values))
        self.assertEqual([(1, "unused", "before"), (1, "unused", "after")], values[:2])
        self.assertEqual([(2, "unused", "after"), (2, "unused", "before")], values[4:6])
        with self.assertRaises(ValueError): capture.schedule(0, ["before", "after"])

    def test_only_actual_initialization_records_count(self):
        log = "[0.10s][info][class,init] 1 Initializing 'ai/onnxruntime/OnnxRuntime' (0x0)\n" \
              "[0.11s][info][class,init] Start class verification for: ai.onnxruntime.OrtEnvironment\n"
        self.assertEqual(["ai/onnxruntime/OnnxRuntime"], capture.initialization(log))

    def test_validates_real_lifecycle_and_rejects_missing_initialization_evidence(self):
        classes = ["com/vivenotes/ai/OnnxInkRecognitionEngine", "ai/onnxruntime/OnnxRuntime"]
        capture.validate(report("ocr"), "ocr", classes)
        with self.assertRaises(ValueError): capture.validate(report("ocr"), "ocr", [])
        broken = report("ocr")
        broken["events"].remove("closed Text session")
        with self.assertRaises(ValueError): capture.validate(broken, "ocr", classes)

    def test_rejects_incomplete_or_nonfinite_timing(self):
        for value in (0, -1, float("nan"), True):
            broken = report("unused")
            broken["stagesNs"]["construct"] = value
            with self.assertRaises(ValueError):
                capture.validate(broken, "unused", ["com/vivenotes/ai/OnnxInkRecognitionEngine"])

    def test_comparison_keeps_first_request_cost(self):
        values = rows()
        values[3]["report"]["stagesNs"].update(construct=10, firstOcr=290)
        comparison = capture.compare_rows(values)
        self.assertEqual(0, comparison["ocr"]["constructPlusFirstOcr"]["changePercent"])
        self.assertGreater(comparison["ocr"]["firstOcr"]["changePercent"], 0)

    def test_comparison_rejects_changed_fixture_output_and_runtime(self):
        for field in ("fixtureSha256", "readingSha256", "confidenceBits", "java", "collectors"):
            values = rows()
            values[3]["report"][field] = "changed"
            with self.assertRaises(ValueError): capture.compare_rows(values)

    def test_comparison_rejects_missing_pairs_and_unexpected_native_initialization(self):
        values = rows()
        with self.assertRaises(ValueError): capture.compare_rows(values[:-1])
        values[1]["onnxInitialized"] = True
        with self.assertRaises(ValueError): capture.compare_rows(values)

    def test_unused_engine_must_not_have_opened_a_session(self):
        value = report("unused")
        value["events"] = ["opened Text session"]
        with self.assertRaises(ValueError):
            capture.validate(value, "unused", ["com/vivenotes/ai/OnnxInkRecognitionEngine"])


if __name__ == "__main__":
    unittest.main()
