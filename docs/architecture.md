# Architecture

## Integration

`PreferredGraphicsApiMixin` selects `MetalBackend` before window creation. The backend implements the public RenderPearl interfaces shipped with Minecraft 26.3. It creates an SDL window with `SDL_WINDOW_METAL` and returns Mojang's `FrontendGpuDevice` around `MetalDevice`.

```text
Minecraft / Fabric rendering
  → RenderPearl frontend (validation, shader compilation, resource bindings)
    → MetalDevice, MetalCommandEncoder, MetalRenderPass
      → MetalNative JNI
        → MTLDevice / MTLCommandQueue / Metal encoders
          → CAMetalLayer owned by SDL's Metal view
```

The game's own render graph and render pipelines drive GUI, terrain, entities, particles, and post-processing. Terrain uses Minecraft's section buffers and indexed indirect draw commands, including an instance-rate vertex stream for section position and visibility. The mod does not replace the world mesher.

## Java modules

| Class | Responsibility |
| --- | --- |
| `MetalBackend`, `MetalMod` | Early backend selection, SDL window creation, initialization |
| `MetalDevice` | RenderPearl backend device, capabilities, resource and pipeline creation |
| `MetalCommandEncoder`, `MetalRenderPass` | Copies, clears, attachments, numeric bindings, push constants, draws, submission |
| `MetalGpuBuffer`, `MetalGpuTexture`, `MetalGpuTextureView`, `MetalGpuSampler` | Resource metadata and explicit native ownership |
| `MetalTransientMemory`, `MetalFence`, `MetalQueryPool` | Upload allocation, submission retirement, GPU timestamps |
| `MetalShaderCompiler`, `MetalRenderPipeline`, `MetalMappings` | Linked SPIR-V translation, Metal binding table, graphics state |
| `MetalSurface` | SDL Metal view lifetime, drawable acquisition, presentation |
| `MetalNative` | Central JNI declarations and content-addressed native loading |

## Native modules

Native code is built as Objective-C++17 with ARC. An opaque handle registry rejects stale Java handles. Native calls use autorelease pools and translate C++/Objective-C failures into Java exceptions.

`MetalContext` owns the Metal command queue and current command buffer. `MetalResources` allocates and transfers buffers/textures. `MetalCommands` manages pipelines, bindings, draw calls, fences, and timestamp queries. `MetalPresentation` configures SDL's layer and records the final presentation pass. `MetalFormats` maps GPU formats into Metal formats.

## Shaders and pipeline state

The shader path is Minecraft GLSL → RenderPearl's shaderc compilation and SPIR-V linking → `MetalShaderCompiler` → SPIRV-Cross MSL → `MTLLibrary` → `MTLRenderPipelineState`.

Mojang's frontend applies shader defines, links vertex attributes and stage interfaces, and assigns numeric uniform bindings. The Metal backend consumes that linked SPIR-V and `BackendRenderPipeline.CreateInfo`; it does not redo the frontend's GLSL compilation. Vertex buffers use slots 0–15, uniform buffers use slots 16–29, and push constants use slot 30. Sampled textures, samplers, and texel buffers have reflected Metal bindings shared across stages.

MSL translations are cached by SPIR-V contents, stage, binding layout, and depth-output variant. Native pipeline variants account for actual attachment formats and depth presence. A shader that writes fragment depth keeps that output when a depth attachment exists; SPIRV-Cross generates a separate variant without the output for passes that omit depth. Resource reload can rebuild frontend pipelines, while unchanged shader contents can reuse the MSL translation cache.

Metal advertises zero-to-one depth and enables Mojang's Apple Silicon explicit depth-invariance workaround. Improved Transparency uses floating-point depth bounds with MAX blending, two simultaneous additive half-float transmittance targets, additive accumulation, and a premultiplied composite that writes fragment depth. Water masks also require depth-only passes. These use ordinary Metal render targets and sampling between passes.

## Commands and lifetime

Calls to `createCommandEncoder` share the device's current GPU submission. Multiple blit and render encoders record into one Metal command buffer; draws do not allocate or submit a command buffer each time. Push-constant bytes are copied when recorded and preserved if a render encoder must be restarted.

The native upload ring has three slots, and completion signals bound submissions in flight. Java transient allocations retire behind GPU fences. Frequently mapped buffers use shared storage; other buffers use private storage with ordered staging copies. Callers fence GPU writes before CPU buffer reads, matching RenderPearl's explicit synchronization contract.

Java wrappers guard closed handles and provide idempotent close operations. Mapped buffers cannot close until their mapped views close. Metal command buffers retain GPU objects referenced by recorded work. Shutdown drains outstanding submissions. Pipeline compilation may be requested concurrently by the frontend; the Metal translator serializes its cache and compiler lifetime, while rendering commands follow render-thread ownership.

Timestamp queries use real Metal counter samples. On Apple GPUs with stage-boundary sampling, a marker suspends and restores the render pass around a small ordered blit encoder. Completion callbacks resolve counter data; three sample-buffer slots and per-index generations prevent older submissions from overwriting reused queries. GPU encoder fences order render/blit transitions. Ordinary submission waits on the CPU only when all three in-flight slots are occupied.

## Presentation

`MetalSurface` creates an SDL Metal view and obtains its `CAMetalLayer` through `SDL_Metal_GetLayer`. SDL owns the native view integration and backing-scale changes. The native backend configures three drawables, the framebuffer dimensions, and FIFO versus immediate presentation.

Minecraft calls surface blit, command submission, then surface present. The blit records the final color target into the drawable and schedules `presentDrawable` before commitment. The final present call releases the application's drawable reference. Closing the surface releases its native resources and calls `SDL_Metal_DestroyView`.
