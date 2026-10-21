# Separate warmed ink diagnostic

The frozen eight-warmup 10k ordinary-append case regresses from 51.428 to 70.163 µs. This
additional protocol investigates warmup sensitivity; it does not replace that result.

Both versions use the identical frozen `performanceInkAppendFixture(10000)`, validate the
127th-append result before timing, then reuse `BaselineMeasurements` with 20,000 warmup calls,
50 samples and one operation/sample. Each version runs in three fresh JVMs, alternating
original/current. No 128th-append timing executes in this diagnostic.

| Version | Fork medians µs | Median µs | Median caller Java bytes |
|---|---|---:|---:|
| Original | 14.037, 12.564, 7.354 | 12.564 | 23,192 |
| Optimized | 10.900, 11.241, 5.741 | 10.900 | 21,464 |

The median falls 13.2%. Both versions vary across forks. Invocation counts do not guarantee C2
compilation; no compilation logs were captured. These are isolated operation wall times and
caller-thread allocations, excluding native memory and application input/frame latency.

All six raw JSON reports and logs are retained. Runtime, heap and every JVM argument match
exactly, including a common scratch directory. The resolved task classpaths were hashed after
the runs: all 120 external JARs match each other and the original capture. Build inputs and
offline dependencies remained unchanged between forks. The original archive was verified against
all 315 baseline production/resource/schema hashes and nine frozen harness hashes. The final
working tree is identified by its separate 334-file capture manifest.

The Java main and Gradle init scripts are additional diagnostic artifacts; the original nine
capture harness files remain unchanged. A preliminary diagnostic with different checkout-specific
scratch-directory arguments was rejected and rerun with matching arguments. Its reports are not
used here.

To reproduce, use the same Java/runtime dependencies and idle machine. From the repository root,
create a temporary original checkout without altering Git metadata:

```sh
VIVE_INK_CURRENT="$(pwd)"
VIVE_INK_DIAG="$VIVE_INK_CURRENT/benchmarks/results/2026-10-21-optimized-final/ink-warm-diagnostic"
VIVE_INK_ORIGINAL="$(mktemp -d /tmp/vive-ink-original.XXXXXX)"
VIVE_INK_RESULTS="$(mktemp -d /tmp/vive-ink-results.XXXXXX)"
git archive 0e7a994664b7541c90953931d810a5458c9f5d79 | tar -x -C "$VIVE_INK_ORIGINAL"
```

Run the following from each checkout serially, alternating original/current for forks 1–3.
Use a distinct report filename for each invocation:

```sh
cd "$VIVE_INK_ORIGINAL"
env -u WAYLAND_DISPLAY -u XDG_SESSION_TYPE \
  JAVA_HOME=/usr/lib/jvm/java-latest-openjdk \
  ./gradlew -I "$VIVE_INK_DIAG/diagnostic.init.gradle" \
  :shared:inkAppendWarmDiagnostic \
  -PinkWarmSource="$VIVE_INK_DIAG/InkAppendWarmDiagnostic.java" \
  -PinkWarmScratch="$VIVE_INK_RESULTS/scratch" \
  -PinkWarmOutput="$VIVE_INK_RESULTS/original-fork-1.json" \
  -PinkWarmFork=1 -PinkWarmUnits=10000 \
  --offline --max-workers=1 --no-configuration-cache --console=plain
```

Change to `$VIVE_INK_CURRENT` for the corresponding current fork. Record both versions' source
hashes and keep this protocol separate from `capture.py` results. `metadata.init.gradle` adds
`:shared:inkAppendWarmMetadata`; pass `-PinkWarmMetadataOutput=/absolute/output.json` to retain the
resolved JAR hashes. Never run these diagnostics alongside other benchmarks or test suites.
