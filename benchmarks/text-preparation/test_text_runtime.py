import copy
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("text_runtime", HERE / "text_runtime.py")
extension = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(extension)


class TextRuntimeTest(unittest.TestCase):
    def events(self):
        fixture = extension.load("runtime_fixture", extension.BASE / "test_desktop_runtime.py")
        fixture.runtime = extension.runtime
        events = fixture.RuntimeLauncherTest().events(nmt=True)
        additions = {
            "text_autosave_settle": {"settleMillis": 2_000},
            "robot_reopen_text": {"selectedPage": "runtime-probe-text", "savedTokenObserved": True},
            "text_reopen_settle": {"settleMillis": 2_000},
            "robot_final_ink": {"selectedPage": "runtime-probe-ink", "inkReady": True},
        }
        for row in events:
            if row["state"] == "end":
                row["evidence"].update(additions.get(row["phase"], {}))
        return events

    def test_complete_revisit_keeps_all_original_and_new_phase_checks(self):
        result = extension.validate_events(self.events(), True, 8)
        self.assertEqual(list(extension.runtime.PHASES), list(result))
        self.assertIs(extension.runtime, extension.analyzer().runtime)
        self.assertIs(extension.runtime, extension.exporter().runtime)

    def test_missing_changed_or_unrendered_revisit_is_rejected(self):
        for phase, key, value in (
            ("robot_reopen_text", "savedTokenObserved", False),
            ("robot_reopen_text", "completedFramesAfter", 1),
            ("robot_final_ink", "inkReady", False),
            ("text_reopen_settle", "settleMillis", 0),
            ("text_autosave_settle", "settleMillis", 0),
            ("robot_text_page", "editorTokenObserved", False),
        ):
            events = self.events()
            next(r for r in events if r["phase"] == phase and r["state"] == "end")["evidence"][key] = value
            with self.subTest(phase=phase, key=key), self.assertRaises(ValueError):
                extension.validate_events(events, True, 8)
        events = self.events()
        events = [r for r in events if r["phase"] != "robot_reopen_text"]
        with self.assertRaises(ValueError):
            extension.validate_events(events, True, 8)

    def test_probe_identity_includes_changed_driver_and_unchanged_render_observer(self):
        self.assertEqual(HERE / "DesktopRuntimeProbe.kt", extension.runtime.PROBE_SOURCES[0])
        self.assertEqual(extension.BASE / "RuntimeRenderProbe.kt", extension.runtime.PROBE_SOURCES[1])
        self.assertNotEqual(extension.runtime.probe_stamp(),
            extension.load("original_identity", extension.BASE / "desktop_runtime.py").probe_stamp())


if __name__ == "__main__":
    unittest.main()
