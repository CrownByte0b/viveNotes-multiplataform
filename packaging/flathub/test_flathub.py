#!/usr/bin/env python3
"""Contract tests for the Flathub source build: the manifest, its launcher and install step.

The manifest restates pins that live elsewhere (uv.lock, ByteInk's upstream/pins.properties, the
Gradle wrapper, the local package's JBR and permissions); these tests keep each copy equal to its
source. ByteInk's pins are read from ../byteink and skipped without that checkout.
"""
import os
import re
import subprocess
import sys
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_ID = "net.vivenotes.ViveNotes"
BYTEINK = Path(os.environ.get("VIVE_BYTEINK_SOURCE", ROOT.parent / "byteink"))
sys.path.insert(0, str(HERE))
import install  # noqa: E402


def manifest():
    return yaml.safe_load((HERE / f"{APP_ID}.yml").read_text())


def module(name):
    return next(m for m in manifest()["modules"] if m["name"] == name)


class FlathubManifestTest(unittest.TestCase):
    def test_identity_and_permissions_match_the_local_package(self):
        local = __import__("json").loads((ROOT / "packaging/flatpak" / f"{APP_ID}.json").read_text())
        flathub = manifest()
        for key in ["id", "runtime", "runtime-version", "command", "finish-args"]:
            self.assertEqual(local[key], flathub[key], key)

    def test_math_wheels_are_every_package_uv_lock_pins(self):
        lock = tomllib.loads((ROOT / "shared/src/jvmMain/python/uv.lock").read_text())
        locked = {wheel["url"]: wheel["hash"].removeprefix("sha256:")
                  for package in lock["package"] if "registry" in package["source"]
                  for wheel in package["wheels"]}
        self.assertEqual(locked, {wheel["url"]: wheel["sha256"] for wheel in module("math-python")["sources"]})

    def test_the_jbr_is_the_release_the_local_package_ships(self):
        local = (ROOT / "packaging/flatpak/build.py").read_text()
        release = re.search(r"jbr-(\d+(?:\.\d+)*)-linux-x64-b(\d+\.\d+)\.tar\.gz", local)
        jbr = module("jbr")
        self.assertIn(f"jbr-release-{release[1]}b{release[2]}.tar.gz", jbr["sources"][0]["url"])
        commands = " ".join(jbr["build-commands"])
        self.assertIn(f"--with-version-opt=b{release[2]}", commands)
        self.assertIn(f"--with-vendor-version-string=JBR-{release[1]}+1-{release[2]}-nomod", commands)

    def test_gradle_is_the_wrappers_distribution(self):
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        url = re.search(r"^distributionUrl=(.+)$", wrapper, re.M)[1].replace("\\:", ":")
        digest = re.search(r"^distributionSha256Sum=(.+)$", wrapper, re.M)[1]
        gradle = next(s for s in module("vivenotes")["sources"] if isinstance(s, dict) and s.get("dest") == "gradle-dist")
        self.assertEqual((url, digest), (gradle["url"], gradle["sha256"]))

    @unittest.skipUnless((BYTEINK / "upstream/pins.properties").is_file(), "needs the ByteInk checkout")
    def test_ink_sources_are_byteinks_pins(self):
        pins = dict(re.findall(r"^([\w.]+)=(.*)$", (BYTEINK / "upstream/pins.properties").read_text(), re.M))
        sources = {source["dest"]: source for source in module("libink")["sources"]}
        self.assertEqual((pins["google.ink.repository"], pins["google.ink.commit"]),
                         (sources["ink"]["url"], sources["ink"]["commit"]))
        self.assertEqual((pins["cmake.libtess2.repository"], pins["cmake.libtess2.commit"]),
                         (sources["libtess2"]["url"], sources["libtess2"]["commit"]))
        for name, dest in [("abseil", "abseil-cpp"), ("protobuf", "protobuf"), ("zlib", "zlib")]:
            self.assertEqual((pins[f"cmake.{name}.url"], pins[f"cmake.{name}.sha256"]),
                             (sources[dest]["url"], sources[dest]["sha256"]))

    def test_the_offline_build_reads_only_the_listed_maven_sources(self):
        app = module("vivenotes")
        self.assertIn("maven-sources.json", app["sources"])
        repository = app["build-options"]["env"]["VIVE_OFFLINE_REPOSITORY"]
        self.assertTrue(repository.endswith("/offline-repository"))
        listed = __import__("json").loads((HERE / "maven-sources.json").read_text())
        self.assertTrue(listed)
        self.assertTrue(all(entry["dest"].startswith("offline-repository/") and entry["sha256"] for entry in listed))
        gradle = " ".join(app["build-commands"])
        self.assertNotIn("Secret", gradle)
        self.assertNotIn("SECRET", gradle)
        for flag in ["--offline", "--init-script packaging/flathub/offline-repository.init.gradle.kts",
                     "-Pvive.googleDesktopClientId=",
                     "-PbyteinkBundledPlatforms=linux-x86_64", "-PbyteinkLinuxLibrary=/app/lib/libink.so"]:
            self.assertIn(flag, gradle)


class FlathubLauncherTest(unittest.TestCase):
    def test_launcher_runs_the_built_jbr_library_and_python_with_literal_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            java = Path(folder) / "java"
            java.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            java.chmod(0o755)
            launcher = Path(folder) / "vivenotes"
            launcher.write_text((HERE / "vivenotes").read_text().replace("/app/jbr/bin/java", str(java)))
            args = subprocess.run(["sh", str(launcher), "--debug", "$(literal)"],
                                  capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertIn("-Dvivenotes.profile=production", args)
        self.assertIn("-Dvivenotes.mathPython=/app/lib/vivenotes/math-python/bin/python", args)
        self.assertIn("-Dbyteink.ink.library=/app/lib/libink.so", args)
        self.assertNotIn("-Dvivenotes.uv=/app/bin/uv", args)
        local = (ROOT / "packaging/flatpak/vivenotes").read_text()
        for option in re.findall(r"(--[a-z-]+=\S+)", local):
            self.assertIn(option, args)
        self.assertEqual(["/app/lib/*", "com.vivenotes.desktop.MainKt", "--debug", "$(literal)"], args[-4:])

    def test_manifest_installs_what_the_launcher_names(self):
        commands = " ".join(command for m in manifest()["modules"] for command in m["build-commands"])
        self.assertIn("--output ${FLATPAK_DEST}/jbr", commands)
        self.assertIn("${FLATPAK_DEST}/lib/libink.so", commands)
        self.assertIn("python3 -m venv ${FLATPAK_DEST}/lib/vivenotes/math-python", commands)


class FlathubInstallTest(unittest.TestCase):
    def test_install_lays_out_jars_launcher_resolved_metadata_icon_and_notices(self):
        with tempfile.TemporaryDirectory() as folder:
            root, destination = Path(folder) / "source", Path(folder) / "app"
            staged = root / "desktopApp/build/flatpak/payload/lib"
            staged.mkdir(parents=True)
            with zipfile.ZipFile(staged / "0123-app.jar", "w") as jar:
                jar.writestr("META-INF/LICENSE.txt", "licence")
            (root / "shared/src/jvmMain/resources/ai").mkdir(parents=True)
            (root / "shared/src/jvmMain/resources/ai/NOTICE.md").write_text("ai")
            (root / "LICENSE.md").write_text("source first")

            install.install(root, destination, "1.2.3", "2026-10-25")

            self.assertTrue((destination / "lib/0123-app.jar").is_file())
            self.assertTrue(os.access(destination / "bin/vivenotes", os.X_OK))
            release = ET.parse(destination / f"share/metainfo/{APP_ID}.metainfo.xml").find("releases/release")
            self.assertEqual({"version": "1.2.3", "date": "2026-10-25"}, release.attrib)
            self.assertNotIn("@", (destination / f"share/applications/{APP_ID}.desktop").read_text())
            self.assertTrue((destination / f"share/icons/hicolor/512x512/apps/{APP_ID}.png").is_file())
            licenses = destination / "share/licenses" / APP_ID
            self.assertEqual("source first", (licenses / "LICENSE.md").read_text())
            self.assertEqual("ai", (licenses / "AI-NOTICE.md").read_text())
            self.assertEqual(sorted(install.FONT_LICENSES), sorted(p.name for p in (licenses / "fonts").iterdir()))
            self.assertEqual("licence", (licenses / "dependencies/0123-app.jar/META-INF/LICENSE.txt").read_text())


if __name__ == "__main__":
    unittest.main()
