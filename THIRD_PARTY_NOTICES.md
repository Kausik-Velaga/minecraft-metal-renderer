# Third-party notices

The root [MIT license](LICENSE) applies only to rights held by this project's contributors. It does not replace the terms below or claim authorship of upstream material.

## Gradle wrapper — Apache License 2.0

`gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are upstream Gradle tooling, not AI-authored project implementation. Preserve the scripts' original copyright and license headers and the JAR's embedded `META-INF/LICENSE` when redistributing them. A copy of the license is provided at [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

- Upstream: [Gradle](https://github.com/gradle/gradle).
- Wrapper distribution: **9.7.1**.
- Wrapper JAR SHA-256: `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`, matching the [official checksum](https://services.gradle.org/distributions/gradle-9.7.1-wrapper.jar.sha256).

## Fabric example build scaffolding — CC0 1.0

Fabric's example mod was consulted for build setup. The project uses conventional Fabric/Gradle configuration also found in that template. Credit: [FabricMC/fabric-example-mod, 26.1 branch](https://github.com/FabricMC/fabric-example-mod/tree/26.1), published under [CC0 1.0](https://github.com/FabricMC/fabric-example-mod/blob/26.1/LICENSE). A copy is included at [LICENSES/CC0-1.0.txt](LICENSES/CC0-1.0.txt).

## External dependencies and game content

Minecraft, RenderPearl, Fabric Loader, Fabric API, Fabric Loom, LWJGL and its native libraries, the JDK, and Apple's SDK/frameworks retain their respective terms. They are obtained separately through the game installation, build tooling, or operating system. The inspected 0.2.0 mod JAR contains this project's classes, metadata, native library, and MIT license; it does not bundle those dependencies or Minecraft assets.

Gameplay screenshots show Minecraft-owned visual content. That content, Minecraft names and marks, and upstream text in captured logs are not relicensed under this project's MIT license. See the [Minecraft EULA](https://www.minecraft.net/en-us/eula) and [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines). This is an unofficial project, not approved by or associated with Mojang or Microsoft.

## Research references

MetalRender and Mojang's backends informed API and architectural research; Sodium was inspected for compatibility. Their source remains external research material, not part of this repository's deliverable. The [upstream research notes](docs/upstream-research.md) and [provenance review](docs/license-and-provenance.md) credit the references and explain the comparison. Credit alone does not permit incorporating code under incompatible terms.
