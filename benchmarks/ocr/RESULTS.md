**Desktop OCR optimization milestone — 2026-10-21**

The baseline is the user's ink commit, `e120e01`. This milestone optimizes text recognition output
handling and OCR geometry, and repairs native session closure during inference. ByteInk, production
OCR/formula model bytes, dictionaries, dependency versions and saved data formats remain unchanged.
Checkpoint, sync and ink production sources retain their committed hashes. Full-app runtime
profiling remains a separate archived milestone.

The production changes are:

- Reduce the recognizer's native `[1, time, 18710]` output with a small separate in-memory ONNX
  TopK graph, with one reduction thread and worker spinning disabled independently of recognizer
  threads. First-index ties and selected scores preserve the original decoder's exact text and
  confidence. Normal outputs transfer only one index and score per time step to Java. A selected
  NaN triggers an exact full-copy decoder for that request, preserving unusual score behavior
  without disabling subsequent native decoding. The reduction session is reused across the
  existing bounded OCR lanes and released with its OCR lifetime.
  Unsupported native opening/running falls back to the original decoder for that lifetime.
- Reuse the detector's complete Java-owned defensive copy instead of copying it into a second
  probability array. Sliced, direct, read-only or partial buffers retain the exact copying fallback.
  Real native-output tests prove the retained array remains independent after output/session close.
- Prune impossible reading-order rows using conservative bounds and preserve the original first
  matching row and stable horizontal order. Separated rows avoid repeatedly scanning every earlier
  row. Dense/adversarial overlapping bounds can still require quadratic work.
- For large finite bounded hulls, use a support tree to prune points when computing edge extrema.
  Preserve original edge order, Float arithmetic and first equal-area result. Small/non-finite or
  unsafe hulls use the exhaustive path. This is bounded pruning, not a linear rotating-calipers
  implementation; its worst case remains quadratic.
- Count native session uses across inference/result decoding. External engine close becomes
  terminal, rejects queued/new native work and defers native release until admitted uses leave.
  Decoder eviction retires a separate generation, so an old completion cannot close or disable a
  reopened session. Closure can safely abort admitted work before a later session lookup or first
  reducer creation. Existing concurrent lanes remain concurrent. Session-option ownership also
  covers configuration failures.

The focused run additionally exposed two existing recognition raster tests that depended on an
earlier UI test initializing Compose graphics. Their setup now registers the normal desktop
backend explicitly; their original geometry and pixel assertions are preserved. The failing
cold-run XML is retained under the verification build directory.

The final [CTC comparison](../results/2026-10-21-desktop-ocr-ctc-verified/comparison.md)
completed 24 serial fresh JVMs: four configurations, baseline/current, three forks. All model,
dictionary, input, output, reading and confidence bits match exactly. All 12 current processes
opened one reduction session with one reduction thread and recorded zero exceptional full copies.
Widths are pixels in an already preprocessed 48-pixel-high crop. Threads below belong to the
unchanged recognizer; each native reduction uses one thread independently.

| Lanes / recognizer threads / width | Warm batch ms, before → after | Change | Decode per lane ms, before → after | Change |
| --- | ---: | ---: | ---: | ---: |
| 1 / 1 / 320 | 17.156 → 16.679 | -2.78% | 0.368 → 0.331 | -10.09% |
| 1 / 1 / 3200 | 162.981 → 161.867 | -0.68% | 3.810 → 2.975 | -21.91% |
| 2 / 4 / 320 | 9.705 → 8.197 | -15.54% | 0.485 → 0.314 | -35.40% |
| 2 / 4 / 3200 | 87.859 → 84.812 | -3.47% | 5.448 → 3.030 | -44.38% |

Measured pipeline Java allocation per lane falls from 2,995,336 to 3,624 bytes for narrow crops
and 29,938,464 to 8,672 bytes for wide crops (99.88–99.97% lower). Decode-only allocation falls
from 2,994,136 to 2,424 bytes and 29,937,264 to 7,472 bytes (99.92–99.98% lower). These exclude
native storage and allocations on other threads. Whole-process CPU over the fixed pipeline
sample batches falls 1.8–15.3%; decode sample CPU falls 26.5–42.1%. Cold batch medians fall
0.4–20.1%, and session-open medians fall 0.6–4.1%; these warm-cache observations do not establish
a startup guarantee. Every raw observation and every fork median remains in the comparison.

| Lanes / recognizer threads / width | Pipeline idle RSS MiB, before → after | Held-output kernel idle RSS MiB | Closed + requested GC RSS MiB |
| --- | ---: | ---: | ---: |
| 1 / 1 / 320 | 340.73 → 259.09 | 449.68 → 271.44 | 450.30 → 271.76 |
| 1 / 1 / 3200 | 559.75 → 414.53 | 569.10 → 428.03 | 483.39 → 342.23 |
| 2 / 4 / 320 | 439.61 → 275.00 | 470.02 → 287.48 | 470.38 → 287.77 |
| 2 / 4 / 3200 | 627.57 → 559.26 | 720.22 → 580.28 | 577.32 → 405.47 |

Warm pipeline-idle RSS is 10.9–37.4% lower in these runs. Some setup RSS/non-heap observations
and final small retained Java heap measurements increase; the wide single-lane pipeline idle
heap rises 4.5% even as process RSS falls. This is not a universal reduction in every memory
category. Heap/direct-buffer reclamation and native allocator retention remain visible in all
raw lifecycle snapshots. No process-memory cap follows from this result.

The earlier [complete CTC capture](../results/2026-10-21-desktop-ocr-ctc/comparison.md) is retained
and superseded: its IsNaN/Where/ArgMax/GatherElements graph and recognizer-sized reduction pool
reduced Java allocation but regressed decoding and CPU, with a 20% wide two-lane idle-RSS
increase. Separate [kernel controls](KERNELS.md) identified the cheaper guarded TopK path and
one-thread/no-spinning configuration. The final capture above uses the revised production code;
old and new captures each have their own paired baseline, hashes and raw samples.

The completed [geometry comparison](../results/2026-10-21-desktop-ocr-geometry/comparison.md) uses
three fresh JVM forks per variant. All detector probability maps, ordered quads and Float-bit
rectangle outputs match exactly. Every case retains 31 ten-operation batch samples after at least
400 ms warmup; image generation, inference and hash validation are outside timing.

| Geometry case | Before µs | After µs | Change |
| --- | ---: | ---: | ---: |
| Seven-line rectangles | 4.264 | 4.262 | -0.05% |
| Seven-line full postprocessing | 1829.243 | 1867.623 | +2.10% |
| Dense-page rectangles | 12.988 | 12.603 | -2.96% |
| Dense-page full postprocessing | 4675.797 | 4645.168 | -0.66% |
| Slanted-page rectangles | 7.230 | 6.967 | -3.64% |
| Slanted-page full postprocessing | 1852.860 | 1825.626 | -1.47% |
| Synthetic hull, 16 vertices | 0.651 | 0.637 | -2.15% |
| Synthetic hull, 64 vertices | 7.182 | 6.991 | -2.66% |
| Synthetic hull, 256 vertices | 116.812 | 93.646 | -19.83% |
| Synthetic hull, 1024 vertices | 1747.184 | 944.060 | -45.97% |
| Reading order, 40 separated rows | 2.467 | 2.157 | -12.57% |
| Reading order, 400 separated rows | 113.600 | 23.552 | -79.27% |

Real detector hulls contain 8–24 vertices and bypass the large-hull support tree. Rectangles consume
under 0.5% of real-page postprocessing across captured forks; these captures establish no material
real detector speedup. The measured benefit is in synthetic large hulls and many separated reading-order rows.
The 128-point gate is retained because the measured larger hull cases benefit; improvements are
not extrapolated to every hull size, shape or overlap distribution.

Verification: **2,155 passed, 11 skipped, zero failures/errors**, plus resource lint and desktop
assembly. Shared has 2,015 cases (2,009 passed, six skips); desktop has 151 (146 passed, five camera
skips). Relative to `e120e01`, 31 additional regressions execute and one opt-in geometry diagnostic
is skipped in the ordinary suite. All 125 focused OCR tests pass, with three optional/diagnostic
skips, and all 12 Python capture contracts pass. Actual v6 and compatibility v5 outputs retain
exact decoded confidence/text. Tests include non-finite scores, ties, Unicode, blank/repeats,
concurrent lanes, fallback/reopen, native close/cancel/failed admission, output-buffer lifetimes,
geometry extrema/area ties/non-finite bounds and dense/stable row ordering.
[Counts, skips and exact source hashes](verification.json) identify the tested source. Full/focused
logs and XML are in `build/ocr-milestone-verification-20261021/`.

Reproduction is documented in [USAGE.md](USAGE.md). The baseline checkout has unchanged tracked
`e120e01` files plus only two generic test diagnostics copied byte-identically from current. A
test-only reflective adapter binds native decoding in the current checkout; the baseline compiles
and runs its original flat decoder. The init script adds separate tasks while retaining the
original nine-file harness and all earlier captures. Every task records its actual/configured JVM
identity and flags, complete classpath hashes, model/input/output/confidence identities, raw
samples and source provenance. Variant order alternates by fork. Outputs are immutable new
directories; failures retain evidence and prevent a successful comparison.

Recognition timings isolate the unchanged model's inference plus selected decoder, and separately
decode one held real native output. The kernel's common hash/parity setup copies output once in
both variants, then requests GC before timing. Session open is separate; cold inference includes
executor startup and lazy reducer opening. Images/preprocessing and the public engine's admission/
lifecycle orchestration are outside these timings. Even-count summaries use the recorded upper
median; the comparison takes the median of three fork summaries. All raw observations remain.

Java allocation and caller CPU cover measured lane threads. Process CPU includes ORT workers;
summed per-batch allocation is derived separately. Whole-process Linux RSS/HWM includes JVM heap,
JIT, libraries, native scratch/arenas and allocator retention. TopK working storage and the
reduction session still consume native memory. Heap savings alone establish no native
RSS reduction or memory cap. Requested GC does not guarantee collection or return to baseline.
These warm-cache diagnostics establish no whole-app latency, physical input, throughput, tail
latency, confidence interval, GPU or FPS result.
