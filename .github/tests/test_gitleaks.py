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


if __name__ == "__main__":
    unittest.main()
