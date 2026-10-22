# Paired OCR capture

Phase: ctc; three serial fresh JVM forks per side and configuration.

| Lanes | Threads | Width | Metric | Before | After | Change |
|---:|---:|---:|---|---:|---:|---:|
| 1 | 1 | 320 | sessionOpenNanos | 84195023.000 | 80958037.000 | -3.8% |
| 1 | 1 | 320 | coldBatchWallNanos | 29518842.000 | 29396560.000 | -0.4% |
| 1 | 1 | 320 | pipeline.medianBatchWallNanos | 17155639.000 | 16678751.000 | -2.8% |
| 1 | 1 | 320 | pipeline.medianLaneWallNanos | 17089874.000 | 16618117.000 | -2.8% |
| 1 | 1 | 320 | pipeline.medianLaneCpuNanos | 17037728.000 | 16537428.000 | -2.9% |
| 1 | 1 | 320 | pipeline.medianLaneAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 1 | 1 | 320 | pipeline.processCpuNanos | 350000000.000 | 340000000.000 | -2.9% |
| 1 | 1 | 320 | pipeline.upperMedianBatchAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 1 | 1 | 320 | kernel.medianBatchWallNanos | 398358.000 | 355076.000 | -10.9% |
| 1 | 1 | 320 | kernel.medianLaneWallNanos | 367910.000 | 330789.000 | -10.1% |
| 1 | 1 | 320 | kernel.medianLaneCpuNanos | 366545.000 | 331030.000 | -9.7% |
| 1 | 1 | 320 | kernel.medianLaneAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 1 | 1 | 320 | kernel.processCpuNanos | 60000000.000 | 40000000.000 | -33.3% |
| 1 | 1 | 320 | kernel.upperMedianBatchAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 1 | 1 | 3200 | sessionOpenNanos | 81092271.000 | 80641404.000 | -0.6% |
| 1 | 1 | 3200 | coldBatchWallNanos | 225131794.000 | 200619915.000 | -10.9% |
| 1 | 1 | 3200 | pipeline.medianBatchWallNanos | 162980728.000 | 161867390.000 | -0.7% |
| 1 | 1 | 3200 | pipeline.medianLaneWallNanos | 162901437.000 | 161792919.000 | -0.7% |
| 1 | 1 | 3200 | pipeline.medianLaneCpuNanos | 161691573.000 | 161177001.000 | -0.3% |
| 1 | 1 | 3200 | pipeline.medianLaneAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 1 | 1 | 3200 | pipeline.processCpuNanos | 3290000000.000 | 3230000000.000 | -1.8% |
| 1 | 1 | 3200 | pipeline.upperMedianBatchAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 1 | 1 | 3200 | kernel.medianBatchWallNanos | 3849292.000 | 3007931.000 | -21.9% |
| 1 | 1 | 3200 | kernel.medianLaneWallNanos | 3809766.000 | 2975208.000 | -21.9% |
| 1 | 1 | 3200 | kernel.medianLaneCpuNanos | 3783901.000 | 2964701.000 | -21.6% |
| 1 | 1 | 3200 | kernel.medianLaneAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 1 | 1 | 3200 | kernel.processCpuNanos | 340000000.000 | 250000000.000 | -26.5% |
| 1 | 1 | 3200 | kernel.upperMedianBatchAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 2 | 4 | 320 | sessionOpenNanos | 84297588.000 | 83573661.000 | -0.9% |
| 2 | 4 | 320 | coldBatchWallNanos | 26022333.000 | 20784010.000 | -20.1% |
| 2 | 4 | 320 | pipeline.medianBatchWallNanos | 9705309.000 | 8196810.000 | -15.5% |
| 2 | 4 | 320 | pipeline.medianLaneWallNanos | 9584168.000 | 8105886.000 | -15.4% |
| 2 | 4 | 320 | pipeline.medianLaneCpuNanos | 9545312.000 | 8064424.000 | -15.5% |
| 2 | 4 | 320 | pipeline.medianLaneAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 2 | 4 | 320 | pipeline.processCpuNanos | 980000000.000 | 830000000.000 | -15.3% |
| 2 | 4 | 320 | pipeline.upperMedianBatchAllocatedBytes | 5990672.000 | 7248.000 | -99.9% |
| 2 | 4 | 320 | kernel.medianBatchWallNanos | 514589.000 | 346208.000 | -32.7% |
| 2 | 4 | 320 | kernel.medianLaneWallNanos | 485494.000 | 313636.000 | -35.4% |
| 2 | 4 | 320 | kernel.medianLaneCpuNanos | 484086.000 | 313977.000 | -35.1% |
| 2 | 4 | 320 | kernel.medianLaneAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 2 | 4 | 320 | kernel.processCpuNanos | 100000000.000 | 70000000.000 | -30.0% |
| 2 | 4 | 320 | kernel.upperMedianBatchAllocatedBytes | 5988272.000 | 4848.000 | -99.9% |
| 2 | 4 | 3200 | sessionOpenNanos | 83555116.000 | 80168495.000 | -4.1% |
| 2 | 4 | 3200 | coldBatchWallNanos | 126909362.000 | 102270100.000 | -19.4% |
| 2 | 4 | 3200 | pipeline.medianBatchWallNanos | 87859001.000 | 84811586.000 | -3.5% |
| 2 | 4 | 3200 | pipeline.medianLaneWallNanos | 85938469.000 | 83377759.000 | -3.0% |
| 2 | 4 | 3200 | pipeline.medianLaneCpuNanos | 85136118.000 | 82984675.000 | -2.5% |
| 2 | 4 | 3200 | pipeline.medianLaneAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 2 | 4 | 3200 | pipeline.processCpuNanos | 8720000000.000 | 8360000000.000 | -4.1% |
| 2 | 4 | 3200 | pipeline.upperMedianBatchAllocatedBytes | 59876928.000 | 17344.000 | -100.0% |
| 2 | 4 | 3200 | kernel.medianBatchWallNanos | 5553262.000 | 3091650.000 | -44.3% |
| 2 | 4 | 3200 | kernel.medianLaneWallNanos | 5448162.000 | 3030063.000 | -44.4% |
| 2 | 4 | 3200 | kernel.medianLaneCpuNanos | 5370371.000 | 2998551.000 | -44.2% |
| 2 | 4 | 3200 | kernel.medianLaneAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 2 | 4 | 3200 | kernel.processCpuNanos | 950000000.000 | 550000000.000 | -42.1% |
| 2 | 4 | 3200 | kernel.upperMedianBatchAllocatedBytes | 59874528.000 | 14944.000 | -100.0% |

| Lanes | Threads | Width | Lifecycle stage | Before RSS MiB | After RSS MiB | Change |
|---:|---:|---:|---|---:|---:|---:|
| 1 | 1 | 320 | pipeline-idle-before-close | 340.734 | 259.090 | -24.0% |
| 1 | 1 | 320 | kernel-idle-output-still-open | 449.676 | 271.441 | -39.6% |
| 1 | 1 | 320 | closed-after-explicit-gc | 450.305 | 271.758 | -39.7% |
| 1 | 1 | 3200 | pipeline-idle-before-close | 559.750 | 414.531 | -25.9% |
| 1 | 1 | 3200 | kernel-idle-output-still-open | 569.102 | 428.031 | -24.8% |
| 1 | 1 | 3200 | closed-after-explicit-gc | 483.387 | 342.234 | -29.2% |
| 2 | 4 | 320 | pipeline-idle-before-close | 439.609 | 274.996 | -37.4% |
| 2 | 4 | 320 | kernel-idle-output-still-open | 470.016 | 287.477 | -38.8% |
| 2 | 4 | 320 | closed-after-explicit-gc | 470.379 | 287.766 | -38.8% |
| 2 | 4 | 3200 | pipeline-idle-before-close | 627.566 | 559.262 | -10.9% |
| 2 | 4 | 3200 | kernel-idle-output-still-open | 720.219 | 580.281 | -19.4% |
| 2 | 4 | 3200 | closed-after-explicit-gc | 577.320 | 405.473 | -29.8% |

All lifecycle RSS/HWM/heap/GC observations and raw fork values are retained in comparison.json and the raw reports.

- CTC compares isolated pinned v6 baseline flat versus current native decoding; engine lifecycle/whole-app latency is not measured.
- Model/dictionary/input/output/reading hashes, confidence bits and configured lane/thread/width identity must match exactly.
- CTC summaries retain the diagnostic's upper medians; between forks, the ordinary median of three summaries is used.
- Lane allocations exclude native arenas and main/ORT threads; summed batch allocations are derived separately from raw lane observations.
- RSS/HWM are whole-process observations including JIT, heap, libraries and ORT arenas; Java allocation reductions do not establish native RSS savings.
- Explicit GC is requested only at the recorded common-output validation and final closed phases; collection is not guaranteed.
- Cold inference includes executor startup and lazy native reducer opening; image/preprocessing setup is separate, session open is reported separately.
- Each task is a fresh JVM with warm OS caches; samples include scheduling/GC. No latency-tail, throughput, confidence-interval or FPS claim.
- All raw reports remain intact; positive change percentages are regressions and are never suppressed.
