#!/usr/bin/env python3
"""Run one isolated, reproducible pack benchmark. No dependency on archived experiments."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    parser.add_argument('--fixture', required=True, type=Path)
    parser.add_argument('--pack', type=Path, default=ROOT / 'shader-loader/build/shaderpacks/Solstice-0.1.0.zip')
    parser.add_argument('--output', type=Path, default=ROOT / '.research/solstice')
    parser.add_argument('--warmup', type=int, default=5)
    parser.add_argument('--seconds', type=int, default=10)
    parser.add_argument('--action', choices=['static', 'forestWalk', 'cavernBreak'], default='static')
    parser.add_argument('--time', type=int, default=4000)
    parser.add_argument('--position', type=float, nargs=3, default=[219.5, 68, 216.5], metavar=('X', 'Y', 'Z'))
    parser.add_argument('--look', type=float, nargs=2, default=[0, 5], metavar=('YAW', 'PITCH'))
    parser.add_argument('--offscreen', action='store_true', help='Diagnostic throughput; not visible FPS')
    parser.add_argument('--stages', action='store_true', help='Sample GPU stages; adds profiling overhead')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.label):
        parser.error('Unsafe label')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.glob(args.label + '.*')):
        parser.error('Label already exists; choose a fresh label')
    if not (args.fixture / 'level.dat').is_file():
        parser.error('Fixture must be a closed disposable Minecraft world')
    for pack_id, title, version in [('solstice', 'Solstice', '0.1.0')]:
        if args.pack.resolve() == ROOT / f'shader-loader/build/shaderpacks/{title}-{version}.zip':
            subprocess.run(['./gradlew', '--no-parallel', f':shader-loader:{pack_id}Pack'], cwd=ROOT, check=True)
    command = ['./gradlew', '--no-parallel', ':shader-loader:runPackagedOptimizedBsl']
    settings = {
        'shaderPack': str(args.pack.resolve()), 'benchmarkScene': 'natural',
        'benchmarkFixture': str(args.fixture.resolve()), 'benchmarkOutput': str(output),
        'benchmarkLabel': args.label, 'benchmarkWarmupSeconds': args.warmup,
        'benchmarkMeasureSeconds': args.seconds, 'benchmarkAction': args.action,
        'benchmarkWorldTime': args.time, 'benchmarkWidth': 3456, 'benchmarkHeight': 2168,
        'benchmarkBorderless': 'true', 'benchmarkViewDistance': 16, 'benchmarkSimulationDistance': 12,
        'benchmarkX': args.position[0], 'benchmarkY': args.position[1], 'benchmarkZ': args.position[2],
        'benchmarkYaw': args.look[0], 'benchmarkPitch': args.look[1], 'benchmarkScreenshot': 'true',
        'shaderHardwareShadowComparison': 'true', 'shaderNativeMipmaps': 'true',
        'shaderOptimizeFragmentSpirv': 'true', 'shaderOptimizeVertexSpirv': 'true',
        'shaderTerrainFrameMatrices': 'true', 'shaderCompactTerrainVertices': 'true',
        'shaderLazyTargetClears': 'true', 'shaderDiscardFullscreenLoads': 'true',
        'sceneDrawMetadataCache': 'true', 'sceneAsyncIndex': 'true',
        'sceneDrawWorkCounters': 'true', 'metalIndirectCommandBuffers': 'true',
        'shaderProfiler': 'false', 'sparkProfiler': 'false',
    }
    command += [f'-P{k}={v}' for k, v in settings.items()]
    environment = {k: v for k, v in os.environ.items()
                   if not k.startswith(('MINECRAFT_METAL_', 'MTL_'))}
    diagnostics = {'MTL_DEBUG_LAYER': '0', 'MINECRAFT_METAL_TRACKED_HAZARDS': '1',
                   'MINECRAFT_METAL_CPU_ICB': '1', 'MINECRAFT_METAL_ICB_REUSE': '1',
                   'MINECRAFT_METAL_MATH_MODE': 'safe',
                   'MINECRAFT_METAL_OFFSCREEN_PRESENT': str(int(args.offscreen)),
                   'MINECRAFT_METAL_RENDER_STAGE_TIMINGS': str(int(args.stages))}
    environment.update(diagnostics)
    manifest = {'command': command, 'environmentOverrides': diagnostics,
                'note': 'Short runs and offscreen runs are exploratory, not sustained FPS claims.'}
    (output / 'commands').mkdir(exist_ok=True)
    (output / 'commands' / (args.label + '.json')).write_text(json.dumps(manifest, indent=2) + '\n')
    with (output / (args.label + '.log')).open('x') as log:
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f'Benchmark failed; see {output / (args.label + ".log")}')
    report = json.loads((output / (args.label + '.json')).read_text())
    if (report['framebufferWidth'], report['framebufferHeight']) != (3456, 2168):
        raise SystemExit('Incorrect framebuffer size')
    if report['nativeExecutionDelta']['failedBuffers']:
        raise SystemExit('GPU command failures')
    stats = report.get('diagnosticFrameIntervals', report.get('frameIntervals'))
    print(json.dumps({'label': args.label, 'intervals': stats,
                      'renderCpuMsPerFrame': report['renderThreadCpuTimeMs'] / stats['frames'],
                      'report': str(output / (args.label + '.json'))}, indent=2))


if __name__ == '__main__':
    main()
