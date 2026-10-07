# Remote issue and PR integration — October 7, 2026

Base: `a914daa9a95f8f60bc4bfcf67a98a8e18c5e8765` (`main`). Host: Apple M3 Pro,
macOS 26.5.2, Homebrew OpenJDK 26.0.1, Minecraft 26.3. AI-assisted integration and
validation by Codex; original fixes and authorship retained from jackforlife101.

## Renderer fixes

- [PR #3](https://github.com/Kausik-Velaga/minecraft-metal-renderer/pull/3): moved
  the mip-filter correction into the current shared sampler helper. Nearest texel
  filtering blends adjacent mip levels when mipmaps are enabled; max LOD zero
  still samples the base level.
- [PR #4](https://github.com/Kausik-Velaga/minecraft-metal-renderer/pull/4): expanded
  indexed triangle fans on the GPU, retaining 16/32-bit source indices, offsets,
  base vertex and draw arguments. Adapted compute synchronization to the current
  fence helpers, restored texture argument residency after resuming the render
  encoder, and marked the resumed encoder as having drawn.
- Added repeated-fan pixel/scissor preservation checks and a tracked-resource
  native test. Fan checks run before timestamps force global fence ordering.
  Indirect triangle fans remain unsupported; each direct fan splits the pass.

## Verification

- Full `./gradlew --no-parallel build`: PASS (94 executed tasks).
- Renderer `:runClientGameTest` and `:runClientNaturalGameTest`: PASS. Inspected
  the profiler pie chart and natural-terrain screenshots. The chart has filled
  slices and a shaded rim, with the surrounding scene retained.
- Updated `:nativeSmokeTest` and `:trackedNativeSmokeTest`: PASS, Metal validation
  enabled; mip interpolation/base-only pixels and repeated 16/32-bit fan draws.
- `:shader-loader:blockingPipelineCompilationTest`: PASS; compilation progresses
  with occupied chunk workers, while preserving executor scope and failures.
- `:shader-loader:bslShaderSmokeTest` with user-supplied BSL 10.1.8: PASS, 583
  pipelines, including eligible registered world/hand routes in all dimensions.
- Two fresh packaged BSL 10.1.8 loads (`bsl-distance19-2` and `-3`): PASS at
  render distance 19, simulation distance 12, 3456×2168, no scene optimizer. Each
  completed 5 seconds warmup and at least 30 seconds of recorded frames.
  [Compact results and artifact hashes](world-load-checks.json) identify the
  tested inputs. Inspected the forest capture; both runs continued rendering
  and finished with terrain meshing complete.
  Initial attempt `bsl-distance19-1` rendered at distance 19 but failed the
  framebuffer-size guard (3456×2104 window content versus 3456×2168 requested).
  Retained that failure and switched subsequent runs to borderless mode.

Full local logs, input manifests and run outputs are retained in
`build/remote-fixes-evidence/`. The checked-in [profiler capture](profiler-chart.png)
records the original crash trigger. No performance improvement is claimed.

## World-load issue #6 and PR #7

`main` already contains `ShaderPipelineCacheMixin` and `BlockingPipelineCompilation`:
blocking render-thread misses use a direct executor whenever a pack is selected.
This also covers misses outside an active pack frame. PR #7 restricts the same
policy to active pack rendering and is superseded by this existing implementation.
The regression test above validates the dependency-cycle fix. Real-client load
results are recorded separately; they do not reproduce the reporter's exact M4 Max
hardware or original world.

## Distant Horizons issue #5 — remains open

Source investigation confirms the routing mismatch in DH **3.3.4**, commit
`eb1007cb6cc3e0419effe2b40ab485cf24e55b43`:

1. [BlazePostProcessUtil](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/eb1007cb6cc3e0419effe2b40ab485cf24e55b43/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/util/BlazePostProcessUtil.java)
   supplies `vPosition` for fullscreen passes. The loader's geometry adapter
   requires the Minecraft `Position` contract.
2. [BlazeVanillaFadeRenderer](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/eb1007cb6cc3e0419effe2b40ab485cf24e55b43/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/postProcessing/BlazeVanillaFadeRenderer.java)
   reads Minecraft's main color and depth textures, renders a fade texture, then
   calls the copy renderer to write it back to main color.
3. [BlazeDhCopyRenderer](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/eb1007cb6cc3e0419effe2b40ab485cf24e55b43/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/apply/BlazeDhCopyRenderer.java)
   selects `distanthorizons:copy`. The loader intercepts passes by main-color
   attachment identity and classifies this pipeline as `gbuffers_entities`.

A generic passthrough could avoid the adapter exception, but is insufficient:
pack scene/depth data live in separate targets and the final pack pass overwrites
main color. Correct compatibility needs explicit DH color/depth and composition
integration, including opaque/translucent ordering, resizing and reload. Validate
it with real DH LODs and the reporter's non-OpenGL routing adapter before closing
#5. This investigation did not run DH or claim a compatibility fix; production
shader routing was left unchanged.
