# Validation and known limitations

## Verified version and hardware

Minecraft **26.3**, mod **0.2.0**, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3. Validation host: Apple M3 Pro, macOS 26.5.2, OpenJDK 26.0.1. Build target: arm64 macOS 14+, Java 25.

The October 2, 2026 build and both real-client gameplay scenarios passed. See the [build log](evidence/26.3/build.log) and [gameplay log](evidence/26.3/gameplay.log). The 26.3 evidence lives under `docs/evidence/26.3/`; [prior 26.2 validation](validation-26.2.md) is retained separately and is not used as evidence for this port.

```sh
./gradlew --no-parallel build
./gradlew --no-parallel runClientGameTest runClientNaturalGameTest
```

A normal `./gradlew runClient` launch also selected Metal through SDL's `cocoa` driver and entered a singleplayer world. The [startup log snapshot](evidence/26.3/interactive-startup.log) records the 26.3 server and player join. This additional launch is not a visual or performance benchmark at its selected 32-chunk view distance.

The build runs all three GPU smoke tests. The packaged `build/libs/minecraft-metal-renderer-0.2.0.jar` was checked for version 0.2.0, Minecraft dependency `~26.3`, its arm64 native library, and exclusion of gameplay probes and smoke classes. The bundled native bytes match the build output and link only to system libraries/frameworks.

The subsequent [packaged JAR and clean-launcher check](launcher-validation.md) verified the distribution in the official Minecraft Launcher with bundled ARM64 Java 25.0.1 and no Fabric API. It includes user-reported visual verification and a successful assembly from a fresh clone of the public source.

## GPU checks

All checks below use actual Metal GPU work with Metal API Validation enabled.

| Test | Verified behavior |
| --- | --- |
| `nativeSmokeTest` | Staged private-buffer uploads, offset copies, texture and mip readback, whole and rectangular clears, specific-mip clears, shader draws, top-left scissor, sampler-buffer reads, repeated allocation/disposal |
| Native OIT regression | Mixed `RGBA32_FLOAT`/`RGBA16_FLOAT` attachments, MAX and additive blending, signed float values, explicit fragment depth, depth-only rendering, switching depth attachment presence |
| Native lifetime and timing | Push-constant snapshots across encoder restoration, calibrated GPU timestamps inside/outside passes, query-index reuse, zero remaining native handles after teardown |
| `shaderSmokeTest` | 195 vanilla pipeline variants, including every OIT stage and optional terrain variants, compiled twice through the real RenderPearl frontend: 390 compilations |
| Frontend drawing regression | Numeric uniform replay and shared vertex/fragment push constants rendered and checked by GPU pixel readback |
| `transientMemorySmokeTest` | Upload alignment, empty uploads, source slices, independent borrowed-view ownership, submission expiration, fence-protected reuse |

The port exposed a Metal pipeline restriction when a fragment shader writes depth but a render pass has no depth attachment. The backend now generates a separate SPIRV-Cross fragment variant for that case, while retaining the original fragment depth output for passes with depth. Both routes are covered by GPU regression checks.

## Real Minecraft scenes

Both test processes assert that the active backend is Metal. Screenshots were visually inspected. The flat test checks the active Improved Transparency mode and uses a test-only renderer probe to confirm that boat water-mask passes actually finish recording. It recorded 718 such passes during the enabled interval; the probe is not included in the distributed mod.

| Scenario | Verified 26.3 evidence |
| --- | --- |
| Title panorama, menu buttons, text, version label | [Title](evidence/26.3/title.png) |
| Terrain, flowing water, glass, cutout foliage, sheep, pig, held item, HUD | [Materials and entities](evidence/26.3/materials-entities.png) |
| Particles over terrain and water | [Particles](evidence/26.3/particles.png) |
| Creative inventory, item models, tabs, text | [Inventory](evidence/26.3/inventory.png) |
| Torch-lit stone room, chest, entities | [Indoor lighting](evidence/26.3/indoor-lighting.png) |
| Overlapping colored glass and water with Improved Transparency disabled | [Transparency off](evidence/26.3/transparency-off.png) |
| Same scene with Improved Transparency enabled and visible boat water mask | [Transparency on](evidence/26.3/transparency-on-boat-water-mask.png) |
| Particles with Improved Transparency enabled, then return to disabled mode | [OIT particles](evidence/26.3/transparency-on-particles.png), [off restored](evidence/26.3/transparency-off-restored.png) |
| Seed-1 forest/coast world, eight-chunk render distance, indexed indirect terrain | [Natural terrain](evidence/26.3/natural-terrain.png) |
| Natural terrain with Improved Transparency enabled | [Natural OIT](evidence/26.3/natural-terrain-transparency-on.png) |
| Flight, resource reload, SDL fullscreen, return to a 1280×720 window, OIT disabled again | [After reload/fullscreen](evidence/26.3/after-reload-fullscreen.png), [off restored](evidence/26.3/natural-terrain-transparency-off-restored.png) |

The natural test asserted indexed indirect terrain rendering and observed 212 visible sections. It ran over 500 world ticks, including movement and repeated geometry rebuilds while changing transparency settings. Both test tasks require a fresh completion marker written after successful world shutdown, so a startup failure cannot be treated as a passing test.

No rendering defects or Metal validation errors were observed in these scenarios. The logs contain Fabric's default anisotropy-option warning and offline development-account Realms/profile messages. The test framework controls ticks and framebuffer dimensions; independent Retina monitor transitions and prolonged interactive play remain separate validation work.

## Performance scope

The natural run reported 119 FPS and a last CPU frame of 1.89 ms with test scheduling and Metal API Validation enabled. These are diagnostic samples, **not a gameplay benchmark**. They cannot establish a speedup over OpenGL, Vulkan, or the 26.2 backend. GPU timestamps are exercised by regression tests, but no comparative gameplay GPU timing or JNI profile has been collected.

## Current limits

- Apple Silicon macOS only; the build packages an arm64 dylib. Other GPUs and operating systems are unvalidated.
- Minecraft 26.3 only. The 0.1.0 artifact targets 26.2 and should not be installed alongside this one.
- Sodium for 26.3, Iris, shader packs, and renderer-replacing mods are unvalidated. The older Sodium 26.2 source audit is historical; see [compatibility](compatibility.md).
- Direct non-indexed triangle fans are expanded. Indexed and indirect triangle fans are rejected explicitly.
- Three-component texture formats and D24 depth formats without native Apple Silicon equivalents are rejected explicitly. All tested vanilla pipeline variants compile.
- Rendering commands follow RenderPearl render-thread ownership. Concurrent native resource destruction and use from different threads are unsupported.
- Minecraft's indexed indirect terrain path is implemented. Additional GPU-driven meshing/culling, Hi-Z, mesh shaders, and native indirect-command-buffer optimizations are not implemented.
- The evidence covers controlled scenes, not exhaustive vanilla behavior or production reliability under every world/resource pack.

## Next validation work

Run longer survival sessions and dimension travel; exercise weather, underwater views, dense overlapping transparency, third-party resource packs, and monitors with different backing scales. Compare matched scenes against a vanilla backend, then profile CPU/JNI/upload/GPU costs with Metal API Validation disabled. Begin a fresh 26.3 source audit before making Sodium or Iris compatibility claims.
