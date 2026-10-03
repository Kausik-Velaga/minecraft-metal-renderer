# Development

For installing the released mod, see the [player guide](installation.md). The tools and commands below are for building and testing the source.

To contribute results from your Mac, see [community testing](community-testing.md#automated-tests-for-source-contributors) for the combined test command, evidence locations, and hardware report form. Successful runs and partial failures are both useful.

## Build and run

Requirements:

- Apple Silicon Mac; macOS 14 or newer. Validation host: macOS 26.5.2, Apple M3 Pro.
- JDK 25 or newer. The project compiles for Java 25; validation used Homebrew OpenJDK 26.0.1.
- Xcode Command Line Tools, including Clang and the macOS SDK.
- CMake 3.22 or newer, available on `PATH`.

Run from the project directory:

```sh
./gradlew --no-parallel build
./gradlew --no-parallel :nativeSmokeTest
./gradlew --no-parallel :shaderSmokeTest :transientMemorySmokeTest
./gradlew --no-parallel :runClientGameTest
./gradlew --no-parallel :runClientNaturalGameTest
MTL_DEBUG_LAYER=1 ./gradlew --no-parallel :runClient
```

From the repository root, `build` builds both mods and runs the renderer's native, shader, and transient-memory GPU checks. The explicit `:build` task builds and checks only the renderer. `:shader-loader:build` builds the loader and the renderer artifact it depends on, without running the renderer's GPU checks.

| Mod | Installable artifact |
| --- | --- |
| Minecraft Metal | `build/libs/minecraft-metal-renderer-0.2.1.jar` |
| Minecraft Shader Loader | `shader-loader/build/libs/minecraft-shader-loader-0.1.0.jar` |

The shader loader's version is controlled separately by `shader_loader_version` in `gradle.properties`. The renderer is a regular dependency of the loader, not embedded in its JAR. Neither mod requires Fabric API at runtime; the test harness uses it and the loader includes optional integration for Fabric's Indigo terrain renderer.

Release both artifacts together with their tested compatibility range. Validate the renderer on its
own, then the pair with the recorded pack versions and options. See [shader-pack installation](shader-loader.md)
and the [shader validation record](shader-validation.md).

Gradle configures and builds the arm64 native library automatically and packages it at `native/macos-arm64/libminecraft_metal.dylib` in the renderer JAR only. Use the versioned mod JARs, not sources JARs or the older 0.1.0 renderer build.

Use `./gradlew --no-parallel :runClient` to launch just the renderer from source, with saves, options, and logs in `run/`. Use `./gradlew --no-parallel :shader-loader:runClient` to launch both mods, using the separate `shader-loader/run/` directory. Always use these qualified task names: the unqualified `runClient` task selects both projects. GPU checks and client game tests enable Metal API Validation; it can substantially reduce game performance when enabled for a development client.

The two client game tests launch real Minecraft instances, create disposable worlds, exercise the renderer, and save screenshots under `build/run/clientGameTest/screenshots/` and `build/run/clientNaturalGameTest/screenshots/`. The flat scene switches Improved Transparency off/on/off and checks that the boat water-mask pass executes. The natural scene checks indexed indirect terrain drawing, both transparency modes, movement, resource reload, and SDL fullscreen transitions. The test source set and renderer probe are excluded from the mod JAR. Both tasks require a fresh completion marker so a startup failure cannot be mistaken for a passing test. Run project Gradle commands sequentially; concurrent builds can replace class files while a development client is starting.

The pinned dependencies are Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, and Fabric Loom 1.18.2. Minecraft supplies RenderPearl and LWJGL 3.4.3, including SDL, shaderc, and SPIRV-Cross. Gradle is provided by the checked-in wrapper.

## Shader-pack validation

Supply your own shader pack. Packs are not downloaded, bundled, or redistributed by this project.
BSL 10.1.8 is the initial validation target. These tasks test pack parsing, GLSL adaptation, actual
Metal pipeline compilation, depth snapshots, temporal-buffer clears, and GPU texture filtering:

```sh
./gradlew --no-parallel :shader-loader:check :shader-loader:bslShaderSmokeTest -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
./gradlew --no-parallel :shader-loader:runClientGameTest -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
```

The shader client test creates a disposable scene with water, glass, vegetation, entities, shadows,
particles, camera movement, night, rain, resizing, and resource reload. Screenshots are saved under
`shader-loader/build/run/clientGameTest/screenshots/`. A completion marker verifies that the entire
scenario ran; inspect the captures separately for rendering quality. Test scheduling and Metal
validation make these runs unsuitable as FPS benchmarks.

For an ordinary client, put the pack ZIP in the profile's `shaderpacks/` folder, then create
`config/minecraft-shader-loader.properties` with, for example:

```properties
pack=BSL_v10.1.8.zip
# Optional: profile=HIGH
# Optional: option.SHADOW_MAP_RESOLUTION=2048
```

The optional keys must match the selected pack's own profiles and options. JVM properties
`minecraftShaders.pack`, `minecraftShaders.profile`, and `minecraftShaders.option.<name>` override
the corresponding configuration values. An empty pack selection leaves ordinary rendering active.
The initial implementation rejects unsupported compute/storage-image programs and advanced stage
types with an explicit error. It uses classic transparency while the pack is rendering.

## Controls and diagnostics

- Add JVM option `-Dmetal.enabled=false` in a normal launcher to use Minecraft's selected graphics API. The development run configuration explicitly enables Metal.
- Add `-DminecraftMetal.dumpShaders=true` to retain translated MSL under the game's `debug/metal-shaders/` directory. Failed native shader compilations also save the translated shader pair.
- Use `MTL_DEBUG_LAYER=1` while diagnosing rendering errors. Search the client log for `Using graphics backend Metal` and `Metal device created` to confirm the selected backend.

The Metal renderer extracts each native build into a directory named by its SHA-256 hash under `~/.cache/minecraft-metal/`. This prevents a running or previously cached library from being mistaken for a new build.

## Troubleshooting

If native configuration fails, check that `cmake` is available and `xcode-select -p` points to a working Xcode or Command Line Tools installation. If Gradle reports an unsupported Java version, launch it with JDK 25 or newer. A missing bundled native library indicates that the native build or resource packaging did not finish; run `./gradlew --no-parallel build` again and inspect the first failure.

Metal initialization errors are reported directly. The mod does not silently fall back to OpenGL while Metal is enabled, because that would hide failures during validation. For compatibility testing, disable Metal explicitly with the JVM option above.

Fabric development clients use an offline development identity. Realms authorization and session-profile download warnings can occur independently of the rendering backend.

### Check the release JARs

After the loader's flat client test creates its disposable fixture, these tasks launch the actual
production JARs with vanilla Minecraft and Fabric Loader, without Fabric API. A separate test-only
probe drives the fixture, verifies the backend/mod set, saves a screenshot, and exits. It is not
included in either distributed mod. These short runs are installation checks, not FPS benchmarks.

```sh
MTL_DEBUG_LAYER=0 ./gradlew --no-parallel :shader-loader:runPackagedRenderer
MTL_DEBUG_LAYER=0 ./gradlew --no-parallel :shader-loader:runPackagedBsl -PshaderPack=/absolute/path/to/BSL_v10.1.8.zip
```

Each paired release must record renderer/loader/Minecraft/pack versions, options, hardware,
GPU checks, visual inspection, and the exact tested artifact hashes. Re-run the renderer alone as
well as the pair. Add separately documented pack configurations as support expands; do not infer
compatibility from compilation alone. Release only the production JARs and their checksums;
`benchmark-probe` and stale development artifacts are not player downloads.
