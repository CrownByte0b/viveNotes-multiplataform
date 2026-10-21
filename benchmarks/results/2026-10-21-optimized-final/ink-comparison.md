# Ink renderer comparison

Three fresh JVMs; eight matched 40k-stroke cases. No timing assertions.

| View | Density | Additions | Before pooled handler median ms | After ms | Change | Before fork medians ms | After fork medians ms |
|---|---:|---:|---:|---:|---:|---|---|
| fit | 1.0 | 1 | 1.442 | 1.343 | -6.9% | 1.430, 1.392, 1.550 | 1.292, 1.349, 1.373 |
| fit | 1.0 | 32 | 0.614 | 0.634 | +3.2% | 0.601, 0.623, 0.625 | 0.624, 0.640, 0.639 |
| fit | 2.0 | 1 | 0.452 | 0.358 | -20.7% | 0.505, 0.440, 0.443 | 0.357, 0.363, 0.359 |
| fit | 2.0 | 32 | 0.354 | 0.298 | -15.8% | 0.368, 0.330, 0.377 | 0.296, 0.297, 0.304 |
| sparse | 1.0 | 1 | 0.480 | 0.476 | -0.9% | 0.502, 0.463, 0.472 | 0.465, 0.469, 0.492 |
| sparse | 1.0 | 32 | 0.516 | 0.414 | -19.7% | 0.520, 0.506, 0.524 | 0.410, 0.416, 0.419 |
| sparse | 2.0 | 1 | 0.303 | 0.259 | -14.3% | 0.300, 0.287, 0.334 | 0.280, 0.251, 0.257 |
| sparse | 2.0 | 32 | 0.292 | 0.249 | -14.7% | 0.304, 0.278, 0.291 | 0.249, 0.253, 0.248 |

| View | Density | Additions | Before release→committed draw median ms | After ms | Change | Before raw fork ms | After raw fork ms |
|---|---:|---:|---:|---:|---:|---|---|
| fit | 1.0 | 1 | 158.599 | 153.443 | -3.3% | 149.878, 163.392, 158.599 | 152.819, 158.457, 153.443 |
| fit | 1.0 | 32 | 147.279 | 131.172 | -10.9% | 147.279, 151.041, 146.134 | 129.863, 131.172, 131.677 |
| fit | 2.0 | 1 | 163.531 | 116.317 | -28.9% | 163.531, 143.086, 178.715 | 116.317, 118.528, 115.891 |
| fit | 2.0 | 32 | 124.915 | 113.792 | -8.9% | 127.335, 124.915, 124.754 | 110.112, 114.538, 113.792 |
| sparse | 1.0 | 1 | 10.456 | 7.080 | -32.3% | 10.943, 9.213, 10.456 | 6.610, 7.080, 7.733 |
| sparse | 1.0 | 32 | 10.630 | 5.675 | -46.6% | 10.630, 10.427, 11.650 | 5.642, 6.489, 5.675 |
| sparse | 2.0 | 1 | 6.988 | 4.023 | -42.4% | 6.988, 5.846, 7.124 | 4.030, 3.072, 4.023 |
| sparse | 2.0 | 32 | 6.931 | 3.669 | -47.1% | 6.931, 5.827, 7.857 | 3.669, 4.240, 3.492 |

Structural deviations: 166. Failed preserved-work checks: 0.

| View | Density | Additions | Fork | Counter | Before | After | Delta |
|---|---:|---:|---:|---|---:|---:|---:|
| fit | 1.0 | 32 | 1 | erasePreview.draws | 5 | 6 | +1 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 1 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 1.0 | 32 | 1 | total.draws | 144 | 145 | +1 |
| fit | 1.0 | 32 | 1 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 1.0 | 32 | 1 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 1.0 | 32 | 1 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 1.0 | 32 | 2 | erasePreview.draws | 5 | 6 | +1 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 2 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 1.0 | 32 | 2 | total.draws | 144 | 145 | +1 |
| fit | 1.0 | 32 | 2 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 1.0 | 32 | 2 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 1.0 | 32 | 2 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 1.0 | 32 | 3 | erasePreview.draws | 5 | 6 | +1 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedPathBuilds | 0 | 73174 | +73174 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 73174 | +73174 |
| fit | 1.0 | 32 | 3 | erasePreview.pathBuilds | 0 | 73174 | +73174 |
| fit | 1.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 1.0 | 32 | 3 | total.draws | 144 | 145 | +1 |
| fit | 1.0 | 32 | 3 | total.finishedPathBuilds | 73054 | 146228 | +73174 |
| fit | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 73550 | 146724 | +73174 |
| fit | 1.0 | 32 | 3 | total.pathBuilds | 73157 | 146331 | +73174 |
| fit | 1.0 | 32 | 3 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 2.0 | 1 | 1 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedPathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 1 | erasePreview.pathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 1 | erasePreview.rasterBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 1 | total.draws | 20 | 21 | +1 |
| fit | 2.0 | 1 | 1 | total.finishedPathBuilds | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 1 | total.pathBuilds | 73033 | 109695 | +36662 |
| fit | 2.0 | 1 | 1 | total.rasterBuilds | 3 | 4 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedPathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 2 | erasePreview.pathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 2 | erasePreview.rasterBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 2 | total.draws | 20 | 21 | +1 |
| fit | 2.0 | 1 | 2 | total.finishedPathBuilds | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 2 | total.pathBuilds | 73033 | 109695 | +36662 |
| fit | 2.0 | 1 | 2 | total.rasterBuilds | 3 | 4 | +1 |
| fit | 2.0 | 1 | 3 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedPathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 3 | erasePreview.pathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 1 | 3 | erasePreview.rasterBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 3 | total.draws | 20 | 21 | +1 |
| fit | 2.0 | 1 | 3 | total.finishedPathBuilds | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 73023 | 109685 | +36662 |
| fit | 2.0 | 1 | 3 | total.pathBuilds | 73033 | 109695 | +36662 |
| fit | 2.0 | 1 | 3 | total.rasterBuilds | 3 | 4 | +1 |
| fit | 2.0 | 32 | 1 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 2.0 | 32 | 1 | total.draws | 144 | 145 | +1 |
| fit | 2.0 | 32 | 1 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 2.0 | 32 | 1 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 2.0 | 32 | 1 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedPathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 2 | erasePreview.pathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 1 | +1 |
| fit | 2.0 | 32 | 2 | total.finishedPathBuilds | 73054 | 109716 | +36662 |
| fit | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 73550 | 110212 | +36662 |
| fit | 2.0 | 32 | 2 | total.pathBuilds | 73157 | 109819 | +36662 |
| fit | 2.0 | 32 | 2 | total.rasterBuilds | 34 | 35 | +1 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedPathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 3 | erasePreview.pathBuilds | 0 | 36662 | +36662 |
| fit | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 1 | +1 |
| fit | 2.0 | 32 | 3 | total.finishedPathBuilds | 73054 | 109716 | +36662 |
| fit | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 73550 | 110212 | +36662 |
| fit | 2.0 | 32 | 3 | total.pathBuilds | 73157 | 109819 | +36662 |
| fit | 2.0 | 32 | 3 | total.rasterBuilds | 34 | 35 | +1 |
| sparse | 1.0 | 1 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 1 | total.draws | 20 | 24 | +4 |
| sparse | 1.0 | 1 | 1 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 1 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 1 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 2 | total.draws | 20 | 25 | +5 |
| sparse | 1.0 | 1 | 2 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 2 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 3 | total.draws | 20 | 25 | +5 |
| sparse | 1.0 | 1 | 3 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 3 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 32 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 1 | total.draws | 144 | 148 | +4 |
| sparse | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 1 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 1.0 | 32 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 32 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 2 | total.draws | 144 | 149 | +5 |
| sparse | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 2 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 1.0 | 32 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 3 | total.draws | 144 | 148 | +4 |
| sparse | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 3 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 1 | 1 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 1 | total.draws | 20 | 25 | +5 |
| sparse | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 1 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 1 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 2 | total.draws | 20 | 24 | +4 |
| sparse | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 2 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 3 | total.draws | 20 | 25 | +5 |
| sparse | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 3 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 32 | 1 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 1 | total.draws | 144 | 149 | +5 |
| sparse | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 1110 | 2278 | +1168 |
| sparse | 2.0 | 32 | 1 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 32 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 2 | total.draws | 144 | 149 | +5 |
| sparse | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 1110 | 2278 | +1168 |
| sparse | 2.0 | 32 | 2 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 32 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 3 | total.draws | 144 | 149 | +5 |
| sparse | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 1110 | 2278 | +1168 |
| sparse | 2.0 | 32 | 3 | total.rasterBuilds | 34 | 38 | +4 |

Notes:

- Handler median pools raw observations across three fresh JVMs, matching the frozen ink-summary.md; fork medians and sample counts are retained separately.
- Erase commit has one observation per fork: median of three, with all three raw ns/ms values and unchanged timing scope retained; no p95/confidence claim.
- Timings include test scheduling/GC; handler completion may precede processing queued samples. These are not physical input-to-photon, FPS or whole-app frame results.
- Structural deviations pair fork numbers for auditing; inspect every case/fork. Different draw/worker-publication attribution is not automatically a correctness failure.
- Raster counters measure retained physical N32 viewports, excluding transient old/new copy surfaces, native RSS and other heap/native objects.
- This harness uses Object erasing and at most32 appended strokes; it does not benchmark Normal erasing or the128th append boundary.
- Schema has no independent renderer fixture SHA: identical frozen harness SHA plus exact case/fixture/native/runtime fields establish workload compatibility.
- No timing thresholds are asserted. Structural preservation failures are reported for review, not hidden by averaging or treated as timing failures.
