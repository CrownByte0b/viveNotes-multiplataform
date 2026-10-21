#!/usr/bin/env python3
"""Compare desktop metadata wait with a fixed simulated upload, in serial fresh test JVMs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET

HARNESS = Path("shared/src/jvmTest/kotlin/com/vivenotes/data/sync/HierarchySyncTest.kt")
CASE = "*HierarchySyncTest.profileMetadataWaitBehindOneDelayedDesktopPictureUpload"
XML = Path("shared/build/test-results/jvmTest/TEST-com.vivenotes.data.sync.HierarchySyncTest.xml")
PRODUCTION = [
    "shared/src/commonMain/kotlin/com/vivenotes/data/db/Daos.kt",
    "shared/src/commonMain/kotlin/com/vivenotes/data/db/Entities.kt",
    "shared/src/jvmMain/kotlin/com/vivenotes/data/sync/AttachmentBlobSync.kt",
    "shared/src/jvmMain/kotlin/com/vivenotes/data/sync/HierarchySync.kt",
    "shared/src/jvmMain/kotlin/com/vivenotes/data/sync/SyncMetadataGate.kt",
]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def groovy_literal(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"


def result(path):
    suite = ET.parse(path).getroot()
    if any(int(suite.attrib.get(key, 0)) for key in ("failures", "errors", "skipped")) or int(suite.attrib["tests"]) != 1:
        raise ValueError("Expected one successful, unskipped diagnostic")
    output = suite.findtext("system-out", "")
    samples = re.search(r"DESKTOP_SYNC_CONTENTION injectedUploadDelayMs=250 bytes=1024 warmups=2 samplesNs=(\[[^\n]+\])", output)
    runtime = re.search(r"DESKTOP_SYNC_RUNTIME (\{[^\n]+\})", output)
    if not samples or not runtime:
        raise ValueError("Diagnostic samples/runtime metadata are missing")
    values = json.loads(samples[1])
    if len(values) != 6 or any(type(value) is not int or value <= 0 for value in values):
        raise ValueError("Expected six positive nanosecond samples")
    return {"samplesNs": values, "medianNs": statistics.median(values), "runtime": json.loads(runtime[1])}


def runtime_identity(report, project):
    runtime = dict(report["runtime"])
    runtime["vmArgs"] = [arg.replace(str(project), "<project>") for arg in runtime["vmArgs"]]
    return runtime


def artifacts(paths, project):
    return sorted((Path(path).name, digest(Path(path))) for path in paths
                  if Path(path).is_file() and not Path(path).resolve().is_relative_to(project))


def capture(before, after, output, forks):
    if output.exists():
        raise ValueError("Output already exists; captures must never be overwritten")
    if digest(before / HARNESS) != digest(after / HARNESS):
        raise ValueError("Before/after must contain the identical diagnostic source")
    output.mkdir(parents=True)
    environment = dict(os.environ, VIVE_SYNC_CONTENTION_PROFILE="1")
    environment.pop("WAYLAND_DISPLAY", None)
    environment.pop("XDG_SESSION_TYPE", None)
    reports = {"before": [], "after": []}
    identity = None
    dependencies = None
    for fork in range(1, forks + 1):
        order = ("before", "after") if fork % 2 else ("after", "before")
        for variant in order:
            project = before if variant == "before" else after
            prefix = output / f"{variant}-{fork}"
            cp = Path(str(prefix) + "-classpath.json")
            init = Path(str(prefix) + ".init.gradle")
            init.write_text("""import groovy.json.JsonOutput
gradle.projectsEvaluated {
    def task = gradle.rootProject.project(':shared').tasks.named('jvmTest')
    task.configure {
        outputs.upToDateWhen { false }
        doFirst {
            new File(%s).text = JsonOutput.toJson(classpath.files.collect { it.absolutePath })
        }
    }
}
""" % groovy_literal(str(cp)))
            command = ["xvfb-run", "-a", "-s", "-screen 0 1920x1200x24", "./gradlew",
                       ":shared:jvmTest", "--tests", CASE, "--max-workers=1", "--offline",
                       "--console=plain", "--no-configuration-cache", "-I", str(init)]
            with Path(str(prefix) + "-log.txt").open("w") as log:
                subprocess.run(command, cwd=project, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
            xml = project / XML
            Path(str(prefix) + ".xml").write_bytes(xml.read_bytes())
            report = result(xml)
            actual = runtime_identity(report, project)
            external = artifacts(json.loads(cp.read_text()), project)
            if identity is None:
                identity, dependencies = actual, external
            elif actual != identity or external != dependencies:
                raise ValueError("Runtime/VM flags or external artifact hashes changed between forks")
            report.update(fork=fork, variant=variant)
            reports[variant].append(report)
            Path(str(prefix) + ".json").write_text(json.dumps(report, indent=2) + "\n")
            print(f"{variant} fork {fork}: {report['medianNs'] / 1e6:.3f} ms", flush=True)
    summary = {
        "schema": "vive-desktop-sync-contention/1", "harnessSha256": digest(after / HARNESS),
        "scope": "Metadata apply wall time while one simulated 1KiB upload waits 250ms; not network throughput or UI frame latency",
        "warmupsPerFork": 2, "samplesPerFork": 6, "runtime": identity,
        "externalArtifacts": [{"name": name, "sha256": sha} for name, sha in dependencies],
        "sourceCommits": {name: subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=project, text=True).strip()
                          for name, project in (("before", before), ("after", after))},
        "productionSha256": {name: {path: digest(project / path) if (project / path).is_file() else None
                                     for path in PRODUCTION}
                             for name, project in (("before", before), ("after", after))},
        "sourceChanges": subprocess.check_output(["git", "diff", "--stat"], cwd=after, text=True),
        "forks": reports,
        "medianOfForkMediansNs": {name: statistics.median(row["medianNs"] for row in rows) for name, rows in reports.items()},
    }
    (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--forks", type=int, default=3)
    arguments = parser.parse_args()
    if arguments.forks < 1:
        parser.error("--forks must be positive")
    capture(arguments.before.resolve(), arguments.after.resolve(), arguments.output.resolve(), arguments.forks)
