# Performance-mod source research

Reviewed **October 3, 2026**. This is a focused review of selected implementation paths in nine
projects, followed by an assessment for this repository. It is not a full audit, a benchmark,
or a certification of compatibility with Minecraft Metal. No runtime implementation changed.

Current Minecraft 26.3 branches were preferred over repository defaults, several of which still
point to older versions. Exact snapshots are recorded below; a development branch was not assumed
to match a published binary. FerriteCore's inspected source is its current `26.1` branch, not a
verified 26.3-specific revision.

## Research and license boundary

The user authorized reading source to record approaches and logic without reusing implementation.
These notes contain original prose and links, not copied code, translated implementations, or
line-by-line pseudocode. Temporary source downloads were removed after review; the pinned source links below preserve
provenance. None were bundled in the mods.

MIT and GNU GPL/LGPL projects were eligible for this research. Reading GPL/LGPL code does not by
itself relicense unrelated work, but incorporating protected implementation can create obligations.
Renaming variables, translating languages, or rewriting code in prose does not establish independence.
This is source-informed research, **not a clean-room process**. The
[U.S. Copyright Office](https://www.copyright.gov/help/faq/faq-protect.html) distinguishes ideas and
methods from their protected expression; that is not legal clearance for a particular future
implementation. File notices and dependency licenses must also be considered.

| Project | Reviewed terms | Scope of this pass |
| --- | --- | --- |
| ImmediatelyFast | LGPL-3.0-or-later, also stated in file headers | Current rendering paths |
| Lithium | LGPL-3.0 | Sleeping and invalidation |
| Iris | LGPL-3.0; dependency notice flags AGPL glsl-transformer; one geometry derivation also attributes a CC BY-SA source | Orchestration and culling policy; no implementation/dependency imported |
| Nvidium | LGPL-3.0 | Terrain orchestration and storage |
| C2ME | MIT except proprietary `c2me-opts-accel-opencl/` | MIT scheduling/chunk-system paths only |
| ScalableLux | LGPL-3.0 | Lighting job scheduling |
| More Culling | GPL-3.0 | Model/face visibility |
| FerriteCore | MIT | Compact state indexing |
| AsyncParticles | LGPL-3.0; eligibility code credits older LGPL Sodium-derived work | GPU preparation and fallback selection |
| Sodium | PolyForm Shield 1.0.0, including restrictions on competing products across platforms | Documentation/release notes only |
| Entity Culling | Custom tr7zw Protective License restricts commercial advantage/compensation | Documentation only |

Excluding the restricted implementations is a conservative scope choice for a potentially competing
project, not a conclusion that viewing public code is unlawful. Our earlier provenance review already
records historical Sodium and other renderer source exposure; this pass does not erase that history.

## Implementation findings

### ImmediatelyFast: scene batching and backend workarounds are distinct

The 26.3 batching hook makes render groups eligible for reordering. Maps share texture atlases;
sign text is retained in atlas slots, unused slots are reclaimed, and resource reload invalidates
the cache. These reduce repeated preparation and differences between draws that inhibit batching.

The Apple upload workaround specifically intercepts the **OpenGL** backend: complete buffer
replacement takes a different upload route from partial updates. Installing it would not apply
that fix to our Metal backend.

**Interpretation:** scene grouping, atlas policy and retained text belong with scene optimization.
Metal upload policy belongs in the backend. Reordering must be permitted by material/pass semantics,
especially for transparency and packs; an isolated unconditional hook is not our correctness proof.

### Iris: shader support is a rendering runtime

Composite passes retain programs, outputs, dimensions, viewport settings, mipmap requirements and
which side of paired targets to read. Written targets change roles subject to pack directives.
Compute work precedes its associated draw with a dependency before consumers use its results.

Shadow selection uses a caster volume derived from camera visibility and light direction. Pack
policy or detected voxelization can select broader distance-based handling. The tight-volume
documentation explicitly excludes some indirect-lighting uses from its assumptions.

**Interpretation:** the loader owns pass meaning, history and culling eligibility; the scene provider
performs world selection; the backend implements resource operations and dependencies. Maturity is
not evidence that every optimization is implemented: this composite path still notes an opportunity
to avoid regenerating unchanged mipmaps.

### Nvidium: GPU-driven terrain requires persistent scene data

Section builds populate persistent geometry storage and compact section/region metadata. The frame
path combines coarse region selection, depth-based region/section visibility, GPU-generated indirect
commands and optional temporal reuse. Mesh tasks are submitted in groups. CPU region management
and updates remain; GPU-driven does not mean CPU-free.

**Interpretation:** the opportunity is removing recurring preparation and unnecessary geometry through
a persistent world representation. Repackaging an existing draw list into indirect commands alone
does not achieve this. Section management and visibility are scene work; hardware command encoding
is backend work. NVIDIA requirements and documented disabling with active Iris shaders prevent
treating Nvidium's speed as an expectation for native-resolution BSL on M3.

### C2ME: schedule dependencies, urgency and conflicts

The MIT scheduler associates tasks with world positions, tracks priority from chunk demand, and
raises urgency around a synchronous load. It can consolidate priority updates. Lock tokens identify
conflicting uses by owner, position and operation category. The chunk system advances statuses with
dependencies and routes selected publication/notification work to the main-thread executor.

**Interpretation:** useful concurrency requires ownership, dependencies and priority, not just a larger
pool. Render preparation can apply those general principles to stable snapshots and versioned
results. Reimplementing world generation, storage and chunk lifecycle is outside the proposed client
scene mod. The proprietary OpenCL subtree was not inspected.

### ScalableLux: spatial conflicts define safe parallelism

Block changes, section changes and edge checks accumulate into per-chunk work. Concurrent jobs
acquire scheduling tokens for their affected neighborhood, coordinating overlapping work while
allowing independent areas to progress. Propagation engines are pooled; completion futures expose
readiness. The inspected client path uses the simpler queue.

**Interpretation:** thread safety depends on affected data and job lifetime. Minecraft block/skylight
propagation belongs to world simulation, separate from shader-pack shadows and lighting effects.
This is a useful scheduling lesson, not a reason to move lighting propagation into the loader.

### Lithium: wake-up events remove recurring simulation work

The furnace path suspends ticking when a supported furnace is inactive, restoring it on relevant
inventory changes or loading. The hopper path tracks inventories and changes, with conditions for
sleeping and listeners for inventory/entity changes. Special interactions, including comparator
behavior, constrain when work can be skipped.

**Interpretation:** retain results until relevant events invalidate them. In rendering, use explicit
mesh/material/resource generations. This does not justify skipping server simulation because an
object is off-screen. Existing simulation optimizers should remain companions.

### More Culling: model semantics establish whether a face is unnecessary

The inspected path combines block-specific rules, model opacity and face shapes. It tests whether
part of a face remains uncovered by its neighbor and caches shape-pair decisions. Empty and fully
covering shapes permit early decisions. Separate foliage modes use stronger heuristics and are not
equivalent to exact coverage tests.

**Interpretation:** this belongs in scene/meshing work; Metal cannot infer it from arbitrary vertices.
Shader displacement, cutout alpha and auxiliary views can invalidate apparent removal opportunities.
Pack requirements must participate in eligibility. Preserve BSL defaults rather than enabling
approximate leaf removal.

### FerriteCore: compact representation replaces object-heavy lookup structures

The state map indexes combinations of block properties into shared storage. Changing one property
adjusts its part of the index, avoiding a separate general-purpose transition map for every state.
Compact and bit-oriented key strategies trade space against access operations.

**Interpretation:** contiguous compact section metadata and draw records may outperform repeatedly
walking object graphs. Replacing Minecraft's global state representation is broader than our scene
mod and would duplicate FerriteCore. This inspection does not establish correspondence to a distinct
26.3 release binary.

### AsyncParticles: publish reusable state and transform it on the GPU

The Vulkan path uses reusable source and submission slots. Tick-produced particle data feeds
per-frame GPU work with camera/interpolation inputs; an explicit dependency makes the generated
buffer available as vertex input. The normal result accessor returns prepared metadata rather than
reading generated vertices back to the CPU. Reuse and resize have distinct lifetime handling.
Eligibility checks keep unsupported/custom particle behavior off the fast path.

**Interpretation:** the scene layer owns particle representation and eligibility; the backend owns
generic compute, barriers and native lifetime; the loader supplies pack attribute/material needs.
Existing OpenGL/Vulkan integration does not imply our Metal backend is supported automatically.

## Application to this repository

The strongest principles are persistent scene data, precise invalidation, conservative multi-view
visibility, compatible batching, dependency-aware jobs and less CPU vertex expansion. They require
different owners. A third mod should own **client scene preparation**, not every performance change.
See [rendering boundaries](rendering-boundaries.md) for the proposed split and migration seams.

No code was imported into production, no outside binaries were installed, and no performance gain
was measured during this research pass.

## Revision and source register

Links below identify the exact snapshots and selected files supporting these observations.
Only relevant portions were inspected; the register does not claim exhaustive review.

### ImmediatelyFast

Branch `26.3`, revision `b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3`, commit date 2026-09-24.

Licenses: [LICENSE](https://github.com/RaphiMC/ImmediatelyFast/blob/b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3/LICENSE).

- [common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/enhanced_batching/MixinRenderTypeFeatureRenderer_Group.java](https://github.com/RaphiMC/ImmediatelyFast/blob/b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3/common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/enhanced_batching/MixinRenderTypeFeatureRenderer_Group.java)
- [common/src/main/java/net/raphimc/immediatelyfast/feature/map_atlas_generation/MapAtlas.java](https://github.com/RaphiMC/ImmediatelyFast/blob/b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3/common/src/main/java/net/raphimc/immediatelyfast/feature/map_atlas_generation/MapAtlas.java)
- [common/src/main/java/net/raphimc/immediatelyfast/feature/sign_text_buffering/SignTextCache.java](https://github.com/RaphiMC/ImmediatelyFast/blob/b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3/common/src/main/java/net/raphimc/immediatelyfast/feature/sign_text_buffering/SignTextCache.java)
- [common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/fix_slow_buffer_upload_on_apple_gpu/MixinGlCommandEncoder.java](https://github.com/RaphiMC/ImmediatelyFast/blob/b42e5762ec99e26f99d2d47fa256cb6f7e5a8db3/common/src/main/java/net/raphimc/immediatelyfast/injection/mixins/fix_slow_buffer_upload_on_apple_gpu/MixinGlCommandEncoder.java)

### Iris

Branch `26.3`, revision `7521bb1b1675138bc567586041d0cb857771933e`, commit date 2026-10-03.

Licenses: [LICENSE](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/LICENSE), [LICENSE-DEPENDENCIES](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/LICENSE-DEPENDENCIES).

- [common/src/main/java/net/irisshaders/iris/targets/BufferFlipper.java](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/common/src/main/java/net/irisshaders/iris/targets/BufferFlipper.java)
- [common/src/main/java/net/irisshaders/iris/pipeline/CompositeRenderer.java](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/common/src/main/java/net/irisshaders/iris/pipeline/CompositeRenderer.java)
- [common/src/main/java/net/irisshaders/iris/shadows/frustum/advanced/AdvancedShadowCullingFrustum.java](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/common/src/main/java/net/irisshaders/iris/shadows/frustum/advanced/AdvancedShadowCullingFrustum.java)
- [common/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java](https://github.com/IrisShaders/Iris/blob/7521bb1b1675138bc567586041d0cb857771933e/common/src/main/java/net/irisshaders/iris/shadows/ShadowRenderer.java)

### Nvidium

Branch `26.3`, revision `27ceb7299e8a8de9ccca69bb4700909e600ea10f`, commit date 2026-09-23.

Licenses: [LICENSE.txt](https://github.com/MCRcortex/nvidium/blob/27ceb7299e8a8de9ccca69bb4700909e600ea10f/LICENSE.txt).

- [src/main/java/me/cortex/nvidium/RenderPipeline.java](https://github.com/MCRcortex/nvidium/blob/27ceb7299e8a8de9ccca69bb4700909e600ea10f/src/main/java/me/cortex/nvidium/RenderPipeline.java)
- [src/main/java/me/cortex/nvidium/managers/SectionManager.java](https://github.com/MCRcortex/nvidium/blob/27ceb7299e8a8de9ccca69bb4700909e600ea10f/src/main/java/me/cortex/nvidium/managers/SectionManager.java)
- [src/main/java/me/cortex/nvidium/renderers/PrimaryTerrainRasterizer.java](https://github.com/MCRcortex/nvidium/blob/27ceb7299e8a8de9ccca69bb4700909e600ea10f/src/main/java/me/cortex/nvidium/renderers/PrimaryTerrainRasterizer.java)
- [src/main/java/me/cortex/nvidium/managers/RegionVisibilityTracker.java](https://github.com/MCRcortex/nvidium/blob/27ceb7299e8a8de9ccca69bb4700909e600ea10f/src/main/java/me/cortex/nvidium/managers/RegionVisibilityTracker.java)

### C2ME

Branch `dev/26.3.0`, revision `33a081d17cd61665a4e85ca39aa80e69526088b2`, commit date 2026-10-01.

Licenses: [LICENSE.md](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/LICENSE.md), [licenses/LICENSE-ARR.txt](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/licenses/LICENSE-ARR.txt), [licenses/LICENSE-MIT.txt](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/licenses/LICENSE-MIT.txt).

- [c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/SchedulingManager.java)
- [c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/LockTokenImpl.java](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/c2me-base/src/main/java/com/ishland/c2me/base/common/scheduler/LockTokenImpl.java)
- [c2me-rewrites-chunk-system/src/main/java/com/ishland/c2me/rewrites/chunksystem/common/TheChunkSystem.java](https://github.com/RelativityMC/C2ME-fabric/blob/33a081d17cd61665a4e85ca39aa80e69526088b2/c2me-rewrites-chunk-system/src/main/java/com/ishland/c2me/rewrites/chunksystem/common/TheChunkSystem.java)

### ScalableLux

Branch `ver/26.3.0`, revision `fa8b28b1551a1ec9c23fa2d26233e61194c3314e`, commit date 2026-09-16.

Licenses: [LICENSE](https://github.com/RelativityMC/ScalableLux/blob/fa8b28b1551a1ec9c23fa2d26233e61194c3314e/LICENSE).

- [src/main/java/ca/spottedleaf/starlight/common/thread/SchedulingUtil.java](https://github.com/RelativityMC/ScalableLux/blob/fa8b28b1551a1ec9c23fa2d26233e61194c3314e/src/main/java/ca/spottedleaf/starlight/common/thread/SchedulingUtil.java)
- [src/main/java/ca/spottedleaf/starlight/common/light/StarLightInterface.java](https://github.com/RelativityMC/ScalableLux/blob/fa8b28b1551a1ec9c23fa2d26233e61194c3314e/src/main/java/ca/spottedleaf/starlight/common/light/StarLightInterface.java)

### Lithium

Branch `develop`, revision `38104b18fa0f21d8e04e0599ce3ee2bb0566bd58`, commit date 2026-09-27.

Licenses: [LICENSE.md](https://github.com/caffeinemc/lithium-fabric/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/LICENSE.md).

- [common/src/main/java/net/caffeinemc/mods/lithium/mixin/world/block_entity_ticking/sleeping/furnace/AbstractFurnaceBlockEntityMixin.java](https://github.com/caffeinemc/lithium-fabric/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/world/block_entity_ticking/sleeping/furnace/AbstractFurnaceBlockEntityMixin.java)
- [common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/hopper/HopperBlockEntityMixin.java](https://github.com/caffeinemc/lithium-fabric/blob/38104b18fa0f21d8e04e0599ce3ee2bb0566bd58/common/src/main/java/net/caffeinemc/mods/lithium/mixin/block/hopper/HopperBlockEntityMixin.java)

### More Culling

Branch `master`, revision `23e0f29f0f6b146916bd293a79e8fafcbba596af`, commit date 2026-10-03.

Licenses: [LICENSE](https://github.com/fxmorin/moreculling/blob/23e0f29f0f6b146916bd293a79e8fafcbba596af/LICENSE).

- [common/src/main/java/ca/fxco/moreculling/utils/CullingUtils.java](https://github.com/fxmorin/moreculling/blob/23e0f29f0f6b146916bd293a79e8fafcbba596af/common/src/main/java/ca/fxco/moreculling/utils/CullingUtils.java)
- [common/src/main/java/ca/fxco/moreculling/mixin/blockstates/BlockStateBase_moreMixin.java](https://github.com/fxmorin/moreculling/blob/23e0f29f0f6b146916bd293a79e8fafcbba596af/common/src/main/java/ca/fxco/moreculling/mixin/blockstates/BlockStateBase_moreMixin.java)

### FerriteCore

Branch `26.1`, revision `0cef1f2add1f1329aa6e690e8e292acd625c5c6d`, commit date 2026-03-24.

Licenses: [LICENSE](https://github.com/malte0811/FerriteCore/blob/0cef1f2add1f1329aa6e690e8e292acd625c5c6d/LICENSE).

- [Common/src/main/java/malte0811/ferritecore/fastmap/FastMap.java](https://github.com/malte0811/FerriteCore/blob/0cef1f2add1f1329aa6e690e8e292acd625c5c6d/Common/src/main/java/malte0811/ferritecore/fastmap/FastMap.java)
- [Common/src/main/java/malte0811/ferritecore/fastmap/CompactFastMapKey.java](https://github.com/malte0811/FerriteCore/blob/0cef1f2add1f1329aa6e690e8e292acd625c5c6d/Common/src/main/java/malte0811/ferritecore/fastmap/CompactFastMapKey.java)

### AsyncParticles

Branch `26.3`, revision `6718bc22f01166d646ae886f17e1832219d5550a`, commit date 2026-09-18.

Licenses: [LICENSE](https://github.com/Harveykang/AsyncParticles/blob/6718bc22f01166d646ae886f17e1832219d5550a/LICENSE).

- [common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/gpu_acceleration/GpuParticleBehavior.java](https://github.com/Harveykang/AsyncParticles/blob/6718bc22f01166d646ae886f17e1832219d5550a/common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/gpu_acceleration/GpuParticleBehavior.java)
- [common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/gpu_acceleration/vulkan/VkCompParticleRenderer.java](https://github.com/Harveykang/AsyncParticles/blob/6718bc22f01166d646ae886f17e1832219d5550a/common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/gpu_acceleration/vulkan/VkCompParticleRenderer.java)
- [common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/tick/AsyncTickerThread.java](https://github.com/Harveykang/AsyncParticles/blob/6718bc22f01166d646ae886f17e1832219d5550a/common/src/main/java/fun/qu_an/minecraft/asyncparticles/client/core/particle/tick/AsyncTickerThread.java)

### Restricted implementations not inspected in this pass

- [Sodium license at mc26.3-0.9.2](https://github.com/CaffeineMC/sodium/blob/mc26.3-0.9.2/LICENSE.md). The prior research used public release notes for its 2026 asynchronous culling and allocator changes.
- [Entity Culling license at ae786c8921de](https://github.com/tr7zw/EntityCulling/blob/ae786c8921de5ea52c06481dfa47180e42bbd54d/LICENSE-EntityCulling).
- C2ME OpenCL exclusion is stated in the pinned C2ME root license linked above. Its implementation was not downloaded for this review.
