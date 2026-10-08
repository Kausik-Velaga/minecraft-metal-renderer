# Registry submission notes

## Current source packaging

Build with `./gradlew --no-parallel build :shader-loader:solsticePack`, then use
`python3 tools/package-release.py`. The allowlist contains three production JARs,
one Solstice ZIP, the suite ZIP and SHA256SUMS. Solstice is selected by default.
Choose a new suite release version before publishing changed artifacts; do not
replace an existing published version with this working tree.

## Historical October 7, 2026 suite release

The GitHub `v0.2.2` prerelease distributes Minecraft Metal 0.2.2, Shader Loader
0.1.3, Scene Optimizer 0.1.0, the retired Canopy 0.2.0 and Solstice 0.1.0.
That published download and its [release notes](releases/0.2.2.md) describe the
historical build. Its configuration selected Canopy; current source selects Solstice.

The registry entries below record the earlier 0.2.0 submissions. Their status
has not been rechecked and these listings have not been updated by the GitHub
suite release. Do not present their older files as the current suite.

## Historical 0.2.0 submissions

Prepared October 2, 2026 for the experimental 0.2.0 release. These are maintainer notes, not the public mod description.

## Project name

Use **Minecraft Metal** as the canonical public name in the README, mod metadata, documentation, release titles, and registry listings. The 0.2.0 registry file display name is **Minecraft Metal 0.2.0 - MC 26.3 - Apple Silicon**. Repository names, URL slugs, package identifiers, existing copyright notices, and published artifact filenames are stable identifiers and may retain their original wording.

## Listing status

- **CurseForge:** project **1723425**, **Minecraft Metal**, is awaiting moderator approval. Release file **9043119** is **Under Review**, tagged Alpha / Minecraft 26.3 / Fabric / Client / Java 25, with automatic publication after approval selected. The MIT license, GitHub source, icon, description, and three captioned gameplay images are saved. [Author dashboard](https://authors.curseforge.com/#/projects/1723425/files) · [Listing preview](https://www.curseforge.com/minecraft/mc-mods/metal-renderer/preview). The listing is not yet public. Alpha files are not shown in the CurseForge client and require a direct project link.
- **Nexus Mods:** **[Minecraft Metal — 1362](https://www.nexusmods.com/minecraft/mods/1362)** is published, with one main archive, three gameplay images, **AI-Generated Content** and **AI Media** tags, an external Fabric Loader requirement, and custom permissions containing the MIT license and its scope qualifications. The public description includes the ZIP-specific manual installation instructions. Mod rewards were not enabled.

## Shared listing material

- **Description:** copy [listing-description.md](listing-description.md) into the registry's description editor and check its preview. It includes the AI disclosure, requirements, installation, tested scope, and support links.
- **Summary:** Experimental native Metal renderer for Minecraft Java 26.3 on Apple Silicon Macs.
- **Main file:** `minecraft-metal-renderer-0.2.0.jar`, available in the [GitHub prerelease](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/tag/v0.2.0). Upload the JAR itself to CurseForge; use the wrapper ZIP described below for Nexus. Never upload a source archive as the playable mod.
- **File version:** 0.2.0. Mark experimental/alpha where that option is offered.
- **Game / loader / environment:** Minecraft Java 26.3 / Fabric / client-only.
- **Requirements:** Apple Silicon, macOS 14+, ARM64 Java 25+, Fabric Loader 0.19.3+ (0.19.5 tested). Do not list Fabric API as a required dependency.
- **License:** MIT for the project's own licensable material, with the linked notices and provenance qualifications retained.
- **Source / issues:** use the public repository and issue tracker linked in the description.
- **Changelog:** use the feature and limitations sections of [0.2.0 release notes](releases/0.2.0.md); omit GitHub-specific references to "assets below."

The package checksum is `d6e5a363ee4e2c354c8f4da23830e443f1bfb7c25a8a41edacc5656e7d8993e7`. No speedup claim or compatibility with Sodium/Iris is established.

## Screenshots

Use the existing, unaltered game captures as gallery images:

- [Natural terrain and water](evidence/26.3/natural-terrain-transparency-on.png) — Minecraft 26.3 on Apple M3 Pro, Improved Transparency enabled.
- [Overlapping glass and boat water mask](evidence/26.3/transparency-on-boat-water-mask.png) — controlled transparency test scene.
- [Inventory](evidence/26.3/inventory.png) — inventory and item rendering.

These demonstrate the tested rendering, not comparative performance. The original, AI-generated project icon is available as [editable SVG](assets/icon.svg) and [400 × 400 PNG](assets/icon.png). Retain the icon's AI disclosure in listings; the gameplay images are actual captures.

## CurseForge

Display title: **Minecraft Metal**, matching the maintainer's chosen canonical name. The author dashboard accepted and saved this title. CurseForge's published naming guidance excludes game names, so title acceptance by moderation is not yet established; a moderator may request an alternative. Keep any required platform exception explicit rather than treating it as a second project name.

Upload the actual JAR to CurseForge and keep the description self-contained. The shared description intentionally has no external binary-download link. Add an original **400 × 400** project icon and the real gameplay screenshots. Select the closest available rendering/graphics category; an optimization category must not be used to imply an unmeasured speedup.

The current published rules do not state a blanket prohibition on AI-generated code, but acceptance remains subject to moderation. Retain the explicit AI disclosure. Review the [CurseForge moderation policies](https://support.curseforge.com/support/solutions/articles/9000197279-project-and-modpack-moderation-policies) when submitting.

## Nexus Mods

Use the **Minecraft** game category, with **Minecraft Metal** as the title. The upload form accepts archives, not JARs. Upload `minecraft-metal-renderer-0.2.0-nexus.zip`, containing the unchanged tested JAR, `INSTALL.txt`, `LICENSE`, and `SHA256SUMS`. Its SHA-256 is `a06b6030cb3f36fb06419bd3f5ca0934b6f7a6971e364c01547ca2bed7fab46c`.

State the Apple Silicon/macOS restriction prominently. Adapt the shared description's download step: extract the outer ZIP once, then copy the intact enclosed JAR into `mods`. Use manual installation instructions; no Nexus mod-manager integration has been tested. See the [Nexus installation notes](installation.md#nexus-mods-downloads).

Apply **AI-Generated Content** for the generated code and **AI Media** for the generated page description and project icon. "AI Assisted" would understate how this project was made. Use the real gameplay captures for the gallery and mark the file as experimental in its description.

Review the [Nexus file submission guidelines](https://help.nexusmods.com/article/28-file-submission-guidelines), particularly AI tags and evidence for performance claims, when submitting.

## After approval

Add the actual approved listing URLs to the README. Check that the registry serves the intended JAR and that its Minecraft version, loader, requirements, AI disclosure, and release status match this repository. Keep subsequent releases' descriptions and compatibility claims aligned with their testing evidence.
