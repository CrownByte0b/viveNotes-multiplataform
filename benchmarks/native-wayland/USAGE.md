# Native Wayland method timing

This additive adapter uses JBR 25's `jdk.MethodTrace` on the application's existing
SwingGraphics host. The original `benchmarks/runtime/` workload and observer are unchanged.
The adapter compiles that exact workload with this directory's `RuntimeRenderProbe.kt`.

The observer measures elapsed invocations of `ComposeSceneMediator.onRender`,
`SwingRendererBase.redraw`, and `SkiaSwingLayer.paint`. The scopes are nested; never add their
durations together. These are not CPU-exclusive, GPU, input-latency, presentation or FPS metrics.
JFR has no receiver identity or successful-return flag. The observer requires one visible AWT
window, its EDT, and the SwingGraphics path, and reports the limitations explicitly.

Live counts support the existing readiness waits. `RecordingStream.stop()` drains the stream
before its recording is dumped and closed. Offline analysis checks its counts against the raw
recording, rejects data loss, foreign threads, overlapping or incorrectly nested intervals,
and attributes phases by method-entry timestamps. JFR delivery batches can lengthen readiness
waits; old X11 callback timings and these native measurements are not interchangeable.

Run the parser contracts with:

```sh
python3 -B -m unittest discover -s benchmarks/native-wayland -v
```

`desktop_runtime.py` accepts the same arguments as the original runtime launcher, with
`--backend native`. Its `--contract-only` mode checks the original text/polling contracts,
real JFR normal/caught/escaping method counts, and synthetic SQLite seed/verification without
opening a display. Supply the app's built classpath and a Kotlin compiler classpath as described
in `benchmarks/runtime/USAGE.md`. The diagnostic classes target JVM 25.

Use the normal application JVM access flags, plus
`--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED`. Full input also requires
`-Dsun.awt.wl.UseRemoteDesktopRobot=true` and
`--add-exports=java.desktop/sun.awt.screencast=ALL-UNNAMED`.
An isolated, functioning Remote Desktop portal is required in addition to these flags.
The preflight rejects unavailable input before seeding or launching the full workload.

`owned_display.py --output NEW_DIRECTORY -- COMMAND ...` owns and cleans up a private
Xvfb plus nested Weston session. It removes inherited display/session-bus variables.
This installed Weston setup has **no Remote Desktop portal**: full-workload attempts fail the
input preflight. Do not bypass it, dispatch synthetic AWT events, or use the user's live session
to produce a purportedly equivalent full-workload capture.

`NativeTimingSmoke` is a separate real-app startup/resize contract, compiled with the adapter.
Launch it only with a **new copy** of a verified, account-free synthetic runtime profile as the
working directory, isolated HOME/XDG paths, the private display, normal native application
flags, and the probe/app classpath. Never run it inside a completed capture: it writes
`rendering.json` and `native-render.jfr`. It awaits the synthetic ink page, observes four natural
window resizes, tests idempotent observer close, and requests normal application close.
The smoke does not inject input and does not establish full-workload coverage.

Decode its recording with the matching JBR:

```sh
jfr print --json --events jdk.MethodTrace,jdk.DataLoss,com.vivenotes.RuntimeProbePhase native-render.jfr > events.json
python3 /absolute/repo/benchmarks/native-wayland/analyze.py --events events.json --ledger rendering.json --output analysis.json --unphased-smoke
```

Full captures run this analysis automatically, with all original phases required. Keep raw
recordings, live ledgers, logs, failed attempts and source/runtime hashes. Comparisons and
observer-overhead measurements remain pending until the full input workload can run on a
properly isolated native session. See `RESULTS.md` for the verified scope.
