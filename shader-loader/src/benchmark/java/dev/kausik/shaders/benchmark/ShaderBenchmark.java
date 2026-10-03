package dev.kausik.shaders.benchmark;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Benchmark-only Fabric mod. The normal client and integrated server advance themselves; there is
 * no test scheduler, synthetic frame pacing, GPU wait, or frame sampling in the shipping mods.
 */
public final class ShaderBenchmark implements ClientModInitializer {
  private static final String PREFIX = "minecraftShaders.benchmark.";
  private static final int MAX_SAMPLES = 1_000_000;
  private static ShaderBenchmark instance;
  private final long[] intervals = new long[MAX_SAMPLES];
  private final long[] runTicks = new long[MAX_SAMPLES];
  private final String label = property("label", "bsl-default");
  private final String expectedWorld = property("world", "shader-benchmark-scene");
  private final boolean expectPack = Boolean.parseBoolean(property("expectPack", "true"));
  private final int width = integer("width", 1280, 320, 7680);
  private final int height = integer("height", 720, 240, 4320);
  private final int viewDistance = integer("viewDistance", 5, 2, 32);
  private final int simulationDistance = integer("simulationDistance", 5, 2, 32);
  private final long warmupNanos = integer("warmupSeconds", 30, 1, 600) * 1_000_000_000L;
  private final long measureNanos = integer("measureSeconds", 60, 1, 1800) * 1_000_000_000L;
  private final long startupNanos = integer("startupSeconds", 240, 30, 1800) * 1_000_000_000L;
  private final Map<String, Object> metadata = new LinkedHashMap<>();
  private Path output;
  private long started,
      warmupStarted,
      measuredStarted,
      previousFrameStarted,
      frameStarted,
      previousRunTick;
  private long gcCount, gcMillis;
  private int count, resizeAttempts;
  private boolean configured, setupRequested, measuring, finished;
  private volatile boolean sceneReady;
  private volatile Throwable setupFailure;

  @Override
  public void onInitializeClient() {
    if (!Boolean.getBoolean("minecraftShaders.benchmark"))
      throw new IllegalStateException("Benchmark mod requires -DminecraftShaders.benchmark=true");
    if ("1".equals(System.getenv("MTL_DEBUG_LAYER")))
      throw new IllegalStateException(
          "Disable Metal API Validation for independent FPS measurements");
    if (Boolean.getBoolean("fabric.client.gametest"))
      throw new IllegalStateException(
          "Use the normal client benchmark run, not client gametest scheduling");
    output =
        Path.of(
            property(
                "output",
                FabricLoader.getInstance().getGameDir().resolve("benchmarks").toString()));
    Arrays.fill(intervals, -1L);
    Arrays.fill(runTicks, -1L);
    instance = this;
    System.out.println("[Shader benchmark] Awaiting normal quick-play world " + expectedWorld);
  }

  public static void frameStart(Minecraft client, boolean advanceTime) {
    ShaderBenchmark benchmark = instance;
    if (benchmark == null || benchmark.finished) return;
    try {
      benchmark.start(client, advanceTime);
    } catch (Throwable failure) {
      benchmark.fail(client, failure);
    }
  }

  public static void frameEnd() {
    ShaderBenchmark benchmark = instance;
    if (benchmark != null && !benchmark.finished && benchmark.frameStarted != 0)
      benchmark.previousRunTick = System.nanoTime() - benchmark.frameStarted;
  }

  private void start(Minecraft client, boolean advanceTime) throws Exception {
    long now = System.nanoTime();
    frameStarted = now;
    if (started == 0) started = now;
    if (!configured) configure(client);
    if (setupFailure != null)
      throw new IllegalStateException("Benchmark scene setup failed", setupFailure);
    if (measuring) {
      requireStable(client, advanceTime);
      if (measuredStarted == 0) {
        // Start one normal frame after metadata/log setup, excluding instrumentation startup.
        measuredStarted = now;
        previousFrameStarted = now;
        return;
      }
      if (count == MAX_SAMPLES)
        throw new IllegalStateException("Benchmark sample capacity exceeded");
      intervals[count] = now - previousFrameStarted;
      runTicks[count] = previousRunTick;
      count++;
      previousFrameStarted = now;
      if (now - measuredStarted >= measureNanos) finish(client);
      return;
    }
    if (now - started > startupNanos)
      throw new IllegalStateException(
          "Timed out loading the fixed benchmark scene; verify --quickPlaySingleplayer and world"
              + " name");
    if (client.level == null || client.player == null || client.getSingleplayerServer() == null)
      return;
    if (!setupRequested) setupScene(client);
    if (!sceneReady || client.gui.screen() != null || client.gui.overlay() != null) return;
    if (!atViewpoint(client) || !client.levelRenderer.hasRenderedAllSections()) {
      warmupStarted = 0;
      return;
    }
    if (client.getWindow().getWidth() != width || client.getWindow().getHeight() != height) {
      if (++resizeAttempts > 4)
        throw new IllegalStateException(
            "Could not obtain requested framebuffer size " + width + "x" + height);
      resizeFramebuffer(client);
      warmupStarted = 0;
      return;
    }
    requireStable(client, advanceTime);
    if (warmupStarted == 0) {
      warmupStarted = now;
      client.gui.hud.getChat().clearMessages(false);
      client.gui.toastManager().clear();
      System.out.println(
          "[Shader benchmark] Warming up for "
              + warmupNanos / 1_000_000_000L
              + "s at "
              + width
              + "x"
              + height);
    }
    if (now - warmupStarted < warmupNanos) return;
    metadata.put("startedUtc", Instant.now().toString());
    metadata.put("visibleSections", client.levelRenderer.visibleSections().size());
    metadata.put("windowFocusedAtStart", client.getWindow().isFocused());
    gcCount = gc(false);
    gcMillis = gc(true);
    measuring = true;
    System.out.println(
        "[Shader benchmark] Measuring all frame intervals for "
            + measureNanos / 1_000_000_000L
            + "s");
  }

  private void configure(Minecraft client) throws Exception {
    client.options.renderDistance().set(viewDistance);
    client.options.simulationDistance().set(simulationDistance);
    client.options.enableVsync().set(false);
    client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
    client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
    client.options.fullscreen().set(false);
    client.options.pauseOnLostFocus = false;
    client.options.fov().set(70);
    client.options.bobView().set(false);
    client.options.cloudStatus().set(CloudStatus.FANCY);
    client.options.cloudRange().set(5);
    client.options.ambientOcclusion().set(true);
    client.options.improvedTransparency().set(false);
    client.options.entityDistanceScaling().set(1.0);
    client.options.gamma().set(0.5);
    if (client.gui.hud.isHidden()) client.gui.hud.toggle();
    resizeFramebuffer(client);
    var device = RenderSystem.getDevice().getDeviceInfo();
    if (!device.backendName().equals("Metal"))
      throw new IllegalStateException("Benchmark expected Metal, got " + device.backendName());
    boolean selectedPack = false;
    if (FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      selectedPack =
          Class.forName("dev.kausik.shaders.runtime.ShaderRuntime").getMethod("get").invoke(null)
              != null;
    if (selectedPack != expectPack)
      throw new IllegalStateException(
          "Expected pack=" + expectPack + ", actual selected pack=" + selectedPack);
    metadata.put("label", label);
    metadata.put("backend", device.backendName());
    metadata.put("device", device.name());
    metadata.put("driver", device.driverInfo());
    metadata.put(
        "minecraftVersion",
        FabricLoader.getInstance()
            .getModContainer("minecraft")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString());
    metadata.put(
        "shaderLoaderInstalled", FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"));
    if (Boolean.getBoolean(PREFIX + "production")
        && FabricLoader.getInstance().isModLoaded("fabric-api"))
      throw new IllegalStateException(
          "Packaged installation validation must not include Fabric API");
    metadata.put("fabricApiInstalled", FabricLoader.getInstance().isModLoaded("fabric-api"));
    metadata.put("packagedInstallation", Boolean.getBoolean(PREFIX + "production"));
    metadata.put(
        "loadedMods",
        FabricLoader.getInstance().getAllMods().stream()
            .map(
                mod ->
                    mod.getMetadata().getId()
                        + " "
                        + mod.getMetadata().getVersion().getFriendlyString())
            .sorted()
            .toList());
    metadata.put("selectedPack", selectedPack);
    metadata.put("packSha256", packHash());
    metadata.put("framebufferWidth", width);
    metadata.put("framebufferHeight", height);
    metadata.put("viewDistanceChunks", viewDistance);
    metadata.put("simulationDistanceChunks", simulationDistance);
    metadata.put("warmupSeconds", warmupNanos / 1e9);
    metadata.put("requestedMeasurementSeconds", measureNanos / 1e9);
    metadata.put("vsync", false);
    metadata.put("frameLimit", "unlimited");
    metadata.put("metalApiValidation", false);
    metadata.put("gameTestScheduling", false);
    metadata.put("scene", expectedWorld);
    metadata.put(
        "viewpoint", Map.of("x", 11, "y", -54, "z", 14, "yaw", 145, "pitch", 27, "fov", 70));
    metadata.put("worldTime", 4000);
    metadata.put("weather", "clear");
    metadata.put("hudVisible", true);
    metadata.put("clouds", "fancy");
    metadata.put("cloudRangeChunks", 5);
    metadata.put("improvedTransparencyPreference", false);
    metadata.put("effectiveTransparency", "classic");
    metadata.put(
        "notes",
        "Normal static-scene game loop. Start-to-start wall intervals include presentation,"
            + " integrated-server contention and GC; they are not GPU timings. No stalls are"
            + " removed. Percentiles use nearest rank.");
    configured = true;
  }

  private void resizeFramebuffer(Minecraft client) {
    var window = client.getWindow();
    float density = Math.max(1, window.getPixelDensity());
    // setWindowed takes logical window coordinates. The measurement uses actual framebuffer pixels.
    window.setWindowed(Math.round(width / density), Math.round(height / density));
  }

  private void setupScene(Minecraft client) {
    var server = client.getSingleplayerServer();
    Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    if (!world.getFileName().toString().equals(expectedWorld))
      throw new IllegalStateException(
          "Refusing benchmark scene commands in unexpected world " + world.getFileName());
    setupRequested = true;
    server.execute(
        () -> {
          try {
            var source = server.createCommandSourceStack();
            for (String command :
                new String[] {
                  "gamerule minecraft:send_command_feedback false",
                  "gamerule minecraft:advance_time false",
                  "time set 4000",
                  "weather clear 1000000",
                  "gamemode creative @a",
                  "clear @a",
                  "give @a minecraft:diamond_sword",
                  "tp @a 11.0 -54.0 14.0 145 27"
                }) server.getCommands().performPrefixedCommand(source, command);
            sceneReady = true;
          } catch (Throwable failure) {
            setupFailure = failure;
          }
        });
  }

  private boolean atViewpoint(Minecraft client) {
    var player = client.player;
    return player != null
        && Math.abs(player.getX() - 11) < .05
        && Math.abs(player.getY() + 54) < .05
        && Math.abs(player.getZ() - 14) < .05
        && Math.abs(player.getYRot() - 145) < .05
        && Math.abs(player.getXRot() - 27) < .05;
  }

  private void requireStable(Minecraft client, boolean advanceTime) {
    if (!advanceTime
        || client.level == null
        || client.player == null
        || client.gui.screen() != null
        || client.gui.overlay() != null
        || client.getWindow().isIconified()
        || !atViewpoint(client))
      throw new IllegalStateException("Benchmark scene/viewpoint was interrupted");
    if (client.getWindow().getWidth() != width || client.getWindow().getHeight() != height)
      throw new IllegalStateException("Framebuffer changed during measurement");
    if (client.options.enableVsync().get()
        || client.getFramerateLimitTracker().getFramerateLimit()
            < Options.UNLIMITED_FRAMERATE_CUTOFF)
      throw new IllegalStateException("Benchmark frame pacing became limited");
  }

  private void finish(Minecraft client) throws IOException {
    finished = true;
    metadata.put("frameIntervals", FrameStatistics.calculate(intervals, count));
    metadata.put("runTickWallTime", FrameStatistics.calculate(runTicks, count));
    metadata.put("gcCollections", gc(false) - gcCount);
    metadata.put("gcCollectionMillis", gc(true) - gcMillis);
    metadata.put("visibleSectionsAtEnd", client.levelRenderer.visibleSections().size());
    metadata.put("windowFocusedAtEnd", client.getWindow().isFocused());
    Files.createDirectories(output);
    String file = label.replaceAll("[^a-zA-Z0-9._-]", "_");
    try (var writer = Files.newBufferedWriter(output.resolve(file + ".csv"))) {
      writer.write("frame,interval_ns,run_tick_wall_ns\n");
      for (int i = 0; i < count; i++)
        writer.write(i + "," + intervals[i] + "," + runTicks[i] + "\n");
    }
    Path summary = output.resolve(file + ".json");
    Files.writeString(
        summary, new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
    FrameStatistics result = (FrameStatistics) metadata.get("frameIntervals");
    System.out.printf(
        Locale.ROOT,
        "[Shader benchmark] COMPLETE %s: %.2f FPS, p50 %.3fms, p95 %.3fms, p99 %.3fms (%d frames)."
            + " %s%n",
        label,
        result.meanFps(),
        result.p50Ms(),
        result.p95Ms(),
        result.p99Ms(),
        count,
        summary);
    // Capture only after measurement, so the readback never enters the frame-time sample.
    if (Boolean.getBoolean(PREFIX + "production")) {
      Screenshot.takeScreenshot(
          client.gameRenderer.mainRenderTarget(),
          image -> {
            try (image) {
              image.writeToFile(output.resolve(file + ".png"));
            } catch (IOException failure) {
              fail(client, failure);
            } finally {
              client.stop();
            }
          });
    } else client.stop();
  }

  private void fail(Minecraft client, Throwable failure) {
    finished = true;
    failure.printStackTrace();
    try {
      Files.createDirectories(output);
      Files.writeString(
          output.resolve(label.replaceAll("[^a-zA-Z0-9._-]", "_") + ".failed.txt"), failure + "\n");
    } catch (IOException writeFailure) {
      failure.addSuppressed(writeFailure);
    }
    client.stop();
  }

  private static long gc(boolean millis) {
    long total = 0;
    for (var collector : ManagementFactory.getGarbageCollectorMXBeans())
      total += Math.max(0, millis ? collector.getCollectionTime() : collector.getCollectionCount());
    return total;
  }

  private static String packHash() throws Exception {
    String selected = System.getProperty("minecraftShaders.pack", "");
    if (selected.isBlank() || !Files.isRegularFile(Path.of(selected)))
      return "not supplied as a ZIP system property";
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(Path.of(selected))) {
      byte[] buffer = new byte[65536];
      int read;
      while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(hash.digest());
  }

  private static String property(String name, String fallback) {
    return System.getProperty(PREFIX + name, fallback);
  }

  private static int integer(String name, int fallback, int minimum, int maximum) {
    int result = Integer.parseInt(property(name, Integer.toString(fallback)));
    if (result < minimum || result > maximum)
      throw new IllegalArgumentException(
          "Benchmark " + name + " outside " + minimum + ".." + maximum);
    return result;
  }
}
