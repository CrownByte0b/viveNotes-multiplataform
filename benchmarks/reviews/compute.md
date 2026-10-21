# Compute, rich text, search, AI and PDF performance review

Review of current repository code on 2026-10-21. No production/build edits or test/benchmark executions performed by this reviewer. Read AGENTS.md, memory/initial.md, memory/ai.md, memory/search.md, memory/pdf-export.md and relevant memory/document-tab.md sections. Findings below follow actual code, not comments. Runtime impact beyond the baseline workloads must be measured; algorithmic costs are directly visible in the loops.

## Highest-confidence improvements

### C1. Ordinary HTML paste repeatedly copies a growing paragraph (interactive, quadratic)

Files/lines:
- shared/src/commonMain/kotlin/com/vivenotes/richtext/HtmlPaste.kt:229-239 splits ordinary text into words.
- HtmlPaste.kt:249-253 adds a Run for each word and pending space.
- HtmlPaste.kt:267-270 normalizes these on endLine.
- HtmlPaste.kt:586-592 merges same-mark runs using previous.text + run.text on every piece.
- shared/src/commonMain/kotlin/com/vivenotes/richtext/RichTextBuffer.kt:473-480 has the same merge pattern.
- shared/src/commonMain/kotlin/com/vivenotes/ui/canvas/TextClipboardActions.kt:39-42 invokes workspace/TextClipboard.kt:35-43 synchronously for UI paste.

A plain <p>word word ...</p> with W words creates about 2W runs, then copies successively longer strings. Fixed average word length gives Theta(W^2) copied characters and transient allocations. No exotic source markup is required. A large browser paragraph can stall the event thread.

Plan: use one StringBuilder per contiguous equal-mark group and flush once at a mark boundary. Share the normalizer if the equation-atomicity distinction can be expressed explicitly (RichTextBuffer deliberately does not merge Equation runs; preserve that). Keep IDs, whitespace collapse, paragraph/list styles and source offsets identical. After algorithmic fix, consider off-thread parsing only for large HTML, capturing target page/container/selection/revision and applying only if still valid.

Benchmark: pastedHtmlBlocks("<p>" + "word ".repeat(W).trimEnd() + "</p>", emptySet())!! at 250/1,000/4,000 words; assert one block, one maximal Run and exact plain text. Also mixed equal/style groups, preserving Equation cases for RichTextBuffer.

Tests: extend HtmlPasteTest, BrowserClipboardFixtureTest, RichTextBufferTest and TextBoxEditingTest. Property/reference compare existing parser semantics on mixed tags, nested lists, whitespace and math. Avoid elapsed-time assertions in normal tests; baseline harness owns timing/allocation checks.

### C2. Exact fuzzy substring matching performs a word-list scan for every occurrence (search, quadratic)

shared/src/commonMain/kotlin/com/vivenotes/model/search/FuzzyMatcher.kt:81-89 enumerates every occurrence; :93-97 uses words.firstOrNull from the beginning to find its containing word. For query "a" against "a a a ...", the k-th occurrence scans k word ranges. Theta(W^2) range checks for a common one-letter query.

Plan: advance a monotonic word-range cursor with monotonically increasing occurrence offsets, or binary-search precomputed boundaries. Preserve the first highest-scored occurrence, score tiers/bonuses, punctuation behavior, overlapping occurrences and UTF-16 coordinates. A safe early exit after the maximum possible substring score is another opportunity, since equal scores keep the earlier match.

Benchmark: FuzzyMatcher.match("a", "a ".repeat(W)) at W=250/1,000/4,000; assert score=145 and single span 0..1. Additional later-whole-word fixture must retain preference over earlier buried substring.

Tests: existing FuzzyMatcherTest and ContentSearchTest are Android compatibility specifications. Add repeated exact-word occurrences, later boundary advantage, Unicode/punctuation and stable equal-score cases.

### C3. Preview offset rebasing repeatedly scans replacement ranges (UI composition/draw, quadratic)

shared/src/commonMain/kotlin/com/vivenotes/ui/canvas/TextBoxPreviews.kt:225-246 calls projectedOffset twice for every paragraph style and also ranges.count per style; projectedOffset at :250-258 scans all preceding replacements. P paragraph styles and R link/equation/video placeholders cost Theta(P*R), commonly Theta(P^2) for one link per paragraph. Preview draw at :174-175 calls the same mapper twice per decorated block through RichTextRendering.kt:276-282. Per-link overlap checks at TextBoxPreviews.kt:144-159 also scan growing entries, another quadratic path for many link icons.

Plan: immutable projection index keyed by source structure and ordered ranges, storing cumulative removed lengths; binary-search source/display positions or use a monotonic merge while building projection. Preserve empty insertion ranges used by link icons, multiline formula boundaries, paragraph ranges collapsed by replacements, inverse sourceOffset mapping and clicked caret behavior. Memoize derived projection by blocks/ranges/style rather than selection or whole workspace state. Handle AnnotatedString slicing with a single style/range traversal if profiling attributes significant cost there.

Benchmark: previewProjection(source, ranges), source with P paragraphs/styles and one empty insertion range at each paragraph's link end. Assert projected text grows by exactly P, no source text removed, paragraph offsets remain correct. Existing internals accessible from jvmTest friend compilation. Add draw/composition profile because pure mapping does not measure dual-layout cost.

Tests: PreviewProjectionTest, TextBoxPreviewTest, TextBoxEditingTest, RichTextLayoutTest. Randomized source/projected offset round trips away from replaced interiors, adjacent/empty ranges, trailing empty block, and prior placeholder-overlap regression.

### C4. PDF whitespace grouping repeatedly processes almost the entire suffix (export planning, quadratic)

shared/src/jvmMain/kotlin/com/vivenotes/pdf/ContentGroups.kt:154-188 computes union, two widest gaps and partitions for each pending block. widestGap at :231-247 allocates intervals and sorts per call. Equal gaps keep the earliest gap (:242). N equally spaced rows create split sizes 1+(N-1), then 1+(N-2), etc: Theta(N^2) scans and allocations; general per-node sort upper bound O(N^2 log N). Current iterative queue correctly avoids stack overflow; it does not remove repeated work.

Benchmark API: List(N) { i -> PdfAtom("atom-$i", PdfBounds(0f, i*60f, 10f, i*60f+10f)) }; groupContent(atoms, 600f, 800f), N=250/1,000/4,000. Exact expected N singleton groups with ID "group:atom-$i" and original bounds. This is geometry-only, no native strokes required.

Plan: retain interval/order information across partitions and avoid reconstructing lists/union values; then an indexed or sweep-based cut implementation that preserves the existing widest-gap, axis and stable tie behavior. Merely balancing equal cuts can change grouping for 2D arrangements; require reference equivalence on randomized layouts before replacing the algorithm. Avoid a blanket promise of O(N log N) until implementation/proof is available.

Tests: all existing Android ContentGroupTest and PageTilingTest cases plus equal-gap many-row fixture, randomized disjoint/overlapping rectangles compared against current implementation, negative offsets, forced oversize cuts, exact gap thresholds, lasso groups and deterministic IDs.

## Further material findings

### C5. Fuzzy typo DP scans irrelevant suffixes; query and content preprocessing repeats

FuzzyMatcher.kt:138-162 allocates two rows sized to the full word and processes every column of that word for every query-term row. It is bounded by early row rejection in some cases, but a nearly matching prefix of a very long word still scans the entire suffix. A prefix longer than term.length + budget cannot be within the edit budget. The budget is at most two (:201-205).

Plan: cap columns to min(word.length, term.length+budget), optionally use a diagonal edit band. Existing prefix semantics differ from whole-word edit distance; retain them. Also prepare folded query terms once per search; currently match at :45 folds/splits the same query for every candidate and alternative. Folded text/word boundaries at :48-49 can be cached with immutable projected units per source revision, with a bounded memory budget. folded(:241) currently creates an intermediate character list; use a string/char builder retaining lowercaseChar UTF-16 behavior.

Benchmark long-word near miss: FuzzyMatcher.match("abcdefg", "abcdefx" + "z".repeat(L)); vary L while query fixed and verify returned prefix span/score. Benchmark many normal ContentUnits with the same query separately from the pathological exact-substring workload.

### C6. Query changes cancel query-independent OCR and kill the warm model process

DesktopContentSearch.kt:40-112 owns projection, matching and all uncached image OCR inside one query snapshot. SearchPane.kt:52-57 cancels/restarts that Flow after query/live document changes (180ms debounce). SearchProcessPool.kt:49-56 stops the occupied process on cancellation. Thus typing a new query after OCR starts discards work and its loaded OCR session; it repeats at the next query until one remains stable long enough. Database invalidations use transformLatest at DesktopContentSearch.kt:31 as well.

Plan: separate bounded attachment indexing from query matching. Single-flight each (attachment ID, engine ID) within a notebook/workspace indexing owner; retain completed/in-flight OCR across query changes and publish cache updates for the newest query only. Introduce cancellation only for lost scope, removed content or true shutdown, with an explicit policy for continued background work. Retain process-isolation and timeout recovery. Prevent OCR tasks from occupying every slot ahead of interactive typed matching (separate bounded queue/priority or reserved capacity); measure cold/warm mixed workload and memory before changing pool size.

Tests: SearchProcessPoolTest already proves process reuse, concurrent PIDs, cancellation, timeout and cleanup; preserve these contracts. Add controlled delayed OCR query-A→query-B scenario proving one OCR run, newest result only and matching not blocked behind all OCR; close must still kill children, engine changes invalidate, retry must work and failed jobs cannot loop forever.

### C7. Every OCR completion scans all image placements

DesktopContentSearch.kt:104: placements.filter { it.attachmentId == id } for every unique pending attachment. U unique images and P placements cost Theta(U*P), despite attachmentId being a natural lookup key. reading.text.lines() repeats for every placement too.

Plan: group placements by attachment ID once and split each OCR text once, giving O(P) indexing plus actual per-page result construction. Retain one attachment per page and separate results for separate pages. This is a low-risk algorithmic improvement; likely smaller than inference but can matter for notebooks with many pictures.

Tests: ContentSearchIndexPicturesTest, DesktopContentSearchTest cross-page/dedup cases, engine invalidation and cache behavior.

### C8. Projection cache prevents decoding but notebook bodies are reread and texts resent every query

DesktopContentSearch.kt:42-58 fetches all stored notebook bodies before consulting projected cache, compares full WorkerRequests, and serializes live PageDoc to JSON for projection cache lookup. :116-124 retransmits chunks of all units to workers for every query. SearchWorker.kt:92-104 discards decoded page data after replying; worker matching has no normalized-document index.

Plan: cache source projections by page revision/body identity and apply targeted invalidation (observeSearchChanges currently listens globally to notebooks/sections/pages/page_content/attachments at NotesRepository.kt:210). Query body rows only for changed pages where repository revision semantics allow it. Avoid live JSON round-trip when an immutable live PageDoc projection can be prepared directly off UI or revision-cached. Optionally install immutable projected/prepared units in workers and send query+revision IDs; weigh protocol complexity and lost-worker recovery against measured JSON and query cost. Preserve live unsaved override, deletion/import/sync invalidation, unknown codec/title fallback and notebook scope. Do not rely only on timestamps that can collide.

### C9. Every recognized formula rereads/parses the same tokenizer

FormulaTokenizerDecoder.kt:23-46 reads UTF-8 tokenizer, builds full Json tree and scans its 50k vocabulary for every nonempty result. InkRecognitionEngine.kt:167 and :175 invoke it every formula, including repeated calculator uses. This duplicate CPU/I/O/allocation is independent of inference and occurs after the formula lane is released.

Plan: bounded immutable vocabulary+special-ID cache keyed by pinned tokenizer artifact identity and installed generation, created once/single-flight. Make switch/delete/reinstall invalidation explicit. Keep EOS/PAD/invalid-token rejection and ByteLevel UTF-8 decoding unchanged. Actual file exists at devdb/ai_models/unimernet-small-v1/unimernet-small-tokenizer.json; contains model vocabulary, not user notes. Synthetic 50k-token fixture can provide portable baseline without optional model files. Compare cold load vs warm decode and report retained cache size.

Tests: FormulaTokenizerDecoderTest, AiModelStoreTest, OnnxRecognitionSmokeTest model-switch cases; add concurrent first decode, changed tokenizer at same slot, special/missing token and UTF-8 fixtures.

### C10. OCR line output creates a large Java logits tensor solely to take argmax

InkRecognitionEngine.kt:325-342 copies output[0].value as Array<Array<FloatArray>>, then decodeCtc(:657-682) scans each row over the 18,710 classes. This is linear required scoring, but the nested Java materialization duplicates native tensor contents and remains live outside the inference lane. At T timesteps, float data alone is T*18,710*4 bytes, before object/array overhead; line width is capped at 3200. Multiple processes/crops amplify peaks.

Plan: profile tensor materialization/CTC with allocation counters and native RSS. Consider flat-buffer argmax directly while output remains open, or model-side ArgMax+Max output preserving exact class/confidence tie rules. Do not claim zero-copy solely from FloatBuffer accessor; verify actual ONNX Java implementation/API behavior. Keep crop/preprocess concurrency bounded. No current model change recommended before fixture parity and before/after quality/perf measurement.

Tests: existing CTC multilingual/dictionary/repeated blank tests and real OCR image contracts. Add all equal logits, NaN/Inf handling parity, empty rows and confidence values.

### C11. PDF repeatedly measures/replans and renders complete long containers for every tile

PdfExporter.kt:81-95 measures and tiles each page during plan; measureCached(:285-309) reloads doc+ink, remeasures and retiles on first preview/write. It keeps only one page (:54-55), so switching preview pages then writing can repeat expensive text/equation work. SourcesFor(:220-240) loads every section page+ink into one list before planning starts, increasing memory peak and serial latency.

PageMeasure.kt:219-220 measures original text then :303-307 measures every oversized text box again at printable width; each wide-text lookup uses atoms.indexOfFirst, O(wideTexts*allAtoms). PdfTextLayout.kt:219-228 creates equations for all candidates before discarding overlapping replacements, doing avoidable parses. PageRenderer.kt:103-153 traverses/filter-allocates per outline type per sheet; per-table culling only checks the table bounds and drawTable(:293-345) visits every row/cell on every intersecting tile. PdfTextLayout.draw(:93-141) visits every paragraph of a tall box on every tile; native clipping does not remove Kotlin layout/glyph/decorations setup. Large table/text spanning S sheets therefore costs O(S*containerSize).

Plan: stream source pages and retain lightweight immutable measurement/plan identity; separate geometry/layout from decoded images and use a bounded cache with revision+options+paper keys. Skip placed-equation parse in geometry-only pass where stored dimensions already suffice; preserve inline equations needed for text geometry. Index text atoms rather than scan per wide box. Pass visible bounds into text/table draw and use prefix row/paragraph extents to visit only intersecting rows/paragraphs while preserving decorations that cross tiles. Pre-index per-tile visible atoms/groups. Later parallelize independent page preparation with a small concurrency and isolated PageMeasurer/TextMeasurer/renderer instances, plus ordered assembly and explicit native-resource ownership.

The current single PdfExporter mutex is necessary: PageRenderer holds mutable Paints and caches, PdfTextMeasurer has mutable fonts/cache, PdfVectorDocument writes an ordered PDF graphics object. Removing mutex or simply using async on those shared objects is unsafe. Preserve serialized output; any concurrency must use per-job owners and bounded memory. Existing preview requests are also serialized for safe release.

Tests: PdfExportTest, PdfRenderingTest, PdfTextWrappingTest, PdfStartupTest, PdfFittingOrderTest and original Android grouping/tiling cases. Add renderer-work counters for long tables/paragraphs, PDFBox boundary raster comparisons, revision/options cache invalidation, cancel/release during preview+write, section streaming/max in-flight count and destination-preservation failures.

No confirmed native-resource leak is asserted for the plan's unclosed MeasuredPage: PdfEquation font faces are allocated lazily only during draw (PdfTextLayout.kt:34-46), so missing close there does not establish such a leak. Fix lifecycle asymmetry if desired, but profile retained native state before making that claim.

### C12. Other bounded quadratic loops exist but are lower priority than the common workloads above

- PageTiling.kt:197-224 calls items.none for every overhanging fitted item; worst-case O(N^2) collisions. :115-120 checks placed.any for every tile in the bounding rectangle, O(tileCount*N), capped 100x100. Spatial broad phase/tile occupancy can preserve exact intersection/order semantics, but profile realistic export first.
- TextDetection.kt:181-233 loops each hull edge over the entire hull, O(H^2). Detector input longest side is bounded 960 and text hulls often small; rotating-caliper replacement should be driven by real detector output, with degenerate/tie parity fixtures.
- TextDetection.kt:363-376 scans existing rows for each OCR line (O(Q^2)); Q capped at 400 (:398). It also recomputes quadrilateral dimensions repeatedly. Cache derived quad metrics and consider sorted row candidates only after profile; preserve reading-order tolerance semantics.
- AiModelStore.kt:153-168 downloads artifacts of a formula package sequentially. Independent packages already run separate jobs, downloader streams hash verification in the same pass (VerifiedArtifactDownloader.kt:41-63), and installed verification markers avoid full graph rehashes (AiModelStore.kt:326-340). Two-artifact bounded concurrent download could reduce first-install time, but is not an interactive hot path and should not change aggregate progress, staged atomic install, deletion/cancel or network limits. Progress currently emits every 64KiB read; consider progress distinct-by-visible-percent/time if UI profile shows churn.

## Already implemented optimizations to retain

- Search: 180ms debounce; whole-picture OCR concurrent across persistent isolated JVM workers; bounded 2-4 default workers (2-8 accepted explicitly); per-worker OCR lanes=1, native threads=1; bounded queue; max 128 ContentUnits per request; projections cached until request changes; OCR cache keyed by attachment+engine; ranking trimmed to 121; empty query bypass; stale cancellation, process timeout/restart/close tests.
- OCR: inference semaphore lanes, crop in-flight semaphore 2*lanes; dictionary lazy per engine; detector downscaling max 960 and max 400 quads; formula session exclusion/model hold; native UniMERNet KV tensors reused across autoregressive steps (InkRecognitionEngine.kt:198-238). Tokens are autoregressive: independent formula/image tasks can run concurrently; decoder token steps cannot be parallelized without changing model/algorithm.
- Models: startup background scope; installed .verified marker avoids repeated full SHA-256 scan; streamed artifact hashing plus staged install, deduplicated same-package jobs.
- PDF: heavy work on dispatcher; one-page rendered measurement cache; image decode dedup by attachment and maximum edge; geometry-only plan skips image bytes; per-outline/per-group tile rejection; cached ink path renderer; serialized mutable renderer ownership and staged destination write.

## Suggested implementation order

1. Keep the current baseline and harness stable; benchmark algorithm workloads plus representative editing/search/PDF interaction before changes. Add missing semantic/counter fixtures alongside each change.
2. Linear HTML/run normalization, exact substring word lookup and preview projection index. Small reviewable changes, immediate interactive benefit, no additional worker complexity.
3. Prepared/cached fuzzy match inputs and bounded typo-prefix DP; direct-index image placements/tokenizer cache.
4. Indexed PDF grouping with reference equivalence, then streaming section planning and visible row/paragraph drawing.
5. Query-independent singleflight OCR indexing with foreground matching priority and explicit cancellation lifecycle; revisit concurrency limits from CPU/RSS results rather than maximizing threads.
6. Profile ONNX logits copying and native memory, then decide on flat-buffer/model-output changes; page-preparation concurrency only after ownership boundaries are explicit.
7. Run focused unit/UI/integration tests for each change and full JVM shared+desktop suite before handoff, as AGENTS.md requires. Retain original Android compatibility cases; report baseline vs after medians and allocation/peak-RSS deltas on the same machine/runtime/fixtures.
