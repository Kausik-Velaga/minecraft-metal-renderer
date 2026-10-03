# Packaged JAR and public-source validation

Date: October 2, 2026. Host: Apple M3 Pro, macOS 26.5.2.

## Artifact

- Mod: `minecraft-metal-renderer-0.2.0.jar`, Minecraft 26.3.
- SHA-256: `d6e5a363ee4e2c354c8f4da23830e443f1bfb7c25a8a41edacc5656e7d8993e7`.
- The installed file is byte-for-byte identical to the packaged JAR.
- The JAR includes the arm64 Metal library and MIT license, and excludes gameplay probes and smoke-test classes. Its native library matches the build output.
- The distribution metadata includes the public source and issue-tracker links.

## Build and source publication

`./gradlew --no-parallel build` passed after preparing the distribution metadata, including the native, shader, frontend pixel-readback, and transient-memory checks. See the [release build log](evidence/26.3/release-build.log).

The public repository was independently fetched without authentication. GitHub reports public visibility, the MIT license, the `main` default branch, and an enabled issue tracker. A fresh clone of the published source at `475d34c` built successfully with `./gradlew --no-parallel assemble`, including compilation of the native library from source. This proves that assembly does not depend on untracked research files; it does not claim bit-for-bit reproducibility of native debug information. See the [fresh-clone build log](evidence/26.3/source-build.log).

## Official launcher profile

The official Minecraft Launcher started a separate game directory with Fabric Loader 0.19.5 and its bundled **ARM64 OpenJDK 25.0.1**. Before launch, that directory contained only a `mods` folder with the packaged mod. Existing worlds, options, and other mods were not copied into it.

The user activated Play after computer automation could not reliably target the launcher. The game log confirms Minecraft 26.3, Minecraft Metal 0.2.0, the native Metal backend on Apple M3 Pro, SDL's `cocoa` driver, resource loading, and integrated-server startup for a new world. The only discovered mod entries were Fabric Loader, its bundled MixinExtras, Java, Minecraft, and Minecraft Metal. **Fabric API was not installed and is not a runtime requirement.** See the [selected clean-launcher log](evidence/26.3/clean-launcher.log).

The user performed the requested interactive walkthrough because the computer-control tool could not attach to the game's Java window: a disposable world, terrain/water, inventory, Improved Transparency, resource reload, fullscreen switching, and save/quit. The user reported, "yes, everything renders normally." This is user-reported visual verification, separate from the directly inspected log evidence. Earlier automated rendering and visual checks are documented in [validation.md](validation.md).

This is a functional installation check, not a performance benchmark. Broader hardware, operating-system, and third-party-mod compatibility remain unverified. Published log copies replace personal home paths and omit account identity from the clean-launcher excerpt.
