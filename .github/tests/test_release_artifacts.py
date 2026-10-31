import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "release_artifacts", ROOT / ".github/scripts/attach_release_artifacts.py",
)
release_artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_artifacts)
REPO = "CrownByte0b/viveNotes-multiplataform"
TAG = "v1.2.3"
COMMIT = "a" * 40
MSI = "ViveNotes-1.2.3-x64.msi"
FLATPAK = "ViveNotes-1.2.3-x86_64.flatpak"
APPIMAGE = "ViveNotes-1.2.3-x86_64.AppImage"


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.release = {
            "id": 123, "tag_name": TAG, "draft": True,
            "name": "My release title", "body": "My manually written notes", "assets": [],
        }
        self.releases = [self.release]
        self.current = None
        self.annotated = False
        self.moved_tag = False
        self.tag_reads = 0
        self.corrupt_msi = False
        self.missing_flatpak = False
        self.duplicate_flatpak = False
        self.corrupt_appimage = False
        self.missing_appimage = False
        self.expired = False
        self.assets = []
        self.runs = {}
        for index, (workflow, _) in enumerate(release_artifacts.BUILDS, 10):
            self.runs[workflow] = [{
                "id": index, "head_branch": TAG, "head_sha": COMMIT, "event": "push",
                "status": "completed", "conclusion": "success",
                "html_url": f"https://github.com/{REPO}/actions/runs/{index}",
            }]
        self.downloads = []
        self.uploads = []
        self.calls = []
        self.sandbox = tempfile.TemporaryDirectory()
        self.addCleanup(self.sandbox.cleanup)
        self.summary = Path(self.sandbox.name) / "summary.md"
        self.environment = patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(self.summary)})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.process = patch.object(release_artifacts.subprocess, "run", side_effect=self.command)
        self.process.start()
        self.addCleanup(self.process.stop)

    def command(self, arguments, **kwargs):
        self.calls.append(arguments)
        self.assertTrue(kwargs["check"])
        self.assertEqual("gh", arguments[0])
        if arguments[1] == "api":
            endpoint = arguments[-1]
            path = urlsplit(endpoint).path.removeprefix(f"repos/{REPO}/")
            paginated = "--paginate" in arguments
            if path == "releases":
                # Exercise an empty first page and a draft on a later page.
                response = [[], self.releases]
            elif path == "git/ref/tags/" + TAG:
                self.tag_reads += 1
                sha = "c" * 40 if self.moved_tag and self.tag_reads > 1 else COMMIT
                response = {"ref": "refs/tags/" + TAG, "object": {
                    "type": "tag" if self.annotated else "commit", "sha": sha,
                }}
            elif path == "git/tags/" + COMMIT:
                response = {"object": {"type": "commit", "sha": COMMIT}}
            elif path.startswith("actions/workflows/"):
                workflow = path.split("/")[2]
                query = parse_qs(urlsplit(endpoint).query)
                self.assertEqual([TAG], query["branch"])
                self.assertEqual([COMMIT], query["head_sha"])
                self.assertEqual(["push"], query["event"])
                response = [{"total_count": 0, "workflow_runs": []},
                            {"total_count": len(self.runs[workflow]), "workflow_runs": self.runs[workflow]}]
            elif path.startswith("actions/runs/"):
                index = int(path.split("/")[2]) - 10
                artifact = release_artifacts.BUILDS[index][1]
                response = [{"total_count": 1, "artifacts": [
                    {"id": 900 + index, "name": artifact, "expired": self.expired},
                ]}]
            elif path == "releases/123":
                response = self.current if self.current is not None else self.release
            elif path == "releases/123/assets":
                response = [self.assets]
            else:
                self.fail(f"Unexpected API request: {endpoint}")
            self.assertEqual(isinstance(response, list), paginated)
            return subprocess.CompletedProcess(arguments, 0, stdout=json.dumps(response))
        if arguments[1:3] == ["run", "download"]:
            self.downloads.append(arguments)
            directory = Path(arguments[arguments.index("--dir") + 1])
            directory.mkdir(parents=True)
            if arguments[3] == "10":
                msi = directory / MSI
                msi.write_bytes(b"MSI fixture")
                digest = hashlib.sha256(msi.read_bytes()).hexdigest()
                (directory / (MSI + ".sha256")).write_bytes(f"{digest}  {MSI}\r\n".encode("ascii"))
                if self.corrupt_msi:
                    msi.write_bytes(b"tampered MSI")
                (directory / "smoke.log").write_text("not a release asset")
            elif arguments[3] == "11" and not self.missing_flatpak:
                bundle = directory / "flatpak" / FLATPAK
                bundle.parent.mkdir()
                bundle.write_bytes(b"Flatpak fixture")
                if self.duplicate_flatpak:
                    (directory / FLATPAK).write_bytes(b"ambiguous bundle")
                (directory / "SHA256SUMS").write_text("dependency inventory, not a bundle checksum")
                (directory / "manifest.json").write_text("{}")
            elif arguments[3] == "12" and not self.missing_appimage:
                image = directory / APPIMAGE
                image.write_bytes(b"AppImage fixture")
                digest = hashlib.sha256(image.read_bytes()).hexdigest()
                (directory / (APPIMAGE + ".sha256")).write_text(f"{digest}  {APPIMAGE}\n", encoding="ascii")
                if self.corrupt_appimage:
                    image.write_bytes(b"tampered AppImage")
                (directory / "smoke-test.log").write_text("not a release asset")
            return subprocess.CompletedProcess(arguments, 0, stdout="")
        if arguments[1:3] == ["release", "upload"]:
            self.assertEqual(TAG, arguments[3])
            self.assertEqual(["--repo", REPO], arguments[-2:])
            self.uploads.append({Path(file).name: Path(file).read_bytes() for file in arguments[4:-2]})
            return subprocess.CompletedProcess(arguments, 0, stdout="")
        self.fail(f"Unexpected command (must never create, edit or publish releases): {arguments}")

    def attach(self):
        release_artifacts.attach(REPO, TAG)

    def test_attaches_only_installers_and_msi_checksum_and_keeps_authored_draft(self):
        before = copy.deepcopy(self.release)
        self.attach()
        self.assertEqual(before, self.release)
        self.assertEqual(["10", "11", "12"], [call[3] for call in self.downloads])
        self.assertEqual({MSI, MSI + ".sha256", FLATPAK, APPIMAGE, APPIMAGE + ".sha256"}, set(self.uploads[0]))
        self.assertEqual(b"AppImage fixture", self.uploads[0][APPIMAGE])
        self.assertIn("publish it manually", self.summary.read_text())
        for run in ("10", "11", "12"):
            self.assertIn(f"actions/runs/{run}", self.summary.read_text())

    def test_supports_annotated_tags(self):
        self.annotated = True
        self.attach()
        self.assertEqual(1, len(self.uploads))

    def test_requires_saved_draft_before_downloading(self):
        for releases in ([], [{**self.release, "draft": False}]):
            with self.subTest(releases=releases):
                self.releases = releases
                with self.assertRaisesRegex(ValueError, "draft"):
                    self.attach()
                self.assertEqual([], self.downloads)

    def test_rejects_wrong_tag_commit_and_branch_runs(self):
        original = self.runs["windows-msi.yml"][0]
        for changes in ({"head_branch": "main"}, {"head_branch": "v1.2.2"},
                        {"head_sha": "b" * 40}, {"event": "pull_request"}):
            with self.subTest(changes=changes):
                self.runs["windows-msi.yml"] = [{**original, **changes}]
                with self.assertRaisesRegex(ValueError, "No tag build"):
                    self.attach()
                self.assertEqual([], self.downloads)

    def test_every_latest_build_must_succeed_before_downloading(self):
        for workflow, _ in release_artifacts.BUILDS:
            original = self.runs[workflow][0]
            for state in ({"status": "in_progress", "conclusion": None},
                          {"status": "completed", "conclusion": "failure"}):
                with self.subTest(workflow=workflow, state=state):
                    self.runs[workflow] = [original, {**original, "id": 99, **state}]
                    with self.assertRaisesRegex(ValueError, "has not succeeded"):
                        self.attach()
                    self.assertEqual([], self.downloads)
            self.runs[workflow] = [original]

    def test_expired_artifacts_cannot_be_attached(self):
        self.expired = True
        with self.assertRaisesRegex(ValueError, "missing or expired"):
            self.attach()
        self.assertEqual([], self.downloads)

    def test_tampered_installer_missing_or_ambiguous_bundle_upload_nothing(self):
        for attribute in ("corrupt_msi", "missing_flatpak", "duplicate_flatpak", "corrupt_appimage", "missing_appimage"):
            with self.subTest(attribute=attribute):
                setattr(self, attribute, True)
                with self.assertRaises(ValueError):
                    self.attach()
                self.assertEqual([], self.uploads)
                setattr(self, attribute, False)

    def test_draft_published_during_download_is_not_modified(self):
        self.current = {**self.release, "draft": False}
        with self.assertRaisesRegex(ValueError, "published releases are not modified"):
            self.attach()
        self.assertEqual([], self.uploads)

    def test_moved_tag_is_not_attached(self):
        self.moved_tag = True
        with self.assertRaisesRegex(ValueError, "tag moved"):
            self.attach()
        self.assertEqual([], self.uploads)

    def test_existing_release_assets_are_not_overwritten(self):
        self.assets = [{"id": 444, "name": MSI}]
        with self.assertRaisesRegex(ValueError, "existing assets are preserved"):
            self.attach()
        self.assertEqual([], self.uploads)

    def test_tag_input_cannot_be_an_option_or_arbitrary_ref(self):
        for tag in ("main", "--latest", "v1.2.3;echo invalid", "v1.2.3/other"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "version tag"):
                release_artifacts.attach(REPO, tag)
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
