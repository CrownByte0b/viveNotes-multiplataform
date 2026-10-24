# Idle GC investigation — 2026-10-24

Keep production GC behavior unchanged. These component experiments show a memory benefit on
JBR 25 and no RSS benefit on Zulu 27. They do not establish an application inactivity policy or
its effect on UI responsiveness.

Both installed Linux runtimes select G1. Periodic collection is disabled by default, and
explicit collection is enabled with concurrent explicit collection disabled. Verified flags
also differ: JBR reports `GCTimeRatio=12`, free-heap ratios 40/70 and an approximately 488 MiB
ergonomic initial heap; Zulu reports 24, 0/100 and an 8 MiB initial heap. No runtime/dependency
was upgraded, and these observations must not be generalized to all JDK builds/platforms.

The experiment ran three fresh processes per mode on each runtime: **24 successful processes,
48 real OCR workloads**, with exact fixture, text and confidence parity. Each workload contains
one first inference and three warm repeats (192 inferences total); the next-workload column
includes engine reconstruction, all four inferences and disposal. Modes ran in forward
order for forks 1/3 and reverse order for fork 2, serially without concurrent builds/profiling.
The first workload explicitly closed the recognition engine, image and model store. Twelve
seconds without recognition work followed; explicit modes requested collection once at five
seconds. Periodic mode used a five-second interval with its load gate disabled. Each process
then reconstructed the engine and repeated OCR. All processes used `-Xmx2g`, no `-Xms`, JFR
profile, NMT summary and the same probe/classpath. This is a diagnostic interval, not a proposed
production setting.

Heap, RSS and next-work values below are medians of three processes. Pause is the largest
individual stop-the-world pause observed during idle across those processes.

| Runtime / mode | Used heap MiB | Committed heap MiB | RSS MiB | Max idle pause ms | Next workload ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| JBR 25 normal | 64.42 | 491 | 342.62 | none observed | 261.51 |
| JBR 25 periodic | 11.94 | 44 | 309.94 | 1.81 | 251.97 |
| JBR 25 explicit | 10.38 | 50 | 308.56 | 8.08 | 255.65 |
| JBR 25 explicit concurrent | 11.90 | 44 | 304.08 | 1.70 | 251.10 |
| Zulu 27 normal | 61.75 | 72 | 306.32 | none observed | 260.27 |
| Zulu 27 periodic | 8.52 | 72 | 310.04 | 1.85 | 243.68 |
| Zulu 27 explicit | 7.52 | 72 | 309.94 | 8.58 | 247.12 |
| Zulu 27 explicit concurrent | 8.74 | 72 | 310.80 | 2.00 | 243.71 |

JBR periodic collection lowered median post-idle RSS by 32.68 MiB versus normal collection;
its median within-process RSS change during idle was −51.44 MiB, versus +2.00 MiB for normal.
The much larger committed-heap change is not equivalent to physical memory released. Zulu's
used-heap reduction did not reduce committed heap, and post-idle RSS was slightly higher in
every intervention mode. Preserve that negative result. NMT categories and every individual
run, including next-work ranges, are in `results.json`.

The largest direct-GC pause was 8.58 ms; running the request away from a UI thread does not
prevent that stop-the-world pause. Concurrent requests also have pauses, and the caller spent
roughly 5–6 ms waiting in the median concurrent request. No next-work median regression appeared
here, but three descriptive trials and a headless operation do not demonstrate lower input
latency. No statistical significance or leak conclusion is claimed.

The four-run Zulu pilot is retained separately and excluded from this table. All 24 final raw
manifests/hashes, required periodic/explicit GC causes and absence of reported JFR data loss
validate. Seven Python contracts cover scheduling, policy flags, snapshot ordering, heap/output
checks, required observed collections and event loss. The shared/desktop JVM suites, lint and
assembly also pass: 2,241 tests passed and 14 skipped. All 333 production Kotlin sources match
the retained compiled checkout; original benchmarks and production sources remain unchanged.

Still required before implementing a policy: import/PDF workloads, recognition with an engine
retained by an open workspace, real UI wake-up/ribbon latency, Windows runtime measurements,
and deterministic activity/cooldown tests plus platform/Compose coverage. Native Wayland's full
input workload remains blocked separately by its isolated Remote Desktop portal requirement.
G1 periodic collection is based on GC activity/system load, not the application's definition
of user inactivity. A general periodic JVM flag therefore does not implement that policy.

JFR, NMT and sampling perturb execution. NMT excludes some third-party native allocation;
sampled RSS can miss peaks and its boundary observations are within 500 ms of heap snapshots.
Twelve seconds is not a long-session memory study, and ergonomic defaults, native allocators,
filesystem caches and scheduling remain sources of variation. Run conditions and raw hashes
are recorded privately; `verification.json` identifies the retained evidence.

Reference: [Oracle's G1 collection and periodic-collection documentation](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html).
Runtime-specific defaults and all numerical results above come from the installed binaries
and these captures, rather than assumed defaults from documentation.
