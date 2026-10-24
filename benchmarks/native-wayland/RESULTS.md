# Native Wayland timing — 2026-10-24

The JFR observer works on the unchanged native SwingGraphics host. Full input-workload timing
is still blocked by the isolated session's missing Remote Desktop portal.

The actual application opened its synthetic ink page under private Xvfb/nested Weston, resized
four times, drained/closed the observer twice safely, and closed normally. JBR was
`25.0.4.1+1-b610.67`, with Compose UI `1.13.0-alpha02` and Skiko `0.153.0`.

| Check | Result |
| --- | --- |
| Compose `onRender` invocations | 310 |
| Swing `redraw` invocations | 310 |
| Swing layer `paint` invocations | 310 |
| Correctly nested triples, on the owned EDT | 310 |
| Drained live counts versus raw JFR | Exact match |
| Reported data-loss events / observer errors | 0 / 0 |
| Normal/caught/escaping exception controls | 20 invocations each, exact counts |

The tested component is a Compose subclass of `SkiaSwingLayer`. No renderer delegate was
replaced and the probe never requested a frame. The existing runtime workload remains byte
identical; its native adapter uses a separate observer/schema and validates raw phase times.

The installed JBR's `WLRobotPeer` gates all input on `sun.awt.wl.UseRemoteDesktopRobot`, whose
default is false. With that property enabled, the separate Remote Desktop capability check
still fails on this private Weston session. The full benchmark therefore stops explicitly at
preflight. There are no native ink/text/viewport workload results, before/after comparisons or
observer-overhead claims here. Neither setting the property alone nor the successful rendering
smoke resolves input delivery.

The ten native Python analysis tests cover exact timestamps, scope nesting, stream/raw count
parity, failed ownership/lifecycle, event loss, foreign threads and phase boundaries. A delayed
live count cannot substitute for a method invocation inside a robot phase. The original 41
Python runtime tests pass. Full shared/desktop JVM tests, lint and assembly pass: 2,241 tests
passed, 14 skipped, no failures/errors. No production or dependency change was made.

Retain the initial validation attempt as well: its observer recorded 328 matching calls, but
the outer script incorrectly expected debug close output without launching with `--debug`.
The corrected driver awaits the synthetic page and enables that output; the 310-call run above
is the successful validation. Earlier 320-call feasibility research remains separate.

Raw recordings, profiles, failed attempts and exact hashes are retained privately; see
`verification.json` for the evidence index. The current production sources match all 333
production Kotlin files in the retained compiled startup checkout. These diagnostics measure
elapsed method invocations in a nested display, not displayed frames, physical input latency,
GPU time or the user's physical Wayland session.

References: [JDK 25 method-filter settings](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/package-summary.html),
[RecordingStream drain/stop API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/consumer/RecordingStream.html),
and the pinned [Skiko Swing renderer](https://github.com/JetBrains/skiko/blob/219af4abf792da15dca1fc859af7b8a3a3ce0755/skiko/src/awtMain/kotlin/org/jetbrains/skiko/swing/SwingRendererBase.kt).
The input gates were verified against the installed JBR's `lib/src.zip` and bytecode.
