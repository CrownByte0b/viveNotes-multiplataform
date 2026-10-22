import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("runtime_export", Path(__file__).with_name("export_results.py"))
export = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(export)


class RuntimeExportTest(unittest.TestCase):
    def test_nmt_preserves_total_and_heap_separately_and_ignores_malloc_details(self):
        result = export.nmt_summary("""100:
Native Memory Tracking:
Total: reserved=4000KB, committed=2000KB
       malloc: 100KB #20
-                 Java Heap (reserved=3000KB, committed=1500KB)
                            (mmap: reserved=3000KB, committed=1500KB, at peak)
-                     Class (reserved=500KB, committed=100KB)
""")
        self.assertEqual(2000 * 1024, result["Total"]["committedBytes"])
        self.assertEqual(1500 * 1024, result["Java Heap"]["committedBytes"])
        self.assertEqual({"Total", "Java Heap", "Class"}, set(result))

    def test_unavailable_nmt_is_rejected_instead_of_becoming_zero_memory(self):
        with self.assertRaises(ValueError):
            export.nmt_summary("Native memory tracking is not enabled")
        with self.assertRaises(ValueError):
            export.nmt_summary("Total: reserved=4000KB, committed=2000KB")

    def test_private_profile_paths_are_removed_without_changing_instrumentation(self):
        result = export.public_vm_args([
            "-Xmx2g", "-Duser.home=/private/profile/home",
            r"-Djava.util.prefs.userRoot=C:\private\profile\preferences",
            r"-XX:StartFlightRecording=filename=C:\private\runtime.jfr,settings=profile,dumponexit=true",
            "-XX:NativeMemoryTracking=summary"])
        self.assertEqual(["-Xmx2g", "-Duser.home=<profile>/home", "-Djava.util.prefs.userRoot=<profile>/preferences",
                          "-XX:StartFlightRecording=filename=<capture>/runtime.jfr,settings=profile,dumponexit=true",
                          "-XX:NativeMemoryTracking=summary"], result)
