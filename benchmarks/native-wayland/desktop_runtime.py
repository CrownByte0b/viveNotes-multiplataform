#!/usr/bin/env python3
"""Native Wayland adapter for the frozen runtime workload; requires JBR 25 method tracing."""
import importlib.util
from pathlib import Path

HERE = Path(__file__).resolve().parent

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

runtime = load("_frozen_runtime", HERE.parent / "runtime/desktop_runtime.py")
native = load("_native_analysis", HERE / "analyze.py")
# The launcher helpers retain their original module globals; only explicit extension points change.
for name in dir(runtime):
    if not name.startswith("_") and name not in ("HERE", "Path", "capture_owned", "provenance"):
        globals()[name] = getattr(runtime, name)

BASE_PROVENANCE = runtime.provenance
runtime.PROBE_SOURCES = (runtime.SOURCE, HERE / "RuntimeRenderProbe.kt", HERE / "NativeTimingSmoke.kt")

def provenance(args, classpath):
    result = BASE_PROVENANCE(args, classpath)
    result["toolFiles"] += [{"path": "native-wayland/" + name, "sha256": sha256(HERE / name)} for name in
                           ("desktop_runtime.py", "analyze.py", "test_analyze.py", "RuntimeRenderProbe.kt", "NativeTimingSmoke.kt", "owned_display.py")]
    result["observer"] = "JFR MethodTrace SwingGraphics/1"
    return result

def capture_owned(args, output, classpath, initial, manifest):
    require(args.backend == "native", "This adapter requires --backend native")
    manifest["measurement"] = "JFR elapsed SwingGraphics method invocations; asynchronous live-count waits; no return-status, receiver identity or presentation measurement"
    work = output / "isolated-profile"
    work.mkdir(mode=0o700)
    owner = str(uuid.uuid4())
    (work / ".vive-runtime-probe-owner").write_text(owner + "\n")
    env = os.environ.copy()
    for variable in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(variable, None)
    env.update({"XDG_DATA_HOME": str(work / "xdg-data"), "XDG_CONFIG_HOME": str(work / "xdg-config"),
                "XDG_CACHE_HOME": str(work / "xdg-cache")})
    if args.backend == "x11":
        env.pop("WAYLAND_DISPLAY", None)
        env["XDG_SESSION_TYPE"] = "x11"
        if args.display:
            env["DISPLAY"] = args.display
    classes = compile_probe(args, output, classpath, env)
    compiled = compiled_probe(classes)
    check_frozen(args, classpath, initial, classes, compiled)
    manifest.update(probeClasses={"path": str(classes), "files": compiled},
                    javaVersion=subprocess.check_output([str(args.java), "-version"], stderr=subprocess.STDOUT, text=True, env=env).strip())
    check_frozen(args, classpath, initial, classes, compiled)
    write_json(output / "environment.json", manifest)
    command = [str(args.java), f"-Xmx{args.heap}", *args.jvm_arg,
        "-Dvivenotes.profile=dev", "-Duser.language=en", "-Duser.country=US",
        f"-Duser.home={work / 'home'}", f"-Djava.util.prefs.userRoot={work / 'preferences'}",
        "-cp", os.pathsep.join([str(classes), *classpath]), MAIN]
    options = ["--work-dir", str(work), "--owner-token", owner, "--backend", args.backend,
        "--strokes", str(args.strokes), "--nmt-handshake", str(bool(args.jcmd)).lower()]
    fixture_options = ["--ink-count", str(4 if args.contract_only else args.ink_count),
        "--text-outlines", str(2 if args.contract_only else args.text_outlines),
        "--characters", str(64 if args.contract_only else args.characters)]

    def child(role, cmd, timeout=args.timeout_seconds):
        manifest.setdefault("commands", []).append({"role": role, "command": cmd})
        write_json(output / "environment.json", manifest)
        print(f"runtime probe: {role}", flush=True)
        run_logged(cmd, output / f"{role}-log.txt", work, env, timeout)
        check_frozen(args, classpath, initial, classes, compiled)

    for role, key, count in (("probe-contract", "textValidationCases", 6), ("render-contract", "checks", 3)):
        child(role, [*command, "--mode", role, *options])
        outcome = read_json(work / (role + ".json"))
        require(outcome.get("passed") is True and outcome.get(key) == count, "Probe-only deterministic contracts failed")
        if role == "probe-contract":
            require(outcome.get("pollingCases") == 2, "Bounded control-republication polling contracts failed")
    if not args.contract_only:
        child("native-input-preflight", [*command[:-1], "com.vivenotes.runtimeprobe.NativeInputPreflight"])
    child("seed", [*command, "--mode", "seed", *options, *fixture_options])
    fixture = validate_fixture(work, args, args.contract_only)
    if args.contract_only:
        verify_options = ["--work-dir", str(work), "--owner-token", owner, "--backend", args.backend, "--strokes", "0"]
        child("verify", [*command, "--mode", "verify", *verify_options])
        verification = validate_verification(work, fixture, 0)
        write_json(output / "summary.json", {"schema": SCHEMA, "contractOnly": True, "verified": True,
            "fixtureSha256": fixture["fixtureSha256"], "verification": verification})
        return output
    app_command = [command[0], "-XX:NativeMemoryTracking=summary",
        f"-XX:StartFlightRecording=filename={output / 'runtime.jfr'},settings=profile,dumponexit=true",
        *command[1:], "--mode", "app", *options]
    manifest["commands"].append({"role": "app", "command": app_command})
    write_json(output / "environment.json", manifest)
    samples, observed_nmt, successful_nmt = [], set(), set()
    unavailable_samples = 0
    launch_epoch, launch = time.time_ns(), time.monotonic()
    with (output / "app-log.txt").open("w") as log, (output / "process.jsonl").open("w") as sample_log:
        process = subprocess.Popen(app_command, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            while process.poll() is None:
                require(time.monotonic() - launch <= args.timeout_seconds, "Owned app exceeded timeout; diagnostic incomplete")
                try:
                    sample = windows_process_sample(process.pid) if os.name == "nt" else linux_process_sample(process.pid)
                    sample.update(elapsedSeconds=time.monotonic() - launch, epochNanos=time.time_ns())
                    unavailable_samples += int(not record_process_sample(sample, samples, sample_log))
                except (OSError, ValueError, KeyError) as failure:
                    unavailable_samples += 1
                    sample_log.write(json.dumps({"elapsedSeconds": time.monotonic() - launch, "unavailable": str(failure)}) + "\n")
                    sample_log.flush()
                if args.jcmd:
                    for event in read_events(work / "events.jsonl"):
                        name = {"nmt_initial_request": "idle_initial", "nmt_final_request": "idle_final"}.get(event.get("phase"))
                        if name and name not in observed_nmt:
                            observed_nmt.add(name)
                            status = "unavailable"
                            try:
                                run_logged([str(args.jcmd), str(process.pid), "VM.native_memory", "summary", "scale=KB"], output / f"nmt-{name}.txt", work, env, 8)
                                require("Native Memory Tracking" in (output / f"nmt-{name}.txt").read_text(), "NMT attach did not return a memory report")
                                successful_nmt.add(name)
                                status = "captured"
                            except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as failure:
                                (output / f"nmt-{name}-unavailable.txt").write_text(str(failure) + "\n")
                            finally:
                                check_frozen(args, classpath, initial, classes, compiled)
                                write_json(work / f"nmt-{name}.done", {"status": status})
                time.sleep(args.sample_seconds)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    exited = time.monotonic()
    require(process.returncode == 0, f"Owned app failed ({process.returncode}); inspect retained app log")
    check_frozen(args, classpath, initial, classes, compiled)
    events = read_events(work / "events.jsonl", final=True)
    ends = validate_events(events, bool(args.jcmd), args.strokes)
    validate_samples(samples)
    runtime = read_json(work / "runtime.json")
    require(runtime.get("userDir") == str(work) and runtime.get("classPath") == os.pathsep.join([str(classes), *classpath]),
            "Actual JVM working directory/classpath differs from isolated launch")
    require(runtime.get("schema") == PROBE_SCHEMA and runtime.get("profile") == "dev" and
            runtime.get("requestedBackend") == args.backend and isinstance(runtime.get("vmArgs"), list), "Actual runtime identity is missing")
    required_toolkit = {"x11": "XToolkit", "native": "WLToolkit", "windows": "WToolkit"}[args.backend]
    require(required_toolkit in runtime.get("awtToolkit", ""), "Actual toolkit differs from requested backend")
    rendering = read_json(work / "rendering.json")
    child("native-jfr", [str(args.jfr_tool), "print", "--json", "--events", native.EVENTS,
                         str(work / "native-render.jfr")], timeout=30)
    native_analysis = native.analyze(read_json(output / "native-jfr-log.txt"), rendering, PHASES)
    write_json(output / "native-analysis.json", native_analysis)
    validate_jfr(output / "runtime.jfr")
    child("jfr-phases", [str(args.jfr_tool), "print", "--json", "--events", "com.vivenotes.RuntimeProbePhase", str(output / "runtime.jfr")], timeout=30)
    read_jfr_phases(output / "jfr-phases-log.txt")
    child("verify", [*command, "--mode", "verify", *options])
    require(read_json(work / "fixture.json") == fixture, "Seed fixture metadata changed during application run")
    verification = validate_verification(work, fixture, args.strokes)
    ready = next(event for event in events if event["phase"] == "workspace_ready")
    closing = next(event for event in events if event["phase"] == "window_close_request")
    shutdown = events[-1]
    summary = {"schema": SCHEMA, "contractOnly": False, "verified": True, "processExitCode": process.returncode,
        "fixtureSha256": fixture["fixtureSha256"], "verification": verification, "rendering": rendering,
        "jvmMainToWorkspaceNanos": ready["elapsedNanos"],
        "launchToWorkspaceEpochMillis": ready["epochMillis"] - launch_epoch // 1_000_000,
        "processLifetimeSeconds": exited - launch,
        "phaseNanos": {name: event["elapsedNanos"] for name, event in ends.items()},
        "phaseEvidence": [{"phase": name, "evidence": event["evidence"]} for name, event in ends.items()],
        "closeRequestToShutdownHookNanos": shutdown["nanoTime"] - closing["nanoTime"],
        "sampledPeakRssBytes": max(sample["rssBytes"] for sample in samples),
        "highWaterRssBytes": max(sample["highWaterRssBytes"] for sample in samples),
        "lastSampledProcessCpuSeconds": samples[-1]["processCpuSeconds"],
        "validProcessSamples": len(samples), "unavailableProcessSamples": unavailable_samples,
        "shutdownHookProcessCpuNanos": shutdown["processCpuNanos"],
        "nmtSnapshots": sorted(successful_nmt), "nmtAttemptedAt": sorted(observed_nmt),
        "limits": ["Method invocations have no return-status or receiver identity and are not GPU/display presentation, input latency or FPS.",
                   "Input pacing, live JFR delivery batches, settle waits and accessibility traversal contribute to scripted phases.",
                   "JFR profile, render observer and NMT attach perturb execution; comparison requires identical instrumentation.",
                   "Normal startup maintenance and warm filesystem/native/OS caches remain part of this diagnostic.",
                   "Process samples can miss instantaneous peaks and final CPU; shutdown-hook CPU is not final process CPU."]}
    write_json(output / "summary.json", summary)
    return output


runtime.provenance = provenance
runtime.capture_owned = capture_owned

if __name__ == "__main__":
    runtime.main()
