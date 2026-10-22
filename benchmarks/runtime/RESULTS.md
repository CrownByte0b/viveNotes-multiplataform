# Desktop runtime profiling — 2026-10-22

This milestone completes cumulative real-application profiling from original review commit
`0f5e37070b2532b001eb7db998048edd91b1c103` to OCR commit
`11c51a5593347dacfbf81a40e08f42779949ea7d`. It measures the earlier combined optimizations.
The current `765483b` checkout passed the full JVM suite, but its newer CI, title-bar, shelf and
Google sign-in changes are outside this performance comparison. No additional production
optimization was implemented here.

There are three alternating fresh-process pairs on isolated Linux X11 and three on the dedicated
Windows VM: before/after, after/before, before/after. Every successful capture verifies its
synthetic corpus, workflow, exact added ink, preserved original strokes and text, save, normal
shutdown, runtime/dependency/source hashes and complete JFR/observer counter agreement.
All twelve captures and all six pair comparisons pass. The Windows decoded JFR data was also
independently reanalyzed on Linux with identical results.

## Results

Each number is the median of three separate fork observations. A p95 row is the median of the
three nearest-rank per-fork p95s, not a pooled population tail. Positive changes are increases.
All individual observations are retained in [comparison.json](../results/2026-10-22-desktop-runtime/comparison.json).
Callbacks measure elapsed time in the existing Skia recording callback, before presentation;
they do not measure GPU duration, FPS or physical input latency. Scripted phases include Robot
pacing, accessibility polling and autosave waits. The two platforms have different host/display
conditions and should be assessed separately.

### Linux X11

| Observation | Before | After | Change |
| --- | ---: | ---: | ---: |
| JVM main → ready (ms) | 2,794.665 | 2,937.665 | +5.12% |
| Recording callback elapsed sum (ms) | 3,282.050 | 2,541.823 | -22.55% |
| Overall callback p95 (ms) | 48.705 | 20.887 | -57.11% |
| Sampled peak RSS (MiB) | 2,500.781 | 2,241.801 | -10.36% |
| Text-page workflow (ms) | 3,285.422 | 2,679.243 | -18.45% |
| Text-phase callback p95 (ms) | 86.477 | 98.607 | +14.03% |
| Text-phase maximum callback (ms) | 1,066.820 | 997.725 | -6.48% |
| Return-to-ink workflow (ms) | 1,104.673 | 1,132.045 | +2.48% |
| Scroll/zoom workflow (ms) | 2,326.420 | 2,267.055 | -2.55% |
| Process CPU at shutdown hook (s) | 24.690 | 23.860 | -3.36% |
| Observed used Java heap at shutdown (MiB) | 464.000 | 586.373 | +26.37% |
| Committed Java heap at shutdown (MiB) | 1,113.000 | 1,121.000 | +0.72% |

### Windows 11 VM

| Observation | Before | After | Change |
| --- | ---: | ---: | ---: |
| JVM main → ready (ms) | 3,772.648 | 3,756.767 | -0.42% |
| Recording callback elapsed sum (ms) | 5,114.946 | 3,407.860 | -33.37% |
| Overall callback p95 (ms) | 70.734 | 28.089 | -60.29% |
| Sampled peak RSS (MiB) | 1,620.422 | 1,596.633 | -1.47% |
| Text-page workflow (ms) | 3,363.833 | 1,913.720 | -43.11% |
| Text-phase callback p95 (ms) | 109.692 | 126.061 | +14.92% |
| Text-phase maximum callback (ms) | 1,578.926 | 975.577 | -38.21% |
| Return-to-ink workflow (ms) | 1,198.071 | 1,013.958 | -15.37% |
| Scroll/zoom workflow (ms) | 2,220.463 | 2,256.872 | +1.64% |
| Process CPU at shutdown hook (s) | 31.891 | 27.234 | -14.60% |
| Observed used Java heap at shutdown (MiB) | 299.000 | 360.000 | +20.40% |
| Committed Java heap at shutdown (MiB) | 543.000 | 526.000 | -3.13% |

The callback sums improve by 22.55% on X11 and 33.37% on Windows; the complete text workflow
improves by 18.45% and 43.11%. Peak RSS falls by 10.36% and 1.47% respectively. These are observations
of this fixture under instrumentation, without a significance or universal speedup claim.

Retained regressions include X11 readiness +5.12% and ink reopening +2.48%, Windows viewport
workflow +1.64%, and text-phase callback p95 +14.03% / +14.92%. Callback counts change between
versions, so the per-callback distribution is not a set of matched draws. Maximum text callbacks
still approach one second on both platforms. The used Java heap observations increase by
26.37% / 20.40%; natural GC states differ, so this does not establish a leak. Committed heap,
used heap, HotSpot NMT categories and process RSS remain distinct in each resources.json.
NMT does not account for all third-party native allocations. No GC policy was added.

## Workload, runtime and provenance

- Real desktop `MainKt`, fresh isolated development profile, no account, and explicit existing
  removal preferences suppressing model/math downloads. Original harnesses and earlier milestone
  captures in Git remain untouched.
- 40,000 deterministic genuine ByteInk strokes on one page; 1,000 text boxes of 1,000 characters
  on another. Eight real strokes, persistence, scroll down/up, zoom in/out, lasso selection and
  clearing, page switch, real typing, save, return to ink, two-second idle periods and normal close.
- Zulu 27+35 on both platforms; 512 MiB initial / 2 GiB maximum heap; target-25 application and
  probe bytecode. The application's configured ByteInk SNAPSHOT and all dependency bytes are
  identical within each comparison. These are not measurements of the hosted CI alpha.1 package.
- Linux: isolated 1600×1000 Xvfb, 1280×900 application frame, scale 1. Windows: dedicated console
  at 3840×2160 physical pixels, AWT screen 2194×1234 logical units, 1280×900 logical application
  frame and scale 1.75. Exact CPU/OS, screen, flags, Java executable,
  modules/native library and classpath hashes are in each provenance.json. Renderer selection
  remains the normal application selection; the observer does not request extra draws.
- Windows runs use the public source and target-25 binaries built from the same clean Linux
  checkouts. All 1,725 transferred files and 110 reused dependencies passed SHA checks, with
  explicit hash-verified probe deltas. Source inventory provenance explicitly says that the VM
  has an exported inventory rather than a local Git checkout. Source and binary hashes bind
  independent inputs; they are not by themselves proof of compilation.
- JFR profile, render observer, 250 ms process sampling and NMT summary instrumentation are
  consistent within each pair. Initial/final NMT attaches complete before their idle windows.
  AWT dispatch-to-next-returned-callback observations preserve dropped/unmatched inputs but do
  not establish causality or end-to-end latency. Sample hits are not CPU milliseconds; X11
  touch stacks include blocking waits and do not establish an idle busy loop.

The six raw X11 captures referenced by the previous handoff had disappeared from /tmp. Their
saved orchestration and historical memory notes remain, but those numbers are not reused here.
Both clean revisions were rebuilt and all six X11 runs were repeated. The current evidence was
backed up to private local-state storage outside the repository and /tmp; its exact location and
archive checksums are recorded in project memory and the ignored verification directory.

## Tooling corrections and native Wayland limit

The Windows driver initially completed ink and viewport work, then observed a temporarily
incomplete accessibility tree after selecting Lasso. Canvas lookup now polls for the actual
accessible scroll range for at most ten seconds. It retains all targeting, focus, range and
persistence assertions. Two deterministic fake-clock cases cover republication and timeout.
The failed capture is retained separately and contributes no performance result.

Windows postprocessing exposed a path-separator mismatch in the required-evidence set. Paths
are normalized to portable names before membership and duplicate checks; missing and changed
raw data still fail. Linux-only test mocks and path fixtures were made portable, and explicit
Windows path-boundary coverage was added. Exported source inventories verify all source bytes
without transferring Git history or credentials. The sanitized exporter keeps resource categories
separate and rejects incomplete or changed evidence.

The exact capture tools are retained in
[x11-tooling](../results/2026-10-22-desktop-runtime/x11-tooling/) and
[windows-tooling](../results/2026-10-22-desktop-runtime/windows-tooling/). The Windows capture snapshot
predates the postprocessing path fix; use the current analyzer/validator to analyze those unchanged
captures. These versions are disclosed and are not used for cross-platform timing comparisons.

Native Wayland was retried in a private nested X11 Weston compositor with JBR 25.0.4+1-b508.27.
The real app opens its unchanged SwingGraphics host. The observer fails with
`No observable SkiaLayer in the owned frame`: this host uses SkiaSwingLayer with an immutable
renderer delegate instead of the observer's supported mutable SkiaLayer API. That failed preflight
is retained with its manifest, events and window capture. There is no valid comparable native
Wayland workflow or frame timing in this report. The renderer was not replaced to obtain numbers.

## Verification and remaining work

The current checkout passed `:shared:jvmTest --rerun :desktopApp:test --rerun lint :desktopApp:assemble
--offline --max-workers=1` under a separate 1920×1200 Xvfb: **2,199 passed, 13 skips, zero failures
or errors**, in 2m32s. Resource lint checked 1,133 resources / 341 Kotlin files. All **41 Python
runtime contracts pass on Linux and Windows**, and the **14 real-scanner contracts pass**.
Every final Windows run includes six text, two polling and seventeen render-counter checks;
the frozen X11 probe includes the original six text and seventeen counter checks. Sanitized
result/tooling secret scans have no findings. See [verification.json](verification.json).

Hosted CI was checked independently. At `535b975`, [desktop run 39731270627](https://github.com/CrownByte0b/viveNotes-multiplataform/actions/runs/39731270627)
passes Linux and fails Windows in `ToolkitPopupKeyboardContractTest` while waiting for native
selection-palette typing. The earlier tokenizer, picture and PDF failures are absent. The
[MSI workflow on the same commit](https://github.com/CrownByte0b/viveNotes-multiplataform/actions/runs/39731270490)
passed. The hosted popup timeout remains a separate unresolved CI issue; this report does not
claim a green hosted Windows suite or a Windows suite run on the newer local `765483b`.

The next optimization remains bounded exact text preparation and measurement reuse. Current
PageCanvas discards measured heights on page change, and unknown-height text remains composed
until measured. The retained traces still contain expensive text layout and roughly one-second
callbacks. Preserve long boxes above the viewport, unknown content, scroll extents/targets,
selection, IME, fonts, density and asynchronous media when changing this behavior.

Subsequent milestones remain large-ink reopening (including authoritative caches, worker-prepared
scenes and native lifetime/cancellation guards), viewport raster instrumentation/reuse, startup
investigation with cold-process and first-OCR measurements, and measured idle-GC research.
Ink reopens still pass through loading/scene preparation; these captures do not isolate each
component's cost. Existing fit-view erase and seal-append regressions remain in the ink report.
Create a fresh current-commit baseline before implementing the next production change. Pause
here for the user's commit, as requested for each major milestone.

## Reproduction

[USAGE.md](USAGE.md) gives the complete interface, isolation guards and measurement limits.
For exact X11 reproduction, use the retained x11-tooling snapshot with clean original and OCR
checkouts and the pinned dependencies. Collect each checkout's runtime/compiler classpaths:

```sh
./gradlew --offline --no-configuration-cache --max-workers=1 \
  -I /absolute/path/to/benchmarks/runtime/classpath.init.gradle \
  -PruntimeProbeOutput=/tmp/new-classpaths :desktopApp:runtimeProbeClasspath
```

Compile/verify the probe once with `--compiler-classpath-file`, `--contract-only` and a new output;
then supply its `probe-classes` directory to each actual run. On an owned 1600×1000 Xvfb display:

```sh
python3 "$probe_tools/desktop_runtime.py" \
  --java "$probe_java" --jcmd "$probe_jcmd" \
  --project-root "$probe_checkout" --expected-commit "$probe_revision" \
  --classpath-file "$probe_classpaths/runtime-classpath.json" \
  --probe-classes "$probe_classes" --label "$probe_label" \
  --backend x11 --display "$probe_display" --owned-display \
  --jvm-arg=-Xms512m --jvm-arg=--enable-native-access=ALL-UNNAMED \
  --jvm-arg=--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED \
  --jvm-arg=--add-exports=java.desktop/sun.awt=ALL-UNNAMED \
  --output "$probe_output"
```

Run three fresh pairs in B/A, A/B, B/A order. Windows uses the Windows capture snapshot, its
native Zulu 27 executables, `--backend windows --owned-display`, the two `sun.awt` / `sun.awt.windows`
exports, and an owned interactive console. Use `--source-manifest` only for a verified exported
source inventory. Preserve source/compiler/class/native hashes and keep build/test jobs serial
with captures. Do not reuse an existing output/profile or a personal development profile.

Analyze, compare and export with the current tools (new output paths each time):

```sh
python3 benchmarks/runtime/analyze_jfr.py --capture "$probe_output" \
  --jfr "$probe_jfr" --output "$probe_analysis"
python3 benchmarks/runtime/desktop_runtime.py --compare "$probe_before" "$probe_after" \
  --comparison-output "$probe_comparison"
python3 benchmarks/runtime/export_results.py --capture "$probe_output" \
  --analysis "$probe_analysis" --output "$probe_public_result"
```

Keep databases, JFR, accessibility dumps, screenshots and raw logs outside Git. Commit the
sanitized provenance, per-phase summaries, resource snapshots, analysis and aggregate comparison.
