# Ink renderer comparison

Three fresh JVMs; eight matched40k-stroke cases. No timing assertions.

| View | Density | Additions | Before pooled handler median ms | After ms | Change | Before fork medians ms | After fork medians ms |
|---|---:|---:|---:|---:|---:|---|---|
| fit | 1.0 | 1 | 1.442 | 1.408 | -2.4% | 1.430, 1.392, 1.550 | 1.385, 1.387, 1.537 |
| fit | 1.0 | 32 | 0.614 | 0.621 | +1.1% | 0.601, 0.623, 0.625 | 0.635, 0.611, 0.619 |
| fit | 2.0 | 1 | 0.452 | 0.355 | -21.4% | 0.505, 0.440, 0.443 | 0.359, 0.360, 0.347 |
| fit | 2.0 | 32 | 0.354 | 0.300 | -15.5% | 0.368, 0.330, 0.377 | 0.302, 0.302, 0.291 |
| sparse | 1.0 | 1 | 0.480 | 0.485 | +1.0% | 0.502, 0.463, 0.472 | 0.489, 0.467, 0.495 |
| sparse | 1.0 | 32 | 0.516 | 0.419 | -18.9% | 0.520, 0.506, 0.524 | 0.410, 0.422, 0.421 |
| sparse | 2.0 | 1 | 0.303 | 0.269 | -11.0% | 0.300, 0.287, 0.334 | 0.271, 0.268, 0.265 |
| sparse | 2.0 | 32 | 0.292 | 0.251 | -14.2% | 0.304, 0.278, 0.291 | 0.248, 0.247, 0.261 |

| View | Density | Additions | Before release→committed draw median ms | After ms | Change | Before raw fork ms | After raw fork ms |
|---|---:|---:|---:|---:|---:|---|---|
| fit | 1.0 | 1 | 158.599 | 153.348 | -3.3% | 149.878, 163.392, 158.599 | 155.354, 153.348, 152.765 |
| fit | 1.0 | 32 | 147.279 | 133.006 | -9.7% | 147.279, 151.041, 146.134 | 132.633, 133.006, 133.140 |
| fit | 2.0 | 1 | 163.531 | 117.870 | -27.9% | 163.531, 143.086, 178.715 | 117.870, 119.603, 115.014 |
| fit | 2.0 | 32 | 124.915 | 113.635 | -9.0% | 127.335, 124.915, 124.754 | 113.635, 112.725, 117.693 |
| sparse | 1.0 | 1 | 10.456 | 7.168 | -31.4% | 10.943, 9.213, 10.456 | 6.982, 7.168, 7.190 |
| sparse | 1.0 | 32 | 10.630 | 6.562 | -38.3% | 10.630, 10.427, 11.650 | 6.005, 7.150, 6.562 |
| sparse | 2.0 | 1 | 6.988 | 4.140 | -40.7% | 6.988, 5.846, 7.124 | 3.809, 4.140, 4.296 |
| sparse | 2.0 | 32 | 6.931 | 4.006 | -42.2% | 6.931, 5.827, 7.857 | 4.006, 3.913, 4.764 |

Structural deviations: 197. Failed preserved-work checks: 0.

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
| fit | 1.0 | 32 | 3 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 3 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 1.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 1.0 | 32 | 3 | total.draws | 144 | 145 | +1 |
| fit | 1.0 | 32 | 3 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 1.0 | 32 | 3 | total.pathBuilds | 73157 | 146409 | +73252 |
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
| fit | 2.0 | 32 | 1 | erasePreview.draws | 5 | 7 | +2 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 2.0 | 32 | 1 | total.draws | 144 | 146 | +2 |
| fit | 2.0 | 32 | 1 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 2.0 | 32 | 1 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 2.0 | 32 | 1 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 2.0 | 32 | 2 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 2 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 2.0 | 32 | 2 | total.draws | 144 | 145 | +1 |
| fit | 2.0 | 32 | 2 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 2.0 | 32 | 2 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 2.0 | 32 | 2 | total.rasterBuilds | 34 | 36 | +2 |
| fit | 2.0 | 32 | 3 | erasePreview.draws | 5 | 7 | +2 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedPathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 3 | erasePreview.pathBuilds | 0 | 73252 | +73252 |
| fit | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 2 | +2 |
| fit | 2.0 | 32 | 3 | total.draws | 144 | 146 | +2 |
| fit | 2.0 | 32 | 3 | total.finishedPathBuilds | 73054 | 146306 | +73252 |
| fit | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 73550 | 146802 | +73252 |
| fit | 2.0 | 32 | 3 | total.pathBuilds | 73157 | 146409 | +73252 |
| fit | 2.0 | 32 | 3 | total.rasterBuilds | 34 | 36 | +2 |
| sparse | 1.0 | 1 | 1 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 1 | erasePreview.draws | 5 | 8 | +3 |
| sparse | 1.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 1 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 1 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 1 | total.draws | 20 | 24 | +4 |
| sparse | 1.0 | 1 | 1 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 1 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 1 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 2 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 2 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 2 | total.draws | 20 | 25 | +5 |
| sparse | 1.0 | 1 | 2 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 2 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 1 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 1 | 3 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 3 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 1 | 3 | total.draws | 20 | 25 | +5 |
| sparse | 1.0 | 1 | 3 | total.finishedStrokeVectorDraws | 2395 | 7206 | +4811 |
| sparse | 1.0 | 1 | 3 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 1.0 | 32 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 1 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 1 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 1 | total.draws | 144 | 148 | +4 |
| sparse | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 1 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 1.0 | 32 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 32 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 2 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 2 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 2 | total.draws | 144 | 149 | +5 |
| sparse | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 2 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 1.0 | 32 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 32 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 4811 | +4811 |
| sparse | 1.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 1.0 | 32 | 3 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 3 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 1.0 | 32 | 3 | total.draws | 144 | 149 | +5 |
| sparse | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 2922 | 7733 | +4811 |
| sparse | 1.0 | 32 | 3 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 1 | 1 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 1 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 1 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 1 | total.draws | 20 | 25 | +5 |
| sparse | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 1 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 1 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 2 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 2 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 2 | total.draws | 20 | 25 | +5 |
| sparse | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 2 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 1 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 1 | 3 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 3 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 1 | 3 | total.draws | 20 | 25 | +5 |
| sparse | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 583 | 1751 | +1168 |
| sparse | 2.0 | 1 | 3 | total.rasterBuilds | 3 | 7 | +4 |
| sparse | 2.0 | 32 | 1 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 1 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 1 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 1 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 32 | 1 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 32 | 1 | total.draws | 144 | 149 | +5 |
| sparse | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 1110 | 2278 | +1168 |
| sparse | 2.0 | 32 | 1 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 32 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 2 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 2 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 2 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 32 | 2 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 32 | 2 | total.draws | 144 | 149 | +5 |
| sparse | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 1110 | 2278 | +1168 |
| sparse | 2.0 | 32 | 2 | total.rasterBuilds | 34 | 38 | +4 |
| sparse | 2.0 | 32 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 3 | erasePreview.draws | 5 | 9 | +4 |
| sparse | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 0 | 1168 | +1168 |
| sparse | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 0 | 4 | +4 |
| sparse | 2.0 | 32 | 3 | peakRetainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
| sparse | 2.0 | 32 | 3 | retainedRasterPixelBytes | 2097152 | 2101252 | +4100 |
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
