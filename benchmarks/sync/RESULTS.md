# Desktop sync milestone — 2026-10-21

Desktop metadata commits can now proceed while picture bytes upload or download. Transfers retain
two bounded workers and per-digest ownership; metadata commits and outgoing batch orchestration
remain serialized. The database stays at schema 2 and attempted requests retain their batch IDs
and wire payloads across retries.

The final controlled capture reduced metadata wait from **253.397 ms to 1.113 ms (99.56%)** while
one simulated 1 KiB upload waited 250 ms. This measures coordinator contention; it does not measure
internet throughput, startup, rendering or frame latency.

| Fresh JVM fork | Before, ms | After, ms |
| --- | ---: | ---: |
| 1 | 253.397321 | 1.236535 |
| 2 | 253.380380 | 1.101122 |
| 3 | 253.453754 | 1.113070 |
| Median of fork medians | 253.397321 | 1.113070 |

Each fork performs two warmups and six measured operations. Before/after executions alternate,
run serially, and use identical diagnostic source. All six JVMs use Zulu 27+35, a 512 MiB heap,
matching VM flags and matching external artifact hashes. The baseline is checkpoint commit
`b35c45d`; the after source is identified by production hashes because this milestone is left
uncommitted for the user. The [final summary](../results/2026-10-21-desktop-sync-final/summary.json)
contains every sample, runtime identity, dependency hashes and source hashes. XML, classpaths and
raw `*-log.txt` files accompany it. The earlier
[capture](../results/2026-10-21-desktop-sync/summary.json) is retained as superseded evidence.
The original algorithm/renderer harness and all three original capture directories are unchanged.

The implementation also removes duplicate metadata work:

- Lagging stream pages cannot regress the committed cursor or reapply already accepted versions.
  Version checks use bounded, version-only SQL projections instead of loading accepted document
  and ink JSON. A 200-stroke duplicate page reads 200 versions plus the result terminator and no
  accepted JSON rows.
- A never-sent batch is revalidated after unlocked picture I/O. An attempted batch consumes its
  cached reply before inspecting local picture bytes, preserving exact replay after a lost reply.
- Older/equal cached acknowledgments and conflict snapshots preserve later local edits, including
  reused outbox generations. Purge invalidation is stored in local batch metadata before deleting
  or remapping rows. Imported descendants are requeued against their new server bases. Legacy
  orphan replies are ignored while physically retained released pictures still record acceptance.
- Account/source changes cancel active transfers and invalidate work queued behind the transfer
  pipeline. Old downloads cannot publish bytes after a purge or account change.
- Picture candidates are published after commit, survive concurrent scans, and are rediscovered
  after cancellation at the commit boundary. Reconciliation requests cannot be overwritten by an
  older scan, and a completed 404 cannot be requeued by stale file-presence results.

Deterministic Room/SQLite and coroutine regressions cover these transitions, rollback, durable
restart, exact retry payloads, transfer cancellation and source ownership. Existing Android-ported
compatibility tests remain intact. The new opt-in live probe is excluded from ordinary test runs.

Live compatibility passed against the user's running local server and existing emulator app:

1. Desktop code used a separate temporary database and created one uniquely owned notebook with
   text, a valid PNG and genuine ByteInk data.
2. The existing Android app displayed all three. One additional stroke was drawn in that page.
3. Desktop received both strokes and decoded both through the normal ink reader.
4. Desktop pushed the owned notebook's deletion; Android navigation confirmed its removal.

Two live JVM phases actually executed successfully. Raw account data, notes databases, layouts and
screenshots stay outside the repository. Android, server and ByteInk source were not modified.

Full shared/desktop JVM verification, lint and assembly pass in 2m28s: **2,091 passed, nine skipped,
zero failures/errors** (shared 1,945 passed/four skipped; desktop 146 passed/five skipped). Seven
skips are existing optional-model/camera cases; two are the opt-in diagnostics, executed separately
for this milestone. The 31 new deterministic sync regressions execute in the full suite. The
benchmark tooling's three Python contracts pass. Counts, skipped cases, source hashes and live
receipts are recorded in [verification.json](verification.json).

To repeat the contention capture, create a clean checkout of `b35c45d`, copy the current
`HierarchySyncTest.kt` into the corresponding path there, then run from this checkout:

```sh
JAVA_HOME=/usr/lib/jvm/java-latest-openjdk \
python3 benchmarks/sync/capture_contention.py \
  --before /tmp/vive-desktop-sync-before-b35c45d \
  --after "$PWD" --output /tmp/vive-desktop-sync-new-capture --forks 3

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/sync -p 'test_*.py'
```

The output directory must be new. Only the contention diagnostic is run against the old source;
the added regression cases describe behavior implemented by this milestone.

The live probe requires `VIVE_DESKTOP_LIVE_SYNC=1`, `VIVE_DESKTOP_LIVE_ACCOUNT` pointing to the
existing local-server account preferences, and a fresh `VIVE_DESKTOP_LIVE_DIRECTORY` under `/tmp`
whose basename starts with `vive-desktop-live-`. Run `:shared:jvmTest --tests '*DesktopLiveSyncProbeTest'`
with `--no-configuration-cache -I benchmarks/sync/live-probe.init.gradle`, setting
`VIVE_DESKTOP_LIVE_PHASE=prepare`, then contributing a stroke through the existing Android app,
then `VIVE_DESKTOP_LIVE_PHASE=verify`. The init script forces actual execution of each externally
dependent phase. Use phase `cleanup` to delete only the recorded owned notebook if interrupted.

Ink, OCR and broad application/runtime optimizations remain in the saved follow-up checkout for
the next user-authorized milestone after this commit pause.
