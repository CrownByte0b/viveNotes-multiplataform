# Paired desktop ink capture

Three serial fresh app and renderer JVMs per variant; variant order reverses on fork2.

| Ink app workload | Units | Before ms | After ms | Change | Before fork ms | After fork ms |
|---|---:|---:|---:|---:|---|---|
| ink.apply_127th_append | 1000 | 0.130 | 0.071 | -45.8% | 0.132, 0.130, 0.126 | 0.071, 0.071, 0.070 |
| ink.apply_128th_append | 1000 | 0.123 | 0.116 | -6.2% | 0.123, 0.128, 0.119 | 0.116, 0.119, 0.104 |
| ink.apply_127th_append | 10000 | 0.070 | 0.040 | -43.1% | 0.070, 0.070, 0.069 | 0.038, 0.041, 0.040 |
| ink.apply_128th_append | 10000 | 0.074 | 0.064 | -13.1% | 0.075, 0.074, 0.072 | 0.064, 0.067, 0.063 |
| ink.apply_127th_append | 40000 | 0.017 | 0.012 | -26.0% | 0.017, 0.022, 0.017 | 0.012, 0.012, 0.013 |
| ink.apply_128th_append | 40000 | 0.018 | 0.025 | +37.3% | 0.018, 0.028, 0.017 | 0.024, 0.025, 0.025 |
| storage.append_ink_coalesced | 1000 | 0.200 | 0.223 | +11.4% | 0.200, 0.182, 0.207 | 0.238, 0.223, 0.206 |
| storage.append_ink_checkpoint | 1000 | 1.698 | 1.906 | +12.2% | 1.852, 1.698, 1.687 | 1.808, 1.906, 1.931 |
| storage.append_ink_coalesced | 10000 | 0.165 | 0.182 | +10.7% | 0.125, 0.173, 0.165 | 0.157, 0.182, 0.188 |
| storage.append_ink_checkpoint | 10000 | 12.206 | 12.379 | +1.4% | 11.922, 12.206, 12.232 | 12.042, 12.379, 13.004 |
| storage.append_ink_coalesced | 40000 | 0.149 | 0.147 | -1.0% | 0.129, 0.149, 0.163 | 0.198, 0.147, 0.130 |
| storage.append_ink_checkpoint | 40000 | 46.278 | 47.349 | +2.3% | 45.741, 46.278, 47.585 | 48.293, 47.349, 47.330 |

| View | Density | Additions | Before handler ms | After ms | Change | Before commit ms | After ms | Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fit | 1.0 | 1 | 1.456 | 1.482 | +1.8% | 156.088 | 289.582 | +85.5% |
| fit | 1.0 | 32 | 0.642 | 0.605 | -5.8% | 133.380 | 196.684 | +47.5% |
| sparse | 1.0 | 1 | 0.498 | 0.494 | -0.8% | 6.614 | 3.529 | -46.6% |
| sparse | 1.0 | 32 | 0.431 | 0.411 | -4.6% | 6.551 | 2.735 | -58.2% |
| fit | 2.0 | 1 | 0.364 | 0.363 | -0.4% | 138.734 | 161.399 | +16.3% |
| fit | 2.0 | 32 | 0.302 | 0.309 | +2.0% | 115.336 | 169.633 | +47.1% |
| sparse | 2.0 | 1 | 0.283 | 0.290 | +2.4% | 4.215 | 3.139 | -25.5% |
| sparse | 2.0 | 32 | 0.259 | 0.249 | -4.0% | 4.172 | 3.069 | -26.4% |

Structural deviations: 152. Failed preservation checks: 0.

| View | Density | Additions | Fork | Counter | Before | After | Delta |
|---|---:|---:|---:|---|---:|---:|---:|
| fit | 1.0 | 1 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 1 | 1 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 1.0 | 1 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 1 | 2 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 1.0 | 1 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 1 | 3 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 1.0 | 32 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 32 | 1 | erasePreview.draws | 6 | 7 | +1 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedPathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 73252 | 160 | -73092 |
| fit | 1.0 | 32 | 1 | erasePreview.pathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 1 | total.draws | 145 | 146 | +1 |
| fit | 1.0 | 32 | 1 | total.finishedPathBuilds | 146306 | 73198 | -73108 |
| fit | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 146802 | 73710 | -73092 |
| fit | 1.0 | 32 | 1 | total.pathBuilds | 146409 | 73301 | -73108 |
| fit | 1.0 | 32 | 1 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| fit | 1.0 | 32 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 32 | 2 | erasePreview.draws | 6 | 7 | +1 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedPathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 73252 | 160 | -73092 |
| fit | 1.0 | 32 | 2 | erasePreview.pathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 2 | total.draws | 145 | 146 | +1 |
| fit | 1.0 | 32 | 2 | total.finishedPathBuilds | 146306 | 73198 | -73108 |
| fit | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 146802 | 73710 | -73092 |
| fit | 1.0 | 32 | 2 | total.pathBuilds | 146409 | 73301 | -73108 |
| fit | 1.0 | 32 | 2 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| fit | 1.0 | 32 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 1.0 | 32 | 3 | erasePreview.draws | 6 | 7 | +1 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedPathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 73252 | 160 | -73092 |
| fit | 1.0 | 32 | 3 | erasePreview.pathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 3 | total.draws | 145 | 146 | +1 |
| fit | 1.0 | 32 | 3 | total.finishedPathBuilds | 146306 | 73198 | -73108 |
| fit | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 146802 | 73710 | -73092 |
| fit | 1.0 | 32 | 3 | total.pathBuilds | 146409 | 73301 | -73108 |
| fit | 1.0 | 32 | 3 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| sparse | 1.0 | 1 | 1 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 1 | 1 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 1 | total.draws | 25 | 24 | -1 |
| sparse | 1.0 | 1 | 1 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 1 | 2 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 2 | erasePreview.draws | 9 | 8 | -1 |
| sparse | 1.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 2 | total.draws | 24 | 23 | -1 |
| sparse | 1.0 | 1 | 2 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 3 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 3 | total.draws | 24 | 25 | +1 |
| sparse | 1.0 | 1 | 3 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 32 | 1 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 1 | erasePreview.draws | 9 | 7 | -2 |
| sparse | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 4811 | 9 | -4802 |
| sparse | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 4 | 3 | -1 |
| sparse | 1.0 | 32 | 1 | total.draws | 148 | 146 | -2 |
| sparse | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 7733 | 1765 | -5968 |
| sparse | 1.0 | 32 | 1 | total.rasterBuilds | 38 | 37 | -1 |
| sparse | 1.0 | 32 | 2 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 32 | 2 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 2 | erasePreview.draws | 9 | 7 | -2 |
| sparse | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 32 | 2 | total.draws | 149 | 146 | -3 |
| sparse | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 7733 | 1768 | -5965 |
| sparse | 1.0 | 32 | 3 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 32 | 3 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 32 | 3 | total.draws | 149 | 148 | -1 |
| sparse | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 7733 | 1768 | -5965 |
| fit | 2.0 | 1 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedPathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 1 | erasePreview.pathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 1 | total.finishedPathBuilds | 109685 | 73047 | -36638 |
| fit | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 109685 | 73047 | -36638 |
| fit | 2.0 | 1 | 1 | total.pathBuilds | 109695 | 73057 | -36638 |
| fit | 2.0 | 1 | 1 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 2.0 | 1 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 1 | 2 | erasePreview.draws | 6 | 7 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedPathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 73252 | 112 | -73140 |
| fit | 2.0 | 1 | 2 | erasePreview.pathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 1 | 2 | total.draws | 21 | 22 | +1 |
| fit | 2.0 | 1 | 2 | total.finishedPathBuilds | 146275 | 73124 | -73151 |
| fit | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 146275 | 73135 | -73140 |
| fit | 2.0 | 1 | 2 | total.pathBuilds | 146285 | 73134 | -73151 |
| fit | 2.0 | 1 | 2 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 2.0 | 1 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedPathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 3 | erasePreview.pathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 1 | 3 | total.finishedPathBuilds | 109685 | 73047 | -36638 |
| fit | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 109685 | 73047 | -36638 |
| fit | 2.0 | 1 | 3 | total.pathBuilds | 109695 | 73057 | -36638 |
| fit | 2.0 | 1 | 3 | total.sceneAndSceneIndexBuilds | 2 | 313 | +311 |
| fit | 2.0 | 32 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 32 | 1 | erasePreview.draws | 7 | 6 | -1 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedPathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 1 | erasePreview.pathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 1 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 2.0 | 32 | 1 | total.draws | 146 | 145 | -1 |
| fit | 2.0 | 32 | 1 | total.finishedPathBuilds | 146306 | 73078 | -73228 |
| fit | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 146802 | 73574 | -73228 |
| fit | 2.0 | 32 | 1 | total.pathBuilds | 146409 | 73181 | -73228 |
| fit | 2.0 | 32 | 1 | total.rasterBuilds | 36 | 35 | -1 |
| fit | 2.0 | 32 | 1 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| fit | 2.0 | 32 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 32 | 2 | erasePreview.draws | 7 | 6 | -1 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedPathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 73252 | 112 | -73140 |
| fit | 2.0 | 32 | 2 | erasePreview.pathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 32 | 2 | total.draws | 146 | 145 | -1 |
| fit | 2.0 | 32 | 2 | total.finishedPathBuilds | 146306 | 73155 | -73151 |
| fit | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 146802 | 73662 | -73140 |
| fit | 2.0 | 32 | 2 | total.pathBuilds | 146409 | 73258 | -73151 |
| fit | 2.0 | 32 | 2 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| fit | 2.0 | 32 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 311 | +311 |
| fit | 2.0 | 32 | 3 | erasePreview.draws | 7 | 6 | -1 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedPathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 3 | erasePreview.pathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 2.0 | 32 | 3 | total.draws | 146 | 145 | -1 |
| fit | 2.0 | 32 | 3 | total.finishedPathBuilds | 146306 | 73078 | -73228 |
| fit | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 146802 | 73574 | -73228 |
| fit | 2.0 | 32 | 3 | total.pathBuilds | 146409 | 73181 | -73228 |
| fit | 2.0 | 32 | 3 | total.rasterBuilds | 36 | 35 | -1 |
| fit | 2.0 | 32 | 3 | total.sceneAndSceneIndexBuilds | 33 | 344 | +311 |
| sparse | 2.0 | 1 | 1 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 1 | 2 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 1 | 3 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 2.0 | 1 | 3 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 3 | erasePreview.draws | 9 | 8 | -1 |
| sparse | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 3 | erasePreview.rasterBuilds | 4 | 3 | -1 |
| sparse | 2.0 | 1 | 3 | total.draws | 25 | 23 | -2 |
| sparse | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 1 | 3 | total.rasterBuilds | 7 | 6 | -1 |
| sparse | 2.0 | 32 | 1 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |
| sparse | 2.0 | 32 | 2 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |
| sparse | 2.0 | 32 | 3 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |


- All48 app cases are retained raw; the comparison includes six ink append cases and six ink storage controls only.
- App figures are medians of three fork medians; p95 values describe batch averages, not individual-event tail latency.
- Renderer handler medians pool raw observations; all fork medians/counts and three raw commit observations are retained.
- Frozen renderer JSON has no independent fixture SHA: exact nine-file harness, native hash and case/fixture metadata establish identity.
- Renderer counters and every deviation are retained; preservation failures are review findings, never silently discarded.
- No timing thresholds, physical input latency, FPS, native RSS bound or whole-app performance claim is made.
- Configured renderer JVM flags/classpath are captured immediately before launch; actual runtime/vendor/native identity comes from its unchanged JSON.
- Normal-erasing/dirty-raster complementary references are outside this frozen-harness capture.
