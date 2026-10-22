# Viewport raster measurements

Use a clean checkout of `5cbf74f` as the baseline. Copy only
`shared/src/jvmTest/kotlin/com/vivenotes/ui/canvas/InkViewportPerformanceScenario.kt` into it,
leaving that new probe untracked. Use a separate checkout containing the implementation for
the after variant. The probe bytes must match this directory's repository and both checkouts.
Neither source copy should contain local account configuration or user data.

Run the capture serially, without other Gradle/native/GUI/VM measurement jobs:

```sh
env PYTHONDONTWRITEBYTECODE=1 \
  JAVA_HOME=/home/mahalo/.gradle/jdks/azul_systems__inc_-27-amd64-linux.2 \
  python3 benchmarks/viewport-raster/capture.py \
  --before /tmp/viewport-before --after /tmp/viewport-after \
  --baseline-commit 5cbf74f --output /tmp/viewport-paired-new
```

The runner owns Xvfb, uses the existing offline dependency/toolchain configuration and a 4 GiB
probe heap, forces a fresh test execution, and captures three alternating pairs. It imports the
unchanged ink harness's source/artifact hashing helpers. Sources, tool bytes, native/runtime
identity, external classpaths and actual JVM arguments must remain compatible. Each fork runs
20 cases: five eight-view sequences at two densities and fit/sparse zoom. Every sequence starts
with an empty raster cache. Eight warmups and seven measured sequences use the same fixed work.
The 40,000 original native meshes are distinct. Exact PNG checks compare every view with a fresh
published ByteInk full raster before timing.

Counters count actual builds and vector draws, with no stale vector count on a cache hit.
Allocated surface pixels count full destination allocations even for a dirty update; updated
pixels are changed rectangles, not total Skia fragment work. Retained pixel bytes exclude
transient surfaces, native paths, meshes and indexes. Timings are synchronous software work,
including cache/surface clear and image blits; they do not measure UI scheduling, FPS, GPU time
or physical input latency. Allocation samples cover only the caller thread. Quantiles are
per-fork batch observations, not established individual-frame tail latency.

Verify all raw hashes and recompute the comparison without rewriting evidence:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/viewport-raster/capture.py \
  --verify /tmp/viewport-paired-new
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s benchmarks/viewport-raster -p 'test_*.py' -v
```

The existing `benchmarks/ink/capture.py` separately rechecks the original 40k seal-append and
fit-erase workloads against a fully clean baseline. The unchanged
`benchmarks/text-preparation/text_runtime.py` supplies real-app X11/Windows measurements and
JFR/NMT analysis. These are separate workloads and their numbers must not be combined as if
they measured the same operation. See RESULTS.md and verification.json for the completed
milestone's exact source identities, commands, retained evidence and limitations.
