import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlencode


BUILDS = (
    ("windows-msi.yml", "ViveNotes-Windows-x64"),
    ("flatpak.yml", "ViveNotes-Linux-x86_64"),
)


def gh(*arguments):
    return subprocess.run(
        ["gh", *map(str, arguments)], check=True, capture_output=True, text=True,
    ).stdout


def api(endpoint):
    return json.loads(gh("api", endpoint))


def listing(endpoint, key=None):
    pages = json.loads(gh("api", "--paginate", "--slurp", endpoint))
    return [item for page in pages for item in (page[key] if key else page)]


def require_draft(release, tag):
    if release["tag_name"] != tag or not release["draft"]:
        raise ValueError(f"Save a draft release for {tag} first; published releases are not modified.")


def tag_commit(repo, tag):
    obj = api(f"repos/{repo}/git/ref/tags/{tag}")["object"]
    while obj["type"] == "tag":
        obj = api(f"repos/{repo}/git/tags/{obj['sha']}")["object"]
    if obj["type"] != "commit":
        raise ValueError(f"Tag {tag} does not point to a commit.")
    return obj["sha"]


def build_run(repo, workflow, artifact, tag, commit):
    query = urlencode({"branch": tag, "head_sha": commit, "event": "push", "per_page": 100})
    runs = listing(f"repos/{repo}/actions/workflows/{workflow}/runs?{query}", "workflow_runs")
    matches = [run for run in runs if run["head_branch"] == tag
               and run["head_sha"] == commit and run["event"] == "push"]
    if not matches:
        raise ValueError(f"No tag build for {tag} in {workflow}; push the tag and wait for CI.")
    run = max(matches, key=lambda item: item["id"])
    if run["status"] != "completed" or run["conclusion"] != "success":
        raise ValueError(f"The latest {workflow} build for {tag} has not succeeded: {run['html_url']}")
    artifacts = listing(f"repos/{repo}/actions/runs/{run['id']}/artifacts?per_page=100", "artifacts")
    matches = [item for item in artifacts if item["name"] == artifact and not item["expired"]]
    if len(matches) != 1:
        raise ValueError(f"Artifact {artifact} is missing or expired; rerun the tag's {workflow} build.")
    return run


def release_files(directory, version):
    names = [f"ViveNotes-{version}-x64.msi", f"ViveNotes-{version}-x64.msi.sha256",
             f"ViveNotes-{version}-x86_64.flatpak"]
    files = []
    for name in names:
        matches = list(directory.rglob(name))
        if len(matches) != 1 or not matches[0].is_file():
            raise ValueError(f"Expected exactly one {name} in the downloaded artifacts.")
        files.append(matches[0])
    msi, checksum, _ = files
    match = re.fullmatch(r"([0-9a-fA-F]{64})  " + re.escape(msi.name) + r"\s*",
                         checksum.read_text(encoding="ascii"))
    with msi.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if not match or match[1].lower() != digest:
        raise ValueError("The MSI SHA-256 checksum does not match the installer.")
    return files


def attach(repo, tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Use the pushed version tag, for example v1.0.0.")
    releases = listing(f"repos/{repo}/releases?per_page=100")
    matches = [release for release in releases if release["tag_name"] == tag]
    if len(matches) != 1:
        raise ValueError(f"Save a draft release for {tag} on GitHub before running this workflow.")
    release = matches[0]
    require_draft(release, tag)
    commit = tag_commit(repo, tag)
    runs = [(artifact, build_run(repo, workflow, artifact, tag, commit))
            for workflow, artifact in BUILDS]
    with tempfile.TemporaryDirectory(prefix="vive-release-") as folder:
        directory = Path(folder)
        for artifact, run in runs:
            gh("run", "download", run["id"], "--repo", repo, "--name", artifact,
               "--dir", directory / artifact)
        files = release_files(directory, tag[1:])
        current = api(f"repos/{repo}/releases/{release['id']}")
        require_draft(current, tag)
        if tag_commit(repo, tag) != commit:
            raise ValueError("The tag moved while downloading artifacts; rerun this workflow.")
        assets = listing(f"repos/{repo}/releases/{release['id']}/assets?per_page=100")
        duplicates = {asset["name"] for asset in assets} & {file.name for file in files}
        if duplicates:
            raise ValueError(f"Draft already contains {', '.join(sorted(duplicates))}; existing assets are preserved.")
        gh("release", "upload", tag, *files, "--repo", repo)
        summary = (f"Attached installers to your draft for **{tag}**. Review and publish it manually.\n\n"
                   + "\n".join(f"- `{file.name}`" for file in files) + "\n\n"
                   + "\n".join(f"- [{artifact}]({run['html_url']})" for artifact, run in runs) + "\n")
        print(summary)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
                output.write(summary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Attach a tag's tested installers to an existing draft release.")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), required=not os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    try:
        attach(args.repo, args.tag)
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
