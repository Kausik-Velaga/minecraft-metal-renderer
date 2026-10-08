# Minecraft Metal

An experimental Fabric mod that renders **Minecraft Java 26.3** using Apple's Metal graphics API on **Apple Silicon Macs**. Install it on your client; no server installation is needed.

> [!IMPORTANT]
> **Completely AI-generated custom implementation**
>
> This mod's custom code, tests, project documentation, listing descriptions, and project icon were completely AI-generated using Codex. Human involvement consisted of direction, prompts, and testing; the custom implementation was not handwritten by the maintainer.
>
> Third-party tooling, dependencies, and license texts retain their original authorship. Screenshots and logs are actual game captures. The documented testing is not an independent code or security audit.

## Download

**[Download the complete suite 0.2.2](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/download/v0.2.2/minecraft-metal-suite-0.2.2.zip)** · [Release notes and individual downloads](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/tag/v0.2.2)

The published bundle contains **Minecraft Metal 0.2.2**, **Shader Loader 0.1.3**, and **Scene Optimizer 0.1.0**. **Solstice 0.1.0** is the maintained original shader pack. The older v0.2.2 download also contains the retired Canopy pack; install only Solstice and select it as described below. Current source builds package Solstice alone. **BSL is not bundled.** The renderer also works on its own; both companions are optional and require the renderer.

This is an **experimental prerelease**. Use a separate launcher profile for your first try. Extract the suite ZIP once and follow its `INSTALL.txt`, or download individual mod `.jar` files. GitHub's **Source code** archives are for developers and cannot be installed as mods.

The earlier renderer 0.2.0 is also available on **[Nexus Mods](https://www.nexusmods.com/minecraft/mods/1362)** as a ZIP: extract that outer ZIP once and install the enclosed JAR. See the [Nexus installation notes](docs/installation.md#nexus-mods-downloads).

![Minecraft 26.3 terrain and water rendered with Metal, with Improved Transparency enabled](docs/evidence/26.3/natural-terrain-transparency-on.png)

## Requirements

| Requirement | Version / support |
| --- | --- |
| Mac | Apple Silicon (M-series); Intel Macs are unsupported |
| macOS | Built for 14 or newer; tested on 26.5.2 |
| Minecraft Java | **26.3** |
| Fabric Loader | **0.19.5 recommended and tested**; minimum 0.19.3 |
| Java | ARM64 Java **25 or newer**; tested with the official launcher's bundled Java 25.0.1 |

**Fabric API is not required.** The Metal library is included in the mod. Players do not need Xcode, CMake, or to compile anything. Windows and Linux are unsupported.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for **Minecraft 26.3**. If your launcher supports installing Fabric directly, select it when creating a new instance.
2. Create a separate profile or instance for testing, using Fabric Loader **0.19.5**. Start with no other mods or resource packs.
3. Extract the suite ZIP once. Copy its three intact JARs into **`mods`**, `Solstice-0.1.0.zip` into **`shaderpacks`**, and set `pack=Solstice-0.1.0.zip` and `profile=BALANCED` in **`config/minecraft-shader-loader.properties`**. For updates, edit your existing configuration.
4. Launch the Fabric profile. **Metal and Solstice activate automatically.** Restart Minecraft after changing the [shader configuration](docs/shader-loader.md).

Need help finding the folder or setting up the official launcher? See the [step-by-step installation guide](docs/installation.md).

To uninstall, close the game and remove the JAR from `mods`. Remove any older Minecraft Metal JAR before installing an update; version 0.1.0 is for Minecraft 26.2.

## What has been tested?

The renderer was tested on an **Apple M3 Pro** in Minecraft 26.3 worlds. Checks covered terrain, water, glass, entities, particles, inventory, lighting, Improved Transparency, resource reload, resizing, and fullscreen transitions. A clean official-launcher profile also passed a user-performed visual walkthrough with only this mod and Fabric Loader installed.

The 0.2.2 release added the scene optimizer and shader-pack support. See [historical release validation](docs/releases/0.2.2.md), [Solstice](docs/solstice.md) and the [optional material-atlas contract](docs/materials.md).

See [renderer screenshots and validation details](docs/validation.md) and the [clean-install report](docs/launcher-validation.md). These checks cover specific scenes and one hardware configuration. **No comparative FPS improvement has been established.**

## Compatibility

- **Client-only:** install on the Mac running the game, not on a server.
- **Solstice 0.1.0** is included and tested with this loader. Historical BSL checks are recorded in [shader validation](docs/shader-validation.md); BSL must be obtained separately. Arbitrary packs, Sodium, Iris and other rendering replacements remain unverified.
- Third-party resource packs, other Mac models, and extended gameplay have not been broadly tested.
- Mods that require OpenGL or Vulkan internals may not work with this renderer.

See the [compatibility notes](docs/compatibility.md) for more detail.

## How can I help?

**Testing on your Mac is a contribution—no coding required.** So far, the documented checks cover one Apple M3 Pro configuration. Reports from other Apple Silicon Macs, macOS versions, and displays help us find problems we cannot reproduce on that machine. **“Everything worked” reports are useful too.**

- **Have 10–15 minutes?** Follow the [community testing guide](docs/community-testing.md), try the released JAR in a separate profile, and [submit a hardware test report](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=hardware_test.yml). Report what you tried, including anything you skipped.
- **Already playing?** Longer sessions, dimension travel, external displays, and testing one resource pack or mod at a time all help. Start with a clean baseline and include exact versions.
- **Comfortable building from source?** Run the [automated GPU and gameplay checks](docs/community-testing.md#automated-tests-for-source-contributors). They exercise real Metal rendering and save screenshots for review.
- **Want to help with code?** Reproduce reported failures, add regression scenes, or improve the testing tools. See [Contributing](CONTRIBUTING.md).

Submit reports yourself after reviewing the evidence; this contribution workflow does not upload anything automatically. One successful run establishes evidence for that setup and those checks, not universal compatibility or a performance gain.

## Help and bug reports

If the game fails to start, check the Minecraft/Fabric versions and confirm your launcher uses ARM64 Java 25 or newer. If removing this JAR resolves the problem, [report it on GitHub](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=bug_report.yml).

Include your Mac chip, macOS and game versions, other mods, reproduction steps, and relevant logs or screenshots. Remove private information before sharing logs. See the [installation and troubleshooting guide](docs/installation.md#troubleshooting) for common problems and how to confirm Metal is active.

## Development

Build tools and commands are in the [development guide](docs/development.md). Players can use the download above without building from source.

The source tree now builds three separate mod JARs: the Metal backend, `shader-loader/`, and
`scene-optimizer/`. The loader and scene optimizer each require the backend and can be installed
independently of one another. All three are included in the release above. Releases state the
tested mod, game, and pack versions.
See the [module boundaries](docs/architecture.md#three-mods-in-one-repository)
and [shader development instructions](docs/development.md#shader-pack-validation).

The source includes **[Solstice](docs/solstice.md)** for lightweight lighting, shadows,
water and bloom at native resolution. The shader loader also accepts user-supplied packs.

- [Architecture](docs/architecture.md)
- [Renderer, shader runtime and scene optimization boundaries](docs/rendering-boundaries.md)
- [Performance-mod source research and license scope](docs/performance-source-review.md)
- [Validation and known limitations](docs/validation.md)
- [Upstream research and reference credits](docs/upstream-research.md)
- [Contributing](CONTRIBUTING.md)

## License and attribution

The project's own material is offered under the [MIT license](LICENSE), to the extent the contributors hold copyright or other licensable rights. This does not assert copyright over otherwise unprotected AI output or relicense third-party material. See [third-party notices](THIRD_PARTY_NOTICES.md) and the [license and provenance review](docs/license-and-provenance.md).

This is an unofficial project, not approved by or associated with Mojang or Microsoft.
