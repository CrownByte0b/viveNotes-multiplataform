import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("capture_contention", Path(__file__).with_name("capture_contention.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureContractTest(unittest.TestCase):
    def test_gradle_file_paths_remain_literal(self):
        self.assertEqual("'/tmp/user$var\\'s\\nfile'", capture.groovy_literal("/tmp/user$var's\nfile"))

    def test_missing_skipped_failed_or_wrong_sample_capture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "result.xml"
            good = ('<testsuite tests="1" failures="0" errors="0" skipped="0"><system-out><![CDATA['
                    'DESKTOP_SYNC_CONTENTION injectedUploadDelayMs=250 bytes=1024 warmups=2 samplesNs=[1,2,3,4,5,6]\n'
                    'DESKTOP_SYNC_RUNTIME {"vmArgs":[]}\n]]></system-out></testsuite>')
            file.write_text(good)
            self.assertEqual(3.5, capture.result(file)["medianNs"])
            for invalid in [good.replace('skipped="0"', 'skipped="1"'), good.replace('failures="0"', 'failures="1"'),
                            good.replace('[1,2,3,4,5,6]', '[1,2]'), good.replace('[1,2,3,4,5,6]', '[1,2,3,4,5,0]'),
                            good.replace('DESKTOP_SYNC_RUNTIME ', 'UNAVAILABLE ')]:
                file.write_text(invalid)
                with self.assertRaises(ValueError): capture.result(file)

    def test_external_hashes_exclude_project_artifacts_and_runtime_normalizes_only_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); project = root / "project"; project.mkdir()
            own = project / "own.jar"; own.write_bytes(b"own")
            external = root / "external.jar"; external.write_bytes(b"external")
            self.assertEqual([("external.jar", capture.digest(external))],
                             capture.artifacts([str(own), str(external), str(project)], project))
            identity = capture.runtime_identity({"runtime": {"vmArgs": [f"-Dtmp={project}/tmp", "-Xmx2g"]}}, project)
            self.assertEqual(["-Dtmp=<project>/tmp", "-Xmx2g"], identity["vmArgs"])


if __name__ == "__main__": unittest.main()
