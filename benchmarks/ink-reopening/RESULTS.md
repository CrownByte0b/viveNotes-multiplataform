# Desktop large-ink page reopening — 2026-10-22

Returning to a 40,000-stroke page now reuses validated decoded ink and worker-prepared immutable scenes. Across three alternating pairs per platform, the median first ink return fell **46.38% on X11** and **78.57% on Windows**. The final revisit fell **51.09%** and **79.33%**. Storage is still reread on every visit, and renderer resources still close when the page leaves the UI.

Process CPU fell 11.78%/13.12% and sampled peak resident memory fell 28.00%/13.80%. Tradeoffs remain: final used Java heap rose 62.11% on X11; Windows maximum observed used heap rose 12.94%, NMT commitment rose 5.62%, and text revisit rose 14.75%. These are short three-fork observations with unequal natural GC states.

## Compared inputs and method

- Before: clean `0bb1a792ff6c9120a192201e07a8bc525b06215b`, the user's committed text-preparation milestone.
- After: that base plus the eleven exact source/test files in [verification.json](verification.json). This is an uncommitted source snapshot; the common base commit does not identify the changed build. The user's existing two-line `Theme.kt` comment change is preserved in the workspace and excluded from the benchmark build.
- Both builds use isolated source copies with empty OAuth build configuration. Source inventories, compiled artifacts, classpaths, runtimes, probes and instrumentation have separate hashes; build execution is logged. Hashes alone do not establish a source-to-binary relationship.
- The existing [text-revisit workload and tools](../text-preparation/USAGE.md) are unchanged. The real app opens 40,000 native-encoded strokes and 1,000 text boxes of 1,000 characters, draws eight strokes, pans/zooms, selects/clears a lasso, edits/saves text, returns to ink, revisits text, returns to ink again, then closes. Persistence, saved text, stroke count and integrity checks pass in every capture.
- Six fresh processes per platform ran serially in B/A, A/B, B/A order. Both sides use the same debug logging, Robot pacing/settles, JFR profile, NMT summary, 512 MiB initial / 2 GiB maximum heap and render observer. All six paired comparisons pass compatibility checks, and all twelve JFR analyses pass observer/event count parity.
- Both platforms use Azul Zulu 27+35. X11 uses an owned 1600×1000 Xvfb display at scale 1. Windows 11 uses its console renderer at 3840×2160 physical, 2194×1234 AWT logical, scale 1.75. App frames are 1280×900 logical. Compare within each platform.

## Results

Values are medians of three per-fork observations; percentages compare those medians. Callback p95 is the median of per-fork nearest-rank p95 values, not a pooled quantile. Scripted phase durations include polling and Robot overhead. All per-fork observations and further callback/count metrics are in [comparison.json](../results/2026-10-22-desktop-ink-reopening/comparison.json).

| Observation | X11 before → after | Change | Windows before → after | Change |
| --- | ---: | ---: | ---: | ---: |
| First ink return, ms | 1,138.51 → 610.48 | -46.38% | 1,035.07 → 221.87 | -78.57% |
| First ink return callback sum, ms | 102.59 → 57.35 | -44.10% | 109.41 → 65.84 | -39.82% |
| First ink return callback p95, ms | 8.38 → 7.81 | -6.78% | 11.15 → 27.93 | +150.49% |
| First ink return worst callback, ms | 64.78 → 23.41 | -63.87% | 63.84 → 27.93 | -56.25% |
| First ink return callbacks | 26.00 → 25.00 | -3.85% | 25.00 → 9.00 | -64.00% |
| Final ink return, ms | 1,028.94 → 503.24 | -51.09% | 994.33 → 205.51 | -79.33% |
| Final ink return callback sum, ms | 147.22 → 38.55 | -73.81% | 74.84 → 39.34 | -47.43% |
| Final ink return callback p95, ms | 5.67 → 5.75 | +1.46% | 9.61 → 24.28 | +152.55% |
| Final ink return worst callback, ms | 129.01 → 22.29 | -82.73% | 49.31 → 24.28 | -50.76% |
| Final ink return callbacks | 24.00 → 21.00 | -12.50% | 27.00 → 9.00 | -66.67% |
| JVM main → workspace, ms | 2,868.36 → 2,887.33 | +0.66% | 3,574.18 → 3,680.62 | +2.98% |
| First text phase, ms | 3,221.47 → 3,119.76 | -3.16% | 2,052.09 → 2,007.21 | -2.19% |
| First text callback p95, ms | 82.75 → 80.86 | -2.28% | 82.87 → 98.52 | +18.88% |
| First text worst callback, ms | 114.52 → 117.98 | +3.02% | 145.05 → 117.09 | -19.28% |
| Text revisit phase, ms | 534.59 → 515.41 | -3.59% | 158.00 → 181.31 | +14.75% |
| Text revisit callback sum, ms | 73.39 → 68.06 | -7.26% | 66.18 → 93.66 | +41.51% |
| Text revisit callback p95, ms | 7.25 → 10.71 | +47.63% | 58.83 → 85.12 | +44.70% |
| Text revisit worst callback, ms | 47.04 → 45.82 | -2.59% | 58.83 → 85.12 | +44.70% |
| Viewport phase, ms | 2,275.90 → 2,249.12 | -1.18% | 2,239.77 → 2,201.32 | -1.72% |
| All recording callback sum, ms | 2,806.84 → 2,610.80 | -6.98% | 3,539.65 → 3,426.86 | -3.19% |
| All recording callback p95, ms | 58.61 → 50.25 | -14.26% | 63.97 → 63.53 | -0.69% |
| Process CPU at shutdown hook, s | 31.99 → 28.22 | -11.78% | 33.94 → 29.48 | -13.12% |
| Sampled peak resident memory, MiB | 2,742.50 → 1,974.59 | -28.00% | 1,821.98 → 1,570.59 | -13.80% |
| Final used Java heap, MiB | 274.50 → 445.00 | +62.11% | 350.50 → 211.75 | -39.59% |
| Final committed Java heap, MiB | 1,123.00 → 1,123.00 | +0.00% | 512.00 → 512.00 | +0.00% |
| Maximum observed used heap, MiB | 593.44 → 452.00 | -23.83% | 355.50 → 401.50 | +12.94% |
| Final NMT committed total, MiB | 1,426.06 → 1,412.97 | -0.92% | 767.00 → 810.14 | +5.62% |
| Process CPU in initial 2 s idle, ms | 30.00 → 20.00 | -33.33% | 15.62 → 0.00 | -100.00% |
| Process CPU in final 2 s idle, ms | 0.00 → 10.00 | — | 31.25 → 46.88 | +50.00% |

Windows ink return now has only nine callbacks, down from 25/27. Its callback p95 therefore approaches the maximum: p95 rises about 150–153% even while the maxima fall 51–56%, sums fall 40–47%, and scripted phases fall 79%. Neither changed callback counts nor small samples support a general tail-latency claim.

Other measured regressions include startup +0.66%/+2.98%, X11 first-text worst callback +3.02%, text-revisit p95 +47.63%/+44.70%, Windows first-text p95 +18.88%, and Windows text-revisit callback sum +41.51%. Final idle process CPU rose 0→10 ms on X11 and 31.25→46.88 ms on Windows over two seconds. Causes and statistical significance are not isolated by these runs.

Every changed-app capture logs the same sequence of completed loads (rows/full-loads/prefix-loads/hits/retained-pages):

```text
40000 / 1 / 0 / 0 / 1   initial ink decode
    0 / 2 / 0 / 0 / 2   text page, no ink
40008 / 2 / 1 / 0 / 2   first return: reuse original 40k, decode eight additions
    0 / 2 / 1 / 1 / 2   text revisit: exact hit
40008 / 2 / 1 / 2 / 2   final ink return: exact hit
```

This confirms the intended reuse path in addition to timing. A separate 40,000-stroke regression verifies shared segment/scene identity across append and revisit; Compose tests verify off-thread scene preparation, unchanged pixels and native raster disposal.

## Implementation and correctness

- One repository-owned coroutine lane reads page/section/notebook/account availability, strokes, erases, moves and operation clock in a single deferred Room reader transaction. Comparison, native decoding/replay and scene preparation then run on the CPU dispatcher after the transaction ends. The lane begins before reading, preventing an older concurrent read from replacing a newer cached snapshot.
- Exact comparison covers every stored stroke field and encoded byte, every erase/move field, target sets and operation clock. Entity equality is insufficient because it omits metadata. Same-timestamp replacements, tombstones and changed operation targets trigger canonical replay. A clock-only change updates the returned clock without decoding the same geometry again.
- An unchanged row prefix and exactly unchanged operations allow decoding only the appended suffix. If an old operation targets an arriving row, full replay preserves shared move clamping and erase behavior. Reordering, removal, metadata or operation changes also use canonical replay.
- Retention is limited to two pages, 80,000 total stored rows/operations, 32 MiB of weighted source data, one million decoded input samples and 120,000 projections. The source weight includes encoded bytes plus string/metadata estimates; it is not a total Java/native/RSS bound. Active UI state, undo history and oversized returned pages have separate lifetimes. Account/notebook changes, unavailable/deleted/closed/cloud pages and library close drop retained entries when encountered.
- Immutable scene values and exact row/projection occurrence maps live with each geometry segment. The worker prepares the normal light/dark foreground variants; a synchronized two-color cache retains them across page visits and unchanged-prefix appends. No renderer path, raster or controller is retained by this cache. Initial loading performs additional scene preparation and is not claimed faster.
- Cancellation and close reject late cache publication. Workspace visit tokens reject old ink results after navigating away and back to the same page, including queued remote refreshes. No storage format, ByteInk, Android, server, dependency or GC-policy change is included.

## Verification

- Full Linux shared/desktop JVM suites, lint and assembly pass: **2,225 passed, 13 skipped, zero failures/errors** (shared 2,070/6; desktop 155/7), in 2m40s. Fifteen new regressions cover cache validity/bounds/lifetimes, 40k reuse, file-backed persistence/history/account changes, visible scene behavior and stale page visits.
- Focused Linux verification passed 184 tests with one opt-in skip before the final two added cases; all final tests are included in the full suite. The first focused run exposed two new-fixture mistakes (duplicate erase insertion and a coroutine-debug thread-name suffix); both were corrected, with failed logs retained.
- **68 focused Windows JVM tests pass** on Zulu 27 using the same target-25 compiled test JARs, including existing ink rendering/disposal regressions. This is actual Windows runtime coverage, not a current native Windows Gradle build or full suite.
- Python contracts: 44 passed on Linux; 43 passed and one existing symlink-capability skip on Windows. The unchanged embedded probe/render contracts and native SQLite fixture pass. All 14 scanner contracts pass; the final redacted changed-file scan is recorded in verification.json.
- Downloaded Windows raw hashes pass. Linux independently recomputed all six decoded JFR analyses with exact structural equality and re-exported all public JSON with exact value equality. The final audit verifies all twelve capture/source/analysis manifests, six comparisons, exact source/test bytes, frozen original artifacts and the empty index.

Full-suite command:

```sh
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
  JAVA_HOME=/home/mahalo/.gradle/jdks/azul_systems__inc_-27-amd64-linux.2 \
  xvfb-run -a -s "-screen 0 1920x1200x24 -nolisten tcp" \
  ./gradlew :shared:jvmTest --rerun :desktopApp:test --rerun lint \
  :desktopApp:assemble --offline --max-workers=1
```

The unchanged capture command is documented in the linked text-revisit usage. Exact commands, source inventories, public Windows package manifest and classpath exports are retained under `build/ink-reopening-verification-20261022/` and in the durable archive. The initial X11 preflight pair is exploratory only; the table uses the six final captures per platform.

## Limits and retained evidence

Recording callbacks measure elapsed Compose/Skia work and command recording before picture finalization; they do not measure exclusive thread CPU, GPU execution, presentation, FPS or physical input latency. Robot/accessibility polling, settles, scheduling, JFR/NMT/debug overhead and startup maintenance are included. OS/native/filesystem caches remain warm.

Natural GC states differ, so the larger X11 final used heap does not establish a leak, and lower sampled RSS does not establish a native-memory bound. NMT omits third-party native allocations; process sampling can miss peaks. Shutdown-hook CPU is not final process CPU. Short idle observations, especially Windows' coarse CPU clock, do not diagnose a busy loop.

Native Wayland timing remains unavailable through the previously tested observer binding. Hosted Windows popup-keyboard CI and the two-axis `performScrollTo` limitation remain separate unresolved issues. The earlier fit-view erase cleanup and 40k seal-append regressions in [ink results](../ink/RESULTS.md) were not remeasured or repaired here; viewport raster reuse is the next milestone.

Sanitized per-fork results are in [the result directory](../results/2026-10-22-desktop-ink-reopening/); [verification.json](verification.json) binds source, tool, report and result hashes. Raw Linux captures remain under `/tmp/vive-ink-reopen-final-x11-*`; downloaded Windows evidence is `/tmp/vive-ink-reopen-windows-raw-20261022.zip`. Build logs, XML, manifests and independent audits are in `build/ink-reopening-verification-20261022/`.

Durable owner-only archives outside the repository and `/tmp` are indexed at `/home/mahalo/.local/state/vive-runtime-benchmarks/2026-10-22-ink-reopening/retention.json`. They preserve all final Linux captures/analyses/comparisons, exploratory captures/fixture, Windows raw evidence, public build inputs, classpath artifacts and verification logs. The prior runtime archive retains the unchanged portable Windows Java/Python distributions. Owned VM source/results folders are `vivenotes-ink-reopen-20261022` and `vivenotes-ink-reopen-results-20261022` under `C:/Users/z0/byteink-verification/`.

All owned measurement jobs and Xvfb displays are finished. Windows console session 1 is logged off, the temporary SSH connection is closed, and RDPWindows is restored to **shut off**, its entry state. Other VMs and user notes/accounts were untouched.

Ready for the user's commit; no staging, commit or push was performed. Pause before the viewport raster milestone until the user resumes.
