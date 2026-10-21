# Prepared sync freshness candidates — NOT APPLIED

These review artifacts are part of the 2026-10-21 performance work. They are proposed patches,
not changes to either app's current sync behavior. The user authorized performance improvements,
but `memory/accounts.md` separately requires fixing this shared correctness bug in Android first
and then porting its production change and tests. The parent's scope question is still pending:
matching fixes in both apps, an explicit desktop-only override, or defer the freshness-dependent
sync work. Do not infer approval from the general optimization request.

Read-only inspection of Android HEAD `883895f` and all available local refs found the known bug
still present. The older Android `bug.md` path in memory is absent. No Android files were edited.

## Files and verification

- `desktop.patch`: candidate production guards against the current optimized desktop source.
- `desktop-tests.patch`: four regression tests for the desktop's existing fake-server suite.
- `android.patch`: matching Android production, query-only batch DAO read and instrumentation
  regressions against the inspected upstream checkout. No database entity or schema change.

All three patches pass `git apply --check` against their respective current checkouts. They have
**not** been applied, compiled or executed. The regression tests have **not** run; passing patch
application checks are not test evidence. Existing storage/concurrency tests do not verify this
unapplied candidate. The frozen benchmark and harness are unchanged.

## Proposed behavior

1. Ignore incoming entity versions at or below the last acknowledged server version before
   changing local rows, accepted state or the outbox. A duplicate own echo cannot discard the next
   local generation; an older delivery cannot replace a newer server base.
2. Keep Android's existing whole-entity server-wins conflict policy for strictly newer versions,
   including replacing a concurrent dirty local edit. This does not add merging.
3. Commit `max(current cursor, supplied cursor)` in the same transaction as applied metadata.
   Permanent purges remain idempotent and apply even when an event carries an older cursor.
4. Batch-read known versions before parent admission and ignore obsolete changes first. An old
   streamed page drops its obsolete held rows, so a stale child cannot wait forever on a parent
   that was permanently purged. The transactional entity guard also handles duplicate keys within
   one batch. Clamping the cursor alone does not prevent dirty-generation loss.
5. Retain the existing serialized metadata coordinator. These candidates do not release the
   metadata mutex or increase event concurrency.

The four regressions cover a lagging cursor/body against a newer dirty body; a duplicate accepted
echo against the next local generation; a strictly newer version retaining server-wins behavior;
and a stale child after permanent parent purge. Existing parents-last, cloud defer/purges, exact
retry, generation acknowledgement and stream tests must remain green in both applications.

Independent attachment transfers already use two bounded workers under the existing metadata
mutex. Moving byte work outside that lock remains dependent on resolving this scope and verifying
matching freshness behavior. Approval of these patches would still require focused tests in each
app, full applicable suites and a separate safe result-handoff design before releasing that lock.
