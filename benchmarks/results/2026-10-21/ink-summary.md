| View | Density | Additions | Median handler→next draw ms (all fork observations) | Median test release→committed draw ms | Peak raster bytes |
|---|---:|---:|---:|---:|---:|
| fit | 1.0 | 1 | 1.442 | 158.599 | 2,105,352 |
| fit | 1.0 | 32 | 0.614 | 147.279 | 2,105,352 |
| sparse | 1.0 | 1 | 0.480 | 10.456 | 2,097,152 |
| sparse | 1.0 | 32 | 0.516 | 10.630 | 2,097,152 |
| fit | 2.0 | 1 | 0.452 | 163.531 | 2,105,352 |
| fit | 2.0 | 32 | 0.354 | 124.915 | 2,105,352 |
| sparse | 2.0 | 1 | 0.303 | 6.988 | 2,097,152 |
| sparse | 2.0 | 32 | 0.292 | 6.931 | 2,097,152 |

Three fresh JVMs; 40,000 distinct finished meshes. Raw observations remain in ink-fork-*.json.
Handler→next draw may precede processing all queued observations. Commit timing includes wait polling and screenshot/test scheduling; neither is physical input-to-photon latency.
All 24 cases retain six observed/advanced held samples and zero held finished scene/raster/path/vector rebuilds; eraser previews retain zero scene/index rebuilds.
