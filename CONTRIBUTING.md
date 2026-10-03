# Contributing

This is an experimental native Metal backend for Minecraft 26.3 on Apple Silicon macOS. Report reproducible problems through the [issue tracker](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues), including versions, Mac hardware, graphics settings, other mods, and relevant logs or screenshots. Remove private information before uploading logs.

## Contribute a test run

You do not need to write code to help. Follow the [community testing guide](docs/community-testing.md) and submit a [hardware test report](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=hardware_test.yml), even if everything works. A short, specific report from a new hardware, macOS, or display configuration is useful; mark skipped checks as **Not tested**. The guide includes a player checklist, the existing automated test command, and ideas for extending automation.

## Contribute code

Build requirements and commands are in the [development guide](docs/development.md). GPU and real-client tests require an Apple Silicon Mac; they are not ordinary headless Linux tests. Run Gradle tasks sequentially. Start with `./gradlew --no-parallel build`; rendering changes should also pass `runClientGameTest` and `runClientNaturalGameTest` and receive visual inspection. Compare the actual packaged JAR in a clean launcher profile before a release.

Keep Java backend integration and native Metal responsibilities separated. Preserve resource ownership, fence retirement, uniform bindings, and visible rendering behavior. Performance changes should include matched-scene measurements with validation disabled and a visual comparison. Do not claim compatibility or performance gains without evidence.

Submit focused pull requests describing the behavior changed, hardware tested, verification performed, and remaining limitations. Preserve applicable licensing and attribution, and identify substantial AI-generated contributions so reviewers understand their provenance.
