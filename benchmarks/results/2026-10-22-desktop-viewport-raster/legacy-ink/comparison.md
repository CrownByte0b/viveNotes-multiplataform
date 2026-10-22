# Paired desktop ink capture

Three serial fresh app and renderer JVMs per variant; variant order reverses on fork2.

| Ink app workload | Units | Before ms | After ms | Change | Before fork ms | After fork ms |
|---|---:|---:|---:|---:|---|---|
| ink.apply_127th_append | 1000 | 0.072 | 0.070 | -2.8% | 0.073, 0.072, 0.071 | 0.070, 0.072, 0.069 |
| ink.apply_128th_append | 1000 | 0.106 | 0.114 | +7.5% | 0.106, 0.123, 0.106 | 0.110, 0.117, 0.114 |
| ink.apply_127th_append | 10000 | 0.039 | 0.041 | +3.0% | 0.039, 0.041, 0.039 | 0.041, 0.041, 0.040 |
| ink.apply_128th_append | 10000 | 0.065 | 0.074 | +13.4% | 0.065, 0.067, 0.064 | 0.066, 0.074, 0.074 |
| ink.apply_127th_append | 40000 | 0.013 | 0.013 | +3.5% | 0.012, 0.013, 0.013 | 0.013, 0.013, 0.013 |
| ink.apply_128th_append | 40000 | 0.023 | 0.024 | +4.2% | 0.023, 0.025, 0.023 | 0.023, 0.024, 0.025 |
| storage.append_ink_coalesced | 1000 | 0.201 | 0.195 | -3.1% | 0.210, 0.174, 0.201 | 0.194, 0.195, 0.195 |
| storage.append_ink_checkpoint | 1000 | 1.705 | 1.832 | +7.4% | 1.705, 1.692, 1.839 | 1.755, 2.002, 1.832 |
| storage.append_ink_coalesced | 10000 | 0.131 | 0.141 | +7.5% | 0.129, 0.131, 0.159 | 0.142, 0.141, 0.129 |
| storage.append_ink_checkpoint | 10000 | 11.717 | 11.892 | +1.5% | 11.468, 11.717, 12.114 | 11.892, 11.802, 12.177 |
| storage.append_ink_coalesced | 40000 | 0.148 | 0.159 | +7.4% | 0.148, 0.185, 0.135 | 0.159, 0.183, 0.118 |
| storage.append_ink_checkpoint | 40000 | 46.032 | 46.804 | +1.7% | 45.993, 46.032, 47.190 | 46.804, 46.330, 47.044 |

| View | Density | Additions | Before handler ms | After ms | Change | Before commit ms | After ms | Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fit | 1.0 | 1 | 1.692 | 1.507 | -10.9% | 256.571 | 263.607 | +2.7% |
| fit | 1.0 | 32 | 0.675 | 0.615 | -8.9% | 190.838 | 187.180 | -1.9% |
| sparse | 1.0 | 1 | 0.529 | 0.495 | -6.6% | 4.350 | 4.256 | -2.2% |
| sparse | 1.0 | 32 | 0.433 | 0.426 | -1.6% | 3.734 | 3.253 | -12.9% |
| fit | 2.0 | 1 | 0.375 | 0.387 | +3.1% | 188.436 | 192.055 | +1.9% |
| fit | 2.0 | 32 | 0.306 | 0.316 | +3.1% | 148.741 | 158.283 | +6.4% |
| sparse | 2.0 | 1 | 0.289 | 0.302 | +4.5% | 3.155 | 3.116 | -1.2% |
| sparse | 2.0 | 32 | 0.264 | 0.257 | -2.7% | 2.862 | 2.782 | -2.8% |

Structural deviations: 43. Failed preservation checks: 15.

| View | Density | Additions | Fork | Counter | Before | After | Delta |
|---|---:|---:|---:|---|---:|---:|---:|
| fit | 1.0 | 32 | 1 | erasePreview.finishedPathBuilds | 144 | 31 | -113 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 160 | 31 | -129 |
| fit | 1.0 | 32 | 1 | erasePreview.pathBuilds | 144 | 31 | -113 |
| fit | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 1.0 | 32 | 1 | total.finishedPathBuilds | 73198 | 73085 | -113 |
| fit | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 73710 | 73581 | -129 |
| fit | 1.0 | 32 | 1 | total.pathBuilds | 73301 | 73188 | -113 |
| fit | 1.0 | 32 | 1 | total.rasterBuilds | 36 | 35 | -1 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedPathBuilds | 144 | 31 | -113 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 160 | 31 | -129 |
| fit | 1.0 | 32 | 2 | erasePreview.pathBuilds | 144 | 31 | -113 |
| fit | 1.0 | 32 | 2 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 1.0 | 32 | 2 | total.finishedPathBuilds | 73198 | 73085 | -113 |
| fit | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 73710 | 73581 | -129 |
| fit | 1.0 | 32 | 2 | total.pathBuilds | 73301 | 73188 | -113 |
| fit | 1.0 | 32 | 2 | total.rasterBuilds | 36 | 35 | -1 |
| fit | 1.0 | 32 | 3 | erasePreview.draws | 6 | 7 | +1 |
| fit | 1.0 | 32 | 3 | total.draws | 145 | 146 | +1 |
| sparse | 1.0 | 1 | 3 | erasePreview.draws | 8 | 9 | +1 |
| sparse | 1.0 | 1 | 3 | total.draws | 24 | 25 | +1 |
| sparse | 1.0 | 32 | 2 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 32 | 2 | total.draws | 149 | 148 | -1 |
| sparse | 1.0 | 32 | 3 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 32 | 3 | total.draws | 149 | 148 | -1 |
| fit | 2.0 | 1 | 1 | erasePreview.draws | 6 | 7 | +1 |
| fit | 2.0 | 1 | 1 | total.draws | 21 | 22 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.draws | 6 | 7 | +1 |
| fit | 2.0 | 1 | 2 | total.draws | 21 | 22 | +1 |
| fit | 2.0 | 1 | 3 | erasePreview.draws | 6 | 7 | +1 |
| fit | 2.0 | 1 | 3 | total.draws | 21 | 22 | +1 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedPathBuilds | 184 | 101 | -83 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 205 | 112 | -93 |
| fit | 2.0 | 32 | 3 | erasePreview.pathBuilds | 184 | 101 | -83 |
| fit | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 3 | 2 | -1 |
| fit | 2.0 | 32 | 3 | total.finishedPathBuilds | 73238 | 73155 | -83 |
| fit | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 73755 | 73662 | -93 |
| fit | 2.0 | 32 | 3 | total.pathBuilds | 73341 | 73258 | -83 |
| fit | 2.0 | 32 | 3 | total.rasterBuilds | 37 | 36 | -1 |
| sparse | 2.0 | 1 | 1 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 2.0 | 1 | 1 | erasePreview.draws | 9 | 5 | -4 |
| sparse | 2.0 | 1 | 1 | total.draws | 25 | 20 | -5 |
| sparse | 2.0 | 32 | 1 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 2.0 | 32 | 1 | total.draws | 149 | 148 | -1 |

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 1.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}

Failed preservation: {'view': 'sparse', 'density': 2.0, 'zoom': 1.25, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 0, 'passed': False}


- All48 app cases are retained raw; the comparison includes six ink append cases and six ink storage controls only.
- App figures are medians of three fork medians; p95 values describe batch averages, not individual-event tail latency.
- Renderer handler medians pool raw observations; all fork medians/counts and three raw commit observations are retained.
- Frozen renderer JSON has no independent fixture SHA: exact nine-file harness, native hash and case/fixture metadata establish identity.
- Renderer counters and every deviation are retained; preservation failures are review findings, never silently discarded.
- No timing thresholds, physical input latency, FPS, native RSS bound or whole-app performance claim is made.
- Configured renderer JVM flags/classpath are captured immediately before launch; actual runtime/vendor/native identity comes from its unchanged JSON.
- Normal-erasing/dirty-raster complementary references are outside this frozen-harness capture.
