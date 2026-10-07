# BSL performance investigation — October 3, 2026

**Preliminary source-build results.** Renderer 0.2.2 / loader 0.1.3 are the proposed patch pair;
build/GPU checks and short packaged-launch checks pass, while full gameplay and comparative
performance signoff remain pending. These results do not describe
an installed release or establish a guaranteed FPS improvement.

A report of 22–31 FPS with BSL at 3456×2168 prompted a larger natural-terrain benchmark. The earlier
1280×720 flat-world measurements in [shader validation](shader-validation.md) do not represent that
workload. This investigation uses the supplied BSL 10.1.8 ZIP with its default shader settings.

## Workload and limits

The host is an Apple M3 Pro on macOS 26.5.2, running Minecraft 26.3 and Fabric Loader 0.19.5 on
Homebrew ARM64 Java 26.0.1. The reported player installation uses Java 25. These development runs
include Fabric API; a production installation need not. The Mac was on battery with Low Power Mode
off, so power and thermal conditions remain possible sources of run-to-run variation.

The benchmark copies the disposable seed-1 natural world into its own game directory. The camera
is stationary in spectator mode at `(151.5, 95, 160.5)`, yaw `-25`, pitch `24`, FOV `70`: an aerial
forest/coast view, not the user's exact ground-level foliage scene. Render distance is 16 chunks,
simulation distance 12, time 4000, clear weather, RGSS texture filtering, four mip levels, fancy
clouds, and classic transparency. No user save or settings are modified.

The initial windowed runs were limited to **3456×2104**, about **3% fewer pixels** than 3456×2168.
The benchmark now supports a borderless window (`benchmarkBorderless=true`), and subsequent action
validation and culling diagnostics obtained the exact **3456×2168** native framebuffer. The benchmark
requires its requested actual framebuffer size throughout measurement. VSync and Metal
API Validation are off, the frame limit is unlimited, and Fabric game-test scheduling is absent.
After initial chunk loading/meshing, the normal game loop warms for 30 seconds and records every
frame interval for 60 seconds. Ordinary chunk updates do not restart warmup. Slow frames and GC
pauses remain in the results; wall intervals include presentation and integrated-server contention.

## Preliminary comparison

Both runs include the blocking pipeline-compilation fix described below. Only the shadow-comparison
strategy differs intentionally; BSL's shadow resolution, sample count, and other quality settings
remain unchanged. There is **one run per strategy**, with 700 versus 702 visible sections, so this
is a preliminary comparison rather than a controlled estimate of repeatable improvement.

| Comparison strategy | Mean FPS | Mean frame ms | Median ms | p95 ms | p99 ms | Longest frame ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| No pack selected | 120.00 | 8.333 | 8.241 | 9.195 | 10.073 | 10.811 |
| Four explicit depth comparisons | 16.92 | 59.106 | 57.407 | 66.756 | 73.171 | 573.339 |
| Hardware filtered depth comparison | 18.47 | 54.132 | 52.090 | 58.661 | 65.473 | 1,065.696 |

The observed mean FPS increased by about **9.2%**, and median frame time decreased by about **9.3%**.
The hardware run still contained a one-second stall. These samples do not establish its cause,
long-session stability, or performance in the user's exact scene.

At the same framebuffer size and distances, the no-pack control averaged **120.00 FPS**, with
**1.664 ms of render-thread CPU time per frame**. Adding BSL is the dominant measured throughput
cost in this scene. The baseline's roughly 8.3 ms intervals may still reflect presentation pacing;
120 FPS is not a claim about the renderer's maximum throughput.

The 1728×1052 diagnostic control averaged **32.54 FPS**, median **28.808 ms**, p95 **35.318 ms**,
p99 **41.831 ms**, with a **1,033.726 ms** longest frame. It retains BSL defaults but renders one
quarter as many pixels. It is **not an acceptable result for the current goal**, which requires
native resolution and default BSL settings. It indicates both pixel-dependent and other costs.
Current evidence is staged in
`.research/shader-performance-20261003`; the release evidence copy and final artifact identities
are also pending.

## GPU evidence

A separate diagnostic run sampled GPU timestamps every 15 frames without waiting for query
completion. Its last 40 resolved samples had a median measured GPU span of **51.04 ms**. Median CPU
time spent inserting markers was **0.208 ms**; ordered GPU marker encoders also add overhead. This
profile locates costs and is kept separate from the unprofiled FPS comparison.

| Sampled GPU interval | Median ms |
| --- | ---: |
| Shadow geometry | 11.92 |
| After shadows through opaque world completion | 8.96 |
| After deferred passes through hand entry | 5.85 |
| `deferred1` | 4.28 |
| `composite` | 3.74 |
| `deferred` | 3.69 |

These are marker intervals, not isolated shader instruction costs. Interval medians need not sum to
the median frame span. The measured GPU work is close to the unprofiled hardware run's median wall
interval, supporting substantial GPU cost in this scene. CPU submission wall time is not CPU execution
time. Benchmark JSON separately records process and render-thread CPU totals.

## Subsequent experiments (not release defaults)

A conservative receiver-volume prototype retains camera-visible receivers, volumetric samples
between the eye and the near plane, and their lightward casters with 64 blocks of padding. It only
changes terrain shadow submission; broad extraction and feature replay remain intact. This padding
was assessed against BSL 10.1.8 defaults and is **not a general contract for arbitrary shader packs**.
The opt-in flag remains `minecraftShaders.shadowReceiverCulling`.

At **3456×2168**, a separate 45-second profiled stationary run submitted **3,328 of 6,399** candidate
shadow sections. Its final 40 GPU samples had median shadow time **6.496 ms**, opaque interval
**8.962 ms**, and total measured span **46.052 ms**. Median marker CPU cost was **0.194 ms**. The run
averaged **20.69 FPS**, median **48.228 ms**, p95 **49.726 ms**, and p99 **50.698 ms**. This is a
diagnostic comparison: it also includes the depth-copy removal and slightly more pixels than the
initial measurements. It does not establish sustained gameplay throughput or 120 FPS.

Focused checks completed during this phase:

- 21,600 conservative caster-volume samples, including off-camera lightward casters, camera
  effects, useful rejection and invalid-input fallback.
- Actual GPU depth merge and four-frame ownership rotation, preserving opaque/hand depth and clears.
- Actual GPU mip-chain comparison for RGBA8, RGBA16F and RG11B10F. The unrestricted native generator
  failed strict NPOT comparisons. The revised prototype batches only exact 2:1 reductions and uses
  the original raster filter for the irregular suffix; all tested full chains pass within one
  destination-format ULP with unchanged base bytes. Other formats retain raster generation.
- 504 actual GPU terrain transform/normal cases, comparing per-frame matrices with the previous
  per-draw expressions across bob, hurt, nausea, rotations, direct/instanced layout and world/shadow.
- Actual GPU indirect-command/loop equivalence with indexed16/32 and nonindexed draws, offsets,
  signed base vertices, instances, zero draws, textures and comparison samplers, blending order,
  state restoration, submission-ring reuse, released resource handles and loop fallback. Direct
  texture arguments are incompatible with ICBs on this driver; the opt-in compiler path uses
  SPIRV-Cross argument buffers and retains fallback for unsupported layouts.

An initial normal-input forest action validation completed over 100 blocks of movement and four
24-block legs at native resolution. Visual inspection showed a beach/forest-edge route, so the
selector was tightened to require forest soil, surrounding forest biome and dense overhead/lateral
canopy. That initial run is retained as validation evidence, not the final representative forest
benchmark. Validation runs take a midpoint screenshot and deliberately do not publish comparable
FPS.

The tightened dense-forest validation completed **96.24 blocks of normal input movement and four
legs** with the experimental culling, frame matrices, mip generation, unused-mip proof and ICB paths
requested together. Its natural route begins at `(219.5, 68, 216.5)` and follows 24 blocks through
dense canopy, including a two-block elevation change. The selector counted 478 nearby leaf samples
and canopy in 39 of 63 columns; begin/midpoint images were inspected.

The natural-cavern validation completed **nine server-confirmed stone breaks**, reaching crack stage
9 with an ordinary wooden pickaxe. The unedited cavern has a 10-block ceiling and 24-block clear
view. It starts at `(143.5, 32, 160.5)` and returns to the wide cavern view after mining. An initial
failure was traced to the test aiming at an ambiguous block edge; aiming at a verified face interior
fixed the test. These action runs passed at native resolution, but their screenshot readbacks make
their intervals unsuitable for comparative FPS claims.

A separate, unprofiled 60-second dense-forest control measured **21.56 FPS**, median **46.046 ms**,
p95 **48.971 ms**, p99 **54.439 ms**, and maximum **95.929 ms**. It completed six walking legs with
native resolution and default BSL options. Hardware shadow comparison was enabled; all five
experimental flags above were disabled.

The matching combined run completed **161.67 blocks and six legs**, averaging **24.86 FPS**, median
**40.088 ms**, p95 **42.053 ms**, p99 **43.085 ms**, and maximum **93.056 ms**. Actual native counters
recorded **25,235 ICB batches over 1,492 frames**. This pair shows about **15.3% higher average FPS**;
repeat runs and isolated comparisons are still needed. Render-thread CPU cost fell from roughly
16.88 to 11.65 ms per frame. The 120 FPS objective remains unmet.

Additional focused checks passed: per-binding state preservation across direct and indirect draws,
pipeline layout changes, texture/sampler updates, and native resource retirement before submission.
The unused-mip analysis proves only `composite6`'s colortex1 rebuild unused in default Overworld BSL;
it retains `composite7` because a later frame can read those mip levels.

The current hardware-comparison/ICB/frame-matrix configuration also passed all **583 BSL pipeline
compilations** across three dimensions and **195 vanilla pipeline variants** compiled twice.

### Resource-tracking experiment

`MINECRAFT_METAL_TRACKED_HAZARDS=1` replaces the global encoder fence chain with Metal's tracked
resource dependencies. This remains opt-in. All allocations come from the device with tracked
hazards; indirect argument resources are explicitly declared at their consuming stages. Apple
documents these guarantees for the existing `MTLCommandQueue` API in
[resource synchronization](https://developer.apple.com/documentation/metal/resource-synchronization)
and [useResource](https://developer.apple.com/documentation/metal/mtlrendercommandencoder/useresource(_:usage:stages:)).

The first actual timestamp write inserts a GPU event barrier and permanently restores global
fences for that device. Merely creating vanilla's dormant timer pool does not activate that fallback.
This preserves ordered timestamp markers; profiled results therefore cannot measure the unfenced
configuration. Benchmarks record the actual synchronization mode at both boundaries and reject a
mode change during an FPS measurement. GPU checks passed for ordinary and indirect draws, resource
retirement, mixed bindings, mip chains, depth merges, and the mid-pass timestamp transition.

The preliminary normal-input forest run with tracked hazards and all five earlier experiments
averaged **28.62 FPS** (median 34.839 ms, p95 37.073 ms). Disabling ICBs in that configuration
averaged **29.82 FPS** (median 33.279 ms, p95 35.300 ms). Both completed six walking legs at
3456×2168 with default BSL settings. ICBs reduced CPU submission cost but did not improve total
throughput in these runs; their compute conversion split roughly 17 physical render encoders per
frame. These are single preliminary runs, not a repeatability estimate or a 120 FPS result.

### Fixed-world image controls

The original natural fixture contained partially generated distant chunks. Two independent launches
finished those chunks differently, producing different distant trees and stone in otherwise matched
images. Region data confirmed that these were world-content differences, so those images cannot
isolate rendering changes.

Subsequent forest comparisons use a byte-verified copy of a completed save, with all 987 chunks in
the union of the route's 16-chunk view masks saved at full generation status. A separate manifest
records file hashes and provenance. The canonical save keeps normal game rules; each run copies it
into a disposable directory. Only image-validation runs freeze world ticks and shader clocks, reset
animated textures and temporal history, settle 128 rendered frames, and capture eight consecutive
frames. Those runs deliberately produce no comparable FPS statistics.

Two independent fixed-world controls matched **pixel for pixel across all eight native-resolution
frames**. Tracked hazards alone also matched the control exactly. A further configuration combining
tracked hazards, audited shadow receiver culling, the unused-mip proof, and early terrain alpha
demotion matched all eight frames exactly. This evidence applies to the tested scene and sequence,
not every possible gameplay state.

The larger experimental combination including precomputed terrain matrices, native mip generation
and ICBs showed small numerical differences (mean absolute RGB error about 0.041 on a 0–255 scale,
with larger isolated edge differences). The responsible changes still need isolation; these results
are not labeled pixel-equivalent.

Early alpha demotion remains opt-in and is limited to the audited default BSL terrain program.
A conservative source proof moves the existing alpha decision immediately after the initial color
sample, while retaining the final decision and derivative-helper behavior. CPU tests and GPU tests
cover all alpha comparators, transparent/opaque/mixed quads, derivatives, implicit mip selection,
color, and depth. Performance benefit has not yet been established.

## Source changes and focused checks

The renderer now exposes a LEQUAL comparison sampler. When available, the loader preserves
`sampler2DShadow` and uses hardware filtering in place of four explicit depth fetches/comparisons.
Ordinary depth reads keep their original sampler. Compile strategy and sampler bindings are chosen
together for each pack graph; renderers without the capability retain emulation.

GPU readback passed **1,764** native/manual D32 comparisons, covering equality, fractional positions,
clamped edges, out-of-range coordinates/references, sampler parameters, and unchanged ordinary depth
reads. Maximum observed filtering difference was **0.0020653605** on the 0–1 comparison result.
Hardware interpolation is not claimed to be bit-identical to the explicit calculation.

The larger scene also exposed worker starvation: the render thread joined a pipeline compilation
queued behind chunk workers waiting for render-thread upload progress. For an already-blocking
pipeline-cache miss, the loader now compiles on the calling render thread when a pack is selected.
Asynchronous preloading and other callers keep their existing executor. A focused CPU test passed
with the shared workers deliberately occupied, including caller scope and error propagation.
The profiler's CPU tests passed asynchronous query-ring, duration, overflow, and abort checks.

## Audited shadow-culling eligibility

Receiver culling remains **off by default**. Even an explicit
`minecraftShaders.shadowReceiverCulling=true` now requires the audited BSL 10.1.8 snapshot, effective
options equal to that pack's defaults, the exact `minecraft:overworld` dimension and `world0` program
directory, and the audited Minecraft 26.3 preprocessing environment. Modified packs, other settings,
End/Nether/custom dimensions, and added or changed capability macros retain the original submission
list. Culling diagnostics report the eligibility reason separately from whether valid frame matrices
allowed culling to be applied. There is no force-unsupported override.

Identity comes from the immutable in-memory `ShaderPack` snapshot used for compilation, never its
filename or a second read from disk. SHA-256 is calculated once at load over the UTF-8 domain
`MinecraftShaderLoader-PackSnapshot-v1` followed by one zero byte, a four-byte big-endian file count,
and each normalized shader-relative path in Java string sort order. Each entry contains its four-byte
UTF-8 path length, path bytes, eight-byte content length, and raw file bytes. ZIP compression, entry
order, timestamps and enclosing folder names do not affect identity. All captured files participate,
including properties and textures. The supplied snapshot contains 292 files and 1,516,511 content
bytes; its fingerprint is:

```text
fb4652d2b9bf7a142e56f6cad4046bd18115e2b5d4d61f534b2e711c309f925c
```

The receiver audit used only that user-supplied pack and our renderer/game APIs. No competing loader
implementation was consulted. In the supplied pack:

- `lib/lighting/forwardLighting.glsl:22` queries shadows at the shaded visible position.
  `lib/lighting/shadows.glsl:135` applies its filter there. Default `SHADOW_BIAS=0` and disabled
  `HALF_LAMBERT` mean the world-normal offset is inactive. The additional detailed SSS path at
  `forwardLighting.glsl:60` requires `ADVANCED_MATERIALS`, which is disabled by default.
- `lib/atmospherics/lightShafts.glsl:247` samples the same camera rays, up to its nominal 128-block
  limit. Including the eye and the full frustum covers the actual reconstructed positions. Its
  small opposite-light depth bias corresponds to less than 0.13 blocks at the default projection.
- `lib/reflections/raytrace.glsl:50` searches existing screen depth, and the reflection programs
  sample already rendered color. They do not query shadows at off-screen reflection points.
  Procedural cloud illumination also adds no shadow-map receiver region.
- `lib/lighting/shadows.glsl:198` has a maximum basic-SSS filter offset of 0.00175 UV; callers clamp
  `NoL` to 0–1. Linear shadow comparison can touch texel centers almost one texel away per axis.
  **The earlier half-texel audit was insufficient**: using the global maximum offset at raw radius
  `sqrt(2)` gives 25.060 blocks, not 21.33. The unchanged 64-block padding exceeded either value.
  A radius-aware bound is tighter: the SSS blur multiplier is exactly one for raw radius `r >= .3`,
  giving offset .0007; at `r <= .3`, use .00175. With distance `D=256`, map size `N=2048`, bias
  `a=.9`, and `c=.1`, radial distortion is `f(r)=r/(a*r+c)` and its inverse is
  `g(s)=c*s/(1-a*s)`. A conservative world-space footprint is
  `D * (g(f(r) + 2*(offset + sqrt(2)/N)) - r)`. Both radial inverse gain and this bound increase
  with radius: the inner region is below 1.739 blocks; the outer region is below **13.895 blocks**.
  Ordinary PCF (`offset=1/N`) is below 11.716 blocks. Positive surface depth bias moves toward
  the light and is covered by the unbounded lightward sweep.
- `lib/util/jitter.glsl:7` has default TAA offsets no larger than 0.875; reconstructing a fragment
  removes at most 0.4375 pixels. Pixel centers therefore stay within the unjittered viewport.
  Default waving in `lib/vertex/waving.glsl:88` moves vertices by less than a block: the largest
  grass noise displacement is below .180 blocks and bending below .413; fire is bounded by .5,
  and lantern rotation below .185. Bob, hurt and nausea are already in the receiver matrices.
- `program/shadow.glsl:263` distorts each vertex before rasterization. Triangle interpolation thus
  needs its own bound; a filter-only proof is incomplete. For vertices `p_i` in raw light-plane
  coordinates and raster weights `w_i`, let `alpha_i` be the normalized weights
  `w_i/(a*length(p_i)+c)`, `pbar=sum(alpha_i*p_i)`, and `rbar=length(pbar)`. Inverting the raster
  point pulls it radially inward from `pbar`. Its displacement is at most
  `(a/(2*c))*sum(alpha_i*length(p_i-pbar)^2)`, at most `a*d^2/(6*c)` for a triangle of raw
  diameter `d`. For terrain vertices inside one 16-block section plus less than one block of
  waving, the world diameter is less than 32 blocks. At `D=256`, the world interpolation
  displacement is consequently at most **6 blocks**. Orthographic shadow projection has constant
  clip W, so these raster weights are ordinary nonnegative barycentric weights.

An experimental `minecraftShaders.tightShadowReceiverPadding=true` uses **32 blocks** only when
receiver culling is requested and the existing pack/options/dimension/environment gate passes;
64 remains the default. `CullingStats.receiverPadding` reports the chosen value. The bounded-terrain
budget is less than `13.895 + 6 + 1 + .128 = 21.023` blocks, leaving nearly 11 blocks for numeric
slack at 32. This is a geometric bound, not proof of image equivalence or of all GPU float error.
The trial still needs rendered comparisons near receiver boundaries, across light directions,
camera effects, waving, water/translucency, and large positive/negative world coordinates.

The section-bound assumption is a preexisting limitation of section-based frustum and receiver
culling. The eligibility gate does **not** pin resource packs or prove that arbitrary custom models
emit vertices inside their section's bounds. Neither 64 nor 32 is a general guarantee for unbounded
custom geometry. Tightening must not be presented as validated support for those models; supporting
them requires actual emitted bounds or a fallback. CPU camera translation tests alone also do not
certify far-coordinate GPU rendering.

These bounds justify a narrow eligibility contract; they do not establish visual equivalence for
unreviewed options or packs. Source files remain user supplied and are not redistributed. Raw SHA-256
identities for the principal audited files are recorded below for reproducibility:

| Shader-relative source | SHA-256 |
| --- | --- |
| `lib/settings.glsl` | `5617a7a13c37373c27e742d2dffb777a1437bae83cf25693451c104915050285` |
| `lib/lighting/shadows.glsl` | `b7cecede848a68f6118cdb1ad1c14dfaae32c7fbcdb82f3a11d14bf9274c064a` |
| `lib/lighting/forwardLighting.glsl` | `21687dd916c69c8bcd557e4b1bde367d7eeb77be01be1efc0b7e0ff20109fae8` |
| `lib/atmospherics/lightShafts.glsl` | `968a34ffcfe1ee2057297188361bc3bd6a00e6355f6ae714281141ec9e9f223c` |
| `lib/reflections/raytrace.glsl` | `7e12a6a6472ea4cbff731e4b1e93698b9e21a391600facfd483c3e0211c5342a` |
| `lib/util/jitter.glsl` | `b1501381ec079dd1f0662de32adcb1338c2b9fdcfb6e15bd59614215791c4306` |
| `lib/vertex/waving.glsl` | `9c630dce1c33d0c6dcd3d215db3dab43181a0c4215e7e93b949830b091f7a27b` |

## Fixed-route measurements retained before the three-mod split

The completed, fixed fixture removes the distant-generation difference in the earlier runs.
These are still individual development-client samples, not an ABBA repeatability study or a new
release claim. All three use native 3456×2168, BSL defaults, RGSS/four mip levels, 16/12 distances,
30 seconds of warmup and 60 seconds measured, with the fixed southward forest route.

| Variant | Mean FPS | Median ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: |
| Tracked hazards, shadow culling, unused-mip removal; early alpha off | 28.94 | 33.823 | 43.390 | 50.267 |
| Same, early alpha on | 29.33 | 33.775 | 37.561 | 42.548 |
| Same, early alpha on plus indirect commands/reuse | 28.77 | 34.677 | 37.636 | 39.473 |

Native mip generation and frame-matrix translation are off in these samples. The alpha-off sample
included a 532 ms stall and walked 145.48 blocks; the other two walked 161.45 blocks. All completed
six legs, but differing movement progress remains a comparison limitation. Do not attribute their
small differences to an optimization without repeats. Indirect-command reuse did not produce a
throughput win here. The approximately 29 FPS level remains far from the 120 FPS objective.

Compact JSON/CSV/log reports retain the labels `fixed-route-alpha-off-v2`, `fixed-route-alpha-on`
and `fixed-route-icb-reuse` under `.research/shader-performance-20261003/`. Earlier rows in this
document describe earlier fixtures/configurations and are not interchangeable with these rows.
The natural-cavern action test confirmed nine genuine server-side breaks; its midpoint screenshot
makes it a correctness test, not a comparative FPS result. A fresh unprofiled cavern timing baseline
belongs in the first implementation milestone.

## Current benchmark recipe

The canonical natural fixture is
`.research/shader-performance-20261003/fixtures/forest-pregenerated`, with its adjacent
`forest-pregenerated.manifest.json`. All recorded file hashes were verified during cleanup. A small
flat fixture is retained at `.research/shader-performance-20261003/fixtures/flat`. These are local,
ignored assets; another checkout must generate and pin its own fixture, not assume matching results.
The original flat-fixture generator is `:shader-loader:runClientGameTest`; the natural generator is
`:shader-loader:runClientNaturalGameTest`. Pregeneration of the full route view is a separate step.

Run from the repository root with absolute fixture/output paths. This recipe preserves the last
fixed-route alpha-on configuration, now with production JARs. It establishes a **new** packaged
baseline rather than making old development results directly comparable:

```sh
MTL_DEBUG_LAYER=0 MINECRAFT_METAL_TRACKED_HAZARDS=1 MINECRAFT_METAL_ICB_REUSE=0 \
MINECRAFT_METAL_MATH_MODE=safe \
./gradlew --no-parallel :shader-loader:runPackagedBsl \
  -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip \
  -PbenchmarkFixture=/absolute/path/to/fixtures/forest-pregenerated \
  -PbenchmarkOutput=/absolute/path/to/results \
  -PbenchmarkScene=natural -PbenchmarkAction=forestWalk -PbenchmarkForestDirection=south \
  -PbenchmarkX=219.5 -PbenchmarkY=68 -PbenchmarkZ=216.5 -PbenchmarkYaw=0 -PbenchmarkPitch=5 \
  -PbenchmarkWidth=3456 -PbenchmarkHeight=2168 -PbenchmarkBorderless=true \
  -PbenchmarkViewDistance=16 -PbenchmarkSimulationDistance=12 \
  -PbenchmarkWarmupSeconds=30 -PbenchmarkMeasureSeconds=60 \
  -PshaderHardwareShadowComparison=true -PshaderShadowReceiverCulling=true \
  -PshaderSkipUnusedMipmaps=true -PshaderEarlyAlphaDemote=true \
  -PshaderNativeMipmaps=false -PshaderTerrainFrameMatrices=false \
  -PshaderOptimizeFragmentSpirv=false -PmetalIndirectCommandBuffers=false \
  -PbenchmarkValidationOnly=false -PbenchmarkVisualCompare=false -PshaderProfiler=false
```

Use `:shader-loader:runPackagedRenderer` for the no-loader baseline with identical world, route and
settings. For the cavern workload use `-PbenchmarkAction=cavernBreak` and replace the viewpoint with
`-PbenchmarkX=143.5 -PbenchmarkY=32 -PbenchmarkZ=160.5 -PbenchmarkYaw=0 -PbenchmarkPitch=0`; remove
`benchmarkForestDirection`. The selector validates natural cavern geometry and records its targets.
Compare those targets and server-confirmed breaks across runs.

Development tasks `runClientBenchmarkBsl` and `runClientBenchmarkBaseline` remain useful for diagnosis;
do not mix their results with packaged runs. Every natural run now requires an explicit fixture.
Labels are unique by default; reused labels are rejected. The input manifest and effective settings
are included in results. Profiler runs (`shaderProfiler=true`) emit diagnostic timings, since GPU
markers change synchronization. Image comparisons remain separate from performance measurement.

## Preparation cleanup — October 3, 2026

Removed 2,585,927,099 bytes across 5,810 generated or superseded files: disposable run directories,
obsolete build artifacts and the 26.2 game cache, downloaded source/decompiler material, shader dumps and redundant images.
The local deletion inventory is `build/cleanup-report.json`. Linked historical release evidence,
compact benchmark reports, both canonical fixtures, the eight-frame visual control sequence and
selected forest/cavern action captures remain. Duplicate candidate images were removed after their
comparison reports had been retained; rerunning those comparisons requires new candidate captures.
Development player worlds, the supplied BSL ZIP, launcher configuration and current dependency caches were
preserved. The unregistered fullscreen-clear experiment and its unused tests were removed from
runtime source; validated or still actively testable paths retain their regression coverage.

## Three-mod implementation measurements

The first packaged implementation runs use the pinned natural fixture, native **3456×2168**, BSL
10.1.8 defaults, 30 seconds of warmup and 60 seconds measured. They exclude Fabric API, the stage
profiler, GPU validation and screenshot readbacks. Tracked resource hazards remain active throughout.
Results are in `.research/three-mod-implementation/`; each JSON contains source/JAR/fixture identities.

| Run label | Main difference | FPS | Median ms | p95 ms | p99 ms | Render CPU ms/frame |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `initial-packaged-forest` | Prior two-mod control | 28.84 | 34.363 | 39.718 | 43.450 | 15.90 |
| `compression-packaged-forest` | Same-format texture views permit compression; new passive telemetry | 32.40 | 30.303 | 37.681 | 46.510 | 13.97 |
| `three-mod-cpu-icb-forest` | Spatial provider, lazy clears, CPU ICB fill and command reuse | 31.24 | 31.827 | 35.468 | 37.684 | 13.61 |
| `retained-metadata-forest` | Full-cell classification, draw metadata cache, disjoint command snapshots | 31.05 | 32.148 | 35.975 | 37.355 | 12.97 |

These are single exploratory runs, not isolated repeated A/B evidence. The middle run included a
568 ms stall and traversed 146.55 blocks; the last traversed 163.89 blocks, both completing six legs.
The last configuration therefore does **not** establish an FPS gain over the preceding configuration.
The host was on battery. Native-resolution sustained 120 FPS remains unachieved.

The backend now omits unnecessary `MTLTextureUsagePixelFormatView` for same-format views. Apple
documents that this usage can prevent lossless compression on Apple GPUs. The change preserves
texture formats and resolution; it makes compression eligible rather than guaranteeing that the
driver compresses every allocation. The control environment variable
`MINECRAFT_METAL_TEXTURE_FORMAT_VIEWS=1` restores the old usage.
Sources: [texture optimization](https://developer.apple.com/documentation/metal/optimizing-texture-data),
[pixelFormatView](https://developer.apple.com/documentation/metal/mtltextureusage/pixelformatview).

The `three-mod-cpu-icb-forest` run recorded 14,329 CPU-filled ICB batches over 1,875 frames at 0.319 ms CPU/frame, but
14,366 batches still required GPU command conversion and render-encoder splits. Source inspection
identified a cause: publishing the shadow range discarded the CPU snapshot of the disjoint camera
range in the same append-only argument buffer. Bounded range retention is now implemented.
Spatial queries also performed about 32,390 individual section tests per frame; only 13 index
rebuilds occurred during measurement. Full cell containment and repeated draw metadata are the
scene-side targets, rather than adding threads around the same redundant work.

The subsequent retained-metadata run completed six legs / 161.67 blocks. Its 30,718 ICB lookups
were all covered by authoritative CPU snapshots, and it recorded **zero command-conversion encoder
splits**. Individual section tests fell to about 11,885/frame, with about 20,505 sections/frame
accepted through full-cell containment. These changes removed the intended work, but did not
establish a throughput gain: average FPS remained around 31. Read-only fence telemetry recorded
**17.64 ms/frame** waiting for ordinary submitted GPU fences, alongside **1.58 ms/frame** acquiring
a drawable. These are CPU wall waits, not additional GPU passes or additive GPU critical-path costs.
The host was on battery at 25%, Low Power Mode off, with no recorded thermal/performance warning.

The first cavern run was rejected after only one confirmed break: incidental player displacement
changed the mining viewpoint and obstructed the remaining targets. Its failed report is retained;
it is not a valid performance result. The stationary mining probe now suppresses entity pushes
against its player throughout the action, records that fixture behavior, and rejects displacement.
Normal mob updates continue, and forest movement retains ordinary collisions during measurement.
Beginning screenshots now occur after warmup so they show settled terrain; old beginning captures
could read the previous, empty frame at the first mesh-ready boundary. This readback remains outside
the measured interval.

A separate coverage-program prototype retained translated vertex calculations and alpha rejection, while
privatizing our emitted color outputs so compiler dead-code elimination can remove RGB-only work.
Tests of four supplied BSL terrain variants remove shadow-color/depth sampling and retain atlas
alpha. An actual optimized GPU test compares exact depth for all eight alpha functions, threshold
neighbors, equality, NaN and mixed helper quads. It annotated both vertex variants as invariant
and used equal-depth shading only after strict eligibility checks.
Eighteen actual GPU image/depth comparisons passed, including equal-depth
ordering, perspective, animation, derivatives and implicit mip selection. Full BSL gameplay visual
equivalence remained unproven, and its short timing below did not show a useful gain. The experiment,
its exclusive tests and its benchmark switches were subsequently removed; compact timing evidence
is retained here so the rejected direction is not repeated.

The corrected cavern run, `retained-metadata-cavern-v2`, confirmed all **nine block breaks** and
averaged **34.75 FPS** over 60.00 seconds: median 28.707 ms, p95 29.669 ms, p99 32.160 ms and
11.32 ms render-thread CPU/frame. It supplies a successful cavern timing sample, not a matched
before/after comparison.

### Rapid exploration

While throughput is far below the goal, use five seconds of warmup and ten seconds measured to
reject unpromising directions quickly. Longer walking, mining and image comparisons are reserved
for changes with a substantial initial benefit or a specific correctness risk. These short probes
are marked `performanceComparable=false`; they do not establish sustained gameplay FPS.

The following probes use the same native framebuffer, default BSL settings and fixed forest
viewpoint `(219.5, 68, 216.5)`, yaw 0 / pitch 5, with a stationary spectator camera. They therefore
must not be compared directly to the walking rows above.

| Run label | Main configuration | FPS | Render CPU ms/frame | CPU fence wait ms/frame |
| --- | --- | ---: | ---: | ---: |
| `prepass-quick-on` | Coverage prepass active; frame matrices/native mips off | 32.35 | 11.13 | 19.71 |
| `sparse-matrices-mipmaps-quick` | Sparse extraction, frame matrices/native mips on; prepass off | 34.35 | 8.81 | 20.25 |

The prepass ran in all 701 attempted opaque groups with no fallback; its lack of a large benefit
was not caused by remaining inactive. The second probe uses separate event-maintained candidate
sets for dirty-section processing and block-entity extraction, retaining live vanilla checks and
camera-first ordering. Its scene queries fell to one/frame with about 1,051 individual section
tests/frame. Focused tests pass for dirty reset/rebind, mesh publication/replacement, coalescing,
world isolation, immutable snapshots, ordering and concurrent publication. Drawable acquisition
was about 0.02 ms/frame and no upload-ring acquisition blocked. GPU work is now the priority;
CPU fence waits indicate backpressure and are not work that can simply be deleted.

The next combined exploratory run, `structural-gpu-quick`, measured **35.82 diagnostic FPS**
(median 27.913 ms, p95 28.704 ms) over ten seconds. It combined active shader-stage resource
dependencies, uniform initializer lifting and proven fullscreen attachment-load discard. Native
counters confirm ten discard-eligible passes and fourteen discarded attachment loads per measured
frame. These changes preserve formats, pixel dimensions and BSL options; they did not produce the
large throughput improvement needed for 120 FPS.

That run also sampled vertex/fragment timestamps on existing Metal render-pass descriptors every
fifteenth submission. It retains tracked-resource synchronization, adds no marker encoder, and
marks the result diagnostic. Drawn-pass spans pointed to terrain vertices (~3.3 ms) and fragments
(~3.2 ms), shadow vertices (~5.0 ms) and fragments (~3.0 ms), AO (~5.3 ms fragments), fog/clouds
(~2.6 ms), shafts (~2.2 ms), and sky (~2.5 ms). These spans can overlap; they are neither additive
frame time nor isolated instruction costs. Clear-only passes exposed stale unwritten stage samples;
their timings are invalid and must be excluded from this report. The sampler now excludes encoders
without draws and rejects pairs whose span exceeds the completed command-buffer duration.
Focused GPU tests cover those exclusions and resumed render passes without changing hazard mode.

The initializer compiler transfers only substantial immutable uniform-only expressions into flat
vertex outputs, leaving fragment sampling and effect logic intact. An initial low-cost expression
differed by one float ULP because fragment shaderc optimized division into reciprocal multiplication;
the cost threshold now excludes it. The retained outputs match exactly across 24 synthetic
weather/time/moon inputs in actual GPU readback, including tests built from the supplied BSL pack.
This verifies the transferred values, not pixel equivalence of the complete BSL frame. The option
remains opt-in. Active-resource introspection subsequently lets the loader omit dependencies that
the backend proves unused, with conservative fallback for unknown backends and strict frontend
validation; original binding layouts and explicit mip directives remain intact.

The next ten-second diagnostic (`compact-vertex-quick`) averaged **36.82 FPS** at the same
native resolution and defaults. Actual terrain stride was **48 bytes**, down from 64, with raw
positions/UVs/normals/tangents preserved and material metadata packed losslessly. An immutable
material-range proof gates packing; unsupported IDs retain the full format. Optional vertex
SPIR-V optimization also ran. Render-thread CPU execution averaged **8.89 ms/frame**. Sampled
shadow vertex work was about **4.34 ms**, terrain vertex work **3.05 ms**, and AO fragments
**5.27 ms**. This is another short direction-finding result, not a repeatable paired performance
claim; one frame took 115 ms. No extended rerun was warranted by this modest improvement.

A late-sky experiment was removed after native tests found that moving its raster depth could not
preserve original fragment-depth values exactly. Actual default BSL sky programs consume that
depth for position reconstruction. Keeping an unproven path added complexity for limited headroom.

The guarded sparse projection experiment retains the dense shader and checks the exact uploaded
matrix every frame, including signed zeros and finite values. It exposes eleven known zero entries
without changing sample count or AO algorithms. Actual BSL AO native tests passed 24 depth/camera
fixtures with identical float and R8 outputs, including one dense fallback for an off-axis matrix.
Full-frame performance and the other consuming passes still require separate validation.

The combined sparse-projection/relaxed-math probe (`sparse-relaxed-quick`) reached **49.24 FPS**
(493 frames, 10.013 seconds; median 20.289 ms, p95 20.806 ms). It exercised four sparse fullscreen
passes per frame with zero fallback selections. Sampled fragment time fell to about 3.05 ms for
AO, 1.28 ms for fog/clouds, 1.05 ms for sky and 0.89 ms for TAA; shadow vertices remained about
4.11 ms. The two compiler changes are combined here, so this run does not isolate their individual
contributions. It also selected slightly more shadow sections than the preceding short run.
Native dimensions, sampling options and BSL settings stayed unchanged; relaxed arithmetic needs
its own image validation before acceptance. Sustained 120 FPS remains unachieved.

Prepared-command counters show why selected sections are not draw counts: this scene prepared
about 1,350 camera layer draws / 4.82 million indices and 5,512 shadow layer draws / 11.93 million
indices per frame. These are software submissions, not hardware vertex or fragment invocations.
Counts alone cannot establish savings from occlusion or face grouping.

An earlier math probe crashed before measurement when shadow preparation requested more shared
quad indices after vanilla had already committed its camera-side capacity request. Independent
scene preparation now realizes the cumulative index-buffer request before returning a drawable
view. CPU integration tests cover both draw paths and a short-to-int index transition; native
tests reproduce rejection of the undersized allocation and correct rendering after growth.
The failed startup has no FPS result and is excluded from comparisons.

Eight deterministic forest image pairs compared global Safe and Relaxed math with all other flags
identical, including sparse projection. Around **98.9% of pixels were identical**; average absolute
RGB-channel difference was **0.0053 on the 0–255 scale**. About 0.10% of pixels differed by more
than one channel level, with maxima of 14–28 at a handful of edge pixels. Visual inspection found
no apparent change at the displayed scale. This is one scene, not global equivalence; earlier
safe-mode repeats also showed some nondeterminism, so these differences are not exclusively
attributable to arithmetic. The retained images and exact metrics are under
`.research/three-mod-implementation/math-mode-image-comparison.json`.

Metal Relaxed permits reassociation, reciprocal substitution, disregarding signed zero and
cross-statement contraction, while retaining NaN/Inf support. Both runs already used the same
default fast FP32 intrinsic setting. See [MSL specification §1.6.3](https://developer.apple.com/metal/Metal-Shading-Language-Specification.pdf).
The generic backend remains Safe by default. The scoped candidate limits the requested arithmetic
policy to audited BSL fragment programs and retains Safe vertex calculations and utility shaders.
Its short probe (`scoped-math-quick`) held **49.12 FPS** (492 frames, 10.016 seconds;
median 20.358 ms, p95 20.922 ms). Actual native counters confirmed all 36 eligible compiled
fragment pipelines used Relaxed mode, with the global environment set to Safe. Native tests
verified Safe vertex compilation, explicit-precision fallback, no-depth variants and finite/Inf/NaN
fixtures. Unknown packs, changed options and other dimensions retain the original policy.

The scheduling audit does not support treating 8.31 ms of render-thread CPU execution plus
12.00 ms of buffer-fence waiting as serial CPU then GPU work. Average command-buffer GPU spans
were 33.69 ms while new frames arrived every 20.36 ms: submissions already overlap. There were
no upload-ring blocked acquisitions, and drawable acquisition averaged 0.022 ms/frame. Deeper
buffering alone is therefore not a credible route to the target. Current work focuses on reducing
GPU workload; this diagnostic does not prove full hardware utilization.

A draw-command perturbation (`draw-split4-quick`) split consecutive quad draws without changing
BSL geometry or primitive order. Actual command counts rose from 6,838 to 26,618 per frame
(3.89×), while the short probe fell to **45.25 FPS**, about 1.74 ms slower than the preceding
49.12 FPS sample. Shadow membership differed slightly, so this is not an isolated calibrated cost.
Nevertheless, it does not justify a major merged-index/section-ID redesign: a simple linear
estimate attributes only about 0.60 ms of the original frame to this command-count slope. The
diagnostic and its exclusive tests were removed. Reports retain the actual expansion counts.

Directional voxel-connectivity shadow culling was also rejected before implementation. Even
vanilla solid sections at a clipped shadow-domain boundary may have no emitted light-facing
surface. Voxel occupancy can therefore claim an occluder that the shadow map never rendered;
neither the six-face connectivity bits nor a fixed neighbor halo proves safe rejection. Custom
models/atlas transparency introduce an additional mismatch. Any future occlusion implementation
needs actual emitted-geometry coverage, rather than treating block solidity as that certificate.

### Rejected frame-global vertex evaluator

A short native-resolution/default-BSL probe moved camera/sun expressions out of per-vertex
programs into seven tiny GPU evaluations per frame. Exact GPU checks covered 24 camera/time
cases with zero ULP differences. The forest probe nevertheless returned **48.65 FPS** versus
**49.12 FPS** without the evaluator; terrain vertex timing was effectively unchanged. Shader
instruction reductions did not translate into throughput. The evaluator, runtime integration,
and dedicated tests were removed. Compact evidence remains in
`.research/three-mod-implementation/frame-vertex-values-*`.

Future speculative architectural changes start with a small workload perturbation to measure
an upper bound before implementing a general solution or extensive correctness scaffolding.

### Architectural workload ceilings and hardware activity

An eight-second Metal System Trace of the warmed native/default-BSL forest showed game GPU
channels active for **99.3%** of the 8.48-second resolved interval. Fragment activity covered
80.5%, vertex activity 38.4%, and compute activity 14.6%; these overlap and must not be summed.
This establishes a continuously occupied GPU timeline, not arithmetic efficiency or full ALU
utilization. Detailed GPU counter profiles were rejected as unsupported by the installed
Apple tool/device combination. Compact evidence: `hardware-counters-summary.json` in
`.research/three-mod-implementation`; raw traces were discarded after export.

Two temporary workload perturbations bracketed larger architectural opportunities:

| Short probe | FPS | Mean frame ms | Interpretation |
| --- | ---: | ---: | --- |
| Retained scoped-math implementation | 49.12 | 20.36 | Actual pack rendering |
| Trivial fullscreen fragment programs | 79.19 | 12.63 | Invalid image; optimistic ceiling for fullscreen work, including sky background |
| Freeze populated shadow maps after warmup | 59.97 | 16.67 | Invalid temporal shadows; selection/preparation still execute |

Neither removing fullscreen shading work nor eliminating shadow-map updates alone reaches
120 FPS. These are workload-sensitivity diagnostics, not shippable gains or independent
additive cost estimates. The fullscreen perturbation changes data read by later passes; the
shadow freeze preserves a settled map but stops animated casters updating. A startup shadow
suppression and an initial freeze attempt that still cleared targets were confounded and are
explicitly classified as such in `workload-ceilings-summary.json`. All diagnostic production
hooks were removed. Subsequent work should prioritize large reductions in fullscreen shading
alongside world/shadow geometry, rather than another small compiler rewrite justified only by
instruction counts.

### Rejected stable-state shader specialization probe

A minimal fragment-program experiment replaced clear-weather and absent-effect uniforms with
zero-valued globals before optimization. The fixed forest probe produced **46.33 FPS**, median
**20.261 ms**, versus the retained 49.12 FPS/20.358 ms median. This offers no useful performance
signal; no production state classifier or shader-variant cache was built. The temporary transform
was removed. An initial compile attempt used GLSL constants and exposed compile-time rejection
of a negative array index inside unreachable underwater code; the measured attempt used initialized
globals so normal optimization could eliminate dead branches after validation. Evidence:
`state-specialization-v2.*` in `.research/three-mod-implementation`.

### Rejected frame-target resource versioning probe

A three-bank texture experiment rotated frame-local color, depth and shadow targets while sharing
`Clear=false` temporal pairs. Its short native/default-BSL probe returned **45.54 FPS**, median
**21.356 ms**, versus the retained 49.12 FPS/20.358 ms median. Extra target storage did not expose a
useful throughput gain. The bank integration and history-sharing helper were removed before any
production lifetime analysis or compatibility claims. Evidence: `target-banks-quick.*` under
`.research/three-mod-implementation`. This experiment is distinct from increasing CPU frames in
flight; it tested cross-frame resource reuse dependencies.

### Bounded spatial-index worker

The optimizer now constructs spatial groups from render-thread snapshots on one background worker.
The queue retains only the latest replacement, interruption abandons obsolete construction, and
publication checks the world/material/position identity. While unavailable, selection reads live
Minecraft sections through the vanilla provider. The existing exhaustive spatial-equivalence tests
and new worker isolation/replacement/failure/cancellation checks pass. A packaged native-resolution
BSL launch confirmed all three production mods loaded, two builds submitted, one obsolete build
cancelled, one current build published, and no pending build at exit. The captured forest rendered
normally on inspection. This three-second launch is installation validation, not evidence of a new
FPS improvement. Evidence: `async-index-tests-final.log` and `async-index-smoke-v2.*` under
`.research/three-mod-implementation`. The first smoke invocation used the action-only
`benchmarkValidationOnly` flag without an action and was rejected before world entry; it is retained
as a harness setup failure, not an implementation regression.

## Spark captures of the current three-mod build — October 3, 2026

Fresh packaged runs used renderer 0.2.2, shader loader 0.1.3 and scene optimizer 0.1.0, with all
retained optimization flags enabled, including the asynchronous spatial index. Both launches have
identical source and artifact hashes. Spark 1.10.187 / async-profiler 4.5 sampled all threads every
4 ms in wall-clock mode. No GPU stage timestamps, ordered-marker profiler or Metal validation were
enabled. Framebuffer: 3456×2168; BSL 10.1.8 defaults; render/simulation distances: 16/12.
The fixed forest fixture, pack and artifact identities are recorded in each input manifest.

These are **diagnostic samples**, five seconds of warmup and 30 seconds measured each, on battery
(90% before stationary, 88% before walking), Low Power Mode off. They do not establish a regression
from the earlier 49.1 FPS sample: instrumentation, power/thermal context and scene state are not
controlled pairs. Every frame, including one approximately half-second stall in each run, remains
in the result. The causes of those stalls are unresolved. GC totals were only 26 ms / 54 ms across
the complete intervals, insufficient to explain those stalls by GC pause time alone.

| Diagnostic | FPS | Mean frame ms | Median ms | p95 ms | p99 ms | Render CPU ms/frame | Fence wait ms/frame | Drawable acquire ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Stationary forest | 44.13 | 22.66 | 21.49 | 26.14 | 27.42 | 9.04 | 12.89 | 0.73 |
| Forest walk, including before/after observation | 43.71 | 22.88 | 22.37 | 26.40 | 28.15 | 9.48 | 12.25 | 1.15 |

The walk completed two legs and 52.76 blocks of movement. Its moving phase alone averaged
42.12 FPS over 19.99 seconds. No production code was changed for these captures.

Spark render-thread wall samples can be partitioned by stack ancestry into the following mutually
exclusive groups. These percentages describe sampled wall time, not CPU utilization or GPU work;
individual inclusive methods within a group overlap and must not be added.

| Sampled render-thread stack category | Stationary | Walk |
| --- | ---: | ---: |
| Metal buffer-fence wait | 64.7% | 60.7% |
| Shadow preparation/submission (`ShadowRenderer.render`) | 10.6% | 13.1% |
| Shadow feature-extraction selection | 4.1% | 3.8% |
| Display drawable acquisition | 3.3% | 4.3% |
| Other | 17.2% | 18.1% |

The dominant wait is specifically `DynamicGpuDataStorageMapped.writeData` →
`MappableRingBuffer.currentBuffer` → `MetalFence.awaitCompletion` → native condition-variable wait.
Inspection of the current Minecraft bytecode confirms three ring slots and a wait before reuse.
Our native fence implementation marks completion in the command buffer's completed handler
(`MetalContext.mm`, `Device::submit`), so this is a resource reuse/submission-lifetime boundary.
This identifies where the CPU stalls; it does **not** establish that the fence is redundant or that
adding buffers would recover all waiting time. GPU throughput can simply move the wait elsewhere,
and additional queued frames can increase latency. Any change must preserve buffer lifetime safety.

Within shadow work, spatial selection accounts for 5.5% / 6.0% of render-thread wall samples,
with scene draw preparation at 3.2% / 4.4%. `BitSet.nextSetBit`, `SceneSelection` construction,
`LevelRenderer.extractSectionDrawGroups` and extraction membership/map operations are concrete CPU
hotspots. These identify optimization/parallel-preparation candidates more precisely than the
previous aggregate render-thread CPU counter, but do not quantify a guaranteed FPS gain.

Chunk workers sampled as waiting 99.7–100% of the stationary capture and 98.7–99.2% of the walking
capture. The integrated server sampled as waiting 91.4% / 90.5%. This pregenerated short route is
therefore not limited by a saturated chunk-worker pool or server tick thread. It does not represent
exploring ungenerated terrain. During the measured walk, the optimizer completed four asynchronous
index builds and used four fresh-selection fallbacks, with no extraction fallback; the stationary
interval needed no rebuild. The worker is functioning but index construction is not the main cost.

Interpretation: prioritize understanding the renderer's resource reuse/fence scope and reducing
GPU workload, with shadow selection/preparation as the clearest remaining CPU target. Spark still
cannot identify GPU arithmetic/bandwidth/occupancy limits or prove that 120 FPS is attainable.

Evidence: `.research/spark-current-20261003/summary.json`, `spark-current-{static,walk}.sparkprofile`,
matching `.json`, `.csv`, `.inputs.json`, `.analysis.json`, screenshots and logs. `run.py` plus its
`commands/` records reproduce the launches. `SparkDump.java` and `analyze.py` decode profiles using
the pinned Spark protobuf classes; analysis checks tree references, nonnegative self times and
self-time totals against each thread root. Profiles remain local. End screenshots include Spark's
post-capture chat messages and are not visual-equivalence fixtures. The prelaunch evidence-name
collision was rejected by the overwrite guard before game launch and retained as `setup-rejected.log`.

## Resolution comparison — October 3, 2026

Three fresh packaged runs used the current renderer 0.2.2, shader loader 0.1.3 and scene optimizer
0.1.0, with all retained optimization flags enabled. Source and artifact hashes match across all
three and match the preceding Spark build. BSL 10.1.8 defaults, fixed forest fixture and camera,
16/12 render/simulation distance, unlimited FPS and disabled VSync were held constant. Spark,
ordered GPU markers, render-stage sampling and Metal validation were off. These are short
exploratory measurements: initial meshing completion, ten seconds warmup, then fifteen seconds
recording every frame. They are not sustained performance signoff or direct comparisons with the
earlier instrumented 44 FPS captures.

| Actual framebuffer | Average FPS | Gain over fresh native | Mean frame ms | p95 ms | p99 ms | Render CPU ms/frame | Fence wait ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 3456×2168 | 46.98 | — | 21.28 | 24.21 | 25.26 | 8.50 | 12.74 |
| 2560×1600 | 66.87 | 42.3% | 14.95 | 17.53 | 18.20 | 9.48 | 5.44 |
| 1920×1200 | 74.75 | 59.1% | 13.38 | 17.28 | 18.72 | 9.99 | 3.16 |

The smaller sizes render 54.7% and 30.8% of native pixels. Dropping from 2560×1600 to 1920×1200
removes another 43.75% of pixels but increased measured FPS by only 11.8%. This supports a mix of
pixel-dependent and other costs; it does not identify a hardware bottleneck by itself. At the
smallest size render-thread CPU execution already averages about 10 ms/frame, above the 8.33 ms
budget for 120 FPS. The CPU/GPU overlap remains important: fence waits are CPU wall waits, not
independent additive GPU stage costs. No resolution reached 120 FPS.

All launches completed successfully, retained focus and the exact requested framebuffer, and
reported zero failed GPU submissions. CSV frame counts and durations were checked against reports;
all three end screenshots were inspected and show the expected forest scene. These are smaller
borderless windows with actual lower-resolution framebuffers, not full-screen upscaling or an
implementation of internal render scaling. The native aspect ratio differs slightly from 16:10;
shadow submissions were 3090 at native and 3092 at both smaller sizes. Scene animation, sequential
run order, battery power (77% before the first launch, 74% after the final launch), and uncontrolled
thermal state limit precision. Low Power Mode remained off. No large stall was discarded: maximum
frame times were 35.41, 32.49 and 19.74 ms, respectively.

Evidence: `.research/resolution-comparison-20261003/summary.json`, `run.py`, `analyze.py`, command
manifests and per-resolution JSON/CSV/log/PNG files. The analysis checks common configuration,
source/artifact/fixture identities, dimensions and recorded intervals. Only disposable world copies
created by these runs are removed after evidence validation; the pinned fixture and results remain.

## Renewed investigation: workload interaction and presentation pacing

Evidence lives in `.research/optimization-round2-20261003/`. These are deliberately short,
five-second warmup / ten-second measurement samples at 3456×2168 and BSL defaults. Runs retain
every frame, use the pinned forest, disable Spark/GPU markers/API validation, and record source,
artifact, fixture, power and effective-setting identities. The machine remains on battery; run
order, animations and changing power/thermal state limit small comparisons. The analyzer groups
matching builds and workloads, verifies counters and CSV totals, and excludes failed launches.

| Experiment | Average FPS | Mean frame ms | Render CPU ms/frame | Interpretation |
| --- | ---: | ---: | ---: | --- |
| Fresh control v2 | 48.97 | 20.42 | 8.56 | Normal visible rendering |
| Trivial fullscreen fragments + populated shadow-map freeze | 116.86 | 8.56 | 7.10 | Invalid imagery; preparation retained; presentation can limit this result |
| Direct resource bindings for procedural pipelines | 48.25 | 20.73 | 8.28 | Activated, no useful throughput signal; compiler experiment removed |
| Same-phase mipmap reuse | 48.39 | 20.66 | 9.60 | Zero reuse: premise does not apply to defaults; candidate removed |
| Coarse receiver classification | 47.76 | 20.94 | 8.55 | Fewer plane tests, no CPU/throughput signal; candidate removed |
| Fresh control v3 | 49.19 | 20.33 | 8.12 | Normal visible rendering after cleanup |
| Bloom-region scissor and exterior clear | 49.68 | 20.13 | 8.41 | ~1% short-sample difference; image equivalence unverified |
| 32-block receiver-padding trial | 42.38 | 23.60 | 8.81 | Presentation-stalled sample; no regression/improvement attribution |
| No pack, renderer + optimizer | 119.88 | 8.34 | 1.33 | Different workload; 7.04 ms/frame waiting for display drawables |

The combined workload experiment replaced fourteen compiled fullscreen variants (including sky)
and froze populated shadow maps only after normal warmup. It retained 3,090 selected shadow
sections and their preparation; all 1,169 measured shadow draw phases and subsequent shadow clears
were suppressed. Fence waiting fell from 11.81 to 0.058 ms/frame, while drawable acquisition rose
from 0.020 to 1.368 ms/frame. Altered fullscreen outputs change downstream inputs and resource
dependencies: this is conditional workload sensitivity, not an attainable optimization result or
a sum of independent pass costs.

The no-pack control reveals a measurement boundary: its approximately 120 FPS accompanies only
1.33 ms of render CPU work and 7.04 ms waiting for a display drawable, despite VSync off and an
unlimited game frame rate. The combined experiment must consequently **not** be used to declare
117 FPS a CPU/GPU hardware ceiling. An explicit offscreen diagnostic preserves the final
presentation triangle in three private BGRA8 textures, but bypasses display acquisition/presentation.
Its reports are labeled diagnostic throughput, never visible FPS or performance signoff.

The mipmap source hypothesis was corrected using runtime counters and preprocessing conditions:
composite5's colortex0 request requires AUTO_EXPOSURE, which is off by default; its colortex9
request requires MCBL_SS, also off. Actual defaults generate composite4/colortex0 and
composite7/colortex1. Existing unused-mip analysis already removes composite6/colortex1. The zero
reuse result is not a performance rejection of an activated cache. The unused code was removed.

Coarse receiver classification reduced individual refinement tests from 6,621 to 1,711/frame,
with 450 coarse tests, while preserving source order, live mesh checks and 3,090 selected sections.
Render CPU time stayed approximately 8.55 ms. The extra bitsets/classification path and its tests
were removed rather than retaining an unproven option.

The tighter receiver trial selected 2,477 rather than 3,090 sections and reduced prepared shadow
draws about 18.8% and indices about 17.6%. However, the run included a 1,020.958 ms frame and
3.158 ms/frame of drawable acquisition; its median was 20.416 ms versus control 20.322 ms.
All intervals remain in the result. The separate diagnostic without presentation waiting reported
below addresses this confound. Its geometric assumptions are documented in
the receiver-eligibility audit above; no general custom-model correctness claim follows.

Bloom composite4's sampling regions cover about 5.78% of native pixels. A benchmark-only probe
preserved the viewport and samples, cleared black/alpha-one, and scissored to 1106×477 pixels.
Counters confirmed all 497 measured passes were modified. The ~0.20 ms difference is insufficient
to justify general pass-region infrastructure or acceptance without image verification.

The first control launch failed before world entry because the diagnostic clearColor mixin matched
an overload with a different descriptor. The corrected probe uses the full method descriptor;
the failed log/report remain excluded from the comparisons. Diagnostic hooks live in the benchmark
JAR; they do not enable altered images in ordinary launches.

### Offscreen follow-up

The completed presentation-independent diagnostics preserve native dimensions and final compositing
into private textures. Runtime counters confirm zero drawable acquisitions. Rates below describe
offscreen throughput, not visible-window FPS, and the earlier short-run/power limitations apply.

| Workload | Offscreen frames/s | Mean frame ms | Render CPU ms/frame |
| --- | ---: | ---: | ---: |
| BSL control | 48.19 | 20.75 | 8.67 |
| Trivial fullscreen fragments + populated shadow-map freeze | 126.87 | 7.88 | 6.80 |
| No pack, renderer + optimizer | 229.41 | 4.36 | 1.21 |
| BSL with 32-block receiver padding | 51.44 | 19.44 | 8.15 |

The matching-build shadow-padding pair improves throughput by 6.74%, saving 1.31 ms/frame, with
prepared shadow draws falling from 5,334 to 4,332. This is a promising candidate, pending image and
representative gameplay validation; the default padding remains 64. The combined altered-image
diagnostic and no-pack control rule out an absolute 120-frames/s execution ceiling in these
configurations. Neither is a prediction of achievable default-BSL performance. Normal BSL remains
near 48 frames/s without display waiting, so presentation does not explain its main cost.

A separate colored-shadow sampling elision reached 48.50 visible-window FPS, but lacks a matching
same-build visible control and prepared more shadow geometry: 5,512 rather than 5,334 draws. It does
not isolate that sampling cost. Its diagnostic implementation and the bloom-region implementation
were removed; source audits and activation evidence remain in the research folder. The completed
analyzer verifies fourteen timed reports, grouping source, workload and presentation mode, and
retains these confounds explicitly.

The subsequent fixed-world padding comparison matched all eight frame ordinals (128–135) exactly
in decoded RGB and alpha: 59,940,864 pixels with zero difference. Source/artifacts, fixture and
camera/time/animation settings match; runtime counters verify padding 64 to 32 and selected shadow
sections 3,090 to 2,477. Evidence: `round2-shadow32-visual-comparison.json` plus paired reports and
captures. This passes the fixed-scene image gate, not movement, low-sun, boundary-caster,
custom-model or general gameplay validation. The candidate remains opt-in.

## Fullscreen-family investigation and shadow validation

Evidence: `.research/optimization-round3-20261003/summary.json`, per-run command/input manifests,
CSV intervals, reports, logs and images. The static throughput probes use five seconds warmup and
ten seconds measurement at native 3456×2168, BSL defaults, the same fixed forest, and no Spark,
GPU markers or Metal validation. Offscreen presentation removes display pacing; these rates are
not visible FPS. All intervals remain. Power/thermal state and world animation remain uncontrolled.
Generated Metal shader dumps were enabled during startup for source inspection, not during timing.

| Probe | Matched control frames/s | Probe frames/s | Control → probe mean ms | Interpretation |
| --- | ---: | ---: | ---: | --- |
| Trivial AO (`deferred`) | 48.19 | 56.78 | 20.75 → 17.61 | Incorrect image; exact requested program activation verified |
| Trivial atmosphere (`deferred1`, `composite`) | 48.19 | 56.81 | 20.75 → 17.60 | Incorrect image; same camera/shadow prepared draw counts |
| Dirty-section ordinal cache | 48.19 | 48.06 | 20.75 → 20.81 | Activated, no large signal; more shadow geometry than control |
| Fast arithmetic for eligible fragment requests | 49.07 | 49.72 | 20.38 → 20.11 | Changed numerical assumptions; no large signal |
| Trivial later postprocessing (six programs) | 49.07 | 57.38 | 20.38 → 17.43 | Incorrect image; more shadow geometry than control |

The AO and atmosphere runs retain 3,090 selected shadow sections and 5,334 prepared shadow draws;
the cache and postprocessing runs instead have 3,188 and 5,512. Those scene-population differences
are explicit confounds. Shader replacement changes downstream inputs and active resources, so the
approximately three-millisecond sensitivities are neither isolated stage costs nor additive savings.
AO render CPU time also rises from 8.50 to 9.64 ms/frame. No single tested fullscreen family removes
enough work to approach 120 FPS, and CPU preparation must improve alongside GPU execution.

The dirty projection cache recorded 343 hits and 138 rebuilds over 481 measured frames. It retained
node-to-section-ordinal membership while preserving live visibility/dirty checks. Tests covered
membership changes, recycled owners, tracker/index replacement and stale publication. The candidate
and its tests/flag/counters were removed after the small, confounded result; its re-applicable patch
and disposition remain in the research folder. This is a priority decision, not proof of no CPU benefit.

The arithmetic experiment changed only already eligible Relaxed fragment compilations to Fast;
35 actual fragment compilations and no vertex overrides were logged. Unlike Relaxed, Fast can
ignore Inf/NaN semantics ([Apple MTLMathMode documentation](https://developer.apple.com/documentation/metal/mtlmathmode)).
Its roughly 1.33% short-sample difference did not justify the additional semantic risk. The native
override and benchmark metadata hook were removed. Ordinary arithmetic policy is unchanged.

The read-only generated-shader audit is recorded in
`.research/gpu-targets-20261003/fullscreen-audit.json`. Concrete remaining candidates include the
AO loop schedule, repeated light-shaft ray transforms, four eager TAA blur reads used only on an
invalid-history branch, and cloud lighting evaluated before an immediate occlusion exit. These are
hypotheses with bounded opportunities, not implemented gains. Existing compact target formats and
neighboring-pixel dependencies weaken simple format-reduction and pass-fusion proposals.

### Shadow candidate under low sun and movement

The image harness now accepts an explicit celestial time, defaults to 4000, and records actual
client clock time at capture start. A dawn comparison at time 600 and yaw -90 matched all eight
frame ordinals exactly in RGBA (59,940,864 pixels). Source, artifacts, fixture and relevant settings
match; receiver padding is the sole intended difference. Selected shadow sections fall from 3,036
to 2,433. This extends the earlier time-4000 comparison without establishing arbitrary-model bounds.

Two subsequent visible-window samples used the verified southbound natural forest route, five
seconds warmup and twenty seconds recording. Both completed one 24-block outward leg and part of
the return, with ordinary movement, camera turning and terrain updates. Native resolution and BSL
defaults were retained; source/artifacts and route definitions match. These are short exploratory
movement samples, not sustained gameplay signoff or pixel-aligned moving-image comparisons.

| Receiver padding | Visible FPS | Mean ms | p95 ms | p99 ms | Render CPU ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: |
| 64 blocks | 47.09 | 21.23 | 22.87 | 26.68 | 10.46 |
| 32 blocks | 50.05 | 19.98 | 20.70 | 21.89 | 9.64 |

The observed gain is 6.26%, or 1.25 ms/frame, consistent in direction with the earlier static
offscreen result. End captures were inspected and show expected forest/shadow rendering, with
slightly different final camera positions. The candidate remains opt-in; full moving-image,
boundary-caster, custom-model and broader gameplay validation remains incomplete. The 120 FPS
goal is still unmet.

An earlier walking launch omitted the fixed direction, selected a different automatic route and
stalled against terrain after 11.2 blocks. Its action validator rejected the run; it remains an
excluded failure report. The corrected pair explicitly uses `benchmarkForestDirection=south`.

Native smoke, tracked execution statistics, scoped fragment math, scene optimizer, benchmark
statistics and benchmark compilation checks passed; see `focused-checks.log`. The shadow caster
volume suite also passed before the probe matrix. After all game sessions ended, 29 disposable
round2/round3 world copies were removed (1,139,017,950 bytes). The pinned fixture, reports, images,
logs, manifests and shader audit files remain; `cleanup.json` records the exact deletion scope.

## Shader candidates, discarded water geometry, and current GPU stages

Evidence: `.research/optimization-round4-20261003/`, with separate `summary.json`,
`water-summary.json`, `water-visual-comparison.json`, and `stage-summary.json`. These are native
3456×2168/default-BSL forest experiments with the existing 32-block receiver-padding candidate
enabled in both sides. Timed pairs use five seconds warmup and ten seconds recording, retain every
interval, and bypass presentation. Rates are offscreen throughput, not sustained visible FPS.
Analyzers check source/artifact/fixture identities, CSV/report agreement, activation, presentation,
GPU failures, and scene work. The shader and water pairs use different builds and separate controls.

### Three fullscreen compiler candidates

One combined trial moved the four TAA fallback blur reads into the branch that consumes them,
expanded the exact AO four-by-two loops without changing arithmetic order, and factored repeated
light-shaft affine transforms out of its seven-sample loop. Strict source fingerprints restricted
each rewrite to the audited supplied pack. The light-shaft rewrite changed floating-point
association and therefore needed image validation if it showed a useful performance signal.

The control reached **51.41 frames/s (19.452 ms)** and the candidate **50.17 (19.931 ms)**.
All requested shaders compiled: two TAA, two AO and one sparse light-shaft variant. All four
sparse fullscreen passes remained active throughout the measured interval; four startup fallbacks
in the candidate did not recur during measurement. Camera/shadow prepared draw counts match.
Render CPU execution changed from **7.98 to 9.49 ms/frame**, so the result does not prove an
individual shader regression. It provides no useful combined throughput signal.

The five candidate/helper/test files and their benchmark hooks were removed instead of continuing
individual timing or image runs. `fullscreen-candidates.patch` preserves the exact re-applicable
experiment, and `fullscreen-disposition.json` records the decision. Focused source checks had passed
for TAA (38), light shafts (53), and actual dense/sparse AO shader compilation/loop expansion.
No fullscreen rewrite was promoted to ordinary rendering.

### Water-only shadow layers

The supplied BSL defaults disable water shadow color and water caustics. Its audited active vertex
shader assigns water material classes 200/205 the same water varying, and the fragment shader
unconditionally discards them. The active vertex program also has no voxel/image side effects.
Consequently an entire layer made exclusively of those materials can be omitted from shadow
preparation before vertex processing. Dropping every translucent layer would not preserve this proof.

The benchmark candidate inspects every emitted vertex once while `MeshData` is live at compiled-mesh
construction return. It accepts only the exact FULL/COMPACT terrain layouts and validated material
IDs; mixed materials, malformed data, custom meshes and material-generation changes retain the
ordinary path. A scoped draw lookup returns vanilla's supported null/no-draw result only for a
proved water-only translucent layer in `shadow-render` preparation. Camera rendering, meshes,
allocations, sorting, shadow depth copies, other layers and entities remain ordinary. Eligibility
checks the complete active preprocessed shadow vertex/fragment fingerprints. Unsupported pack
sources/options fail the requested benchmark instead of silently claiming activation.

| Short paired sample | Frames/s | Mean ms | Render CPU ms/frame | Shadow translucent draws/frame |
| --- | ---: | ---: | ---: | ---: |
| Control | 52.26 | 19.135 | 7.54 | 880 |
| Water-only layers omitted | 53.17 | 18.807 | 7.29 | 0 |

This removes **880 prepared draws and 741,072 prepared indices per frame**, approximately 20.3%
of shadow draws, while ordinary camera and solid/cutout shadow draw counts agree. The observed
throughput difference is **1.74%, or 0.327 ms/frame**, not a statistically established speedup.
Small live mesh/index-count variations remain in both runs; all shadow selection counts match.
The candidate removes geometry, but this result does not support treating draw count alone as
proportional to frame time.

All eight deterministic native-resolution image pairs matched exactly in RGBA: **59,940,864
corresponding pixels**. The candidate image was also inspected. Seventy isolated checks cover the
classifier, actual native FULL/COMPACT meshes, mixed materials and changed water-effect options.
This is one fixed forest/daylight sequence, not broad moving-image, reload or gameplay validation.
The feature remains an opt-in benchmark candidate (`benchmarkSkipWaterShadows`), is excluded from
ordinary performance signoff, and is not installed in the launcher or packaged in the production
loader. It is a small candidate to combine with larger work reduction, not the main 120 FPS strategy.

Two startup attempts exposed integration issues (array-store injection and constructor-before-super
injection); both were corrected before a timed control. A shorter candidate label also collided
with the evidence guard's existing control prefix and was rejected before launch. The failed/rejected
logs remain excluded; the successful pair is `round4-water-control-v3` / `round4-water-candidate-v1`.

### Current render-pass stage samples

The existing native stage profiler attaches timestamp slots to actual render-pass descriptors,
samples every fifteenth submission, and resolves after GPU completion. It does not use the general
ordered-marker query path. One fresh control run kept tracked resource hazards throughout, with
Spark and the shader-marker profiler off. It produced **34 sampled submissions**, zero invalid,
implausible or truncated samples, and 748 excluded clear/load/store-only encoders. The diagnostic
ran at 51.27 frames/s; sampling/resolve overhead is still present.

The following values subtract the start counters and divide by sampled submissions. Shadow has
two encoders per sampled submission; its row accumulates those two intervals. These are GPU-stage
timing intervals, not ALU utilization or an additive frame-latency budget.

| Work | Vertex-stage ms/sample | Fragment-stage ms/sample |
| --- | ---: | ---: |
| Main terrain | 2.797 | 2.755 |
| Shadows, two encoders | 3.481 | 2.179 |
| Ambient occlusion (`deferred`) | 0.015 | 3.070 |
| Light shafts (`composite`) | 0.015 | 1.544 |
| Atmosphere/clouds (`deferred1`) | 0.015 | 1.335 |
| TAA (`composite7`) | 0.013 | 0.896 |

Stages can overlap. Total pass envelopes include dependency gaps: for example, the final pass's
envelope averages 4.85 ms while its fragment interval is only 0.249 ms. Summing envelopes would
give a misleading budget. Detailed occupancy/bandwidth counters remain unavailable through the
previously attempted Xcode counter profile; that is separate from these working timestamp counters.

These samples support pursuing substantial geometry removal before vertex processing alongside
AO/fullscreen work. The CPU follow-up audit at
`.research/cpu-targets-20261003/selection-overlap-audit.json` verifies a same-frame overlap window:
shadow matrices are ready before `FeatureRenderDispatcher.prepareFrame`, where Spark observed the
long GPU-buffer wait. An immutable shadow-selection task could start just before that call, after
camera recentering, and be consumed with an immediate synchronous fallback before shadow drawing.
This remains a proposed implementation, with explicit frame/index/world/material lifetime checks;
extra queued frames and generic worker-count increases are not required.

Final benchmark compilation and statistics checks passed (`final-checks.log`). The 120 FPS goal
remains unmet; the water candidate and source-level probes do not establish that it is attainable.
After all launches ended, nine isolated benchmark world copies were removed (352,195,581 bytes).
`cleanup.json` records the paths; fixture, source audits, paired images, profiler data, command/input
manifests, logs, generated shaders and benchmark artifacts remain available.

## Same-frame selection overlap and static-face census

Evidence: `.research/optimization-round5-20261003/selection-summary.json` and
`round5-static-face-census.json`, with command/input manifests, logs and full interval CSVs.
The short paired selection runs retain the native 3456×2168/default-BSL forest and 32-block
receiver-padding candidate. Both bypass presentation and disable Spark/stage-marker profiling.
Source, artifact and fixture identities match. Five seconds warmup and ten seconds measurement
remain an exploratory protocol, not sustained visible-FPS signoff.

### Selection overlap

An opt-in `sceneAsyncSelection` experiment captures current matrices after camera recentering and
submits geometric shadow selection before feature preparation can wait on its transform buffer.
The worker reads an immutable index, frustum copy and receiver planes. Consumption requires the
same request, frame, renderer, view area, runtime/programs, generations, section positions and exact
matrix/vector/origin values. Live mesh readiness is filtered on the render thread. Late, unsupported,
obsolete or failed work falls back without waiting. Cancellation covers frame/world/runtime exits.

| Short offscreen pair | Frames/s | Mean ms | Render-thread CPU ms/frame | Fence wait ms/frame |
| --- | ---: | ---: | ---: | ---: |
| Synchronous selection | 52.142 | 19.178 | 7.752 | 11.391 |
| Overlapped selection | 52.086 | 19.199 | 7.165 | 11.982 |

Of 521 measured submissions, 520 worker results were ready (99.808%); one used the synchronous
fallback. There were no stale loader inputs or worker failures. Main-thread input capture averaged
0.034 ms/frame, consumption 1.135 ms/frame, and the live-readiness filter within consumption
1.104 ms/frame. Worker query duration averaged 1.056 ms/frame. These phase timers are elapsed
durations, not thread CPU measurements. Loader `used` counts tickets passed to the provider;
provider `selectionPrefetchReady` proves actual worker-result use.

Render-thread CPU falls by 0.586 ms but fence waiting rises by 0.590 ms, leaving throughput
essentially unchanged. Process CPU rises from 14.171 to 16.047 ms/frame; parallelism does not remove
all work and adds dispatch/result/filter overhead. This is a bounded CPU-headroom candidate, not
an FPS improvement. It stays disabled by default and was not promoted to the launcher. No further
timing or image suite was run for this direction after the flat throughput result.

Prepared draw counts and final shadow-selection counts match. Index-count differences are small
live-mesh variations (about 189 shadow-cutout and 271 camera-cutout indices/frame, 1.31 shadow-solid;
other layers match). They do not support attributing a tiny throughput difference to the candidate.
CPU tests cover exact ordered selections/counters, readiness changes, bounded replacement,
cancellation, worker isolation, copied-frustum/input semantics, invalidation and lifecycle cleanup.

### Static SOLID face opportunity

A separate one-shot census inspects actual compiled QUADS at mesh construction and evaluates both
original triangles at the settled camera/shadow pose. It accepts only supported terrain layouts,
exact axis-aligned rectangles, static material classes and four complete active BSL stage hashes.
Shadow evaluation includes its radial distortion. Original vertex/index data and rendering remain
unchanged. Backface winding includes Metal's Y flip and clockwise front-face convention; uncertain
numeric and degenerate cases remain.

| Potential SOLID work | Camera | Shadow |
| --- | ---: | ---: |
| Mesh layers inspected | 564 | 2,406 |
| Original indices | 1,287,252 | 5,104,896 |
| Static eligible quads | 214,316 | 842,144 |
| Quads counted as rejectable | 100,047 | 413,121 |
| Potential indices avoided | 600,282 | 2,478,726 |
| Share of SOLID indices | 46.63% | 48.56% |

The combined opportunity is 3,079,008 of 6,392,148 SOLID indices (48.17%). This is not half of all
terrain work: cutout foliage, other layers, entities and fullscreen effects remain. Every sampled
SOLID mesh used a supported layout; animated materials were retained. At this pose the rejected
shadow faces were precisely the eligible negative-X, negative-Y and negative-Z directions.

The full per-quad census took 379 ms once, before interval recording, and retains extra mesh data;
its run is explicitly excluded from FPS comparison. Counts precede GPU upload-readiness checks.
CPU matrix composition groups floats differently from shader execution, so this is an opportunity
estimate, not GPU-output equivalence or a safe production bucket proof. Isolated visibility tests
passed 40,015 assertions and mesh/classifier/source-guard tests passed 139 checks. The next gate is
a cheap conservative directional-bucket policy plus original-order shared index variants, followed
by matched GPU/performance and deterministic-image checks. The 120 FPS target remains unmet.

## Filtered SOLID indices and AO coverage

Evidence: `.research/optimization-round5-20261003/indices-v2-summary.json`,
`indices-v3-summary.json`, and `ao-coverage-summary.json`. All use the native default-BSL forest
and existing 32-block receiver-padding candidate. Geometry pairs retain five-second warmup and
ten-second measurement, bypass presentation, and enable the same sparse native stage sampler on
both sides. Each pair has matching source/artifact/fixture identities. These are exploratory results.

### Geometry experiment: GPU work falls, throughput benefit is not established

The benchmark-only index candidate retains the original vertices and surviving triangle order,
using bounded shared SHORT/INT index pages rather than a separate buffer per mesh. A conservative
whole-direction test includes actual published matrix chains, camera-relative input rounding,
shadow radial distortion and numerical error. Source fingerprints restrict eligibility to the
audited default programs. Animated/unknown geometry and unsupported states retain original draws.
The first launch failed a backend-description check before measurement; the corrected check uses
the device's explicit backend name. Failed evidence is retained and excluded.

| Short pair | Control frames/s | Candidate frames/s | Control mean ms | Candidate mean ms | Candidate selection elapsed ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: |
| Initial implementation (v2) | 52.31 | 54.11 | 19.119 | 18.481 | 4.222 |
| Reused scratch and section transforms (v3) | 50.12 | 48.22 | 19.954 | 20.738 | 2.262 |

Both candidates avoid about 423,000 camera and 2,211,500 shadow indices/frame, with 173 completely
removed shadow SOLID draws. Existing raster backface culling already prevents these triangles'
fragment shading. The expected opportunity is vertex fetch/transformation and primitive processing.
In v2 the sampled terrain vertex interval falls from 2.780 to 2.622 ms, and accumulated shadow
vertex intervals from 3.292 to 2.794 ms; corresponding fragment intervals remain close.

The initial render-thread CPU increase, 7.707 to 12.097 ms/frame, motivated reuse of scratch arrays
and one section transform across six directions, plus an early shadow winding condition. Selection
cost approximately halves. Nevertheless the fresh v3 pair has no overall gain: CPU is 9.130 versus
10.690 ms, terrain vertex intervals 2.814 versus 2.720 ms, shadow vertex 3.414 versus 3.086 ms, and
shadow fragment 2.086 versus 2.397 ms. Broader timing variation is present; no cause is established.
Both runs stayed on AC power and retained all intervals, including stalls. Stage intervals overlap
and must not be added into a frame-time budget.

This direction is parked, disabled by default and excluded from launcher/production deployment.
The small possible GPU saving does not justify more marginal tuning while CPU overhead and net
throughput remain unfavorable. No image-equivalence claim is made: the planned image runs were
not started after the second pair failed to show a gain. A future redesign must first demonstrate
lower selection cost and worthwhile frame time before broader visual/lifecycle validation.

Numerical validation passed 578,880 differential checks against the frozen pre-scratch helper plus
214,409 existing geometric/FP32-chain assertions. Index construction and arena checks remain in
the prior audit. Integrated benchmark compilation/statistics checks passed. These tests do not
replace actual GPU image validation.

### AO output offers coherent regions for a larger diagnostic

A one-shot benchmark hook reads actual R8 `colortex4` and D32 `depthtex0` after `deferred` flips its
output, before `deferred1` overwrites it. It runs after warmup and blocks on its own readback before
measurement; the entire run is excluded from FPS comparison. Format, dimensions, buffer ranges,
finite depth and completion are checked. Synthetic counting tests passed 34,120 assertions.

The captured 7,492,608-pixel frame contains 7,438,766 foreground pixels; 5,857,098 of those are
stored white (78.74%). All 53,842 sky pixels are white. Fully foreground, fully white aligned
2x2 blocks cover 4,969,044 pixels (66.32% of the screen), 8x8 blocks cover 3,012,608 (40.21%),
and 16x16 blocks cover 1,947,904 (26.00%). These counts indicate coherent output regions where
the expensive AO calculation might be avoidable. R8 quantization, frame noise, changing geometry
and samples outside each tile prevent using this observation directly as a correct early exit.

The next gate is a deliberately invalid frozen-mask experiment to estimate achievable GPU savings
before building a conservative depth/plane certificate. Its result cannot be claimed as a
quality-preserving optimization, and any real classifier's construction/lookup cost must be paid.

An alternative research direction is texture locality: NVIDIA's
[cache-aware SSAO sample description](https://github.com/nvpro-samples/gl_ssao/blob/master/README.md)
groups pixels sharing sample directions to improve cache reuse without reducing the total pixel
count. This is an algorithmic reference, not contemporary M3/BSL benchmark evidence. BSL's existing
noise/sample pattern would require separate analysis; replacing its AO algorithm or sample pattern
would not preserve the selected pack's defaults. No external implementation was imported.

### Frozen AO mask: substantial stage saving, still an invalid-image diagnostic

Evidence: `.research/optimization-round5-20261003/ao-mask-summary.json`. A source-pinned benchmark
rewrite adds a private immutable R8 mask and returns white early at captured white foreground
pixels. The mask is initially zero, so its settled capture comes from the full original algorithm.
Readback, mask construction and upload finish before measurement. Every measured pixel pays a
mask fetch/branch; unmasked pixels execute the original AO body. Pack targets are not repurposed.
The mask is intentionally stale as frame noise/geometry changes, so this is not a valid AO cache.

| Short offscreen sample | Frames/s | Mean ms | Render CPU ms/frame | AO fragment ms/sample |
| --- | ---: | ---: | ---: | ---: |
| Control | 52.257 | 19.136 | 7.587 | 3.036 |
| Frozen white-foreground mask | 57.191 | 17.485 | 7.587 | 1.258 |

The candidate masks 5,869,299 pixels, creates exactly one private texture, compiles both dense and
sparse variants, and binds the frozen mask in all 572 measured frames. Both sides retain native
resolution, default pack options, matching source/artifacts/fixture, tracked hazards, identical
stage instrumentation and AC power. There are no failed GPU command buffers or invalid/truncated
stage samples. The observed 1.651 ms/frame saving and 1.777 ms AO-stage reduction justify examining
a correct classifier; they do not establish a deployable 9.44% improvement.

The pair has a scene-work limitation: camera prepared draw counts match, but selected shadow
sections are 2,477 versus 2,570. The candidate prepares 91 more SOLID, 44 more cutout and 35 more
translucent shadow draws/frame. Counts are stable at both ends, and main-camera meshing is settled.
No direct dependency from AO masking to earlier shadow selection was found. Actual light/frustum
matrices and off-camera renderable membership were not recorded, so the cause is unproven. The
analyzer explicitly records this mismatch. Total-frame gain is therefore qualified; the direct AO
stage change remains the main evidence. Later harness reports now include actual pack view/shadow
matrices, celestial scalars, light directions and both camera origins at interval boundaries.

This experiment is optimistic about free classification, but is not a strict upper bound on all
AO optimizations: its additional texture access and divergent branches also have costs. A correct
per-frame classifier must demonstrate useful conservative coverage and construction/lookup cost
comfortably below the measured opportunity before a GPU hierarchy or general runtime is built.
No image-equivalence validation is appropriate for the deliberately invalid frozen-mask output.
The diagnostic is disabled by default and stays out of the launcher.

Actual dense/sparse shader compilation and reflection, full original AO body preservation, source
guards and binary mask tests passed 52,361 checks; the 34,120 census checks also passed. Final
benchmark compilation/statistics checks passed after adding view-input metadata. The metadata
addition is compile-verified and has not yet been exercised by a new game launch. The 120 FPS
target remains unmet.

### AO depth hierarchy feasibility: parked before GPU implementation

Evidence: `.research/ao-certification-20261003/ao-inputs-v1.ao-inputs.json`,
`reference-full-frame.json`, `certificate-summary-v1.json`, and `certificate-audit.json`.
A single settled, unchanged deferred frame captured native R8 AO, D32 depth, the actual bound
512x512 noise texture and samplers, the active sparse shader sources, and uniform bytes re-emitted
through the same writer at binding time. File lengths and SHA-256 identities were verified. The
packaged run completed with zero failed GPU buffers; its blocking readback excludes it from FPS
comparisons. The new view/shadow input metadata also worked in this launch.

An offline float32 reference matches 99.7103% of all 7,492,608 output bytes exactly and 99.98393%
within one R8 unit. Rare differences reach 53 units, and 35 pixels evaluated as exactly one on the
CPU are nonwhite on the GPU. This is suitable for opportunity screening, not a numerical proof or
replacement for GPU validation. In particular, sampling nearest depth and reconstructing at the
original fractional coordinate must both be retained, even on apparently planar surfaces.

The feasibility study sampled 4,096 deterministic random tiles at each size. It compared a raw
depth-range hierarchy with a slope-aware plane/residual hierarchy over the rectangle containing
the actual eight AO samples. The table uses the union of both bounds and a 0.001 angular margin.

| Tile size | Observed all-white foreground tiles | Refined hierarchy accepted tiles | Exact rectangle scan accepted tiles |
| --- | ---: | ---: | ---: |
| 2x2 | 66.85% | 3.42% | 16.63% |
| 4x4 | 54.81% | 1.88% | 6.86% |
| 8x8 | 39.75% | 1.88% | 4.91% |

No accepted pixel disagreed with the captured GPU's white output in this sample, but these are
real-arithmetic bounds, not certified bounds for relaxed Metal arithmetic. The analysis also uses
normals and sample positions from the full AO reference, leaving their recurring preparation cost
unpaid. Despite that optimistic assumption, useful coherent coverage is poor. The median 8x8 tile
footprint rectangle contains 101,332 texels: bounding unrelated occluders inside that rectangle loses
the spatial correlation of the actual eight samples. The refined query averages 13.32 hierarchy
records per tile, with seven floats per record, before hierarchy construction and normal preparation.
The exact scan touches 375 million texels just for the 4,096 sampled 8x8 tiles.

The full-footprint depth-hierarchy approach is therefore parked without implementing a GPU
classifier or running another FPS pair. The frozen-mask result remains useful evidence of AO
opportunity; it does not make this particular classifier economical. Different sample-specific
methods would require a new low-cost hypothesis. This investigation changes no production or
launcher behavior and does not advance the measured FPS baseline.

## Terrain RGB shading and shadow dependency diagnostic

Evidence: `.research/optimization-round6-20261003/terrain-unlit-summary.json`, command/input
manifests, interval CSVs and `.research/gpu-targets-20261003/terrain-unlit/packaged-msl-v2.json`.
The probe changes only the known default BSL camera SOLID/CUTOUT terrain fragment programs.
It retains the original atlas sample, alpha calculations and rejection, vertex source, geometry,
pipeline states and sole color0 attachment, but publishes unlit atlas RGB. This deliberately invalid
image measures an opportunity; it is not a performance candidate for normal rendering.

The first public-output overwrite did not remove lighting from optimized SPIR-V/MSL. Routing the
original output through private storage and retaining the one original atlas sample allowed the
compiler to eliminate RGB-only lighting and shadow reads. All eight FULL/COMPACT, direct/indirect,
SOLID/CUTOUT combinations passed 160 isolated preservation/compilation checks. Actual packaged
fragments also have one atlas sample, two original alpha rejection sites, and no shadow sampling.
Both terrain layers were successfully bound once in each of the 597 measured candidate frames.
Within-process original/modified vertex hashes match. Across JVMs, vertex MSL differs only in two
matrix declarations' order inside `PackUniforms`; vertex logic outside that layout is identical.

The first candidate launch stopped before timing because the activation hook observed pass opening,
which misses CUTOUT's pipeline switch inside the shared terrain pass. The corrected hook observes
successful binding and still requires both layers. That launch and its earlier control are excluded
from the fresh matching-build v2 comparison.

| Native offscreen, default BSL | Control | Unlit diagnostic |
| --- | ---: | ---: |
| Frames/s | 52.28 | 59.66 |
| Mean frame ms | 19.128 | 16.761 |
| Render CPU ms/frame | 7.522 | 7.574 |
| CPU fence wait ms/frame | 11.548 | 9.153 |
| Terrain vertex interval ms/sample | 2.799 | 1.714 |
| Terrain fragment interval ms/sample | 2.707 | 0.586 |
| Terrain pass span ms/sample | 5.507 | 3.363 |

Source/artifact/fixture identities, prepared draw counts, shadow selections and recorded camera/light
inputs match (excluding expected frame-noise ordinal differences). Small live index-count differences
remain, under 0.006% per affected layer. Both launches stayed on AC power, used five seconds warmup
and ten seconds measurement, retained all intervals, bypassed presentation and used identical stage
sampling. There are no failed GPU submissions or invalid/truncated stage samples. Neither rate is
sustained visible FPS.

The roughly 2.37 ms frame reduction and 2.12 ms terrain-fragment reduction justify investigating the
terrain lighting/shadow path. They do not isolate arithmetic or bandwidth. Removing shadow reads
also removes resource dependencies; pruning fragment inputs can change interpolation, register
demand and linked vertex work; changed RGB can affect compression and downstream branches. The
shadow fragment interval rises from 2.132 to 2.829 ms despite matching draw counts, while AO's direct
fragment interval stays near 3.04 ms. These interacting intervals must not be summed into savings.

The next narrow question is how much of this bundle comes from primary nine-tap shadow filtering
while retaining full lighting, varyings and shadow resources. A potential quality-preserving follow-up
is conservative min/max depth rejection over the exact filter support, with ordinary PCF fallback.
NVIDIA's February 2007 [Soft Shadows white paper](https://developer.download.nvidia.com/SDK/10/direct3d/Source/SoftShadows/doc/SoftShadows.pdf)
describes the underlying min/max hierarchy principle. This is an algorithm reference, not contemporary
Minecraft or Apple performance evidence; no external implementation was imported. BSL's nine-tap
footprint, bilinear comparisons, bias, borders, colored-shadow branch and recurring hierarchy cost
need their own evidence before implementing or claiming a benefit.

No candidate was promoted or installed. After all game handles ended, five disposable benchmark
world copies and isolated generated test classes were removed: 195,964,187 bytes, recorded in
`cleanup.json`. Raw captures, fixtures, sources, reports, logs, shaders and mod artifacts remain.
Native/default-BSL sustained 120 FPS is still unachieved.
