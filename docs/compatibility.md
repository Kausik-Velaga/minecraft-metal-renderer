# Mod compatibility

## Current target

The runtime validation target is Minecraft 26.3 with Fabric Loader and Fabric API. The Metal backend does not require Sodium or Iris. The separate [validation record](validation.md) describes the scenarios actually exercised; source inspection alone is not a compatibility test.

## Sodium for Minecraft 26.3

Compatibility is **unknown**. No 26.3 Sodium source audit or runtime test has been completed. The previous audit below concerns a different game version and is not a compatibility verdict for current Sodium.

## Historical audit: Sodium 0.9.2 for Minecraft 26.2

**That 26.2 Sodium release was incompatible with the 26.2 Metal backend by source inspection.** It was not installed or launched for a runtime test. No Sodium code is included in this project.

Audit performed October 2, 2026 against the official release tag [`mc26.2-0.9.2`](https://github.com/CaffeineMC/sodium/releases/tag/mc26.2-0.9.2), commit `6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a`. The inspection checkout is ignored development material under `.research/sodium-26.2`.

| Area | Upstream behavior | Consequence for Metal |
| --- | --- | --- |
| Backend selection | [`DrawBackend.chooseBackend`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/backend/DrawBackend.java#L15-L28) recognizes `VulkanDevice`; every other implementation selects `OPENGL`. | Metal selects the OpenGL path despite exposing the required multidraw features. |
| Draw context | [`GLDrawContext`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/context/GLDrawContext.java#L18-L37) casts the pass backend to `GlRenderPassAccessor`, extracts an OpenGL program and calls `glUniform*`. | A Metal pass cannot satisfy the cast or execute these calls without an OpenGL context. |
| Persistent staging | [`MappedStagingBuffer.flush`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/arena/staging/MappedStagingBuffer.java#L76-L93) casts the selected OpenGL backend and mapped buffer to `GlDevice` and `GlBuffer`. | The default Metal persistent-mapping path encounters another incompatible cast. |
| Vulkan alternative | [`VKDrawContext`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/context/VKDrawContext.java#L17-L42) casts to a Vulkan pass accessor and calls `vkCmdPushConstants`. | Merely selecting Sodium's Vulkan multidraw enum does not make it backend-neutral. |
| Terrain shader parameters | The [terrain vertex shader](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/resources/assets/sodium/shaders/blocks/block_layer_opaque.vsh#L17-L28) uses a push-constant block on Vulkan and standalone uniforms on OpenGL. [`VulkanPipelineMixin`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/VulkanPipelineMixin.java#L19-L32) adds the native push-constant layout. | The custom region offset/time/ID path needs an explicit Metal or portable uniform-block implementation. It is absent from Sodium's ordinary bind-group layout. |

There is useful common infrastructure: [`VKMultiDrawBatch.draw`](https://github.com/CaffeineMC/sodium/blob/6c26e7b7eded82ce5a1d27f9b147ce5d8de99b7a/common/src/main/java/net/caffeinemc/mods/sodium/client/gpu/device/batch/VKMultiDrawBatch.java#L32-L34) ultimately calls the public Blaze3D multidraw API. Sodium also expresses its texture, global-uniform and section-time texel-buffer bindings through Blaze3D. Those operations had Metal implementations in the 26.2 backend.

For that release, an adapter would need explicit backend selection, a portable draw context, region-parameter bindings, and a replacement for OpenGL-specific mapped-memory flushing on coherent Metal shared memory. Any current adapter must begin with a fresh source audit and then validate compressed terrain vertices, chunk uploads and retirement, region sorting, translucent terrain and resource reload in a real Sodium client. This is a separate compatibility project; changing one backend check is insufficient.

## Iris and shader packs

Iris and shader packs have not been tested or adapted. They are outside the current vanilla/Fabric validation target. No compatibility is claimed for mods that directly call OpenGL or Vulkan, cast concrete RenderPearl backend objects, or require shader resources not exposed by the vanilla render-pipeline binding API.
