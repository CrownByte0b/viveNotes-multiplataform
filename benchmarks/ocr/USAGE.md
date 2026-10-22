# Paired OCR diagnostics

These opt-in tools are separate from the original nine-file benchmark harness and all frozen
captures. They never read a user profile, download models, modify model pins or change build files.
Root coordinates all Gradle/JVM runs; use them only after the source/tests are frozen and other
build/benchmark/VM activity has stopped. Dependencies and both checkouts should already compile.

The baseline is production commit `e120e01`. Its only permitted untracked files are the two
generic test diagnostics, copied byte-identically from current:

- `shared/src/jvmTest/kotlin/com/vivenotes/ai/OcrCtcProfile.kt`
- `shared/src/jvmTest/kotlin/com/vivenotes/ai/OcrGeometryProfileTest.kt`

All tracked baseline bytes must match that commit. The generic CTC main loads the native adapter
by reflection only in native mode; baseline flat mode needs no new production source or native
adapter. Current additionally supplies the test-only `NativeCtcProfileAdapter`. Both test programs
and every production source/hash are recorded. The original nine-file harness must still match its
original manifest in both trees.

Run the lightweight tooling contracts without launching Gradle/JVMs:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/ocr -p 'test_*.py' -v
```

The CTC phase runs24 serial fresh JVMs: baseline flat versus current native decoding, widths320
and3200, lane/thread configurations1/1 and2/4, three forks. Pair order reverses on fork2. It uses
the unchanged pinned production v6 recognizer/dictionary. Real v5 compatibility remains covered
by integration tests; it is not another benchmark matrix. Progress prints the run/configuration,
elapsed time and a rolling remaining-time estimate after the first run.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/ocr/capture.py \
  --before /tmp/vive-desktop-ocr-before-e120e01 \
  --after /home/mahalo/repos/viveHub/multiplataform-vive \
  --phase ctc \
  --output benchmarks/results/2026-10-21-desktop-ocr-ctc-final
```

Geometry is a separate six-JVM phase, three fresh baseline/current pairs. Each measures12 cases:
rectangle and full postprocessing on three genuine pinned detector outputs; synthetic16/64/256/
1024-vertex hulls; and40/400 separated reading-order rows. Exact Float-bit geometry and ordered
outputs must match. Real-page hull sizes remain in the report; synthetic large-hull improvements
do not establish a real detector speedup.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/ocr/capture.py \
  --before /tmp/vive-desktop-ocr-before-e120e01 \
  --after /home/mahalo/repos/viveHub/multiplataform-vive \
  --phase geometry \
  --output benchmarks/results/2026-10-21-desktop-ocr-geometry-final
```

Use a NEW output directory for every attempt. Existing files/directories/symlinks and output inside
the baseline or any prior capture are refused. The runner never deletes partial evidence to retry.
After each task, it validates schema/case order, fixtures, outputs, confidence, sample counts,
upper medians, lifecycle stages, JVM identity/flags and all external artifact bytes. It rechecks
all source/harness/tool hashes before launching the next task. Current project artifacts may
differ from baseline; within each side their compiled hashes must remain unchanged.

The supplied `profile.init.gradle` registers only `:shared:ocrCtcProfile` and
`:shared:ocrGeometryProfile`, reusing the frozen baseline task's launcher/classpath/heaps/flags.
It forces fresh task execution and emits launcher SHA, complete configured VM flags, program
arguments, working directory and every classpath file/directory SHA before launch. Renderer/display
access is unnecessary. Commands are offline with one worker and configuration cache disabled.
Properties are `ocrRuntimeOutput` plus `ocrCtcOutput/Mode/Lanes/Threads/Width`, or
`ocrGeometryOutput` (mapped to `vivenotes.ocr.geometry.report`; the task enables
`VIVE_OCR_GEOMETRY_PROFILE=1`). The ONNX native runtime is supplied by the identical hashed external
ORT artifact; this tool does not inventory arbitrary process-loaded modules.

Raw JSON/runtime metadata and `-log.txt` files are retained per run. `execution.json` records
running/validated/failed status; `environment.json` contains source commits, dirty state, source,
harness, diagnostics and tool hashes, schedule, host CPU/affinity and final raw hashes. Source/hash
metadata uses array records with separate path/hash fields; no secret environment dump or scanner
exceptions are added. Every failure preserves partial reports/logs and leaves `complete:false`.
No successful comparison is published for a partial capture. Source drift is a failure.

Successful captures add `comparison.json` and `comparison.md`. Every timing/allocation/RSS regression
and all three fork values remain in JSON; complete raw lane/batch samples remain in the immutable
reports. Markdown shows every timing/allocation metric plus selected lifecycle RSS stages. All
RSS/HWM/heap/direct-buffer/GC observations remain raw. Revalidate raw hashes and regenerate only
derived comparisons for a COMPLETE capture with the same tool bytes:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/ocr/capture.py \
  --analyze benchmarks/results/2026-10-21-desktop-ocr-ctc-final
```

CTC reports session open separately; cold inference includes first executor work and lazy native
reducer opening. Image drawing/preprocessing is outside timing. Pipeline uses8 warmup and20
measured concurrent batches. Kernel uses one exact real native output held open,40 warmup and80
measured batches. Common output-hash/flat-parity setup makes one copy in both modes; GC is requested
before kernel timing. Output lifetime extends until all lane operations finish; sessions close
after lane shutdown. Profiles reject native fallback. Geometry uses31 ten-operation batch averages
after at least400ms warmup per case, excluding inference/hash setup.

The CTC diagnostic's even-count summaries are upper medians; comparisons use the median of three
fork summaries and retain the raw observations. Caller allocation/CPU covers only measured Java
lane threads; summed batch allocation is derived separately. Process CPU includes ORT workers.
Unsupported values remain unavailable. RSS/HWM is whole-process Linux memory including heap, JIT,
libraries, native arenas and allocator retention, sampled warm/idle/result-close/session-close/GC
stages. Java allocation savings do not prove native RSS savings. Requested GC is not guaranteed
collection. These warm-cache isolated pipeline/kernel diagnostics do not establish engine lifecycle,
whole-app, physical-input, throughput, tail-latency, confidence-interval or FPS results. There is no
performance claim until actual captures complete validation.
