#!/usr/bin/env python3
"""Run Flathub's pinned build recipes locally or from the manual GitHub workflow.

    python3 -B packaging/flathub/run_ci.py

Docker runs the same unprivileged builder image as Flathub's submission CI. Inputs retain
the manifest's release pins. Outputs and the upstream builder checkout live in build/flathub-ci/.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
APP_ID = "net.vivenotes.ViveNotes"
UPSTREAM_URL = "https://github.com/flathub-infra/vorarbeiter.git"
UPSTREAM_REVISION = "90b52674d33facc30feccf7d2f3d40ba5b819b77"
BUILDER_IMAGE = (
    "ghcr.io/flathub-infra/flatpak-builder-lint@"
    "sha256:5b41839c0657f7e57a95400de11833ee1b38ff3ccc1c5572426c7fb4830b0aa4"
)
LOCAL_REF = "refs/pull/local/head"
PHASES = ("manifest", "download", "build", "lint", "bundle")


def run(command, **kwargs):
    print("+ " + shlex.join(map(str, command)), flush=True)
    return subprocess.run(command, check=True, **kwargs)


def ensure_builder(directory, url=UPSTREAM_URL, revision=UPSTREAM_REVISION):
    if not directory.exists():
        directory.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--depth=1", url, str(directory)])
    head = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
    if head != revision:
        run(["git", "-C", str(directory), "fetch", "--depth=1", "origin", revision])
        run(["git", "-C", str(directory), "checkout", "--detach", revision])
    for name in ("justfile", "flatpak.seccomp.json", "LICENSE"):
        if not (directory / name).is_file():
            raise ValueError(f"Missing upstream builder file: {directory / name}")
    run(["git", "-C", str(directory), "diff", "--exit-code", revision, "--",
         "justfile", "flatpak.seccomp.json", "LICENSE"], stdout=subprocess.DEVNULL)


def prepare(root, state, builder):
    work = state / "work"
    for directory in (work, work / "tmp", work / ".local/state", work / "artifacts", state / "flatpak"):
        directory.mkdir(parents=True, exist_ok=True)
        directory.chmod(0o777)

    source = root / "packaging/flathub"
    for name in (f"{APP_ID}.yml", "maven-sources.json", "vivenotes"):
        shutil.copy2(source / name, work / name)
    config = source / "flathub.json"
    if not config.is_file():
        config = root / "packaging/flatpak/flathub.json"
    shutil.copy2(config, work / "flathub.json")
    if (source / "patches").is_dir():
        shutil.copytree(source / "patches", work / "patches", dirs_exist_ok=True)
    shutil.copy2(builder / "justfile", work / ".flathub.justfile")
    shutil.copy2(builder / "LICENSE", work / "FLATHUB-BUILDER-LICENSE")

    # The upstream recipes use git log for the build subject. Fetch metadata without checking
    # out application files or creating a synthetic submission commit.
    run(["git", "init", "--quiet", str(work)])
    run(["git", "-C", str(work), "fetch", "--quiet", "--no-tags", "--depth=1", str(root), "HEAD"])
    run(["git", "-C", str(work), "update-ref", "refs/heads/source", "FETCH_HEAD"])
    run(["git", "-C", str(work), "symbolic-ref", "HEAD", "refs/heads/source"])
    provenance = {
        "builder_repository": UPSTREAM_URL,
        "builder_revision": subprocess.check_output(["git", "-C", str(builder), "rev-parse", "HEAD"], text=True).strip(),
        "builder_image": BUILDER_IMAGE,
        "checkout_commit": subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip(),
        "manifest_sha256": hashlib.sha256((work / f"{APP_ID}.yml").read_bytes()).hexdigest(),
    }
    (work / "artifacts/provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    return work


def container_command(phase, state, builder):
    recipes = {
        "manifest": [("validate-manifest", APP_ID)],
        "download": [("download-sources", APP_ID)],
        "build": [("build", APP_ID, LOCAL_REF, "x86_64"), ("install-if-extra-data", APP_ID, LOCAL_REF)],
        "lint": [("validate-build",)],
    }
    commands = [["just", "-f", ".flathub.justfile", "prepare-env"]]
    if phase == "bundle":
        commands.append(["flatpak", "build-bundle",
                         "--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo",
                         "repo", f"artifacts/{APP_ID}.flatpak", APP_ID, "test"])
    else:
        commands.extend([["just", "-f", ".flathub.justfile", *recipe] for recipe in recipes[phase]])
    script = "\n".join(shlex.join(command) for command in commands)
    command = [
        "docker", "run", "--rm", "--init",
        "--security-opt", f"seccomp={builder / 'flatpak.seccomp.json'}",
        "--security-opt", "apparmor=unconfined", "--cap-drop", "all", "--entrypoint=",
        "--env", "TMPDIR=/work/tmp", "--env", "XDG_STATE_HOME=/work/.local/state",
        "--env", f"APP_ID={APP_ID}", "--env", f"REF={LOCAL_REF}",
        "--env", "PR_TARGET_BRANCH=new-pr", "--env", "BUILD_ARCH=x86_64",
        "--env", "REPO=https://github.com/CrownByte0b/viveNotes-multiplataform",
        "--env", "GITHUB_ACTIONS", "--env", "GITHUB_TOKEN",
        "--volume", "/proc:/host/proc",
        "--volume", f"{state / 'work'}:/work:z",
        "--volume", f"{state / 'flatpak'}:/home/flatbld/.local/share/flatpak:z",
        "--workdir", "/work", BUILDER_IMAGE,
        "/bin/bash", "-euo", "pipefail", "-c", script,
    ]
    return command


def run_logged(command, log):
    print("+ " + shlex.join(command), flush=True)
    with log.open("w") as stream, subprocess.Popen(
        command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    ) as process:
        try:
            for line in process.stdout:
                print(line, end="", flush=True)
                stream.write(line)
            code = process.wait()
        except BaseException:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            raise
    if code:
        raise subprocess.CalledProcessError(code, command)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--phase", choices=("all", "prepare", *PHASES), default="all")
    parser.add_argument("--state-dir", type=Path, default=ROOT / "build/flathub-ci")
    args = parser.parse_args()
    state = args.state_dir.resolve()
    builder = state / "vorarbeiter"
    if args.phase in {"all", "prepare"}:
        ensure_builder(builder)
        prepare(ROOT, state, builder)
    elif not (state / "work/.flathub.justfile").is_file():
        parser.error("Run --phase prepare first")
    phases = PHASES if args.phase == "all" else (() if args.phase == "prepare" else (args.phase,))
    for phase in phases:
        run_logged(container_command(phase, state, builder), state / f"work/artifacts/{phase}.log")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Flathub CI failed: {error}", file=sys.stderr)
        sys.exit(1)
