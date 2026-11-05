import copy
import importlib.util
from pathlib import Path
import unittest
import tempfile
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("egl_capture", Path(__file__).with_name("capture.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def fixture():
    names = ["idle", "scroll_down", "scroll_up", "idle_final"]
    return {"schema": "vive-egl-scroll/2", "toolkit": "sun.awt.wl.WLToolkit", "gpu": "Mesa Intel Iris Xe", "awtScale": 2,
            "renderApis": ["OPENGL"], "javaRuntime": "25-test", "trace": False, "logicalSize": [720, 670], "variant": "raster",
            "scroll": [0, 20, 0], "inputPeriodNanos": 10,
            "phases": [{"name": name, "startNanos": i * 100, "endNanos": i * 100 + 90,
                        "cpuNanos": 40, "submittedFrames": 2,
                        "memoryBefore": dict(rssBytes=100, highWaterRssBytes=200, heapUsedBytes=50),
                        "memoryAfter": dict(rssBytes=120, highWaterRssBytes=200, heapUsedBytes=70)} for i, name in enumerate(names)],
            "inputs": [[i, 110 + i * 10 if i < 2 else 210 + (i - 2) * 10,
                        112 + i * 10 if i < 2 else 212 + (i - 2) * 10,
                        115 + i * 10 if i < 2 else 215 + (i - 2) * 10,
                        118 + i * 10 if i < 2 else 218 + (i - 2) * 10, 1 if i < 2 else -1] for i in range(4)],
            "drawCompletions": [[115, 1440, 1340], [135, 1440, 1340], [215, 1440, 1340], [235, 1440, 1340]]}


class CaptureContracts(unittest.TestCase):
    def test_missing_bundle_cannot_produce_a_capture_or_create_a_profile(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "capture"
            args = SimpleNamespace(output=output, forks=1, events=10, strokes=1, columns=1, points=3,
                period_ms=16, ink_repetitions=1, bundle=Path(folder)/"missing.flatpak", flatpak_build=Path(folder)/"artifact")
            with self.assertRaisesRegex(ValueError, "Bundle must be an existing file"):
                capture.capture(args)
            self.assertFalse(output.exists())

    def test_flatpak_uses_only_packaged_app_jars_and_its_jbr(self):
        env = {key: "/owned profile/"+key for key in ("XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME")}
        env["WAYLAND_DISPLAY"] = "/run/owned/wayland-1"
        command = capture.flatpak_command(Path("/artifact"), Path("/capture"), Path("/capture/probes"), Path("/capture/fork"),
            ["/host/jbr/bin/java", "-Duser.home=/capture/home", "-cp", "/host/build/classes:/host/dependency.jar",
             "Probe", "argument with spaces;$(literal)"], env)
        self.assertIn("--unshare=network", command)
        self.assertIn("--nosocket=x11", command)
        self.assertIn("--unset-env=DISPLAY", command)
        self.assertIn("--env=WAYLAND_DISPLAY=wayland-1", command)
        self.assertIn("--env=XDG_SESSION_TYPE=wayland", command)
        self.assertIn("--build-dir=/capture/fork", command)
        self.assertEqual(["/capture/probes:/app/lib/*", "Probe", "argument with spaces;$(literal)"], command[command.index("-cp")+1:])
        self.assertEqual("/app/jbr/bin/java", command[command.index("/artifact")+1])
        self.assertNotIn("/host/build/classes:/host/dependency.jar", command)

    def interactions(self):
        raw = fixture()
        raw["phases"][-1:] = [dict(name=name, startNanos=300+i*100, endNanos=390+i*100,
            cpuNanos=40, submittedFrames=2,
            memoryBefore=dict(rssBytes=100, highWaterRssBytes=200, heapUsedBytes=50),
            memoryAfter=dict(rssBytes=120, highWaterRssBytes=200, heapUsedBytes=70))
            for i, name in enumerate(["zoom", "pen", "erase", "resize", "popup", "idle_final"])]
        raw["interactions"] = {"zoomBefore": 1, "zoomPeak": 1.25, "zoomAfter": 1,
            "persistedPenStrokes": 2, "remainingAuthoredStrokes": 0, "resizeHonored": True,
            "resizeReusedContext": True, "popupSurfaces": 1, "popupClosed": True, "pointerPeriodNanos": 1}
        for name, start in (("pen", 410), ("erase", 510)):
            raw["interactions"][name+"Inputs"] = [[i, start+i, start+i+1, start+i+2, start+i+3,
                501 if i % 15 == 0 else 502 if i % 15 == 14 else 506] for i in range(30)]
        return raw

    def test_validates_acknowledged_drawing_zoom_and_popup_lifecycle(self):
        raw = self.interactions()
        result = capture.summarize(raw, 2)
        self.assertEqual(2, result["interactions"]["persistedPenStrokes"])
        self.assertEqual(30, result["pointerMeasurements"]["pen"]["queueNanos"]["count"])
        self.assertEqual(1, result["pointerMeasurements"]["pen"]["queueNanos"]["p95"])
        for key, value in (("persistedPenStrokes", 1), ("remainingAuthoredStrokes", 1),
                           ("zoomPeak", 1), ("zoomAfter", 1.25), ("resizeReusedContext", False),
                           ("popupSurfaces", 0), ("popupClosed", False)):
            bad = copy.deepcopy(raw)
            bad["interactions"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): capture.summarize(bad, 2)

    def test_interaction_acknowledgement_needs_a_frame(self):
        raw = self.interactions()
        raw["phases"][3]["submittedFrames"] = 0
        with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_distinguishes_producer_lateness_queue_delay_and_handler_time(self):
        result = capture.summarize(fixture(), 2)
        self.assertEqual(2, result["producerLatenessNanos"]["median"])
        self.assertEqual(3, result["queueNanos"]["median"])
        self.assertEqual(5, result["scheduledToDispatchNanos"]["median"])
        self.assertEqual(3, result["dispatchNanos"]["median"])
        self.assertEqual(80, result["activeCpuNanos"])
        self.assertEqual(20, result["drawCompletionIntervalsNanos"]["scroll_down"]["median"])

    def test_rejects_dropped_reordered_or_adaptively_paced_input(self):
        for mutate in (lambda r: r["inputs"].pop(),
                       lambda r: r["inputs"][0].__setitem__(0, 1),
                       lambda r: r["inputs"][1].__setitem__(1, 121),
                       lambda r: r["inputs"][0].__setitem__(5, -1),
                       lambda r: r["inputs"][0].__setitem__(3, 111)):
            raw = fixture()
            mutate(raw)
            with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_rejects_incomplete_phases_software_and_absent_motion(self):
        for key, value in (("renderApis", ["SOFTWARE_FAST"]), ("renderApis", []),
                           ("gpu", "llvmpipe"), ("toolkit", "sun.awt.X11.XToolkit"),
                           ("scroll", [0, 0, 0]), ("scroll", [0, 20, 10]), ("phases", fixture()["phases"][:-1]),
                           ("drawCompletions", [])):
            raw = fixture()
            raw[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_rejects_missing_frames_in_one_active_phase(self):
        raw = fixture()
        raw["drawCompletions"] = raw["drawCompletions"][:2]
        with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_rejects_negative_nonfinite_or_overlapping_observations(self):
        for mutate in (lambda r: r["inputs"][0].__setitem__(2, float("nan")),
                       lambda r: r["phases"][0].__setitem__("cpuNanos", -1),
                       lambda r: r["phases"][1].__setitem__("startNanos", 80),
                       lambda r: r["drawCompletions"].reverse()):
            raw = fixture()
            mutate(raw)
            with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_rejects_invalid_scale_or_memory(self):
        for mutate in (lambda r: r.__setitem__("awtScale", 0),
                       lambda r: r.__setitem__("awtScale", float("nan")),
                       lambda r: r["phases"][0]["memoryAfter"].__setitem__("rssBytes", 201)):
            raw = fixture()
            mutate(raw)
            with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_rejects_lost_reordered_or_throttled_pointer_input(self):
        for mutate in (lambda r: r["penInputs"].pop(),
                       lambda r: r["penInputs"][1].__setitem__(0, 2),
                       lambda r: r["penInputs"][1].__setitem__(1, 412),
                       lambda r: r["eraseInputs"][0].__setitem__(5, 506),
                       lambda r: r["eraseInputs"][0].__setitem__(4, 900)):
            raw = self.interactions()
            mutate(raw["interactions"])
            with self.assertRaises(ValueError): capture.summarize(raw, 2)

    def test_long_inking_run_requires_every_stroke_and_scheduled_event(self):
        raw = self.interactions()
        interaction = raw["interactions"]
        interaction["expectedPenStrokes"] = interaction["persistedPenStrokes"] = 20
        interaction["penInputs"] = [[i, 410+i, 411+i, 412+i, 413+i,
            501 if i % 15 == 0 else 502 if i % 15 == 14 else 506] for i in range(300)]
        phases = [dict(name="pen", startNanos=400, endNanos=800), dict(name="erase", startNanos=500, endNanos=600)]
        result = capture.pointer_measurements(interaction, phases)
        self.assertEqual(300, result["pen"]["queueNanos"]["count"])
        interaction["penInputs"].pop()
        with self.assertRaises(ValueError): capture.pointer_measurements(interaction, phases)


if __name__ == "__main__": unittest.main()
