# Idle GC component investigation

This experiment invokes the frozen startup recognition probe twice in a fresh JVM. Each
invocation uses real ONNX inference on the synthetic `hello` fixture and explicitly closes
the engine, image and model store. Each invocation performs one first inference and three
warm repeats; next-work duration includes construction, all four calls and disposal.
Between them, the process performs no recognition work
for 12 seconds. It records Java heap snapshots, sampled process RSS, JVM NMT summaries,
GC causes/pauses and the duration of engine reconstruction plus the next OCR workload.
Both recognition results must agree exactly.

Four diagnostic modes run in forward/reverse order on alternating forks:

- Normal runtime defaults, with `-Xmx2g` and no explicit initial/minimum heap.
- G1 periodic collection every five seconds, concurrent mode, system-load gate disabled.
- A single `System.gc()` call after five seconds without recognition work.
- The same request with `-XX:+ExplicitGCInvokesConcurrent`.

Five seconds is an experimental setting, not a proposed application policy. Periodic G1
uses collection timing and system load, not user interaction or application-job inactivity.
The explicit request runs on the diagnostic main thread, never an application UI handler;
stop-the-world pauses can still affect other threads.

```sh
python3 -B -m unittest discover -s benchmarks/idle-gc -v
python3 -B benchmarks/idle-gc/idle_gc.py \
  --output /new/private/output \
  --java /jdk/bin/java \
  --classpath /built/app-classpath.json \
  --compiler-classpath /kotlin/compiler-classpath.json \
  --project /matching/source/checkout \
  --recognition-classes /frozen/startup/probe-classes \
  --fixture /frozen/synthetic/hello.png \
  --forks 3
```

Use Linux JBR 25 or Zulu 27 with the already verified public model/runtime dependencies.
The recognition classes are compiled from `benchmarks/startup/StartupRecognitionProbe.kt`.
The runner freezes sources, probes, compiler/runtime artifacts and fixture bytes, isolates
each process's data/home paths, enforces a timeout, and records all commands and raw hashes.
Do not run builds, GUI benchmarks or another profiling job concurrently with these captures.

NMT describes JVM-tracked categories; third-party native allocation can be absent from it.
RSS is sampled and its phase snapshots are nearby observations, not exact simultaneous
measurements. JFR/NMT/sampling perturb execution equally across modes. Heap-used reduction
does not imply committed-heap or RSS reduction.

This is a component experiment after resource disposal. It does not measure ribbon/input
latency, an engine retained by an open workspace, import/PDF workflows, a sustained-activity
detector, cooldown logic, native Wayland interaction or Windows behavior. A production policy
requires those additional workloads and regression tests. No production GC flags or policy
are changed by these tools.
