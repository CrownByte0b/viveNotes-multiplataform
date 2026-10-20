#!/usr/bin/env python3
"""Build a local Linux x86_64 Flatpak; run with --help for prerequisites/options."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_ID = "net.vivenotes.ViveNotes"
JBR = ("https://cache-redirector.jetbrains.com/intellij-jbr/"
       "jbr-25.0.4.1-linux-x64-b635.75.tar.gz",
       "3f9e4cb3e4d277daaabe17405dec80428a5845f63109008e36176dcfa93f1742")
UV = ("https://github.com/astral-sh/uv/releases/download/0.12.19/"
      "uv-x86_64-unknown-linux-gnu.tar.gz",
      "23bf5552d220e0842b65c862097b2ebaeba0064b74eda5e565e77fd25969d8c8")


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def verified_archive(override, spec, cache):
    url, expected = spec
    archive = Path(override) if override else cache / url.rsplit("/", 1)[1]
    if not archive.exists():
        if override:
            raise ValueError(f"Archive not found: {archive}")
        cache.mkdir(parents=True, exist_ok=True)
        part = archive.with_suffix(".part")
        try:
            urllib.request.urlretrieve(url, part)
            part.replace(archive)
        finally:
            part.unlink(missing_ok=True)
    if sha256(archive) != expected:
        raise ValueError(f"SHA-256 mismatch: {archive}")
    return archive


def unpack(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        source.extractall(destination, filter="data")
    entries = list(destination.iterdir())
    if len(entries) != 1 or not entries[0].is_dir():
        raise ValueError(f"Expected one archive root in {archive}")
    return entries[0]


def jar_notices(payload):
    notices = payload / "share/licenses" / APP_ID / "dependencies"
    notices.mkdir(parents=True, exist_ok=True)
    inventory = []
    for jar in sorted((payload / "lib").glob("*.jar")):
        inventory.append(f"{sha256(jar)}  {jar.name}")
        with zipfile.ZipFile(jar) as source:
            for name in source.namelist():
                parts = Path(name).parts
                if (not name.endswith("/") and not name.startswith("/") and ".." not in parts
                        and re.search(r"(^|/)(license|notice|copying|copyright)([./_-]|$)", name, re.I)):
                    target = notices / jar.name / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read(name))
    (notices / "SHA256SUMS").write_text("\n".join(inventory) + "\n")


def stage_metadata(output, version, release_date):
    payload = output / "payload"
    for kind, suffix in [("applications", "desktop"), ("metainfo", "metainfo.xml")]:
        target = payload / "share" / kind / f"{APP_ID}.{suffix}"
        target.parent.mkdir(parents=True, exist_ok=True)
        source = (HERE / target.name).read_text()
        target.write_text(source.replace("@VERSION@", version).replace("@DATE@", release_date))
    icon = payload / f"share/icons/hicolor/512x512/apps/{APP_ID}.png"
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(HERE / f"{APP_ID}.png", icon)
    shutil.copy2(HERE / "flathub.json", output)


def stage(args, output):
    version = args.version or re.search(r"^version=(.+)$", (ROOT / "gradle.properties").read_text(), re.M)[1]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][a-zA-Z0-9.-]+)?", version):
        raise ValueError("Use a three-component version, optionally with a prerelease suffix")
    run(ROOT / "gradlew", ":desktopApp:stageFlatpak", f"-Pversion={version}")
    payload = output / "payload"
    licenses = payload / "share/licenses" / APP_ID
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "LICENSE.md", licenses)
    shutil.copy2(ROOT / "shared/src/jvmMain/resources/ai/NOTICE.md", licenses / "AI-NOTICE.md")
    shutil.copytree(HERE / "licenses", licenses / "fonts-and-uv", dirs_exist_ok=True)
    jar_notices(payload)
    cache = output / "downloads"
    for label, override, spec in [("jbr", args.jbr_archive, JBR), ("uv", args.uv_archive, UV)]:
        archive = verified_archive(override, spec, cache)
        unpacked = output / f"unpack-{label}"
        if unpacked.exists():
            shutil.rmtree(unpacked)
        content = unpack(archive, unpacked)
        if label == "jbr":
            if (payload / "jbr").exists():
                shutil.rmtree(payload / "jbr")
            shutil.move(content, payload / "jbr")
        else:
            (payload / "bin").mkdir(exist_ok=True)
            shutil.copy2(content / "uv", payload / "bin/uv")
        shutil.rmtree(unpacked)
    (payload / "bin").mkdir(exist_ok=True)
    shutil.copy2(HERE / "vivenotes", payload / "bin/vivenotes")
    (payload / "bin/vivenotes").chmod(0o755)
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH") or subprocess.check_output(
        ["git", "show", "--no-show-signature", "-s", "--format=%ct", "HEAD"], cwd=ROOT, text=True).strip())
    date = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).date().isoformat()
    stage_metadata(output, version, date)
    shutil.copy2(HERE / f"{APP_ID}.json", output)
    run("desktop-file-validate", payload / f"share/applications/{APP_ID}.desktop")
    run("appstreamcli", "validate", "--no-net", payload / f"share/metainfo/{APP_ID}.metainfo.xml")
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Requires Python 3.12+, JDK 27, appstreamcli, desktop-file-validate, "
        "flatpak and org.freedesktop.Platform//26.08. ByteInk must resolve through local Maven "
        "or GitHub Packages. Output: desktopApp/build/flatpak/."))
    parser.add_argument("--version")
    parser.add_argument("--jbr-archive", help="Use a local copy of the pinned JBR archive")
    parser.add_argument("--uv-archive", help="Use a local copy of the pinned uv archive")
    parser.add_argument("--stage-only", action="store_true", help="Stage payload and builder manifest only")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("The first Flatpak package supports Linux x86_64")
    output = ROOT / "desktopApp/build/flatpak"
    output.mkdir(parents=True, exist_ok=True)
    version = stage(args, output)
    if args.stage_only:
        return
    manifest = json.loads((HERE / f"{APP_ID}.json").read_text())
    build = output / "app"
    if build.exists():
        shutil.rmtree(build)
    # All compilation happened on the host. The runtime is enough for this assembly-only step;
    # a normal flatpak-builder invocation uses the manifest's full SDK instead.
    run("flatpak", "build-init", build, APP_ID, manifest["runtime"], manifest["runtime"], manifest["runtime-version"])
    shutil.copytree(output / "payload", build / "files", symlinks=True, dirs_exist_ok=True)
    run("flatpak", "build-finish", f"--command={manifest['command']}", *manifest["finish-args"], build)
    repo = output / "repo"
    run("flatpak", "build-export", repo, build, "devel")
    bundle = output / f"ViveNotes-{version}-x86_64.flatpak"
    bundle.unlink(missing_ok=True)
    run("flatpak", "build-bundle", repo, bundle, APP_ID, "devel",
        "--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo")
    print(f"Built {bundle}")


if __name__ == "__main__":
    main()
