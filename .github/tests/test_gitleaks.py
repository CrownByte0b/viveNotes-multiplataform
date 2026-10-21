import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
NOTICE = "shared/src/jvmMain/resources/ai/NOTICE.md"
SYNC_TEST = "shared/src/jvmTest/kotlin/com/vivenotes/data/sync/SyncServerClientTest.kt"
BENCHMARK_MANIFEST = "benchmarks/results/2026-10-21/environment.json"
OPTIMIZED_BENCHMARK_MANIFEST = "benchmarks/results/2026-10-21-optimized/environment.json"
FINAL_BENCHMARK_MANIFEST = "benchmarks/results/2026-10-21-optimized-final/environment.json"
BENCHMARK_SOURCE_FILES = (
    "desktopApp/src/main/kotlin/com/vivenotes/desktop/KeyBindingsFile.kt",
    "shared/src/commonMain/kotlin/com/vivenotes/ui/canvas/TextCaretKeys.kt",
    "shared/src/commonMain/kotlin/com/vivenotes/ui/keyboard/ShortcutKeys.kt",
    "shared/src/commonMain/kotlin/com/vivenotes/workspace/KeyBindings.kt",
    "shared/src/jvmMain/kotlin/com/vivenotes/ai/FormulaTokenizerDecoder.kt",
)
TOKENIZER_HASHES = (
    "02c318d9cfa95bf323371762b8f838a82709530274d36dba6eca880f0add6cc4",
    "2811d82701ec97c192fa256aa2b4516929373870ae660326cc5b1dc879b95ff2",
)


class GitleaksConfigTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gitleaks = shutil.which(os.environ.get("GITLEAKS_BIN", "gitleaks"))
        if cls.gitleaks is None:
            raise RuntimeError("Install gitleaks or set GITLEAKS_BIN to its executable")

    def scan(self, path, content):
        with tempfile.TemporaryDirectory(prefix="vive-gitleaks-") as directory:
            repo = Path(directory) / "repo"
            repo.mkdir()
            fixture = repo / path
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(content, encoding="utf-8")
            for arguments in (
                ["init", "--quiet"],
                ["add", "--", path],
                ["-c", "user.name=CI test", "-c", "user.email=ci@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture"],
            ):
                subprocess.run(["git", *arguments], cwd=repo, check=True, capture_output=True)
            report = Path(directory) / "report.json"
            result = subprocess.run(
                [self.gitleaks, "git", "--redact", "--no-banner",
                 "--config", str(ROOT / ".gitleaks.toml"),
                 "--report-format", "json", "--report-path", str(report), str(repo)],
                capture_output=True, text=True,
            )
            self.assertIn(result.returncode, (0, 1), result.stderr)
            findings = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(result.returncode, int(bool(findings)), result.stderr)
            return [(finding["RuleID"], finding["File"]) for finding in findings]

    def test_published_tokenizer_checksums_in_notice_are_allowed(self):
        notice = (ROOT / NOTICE).read_text(encoding="utf-8")
        for digest in TOKENIZER_HASHES:
            self.assertIn(digest, notice)
        self.assertEqual([], self.scan(NOTICE, notice))

    def test_same_checksums_in_other_files_are_still_detected(self):
        content = "\n".join(f'api_key = "{digest}"' for digest in TOKENIZER_HASHES)
        self.assertEqual(
            [("generic-api-key", "settings.properties")] * 2,
            self.scan("settings.properties", content),
        )

    def test_other_tokens_in_notice_are_still_detected(self):
        token = hashlib.sha256(b"gitleaks regression control").hexdigest()
        self.assertEqual(
            [("generic-api-key", NOTICE)],
            self.scan(NOTICE, f'api_key = "{token}"'),
        )

    def test_checksum_exceptions_require_the_entire_value(self):
        for digest in TOKENIZER_HASHES:
            with self.subTest(digest=digest):
                self.assertEqual(
                    [("generic-api-key", NOTICE)],
                    self.scan(NOTICE, f'api_key = "a{digest}f"'),
                )

    def test_existing_fake_sync_credentials_are_allowed(self):
        values = (
            "vive_c29tZS1leGFtcGxlLWRldmljZS10b2tlbg",
            "4f2a1c88-0e3d-4c1b-8b4a-77c9f0e5d612",
            "5a3b2d99-1f4e-4d2c-9c5b-88daf1e6c723",
        )
        content = "\n".join(f'val api_key = "{value}"' for value in values)
        self.assertEqual([], self.scan(SYNC_TEST, content))

    def test_other_sync_credentials_are_still_detected(self):
        token = hashlib.sha256(b"new sync credential control").hexdigest()
        self.assertEqual(
            [("generic-api-key", SYNC_TEST)],
            self.scan(SYNC_TEST, f'val api_key = "{token}"'),
        )

    def benchmark_checksums(self):
        return json.loads((ROOT / BENCHMARK_MANIFEST).read_text(encoding="utf-8"))["productionFiles"]

    def test_benchmark_manifest_contains_source_hashes_and_scans_clean(self):
        commit = json.loads((ROOT / BENCHMARK_MANIFEST).read_text(encoding="utf-8"))["sourceCommit"]
        checksums = self.benchmark_checksums()
        for path in BENCHMARK_SOURCE_FILES:
            with self.subTest(path=path):
                source = subprocess.check_output(["git", "show", f"{commit}:{path}"], cwd=ROOT)
                self.assertEqual(hashlib.sha256(source).hexdigest(), checksums[path])
        self.assertEqual([], self.scan(BENCHMARK_MANIFEST, (ROOT / BENCHMARK_MANIFEST).read_text(encoding="utf-8")))

    def test_benchmark_hash_exceptions_require_the_manifest_path(self):
        checksums = self.benchmark_checksums()
        content = json.dumps({path: checksums[path] for path in BENCHMARK_SOURCE_FILES}, indent=2)
        self.assertEqual(
            [("generic-api-key", "other/environment.json")] * len(BENCHMARK_SOURCE_FILES),
            self.scan("other/environment.json", content),
        )

    def test_benchmark_hashes_in_credential_fields_are_still_detected(self):
        checksums = self.benchmark_checksums()
        content = "\n".join(f'api_key = "{checksums[path]}"' for path in BENCHMARK_SOURCE_FILES)
        self.assertEqual(
            [("generic-api-key", BENCHMARK_MANIFEST)] * len(BENCHMARK_SOURCE_FILES),
            self.scan(BENCHMARK_MANIFEST, content),
        )

    def test_benchmark_hash_exceptions_require_the_full_source_path(self):
        checksums = self.benchmark_checksums()
        content = json.dumps({f"other/{path}": checksums[path] for path in BENCHMARK_SOURCE_FILES}, indent=2)
        self.assertEqual(
            [("generic-api-key", BENCHMARK_MANIFEST)] * len(BENCHMARK_SOURCE_FILES),
            self.scan(BENCHMARK_MANIFEST, content),
        )

    def test_benchmark_hash_exceptions_require_exact_source_and_value(self):
        checksums = self.benchmark_checksums()
        token = hashlib.sha256(b"benchmark manifest credential control").hexdigest()
        for path in BENCHMARK_SOURCE_FILES:
            for value in (token, f'a{checksums[path]}f'):
                with self.subTest(path=path, altered=value != token):
                    self.assertEqual(
                        [("generic-api-key", BENCHMARK_MANIFEST)],
                        self.scan(BENCHMARK_MANIFEST, json.dumps({path: value}, indent=2)),
                    )

    def test_extra_token_in_benchmark_manifest_is_still_detected(self):
        manifest = json.loads((ROOT / BENCHMARK_MANIFEST).read_text(encoding="utf-8"))
        manifest["api_key"] = hashlib.sha256(b"extra benchmark credential control").hexdigest()
        self.assertEqual(
            [("generic-api-key", BENCHMARK_MANIFEST)],
            self.scan(BENCHMARK_MANIFEST, json.dumps(manifest, indent=2)),
        )

    def test_optimized_capture_exceptions_are_exact_and_keep_credentials_detectable(self):
        for path in (OPTIMIZED_BENCHMARK_MANIFEST, FINAL_BENCHMARK_MANIFEST):
            with self.subTest(capture=path):
                self.check_optimized_capture_exceptions(path)

    def check_optimized_capture_exceptions(self, path):
        content = (ROOT / path).read_text(encoding="utf-8")
        manifest = json.loads(content)
        checksums = manifest["productionFiles"]
        self.assertEqual([], self.scan(path, content))
        control = hashlib.sha256(b"optimized benchmark credential control").hexdigest()
        fixtures = [
            ("other/environment.json", json.dumps({name: checksums[name] for name in BENCHMARK_SOURCE_FILES}, indent=2), 5),
            (path, "\n".join(f'api_key = "{checksums[name]}"' for name in BENCHMARK_SOURCE_FILES), 5),
            (path, json.dumps({f"other/{name}": checksums[name] for name in BENCHMARK_SOURCE_FILES}, indent=2), 5),
            (path, json.dumps({**manifest, "api_key": control}, indent=2), 1),
        ]
        for source in BENCHMARK_SOURCE_FILES:
            for value in (control, f'a{checksums[source]}f'):
                fixtures.append((path, json.dumps({source: value}, indent=2), 1))
        tokenizer = BENCHMARK_SOURCE_FILES[-1]
        previous = self.benchmark_checksums()[tokenizer]
        self.assertNotEqual(previous, checksums[tokenizer])
        fixtures.extend([
            (path, json.dumps({tokenizer: previous}, indent=2), 1),
            (BENCHMARK_MANIFEST, json.dumps({tokenizer: checksums[tokenizer]}, indent=2), 1),
        ])
        for fixture_path, fixture, count in fixtures:
            with self.subTest(path=fixture_path, fixture=fixture[:120]):
                self.assertEqual(
                    [("generic-api-key", fixture_path)] * count,
                    self.scan(fixture_path, fixture),
                )


if __name__ == "__main__":
    unittest.main()
