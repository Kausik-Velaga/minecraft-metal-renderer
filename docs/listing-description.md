# About the mod

Minecraft Metal is an experimental Fabric mod that renders Minecraft Java 26.3 using Apple's Metal graphics API on Apple Silicon Macs. It runs on your client and activates automatically when installed.

**AI disclosure:** this mod's custom implementation, tests, and project documentation were completely AI-generated using Codex. Human involvement consisted of direction, prompts, and testing. Third-party tooling and dependencies retain their original authorship. Screenshots and logs are actual game captures. This description was also AI-generated.

## Requirements

- An **Apple Silicon Mac** (M-series). Intel Macs, Windows, and Linux are unsupported.
- **macOS 14 or newer.** Testing has been performed on macOS 26.5.2 with an Apple M3 Pro.
- **Minecraft Java 26.3** and **Fabric Loader 0.19.5** recommended (minimum 0.19.3).
- **ARM64 Java 25 or newer.** The official launcher's bundled ARM64 Java 25.0.1 passed the clean-install test.

**Fabric API is not required.** The Metal library is included. No compiling, Xcode, or separate native-library installation is needed to run the mod.

## Installation

1. Create a separate Minecraft 26.3 profile or instance with Fabric Loader 0.19.5.
2. Download `minecraft-metal-renderer-0.2.0.jar` from this page.
3. Put the JAR in that profile's `mods` folder. Create the folder if needed; do not open or unzip the JAR.
4. Launch the Fabric profile. Metal activates automatically.

Start with no other mods or resource packs. Install on the client only, not on a server. Remove older Minecraft Metal JARs before updating; version 0.1.0 was for Minecraft 26.2. To uninstall, close the game and remove the JAR.

## Tested features

Validation on an Apple M3 Pro covered terrain, water, glass, entities, particles, inventory, lighting, Improved Transparency, resource reload, resizing, and fullscreen transitions. A clean official-launcher installation also passed a user-performed visual walkthrough with only this mod and Fabric Loader installed.

This is an **experimental release** with limited hardware and gameplay coverage. No comparative FPS improvement has been established. Sodium, Iris, shader packs, other rendering mods, and third-party resource packs are unverified. Mods that depend on OpenGL or Vulkan internals may not work with this renderer.

## Help and source

[Installation help](https://github.com/Kausik-Velaga/minecraft-metal-renderer/blob/main/docs/installation.md) · [Bug reports](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=bug_report.yml) · [Source code](https://github.com/Kausik-Velaga/minecraft-metal-renderer) · [Validation and screenshots](https://github.com/Kausik-Velaga/minecraft-metal-renderer/blob/main/docs/validation.md)

For bug reports, include your Mac chip, macOS and game versions, other mods, reproduction steps, and relevant logs or screenshots. Remove private information before sharing logs.

The project's own licensable material is offered under MIT. See the [third-party notices](https://github.com/Kausik-Velaga/minecraft-metal-renderer/blob/main/THIRD_PARTY_NOTICES.md) and [license and provenance review](https://github.com/Kausik-Velaga/minecraft-metal-renderer/blob/main/docs/license-and-provenance.md) for scope and exceptions.

This is an unofficial project, not approved by or associated with Mojang or Microsoft.
