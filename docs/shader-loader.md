# Shader packs with Minecraft Metal

Minecraft Shader Loader is an optional Fabric mod. Minecraft Metal runs without it;
shader packs require both mods. The release includes our original **Canopy 0.2.0**
and **Solstice 0.1.0** packs for Minecraft Java **26.3** and Apple Silicon.
Scene Optimizer 0.1.0 is an optional third mod for scene preparation.

## Install and select a pack

1. Use a separate Minecraft 26.3 Fabric profile. See the [installation guide](installation.md).
2. Put `minecraft-metal-renderer-0.2.2.jar`, `minecraft-shader-loader-0.1.3.jar`, and optionally
   `minecraft-scene-optimizer-0.1.0.jar` in `mods`. Remove older copies. Fabric API is not required.
3. Put `Canopy-0.2.0.zip` and `Solstice-0.1.0.zip`, intact, in `shaderpacks`.
4. Create or edit `config/minecraft-shader-loader.properties` in that profile:

   ```properties
   pack=Canopy-0.2.0.zip
   profile=BALANCED
   ```

   To select Solstice, use `pack=Solstice-0.1.0.zip`. Remove incompatible `option.*` entries
   when switching packs. The suite ZIP includes a fresh-profile Canopy configuration.
5. Start Minecraft and enter a world. The first load compiles the pack's programs.

BSL 10.1.8 was the initial compatibility target and has historical validation records.
Obtain it separately from its author if needed; no BSL files are included in this release.

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
- Canopy uses the loader's optional MERS material atlas; see [material maps and resource-pack
  overrides](materials.md). Normal maps, nonuniform animated material masks and separate entity
  atlases remain unsupported. Legacy normal/specular samplers retain fallback/custom behavior.
  Arbitrary PBR resource packs and combinations with other rendering mods are unverified.
- Spectral-effect entities do not yet select the optional pack `gbuffers_entities_glowing`
  program. Emissive materials retain their ordinary entity route.
- Minecraft 26.3 materials that combine an item's base color and enchantment glint in one draw
  retain their base rendering, but separate pack glint layering is not yet implemented for those
  fused materials. Standalone glint draws retain their texture transform and pack program.
- Sodium and Iris are not required. Combining other rendering replacements with this pair is
  unverified. The loader has no tested backend other than Minecraft Metal.

Install the matching versions from the same release. Loader 0.1.3 and Scene Optimizer 0.1.0
require renderer 0.2.2 (or a compatible 0.2.x patch). New Minecraft versions need a fresh
compatibility check even if a shader pack itself has not changed.

## Reporting a problem

Include both mod versions, Minecraft/Fabric versions, Mac chip, pack version, pack options, render
size and distance, other mods/resource packs, and a small reproduction scene. Attach relevant log
lines and a screenshot after removing private information. State whether the same scene works
with no pack selected. Report shader compilation errors separately from incorrect images or poor
frame pacing.

The pack remains its author's work and is loaded locally at runtime. This repository does not
redistribute BSL source, textures, or its ZIP. Generated shader dumps are local diagnostics and
should not be included in public reports without appropriate permission.
