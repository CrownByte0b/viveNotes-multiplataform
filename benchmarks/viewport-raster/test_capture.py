import copy
import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('viewport_capture', Path(__file__).with_name('capture.py'))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


def fixture():
    return dict(schema='vive-viewport-raster/1', cases=[dict(name=name, units=40_000,
        exactFreshRasterPixels=True, sampleCount=7, warmupOps=8, operationsPerSample=1,
        sampleNanosPerOperation=[1, 2, 3, 4, 5, 6, 7], medianNanos=4, p95BatchAverageNanos=7,
        sampleAllocatedBytesPerOperation=[10] * 7, medianAllocatedBytes=10,
        requests=120, rasterBuilds=30, cacheHits=90, finishedStrokeDraws=500,
        allocatedSurfacePixels=1000, updatedPixels=1000, pathBuilds=100,
        peakRetainedPixelBytes=2000, fixtureSha256='a' * 64) for name in capture.NAMES])


class CaptureContractTest(unittest.TestCase):
    def test_complete_raw_fixture_is_accepted(self):
        self.assertEqual(20, len(capture.cases(fixture())))

    def test_missing_duplicate_and_reordered_cases_are_refused(self):
        for mutate in (lambda rows: rows.pop(), lambda rows: rows.__setitem__(1, rows[0]), lambda rows: rows.reverse()):
            value = fixture(); mutate(value['cases'])
            with self.assertRaises(ValueError): capture.cases(value)

    def test_pixel_validation_and_original_corpus_are_required(self):
        for key, bad in [('exactFreshRasterPixels', False), ('units', 1024), ('fixtureSha256', '')]:
            value = fixture(); value['cases'][0][key] = bad
            with self.assertRaises(ValueError): capture.cases(value)

    def test_sample_summaries_must_match_raw_values(self):
        for key, bad in [('medianNanos', 5), ('p95BatchAverageNanos', 6), ('medianAllocatedBytes', 11),
                         ('sampleNanosPerOperation', [float('nan')] * 7), ('sampleAllocatedBytesPerOperation', [-1] * 7)]:
            value = fixture(); value['cases'][0][key] = bad
            with self.assertRaises(ValueError): capture.cases(value)

    def test_measured_and_warmup_operation_counts_are_fixed(self):
        for key, bad in [('sampleCount', 6), ('warmupOps', 9), ('operationsPerSample', 2)]:
            value = fixture(); value['cases'][0][key] = bad
            with self.assertRaises(ValueError): capture.cases(value)

    def test_hit_build_and_request_counts_must_reconcile(self):
        for key, bad in [('cacheHits', 89), ('rasterBuilds', 31), ('requests', 119), ('finishedStrokeDraws', True),
                         ('pathBuilds', -1), ('updatedPixels', 1001)]:
            value = fixture(); value['cases'][0][key] = bad
            with self.assertRaises(ValueError): capture.cases(value)

    def test_retained_pixel_budget_is_a_hard_validation_gate(self):
        value = fixture(); value['cases'][0]['peakRetainedPixelBytes'] = 64 * 1024 * 1024 + 1
        with self.assertRaises(ValueError): capture.cases(value)

    def test_counter_validation_does_not_mutate_raw_evidence(self):
        value = fixture(); original = copy.deepcopy(value)
        capture.cases(value)
        self.assertEqual(original, value)


if __name__ == '__main__': unittest.main()
