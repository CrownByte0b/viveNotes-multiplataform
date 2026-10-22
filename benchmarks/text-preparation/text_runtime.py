#!/usr/bin/env python3
"""Text revisit workload using the unchanged runtime launcher, integrity checks and renderer."""
import importlib.util
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
BASE = HERE.parent / "runtime"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runtime = load("text_runtime_base", BASE / "desktop_runtime.py")
runtime.SOURCE = HERE / "DesktopRuntimeProbe.kt"
runtime.PROBE_SOURCES = (runtime.SOURCE, BASE / "RuntimeRenderProbe.kt")
runtime.PHASES = (*runtime.PHASES[:-1], "robot_reopen_text", "text_reopen_settle", "robot_final_ink", "idle_final")
TOOL_FILES = (Path(__file__).resolve(), runtime.SOURCE, HERE / "test_text_runtime.py", HERE / "USAGE.md")

_provenance = runtime.provenance
_validate_events = runtime.validate_events


def provenance(args, classpath):
    result = _provenance(args, classpath)
    result["toolFiles"] += [{"path": "text-preparation/" + p.name, "sha256": runtime.sha256(p)} for p in TOOL_FILES]
    result["textWorkload"] = "original runtime fixture plus two 2-second settles and one text revisit; debug logging enabled"
    return result


def validate_events(events, nmt, strokes=None):
    ends = _validate_events(events, nmt, strokes)
    proof = ends["robot_reopen_text"]["evidence"]
    runtime.require(proof.get("selectedPage") == "runtime-probe-text" and proof.get("savedTokenObserved") is True,
                    "Text revisit lacks saved-content evidence")
    for phase in ("text_autosave_settle", "text_reopen_settle"):
        runtime.require(ends[phase]["evidence"].get("settleMillis") == 2_000, "Text preparation settle is missing")
    proof = ends["robot_final_ink"]["evidence"]
    runtime.require(proof.get("selectedPage") == "runtime-probe-ink" and proof.get("inkReady") is True,
                    "Final ink return lacks readiness evidence")
    return ends


runtime.provenance = provenance
runtime.validate_events = validate_events


def analyzer():
    module = load("text_runtime_analysis", BASE / "analyze_jfr.py")
    module.runtime = runtime
    module.SOURCES = (*module.SOURCES, *TOOL_FILES)
    return module


def exporter():
    module = load("text_runtime_export", BASE / "export_results.py")
    module.runtime = runtime
    return module


def main():
    if len(sys.argv) > 1 and sys.argv[1] in ("analyze", "export"):
        mode = sys.argv.pop(1)
        (analyzer() if mode == "analyze" else exporter()).main()
    else:
        runtime.main()


if __name__ == "__main__":
    main()
