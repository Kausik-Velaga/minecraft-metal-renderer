# Minecraft Metal

An experimental Fabric mod that renders **Minecraft Java 26.3** using Apple's Metal graphics API on **Apple Silicon Macs**. Install it on your client; no server installation is needed.

> [!IMPORTANT]
> **Completely AI-generated custom implementation**
>
> This mod's custom code, tests, and project documentation were completely AI-generated using Codex. Human involvement consisted of direction, prompts, and testing; the custom implementation was not handwritten by the maintainer.
>
> Third-party tooling, dependencies, and license texts retain their original authorship. Screenshots and logs are actual game captures. The documented testing is not an independent code or security audit.

## Download

**[Download Minecraft Metal 0.2.0 for Minecraft 26.3](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/download/v0.2.0/minecraft-metal-renderer-0.2.0.jar)** · [Release notes](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/tag/v0.2.0)

This is an **experimental prerelease**. Use a separate launcher profile for your first try. Download the mod `.jar`; GitHub's **Source code** archives are for developers and cannot be installed as mods.

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
3. Download the JAR above and put it in that profile's **`mods` folder**. Create the folder if needed. Keep the JAR intact; do not open or unzip it.
4. Launch the Fabric profile. **Metal activates automatically.**

Need help finding the folder or setting up the official launcher? See the [step-by-step installation guide](docs/installation.md).

To uninstall, close the game and remove the JAR from `mods`. Remove any older Minecraft Metal JAR before installing an update; version 0.1.0 is for Minecraft 26.2.

## What has been tested?

Version 0.2.0 was tested on an **Apple M3 Pro** in Minecraft 26.3 worlds. Checks covered terrain, water, glass, entities, particles, inventory, lighting, Improved Transparency, resource reload, resizing, and fullscreen transitions. A clean official-launcher profile also passed a user-performed visual walkthrough with only this mod and Fabric Loader installed.

See [screenshots and validation details](docs/validation.md) and the [clean-install report](docs/launcher-validation.md). These checks cover specific scenes and one hardware configuration. **No comparative FPS improvement has been established.**

## Compatibility

- **Client-only:** install on the Mac running the game, not on a server.
- **Sodium, Iris, shader packs, and other rendering mods are unverified.** Start with Minecraft Metal on its own.
- Third-party resource packs, other Mac models, and extended gameplay have not been broadly tested.
- Mods that require OpenGL or Vulkan internals may not work with this renderer.

See the [compatibility notes](docs/compatibility.md) for more detail.

## Help and bug reports

If the game fails to start, check the Minecraft/Fabric versions and confirm your launcher uses ARM64 Java 25 or newer. If removing this JAR resolves the problem, [report it on GitHub](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=bug_report.yml).

Include your Mac chip, macOS and game versions, other mods, reproduction steps, and relevant logs or screenshots. Remove private information before sharing logs. See the [installation and troubleshooting guide](docs/installation.md#troubleshooting) for common problems and how to confirm Metal is active.

## Development

Build tools and commands are in the [development guide](docs/development.md). Players can use the download above without building from source.

- [Architecture](docs/architecture.md)
- [Validation and known limitations](docs/validation.md)
- [Upstream research and reference credits](docs/upstream-research.md)
- [Contributing](CONTRIBUTING.md)

## License and attribution

The project's own material is offered under the [MIT license](LICENSE), to the extent the contributors hold copyright or other licensable rights. This does not assert copyright over otherwise unprotected AI output or relicense third-party material. See [third-party notices](THIRD_PARTY_NOTICES.md) and the [license and provenance review](docs/license-and-provenance.md).

This is an unofficial project, not approved by or associated with Mojang or Microsoft.
