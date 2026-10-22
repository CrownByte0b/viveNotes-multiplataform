# Paired OCR capture

Phase: geometry; three serial fresh JVM forks per side and configuration.

| Geometry case | Units | Before ns | After ns | Change |
|---|---:|---:|---:|---:|
| seven-lines-rectangles | 7 | 4264.000 | 4262.000 | -0.0% |
| seven-lines-postprocess | 486400 | 1829243.000 | 1867623.000 | +2.1% |
| dense-page-rectangles | 38 | 12988.000 | 12603.000 | -3.0% |
| dense-page-postprocess | 921600 | 4675797.000 | 4645168.000 | -0.7% |
| slanted-page-rectangles | 7 | 7230.000 | 6967.000 | -3.6% |
| slanted-page-postprocess | 486400 | 1852860.000 | 1825626.000 | -1.5% |
| hull-16 | 16 | 651.000 | 637.000 | -2.2% |
| hull-64 | 64 | 7182.000 | 6991.000 | -2.7% |
| hull-256 | 256 | 116812.000 | 93646.000 | -19.8% |
| hull-1024 | 1024 | 1747184.000 | 944060.000 | -46.0% |
| rows-40 | 40 | 2467.000 | 2157.000 | -12.6% |
| rows-400 | 400 | 113600.000 | 23552.000 | -79.3% |

- CTC compares isolated pinned v6 baseline flat versus current native decoding; engine lifecycle/whole-app latency is not measured.
- Model/dictionary/input/output/reading hashes, confidence bits and configured lane/thread/width identity must match exactly.
- CTC summaries retain the diagnostic's upper medians; between forks, the ordinary median of three summaries is used.
- Lane allocations exclude native arenas and main/ORT threads; summed batch allocations are derived separately from raw lane observations.
- RSS/HWM are whole-process observations including JIT, heap, libraries and ORT arenas; Java allocation reductions do not establish native RSS savings.
- Explicit GC is requested only at the recorded common-output validation and final closed phases; collection is not guaranteed.
- Cold inference includes executor startup and lazy native reducer opening; image/preprocessing setup is separate, session open is reported separately.
- Each task is a fresh JVM with warm OS caches; samples include scheduling/GC. No latency-tail, throughput, confidence-interval or FPS claim.
- All raw reports remain intact; positive change percentages are regressions and are never suppressed.
