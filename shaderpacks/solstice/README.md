# Solstice 0.1.0

An original, lightweight shader pack for the Minecraft Shader Loader 0.1.3, Minecraft 26.3, and Apple Silicon. MIT licensed. No BSL code or assets are included.

The default BALANCED look uses warm sunlight, cooler ambient light, vanilla vertex ambient occlusion, a 64-block shadow range with a 1536-pixel map, two filtered shadow samples, distance/weather fog, procedural sky clouds, depth-colored water with sky reflection, subtle mipmap bloom, and one final color pass. Terrain remains at native resolution. LITE disables shadows and bloom.

There is no screen-space ambient occlusion, reflection ray marching, volumetric ray marching, temporal history, depth of field, motion blur, or special antialiasing pass. Clouds are a sky layer, not a volume you can fly through. Water reflects the sky color, not nearby objects. Translucent objects do not cast shadows. These are intentional visual tradeoffs; Solstice does not reproduce BSL defaults.

Build from the repository root with `./gradlew :shader-loader:solsticePack`. The ZIP is written to `shader-loader/build/shaderpacks/Solstice-0.1.0.zip`. Put it in the game directory's `shaderpacks` folder and select it with the shader loader. Use the matching three-mod release; older loaders predate this pack. See `docs/shader-loader.md` in the repository for configuration.

The pack uses two loader properties: `shadow.translucent=false` skips translucent shadow casters; `shadow.texelSnap=true` anchors a linear orthographic shadow map to light-space texels as the camera moves. Texel snapping does not freeze the sun. Neither extension has been validated on other shader loaders.

Benchmark recipe and measured results are recorded in the repository's `docs/solstice.md`. FPS depends on scene, resolution, configuration, and presentation; a short offscreen run is not a sustained gameplay claim.
