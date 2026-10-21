# Intermediate optimization capture

This complete three-fork capture is preserved as investigation evidence. It found a 4,100-byte
sparse-view raster rounding mismatch and a 10k-stroke ordinary-append timing regression under
the frozen eight-call warmup. The raster rounding and an avoidable source-lookup iterator were
subsequently corrected and covered by additional regressions.

Use [the final capture](../2026-10-21-optimized-final/summary.md) and
[implementation report](../../OPTIMIZATION_RESULTS.md) for the final comparison. This directory's
source manifest describes its captured code, before those last two corrections; it is not expected
to match the later working tree. All original baseline/harness bytes are unchanged.
