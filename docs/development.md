# Development

For installing the released mod, see the [player guide](installation.md). The tools and commands below are for building and testing the source.

## Build and run

Requirements:

- Apple Silicon Mac; macOS 14 or newer. Validation host: macOS 26.5.2, Apple M3 Pro.
- JDK 25 or newer. The project compiles for Java 25; validation used Homebrew OpenJDK 26.0.1.
- Xcode Command Line Tools, including Clang and the macOS SDK.
- CMake 3.22 or newer, available on `PATH`.

Run from the project directory:

```sh
./gradlew --no-parallel build
./gradlew --no-parallel nativeSmokeTest
./gradlew --no-parallel shaderSmokeTest transientMemorySmokeTest
./gradlew --no-parallel runClientGameTest
./gradlew --no-parallel runClientNaturalGameTest
MTL_DEBUG_LAYER=1 ./gradlew --no-parallel runClient
```

The `build` task also runs the native, shader, and transient-memory GPU checks. Gradle configures and builds the arm64 native library automatically and packages it at `native/macos-arm64/libminecraft_metal.dylib` in **`build/libs/minecraft-metal-renderer-0.2.0.jar`**. Use that versioned mod JAR, not the sources JAR or the older 0.1.0 build. The development client uses `run/` for saves, options, and logs. GPU checks and client game tests enable Metal API Validation; it can substantially reduce game performance when enabled for `runClient`.

The two client game tests launch real Minecraft instances, create disposable worlds, exercise the renderer, and save screenshots under `build/run/clientGameTest/screenshots/` and `build/run/clientNaturalGameTest/screenshots/`. The flat scene switches Improved Transparency off/on/off and checks that the boat water-mask pass executes. The natural scene checks indexed indirect terrain drawing, both transparency modes, movement, resource reload, and SDL fullscreen transitions. The test source set and renderer probe are excluded from the mod JAR. Both tasks require a fresh completion marker so a startup failure cannot be mistaken for a passing test. Run project Gradle commands sequentially; concurrent builds can replace class files while a development client is starting.

The pinned dependencies are Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0+26.3, and Fabric Loom 1.18.2. Minecraft supplies RenderPearl and LWJGL 3.4.3, including SDL, shaderc, and SPIRV-Cross. Gradle is provided by the checked-in wrapper.

## Controls and diagnostics

- Add JVM option `-Dmetal.enabled=false` in a normal launcher to use Minecraft's selected graphics API. The development run configuration explicitly enables Metal.
- Add `-DminecraftMetal.dumpShaders=true` to retain translated MSL under the game's `debug/metal-shaders/` directory. Failed native shader compilations also save the translated shader pair.
- Use `MTL_DEBUG_LAYER=1` while diagnosing rendering errors. Search the client log for `Using graphics backend Metal` and `Metal device created` to confirm the selected backend.

The loader extracts each native build into a directory named by its SHA-256 hash under `~/.cache/minecraft-metal/`. This prevents a running or previously cached library from being mistaken for a new build.

## Troubleshooting

If native configuration fails, check that `cmake` is available and `xcode-select -p` points to a working Xcode or Command Line Tools installation. If Gradle reports an unsupported Java version, launch it with JDK 25 or newer. A missing bundled native library indicates that the native build or resource packaging did not finish; run `./gradlew --no-parallel build` again and inspect the first failure.

Metal initialization errors are reported directly. The mod does not silently fall back to OpenGL while Metal is enabled, because that would hide failures during validation. For compatibility testing, disable Metal explicitly with the JVM option above.

Fabric development clients use an offline development identity. Realms authorization and session-profile download warnings can occur independently of the rendering backend.
