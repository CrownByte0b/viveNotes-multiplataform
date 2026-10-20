import importlib.util
import io
import json
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
