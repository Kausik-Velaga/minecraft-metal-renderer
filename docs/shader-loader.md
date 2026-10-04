# Shader packs with Minecraft Metal

Minecraft Shader Loader is an optional second Fabric mod. Minecraft Metal runs without it; shader
packs require both mods. The initial compatibility target is **BSL 10.1.8 with its default settings**
on Minecraft Java **26.3**, Apple Silicon, and the Metal backend. This is experimental support.

## Install and select a pack

1. Use a separate Minecraft 26.3 Fabric profile. See the [renderer installation guide](installation.md).
2. Put `minecraft-metal-renderer-0.2.1.jar` and `minecraft-shader-loader-0.1.0.jar` in that profile's
   `mods` folder. Remove older copies of either mod. Fabric API is optional.
3. Obtain BSL 10.1.8 from its author or official distribution page. Put its ZIP, intact, in the
   profile's `shaderpacks` folder. The pack is not included in either mod.
4. Create `config/minecraft-shader-loader.properties` inside the same profile, containing:

   ```properties
   pack=BSL_v10.1.8.zip
   ```

5. Start Minecraft and enter a world. The first world load compiles the pack's programs. Logs identify
   the selected pack, Metal device, dimension, and render size.

There is no shader settings screen yet. Restart Minecraft after changing the selected pack or its
options. An absent or empty `pack` setting leaves ordinary rendering active. To remove shader
support, remove the loader JAR; the renderer remains independently usable.

## Options

Start with the pack's defaults. A `profile` entry selects a profile supplied by the pack. Individual
settings use `option.<name>=<value>` and override the profile. Names and values are checked against
the pack; invalid settings produce an error rather than being ignored.

Advanced launch configurations can override the file with `-DminecraftShaders.pack=<path>`,
`-DminecraftShaders.profile=<profile>`, or `-DminecraftShaders.option.<name>=<value>`. Relative pack
paths are resolved inside `shaderpacks`; absolute ZIP or directory paths are also accepted.

The loader selects classic transparency while rendering a pack and preserves the player's saved
Improved Transparency preference. BSL's own anti-aliasing, bloom, water, lighting, and shadow
settings remain controlled by the pack.

## Compatibility limits

- Tested pack configurations and evidence are listed in [shader validation](shader-validation.md).
  Successful compilation alone is not evidence of correct rendering.
- Compute shaders, storage images, extra geometry/tessellation stages, and 3D texture resources are
  unsupported. BSL's compute-based multicolored block lighting must remain disabled.
- Enabled setup passes, custom buffer flipping or viewport scaling, explicit buffer sizes, and
  shadow-color format, clear, filtering, or mipmap directives are rejected before GPU allocation.
  BSL 10.1.8's default configuration does not require these features.
- BSL's host autofocus mode (`DOF=true`, `DOF_FOCUS_MODE=0`) is rejected because the loader does not
  supply its required center-depth history. The pack's depth-texture focus mode 1 can compile; the
  default configuration keeps depth of field off.
- Advanced material maps, PBR resource packs, arbitrary shader packs, and combinations with other
  rendering mods have not been validated. Default normal/specular fallback textures are supplied;
  material-map discovery is not implemented.
- Spectral-effect entities do not yet select the optional pack `gbuffers_entities_glowing`
  program. Emissive materials retain their ordinary entity route.
- Sodium and Iris are not required. Combining other rendering replacements with this pair is
  unverified. The loader has no tested backend other than Minecraft Metal.
- While a pack frame renders, a pipeline that is not yet in Minecraft's pipeline cache compiles on the
  render thread instead of the shared background executor, so it cannot wait behind chunk workers that
  need this frame's terrain upload. `-DminecraftShaders.logPipelineMisses=true` logs each such miss.

Install the paired versions from the same release. Loader 0.1.0 requires renderer 0.2.1 or a compatible
0.2.x patch; renderer 0.2.0 lacks a mipmap-size fix required by the loader. New Minecraft versions
need a fresh compatibility check even if a shader pack itself has not changed.

## Reporting a problem

Include both mod versions, Minecraft/Fabric versions, Mac chip, pack version, pack options, render
size and distance, other mods/resource packs, and a small reproduction scene. Attach relevant log
lines and a screenshot after removing private information. State whether the same scene works
with no pack selected. Report shader compilation errors separately from incorrect images or poor
frame pacing.

The pack remains its author's work and is loaded locally at runtime. This repository does not
redistribute BSL source, textures, or its ZIP. Generated shader dumps are local diagnostics and
should not be included in public reports without appropriate permission.
