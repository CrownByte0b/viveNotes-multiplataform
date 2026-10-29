#!/usr/bin/env python3
"""Builds the Flathub manifest locally from the working trees, before anything is committed.

The manifest's git sources name release commits of this repository and ByteInk. This packs each
working tree as a commit of it would hold it (tracked and new files that are not ignored), points
those sources at the packs instead, and runs org.flatpak.Builder on the result. Everything lands
in build/flathub-dev/. Other sources are fetched as on Flathub.

    python3 -B packaging/flathub/build_local.py [--byteink ../byteink] [--stop-at MODULE] [--bundle]
"""
import argparse
import json
import subprocess
import tarfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APP_ID = "net.vivenotes.ViveNotes"
REPOSITORIES = {
    "https://github.com/CrownByte0b/viveNotes-multiplataform.git": "vivenotes",
    "https://github.com/CrownByte0b/ByteInk.git": "byteink",
}


def pack(tree, archive):
    """Writes the files a commit of [tree] would hold, with their working-tree contents."""
    listed = subprocess.run(["git", "-C", tree, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                            check=True, capture_output=True).stdout.split(b"\0")
    with tarfile.open(archive, "w:gz") as output:
        for name in sorted(filter(None, listed)):
            path = Path(tree) / name.decode()
            if path.is_file() or path.is_symlink():
                output.add(path, arcname=name.decode(), recursive=False)


def local_client_id():
    """The developer's Google desktop client id from local.properties, which is never committed."""
    properties = ROOT / "local.properties"
    for line in properties.read_text().splitlines() if properties.is_file() else []:
        key, _, value = line.partition("=")
        if key.strip() == "vive.googleDesktopClientId":
            return value.strip()
    return ""


def local_manifest(manifest, packs, release_date, client_id=""):
    for module in manifest["modules"]:
        sources = []
        for source in module["sources"]:
            if isinstance(source, str):
                source = str(HERE / source)
            elif source.get("type") == "git" and source["url"] in REPOSITORIES:
                local = {"type": "archive", "path": str(packs[REPOSITORIES[source["url"]]]), "strip-components": 0}
                if "dest" in source:
                    local["dest"] = source["dest"]
                source = local
            elif source.get("type") in {"patch", "file"} and "url" not in source:
                source = source.copy()
                if "path" in source:
                    source["path"] = str(HERE / source["path"])
                if "paths" in source:
                    source["paths"] = [str(HERE / path) for path in source["paths"]]
            sources.append(source)
        module["sources"] = sources
        module["build-commands"] = [command.replace('"$(git log -1 --no-show-signature --format=%cs)"', release_date)
                                    .replace("GOOGLE_DESKTOP_CLIENT_ID", client_id)
                                    for command in module.get("build-commands", [])]
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--byteink", default=str(ROOT.parent / "byteink"))
    parser.add_argument("--stop-at", help="Build modules up to this one only")
    parser.add_argument("--bundle", action="store_true", help=f"Also export build/flathub-dev/{APP_ID}.flatpak")
    args = parser.parse_args()

    work = ROOT / "build/flathub-dev"
    work.mkdir(parents=True, exist_ok=True)
    packs = {"vivenotes": work / "vivenotes-source.tar.gz", "byteink": work / "byteink-source.tar.gz"}
    pack(ROOT, packs["vivenotes"])
    pack(Path(args.byteink), packs["byteink"])
    release_date = subprocess.run(["git", "-C", ROOT, "log", "-1", "--no-show-signature", "--format=%cs"],
                                  check=True, capture_output=True, text=True).stdout.strip()

    manifest = local_manifest(yaml.safe_load((HERE / f"{APP_ID}.yml").read_text()), packs, release_date,
                              local_client_id())
    local = work / f"{APP_ID}.json"
    local.write_text(json.dumps(manifest, indent=4) + "\n")

    builder = ["flatpak", "run", "org.flatpak.Builder", "--user", "--force-clean",
               f"--state-dir={work / 'state'}", f"--repo={work / 'repo'}"]
    if args.stop_at:
        builder.append(f"--stop-at={args.stop_at}")
    subprocess.run(builder + [str(work / "app"), str(local)], check=True)
    if args.bundle and not args.stop_at:
        subprocess.run(["flatpak", "build-bundle", "--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo",
                        str(work / "repo"), str(work / f"{APP_ID}.flatpak"), APP_ID], check=True)


if __name__ == "__main__":
    main()
