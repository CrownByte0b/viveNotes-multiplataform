#!/usr/bin/env python3
"""Contract tests for the Flathub source build: the manifest, its launcher and install step.

The manifest restates pins that live elsewhere (uv.lock, ByteInk's upstream/pins.properties, the
Gradle wrapper, the local package's JBR and permissions); these tests keep each copy equal to its
source. ByteInk's pins are read from ../byteink and skipped without that checkout.
Set VIVE_TEST_FLATHUB_APP to a built Flatpak directory to include the real sandbox contracts.
"""
import json
import os
import re
import subprocess
import sys
import tarfile
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
SANDBOX_APP = os.environ.get("VIVE_TEST_FLATHUB_APP")
sys.path.insert(0, str(HERE))
import install  # noqa: E402
import build_local  # noqa: E402


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
        commands = " ".join(jbr["config-opts"])
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
        sources = {source["dest"]: source for source in module("libink")["sources"]
                   if source["type"] in {"git", "archive"}}
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

    def test_native_modules_use_their_build_systems(self):
        jbr = module("jbr")
        self.assertEqual("autotools", jbr["buildsystem"])
        self.assertIn("images", jbr["make-args"])
        self.assertIn("CONF=linux-x86_64-server-release", jbr["make-args"])
        self.assertTrue(jbr["no-make-install"])
        self.assertNotIn("build-commands", jbr)
        self.assertEqual("cmake-ninja", module("libink")["buildsystem"])
        self.assertTrue(module("libink")["builddir"])
        self.assertNotIn("build-commands", module("libink"))

    def test_jbr_uses_real_compilers_with_its_own_ccache_support(self):
        jbr = module("jbr")
        for key, compiler in [("CC", "/usr/bin/gcc"), ("CXX", "/usr/bin/g++")]:
            self.assertEqual(compiler, jbr["build-options"]["env"][key])
        self.assertIn("--enable-ccache", jbr["config-opts"])

    def test_jbr_outer_make_is_serial_and_its_inner_build_receives_the_builder_job_limit(self):
        jbr = module("jbr")
        self.assertEqual("-j1", jbr["make-args"][0])
        job_arg = next(arg for arg in jbr["make-args"] if arg.startswith("JOBS="))
        result = subprocess.run(["make", "-j8", jbr["make-args"][0], "--no-print-directory", "-f", "-", job_arg],
                                input='all:\n\t$(if $(findstring --jobserver,$(MAKEFLAGS)),$(error OpenJDK rejects the outer jobserver))\n\t@printf "%s" "$(JOBS)"\n',
                                env={**os.environ, "FLATPAK_BUILDER_N_JOBS": "3"},
                                check=True, capture_output=True, text=True)
        self.assertEqual("3", result.stdout)

    def test_python_packages_install_offline_into_the_application_prefix(self):
        commands = " ".join(module("math-python")["build-commands"])
        self.assertIn("python3 -m pip install --prefix=${FLATPAK_DEST}", commands)
        self.assertIn("--no-index --no-deps --no-cache-dir", commands)
        self.assertNotIn("venv", commands)


@unittest.skipUnless((BYTEINK / "upstream/pins.properties").is_file(), "needs the ByteInk checkout")
class FlathubNativeSourcesTest(unittest.TestCase):
    def test_declared_ink_patch_series_matches_the_pinned_byteink_source(self):
        sources = module("libink")["sources"]
        revision = next(s["commit"] for s in sources if s.get("dest") == "byteink" and s["type"] == "git")
        declared = [HERE / path for s in sources if s["type"] == "patch" and s["dest"] == "ink"
                    for path in s.get("paths", [s.get("path")])]
        upstream = subprocess.check_output(
            ["git", "-C", str(BYTEINK), "ls-tree", "-r", "--name-only", revision, "native/patches"], text=True
        ).splitlines()
        self.assertEqual([Path(name).name for name in upstream], [path.name for path in declared])
        for name, path in zip(upstream, declared):
            with self.subTest(patch=path.name):
                expected = subprocess.check_output(["git", "-C", str(BYTEINK), "show", f"{revision}:{name}"])
                self.assertEqual(expected, path.read_bytes())

    def test_install_patch_applies_to_the_pinned_cmake_source(self):
        sources = module("libink")["sources"]
        revision = next(s["commit"] for s in sources if s.get("dest") == "byteink" and s["type"] == "git")
        patch = next(s for s in sources if s["type"] == "patch" and s["dest"] == "byteink")
        original = subprocess.check_output(
            ["git", "-C", str(BYTEINK), "show", f"{revision}:native/cmake/CMakeLists.txt"]
        )
        with tempfile.TemporaryDirectory() as folder:
            cmake = Path(folder) / "native/cmake/CMakeLists.txt"
            cmake.parent.mkdir(parents=True)
            cmake.write_bytes(original)
            with (HERE / patch["path"]).open("rb") as stream:
                subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-d", folder],
                               stdin=stream, check=True, capture_output=True)
            self.assertIn('install(TARGETS ink LIBRARY DESTINATION "${CMAKE_INSTALL_LIBDIR}")', cmake.read_text())

    @unittest.skipUnless((BYTEINK / "native/build/google-ink/.git").exists(), "needs the cached google/ink checkout")
    def test_declared_ink_patches_apply_in_order_to_the_pinned_sources(self):
        sources = module("libink")["sources"]
        revision = next(s["commit"] for s in sources if s.get("dest") == "ink" and s["type"] == "git")
        with tempfile.TemporaryDirectory() as folder:
            archive = Path(folder) / "ink.tar"
            subprocess.run(["git", "-C", str(BYTEINK / "native/build/google-ink"),
                            "archive", "-o", str(archive), revision], check=True, capture_output=True)
            with tarfile.open(archive) as packed:
                packed.extractall(Path(folder) / "ink", filter="data")
            for source in sources:
                if source["type"] == "patch" and source["dest"] == "ink":
                    for path in source.get("paths", [source.get("path")]):
                        with self.subTest(patch=path), (HERE / path).open("rb") as stream:
                            subprocess.run(["patch", "--batch", "--fuzz=0", "-p1", "-d", str(Path(folder) / "ink")],
                                           stdin=stream, check=True, capture_output=True)


class FlathubLocalBuildTest(unittest.TestCase):
    def test_relocated_manifest_keeps_patch_paths_and_destinations_resolvable(self):
        with tempfile.TemporaryDirectory() as folder:
            packs = {"vivenotes": Path(folder) / "app.tar.gz", "byteink": Path(folder) / "ink.tar.gz"}
            local = build_local.local_manifest(manifest(), packs, "2026-10-29")
            patches = [s for m in local["modules"] for s in m["sources"]
                       if isinstance(s, dict) and s.get("type") == "patch"]
            self.assertEqual(["byteink", "ink"], [s["dest"] for s in patches])
            for source in patches:
                for name in source.get("paths", [source.get("path")]):
                    path = Path(name)
                    self.assertTrue(path.is_absolute(), name)
                    self.assertTrue(path.is_file(), name)
            libink = next(m for m in local["modules"] if m["name"] == "libink")
            self.assertEqual("cmake-ninja", libink["buildsystem"])
            self.assertEqual("byteink/native/cmake", libink["subdir"])
            launcher = next(s for m in local["modules"] if m["name"] == "vivenotes"
                            for s in m["sources"] if isinstance(s, dict) and s.get("dest") == "packaging/flathub")
            self.assertEqual(str(HERE / "vivenotes"), launcher["path"])


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
        self.assertIn("-Dvivenotes.mathPython=/usr/bin/python3", args)
        self.assertIn("-Dbyteink.ink.library=/app/lib/libink.so", args)
        self.assertNotIn("-Dvivenotes.uv=/app/bin/uv", args)
        local = (ROOT / "packaging/flatpak/vivenotes").read_text()
        for option in re.findall(r"(--[a-z-]+=\S+)", local):
            self.assertIn(option, args)
        self.assertEqual(["/app/lib/*", "com.vivenotes.desktop.MainKt", "--debug", "$(literal)"], args[-4:])

    def test_manifest_installs_what_the_launcher_names(self):
        commands = " ".join(command for m in manifest()["modules"]
                            for stage in ["build-commands", "post-install"] for command in m.get(stage, []))
        self.assertIn("--output ${FLATPAK_DEST}/jbr", commands)
        self.assertIn("python3 -m pip install --prefix=${FLATPAK_DEST}", commands)
        self.assertEqual("cmake-ninja", module("libink")["buildsystem"])
        launcher = next(s for s in module("vivenotes")["sources"]
                        if isinstance(s, dict) and s.get("dest") == "packaging/flathub")
        self.assertEqual("vivenotes", launcher["path"])


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


@unittest.skipUnless(SANDBOX_APP, "set VIVE_TEST_FLATHUB_APP to a built Flatpak directory")
class FlathubSandboxTest(unittest.TestCase):
    def test_runtime_python_imports_the_locked_packages_from_the_application(self):
        probe = '''
import importlib.metadata, json, sympy, mpmath, antlr4
print(json.dumps({name: [importlib.metadata.version(name), module.__file__]
                  for name, module in [("sympy", sympy), ("mpmath", mpmath),
                                       ("antlr4-python3-runtime", antlr4)]}))
'''
        result = subprocess.run(["flatpak", "build", "--unshare=network", SANDBOX_APP,
                                 "/usr/bin/python3", "-E", "-s", "-c", probe],
                                check=True, capture_output=True, text=True)
        imported = json.loads(result.stdout)
        lock = tomllib.loads((ROOT / "shared/src/jvmMain/python/uv.lock").read_text())
        expected = {p["name"]: p["version"] for p in lock["package"] if "registry" in p["source"]}
        self.assertEqual(expected, {name: value[0] for name, value in imported.items()})
        for version, path in imported.values():
            self.assertTrue(path.startswith("/app/lib/python"), path)

    def test_built_jbr_provides_wayland_and_loads_the_cmake_installed_ink_library(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "NativeLibraryProbe.java"
            source.write_text('''
public class NativeLibraryProbe {
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.vendor.version", "").startsWith("JBR-"))
            throw new AssertionError("Expected the source-built JBR");
        Class.forName("sun.awt.wl.WLToolkit", false, ClassLoader.getSystemClassLoader());
        System.load("/app/lib/libink.so");
        System.out.println("FLATHUB_NATIVE_OK");
    }
}
''')
            subprocess.run(["javac", "--release", "25", "-d", folder, str(source)],
                           check=True, capture_output=True, text=True)
            result = subprocess.run(["flatpak", "build", "--unshare=network", f"--filesystem={folder}:ro",
                                     SANDBOX_APP, "/app/jbr/bin/java", "--enable-native-access=ALL-UNNAMED",
                                     "-cp", folder, "NativeLibraryProbe"],
                                    check=True, capture_output=True, text=True)
            self.assertEqual("FLATHUB_NATIVE_OK", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
