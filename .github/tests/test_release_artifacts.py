import copy
import fnmatch
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import shutil
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
spec = importlib.util.spec_from_file_location("appimage_build", ROOT / "packaging/appimage/build.py")
appimage_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(appimage_build)
REPO = "CrownByte0b/viveNotes-multiplataform"
TAG = "v1.2.3"
COMMIT = "a" * 40
MSI = "ViveNotes-x64.msi"
FLATPAK = "ViveNotes-1.2.3-x86_64.flatpak"
APPIMAGE = "ViveNotes-1.2.3-x86_64.AppImage"
INSTALLERS = {MSI: b"MSI fixture", FLATPAK: b"Flatpak fixture", APPIMAGE: b"AppImage fixture"}
# Where a published release serves its assets.
DOWNLOADS = f"https://github.com/{REPO}/releases/download/{TAG}"


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
        self.zsyncmake_path = "/usr/bin/zsyncmake"
        self.zsyncmake_fails = False
        # What a test changes in the control file a working zsyncmake would write.
        self.control = lambda fields, separator, table: (fields, separator, table)
        self.controls = []
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
        os.environ.pop("GITHUB_SERVER_URL", None)
        self.tools = patch.object(release_artifacts.shutil, "which",
                                  side_effect=lambda tool: self.zsyncmake_path if tool == "zsyncmake" else None)
        self.tools.start()
        self.addCleanup(self.tools.stop)
        self.process = patch.object(release_artifacts.subprocess, "run", side_effect=self.command)
        self.process.start()
        self.addCleanup(self.process.stop)

    def command(self, arguments, **kwargs):
        self.calls.append(arguments)
        self.assertTrue(kwargs["check"])
        if arguments[0] == "zsyncmake":
            return self.zsyncmake(arguments)
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

    def zsyncmake(self, arguments):
        """Stands in for the tool: its header fields, then a block checksum table of the right size."""
        self.controls.append(arguments)
        options = dict(zip(arguments[1:-1:2], arguments[2:-1:2]))
        self.assertEqual({"-u", "-f", "-o"}, set(options))
        if self.zsyncmake_fails:
            raise subprocess.CalledProcessError(2, arguments, stderr="zsyncmake: cannot read the file")
        data = Path(arguments[-1]).read_bytes()
        fields = {"zsync": "0.6.2", "Filename": options["-f"], "MTime": "Thu, 12 Nov 2026 05:00:00 +0000",
                  "Blocksize": "4", "Length": str(len(data)), "Hash-Lengths": "2,2,3",
                  "URL": options["-u"], "SHA-1": hashlib.sha1(data).hexdigest()}
        fields, separator, table = self.control(fields, b"\n\n", b"\x07" * (-(-len(data) // 4) * 5))
        header = "\n".join(f"{name}: {value}" for name, value in fields.items())
        Path(options["-o"]).write_bytes(header.encode("ascii") + separator + table)
        return subprocess.CompletedProcess(arguments, 0, stdout="")

    def attach(self):
        release_artifacts.attach(REPO, TAG)

    def test_attaches_installers_checksums_and_zsync_files_and_keeps_authored_draft(self):
        before = copy.deepcopy(self.release)
        self.attach()
        self.assertEqual(before, self.release)
        self.assertEqual(["10", "11", "12"], [call[3] for call in self.downloads])
        self.assertEqual({MSI, MSI + ".sha256", MSI + ".zsync", FLATPAK, FLATPAK + ".zsync",
                          APPIMAGE, APPIMAGE + ".sha256", APPIMAGE + ".zsync"}, set(self.uploads[0]))
        self.assertEqual(b"AppImage fixture", self.uploads[0][APPIMAGE])
        self.assertIn("publish it manually", self.summary.read_text())
        for run in ("10", "11", "12"):
            self.assertIn(f"actions/runs/{run}", self.summary.read_text())
        for name in self.uploads[0]:
            self.assertIn(f"- `{name}`", self.summary.read_text())

    def test_each_zsync_file_points_at_the_installers_published_address(self):
        self.attach()
        self.assertEqual(3, len(self.controls))
        for name, content in INSTALLERS.items():
            header = self.uploads[0][name + ".zsync"].split(b"\n\n", 1)[0].decode("ascii").splitlines()
            self.assertIn(f"Filename: {name}", header)
            # Absolute: GitHub redirects a download to storage, where a relative address leads nowhere.
            self.assertIn(f"URL: {DOWNLOADS}/{name}", header)
            self.assertIn(f"Length: {len(content)}", header)
            self.assertIn(f"SHA-1: {hashlib.sha1(content).hexdigest()}", header)

    def test_appimage_updaters_find_the_attached_zsync_file(self):
        kind, owner, repository, release, pattern = appimage_build.UPDATE_INFORMATION.split("|")
        self.assertEqual(("gh-releases-zsync", REPO, "latest"), (kind, f"{owner}/{repository}", release))
        self.attach()
        # AppImageUpdate takes the release asset whose name matches "*" + pattern.
        self.assertEqual([APPIMAGE + ".zsync"],
                         [name for name in self.uploads[0] if fnmatch.fnmatchcase(name, "*" + pattern)])

    def test_the_msi_keeps_one_name_in_every_release(self):
        self.assertEqual("ViveNotes-x64.msi", release_artifacts.MSI)
        self.attach()
        self.assertEqual({"ViveNotes-x64.msi", "ViveNotes-x64.msi.sha256", "ViveNotes-x64.msi.zsync"},
                         {name for name in self.uploads[0] if ".msi" in name})
        build = (ROOT / "packaging/windows/build.ps1").read_text()
        self.assertIn(f'"{release_artifacts.MSI}"', build)

    def test_zsync_file_that_does_not_describe_its_installer_uploads_nothing(self):
        def changed(**values):
            return lambda fields, separator, table: (fields | values, separator, table)

        def without(name):
            return lambda fields, separator, table: (
                {key: value for key, value in fields.items() if key != name}, separator, table)

        faults = {
            "another file's length": changed(Length="3"),
            "another file's digest": changed(**{"SHA-1": "0" * 40}),
            "another file's name": changed(Filename="Other.msi"),
            "relative address": changed(URL=MSI),
            "no address": without("URL"),
            "not a zsync file": without("zsync"),
            "no block size": without("Blocksize"),
            "unreadable hash lengths": changed(**{"Hash-Lengths": "2,2"}),
            "zero block size": changed(Blocksize="0"),
            "truncated table": lambda fields, separator, table: (fields, separator, table[:-1]),
            "no table": lambda fields, separator, table: (fields, b"\n", b""),
        }
        for fault, control in faults.items():
            with self.subTest(fault=fault):
                self.control = control
                with self.assertRaisesRegex(ValueError, "usable control file"):
                    self.attach()
                self.assertEqual([], self.uploads)

    def test_failing_zsyncmake_uploads_nothing(self):
        self.zsyncmake_fails = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.attach()
        self.assertEqual([], self.uploads)

    def test_missing_zsyncmake_stops_before_anything_is_requested(self):
        self.zsyncmake_path = None
        with self.assertRaisesRegex(ValueError, "zsync package"):
            self.attach()
        self.assertEqual([], self.calls)

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
                # Only installers that passed their checks are described.
                self.assertEqual([], self.controls)
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
        for name in (MSI, APPIMAGE + ".zsync"):
            with self.subTest(name=name):
                self.assets = [{"id": 444, "name": name}]
                with self.assertRaisesRegex(ValueError, "existing assets are preserved"):
                    self.attach()
                self.assertEqual([], self.uploads)

    def test_tag_input_cannot_be_an_option_or_arbitrary_ref(self):
        for tag in ("main", "--latest", "v1.2.3;echo invalid", "v1.2.3/other"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "version tag"):
                release_artifacts.attach(REPO, tag)
        self.assertEqual([], self.calls)


@unittest.skipUnless(shutil.which("zsyncmake"), "zsync is not installed")
class ZsyncMakeTest(unittest.TestCase):
    """The installed zsyncmake itself; the release workflow runs this before it attaches anything."""

    def test_installed_zsyncmake_writes_the_control_file_the_release_needs(self):
        with tempfile.TemporaryDirectory() as folder:
            installer = Path(folder) / APPIMAGE
            # Many blocks and a short last one.
            content = random.Random(7).randbytes(3_000_001)
            installer.write_bytes(content)
            control = release_artifacts.zsync_file(installer, f"{DOWNLOADS}/{APPIMAGE}")
            self.assertEqual(installer.with_name(APPIMAGE + ".zsync"), control)
            self.assertEqual({APPIMAGE, APPIMAGE + ".zsync"}, set(os.listdir(folder)))
            self.assertEqual(content, installer.read_bytes())
            header, table = control.read_bytes().split(b"\n\n", 1)
            fields = dict(line.split(": ", 1) for line in header.decode("ascii").splitlines())
            self.assertRegex(fields["zsync"], r"^0\.6\.")
            self.assertEqual(APPIMAGE, fields["Filename"])
            self.assertEqual(f"{DOWNLOADS}/{APPIMAGE}", fields["URL"])
            self.assertEqual("3000001", fields["Length"])
            self.assertEqual(hashlib.sha1(content).hexdigest(), fields["SHA-1"])
            # Not compressed in transit: a client must end with the released bytes.
            self.assertEqual({"zsync", "Filename", "MTime", "Blocksize", "Length", "Hash-Lengths", "URL", "SHA-1"},
                             set(fields))
            self.assertGreater(len(table), 1000)

    def test_replaces_a_control_file_left_by_an_earlier_attempt(self):
        with tempfile.TemporaryDirectory() as folder:
            installer = Path(folder) / MSI
            installer.write_bytes(b"MSI fixture" * 1000)
            stale = installer.with_name(MSI + ".zsync")
            stale.write_bytes(b"left over")
            control = release_artifacts.zsync_file(installer, f"{DOWNLOADS}/{MSI}")
            self.assertEqual(stale, control)
            self.assertIn(f"URL: {DOWNLOADS}/{MSI}\n".encode("ascii"), control.read_bytes())


if __name__ == "__main__":
    unittest.main()
