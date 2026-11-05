Implemented native Wayland ink replay — 2026-11-04

The existing EGL/OpenGL backend now replays retained ink paths when the viewport changes, avoiding
an intermediate CPU bitmap rebuild. Exact cached views are reused, and stationary views regain a
raster image for pen frames. Content changes, erase/undo, memory bounds and software fallback retain
the existing cache behavior. The moving policy is enabled by default; the diagnostic system
property `vivenotes.ink.viewportRendering=raster` selects the old path.

Compose records drawing commands into canvases with no exposed GPU context. A scoped, per-thread
capability around the EGL render delegate identifies accelerated recording without creating or
retaining GPU-owned ink images. Nested frames and errors restore the previous capability. A
transparent Skia saveLayer preserves compositing across ink segments. Drawing diagnostics count
replayed vectors as well as raster builds.

Five alternating fresh-process pairs per workload, after warmup, on Intel Iris Xe and Hyprland
at output scale 1.5 (JBR buffer scale 2). Numbers are medians of each fork's p95 EDT queue delay:

| Workload | Raster | Moving replay | Reduction |
| --- | ---: | ---: | ---: |
| sparse (42 strokes), development classes | 18.30 ms | 13.18 ms | 28% |
| typical (419 strokes), development classes | 22.90 ms | 13.50 ms | 41% |
| dense (10000 strokes), development classes | 80.43 ms | 40.64 ms | 49% |
| 419 strokes, actual exported Flatpak | 35.98 ms | 20.05 ms | 44% |

Every paired scroll comparison cleared the predeclared 15% queue-tail bar. Packaged active scroll
CPU rose from 6.28 to 7.49 process seconds (19%) while more updates were submitted.
Peak process RSS stayed similar and idle submitted zero frames. No displayed-FPS, physical pen
latency or energy-saving claim is made. Short sparse pen/typical erase samples showed roughly 3 ms
higher p95 queue delay. A separate 300-event/20-stroke stationary drawing check showed pen queue
p95 0.297 versus 0.258 ms, comparable handler/renderer times and successful erase/persistence checks.

Validation passed:

- Shared JVM: 2343 tests, zero failures, 10 optional skips.
- Desktop JVM: 237 tests, zero failures, 12 platform/optional skips; popup suite: 2/0/0.
- Native JBR Wayland: 7 tests, zero failures or skips at each of scales 1 and 2, with DISPLAY unset,
  Intel GL, compositor screenshots, alpha/resize/popup/resource/fallback/input checks and recorded-ink
  GPU image comparisons. Existing exact CPU pixel tests were preserved. GPU uniform interiors use
  one color level of tolerance; edge coverage may vary within one physical pixel, with a per-stroke
  bound for accumulated 8-bit blending error. The image oracle rejects interior color/geometry errors.
- Benchmark contracts: 12 Python cases plus fresh SQLite/native-codec fixture contracts; actual
  interactions acknowledge authored ink and object erasure without changing original fixture bytes.
- Actual bundle imported and checked out into a separate OSTree repository, then compared with
  networking/X11 disabled. All app classes loaded from `/app/lib`; synthetic profiles closed normally
  and EGL resources returned to zero. No installation or publication was performed.

Reflection/restacking cost was small, so it was left alone. Presentation feedback was not collected,
so no native frame scheduler was added. No evidence justified Vulkan/WebGPU or a Skiko fork.

Evidence: [baseline](step-1.json), [host comparisons](candidate-comparison.json),
[exported bundle comparisons](packaged-comparison.json). Raw profiles/JFRs/XMLs/screenshots and
frozen harness sources persist under `/home/mal0/.cache/vive-egl/`. Benchmark usage is in
[USAGE.md](../../wayland-egl/USAGE.md).

Bundle: `desktopApp/build/flatpak/ViveNotes-1.0.2-x86_64.flatpak`.
Bundle SHA-256: `6613a92c95ed1abe2c673d93a15d00b53ed015c850a2d2c46f9aec19340cf20e`.
Exported Flatpak commit: `c34e8f8007034a4ececc799a5555a960a15f09e089c1bed7f2ab66073ba0d3c0` (local artifact, not a Git commit).
