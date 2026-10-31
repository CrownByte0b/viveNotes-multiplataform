import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
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
        self.assertIn("REPO=https://github.com/flathub/flathub", command)
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

    def test_custom_apparmor_profile_is_selected_without_enabling_a_privileged_container(self):
        for phase in ci.PHASES:
            with self.subTest(phase=phase):
                command = ci.container_command(phase, self.state, self.builder, ci.APPARMOR_PROFILE)
                self.assertIn(f"apparmor={ci.APPARMOR_PROFILE}", command)
                self.assertNotIn("apparmor=unconfined", command)
                self.assertNotIn("--privileged", command)

    def test_sandbox_probe_checks_user_and_network_namespaces_before_building(self):
        command = ci.container_command("sandbox", self.state, self.builder)
        for flag in ("--unshare-user", "--unshare-net", "--unshare-pid"):
            self.assertIn(flag, command[-1])
        self.assertIn("127.0.0.1", command[-1])
        self.assertNotIn("prepare-env", command[-1])
        self.assertEqual("sandbox", ci.PHASES[0])

    def test_every_phase_hands_the_builders_directories_back_when_it_ends(self):
        command = ci.container_command("build", self.state, self.builder)
        self.assertIn(f"{self.state / 'work'}:{ci.WORK_MOUNT}:z", command)
        self.assertIn(f"{self.state / 'flatpak'}:{ci.FLATPAK_MOUNT}:z", command)
        for phase in ci.PHASES:
            with self.subTest(phase=phase):
                trap = ci.container_command(phase, self.state, self.builder)[-1].splitlines()[0]
                self.assertTrue(trap.startswith("trap ") and trap.endswith(" EXIT"), trap)
                for mount in (ci.WORK_MOUNT, ci.FLATPAK_MOUNT):
                    self.assertIn(mount, trap)

    def test_handback_opens_the_builders_directories_and_keeps_a_failed_recipes_status(self):
        work, flatpak = Path(self.temporary.name) / "work", Path(self.temporary.name) / "flatpak"
        app = work / "builddir/files/app"
        directories = (work, work / "builddir", work / "builddir/files", flatpak, flatpak / "repo/objects")
        script = "\n".join([
            f"trap {shlex.quote(ci.handback((work, flatpak)))} EXIT",
            shlex.join(["mkdir", "-p", str(app.parent), str(flatpak / "repo/objects")]),
            shlex.join(["touch", str(app)]),
            shlex.join(["chmod", "0444", str(app)]),
            shlex.join(["chmod", "0755", *map(str, directories)]),
            "exit 7",
        ])
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script], capture_output=True, text=True)
        self.assertEqual(7, result.returncode, result.stderr)
        for directory in directories:
            self.assertEqual(0o777, stat.S_IMODE(directory.stat().st_mode), directory)
        # Only directories open up; deleting a file needs its directory, not the file, writable.
        self.assertEqual(0o444, stat.S_IMODE(app.stat().st_mode))

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

    def test_hosted_builder_loads_a_named_userns_profile_before_any_container_runs(self):
        workflow = (ROOT / ".github/workflows/flathub.yml").read_text()
        profile = (ROOT / ".github/flathub-ci.apparmor").read_text()
        self.assertIn(f"profile {ci.APPARMOR_PROFILE} flags=(unconfined)", profile)
        self.assertIn("  userns,", profile)
        self.assertIn(f"VIVE_FLATHUB_APPARMOR_PROFILE: {ci.APPARMOR_PROFILE}", workflow)
        load = workflow.index("sudo apparmor_parser --replace .github/flathub-ci.apparmor")
        probe = workflow.index("run_ci.py --phase sandbox")
        download = workflow.index("run_ci.py --phase download")
        self.assertLess(load, probe)
        self.assertLess(probe, download)
        self.assertNotIn("sysctl", workflow)

    @unittest.skipUnless((ROOT / "build/flathub-ci/vorarbeiter/justfile").is_file(), "needs the cloned upstream builder")
    def test_pinned_upstream_build_preserves_the_flathub_offline_build_flags(self):
        builder = ROOT / "build/flathub-ci/vorarbeiter"
        self.assertEqual(ci.UPSTREAM_REVISION, git(builder, "rev-parse", "HEAD"))
        recipes = (builder / "justfile").read_text()
        for flag in ("--sandbox", "--disable-download", "--ccache", "--override-source-date-epoch 1321009871"):
            self.assertIn(flag, recipes)


@unittest.skipUnless(os.environ.get("VIVE_TEST_FLATHUB_SANDBOX") == "1", "set VIVE_TEST_FLATHUB_SANDBOX=1 for Docker checks")
class FlathubSandboxTest(unittest.TestCase):
    def test_real_builder_creates_an_isolated_network_and_working_loopback(self):
        profile = os.environ.get("VIVE_FLATHUB_APPARMOR_PROFILE", "unconfined")
        command = ci.container_command("sandbox", ROOT / "build/flathub-ci",
                                       ROOT / "build/flathub-ci/vorarbeiter", profile)
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("FLATHUB_SANDBOX_OK", result.stdout)


@unittest.skipUnless(os.environ.get("VIVE_TEST_FLATHUB_SANDBOX") == "1", "set VIVE_TEST_FLATHUB_SANDBOX=1 for Docker checks")
class FlathubOwnershipTest(unittest.TestCase):
    def test_the_invoking_user_can_delete_what_the_builder_leaves(self):
        temporary = Path(tempfile.mkdtemp(prefix="vive-flathub-owner-"))
        work = temporary / "work"
        work.mkdir()
        work.chmod(0o777)
        docker = ["docker", "run", "--rm", "--init", "--cap-drop", "all", "--entrypoint=",
                  "--volume", f"{work}:{ci.WORK_MOUNT}:z", "--workdir", ci.WORK_MOUNT, ci.BUILDER_IMAGE,
                  "/bin/bash", "-euo", "pipefail", "-c"]
        # Cleanups run last first: the builder removes its own files whatever happens, then the rest goes.
        self.addCleanup(shutil.rmtree, temporary, ignore_errors=True)
        self.addCleanup(subprocess.run, [*docker, "rm -rf builddir"], capture_output=True)
        trap = ci.container_command("manifest", temporary, temporary)[-1].splitlines()[0]
        script = "\n".join([trap, 'test "$(id -u)" != 0', "mkdir -p builddir/files/app",
                            "chmod 0755 builddir builddir/files builddir/files/app",
                            "touch builddir/files/app/bin", "exit 3"])
        result = subprocess.run([*docker, script], capture_output=True, text=True, timeout=120)
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        shutil.rmtree(work / "builddir")


@unittest.skipUnless(os.environ.get("VIVE_TEST_FLATHUB_REPOSITORY") == "1", "needs a completed Flathub source build")
class FlathubRepositoryTest(unittest.TestCase):
    def test_real_repository_passes_the_submission_linter(self):
        profile = os.environ.get("VIVE_FLATHUB_APPARMOR_PROFILE", "unconfined")
        command = ci.container_command("lint", ROOT / "build/flathub-ci",
                                       ROOT / "build/flathub-ci/vorarbeiter", profile)
        result = subprocess.run(command, capture_output=True, text=True, timeout=180)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
