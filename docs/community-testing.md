# Community testing

Testing on your own Mac is a useful contribution, including when everything works. No coding or build tools are needed for the player checklist. Submit a [hardware test report](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=hardware_test.yml) with the exact setup and checks you tried. For a specific reproducible defect, use the [bug report form](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=bug_report.yml) and link it from your test report.

## Where testing helps most

The [current validation record](validation.md) covers an Apple M3 Pro on macOS 26.5.2. It is evidence for specific scenarios, not a certification of every M-series Mac.

| Coverage gap | Useful contributions |
| --- | --- |
| Other Apple Silicon chips | M1/M2 machines and other generations or base, Pro, Max, and Ultra variants; include the exact chip and RAM |
| Different macOS versions | Especially macOS 14, the minimum build target; record the exact version, including patch release |
| Display configurations | Built-in Retina and external displays, different scaling and refresh rates, windowed/fullscreen transitions, moving the window between displays |
| Longer gameplay | Survival sessions, Nether/End travel, weather, underwater views, dense transparency, and world re-entry |
| Packs and other mods | A clean baseline first, then one added pack or mod at a time with exact names and versions |

Intel Macs, Windows, and Linux are outside the current support target. Similar machines can still produce useful reports when the OS, display, game settings, or tested scenarios differ.

## A 10–15 minute player check

1. Follow the [installation guide](installation.md) using a separate Minecraft **26.3** profile with Fabric Loader **0.19.5** and the released Minecraft Metal JAR. Start with no other mods or resource packs. Use a disposable world or a copy of a world, and record the mod version.
2. Confirm `Using graphics backend Metal` and `Metal device created` appear in the profile's `logs/latest.log`. If startup fails or you cannot confirm Metal is active, report that; do not count the run as a Metal pass.
3. Create a Creative world. Record its seed, render and simulation distances, resolution, GUI scale, VSync/FPS cap, and Improved Transparency setting. Using seed `1` and eight-chunk render distance is a useful common starting point; include coordinates and viewing direction when reporting a scene problem.
4. Try the checks below. Record **Pass**, **Issue**, or **Not tested** for each. A partial report is welcome; stop and report a crash or hang if one prevents the remaining checks.
5. Submit the hardware test report. Include a screenshot of the world and any visual defects if possible. A successful report is useful even without an FPS measurement.

| Check | What to look for |
| --- | --- |
| Startup and menus | Readable text, correct title panorama, responsive menus |
| Terrain and movement | Chunks appear while moving; no persistent missing terrain, flashing geometry, or corrupted textures |
| Water, glass, and leaves | Correct surfaces and overlap; look above and below water |
| Improved Transparency off → on → off | Compare the same water/glass scene in each mode; watch for missing objects or corruption after switching |
| Entities, particles, and inventory | Visible mobs, particles, held items, inventory icons, and text |
| Lighting | Daylight and a torch-lit interior render sensibly |
| Resource reload | F3+T completes and the world still renders (the Mac keyboard may also require Fn) |
| Resize and fullscreen | Resize the window, enter fullscreen, then return; rendering should recover without a stuck or blank image |
| Save and re-enter | Return to the menu and reopen the test world |

After that, optional longer sessions can cover dimension travel, weather, display changes, or a particular pack/mod. Include how long you played and which extra scenarios you tried. Do not mark a check as passed just because the game stayed open.

## Automated tests for source contributors

The repository already automates GPU checks and two Minecraft walkthroughs. After installing the [development prerequisites](development.md#build-and-run), run this from the repository root:

```sh
./gradlew --no-parallel build runClientGameTest runClientNaturalGameTest
```

Use an Apple Silicon Mac with an active graphical login session. The client tests open Minecraft windows and switch fullscreen modes, so run them when they will not interrupt your work. The first run downloads build/game dependencies. Do not run another project build or development client at the same time.

| Stage | What it does | Evidence to review |
| --- | --- | --- |
| `build` | Builds the mod and runs native GPU/readback, shader compilation, and transient-memory checks with Metal API Validation | Gradle output, including any failure |
| `runClientGameTest` | Creates a disposable flat world; checks Metal selection, transparency switching, and boat water-mask execution; captures materials, entities, particles, inventory, lighting, and resize scenes | `build/run/clientGameTest/logs/latest.log` and `build/run/clientGameTest/screenshots/` |
| `runClientNaturalGameTest` | Creates a disposable seed-1 world; checks Metal and indexed indirect terrain drawing; exercises transparency, movement, reload, and fullscreen | `build/run/clientNaturalGameTest/logs/latest.log` and `build/run/clientNaturalGameTest/screenshots/` |

Both gameplay tasks require a fresh completion marker as well as a successful process exit. Their marker is `metal-gametest-complete.txt` in the corresponding run directory. If a stage fails, later stages may not run: report them as **Not tested**, and attach the failing stage's output. Only attach logs and screenshots from this attempt; a run directory may contain evidence from an older run.

**A passing command still needs visual review.** The GPU tests assert selected pixel values and the gameplay harness checks specific rendering paths; screenshots are captured for a person to inspect. Report automated completion and screenshot review separately. These runs use Metal API Validation and controlled test scheduling, so their FPS output is not a performance benchmark.

For a source run, include `git rev-parse HEAD`, whether you changed the source, and which stages completed. A source result does not verify installation of the published JAR; the player checklist covers that separately. Players using the released JAR do not need the source harness or Fabric API.

## If you want to compare performance

Keep comparisons optional. Use the same world copy, location, camera direction, resolution, graphics settings, render distance, VSync/FPS cap, and power mode. Let chunks and shaders settle before measuring; use the same warm-up and measurement duration, repeat each run, and report the method and variation as well as the numbers.

Restart between Metal and the comparison backend. In a normal launcher, `-Dmetal.enabled=false` disables this mod's backend selection; confirm and report the backend actually used on each run. Disable Metal API Validation for timing, and include AC/battery status and any visible rendering differences. An FPS value from a single screenshot or an automated walkthrough does not establish a speedup.

## Sharing a useful report

Use one report per configuration and mod version, or add a dated follow-up that clearly lists what changed. Search existing reports for your chip or issue first; link related reports rather than assuming the same symptom has the same cause. Include:

- Mac model/chip and RAM; exact macOS, Minecraft, mod, Fabric Loader, Java version/architecture, and launcher. For source tests, include the commit and local changes.
- Display setup and graphics settings, test duration, and other mods/packs (or explicitly “none”).
- Pass/Issue/Not tested results, Metal activation evidence, and any reproduction steps. Separate automated assertions from visual review.
- Relevant screenshots and log excerpts, or a linked bug report for a reproducible failure.

Review attachments before posting: logs and screenshots may include usernames, personal paths, chat, server addresses, or credentials. Do not upload launcher account files, entire game folders, or private worlds. This workflow saves evidence locally and asks you to submit it yourself; it does not upload it automatically.

Reports should remain scoped to the version, configuration, and scenarios tested. Maintainers can link reviewed reports from the [validation record](validation.md); untested combinations remain unknown, and a passing report is not a claim of universal compatibility.

## Ways to improve automation

These are contribution ideas, not features currently shipped:

- Add an opt-in local runner that invokes the existing tests and prepares a report with chip/RAM/OS, versions, stage results, and selected evidence. Keep collection narrow, distinguish incomplete runs from passes, and let the tester inspect everything before sharing.
- Extend the gameplay harness with repeatable dimension, underwater, weather, and longer-session scenarios. Pair meaningful assertions with reviewable screenshots.
- Add matched-scene performance capture with validation disabled, fixed warm-up/sampling periods, repeated runs, and the actual backend/settings recorded.
- Turn reviewed reports into a coverage table keyed by mod version, chip, macOS, and display setup, linking each result to evidence and keeping skipped checks visible.

Unattended GPU testing would still need suitable Apple Silicon hardware and a working graphical session for the walkthroughs. Any future shared Mac runners should run maintainer-reviewed code; contributors can run the current harness locally without giving the project remote access to their computers.
