# Canvas, text, ink, interaction performance review — 2026-10-21

Scope: current application source, after reading project instructions and relevant project memory. No production/build changes and no benchmark/test execution by this reviewer. At the parent's later request, added two benchmark/test source files only (see below). Timing claims must come from the parent's fresh baseline. The sibling ByteInk source was consulted for candidate APIs only; the resolved publication must be checked before implementation.

## Highest priority findings

### C1. Quadratic text-container resolution on the composition path

Evidence:

- `shared/src/commonMain/kotlin/com/vivenotes/ui/shell/CanvasTextBoxes.kt:128–131` loops every text outline and calls `state.richTextFor(outline.id)`.
- `shared/src/commonMain/kotlin/com/vivenotes/workspace/WorkspaceState.kt:125–133` resolves each container by materializing `filterIsInstance<Outline.Text>()`, scanning it, then scanning eligible tables and their cells.
- `WorkspaceState.kt:264–267` additionally resolves `focusedTextOutline` twice when selecting buffer state. That accessor resolves the container again (`:230–233`).
- `shared/src/commonMain/kotlin/com/vivenotes/ui/ribbon/draw/TableContent.kt:96–110` calls the same resolver for every typed cell. `model/Document.kt:615–620` shows `Table.locate` traverses cell IDs until a match.

Cost: N text boxes cause O(N²) container work/temporary filtered lists per complete text layer composition. A C-cell typed table causes O(C²) cell resolution, plus scans of surrounding outlines/tables. C is bounded at 200 per valid table (`Document.kt:675–677`), but multiple tables increase page-wide lookup costs. A focused cell/text box near the end increases repeated focus lookup cost.

Trigger: typing, caret changes and other WorkspaceState replacement events. `CanvasTextBoxes.kt:118` eagerly reads the entire `State<WorkspaceState>`; `CanvasPrimeObjects.kt:84` does likewise. These broad reads invalidate whole layer functions. Do not assume all pointer events always recompose every child without measuring Compose skipping.

Plan: use the already held `Outline.Text`/`TableCell` to build its buffer, resolving focused selection once per layer; or construct a page-scoped immutable ID→container/cell-location index once per document structural/content revision and share it with commands. Pass narrow per-item state and introduce keyed item composables. Keep formatting projection keyed by blocks + style/colors, independent of caret/position. Do not move mutable Compose text state to a background executor.

Required tests: pure lookup equivalence for text/table/missing/locked/ink-only cell cases; ensure index invalidates after add/delete/table row/column operations and undo; Compose caret, formatting, selection, Tab navigation and existing IME/right-click contracts; count resolver/projection work on an unrelated object move/caret update. Baseline sizes: 20/100/500/1000 text boxes; 20/50/100/200 typed cells; focused and unfocused variants.

### C2. Geometry-only editing rebuilds all page text for a 140-character preview

Evidence:

- `WorkspaceState.kt:629–638`, `:860–885` use `editOutlines` for each live move/resize.
- `WorkspaceState.kt:714–719` calls `PageSummary.withDocument` for every resulting document.
- `WorkspaceState.kt:109–110` recomputes `pagePreview` unconditionally.
- `shared/src/commonMain/kotlin/com/vivenotes/data/NotesStore.kt:109–112` calls `doc.plainText()` before finding/truncating the first nonblank line.
- `shared/src/commonMain/kotlin/com/vivenotes/model/Document.kt:891–907` joins text from every text outline/table/equation, allocating the full flattened string.

Cost: O(all page text characters) plus allocation on each drag event, even moving a picture/shape or resizing a box whose text did not change. A 140-character preview currently requires materializing megabytes of unrelated text. This is duplicated work, independent of the quadratic container lookup.

Plan: preserve preview for geometry/style/lock changes; derive it only on relevant text/source/content-order changes. Implement a streaming first-nonblank-line preview that can stop after the required 140 characters, preserving the current whitespace/table/equation order exactly. If using cached text-content keys, separate them from x/y/width.

Required tests: exact preview compatibility including leading blank lines, table cells, equations and 140-character truncation; geometry/lock/color/paper changes preserve preview and do not visit text; typing/deletion/order changes recompute correctly. Benchmark repeated `moveTextBox(..., recordHistory=false)` with fixed outline count and 10k/100k/1M characters elsewhere, plus direct `pagePreview`.

### C3. Entire notebook tree copied for every edit

`WorkspaceState.kt:1117–1129` maps and copies every notebook, every section and every section's page list when changing one page. Live typing and transforms use this helper. Cost is O(total loaded tree pages), allocates all branches, and changes identities of unrelated notebooks/sections. Selected getters (`:211–220`) also rescan tree structure repeatedly.

Plan: stable page→owner/index lookup and immutable path-copy of only the changed page's section/notebook; preserve untouched branch identity. Keep persisted tree refresh and selection behavior authoritative. This overlaps the parent persistence review.

Tests: reference identity assertions for untouched branches; update/missing-page equivalence; reorder/delete/import/tree-refresh index invalidation; autosave and in-flight page-switch integration checks. Benchmark one page edit with 100/1k/10k tree pages while edited page content stays fixed.

### C4. Every 128th appended stroke rebuilds the full native page on the UI thread

Evidence:

- `shared/src/jvmMain/kotlin/com/vivenotes/ui/canvas/InkLayer.jvm.kt:311–314` synchronously finishes/canonicalizes a stroke and calls `currentPage.withDesktopEdit(edit)` in pointer dispatch.
- `shared/src/jvmMain/kotlin/com/vivenotes/ink/DesktopInkPage.kt:96–100` compacts all live projections when tail additions reach 128, building a fresh `DesktopInkPage`/`DesktopInkSegment`.
- Segment construction builds bounds and a fresh `InkPageIndex` (`:17–21`).
- `DesktopInkPage.kt:324–327` independently flattens complete native/portable appended lists at a 128-entry tail.
- The new base identity triggers `InkLayer.jvm.kt:118–125` to rebuild the full InkScene/index in composition.

This is a periodic O(N)-scale finish cliff (plus indexing/native bounds and draw preparation). Ordinary appends deliberately retain the large base and are already substantially optimized; do not describe all appends as full-page work. With a fixed 128 threshold the full-base reconstruction cost grows with page size.

Crucial benchmark gap: `InkLayerPerformanceScenario.kt:69–74` tests only 1/32 additions, so its 40k tests never cross the 128-addition cliff. Existing `DesktopInkOverlayTest.kt:49–64` verifies threshold correctness using tiny page fixtures, not latency on large pages.

Plan: retain segmented immutable page/index structures longer, or prepare compaction on a bounded worker and commit through a source-snapshot/version guard. Continue presenting an immutable base + small tail while preparation runs, preserve append order and accumulated new strokes, and retain the exact canonical payload/row binding. Canonical finish, scene creation and display should be profiled separately before parallelizing. Renderer/raster cache access remains UI-thread confined.

Tests: 127→128 and 255→256 boundary behavior on large fixtures; source/operation order and old snapshot immutability; erasing/undoing while compaction is pending; stale result rejection/page switch/tool cancellation; retained-raster bounds/disposal and exact highlighter/fragment pixels. Benchmark prebuilt prepared edit applied to 126/127/128 tails over 1k/10k/40k bases, plus full Compose finish boundary observations.

## Other significant hotspots

### C5. Normal erase previews repeat canonical replay and UI scene reconstruction

- `InkLayer.jvm.kt:106–112` starts preview preparation on a worker after each observed request/frame; `:193–206` separately prepares the completed gesture again, including when the same finished request already supplied a preview.
- `DesktopPartialEraseGesture.kt:45–53` models/encodes the mask, decodes it, performs target geometry tests, then applies the edit.
- `DesktopInkPage.kt:258–278` uses full-source canonical replay for Normal masks; `:309–311` rebuilds the page geometry/index and portable operation projection.
- Every resulting preview base identity then rebuilds full InkScene/index in UI composition (`InkLayer.jvm.kt:114–125`).

The worker prevents the main replay itself blocking UI, but cancellation cannot interrupt an ongoing blocking native call. Repeated superseded snapshots still consume CPU. Object mode already has a retained-base exclusion fast path and must keep it.

Plan: cache a prepared result by immutable source snapshot + request identity; reuse a valid final preview for commit, rather than repeating the same mask/replay. Coalesce pending preview work to latest request using one bounded worker. Follow with target-limited incremental Normal erase replay/prepared scene handoff only after exact fragment/operation semantics have tests. Separate preview and final commit correctness: final edits remain ordered and rebased over earlier completed erases.

Tests: rapid/overlapping strokes, stale snapshots, noncancellable native fakes, no-input-loss, exact Android reload geometry/pixels, operations and undo ordering, no duplicate completed edit, disabled/readonly/page/tool/density/zoom cancellation. Benchmark Normal independently from Object; existing 40k scenario covers Object only.

### C6. Lasso bypasses retained native selection index; closure runs on UI

- `DesktopInkPage.kt:59–62` and `:75–78` call the full-list `projections.selectWithLasso` although base/tail each retain `InkPageIndex`.
- `CanvasSelectionGestures.kt:215` dispatches the selection callback directly; `WorkspaceState.kt:802–820` synchronously tests all outline bounds and selects native ink.
- Outline enclosure in `model/ink/LassoRegion.kt:5–25` walks the whole loop for each of four corners: O(outlines × path points).
- Native grouped pointer hits scan all projections to expand the group (`DesktopInkPage.kt:49–56`).

Plan: use indexed bounding candidates for base/tail, excluding row/projection tombstones, then expand groups across both segments using cached group membership; preserve global draw order/top-hit behavior. Build one prepared outline polygon with extent rejection. Prepare large selections off UI with source-document/ink/gesture version checks before commit. Sibling ByteInk exposes `InkPageIndex.selectWithLasso`; verify exact resolved jar API before relying on it.

Tests: indexed/unindexed equivalence for convex/concave/open loops, tolerated edges, zero geometry, transformed/cut fragments, groups spanning base/tail, exclusions, locked/mixed outlines, draw-order/top-hit and stale selection cancellation. Baseline small/sparse lasso over 1k/10k/40k strokes; separately vary polygon points and number of outlines.

### C7. Freehand lasso copies an ever-growing path per input event

`CanvasSelectionGestures.kt:185–188` appends historical samples and does `loop.toList()` for every event: O(P²) cumulative copies over P similarly sized input events. `CanvasPaper.kt:114–121` rebuilds a Path over all collected points each draw. The snapshot state also causes PageCanvas composition work instead of only invalidating a drawing node.

Plan: preserve every raw point for the final exact selection but batch visual publication once per frame; build/update a UI-owned drawing path incrementally or use a specialized drawing state. If visual decimation is wanted, keep it separate from exact selection inputs and test error bounds; do not silently discard historical observations.

Tests: historical point retention and exact closed-loop outcome, one publication per frame, cancellation/second finger/page/tool changes, zoom/density/page-coordinate fidelity. Baseline 100/1k/10k observations with equal batches and allocation counters.

### C8. Text-preview offset mapping is quadratic in paragraphs × placeholders

`TextBoxPreviews.kt:240–245` maps each paragraph's endpoints by repeatedly scanning all replacement ranges (`:250–258`) plus a full `ranges.count`. For B paragraphs/R formula/link placeholders this is O(BR), often quadratic when each paragraph has a link/equation. `TextBoxPreviews.kt:175` uses the same mapping during `drawBlockDecorations` for list/quote blocks on redraw (`RichTextRendering.kt:278–282`). `links.forEach` also does `entries.any` (`TextBoxPreviews.kt:144–148`), quadratic when links append their own entries.

Plan: immutable prefix-offset map once per sorted nonoverlapping range list, binary-search direct access and a monotone sweep for ordered paragraph styles. Cache projected text and decoration metadata independently of caret and canvas movement.

Tests: retain all existing mapping/paragraph-boundary regression cases including empty insertion ranges and multiline formula; compare new mapping against current implementation over deterministic randomized ranges/source offsets and reverse clicks; Compose preview markers/click caret/right-click/links remain identical. Pure benchmark `previewProjection` with 20/100/500/1000 paragraphs and one placeholder each.

### C9. Eager offscreen text/object work and duplicate preview/editor layout

`CanvasTextBoxes.kt:128` and `CanvasPrimeObjects.kt:90` compose all page objects, without viewport culling. Typed table styles are generated in `measuredTableRows` before its remember block (`TableContent.kt:42–43`), then generated again for the displayed cells (`:103–110`). Moving/resizing a table changes its whole `table` remember key even where text/widths did not change. All text boxes always retain a BasicTextField (`CanvasTextBoxes.kt:238–323`), including a fully transparent editor when TextBoxPreviews renders a second layout (`:217–235`, `:305–306`).

Plan: first isolate/key per-item composables and cache styled text by block content, colors and font settings. Cache row measurement by widths/cell text/header font/density, excluding x/y/selection/border/fill. Then evaluate viewport virtualization with overscan and retained measurements; preserve a focused/selected/pending-search target outside the view. Consider a lightweight read-only display path for unfocused boxes, creating an editor when needed. Keep focus, IME, clipboard, accessibility and source/preview offset behavior intact.

Tests: count style/measure recomputations during caret/move/selection, offscreen focused editors and search reveal, canvas extent stable after item disposal, editor focus/selection and popup behavior. Full workspace Compose baseline is needed before claiming a frame-time gain; pure resolver benchmarks isolate only C1.

### C10. Image memory/serial loading and UI thumbnail decode

- `PictureRendering.kt:42–56` serially loads/decodes all attachments on the entire page and retains their full ImageBitmaps with no viewport/byte budget. Decode correctly uses Default (`:51`). Old-page assets are evicted only after all new-page images finish (`:56`).
- A 2048×2048 RGBA bitmap needs about 16 MiB; pages with many distinct images can occupy hundreds of MiB or more even with almost all offscreen. `assets.keys.filterNot { it in attachmentIds }` also uses quadratic List membership (lower priority).
- `TextBoxPreviews.kt:95–103` serially loads video thumbnails, then calls `decodeToImageBitmap()` directly after the suspension in the LaunchedEffect UI coroutine.

Plan: hash/set membership; evict stale page demand early; use viewport-aware image demand and a byte-budget LRU, resolving target render size rather than always full bitmap where quality permits. Use small bounded decode concurrency (2–4 only after measuring), deduplicate same attachment across users. Put thumbnail bitmap decode on a worker. Do not launch unbounded parallel full-sized decodes or remove source attachments.

Tests: shared attachment decoded once, bounded in-flight requests/bytes, page switching cancellation and no old result publication, missing/corrupt images, bitmap demand/quality at zoom, precise selection/ink/transparent picture pixels. Baseline page-load-to-first-visible-image and peak heap/native bitmap memory on 10/50/100 images.

## Smaller confirmed repeated scans

- `PageCanvas.kt:389–396`: scans all outlines twice for extent on each parent recomposition. Cache a page geometry projection/update only changed object's bounds, accounting for measured text/table height changes.
- `SelectionFrame.kt:16–30`: full outline filter and multiple bounds passes even for empty/small selection; called repeatedly from hit tests and toolkit composition. Short-circuit empty selected IDs; share selected bounds keyed by relevant geometry revision.
- `InkSelection.kt:12–15` and `DesktopInkSelection.kt:24–25`: color/theme/group metadata scan page strokes repeatedly; selection is immutable, so selected rows/projections and metadata can be computed once. `InkLayer.jvm.kt:144–155` scans all projections on drag start to obtain/exclude selected pieces despite already building maps by projection. Use selected-key lookup, preserving scene order.
- `PageCanvas.kt:774–782`: reads scroll values in composition for the ink toolbar. Thus selected-ink scrolling invalidates the parent and rescans its outline extent/bounds. Move placement into layout/layer lambdas where possible. Without selected ink/ruler these scroll reads are mostly deferred to drawing.

## Paths already optimized: retain the protections

- Ink geometry uses immutable native snapshots. `InkPageReader` retains decoded sources/operations; portable samples materialize lazily (`DesktopInkPage.kt:343–356`). Do not add duplicate codecs or eagerly materialize all samples.
- Base/tail indices and scenes are retained across ordinary strokes; tail is bounded and periodic compaction deliberate. Object eraser and moving selection exclude exact projections, preserving siblings. `InkLayer.jvm.kt:125–156`, `DesktopInkPage.kt:81–113`.
- Ink raster caches cover two physical viewports rather than full document extent; cleanup closes both (`InkLayer.jvm.kt:69–78`, `:335–355`). Rasters and renderer stay confined to rendering lifecycle.
- Wet-ink inputs are queued and geometrically advanced on frames (`InkLayer.jvm.kt:164–174`, `:303–318`); final canonical storage remains exact. Existing reuse/observation/pressure tests are acceptance constraints.
- Base ruling draws only the visible window and suppresses sub-3px spacing (`CanvasPaper.kt:88–89`, `:151–153`). Infinite canvas grows via screenful high-water marks (`PageCanvas.kt:335–348`), not every pixel/frame.
- Move, recolor, grouping, paste and Insert Space preparation already use `Dispatchers.Default` and stale snapshot checks. More concurrent mutations are not automatically correct; per-page edit order and final UI-state publication must stay serial.

## Current existing benchmark usability

`shared/build.gradle.kts:235–249` registers `:shared:inkPhase2Scenario`, isolated from ordinary jvmTest. It runs `InkLayerPerformanceScenario` with 4 GiB heap and writes JSON to `-PbyteinkInkScenarioOutput=<path>`; `-PbyteinkInkScenarioRequireReuse=true` enables structural reuse assertions. Use isolated Xvfb and `--max-workers=1`, serial fresh JVM forks, same exact source/native/runtime/workload hashes.

Fixture: 40,000 distinct short canonical native meshes, 200×200 grid. Eight cases: density 1/2, fit/sparse viewport, 1/32 authored pending additions, fractional both-axis scroll. Counters record scene/index builds, paths, raster builds, finished vector draws, input observations/shape advances and retained/peak raster bytes. Hardware/JVM/native metadata and fixture setup time are saved.

Limitations:

- Measures pointer-handler start → next completed software Canvas draw; that draw may precede processing all queued samples, includes scheduling and GC, and is not physical input/display latency.
- No stable statistical microbenchmark protocol built in; report raw observations and compare distributions across fresh forks.
- Fixture setup combines native stroke creation, encoding and page reading; no isolated read/decode time.
- Bypasses WorkspaceState, all text/tables/images and parent canvas composition. Does not cover C1/C2/C3/C8/C9/C10.
- 1/32 additions omit C4's 128th finish compaction. Object eraser omits Normal replay. Fixed viewport cases omit changed-viewport rasterization cadence.

Recommended permanent baseline additions: deterministic pure scaling scenarios for lookup/preview/tree update/offset mapping; ink apply at threshold vs nonthreshold, Normal mask preview/commit, sparse native lasso; targeted full-workspace UI scenarios with text/table/image mix. Record wall time, allocated bytes where JVM counters permit, GC, fixture dimensions and structural work counts. Timing is diagnostic rather than a flaky unit-test assertion.

Added at the parent's request: `shared/src/jvmTest/kotlin/com/vivenotes/performance/InkPerformanceCases.kt` defines `measureInkPerformance(BaselineMeasurements)` with `ink.apply_127th_append` / `ink.apply_128th_append` over 1k/10k/40k distinct real three-point canonical marker grid strokes. It applies prebuilt canonical payloads to immutable states with 126/127 pending additions, respectively, using fixed one-operation samples. Creation/encoding/page read/hash/validation stay outside timed operations. SHA-256 fingerprints serialize the exact ordered stored rows and input bytes plus starting tail size. This isolates page/index/list apply cost; it does not time controller.finish or the subsequent Compose scene/raster build. `InkPerformanceCasesTest.kt` validates deterministic fixtures, input geometry/count/order and starting-snapshot immutability without asserting timings. Parent owns compilation, focused/full suites and actual captures.

## Concurrency implementation order

1. Remove duplicate scans/derive immutable document/container/text projections before adding threads; this directly lowers work and avoids moving O(N²) elsewhere.
2. Keep UI snapshot state, text measurers, live controller, drawing paths and render caches thread-confined. Use immutable, versioned worker requests/results only.
3. Coalesce latest preview/selection work; serialize ordered page edits and persistence. Bound outstanding native work and reuse completed identical results.
4. Consider bounded native decode workers, background compaction/prepared scene construction and independent image decodes after verifying actual library ownership/thread-safety APIs and memory bounds. Profile first; parallel native tasks may compete with ONNX/sync/GC.
5. Extend correctness and cost-regression tests, rerun focused suites and the full shared/desktop JVM suite; compare identical fresh serial baseline forks and retain raw evidence.
