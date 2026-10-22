#!/usr/bin/env python3
"""Capture three alternating native viewport pairs with exact source/runtime/work validation."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('frozen_ink_capture', HERE.parent / 'ink/capture.py')
legacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legacy)
PROBE = 'shared/src/jvmTest/kotlin/com/vivenotes/ui/canvas/InkViewportPerformanceScenario.kt'
XML = Path('shared/build/test-results/jvmTest/TEST-com.vivenotes.ui.canvas.InkViewportPerformanceScenario.xml')
NAMES = [f'ink.viewport_{name}_d{density}_{mode}' for density in (1, 2) for mode in ('fit', 'sparse')
         for name in ('stationary', 'pan_new', 'pan_return', 'zoom_return', 'fractional_pan')]
COUNTERS = ('requests', 'rasterBuilds', 'cacheHits', 'finishedStrokeDraws', 'allocatedSurfacePixels',
            'updatedPixels', 'pathBuilds', 'peakRetainedPixelBytes')
require = legacy.require
read = legacy.read_json
write = legacy.write_json
sha = legacy.digest


def cases(report):
    require(report.get('schema') == 'vive-viewport-raster/1', 'Wrong viewport schema')
    rows = report.get('cases')
    require(isinstance(rows, list) and [r.get('name') for r in rows] == NAMES, 'All 20 ordered cases required')
    for row in rows:
        require(row.get('units') == 40_000 and row.get('exactFreshRasterPixels') is True, 'Native fixture/pixel checks missing')
        require(row.get('sampleCount') == 7 and row.get('warmupOps') == 8 and row.get('operationsPerSample') == 1,
                'Seven samples and eight one-operation warmups required')
        samples = legacy.numbers(row.get('sampleNanosPerOperation'), 'viewport samples', 7)
        require(math.isclose(row.get('medianNanos', -1), statistics.median(samples), rel_tol=1e-12), 'Median differs from samples')
        require(math.isclose(row.get('p95BatchAverageNanos', -1), max(samples), rel_tol=1e-12), 'p95 differs from samples')
        allocation = legacy.numbers(row.get('sampleAllocatedBytesPerOperation'), 'caller allocations', 7)
        require(math.isclose(row.get('medianAllocatedBytes', -1), statistics.median(allocation), rel_tol=1e-12),
                'Allocation median differs from samples')
        require(all(type(row.get(key)) is int and row[key] >= 0 for key in COUNTERS), 'Invalid work counters')
        require(row['requests'] == 120 and row['rasterBuilds'] + row['cacheHits'] == 120, 'Work counts do not reconcile')
        require(row['updatedPixels'] <= row['allocatedSurfacePixels'], 'Updated pixels exceed allocated surfaces')
        require(row['peakRetainedPixelBytes'] <= 64 * 1024 * 1024, 'Retained pixel budget exceeded')
        require(isinstance(row.get('fixtureSha256'), str) and len(row['fixtureSha256']) == 64, 'Fixture hash missing')
    return rows


def task_identity(runtime, project, report):
    return legacy.task_identity(runtime, project, report)


def compare(output, environment):
    result = []
    anchor = None
    reports = {side: [] for side in ('before', 'after')}
    for fork in range(1, 4):
        for side in reports:
            file = output / side / f'fork-{fork}.json'
            report = read(file)
            rows = cases(report)
            identity = task_identity(read(file.with_name(f'fork-{fork}-runtime.json')),
                                     Path(environment['variants'][side]['project']), file)
            current = {'task': identity, **{key: report[key] for key in ('runtime', 'vendor', 'os', 'nativeSha256', 'maxHeapBytes')}}
            if anchor is None:
                anchor = current
            require(current == anchor, 'JVM/native/external classpath identity differs')
            reports[side].append(rows)
    for index, name in enumerate(NAMES):
        pair = {side: [fork[index] for fork in forks] for side, forks in reports.items()}
        require(len({row['fixtureSha256'] for rows in pair.values() for row in rows}) == 1, 'Fixture changed between captures')
        values = {}
        for side, rows in pair.items():
            values[side] = dict(forkMedianNanos=[r['medianNanos'] for r in rows],
                medianNanos=statistics.median(r['medianNanos'] for r in rows),
                forkAllocatedBytes=[r['medianAllocatedBytes'] for r in rows],
                medianAllocatedBytes=statistics.median(r['medianAllocatedBytes'] for r in rows),
                counters={key: [r[key] for r in rows] for key in COUNTERS})
        before, after = values['before']['medianNanos'], values['after']['medianNanos']
        result.append(dict(name=name, fixtureSha256=pair['before'][0]['fixtureSha256'], **values,
                           changePercent=100 * (after / before - 1) if before else None))
    return dict(schema='vive-viewport-comparison/1', forks=3, aggregation='median of three fork medians; no pooled p95',
                runtimeIdentity=anchor, cases=result)


def tools():
    return legacy.hashes(ROOT, [str(p.relative_to(ROOT)) for p in
        (HERE / 'capture.py', HERE / 'test_capture.py', HERE / 'USAGE.md', HERE.parent / 'ink/capture.py',
         HERE.parent / 'compare_ink.py')])


def capture(before, after, output, baseline_commit):
    before, after = before.resolve(), after.resolve()
    require(before != after, 'Use separate source copies')
    output = legacy.new_output(output, before, after)
    require(legacy.git(before, 'rev-parse', 'HEAD') == legacy.git(before, 'rev-parse', f'{baseline_commit}^{{commit}}'),
            'Baseline commit differs')
    expected_status = '?? ' + PROBE
    require(legacy.git(before, 'status', '--porcelain', '--untracked-files=all') == expected_status,
            'Baseline must contain only the new untracked probe overlay')
    require(sha(before / PROBE) == sha(after / PROBE) == sha(ROOT / PROBE), 'Probe bytes differ')
    variants = {side: dict(project=str(project), commit=legacy.git(project, 'rev-parse', 'HEAD'),
        workingTree=legacy.git(project, 'status', '--short'), sourceFiles=legacy.source_snapshot(project))
        for side, project in (('before', before), ('after', after))}
    schedule = [dict(fork=fork, side=side) for fork in range(1, 4)
                for side in (('before', 'after') if fork % 2 else ('after', 'before'))]
    environment = dict(schema='vive-viewport-environment/1', complete=False,
        startedAt=datetime.now(timezone.utc).isoformat(), variants=variants, toolFiles=tools(), schedule=schedule,
        baselineOverlay=PROBE, logicalCpus=os.cpu_count(),
        affinity=sorted(os.sched_getaffinity(0)) if hasattr(os, 'sched_getaffinity') else None)
    output.mkdir(parents=True)
    for side in variants:
        (output / side).mkdir()
    init = output / 'capture.init.gradle'
    script = legacy.init_script()
    require(script.count("['performanceBaseline', 'inkPhase2Scenario']") == 1, 'Frozen init template changed')
    script = script.replace("['performanceBaseline', 'inkPhase2Scenario']", "['jvmTest']")
    script = script.replace('outputs.upToDateWhen { false }', "outputs.upToDateWhen { false }\n            maxHeapSize = '4g'")
    init.write_text(script)
    write(output / 'environment.json', environment)
    execution = []
    for row in schedule:
        side, fork = row['side'], row['fork']
        project = before if side == 'before' else after
        report = output / side / f'fork-{fork}.json'
        runtime = report.with_name(f'fork-{fork}-runtime.json')
        env = dict(os.environ, XDG_SESSION_TYPE='x11', PYTHONDONTWRITEBYTECODE='1',
                   VIVE_VIEWPORT_BENCHMARK='1', VIVE_VIEWPORT_REPORT=str(report))
        env.pop('WAYLAND_DISPLAY', None)
        command = ['xvfb-run', '-a', '-s', '-screen 0 1920x1200x24 -nolisten tcp', './gradlew', ':shared:jvmTest',
            '--tests', '*InkViewportPerformanceScenario', '--offline', '--no-configuration-cache', '--max-workers=1',
            '--console=plain', '-I', str(init), f'-PpairedInkRuntimeOutput={runtime}']
        print(f'START {side} fork {fork}', flush=True)
        execution.append(dict(**row, command=command, status='running'))
        write(output / 'execution.json', dict(runs=execution))
        with report.with_name(f'fork-{fork}.log').open('w') as log:
            subprocess.run(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        xml = ET.parse(project / XML).getroot()
        require(int(xml.attrib.get('tests', 0)) == 1 and all(int(xml.attrib.get(k, 0)) == 0
                for k in ('failures', 'errors', 'skipped')), 'Probe did not execute')
        shutil.copyfile(project / XML, report.with_suffix('.xml'))
        cases(read(report)); require(runtime.is_file(), 'Runtime manifest missing')
        execution[-1]['status'] = 'validated'
        write(output / 'execution.json', dict(runs=execution))
        print(f'PASS {side} fork {fork}', flush=True)
    require(tools() == environment['toolFiles'], 'Capture tools changed')
    for side, project in (('before', before), ('after', after)):
        require(legacy.source_snapshot(project) == variants[side]['sourceFiles'], 'Source changed during capture')
    comparison = compare(output, environment)
    write(output / 'comparison.json', comparison)
    environment['rawFiles'] = [dict(path=p.relative_to(output).as_posix(), sha256=sha(p))
        for p in sorted(output.rglob('*')) if p.is_file() and p.name != 'environment.json']
    environment['complete'] = True
    environment['completedAt'] = datetime.now(timezone.utc).isoformat()
    write(output / 'environment.json', environment)
    return comparison


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path)
    parser.add_argument('--after', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--baseline-commit', default='5cbf74f')
    parser.add_argument('--verify', type=Path)
    args = parser.parse_args()
    if args.verify:
        require(not any((args.before, args.after, args.output)), 'Use verification or capture')
        environment = read(args.verify / 'environment.json')
        require(environment.get('complete') is True, 'Incomplete capture')
        for file in environment['rawFiles']:
            require(sha(args.verify / file['path']) == file['sha256'], 'Raw evidence changed')
        require(compare(args.verify, environment) == read(args.verify / 'comparison.json'), 'Comparison differs')
        print('All raw hashes, runtimes, fixtures, counters and comparisons verified')
    else:
        require(all((args.before, args.after, args.output)), 'Supply before, after and new output')
        capture(args.before, args.after, args.output, args.baseline_commit)


if __name__ == '__main__':
    main()
