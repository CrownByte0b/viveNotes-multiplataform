**Desktop improvements: `755073f` → `ed8bc24`**

Before: `755073fabd6581ef8e5bc0f748b8b9598c964f36`.
After: `ed8bc246605d30d17274361307936861047dd144`.
GC optimization is deferred at the user's request; production GC behavior remains unchanged.

The range contains six commits and changes 35 application/test files, including one comment-only
theme edit. Completed production work improves text preparation, page revisits, recent ink view
reuse, startup and installation/account reliability. Additional files provide benchmark tooling
and retained evidence.

The code comparison covers the exact requested range. Performance figures come from existing
paired experiments for each milestone against its immediate predecessor. **There is no single
controlled `755073f` → `ed8bc24` cumulative measurement.** Percentages cannot be combined into a
total speedup. The approximately 23% Linux / 33% Windows callback-time reduction described by
`755073f` predates this range and is already part of the starting point.

**Completed changes**

| Area | Before | After | Commit |
| --- | --- | --- | --- |
| Large text pages | Preparing unknown offscreen boxes could produce a nearly one-second recording callback; exact heights were measured again on revisit. | Definitely-offscreen unknown boxes prepare in batches of at most 64; exact measurements survive suitable page revisits. Potentially visible and focused/selected/search targets remain immediately active. | `0bb1a79` |
| Ink page reopening | Returning to a page decoded/replayed stored ink again and rebuilt scene data. | Fresh storage validation permits bounded decoded-ink reuse, safe append-prefix reuse and immutable scenes prepared on a worker. | `5cbf74f` |
| Recent pan/zoom views | Only the active viewport raster was retained. Returning to an earlier view rebuilt it. | Up to four exact recent views share the existing 64 MiB retained-pixel budget. Content and renderer changes invalidate previous views. | `031fbb7` |
| Math/model retries | A terminal state could appear while its job was still marked active, causing an immediate retry to be ignored. | Clearing the active job and publishing its terminal state happen together. An early artifact failure prevents later sibling downloads from starting. | `9e7fa39` |
| Windows account persistence | Transient file-replacement denial could leave revoked credentials on disk after the in-memory account changed. | Writes retry `AccessDeniedException` up to six attempts, with 750 ms total backoff; persistent failure still surfaces. | `9e7fa39` |
| Ordinary startup | Constructing the recognition engine loaded ONNX even without recognition use. | ONNX environment/decoder preparation occurs on admitted inference work; closing an unused engine leaves ONNX uninitialized. | `5d3571e` |
| Native Wayland diagnostics | The original renderer observer could not attach to SwingGraphics. | A separate JFR observer validates nested rendering calls, ownership, lifecycle, event loss and count parity. Full input benchmarking remains unavailable in the isolated session. | `ed8bc24` |

**Measured performance**

The app workload contains 1,000 text boxes of 1,000 characters each and 40,000 original strokes.
Each comparison uses three alternating fresh before/after pairs per platform. Numbers are
medians; scripted durations include input pacing and polling. Linux app results use X11;
Windows uses its normal console renderer. Displays and scaling differ between platforms.

| Metric | Linux X11 before → after | Change | Windows before → after | Change |
| --- | ---: | ---: | ---: | ---: |
| Worst first-text recording callback | 980.94 → 114.27 ms | −88.35% | 960.74 → 121.26 ms | −87.38% |
| Text revisit | 1,271.17 → 534.31 ms | −57.97% | 1,092.79 → 154.95 ms | −85.82% |
| First return to ink page | 1,138.51 → 610.48 ms | −46.38% | 1,035.07 → 221.87 ms | −78.57% |
| Final return to ink page | 1,028.94 → 503.24 ms | −51.09% | 994.33 → 205.51 ms | −79.33% |
| Workspace readiness, startup change | 2,924.85 → 2,817.77 ms | −3.66% | 3,994.95 → 3,693.84 ms | −7.54% |
| Full-app viewport phase, raster-cache change | 2,296.86 → 2,247.73 ms | −2.14% | 2,212.03 → 2,209.68 ms | −0.11% |

Sources: [text preparation](text-preparation/RESULTS.md),
[ink reopening](ink-reopening/RESULTS.md), [startup](startup/RESULTS.md), and
[viewport raster reuse](viewport-raster/RESULTS.md). Each identifies its own compared revisions.

The separate native software-raster experiment isolates exact return views:

| Eight-view sequence | Measured result across density/view cases | Raster builds across 120 requests |
| --- | ---: | ---: |
| Pan away and return | 71.40–75.34% less time | 120 → 30 |
| Zoom away and return | 60.41–64.06% less time | 120 → 45 |
| Visit new views | −2.59% to +1.56% time change | 120 → 120 |

Every validation view matches a fresh reference PNG exactly. These measurements establish reuse
of identical views, not an equivalent improvement during arbitrary continuous panning.
[Native results and original ink controls](viewport-raster/RESULTS.md).

**Memory, CPU and latency tradeoffs**

| Milestone | Benefit observed | Cost or regression retained |
| --- | --- | --- |
| Text preparation | Sampled peak RSS −5.48% Linux / −8.47% Windows; total recording callback time −23.46% / −19.95%. | First text phase +15.36% / +2.80%; process CPU +8.03% / +7.59%. Overall callback p95 +181.20% / +105.03% as work is redistributed. Worst callbacks still reach roughly 114–121 ms. |
| Ink reopening | Process CPU −11.78% / −13.12%; sampled peak RSS −28.00% / −13.80%. | Linux final used heap +62.11%; Windows maximum observed used heap +12.94%, NMT commitment +5.62%, text revisit +14.75%. Windows ink-return callback p95 rises about 150–153% as counts fall, despite lower maxima and shorter phases. |
| Recent-view reuse | Large reduction in repeated exact-view rendering. | Diagnostic retained image pixels increase from about 1 MiB to 2–3 MiB, reaching 4.016 MiB for unique views. Windows full-app CPU +4.95%; sampled peak RSS +0.92% Linux / +0.21% Windows. |
| Deferred OCR startup | Unused engine construction drops 94.14 → 3.11 ms on Linux Zulu, 100.14 → 3.31 ms on Linux JBR, and 224.30 → 3.92 ms on Windows Zulu. | First OCR takes about 85–94 ms longer on Linux and 220.29 ms longer on Windows. Windows construction plus first OCR +9.82%; lasso phase +2.54% and final used heap +2.53% in the app control. |

Each row describes its own milestone experiment. Natural GC states differ; RSS, Java heap and
Native Memory Tracking measure different things. These observations establish neither a process
memory bound nor a leak fix. Recording callbacks are not GPU execution, displayed FPS or physical
input latency. Three pairs do not establish statistical significance or universal speedups.
Full reports retain smaller phase regressions and individual-fork variation.

**Correctness and resource ownership**

- Text-height reuse checks content, dimensions, density/font scale, style, direction, resolver
  and resolved fonts. Asynchronous video previews reprepare across visits. Retention is bounded
  to 2,048 entries, four pages and 2,000,000 weighted text units.
- Ink rereads storage on every visit. Reuse validates stored fields, encoded bytes, erases,
  moves, targets and operation clocks. Safe appends decode only the added suffix; changed
  historical operations or metadata require full replay. Tests cover stale navigation,
  cancellation, history restoration, account changes and late publication.
- Ink retention is capped at two pages, 80,000 rows/operations, 32 MiB of weighted source data,
  one million samples and 120,000 projections. These are cache limits, not whole-process limits.
  Native renderer paths and rasters still close on page exit.
- Raster reuse requires exact viewport/transform/scale and scene/renderer identity. Tests cover
  edits, erases, undo, invalidation and disposal. Existing append/damage rendering remains covered.
- Lazy recognition preserves admission, session ownership and terminal-close behavior. Measured
  OCR strings and raw confidence bits match the before version.
- This range changes no storage format, model/dictionary, dependency, ByteInk library, Android
  application, server, packaging configuration or visible UI design. The theme edit is a comment.

**Reliability and verification**

Deterministic regressions reproduce the old math/model terminal-state races. The Windows account
test reproduces the old replacement failure and passes after the retry change. Picture-rendering
test instrumentation now publishes its counters in the correct order. Popup keyboard tests wait
for Compose field focus and AWT popup focus before native typing. The latter has not been proven
to resolve the hosted Windows flake: the old test also passed locally under load.

Recorded full Linux JVM results grow from **2,199 passed / 13 skipped** at the starting point to
**2,241 passed / 14 skipped**, with zero failures/errors, passing lint and desktop assembly.
The **42 additional passing tests** comprise 11 text, 15 ink reopening, eight viewport, three
CI/reliability and five startup regressions. The extra skip is the opt-in viewport benchmark.

| Milestone | Full Linux JVM passes | Focused Windows coverage |
| --- | ---: | --- |
| Text preparation | 2,210 | 35 passed |
| Ink reopening | 2,225 | 68 passed |
| Viewport raster reuse | 2,233 | 29 passed |
| CI/reliability repair | 2,236 | Account-store matrix: 24 passed |
| Startup and final diagnostics | 2,241 | Startup: 25 passed, five optional formula-model skips |

Windows runs verified compiled classes; this is not a full Windows Gradle suite or rebuilt
installer. Full-app captures validate drawing, navigation, selection, editing, saved contents and
normal close. The four performance milestones retain 48 completed app captures, plus 60 cold
startup/OCR processes and separate native raster/original ink controls. Failed attempts are retained.

Latest diagnostic verification records 58 Python runtime/native/GC contracts and 14 scanner
contracts passing; earlier tooling has separate recorded suites. For this report, all 27 milestone
source-manifest entries matched their committed revisions; all 26 distinct final files matched
their latest recorded hashes. No application tests or performance captures were rerun.

Evidence: [starting point](runtime/verification.json),
[text](text-preparation/verification.json), [ink](ink-reopening/verification.json),
[viewport](viewport-raster/verification.json), [startup](startup/verification.json),
[final diagnostics](native-wayland/verification.json). Reliability regressions are in
[AiModelStoreTest](../shared/src/jvmTest/kotlin/com/vivenotes/ai/AiModelStoreTest.kt),
[UvMathRuntimeTest](../shared/src/jvmTest/kotlin/com/vivenotes/math/UvMathRuntimeTest.kt) and
[DesktopSyncAccountStoreTest](../shared/src/jvmTest/kotlin/com/vivenotes/data/sync/DesktopSyncAccountStoreTest.kt).

**Wayland and remaining work**

The Wayland observer records **310 nested Compose/redraw/paint triples**, exact live/raw count
parity, zero reported event loss/errors and normal app close. Exception controls pass. The full
input workload stops explicitly because the isolated Weston session lacks the Remote Desktop
portal required by the installed runtime's Robot input path. No native Wayland before/after
workload, presentation timing or observer-overhead result is claimed.
[Wayland evidence](native-wayland/RESULTS.md).

- **GC policy is deferred by the user.** Existing research is retained; no production policy was
  adopted. In 24 prior Linux processes, JBR periodic collection reduced median RSS by 32.68 MiB,
  Zulu showed no RSS benefit, and direct GC pauses reached 8.58 ms. UI/ribbon wake-up,
  retained-engine, import/PDF and Windows coverage remain required before adoption.
  [Existing investigation](idle-gc/RESULTS.md).
- Native Wayland full interaction and presentation measurements remain unavailable.
- Hosted Windows popup-keyboard success is unconfirmed; no new hosted run was checked for this
  report. A separately retained Windows End-key/caret driver timeout also has no demonstrated fix.
- Earlier global erase-cleanup and 40k seal-append limitations remain. Viewport controls measured
  40k seal append 23.225 → 24.196 µs (+4.18%) and fit erase changes from −1.92% to +6.41%.
  All 15 historical preservation-expectation failures also occur identically on that clean
  baseline; they were retained, not weakened or reported as repaired.
- A controlled final cumulative benchmark against `755073f` has not been run. This report
  preserves the available milestone evidence without assigning a combined speedup.
