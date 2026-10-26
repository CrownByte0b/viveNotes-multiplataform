#!/usr/bin/env python3
"""Lists what a Gradle build downloaded as flatpak-builder sources, for the offline Flathub build.

Run the Flathub build's Gradle command once online with an empty GRADLE_USER_HOME (BUILDING.md in
this directory), then:

    python3 -B packaging/flathub/generate_maven_sources.py <gradle home> > packaging/flathub/maven-sources.json

Every file in that home's module cache becomes a `file` source laid out in Maven form under
`offline-repository/`, at the first repository the builds declare that serves it, with the SHA-256
of the copy Gradle verified. offline-repository.init.gradle.kts points the builds there.
"""
import concurrent.futures
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

DESTINATION = "offline-repository"

# The repositories settings.gradle.kts (here and in ByteInk) declares, Google first for its groups.
CENTRAL = "https://repo.maven.apache.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
GOOGLE_GROUPS = ("androidx", "com.android", "com.google")


def cached_artifacts(gradle_home):
    """
    Yields (group, module, version, file, name) for each file in the Gradle module cache: name is
    what the repository calls it. Gradle module metadata may give a file a local `name` that differs
    from its `url` (Kotlin Multiplatform libraries do), and the cache keeps the local name.
    """
    cache = Path(gradle_home) / "caches" / "modules-2" / "files-2.1"
    if not cache.is_dir():
        raise SystemExit(f"{cache} does not exist: run the build with this GRADLE_USER_HOME first")
    # files-2.1/<group>/<module>/<version>/<sha1>/<file>
    for version_directory in sorted(cache.glob("*/*/*")):
        group, module, version = version_directory.parts[-3:]
        urls = {}
        for metadata in version_directory.glob(f"*/{module}-{version}.module"):
            for variant in json.loads(metadata.read_text()).get("variants", []):
                for file in variant.get("files", []):
                    urls[file["name"]] = file["url"]
        for file in sorted(version_directory.glob("*/*")):
            if file.is_file():
                yield group, module, version, file, urls.get(file.name, file.name)


def maven_path(group, module, version, name):
    return "/".join([*group.split("."), module, version, name])


def repositories_for(group):
    google_first = any(group == prefix or group.startswith(prefix + ".") for prefix in GOOGLE_GROUPS)
    return [GOOGLE, CENTRAL, PLUGIN_PORTAL] if google_first else [CENTRAL, GOOGLE, PLUGIN_PORTAL]


def serves(url):
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "vivenotes-flathub-sources"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status == 200
    except urllib.error.HTTPError:
        return False


def source(artifact, exists=serves):
    """The flatpak-builder source for one cached file, or an error naming it."""
    group, module, version, file, name = artifact
    path = maven_path(group, module, version, name)
    url = next((f"{repository}/{path}" for repository in repositories_for(group) if exists(f"{repository}/{path}")), None)
    if url is None:
        raise LookupError(f"no declared repository serves {path}")
    return {
        "type": "file",
        "url": url,
        "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
        "dest": f"{DESTINATION}/{path.rsplit('/', 1)[0]}",
        "dest-filename": path.rsplit("/", 1)[1],
    }


def fetched_sha256(url):
    request = urllib.request.Request(url, headers={"User-Agent": "vivenotes-flathub-sources"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return hashlib.sha256(response.read()).hexdigest()


def sources(gradle_home, exists=serves, fetch=fetched_sha256, workers=16):
    artifacts = list(cached_artifacts(gradle_home))
    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        found = list(pool.map(lambda artifact: source(artifact, exists), artifacts))
    by_path = {}
    for entry in found:
        by_path.setdefault(f"{entry['dest']}/{entry['dest-filename']}", []).append(entry)
    chosen = []
    for key in sorted(by_path):
        entries = by_path[key]
        cached = {entry["sha256"] for entry in entries}
        if len(cached) > 1:
            # Repositories can publish one version's metadata differently (the Plugin Portal and
            # Maven Central do for some plugins), and Gradle caches each copy it read. The offline
            # repository holds one: the copy the chosen URL serves, which Gradle also verified.
            served = fetch(entries[0]["url"])
            if served not in cached:
                raise ValueError(f"{entries[0]['url']} serves none of the copies Gradle cached of {key}")
            entries = [dict(entries[0], sha256=served)]
        chosen.append(entries[0])
    return chosen


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    json.dump(sources(argv[1]), sys.stdout, indent=4)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
