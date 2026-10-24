# Desktop startup investigation — 2026-10-23

**READY for the user's commit: Linux and Windows implementation and verification pass.**
Baseline: clean `9e7fa39`. Candidate: that revision plus `InkRecognitionEngine.kt` and the new
`RecognitionStartupTest.kt`. No commit, staging or push was performed. Idle-GC policy remains
the next separate milestone, after the user's commit pause.

## Finding and change

The first window composition obtains `DesktopNotes.drawMath`, constructs `DesktopDrawMath`, and
constructs `OnnxInkRecognitionEngine`. The baseline constructor immediately calls
`OrtEnvironment.getEnvironment()` and creates the CTC decoder. JFR confirms both ONNX native
libraries are loaded even when the actual app only draws, edits text, switches pages and closes.

The environment and decoder are now lazy. Native preparation happens during an admitted request
on the inference worker. Closing or evicting an unused decoder does not initialize it. Existing
lane admission, model eviction, active-use draining, tensor ownership, and terminal close guards
remain. The first preparation emits one content-free debug event only when debugging is enabled.
Model files, dictionaries, inference thread counts, dependencies, ByteInk and storage are unchanged.

This removes unnecessary native initialization from ordinary startup. It **moves work to the
first recognition request**, rather than making native initialization faster. It does not prove
that eager ONNX loading caused the older cumulative runtime report's startup regression.

## Cold-process recognition controls

Five alternating pairs per runtime and mode, with new JVMs and model directories. Same target-25
probe, synthetic PNG, bundled PP-OCRv6 model/dictionary, classpath dependencies and VM flags on
both sides. One inference lane/thread isolates preparation cost. Class-initialization logging is
enabled identically. Every recognized string SHA and raw confidence bit pattern matches; every
used recognizer/reducer is closed exactly once. Every unused candidate leaves ONNX uninitialized.

Medians, milliseconds:

| Runtime | Unused construction, before → after | First OCR, before → after | Construction + first OCR, before → after |
| --- | ---: | ---: | ---: |
| Linux Zulu 27 | 94.14 → 3.11 | 160.25 → 244.80 | 249.26 → 246.91 |
| Linux JBR 25 | 100.14 → 3.31 | 156.65 → 250.15 | 248.18 → 252.15 |
| Windows Zulu 27 | 224.30 → 3.92 | 189.05 → 409.34 | 375.05 → 411.88 |

The first request is **52.76% / 59.69% slower**, about **85 / 94 ms** extra on Linux Zulu/JBR.
Combined construction plus first OCR changes −0.94% / +1.60%; warm requests change between
−1.62% and +0.15%. On Windows, first OCR is **116.52% slower (+220.29 ms)**, and combined
construction plus first OCR is **9.82% slower (+36.83 ms)**. Windows warm requests change
−2.06%, −0.12% and −1.81%. The Windows combined regression remains part of the result;
moving initialization does not establish equal total first-use cost across runtimes.
JBR's unused close is +6.41% (+0.021 ms); Windows unused close is +8.96% (+0.042 ms).
Each raw observation, process lifetime, Java heap snapshot, output identity and source/runtime
hash is retained in `../results/2026-10-23-desktop-startup/recognition/`.

These are cold **processes**, with warm/uncontrolled filesystem and OS caches. The fixture and
Skia are prepared before the constructor measurement. First OCR includes worker dispatch, model
open, preprocessing, inference and decoding. This is an engine API diagnostic, not measured
Search UI latency or a formula-model performance claim. Optional large formula graphs were not
downloaded for this milestone.

## Actual application on X11

The unchanged runtime harness runs three alternating fresh pairs (B/A, A/B, B/A) on an owned
1920×1200 Xvfb display. Each synthetic profile contains 40,000 original native strokes and 1,000
text boxes. All six runs pass real drawing, viewport, lasso, typing, page return, original-content
preservation, saved edit verification, normal close, source/runtime/dependency integrity,
comparison, JFR counter parity and native-library checks.

| Metric | Before median | After median | Change |
| --- | ---: | ---: | ---: |
| JVM main → workspace ready | 2,924.85 ms | 2,817.77 ms | −3.66% |
| Process launch → workspace ready | 3,136 ms | 3,021 ms | −3.67% |
| Sampled peak RSS | 1,900.08 MiB | 1,883.24 MiB | −0.89% |
| Process CPU at shutdown hook | 26.44 s | 26.27 s | −0.64% |
| Recording callback sum | 2,485.79 ms | 2,441.31 ms | −1.79% |
| Recording callback count | 308 | 299 | −2.92% |
| Normal close → shutdown hook | 16.79 ms | 16.21 ms | −3.45% |

Workspace-ready observations, before → after, milliseconds:
`2887.88 → 2838.46`, `2946.11 → 2817.77`, `2924.85 → 2815.47`.
Every baseline maps both `libonnxruntime.so` and `libonnxruntime4j_jni.so`; every candidate omits
both throughout this non-recognition workflow, including shutdown.

Retained regressions: drawing phase +0.32%, ink autosave settle +0.51%, lasso +1.16%, and text
autosave settle +0.04%. Other scripted phases and all per-fork observations remain in
`x11/aggregate.json`; no phase was removed. Final used Java heap changes −0.25%, committed heap
−1.52%, and NMT commitment −0.38%. These snapshots have unequal natural GC states. Individual
forks can regress even when the median improves; this does not establish a memory bound or leak
fix. NMT excludes some third-party native allocations.

Readiness includes 100 ms polling, the initial ribbon action and a returned recording callback.
It is not first-pixel time, display presentation, physical latency, or a statistically established
universal speedup. Three paired observations remain susceptible to host, JIT and OS cache noise.
The untouched original text/ink/cache performance reports retain their earlier limitations.

## Actual application on Windows

The same unchanged workload passes three alternating fresh pairs on the dedicated Windows VM,
using Zulu 27 and the normal WToolkit console renderer at scale 1.75. All six captures pass
workload, original-content preservation, saved-edit verification, normal close, comparison,
source/runtime/dependency integrity, JFR counter parity and native-library checks. Every baseline
maps `onnxruntime.dll` and `onnxruntime4j_jni.dll`; every candidate omits both. All six JFR event
analyses, public exports and three comparisons were independently recomputed after download.
Comparison path normalization uses the original Windows roots; its compatibility gates are
unchanged. These measurements use target-25 application classes, not a rebuilt native installer.

| Metric | Before median | After median | Change |
| --- | ---: | ---: | ---: |
| JVM main → workspace ready | 3,994.95 ms | 3,693.84 ms | −7.54% |
| Process launch → workspace ready | 4,333 ms | 3,992 ms | −7.87% |
| Sampled peak RSS | 1,583.60 MiB | 1,580.00 MiB | −0.23% |
| Process CPU at shutdown hook | 28.77 s | 27.70 s | −3.69% |
| Recording callback sum | 3,494.82 ms | 3,361.98 ms | −3.80% |
| Recording callback count | 244 | 240 | −1.64% |
| Normal close → shutdown hook | 52.14 ms | 51.40 ms | −1.42% |

Workspace-ready observations, before → after, milliseconds:
`3994.95 → 3785.59`, `4038.44 → 3587.90`, `3991.16 → 3693.84`.
Retained regressions: lasso phase +2.54%, final used Java heap +2.53% (376 → 385.5 MiB), and
the paced final idle interval +0.008%. Final committed Java heap remains 512 MiB; NMT commitment
changes −0.72%. Every phase and per-fork observation remains in `windows/aggregate.json`.
Unequal natural GC states, VM scheduling and the different platform displays prevent a leak,
memory-bound, or cross-platform latency claim. The X11 readiness and instrumentation limits
also apply here. No failed app capture was discarded or replaced in this Windows matrix.

## Native Wayland

JBR 25's actual WLToolkit/SwingGraphics app opens the synthetic ink page, makes its page controls
accessible, paints the expected content, and closes through the normal window-close path. A
fresh-process class-initialization log confirms no ONNX initialization. The owned nested Weston
and Xvfb processes are closed. This is a lifecycle/visual smoke, **not native Wayland timing**;
the earlier unsupported render-observer limitation remains. An initial headless Weston attempt
lacked `wl_seat`; its failed probe and logs are retained, and the retry supplies an isolated X11
input seat without changing app sources.

## Tests and evidence

- Full shared/desktop JVM suites, lint and desktop assembly pass: **2,241 passed, 14 skips,
  zero failures/errors**. Five new regressions cover cold unused/closed admission, concurrent
  first use, cancellation before worker admission, failed model-open retry, and opt-in logging.
  Existing real OCR and native lifetime tests also pass. The fresh-process eager-initialization
  assertion fails on the baseline; the two new preparation-event expectations also fail there.
- Windows focused JVM tests: **25 passed, five optional formula-model skips, zero failures**,
  including all five new startup regressions. These reuse the hash-verified target-25 test
  classes on Zulu 27; this is not a full Windows Gradle suite. The same Linux-tested production
  and test bytes remain unchanged. Windows Python: **40 runtime passes, one symlink skip,
  and eight startup passes**. Together the three recognition runtime matrices contain
  **60 fresh measured processes**, and the X11/Windows matrices contain **12 actual app runs**.
- Python: **41 runtime + eight startup contracts pass**. All **14 scanner contracts pass**
  with isolated Git configuration; the initial run hit the user's global fixture-commit hooks.
  A prefixed-path payload scan initially reported existing fake test tokens and published
  checksums; rescanning each source root under the unchanged exact-path exceptions passes.
  No scanner rule or hook was changed.
- Source/test/report/result hashes and current completion status: `verification.json`.
  Public observations: `../results/2026-10-23-desktop-startup/`. Local build/XML/audit evidence:
  `build/startup-verification-20261023/`. Raw captures, failed setup attempts, synthetic profiles,
  JFR, and compiled probes remain outside the repository; retention is recorded in verification.

The user explicitly approved the prepared 216,190,419-byte Windows package and destination with
"ok go" after the initial automatic transfer rejection. All 1,967 payload files and 114 reused
dependencies were rehashed before and after verification. The downloaded raw ZIP contains 477
hash-verified files and is retained alongside the earlier Linux captures and prepared inputs.
Initial result-collection command-length/PowerShell-policy failures are retained; they occurred
after all measured runs. Collection succeeded using a script file and a process-scoped policy.
The first cleanup check expected the earlier console ID 1 and refused to act; the resumed run's
owned console 2 was then verified and logged off. No users remain, temporary SSH is closed, and
RDPWindows remains running as found. No user notes/accounts or machine-wide policy changed.

Startup verification is complete. Pause for the user's commit before continuing with idle GC;
idle GC is not implemented or started.
