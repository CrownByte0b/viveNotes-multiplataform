# Desktop text preparation and measurement reuse — 2026-10-22

Text pages now prepare definitely-offscreen editors in batches of at most 64 and reuse exact settled measurements when revisited. In three alternating pairs per platform, the median worst first-text recording callback fell **88.35% on X11** and **87.38% on Windows**. The scripted text revisit fell **57.97%** and **85.82%**, respectively.

This is a responsiveness/reuse tradeoff. The scripted first text visit became **15.36% slower on X11** and **2.80% slower on Windows**, process CPU increased about **8%**, and the overall callback p95 increased. No first-load throughput, GPU, FPS or physical input-latency improvement is claimed.

## Compared inputs and workload

- Before: clean `755073fabd6581ef8e5bc0f748b8b9598c964f36`, the runtime-profiling commit supplied by the user.
- After: that same commit plus the eight source/test files in [verification.json](verification.json). The after build is an uncommitted source snapshot; the shared base commit alone does not identify it.
- Both builds came from isolated source copies without local account configuration. Google OAuth build-info values are empty. Source inventories, compiled artifacts, classpaths, Java binaries, probe classes and instrumentation are independently hashed. Build logs establish the build execution; hashes alone do not prove a source-to-binary relationship.
- The unchanged synthetic corpus has 40,000 original ink strokes and 1,000 text boxes of 1,000 characters each. The normal application entry point runs with debug logging enabled on both sides. Every run draws eight strokes, pans/zooms, selects/clears a lasso, opens/edits/saves text, returns to ink, revisits text, then returns to ink and closes normally.
- This separate [probe extension](USAGE.md) adds two two-second settle windows, a real text-page revisit, saved-token verification and a final ink return. It imports the frozen original launcher, render observer, analyzer and exporter. All original workload/persistence checks remain. Original benchmark tools and captures are unchanged.
- Six fresh processes per platform ran serially in B/A, A/B, B/A order. JFR profile, NMT summary, 512 MiB initial / 2 GiB maximum heap, Robot pacing and the render observer are identical within each pair. Each pair passed compatibility checks; each of the 12 JFR analyses passed observer/event count parity.
- Both platforms use Azul Zulu 27+35. Linux uses an owned 1600×1000 Xvfb display at scale 1; Windows 11 uses its console renderer at 3840×2160 physical, 2194×1234 AWT logical and scale 1.75. App frames are 1280×900 logical. Compare within each platform, not between the different displays/hosts.

## Measured results

Entries below are medians of three per-fork observations. Percent changes compare those medians. Per-fork p95 values use nearest ranks; they are not pooled quantiles or statistically established tail latencies. Full observations and additional callback/count metrics are in [comparison.json](../results/2026-10-22-desktop-text-preparation/comparison.json).

| Observation | X11 before → after | Change | Windows before → after | Change |
| --- | ---: | ---: | ---: | ---: |
| JVM main → workspace, ms | 2,826.81 → 2,832.81 | +0.21% | 3,697.47 → 3,617.59 | -2.16% |
| First text scripted phase, ms | 2,660.84 → 3,069.54 | +15.36% | 1,906.14 → 1,959.48 | +2.80% |
| First text callback sum, ms | 1,389.74 → 1,389.02 | -0.05% | 1,365.02 → 1,412.62 | +3.49% |
| First text callback p95, ms | 97.68 → 82.41 | -15.63% | 118.79 → 84.55 | -28.83% |
| First text worst callback, ms | 980.94 → 114.27 | -88.35% | 960.74 → 121.26 | -87.38% |
| First text callbacks | 49.00 → 62.00 | +26.53% | 27.00 → 41.00 | +51.85% |
| Text revisit scripted phase, ms | 1,271.17 → 534.31 | -57.97% | 1,092.79 → 154.95 | -85.82% |
| Text revisit callback sum, ms | 899.32 → 65.75 | -92.69% | 989.10 → 61.27 | -93.81% |
| Text revisit callback p95, ms | 750.46 → 6.67 | -99.11% | 831.35 → 49.27 | -94.07% |
| Text revisit worst callback, ms | 750.46 → 43.79 | -94.16% | 831.35 → 49.27 | -94.07% |
| Text revisit callbacks | 12.00 → 20.00 | +66.67% | 7.00 → 5.00 | -28.57% |
| First ink return, ms | 1,128.81 → 1,119.55 | -0.82% | 1,039.04 → 1,054.71 | +1.51% |
| Final ink return, ms | 958.21 → 1,071.81 | +11.86% | 1,132.11 → 983.31 | -13.14% |
| Viewport phase, ms | 2,239.29 → 2,337.43 | +4.38% | 2,213.60 → 2,222.40 | +0.40% |
| All recording callback sum, ms | 3,393.17 → 2,597.00 | -23.46% | 4,391.36 → 3,515.07 | -19.95% |
| All recording callback p95, ms | 20.17 → 56.71 | +181.20% | 30.76 → 63.08 | +105.03% |
| Process CPU at shutdown hook, s | 28.64 → 30.94 | +8.03% | 32.11 → 34.55 | +7.59% |
| Sampled peak resident memory, MiB | 2,890.62 → 2,732.19 | -5.48% | 1,972.48 → 1,805.45 | -8.47% |
| Final used Java heap, MiB | 471.00 → 300.00 | -36.31% | 390.08 → 291.75 | -25.21% |
| Final committed Java heap, MiB | 1,028.00 → 1,110.00 | +7.98% | 512.00 → 512.00 | +0.00% |
| Maximum observed used heap, MiB | 748.50 → 603.50 | -19.37% | 398.08 → 352.50 | -11.45% |
| Final NMT committed total, MiB | 1,325.90 → 1,410.64 | +6.39% | 763.95 → 801.99 | +4.98% |
| Process CPU in initial 2 s idle, ms | 40.00 → 30.00 | -25.00% | 0.00 → 0.00 | — |
| Process CPU in final 2 s idle, ms | 0.00 → 0.00 | — | 15.62 → 0.00 | -100.00% |

The initial work is spread across more callbacks: 49→62 on X11 and 27→41 on Windows. The first-text maximum improves while its total callback time stays approximately level on X11 and rises 3.49% on Windows. The overall p95 rises 181.20%/105.03%; callback counts and the distribution change, so the smaller maximum must not be presented as an across-the-board latency improvement. About 114–121 ms is still a noticeable long callback.

Other observed regressions remain visible: X11 startup +0.21%, viewport +4.38% and final ink return +11.86%; Windows viewport +0.40% and first ink return +1.51%; final NMT commitment +6.39%/+4.98% and X11 committed heap +7.98%. These three-fork observations do not isolate causes or establish significance. Ink reopening, raster reuse, startup and GC remain separate follow-up milestones.

Each changed-app run logs 1,000 initial text layouts and zero reused measurements. On reopening, all 1,000 measurements are reused; the completion log sees one visible-editor text layout on X11 and four on Windows. These are counters at page-preparation completion, not a guarantee that no later editor layout occurs. The Compose regression separately verifies all 1,000 exact heights, final extent and the background admission limit. Fixed settle windows alone are not treated as preparation evidence.

## Implementation and correctness

- A box with unknown height stays immediately active whenever its known top and horizontal span could intersect the viewport. Long boxes starting above the viewport are preserved. Focused, selected, menu, keyboard and search targets bypass the background queue.
- Definitely-offscreen unknown boxes use the existing real editor/preview for measurement, with at most 64 background admissions per batch. Placement completion drives the next frame admission; idle pages suspend the scheduler. Minimum-size placeholders do not count as exact text measurements. Existing provisional page-extent behavior remains until exact placement.
- A cache owned by the window/notebook retains at most 2,048 entries, four pages and 2,000,000 weighted text units (64 per block, 32 per run, plus characters). Active-page layout state has its own lifetime. This bound is not a byte, native-memory or RSS limit; stored font intrinsics can retain platform resources.
- Reuse requires equal text blocks, width/minimum height, density/font scale, editing/thumbnail source, text style, layout direction and font resolver, plus current resolved-font intrinsics. Adopted keys use current immutable content. Deleted boxes/pages are pruned; notebook/window changes drop the cache. Stale page/request placements are rejected.
- Video-bearing previews with a thumbnail source are not cached across page visits, including missing or failed loads. They still wait for asynchronous completion and final placement. Synchronous previews avoid an extra readiness frame.
- Optional internal counters expose composition, preparation, layouts, placements and reuse to tests. Existing debug mode emits one page-level start/completion pair without document text. Editing during initial preparation is covered by a completion-log regression.

## Verification

- Full shared/desktop JVM suites, lint and desktop assembly passed on Linux: **2,210 passed, 13 skipped, zero failures/errors** (shared 2,055/6; desktop 155/7). The exact final source bytes were then frozen for both isolated builds and all final captures.
- Eleven new deterministic/Compose tests cover conservative geometry, batch bounds, unfinished admissions, exact-key/font invalidation, cache budgets/eviction/removal, invalid entries, the million-character page and revisit, width/content/font-scale changes, platform resolver changes, focused distant editing, delayed media cancellation/revisit, and completion logging after edits. Existing virtualization, video, selection/caret/IME and rich-text tests remain in the full suite.
- Windows ran **35 focused JVM tests**, all passing on Zulu 27, using the same target-25 compiled test classes packaged as JARs. This is runtime coverage, not a current native Windows Gradle build or full Windows JVM-suite run.
- Linux Python tooling: **44 passed** (41 original + three extension). Windows: **43 passed, one skipped** because that host cannot create the symlink required by one existing path-isolation contract. No tests were weakened.
- The extended native/SQLite fixture and 25 embedded probe contracts pass. All 14 scanner contracts pass; the final changed-file scan is recorded in verification.json.
- Downloaded Windows raw-file and analysis hashes passed. Linux independently recomputed all six decoded JFR analyses with exact structural equality and independently re-exported all public JSON with exact value equality (Windows originals use CRLF). All 12 capture/source/analysis manifests and all six comparison results passed the final audit.

Exact full-suite command:

```sh
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
  JAVA_HOME=/home/mahalo/.gradle/jdks/azul_systems__inc_-27-amd64-linux.2 \
  xvfb-run -a -s "-screen 0 1920x1200x24 -nolisten tcp" \
  ./gradlew :shared:jvmTest --rerun :desktopApp:test --rerun lint \
  :desktopApp:assemble --offline --max-workers=1
```

## Retained experiments and limits

The unchanged original runtime workload first established a clean-current baseline. Exploratory after runs used 16-box batches (8.76 s first-text phase), synchronous readiness (7.30 s), 64-box batches (3.65 s), then direct placement-driven admission (3.09 s), versus a 2.68 s exploratory baseline. Their maximum callbacks fell from 993.54 ms to about 48–111 ms. These single runs and changing implementations are tuning evidence; they are not the final paired text-revisit measurements. Every exploratory capture is retained separately.

An initial Windows input extraction hit MAX_PATH on generated Kotlin test-class filenames. Test directories were repackaged as JARs and extracted into a fresh folder; Windows policy was unchanged. The failed package/setup log remains. An intermediate Gradle classpath-export helper failed configuration-cache serialization after production tasks passed; the final verification command above excludes that helper and passed. A custom font-resolver test fixture was unsupported by the pinned Skiko backend and was replaced with its real platform resolver factory; no real downloaded asynchronous-font test is claimed.

Recording callbacks measure elapsed Compose/Skia CPU work and command recording before picture finalization, not exclusive thread CPU, GPU execution, presentation or FPS. Delivered AWT-event-to-next-callback observations do not prove causality or physical latency. Robot pacing, accessibility polling, fixed settles, scheduling, JFR/NMT/debug overhead and normal startup maintenance are included. OS/native/filesystem caches remain warm and the hosts/displays differ.

Natural GC states differ between snapshots. Lower sampled RSS and used heap do not establish a leak fix or a native-memory bound. NMT omits third-party native allocations; process sampling can miss peaks, and shutdown-hook CPU is not final process CPU. Idle CPU snapshots are short and coarse, especially on Windows.

Native Wayland timing remains unavailable through the prior observer binding; no new native Wayland benchmark is claimed. The previously observed hosted Windows popup-keyboard timeout and Compose two-axis performScrollTo limitation remain separate unresolved issues. No workflow, ByteInk, Android, server, dependency, storage-format or GC-policy change is part of this milestone.

## Evidence and handoff

- Sanitized per-fork source/runtime provenance, summaries, JFR analysis and memory snapshots: [results](../results/2026-10-22-desktop-text-preparation/). [verification.json](verification.json) binds source/test/tool/result hashes and exact test counts.
- Build/test/XML/transfer/independent audit logs: `build/text-preparation-verification-20261022/`.
- Durable owner-only archive: `/home/mahalo/.local/state/vive-runtime-benchmarks/2026-10-22-text-preparation/retention.json`. It retains all final Linux raw captures/analyses/comparisons, exploratory runs, the fixture contract, Windows raw evidence, initial/corrected public input packages, classpath artifacts and verification logs. The prior runtime archive retains the unchanged portable Windows Java/Python distributions.
- Original temporary Linux captures remain under `/tmp/vive-text-revisit-x11-*`; downloaded Windows evidence is `/tmp/vive-text-windows-raw-20261022.zip`. VM sources/results remain in owned `vivenotes-text-v2-20261022` / `vivenotes-text-results-20261022` folders.
- All owned measurement jobs are finished. The owned Windows console session 2 and temporary SSH connection are closed; RDPWindows remains running as found, and the other VM is untouched. User notes/accounts were not used.
- Ready for the user’s commit. No staging, commit or push was performed. Pause here; the next implementation milestone is large-ink page reopening after the user resumes.
