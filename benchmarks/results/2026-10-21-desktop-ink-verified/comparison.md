# Paired desktop ink capture

Three serial fresh app and renderer JVMs per variant; variant order reverses on fork2.

| Ink app workload | Units | Before ms | After ms | Change | Before fork ms | After fork ms |
|---|---:|---:|---:|---:|---|---|
| ink.apply_127th_append | 1000 | 0.132 | 0.086 | -34.8% | 0.132, 0.163, 0.128 | 0.119, 0.070, 0.086 |
| ink.apply_128th_append | 1000 | 0.132 | 0.141 | +6.6% | 0.133, 0.132, 0.128 | 0.141, 0.109, 0.165 |
| ink.apply_127th_append | 10000 | 0.071 | 0.046 | -35.1% | 0.070, 0.071, 0.079 | 0.046, 0.047, 0.046 |
| ink.apply_128th_append | 10000 | 0.077 | 0.075 | -1.6% | 0.077, 0.077, 0.076 | 0.075, 0.071, 0.078 |
| ink.apply_127th_append | 40000 | 0.018 | 0.014 | -25.0% | 0.021, 0.018, 0.018 | 0.014, 0.013, 0.014 |
| ink.apply_128th_append | 40000 | 0.018 | 0.025 | +38.2% | 0.018, 0.018, 0.020 | 0.025, 0.025, 0.025 |
| storage.append_ink_coalesced | 1000 | 0.257 | 0.244 | -5.0% | 0.209, 0.294, 0.257 | 0.244, 0.202, 0.266 |
| storage.append_ink_checkpoint | 1000 | 2.226 | 2.242 | +0.7% | 2.105, 2.280, 2.226 | 2.159, 2.374, 2.242 |
| storage.append_ink_coalesced | 10000 | 0.176 | 0.196 | +11.2% | 0.176, 0.174, 0.185 | 0.237, 0.192, 0.196 |
| storage.append_ink_checkpoint | 10000 | 14.310 | 14.775 | +3.2% | 14.820, 14.310, 13.751 | 14.775, 14.874, 14.152 |
| storage.append_ink_coalesced | 40000 | 0.169 | 0.232 | +37.5% | 0.148, 0.194, 0.169 | 0.253, 0.232, 0.224 |
| storage.append_ink_checkpoint | 40000 | 53.774 | 52.106 | -3.1% | 53.774, 54.208, 49.474 | 53.061, 51.346, 52.106 |

| View | Density | Additions | Before handler ms | After ms | Change | Before commit ms | After ms | Change |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| fit | 1.0 | 1 | 1.850 | 1.982 | +7.1% | 177.240 | 299.226 | +68.8% |
| fit | 1.0 | 32 | 0.862 | 0.849 | -1.5% | 157.692 | 211.917 | +34.4% |
| sparse | 1.0 | 1 | 0.697 | 0.724 | +3.8% | 8.664 | 4.824 | -44.3% |
| sparse | 1.0 | 32 | 0.593 | 0.537 | -9.4% | 8.127 | 3.932 | -51.6% |
| fit | 2.0 | 1 | 0.488 | 0.499 | +2.3% | 208.995 | 236.840 | +13.3% |
| fit | 2.0 | 32 | 0.389 | 0.375 | -3.4% | 135.205 | 163.066 | +20.6% |
| sparse | 2.0 | 1 | 0.320 | 0.336 | +5.0% | 5.364 | 2.908 | -45.8% |
| sparse | 2.0 | 32 | 0.312 | 0.295 | -5.6% | 5.025 | 2.718 | -45.9% |

Structural deviations: 159. Failed preservation checks: 12.

| View | Density | Additions | Fork | Counter | Before | After | Delta |
|---|---:|---:|---:|---|---:|---:|---:|
| fit | 1.0 | 1 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 1 | 1 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 1 | 1 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 1.0 | 1 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 1 | 2 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 1 | 2 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 1.0 | 1 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 1 | 3 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 1 | 3 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 1.0 | 32 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedPathBuilds | 73252 | 31 | -73221 |
| fit | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 73252 | 31 | -73221 |
| fit | 1.0 | 32 | 1 | erasePreview.pathBuilds | 73252 | 31 | -73221 |
| fit | 1.0 | 32 | 1 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 1.0 | 32 | 1 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 32 | 1 | total.finishedPathBuilds | 146306 | 73085 | -73221 |
| fit | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 146802 | 73581 | -73221 |
| fit | 1.0 | 32 | 1 | total.pathBuilds | 146409 | 73188 | -73221 |
| fit | 1.0 | 32 | 1 | total.rasterBuilds | 36 | 35 | -1 |
| fit | 1.0 | 32 | 1 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| fit | 1.0 | 32 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedPathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 73252 | 160 | -73092 |
| fit | 1.0 | 32 | 2 | erasePreview.pathBuilds | 73252 | 144 | -73108 |
| fit | 1.0 | 32 | 2 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 32 | 2 | total.finishedPathBuilds | 146306 | 73198 | -73108 |
| fit | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 146802 | 73710 | -73092 |
| fit | 1.0 | 32 | 2 | total.pathBuilds | 146409 | 73301 | -73108 |
| fit | 1.0 | 32 | 2 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| fit | 1.0 | 32 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedPathBuilds | 36662 | 144 | -36518 |
| fit | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 36662 | 160 | -36502 |
| fit | 1.0 | 32 | 3 | erasePreview.pathBuilds | 36662 | 144 | -36518 |
| fit | 1.0 | 32 | 3 | erasePreview.rasterBuilds | 1 | 2 | +1 |
| fit | 1.0 | 32 | 3 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 1.0 | 32 | 3 | total.finishedPathBuilds | 109716 | 73198 | -36518 |
| fit | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 110212 | 73710 | -36502 |
| fit | 1.0 | 32 | 3 | total.pathBuilds | 109819 | 73301 | -36518 |
| fit | 1.0 | 32 | 3 | total.rasterBuilds | 35 | 36 | +1 |
| fit | 1.0 | 32 | 3 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| sparse | 1.0 | 1 | 1 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 1 | 1 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 1 | erasePreview.draws | 9 | 8 | -1 |
| sparse | 1.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 1 | total.draws | 25 | 23 | -2 |
| sparse | 1.0 | 1 | 1 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 1 | 2 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 1.0 | 1 | 2 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 2 | erasePreview.draws | 9 | 8 | -1 |
| sparse | 1.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 2 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 1 | 3 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 1 | 3 | erasePreview.draws | 7 | 9 | +2 |
| sparse | 1.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 1 | 3 | total.draws | 22 | 24 | +2 |
| sparse | 1.0 | 1 | 3 | total.finishedStrokeVectorDraws | 7206 | 1241 | -5965 |
| sparse | 1.0 | 32 | 1 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 1.0 | 32 | 1 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 32 | 1 | total.draws | 149 | 148 | -1 |
| sparse | 1.0 | 32 | 1 | total.finishedStrokeVectorDraws | 7733 | 1768 | -5965 |
| sparse | 1.0 | 32 | 2 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 32 | 2 | total.finishedStrokeVectorDraws | 7733 | 1768 | -5965 |
| sparse | 1.0 | 32 | 3 | eraseFinish.finishedStrokeVectorDraws | 1169 | 3 | -1166 |
| sparse | 1.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 4811 | 12 | -4799 |
| sparse | 1.0 | 32 | 3 | total.finishedStrokeVectorDraws | 7733 | 1768 | -5965 |
| fit | 2.0 | 1 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedPathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 73252 | 24 | -73228 |
| fit | 2.0 | 1 | 1 | erasePreview.pathBuilds | 73252 | 24 | -73228 |
| fit | 2.0 | 1 | 1 | erasePreview.rasterBuilds | 2 | 1 | -1 |
| fit | 2.0 | 1 | 1 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 1 | 1 | total.finishedPathBuilds | 146275 | 73047 | -73228 |
| fit | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 146275 | 73047 | -73228 |
| fit | 2.0 | 1 | 1 | total.pathBuilds | 146285 | 73057 | -73228 |
| fit | 2.0 | 1 | 1 | total.rasterBuilds | 5 | 4 | -1 |
| fit | 2.0 | 1 | 1 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 2.0 | 1 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedPathBuilds | 36662 | 101 | -36561 |
| fit | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 36662 | 112 | -36550 |
| fit | 2.0 | 1 | 2 | erasePreview.pathBuilds | 36662 | 101 | -36561 |
| fit | 2.0 | 1 | 2 | erasePreview.rasterBuilds | 1 | 2 | +1 |
| fit | 2.0 | 1 | 2 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 1 | 2 | total.draws | 20 | 21 | +1 |
| fit | 2.0 | 1 | 2 | total.finishedPathBuilds | 109685 | 73124 | -36561 |
| fit | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 109685 | 73135 | -36550 |
| fit | 2.0 | 1 | 2 | total.pathBuilds | 109695 | 73134 | -36561 |
| fit | 2.0 | 1 | 2 | total.rasterBuilds | 4 | 5 | +1 |
| fit | 2.0 | 1 | 2 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 2.0 | 1 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedPathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 73252 | 112 | -73140 |
| fit | 2.0 | 1 | 3 | erasePreview.pathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 1 | 3 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 1 | 3 | total.finishedPathBuilds | 146275 | 73124 | -73151 |
| fit | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 146275 | 73135 | -73140 |
| fit | 2.0 | 1 | 3 | total.pathBuilds | 146285 | 73134 | -73151 |
| fit | 2.0 | 1 | 3 | total.sceneAndSceneIndexBuilds | 2 | 3 | +1 |
| fit | 2.0 | 32 | 1 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedPathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 73252 | 112 | -73140 |
| fit | 2.0 | 32 | 1 | erasePreview.pathBuilds | 73252 | 101 | -73151 |
| fit | 2.0 | 32 | 1 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 32 | 1 | total.finishedPathBuilds | 146306 | 73155 | -73151 |
| fit | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 146802 | 73662 | -73140 |
| fit | 2.0 | 32 | 1 | total.pathBuilds | 146409 | 73258 | -73151 |
| fit | 2.0 | 32 | 1 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| fit | 2.0 | 32 | 2 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 32 | 2 | erasePreview.draws | 5 | 6 | +1 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedPathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 36662 | 24 | -36638 |
| fit | 2.0 | 32 | 2 | erasePreview.pathBuilds | 36662 | 24 | -36638 |
| fit | 2.0 | 32 | 2 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 32 | 2 | total.draws | 144 | 145 | +1 |
| fit | 2.0 | 32 | 2 | total.finishedPathBuilds | 109716 | 73078 | -36638 |
| fit | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 110212 | 73574 | -36638 |
| fit | 2.0 | 32 | 2 | total.pathBuilds | 109819 | 73181 | -36638 |
| fit | 2.0 | 32 | 2 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| fit | 2.0 | 32 | 3 | eraseFinish.sceneAndSceneIndexBuilds | 0 | 1 | +1 |
| fit | 2.0 | 32 | 3 | erasePreview.draws | 5 | 7 | +2 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedPathBuilds | 36662 | 101 | -36561 |
| fit | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 36662 | 112 | -36550 |
| fit | 2.0 | 32 | 3 | erasePreview.pathBuilds | 36662 | 101 | -36561 |
| fit | 2.0 | 32 | 3 | erasePreview.rasterBuilds | 1 | 2 | +1 |
| fit | 2.0 | 32 | 3 | fullPageSceneBuilds | 1 | 2 | +1 |
| fit | 2.0 | 32 | 3 | total.draws | 144 | 146 | +2 |
| fit | 2.0 | 32 | 3 | total.finishedPathBuilds | 109716 | 73155 | -36561 |
| fit | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 110212 | 73662 | -36550 |
| fit | 2.0 | 32 | 3 | total.pathBuilds | 109819 | 73258 | -36561 |
| fit | 2.0 | 32 | 3 | total.rasterBuilds | 35 | 36 | +1 |
| fit | 2.0 | 32 | 3 | total.sceneAndSceneIndexBuilds | 33 | 34 | +1 |
| sparse | 2.0 | 1 | 1 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 1 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 1 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 1 | 2 | eraseFinish.draws | 3 | 2 | -1 |
| sparse | 2.0 | 1 | 2 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 2 | erasePreview.draws | 9 | 8 | -1 |
| sparse | 2.0 | 1 | 2 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 2 | total.draws | 25 | 23 | -2 |
| sparse | 2.0 | 1 | 2 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 1 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 1 | 3 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 1 | 3 | erasePreview.draws | 7 | 9 | +2 |
| sparse | 2.0 | 1 | 3 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 1 | 3 | total.draws | 22 | 25 | +3 |
| sparse | 2.0 | 1 | 3 | total.finishedStrokeVectorDraws | 1751 | 309 | -1442 |
| sparse | 2.0 | 32 | 1 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 1 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 1 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |
| sparse | 2.0 | 32 | 2 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 2 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 2 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |
| sparse | 2.0 | 32 | 3 | eraseFinish.draws | 2 | 3 | +1 |
| sparse | 2.0 | 32 | 3 | eraseFinish.finishedStrokeVectorDraws | 276 | 0 | -276 |
| sparse | 2.0 | 32 | 3 | erasePreview.finishedStrokeVectorDraws | 1168 | 2 | -1166 |
| sparse | 2.0 | 32 | 3 | total.draws | 148 | 149 | +1 |
| sparse | 2.0 | 32 | 3 | total.finishedStrokeVectorDraws | 2278 | 836 | -1442 |

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 1.0, 'zoom': 0.21333334, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 1, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 1, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 2, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}

Failed preservation: {'view': 'fit', 'density': 2.0, 'zoom': 0.10666667, 'viewportPx': 512, 'scrollXPx': 17.25, 'scrollYPx': 29.75, 'finishedPageStrokes': 40000, 'pendingAdditions': 32, 'eraseCommitTimingScope': 'test release dispatch through worker commit and captured Canvas draw, including wait polling/scheduling'}: {'fork': 3, 'counter': 'fullPageSceneBuilds', 'expected': 1, 'actual': 2, 'passed': False}


- All48 app cases are retained raw; the comparison includes six ink append cases and six ink storage controls only.
- App figures are medians of three fork medians; p95 values describe batch averages, not individual-event tail latency.
- Renderer handler medians pool raw observations; all fork medians/counts and three raw commit observations are retained.
- Frozen renderer JSON has no independent fixture SHA: exact nine-file harness, native hash and case/fixture metadata establish identity.
- Renderer counters and every deviation are retained; preservation failures are review findings, never silently discarded.
- No timing thresholds, physical input latency, FPS, native RSS bound or whole-app performance claim is made.
- Configured renderer JVM flags/classpath are captured immediately before launch; actual runtime/vendor/native identity comes from its unchanged JSON.
- Normal-erasing/dirty-raster complementary references are outside this frozen-harness capture.
