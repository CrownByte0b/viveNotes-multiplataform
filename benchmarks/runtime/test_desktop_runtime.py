"""Fast launcher contracts; actual SQLite/canonical fixture contract uses --contract-only."""
import argparse
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import struct
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("desktop_runtime", Path(__file__).with_name("desktop_runtime.py"))
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeLauncherTest(unittest.TestCase):
    def test_exported_source_inventory_checks_all_source_bytes_without_git(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "shared/src/commonMain/kotlin/Example.kt"
            source.parent.mkdir(parents=True)
            source.write_text("class Example")
            inventory = root / "inventory.json"
            commit = "1234567" + "a" * 33
            inventory.write_text(json.dumps({"schema": "vive-runtime-source-inventory/1",
                "sourceCommit": commit, "sourceFiles": runtime.project_snapshot(root)}))
            with patch.object(runtime, "git", side_effect=AssertionError("No Git on exported host")):
                identity = runtime.source_identity(root, "1234567", inventory)
            self.assertEqual(commit, identity["sourceCommit"])
            self.assertIsNone(identity["workingTree"])
            self.assertIn("no Git checkout", identity["sourceIdentity"])
            self.assertEqual(runtime.sha256(inventory), identity["sourceInventorySha256"])
            with self.assertRaises(ValueError):
                runtime.source_identity(root, "7654321", inventory)
            source.write_text("class Changed")
            with self.assertRaises(ValueError):
                runtime.source_identity(root, "1234567", inventory)

    def test_exported_source_inventory_rejects_extra_missing_and_invalid_sources(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "desktopApp/src/main/kotlin/Main.kt"
            source.parent.mkdir(parents=True)
            source.write_text("fun main() {}")
            inventory = root / "inventory.json"
            content = {"schema": "vive-runtime-source-inventory/1", "sourceCommit": "b" * 40,
                       "sourceFiles": runtime.project_snapshot(root)}
            inventory.write_text(json.dumps(content))
            extra = source.with_name("Extra.kt")
            extra.write_text("class Extra")
            with self.assertRaises(ValueError):
                runtime.source_identity(root, source_manifest=inventory)
            extra.unlink()
            source.unlink()
            with self.assertRaises(ValueError):
                runtime.source_identity(root, source_manifest=inventory)
            content.update(sourceCommit="unverified", sourceFiles=[])
            inventory.write_text(json.dumps(content))
            with self.assertRaises(ValueError):
                runtime.source_identity(root, source_manifest=inventory)

    def test_output_must_be_new_and_outside_repository_even_through_symlink(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            repository = root / "repository"
            repository.mkdir()
            with self.assertRaises(ValueError):
                runtime.check_new_output(repository / "private-jfr", repository)
            with self.assertRaises(ValueError):
                runtime.check_new_output(root, repository)
            self.assertEqual(root / "new", runtime.check_new_output(root / "new", repository))
            link = root / "alias"
            try:
                link.symlink_to(repository, target_is_directory=True)
            except OSError:
                self.skipTest("Symlink creation unavailable on this host")
            with self.assertRaises(ValueError):
                runtime.check_new_output(link / "private-jfr", repository)
            dangling = root / "dangling"
            dangling.symlink_to(root / "missing")
            with self.assertRaises(ValueError):
                runtime.check_new_output(dangling, repository)

    def test_classpath_supports_json_and_platform_separators_and_rejects_missing_entries(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            for name in ("one.jar", "two.jar"):
                (root / name).write_bytes(name.encode())
            path = root / "cp.txt"
            expected = [str(root / name) for name in ("one.jar", "two.jar")]
            path.write_text(json.dumps(["one.jar", "two.jar"]))
            self.assertEqual(expected, runtime.read_classpath(path))
            path.write_text(os.pathsep.join(expected))
            self.assertEqual(expected, runtime.read_classpath(path))
            path.write_text("absent.jar")
            with self.assertRaises(ValueError):
                runtime.read_classpath(path)

    def test_precompiled_probe_requires_exact_source_stamp(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            classes = root / "compiled"
            classes.mkdir()
            args = argparse.Namespace(probe_classes=classes)
            with self.assertRaises(ValueError):
                runtime.compile_probe(args, root, [], {})
            (classes / "probe-source.sha256").write_text("0" * 64)
            with self.assertRaises(ValueError):
                runtime.compile_probe(args, root, [], {})
            (classes / "probe-source.sha256").write_text(runtime.probe_stamp() + "\n")
            with self.assertRaises(ValueError):
                runtime.compile_probe(args, root, [], {})
            for name in ("DesktopRuntimeProbe", "ProbePhase", "RuntimeRenderProbe"):
                path = classes / f"com/vivenotes/runtimeprobe/{name}.class"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"\xca\xfe\xba\xbe\0\0\0\x45" + b"synthetic-class-fixture")
            owned = runtime.compile_probe(args, root, [], {})
            self.assertEqual(root / "probe-classes", owned)
            self.assertEqual(runtime.compiled_probe(classes), runtime.compiled_probe(owned))

    def test_events_ignore_only_an_incomplete_final_record(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "events.jsonl"
            path.write_text('{"phase":"ready"}\n{"phase":')
            self.assertEqual([{"phase": "ready"}], runtime.read_events(path))
            with self.assertRaises(ValueError):
                runtime.read_events(path, final=True)
            path.write_text('{"phase":"ready"}\nbroken\n')
            with self.assertRaises(json.JSONDecodeError):
                runtime.read_events(path)

    def test_proc_sampling_handles_spaces_parentheses_and_uses_process_cpu(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            process = root / "17"
            process.mkdir()
            (process / "status").write_text("VmRSS:\t123 kB\nVmHWM:\t150 kB\nVmSize:\t900 kB\nThreads:\t8\n")
            fields = ["S"] + ["0"] * 20
            fields[11], fields[12] = "100", "50"
            (process / "stat").write_text("17 (name (with) spaces) " + " ".join(fields))
            with patch.object(runtime.os, "sysconf", return_value=100, create=True):
                result = runtime.linux_process_sample(17, root)
            self.assertEqual(123 * 1024, result["rssBytes"])
            self.assertEqual(150 * 1024, result["highWaterRssBytes"])
            self.assertEqual(1.5, result["processCpuSeconds"])

    def test_artifacts_hash_directory_contents_and_identify_external_dependencies(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            classes = root / "project/classes"
            classes.mkdir(parents=True)
            file = classes / "Sample.class"
            file.write_bytes(b"first")
            before = runtime.artifact(str(classes), root / "project")
            file.write_bytes(b"second")
            after = runtime.artifact(str(classes), root / "project")
            self.assertNotEqual(before["sha256"], after["sha256"])
            self.assertTrue(before["projectArtifact"])
            dependency = root / "library.jar"
            dependency.write_bytes(b"external")
            self.assertFalse(runtime.artifact(str(dependency), root / "project")["projectArtifact"])

    def events(self, nmt=False):
        rows = []
        def event(name, state="instant", **extra):
            rows.append({"schema": runtime.PROBE_SCHEMA, "phase": name, "state": state,
                         "nanoTime": len(rows) * 10, "processCpuNanos": len(rows), **extra})
        event("main_enter")
        event("frame_visible")
        event("workspace_ready", evidence={"selectedPage": "runtime-probe-ink", "inkReady": True, "completedFrames": 1})
        proofs = {"robot_ink": {"injectedStrokes": 8, "penSelected": True},
            "autosave_settle": {"persistedRobotStrokes": 8},
            "robot_viewport": {"scrollBefore": 0, "scrollDown": 20, "scrollRestored": 0,
                "zoomBefore": 1, "zoomChanged": 1.2, "zoomRestored": 1},
            "robot_lasso": {"lassoSelectionObserved": True, "selectionCleared": True},
            "robot_text_page": {"selectedPage": "runtime-probe-text", "editorTokenObserved": True},
            "text_autosave_settle": {"persistedTextToken": True},
            "robot_return_ink": {"selectedPage": "runtime-probe-ink", "inkReady": True}}
        for phase in runtime.PHASES:
            if nmt and phase in ("idle_initial", "idle_final"):
                suffix = "initial" if phase == "idle_initial" else "final"
                event(f"nmt_{suffix}_request")
                event(f"nmt_{suffix}_complete", evidence={"status": "captured"})
            event(phase, "begin")
            event(phase, "end", elapsedNanos=5,
                  evidence={"completedFramesBefore": 1, "completedFramesAfter": 2, **proofs.get(phase, {})})
        for name in ("driver_complete", "window_close_request", "process_shutdown"):
            event(name)
        return rows

    def test_full_event_protocol_requires_every_phase_and_actual_interaction_evidence(self):
        import copy
        for nmt in (False, True):
            events = self.events(nmt)
            self.assertEqual(list(runtime.PHASES), list(runtime.validate_events(events, nmt, 8)))
            changes = [lambda rows: rows.pop(), lambda rows: rows.insert(3, dict(rows[2])),
                lambda rows: rows.reverse(),
                lambda rows: next(row for row in rows if row["phase"] == "robot_ink" and row["state"] == "end")["evidence"].update(injectedStrokes=7),
                lambda rows: next(row for row in rows if row["phase"] == "robot_lasso" and row["state"] == "end")["evidence"].update(lassoSelectionObserved=False),
                lambda rows: next(row for row in rows if row["phase"] == "robot_viewport" and row["state"] == "end")["evidence"].update(zoomChanged=1),
                lambda rows: next(row for row in rows if row["phase"] == "robot_text_page" and row["state"] == "end")["evidence"].update(completedFramesAfter=1)]
            for change in changes:
                changed = copy.deepcopy(events)
                change(changed)
                with self.subTest(nmt=nmt, change=change), self.assertRaises(ValueError):
                    runtime.validate_events(changed, nmt, 8)

    def test_samples_require_real_finite_monotone_cpu_and_rss_observations(self):
        rows = [{"rssBytes": 1, "highWaterRssBytes": 2, "processCpuSeconds": value, "elapsedSeconds": value + 1} for value in (0, 1)]
        runtime.validate_samples(rows)
        for values in ([], rows[:1], list(reversed(rows)), [{**rows[0], "rssBytes": None}, rows[1]],
                       [{**rows[0], "processCpuSeconds": float("nan")}, rows[1]]):
            with self.assertRaises(ValueError):
                runtime.validate_samples(values)

    def test_exit_sample_missing_memory_remains_raw_and_does_not_invalidate_valid_samples(self):
        rows = [{"rssBytes": 123, "highWaterRssBytes": 150, "processCpuSeconds": 24.5, "elapsedSeconds": 18.9},
                {"rssBytes": 123, "highWaterRssBytes": 150, "processCpuSeconds": 24.68, "elapsedSeconds": 18.97},
                {"rssBytes": None, "highWaterRssBytes": None, "virtualBytes": None, "threads": 2,
                 "processCpuSeconds": 24.89, "elapsedSeconds": 19.22}]
        log = io.StringIO()
        valid = []
        availability = [runtime.record_process_sample(row, valid, log) for row in rows]
        self.assertEqual([True, True, False], availability)
        self.assertEqual(rows[:2], valid)
        runtime.validate_samples(valid)
        recorded = [json.loads(line) for line in log.getvalue().splitlines()]
        self.assertIsNone(recorded[-1]["rssBytes"])
        self.assertEqual(24.89, recorded[-1]["processCpuSeconds"])
        self.assertEqual("Invalid process sampler observation", recorded[-1]["unavailable"])
        self.assertNotIn("unavailable", rows[-1])
        with self.assertRaises(ValueError):
            runtime.validate_samples(rows)
        with self.assertRaises(ValueError):
            runtime.validate_samples(valid[:1])

    def test_jfr_requires_complete_chunks_and_parsed_complete_custom_phase_events(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            jfr = root / "runtime.jfr"
            data = bytearray(100)
            data[:8] = b"FLR\0\0\x02\0\x01"
            struct.pack_into(">qqq", data, 8, 100, 68, 69)
            jfr.write_bytes(data)
            runtime.validate_jfr(jfr)
            jfr.write_bytes(data[:-1])
            with self.assertRaises(ValueError):
                runtime.validate_jfr(jfr)
            parsed = root / "jfr.json"
            value = {"recording": {"events": [{"type": "com.vivenotes.RuntimeProbePhase",
                "values": {"phase": name, "outcome": "complete"}} for name in runtime.PHASES]}}
            runtime.write_json(parsed, value)
            runtime.read_jfr_phases(parsed)
            value["recording"]["events"][-1]["values"]["outcome"] = "failed"
            runtime.write_json(parsed, value)
            with self.assertRaises(ValueError):
                runtime.read_jfr_phases(parsed)

    def test_normalization_replaces_only_owned_roots_not_similarly_named_paths(self):
        self.assertEqual(["-Ddata=<profile>/home", "-Dother=/capture-other", "filename=<output>/runtime.jfr,settings=profile"],
            runtime.normalized(["-Ddata=/capture/isolated-profile/home", "-Dother=/capture-other", "filename=/capture/runtime.jfr,settings=profile"],
                PurePosixPath("/project"), PurePosixPath("/capture/isolated-profile"), PurePosixPath("/capture")))

    def test_windows_normalization_preserves_unowned_paths_and_option_boundaries(self):
        self.assertEqual([r"-Ddata=<profile>\home", r"-Dother=C:\capture-other",
                          r"filename=<output>\runtime.jfr,settings=profile"],
            runtime.normalized([r"-Ddata=C:\capture\isolated-profile\home", r"-Dother=C:\capture-other",
                                r"filename=C:\capture\runtime.jfr,settings=profile"],
                PureWindowsPath("C:/project"), PureWindowsPath("C:/capture/isolated-profile"), PureWindowsPath("C:/capture")))

    def test_source_java_and_binary_provenance_drift_is_rejected(self):
        with patch.object(runtime, "provenance", return_value={"sourceCommit": "changed"}):
            with self.assertRaisesRegex(ValueError, "provenance changed"):
                runtime.check_frozen(None, [], {"sourceCommit": "original"})

    def test_failure_retains_incomplete_manifest_and_partial_evidence(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            java = root / "java"
            java.write_bytes(b"synthetic executable")
            cp = root / "classpath.json"
            cp.write_text(json.dumps([str(java)]))
            args = argparse.Namespace(output=root / "failed", project_root=root / "project", java=java, jcmd=None,
                kotlinc=None, jfr_tool=None, classpath_file=cp, compiler_classpath_file=None, contract_only=True,
                owned_display=False, backend="x11", display=None, strokes=8, sample_seconds=.25, timeout_seconds=10,
                jvm_arg=[], heap="2g", label="baseline", expected_commit=None)
            def fail(args, output, *unused):
                (output / "partial-log.txt").write_text("retained")
                raise ValueError("deliberate failure")
            with patch.object(runtime, "provenance", return_value={"sourceCommit": "baseline"}), \
                    patch.object(runtime.platform, "processor", return_value="test CPU"), \
                    patch.object(runtime.platform, "platform", return_value="test OS"), \
                    patch.object(runtime, "capture_owned", side_effect=fail):
                with self.assertRaisesRegex(ValueError, "deliberate failure"):
                    runtime.capture(args)
            self.assertFalse(runtime.read_json(args.output / "environment.json")["complete"])
            self.assertEqual("retained", (args.output / "partial-log.txt").read_text())
            self.assertFalse((args.output / "summary.json").exists())
            with self.assertRaisesRegex(ValueError, "incomplete"):
                runtime.validate_complete_capture(args.output)

    def test_complete_capture_validation_rejects_raw_and_manifest_changes(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            summary = {"schema": runtime.SCHEMA, "verified": True, "contractOnly": True}
            runtime.write_json(root / "summary.json", summary)
            manifest = {"schema": runtime.SCHEMA, "complete": True, "rawFiles": [
                {"path": "summary.json", "sha256": runtime.sha256(root / "summary.json")}]}
            runtime.write_json(root / "environment.json", manifest)
            receipt = root / "capture-manifest.sha256"
            receipt.write_text(runtime.sha256(root / "environment.json") + "\n")
            self.assertEqual(manifest, runtime.validate_complete_capture(root))
            (root / "summary.json").write_text("changed raw")
            with self.assertRaisesRegex(ValueError, "Raw evidence changed"):
                runtime.validate_complete_capture(root)
            runtime.write_json(root / "environment.json", {**manifest, "changed": True})
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                runtime.validate_complete_capture(root)

    def test_comparison_rejects_identity_changes_but_preserves_different_frame_counts(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            directories = [root / name for name in ("before", "after")]
            for index, directory in enumerate(directories):
                work = directory / "isolated-profile"
                work.mkdir(parents=True)
                runtime.write_json(directory / "environment.json", {"schema": runtime.SCHEMA, "complete": True,
                    "label": "before" if index == 0 else "after", "rawFiles": [], "backend": "x11", "strokes": 8,
                    "heap": "2g", "sampleSeconds": .25, "jfrEnabled": True, "nmtEnabled": True,
                    "nmtSnapshotsRequested": False, "platform": "synthetic OS", "cpu": "synthetic CPU", "logicalCpus": 4, "affinity": [0, 1],
                    "provenance": {"projectRoot": str(root / f"project-{index}"), "sourceCommit": str(index) * 40,
                        "probeSources": [{"path": "probe.kt", "sha256": "a" * 64}], "toolFiles": [],
                        "runtimeArtifacts": [{"name": "native.jar", "sha256": "a" * 64, "kind": "file", "projectArtifact": False}],
                        "executables": [{"role": "java", "sha256": "b" * 64}], "javaRuntimeArtifacts": []}})
                runtime.write_json(directory / "summary.json", {"schema": runtime.SCHEMA, "contractOnly": False, "verified": True,
                    "nmtSnapshots": [], "rendering": {"observations": {"completedCallbacks": 10 + index * 5}},
                    "phaseNanos": {phase: 1 + index for phase in runtime.PHASES}})
                runtime.write_json(work / "fixture.json", {"fixtureSha256": "a" * 64, "inkCorpusSha256": "b" * 64})
                runtime.write_json(work / "verification.json", {"inkCorpusSha256": "b" * 64})
                runtime.write_json(work / "runtime.json", {"vmArgs": [f"-Duser.home={work}/home"],
                    "userDir": str(work), "classPath": "owned classpath", "javaRuntime": "same JVM", "awtToolkit": "XToolkit"})
                (work / "events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in self.events()))
            with patch.object(runtime, "validate_complete_capture", side_effect=lambda path: runtime.read_json(path / "environment.json")):
                output = runtime.compare_captures(*directories, root / "comparison")
                compared = runtime.read_json(output / "comparison.json")
                self.assertEqual(10, compared["before"]["summary"]["rendering"]["observations"]["completedCallbacks"])
                self.assertEqual(15, compared["after"]["summary"]["rendering"]["observations"]["completedCallbacks"])
                self.assertNotEqual(compared["before"]["sourceCommit"], compared["after"]["sourceCommit"])
                path = directories[1] / "environment.json"
                original = path.read_bytes()
                changes = [lambda value: value["provenance"]["runtimeArtifacts"][0].update(sha256="c" * 64),
                    lambda value: value["provenance"]["executables"][0].update(sha256="c" * 64),
                    lambda value: value.update(heap="4g"), lambda value: value.update(strokes=9),
                    lambda value: value["provenance"]["probeSources"][0].update(sha256="c" * 64)]
                for index, change in enumerate(changes):
                    value = json.loads(original)
                    change(value)
                    runtime.write_json(path, value)
                    with self.subTest(index=index), self.assertRaises(ValueError):
                        runtime.compare_captures(*directories, root / f"rejected-{index}")
                    path.write_bytes(original)

    def test_completed_capture_checks_portable_nested_paths_and_all_required_evidence(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            work = root / "isolated-profile"
            work.mkdir()
            names = ("runtime.jfr", "jfr-phases-log.txt", "process.jsonl", "isolated-profile/runtime.json",
                     "isolated-profile/rendering.json", "isolated-profile/fixture.json", "isolated-profile/verification.json")
            for name in names:
                (root / name).write_text("independently tested payload")
            (work / "events.jsonl").write_text("".join(json.dumps(event) + "\n" for event in self.events()))
            runtime.write_json(root / "summary.json", {"schema": runtime.SCHEMA, "verified": True, "contractOnly": False})
            manifest = {"schema": runtime.SCHEMA, "complete": True, "nmtSnapshotsRequested": False, "strokes": 8,
                        "rawFiles": [{"path": path.relative_to(root).as_posix(), "sha256": runtime.sha256(path)}
                                     for path in root.rglob("*") if path.is_file()]}
            def seal():
                runtime.write_json(root / "environment.json", manifest)
                (root / "capture-manifest.sha256").write_text(runtime.sha256(root / "environment.json"))
            seal()
            with patch.object(runtime, "validate_jfr"), patch.object(runtime, "read_jfr_phases"):
                self.assertEqual(manifest, runtime.validate_complete_capture(root))
                kept = manifest["rawFiles"]
                manifest["rawFiles"] = [row for row in kept if row["path"] != "isolated-profile/fixture.json"]
                seal()
                with self.assertRaisesRegex(ValueError, "missing required raw evidence"):
                    runtime.validate_complete_capture(root)
                manifest["rawFiles"] = kept
                seal()
                (work / "fixture.json").write_text("changed after capture")
                with self.assertRaisesRegex(ValueError, "Raw evidence changed"):
                    runtime.validate_complete_capture(root)

    def test_new_capture_cannot_be_nested_in_an_existing_capture(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            runtime.write_json(root / "environment.json", {"schema": runtime.SCHEMA})
            with self.assertRaisesRegex(ValueError, "new sibling"):
                runtime.check_new_output(root / "nested")


if __name__ == "__main__":
    unittest.main()
