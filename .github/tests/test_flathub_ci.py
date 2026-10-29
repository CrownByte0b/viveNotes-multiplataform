import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "packaging/flathub/run_ci.py"
spec = importlib.util.spec_from_file_location("flathub_ci", SCRIPT)
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


def git(directory, *arguments):
    return subprocess.check_output(
        ["git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false",
         "-c", "user.name=CI fixture", "-c", "user.email=ci@example.invalid",
         "-C", str(directory), *arguments], stderr=subprocess.PIPE, text=True,
    ).strip()


def repository(directory, files):
    directory.mkdir(parents=True)
    git(directory, "init", "--quiet")
    for name, content in files.items():
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
    git(directory, "add", ".")
    git(directory, "commit", "--quiet", "-m", "fixture")
    return git(directory, "rev-parse", "HEAD")


class FlathubRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="vive flathub $(literal) ")
        self.addCleanup(self.temporary.cleanup)
        base = Path(self.temporary.name)
        self.root = base / "project"
        self.builder = base / "upstream"
        self.state = base / "state"
        self.manifest = f"id: {ci.APP_ID}\nmodules:\n  - sources:\n      - commit: {'a' * 40}\n"
        self.source_revision = repository(self.root, {
            ".gitignore": "local.properties\n",
            f"packaging/flathub/{ci.APP_ID}.yml": self.manifest,
            "packaging/flathub/maven-sources.json": "[]\n",
            "packaging/flathub/vivenotes": "#!/bin/sh\nexec /app/jbr/bin/java \"$@\"\n",
            "packaging/flathub/patches/ink/native.patch": "patch fixture\n",
            "packaging/flatpak/flathub.json": '{"only-arches": ["x86_64"]}\n',
        })
        (self.root / "local.properties").write_text("private fixture\n")
        self.builder_revision = repository(self.builder, {
            "justfile": "prepare-env:\n    echo ready\n",
            "flatpak.seccomp.json": '{}\n',
            "LICENSE": "upstream licence fixture\n",
        })

    def prepare(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return ci.prepare(self.root, self.state, self.builder)

    def test_prepare_preserves_release_pins_and_copies_every_local_build_input(self):
        work = self.prepare()
        self.assertEqual(self.manifest, (work / f"{ci.APP_ID}.yml").read_text())
        for name in ("maven-sources.json", "vivenotes", "patches/ink/native.patch"):
            self.assertEqual((self.root / "packaging/flathub" / name).read_bytes(), (work / name).read_bytes())
        self.assertEqual({"only-arches": ["x86_64"]}, json.loads((work / "flathub.json").read_text()))
        self.assertFalse((work / "local.properties").exists())
        self.assertEqual((self.builder / "justfile").read_bytes(), (work / ".flathub.justfile").read_bytes())
        self.assertEqual((self.builder / "LICENSE").read_bytes(), (work / "FLATHUB-BUILDER-LICENSE").read_bytes())

    def test_prepare_records_real_checkout_metadata_without_creating_a_commit(self):
        work = self.prepare()
        self.assertEqual(self.source_revision, git(work, "rev-parse", "HEAD"))
        provenance = json.loads((work / "artifacts/provenance.json").read_text())
        self.assertEqual(self.source_revision, provenance["checkout_commit"])
        self.assertEqual(self.builder_revision, provenance["builder_revision"])
        self.assertEqual(ci.BUILDER_IMAGE, provenance["builder_image"])
        self.assertEqual(64, len(provenance["manifest_sha256"]))

    def test_prepare_refreshes_inputs_without_discarding_the_source_cache(self):
        work = self.prepare()
        cached = work / ".flatpak-builder/downloads/cached-source"
        cached.parent.mkdir(parents=True)
        cached.write_text("cached")
        updated = self.manifest + "# changed locally\n"
        (self.root / f"packaging/flathub/{ci.APP_ID}.yml").write_text(updated)
        self.prepare()
        self.assertEqual(updated, (work / f"{ci.APP_ID}.yml").read_text())
        self.assertEqual("cached", cached.read_text())

    def test_builder_clone_checks_out_the_requested_immutable_revision(self):
        (self.builder / "justfile").write_text("newer recipes\n")
        git(self.builder, "add", "justfile")
        git(self.builder, "commit", "--quiet", "-m", "newer fixture")
        clone = self.state / "vorarbeiter"
        with contextlib.redirect_stdout(io.StringIO()):
            ci.ensure_builder(clone, str(self.builder), self.builder_revision)
        self.assertEqual(self.builder_revision, git(clone, "rev-parse", "HEAD"))
        self.assertEqual("prepare-env:\n    echo ready\n", (clone / "justfile").read_text())

    def test_modified_builder_recipes_fail_instead_of_claiming_to_use_the_pin(self):
        (self.builder / "justfile").write_text("locally modified recipes\n")
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(subprocess.CalledProcessError):
            ci.ensure_builder(self.builder, str(self.builder), self.builder_revision)

    def test_container_uses_the_pinned_image_and_upstream_sandbox_profile(self):
        command = ci.container_command("build", self.state, self.builder)
        self.assertRegex(ci.BUILDER_IMAGE, r"@sha256:[0-9a-f]{64}$")
        self.assertIn(ci.BUILDER_IMAGE, command)
        self.assertIn(f"seccomp={self.builder / 'flatpak.seccomp.json'}", command)
        self.assertIn("--cap-drop", command)
        self.assertIn("all", command)
        self.assertNotIn("--privileged", command)
        self.assertIn("BUILD_ARCH=x86_64", command)
        self.assertIn("/proc:/host/proc", command)
        self.assertIn(f"{self.state / 'flatpak'}:/home/flatbld/.local/share/flatpak:z", command)
        self.assertIn("GITHUB_TOKEN", command)
        self.assertFalse(any(item.startswith("GITHUB_TOKEN=") for item in command))

    def test_all_phases_call_upstream_recipes_instead_of_the_standalone_packager(self):
        expected = {
            "manifest": "validate-manifest", "download": "download-sources",
            "build": "build", "lint": "validate-build",
        }
        for phase, recipe in expected.items():
            with self.subTest(phase=phase):
                script = ci.container_command(phase, self.state, self.builder)[-1]
                self.assertIn(f"just -f .flathub.justfile {recipe}", script)
                self.assertIn("just -f .flathub.justfile prepare-env", script)
                self.assertNotIn("packaging/flatpak/build.py", script)
                self.assertNotIn(" upload ", script)
        build = ci.container_command("build", self.state, self.builder)[-1]
        self.assertIn(f"build {ci.APP_ID} {ci.LOCAL_REF} x86_64", build)
        self.assertIn("install-if-extra-data", build)
        bundle = ci.container_command("bundle", self.state, self.builder)[-1]
        self.assertIn(f"artifacts/{ci.APP_ID}.flatpak {ci.APP_ID} test", bundle)

    def test_failed_builds_keep_the_log_and_propagate_the_failure(self):
        log = Path(self.temporary.name) / "failure.log"
        command = [sys.executable, "-c", "import sys; print('compiler failed'); sys.exit(7)"]
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(subprocess.CalledProcessError) as failure:
            ci.run_logged(command, log)
        self.assertEqual(7, failure.exception.returncode)
        self.assertEqual("compiler failed\n", log.read_text())

    def test_cli_reports_unprepared_inputs_before_starting_a_container(self):
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--phase", "build",
                                 "--state-dir", str(self.state)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("Run --phase prepare first", result.stderr)


class FlathubWorkflowTest(unittest.TestCase):
    def test_workflow_is_manual_and_uses_the_same_local_runner(self):
        workflow = (ROOT / ".github/workflows/flathub.yml").read_text()
        triggers = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual("workflow_dispatch:", triggers.strip())
        self.assertIn("  contents: read\n", workflow)
        self.assertNotIn("contents: write", workflow)
        self.assertIn("persist-credentials: false", workflow)
        phases = [workflow.index(f"run_ci.py --phase {phase}") for phase in ("prepare", *ci.PHASES)]
        self.assertEqual(sorted(phases), phases)
        self.assertIn("steps.build.outcome == 'success'", workflow)
        self.assertIn("if: always()", workflow)
        self.assertIn("build/flathub-ci/work/artifacts/", workflow)
        self.assertIn("build/flathub-ci/work/.flatpak-builder", workflow)
        self.assertNotIn("continue-on-error", workflow)

    @unittest.skipUnless((ROOT / "build/flathub-ci/vorarbeiter/justfile").is_file(), "needs the cloned upstream builder")
    def test_pinned_upstream_build_preserves_the_flathub_offline_build_flags(self):
        builder = ROOT / "build/flathub-ci/vorarbeiter"
        self.assertEqual(ci.UPSTREAM_REVISION, git(builder, "rev-parse", "HEAD"))
        recipes = (builder / "justfile").read_text()
        for flag in ("--sandbox", "--disable-download", "--ccache", "--override-source-date-epoch 1321009871"):
            self.assertIn(flag, recipes)


if __name__ == "__main__":
    unittest.main()
