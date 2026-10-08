# Install Minecraft Metal

This guide installs the experimental **0.2.2** release for **Minecraft Java 26.3** on an **Apple Silicon Mac**. It requires Fabric Loader 0.19.3 or newer (0.19.5 tested), macOS 14 or newer (26.5.2 tested), and ARM64 Java 25 or newer. Intel Macs, Windows, and Linux are unsupported.

**You do not need Fabric API or developer tools to run the mod.** Its native Metal library is included. The official launcher's bundled ARM64 Java 25.0.1 was used for the clean-install test.

## Complete suite

The [suite ZIP](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/download/v0.2.2/minecraft-metal-suite-0.2.2.zip) contains all three mods alongside the now-retired Canopy and Solstice packs. Extract it once and copy only its three intact JARs into `mods`. Set `pack=` (empty) in `config/minecraft-shader-loader.properties` and remove old `profile` and `option.*` entries to disable the retired packs. Remove older copies of these mods before updating. The old bundled `INSTALL.txt` selects Canopy; use the settings above instead. Current source builds include no shader packs.

The steps below also support installing the renderer alone. For the companion mods and switching packs, see [shader setup](shader-loader.md). The suite does not change launcher JVM arguments; [optional performance settings](releases/performance-settings.md) reproduce the tuned benchmark configuration.

## Official Minecraft Launcher

1. **Install Fabric for Minecraft 26.3.** Follow [Fabric's macOS installer guide](https://docs.fabricmc.net/players/installing-fabric/macos), selecting Minecraft **26.3** and Fabric Loader **0.19.5**. The installer creates a Fabric launcher profile. Running the Fabric installer itself may require a separately installed Java runtime; follow Fabric's guide if the installer will not open.
2. **Give the profile a separate game directory.** Open the launcher's **Installations** tab, edit the Fabric profile, and set **Game Directory** to a new folder such as a `Minecraft Metal Test` folder in your home directory. Save the profile. This keeps the initial test separate from existing worlds and mods.
3. **Add the mod.** Download [minecraft-metal-renderer-0.2.2.jar](https://github.com/Kausik-Velaga/minecraft-metal-renderer/releases/download/v0.2.2/minecraft-metal-renderer-0.2.2.jar). In Finder, open the game directory you selected and create a folder named **`mods`** if it does not exist. Move the JAR into it. Do not double-click, unpack, or rename the JAR. Do not use GitHub's **Source code** ZIP or TAR downloads.
4. **Play.** Select that Fabric profile and launch Minecraft. Metal activates automatically. Start with a new test world and no other mods or resource packs.

The `mods` folder belongs inside the selected **game directory**, not inside the launcher application or the game's `versions` folder. For profiles using the default game directory, Finder's **Go → Go to Folder** can open `~/Library/Application Support/minecraft`; the folder for mods is inside it. A separate profile with no custom game directory may still share that default folder, so check the setting before adding the JAR.

### Nexus Mods downloads

The earlier 0.2.0 release on Nexus Mods requires an archive, so its download is `minecraft-metal-renderer-0.2.0-nexus.zip`. Extract that outer ZIP once, then place the enclosed **`minecraft-metal-renderer-0.2.0.jar`** in your profile's `mods` folder. Keep the JAR intact. Do not put the outer ZIP, `INSTALL.txt`, license, or checksum file in `mods`. The enclosed JAR is identical to the GitHub and CurseForge release file. Use manual installation; Vortex integration has not been tested.

## Other launchers

Create a Minecraft **26.3** instance and install Fabric Loader **0.19.5** using the launcher's instance settings. Select **ARM64 Java 25 or newer** if the launcher asks for a Java runtime. Use its option to open the instance's game folder, then place the same JAR in `mods`. Start with no other mods or resource packs.

Launcher interfaces differ; this project's clean-install evidence covers the official launcher, not every third-party launcher.

## Confirm that it is active

The mod does not add a settings screen. In the profile's game directory, open `logs/latest.log` after launching and search for **`Using graphics backend Metal`** and **`Metal device created`**. If neither appears, confirm you launched the correct Fabric profile and installed the mod in that profile's game directory.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Fabric reports the wrong Minecraft or Java version | Use Minecraft 26.3 and ARM64 Java 25 or newer. For a custom Java setting, verify both the version and ARM64 architecture. |
| The game crashes or reports a native-library error | Confirm this is an Apple Silicon Mac with macOS 14+. Retest with only this mod installed. |
| Rendering is incorrect | Retest without other rendering mods, shader packs, or resource packs, then report the scene and settings that reproduce it. |
| An older Metal mod is already installed | Remove the previous Minecraft Metal JAR. Keep only one Minecraft Metal version installed. |
| The game works only after removing the mod | Keep it removed for normal play and submit a bug report with the relevant log or crash report. |

BSL 10.1.8 requires the optional loader and [shader-pack setup](shader-loader.md). Other packs, Sodium, Iris, and rendering replacements are unverified. There is no automatic fallback if Metal initialization fails. As an alternative to removing the mod, advanced users can add `-Dmetal.enabled=false` to the profile's JVM arguments to use Minecraft's selected graphics API.

Report problems through the [bug report form](https://github.com/Kausik-Velaga/minecraft-metal-renderer/issues/new?template=bug_report.yml). Include the Mac chip, macOS, Minecraft, Fabric Loader, Java and mod versions; other mods; and steps to reproduce the problem. Screenshots help with visual defects. Remove access tokens, private server addresses, chat, and personal paths before sharing logs; do not upload launcher account files.

## Update or uninstall

Close Minecraft before changing mods. To update, replace the old Minecraft Metal JAR with a release for the same Minecraft version. To uninstall, remove the JAR from the profile's `mods` folder. The separate test profile can then be used without this mod.
