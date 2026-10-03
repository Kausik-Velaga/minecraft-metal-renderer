# Upstream interfaces and reference review

This project targets released Minecraft **26.3** (September 15, 2026), using its unobfuscated class names. Inspection used the official client JAR and RenderPearl classes supplied through Mojang's version manifest. Decompiled game source is ignored research material and is not part of the deliverable.

## Backend entry point and SDL

`net.minecraft.client.PreferredGraphicsApi#getBackendsToTry()` constructs graphics backend candidates. One required production mixin replaces that list with `MetalBackend` when Metal is enabled. The 26.3 `GpuBackend` contract has `loadLibrary`, `unloadLibrary`, `createWindow`, and `createDevice` methods. It no longer uses GLFW window hints.

`MetalBackend#createWindow` calls SDL with `SDL_WINDOW_METAL`. `createDevice` returns `FrontendGpuDevice(new MetalDevice())`. `MetalSurface` uses `SDL_Metal_CreateView`, `SDL_Metal_GetLayer`, and `SDL_Metal_DestroyView`; SDL supplies the native Cocoa view and Metal layer. No OpenGL window or compositor is created.

`-Dmetal.enabled=false` preserves the vanilla candidate list. Enabled Metal initialization failures are reported rather than silently changing graphics backend.

## RenderPearl frontend and backend ownership

Minecraft 26.3 exposes public contracts under `com.mojang.renderpearl.api` and backend contracts under `com.mojang.renderpearl.backend.api`. `FrontendGpuDevice`, `FrontendCommandEncoder`, and `FrontendRenderPass` handle public validation and forward operations to a backend. The Metal implementation uses this frontend instead of replacing it.

`PipelineBuilder` and `GlslCompiler` compile GLSL through shaderc, apply includes and defines, link stage interfaces, and assign numeric resource bindings. They pass linked `SpvModule` objects and `BackendRenderPipeline.CreateInfo` to the backend. `MetalShaderCompiler` consumes those modules through SPIRV-Cross and maps frontend bindings into Metal slots. Pipeline creation includes vertex strides and instance step rates, optional depth state, sparse color targets, and push-constant size.

The actual Vulkan implementation provides behavioral references for numeric uniform updates, buffer slices, direct and indirect drawing, per-mip clearing, timestamp calibration, and resource ownership. A command encoder records into the device's current submission. CPU mapping does not implicitly wait for GPU writes; callers synchronize readback with fences. Already-recorded work must keep its GPU resources alive after a wrapper is released.

End-of-frame order remains surface blit, command submission, then surface present. Metal schedules `presentDrawable` while recording the blit, before the command buffer is committed.

## New terrain submission and Improved Transparency

`LevelRenderer.prepareChunkRendersIndirect` groups section draws into indexed indirect command buffers. Each command carries index count, instance count, first index, base vertex, and base instance. `ChunkSectionsToRender.DrawIndirect` binds section position/visibility as instance-rate vertex buffer 1, then calls `drawIndexedIndirect`. Terrain can select this route when nonzero first instance and indirect-draw limits are advertised. The natural gameplay test asserts that this route actually runs.

`OitRenderPassProvider`, `OitPipelineSet`, and `RenderPipelines` define three transparency stages:

| Stage | Resources and required behavior |
| --- | --- |
| Depth bounds | One `RGBA32_FLOAT` target, cleared to `(-Float.MAX_VALUE, 0, 0, 0)`, MAX blending for RGB and alpha |
| Transmittance | Two `RGBA16_FLOAT` targets with additive blending; GLSL output array `vec4 coeff[2]` spans both attachments |
| Accumulation | One additive `RGBA16_FLOAT` target; nearest sampling of coefficients and depth bounds |
| Composite | Premultiplied alpha into the main color target; explicit fragment depth output |

The stages sample previous targets using ordinary texture fetches; they do not require input attachments, fragment interlock, compute, or storage images. Boat water masks use a depth-only pass. Clouds switch from a color-write-disabled depth blit to MAX-blended geometry inside a pass.

Both Mojang's Vulkan and OpenGL backends enable `isExplicitDepthRequired` on Apple Silicon. The Metal device does the same, causing the frontend to define `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`. Its zero-to-one depth flag also produces `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, required by OIT's depth reconstruction.

## MetalRender 26.1 review

Reference: [webblepebbles/MetalRender, branch 26.1](https://github.com/webblepebbles/MetalRender/tree/26.1), inspected commit `6c675d1de792838770681aff9d1a7e7a54a0dae9`.

The reference is a hybrid renderer: terrain hooks bypass vanilla draw groups, native code draws selected content through handwritten Metal shaders, and IOSurface-backed textures are composited into OpenGL. Its native files are `src/main/resources/native/metalrender.mm` and `meshshader.mm`; its Java bridge is under `nativebridge`, and `render/IOSurfaceBlitter.java` handles the OpenGL composition path.

Useful concepts examined include shared buffers for dynamic uploads, GPU-private render resources, three in-flight resource slots, completion handlers, autorelease pools, and explicit native teardown. Those concepts informed the independent implementation; the hybrid terrain interception, handwritten terrain-specific shaders, and IOSurface/OpenGL composition are not used.

The reference uses the custom **Pebbles_boon Software Licence**, including same-license and source-availability requirements for distributed derivative works. The scoped [license and provenance review](license-and-provenance.md) found no distinctive implementation matches against the inspected reference source; this is a bounded comparison, not a guarantee that all possible overlap has been excluded. The reference's license would still apply if protected implementation were incorporated.

## Source references

- [Mojang's version manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json): official 26.3 client and bundled library metadata.
- [Fabric API 0.161.0+26.3 metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.161.0+26.3/fabric-api-0.161.0+26.3.pom): pinned Fabric modules, including client gametest 6.0.7+4be74c3f5d.
- [SDL Metal view API](https://wiki.libsdl.org/SDL3/SDL_Metal_CreateView): native Metal view ownership.
- [Apple Metal feature tables](https://developer.apple.com/metal/Metal-Feature-Set-Tables.pdf): native format, blending, and GPU-family capabilities.

The backend calls Metal directly; it does not route rendering through MoltenVK.

## Shader-loader format references

The initial loader targets the documented shader-pack interface and the locally supplied BSL
10.1.8 default configuration. References used for the new loader include the public
[program and stage documentation](https://shaders.properties/current/reference/programs/overview/),
[rendering uniforms](https://shaders.properties/current/reference/uniforms/rendering/), and
[matrix uniforms](https://shaders.properties/current/reference/uniforms/matrices/).
No competing shader-loader implementation source was inspected for this addition. Minecraft's
own vertex formats, transforms, draw ordering, and RenderPearl contracts were inspected locally.
BSL source and generated translations remain excluded from the repository.
