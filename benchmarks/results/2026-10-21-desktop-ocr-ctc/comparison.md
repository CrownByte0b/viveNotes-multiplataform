# Paired OCR capture

Phase: ctc; three serial fresh JVM forks per side and configuration.

| Lanes | Threads | Width | Metric | Before | After | Change |
|---:|---:|---:|---|---:|---:|---:|
| 1 | 1 | 320 | sessionOpenNanos | 93447145.000 | 96738912.000 | +3.5% |
| 1 | 1 | 320 | coldBatchWallNanos | 31220614.000 | 36163839.000 | +15.8% |
| 1 | 1 | 320 | pipeline.medianBatchWallNanos | 18695721.000 | 18116371.000 | -3.1% |
| 1 | 1 | 320 | pipeline.medianLaneWallNanos | 18626970.000 | 18039884.000 | -3.2% |
| 1 | 1 | 320 | pipeline.medianLaneCpuNanos | 18488749.000 | 17982776.000 | -2.7% |
| 1 | 1 | 320 | pipeline.medianLaneAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 1 | 1 | 320 | pipeline.processCpuNanos | 370000000.000 | 370000000.000 | +0.0% |
| 1 | 1 | 320 | pipeline.upperMedianBatchAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 1 | 1 | 320 | kernel.medianBatchWallNanos | 531841.000 | 766757.000 | +44.2% |
| 1 | 1 | 320 | kernel.medianLaneWallNanos | 478319.000 | 739376.000 | +54.6% |
| 1 | 1 | 320 | kernel.medianLaneCpuNanos | 471423.000 | 737680.000 | +56.5% |
| 1 | 1 | 320 | kernel.medianLaneAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 1 | 1 | 320 | kernel.processCpuNanos | 70000000.000 | 70000000.000 | +0.0% |
| 1 | 1 | 320 | kernel.upperMedianBatchAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 1 | 1 | 3200 | sessionOpenNanos | 91630372.000 | 90007157.000 | -1.8% |
| 1 | 1 | 3200 | coldBatchWallNanos | 239362234.000 | 258681692.000 | +8.1% |
| 1 | 1 | 3200 | pipeline.medianBatchWallNanos | 174795728.000 | 182186283.000 | +4.2% |
| 1 | 1 | 3200 | pipeline.medianLaneWallNanos | 174712760.000 | 182110448.000 | +4.2% |
| 1 | 1 | 3200 | pipeline.medianLaneCpuNanos | 173363993.000 | 181341576.000 | +4.6% |
| 1 | 1 | 3200 | pipeline.medianLaneAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 1 | 1 | 3200 | pipeline.processCpuNanos | 3520000000.000 | 3640000000.000 | +3.4% |
| 1 | 1 | 3200 | pipeline.upperMedianBatchAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 1 | 1 | 3200 | kernel.medianBatchWallNanos | 4316133.000 | 12156874.000 | +181.7% |
| 1 | 1 | 3200 | kernel.medianLaneWallNanos | 4265286.000 | 12080699.000 | +183.2% |
| 1 | 1 | 3200 | kernel.medianLaneCpuNanos | 4192124.000 | 12023902.000 | +186.8% |
| 1 | 1 | 3200 | kernel.medianLaneAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 1 | 1 | 3200 | kernel.processCpuNanos | 380000000.000 | 990000000.000 | +160.5% |
| 1 | 1 | 3200 | kernel.upperMedianBatchAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 2 | 4 | 320 | sessionOpenNanos | 96388365.000 | 95280980.000 | -1.1% |
| 2 | 4 | 320 | coldBatchWallNanos | 25318638.000 | 26572310.000 | +5.0% |
| 2 | 4 | 320 | pipeline.medianBatchWallNanos | 9890947.000 | 10856673.000 | +9.8% |
| 2 | 4 | 320 | pipeline.medianLaneWallNanos | 9712388.000 | 10650862.000 | +9.7% |
| 2 | 4 | 320 | pipeline.medianLaneCpuNanos | 9624413.000 | 10574707.000 | +9.9% |
| 2 | 4 | 320 | pipeline.medianLaneAllocatedBytes | 2995336.000 | 3624.000 | -99.9% |
| 2 | 4 | 320 | pipeline.processCpuNanos | 1000000000.000 | 1750000000.000 | +75.0% |
| 2 | 4 | 320 | pipeline.upperMedianBatchAllocatedBytes | 5990672.000 | 7248.000 | -99.9% |
| 2 | 4 | 320 | kernel.medianBatchWallNanos | 612043.000 | 707785.000 | +15.6% |
| 2 | 4 | 320 | kernel.medianLaneWallNanos | 549204.000 | 617223.000 | +12.4% |
| 2 | 4 | 320 | kernel.medianLaneCpuNanos | 539670.000 | 608417.000 | +12.7% |
| 2 | 4 | 320 | kernel.medianLaneAllocatedBytes | 2994136.000 | 2424.000 | -99.9% |
| 2 | 4 | 320 | kernel.processCpuNanos | 110000000.000 | 340000000.000 | +209.1% |
| 2 | 4 | 320 | kernel.upperMedianBatchAllocatedBytes | 5988272.000 | 4848.000 | -99.9% |
| 2 | 4 | 3200 | sessionOpenNanos | 90855510.000 | 92050831.000 | +1.3% |
| 2 | 4 | 3200 | coldBatchWallNanos | 137062997.000 | 154755751.000 | +12.9% |
| 2 | 4 | 3200 | pipeline.medianBatchWallNanos | 96708684.000 | 104587187.000 | +8.1% |
| 2 | 4 | 3200 | pipeline.medianLaneWallNanos | 93683395.000 | 102408867.000 | +9.3% |
| 2 | 4 | 3200 | pipeline.medianLaneCpuNanos | 92601207.000 | 101649855.000 | +9.8% |
| 2 | 4 | 3200 | pipeline.medianLaneAllocatedBytes | 29938464.000 | 8672.000 | -100.0% |
| 2 | 4 | 3200 | pipeline.processCpuNanos | 9480000000.000 | 11900000000.000 | +25.5% |
| 2 | 4 | 3200 | pipeline.upperMedianBatchAllocatedBytes | 59876928.000 | 17344.000 | -100.0% |
| 2 | 4 | 3200 | kernel.medianBatchWallNanos | 6275507.000 | 13254289.000 | +111.2% |
| 2 | 4 | 3200 | kernel.medianLaneWallNanos | 6125512.000 | 12484557.000 | +103.8% |
| 2 | 4 | 3200 | kernel.medianLaneCpuNanos | 5948563.000 | 12404673.000 | +108.5% |
| 2 | 4 | 3200 | kernel.medianLaneAllocatedBytes | 29937264.000 | 7472.000 | -100.0% |
| 2 | 4 | 3200 | kernel.processCpuNanos | 1070000000.000 | 5230000000.000 | +388.8% |
| 2 | 4 | 3200 | kernel.upperMedianBatchAllocatedBytes | 59874528.000 | 14944.000 | -100.0% |

| Lanes | Threads | Width | Lifecycle stage | Before RSS MiB | After RSS MiB | Change |
|---:|---:|---:|---|---:|---:|---:|
| 1 | 1 | 320 | pipeline-idle-before-close | 340.031 | 270.676 | -20.4% |
| 1 | 1 | 320 | kernel-idle-output-still-open | 448.992 | 282.984 | -37.0% |
| 1 | 1 | 320 | closed-after-explicit-gc | 449.641 | 283.438 | -37.0% |
| 1 | 1 | 3200 | pipeline-idle-before-close | 561.352 | 519.078 | -7.5% |
| 1 | 1 | 3200 | kernel-idle-output-still-open | 570.730 | 520.121 | -8.9% |
| 1 | 1 | 3200 | closed-after-explicit-gc | 485.035 | 343.250 | -29.2% |
| 2 | 4 | 320 | pipeline-idle-before-close | 439.117 | 296.953 | -32.4% |
| 2 | 4 | 320 | kernel-idle-output-still-open | 469.543 | 309.527 | -34.1% |
| 2 | 4 | 320 | closed-after-explicit-gc | 469.910 | 309.641 | -34.1% |
| 2 | 4 | 3200 | pipeline-idle-before-close | 658.586 | 790.008 | +20.0% |
| 2 | 4 | 3200 | kernel-idle-output-still-open | 751.480 | 789.227 | +5.0% |
| 2 | 4 | 3200 | closed-after-explicit-gc | 578.742 | 407.184 | -29.6% |

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
