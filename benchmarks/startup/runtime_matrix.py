#!/usr/bin/env python3
"""Run unchanged desktop runtime workloads in alternating fresh-process startup pairs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
RUNTIME = HERE.parent / "runtime"


def run(command, log):
    with log.open("w") as output:
        subprocess.run(list(map(str, command)), stdout=output, stderr=subprocess.STDOUT, check=True,
                       env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--probe-classes", type=Path, required=True)
    for side in ("before", "after"):
        parser.add_argument(f"--{side}-classpath", type=Path, required=True)
        parser.add_argument(f"--{side}-project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--backend", choices=("x11", "windows"), required=True)
    parser.add_argument("--expected-commit", required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    windows = args.backend == "windows"
    java = args.java.resolve()
    jcmd = java.with_name("jcmd.exe" if windows else "jcmd")
    jfr = java.with_name("jfr.exe" if windows else "jfr")
    manifest = {"schema": "vive-startup-runtime-matrix/1", "complete": False, "rows": []}

    def save():
        (output / "matrix.json").write_text(json.dumps(manifest, indent=2) + "\n")

    save()
    for fork in range(1, 4):
        for side in (("before", "after") if fork % 2 else ("after", "before")):
            project = getattr(args, side + "_project").resolve()
            capture = output / f"{side}-fork{fork}"
            command = [sys.executable, RUNTIME / "desktop_runtime.py", "--java", java, "--jcmd", jcmd,
                "--classpath-file", getattr(args, side + "_classpath"), "--probe-classes", args.probe_classes,
                "--project-root", project, "--expected-commit", args.expected_commit,
                "--label", side + "-lazy-recognition-startup", "--backend", args.backend, "--owned-display",
                "--jvm-arg=-Xms512m", "--jvm-arg=--enable-native-access=ALL-UNNAMED",
                "--jvm-arg=--add-exports=java.desktop/sun.awt=ALL-UNNAMED", "--output", capture]
            if windows:
                command += ["--jvm-arg=--add-exports=java.desktop/sun.awt.windows=ALL-UNNAMED"]
            else:
                command += ["--display", os.environ["DISPLAY"], "--jvm-arg=--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"]
            if (project / "source-inventory.json").is_file():
                command += ["--source-manifest", project / "source-inventory.json"]
            print("START", side, fork, flush=True)
            run(command, output / f"{side}-{fork}.log")
            manifest["rows"].append(dict(side=side, fork=fork, capture=str(capture)))
            save()
            print("PASS", side, fork, flush=True)
    for row in manifest["rows"]:
        capture = Path(row["capture"])
        analysis = output / (capture.name + "-analysis")
        run([sys.executable, RUNTIME / "analyze_jfr.py", "--capture", capture, "--jfr", jfr, "--output", analysis],
            output / (capture.name + "-analysis.log"))
        run([sys.executable, RUNTIME / "export_results.py", "--capture", capture, "--analysis", analysis,
             "--output", output / "public" / capture.name], output / (capture.name + "-export.log"))
        libraries = output / (capture.name + "-libraries.json")
        run([jfr, "print", "--json", "--events", "jdk.NativeLibrary", capture / "runtime.jfr"], libraries)
        events = json.loads(libraries.read_text())["recording"]["events"]
        if not events:
            raise ValueError("Missing JFR native library evidence")
        onnx = [e["values"]["name"] for e in events if "onnxruntime" in e["values"]["name"].lower()]
        if bool(onnx) != (row["side"] == "before"):
            raise ValueError("Unexpected ONNX native library load during non-recognition app workflow")
        row["onnxNativeLibraries"] = sorted(set(Path(name).name for name in onnx))
        save()
    for fork in range(1, 4):
        run([sys.executable, RUNTIME / "desktop_runtime.py", "--compare", output / f"before-fork{fork}",
             output / f"after-fork{fork}", "--comparison-output", output / f"comparison-fork{fork}"],
             output / f"comparison-{fork}.log")
    manifest["complete"] = True
    save()
    print("STARTUP_RUNTIME_MATRIX_COMPLETE", flush=True)


if __name__ == "__main__":
    main()
