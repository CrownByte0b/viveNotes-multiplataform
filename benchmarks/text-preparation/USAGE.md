# Text preparation and reopening diagnostic

This extension preserves every original runtime workload and integrity check. It adds a two-second
settle after the first text save, a real click back to the text page, saved-token verification,
another two-second settle and a final ink-page return. Both app versions run with `--debug` so
the changed app's page-level preparation counts can be independently checked in its private log.
The generated note corpus and normal application entry point remain unchanged.

The dedicated DesktopRuntimeProbe.kt is derived from ../runtime/DesktopRuntimeProbe.kt. The
launcher, JFR analyzer, sanitized exporter and render observer are imported from ../runtime and
remain unchanged. Provenance hashes both the original tools and this extension. Captures from
this extended workload must only be compared with the same extended workload.

Use the original [runtime instructions](../runtime/USAGE.md), replacing the launcher with
`python3 benchmarks/text-preparation/text_runtime.py`. Compile a fresh probe with the current
compiler/runtime classpaths and `--contract-only`; the old probe binary has a different stamp.
The `analyze` and `export` subcommands take the original analyzer/exporter arguments:

```sh
python3 benchmarks/text-preparation/text_runtime.py analyze --capture "$capture" --jfr "$jfr" --output "$analysis"
python3 benchmarks/text-preparation/text_runtime.py export --capture "$capture" --analysis "$analysis" --output "$public"
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/text-preparation -p 'test_*.py'
```

Retain three alternating pairs on each platform, all raw evidence outside Git, and the exact
source/dependency/runtime/tool manifests. A completed scripted phase is not physical latency,
GPU time or FPS. The settle windows are pacing, not proof of completed background preparation;
check the changed app's explicit `preparation complete boxes=1000` log for both text visits and
retain the Compose contract that verifies all 1,000 exact measurements and reopening reuse.
Changing or adding this diagnostic does not change any historical benchmark.
