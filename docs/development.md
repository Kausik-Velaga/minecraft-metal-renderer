# Development

For installing the released mod, see the [player guide](installation.md). The tools and commands below are for building and testing the source.

To contribute results from your Mac, see [community testing](community-testing.md#automated-tests-for-source-contributors) for the combined test command, evidence locations, and hardware report form. Successful runs and partial failures are both useful.

## Build and run

Requirements:

- Apple Silicon Mac; macOS 14 or newer. Validation host: macOS 26.5.2, Apple M3 Pro.
- JDK 25 or newer. The project compiles for Java 25; validation used Homebrew OpenJDK 26.0.1.
- Xcode Command Line Tools, including Clang and the macOS SDK.
- CMake 3.22 or newer, available on `PATH`.

Run from the project directory:

```sh
./gradlew --no-parallel build
./gradlew --no-parallel :nativeSmokeTest
./gradlew --no-parallel :shaderSmokeTest :transientMemorySmokeTest
./gradlew --no-parallel :runClientGameTest
./gradlew --no-parallel :runClientNaturalGameTest
MTL_DEBUG_LAYER=1 ./gradlew --no-parallel :runClient
```

From the repository root, `build` builds all three mods and runs the renderer's native, shader, and transient-memory GPU checks. The explicit `:build` task builds and checks only the renderer. `:shader-loader:build` builds the loader and the renderer artifact it depends on, without running the renderer's GPU checks.

| Mod | Installable artifact |
| --- | --- |
| Minecraft Metal | `build/libs/minecraft-metal-renderer-<version>.jar` |
| Minecraft Shader Loader | `shader-loader/build/libs/minecraft-shader-loader-<shader_loader_version>.jar` |
| Minecraft Scene Optimizer | `scene-optimizer/build/libs/minecraft-scene-optimizer-<scene_optimizer_version>.jar` |

Use the versions in `gradle.properties`; the working tree can be newer than the installed release.

The shader loader's version is controlled separately by `shader_loader_version` in `gradle.properties`. The renderer is a regular dependency of the loader, not embedded in its JAR. Neither mod requires Fabric API at runtime; the test harness uses it and the loader includes optional integration for Fabric's Indigo terrain renderer.

Release both artifacts together with their tested compatibility range. Validate the renderer on its
own, then the pair with the recorded pack versions and options. See [shader-pack installation](shader-loader.md)
and the [shader validation record](shader-validation.md).

Gradle configures and builds the arm64 native library automatically and packages it at `native/macos-arm64/libminecraft_metal.dylib` in the renderer JAR only. Use the versioned mod JARs, not sources JARs or the older 0.1.0 renderer build.

Use `./gradlew --no-parallel :runClient` to launch just the renderer from source, with saves, options, and logs in `run/`. Use `./gradlew --no-parallel :shader-loader:runClient` to launch both mods, using the separate `shader-loader/run/` directory. Always use these qualified task names: the unqualified `runClient` task selects both projects. GPU checks and client game tests enable Metal API Validation; it can substantially reduce game performance when enabled for a development client.

The two client game tests launch real Minecraft instances, create disposable worlds, exercise the renderer, and save screenshots under `build/run/clientGameTest/screenshots/` and `build/run/clientNaturalGameTest/screenshots/`. The flat scene switches Improved Transparency off/on/off and checks that the boat water-mask pass executes. The natural scene checks indexed indirect terrain drawing, both transparency modes, movement, resource reload, and SDL fullscreen transitions. The test source set and renderer probe are excluded from the mod JAR. Both tasks require a fresh completion marker so a startup failure cannot be mistaken for a passing test. Run project Gradle commands sequentially; concurrent builds can replace class files while a development client is starting.

The pinned dependencies are Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, and Fabric Loom 1.18.2. Minecraft supplies RenderPearl and LWJGL 3.4.3, including SDL, shaderc, and SPIRV-Cross. Gradle is provided by the checked-in wrapper.

## Shader-pack validation

Supply your own shader pack. Packs are not downloaded, bundled, or redistributed by this project.
BSL 10.1.8 is the initial validation target. These tasks test pack parsing, GLSL adaptation, actual
Metal pipeline compilation, depth snapshots, temporal-buffer clears, and GPU texture filtering:

```sh
./gradlew --no-parallel :shader-loader:check :shader-loader:bslShaderSmokeTest -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
./gradlew --no-parallel :shader-loader:runClientGameTest -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
```

The shader client test creates a disposable scene with water, glass, vegetation, entities, shadows,
particles, camera movement, night, rain, resizing, and resource reload. Screenshots are saved under
`shader-loader/build/run/clientGameTest/screenshots/`. A completion marker verifies that the entire
scenario ran; inspect the captures separately for rendering quality. Test scheduling and Metal
validation make these runs unsuitable as FPS benchmarks.

For an ordinary client, put the pack ZIP in the profile's `shaderpacks/` folder, then create
`config/minecraft-shader-loader.properties` with, for example:

```properties
pack=BSL_v10.1.8.zip
# Optional: profile=HIGH
# Optional: option.SHADOW_MAP_RESOLUTION=2048
```

The optional keys must match the selected pack's own profiles and options. JVM properties
`minecraftShaders.pack`, `minecraftShaders.profile`, and `minecraftShaders.option.<name>` override
the corresponding configuration values. An empty pack selection leaves ordinary rendering active.
The initial implementation rejects unsupported compute/storage-image programs and advanced stage
types with an explicit error. It uses classic transparency while the pack is rendering.

## Controls and diagnostics

- Add JVM option `-Dmetal.enabled=false` in a normal launcher to use Minecraft's selected graphics API. The development run configuration explicitly enables Metal.
- Add `-DminecraftMetal.dumpShaders=true` to retain translated MSL under the game's `debug/metal-shaders/` directory. Failed native shader compilations also save the translated shader pair.
- Use `MTL_DEBUG_LAYER=1` while diagnosing rendering errors. Search the client log for `Using graphics backend Metal` and `Metal device created` to confirm the selected backend.

The Metal renderer extracts each native build into a directory named by its SHA-256 hash under `~/.cache/minecraft-metal/`. This prevents a running or previously cached library from being mistaken for a new build.

## Troubleshooting

If native configuration fails, check that `cmake` is available and `xcode-select -p` points to a working Xcode or Command Line Tools installation. If Gradle reports an unsupported Java version, launch it with JDK 25 or newer. A missing bundled native library indicates that the native build or resource packaging did not finish; run `./gradlew --no-parallel build` again and inspect the first failure.

Metal initialization errors are reported directly. The mod does not silently fall back to OpenGL while Metal is enabled, because that would hide failures during validation. For compatibility testing, disable Metal explicitly with the JVM option above.

Fabric development clients use an offline development identity. Realms authorization and session-profile download warnings can occur independently of the rendering backend.

### Check the release JARs

After the loader's flat client test creates its disposable fixture, these tasks launch the actual
production JARs with vanilla Minecraft and Fabric Loader, without Fabric API. A separate test-only
probe drives the fixture, verifies the backend/mod set, saves a screenshot, and exits. It is not
included in either distributed mod. These short runs are installation checks, not FPS benchmarks.

```sh
MTL_DEBUG_LAYER=0 ./gradlew --no-parallel :shader-loader:runPackagedRenderer
MTL_DEBUG_LAYER=0 ./gradlew --no-parallel :shader-loader:runPackagedBsl -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
```

Each paired release must record renderer/loader/Minecraft/pack versions, options, hardware,
GPU checks, visual inspection, and the exact tested artifact hashes. Re-run the renderer alone as
well as the pair. Add separately documented pack configurations as support expands; do not infer
compatibility from compilation alone. Release only the production JARs and their checksums;
`benchmark-probe` and stale development artifacts are not player downloads.

## Benchmark reproducibility and retention

Benchmark tasks live in `gradle/shader-benchmarks.gradle`. Shared preparation validates and copies
inputs for both development and packaged launches. Natural scenes require an explicit
`-PbenchmarkFixture=/absolute/path/to/world`; the harness no longer picks whichever test world was
modified most recently. A flat scene can use the fixture created by the client game test, or the
same explicit property. The local retained fixtures are listed in the
[performance record](shader-performance.md#current-benchmark-recipe).

A run creates `<label>.inputs.json` containing per-file and aggregate SHA-256 identities for the
fixture and build sources, the Git revision, requested benchmark options and packaged JAR hashes.
The completion JSON embeds this manifest alongside effective settings, pack hash and loaded mods.
Fixtures cannot overlap the working save or evidence directory. Copying verifies both the source
and destination identities. Existing evidence is never overwritten: default labels are timestamped,
and explicit labels must be unused. Failed runs keep their own diagnostics.

Texture filtering (RGSS), four mip levels, distances, camera, weather and other graphics settings
are configured explicitly. Options from another test are not copied. A minimal options file disables first-run onboarding
and tutorial prompts for the disposable test client. The loader's ordered-marker profiler changes
native synchronization, so profiler runs and image/action validation runs emit diagnostic intervals with
`performanceComparable=false`. Keep Metal validation off during normal FPS measurements.

While far below the target, use short packaged probes (five seconds of warmup and ten measured)
to reject poor directions and continue implementing larger changes. Do not repeat long runs to
resolve marginal differences at this stage. For final performance comparisons use the same fixed fixture, route, pack hash and
settings, 30 seconds of warmup and at least 60 seconds of measurement. Run repeated alternating
control/candidate pairs; retain every run, including stalls. Record power mode and thermal context,
and compare average throughput, median, p95 and p99 frame times. Static aerial views, shader
compilation checks, short installation checks and action benchmarks answer different questions.
The existing 10-second packaged defaults remain installation checks only.
New result metadata marks runs shorter than 30 seconds warmup or 60 seconds measurement as
`short-exploratory-sample` and `performanceComparable=false`, even though their raw frame intervals remain
available. Older reports predate this duration gate and must be classified from their durations.

The probe includes gameplay/input guards, all frame intervals, CPU/GC counters and native diagnostic
counters. It does not yet orchestrate repeated pairs or independently validate GPU bottlenecks.
Keep GPU-stage profiling separate from normal timing; add owner-specific CPU preparation, waits,
upload/residency and draw/visibility counters as the three-mod contracts are implemented.

### Spark CPU and wait profiling

Add `-PsparkProfiler=true` to any `:shader-loader:runPackaged*` benchmark command. Use the same
fixture, native framebuffer, shader options and optimization flags as the run being investigated;
five seconds of warmup and 30 seconds of capture are a useful initial diagnostic. For example,
append these flags to the existing packaged BSL recipe:

```text
-PsparkProfiler=true -PbenchmarkWarmupSeconds=5 -PbenchmarkMeasureSeconds=30
-PbenchmarkLabel=spark-forest-1 -PbenchmarkOutput=/absolute/path/to/evidence
```

The harness downloads and verifies Spark Fabric **1.10.187** (Modrinth version `e3hsPc1o`), adds
only its required Fabric API modules, and disables the integrated server's background sampler.
`./gradlew :shader-loader:prepareSparkProfiler` can fetch it separately. The SHA-512 is pinned in
`gradle/shader-benchmarks.gradle`; a mismatch fails the launch. Ordinary benchmarks do not load
Spark or these additional API modules. Neither Spark nor its adapter is included in production jars.

After the world warms, the probe starts Spark's client async sampler at 4 ms intervals for all
threads, keeping individual thread names separate. It waits for startup without blocking the render
thread, then measures the scene. At completion it stops and exports locally, waits for export before
closing Minecraft, validates that the protobuf contains threads, and copies `<label>.sparkprofile`
next to `<label>.json`, `.csv` and `.inputs.json`. The result records the engine, event, thread count,
profile SHA-256 and loaded artifact hashes. A missing sampler, Java fallback or failed/empty export
fails the diagnostic. Existing profiles cannot be overwritten. Capture includes a small boundary
around the measured interval; startup and export overhead are outside frame statistics.

Spark captures always have `performanceComparable=false` and diagnostic timing fields. Its `wall`
samples include waiting: a large native-fence stack is not equivalent to expensive CPU arithmetic.
Use the render-thread/process CPU counters alongside Spark, and Metal tools for GPU execution.
Do not run another async-profiler capture at the same time. The renderer's ordered-marker profiler
should remain off when investigating ordinary CPU scheduling.

The setup does not upload profiles. To inspect a saved `.sparkprofile`, use the local-file import
in the [Spark viewer](https://spark.lucko.me/). For manual captures in a client with Spark installed,
use `/sparkc profiler start --thread * --not-combined`, then
`/sparkc profiler stop --save-to-file`; manual exports live in that client's `config/spark/`.

Upstream source is cloned separately at `.research/upstream/spark`, reviewed at commit
`4188f0c905100c578df70300e65c522276b17b3a` (Minecraft 26.3). Recreate it with
`git clone https://github.com/lucko/spark.git .research/upstream/spark` and check out that revision
inside the clone. The source checkout is for inspection; the launched binary is the checksum-pinned
published artifact, not a claimed reproducible build of that checkout. Spark retains its upstream
GPL license (API: MIT). Our benchmark-only adapter invokes the external tool; no Spark implementation
was copied into our mods. Upgrading the pin requires checking the client command adapter again.

October 3 integration check: `spark-integration-2` loaded all three packaged mods plus Spark,
captured 30 measured seconds at 3456×2168 with default BSL 10.1.8, and exported a valid profile
with **89 thread groups**, including the render thread, integrated server and chunk workers.
The profile metadata confirms async-profiler 4.5, 4 ms sampling and a 32.317-second capture including
start/stop boundaries. The JSON correctly reports diagnostic, non-comparable timing. Evidence is in
`.research/three-mod-implementation/spark-integration-2.*`. Setup/statistics checks pass, and all three
production jars contain no Spark implementation or benchmark adapter. The first integration attempt
failed before capture on the old Fabric client-command class name; its failure evidence is retained.

Retain canonical fixtures outside `build/`, one useful visual reference sequence, compact results
and linked release evidence. Disposable `build/run/` copies, shader dumps, obsolete JARs and duplicate
captures can be deleted after recording results. `run/saves/` contains development player worlds,
not benchmark scratch space. Never include benchmark probes or shader packs in a player release.

October 3 preparation verification: the full build/check suite and all 583 BSL pipeline compilations
passed. New packaged Metal-only and Metal+BSL launch checks passed with the retained flat fixture
(three seconds warmup/three seconds measured, **not performance baselines**). The BSL run enabled
profiling and produced only diagnostic timing fields; the ordinary renderer run produced the normal
timing fields. Fixture/JAR identities matched the files, the duplicate-label guard rejected a reused
label before launch, and production JARs contained neither test probes nor the removed experiment.
Logs and compact results are under local `build/preparation-evidence/` and `build/preparation-*.log`;
the temporary launch worlds were deleted after verification. A first, aborted setup attempt exposed
the onboarding prompt and is recorded in `build/preparation-renderer.log`.

The initial scene optimizer is optional and requires the backend, while it has no shader-loader
dependency. Use `:shader-loader:runPackagedOptimizedRenderer` or
`:shader-loader:runPackagedOptimizedBsl` to validate its two installation combinations. These tasks
verify the installed optimizer flag and record provider counters. The probe records passive native
execution statistics without enabling the intrusive stage profiler. Its GPU span sum is a sum of
completed command-buffer durations, which can overlap; it is not a frame critical path or utilization.

Input manifests also capture read-only macOS power-source, power-policy and thermal-status output
before launch. This snapshot adds no timed-frame work and is not a continuous throttling trace.
Compare power conditions across runs; do not silently treat battery and AC runs as paired controls.

Experimental scene metadata caching is selected with `-PsceneDrawMetadataCache=true`. Its mixins
are absent when disabled. Native CPU command filling uses `MINECRAFT_METAL_CPU_ICB=1` together with
`-PmetalIndirectCommandBuffers=true`; snapshot coverage and CPU-fill counters distinguish actual
use from a requested flag. Loader clear folding uses `-PshaderLazyTargetClears=true`. These are
isolated experiment controls, not independently established release performance improvements.

Additional loader experiments are `-PshaderLiftUniformInitializers=true` (move substantial
uniform-only fullscreen calculations to flat vertex outputs), `-PshaderDiscardFullscreenLoads=true`
(discard old attachment contents only after proving a complete overwrite). The rejected terrain
coverage prepass experiment was removed after its native-resolution probe showed no useful gain.
`-PsceneDisableSparseExtraction=true`
restores the full-selection extraction fallback for diagnostics.

`-PshaderCompactTerrainVertices=true` selects lossless 48-byte terrain records when the immutable
material map proves the supported ID range; the benchmark records the actual selected layout.
`-PshaderOptimizeVertexSpirv=true` independently enables optimization of loader-owned vertex
shaders. `-PshaderSparseProjectionMatrices=true` exposes guarded projection-matrix zeros to the
compiler while retaining a precompiled dense fallback for other cameras. The probe records actual
sparse/fallback pass counts. `-PshaderRelaxedFragmentMath=true` requests Relaxed arithmetic only
for audited BSL default Overworld fragment pipelines, with Safe vertex calculations and a
precision-aware fallback. Actual native compilation counts are recorded under `pipelineMath`.
Keep `MINECRAFT_METAL_MATH_MODE=safe` (or unset) for this scoped experiment. The older global
`relaxed` environment override deliberately still affects unscoped pipelines for diagnostics.
One scene's image comparison does not establish universal visual equivalence.

`-PsceneDrawWorkCounters=true` records cumulative prepared draw and index counts by camera/shadow
view and solid/cutout/translucent layer, including metadata-cache hits. These are prepared command
counts, not hardware invocation counts; later submission changes can
make executed work differ. Start/end snapshots are stored under `sceneProvider.drawWork`.

For a short GPU stage diagnostic, `MINECRAFT_METAL_RENDER_STAGE_TIMINGS=1` samples existing render
encoder descriptors every fifteenth submission. It retains tracked resource hazards and adds no
marker encoders or global ordering fences. The probe stores start/end snapshots and marks all
intervals diagnostic; sampled stage spans can overlap and must not be added as frame time. Empty
passes and implausible stale samples are excluded. Completion-handler resolution still has a cost,
so leave this environment variable unset for throughput comparisons. Native discard counters
separately verify whether full-overwrite hints actually took effect.

### Scene optimizer worker

Spatial index rebuilds use a single bounded background worker by default. Snapshot capture, live
mesh filtering and result publication remain on the render thread. A pending, failed or obsolete
build uses fresh vanilla selection; it never reuses a stale world index. Cancellation is checked
while grouping sections, and at most one replacement may wait behind a running build. World and
material invalidation discard pending results. The idle worker retires after ten seconds.

For a synchronous control use `-DminecraftScene.asyncIndex=false`, or
`-PsceneAsyncIndex=false` with benchmark tasks. Provider diagnostics report submissions,
completions, cancellations, pending work and fallback selections. This moves rebuild work off the
render thread; it does not establish a higher steady-state FPS in the GPU-limited forest.
