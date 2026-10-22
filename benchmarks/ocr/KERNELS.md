# Native decoder investigation

The first paired CTC capture exposed slower native reduction and more CPU use with an
IsNaN → Where → ArgMax → GatherElements graph. It remains unchanged at
[`2026-10-21-desktop-ocr-ctc`](../results/2026-10-21-desktop-ocr-ctc/comparison.md).
That graph is superseded by guarded TopK with one reduction thread and worker spinning disabled.

The separate [kernel controls](../results/2026-10-21-desktop-ocr-kernel-controls/integrity.json)
retain eight exploratory fresh-process runs, their exact source snapshot, raw samples and native
operator traces. These are single-fork controls, not the final paired benchmark. All share a held
real recognizer output; scalar mode retains a common copied FloatBuffer and is only a lower-bound
diagnostic. Flat mode makes the original full copy for every operation. Native modes return only
choices, except guarded TopK's exceptional NaN path. Recognizer pooling is configured separately.
Native profiling opens another session only AFTER all unprofiled latency samples, so its node
times and additional memory are separate from those samples. Embedded absolute paths identify
the original build locations; the integrity manifest identifies the retained copies.

`NativeCtcKernelProfile` also contains the exact legacy graph so it remains reproducible after the
production replacement. Its `current` mode name refers to the initial graph at investigation time.
The initial exploration source is under `source/`; the final correctness preflight and its source
are retained separately under `guarded-topk-final-normal-parity*` and `final-source/`. The latter
checks exact choice indices and raw score bits for exceptional first-class NaNs and finite-first
rows with later NaNs, and verifies no full-copy fallback for the real model output.

Run serially, after stopping other tests and benchmarks, with existing offline dependencies:

```sh
JAVA_HOME=/usr/lib/jvm/java-latest-openjdk ./gradlew \
  -I benchmarks/ocr/kernel.init.gradle :shared:nativeCtcKernelProfile \
  -PctcKernelOutput=/tmp/new-ocr-kernel-report.json \
  -PctcKernelMode=guarded-topk -PctcKernelThreads=1 -PctcKernelSpinning=0 \
  -PctcKernelWidth=3200 -PctcKernelLanes=2 -PctcKernelRecognizerThreads=4 \
  --max-workers=1 --offline --console=plain --no-configuration-cache
```

Use a new report path each time. Modes are `flat`, `scalar`, `current`, `topk`, `guarded-topk`;
unprotected `topk` intentionally exposes the exceptional NaN mismatch. Reduction threads are 1
or 4, spinning is 0 or 1, width is 320 or 3200, lanes are 1 or 2, and recognizer threads are 1 or 4.
Optional `ctcKernelArena` and `ctcKernelPattern` properties default to 1; they did not change in
production. The init script reuses the original benchmark's JVM launcher, heap, flags and classpath.
RSS is whole-process memory, not isolated tensor memory. Final performance claims use the separate
three-fork paired capture linked from [RESULTS.md](RESULTS.md).
