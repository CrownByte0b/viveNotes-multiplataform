# Paired desktop ink capture

This separate opt-in runner invokes the unchanged `:shared:performanceBaseline` (48 cases) and
`:shared:inkPhase2Scenario` (eight cases). It runs three fresh JVMs per task and variant, serially:
before/after app, before/after renderer for fork1; reverse the variant order for fork2; repeat
fork1 order for fork3. There are12 child JVM launches. It does not alter the original capture tool,
the nine frozen harness files, existing captures, production code or build scripts.

First finish correctness checks and prepare both checkouts' dependencies/builds. Freeze their
sources and avoid competing build/benchmark/VM load during capture. The baseline must be a clean
checkout of602ff15, including untracked files. Both checkouts must match the original nine-file
harness manifest and all48 original workload fixture hashes.

Run the lightweight tooling contracts (no Gradle or JVM launches):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s benchmarks/ink -p 'test_*.py' -v
```

Then capture on the same host and environment, using a NEW output directory:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/ink/capture.py \
  --before /tmp/vive-desktop-ink-before-602ff15 \
  --after /home/mahalo/repos/viveHub/multiplataform-vive \
  --output /home/mahalo/repos/viveHub/multiplataform-vive/benchmarks/results/2026-10-21-desktop-ink-final
```

An existing output path is always refused, including a failed partial capture. Output cannot be
inside the baseline checkout or nested inside any existing results directory. Nothing is cleaned
or overwritten to make a capture pass. After any failure, retain that directory and use a new
sibling directory for the next attempt.

The tool uses offline Gradle, one worker, configuration cache disabled and an output-only generated
init script. The init script forces execution of each target task and records its configured JVM
launcher, full VM arguments and hashes of every classpath file/directory immediately before launch.
Renderer runs use `xvfb-run` with1280×1024×24; inherited Wayland display is removed and the session
type is X11. The frozen tasks keep their existing heap sizes, warmups and sampling rules.

Every completed task's raw report and runtime metadata are validated before the next launch.
Across all runs, the tool checks exact case coverage/order, app fixture hashes, actual app VM
arguments/runtime, configured task launchers/flags, external artifact bytes/order and renderer
native/runtime/fixture identities. Checkout-root arguments and the exact renderer report property
are normalized. Project classpath hashes may change and are retained raw; external hashes must
match. App and renderer use the same external classpath bytes. The frozen renderer JSON has no
independent fixture SHA, so unchanged harness bytes, native hash and exact fixture/case metadata
establish its workload identity. Renderer arguments are prelaunch configuration observations;
actual runtime/vendor/native fields come from the unchanged renderer report.

Output retains all six app reports, six renderer reports, per-task runtime metadata, command/log
records, renderer JUnit XML, ordered execution status and source/tool/harness provenance. Log names
end in `-log.txt`. Source hashes, harness hashes and changed-source hashes use arrays of records with
separate path/hash fields; no scanner exceptions are added. Final source/harness/tool hashes must
still match the starting snapshot. A failed task marks the execution record failed, preserves its
log/partial JSON, leaves `environment.json` with `complete:false` and produces no final comparison.

The comparison includes12 app ink workloads (six append-boundary cases plus six repository append
controls) and all eight renderer cases. All48 app cases remain available in each raw report. Every
timing regression, fork sample, raw erase-commit observation, structural counter deviation and
failed preservation check remains visible; no performance threshold suppresses a result. The
comparison is JSON plus Markdown. To revalidate retained raw hashes and regenerate only these
derived outputs after a COMPLETE capture:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/ink/capture.py --analyze benchmarks/results/2026-10-21-desktop-ink-final
```

App timings are medians of three fork medians; its p95 is a percentile of batch averages, not
individual-event tail latency. Adaptive batch counts and warmup counts remain in the raw reports.
Renderer handler timings pool raw observations, with fork medians/counts retained. Erase commit has
one observation per fork, so only three raw observations and their median are reported. These
scopes include scheduling/GC and warm caches. This tool does not measure physical input latency,
FPS, whole-app CPU/GPU or a native RSS bound. The frozen renderer uses Object erasing and at most32
appends. Complementary current-reference Normal-erase/dirty-raster diagnostics are separate from
this historical before/after capture and are not launched by this tool. No performance result is
claimed until actual captures pass validation.
