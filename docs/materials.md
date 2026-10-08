# Material assets and generated atlases

The shader loader supports an optional material atlas for user-supplied packs.
Solstice does not use it or allocate a companion atlas. Material recipes belong
to the pack; the loader retains generic validation and generation support.

## Ownership and preparation

- **Pack:** supplies reusable presets and explicit masks in `shaders/materials.json`, declares `materialPalette=materials.json`, and chooses how material values affect lighting.
- **Loader:** validates recipes before activation, generates a companion to Minecraft's block atlas, matches sprite positions/padding/mip levels, manages bindings and resource reloads.
- **Renderer:** ordinary texture allocation, upload and sampling; no pack-specific material code.
- **Scene optimizer:** scene selection and draw preparation remain independent of the material textures.

Generation runs once on the render thread at first use and again when the game's atlas is replaced. The atlas survives dimension changes. No images are regenerated or uploaded during ordinary frames. Generation is not yet part of asynchronous resource preparation, and there is no persistent disk cache. The measured 2048² RGBA8 companion with five mip levels occupies **22,347,776 bytes (21.31 MiB)**. Packs that do not read `materialtex` allocate no material atlas.

## Data contract

`materialtex` is an explicit loader sampler, separate from legacy normal/specular samplers and LabPBR. It binds the companion only when the draw's albedo is the block atlas. Other textures receive an unannotated one-pixel fallback. It follows albedo binding changes, preventing stale terrain bindings on unrelated draws.

| Channel | Meaning |
| --- | --- |
| R | Metalness, 0–1 |
| G | Emission, 0–1; the pack controls its lighting scale |
| B | Roughness, 1/255–1 |
| A | Subsurface/transmission, 0–1 |

Roughness zero means **unannotated**. These are linear data values. Mip generation averages channels independently, without sRGB conversion or alpha premultiplication. Sprite padding repeats edge values. Nearest texel sampling with mip selection preserves the texture grid while reducing distant aliasing. This is simple parameter filtering, not a physically exact roughness-variance filter.

The JSON schema is version 1. Presets specify `metalness`, `emission`, `roughness`, and `subsurface`, defaulting to 0, 0, 1, and 0. A sprite recipe either names a preset or supplies rectangular symbolic `mask` rows and a `legend` mapping symbols to presets. The synthetic recipes in `MaterialPaletteTest` demonstrate both forms.

Masked recipes include the SHA-256 of their source color PNG as `albedoSha256`. The file hash and sprite dimensions must match, and the sprite must be static. A changed texture disables its old mask; even a visually identical PNG re-encoding conservatively needs an updated hash. Uniform presets remain valid on resized or animated sprites because they have no pixel-placement dependency.

This initial guard checks conventional `textures/<sprite-path>.png` sources. Custom atlas remapping or derived sprites are not verified by that file lookup and need an explicit material override.

## Resource-pack overrides and limits

Resource packs can supply a packed material PNG at `assets/<namespace>/shader_materials/<sprite-path>.png`. For example, `assets/minecraft/shader_materials/block/gold_ore.png` annotates `minecraft:block/gold_ore`. Its dimensions must match one sprite frame. Overrides take priority over pack recipes and can add annotations for other block-atlas sprites. The separate directory keeps data maps out of the color atlas.

PNG alpha contains subsurface data, not opacity. Exporters must preserve RGB even where alpha is zero. Explicit overrides do not require hashes: their author is responsible for pairing material and color art.

Uniform materials work on animated sprites. Nonuniform maps on animated sprites are skipped; multi-frame material sheets are unsupported. Synchronizing varying masks with Minecraft's frame sequence and interpolation remains future work. Normal maps, separate entity/item atlases, and complete vanilla coverage are also outside this first implementation.

## Verification

Run `./gradlew :shader-loader:materialPaletteTest`. It uses a small synthetic
palette to verify mixed material masks, emission separation, source/animation
guards, atlas padding, linear channel reduction and invalid-recipe rejection.
Pack-specific art and the old material gallery test have been removed.
