import importlib.util
import io
import json
import re
import shlex
import shutil
import struct
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("flatpak_build", HERE / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class PackagingTest(unittest.TestCase):
    def test_ci_ui_checks_have_a_large_exclusive_display_and_bounded_diagnostics(self):
        workflow = (HERE.parents[1] / ".github/workflows/flatpak.yml").read_text()
        checks = workflow.split("      - name: Run JVM tests and lint\n", 1)[1].split("      - ", 1)[0]
        self.assertRegex(checks, r"timeout-minutes: (?:[12][0-9]|30)\b")
        command = shlex.split(re.search(r"^        run: (.+)$", checks, re.M)[1])
        screen = shlex.split(command[command.index("-s") + 1])
        width, height, depth = map(int, screen[2].split("x"))
        self.assertGreaterEqual(width, 1800)
        self.assertGreaterEqual(height, 1100)
        self.assertEqual(24, depth)
        self.assertIn("--no-parallel", command)
        self.assertIn("--max-workers=1", command)
        self.assertIn("--console=plain", command)
        self.assertIn("--continue", command)
        self.assertTrue({"lint", ":shared:jvmTest", ":desktopApp:test"}.issubset(command))
        self.assertEqual(".github/scripts/ci-tests.init.gradle", command[command.index("--init-script") + 1])
        failure_upload = workflow.split("      - name: Upload failed test reports\n", 1)[1]
        self.assertIn("if: failure()", failure_upload)
        self.assertIn("shared/build/test-results/", failure_upload)
        self.assertIn("desktopApp/build/test-results/", failure_upload)

    def test_desktop_identity_and_scoped_permissions(self):
        manifest = json.loads((HERE / f"{build.APP_ID}.json").read_text())
        self.assertEqual(build.APP_ID, manifest["id"])
        self.assertEqual("26.08", manifest["runtime-version"])
        self.assertEqual({"--socket=wayland", "--socket=fallback-x11", "--share=ipc",
                          "--share=network", "--device=dri"}, set(manifest["finish-args"]))
        desktop = (HERE / f"{build.APP_ID}.desktop").read_text()
        self.assertIn(f"Icon={build.APP_ID}\n", desktop)
        self.assertIn(f"StartupWMClass={build.APP_ID}\n", desktop)
        # The app has no command-line file-opening support yet.
        self.assertIn("Exec=vivenotes\n", desktop)
        self.assertNotIn("MimeType=", desktop)
        metadata = ET.parse(HERE / f"{build.APP_ID}.metainfo.xml").getroot()
        self.assertEqual(build.APP_ID, metadata.findtext("id"))
        self.assertEqual(f"{build.APP_ID}.desktop", metadata.findtext("launchable"))
        self.assertTrue(metadata.findtext("project_license").startswith("LicenseRef-proprietary="))

    def test_flathub_listing_has_identity_licensing_branding_and_desktop_support(self):
        metadata = ET.parse(HERE / f"{build.APP_ID}.metainfo.xml").getroot()
        self.assertEqual("desktop-application", metadata.get("type"))
        self.assertLessEqual(len(metadata.findtext("summary")), 35)
        self.assertEqual("CC0-1.0", metadata.findtext("metadata_license"))
        self.assertEqual("CROWNBYTE LLC", metadata.findtext("developer/name"))
        self.assertEqual("net.vivenotes", metadata.find("developer").get("id"))
        self.assertEqual("oars-1.1", metadata.find("content_rating").get("type"))
        colors = metadata.findall("branding/color")
        self.assertEqual({"light", "dark"}, {c.get("scheme_preference") for c in colors})
        self.assertNotEqual(colors[0].text, colors[1].text)
        self.assertEqual("en", metadata.findtext("languages/lang"))
        self.assertEqual("540", metadata.findtext("requires/display_length"))
        self.assertEqual({"pointing", "keyboard", "touch"}, {c.text for c in metadata.findall("supports/control")})
        description = " ".join(metadata.find("description").itertext())
        self.assertIn("209 MB", description)
        self.assertIn("automatically on first run", description)
        self.assertIn("offline", description)
        desktop = (HERE / f"{build.APP_ID}.desktop").read_text()
        self.assertIn("Comment=" + metadata.findtext("summary") + "\n", desktop)
        self.assertIn("Categories=Office;\n", desktop)
        self.assertEqual({"only-arches": ["x86_64"]}, json.loads((HERE / "flathub.json").read_text()))

    def test_staged_metadata_contains_resolved_release_launcher_icon_and_architectures(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            build.stage_metadata(output, "1.2.3", "2026-10-20")
            metadata = output / f"payload/share/metainfo/{build.APP_ID}.metainfo.xml"
            text = metadata.read_text()
            self.assertNotIn("@VERSION@", text)
            self.assertNotIn("@DATE@", text)
            release = ET.parse(metadata).getroot().find("releases/release")
            self.assertEqual({"version": "1.2.3", "date": "2026-10-20"}, release.attrib)
            self.assertTrue(release.findtext("description/p"))
            icon = output / f"payload/share/icons/hicolor/512x512/apps/{build.APP_ID}.png"
            data = icon.read_bytes()
            self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
            self.assertEqual((512, 512), struct.unpack(">II", data[16:24]))
            self.assertEqual((HERE / f"{build.APP_ID}.png").read_bytes(), data)
            desktop = output / f"payload/share/applications/{build.APP_ID}.desktop"
            self.assertEqual((HERE / desktop.name).read_text(), desktop.read_text())
            self.assertEqual((HERE / "flathub.json").read_bytes(), (output / "flathub.json").read_bytes())
            self.assertFalse(list((output / "payload").rglob("*.in")))

    def test_staged_metainfo_and_desktop_pass_available_system_validators(self):
        validators = [shutil.which("appstreamcli"), shutil.which("desktop-file-validate")]
        if not all(validators):
            self.skipTest("AppStream and desktop-file validators are not installed")
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            build.stage_metadata(output, "1.2.3", "2026-10-20")
            for args in [
                [validators[0], "validate", "--no-net", str(output / f"payload/share/metainfo/{build.APP_ID}.metainfo.xml")],
                [validators[1], str(output / f"payload/share/applications/{build.APP_ID}.desktop")],
            ]:
                result = subprocess.run(args, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_screenshot_template_is_explicitly_pending_and_has_one_default(self):
        screenshots = ET.parse(HERE / "screenshots.xml.in").getroot()
        self.assertEqual("screenshots", screenshots.tag)
        defaults = screenshots.findall("screenshot[@type='default']")
        self.assertEqual(1, len(defaults))
        self.assertEqual("source", defaults[0].find("image").get("type"))
        self.assertEqual("@SCREENSHOT_URL@", defaults[0].findtext("image"))
        self.assertEqual("@SCREENSHOT_CAPTION@", defaults[0].findtext("caption"))

    def test_archives_are_rejected_when_checksum_does_not_match(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "bad.tar.gz"
            archive.write_bytes(b"unexpected download")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                build.verified_archive(archive, ("https://invalid.invalid/archive", "0" * 64), Path(folder))

    def test_archive_cannot_write_outside_staging(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                entry = tarfile.TarInfo("../../escape")
                entry.size = 1
                output.addfile(entry, io.BytesIO(b"x"))
            with self.assertRaises(tarfile.FilterError):
                build.unpack(archive, Path(folder) / "unpack")

    def test_dependency_notices_and_inventory_are_preserved_safely(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "lib").mkdir()
            with zipfile.ZipFile(root / "lib/example.jar", "w") as jar:
                jar.writestr("META-INF/LICENSE.txt", "Example license")
                jar.writestr("META-INF/NOTICE", "Example attribution")
                jar.writestr("../../LICENSE", "Must not escape")
                jar.writestr("Example.class", "Not a notice")
            build.jar_notices(root)
            notices = root / "share/licenses" / build.APP_ID / "dependencies"
            self.assertEqual("Example license", (notices / "example.jar/META-INF/LICENSE.txt").read_text())
            self.assertEqual("Example attribution", (notices / "example.jar/META-INF/NOTICE").read_text())
            self.assertIn("example.jar", (notices / "SHA256SUMS").read_text())
            self.assertFalse((root / "LICENSE").exists())

    def test_launcher_uses_bundled_java_production_profile_and_literal_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            java = root / "java"
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            java.chmod(0o755)
            launcher = root / "vivenotes"
            launcher.write_text((HERE / "vivenotes").read_text().replace("/app/jbr/bin/java", str(java)))
            result = subprocess.run(["sh", str(launcher), "--debug", "$(literal)"], capture_output=True, text=True, check=True)
            args = result.stdout.splitlines()
            self.assertIn("-Dvivenotes.profile=production", args)
            self.assertIn("-Dvivenotes.uv=/app/bin/uv", args)
            self.assertIn("--enable-native-access=ALL-UNNAMED", args)
            self.assertIn("--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED", args)
            self.assertIn("/app/lib/*", args)
            self.assertEqual(["--debug", "$(literal)"], args[-2:])


if __name__ == "__main__":
    unittest.main()
