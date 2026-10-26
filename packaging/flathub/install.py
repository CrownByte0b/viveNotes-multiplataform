#!/usr/bin/env python3
"""Installs the Flathub build's application module into FLATPAK_DEST.

Runs after `:desktopApp:stageFlatpak`, from the source root, in the manifest's vivenotes module:
the staged jars and this directory's launcher, the desktop entry, MetaInfo (with the release from
gradle.properties and the source commit's date) and icon from packaging/flatpak, and the licence
notices, as packaging/flatpak/build.py lays them out for the local bundle.
"""
import argparse
import os
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "flatpak"))
import build as local  # noqa: E402

FONT_LICENSES = ["Inter-OFL.txt", "JetBrainsMono-OFL.txt", "Lora-OFL.txt"]


def install(root, destination, version, release_date):
    lib = destination / "lib"
    lib.mkdir(parents=True, exist_ok=True)
    for jar in sorted((root / "desktopApp/build/flatpak/payload/lib").glob("*.jar")):
        shutil.copy2(jar, lib / jar.name)
    launcher = destination / "bin/vivenotes"
    launcher.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(HERE / "vivenotes", launcher)
    launcher.chmod(0o755)

    for kind, suffix in [("applications", "desktop"), ("metainfo", "metainfo.xml")]:
        target = destination / "share" / kind / f"{local.APP_ID}.{suffix}"
        target.parent.mkdir(parents=True, exist_ok=True)
        source = (local.HERE / target.name).read_text()
        target.write_text(source.replace("@VERSION@", version).replace("@DATE@", release_date))
    icon = destination / f"share/icons/hicolor/512x512/apps/{local.APP_ID}.png"
    icon.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(local.HERE / f"{local.APP_ID}.png", icon)

    licenses = destination / "share/licenses" / local.APP_ID
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / "LICENSE.md", licenses)
    shutil.copy2(root / "shared/src/jvmMain/resources/ai/NOTICE.md", licenses / "AI-NOTICE.md")
    (licenses / "fonts").mkdir(exist_ok=True)
    for name in FONT_LICENSES:
        shutil.copy2(local.HERE / "licenses" / name, licenses / "fonts" / name)
    local.jar_notices(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--date", required=True, help="Release date, YYYY-MM-DD")
    args = parser.parse_args()
    install(Path.cwd(), Path(os.environ["FLATPAK_DEST"]), args.version, args.date)


if __name__ == "__main__":
    main()
