Native Wayland EGL application profiling

This opt-in diagnostic launches the actual application on new synthetic, account-free notebooks.
It dispatches AWT events to the application's own component and validates scrolling and the actual
EGL renderer. It is separate from the frozen Robot/native-input workload in `benchmarks/runtime/`.
It does not measure physical input latency, GPU completion or compositor presentation.

Build the application and export its classpath and the Kotlin compiler classpath first:

```sh
./gradlew --offline --no-configuration-cache --init-script benchmarks/runtime/classpath.init.gradle \
  :desktopApp:runtimeProbeClasspath -PruntimeProbeOutput=/tmp/egl-classpath-new
```

Use a JBR 25 runtime, an explicitly selected Wayland display, and a new output directory outside
the repository. No installed/development profile, account, recognition model or original note is
read. Formula and math downloads are disabled in the generated profile. Each seed verifies its
native stroke encodings; the final check requires all original ink and document bytes unchanged.
The sparse default has 419 distinct native curves/highlighters, 24 samples per stroke and 12 columns
distributed over a long page. For a dense view, use `--strokes 10000 --columns 200 --points 64`.

```sh
python3 -B benchmarks/wayland-egl/capture.py \
  --java /path/to/jbr/bin/java \
  --classpath-file /tmp/egl-classpath-new/runtime-classpath.json \
  --compiler-classpath-file /tmp/egl-classpath-new/compiler-classpath.json \
  --output /tmp/egl-baseline-new --forks 5 --variant raster
```

`--contract-only --forks 2 --strokes 12 --columns 3` compiles both probes, executes the Java targeting
contract, then independently seeds/verifies two identical SQLite/native fixtures without opening
a GUI. This also checks that persisted object erases remove authored geometry while keeping the
source rows needed for undo. Fast parser tests need only Python:

```sh
python3 -B -m unittest discover -s benchmarks/wayland-egl -v
```

`--trace` adds JBR method tracing for Compose drawing, the viewport raster cache, renderer, peer lookup
and restacking. Use traced runs to attribute costs, then compare minimally instrumented runs separately.
Scopes are nested elapsed invocation times and must not be summed as exclusive CPU times.
All runs collect JFR profile samples and an inexpensive timestamp after the renderer's drawing and
GPU submission, before swap. The application is not forced to repaint for timing measurements.

`--variant moving` selects the candidate that replays paths during GPU viewport changes and retains
raster reuse for stationary views and software rendering. `--variant direct` forces replay on every
draw for diagnostic comparison. The accepted default is the moving policy; it needs an accelerated
frame, so software and other backends retain their raster behavior. `--variant paired --candidate moving` alternates raster/candidate order for each
fresh-process pair and verifies identical fixture, geometry and application bytes. One new
profile is used for every fork. Do not run builds, other benchmarks, or test suites during a capture.
The launcher copies project classpath entries into its owned output so concurrent rebuilds cannot
delete a running application's classes; a changed source/binary inventory still invalidates the run.

Phase boundaries record process RSS, kernel high-water RSS and Java heap use. Captures snapshot
their harness sources and record available compositor/monitor/Mesa identities.

The wheel producer follows absolute monotonic deadlines independently of EDT completion. Each event
records its intended deadline, actual post time, dispatch start/end and direction. Summaries separate
producer lateness, EDT queueing and handler duration. Delivery count/order and the exact nominal
schedule must match. Canvas position is queried from a fresh accessibility tree at phase boundaries,
since Compose may replace accessibility objects after an action. The canvas may span the window
when notebook navigation is hidden; targeting does not assume its center is to the right of the frame.

`--scenario interactions` additionally checks Ctrl-wheel zoom and return, actual pen strokes and
object erasing through AWT mouse events with database acknowledgements, requested resize/context
reuse, and an accelerated pen-settings popup dismissed by Escape. These phases include explicit
acknowledgement waits. Pen/erase events also follow independent 16ms schedules, with validated
delivery and separate queue/handler statistics; event timestamps are taken when posted. Resize evidence records whether the compositor honored
the size request. This is application interaction coverage, not OS/tablet/touch delivery coverage.

Use `--ink-repetitions 10` for 20 persisted strokes and 300 fixed-rate pen events, followed by
object erasing. This gives a longer stationary-drawing comparison than the default two-stroke smoke check.

Inspect `capture.json` only after `complete:true`. It binds source/app/runtime/harness hashes,
commands, fixture identity and every fork's summary. Raw profiles, JFR, readiness dumps and failed
attempts stay in the chosen output directory. A failed motion check, software fallback, lost input,
unclosed surface, changed fixture or changed binary prevents a successful capture. Frame submission
counts and draw-completion intervals are not displayed FPS; no presentation-time feedback is collected.

For isolated verification displays, `run_tests.py` owns and closes a private Xvfb or headless GL
Weston process. Give it a new output directory and the command after `--`:

```sh
python3 -B benchmarks/wayland-egl/run_tests.py --mode x11 --xvfb /path/to/Xvfb \
  --output /tmp/egl-jvm-checks-new -- ./gradlew :shared:jvmTest :desktopApp:test \
  --continue --no-parallel --max-workers=1

python3 -B benchmarks/wayland-egl/run_tests.py --mode wayland --jbr /path/to/jbr --scale 2 \
  --output /tmp/egl-native-checks-new -- ./gradlew :desktopApp:nativeWaylandTest \
  --no-parallel --max-workers=1
```

Native checks run with DISPLAY unset and compositor screenshots enabled. Headless tests establish
correctness; they do not substitute for performance measurements on the real desktop compositor.

For a packaged comparison, give `--flatpak-build` an assembled or bundle-extracted Flatpak directory,
`--java` its `files/jbr/bin/java`, and a classpath JSON listing exactly its `files/lib/*.jar` paths.
`--bundle` additionally records the bundle hash. The driver runs through `flatpak build --runtime`
with networking and X11 unavailable, a private writable profile and read-only `/app`. Application
classes must load from `/app/lib`; the diagnostic probe classes are the only extra classes. No app
installation or installed-profile changes are required. Extracting an actual exported bundle into
an owned temporary repository before capture verifies the delivered artifact's bytes.
