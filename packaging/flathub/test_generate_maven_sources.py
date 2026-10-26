#!/usr/bin/env python3
"""Contract tests for generate_maven_sources.py, on a stand-in Gradle cache: no network."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_maven_sources as generator  # noqa: E402


class GenerateMavenSourcesTest(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp())
        self.cache = self.home / "caches" / "modules-2" / "files-2.1"

    def cached(self, group, module, version, name, content, sha1="0123abcd"):
        file = self.cache / group / module / version / sha1 / name
        file.parent.mkdir(parents=True)
        file.write_bytes(content)
        return file

    def test_each_cached_file_becomes_a_maven_layout_source_with_its_sha256(self):
        self.cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20", "kotlin-stdlib-2.4.20.jar", b"jar")
        self.cached("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20", "kotlin-stdlib-2.4.20.pom", b"pom", "4567ef")

        found = generator.sources(self.home, exists=lambda url: url.startswith(generator.CENTRAL))

        self.assertEqual([
            {
                "type": "file",
                "url": f"{generator.CENTRAL}/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.jar",
                "sha256": hashlib.sha256(b"jar").hexdigest(),
                "dest": "offline-repository/org/jetbrains/kotlin/kotlin-stdlib/2.4.20",
                "dest-filename": "kotlin-stdlib-2.4.20.jar",
            },
            {
                "type": "file",
                "url": f"{generator.CENTRAL}/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.pom",
                "sha256": hashlib.sha256(b"pom").hexdigest(),
                "dest": "offline-repository/org/jetbrains/kotlin/kotlin-stdlib/2.4.20",
                "dest-filename": "kotlin-stdlib-2.4.20.pom",
            },
        ], found)

    def test_google_groups_are_looked_up_on_google_first_and_others_fall_through(self):
        self.cached("androidx.ink", "ink-brush", "1.1.0-alpha06", "ink-brush-1.1.0-alpha06.pom", b"a")
        self.cached("com.example", "plugin", "1.0", "plugin-1.0.pom", b"b")
        asked = []

        def exists(url):
            asked.append(url)
            return url.startswith(f"{generator.GOOGLE}/androidx") or url.startswith(generator.PLUGIN_PORTAL)

        urls = [entry["url"] for entry in generator.sources(self.home, exists=exists, workers=1)]

        self.assertEqual([
            f"{generator.GOOGLE}/androidx/ink/ink-brush/1.1.0-alpha06/ink-brush-1.1.0-alpha06.pom",
            f"{generator.PLUGIN_PORTAL}/com/example/plugin/1.0/plugin-1.0.pom",
        ], urls)
        self.assertEqual(f"{generator.CENTRAL}/com/example/plugin/1.0/plugin-1.0.pom", asked[1])

    def test_a_file_module_metadata_renames_is_placed_and_fetched_at_its_url(self):
        self.cached("io.github.example", "lib-jvm", "0.1", "lib-jvm-0.1.module", json.dumps({"variants": [
            {"name": "jvmRuntimeElements", "files": [{"name": "library-jvm-0.1.jar", "url": "lib-jvm-0.1.jar"}]},
        ]}).encode(), "11")
        self.cached("io.github.example", "lib-jvm", "0.1", "library-jvm-0.1.jar", b"jar", "22")

        jar = generator.sources(self.home, exists=lambda url: url.startswith(generator.CENTRAL))[0]

        self.assertEqual(f"{generator.CENTRAL}/io/github/example/lib-jvm/0.1/lib-jvm-0.1.jar", jar["url"])
        self.assertEqual("lib-jvm-0.1.jar", jar["dest-filename"])
        self.assertEqual(hashlib.sha256(b"jar").hexdigest(), jar["sha256"])

    def test_two_cached_copies_resolve_to_the_one_the_chosen_url_serves(self):
        central_copy = b'{"variants": [], "published": "central"}'
        self.cached("org.example", "plugin", "1.0", "plugin-1.0.module", central_copy, "11")
        self.cached("org.example", "plugin", "1.0", "plugin-1.0.module", b'{"variants": [], "published": "portal"}', "22")
        central = lambda url: url.startswith(generator.CENTRAL)

        found = generator.sources(self.home, exists=central, fetch=lambda url: hashlib.sha256(central_copy).hexdigest())
        self.assertEqual([hashlib.sha256(central_copy).hexdigest()], [entry["sha256"] for entry in found])

        with self.assertRaisesRegex(ValueError, "serves none of the copies"):
            generator.sources(self.home, exists=central, fetch=lambda url: hashlib.sha256(b"other").hexdigest())

    def test_a_file_no_declared_repository_serves_fails_the_generation(self):
        self.cached("com.example", "private", "1.0", "private-1.0.jar", b"c")
        with self.assertRaisesRegex(LookupError, "com/example/private/1.0/private-1.0.jar"):
            generator.sources(self.home, exists=lambda url: False)

    def test_a_missing_cache_explains_how_to_fill_it(self):
        with self.assertRaisesRegex(SystemExit, "run the build with this GRADLE_USER_HOME first"):
            generator.sources(self.home / "empty")


if __name__ == "__main__":
    unittest.main()
