# Real desktop runtime diagnostic

This separate, opt-in diagnostic launches the actual desktop `MainKt` on a new synthetic profile.
It leaves the frozen `benchmarks/capture.py` and performance fixtures unchanged. The launcher
requires a supplied runtime/test classpath and a caller-owned display/session. It never discovers
or reuses development/user profiles, credentials, installed recognition models or accounts.

The default fixture contains 40,000 deterministic genuine ByteInk strokes, encoded from native
`Stroke` inputs, and a separate page with 1,000 text boxes of 1,000 characters. Fixed IDs,
timestamps, document bytes, stroke metadata and original point blobs contribute to a SHA-256.
Every seed verifies its database readback. Existing explicit-removal preferences suppress formula
model and math-runtime downloads in this isolated profile. Account state remains absent.

The Robot driver checks its own focused `Frame` and accessible controls before input. It records initial
idle, eight real drawn strokes, autosave settle, scroll/zoom, lasso, page switch, real text typing,
return to ink and final idle. It closes the normal window and lets the app flush and close. A
separate verifier requires exactly the added strokes, the typed token and unchanged original ink.
Missing controls, unsupported Robot/toolkit input, failed persistence, timeout or nonzero exit make
the run invalid; there is no fallback that directly mutates session state.

## Run

Run the fast Python contracts without a JVM, display or model:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/runtime -p 'test_desktop_runtime.py' -v
```

The classpath file accepts a JSON array, one existing absolute path per line, or a platform
classpath (`:` on Linux, `;` on Windows). It must include desktop app classes/resources, shared
classes/resources, native ByteInk/Skiko and all normal runtime dependencies. The probe compiles for
JVM25; use Java25+ and the same runtime/heap/dependency versions for each comparison. No Gradle task or
network dependency resolution is invoked by this launcher.

Compile the standalone Kotlin source using a supplied `--kotlinc /path/to/kotlinc`, or supply
`--compiler-classpath-file` containing the Kotlin CLI compiler and its dependencies. When neither
compiler executable is available, compile it externally against the same runtime classpath and
pass `--probe-classes /path/to/classes`. Both `DesktopRuntimeProbe.kt` and `RuntimeRenderProbe.kt`
must be compiled together. That directory must contain `probe-source.sha256` with the aggregate
returned by `desktop_runtime.probe_stamp()` for those two sources. A matching stamp alone is
insufficient: Main/Phase/render class payloads must exist with target25 headers. Their complete
class-file hashes are recorded, copied to a new owned snapshot and checked throughout the run.
The same diagnostic sources must be used on both app versions. A caller-supplied compiled binary
is trusted build input; hashes bind its exact bytes, not a proof that it was built from a source tree.

First run the tiny real SQLite/native fixture contract (four strokes, two text boxes, no GUI):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/runtime/desktop_runtime.py \
  --java /path/to/jdk/bin/java --classpath-file /tmp/current-runtime-cp.txt \
  --compiler-classpath-file /tmp/kotlin-compiler-cp.txt \
  --expected-commit 11c51a5 --label baseline-11c51a5 \
  --output /tmp/vive-runtime-contract-unique --contract-only
```

Then use an already owned, dedicated X11 display, for example a separately started Xvfb/Xephyr:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/runtime/desktop_runtime.py \
  --java /path/to/jdk/bin/java --jcmd /path/to/jdk/bin/jcmd \
  --classpath-file /tmp/current-runtime-cp.txt \
  --probe-classes /tmp/vive-runtime-contract-unique/probe-classes \
  --display :92 --backend x11 --owned-display \
  --expected-commit 11c51a5 --label baseline-11c51a5 \
  --output /tmp/vive-runtime-current-fork1
```

Output must be a new directory outside both this harness repository and `--project-root`. Use
`--project-root /tmp/baseline-checkout` with the baseline classpath so its classes are identified
as project artifacts. `--expected-commit` verifies the recorded checkout HEAD; source and dirty-state
hashes are also recorded. The first reference for further fixes is optimized commit `11c51a5`.
A deliberate comparison from original review commit `0f5e370` to `11c51a5` measures cumulative changes;
label both captures accordingly and do not attribute it to a new runtime-only fix. Each needs a new
profile/output and the same display size/toolkit, JDK, heap, instrumentation and operation counts.
Prefer multiple serial fresh JVM forks and report them individually; do not compare different
fixture/source hashes or external dependencies. The profile/DB/JFR are retained for inspection.

Every run first executes six pure text-verification, two bounded-polling and 17 render-counter contracts, then
seeds its database. The contract-only run adds a zero-edit database verification and launches no GUI.
For actual runs, normal application close must complete, all phase/evidence checks must pass,
process sampling must be available and the JDK `jfr print` decoder must parse every successful
custom phase. `--jfr-tool` overrides the default sibling of `--java`. No arbitrary classpath,
profile, argument-file, agent or instrumentation override is accepted through `--jvm-arg`.

Compare two COMPLETE captures, preserving every phase/render counter rather than requiring equal
frame counts or new-row IDs/timestamps:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/runtime/desktop_runtime.py \
  --compare /tmp/vive-runtime-before-fork1 /tmp/vive-runtime-current-fork1 \
  --comparison-output /tmp/vive-runtime-comparison-fork1
```

Comparison checks raw/manifest hashes, exact original fixture and operation counts, probe/tool
identity, external dependency/native artifacts, Java runtime, normalized actual VM arguments,
backend/screen/scale, host CPU/affinity and instrumentation. It names both source revisions/labels
and retains both complete summaries. Only owned profile/output/project argument roots are
normalized. Compare multiple serial fresh forks in alternating before/after order; do not select
only a favorable fork. This command makes no statistical significance or universal speedup claim.

`--backend native` allows an explicitly owned native Wayland session. Native Wayland injection is
toolkit/runtime dependent and has not been claimed supported merely because the app can launch.
If Robot or accessibility is unavailable, the run fails with raw evidence. On a dedicated Windows
desktop VM use `--backend windows --owned-display` and native Windows classpaths/Python/Java; no
DISPLAY is needed. Windows process RSS/CPU uses native process handles. macOS is not supported by
the process sampler. The launcher never starts or controls a display/VM itself.

For a Windows host receiving public sources and compiled artifacts without Git history, add
`--source-manifest /path/to/source-inventory.json`. Export that JSON from the clean build checkout
with schema `vive-runtime-source-inventory/1`, its full `sourceCommit`, and the exact `sourceFiles`
array returned by `project_snapshot()`. The launcher verifies the complete source inventory on
every provenance check, including additions, removals and changed bytes. The result explicitly
records a caller-exported inventory with no local Git checkout; it does not claim that hashes
prove compilation. Preserve the build log and the source-to-binary transfer manifest separately.

The pinned native Wayland host uses `SkiaSwingLayer` with SwingGraphics. Its immutable renderer
delegate is outside this observer's supported `SkiaLayer` API. A native attempt fails closed
and retains evidence; it supplies no callback timing or comparable completed workflow. Keep this
limitation explicit rather than switching the app's renderer for a benchmark.

## Evidence and limits

`environment.json` records source commit/dirty state, app sources, exact diagnostic SHA arrays,
compiled probe bytes, winning normal-app class providers, runtime classpath, Java executable/JDK
module/native bytes, JVM/version/heap, affinity and instrumentation. Source/classpath/tool/probe
hashes are rechecked after compilation and child processes. It has `complete:false` until every
check passes; failures retain their logs and a failure record. `capture-manifest.sha256` protects
the completed manifest, which lists the raw-file hashes. Hashes use separate path/SHA array fields.
`isolated-profile/fixture.json` records the canonical fixture SHA;
`verification.json` records the final persistence checks. `process.jsonl` contains process CPU,
sampled RSS and OS high-water RSS. `events.jsonl` adds JVM CPU/heap/thread snapshots and monotonic
phase timing; `summary.json` separates JVM-main startup, coarse process-launch startup, scripted
phase times, shutdown-hook duration and full process lifetime. Normal first-launch maintenance is
included. Failed runs retain logs and produce no successful summary.

Transient process-exit observations can lack RSS/HWM while still returning CPU counters. They stay
in `process.jsonl` with their original partial fields and an `unavailable` reason, and are excluded
from numeric summaries. At least two valid finite, monotone CPU/RSS observations remain mandatory;
the summary records both valid and unavailable observation counts.

`runtime.jfr` contains normal profile events and custom `com.vivenotes.RuntimeProbePhase` events.
Optional `--jcmd` adds two native-memory summaries during idle; attach cost perturbs those idle
observations. A bounded request/ack handshake completes each attach before its two-second idle
phase starts; unavailable attach is recorded, not silently treated as a measurement. NMT summary
instrumentation is enabled on every actual app run, including when snapshots are not requested.
Raw JFR, logs, DB and accessibility dumps remain private outside the repository and should
be reviewed before sharing. No production data is seeded or copied into them.

Robot injection and EDT idle do **not** establish a rendered frame, physical input latency or FPS.
Phase durations include deliberate Robot pacing and settle waits. Process sampling can miss final
CPU and instantaneous peaks. Filesystem/native/OS caches, display compositing and machine load
remain uncontrolled; a run is a diagnostic trace, not a claim of statistically significant app
latency improvement. OCR/native-output cold/warm profiling uses the separate compute diagnostic.

The render observer wraps the original Skia recording callback and does not request extra frames.
Its completion is before GPU/display presentation. Final rendering.json must show observer closure,
zero in-flight/failed callbacks and positive completed callbacks. Input-to-next-callback observation
does not prove input caused that draw. Workflow phases include bounded100ms accessibility/SQLite
polling and driver pacing; accessibility traversal (up to10k nodes and20k visible editable text)
can impose different probe costs on old unvirtualized and new UIs. Attribute probe stacks separately.
The runtime profile is synthetic and account-free; no Android/server or live-account action occurs.

Canvas targeting waits up to ten seconds for the real accessible scroll range, including when
Windows temporarily republishes only the focused control after a ribbon change. Missing controls
still fail the run. Polling uses the same 100 ms cadence as the other accessibility checks; any
wait remains included in scripted phase time.
