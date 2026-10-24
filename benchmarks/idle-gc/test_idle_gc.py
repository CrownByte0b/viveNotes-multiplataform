import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("idle_gc", Path(__file__).with_name("idle_gc.py"))
gc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc)


def fixture(mode="normal"):
    stages = ["before_work", "resources_closed"]
    for tick in range(1, 13):
        if tick == 5 and mode.startswith("explicit"): stages += ["before_request", "after_request"]
        stages.append(f"idle_{tick}")
    stages += ["before_next_work", "after_next_work"]
    report = {"schema": "vive-idle-gc-component/1", "mode": mode, "outputParity": True, "nextWorkNanos": 10,
              "collectors": ["G1 Young Generation", "G1 Old Generation"],
              "snapshots": [{"stage": name, "nanoTime": i, "epochMillis": i * 1000, "used": 10, "committed": 20}
                            for i, name in enumerate(stages)],
              "requestNanos": 5 if mode.startswith("explicit") else None}
    cause = "G1 Periodic Collection" if mode == "periodic" else "System.gc()" if mode.startswith("explicit") else "G1 Evacuation Pause"
    events = [{"type": "jdk.GarbageCollection", "values": {"cause": cause}},
              {"type": "jdk.GCPhasePause", "values": {"startTime": "1970-01-01T00:00:02Z", "duration": "PT0.000000123S"}}]
    return report, events


class GcContracts(unittest.TestCase):
    def test_alternating_order_and_each_mode_per_fork(self):
        self.assertEqual([(1, mode) for mode in gc.MODES] + [(2, mode) for mode in gc.MODES[::-1]], gc.schedule(2))
        for value in (0, 11, True):
            with self.assertRaises(ValueError): gc.schedule(value)

    def test_controlled_policy_flags(self):
        self.assertEqual([], gc.policy_flags("normal"))
        self.assertEqual([], gc.policy_flags("explicit"))
        self.assertEqual(["-XX:+ExplicitGCInvokesConcurrent"], gc.policy_flags("explicit-concurrent"))
        self.assertIn("-XX:G1PeriodicGCInterval=5000", gc.policy_flags("periodic"))
        with self.assertRaises(ValueError): gc.policy_flags("unknown")

    def test_valid_modes_and_pause_nanos(self):
        for mode in gc.MODES:
            report, events = fixture(mode)
            self.assertEqual(123, gc.validate(report, mode, events)["idlePauses"]["maxNanos"])

    def test_missing_idle_snapshot_and_bad_clock_fail(self):
        for mutation in (lambda r: r["snapshots"].pop(5), lambda r: r["snapshots"][5].update(nanoTime=0)):
            report, events = fixture()
            mutation(report)
            with self.assertRaises(ValueError): gc.validate(report, "normal", events)

    def test_wrong_heap_collector_and_output_fail(self):
        for mutation in (lambda r: r["snapshots"][0].update(used=21), lambda r: r.update(collectors=["ZGC"]),
                         lambda r: r.update(outputParity=False)):
            report, events = fixture()
            mutation(report)
            with self.assertRaises(ValueError): gc.validate(report, "normal", events)

    def test_request_must_be_observed_and_timed(self):
        for mode in ("periodic", "explicit", "explicit-concurrent"):
            report, events = fixture(mode)
            events[0]["values"]["cause"] = "G1 Evacuation Pause"
            with self.assertRaises(ValueError): gc.validate(report, mode, events)
        report, events = fixture("explicit")
        report["requestNanos"] = -1
        with self.assertRaises(ValueError): gc.validate(report, "explicit", events)

    def test_loss_and_missing_gc_fail(self):
        for events in ([], [{"type": "jdk.DataLoss", "values": {}}]):
            report, _ = fixture()
            with self.assertRaises(ValueError): gc.validate(report, "normal", events)


if __name__ == "__main__": unittest.main()
