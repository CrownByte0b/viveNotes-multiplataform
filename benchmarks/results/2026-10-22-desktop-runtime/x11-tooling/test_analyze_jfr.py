"""Pure synthetic JFR contracts; these tests never launch a JVM or native tool."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("runtime_jfr_analysis", Path(__file__).with_name("analyze_jfr.py"))
analysis = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analysis)


def time_at(nanos):
    return f"2026-10-21T00:00:00.{nanos:09d}Z"


def event(kind, at, **values):
    return {"type": kind, "values": {"startTime": time_at(at), **values}}


def phase(name, at, duration):
    return event(analysis.PHASE_EVENT, at, duration=f"PT0.{duration:09d}S", phase=name, outcome="complete")


def frame(sequence, at, duration, phase_name="ink", returned=True, inputs=0):
    return event(analysis.FRAME_EVENT, at, duration=f"PT0.{duration:09d}S", phase=phase_name,
                 frameSequence=sequence, returnedNormally=returned, callbackElapsedNanos=duration + 2,
                 inputObservations=inputs)


def stack_frame(owner, name="method", descriptor="()V"):
    return {"method": {"type": {"name": owner}, "name": name, "descriptor": descriptor}}


def sample(at, thread=1, native=False, frames=None, truncated=False):
    return event(analysis.SAMPLE_EVENTS[int(native)], at,
                 sampledThread={"javaThreadId": thread, "osThreadId": 900 + thread, "javaName": "same-name"},
                 stackTrace=None if frames is None else {"frames": frames, "truncated": truncated})


def rendering(frames, inputs):
    by_phase = {}
    for item in frames:
        value = item["values"]
        row = by_phase.setdefault(value["phase"], {"name": value["phase"], "completedCallbacks": 0,
                                                 "inputsFollowedByCallback": 0})
        row["completedCallbacks"] += int(value["returnedNormally"])
    for item in inputs:
        value = item["values"]
        row = by_phase.setdefault(value["phase"], {"name": value["phase"], "completedCallbacks": 0,
                                                 "inputsFollowedByCallback": 0})
        row["inputsFollowedByCallback"] += 1
    return {"schema": "vive-runtime-render-observation/1", "observations": {
        "closed": True, "inFlightCallbacks": 0, "startedCallbacks": len(frames),
        "completedCallbacks": sum(int(item["values"]["returnedNormally"]) for item in frames),
        "failedCallbacks": sum(int(not item["values"]["returnedNormally"]) for item in frames),
        "inputObservations": len(inputs), "pendingInputs": 0, "droppedPendingInputs": 0,
        "unmatchedInputsAtClose": 0, "phases": list(by_phase.values())}}


def fixture():
    frames = [frame(1, 11, 5, inputs=1), frame(2, 19, 4), frame(3, 31, 8, "idle")]
    inputs = [event(analysis.INPUT_EVENT, 17, phase="startup_observation", inputSequence=1, frameSequence=1,
                    dispatchToCallbackStartNanos=4, dispatchToCallbackEndNanos=11)]
    samples = [sample(9, frames=[stack_frame("native/Worker")]),
               sample(10, frames=[stack_frame("java/lang/Thread"), stack_frame("com/vivenotes/Page")]),
               sample(19, thread=2, native=True, frames=[stack_frame("com/vivenotes/Ink"),
                      stack_frame("com/vivenotes/Ink"), stack_frame("com/vivenotes/Page")], truncated=True),
               sample(20), sample(30, frames=[stack_frame("com/vivenotes/Text")]), sample(40)]
    return {"recording": {"events": [phase("ink", 10, 10), phase("idle", 30, 10), *frames, *inputs, *samples]}}, rendering(frames, inputs)


class JfrParsingTest(unittest.TestCase):
    def test_timestamp_keeps_nanoseconds_offsets_and_pre_epoch_values(self):
        self.assertEqual(1, analysis.timestamp_nanos("1970-01-01T00:00:00.000000001Z"))
        self.assertEqual(-1, analysis.timestamp_nanos("1969-12-31T23:59:59.999999999Z"))
        self.assertEqual(1, analysis.timestamp_nanos("1970-01-01T01:30:00.000000001+01:30"))
        self.assertEqual(1_000_000_000, analysis.timestamp_nanos("1969-12-31T19:00:01-05:00"))
        self.assertEqual(100_000_000, analysis.timestamp_nanos("1970-01-01T00:00:00.1Z"))
        for value in (1, "2026-10-21T01:00:00", "2026-10-21T01:00:00.1234567891Z", "2026-10-21T01:00:00+25:00"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                analysis.timestamp_nanos(value)

    def test_duration_keeps_exact_integer_precision_and_rejects_invalid_inputs(self):
        self.assertEqual(0, analysis.duration_nanos("PT0S"))
        self.assertEqual(123_456_789, analysis.duration_nanos("PT0.123456789S"))
        self.assertEqual(93_784_500_000_000, analysis.duration_nanos("P1DT2H3M4.5S"))
        self.assertEqual(86_400_000_000_000, analysis.duration_nanos("P1D"))
        for value in (5, "P", "PT", "P1DT", "-PT1S", "PT0.0000000001S", "PTNaNS"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                analysis.duration_nanos(value)

    def test_nearest_rank_is_explicit_for_even_small_empty_and_large_counts(self):
        self.assertEqual(2, analysis.nearest_rank([4, 2, 1, 3], 50))
        self.assertEqual(4, analysis.nearest_rank([4, 2, 1, 3], 95))
        self.assertEqual(19, analysis.nearest_rank(list(range(1, 21)), 95))
        self.assertEqual(1, analysis.nearest_rank([1], 95))
        self.assertIsNone(analysis.nearest_rank([], 95))
        self.assertEqual({"count": 0, "sumNanos": 0, "minNanos": None, "p50Nanos": None, "p95Nanos": None,
                          "maxNanos": None}, analysis.distribution([]))
        for value in (0, 101, True, .95):
            with self.assertRaises(ValueError):
                analysis.nearest_rank([1], value)

    def test_phase_boundaries_are_half_open_with_explicit_unassigned_intervals(self):
        data, _ = fixture()
        phases = analysis.phase_intervals(analysis.parse_events(data), ("ink", "idle"))
        expected = {9: "unassigned_startup", 10: "ink", 19: "ink", 20: "unassigned_interphase",
                    29: "unassigned_interphase", 30: "idle", 39: "idle", 40: "unassigned_after_phases"}
        for at, label in expected.items():
            self.assertEqual(label, analysis.interval_name(analysis.timestamp_nanos(time_at(at)), phases))

    def test_phase_validation_rejects_missing_duplicates_overlap_order_and_failure(self):
        candidates = [[phase("ink", 10, 5)], [phase("ink", 10, 5), phase("ink", 20, 5)],
                      [phase("ink", 10, 30), phase("idle", 30, 10)],
                      [phase("idle", 10, 5), phase("ink", 20, 5)]]
        failed = phase("ink", 10, 5)
        failed["values"]["outcome"] = "failed"
        candidates.append([failed, phase("idle", 30, 5)])
        for events in candidates:
            with self.subTest(events=events), self.assertRaises(ValueError):
                analysis.phase_intervals(events, ("ink", "idle"))

    def test_unknown_event_or_unparseable_timestamp_never_silently_disappears(self):
        for events in ([event("jdk.OtherEvent", 0)], [{"type": analysis.SAMPLE_EVENTS[0], "values": {}}]):
            with self.assertRaises(ValueError):
                analysis.parse_events({"recording": {"events": events}})
        with self.assertRaises(ValueError):
            analysis.parse_events({"events": []})


class JfrObservationTest(unittest.TestCase):
    def test_frame_durations_and_crossings_come_from_jfr_not_wrapper_or_phase_labels(self):
        data, observer = fixture()
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        all_frames = result["framesAndInputs"]["all"]["completedCallbacks"]
        self.assertEqual({"count": 3, "sumNanos": 17, "minNanos": 4, "p50Nanos": 5, "p95Nanos": 8,
                          "maxNanos": 8}, all_frames)
        self.assertEqual(1, result["framesAndInputs"]["ink"]["boundaryCrossingCallbacks"])
        self.assertEqual(1, result["framesAndInputs"]["idle"]["completedCallbacks"]["count"])
        self.assertTrue(result["countParity"]["observerCountParity"])

    def test_input_emission_interval_and_original_dispatch_phase_are_both_retained(self):
        data, observer = fixture()
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        group = result["framesAndInputs"]["ink"]
        self.assertEqual(4, group["inputToCallbackStart"]["p50Nanos"])
        self.assertEqual(11, group["inputToCallbackEnd"]["p50Nanos"])
        self.assertEqual({"startup_observation": 1}, group["originalInputDispatchPhaseLabels"])
        self.assertIn("not causal", result["inputScope"])

    def test_samples_count_unique_application_frames_and_distinct_threads_without_cpu_conversion(self):
        data, observer = fixture()
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        sampled = result["sampling"]["all"]
        self.assertEqual(6, sampled["samples"])
        self.assertEqual(2, sampled["missingStackSamples"])
        self.assertEqual(1, sampled["truncatedStackSamples"])
        self.assertEqual({"jdk.ExecutionSample": 5, "jdk.NativeMethodSample": 1}, sampled["eventTypes"])
        inclusive = {row["frame"]: row["sampleHits"] for row in sampled["applicationInclusiveHits"]}
        self.assertEqual(1, inclusive["com.vivenotes.Ink.method()V"])
        self.assertEqual(2, inclusive["com.vivenotes.Page.method()V"])
        self.assertEqual(2, len(sampled["threads"]))
        self.assertEqual(1, result["sampling"]["unassigned_startup"]["samples"])
        self.assertEqual(1, result["sampling"]["unassigned_interphase"]["samples"])
        self.assertEqual(1, result["sampling"]["unassigned_after_phases"]["samples"])
        self.assertIn("not CPU time", result["samplingScope"])

    def test_probe_forwarding_ancestors_do_not_reclassify_framework_leaf_work(self):
        data, observer = fixture()
        data["recording"]["events"].append(sample(12, frames=[
            stack_frame("androidx/compose/ui/node/LayoutNode", "draw"),
            stack_frame("com/vivenotes/runtimeprobe/RuntimeRenderProbe", "wrap"),
            stack_frame("com/vivenotes/runtimeprobe/DesktopRuntimeProbe", "main")]))
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        sampled = result["sampling"]["ink"]
        leaf = {row["frame"]: row["sampleHits"] for row in sampled["leafHits"]}
        inclusive = {row["frame"]: row["sampleHits"] for row in sampled["applicationInclusiveHits"]}
        self.assertEqual(1, leaf["androidx.compose.ui.node.LayoutNode.draw()V"])
        self.assertNotIn("com.vivenotes.runtimeprobe.RuntimeRenderProbe.wrap()V", leaf)
        self.assertEqual(1, inclusive["com.vivenotes.runtimeprobe.RuntimeRenderProbe.wrap()V"])
        self.assertEqual(1, inclusive["com.vivenotes.runtimeprobe.DesktopRuntimeProbe.main()V"])
        self.assertIn("no observer-overhead classification", result["samplingScope"])
        self.assertNotIn("observerOverhead", sampled)

    def test_failed_callback_counts_are_retained_but_not_mixed_into_success_quantiles(self):
        data, observer = fixture()
        failed = frame(4, 33, 100, "idle", returned=False)
        data["recording"]["events"].append(failed)
        frames = [item for item in data["recording"]["events"] if item["type"] == analysis.FRAME_EVENT]
        inputs = [item for item in data["recording"]["events"] if item["type"] == analysis.INPUT_EVENT]
        observer = rendering(frames, inputs)
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        self.assertEqual(1, result["framesAndInputs"]["idle"]["failedCallbacks"])
        self.assertEqual(8, result["framesAndInputs"]["idle"]["completedCallbacks"]["maxNanos"])

    def test_jfr_observer_parity_detects_missing_duplicate_and_conflicting_counts(self):
        data, observer = fixture()
        mutations = [lambda events: events.pop(3), lambda events: events.append(copy.deepcopy(events[2])),
                     lambda events: events[2]["values"].update(frameSequence=7),
                     lambda events: events[2]["values"].update(inputObservations=0),
                     lambda events: events[5]["values"].update(dispatchToCallbackEndNanos=12)]
        for mutate in mutations:
            changed = copy.deepcopy(data)
            mutate(changed["recording"]["events"])
            with self.assertRaises(ValueError):
                analysis.analyze_document(changed, observer, ("ink", "idle"))

    def test_unmatched_and_dropped_inputs_remain_visible_and_require_complete_accounting(self):
        data, observer = fixture()
        observed = observer["observations"]
        observed.update(inputObservations=4, droppedPendingInputs=1, unmatchedInputsAtClose=2)
        result = analysis.analyze_document(data, observer, ("ink", "idle"))
        self.assertEqual(2, result["observer"]["observations"]["unmatchedInputsAtClose"])
        observed["inputObservations"] = 5
        with self.assertRaises(ValueError):
            analysis.analyze_document(data, observer, ("ink", "idle"))


class JfrCaptureIntegrityTest(unittest.TestCase):
    def fixture_capture(self, directory):
        capture = directory / "capture"
        capture.mkdir()
        project = directory / "project"
        project.mkdir()
        bin_dir = directory / "jdk" / "bin"
        bin_dir.mkdir(parents=True)
        java, jfr = bin_dir / "java", bin_dir / "jfr"
        for executable in (java, jfr):
            executable.write_bytes(b"fake-executable-not-launched")
            executable.chmod(0o700)
        (capture / "runtime.jfr").write_bytes(b"fixed-jfr-fixture")
        data, observer = fixture()
        (capture / "isolated-profile").mkdir()
        analysis.write_json(capture / "isolated-profile" / "rendering.json", observer)
        manifest = {"schema": "vive-desktop-runtime-capture/1", "complete": True, "contractOnly": False,
                    "provenance": {"projectRoot": str(project), "executables": [
                        {"role": "java", "path": str(java), "sha256": analysis.sha256(java)},
                        {"role": "jfr", "path": str(jfr), "sha256": analysis.sha256(jfr)}]}}
        analysis.write_json(capture / "environment.json", manifest)
        return capture, jfr, manifest, data

    def run_fake_extract(self, document):
        def extract(command, **kwargs):
            self.assertEqual("print", command[1])
            self.assertIn("--stack-depth", command)
            self.assertEqual(",".join(analysis.EVENTS), command[command.index("--events") + 1])
            kwargs["stdout"].write(json.dumps(document).encode())
            return argparse.Namespace(returncode=0)
        return extract

    def test_analysis_uses_complete_capture_validator_preserves_all_raw_and_hashes_outputs(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            capture, jfr, manifest, data = self.fixture_capture(root)
            raw_before = {p.relative_to(capture).as_posix(): p.read_bytes() for p in capture.rglob("*") if p.is_file()}
            with patch.object(analysis.runtime, "validate_complete_capture", return_value=manifest) as validate, \
                    patch.object(analysis.runtime, "PHASES", ("ink", "idle")), \
                    patch.object(analysis.subprocess, "run", side_effect=self.run_fake_extract(data)) as run:
                output = analysis.analyze_capture(capture, jfr, root / "analysis")
            self.assertEqual(2, validate.call_count)
            self.assertEqual(1, run.call_count)
            saved = analysis.read_json(output / "environment.json")
            self.assertTrue(saved["complete"])
            self.assertEqual(analysis.sha256(capture / "runtime.jfr"), saved["recordingSha256"])
            for item in saved["rawFiles"]:
                self.assertEqual(item["sha256"], analysis.sha256(output / item["path"]))
            self.assertEqual(raw_before, {p.relative_to(capture).as_posix(): p.read_bytes() for p in capture.rglob("*") if p.is_file()})
            self.assertIn("not CPU time", (output / "analysis.md").read_text())

    def test_invalid_or_contract_only_capture_never_launches_jfr_or_creates_output(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            capture, jfr, manifest, _ = self.fixture_capture(root)
            with patch.object(analysis.runtime, "validate_complete_capture", side_effect=ValueError("tampered raw evidence")), \
                    patch.object(analysis.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "tampered"):
                    analysis.analyze_capture(capture, jfr, root / "bad")
                run.assert_not_called()
            self.assertFalse((root / "bad").exists())
            manifest["contractOnly"] = True
            with patch.object(analysis.runtime, "validate_complete_capture", return_value=manifest), \
                    patch.object(analysis.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "headless"):
                    analysis.analyze_capture(capture, jfr, root / "contract")
                run.assert_not_called()

    def test_wrong_jdk_changed_executable_or_existing_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            capture, jfr, manifest, _ = self.fixture_capture(root)
            other = root / "other-jfr"
            other.write_bytes(jfr.read_bytes())
            other.chmod(0o700)
            with self.assertRaisesRegex(ValueError, "exact captured"):
                analysis.verify_jfr_executable(manifest, other)
            jfr.write_bytes(b"changed executable")
            with self.assertRaisesRegex(ValueError, "bytes changed"):
                analysis.verify_jfr_executable(manifest, jfr)
            jfr.write_bytes(b"fake-executable-not-launched")
            with patch.object(analysis.runtime, "validate_complete_capture", return_value=manifest), \
                    patch.object(analysis.subprocess, "run") as run:
                with self.assertRaises(ValueError):
                    analysis.analyze_capture(capture, jfr, root)
                with self.assertRaises(ValueError):
                    analysis.analyze_capture(capture, jfr, capture / "nested")
                run.assert_not_called()

    def test_capture_mutation_after_extraction_is_rejected_with_private_failure_evidence(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            capture, jfr, manifest, data = self.fixture_capture(root)
            write = self.run_fake_extract(data)
            def mutate(command, **kwargs):
                result = write(command, **kwargs)
                (capture / "runtime.jfr").write_bytes(b"injected concurrent change")
                return result
            with patch.object(analysis.runtime, "validate_complete_capture", return_value=manifest), \
                    patch.object(analysis.subprocess, "run", side_effect=mutate):
                with self.assertRaisesRegex(ValueError, "changed during"):
                    analysis.analyze_capture(capture, jfr, root / "failed-analysis")
            saved = analysis.read_json(root / "failed-analysis" / "environment.json")
            self.assertFalse(saved["complete"])
            self.assertTrue((root / "failed-analysis" / "jfr-events.json").is_file())
            self.assertFalse((root / "failed-analysis" / "analysis.json").exists())

    def test_failed_jfr_exit_retains_stdout_stderr_and_never_reports_complete(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            capture, jfr, manifest, _ = self.fixture_capture(root)
            def fail(command, **kwargs):
                kwargs["stdout"].write(b"incomplete json")
                kwargs["stderr"].write(b"tool error")
                return argparse.Namespace(returncode=7)
            with patch.object(analysis.runtime, "validate_complete_capture", return_value=manifest), \
                    patch.object(analysis.subprocess, "run", side_effect=fail):
                with self.assertRaisesRegex(ValueError, "exit 7"):
                    analysis.analyze_capture(capture, jfr, root / "failed-extract")
            self.assertEqual(b"tool error", (root / "failed-extract" / "jfr-print-stderr.txt").read_bytes())
            self.assertFalse(analysis.read_json(root / "failed-extract" / "environment.json")["complete"])


if __name__ == "__main__":
    unittest.main()
