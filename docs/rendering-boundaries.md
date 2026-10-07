# Renderer, shader runtime and scene optimization boundaries

Accepted direction dated **October 3, 2026**, based on the [source review](performance-source-review.md)
and the current local implementation. The repository now builds three separate mod artifacts.
The first scene contract and provider are implemented; the full performance goal remains active.

## Accepted direction

Use three logical components in this repository and, when implemented, three separately installable
mods: the Metal backend, the shader-pack runtime, and a narrowly scoped client scene optimization
mod. “Scene optimizer” describes a responsibility here; the public name remains undecided.

Ownership follows **the information required to establish correctness**, not CPU versus GPU,
threads versus synchronous work, or whether a change improves FPS. All three layers may use CPU
workers, and scene algorithms may have GPU implementations.

| Component | Question it answers | Required knowledge |
| --- | --- | --- |
| Metal renderer/backend | How can this declared GPU work execute correctly and efficiently on Metal? | Device capabilities, resources, state, dependencies and presentation |
| Shader loader/runtime | What rendering does this pack require to produce its intended image? | Pack programs/options, material conventions, uniforms, pass order and history |
| Scene optimizer | How can Minecraft supply the required scene with less repeated work? | Sections, models, entities, visibility, change events and batching rules |

Other projects use “renderer” for a broader world-rendering system. Our current renderer is a GPU
backend. A graphics-API replacement alone does not replace the scene preparation addressed by
projects such as Sodium and Nvidium.

## Responsibility matrix

| Work | Primary owner | Collaboration boundary |
| --- | --- | --- |
| Metal buffers, textures, storage modes and native allocation | Backend | Callers declare usage/lifetime; backend retains native resources until GPU completion |
| Persistent terrain geometry, section residency and dirty ranges | Scene optimizer | Owns meshes and arena/suballocation policy through opaque GPU resources; backend implements allocation/upload machinery |
| Chunk meshing and model caches | Scene optimizer | Loader declares semantic attributes/material mapping; backend does not read block states |
| Camera visibility, entity bounds and per-view draw lists | Scene optimizer | Stable world/view inputs; loader can request additional views |
| Shadow matrices and caster-policy eligibility | Shader runtime | Supplies light view, required volume and restrictions; scene provider finds matching geometry |
| Shadow traversal and draw preparation | Scene provider | Optimized provider when installed, vanilla adapter otherwise; exactly one provider owns a traversal |
| GPU culling and draw-list construction | Scene optimizer for algorithm/data | Backend executes declared compute/indirect work through supported native mechanisms |
| Entity, particle, text and UI preparation | Scene optimizer | Preserve ordering, custom behavior and pack semantics; retain conservative fallbacks |
| Particle simulation fast paths | Optional scene feature | Client-only, explicit supported behavior; unrelated server ticking stays outside |
| Pack discovery, includes, options, macros and programs | Shader runtime | Produces immutable plans and diagnostics |
| Legacy shader language and material adaptation | Shader runtime | Converts pack conventions to supported frontend inputs |
| GLSL compilation provided by RenderPearl | Existing game frontend | Reuse its contract, avoid a redundant compiler owner |
| Generic SPIR-V-to-MSL translation and native pipeline creation | Backend | Complete cache keys and safe compiler lifetime; no BSL interpretation |
| Pass order, logical reads/writes, flipping and temporal history | Shader runtime | Declares semantics and dependencies |
| Native encoding, barriers, attachment load/store and submission | Backend | Optimize only within declared resource/ordering guarantees |
| Whether a mip chain or old target contents are required | Shader runtime for pack passes | Backend implements requested generation/preservation/discard efficiently |
| General clears, copies and mipmap primitives | Backend | Preserve filtering/format behavior and capability-based fallback |
| Presentation and frames in flight | Backend | One active device/presentation owner |
| World generation, saves, networking and server simulation | Game and companion mods | Outside these three components' initial scope |

## Contracts at the difficult boundaries

### Pack requirements and geometry

The loader publishes versioned requirements: semantic vertex attributes, immutable material mapping,
view types, ordering constraints and any proven vertex-displacement bounds. The scene provider
returns a supported layout and geometry handles. Requirement changes invalidate dependent meshes
before the new pack uses them.

The provider can compute normals/tangents while the loader defines their pack meaning. The loader
assigns material IDs; the mesher emits them. Unknown behavior takes a conservative path. Avoid
mutable runtime lookups per vertex and Metal-specific offsets in pack policy.

### Multiple views and visibility

A visibility result belongs to a view and scene generation, not globally to a frame. Camera,
shadow and future reflection/voxelization views can require different geometry. The loader supplies
pack-dependent requirements and the scene provider produces separate immutable selections.

Camera occlusion cannot simply remove shadow casters. A direct-shadow receiver volume also does
not prove safety for voxelization or indirect lighting. The backend executes resulting work without
guessing which world objects a pack needs.

### Logical frame plan and physical execution

The shader runtime specifies images read/written, observable previous contents, persistent history,
and required ordering. Use existing RenderPearl graph/resource/pass interfaces where they suffice.
Add a small contract only for a concrete missing operation.

The backend chooses native encoders, storage and synchronization. It may merge compatible work or
avoid preservation only when the contract proves this safe. A full-screen-looking pass alone does
not justify discarding an attachment, reordering blending or removing history. Arbitrary pack passes
are not assumed fusible or parallel. Physical aliasing requires explicit lifetime information;
otherwise allocate conservatively rather than interpreting pack names in native code.

### Parallel work and publication

Scene workers consume stable snapshots and return versioned results. The owning coordinator
publishes them, rejecting obsolete world/material generations. Block changes, reloads, pack switches
and world unloads trigger invalidation. Workers do not mutate the active visible-section list.

The loader can preprocess programs concurrently; the backend can compile independent pipelines
when compiler contexts/caches have safe ownership. Completion, cancellation and teardown are part
of the contract. Avoid workers synchronously waiting on children queued to the same saturated pool.

Coordinate bounded worker budgets across preparation and compilation, considering the integrated
server and chunk workers. Each mod should not independently consume all cores. Separate JARs do
not require separate processes, devices, frame copies or GPU queues.

## Dependency and packaging

Prefer RenderPearl. Where scene-level interfaces are missing, use a small internal API module with
one runtime class owner; do not bundle duplicate copies of registry/interface classes into each
mod. Initially the existing backend artifact can host it because the loader already requires Metal.
Hosting a contract does not transfer ownership of scene policy to the backend. If standalone,
backend-independent optimizer distribution becomes necessary, move the host to an explicit shared
dependency rather than duplicating classes. No fourth installed mod is needed for the initial split.

The API contains capabilities, versioned requirements and provider registration, not another graphics
API. The optimizer registers a scene provider; the loader retains a vanilla-provider fallback and
uses the game GPU device. The backend has no dependency on optimizer or loader implementation
classes. Select exactly one provider to prevent duplicate traversal and overlapping mixins.

| Installed combination | Intended behavior |
| --- | --- |
| Metal only | Existing vanilla scene through Metal |
| Metal + loader | Shader support using the vanilla scene adapter |
| Metal + optimizer | Optimized scene with ordinary shading |
| Metal + loader + optimizer | Optimized scene satisfying pack requirements |

All four combinations pass short packaged launch checks with the initial implementation. Full
gameplay/performance qualification is ongoing. The loader currently requires Metal. Optimizer-only
operation on another backend, or combining multiple scene replacements,
requires explicit integration and testing.

## Current code and migration seams

| Existing code | Treatment |
| --- | --- |
| `MetalDevice`, `MetalRenderPass`, `MetalShaderCompiler`, `MetalTransientMemory`, `native/Metal*` | Keep backend ownership, including generic compiler concurrency, uploads and indirect encoding |
| `ShaderPack`, `ShaderProperties`, `PackPrograms`, `ShaderCompatibilityCompiler`, `FrameUniforms` | Keep pack/runtime ownership |
| `PackRenderTargets`, `PackMipmaps`, `PackDepthMerge`, `UnusedMipmaps` | Keep pack semantics in loader; extract generic backend primitives only where useful |
| `ShadowRenderer` | Separate pack/light policy from traversal and draw preparation; keep vanilla fallback |
| `TerrainShaderGeometry`, `TerrainVertexWriter`, feature/model adapters | Separate pack requirements from provider-specific construction; move optimized construction when it exists |
| `PackPipeline` and compiler caches | Keep stage-specific ownership; coordinate requests through completion contracts |
| Profiling/benchmarks | Each owner emits costs; test tooling correlates frame, view and generation |

The initial split removes `ShadowRenderer`’s temporary visible-section-list replacement: it requests
an independent selection and prepares it through a scoped iterator adapter. Remaining coupling:
`TerrainShaderGeometry` extends vanilla meshing with a fixed pack vertex layout;
`PackPipeline` compiles inline and joins immediately; both compiler layers synchronize shared state.
These are migration seams. Moving files alone will not improve performance, and current locks
protect real invariants that a parallel design must replace correctly.

## Separation tradeoffs and migration order

Three mods allow independent testing and installation of backend execution, scene preparation and
pack support. Costs include contract versioning, fallback maintenance and more combinations. A
two-JAR interim distribution can house the scene component as an internal renderer module with a
toggle, preserving the same logical boundaries. It saves packaging work but couples releases.

For a long-term split, three mods are reasonable because scene preparation is a substantial domain.
Avoid a catch-all optimization module: backend fixes and pack correctness stay with their owners.
Simulation/world generation remain companion concerns unless a separate measured need changes scope.

1. Define scene/view/material-generation contracts around the existing vanilla adapter without
   changing behavior. Keep the existing two-mod combination working.
2. Introduce per-view preparation instead of shared mutable visibility, then an optional scene
   provider tested with ordinary shading and BSL.
3. Add persistent metadata, precise invalidation, compatible batching and bounded jobs where
   measurements justify them. Evaluate GPU culling against its additional pass/synchronization cost.
4. Improve loader planning/compilation and backend execution independently so results remain attributable.

Validate preloaded forest movement, natural-cavern mining, new-terrain exploration, dense entities
and reload/unload. Measure frame-time distributions, CPU preparation, GPU passes, waits, uploads and
visibility counts. Preserve native resolution and BSL defaults. The split itself is not progress
toward 120 FPS until measured work decreases.

## Implementation readiness

The three-mod direction and native-resolution/current-BSL-default quality constraint are settled.
No additional user decision blocks implementation. The optimizer uses the internal working name
`scene-optimizer`; public branding is not an architecture dependency. The subsequent implementation
goal is active. Local builds and benchmark clients do not update the installed launcher profile or
publish a release.

The first implementation milestone establishes packaged forest and cavern timing baselines from
fixed fixtures, then introduces the contract and vanilla adapter with unchanged rendering. The
second adds the optional optimizer artifact and validates all four installation combinations before
moving a traversal or enabling a fast path. Subsequent work measures and addresses the largest
remaining scene, loader or backend cost, one attributable change at a time.

A completion goal should require working ownership boundaries, the four tested installations,
bounded/cancellable worker jobs, safe world/material generation invalidation, and regression coverage
for reload, resizing, world unload, shadow visibility, walking and server-confirmed mining. The
performance target remains sustained 120 FPS (8.33 ms average frame budget) in native-resolution
forest walking and natural-cavern mining with BSL defaults. Record median/p95/p99 and long stalls,
repeat control/candidate measurements, and preserve visual output. Packaging or thread count alone
does not satisfy the goal; if the target is not reached, report the measured remaining bottleneck.

The [benchmark preparation record](development.md#benchmark-reproducibility-and-retention) documents
input identities, diagnostic timing exclusions and evidence retention. Baseline collection is planned
work in the first milestone, not a reason to delay starting the implementation or request new choices.

## Initial implementation evidence

The backend artifact hosts `dev.kausik.scene` exactly once. `SceneViews` selects one provider,
checks world/material generations and adapts explicit ordered selections to Minecraft's own draw
preparation without replacing camera visibility. The vanilla provider remains available when the
optimizer is absent. The loader still owns light matrices, shadow-receiver eligibility and material
mapping. Generation changes invalidate provider metadata; selections are prepared immediately on
the render thread and do not grant worker access to live Minecraft sections.

The `scene-optimizer` artifact registers a provider that retains 64-block spatial groups until
section positions or world/material identity change. It tests live mesh readiness, conservatively
rejects whole groups and restores original section order. Its terrain-layer cache applies only to
exact vanilla mesh instances; custom meshes retain vanilla behavior. This first stage removes some
repeated selection/preparation work. A subsequent exact-frustum classifier bulk-accepts fully
contained cells and keeps the original per-section test at boundaries. An opt-in metadata cache
retains allocation slices and exact draw/section-info records. Actual allocation changes, resort,
mesh disposal and allocator closure invalidate slices; cached missing allocations also invalidate.
Spatial index construction now runs on one bounded worker. The render thread snapshots immutable
bounds and opaque section references; the worker never reads live section state or performs GPU
operations. New world/material/position identities cancel obsolete work, and publication is accepted
only for the current identity. Fresh vanilla selection remains available while a build is pending
or fails. The queue holds at most one replacement, and an idle worker retires after ten seconds.

Initial verification passed the full build/check suite, 583 BSL pipeline compilations, randomized
spatial-selection equivalence checks, and all four packaged launches. Loader lazy-clear tests compare
55 actual GPU observations covering mip levels, partial writes, read-before-write, depth copies,
flips and history. Backend completion telemetry does not insert GPU markers or change hazard mode.
The CPU indirect-command prototype subsequently passes 71 GPU equivalence/publication checks in each
of four CPU/reuse combinations, including bounded disjoint camera/shadow snapshots and overlapping
map invalidation. The complete build and all 583 BSL pipeline checks passed again after these changes.
These are correctness/launch results, not a 120 FPS claim.

Local logs and measurements for this implementation are under `.research/three-mod-implementation/`.
The first native packaged forest control measured 28.84 FPS (median 34.363 ms, p95 39.718 ms,
p99 43.450 ms) across six walking legs. Tests of lossless compression eligibility, lazy clears,
scene preparation and command encoding follow, with native resolution and BSL defaults fixed.
