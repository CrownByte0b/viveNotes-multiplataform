# Desktop performance review and implementation plan — 2026-10-21

The largest opportunities are removing repeated work in text composition, paste/search, preview
mapping and PDF grouping; moving document CPU work off the event thread; and reducing the amount
of data examined or locked for each ink/sync edit. More threads alone would leave several
quadratic algorithms intact and can violate the existing native/SQLite ownership rules.

Review target: `0f5e37070b2532b001eb7db998048edd91b1c103`, initially clean. Three parallel
reviews covered canvas/input/ink, persistence/sync, and rich text/search/AI/PDF, with parent
verification of the benchmark call paths. Production Kotlin, document formats, database schema,
dependencies and runtime behavior are unchanged. This handoff adds benchmark infrastructure,
its correctness tests, review artifacts and an asynchronous-completion correction to the old
opt-in ink scenario. No proposed production optimization has been applied.

Detailed source findings, exact line references, triggers, tests and existing optimizations:

- [Canvas, input and ink review](reviews/canvas.md).
- [Storage, session, transfers and sync review](reviews/storage.md).
- [Rich text, search, AI and PDF review](reviews/compute.md).
- [SQLite query-plan evidence](reviews/query-plans.txt), explicitly using host SQLite 3.51.2
  against the exact schema-2 DDL. These plans support the scan/sort findings; they are not timing
  measurements from the bundled driver.

## Baseline and reproduction

The capture is in [results/2026-10-21](results/2026-10-21/summary.md), with raw per-fork JSON,
source/harness SHA-256 manifests, external runtime-jar hashes, JVM flags and environment details.
The summary takes the median of three fresh-JVM fork medians. See the results section below for
the measured findings. Capture and comparison commands, from the repository root:

```sh
JAVA_HOME=/usr/lib/jvm/java-latest-openjdk python3 benchmarks/capture.py \
  --output benchmarks/results/after --forks 3 --include-ink
python3 benchmarks/capture.py --compare benchmarks/results/2026-10-21 benchmarks/results/after
```

Use the same installed Java/runtime dependencies, machine, power conditions and idle workload.
`JAVA_HOME` above is this machine's Java 27 location; the Gradle daemon criteria select Zulu 27.
The raw reports identify the actual fork runtime. These are Linux desktop measurements; Windows
and native-Wayland physical latency are not measured. The harness refuses to overwrite an existing
nonempty capture. Comparison rejects changed fixtures, harnesses, CPU/kernel/runtime settings or
external dependencies. Changed project jars are expected after an optimization.

The 48 algorithm/repository cases cover increasing text-box/cell counts; geometry-only edits;
rich-text caret selection, linked previews and HTML paste; exact/typo search; evenly spaced PDF
groups; formula tokenizer decoding; ordinary versus the 128th ink append; and real file-backed
SQLite document save/load/checkpoint and ink append/checkpoint. Fixture setup and semantic
validation happen outside measured operations. The native append fixtures use genuine canonical
ByteInk rows; the separate database-only fixtures deliberately use opaque 1,024-byte points to
isolate persistence from native decoding.

Each fork retains 15 samples/case. Pure synchronous cases warm for at least eight calls and
400 ms, then use adaptive batches. Fixed-operation storage/native cases use eight warmup calls
and one operation/sample; tiered-JIT startup may still affect those cases. Allocations cover only
the caller's Java thread and exclude native memory. Coroutine/SQLite allocations are not reported.
P95 fields describe batch averages, not individual events or application tail latency. Storage
uses new WAL databases on this repository's filesystem with warm caches; the checkpoint case
creates distinct history entries below the 40-version retention cap. Append cases record their
small deterministic row-count growth. They do not measure cold disk, EDT delay or writer contention.

The additional 40,000-distinct-mesh scenario has eight cases/fork: density 1/2, fit/sparse view,
and 1/32 pending additions. Its pointer-handler-to-next-completed-draw observations may precede
processing all queued samples and include test scheduling/GC. They are software diagnostics,
not input-to-photon latency. The erase completion now explicitly awaits the real worker commit;
all previous semantic assertions remain. Results from the old immediate-check harness should not
be mixed with this capture. The separate append cases cover the previously omitted 127→128 cliff;
they isolate snapshot application and exclude canonical finishing, scene construction and drawing.

## Priority findings

P1 means substantial interactive or library-size scaling cost; P2 means background, bounded, or
larger implementation scope. These are engineering priorities, not estimates of how often every
user will encounter a case. Measurements isolate specific operations; broader impact described
below follows the verified call path and needs interaction profiling where stated.

| ID | Priority | Verified repeated work | Trigger and consequence | First change |
|---|---|---|---|---|
| F1 | P1 | Each `richTextFor` scans outlines, then resolves focus twice; tables scan cells again | N text boxes / C cells cost O(N²)/O(C²) in full layer composition | Pass known containers directly; resolve focus once; immutable ID/location index |
| F2 | P1 | `pagePreview` flattens the entire document for 140 chars; `updatePage` copies the entire loaded tree | Every geometry-only move/resize and text edit can copy unrelated content | Text-aware preview invalidation/streaming preview, path-copy only changed owners |
| F3 | P1 | HTML runs repeatedly concatenate an expanding string; rich-text run merger has the same pattern | Ordinary large browser paragraphs cause quadratic copied bytes on paste | StringBuilder per equal-mark group; preserve atomic equations |
| F4 | P1 | Preview style endpoints repeatedly scan all replacement ranges; link overlap checks scan prior entries | Paragraphs × link/formula placeholders, plus repeated decoration mapping on draws | Prefix-offset projection index and ordered sweep; cache independently of caret |
| F5 | P1 | Each exact substring occurrence scans word boundaries from the beginning | Common one-letter or repeated-word query costs O(words²) | Monotonic boundary cursor/maximum-score early stop; preserve ties and UTF-16 offsets |
| F6 | P1 | Whitespace grouping repeatedly sorts and partitions nearly the entire remainder | Equally spaced PDF rows produce 1+(N−1) splits and quadratic scans/allocations | Indexed interval/cut representation with exact grouping equivalence |
| F7 | P1 | The 128th ink addition compacts the full native/portable page and rebuilds indices | Periodic full-page work during synchronous finish; scene rebuild follows | Retain segments or prepare compaction off-thread with guarded immutable handoff |
| F8 | P1 | Document encode/preview and post-DAO decode/unpack run in the caller's UI scope | Autosave, open, own-write observation and revision load compete with input | Explicit CPU dispatcher; retain UI-owned session/order and failure semantics |
| F9 | P1 | Revision checkpoints read all ink `points`/targets although snapshot uses only IDs/state | First append after 30 s reads page geometry and occupies the sole writer | Narrow projection queries, then guarded short snapshot/commit protocol |
| F10 | P1 | Stable sync events rescan pictured bodies and stat all attachment files; incoming rows repeat parent lookups | One new ink row scales with unrelated library size; many same-page rows repeat N+1 queries | Dirty-page/digest reconciliation and transaction-local parent/deferred caches |
| F11 | P1/P2 | Picture GET/PUT operations run sequentially under the global hierarchy mutex | Slow picture transfer delays metadata changes; independent network latency adds up | Durable worklist and bounded per-digest workers outside metadata lock |
| F12 | P2 | Remaining outbox repeatedly scanned/sorted; pending ink lists copied per acknowledgement; unindexed history rescanned in batches | Large initial sync, write-failure backlog, or imported history grows quadratically | Rank-aware/keyset reads, persistent queue, keyset history indexing |
| F13 | P2 | Query changes cancel occupied OCR processes; bodies/text/query normalization are repeatedly prepared | Active typing can restart the same expensive image work; matching retransmits corpus | Query-independent single-flight indexing; prepared query/source projections |
| F14 | P2 | Formula decode reparses the entire tokenizer; OCR materializes nested Java logits for argmax | Every warm recognition adds duplicate parsing/allocations; crops multiply memory | Immutable tokenizer cache; profile flat logits before changing ONNX access |
| F15 | P2 | All offscreen editors/images are created; images retained without byte budget; thumbnail decode resumes on UI | Large mixed pages incur layout and bitmap memory well outside viewport | Narrow keyed state, retained measurements, viewport demand and byte-budget cache |
| F16 | P2 | PDF remeasures/replans and loads all section sources; long containers redraw all rows/paragraphs per intersecting sheet | Large export CPU/memory scales with sheets × whole container | Stream sources, cache immutable measurement, visible row/paragraph ranges |

Additional verified findings and tests are in the detailed reviews: lasso skips available native
broad-phase indices, growing lasso snapshots copy O(points²), Normal eraser replay can duplicate
preview/final work, initial page observation plus explicit load decodes twice, accepted sync
states retain base64 points until echo, and exporting one notebook snapshots the entire library.
Bounded OCR hull/reading-order loops and sequential model artifacts are lower priority than the
interactive paths. No native PDF resource leak is asserted: lazy font allocation means the
observed close asymmetry alone does not establish one.

## Implementation plan

Implement each numbered phase as small reviewable changes. Preserve the frozen capture/harness;
add new scenarios separately if a phase needs coverage that this baseline lacks. Each production
change needs its focused semantic/work-count tests and a full shared/desktop JVM pass. Keep the
Android document/schema/sync contracts and existing compatibility tests intact.

### 1. Remove repeated algorithmic work

Start with F1, F3, F4, F5 and the tokenizer portion of F14. These need little concurrency machinery
and have isolated baselines.

- Derive buffers from the known text outline/cell and compare its ID directly with the focused
  ID. For command lookup, derive an immutable text/cell index once per relevant document revision;
  invalidate after table row/column edits, locking, insertion/deletion, undo/import/sync.
- Normalize equal-mark runs with builders. Keep equation runs atomic, whitespace collapse,
  paragraph IDs, nested lists, marks and rich clipboard behavior unchanged.
- Build one replacement-range projection with cumulative deltas and monotonic or binary-search
  mapping in both directions. Reuse it for paragraph styles, decoration and clicks. Keep empty
  link-icon ranges, multiline math and collapsed-style handling exact.
- Make substring scoring linear in occurrences plus boundary traversal, preserve earliest equal
  score and later whole-word advantage. Bound typo DP columns to prefixes that can still match;
  prepare/fold the query once and normalized source units by immutable revision.
- Cache decoded tokenizer metadata by pinned artifact identity/installed generation, with bounded
  retention and single-flight construction. Deletion/switch/reinstall invalidates explicitly.

Tests: extend `RichTextBufferTest`, `HtmlPasteTest`, browser clipboard fixtures, existing preview
mapping/UI tests, Android `FuzzyMatcherTest`/content search cases, tokenizer and model lifecycle
tests. Use deterministic randomized reference equivalence for ranges, offsets and matching.
Count resolutions/normalization visits instead of adding fragile timing assertions. Success:
equivalent output, near-linear growth on these captured scaling cases, materially lower largest
case median/allocation across all three forks, and no small-case regression beyond fork noise.

### 2. Reduce UI invalidation and move document CPU work off Main

Implement F2 and F8, then the low-risk parts of F15. Make preview derivation stop after the first
nonblank 140-character line and preserve it for geometry-only edits. Path-copy the selected page's
owners while retaining unrelated branch identities. Narrow per-item composable inputs and key
formatting by blocks/style/colors rather than the entire workspace/caret/x/y. Cache table styling
and row measurement without geometry-only keys. Defer selected toolbar positioning to layout.

Dispatch codec/preview/revision integrity CPU work explicitly in the JVM repository. Session
state, generations, queue ownership, edit order and Compose publication stay on Main. Own echoes
must never replace newer unsaved typing. Consolidate the initial observe/load only after its
save-before-switch and remote replacement tests are in place. Decode video thumbnails on workers.

Tests: untouched branch identity, preview rules for leading blanks/tables/equations/truncation,
counted projection work during caret/move, thread-boundary tests, paused-codec UI heartbeat,
unreadable/failing-save/own-echo/page-switch/import/restore cases, focus/IME/right-click/search
reveal. Add a full-workspace text/table/image frame and EDT-queue scenario before claiming FPS or
input-latency gains; current pure fixtures isolate constituent work only.

### 3. Reduce persistence work and keep transactions short

Implement F9 plus the parent-lookup, queue and migration parts of F10/F12. Add DAOs returning
exact revision state fields and active operation IDs, omitting point blobs and unused relations.
Maintain current ordering, tombstone and atomic checkpoint semantics. Remove those unnecessary
reads first; only then consider immutable snapshot capture followed by off-writer compression and
version validation/retry at commit. Cache validated immutable revision identities without hiding
corruption. Cache distinct parent/deferred status inside a single apply transaction, respecting
same-batch hierarchy changes. Drain pending edits with amortized constant-time queue operations;
ordinary `drop(1)` on List still copies. Keyset-walk old unindexed history and compute derived refs
outside the writer with guarded updates. Keep the deletion guard while any history is unindexed.

Tests: exact Android revision restore/opaque bytes, mixed document+ink checkpoint races, corrupt
matching payloads, fail-in-middle prefix retries, append during suspended drain, generation
invalidation and all-page close/export refusal, parents-last/deferred imports. Assert reads grow
with distinct parents and that checkpoint queries never return `points`. Add contention, payload
size and 40-version trim cases; the current DB baseline does not measure those dimensions.

### 4. Address ink cliffs and selection/eraser preparation

Use F7 and the canvas appendix's lasso/Normal erase findings. Profile canonical finish, snapshot
application, scene/index construction and raster drawing separately. Retain immutable base/tail
segments or prepare compaction on a bounded worker and publish only if the captured page/snapshot
still matches; accumulate intervening edits in order. Verify the actual resolved ByteInk jar's
public APIs before using sibling source APIs. Reuse indexed lasso candidates, prepared polygons
and group membership. Batch lasso display updates once per frame while retaining all original
historical input for final selection. Coalesce obsolete eraser previews and reuse identical final
preparation where ordering/rebase semantics permit.

Tests: 127/128 and 255/256 boundaries, old snapshot immutability, draw order/row binding, concurrent
erase/undo/pending compaction, page/tool/density cancellation and stale result rejection, cut
fragments/groups across segments, Android exact geometry/pixels, all historical samples, raster
memory bounds/disposal. Add Normal erase and full Compose threshold scenarios; the captured
boundary timings exclude drawing and the 40k renderer scenario uses Object erase.

### 5. Improve sync safely, then overlap independent bytes

First resolve the existing stale stream/cursor/version bug documented in `memory/accounts.md`:
`HierarchySync.applyStreamPage` lacks a freshness guard; incoming apply can move the cursor back,
overwrite newer state and delete a dirty outbox entry. This is source-confirmed and previously
observed, not newly reproduced data loss in this review. Coordinate the Android fix and port its
tests before changing sync event ordering/concurrency.

Then gate full picture reconciliation by changed kinds/pages and pending repair work, with an
explicit startup/repair sweep. Replace O(backlog) rank sorting per batch with rank-aware prefix
queries or a compatible keyset; any schema index migration must be coordinated with Android.
Normalize accepted ink state without duplicating immutable base64 `points`, preserving unknown
fields and reconstructing payloads from canonical rows.

Keep one serialized metadata/cursor/idempotency coordinator. Move durable independent picture
worklists to bounded workers, initially downloads, with one task per digest, unique staging,
thread-safe presence/arrival state and account-generation guarded publication. Uploads must finish
before pushing metadata that references their bytes. Start with a small cap, measure RSS, disk
and transport behavior, and tune; do not maximize thread count. A slow blob should not prevent an
unrelated metadata apply. Never run concurrent calls on one acquired Room writer connection.

Tests: stale/duplicate event and newer local edit interleavings first; ink-only event visits no
unrelated picture bodies; missing-file repair; parent ordering, no-room/4 MiB caps and durable
retries; slow deterministic loopback blobs alongside metadata; digest dedup, max in-flight count,
account switch/revoke, 404/mismatch/quota/failure and cleanup. Add loopback throughput/latency and
outbox VM-step baselines before implementing these currently source-only performance findings.

### 6. Reuse recognition/search work and bound bitmap/native memory

Separate query matching from query-independent attachment indexing (F13). A single-flight task
per attachment+engine survives query edits; completed reads populate the existing cache while
only the newest query publishes results. Reserve bounded capacity for interactive typed matching,
preserve real cancellation/timeout/shutdown process cleanup, and group image placements by ID
once. Keep source projections until their body identity changes; timestamps alone are insufficient.

For F15, evict stale demand before loading the next page; deduplicate attachment decodes and use
a viewport/overscan-aware byte-budget LRU with a measured concurrency cap. Preserve offscreen
focused/selected/search-target editors and canvas extent through retained measurements. Profile
native bitmap/ONNX RSS in addition to heap. For OCR logits, validate flat-buffer access and exact
argmax/confidence/tie semantics before changing APIs or model outputs. Formula token steps remain
autoregressive; parallelize independent jobs only within session/memory ownership limits.

Tests: query A→B uses one delayed OCR and returns only B, corpus invalidation/retry/model switch,
worker cleanup, interactive match priority, bounded bitmap bytes/decodes, rapid page switch,
broken/translucent images, focus/IME/search reveal, OCR multilingual/blank/tie/confidence fixtures.
Add cold/warm OCR and 10/50/100-image peak-RSS scenarios before declaring memory or throughput gains.

### 7. Fix PDF grouping, then stream and cull export work

F6 deserves a standalone early change if large exports are a common workload. Replace repeated
sorting/suffix allocation with an indexed interval/cut representation; test exact current cut
choice, group membership/stable IDs and fitted output. A balanced split heuristic is not an
automatic equivalent replacement. Assert improvement on equal-gap rows plus dense/overlapping,
two-column, grouped-ink and adversarial layout fixtures.

For F16, stream section pages, retain lightweight revision/options/paper-keyed measurement, and
visit only visible rows/paragraphs per sheet. Reuse indexed atom lookup rather than scanning for
each wide text. Bound decoded pictures and cached layouts. Later independent page preparation
can overlap only with per-job measurers/native owners and ordered PDF assembly. Mutable Paints,
font caches, the renderer and PDF document stay confined; removing the exporter mutex alone is
unsafe. Preserve staged destination replacement and release/cancellation ordering.

Tests: all Android grouping/tiling/fitting compatibility cases, PDFBox raster parity at boundaries,
exact transforms/IDs, wrapping/bound-sheet behavior, long tables/paragraph work counters,
option/revision invalidation, max in-flight pages, cancellation/destination preservation and
fresh-process startup. Compare planning, first preview, repeated preview, write time and peak RSS
separately. Investigate targeted notebook bundle export after the interactive/sync phases; current
whole-library `VACUUM INTO` is a consistency mechanism and cannot simply be dropped.

## Concurrency ownership and retained protections

| Work | Appropriate ownership |
|---|---|
| Compose state, text editor, live ink controller, raster/draw caches | UI/render thread; immutable worker results with generation checks |
| Document codecs, HTML parse, prepared search/projection, large selection | Bounded CPU workers; latest-result rules for previews, ordered commit for edits |
| SQLite writes/checkpoints/metadata and cursor commits | Serialized transaction owner; minimize payload/lock time |
| Image/OCR/blob jobs | Bounded independent jobs, digest/model single-flight, byte/RSS budget |
| PDF page preparation | Isolated per-job native/measurer state; bounded pages; serialized ordered output |

Retain the current viewport-sized ink raster cache, base/tail reuse, frame-batched wet input,
indexed `(pageId,seq,id)` stroke order/MAX allocation, debounced autosave, pre-decode row
distinctness, existing bounded persistent search workers, OCR cache, formula KV reuse, verified
artifact markers, staged attachment/filesystem writes and transactional failure/close guards.
Those are implemented protections, not unimplemented suggestions.

## Results and verification

All three serial captures completed, with the same fixtures, production/harness hashes, 120
runtime artifacts and Zulu `27+35` runtime. CPU: AMD Ryzen 9 7900X, 24 logical CPUs. Main forks
use a 512 MiB initial / 2 GiB maximum heap; the existing renderer task uses its own 4 GiB maximum.
The complete [48-case table](results/2026-10-21/summary.md) includes each fork median and caller
allocation. Selected median-of-three-fork-medians results:

| Operation and fixture | Median | Caller Java allocation/op | Interpretation |
|---|---:|---:|---|
| Resolve 100 / 500 / 2,000 text-box buffers | 0.145 / 3.781 / 59.956 ms | 0.41 / 10.06 / 200.98 MB | 4× boxes from 500→2,000 causes ~15.9× time; quadratic composition lookup |
| Resolve 200 typed cells, last cell focused | 0.364 ms | 0.28 MB | Bounded per table; repeated work still worth eliminating with the text lookup fix |
| Project 1,000 / 4,000 linked paragraphs | 1.273 / 20.596 ms | 0.38 / 1.47 MB | ~16.2× time for 4× paragraphs; endpoint mapping dominates growth |
| Paste 1,000 / 4,000 plain HTML words | 0.309 / 4.189 ms | 5.23 / 80.90 MB | Expanding run concatenation creates quadratic allocation even for simple HTML |
| Search `a` in 1,000 / 4,000 repeated words | 0.269 / 3.912 ms | 0.046 / 0.173 MB | ~14.5× time for 4× words, before searching additional blocks/pages |
| Group 1,000 / 4,000 equal-gap PDF atoms | 15.606 / 250.307 ms | 79.81 / 1,248.32 MB | ~16× time and allocation; strong isolated grouping hotspot |
| Decode formula using synthetic 50k vocabulary | 8.996 ms | 51.92 MB | Fork medians 17.023 / 8.950 / 8.996 ms; warm parsing is material and variable |
| Apply prepared 127th / 128th append on 40k strokes | 0.020 / 6.179 ms | 0.023 / 9.41 MB | Periodic compaction cliff; boundary fork medians 4.870 / 8.636 / 6.179 ms |
| Coalesced / checkpointed append on 40k stored strokes | 0.193 / 49.740 ms | Not available across workers | Checkpoint path reads/state-packs the whole page; includes file-backed repository work |
| Move a box on page with 1M text characters | 0.447 ms | 7.00 MB | O(page text) duplication for unchanged preview, even with only two boxes |

MB here means 1,000,000 bytes allocated by one calling thread, not retained memory or native
allocations. These are operation measurements on deterministic fixtures, not whole-frame/FPS
claims. Geometry-only preview and single table costs are smaller in isolation; prioritize the
larger quadratic paths while removing their shared unnecessary work. Review findings about sync
throughput, image RSS and complete PDF/render interactions remain source-backed opportunities,
with the additional targeted baselines specified in their implementation phases.

Across all 24 renderer cases (eight × three forks), held wet drawing rebuilt no finished scene,
raster or finished path; all six held observations advanced. Object eraser preview rebuilt no
scene/index. Peak retained viewport raster storage remained 2,097,152–2,105,352 bytes. These
confirm that the earlier cache improvements still work. See [ink summary](results/2026-10-21/ink-summary.md)
for per-case timing and counters; timings retain the software/test-scheduling limitations above.

Verification:

- `lint :shared:jvmTest :desktopApp:test :desktopApp:assemble --offline --max-workers=1`
  under isolated Xvfb passed in 2m25s: shared 1,802 cases (two skips), desktop 151 cases
  (five skips), **1,946 passed, zero failures/errors, seven existing skips**. The nine new JVM
  fixture/statistics/lifecycle tests ran, along with the preserved Android/UI/integration suites.
  [Full log](results/2026-10-21/full-jvm-verification.txt).
- Three capture/aggregation/comparison Python tests pass, including accepting changed project
  jars and rejecting changed external dependencies. Comparing the capture with itself produces
  zero changes for all 48 cases. [Comparison check](results/2026-10-21/comparison-selfcheck.txt).
- All three opt-in 40k renderer runs pass after the completion wait correction, without removing
  or loosening the original geometry/erase assertions. Raw JSON and logs are retained.
- `git diff --check` passes. No production Kotlin, schema, dependencies, protected documentation
  or real profile contents were changed. All database/tokenizer fixtures are synthetic and their
  private temporary directories were removed.
