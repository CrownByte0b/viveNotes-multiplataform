# Desktop optimization implementation — 2026-10-21

Implementation follows the [review and seven-phase plan](PERFORMANCE_REVIEW.md). The original
[three-fork baseline](results/2026-10-21/summary.md), fixture definitions and capture harness are
unchanged. Full JVM verification, lint and desktop assembly pass. The [final three-fork after capture](results/2026-10-21-optimized-final/summary.md)
and [complete comparison](results/2026-10-21-optimized-final/comparison.md) are retained; the sync
metadata-lock prerequisite described below remains pending.

## Changes

| Review | Implementation |
|---|---|
| F1 | Immutable document text/cell lookup; direct cell buffers; cached selected workspace owners and focused buffer; keyed text children with narrow selection inputs. |
| F2 | Streaming 140-character preview; geometry edits retain preview; workspace updates copy only changed owner branches. |
| F3 | Builder-based run normalization for paste/editing, preserving atomic equation policies. |
| F4 | Shared binary source/display offset index, link interval coverage and indexed clipped span projection; styled text caches exclude caret/geometry. |
| F5 | Monotone exact-match word cursor, prepared queries/text, bounded prefix typo DP and worker source registration/cache. |
| F6 | Exact endpoint-coverage grouping indexes with original cuts, ties, member order and BFS order. |
| F7 | Immutable append lists and retained 128-stroke native segments; original base/index/scene retained; incremental viewport raster adds only new sealed segments. |
| F8 | Explicit CPU dispatcher for document codec/preview/revision work; bounded exact-body decode single-flight; one body observation supplies initial load and later changes. |
| F9 | Checkpoints select only stroke state and active operation IDs, omitting point blobs/targets. Atomic capture/compression/commit remains serialized. |
| F10 | Dirty/pending picture-page reconciliation, new-file candidates, pure-ink fast path and transaction-local parent/deferred lookup cache. |
| F11 | Two bounded independent blob workers, per-digest serialization, unique staging, cancellation disconnects, generation guards and uploads completed before metadata references. Metadata coordinator mutex retained; see prerequisite below. |
| F12 | Rank-aware/keyset outbox reads using schema-2 indexes, immutable two-stack pending queue, keyset historical indexing outside the writer and batched attachment queries. |
| F13 | Query-independent OCR flights survive query edits; bounded lanes leave matching capacity; image placements grouped once; live document projection, worker source cache and authoritative SQLite snapshot corpus reuse. |
| F14 | Bounded immutable tokenizer vocabulary cache with artifact/generation invalidation; flat CTC buffer reduction under native output lifetime. ONNX still copies its flat output. |
| F15 | Quantized viewport/overscan demand, retained text/table measurements, lightweight offscreen scroll targets, focused/selected/search item pinning, two image decode lanes and a 64 MiB retained bitmap budget; thumbnail decode on a worker. |
| F16 | Streamed PDF source pages, bounded exact-identity preparation cache, lazy drawing assets, indexed reflow/collision/tile membership and visible paragraph/table-row drawing. |

Additional review findings addressed: persistent lasso path with all historical samples and draw-only
invalidation, indexed native lasso/group expansion, cached immutable ink selection metadata,
identical eraser preview/commit preparation reuse with one native lane, cached table styling/row
measurement independent of geometry, selected toolbar scroll observation isolated from the whole
canvas, targeted notebook export and independent consistent WAL backup readers, accepted sync
state omitting duplicated immutable ink points, cached OCR quad metrics and bounded model artifact
downloads.

Unknown text/table heights are measured before they may leave composition. Measurements invalidate
on content, wrapping width, header/ink-table flags, density and font-scale changes. Text keys also
include editing mode and thumbnail source. Media preparation must settle and publish its actual
placed height before a preview can leave composition; slow offscreen media therefore remains
composed during preparation. Offscreen boxes retain cheap geometry/scroll semantics; their
editors, previews and decoders leave composition once their measurements are valid.
Visible previews still retain a source editor to preserve existing caret, IME, clipboard and
accessibility contracts. No frame-rate claim follows from the isolated algorithm timings.

## Ownership and deliberate limits

- Schema 2, serialized document formats, canonical ink bytes, pinned models and dependencies stay
  compatible. Room writes/cursors and native drawing owners remain serialized. Background results
  validate their captured source/generation before publication.
- The bitmap budget counts retained ARGB pixels. Two source decodes/resamples and prior-frame
  references awaiting GC can raise transient native RSS above it; this is not a process memory cap.
- Distinct Normal erase requests still replay canonical operations. Identical final preview/commit
  work is reused; fragment/order behavior takes precedence over an unverified target-only rewrite.
- Retained ink segments avoid full-base work at append boundaries. Spatial queries still probe
  segment indexes; new sealing can reconcile segment metadata. These are not a claim of constant
  work for every operation on an arbitrarily long page.
- Detector minimum-area rectangle and bounded row-candidate scans retain their floating-point/tie
  behavior. Quad metrics are cached. No detector profile established enough cost to justify changing
  crop/order compatibility; rotating calipers/model-side ArgMax remain measured follow-ups.
- Checkpoint compression stays in the atomic writer snapshot. Narrow projections remove unused
  geometry reads without an unsafe off-transaction snapshot/retry protocol.
- Faster Object erase preparation makes intermediate masks visible that the older worker often
  cancelled before publication. Each changed exclusion set still rerasterizes the viewport, so
  some gestures do more raster/path work despite unchanged scene/index reuse. Raw total and phase
  counters are retained. A release-to-committed-draw reduction partly moves work before release;
  it does not establish an overall gesture CPU improvement. Dirty-region redraw, with exact
  overlap/blending/antialiasing tests, remains a measured follow-up.

## Sync prerequisite awaiting scope

Project memory in `memory/accounts.md` requires fixing stale-stream/version/cursor correctness in
Android first and porting its tests, with no independent desktop divergence. Read-only inspection
of Android HEAD `883895f` and local refs found no existing fix. A concrete patch and four regression
cases are prepared in
[pending-sync-freshness](pending-sync-freshness/REVIEW.md); the user was asked to choose matching fixes in
both apps, an explicit desktop-only override, or deferral. No answer has arrived. The freshness
patch and moving network work outside the metadata mutex remain unapplied. Bounded independent
byte requests inside the existing serialized coordinator are implemented and tested separately.

## Verification and comparison

The complete shared and desktop JVM suites passed: shared 1,906 passed/two existing skips;
desktop 146 passed/five existing skips; **2,052 passed, zero failures/errors, seven skips**.
That includes 106 additional cases since the baseline. Lint and desktop assembly passed in the
same invocation (2m 18s) under isolated Xvfb, with Wayland unset:

```sh
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE \
  JAVA_HOME=/usr/lib/jvm/java-latest-openjdk \
  xvfb-run -a -s '-screen 0 1920x1200x24' \
  ./gradlew :shared:jvmTest :desktopApp:test lint :desktopApp:assemble \
  --max-workers=1 --offline --console=plain
```

Regression coverage includes randomized exact offset/span/search/grouping/lasso equivalence,
retained native ink pixels at 128/256 boundaries and stable frames, query cancellation/shutdown,
bounded model/image/blob work, file-backed SQLite snapshots/corruption/external writers,
transaction hierarchy changes, selected-notebook transfer and backup during a paused writer.
Verification caught and fixed a concurrent staging-directory cleanup race; stage files are now
registered before HTTP with OS leases that also protect active cross-process downloads. A killed
child-process fixture checks abandoned lease reclamation. Delayed/missing/failed/cancelled video
fixtures check final canvas extent and culling rather than treating the initial URL height as final.
The final ink checks also cover source membership across base/sealed/tail deletion and readdition,
and exact native-cache pixels/bytes for the fractional sparse viewport. Raster sizing uses Double
endpoint subtraction before rounding, avoiding a redundant physical pixel on both axes.

The long-video fixture uses real wheel input. An existing Compose limitation merges horizontal
and vertical scroll semantics on the same canvas node into one horizontal action; the framework's
uncapped `performScrollTo` can repeatedly request an unconsumed vertical delta. This accessibility
follow-up is outside the optimization changes; actual scrolling remains covered by UI tests.

Deterministic local HTTP evidence (12 × 1 KiB, injected 20 ms latency): one worker 275.679 ms,
peak 1; two workers 126.734 ms, peak 2. This is a local latency-overlap diagnostic, not an internet
bandwidth promise; tests assert overlap/caps/retry/cleanup rather than a timing threshold.

## Measured results

These are medians of three fresh-JVM fork medians using the original 48 fixtures, 15 samples
per fixture/fork, Java 27+35 and unchanged heap/runtime dependencies. Initialization/fixture
construction is outside timing; warm lookup/cache results do not describe first-use cost.
Allocations count the caller's Java thread, excluding native memory and coroutine/SQLite workers.

| Isolated operation | Units | Before ms | After ms | Caller Java MB before → after |
|---|---:|---:|---:|---:|
| Warm text-buffer lookup | 2,000 | 59.956001 | 0.041077 | 200.976 → 0.336 |
| Table cell buffers | 200 | 0.364265 | 0.003916 | 0.280 → 0.034 |
| Move on 1M-character page | 1,000,000 | 0.446642 | 0.000154 | 7.002 → 0.001 |
| Linked preview projection | 4,000 | 20.595833 | 0.535416 | 1.472 → 1.600 |
| Plain HTML paste | 4,000 | 4.188574 | 0.157607 | 80.904 → 0.530 |
| Repeated substring search | 4,000 | 3.912477 | 0.035486 | 0.173 → 0.131 |
| Equal-gap PDF groups | 4,000 | 250.307131 | 4.995581 | 1248.317 → 24.304 |
| Warm tokenizer decode | 50,000 | 8.995993 | 0.001707 | 51.919 → 0.001 |
| 127th append, 40k base | 40,000 | 0.020379 | 0.019116 | 0.023 → 0.025 |
| 128th append, 40k base | 40,000 | 6.178660 | 0.018665 | 9.410 → 0.025 |
| Ink checkpoint | 40,000 | 49.739669 | 25.666143 | unavailable |

47 of 48 operation medians fell. The exception is the ordinary 127th append on a 10k base:
**51.428 → 70.163 µs (+36.4%)**, an 18.735 µs increase. Its three after fork medians
are 70.845, 69.171, 70.163 µs. This regression remains in the frozen comparison. Fixed native cases get
only eight warmup calls; the earlier 1k boundary case also gives the old/new code different JIT
training because its old full compaction was removed. Source inspection finds no 10k-base walk
in this operation: both versions rebuild a 127-projection tail, with no retained segments yet.

A [separate warmed diagnostic](results/2026-10-21-optimized-final/ink-warm-diagnostic/PROTOCOL.md)
uses the exact 10k fixture, 20,000 warmup calls and 50 single-operation samples in each of three
alternating original/current JVMs. Median **12.564 → 10.900 µs (-13.2%)**, allocation
23,192 → 21,464 bytes. Raw fork medians are before 14.037/12.564/7.354 µs and after
10.900/11.241/5.741 µs. Runtime, heap, every JVM argument and all 120 external JAR hashes match.
The original archive matches all 315 baseline production and nine harness hashes. This supports
warmup sensitivity; it does not replace the original protocol or establish completed C2
compilation. Variance is visible in the retained raw samples.

Preview indexing deliberately trades a small allocation increase for less repeated work:
4k-paragraph caller allocation is 1.472 → 1.600 MB (+8.7%), while time falls 97.4%.
Ordinary 40k append allocation is 23,408 → 25,024 bytes (+6.9%), with a lower median time;
the 128th append drops from 9.410 MB to about 0.025 MB. These increases are retained in the
raw summaries rather than presented as universal allocation reductions.

[Renderer comparison](results/2026-10-21-optimized-final/ink-comparison.md) covers all eight
40k-stroke cases in each fork. All 24 preserve zero held scene/raster/finished-path/vector
rebuilds, six held observations/advances, zero Object preview scene/index builds and the original
2,097,152–2,105,352-byte peak retained raster range. Handler-to-next-draw medians vary from
20.7% lower to 3.2% higher. All 166 structural counter differences remain visible case by case.
For example, fit/density1/32 additions performs two more preview rasters and 73,252 more finished
path/vector draws overall. This is visible intermediate feedback and real additional work, as
described above. Release-to-commit timing excludes work completed before release and includes
polling/screenshot scheduling. Neither timing is physical input-to-photon latency, FPS or a
whole-application CPU/RSS measurement.

[Integrity audit](results/2026-10-21-optimized-final/integrity-audit.txt) verifies all 334 after
production/resource/schema hashes, all nine original harness hashes, all 120 runtime artifacts
across the after forks, unchanged fixtures/external dependencies/schema, and unchanged baseline
bytes. The after code is an uncommitted working tree on the recorded `sourceCommit`; use its
exact source manifest and recorded working-tree state for provenance.

The [intermediate capture](results/2026-10-21-optimized/STATUS.md) is preserved and marked as
superseded. Final [full JVM log](results/2026-10-21-optimized-final/full-jvm-verification.txt),
[exact test counts](results/2026-10-21-optimized-final/verification-summary.json),
[loopback raw evidence](results/2026-10-21-optimized-final/blob-overlap-evidence.json), raw app/ink
JSON and all six fork logs are retained. All 24 Python tooling/scanner tests pass; the final
redacted [full-tree scan](results/2026-10-21-optimized-final/security-audit.json) reports zero findings. The scanner exceptions identify only exact
manifest/source-path/SHA entries, preserving credential detection in each recorded manifest.

Reproduce the final capture and comparisons from the repository root, with no competing tests
or CPU-heavy jobs. Choose a new output directory:

```sh
JAVA_HOME=/usr/lib/jvm/java-latest-openjdk python3 benchmarks/capture.py \
  --output benchmarks/results/next --forks 3 --include-ink
python3 benchmarks/capture.py --compare benchmarks/results/2026-10-21 benchmarks/results/next
python3 benchmarks/compare_ink.py benchmarks/results/2026-10-21 benchmarks/results/next
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks -p 'test_*.py'
PYTHONDONTWRITEBYTECODE=1 python3 .github/tests/test_gitleaks.py
```

