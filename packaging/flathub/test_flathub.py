#!/usr/bin/env python3
"""Contract tests for the Flathub source build: the manifest, its launcher and install step.

The manifest restates pins that live elsewhere (uv.lock, ByteInk's upstream/pins.properties, the
Gradle wrapper, the local package's JBR and permissions); these tests keep each copy equal to its
source. ByteInk's pins are read from ../byteink and skipped without that checkout.
Set VIVE_TEST_FLATHUB_APP to a built Flatpak directory to include the real sandbox contracts.
The OneNote converter's pins are checked against ../converter/vive-converter when it exists.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_ID = "net.vivenotes.ViveNotes"
BYTEINK = Path(os.environ.get("VIVE_BYTEINK_SOURCE", ROOT.parent / "byteink"))
CONVERTER = Path(os.environ.get("VIVE_CONVERTER_SOURCE", ROOT.parent / "converter/vive-converter"))
# Rust's names for the Linux targets the converter publishes a library for, by Flatpak architecture.
RUST_TARGETS = {"x86_64": "x86_64-unknown-linux-gnu", "aarch64": "aarch64-unknown-linux-gnu"}
SANDBOX_APP = os.environ.get("VIVE_TEST_FLATHUB_APP")
sys.path.insert(0, str(HERE))
import install  # noqa: E402
import build_local  # noqa: E402
# By path: install.py puts packaging/flatpak, with its own smoke_test.py, first on sys.path.
_smoke = importlib.util.spec_from_file_location("flathub_smoke_test", HERE / "smoke_test.py")
smoke_test = importlib.util.module_from_spec(_smoke)
_smoke.loader.exec_module(smoke_test)


def manifest():
    return yaml.safe_load((HERE / f"{APP_ID}.yml").read_text())


def module(name):
    return next(m for m in manifest()["modules"] if m["name"] == name)


def architectures():
    return json.loads((HERE / "flathub.json").read_text())["only-arches"]


def git_source(module_name, dest=None):
    return next(s for s in module(module_name)["sources"]
                if isinstance(s, dict) and s["type"] == "git" and s.get("dest") == dest)


def gradle_command():
    return next(c for c in module("vivenotes")["build-commands"] if ":desktopApp:stageFlatpak" in c)


def gradle_flag(name):
    return next(word.split("=", 1)[1] for word in gradle_command().split() if word.startswith(f"-P{name}="))


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

    @unittest.skipUnless((BYTEINK / "gradle/libs.versions.toml").is_file(), "needs the ByteInk checkout")
    def test_offline_sources_include_the_app_and_byteink_compose_build_plugins(self):
        versions = {tomllib.loads((repository / "gradle/libs.versions.toml").read_text())
                    ["versions"]["composeMultiplatform"] for repository in (ROOT, BYTEINK)}
        sources = json.loads((HERE / "maven-sources.json").read_text())
        for version in versions:
            with self.subTest(version=version):
                destination = f"offline-repository/org/jetbrains/compose/compose-gradle-plugin/{version}"
                names = {entry["dest-filename"] for entry in sources if entry["dest"] == destination}
                expected = {f"compose-gradle-plugin-{version}.{extension}" for extension in ("jar", "pom", "module")}
                self.assertTrue(expected <= names, f"Missing Compose plugin {version} files: {sorted(expected - names)}")

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
                     "-Pvive.googleDesktopClientId=", "-PbyteinkBundledPlatforms=${BYTEINK_PLATFORM}",
                     "-PbyteinkLinuxLibrary=/app/lib/libink.so", "-PbyteinkLinuxArm64Library=/app/lib/libink.so"]:
            self.assertIn(flag, gradle)

    def test_native_modules_use_their_build_systems(self):
        jbr = module("jbr")
        self.assertEqual("autotools", jbr["buildsystem"])
        self.assertIn("images", jbr["make-args"])
        self.assertIn("CONF=release", jbr["make-args"])
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

    def test_jbr_builds_into_the_same_configuration_on_every_architecture(self):
        jbr = module("jbr")
        self.assertIn("--with-conf-name=release", jbr["config-opts"])
        self.assertNotIn("--openjdk-target", " ".join(jbr["config-opts"]))
        for command in jbr["post-install"]:
            self.assertNotRegex(command, r"x86_64|aarch64|x64|arm64")
        self.assertIn("build/release/images/jdk/bin/jlink", jbr["post-install"][0])
        self.assertIn("--module-path build/release/images/jmods", jbr["post-install"][0])

    def test_python_packages_install_offline_into_the_application_prefix(self):
        commands = " ".join(module("math-python")["build-commands"])
        self.assertIn("python3 -m pip install --prefix=${FLATPAK_DEST}", commands)
        self.assertIn("--no-index --no-deps --no-cache-dir", commands)
        self.assertNotIn("venv", commands)


@unittest.skipUnless((BYTEINK / "upstream/pins.properties").is_file(), "needs the ByteInk checkout")
class FlathubByteInkTest(unittest.TestCase):
    def test_both_byteink_sources_are_one_release_tag(self):
        native, composite = git_source("libink", "byteink"), git_source("vivenotes", "byteink")
        self.assertEqual(native, composite)
        self.assertRegex(native["tag"], r"^v\d+\.\d+\.\d+$")
        self.assertRegex(native["commit"], r"^[0-9a-f]{40}$")

    @unittest.skipUnless((BYTEINK / ".git").exists(), "needs the ByteInk checkout")
    def test_byteink_tag_names_the_pinned_commit(self):
        source = git_source("libink", "byteink")
        tagged = subprocess.check_output(["git", "-C", str(BYTEINK), "rev-parse", f"{source['tag']}^{{commit}}"], text=True)
        self.assertEqual(source["commit"], tagged.strip())

    def test_each_architecture_bundles_its_own_byteink_platform(self):
        options = module("vivenotes")["build-options"]
        platforms = {arch: values["env"]["BYTEINK_PLATFORM"] for arch, values in options["arch"].items()}
        self.assertEqual({"x86_64": "linux-x86_64", "aarch64": "linux-arm64"}, platforms)
        self.assertEqual(set(architectures()), set(platforms))
        self.assertNotIn("BYTEINK_PLATFORM", options["env"])
        self.assertEqual("${BYTEINK_PLATFORM}", gradle_flag("byteinkBundledPlatforms"))
        # Whichever platform is bundled reads the library the libink module installs.
        self.assertEqual("/app/lib/libink.so", gradle_flag("byteinkLinuxLibrary"))
        self.assertEqual("/app/lib/libink.so", gradle_flag("byteinkLinuxArm64Library"))

    @unittest.skipUnless((BYTEINK / ".git").exists(), "needs the ByteInk checkout")
    def test_byteink_platform_names_and_library_properties_exist_at_the_pinned_commit(self):
        revision = git_source("libink", "byteink")["commit"]
        libraries = subprocess.check_output(["git", "-C", str(BYTEINK), "show",
            f"{revision}:build-logic/src/main/kotlin/com/vivenotes/byteink/build/NativeLibraries.kt"], text=True)
        declared = dict(re.findall(r'^\s+LINUX_\w+\("([\w-]+)", "libink.so", "(\w+)"', libraries, re.M))
        self.assertEqual({"linux-x86_64": "byteinkLinuxLibrary", "linux-arm64": "byteinkLinuxArm64Library"}, declared)
        options = module("vivenotes")["build-options"]["arch"]
        for arch, values in options.items():
            with self.subTest(arch=arch):
                self.assertIn(values["env"]["BYTEINK_PLATFORM"], declared)
                self.assertEqual("/app/lib/libink.so", gradle_flag(declared[values["env"]["BYTEINK_PLATFORM"]]))


class FlathubConverterTest(unittest.TestCase):
    def converter(self):
        return git_source("vivenotes", gradle_flag("viveConverterDir"))

    def libraries(self):
        return [s for s in module("vivenotes")["sources"] if isinstance(s, dict) and s["type"] == "archive"
                and "vive-converter" in s["url"]]

    def test_gradle_packages_the_released_library_with_the_tagged_sources_licences(self):
        directory = Path(gradle_flag("viveConverterDir"))
        self.assertEqual("https://github.com/CrownByte0b/vive-converter.git", self.converter()["url"])
        # The converter's build expects the parser checkout beside it (its Cargo.toml, its licence).
        parser = git_source("vivenotes", str(directory.parent / "onenote.rs"))
        self.assertEqual("https://github.com/msiemens/onenote.rs.git", parser["url"])
        library = Path(gradle_flag("viveConverterLibrary"))
        self.assertEqual("libvive_converter.so", library.name)
        self.assertEqual({str(library.parent)}, {s["dest"] for s in self.libraries()})

    def test_every_architecture_gets_exactly_its_own_release_library(self):
        version = self.converter()["tag"].removeprefix("v")
        release = f"https://github.com/CrownByte0b/vive-converter/releases/download/v{version}/"
        found = {}
        for source in self.libraries():
            with self.subTest(url=source["url"]):
                self.assertEqual(1, len(source["only-arches"]))
                self.assertEqual(0, source["strip-components"])
                self.assertRegex(source["sha256"], r"^[0-9a-f]{64}$")
                found[source["only-arches"][0]] = source["url"]
        self.assertEqual({arch: f"{release}vive-converter-{version}-{RUST_TARGETS[arch]}.zip"
                          for arch in architectures()}, found)

    def test_parser_is_the_revision_the_desktop_ci_builds_the_converter_with(self):
        action = (ROOT / ".github/actions/one-note-converter/action.yml").read_text()
        parser_ref = re.search(r"repository: msiemens/onenote\.rs\n\s+ref: ([0-9a-f]{40})", action)[1]
        parser = git_source("vivenotes", str(Path(gradle_flag("viveConverterDir")).parent / "onenote.rs"))
        self.assertEqual(parser_ref, parser["commit"])

    @unittest.skipUnless((CONVERTER / ".git").exists(), "needs the vive-converter checkout")
    def test_converter_tag_names_the_pinned_commit_and_its_version(self):
        source = self.converter()
        tagged = subprocess.check_output(["git", "-C", str(CONVERTER), "rev-parse", f"{source['tag']}^{{commit}}"], text=True)
        self.assertEqual(source["commit"], tagged.strip())
        cargo = tomllib.loads(subprocess.check_output(
            ["git", "-C", str(CONVERTER), "show", f"{source['commit']}:Cargo.toml"], text=True))
        self.assertEqual(source["tag"].removeprefix("v"), cargo["package"]["version"])


class FlathubApplicationSourceTest(unittest.TestCase):
    def test_application_is_a_release_tag_of_this_repository(self):
        source = git_source("vivenotes")
        self.assertEqual("https://github.com/CrownByte0b/viveNotes-multiplataform.git", source["url"])
        tagged = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--verify", "--quiet", f"{source['tag']}^{{commit}}"],
                                capture_output=True, text=True)
        if tagged.returncode:
            self.skipTest(f"this checkout lacks {source['tag']}")
        self.assertEqual(source["commit"], tagged.stdout.strip())
        properties = subprocess.check_output(["git", "-C", str(ROOT), "show", f"{source['commit']}:gradle.properties"], text=True)
        self.assertEqual(source["tag"].removeprefix("v"), re.search(r"^version=(.+)$", properties, re.M)[1])

    def test_offline_maven_sources_fetch_each_compose_platform_for_its_architecture_only(self):
        # compose.desktop.currentOs names the build machine's platform; Gradle resolves no other.
        sources = json.loads((HERE / "maven-sources.json").read_text())
        compose = {"x86_64": "linux-x64", "aarch64": "linux-arm64"}
        for entry in sources:
            for arch in entry.get("only-arches", []):
                self.assertIn(arch, architectures(), entry["dest"])
        for arch in architectures():
            with self.subTest(arch=arch):
                platform = [entry for entry in sources if entry["dest"].startswith(
                    f"offline-repository/org/jetbrains/compose/desktop/desktop-jvm-{compose[arch]}/")]
                self.assertTrue(platform, f"no desktop-jvm-{compose[arch]}")
                self.assertTrue(all(entry.get("only-arches") == [arch] for entry in platform), platform)


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


class FlathubSmokeTestTest(unittest.TestCase):
    def test_runtime_is_looked_up_for_this_machines_architecture_only(self):
        with tempfile.TemporaryDirectory() as folder:
            calls = Path(folder) / "calls"
            flatpak = Path(folder) / "flatpak"
            flatpak.write_text(f'''#!/bin/sh
printf '%s\\n' "$*" >> {calls}
case "$1" in
  --default-arch) echo x86_64 ;;
  info) for a in "$@"; do [ "$a" = --arch=x86_64 ] && echo /i/flatpak/runtime/org.freedesktop.Platform/x86_64/26.08/c && exit 0; done
        echo /u/flatpak/runtime/org.freedesktop.Platform/aarch64/26.08/c ;;
esac
''')
            flatpak.chmod(0o755)
            with mock.patch.dict(os.environ, {"PATH": f"{folder}:{os.environ['PATH']}"}):
                location = smoke_test.runtime_location(manifest())
            asked = calls.read_text()
        self.assertEqual(Path("/i/flatpak"), location.parents[4])
        self.assertIn("info --show-location --arch=x86_64 org.freedesktop.Platform//26.08", asked)


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
