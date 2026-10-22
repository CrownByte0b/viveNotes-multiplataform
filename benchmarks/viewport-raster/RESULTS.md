# Desktop viewport raster reuse — 2026-10-22

The desktop ink layer now retains up to four exact recent viewport rasters within the existing **64 MiB total retained-pixel budget**. Returning to an unchanged recent view can reuse its image without vector rendering. In three alternating native benchmark pairs, return-pan sequences improve **71.40–75.34%** and zoom-return sequences improve **60.41–64.06%**. New views still render fully; new-pan controls range from 2.59% faster to 1.56% slower.

The cost is additional retained images: approximately 1→2 MiB for return-pan, 1→3 MiB for zoom-return, and up to 4.016 MiB for unique views in the 512-pixel diagnostic. The 64 MiB limit covers this cache's retained image pixels, not temporary surfaces, the additions cache, native paths, Java heap or process RSS. A different floating-point viewport/transform/scale is a different view.

The unchanged full-app viewport phase changes by −2.14% on X11 and −0.11% on Windows; overall callback sums are +0.17% on both. Windows process CPU rises 4.95%, and sampled peak resident memory rises 0.92%/0.21% on X11/Windows. The measurements establish the exact-return benefit, not a broad full-app panning improvement.

## Method and exact pixels

Before is the user's clean `5cbf74f9ca6fb83c51be3d41818894467aac1a9d` ink-reopening commit. After is that base plus six exact uncommitted source/test files in [verification.json](verification.json). The native before build overlays only the identical new opt-in probe; the original ink and full-app before builds are fully clean. Both variants use isolated source copies with empty OAuth build configuration. Sources, compiled artifacts, toolchains, dependencies and probes have separate hashes and retained build logs.

A preliminary experiment shifted a full raster by 24 physical pixels. All nine density/scale combinations failed exact overlap comparison, with 158–2,040 different interior pixels even after excluding a four-pixel border. [The comparison](../results/2026-10-22-desktop-viewport-raster/shift-experiment.json), experiment source and 18 PNGs are retained. Existing dirty-region regressions also establish that changing Skia's vector clip can change antialiasing. These observations reject naïve shifted reuse under the current exact-pixel requirement; they do not prove every possible tiling algorithm impossible.

The chosen cache moves image ownership between the active view and an LRU of prior exact views. Scene/exclusion identity, renderer identity/version, viewport, transform and scale must match. Content or renderer changes discard other views and preserve the existing current-view append/damage path. Invalid input is rejected before altering valid entries. Clear, page disposal and close release every retained image. Oversized or disabled retention draws transiently. No ByteInk, dependency, persistence, Android/server or GC-policy change is included.

Work snapshots count requests, no-build hits, full/append/dirty builds, vector draws, full allocated surface pixels, updated rectangle pixels, capacity evictions and retained views/bytes. Updated pixels are not total Skia fragment work. Production debug logging emits one summary on ink-layer disposal, with its pre-release counts; normal launches emit none. Test diagnostics verify zero retained bytes after release.

## Native viewport sequences

The new [capture tool](USAGE.md) runs six serial fresh Zulu 27+35 JVMs in B/A, A/B, B/A order, using the same native library and external classpaths, a 4 GiB heap and owned Xvfb. Each process runs 20 cases: five eight-view sequences at two densities and fit/sparse scales. Every sequence starts with an empty raster cache. Eight warmups and seven measured samples use fixed work. Every validation view matches a fresh published ByteInk full-raster PNG exactly before timing. The fixture contains 40,000 distinct native meshes.

Values below are medians of three fork medians for an **eight-view sequence**, including cache clear, surface clear and image blits. They measure synchronous software work, not UI scheduling, GPU execution, presentation, FPS or physical input latency. Raw per-fork timings, caller-thread allocations and all counters remain in [native/comparison.json](../results/2026-10-22-desktop-viewport-raster/native/comparison.json).

| Sequence | Density / view | Before ms | After ms | Change |
| --- | --- | ---: | ---: | ---: |
| stationary | 1 / fit | 112.458 | 110.496 | -1.74% |
| pan_new | 1 / fit | 736.137 | 743.353 | +0.98% |
| pan_return | 1 / fit | 875.929 | 215.996 | -75.34% |
| zoom_return | 1 / fit | 679.601 | 251.523 | -62.99% |
| fractional_pan | 1 / fit | 865.847 | 865.907 | +0.01% |
| stationary | 1 / sparse | 3.127 | 3.051 | -2.42% |
| pan_new | 1 / sparse | 20.492 | 20.813 | +1.56% |
| pan_return | 1 / sparse | 20.561 | 5.549 | -73.01% |
| zoom_return | 1 / sparse | 16.817 | 6.525 | -61.20% |
| fractional_pan | 1 / sparse | 20.418 | 20.534 | +0.57% |
| stationary | 2 / fit | 113.823 | 115.375 | +1.36% |
| pan_new | 2 / fit | 782.844 | 762.569 | -2.59% |
| pan_return | 2 / fit | 898.435 | 221.687 | -75.33% |
| zoom_return | 2 / fit | 710.951 | 255.503 | -64.06% |
| fractional_pan | 2 / fit | 908.848 | 891.642 | -1.89% |
| stationary | 2 / sparse | 1.836 | 1.859 | +1.28% |
| pan_new | 2 / sparse | 11.112 | 11.157 | +0.40% |
| pan_return | 2 / sparse | 11.101 | 3.175 | -71.40% |
| zoom_return | 2 / sparse | 8.760 | 3.468 | -60.41% |
| fractional_pan | 2 / sparse | 11.173 | 11.082 | -0.82% |

Across the 15 warmup/measured sequences per case, return-pan drops from 120 to 30 builds (90 hits); zoom-return drops from 120 to 45 builds (75 hits). Stationary remains 15 builds/105 hits; unique and fractional pan remain 120 builds/zero hits. There is no new-view work reduction. All three forks preserve these counts and exact pixels. Retained bytes grow as described above; all remain below the unchanged total pixel budget.

## Original append and erase controls

The unchanged original nine-file harness and `benchmarks/ink/capture.py` ran 12 fresh JVMs: three app and three renderer processes per variant. All 48 original app fixtures and eight renderer cases per fork remain validated and retained. These compare the current committed ink implementation with this viewport change; they do not replace the historical comparison against `602ff15` in [the original ink report](../ink/RESULTS.md).

| Ink app control | Rows | Before ms | After ms | Change |
| --- | ---: | ---: | ---: | ---: |
| ink.apply_127th_append | 1000 | 0.072227 | 0.070193 | -2.82% |
| ink.apply_128th_append | 1000 | 0.106222 | 0.114187 | +7.50% |
| ink.apply_127th_append | 10000 | 0.039435 | 0.040627 | +3.02% |
| ink.apply_128th_append | 10000 | 0.065013 | 0.073751 | +13.44% |
| ink.apply_127th_append | 40000 | 0.012534 | 0.012974 | +3.51% |
| ink.apply_128th_append | 40000 | 0.023225 | 0.024196 | +4.18% |
| storage.append_ink_coalesced | 1000 | 0.201182 | 0.194951 | -3.10% |
| storage.append_ink_checkpoint | 1000 | 1.705261 | 1.832132 | +7.44% |
| storage.append_ink_coalesced | 10000 | 0.130708 | 0.140527 | +7.51% |
| storage.append_ink_checkpoint | 10000 | 11.717390 | 11.891771 | +1.49% |
| storage.append_ink_coalesced | 40000 | 0.147881 | 0.158892 | +7.45% |
| storage.append_ink_checkpoint | 40000 | 46.031918 | 46.804136 | +1.68% |

| Erase commit view | Density | Additions | Before ms | After ms | Change |
| --- | ---: | ---: | ---: | ---: | ---: |
| fit | 1.0 | 1 | 256.571 | 263.607 | +2.74% |
| fit | 1.0 | 32 | 190.838 | 187.180 | -1.92% |
| sparse | 1.0 | 1 | 4.350 | 4.256 | -2.15% |
| sparse | 1.0 | 32 | 3.734 | 3.253 | -12.87% |
| fit | 2.0 | 1 | 188.436 | 192.055 | +1.92% |
| fit | 2.0 | 32 | 148.741 | 158.283 | +6.41% |
| sparse | 2.0 | 1 | 3.155 | 3.116 | -1.23% |
| sparse | 2.0 | 32 | 2.862 | 2.782 | -2.78% |

The original comparison records 43 structural counter deviations and 15 failed historical preservation expectations; none is removed or weakened. All 15 failed expectations also fail identically on the clean baseline: four sparse cases report zero full-scene builds, and density-1 fit with one addition reports two, while the historical check expects exactly one. The [independent expectation audit](../results/2026-10-22-desktop-viewport-raster/legacy-expectation-audit.json) confirms no new failed expectation. Of 43 counter deviations, 24 describe one fewer intermediate erase-preview raster and its vector/path totals in three paired observations; 19 are draw-count differences. These are retained observations, not a measurement of the new exact-return path.
The 40k 128th append is 23.225→24.196 µs (+4.18%). Fit erase commits range from −1.92% to +6.41%; sparse cases improve 1.23–12.87%. [All handler samples, three raw erase-commit observations per case, counters and failures](../results/2026-10-22-desktop-viewport-raster/legacy-ink/comparison.md) remain visible. Erase timing includes release dispatch, worker commit, redraw, polling and scheduling. This cache does not change the global cleanup or 128th-append algorithms, and these controls do not establish a repair for their earlier regressions.

## Actual app controls on X11 and Windows

The unchanged [text-revisit runtime workload](../text-preparation/USAGE.md) opens 40,000 native-encoded strokes and 1,000 text boxes, draws eight strokes, pans/zooms, selects/clears a lasso, edits/saves text, revisits both pages and closes. Each platform runs six fresh processes in B/A, A/B, B/A order with 512 MiB initial / 2 GiB maximum heap, JFR profile, NMT summary, identical debug logging and Robot pacing. All persistence, close, compatibility and JFR observer-count checks pass. Both versions preserve the prior validated ink-reopening load sequence, including eight-row prefix extension and final exact reuse.

Both platforms use Zulu 27+35. X11 uses an owned 1920×1200 display at scale 1. Windows uses its console renderer at 3840×2160 physical / 2194×1234 AWT logical, scale 1.75. App frames are 1280×900 logical. Compare within each platform. The scripted viewport phase includes pacing and does not guarantee exact cache-key revisits; these controls are separate from the fixed native return sequences.

Values are medians of three per-fork observations. Callback quantiles are per-fork nearest ranks, not pooled; changing callback counts affects their interpretation. [All observations and counts](../results/2026-10-22-desktop-viewport-raster/comparison.json) are retained.

| Observation | X11 before → after | Change | Windows before → after | Change |
| --- | ---: | ---: | ---: | ---: |
| JVM main → workspace, ms | 2,889.36 → 2,831.92 | -1.99% | 3,799.08 → 3,669.69 | -3.41% |
| Viewport scripted phase, ms | 2,296.86 → 2,247.73 | -2.14% | 2,212.03 → 2,209.68 | -0.11% |
| Viewport callback sum, ms | 601.78 → 563.72 | -6.33% | 839.84 → 819.50 | -2.42% |
| Viewport callback p95, ms | 23.57 → 23.70 | +0.53% | 30.42 → 29.66 | -2.50% |
| Viewport worst callback, ms | 25.24 → 23.81 | -5.67% | 30.54 → 31.33 | +2.58% |
| Viewport callbacks | 33.00 → 32.00 | -3.03% | 37.00 → 36.00 | -2.70% |
| First ink return, ms | 599.43 → 600.08 | +0.11% | 238.65 → 229.25 | -3.94% |
| Final ink return, ms | 510.63 → 512.05 | +0.28% | 201.74 → 177.06 | -12.24% |
| First text phase, ms | 3,045.49 → 3,036.93 | -0.28% | 2,027.23 → 2,044.40 | +0.85% |
| First text callback p95, ms | 79.70 → 81.50 | +2.26% | 101.59 → 87.07 | -14.29% |
| First text worst callback, ms | 114.39 → 115.51 | +0.98% | 117.77 → 121.01 | +2.75% |
| Text revisit, ms | 516.99 → 520.85 | +0.75% | 186.40 → 192.95 | +3.52% |
| Text revisit callback p95, ms | 10.75 → 9.48 | -11.83% | 81.13 → 66.17 | -18.43% |
| Text revisit worst callback, ms | 44.57 → 46.04 | +3.31% | 81.13 → 66.17 | -18.43% |
| All callback sum, ms | 2,543.50 → 2,547.71 | +0.17% | 3,451.55 → 3,457.39 | +0.17% |
| All callback p95, ms | 48.50 → 48.02 | -0.99% | 65.61 → 66.13 | +0.79% |
| All callbacks | 343.00 → 346.00 | +0.87% | 276.00 → 279.00 | +1.09% |
| Process CPU at shutdown hook, s | 27.30 → 27.26 | -0.15% | 30.33 → 31.83 | +4.95% |
| Sampled peak resident memory, MiB | 1,962.31 → 1,980.29 | +0.92% | 1,577.55 → 1,580.86 | +0.21% |
| Final used Java heap, MiB | 440.50 → 446.50 | +1.36% | 220.00 → 214.00 | -2.73% |
| Final committed Java heap, MiB | 1,115.00 → 1,117.00 | +0.18% | 512.00 → 512.00 | +0.00% |
| Maximum observed used heap, MiB | 448.50 → 452.50 | +0.89% | 388.00 → 382.00 | -1.55% |
| Final NMT commitment, MiB | 1,407.11 → 1,406.13 | -0.07% | 792.45 → 805.55 | +1.65% |
| Process CPU in initial 2 s idle, ms | 30.00 → 20.00 | -33.33% | 46.88 → 0.00 | -100.00% |
| Process CPU in final 2 s idle, ms | 10.00 → 10.00 | +0.00% | 15.62 → 31.25 | +100.00% |

Timing regressions and higher memory observations in these controls remain part of the result. Three short forks do not isolate their causes or establish statistical significance. The new disposal counters include stationary cache hits, so they do not independently identify historical-view hits in the real-app script. The largest observed pre-release retained-pixel snapshots are 9.49 MiB on X11 and 27.23 MiB on Windows; these snapshots are not whole-process peak memory. Natural GC states differ, NMT excludes third-party native allocations, and sampled RSS may miss peaks. Neither lower RSS nor the retained-pixel cap establishes a total native-memory bound or a leak result. Callback elapsed time is not exclusive CPU time or physical latency. Startup and idle-GC investigations remain later milestones.

## Verification and retained evidence

- Full shared/desktop JVM suites, lint and assembly pass: **2,233 passed, 14 skipped, zero failures/errors**, in 3m4s. Shared: 2,078 passed/7 skipped; desktop: 155 passed/7 skipped. One new opt-in benchmark accounts for the extra skip over the previous milestone. Six new native regressions and two Compose regressions cover exact fractional/transformed pixels, transparent overlap, LRU/byte limits, content/renderer invalidation, append/damage/undo, invalid requests, disabled/oversized retention, disposal and debug logging. Existing dirty-region, cancellation and reclamation regressions remain unchanged.
- Focused Linux verification passes 27 tests. Windows passes **29 focused JVM tests**, including the eight new cases and existing rendering/reclamation tests, on Linux-built target-25 JARs under Zulu 27. This is actual Windows runtime coverage, not a native Windows Gradle build or full suite.
- Python: 63 contracts pass on Linux (41 runtime, 3 text, 11 original ink, 8 viewport); Windows passes 43 runtime/text contracts with one existing symlink-capability skip. Embedded probe/render contracts and native SQLite fixture checks pass. All 14 scanner contracts and the final redacted changed-file scan pass.
- All six native, twelve original ink-control and twelve full-app captures retain exact source/runtime/tool identities and raw evidence. All full-app comparisons and JFR count-parity analyses pass. Windows raw hashes, decoded JFR analysis and exported JSON were independently recomputed on Linux. Final audits verify 691 frozen original/protected files, the six tested sources and the empty Git index.
- The initial lint attempt rejected four debug strings as UI text. Routing the summary through the existing recognized logging API fixed that check; no lint rule changed. Both full-suite logs are retained, and the final full suite/lint/assembly was rerun after the correction. Initial native preflights and the shifted-raster experiment are exploratory and excluded from paired timing tables.
- The first Windows baseline runtime attempt saved the token inside the paragraph prefix after the driver's End-key step. The saved token remained intact, but the driver's later exact-prefix lookup timed out. That failed capture, screenshot, accessibility tree and trace are retained separately. The same source, binaries, frozen probe and workload were retried in a fresh results directory using three additive verified runner files; only the output location changed. The six completed Windows captures supply the table. This does not establish a fix for that intermittent caret/input behavior.

Exact test/build/capture commands, source hashes and result hashes are in [verification.json](verification.json), [USAGE.md](USAGE.md) and `build/viewport-raster-verification-20261022/`. The full-suite command is:

```sh
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
  JAVA_HOME=/home/mahalo/.gradle/jdks/azul_systems__inc_-27-amd64-linux.2 \
  xvfb-run -a -s "-screen 0 1920x1200x24 -nolisten tcp" \
  ./gradlew :shared:jvmTest --rerun :desktopApp:test --rerun lint \
  :desktopApp:assemble --offline --max-workers=1
```

Public native and original-ink captures, sanitized full-app exports and comparisons are in [the result directory](../results/2026-10-22-desktop-viewport-raster/). Private raw traces, synthetic profiles, PNGs, source/build inputs, classpaths, logs and XML have owner-only durable archives outside the repository and `/tmp`, indexed at `/home/mahalo/.local/state/vive-runtime-benchmarks/2026-10-22-viewport-raster/retention.json`. The previous runtime archive retains the unchanged portable Windows Java/Python distributions. New VM sources/results are `vivenotes-viewport-20261022` and `vivenotes-viewport-results-v2-20261022` under `C:/Users/z0/byteink-verification/`; the initial failed attempt remains in `vivenotes-viewport-results-20261022` and the retained raw archive.

Owned Xvfb and test processes are closed. The owned Windows console is logged off, SSH is closed, and RDPWindows is restored to **shut off**, its entry state. Other VMs and user notes/accounts were untouched. Native Wayland timing and hosted Windows popup-keyboard CI remain separate limitations.

Ready for the user's commit. No staging, commit or push was performed. Pause before startup/GC work until the user resumes.
