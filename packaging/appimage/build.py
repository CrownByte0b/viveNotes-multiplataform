#!/usr/bin/env python3
"""Build a Linux x86_64 AppImage; run with --help for prerequisites/options."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import platform
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("flatpak_build", HERE.parent / "flatpak/build.py")
flatpak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(flatpak)
ROOT = flatpak.ROOT
APP_ID = flatpak.APP_ID
# The type 2 runtime that mounts the image: a dated release, not the moving "continuous" tag.
RUNTIME = ("https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
           "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d")
# Outside Flatpak the app names its windows WINDOW_CLASS (WindowClass.kt); desktops match it here.
WINDOW_CLASS = "vivenotes"


def desktop_entry(version):
    source = (ROOT / "packaging/flatpak" / f"{APP_ID}.desktop").read_text()
    flatpak_class = f"StartupWMClass={APP_ID}\n"
    if flatpak_class not in source:
        raise ValueError("The Flatpak desktop entry no longer names its window class")
    return source.replace(flatpak_class, f"StartupWMClass={WINDOW_CLASS}\n") + f"X-AppImage-Version={version}\n"


def stage_metadata(appdir, version, release_date):
    share = appdir / "usr/share"
    entry = share / f"applications/{APP_ID}.desktop"
    entry.parent.mkdir(parents=True, exist_ok=True)
    entry.write_text(desktop_entry(version))
    shutil.copy2(entry, appdir / entry.name)
    metainfo = share / f"metainfo/{APP_ID}.metainfo.xml"
    metainfo.parent.mkdir(parents=True, exist_ok=True)
    source = (ROOT / "packaging/flatpak" / metainfo.name).read_text()
    metainfo.write_text(source.replace("@VERSION@", version).replace("@DATE@", release_date))
    icon = share / f"icons/hicolor/512x512/apps/{APP_ID}.png"
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "packaging/flatpak" / icon.name, icon)
    shutil.copy2(icon, appdir / icon.name)
    (appdir / ".DirIcon").unlink(missing_ok=True)
    (appdir / ".DirIcon").symlink_to(icon.name)


def stage_licenses(usr):
    licenses = usr / "share/licenses" / APP_ID
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / "LICENSE.md", licenses)
    shutil.copy2(ROOT / "shared/src/jvmMain/resources/ai/NOTICE.md", licenses / "AI-NOTICE.md")
    shutil.copytree(ROOT / "LICENSES", licenses / "third-party")
    shutil.copytree(ROOT / "packaging/flatpak/licenses", licenses / "fonts-and-uv")
    shutil.copytree(HERE / "licenses", licenses / "appimage-runtime")
    flatpak.jar_notices(usr)


def stage(args, appdir):
    version = flatpak.release_version(args.version)
    if appdir.exists():
        shutil.rmtree(appdir)
    flatpak.run(ROOT / "gradlew", ":desktopApp:stageAppImage", f"-Pversion={version}")
    usr = appdir / "usr"
    stage_licenses(usr)
    cache = appdir.parent / "downloads"
    unpacked = appdir.parent / "unpack"
    for label, override, spec in [("jbr", args.jbr_archive, flatpak.JBR), ("uv", args.uv_archive, flatpak.UV)]:
        if unpacked.exists():
            shutil.rmtree(unpacked)
        content = flatpak.unpack(flatpak.verified_archive(override, spec, cache), unpacked)
        if label == "jbr":
            shutil.move(content, usr / "jbr")
        else:
            (usr / "bin").mkdir(exist_ok=True)
            shutil.copy2(content / "uv", usr / "bin/uv")
        shutil.rmtree(unpacked)
    shutil.copy2(HERE / "AppRun", appdir / "AppRun")
    (appdir / "AppRun").chmod(0o755)
    stage_metadata(appdir, version, flatpak.release_date(flatpak.source_epoch()))
    flatpak.run("desktop-file-validate", appdir / f"{APP_ID}.desktop")
    flatpak.run("appstreamcli", "validate", "--no-net", usr / f"share/metainfo/{APP_ID}.metainfo.xml")
    return version


def assemble(appdir, runtime, image, epoch):
    """Writes the runtime followed by a root-owned SquashFS of the AppDir, as appimagetool does."""
    header = runtime.read_bytes()[:11]
    if header[:4] != b"\x7fELF" or header[8:11] != b"AI\x02":
        raise ValueError(f"Not a type 2 AppImage runtime: {runtime}")
    filesystem = image.with_name(image.name + ".squashfs")
    image.unlink(missing_ok=True)
    try:
        # Build hosts' SELinux labels and user IDs must not end up in the image.
        subprocess.run(["mksquashfs", appdir, filesystem, "-noappend", "-all-root", "-no-xattrs",
                        "-comp", "zstd", "-mkfs-time", str(epoch), "-all-time", str(epoch), "-no-progress"],
                       check=True)
        with image.open("wb") as output:
            for part in (runtime, filesystem):
                with part.open("rb") as source:
                    shutil.copyfileobj(source, output)
    except BaseException:
        image.unlink(missing_ok=True)
        raise
    finally:
        filesystem.unlink(missing_ok=True)
    image.chmod(0o755)
    with image.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    checksum = image.with_name(image.name + ".sha256")
    checksum.write_text(f"{digest}  {image.name}\n", encoding="ascii")
    return checksum


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Requires Python 3.12+, JDK 27, mksquashfs (squashfs-tools 4.4+), appstreamcli and "
        "desktop-file-validate. ByteInk must resolve through local Maven or GitHub Packages. "
        "Output: desktopApp/build/appimage/."))
    parser.add_argument("--version")
    parser.add_argument("--jbr-archive", help="Use a local copy of the pinned JBR archive")
    parser.add_argument("--uv-archive", help="Use a local copy of the pinned uv archive")
    parser.add_argument("--runtime-file", help="Use a local copy of the pinned AppImage runtime")
    parser.add_argument("--stage-only", action="store_true", help="Stage the AppDir only")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        parser.error("The AppImage supports Linux x86_64")
    output = ROOT / "desktopApp/build/appimage"
    output.mkdir(parents=True, exist_ok=True)
    appdir = output / "AppDir"
    version = stage(args, appdir)
    if args.stage_only:
        return
    runtime = flatpak.verified_archive(args.runtime_file, RUNTIME, output / "downloads")
    # The smoke test and release upload each expect exactly one image.
    for old in output.glob("ViveNotes-*-x86_64.AppImage*"):
        old.unlink()
    image = output / f"ViveNotes-{version}-x86_64.AppImage"
    assemble(appdir, runtime, image, flatpak.source_epoch())
    print(f"Built {image}")


if __name__ == "__main__":
    main()
