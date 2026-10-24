# Startup and first recognition

These opt-in diagnostics compare `9e7fa39` with the same revision plus lazy recognition preparation.
They do not change the frozen runtime/OCR harnesses, model bytes, dependencies, or normal app flags.
Run all builds, native tests, GUI captures, and VM captures serially.

Export each isolated checkout's normal app classpath with
`benchmarks/runtime/classpath.init.gradle` (`:desktopApp:runtimeProbeClasspath`,
`-PruntimeProbeOutput=/absolute/new/directory`, `--no-configuration-cache`). Keep private
`local.properties`, accounts and notes out of those checkouts. Both app resource files must have
empty desktop OAuth credentials before transferring a test package.

Run the Python contracts:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/startup -p 'test_*.py' -v
```

`recognition.py` runs a real engine in a new JVM for each observation. Supply `--java`,
`--before-project`, `--before-classpath`, `--after-project`, `--after-classpath`,
`--compiler-classpath`, and a new `--output` outside both source trees. Classpath files are the JSON
exports above. The compiler classpath comes from the same Gradle export. Default: five alternating
pairs for each of `unused` and `ocr`. A before-only capture is supported for investigation, and
is not a completed comparison.

The first invocation builds `StartupRecognitionProbe.kt` for JVM 25 and renders a synthetic
320×64 “hello” fixture from the checked-in Inter font. Reuse that exact PNG with `--fixture`
and the compiled directory with `--probe-classes` for subsequent runtimes and Windows. A marker
binds the supplied classes to the probe source; the manifest also records their exact bytes.
As with the existing runtime harness, a supplied binary is trusted build input, not proof of
compilation. Preserve the compiler log and transfer manifests. Windows exported source inventories
use the existing `vive-runtime-source-inventory/1` schema; no Git credentials/history are needed.

Each process uses a new model directory, automatic download disabled, and an isolated user home.
It waits for bundled model verification before measuring construction. In OCR mode, fixture/Skia
preparation also happens before construction. Timed first OCR includes worker dispatch, native
initialization if deferred, graph loading, preprocessing, inference and decoding. Three later
requests check identical output and confidence, and provide descriptive warm timings. Close runs
twice to exercise idempotence. Used/committed Java heap snapshots retain natural GC states.

`-Xlog:class+init=info` is enabled identically on both sides, with the raw log retained. The unused
candidate must not initialize ONNX; OCR must initialize it and open/close exactly one recognizer
and CTC reducer. The comparison rejects changed fixture, output, raw confidence, runtime or
external dependency identities. It reports both first-request latency and construction plus
first-request time, so deferred work stays visible. These are cold processes with warm/uncontrolled
OS/filesystem caches; they are not cold disk or reboot measurements.

`runtime_matrix.py` runs three alternating pairs using the **unchanged**
`benchmarks/runtime/desktop_runtime.py` workload: 40,000 original strokes, 1,000 text boxes,
real drawing, scrolling/zooming, lasso, typing, page switching, persistence checks and normal close.
Supply the same project/classpath arguments, `--java`, `--probe-classes` compiled by the frozen
runtime harness, `--expected-commit 9e7fa39`, `--backend x11|windows`, and a new `--output`.
X11 requires a caller-owned `DISPLAY`; Windows requires an isolated console session. It neither
creates a display nor logs into a VM. Obtain the runtime probe from a contract capture as described
in `benchmarks/runtime/USAGE.md`.

The matrix retains JFR, native-memory snapshots, all source/runtime/dependency hashes, full
persistence/shutdown evidence, per-pair comparisons, and sanitized exports in `public/`.
JFR native-library snapshots must show ONNX on the baseline and its absence on the candidate's
non-recognition workflow. `main`-to-workspace readiness includes the probe's 100 ms polling,
an initial ribbon action and render observation. It is not first-pixel time, GPU presentation,
physical input latency, or proof of the cause of the earlier historical startup regression.
The existing native Wayland render-observer limitation remains; JBR headless recognition results
are not a native Wayland window timing claim.

Never overwrite earlier captures. Archive raw inputs and results outside the repository and
`/tmp` before cleaning. Reports must retain slower first requests and any other regressions.
