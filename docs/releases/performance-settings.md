# Optional performance settings for 0.2.2

These historical measurements used the now-retired Canopy and Solstice packs.
Current source builds bundle no packs and start with shaders off; these results
do not describe that default installation.

The release preserves conservative runtime defaults. The Canopy 0.2.0 result of
113.64 FPS at 3456×2168 used the tuned three-mod configuration below on an M3 Pro,
with 16-chunk view distance, 12-chunk simulation distance, 30 seconds of warmup
and 60 seconds of forest walking. It included a 517 ms hitch. This is not a
measurement of an untouched installation, a locked FPS target, or a promise
for other worlds or machines.

Advanced users can append these JVM arguments in their launcher's profile:

```text
-DminecraftMetal.indirectCommandBuffers=true
-DminecraftShaders.optimizeFragmentSpirv=true
-DminecraftShaders.optimizeVertexSpirv=true
-DminecraftShaders.hardwareShadowComparison=true
-DminecraftShaders.nativeMipmaps=true
-DminecraftShaders.terrainFrameMatrices=true
-DminecraftShaders.compactTerrainVertices=true
-DminecraftShaders.lazyTargetClears=true
-DminecraftShaders.discardFullscreenLoads=true
-DminecraftScene.drawMetadataCache=true
-DminecraftScene.asyncIndex=true
```

The measured run also set these **environment variables on the game process**
(they are not JVM arguments):

```text
MTL_DEBUG_LAYER=0
MINECRAFT_METAL_TRACKED_HAZARDS=1
MINECRAFT_METAL_CPU_ICB=1
MINECRAFT_METAL_ICB_REUSE=1
MINECRAFT_METAL_MATH_MODE=safe
```

Use a launcher's per-instance environment editor when available. The official
Minecraft Launcher has no equivalent environment editor; JVM flags alone do
not reproduce this complete setup. No global environment changes are needed
or performed by this bundle. Frame profiling and offscreen presentation were
disabled. A diagnostic scene draw-work counter was also enabled in that run;
it is not needed for normal play.

Canopy uses `profile=BALANCED`, native resolution and its default effects.
Solstice is a separate, lighter aesthetic. There is no universal FPS estimate
for either pack. Remove optional tuning when isolating a compatibility issue.

[Measurement and exact command](https://github.com/Kausik-Velaga/minecraft-metal-renderer/tree/v0.2.2/docs/evidence/canopy-0.2.0)
