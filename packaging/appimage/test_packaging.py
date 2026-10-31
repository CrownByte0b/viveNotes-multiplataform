import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shlex
import shutil
import struct
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location("appimage_build", HERE / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)
spec = importlib.util.spec_from_file_location("release_artifacts", ROOT / ".github/scripts/attach_release_artifacts.py")
release_artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_artifacts)
# 2023-11-14 22:13:20 UTC
EPOCH = 1700000000


def step(workflow, name):
    return workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - ", 1)[0]


class AppImagePackagingTest(unittest.TestCase):
    def test_workflow_runs_manually_and_on_version_tags_only(self):
        workflow = (ROOT / ".github/workflows/appimage.yml").read_text()
        triggers = workflow.split("\non:\n", 1)[1].split("\nconcurrency:", 1)[0]
        self.assertEqual("  workflow_dispatch:\n  push:\n    tags: ['v*']", triggers)

    def test_ci_ui_checks_have_a_large_exclusive_display_and_bounded_diagnostics(self):
        workflow = (ROOT / ".github/workflows/appimage.yml").read_text()
        checks = step(workflow, "Run JVM tests and lint")
        self.assertRegex(checks, r"timeout-minutes: (?:[12][0-9]|30)\b")
        command = shlex.split(re.search(r"^        run: (.+)$", checks, re.M)[1])
        width, height, depth = map(int, shlex.split(command[command.index("-s") + 1])[2].split("x"))
        self.assertGreaterEqual(width, 1800)
        self.assertGreaterEqual(height, 1100)
        self.assertEqual(24, depth)
        self.assertTrue({"--no-parallel", "--max-workers=1", "--console=plain", "--continue"}.issubset(command))
        self.assertTrue({"lint", ":shared:jvmTest", ":desktopApp:test"}.issubset(command))
        self.assertEqual(".github/scripts/ci-tests.init.gradle", command[command.index("--init-script") + 1])
        failure_upload = step(workflow, "Upload failed test reports")
        self.assertIn("if: failure()", failure_upload)
        self.assertIn("shared/build/test-results/", failure_upload)
        self.assertIn("desktopApp/build/test-results/", failure_upload)

    def test_tag_builds_are_versioned_smoke_tested_and_uploaded_for_the_release_draft(self):
        workflow = (ROOT / ".github/workflows/appimage.yml").read_text()
        tools = step(workflow, "Install packaging tools")
        for package in ("squashfs-tools", "fuse3", "appstream", "desktop-file-utils", "xvfb"):
            self.assertIn(package, tools)
        self.assertIn('build.py --version "${GITHUB_REF_NAME#v}"', step(workflow, "Build AppImage"))
        smoke = step(workflow, "Smoke test the AppImage")
        self.assertIn("xvfb-run -a python3 -B packaging/appimage/smoke_test.py", smoke)
        upload = step(workflow, "Upload AppImage and checksum")
        self.assertIn(("appimage.yml", re.search(r"name: (.+)", upload)[1]), release_artifacts.BUILDS)
        self.assertIn("if-no-files-found: error", upload)
        self.assertIn("desktopApp/build/appimage/*.AppImage\n", upload)
        self.assertIn("desktopApp/build/appimage/*.AppImage.sha256\n", upload)

    def test_runtime_is_a_checksum_pinned_dated_release(self):
        url, digest = build.RUNTIME
        self.assertTrue(url.startswith("https://github.com/AppImage/type2-runtime/releases/download/"))
        self.assertRegex(url.split("/")[-2], r"^[0-9]{8}$")
        self.assertNotIn("continuous", url)
        self.assertRegex(digest, r"^[0-9a-f]{64}$")

    def test_desktop_entry_is_the_flatpak_listing_with_the_apps_own_window_class(self):
        flatpak = (ROOT / "packaging/flatpak" / f"{build.APP_ID}.desktop").read_text().splitlines()
        entry = build.desktop_entry("1.2.3").splitlines()
        self.assertIn("StartupWMClass=vivenotes", entry)
        self.assertEqual("X-AppImage-Version=1.2.3", entry[-1])
        self.assertEqual([line for line in flatpak if not line.startswith("StartupWMClass=")],
                         [line for line in entry[:-1] if not line.startswith("StartupWMClass=")])

    def test_staged_appdir_has_one_root_entry_icons_and_resolved_metainfo(self):
        with tempfile.TemporaryDirectory() as folder:
            appdir = Path(folder)
            build.stage_metadata(appdir, "1.2.3", "2026-10-20")
            self.assertEqual([f"{build.APP_ID}.desktop"], [path.name for path in appdir.glob("*.desktop")])
            self.assertEqual(build.desktop_entry("1.2.3"), (appdir / f"{build.APP_ID}.desktop").read_text())
            self.assertEqual(build.desktop_entry("1.2.3"),
                             (appdir / f"usr/share/applications/{build.APP_ID}.desktop").read_text())
            self.assertEqual(f"{build.APP_ID}.png", os.readlink(appdir / ".DirIcon"))
            source = (ROOT / "packaging/flatpak" / f"{build.APP_ID}.png").read_bytes()
            for icon in (appdir / f"{build.APP_ID}.png", appdir / f"usr/share/icons/hicolor/512x512/apps/{build.APP_ID}.png"):
                data = icon.read_bytes()
                self.assertEqual(source, data)
                self.assertEqual((512, 512), struct.unpack(">II", data[16:24]))
            metainfo = appdir / f"usr/share/metainfo/{build.APP_ID}.metainfo.xml"
            release = ET.parse(metainfo).getroot().find("releases/release")
            self.assertEqual({"version": "1.2.3", "date": "2026-10-20"}, release.attrib)
            self.assertNotIn("@", metainfo.read_text().split("<releases>", 1)[1])

    def test_staged_metadata_passes_available_system_validators(self):
        validators = [shutil.which("appstreamcli"), shutil.which("desktop-file-validate")]
        if not all(validators):
            self.skipTest("AppStream and desktop-file validators are not installed")
        with tempfile.TemporaryDirectory() as folder:
            appdir = Path(folder)
            build.stage_metadata(appdir, "1.2.3", "2026-10-20")
            for args in [
                [validators[0], "validate", "--no-net", str(appdir / f"usr/share/metainfo/{build.APP_ID}.metainfo.xml")],
                [validators[1], str(appdir / f"{build.APP_ID}.desktop")],
            ]:
                result = subprocess.run(args, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_launcher_runs_the_bundled_java_beside_it_with_literal_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            appdir = Path(folder) / "mount point"
            java = appdir / "usr/jbr/bin/java"
            java.parent.mkdir(parents=True)
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            java.chmod(0o755)
            shutil.copy2(HERE / "AppRun", appdir / "AppRun")
            # Desktop integrations may start it through a symlink.
            link = Path(folder) / "vivenotes"
            link.symlink_to(appdir / "AppRun")
            result = subprocess.run([str(link), "--debug", "$(literal)", "two words"],
                                    capture_output=True, text=True, check=True, cwd=folder)
            args = result.stdout.splitlines()
            self.assertIn("-Dvivenotes.profile=production", args)
            self.assertIn(f"-Dvivenotes.uv={appdir}/usr/bin/uv", args)
            self.assertEqual(f"{appdir}/usr/lib/*", args[args.index("-cp") + 1])
            self.assertEqual("com.vivenotes.desktop.MainKt", args[args.index("-cp") + 2])
            self.assertIn("--enable-native-access=ALL-UNNAMED", args)
            self.assertIn("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", args)
            self.assertIn("--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED", args)
            self.assertEqual(["--debug", "$(literal)", "two words"], args[-3:])

    def runtime(self, folder, magic=b"AI\x02"):
        runtime = Path(folder) / "runtime"
        runtime.write_bytes(b"\x7fELF\x02\x01\x01\x00" + magic + b"\x00" * 501)
        return runtime

    def appdir(self, folder):
        appdir = Path(folder) / "AppDir"
        (appdir / "usr/lib").mkdir(parents=True)
        (appdir / "usr/lib/app.jar").write_bytes(b"jar")
        shutil.copy2(HERE / "AppRun", appdir / "AppRun")
        (appdir / ".DirIcon").symlink_to("AppRun")
        try:
            os.setxattr(appdir / "usr/lib/app.jar", "user.build-host", b"label")
        except OSError:
            pass
        return appdir

    def test_image_is_the_runtime_then_a_root_owned_squashfs_with_a_checksum(self):
        if not shutil.which("mksquashfs"):
            self.skipTest("squashfs-tools is not installed")
        with tempfile.TemporaryDirectory() as folder:
            runtime = self.runtime(folder)
            image = Path(folder) / "ViveNotes-1.2.3-x86_64.AppImage"
            checksum = build.assemble(self.appdir(folder), runtime, image, EPOCH)
            data = image.read_bytes()
            self.assertEqual(runtime.read_bytes(), data[:512])
            self.assertEqual(b"hsqs", data[512:516])
            self.assertTrue(os.access(image, os.X_OK))
            self.assertEqual(f"{hashlib.sha256(data).hexdigest()}  {image.name}\n", checksum.read_text())
            self.assertEqual([image.name, checksum.name], sorted(path.name for path in Path(folder).glob("ViveNotes-*")))
            if not shutil.which("unsquashfs"):
                return
            listing = subprocess.run(["unsquashfs", "-lln", "-o", "512", image], capture_output=True, text=True,
                                     check=True, env=os.environ | {"TZ": "UTC"}).stdout.splitlines()
            self.assertEqual(6, len(listing), listing)
            for line in listing:
                self.assertEqual("0/0", line.split()[1], line)
                self.assertIn("2023-11-14 22:13", line)
            self.assertTrue(any(line.startswith("-rwxr-xr-x") and line.endswith("squashfs-root/AppRun") for line in listing))
            self.assertTrue(any(line.endswith("squashfs-root/.DirIcon -> AppRun") for line in listing))
            details = subprocess.run(["unsquashfs", "-s", "-o", "512", image], capture_output=True, text=True,
                                     check=True).stdout
            self.assertIn("Compression zstd", details)
            self.assertIn("Xattrs are not stored", details)

    def test_runtime_without_appimage_magic_builds_nothing(self):
        with tempfile.TemporaryDirectory() as folder:
            image = Path(folder) / "ViveNotes-1.2.3-x86_64.AppImage"
            with self.assertRaisesRegex(ValueError, "type 2 AppImage runtime"):
                build.assemble(self.appdir(folder), self.runtime(folder, magic=b"\x00\x00\x00"), image, EPOCH)
            self.assertEqual([], list(Path(folder).glob("ViveNotes-*")))

    def test_runtime_download_is_rejected_when_checksum_does_not_match(self):
        with tempfile.TemporaryDirectory() as folder:
            runtime = self.runtime(folder)
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                build.flatpak.verified_archive(runtime, (build.RUNTIME[0], "0" * 64), Path(folder))

    def test_licence_notices_cover_the_app_jars_uv_fonts_models_and_runtime(self):
        with tempfile.TemporaryDirectory() as folder:
            usr = Path(folder) / "usr"
            (usr / "lib").mkdir(parents=True)
            with zipfile.ZipFile(usr / "lib/example.jar", "w") as jar:
                jar.writestr("META-INF/LICENSE.txt", "Example license")
            build.stage_licenses(usr)
            licenses = usr / "share/licenses" / build.APP_ID
            self.assertEqual((ROOT / "LICENSE.md").read_bytes(), (licenses / "LICENSE.md").read_bytes())
            self.assertTrue((licenses / "AI-NOTICE.md").is_file())
            for name in os.listdir(ROOT / "LICENSES"):
                self.assertTrue((licenses / "third-party" / name).is_file(), name)
            self.assertTrue((licenses / "fonts-and-uv/uv-MIT.txt").is_file())
            self.assertEqual("Example license", (licenses / "dependencies/example.jar/META-INF/LICENSE.txt").read_text())
            self.assertIn("example.jar", (licenses / "dependencies/SHA256SUMS").read_text())
            notice = (licenses / "appimage-runtime/AppImage-runtime-MIT.txt").read_text()
            self.assertTrue(notice.startswith("MIT License"))
            for library in ("musl libc", "libfuse", "squashfuse", "libzstd", "zlib"):
                self.assertIn(library, notice)


if __name__ == "__main__":
    unittest.main()
