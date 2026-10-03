# Historical validation: Minecraft 26.2 / mod 0.1.0

This record and its linked screenshots describe the earlier 26.2 implementation. They do not validate the 26.3 port. See [current validation](validation.md) for the current target.

## Verified hardware and commands

Validation host: Apple M3 Pro, macOS 26.5.2, OpenJDK 26.0.1. Build target: arm64 macOS 14+, Java 25, Minecraft 26.2.

The final aggregate run on October 2, 2026 passed in 63 seconds:

```sh
./gradlew --no-parallel build nativeSmokeTest shaderSmokeTest transientMemorySmokeTest runClientGameTest runClientNaturalGameTest
```

The [complete validation log](evidence/validation.log) records the native build, all GPU checks, and both Minecraft processes. The packaged library was also checked as an arm64 Mach-O dylib linked only to system libraries/frameworks; the mod JAR contains the library and excludes the gameplay test harness.

`./gradlew nativeSmokeTest` passed with Metal API Validation enabled. This test performs actual GPU operations and checks returned bytes/pixels:

- Private-buffer staged uploads and offset copies to shared readback buffers.
- Releasing the source wrapper after encoding, before GPU submission.
- RGBA texture uploads, texture-to-texture copies, and mip-level readback.
- Depth32 clear and floating-point depth readback.
- A compiled MSL graphics pipeline rendering a triangle into a texture.
- Fragment-coordinate orientation and top-left scissor behavior.
- Sixty-four submissions with repeated allocation and disposal.
- Real GPU timestamps both outside and inside render passes over 32 frames, including state restoration after counter sampling, plus 64 asynchronous submissions reusing query indices.

Further native regression coverage includes sampler-buffer reads, rectangular and mip clears, and checking that all native handles are released. `./gradlew shaderSmokeTest` compiles all 87 registered vanilla pipelines through GLSL, SPIR-V, MSL, and the Metal driver, repeated for initial loading, cached loading, and resource reload. `./gradlew transientMemorySmokeTest` checks upload alignment, empty uploads, source-buffer bounds, borrowed allocation ownership, submission expiration, and fence-protected reuse.

The first run exposed a mismatch between Java's Metal enum values and a native ordinal mapping. That mapping was corrected, and the test passed on the next run. The test remains in `src/test/java/dev/kausik/metal/NativeSmokeTest.java` as a regression check.

## Real Minecraft scenes

Both Fabric client test scenarios launched real Minecraft 26.2 using the native backend. Logs confirm `Using graphics backend Metal` and the tests also assert the active backend name. Screenshots were inspected visually, not merely checked for file existence.

| Scenario | Verified evidence |
| --- | --- |
| Title screen, panorama, menu buttons, text | [Title](evidence/title.png) |
| Terrain, flowing water, transparent glass, cutout foliage, pig and sheep, held item, HUD | [Materials and entities](evidence/materials-entities.png) |
| Flame particles over terrain and water | [Particles](evidence/particles.png) |
| Creative inventory, item models, tabs, tooltip text | [Inventory](evidence/inventory.png) |
| Enclosed stone room, torch lighting, chest, entities | [Indoor lighting](evidence/indoor-lighting.png) |
| Seed-1 normal terrain at eight-chunk render distance: forest, terrain relief, beach, ocean, kelp | [Natural terrain](evidence/natural-terrain.png) |
| Movement, resource reload, fullscreen transition, return to 1280×720 windowed rendering | [After reload and fullscreen](evidence/after-reload-fullscreen.png) |

`./gradlew runClientGameTest` exercises the first five rows plus resizing and returning to the menu. `./gradlew runClientNaturalGameTest` exercises natural terrain, spectator flight, resource reload, and fullscreen transitions over more than 400 world ticks. These execute in separate processes to keep Fabric's test thread/server synchronization isolated.

The natural scene rendered more than 200 visible sections. In the final run, with test scheduling and Metal API Validation enabled, it reported 111 FPS and a last CPU frame of 5.92 ms; these are diagnostics, **not a gameplay benchmark**. The test framework controls ticks and framebuffer dimensions, so independent Retina monitor changes and prolonged interactive play remain separate validation work.

The successful scene runs produced no Metal validation errors. Development-account profile/Realms authorization messages and Fabric's default anisotropy-option warning appeared in logs without preventing the tests.

## Current limits

- Apple Silicon macOS only; the build packages an arm64 dylib.
- Minecraft 26.2 is the current target. Other versions need their actual interfaces checked before use.
- Sodium 0.9.2 for 26.2 selects concrete OpenGL/Vulkan implementations and is incompatible with this backend by source inspection; see [compatibility](compatibility.md). Iris, shader packs, other renderer replacements, Intel Macs, and external GPUs have not been validated.
- Direct non-indexed triangle fans are expanded to triangles. Indexed and indirect triangle fans are rejected explicitly; the validated vanilla scenes do not use those paths.
- GPU formats without native Apple Silicon Metal equivalents, including three-component texture formats and D24 depth formats, are rejected explicitly. All 87 registered vanilla pipelines compile.
- Rendering commands follow Blaze3D render-thread ownership. Concurrent resource destruction and native use from different threads are not supported.
- Standard pipeline/draw submission is the implementation target. GPU-driven terrain, Hi-Z culling, mesh shaders, and indirect command-buffer optimization are not implemented.
- No gameplay performance claim is made from the native smoke test. CPU/GPU frame measurements require a stable, visually verified game scene with Metal API Validation disabled.

## Remaining validation

Run longer interactive survival sessions and test dimension travel, weather, underwater views, complex transparency, third-party resource packs, and movement between monitors with different backing scales. Compare matched scenes against vanilla rendering, then profile CPU/JNI/upload/GPU costs with Metal API Validation disabled. Extend compatibility checks only after those correctness checks remain stable.
