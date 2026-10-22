**Desktop ink optimization milestone — 2026-10-21**

This milestone changes desktop ink geometry, eraser preparation, selection exclusions and raster
updates. The baseline is the user's committed sync milestone, `602ff15`. Checkpoints and sync stay
as committed. OCR and full-app runtime follow-ups remain archived for the next user-approved
continuation. No Android, server, sibling library, dependency or storage-format changes were made.

The final [comparable capture](../results/2026-10-21-desktop-ink-verified/comparison.md)
completed all 12 task launches and passed source, workload, runtime and dependency identity
validation. It retains every raw sample and regression. Ordinary append is 25–35% faster;
sparse erase commit is 44–52% faster. **Fit-view erase commit remains 13–69% slower when
the 256-entry cleanup threshold triggers.** Cleanup frees redundant geometry/index metadata
but rebuilds the filtered global base and its renderer scene. The one-base fix removes the
manufactured chunks; it does not eliminate that global rebuild or its release latency.

App values below are medians of three fork medians. Allocations are caller-thread bytes per
operation, excluding native memory; repository cases have no caller allocation measurement.

| Case | Existing strokes | Before µs | After µs | Change | Before bytes | After bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `ink.apply_127th_append` | 1,000 | 132.341 | 86.314 | -34.78% | 34,552 | 14,664 |
| `ink.apply_128th_append` | 1,000 | 132.201 | 140.987 | +6.65% | 34,896 | 53,480 |
| `ink.apply_127th_append` | 10,000 | 71.326 | 46.298 | -35.09% | 25,024 | 14,664 |
| `ink.apply_128th_append` | 10,000 | 76.736 | 75.494 | -1.62% | 25,328 | 53,480 |
| `ink.apply_127th_append` | 40,000 | 18.415 | 13.806 | -25.03% | 25,024 | 14,664 |
| `ink.apply_128th_append` | 40,000 | 18.225 | 25.178 | +38.15% | 25,328 | 53,480 |
| `storage.append_ink_coalesced` | 1,000 | 256.778 | 244.054 | -4.96% | — | — |
| `storage.append_ink_checkpoint` | 1,000 | 2,225.889 | 2,242.070 | +0.73% | — | — |
| `storage.append_ink_coalesced` | 10,000 | 175.864 | 195.641 | +11.25% | — | — |
| `storage.append_ink_checkpoint` | 10,000 | 14,309.809 | 14,774.703 | +3.25% | — | — |
| `storage.append_ink_coalesced` | 40,000 | 168.660 | 231.960 | +37.53% | — | — |
| `storage.append_ink_checkpoint` | 40,000 | 53,774.465 | 52,106.135 | -3.10% | — | — |

The 128th append seals a chunk and updates searchable locations: its 40k case is 6.953 µs
slower and allocation rises to 53,480 bytes. Ordinary appends save 10,360 bytes at 10k/40k
(19,888 at 1k); the seal tradeoff is retained rather than hidden by an amortized average.
The unchanged repository controls also vary, including +37.53% for 40k coalesced storage
append. Those cases exercise the byte-identical committed repository path; these captures
alone do not establish the cause of that control regression or a storage improvement.
Warm JVM carry-over, GC and scheduling remain part of the measurement scope.

| 40k renderer view | Density | Appends | Before erase commit ms | After ms | Change | Handler change |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fit | 1 | 1 | 177.240 | 299.226 | +68.82% | +7.12% |
| fit | 1 | 32 | 157.692 | 211.917 | +34.39% | -1.49% |
| sparse | 1 | 1 | 8.664 | 4.824 | -44.32% | +3.85% |
| sparse | 1 | 32 | 8.127 | 3.932 | -51.63% | -9.40% |
| fit | 2 | 1 | 208.995 | 236.840 | +13.32% | +2.33% |
| fit | 2 | 32 | 135.205 | 163.066 | +20.61% | -3.43% |
| sparse | 2 | 1 | 5.364 | 2.908 | -45.79% | +5.01% |
| sparse | 2 | 32 | 5.025 | 2.718 | -45.91% | -5.59% |

The renderer comparison reports **159 counter deviations and 12 failed historical preservation
expectations**. All 12 failures are the explicit `fullPageSceneBuilds == 1` expectation: fit cases
now build a second global scene when cleanup replaces the base. Every failure remains in the
unchanged comparison output. Fit commit draws the same 36,356 vectors and makes one replacement
scene, versus 311 in the superseded implementation. Sparse commit drops from 1,169 vectors
to three at density 1 and 276 to zero at density 2. Exact geometry and PNG regression tests pass;
the historical one-global-scene expectation has intentionally not been weakened.

Frozen task heaps remain 2 GiB for app and 4 GiB for renderer; all forks use Zulu 27+35.
The original nine-file harness and three earlier frozen capture directories are unchanged.
The final source hashes match the full test run and the complementary references.


The separate [current-reference capture](../results/2026-10-21-desktop-ink-references-verified/summary.json)
has three fresh JVM forks, seven samples and eight warmups per case. These controls compare the
current public canonical replay/global rebuild and original native full raster with the optimized
algorithms in the same process. They are supplementary algorithm comparisons, not historical
before/after application measurements. Setup, initial editing partition, mask decoding, PNG
validation and UI scheduling are excluded from these warm timings. Normal fixtures include
16 historical erases and eight independent current masks, each targeting one stored row. Raster
sequences accumulate one through eight small visible exclusions. Larger target sets, long
overlapping paths and the historical capture's threshold-triggering cleanup have different costs.

| Eight-update sequence | Reference ms | Optimized ms | Change |
| --- | ---: | ---: | ---: |
| Normal erase previews, 1k strokes | 5.0650 | 1.1959 | -76.39% |
| Normal erase previews, 10k strokes | 12.5920 | 0.4009 | -96.82% |
| Normal erase previews, 40k strokes | 49.4748 | 0.3382 | -99.32% |
| Object raster updates, 40k, fit, density 1 | 977.6372 | 1.2309 | -99.87% |
| Object raster updates, 40k, fit, density 2 | 1048.1993 | 0.9943 | -99.91% |
| Object raster updates, 40k, sparse, density 1 | 23.1698 | 1.0043 | -95.67% |
| Object raster updates, 40k, sparse, density 2 | 11.2213 | 0.8819 | -92.14% |

At 40k, eight Normal previews allocate 254,184 caller-thread bytes versus 98,917,736 for full
replay. The raster reference and optimized cases both build 120 rasters across their 15 warmup
and sample sequences. Fit cases draw 4,399,380 versus 1,755 finished stroke vectors; sparse cases
draw 146,460 versus 225 at density 1, and 36,180 versus 105 at density 2. All three forks have
identical work counts and exact PNG comparisons pass. Each retains the same viewport image bytes:
1,052,676 for fit and 1,048,576 for sparse. This excludes transient copy surfaces and native meshes,
paths and indexes. `lastUpdatedPixels` describes the changed rectangle; overlapping vectors still
rasterize under the original full viewport clip before unchanged strips are restored.

The implementation removes several distinct sources of duplicated work:

- Small segment indexes and selection maps are lazy, so superseded append tails avoid building
  native indexes nobody queries. A persistent ordered AVL stores chunks, bounds and row/group
  locations. Shared groups and fragmented rows also use persistent position trees, avoiding
  quadratic list copying when one identifier occupies thousands of chunks.
- Sparse queries prune unrelated segment bounds before native tests and keep canonical drawing
  order. Strictly newer Normal erases subtract only the new mask from current target chunks.
  Historical shared move/resize clamps remain applied once; undo and late/equal operations use
  canonical replay. A cached editing partition is shared by previews from the same source.
- Pointer events publish cheap immutable observations. Native prefixes freeze once per requested
  preview or release, and the final request reuses its result. Input rebasing has a separate tiny
  lock, so it cannot wait behind native preparation. Adjacent duplicate validation uses normalized
  native coordinates/time, including density-rounding collisions, and preserves A→B→A input.
- Selection exclusions iterate the smaller of each local scene and the global selection. This
  removes the selection-by-chunk cross-product when a drag begins or its exclusions invalidate;
  existing Compose memoization already reuses the result across drag offsets.
- Safe small raster changes redraw ordered overlapping candidates and restore unchanged integer
  strips with SRC copies. Offscreen changes reuse the raster. Unsafe transforms, large damage,
  and large float cancellation retain the full-render fallback and exact Skia clipping/AA.
- Whole-row deletion releases hidden sealed/tail projections immediately. Worker reclamation
  removes redundant base geometry and memberships at the existing 256-entry thresholds. Object
  cleanup keeps a filtered global base together, reuses unaffected chunks and compacts empty slots
  only when at least 256 occupy at least half the store. Only Normal editing partitions that base.
  Publication accepts the exact current source and preserves document, pending edits, history,
  storage state, portable inputs, operation clock and extents.

Reclamation is still linear in the global base and may rebuild its native index. Its first Normal
editing partition is also linear. Old undo snapshots may retain old native indexes. Sparse bounds
can overlap enough to visit every chunk; a fragmented target may have arbitrarily many pieces.
Operation identity/time admission still scans history, whole-row deletion filters source/portable
lists, an extent shrink can scan live bounds, and rendering reconciles the chunk list. These
remaining costs are not included in a constant-time claim. The serial preparation lane preserves
operation order and moves native work away from pointer/UI handling; it does not parallelize native
operations without an ordering contract. The 64 MiB raster limit bounds each retained raster,
not total heap, history or native RSS.

Verification: **2,124 passed, 10 skipped, zero failures/errors**, plus resource lint and desktop
assembly. The shared suite has 1,983 cases (1,978 passed, five skipped); desktop has 151 (146 passed,
five camera skips). The milestone adds 33 executing regressions and one opt-in diagnostic relative
to `602ff15`. All 121 focused ink tests pass, including exact native triangles/outlines, ordering,
shared clamps, undo/late operations, source-only membership, persistent-tree work bounds, concurrent
index readers, input-lock isolation, cancellation/stale publication and visible Compose pixels.
Eight standalone raster tests pass in a cold test process, and all 11 capture-tool contracts pass.
The optional paired scenario actually executes and passes separately in each of its three forks.
[Counts, skip identities and source hashes](verification.json) identify the tested sources;
full logs/XML are retained in `build/ink-milestone-verification-20261021/`.

The first [comparable capture](../results/2026-10-21-desktop-ink-final/comparison.md) is retained
as superseded evidence. It found a fit-view cleanup regression: reclaiming 310 hidden projections
manufactured 311 scene/index objects. That evidence prompted the one-base cleanup fix rather than
changing the workload or reclamation thresholds. The first optional
[reference attempt](../results/2026-10-21-desktop-ink-references/INCOMPLETE.txt) failed because a
standalone test process lacked a registered graphics backend. Its log/XML remain intact; the
scenario and standalone raster tests now explicitly initialize the same backend as production.
Only completed verified captures support the final claims.

Reproduce the comparable capture using [USAGE.md](USAGE.md), a clean `602ff15` checkout, the current
sources, Java 27 and a fresh output directory. The runner validates the original nine harness
files, all 48 app workloads and eight renderer cases, external dependency/native bytes, JVM
launcher/flags, raw samples and source hashes. Runs are serial and variant order alternates by
fork. App timings use three fork medians; renderer commit timing has one observation per fork,
with all three values retained. Reported p95 values are batch averages, not individual-event tails.
These warm CPU/software and test-scheduling measurements do not measure physical input-to-photon,
whole-app FPS, GPU latency, cold startup or a native-memory bound.

The complementary reference command below must run serially three times, using a fresh report
path and a fresh JVM for each fork. `followup.init.gradle` forces execution and gives only this
large optional scenario a 4 GiB heap; ordinary tests keep their usual heap. Remove inherited
Wayland variables, as in the recorded commands in the reference summary.

```sh
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE \
  JAVA_HOME=/usr/lib/jvm/java-latest-openjdk \
  VIVE_INK_FOLLOWUP=1 VIVE_INK_FOLLOWUP_REPORT=/tmp/ink-reference-fork-1.json \
  xvfb-run -a -s '-screen 0 1920x1200x24' \
  ./gradlew :shared:jvmTest --tests '*InkFollowupPerformanceScenario' \
  --max-workers=1 --offline --console=plain --no-configuration-cache \
  -I benchmarks/ink/followup.init.gradle
```

Final redacted secret scan of all 131 changed/new files (4,686,252 bytes) found zero leaks in
314 ms. Scanner rules and exceptions are unchanged. All 18 tested ink source hashes, nine committed
sync source hashes and 50 archived follow-up hashes match; the original captures/harness remain
intact. The Git index is empty. Work is paused for the user's commit after this major milestone.
