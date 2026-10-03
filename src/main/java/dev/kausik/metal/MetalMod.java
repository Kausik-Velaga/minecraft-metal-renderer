package dev.kausik.metal;

import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client entry point and early, dependency-free backend selection. */
public final class MetalMod implements ClientModInitializer {
  public static final String ID = "minecraft_metal";
  public static final Logger LOGGER = LoggerFactory.getLogger(ID);

  /** Read before client initialization, when Minecraft creates its window. */
  public static boolean isEnabled() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
        && (System.getProperty("os.arch", "").equals("aarch64")
            || System.getProperty("os.arch", "").equals("arm64"))
        && Boolean.parseBoolean(System.getProperty("metal.enabled", "true"));
  }

  @Override
  public void onInitializeClient() {
    LOGGER.info(
        "Native Metal backend {}. Use -Dmetal.enabled=false to use Minecraft's graphics setting.",
        isEnabled() ? "selected" : "disabled");
  }
}
