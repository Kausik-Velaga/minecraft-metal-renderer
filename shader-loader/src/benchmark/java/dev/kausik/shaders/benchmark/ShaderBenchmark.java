package dev.kausik.shaders.benchmark;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Benchmark-only Fabric mod. The normal client and integrated server advance themselves; there is
 * no test scheduler, synthetic frame pacing, GPU wait, or frame sampling in the shipping mods.
 */
public final class ShaderBenchmark implements ClientModInitializer {
  private static final String PREFIX = "minecraftShaders.benchmark.";
  private static final int MAX_SAMPLES = 1_000_000;
  private static ShaderBenchmark instance;
  private static volatile UUID preparationPlayerId;
  private final long[] intervals = new long[MAX_SAMPLES];
  private final long[] runTicks = new long[MAX_SAMPLES];
  private final String label = property("label", "bsl-default");
  private final String expectedWorld = property("world", "shader-benchmark-scene");
  private final String scene = property("scene", "flat");
  private final boolean natural = scene.equals("natural");
  private final BenchmarkActions actions = new BenchmarkActions(property("action", "static"));
  private final BenchmarkActions.Sample[] actionSamples =
      actions.enabled() ? new BenchmarkActions.Sample[MAX_SAMPLES] : null;
  private final boolean validationOnly = Boolean.parseBoolean(property("validationOnly", "false"));
  private final boolean profilerEnabled = Boolean.getBoolean("minecraftShaders.profiler.enabled");
  private final boolean sparkEnabled = Boolean.getBoolean(PREFIX + "spark");
  private final SparkCapture spark = sparkEnabled ? new SparkCapture() : null;
  private final boolean offscreenPresentation =
      "1".equals(System.getenv("MINECRAFT_METAL_OFFSCREEN_PRESENT"));
  private final boolean diagnosticTiming =
      validationOnly
          || profilerEnabled
          || sparkEnabled
          || offscreenPresentation
          || "1".equals(System.getenv("MINECRAFT_METAL_RENDER_STAGE_TIMINGS"))
          || Boolean.getBoolean("minecraftScene.asyncSelection");
  private final boolean expectPack = Boolean.parseBoolean(property("expectPack", "true"));
  private final VisualComparison visual = new VisualComparison();
  private final int width = integer("width", 1280, 320, 7680);
  private final int height = integer("height", 720, 240, 4320);
  private final int viewDistance = integer("viewDistance", natural ? 16 : 5, 2, 32);
  private final int simulationDistance = integer("simulationDistance", natural ? 12 : 5, 2, 32);
  private final int worldTime = integer("worldTime", 4000, 0, 23999);
  private final double x = decimal("x", natural ? 151.5 : 11);
  private final double y = decimal("y", natural ? 95 : -54);
  private final double z = decimal("z", natural ? 160.5 : 14);
  private final double yaw = decimal("yaw", natural ? -25 : 145);
  private final double pitch = decimal("pitch", natural ? 24 : 27);
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
  private long processCpuStarted, renderCpuStarted;
  private long nativeIcbStarted = -1;
  private String nativeHazardModeStarted;
  private long[] nativeIcbSplitsStarted;
  private long[] nativeIcbReuseStarted;
  private long[] nativeExecutionStarted;
  private long[] nativeCpuIcbStarted;
  private long[] nativeIndirectSnapshotStarted;
  private long[] nativeFenceWaitStarted;
  private long lastWaitingDiagnostic;
  private int count, resizeAttempts;
  private boolean configured, setupRequested, initialMeshingComplete, measuring, finished;
  private volatile boolean sceneReady;
  private volatile Throwable setupFailure;
  private boolean startupRecenterRequested;
  private volatile boolean startupRecenterPending;
  private CompletableFuture<Void> beginScreenshot, midpointScreenshot;

  @Override
  public void onInitializeClient() {
    if (!Boolean.getBoolean("minecraftShaders.benchmark"))
      throw new IllegalStateException("Benchmark mod requires -DminecraftShaders.benchmark=true");
    if (System.getProperty(PREFIX + "inputs", "").isBlank())
      throw new IllegalStateException(
          "Launch benchmarks through Gradle to record input identities");
    if (!scene.equals("flat") && !natural)
      throw new IllegalArgumentException("Benchmark scene must be flat or natural");
    if (actions.enabled() && !natural)
      throw new IllegalArgumentException("Action benchmarks require benchmarkScene=natural");
    visual.validate(validationOnly, actions.enabled(), expectPack);
    if (sparkEnabled && (visual.enabled() || validationOnly))
      throw new IllegalArgumentException("Spark captures require a normal diagnostic scene run");
    if (validationOnly && !actions.enabled() && !visual.enabled())
      throw new IllegalArgumentException("Validation-only capture requires an action scenario");
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

  /**
   * Physical input must not alter an automated camera or route; scripted key states still apply.
   */
  public static boolean ownsInput() {
    return instance != null && !instance.finished;
  }

  public static void frameStart(Minecraft client, boolean advanceTime) {
    ShaderBenchmark benchmark = instance;
    if (benchmark == null || benchmark.finished) return;
    try {
      benchmark.start(client, advanceTime);
    } catch (Throwable failure) {
      preparationPlayerId = null;
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
    if (visual.started()) {
      requireStable(client, advanceTime);
      if (visual.frameStart(client, output, label, metadata)) {
        recordRuntimeDiagnosticsEnd();
        finished = true;
        Files.createDirectories(output);
        Path summary = output.resolve(label.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json");
        Files.writeString(
            summary, new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
        System.out.println("[Shader benchmark] VISUAL COMPARISON COMPLETE " + summary);
        client.stop();
      }
      return;
    }
    if (measuring) {
      requireStable(client, advanceTime);
      if (measuredStarted == 0) {
        // Start one normal frame after metadata/log setup, excluding instrumentation startup.
        recordRuntimeDiagnosticsStart("measured frames");
        measuredStarted = now;
        previousFrameStarted = now;
        processCpuStarted = processCpuTime();
        renderCpuStarted = renderCpuTime();
        if (actions.enabled()) {
          if (!actions.stationaryMining()) preparationPlayerId = null;
          metadata.put("startupPushProtectionReleased", !actions.stationaryMining());
          actions.advance(client, 0, measureNanos);
        }
        return;
      }
      if (count == MAX_SAMPLES)
        throw new IllegalStateException("Benchmark sample capacity exceeded");
      intervals[count] = now - previousFrameStarted;
      runTicks[count] = previousRunTick;
      if (actions.enabled()) actionSamples[count] = actions.sample(client);
      count++;
      previousFrameStarted = now;
      long elapsed = now - measuredStarted;
      if (elapsed >= measureNanos) finish(client);
      else if (actions.enabled()) {
        actions.advance(client, elapsed, measureNanos);
        // This mode has no comparative FPS result: its midpoint readback is visual validation.
        if (validationOnly && midpointScreenshot == null && elapsed >= measureNanos / 2)
          midpointScreenshot = captureImage(client, "-midpoint");
      }
      return;
    }
    waitingDiagnostic(client, now);
    if (now - started > startupNanos)
      throw new IllegalStateException(
          "Timed out loading the fixed benchmark scene; verify --quickPlaySingleplayer and world"
              + " name");
    if (client.level == null || client.player == null || client.getSingleplayerServer() == null)
      return;
    if (!setupRequested) setupScene(client);
    if (!sceneReady || client.gui.screen() != null || client.gui.overlay() != null) return;
    if (!atViewpoint(client) || !loadedView(client)) {
      warmupStarted = 0;
      initialMeshingComplete = false;
      if (actions.enabled()
          && !atViewpoint(client)
          && loadedView(client)
          && client.levelRenderer.hasRenderedAllSections()) recenterStartup(client);
      return;
    }
    if (startupRecenterPending) return;
    // Initial terrain upload must finish once. Normal world updates can queue new meshes at any
    // time; requiring an empty queue for the entire warmup would never settle in a living world.
    if (!initialMeshingComplete) {
      if (!client.levelRenderer.hasRenderedAllSections()) return;
      initialMeshingComplete = true;
    }
    if (client.getWindow().getWidth() != width || client.getWindow().getHeight() != height) {
      if (++resizeAttempts > 4)
        throw new IllegalStateException(
            "Could not obtain requested framebuffer size "
                + width
                + "x"
                + height
                + "; actual "
                + client.getWindow().getWidth()
                + "x"
                + client.getWindow().getHeight());
      resizeFramebuffer(client);
      warmupStarted = 0;
      initialMeshingComplete = false;
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
    // Capture a settled rendered frame, not the previous frame at the first mesh-ready boundary.
    // The asynchronous readback completes before any measurement begins.
    if (actions.enabled()) {
      if (beginScreenshot == null) {
        beginScreenshot = captureImage(client, "-begin");
        return;
      }
      if (!beginScreenshot.isDone()) return;
      beginScreenshot.join();
    }
    if (sparkEnabled && !spark.startWhenReady(client)) return;
    // Spark's start messages should not cover the diagnostic scene.
    if (sparkEnabled) client.gui.hud.getChat().clearMessages(false);
    metadata.put("startedUtc", Instant.now().toString());
    metadata.put(
        "actualWorldTimeAtStart", Math.floorMod(client.level.getOverworldClockTime(), 24000));
    metadata.put("viewpoint", viewpoint().values());
    if (actions.enabled()) metadata.put("actionFixture", actions.fixture().evidence());
    metadata.put("visibleSections", client.levelRenderer.visibleSections().size());
    metadata.put("terrainMeshingPendingAtStart", !client.levelRenderer.hasRenderedAllSections());
    metadata.put("initialTerrainMeshingBarrierPassed", initialMeshingComplete);
    metadata.put("shadowCullingAtStart", shadowCullingStats());
    metadata.put("windowFocusedAtStart", client.getWindow().isFocused());
    if (FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      metadata.put("materialAtlasAtStart", materialStats());
    if (actions.enabled()) metadata.put("startupRecenters", startupRecenterRequested ? 1 : 0);
    if (visual.enabled()) {
      recordRuntimeDiagnosticsStart("visual fixture freeze, reset, settling and captures");
      visual.start(client, metadata);
      return;
    }
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
    client.options.bobView().set(actions.enabled());
    client.options.cloudStatus().set(CloudStatus.FANCY);
    client.options.cloudRange().set(5);
    client.options.ambientOcclusion().set(true);
    client.options.improvedTransparency().set(false);
    client.options.entityDistanceScaling().set(1.0);
    client.options.gamma().set(0.5);
    client.options.textureFiltering().set(net.minecraft.client.TextureFilteringMethod.RGSS);
    client.options.mipmapLevels().set(4);
    if (client.gui.hud.isHidden()) client.gui.hud.toggle();
    if (visual.enabled()) client.gui.hud.toggle();
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
    try (var reader = Files.newBufferedReader(Path.of(System.getProperty(PREFIX + "inputs")))) {
      var inputs = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
      if (!inputs.get("label").getAsString().equals(label))
        throw new IllegalStateException("Benchmark input manifest label does not match");
      metadata.put("inputs", inputs);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot read benchmark input manifest", failure);
    }
    metadata.put("javaVersion", System.getProperty("java.version"));
    metadata.put("javaVmName", System.getProperty("java.vm.name"));
    metadata.put("backend", device.backendName());
    metadata.put("device", device.name());
    metadata.put("driver", device.driverInfo());
    metadata.put("renderValidationEnvironment", renderValidationEnvironment());
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
    boolean optimizerInstalled =
        FabricLoader.getInstance().isModLoaded("minecraft_scene_optimizer");
    if (Boolean.getBoolean(PREFIX + "production")
        && optimizerInstalled != Boolean.getBoolean(PREFIX + "expectOptimizer"))
      throw new IllegalStateException(
          "Packaged benchmark scene-optimizer mod set differs from request");
    metadata.put("sceneOptimizerInstalled", optimizerInstalled);
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
    metadata.put(
        "hardwareShadowComparison",
        selectedPack
            && device.underlyingExtensions().contains("depth-comparison-lequal")
            && Boolean.parseBoolean(
                System.getProperty("minecraftShaders.hardwareShadowComparison", "true")));
    metadata.put("shaderProfilerEnabled", Boolean.getBoolean("minecraftShaders.profiler.enabled"));
    metadata.put("sparkProfilerEnabled", sparkEnabled);
    metadata.put(
        "shadowReceiverCullingRequested",
        Boolean.getBoolean("minecraftShaders.shadowReceiverCulling"));
    metadata.put("nativeMipmaps", Boolean.getBoolean("minecraftShaders.nativeMipmaps"));
    metadata.put("skipUnusedMipmaps", Boolean.getBoolean("minecraftShaders.skipUnusedMipmaps"));
    metadata.put("lazyTargetClears", Boolean.getBoolean("minecraftShaders.lazyTargetClears"));
    metadata.put("sceneDrawMetadataCache", Boolean.getBoolean("minecraftScene.drawMetadataCache"));
    metadata.put("sceneAsyncSelection", Boolean.getBoolean("minecraftScene.asyncSelection"));
    metadata.put(
        "earlyAlphaDemoteRequested", Boolean.getBoolean("minecraftShaders.earlyAlphaDemote"));
    metadata.put(
        "optimizeFragmentSpirv", Boolean.getBoolean("minecraftShaders.optimizeFragmentSpirv"));
    metadata.put(
        "liftUniformInitializers", Boolean.getBoolean("minecraftShaders.liftUniformInitializers"));
    metadata.put(
        "discardFullscreenLoads", Boolean.getBoolean("minecraftShaders.discardFullscreenLoads"));
    metadata.put(
        "sparseProjectionMatrices",
        Boolean.getBoolean("minecraftShaders.sparseProjectionMatrices"));
    metadata.put("optimizeVertexSpirv", Boolean.getBoolean("minecraftShaders.optimizeVertexSpirv"));
    metadata.put("relaxedFragmentMath", Boolean.getBoolean("minecraftShaders.relaxedFragmentMath"));
    metadata.put(
        "compactTerrainVertices", Boolean.getBoolean("minecraftShaders.compactTerrainVertices"));
    metadata.put(
        "disableSparseExtraction", Boolean.getBoolean("minecraftScene.disableSparseExtraction"));
    metadata.put(
        "terrainFrameMatrices", Boolean.getBoolean("minecraftShaders.terrainFrameMatrices"));
    metadata.put(
        "indirectCommandBuffers", Boolean.getBoolean("minecraftMetal.indirectCommandBuffers"));
    metadata.put(
        "indirectCommandBufferThreshold",
        Math.max(1, Integer.getInteger("minecraftMetal.indirectCommandBufferThreshold", 64)));
    metadata.put(
        "metalMathMode", System.getenv().getOrDefault("MINECRAFT_METAL_MATH_MODE", "safe"));
    metadata.put(
        "trackedHazardsRequested", "1".equals(System.getenv("MINECRAFT_METAL_TRACKED_HAZARDS")));
    metadata.put("icbReuseRequested", "1".equals(System.getenv("MINECRAFT_METAL_ICB_REUSE")));
    metadata.put("cpuIcbRequested", "1".equals(System.getenv("MINECRAFT_METAL_CPU_ICB")));
    metadata.put(
        "textureFormatViewsRequested",
        "1".equals(System.getenv("MINECRAFT_METAL_TEXTURE_FORMAT_VIEWS")));
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
    metadata.put("sceneKind", scene);
    metadata.put("action", property("action", "static"));
    metadata.put("startupEntityPushProtection", actions.enabled());
    metadata.put("stationaryMiningEntityPushProtection", actions.stationaryMining());
    metadata.put("physicalInputSuppressed", true);
    metadata.put("validationOnly", validationOnly);
    metadata.put("offscreenPresentation", offscreenPresentation);
    if (offscreenPresentation)
      metadata.put(
          "offscreenPresentationNotes",
          "Diagnostic throughput only: final presentation rendering targets private textures;"
              + " display drawable acquisition and presentation are bypassed. Not visible FPS.");
    boolean performanceDuration = warmupNanos >= 30_000_000_000L && measureNanos >= 60_000_000_000L;
    boolean nativeStageTimings = "1".equals(System.getenv("MINECRAFT_METAL_RENDER_STAGE_TIMINGS"));
    metadata.put("nativeRenderStageTimingsRequested", nativeStageTimings);
    metadata.put("durationMeetsPerformanceProtocol", performanceDuration);
    metadata.put(
        "performanceComparable", !diagnosticTiming && !nativeStageTimings && performanceDuration);
    metadata.put(
        "timingPurpose",
        diagnosticTiming || nativeStageTimings
            ? "diagnostic"
            : performanceDuration ? "normal-client-frame-times" : "short-exploratory-sample");
    metadata.put("viewBobbing", actions.enabled());
    metadata.put(
        "viewpoint", Map.of("x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch, "fov", 70));
    metadata.put(
        "gameMode",
        actions.enabled() ? "survival" : natural || visual.enabled() ? "spectator" : "creative");
    metadata.put("textureFiltering", client.options.textureFiltering().get().toString());
    metadata.put("mipmapLevels", client.options.mipmapLevels().get());
    metadata.put("shaderProfilerEnabled", Boolean.getBoolean("minecraftShaders.profiler.enabled"));
    metadata.put(
        "shaderProfilerSampleEvery",
        System.getProperty("minecraftShaders.profiler.sampleEvery", "30"));
    metadata.put(
        "shaderProfilerOutput",
        System.getProperty("minecraftShaders.profiler.output", "logs/shader-profile.jsonl"));
    metadata.put("shaderProfile", System.getProperty("minecraftShaders.profile", ""));
    Map<String, String> overrides = new java.util.TreeMap<>();
    for (String key : System.getProperties().stringPropertyNames())
      if (key.startsWith("minecraftShaders.option."))
        overrides.put(key.substring("minecraftShaders.option.".length()), System.getProperty(key));
    metadata.put("shaderOptionSystemOverrides", overrides);
    metadata.put("worldTime", worldTime);
    metadata.put("weather", "clear");
    metadata.put("hudVisible", !visual.enabled());
    metadata.put("clouds", "fancy");
    metadata.put("cloudRangeChunks", 5);
    metadata.put("improvedTransparencyPreference", false);
    metadata.put("effectiveTransparency", "classic");
    metadata.put(
        "notes",
        "Normal game loop. Start-to-start wall intervals include presentation,"
            + " integrated-server contention and GC; they are not GPU timings. No stalls are"
            + " removed. Percentiles use nearest rank.");
    configured = true;
  }

  private void resizeFramebuffer(Minecraft client) {
    var window = client.getWindow();
    float density = Math.max(1, window.getPixelDensity());
    // setWindowed takes logical window coordinates. The measurement uses actual framebuffer pixels.
    window.setWindowed(Math.round(width / density), Math.round(height / density));
    if (Boolean.getBoolean(PREFIX + "borderless")) {
      // Remove the title bar so the requested native content area fits the laptop's usable screen.
      // This only changes the disposable benchmark window, never the renderer's resolution.
      org.lwjgl.sdl.SDLVideo.SDL_SetWindowBordered(window.handle(), false);
      org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(
          window.handle(), Math.round(width / density), Math.round(height / density));
      org.lwjgl.sdl.SDLVideo.SDL_SyncWindow(window.handle());
    }
  }

  private void setupScene(Minecraft client) {
    var server = client.getSingleplayerServer();
    Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    if (!world.getFileName().toString().equals(expectedWorld))
      throw new IllegalStateException(
          "Refusing benchmark scene commands in unexpected world " + world.getFileName());
    setupRequested = true;
    if (actions.enabled()) preparationPlayerId = client.player.getUUID();
    server.execute(
        () -> {
          try {
            var source = server.createCommandSourceStack();
            visual.prepareScene(server);
            for (String command :
                new String[] {
                  "gamerule minecraft:send_command_feedback false",
                  "gamerule minecraft:advance_time false",
                  "time set " + worldTime,
                  "weather clear 1000000",
                  "gamemode "
                      + (actions.enabled()
                          ? "survival"
                          : natural || visual.enabled() ? "spectator" : "creative")
                      + " @a",
                  "clear @a",
                  "give @a minecraft:diamond_sword"
                }) server.getCommands().performPrefixedCommand(source, command);
            if (actions.enabled()) actions.prepare(server, x, z);
            var pose = viewpoint();
            server
                .getCommands()
                .performPrefixedCommand(
                    source,
                    String.format(
                        Locale.ROOT,
                        "execute in minecraft:overworld run tp @a %.6f %.6f %.6f %.5f %.5f",
                        pose.x(),
                        pose.y(),
                        pose.z(),
                        (double) pose.yaw(),
                        (double) pose.pitch()));
            sceneReady = true;
          } catch (Throwable failure) {
            setupFailure = failure;
          }
        });
  }

  /** Stabilize startup and the stationary mining pose; ordinary mob updates continue. */
  public static boolean protectsPreparationPlayer(
      net.minecraft.world.entity.Entity first, net.minecraft.world.entity.Entity second) {
    UUID player = preparationPlayerId;
    return player != null && (player.equals(first.getUUID()) || player.equals(second.getUUID()));
  }

  private void recenterStartup(Minecraft client) {
    if (startupRecenterRequested || startupRecenterPending) return;
    startupRecenterRequested = true;
    startupRecenterPending = true;
    var server = client.getSingleplayerServer();
    UUID playerId = client.player.getUUID();
    var pose = viewpoint();
    client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    server.execute(
        () -> {
          try {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null)
              throw new IllegalStateException("Benchmark player left during recenter");
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            server
                .getCommands()
                .performPrefixedCommand(
                    server.createCommandSourceStack(),
                    String.format(
                        Locale.ROOT,
                        "execute in minecraft:overworld run tp %s %.6f %.6f %.6f %.5f %.5f",
                        playerId,
                        pose.x(),
                        pose.y(),
                        pose.z(),
                        (double) pose.yaw(),
                        (double) pose.pitch()));
            System.out.println(
                "[Shader benchmark] Recentered action start after initial terrain load");
          } catch (Throwable failure) {
            setupFailure = failure;
          } finally {
            startupRecenterPending = false;
          }
        });
  }

  private boolean atViewpoint(Minecraft client) {
    var player = client.player;
    var pose = viewpoint();
    return player != null
        && client.level.dimension().equals(Level.OVERWORLD)
        && Math.abs(player.getX() - pose.x()) < .05
        && Math.abs(player.getY() - pose.y()) < .05
        && Math.abs(player.getZ() - pose.z()) < .05
        && Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() - pose.yaw())) < .05
        && Math.abs(player.getXRot() - pose.pitch()) < .05;
  }

  private BenchmarkActions.Viewpoint viewpoint() {
    return actions.fixture() != null
        ? actions.fixture().start()
        : new BenchmarkActions.Viewpoint(x, y, z, (float) yaw, (float) pitch);
  }

  private boolean loadedView(Minecraft client) {
    return missingViewChunks(client) == 0;
  }

  private int missingViewChunks(Minecraft client) {
    if (!natural) return 0;
    var center = client.player.chunkPosition();
    int distance = client.options.getEffectiveRenderDistance();
    int missing = 0;
    for (int chunkX = center.x() - distance - 1; chunkX <= center.x() + distance + 1; chunkX++)
      for (int chunkZ = center.z() - distance - 1; chunkZ <= center.z() + distance + 1; chunkZ++)
        if (ChunkTrackingView.isInViewDistance(center.x(), center.z(), distance, chunkX, chunkZ)
            && !client.level.getChunkSource().hasChunk(chunkX, chunkZ)) missing++;
    return missing;
  }

  private void waitingDiagnostic(Minecraft client, long now) {
    if (now - lastWaitingDiagnostic < 10_000_000_000L) return;
    lastWaitingDiagnostic = now;
    boolean inWorld = client.level != null && client.player != null;
    System.out.printf(
        Locale.ROOT,
        "[Shader benchmark] Startup %.1fs: setup=%s viewpoint=%s missingChunks=%d meshed=%s"
            + " effectiveDistance=%d framebuffer=%dx%d screen=%s overlay=%s warmup=%.1fs"
            + " position=%s%n",
        (now - started) / 1e9,
        sceneReady,
        inWorld && atViewpoint(client),
        inWorld ? missingViewChunks(client) : -1,
        inWorld && client.levelRenderer.hasRenderedAllSections(),
        client.options.getEffectiveRenderDistance(),
        client.getWindow().getWidth(),
        client.getWindow().getHeight(),
        client.gui.screen() != null,
        client.gui.overlay() != null,
        warmupStarted == 0 ? 0 : (now - warmupStarted) / 1e9,
        inWorld
            ? String.format(
                Locale.ROOT,
                "%.3f,%.3f,%.3f %.3f,%.3f",
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                client.player.getYRot(),
                client.player.getXRot())
            : "none");
  }

  private void requireStable(Minecraft client, boolean advanceTime) {
    if (!advanceTime
        || client.level == null
        || client.player == null
        || client.gui.screen() != null
        || client.gui.overlay() != null
        || client.getWindow().isIconified()
        || !client.level.dimension().equals(Level.OVERWORLD)
        || (!(measuring && actions.enabled()) && (!atViewpoint(client) || !loadedView(client))))
      throw new IllegalStateException("Benchmark scene/viewpoint was interrupted");
    if (client.getWindow().getWidth() != width || client.getWindow().getHeight() != height)
      throw new IllegalStateException(
          "Framebuffer changed during measurement: requested "
              + width
              + "x"
              + height
              + ", actual "
              + client.getWindow().getWidth()
              + "x"
              + client.getWindow().getHeight());
    if (client.options.enableVsync().get()
        || client.getFramerateLimitTracker().getFramerateLimit()
            < Options.UNLIMITED_FRAMERATE_CUTOFF)
      throw new IllegalStateException("Benchmark frame pacing became limited");
  }

  private void finish(Minecraft client) throws Exception {
    long processCpuEnded = processCpuTime(), renderCpuEnded = renderCpuTime();
    recordRuntimeDiagnosticsEnd();
    if (actions.enabled()) {
      actions.release(client);
      actions.validate();
      if (midpointScreenshot != null) midpointScreenshot.join();
      metadata.put("actionResult", actions.result());
      metadata.put("actionPhases", actionPhaseStatistics());
    }
    finished = true;
    metadata.put(
        diagnosticTiming ? "diagnosticFrameIntervals" : "frameIntervals",
        FrameStatistics.calculate(intervals, count));
    metadata.put(
        diagnosticTiming ? "diagnosticRunTickWallTime" : "runTickWallTime",
        FrameStatistics.calculate(runTicks, count));
    metadata.put("gcCollections", gc(false) - gcCount);
    metadata.put("gcCollectionMillis", gc(true) - gcMillis);
    metadata.put("processCpuTimeMs", cpuMillis(processCpuStarted, processCpuEnded));
    metadata.put("renderThreadCpuTimeMs", cpuMillis(renderCpuStarted, renderCpuEnded));
    metadata.put(
        "cpuTimingNotes",
        "CPU execution time across the measured interval, excluding waits; process includes"
            + " integrated server, compilation, GC and worker threads. -1 means unavailable.");
    metadata.put("visibleSectionsAtEnd", client.levelRenderer.visibleSections().size());
    metadata.put("terrainMeshingPendingAtEnd", !client.levelRenderer.hasRenderedAllSections());
    metadata.put("shadowCullingAtEnd", shadowCullingStats());
    metadata.put("windowFocusedAtEnd", client.getWindow().isFocused());
    if (FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      metadata.put("materialAtlasAtEnd", materialStats());
    if (sparkEnabled) {
      spark
          .finish(output.resolve(label + ".sparkprofile"))
          .whenComplete(
              (result, failure) ->
                  client.execute(
                      () -> {
                        try {
                          if (failure != null)
                            throw new IllegalStateException("Spark export failed", failure);
                          metadata.put("sparkProfile", result);
                          client.gui.hud.getChat().clearMessages(false);
                          writeResultsAndStop(client);
                        } catch (Throwable exportFailure) {
                          fail(client, exportFailure);
                        }
                      }));
    } else writeResultsAndStop(client);
  }

  private void writeResultsAndStop(Minecraft client) throws IOException {
    Files.createDirectories(output);
    String file = label.replaceAll("[^a-zA-Z0-9._-]", "_");
    try (var writer = Files.newBufferedWriter(output.resolve(file + ".csv"))) {
      writer.write(
          "frame,interval_ns,run_tick_wall_ns"
              + (actions.enabled()
                  ? ",phase,x,y,z,yaw,pitch,destroy_stage,meshing_pending,loaded_chunks"
                  : "")
              + "\n");
      for (int i = 0; i < count; i++) {
        writer.write(i + "," + intervals[i] + "," + runTicks[i]);
        if (actions.enabled()) {
          var sample = actionSamples[i];
          writer.write(
              ","
                  + sample.phase()
                  + ","
                  + sample.x()
                  + ","
                  + sample.y()
                  + ","
                  + sample.z()
                  + ","
                  + sample.yaw()
                  + ","
                  + sample.pitch()
                  + ","
                  + sample.destroyStage()
                  + ","
                  + sample.meshingPending()
                  + ","
                  + sample.loadedChunks());
        }
        writer.write("\n");
      }
    }
    Path summary = output.resolve(file + ".json");
    Files.writeString(
        summary, new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
    FrameStatistics result = FrameStatistics.calculate(intervals, count);
    if (validationOnly)
      System.out.println(
          "[Shader benchmark] VALIDATION COMPLETE "
              + label
              + ": action evidence only; midpoint GPU readback makes timings non-comparable. "
              + summary);
    else
      System.out.printf(
          Locale.ROOT,
          "[Shader benchmark] COMPLETE %s: %.2f FPS, p50 %.3fms, p95 %.3fms, p99 %.3fms (%d"
              + " frames). %s%n",
          label,
          result.meanFps(),
          result.p50Ms(),
          result.p95Ms(),
          result.p99Ms(),
          count,
          summary);
    // Capture only after measurement, so the readback never enters the frame-time sample.
    if (actions.enabled()
        || Boolean.parseBoolean(
            property("screenshot", System.getProperty(PREFIX + "production", "false")))) {
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
    if (sparkEnabled) spark.cancel();
    finished = true;
    long elapsed = measuredStarted == 0 ? 0 : Math.max(0, System.nanoTime() - measuredStarted);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("status", "failed");
    report.put("validationPassed", false);
    report.put("label", label);
    report.put("failedUtc", Instant.now().toString());
    report.put("elapsedMeasurementSeconds", elapsed / 1e9);
    report.put("recordedActionSamples", actions.enabled() ? count : 0);
    report.put("error", failure.toString());
    report.put(
        "notes",
        "Incomplete action-run diagnostics. This is not a completion report or comparative FPS"
            + " result.");
    Map<String, Object> configuration = new LinkedHashMap<>(metadata);
    for (String key :
        new String[] {
          "frameIntervals",
          "runTickWallTime",
          "diagnosticFrameIntervals",
          "diagnosticRunTickWallTime",
          "actionPhases",
          "processCpuTimeMs",
          "renderThreadCpuTimeMs"
        }) configuration.remove(key);
    report.put("configuration", configuration);
    if (actions.enabled()) {
      try {
        // Snapshot before clearing held inputs; otherwise the failure report conceals the action
        // state that caused the failure. Periodic snapshots retain the prior mining state too.
        report.put("actions", actions.diagnostics(client, elapsed));
      } catch (Throwable diagnosticFailure) {
        failure.addSuppressed(diagnosticFailure);
        report.put("actionDiagnosticError", diagnosticFailure.toString());
      } finally {
        actions.release(client);
      }
    }
    var trace = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(trace));
    report.put("stackTrace", trace.toString());
    failure.printStackTrace();
    try {
      Files.createDirectories(output);
      String file = label.replaceAll("[^a-zA-Z0-9._-]", "_");
      Files.writeString(output.resolve(file + ".failed.txt"), trace.toString());
      Files.writeString(
          output.resolve(file + ".failed.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
      if (actions.enabled()) {
        try (var writer = Files.newBufferedWriter(output.resolve(file + ".failed-actions.csv"))) {
          writer.write(
              "sample,elapsed_seconds,phase,x,y,z,yaw,pitch,destroy_stage,meshing_pending,loaded_chunks\n");
          long sampleElapsed = 0;
          for (int i = 0; i < count; i++) {
            sampleElapsed += Math.max(0, intervals[i]);
            var sample = actionSamples[i];
            if (sample == null) continue;
            writer.write(
                i
                    + ","
                    + sampleElapsed / 1e9
                    + ","
                    + sample.phase()
                    + ","
                    + sample.x()
                    + ","
                    + sample.y()
                    + ","
                    + sample.z()
                    + ","
                    + sample.yaw()
                    + ","
                    + sample.pitch()
                    + ","
                    + sample.destroyStage()
                    + ","
                    + sample.meshingPending()
                    + ","
                    + sample.loadedChunks()
                    + "\n");
          }
        }
      }
      System.err.println(
          "[Shader benchmark] FAILURE DIAGNOSTICS " + output.resolve(file + ".failed.json"));
    } catch (IOException writeFailure) {
      failure.addSuppressed(writeFailure);
      writeFailure.printStackTrace();
    }
    client.stop();
  }

  private CompletableFuture<Void> captureImage(Minecraft client, String suffix) throws IOException {
    Files.createDirectories(output);
    CompletableFuture<Void> result = new CompletableFuture<>();
    Screenshot.takeScreenshot(
        client.gameRenderer.mainRenderTarget(),
        image -> {
          try (image) {
            image.writeToFile(
                output.resolve(label.replaceAll("[^a-zA-Z0-9._-]", "_") + suffix + ".png"));
            result.complete(null);
          } catch (Throwable failure) {
            result.completeExceptionally(failure);
          }
        });
    return result;
  }

  private static Map<String, Object> materialStats() {
    var runtime = dev.kausik.shaders.runtime.ShaderRuntime.get();
    if (runtime == null) return Map.of("available", false);
    var stats = runtime.materialStats();
    return Map.of(
        "generations",
        stats.generations(),
        "mappedSprites",
        stats.mapped(),
        "skippedMasks",
        stats.skipped(),
        "resourceOverrides",
        stats.overrides(),
        "bytes",
        stats.bytes());
  }

  private static Map<String, Object> shadowCullingStats() {
    return shadowStatistics("cullingStats");
  }

  private static Map<String, Object> shadowStatistics(String method) {
    if (!FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      return Map.of("available", false);
    try {
      Object stats =
          Class.forName("dev.kausik.shaders.runtime.ShadowRenderer").getMethod(method).invoke(null);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("available", true);
      for (var component : stats.getClass().getRecordComponents())
        values.put(component.getName(), component.getAccessor().invoke(stats));
      return values;
    } catch (ReflectiveOperationException unavailable) {
      return Map.of("available", false);
    }
  }

  /** Snapshot actual rendered inputs: configured clock/pose alone cannot prove identical views. */
  private Map<String, Object> shaderViewInputs() {
    if (!FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      return Map.of("available", false);
    var runtime = dev.kausik.shaders.runtime.ShaderRuntime.get();
    if (runtime == null || runtime.uniforms() == null) return Map.of("available", false);
    var uniforms = runtime.uniforms();
    if (!((Object) uniforms
        instanceof dev.kausik.shaders.benchmark.mixin.FrameUniformsMatricesAccessor access))
      return Map.of("available", false, "reason", "missing-uniform-accessor");
    var matrices = new LinkedHashMap<String, Object>();
    for (String name :
        List.of(
            "gbufferModelView",
            "gbufferProjection",
            "shadowModelView",
            "shadowProjection",
            "sl_ShadowModelViewProjection")) {
      float[] values = access.benchmark$matrices().get(name);
      if (values != null) matrices.put(name, values.clone());
    }
    var scalars = new LinkedHashMap<String, Object>();
    for (String name :
        List.of("worldTime", "sunAngle", "shadowAngle", "viewWidth", "viewHeight", "framemod8")) {
      Double value = access.benchmark$scalars().get(name);
      if (value != null) scalars.put(name, value);
    }
    var sun = uniforms.sunDirectionWorld();
    var light = uniforms.shadowDirectionWorld();
    var position =
        Minecraft.getInstance()
            .gameRenderer
            .gameRenderState()
            .levelRenderState
            .cameraRenderState
            .pos;
    var primaryCamera = Minecraft.getInstance().gameRenderer.mainCamera().position();
    return Map.of(
        "available",
        true,
        "matrices",
        matrices,
        "scalars",
        scalars,
        "sunDirectionWorld",
        new float[] {sun.x(), sun.y(), sun.z()},
        "shadowDirectionWorld",
        new float[] {light.x(), light.y(), light.z()},
        "renderCameraPosition",
        new double[] {position.x, position.y, position.z},
        "primaryCameraPosition",
        new double[] {primaryCamera.x, primaryCamera.y, primaryCamera.z},
        "notes",
        "Most recently rendered pack inputs at interval boundaries; jitter ordinal is expected to"
            + " differ across runs.");
  }

  private void recordRuntimeDiagnosticsStart(String scope) {
    metadata.put("shaderViewInputsAtStart", shaderViewInputs());
    metadata.put("mipmapGenerationsAtStart", shaderRuntimeSnapshot("mipmapGenerations"));
    metadata.put("sparseProjectionPassesAtStart", shaderRuntimeSnapshot("sparseProjectionPasses"));
    metadata.put(
        "sparseProjectionFallbacksAtStart", shaderRuntimeSnapshot("sparseProjectionFallbacks"));
    metadata.put("terrainVertexLayout", terrainVertexLayout());
    metadata.put("pipelineMathAtStart", pipelineMathSnapshot());
    metadata.put(
        "nativeAttachmentDiscardAtStart", nativeStatistics("attachmentDiscardStatistics", 2));
    metadata.put("nativeRenderStagesAtStart", nativeRenderStages());
    metadata.put("sceneProviderAtStart", sceneProviderSnapshot());
    metadata.put("shadowSelectionOverlapAtStart", shadowStatistics("overlapStats"));
    nativeExecutionStarted = nativeExecutionSnapshot();
    nativeCpuIcbStarted = nativeStatistics("cpuIndirectStatistics", 4);
    nativeIndirectSnapshotStarted = nativeStatistics("indirectSnapshotStatistics", 7);
    nativeFenceWaitStarted = nativeStatistics("fenceWaitStatistics", 3);
    metadata.put("nativeFenceWaitAtStart", fenceWaitStatistics(nativeFenceWaitStarted));
    metadata.put(
        "nativeIndirectSnapshotAtStart", indirectSnapshotStatistics(nativeIndirectSnapshotStarted));
    metadata.put("nativeCpuIcbAtStart", cpuIcbStatistics(nativeCpuIcbStarted));
    metadata.put("nativeExecutionAtStart", executionStatistics(nativeExecutionStarted));
    metadata.put(
        "nativeExecutionNotes",
        "Completion-handler GPU spans add no GPU markers or barriers. Totals describe completed"
            + " command buffers, not per-frame critical paths; boundaries may include in-flight"
            + " work. Acquisition times include API overhead. No GPU waits are added to sample.");
    metadata.put("earlyAlphaDemoteAtStart", earlyAlphaSnapshot());
    metadata.put("earlyAlphaDemoteCounterScope", scope);
    metadata.put(
        "earlyAlphaDemoteCountNotes",
        "Counts compiled geometry pipeline cache entries and entries whose translated label"
            + " contains /early_alpha_demote. These are compilation counts, not executed draws.");
    var snapshot = nativeIcbSnapshot();
    nativeIcbStarted = snapshot.count();
    metadata.put("nativeIcbCounterScope", scope);
    metadata.put("nativeIcbCounterAvailable", nativeIcbStarted >= 0);
    metadata.put("nativeIcbEnabled", snapshot.enabled());
    nativeHazardModeStarted = snapshot.hazardMode();
    metadata.put("nativeHazardModeAtStart", nativeHazardModeStarted);
    metadata.put("nativeIcbExecutionsAtStart", nativeIcbStarted >= 0 ? nativeIcbStarted : null);
    nativeIcbSplitsStarted = snapshot.splits();
    metadata.put("nativeIcbSplitsAtStart", splitStatistics(nativeIcbSplitsStarted));
    nativeIcbReuseStarted = snapshot.reuse();
    metadata.put("nativeIcbReuseEnabled", snapshot.reuse() != null && snapshot.reuse()[0] == 1);
    metadata.put("nativeIcbReuseAtStart", reuseStatistics(nativeIcbReuseStarted));
    metadata.put(
        "nativeIcbSplitEstimateNotes",
        "Physical encoder splits before versus after any draw command. Bytes assume a full"
            + " uncompressed store and load of each color/depth attachment at its physical format;"
            + " this is a footprint estimate, not measured memory traffic. No scissor, coverage,"
            + " compression, or tile-cache reductions are assumed.");
    if (snapshot.error() != null) metadata.put("nativeIcbCounterError", snapshot.error());
  }

  private void recordRuntimeDiagnosticsEnd() {
    metadata.put("shaderViewInputsAtEnd", shaderViewInputs());
    metadata.put("mipmapGenerationsAtEnd", shaderRuntimeSnapshot("mipmapGenerations"));
    metadata.put("sceneProviderAtEnd", sceneProviderSnapshot());
    metadata.put("shadowSelectionOverlapAtEnd", shadowStatistics("overlapStats"));
    metadata.put("sparseProjectionPassesAtEnd", shaderRuntimeSnapshot("sparseProjectionPasses"));
    metadata.put(
        "sparseProjectionFallbacksAtEnd", shaderRuntimeSnapshot("sparseProjectionFallbacks"));
    metadata.put("nativeRenderStagesAtEnd", nativeRenderStages());
    metadata.put("pipelineMathAtEnd", pipelineMathSnapshot());
    metadata.put(
        "nativeAttachmentDiscardAtEnd", nativeStatistics("attachmentDiscardStatistics", 2));
    long[] fenceWaits = nativeStatistics("fenceWaitStatistics", 3);
    metadata.put("nativeFenceWaitAtEnd", fenceWaitStatistics(fenceWaits));
    if (nativeFenceWaitStarted != null && fenceWaits != null) {
      long[] delta = fenceWaits.clone();
      for (int i = 0; i < delta.length; i++) delta[i] -= nativeFenceWaitStarted[i];
      metadata.put("nativeFenceWaitDelta", fenceWaitStatistics(delta));
    }
    long[] snapshots = nativeStatistics("indirectSnapshotStatistics", 7);
    metadata.put("nativeIndirectSnapshotAtEnd", indirectSnapshotStatistics(snapshots));
    if (nativeIndirectSnapshotStarted != null && snapshots != null) {
      long[] delta = snapshots.clone();
      for (int i = 0; i < delta.length; i++) delta[i] -= nativeIndirectSnapshotStarted[i];
      metadata.put("nativeIndirectSnapshotDelta", indirectSnapshotStatistics(delta));
    }
    long[] execution = nativeExecutionSnapshot();
    long[] cpuIcb = nativeStatistics("cpuIndirectStatistics", 4);
    metadata.put("nativeCpuIcbAtEnd", cpuIcbStatistics(cpuIcb));
    if (nativeCpuIcbStarted != null && cpuIcb != null) {
      long[] delta = cpuIcb.clone();
      for (int i = 1; i < delta.length; i++) if (i != 4) delta[i] -= nativeCpuIcbStarted[i];
      metadata.put("nativeCpuIcbDelta", cpuIcbStatistics(delta));
    }
    metadata.put("nativeExecutionAtEnd", executionStatistics(execution));
    if (nativeExecutionStarted != null && execution != null) {
      long[] delta = new long[execution.length];
      for (int i = 0; i < delta.length; i++) delta[i] = execution[i] - nativeExecutionStarted[i];
      metadata.put("nativeExecutionDelta", executionStatistics(delta));
    }
    metadata.put("earlyAlphaDemoteAtEnd", earlyAlphaSnapshot());
    var snapshot = nativeIcbSnapshot();
    boolean valid = nativeIcbStarted >= 0 && snapshot.count() >= nativeIcbStarted;
    metadata.put("nativeIcbCounterAvailable", valid);
    metadata.put("nativeIcbExecutionsAtEnd", snapshot.count() >= 0 ? snapshot.count() : null);
    metadata.put("nativeIcbExecutionDelta", valid ? snapshot.count() - nativeIcbStarted : null);
    metadata.put("nativeIcbSplitsAtEnd", splitStatistics(snapshot.splits()));
    metadata.put("nativeIcbReuseAtEnd", reuseStatistics(snapshot.reuse()));
    if (nativeIcbReuseStarted != null && snapshot.reuse() != null) {
      long[] delta = new long[6];
      for (int i = 1; i < delta.length; i++)
        delta[i] = snapshot.reuse()[i] - nativeIcbReuseStarted[i];
      metadata.put("nativeIcbReuseDelta", reuseStatistics(delta));
    }
    if (nativeIcbSplitsStarted != null && snapshot.splits() != null) {
      long[] delta = new long[4];
      for (int i = 0; i < delta.length; i++)
        delta[i] = snapshot.splits()[i] - nativeIcbSplitsStarted[i];
      metadata.put("nativeIcbSplitDelta", splitStatistics(delta));
    }
    metadata.put("nativeHazardModeAtEnd", snapshot.hazardMode());
    boolean modeChanged =
        nativeHazardModeStarted != null && !nativeHazardModeStarted.equals(snapshot.hazardMode());
    metadata.put("nativeHazardModeChanged", modeChanged);
    if (snapshot.error() != null) metadata.put("nativeIcbCounterError", snapshot.error());
    if (modeChanged && !validationOnly)
      throw new IllegalStateException(
          "Native synchronization mode changed during measurement from "
              + nativeHazardModeStarted
              + " to "
              + snapshot.hazardMode()
              + "; timestamp profiling may have activated, so this run is not comparable");
  }

  /** Optional loader access stays in the benchmark JAR, including for renderer-only runs. */
  private static Map<String, Object> renderValidationEnvironment() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "fabricDevelopmentEnvironment", FabricLoader.getInstance().isDevelopmentEnvironment());
    result.put("javaAssertionsEnabled", ShaderBenchmark.class.desiredAssertionStatus());
    try {
      boolean strict =
          Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice")
              .getField("STRICT_VALIDATION")
              .getBoolean(null);
      boolean ide =
          Class.forName("net.minecraft.SharedConstants")
              .getField("IS_RUNNING_IN_IDE")
              .getBoolean(null);
      result.put("available", true);
      result.put("frontendStrictValidation", strict);
      result.put("minecraftRunningInIde", ide);
      result.put(
          "cpuMeasurementClassification",
          strict
              ? "Includes development-only per-draw RenderPearl validation"
              : "RenderPearl development-only per-draw validation is inactive");
      result.put(
          "notes",
          "STRICT_VALIDATION captures IS_RUNNING_IN_IDE at FrontendGpuDevice class initialization."
              + " Fabric development mode alone does not prove this check is active; no validation"
              + " mode is changed by this probe.");
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      result.put("available", false);
      result.put("error", unavailable.toString());
    }
    return result;
  }

  /** Optional loader access stays in the benchmark JAR, including for renderer-only runs. */
  private static Map<String, Object> earlyAlphaSnapshot() {
    if (!FabricLoader.getInstance().isModLoaded("minecraft_shader_loader"))
      return Map.of("available", false, "reason", "Shader loader is not installed");
    try {
      Class<?> runtimeClass = Class.forName("dev.kausik.shaders.runtime.ShaderRuntime");
      Object runtime = runtimeClass.getMethod("get").invoke(null);
      if (runtime == null)
        return Map.of("available", false, "reason", "No selected shader pack runtime");
      var geometryField = runtimeClass.getDeclaredField("geometry");
      geometryField.setAccessible(true);
      Map<?, ?> geometry = (Map<?, ?>) geometryField.get(runtime);
      int demoted = 0;
      for (Object pipeline : geometry.values()) {
        Object translated = pipeline.getClass().getMethod("translated").invoke(pipeline);
        String translatedLabel =
            (String) translated.getClass().getMethod("label").invoke(translated);
        if (translatedLabel.contains("/early_alpha_demote")) demoted++;
      }
      return Map.of(
          "available",
          true,
          "compiledGeometryPipelines",
          geometry.size(),
          "earlyAlphaDemotedPipelines",
          demoted);
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false, "error", unavailable.toString());
    }
  }

  private static Map<String, Object> pipelineMathSnapshot() {
    try {
      Object stats =
          Class.forName("dev.kausik.metal.MetalPipelineMath").getMethod("snapshot").invoke(null);
      Map<String, Object> result = new LinkedHashMap<>();
      for (var component : stats.getClass().getRecordComponents())
        result.put(component.getName(), component.getAccessor().invoke(stats));
      return result;
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false);
    }
  }

  private static Map<String, Object> terrainVertexLayout() {
    try {
      Object layout =
          Class.forName("dev.kausik.shaders.geometry.TerrainShaderGeometry")
              .getMethod("layout")
              .invoke(null);
      return Map.of(
          "layout",
          layout.toString(),
          "strideBytes",
          layout.getClass().getMethod("stride").invoke(layout));
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false);
    }
  }

  private static Map<String, Object> shaderRuntimeSnapshot(String method) {
    try {
      Object runtime =
          Class.forName("dev.kausik.shaders.runtime.ShaderRuntime").getMethod("get").invoke(null);
      if (runtime == null) return Map.of("available", false);
      Object stats = runtime.getClass().getMethod(method).invoke(runtime);
      if (stats instanceof Number) return Map.of("value", stats);
      Map<String, Object> result = new LinkedHashMap<>();
      for (var component : stats.getClass().getRecordComponents())
        result.put(component.getName(), component.getAccessor().invoke(stats));
      return result;
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false);
    }
  }

  private static Map<String, Object> sceneProviderSnapshot() {
    try {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put(
          "provider",
          Class.forName("dev.kausik.scene.SceneViews").getMethod("providerId").invoke(null));
      if (FabricLoader.getInstance().isModLoaded("minecraft_scene_optimizer")) {
        Object stats =
            Class.forName("dev.kausik.sceneoptimizer.SceneOptimizerMod")
                .getMethod("stats")
                .invoke(null);
        for (var component : stats.getClass().getRecordComponents())
          result.put(component.getName(), component.getAccessor().invoke(stats));
        Object drawStats =
            Class.forName("dev.kausik.sceneoptimizer.SceneOptimizerMod")
                .getMethod("drawMetadataStats")
                .invoke(null);
        Map<String, Object> drawMetadata = new LinkedHashMap<>();
        for (var component : drawStats.getClass().getRecordComponents())
          drawMetadata.put(component.getName(), component.getAccessor().invoke(drawStats));
        result.put("drawMetadata", drawMetadata);
        Object workStats =
            Class.forName("dev.kausik.sceneoptimizer.SceneOptimizerMod")
                .getMethod("drawWorkStats")
                .invoke(null);
        Map<String, Object> drawWork = new LinkedHashMap<>();
        for (var component : workStats.getClass().getRecordComponents())
          drawWork.put(component.getName(), component.getAccessor().invoke(workStats));
        result.put("drawWork", drawWork);
      }
      return result;
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false, "error", unavailable.toString());
    }
  }

  /** Diagnostic access stays in the probe JAR. */
  private static long[] nativeExecutionSnapshot() {
    return nativeStatistics("executionStatistics", 12);
  }

  private static Map<String, Long> cpuIcbStatistics(long[] values) {
    return values == null
        ? null
        : Map.of(
            "enabled",
            values[0],
            "populatedBatches",
            values[1],
            "commandSlots",
            values[2],
            "populateNanos",
            values[3]);
  }

  private static Map<String, Long> indirectSnapshotStatistics(long[] values) {
    if (values == null) return null;
    String[] names = {
      "coveredLookups",
      "untrackedMisses",
      "poisonedMisses",
      "activeMappingMisses",
      "uncoveredRangeMisses",
      "evictedRanges",
      "publishedRanges"
    };
    Map<String, Long> result = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) result.put(names[i], values[i]);
    return result;
  }

  private static Map<String, Long> fenceWaitStatistics(long[] values) {
    return values == null
        ? null
        : Map.of("calls", values[0], "blockedCalls", values[1], "elapsedNanos", values[2]);
  }

  private static long[] nativeStatistics(String method, int length) {
    try {
      var backendField =
          Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice")
              .getDeclaredField("backend");
      backendField.setAccessible(true);
      Object backend = backendField.get(RenderSystem.getDevice());
      long handle = (long) backend.getClass().getMethod("handle").invoke(backend);
      long[] values =
          (long[])
              Class.forName("dev.kausik.metal.MetalNative")
                  .getMethod(method, long.class)
                  .invoke(null, handle);
      return values != null && values.length == length ? values : null;
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return null;
    }
  }

  private static Object nativeRenderStages() {
    try {
      var backendField =
          Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice")
              .getDeclaredField("backend");
      backendField.setAccessible(true);
      Object backend = backendField.get(RenderSystem.getDevice());
      long handle = (long) backend.getClass().getMethod("handle").invoke(backend);
      String json =
          (String)
              Class.forName("dev.kausik.metal.MetalNative")
                  .getMethod("renderStageStatistics", long.class)
                  .invoke(null, handle);
      return com.google.gson.JsonParser.parseString(json);
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return Map.of("available", false);
    }
  }

  private static Map<String, Long> executionStatistics(long[] values) {
    if (values == null) return null;
    String[] names = {
      "submittedBuffers", "completedBuffers", "timedBuffers", "gpuSpanNanosSum",
      "uploadRingAcquires", "uploadRingAcquireNanos", "drawableAcquires", "drawableAcquireNanos",
      "failedBuffers", "unavailableGpuTimes", "uploadRingBlockedAcquires", "drawableTimeouts"
    };
    Map<String, Long> result = new LinkedHashMap<>();
    for (int i = 0; i < names.length; i++) result.put(names[i], values[i]);
    return result;
  }

  private static NativeIcbSnapshot nativeIcbSnapshot() {
    try {
      Object frontend = RenderSystem.getDevice();
      var backendField =
          Class.forName("com.mojang.renderpearl.frontend.FrontendGpuDevice")
              .getDeclaredField("backend");
      backendField.setAccessible(true);
      Object backend = backendField.get(frontend);
      if (!backend.getClass().getName().equals("dev.kausik.metal.MetalDevice"))
        return new NativeIcbSnapshot(
            -1, false, null, null, null, "Active backend is not MetalDevice");
      long handle = (long) backend.getClass().getMethod("handle").invoke(backend);
      Class<?> nativeApi = Class.forName("dev.kausik.metal.MetalNative");
      long count =
          (long) nativeApi.getMethod("indirectCommandExecutions", long.class).invoke(null, handle);
      boolean enabled =
          (boolean) nativeApi.getMethod("indirectCommandsEnabled", long.class).invoke(null, handle);
      String hazardMode =
          (String)
              nativeApi.getMethod("hazardSynchronizationMode", long.class).invoke(null, handle);
      long[] splits =
          (long[]) nativeApi.getMethod("indirectSplitStatistics", long.class).invoke(null, handle);
      if (splits == null || splits.length != 4)
        throw new IllegalStateException("Invalid native ICB split statistics");
      long[] reuse =
          (long[]) nativeApi.getMethod("indirectReuseStatistics", long.class).invoke(null, handle);
      if (reuse == null || reuse.length != 6)
        throw new IllegalStateException("Invalid native ICB reuse statistics");
      return new NativeIcbSnapshot(count, enabled, hazardMode, splits, reuse, null);
    } catch (ReflectiveOperationException | RuntimeException unavailable) {
      return new NativeIcbSnapshot(-1, false, null, null, null, unavailable.toString());
    }
  }

  private static Map<String, Long> splitStatistics(long[] values) {
    return values == null
        ? null
        : Map.of(
            "firstDrawSplits",
            values[0],
            "afterDrawSplits",
            values[1],
            "firstDrawEstimatedAttachmentBytes",
            values[2],
            "afterDrawEstimatedAttachmentBytes",
            values[3],
            "totalEstimatedAttachmentBytes",
            values[2] + values[3]);
  }

  private record NativeIcbSnapshot(
      long count, boolean enabled, String hazardMode, long[] splits, long[] reuse, String error) {}

  private static Map<String, Long> reuseStatistics(long[] values) {
    return values == null
        ? null
        : Map.of(
            "eligibleDraws",
            values[1],
            "cacheHits",
            values[2],
            "cacheMisses",
            values[3],
            "ineligibleDraws",
            values[4],
            "cpuSnapshotBytesPublished",
            values[5]);
  }

  private Map<String, Object> actionPhaseStatistics() {
    Map<String, ArrayList<Long>> phases = new LinkedHashMap<>();
    Map<String, Integer> meshing = new LinkedHashMap<>(), cracking = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      var sample = actionSamples[i];
      phases.computeIfAbsent(sample.phase(), ignored -> new ArrayList<>()).add(intervals[i]);
      if (sample.meshingPending()) meshing.merge(sample.phase(), 1, Integer::sum);
      if (sample.destroyStage() >= 0) cracking.merge(sample.phase(), 1, Integer::sum);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    phases.forEach(
        (phase, values) -> {
          long[] times = values.stream().mapToLong(Long::longValue).toArray();
          result.put(
              phase,
              Map.of(
                  diagnosticTiming ? "diagnosticIntervals" : "frameIntervals",
                  FrameStatistics.calculate(times, times.length),
                  "meshingPendingFrames",
                  meshing.getOrDefault(phase, 0),
                  "crackFrames",
                  cracking.getOrDefault(phase, 0),
                  "framesOver33ms",
                  values.stream().filter(t -> t > 33_333_333).count(),
                  "framesOver50ms",
                  values.stream().filter(t -> t > 50_000_000).count(),
                  "framesOver100ms",
                  values.stream().filter(t -> t > 100_000_000).count()));
        });
    return result;
  }

  private static long gc(boolean millis) {
    long total = 0;
    for (var collector : ManagementFactory.getGarbageCollectorMXBeans())
      total += Math.max(0, millis ? collector.getCollectionTime() : collector.getCollectionCount());
    return total;
  }

  private static long processCpuTime() {
    var bean = ManagementFactory.getOperatingSystemMXBean();
    return bean instanceof com.sun.management.OperatingSystemMXBean extended
        ? extended.getProcessCpuTime()
        : -1;
  }

  private static long renderCpuTime() {
    var bean = ManagementFactory.getThreadMXBean();
    return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
        ? bean.getCurrentThreadCpuTime()
        : -1;
  }

  private static double cpuMillis(long start, long end) {
    return start < 0 || end < start ? -1 : (end - start) / 1e6;
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

  private static double decimal(String name, double fallback) {
    double result = Double.parseDouble(property(name, Double.toString(fallback)));
    if (!Double.isFinite(result)) throw new IllegalArgumentException("Invalid benchmark " + name);
    return result;
  }
}
