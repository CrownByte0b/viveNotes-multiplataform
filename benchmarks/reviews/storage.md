# Storage, session, transfers and sync performance review

Reviewed 2026-10-21. Source review plus isolated SQLite EXPLAIN inspection; parent is coordinating
all elapsed-time measurements and JVM tests.
Production/build files were not changed. Line numbers refer to the current checkout before the
parent adds a benchmark harness. Read AGENTS.md, memory/initial.md, memory/persistence.md and
memory/accounts.md first. Do not treat source comments as requirements: findings below follow
executable code. Android compatibility and the accounts memory's requirement to fix shared sync
semantics in Android first apply to the proposed sync changes.

## Highest-priority findings

### S1 — Page serialization and deserialization run on the Swing event thread (P1)

Evidence and path:

- `desktopApp/src/main/kotlin/com/vivenotes/desktop/DesktopNotes.kt:86` gives `WorkspaceSession`
  the caller's UI context; `DesktopNotes.open` supplies MainScope at line 264.
- `shared/src/commonMain/kotlin/com/vivenotes/workspace/WorkspaceSession.kt:139` consumes its
  ordered work queue on that scope. `save` calls `store.saveDoc` at 919–920; `load` calls
  `store.loadDoc` at 933; the document flow is collected on the same scope at 157–163.
- `shared/src/jvmMain/kotlin/com/vivenotes/data/NotesRepository.kt:668` encodes the complete
  document and `pagePreview` at 669 before entering the Room transaction at 671. `loadDoc` resumes
  after its suspending DAO at 598–599 and synchronously calls the full decode at 608–609.
- `observeDoc` adds `.map(::decodeDoc)` at 101–103, with no `flowOn`, so that map also runs in
  the UI collector context. `loadRevision`/`restoreRevision` unpack gzip, hash and decode before
  entering a transaction (620–648).

Impact: document-size CPU and allocations on every debounced save, open and observed own-write
echo compete directly with pointer input, text editing and Compose. Room's IO query dispatcher
does not move computation before/after a DAO call. The stored observer also decodes each own
save that `acceptStoredDocument` later rejects as its own body (WorkspaceSession:723).

Proposal: inject a CPU dispatcher into the JVM repository and explicitly dispatch encode,
decode, preview and revision integrity work, returning immutable results to the session. Keep
session mutation, generation checking and queue ownership confined to Main. Avoid cancelling a
partly committed save merely because a newer preview exists. Keep page-unreadable semantics.

Tests: dispatcher-aware deterministic tests for encode/decode boundaries; existing session
generation/own-echo/read-only/failed-save regressions; Compose input responsiveness while a
large document save is deliberately paused; file-backed content/revision round trips.

Benchmark: 10, 100, 1,000 text outlines with realistic blocks/runs; encode+preview and load/decode
separately from SQLite; then file-backed coalesced saves and timed UI-thread heartbeat delays.
Use small mutations in the final outline so a benchmark does not only time no-op equality.

### S2 — Full-document reference scans repeat for every stable sync event (P1)

Evidence:

- `shared/src/jvmMain/kotlin/com/vivenotes/data/sync/HierarchySync.kt:249–258` calls
  `materializeReferencedRemoteAttachments()` whenever a streamed page has `hasMore=false`,
  including an event consisting only of a single stroke.
- That method (1804–1815) scans `contents.picturePlacingBodies()`, decodes every matching
  document through `DocumentPictures.idsIn`, and reconstructs all image reference counts.
- DAO at `shared/src/commonMain/kotlin/com/vivenotes/data/db/Daos.kt:741` is
  `SELECT * FROM page_content WHERE docJson LIKE '%attachmentId%'`; the leading wildcard
  makes finding candidates scan body values, and pictures on unrelated notebooks get decoded.
- `AttachmentBlobSync.downloadMissing` (176–179) then calls `missingBytes` (237–239), which
  queries *every* attachment id and performs a filesystem existence check on each one.

Impact: E small incoming events cost O(E × total stored document bytes), plus O(E × attachment
count) filesystem metadata operations, even when no picture or body changed. This is repeated
work in the active pen-sync path, not just one-time migration work. It runs off UI but consumes
CPU and holds the sync mutex (see S4).

Proposal: track changed kinds/pages/digests through each committed batch; skip attachment work
for pure ink/hierarchy events unless pending bytes need retry. Materialize only refs from changed
body/attachment rows, backed by a validated per-page reference index/cache. Retain an explicit
full reconciliation for startup/repair and cloud restore. File-arrival knowledge can be a bounded
presence cache/pending set with explicit invalidation for import/sweep/cloud eviction. Never use
`refCount` alone as permission to delete an attachment.

Tests: seed 1,000 pictured pages, apply an ink-only event and assert zero unrelated body decodes;
remote metadata-before-body/body-before-metadata and deferred-notebook cases; deleted local file
eventually repairs; pictures survive undo/versions/held editor state; loopback stream+blob test.

Benchmark: stable single-row ink events on a fixed page, varying unrelated pages (100/1,000/5,000)
and attachment files (0/1,000/10,000), reporting SQL body bytes read, decodes, file stats and
event latency. A synthetic transport avoids conflating network variability with local work.

### S3 — Checkpointing rereads all immutable ink blobs while holding the sole writer (P1)

Evidence:

- `NotesRepository.kt:731–733` checkpoints before ink mutation when 30,000 ms have elapsed
  since the newest revision (`REVISION_CHECKPOINT_INTERVAL_MS`, line 1146).
- `checkpointOf` (690–700) calls `ink.byPage`, `inkErases.byPage`, `inkMoves.byPage`; these
  are full-row queries. Stroke query is `SELECT *` (`Daos.kt:869–872`), including `points`.
  Erase/move relation queries also fetch their opaque blobs and target lists.
- `InkSnapshot.from` in `shared/src/jvmMain/kotlin/com/vivenotes/data/InkRevisions.kt:37–46`
  consumes only stroke id/color/theme/group and operation ids. Geometry/targets just fetched
  are discarded by this operation.
- The same transaction compresses/hashes that state and the old document, and computes picture
  ids by a full document decode (`DocumentRevisions.kt:26–47`). `storeCheckpoint` (703–717)
  may additionally inflate/decode matching existing payloads before deciding not to insert.

Impact: the first stroke/save after each checkpoint window has latency proportional to total
page geometry bytes rather than the small edit. Other page saves, strokes and sync applies wait
for the sole writer resource; this remains true though it runs on IO.

Verified dispatcher qualification: `NotesDatabase.kt:160` configures Dispatchers.IO. Cached Room
2.8.5 `useWriterConnection` source executes the entire block via `withContext` on that database
context, so this checkpoint CPU is *writer contention on IO*, not a claim of EDT compression.

Proposal: add projection DAOs for stroke state and active operation ids; preserve exact sort
order and tombstone semantics used by snapshots. Remove unused point/target reads first. Then
consider a consistent snapshot/version stamp, compression outside the write transaction and a
short validate/commit step. Do not simply move reads outside the transaction and risk history
describing an impossible mixture of document and ink. Reuse parsed picture refs tied to the old
body identity, and validate integrity once per immutable revision identity without weakening
corruption detection.

Tests: existing exact revision restore and Android compatibility tests; large opaque points
remain byte-identical; concurrent document/ink edit while checkpoint is prepared; corrupt
matching revision is rejected; retained checkpoint reflects one atomic old state.

Benchmark: fixed ink row count with points payload 32/512/4,096 bytes proves unnecessary blob
traffic separately from row count; then 1,000/10,000/50,000 strokes, zero/some erase/move rows.
Measure first append beyond 30 s and coalesced append separately, plus concurrent save queue delay.

### S4 — All picture transfers hold the hierarchy sync mutex and run serially (P1/P2)

Evidence:

- `HierarchySync.kt:206–208` wraps its entire serialized entry points in `mutex.withLock`.
  `applyStreamPage` waits through `blobs.downloadMissing` before returning at 258–260;
  ordinary `run` does the same at 412. Initial blob preflight at 1147–1166 uploads unique digests
  sequentially, with optional HEAD followed by PUT for each one.
- `AttachmentBlobSync.kt:179–207` performs one GET at a time for the complete missing list.
- `SyncServerClient.kt:2042–2045` has 10 s connect/30 s read timeouts. It correctly runs IO off
  Main (1584–1589); that does not release the outer sync mutex.

Impact: first sync of a photo-heavy notebook takes summed transfer latency. A slow old picture
can prevent a newly typed metadata change from taking the same mutex, despite the stream reader
being separately buffered. With 100 pictures and 100 ms/request latency, downloads alone have a
10 s latency floor before bandwidth/IO; this is a model, not a measured result.

Proposal: preserve one serialized metadata/cursor/idempotency coordinator, but snapshot a durable
digest worklist, then run bytes using a bounded 2–4 worker pool outside that mutex. Per-digest
single-flight, generation/account cancellation, checked publish and explicit result handoff are
needed. Blob uploads must complete before pushing a referencing batch; safe independent GETs
can overlap metadata. Quota/no-room handling and first-network-failure cancellation must be
defined before parallel upload, so begin with downloads. Existing mutable `serverHolds`,
`deferred`, and arrival increments are not thread-safe for a naive parallel `.map { async }`.

Tests: paused slow download does not prevent applying a new text event; bounds/in-flight count;
duplicate digest requested once; revoke/switch account cancels cleanly and does not publish a
stale result; 404, digest mismatch, transport failure and quota semantics; no partial files.

Benchmark: loopback transport with deterministic 25–100 ms latency and 1/10/100 picture files,
record transfer throughput and metadata latency while transfers run. Record one timed-out or
failed request separately so stop-on-network-failure behavior remains measurable.

### S5 — Deferred-notebook gate performs three identical lookups per incoming stroke (P1/P2)

Evidence: `HierarchySync.applyPulledChange` (787–803) invokes
`belongsToDeferredNotebook` (807–823). Each payload row fetches page metadata, then section,
then notebook's deferred marker. A batch of 2,048 strokes on the same page performs 6,144 parent
lookups inside the writer transaction. `admit` has a separate per-page parent cache at 723–747,
but that cache does not cover this later gate.

Proposal: cache page→section→notebook→deferred status within the apply transaction, after
hierarchy upserts; invalidate/cache-update on relevant page/section/notebook mutations in the
same ordered batch. Or prefetch unique parent chains using chunked key queries. Batch
entity-state writes/ink upserts/outbox acknowledgements after this lower-risk improvement;
do not parallelize one confined Room writer connection.

Tests: 2,048 same-page strokes trigger parent lookups by distinct page/section/notebook count;
same-batch notebook closure and page arrival preserve deferred rules; parents-last existing
contracts; cursor atomicity on failure and immutable blob compatibility.

Benchmark: extend existing `InkSyncCostTest.firstPullOfADrawnNotebookWhosePageArrivesLast`
with counted queries and same-page vs many-page variants.

## Additional verified scaling issues

### S6 — Outbox sorting rescans the remaining backlog for each bounded push (P2)

`SyncDao.outbox` (`Daos.kt:113–121`) uses ORDER BY CASE(kind), entityId LIMIT 512.
`SyncOutboxEntity` (`Entities.kt:73–76`) has only PK(kind, entityId), which does not order by
the CASE rank. `HierarchySync.pushOutbox` reselects at each batch (969–970), and
`loadOrCreatePendingBatch` at 1308–1309 selects from the entire remaining queue. Source review
predicts a full scan plus temporary sorter, repeated as n falls by at most 512 each time; total
row visits roughly n²/(2×512) until the per-run 1,024 batch cap. The implementation already fixed
the *JSON byte-size accumulation* quadratic at 1303–1325; do not report that older bug as open.

Proposal: preserve explicit rank ordering by querying each kind with the existing PK prefix and
filling a batch in rank order, or coordinate a rank/ordering index migration with Android. A
generation-aware keyset avoids rescanning skipped no-room rows while retaining exact retries.

Isolated EXPLAIN inspection on SQLite 3.51.2, using exact schema-2 DDL and DAO SQL, confirms
`SCAN sync_outbox` and `USE TEMP B-TREE FOR ORDER BY`; `benchmarks/reviews/query-plans.txt`
records the schema hash, SQL and full plans. This uses Python's host SQLite, not the bundled JVM
driver. Total drain growth is still a source-derived inference, not a timed result. Retain bundled
driver EXPLAIN in the parent's baseline if engine parity is required. Test parent-before-child,
no-room skip, concurrent new generations, exact durable batch retries and 4 MiB byte cap.
Benchmark drain at 4,096/16,384/65,536 short strokes and report DB VM steps/query count as well
as elapsed time. Existing InkSyncCostTest is a natural seed.

### S7 — Pending ink queue append and acknowledgement copy the whole backlog (P2)

`WorkspaceState.kt:446,491,677,734,756` appends using immutable list `+`. In
`WorkspaceSession.kt:865–872`, each successful first entry is removed with `filterNot` over
the full remaining list and a new StateFlow state is published. A q-entry backlog drains with
q+(q-1)+...+1 predicate visits and list copies (O(q²)). This appears after storage stalls/fails,
after a large batch of undo edits, or after optimistic input outpaces persistent writes. A normal
one-entry queue hides the issue.

Proposal: use a persistent deque/chunked queue with O(1) amortized append/remove-first and stable
command identities; batch acknowledgement/publication when semantically safe. Preserve
strict write order, partial-prefix retries, generation invalidation and all-page close/export
guards. A plain `drop(1)` still copies an ordinary Kotlin List and is not the complete fix.

Tests: fail a middle edit then retry, concurrent append while drain suspends, restore/import
invalidates old commands, undo/redo order, close cannot lose pending edits. Benchmark q=100,
1,000,10,000 with an immediate fake InkWriter to isolate queue/StateFlow CPU from database cost.

### S8 — Initial page open both observes and explicitly loads/decodes the same document (P2)

On selectedPageId change, `WorkspaceSession.kt:157–163` starts `observeDoc`, while
`react` at 841–844 enqueues `load` at 930–947. Both read/decode the same row; the observer's
first value is ignored while Unloaded (720), or recognized as the session's own after `accept`.

Proposal: make the initial document observation the load source, with a tagged initial-load
generation and ordered save-before-open barrier, then trigger ink load once. Alternatively
share an immutable raw row/stamp across the initial load and observer. Do not remove the
selected-body subscription, which is necessary for remote replacements.

Tests: counted initial reads/decode, delayed initial emissions, A→B→A navigation while writes
are queued, own echo cannot roll back newer typing, remote replacement wins, unreadable page.
Benchmark repeatedly opening a large text-only page with a warm database cache.

### S9 — First picture sweep/index migration repeatedly scans all indexed versions (P2)

`AttachmentSweep.kt:140–150` repeatedly selects up to 64 unindexed versions, then marks them.
`Daos.kt:814` is `WHERE pictureIds IS NULL LIMIT :limit`, without an index on that predicate
(`Entities.kt:219` only indexes pageId,createdAt). Each next batch scans from the beginning past
the already indexed versions, predicting O(R²/64) row visits for large imported/schema-1 history.
It also inflates/decodes every batch inside a writer transaction. The main sweep (66–116)
performs complete body/ref scans and per-attachment state/outbox queries under that writer.
The isolated exact-schema EXPLAIN confirms `SCAN page_revisions` (host SQLite 3.51.2); the
companion picture candidate query confirms `SCAN page_content`. Full plans are retained in
`benchmarks/reviews/query-plans.txt` with the engine qualification above.

Proposal: keyset-walk versions with an existing stable PK, precompute each batch's derived ids
off the writer, then update only immutable revision identities still unindexed. Keep the
unindexed-count deletion guard. Batch state/outbox reads for attachment decisions. A partial
index is an alternative requiring Android-coordinated schema migration. Daily cadence makes
this less urgent than S1–S5, but startup immediately runs it (`DesktopNotes.kt:208–211`).

Tests: imported histories with arbitrary ids, corrupt revision once-only indexing, late arriving
unindexed version protects deletion, concurrent version removal, rollback/file deletion safety.
Benchmark 1,000/10,000/40,000 small revision rows plus a sparse-already-indexed fixture; report
writer wait time for interactive autosave, not only maintenance duration.

### S10 — Exporting one notebook snapshots and rewrites the entire library (P2)

`NotebookTransferManager.kt:128` calls `createConsistentSnapshot`, which runs whole-database
VACUUM INTO on Room's sole writer (208–214). The snapshot then deletes unrelated notebooks
(273), drops caches/sync tables, loads documents/revisions, and VACUUMs the output again (349).
Exporting a one-page notebook from a 5 GiB library first copies that library and blocks use of
Room's writer for the source snapshot. The operation is off UI but delays autosave/ink/sync.
`DatabaseBackupManager.kt:58–63` similarly occupies the writer while copying a daily backup;
that copy is intentional safety work, so avoid deleting or weakening it just for speed.

Proposal: longer term, create a format-compatible bundle database from only the selected
notebook in a consistent read snapshot, using bounded table copies and retaining all referenced
ink/history/attachment semantics. Alternatively investigate a supported online backup/read
snapshot facility before relaxing writer coordination. For backups, defer expensive work until
interactive startup settles and expose bounded/cancellable progress; safety ordering stays.
Do not run VACUUM on Room's existing read-only pool: the current implementation deliberately
uses a writable connection because SQLite rejects it on a read-only handle.

Tests: Android bundle compatibility; export concurrent with edit/sync; tombstone/revision/opaque
ink preservation; missing/damaged attachments still fail; cancellation and atomic destination
replacement; reopen exported/imported database independently. Benchmark the same tiny notebook
against libraries with 0/100 MiB/1 GiB unrelated content to isolate the scaling issue.

### S11 — Accepted push state temporarily stores ink points twice (P2)

`HierarchySync.kt:1039–1040` stores each accepted push via `sent.asAcceptedState(version)`.
That mapper (2071–2076) copies the complete payload, removing only `baseVersion`; all three ink
payloads contain base64 `points` from their immutable row. The pull mapper deliberately removes
those points before storing the same entity state (2014). Thus, before the own stream/pull echo,
local ink is stored both in `ink_strokes`/operations and base64 in `sync_entity_states`. The sync
mutex prevents own echoes being applied while the full initial push drain is in flight, so this
can cover an entire large upload. Even after the echo strips the field, SQLite/WAL pages and
write traffic have already been spent. This is verified source behavior; its duration/size impact
depends on echo scheduling and has not been measured here.

Proposal: make accepted-state normalization remove the known immutable `points` field for ink
kinds exactly as the pull path does; snapshots already reattach points from the canonical ink row
(1458,1471,1492). Keep unknown server fields intact. This is a small candidate for the upstream
Android port workflow, with public sync tests that inspect accepted entity-state JSON before
any echo, simulate a subsequent recolor/move retry and compare exact pushed bytes.
Benchmark database/WAL growth and CPU for the existing first-upload fixture, plus inspect the
stored `serverJson` after pushPending but before an echo; this should be a work/bytes assertion,
not an elapsed-time threshold.

## Existing good work to preserve

- SQLite WAL, IO query context, bundled driver and one deliberate writer preserve database
  semantics. Multiple asynchronous calls against one transaction are unsafe and do not create
  SQLite writer parallelism.
- Debounced document autosave (400 ms), view preference persistence (500 ms), 30 s revision
  coalescing, 40 revisions/page and skip-if-content-identical checks already reduce writes.
- `observeDoc` performs row distinctness before decode, protecting unsaved typing from unrelated
  Room invalidations. Removing that guard would be a correctness regression.
- Native ink replay moves to Dispatchers.Default (`NotesRepository.kt:868`). Ink is row-based,
  so text edits do not serialize stored geometry; `(pageId,seq,id)` supports page order and MAX
  sequence allocation. Do not mislabel MAX(seq) on that indexed prefix as an O(n) full scan.
- Reorder resolves ids via maps/sets and writes only changed positions (`NotesRepository.kt:428`).
- Search body and image-text fetches are chunked in groups of 400 (207–213); import's collision
  audit also uses chunked id queries and maps (`NotebookTransferManager.kt:872–957`).
- Hierarchy input admission is keyed by missing parent and releases breadth-first (723–747,
  2129–2147), avoiding older held-row rescans. Its parent availability cache is per incoming page.
- Sync byte-cap sizing is accumulated exactly once per candidate row (1303–1325).
- Foreground scheduler uses conflated wakeups and no redundant pull after stream events
  (`ForegroundSyncScheduler.kt:60–80`); stream buffer is one page deep and read/parse overlaps
  applying the previous page (`SyncServerClient.kt:1217`, 1295, final `.buffer(1)`).
- Picture bytes are content-addressed, staged, digest-checked and atomically published. Presence,
  defer and server-held memos already exist. Parallel work must preserve these lifecycle rules.
- Most transfer validation passes are integrity/security boundaries, not accidental duplicates;
  optimize by fusing a hash/CRC/staged copy pass, not by dropping validation.

## Correctness prerequisites for concurrency work

1. **Existing stale stream/cursor bug, prerequisite to changing sync concurrency.** This is already
   recorded in `memory/accounts.md` under "Known engine bug" with a request to fix Android first,
   then port its production change/test. The current desktop source still contains the behavior:
   `HierarchySync.applyStreamPage` has no cursor/version rejection at entry (229–238);
   `applyIncomingPage` writes the supplied cursor without a monotonic guard (571); and
   `applyPulledChange` puts the supplied server state (788), applies it (800–801) and deletes the
   dirty outbox key (803) without comparing the incoming version to the stored version. A lagging
   event can therefore overwrite a newer body and discard a local dirty generation. The writer
   mutex alone does not establish server-event freshness. Performance work must not make this
   worse by allowing more event reordering. First port the upstream fix with stale page/cursor,
   newer local edit, duplicate delivery and stream/push interleaving regressions. This is a
   source-confirmed existing correctness risk, not a newly reproduced data-loss test here.
2. **Keep Room connection confinement and atomic checkpoints.** Parallel tasks cannot use the
   same acquired connection; ordinary database writes remain serial. Derive immutable CPU work
   outside a short transaction only with an explicit state stamp and compare/retry at commit.
   `loadInk` currently reads strokes, erases, moves and clocks in separate awaits (863–868);
   if these reads are consolidated or parallelized, define a consistent snapshot rather than
   accidentally widening their observation window.
3. **Make byte worker state safe before worker pools.** `AttachmentBlobSync.serverHolds` and
   `deferred` are ordinary mutable sets (87–88). `AttachmentStore.syncBytes.publish` increments
   `_arrivals.value += 1` (96), which is not an atomic increment. Same-image imports use the same
   `<digest>.part` staging file (146). A bounded worker pool must use a coordinator/single-flight,
   atomic arrival updates and unique staging or per-digest serialization. Publishing after an
   account switch/revoke needs a defined ownership/result protocol; sweeping/deleting files
   must remain after the corresponding row transaction commits.

Suggested implementation order: S1 dispatcher boundaries plus S3 projection queries; S5 batch
lookup caching; S6/S7/S9 repeated-scan/queue fixes; S2 incremental picture reconciliation;
upstream stale-stream prerequisite followed by S4 byte workers; S8 one-source initial load and
S10 targeted export after the lower-risk hot-path changes. Run the full JVM suite at each
reviewable production phase, with focused deterministic/integration/Compose tests first, and
compare the same preserved baseline fixture sizes. Functional tests should assert work counts,
ordering, concurrency caps and lifecycle semantics; avoid fragile timing thresholds.

## Baseline fixture recipes for the parent

Use temporary directories only. `NotesDatabase.create(File(tmp,"notes.db"))` is a real file/WAL
fixture; `NotesDatabase.inMemory()` is appropriate for isolating DAO CPU. Setup publicly via
`NotesRepository(db, clock={now})`, then createNotebook→createSection→createPage. This matches
`NotesRepositoryTest.kt:44–47`. Construct PageDoc using `Outline.Text(id=...,x=...,y=...,width=...,
blocks=listOf(Block.of(text)))`, as that test does at 51–58.

First `saveDoc` checkpoints the initial empty row: seed a baseline body outside the timer. For
coalesced saves hold `now` fixed/below +30,000 ms, alternate two different bodies and verify row
contents to prevent a no-op benchmark. For checkpoint-heavy saves, increment time by
`NotesRepository.REVISION_CHECKPOINT_INTERVAL_MS` per sample and vary bodies to avoid dedup.
Do not mix these phases into one number.

Bulk seed rows through `db.inkStrokeDao().upsert(rows)` outside the timing. For database-only
append/checkpoint tests, `NotesRepositoryTest.kt:249–267` demonstrates opaque `points=byteArrayOf(1)`,
`enc="test/1"`; normal addStroke does not decode input. To time replay too, use genuine
`ViveInkCodec` fixture inputs like `InkSyncCostTest.kt:182–200`, reusing one encoded payload and
unique ids/seq for bulk rows to avoid timing native fixture construction.

Existing `InkSyncCostTest` uses a file-backed NotesDatabase (55–64) and synthetic accepting/
parents-last transports, measures real hierarchy.run calls, verifies counts and prints elapsed,
requests and bytes (105–147,161–179). Its log text still says "in-memory Room" despite the real
file fixture; label new evidence correctly instead of trusting that source comment/string.

For useful before/after comparisons retain raw samples, warmups/forks, fixture sizes and hashes,
commit/status, Java/OS/CPU, JVM flags, DB mode, assertions and whether measured phase is cold or
warm. Record p50/p95 and allocations (where possible), CPU vs wall time and queue/EDT latency.
Keep machine-dependent timing thresholds out of functional unit tests; deterministic tests can
assert call counts, maximum concurrency and O(distinct-parent) work.
