package dev.kausik.shaders.benchmark;

import dev.kausik.shaders.benchmark.mixin.BenchmarkTextureManagerAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;

/** Disposable image-validation fixture; its synthetic shader time is never a performance clock. */
public final class VisualComparison {
  private static final int SETTLING_FRAMES = 128, CAPTURES = 8;
  private static final long EPOCH = 1_000_000_000L;
  private static VisualComparison active;
  private final boolean enabled = Boolean.getBoolean("minecraftShaders.benchmark.visualCompare");
  private final List<CompletableFuture<Void>> captures = new ArrayList<>();
  private final List<Map<String, Object>> captureMetadata = new ArrayList<>();
  private final List<TextureAtlas> atlases = new ArrayList<>();
  private boolean started, armed, resetPending;
  private volatile boolean serverFrozen;
  private volatile Throwable freezeFailure;
  private volatile int removedMobs;
  private int ordinal, completedOrdinal = -1, nextCapture = SETTLING_FRAMES;
  private long startedNanos;

  public boolean enabled() {
    return enabled;
  }

  public boolean started() {
    return started;
  }

  public void validate(boolean validationOnly, boolean actions, boolean selectedPack) {
    if (enabled && (!validationOnly || actions || !selectedPack))
      throw new IllegalArgumentException(
          "visualCompare requires validationOnly=true, action=static, and a selected shader pack");
  }

  /** Called only after ShaderBenchmark verifies the disposable world's exact name. */
  public void prepareScene(MinecraftServer server) {
    if (!enabled) return;
    var rules = server.getGameRules();
    rules.set(GameRules.ADVANCE_WEATHER, false, server);
    rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
    rules.set(GameRules.SPAWN_MOBS, false, server);
    rules.set(GameRules.SPAWN_PATROLS, false, server);
    rules.set(GameRules.SPAWN_PHANTOMS, false, server);
    rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
  }

  public void start(Minecraft client, Map<String, Object> metadata) {
    if (!enabled || started) throw new IllegalStateException("Invalid visual-comparison start");
    started = true;
    startedNanos = System.nanoTime();
    metadata.put("visualCompare", true);
    metadata.put("performanceComparable", false);
    metadata.put("validationOnly", true);
    metadata.put(
        "notes",
        "Deterministic image fixture only; no FPS or frame-time measurement."
            + " Server ticks frozen, non-player mobs removed, particles cleared, atlases reset."
            + " Shader animation time advances once per rendered pack frame at a fixed 60 Hz.");
    var server = client.getSingleplayerServer();
    server.execute(
        () -> {
          try {
            // Remove mobs without spawning death particles, item drops, or death animations.
            for (var level : server.getAllLevels()) {
              List<Mob> mobs = new ArrayList<>();
              for (var entity : level.getAllEntities())
                if (entity instanceof Mob mob) mobs.add(mob);
              for (Mob mob : mobs) mob.discard();
              removedMobs += mobs.size();
            }
            server.tickRateManager().setFrozen(true);
            serverFrozen = true;
          } catch (Throwable failure) {
            freezeFailure = failure;
          }
        });
  }

  public boolean frameStart(
      Minecraft client, Path output, String label, Map<String, Object> metadata)
      throws IOException {
    if (freezeFailure != null)
      throw new IllegalStateException("Could not freeze visual scene", freezeFailure);
    if (System.nanoTime() - startedNanos > 180_000_000_000L)
      throw new IllegalStateException("Deterministic visual comparison timed out");
    if (!armed) {
      if (!serverFrozen || client.level.tickRateManager().runsNormally()) return false;
      for (var entity : client.level.entitiesForRendering())
        if (entity instanceof Mob) return false;
      if (!client.levelRenderer.hasRenderedAllSections()) return false;
      client.particleEngine.clearParticles();
      List<Map<String, Object>> atlasMetadata = new ArrayList<>();
      var textures =
          ((BenchmarkTextureManagerAccessor) client.getTextureManager()).benchmark$textures();
      for (var entry : textures.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
        if (!(entry.getValue() instanceof TextureAtlas atlas)) continue;
        int animations = ((AtlasControl) atlas).benchmark$resetAnimations();
        atlases.add(atlas);
        atlasMetadata.add(
            Map.of(
                "atlas",
                entry.getKey().toString(),
                "animatedSprites",
                animations,
                "frame",
                0,
                "subFrame",
                0));
      }
      if (atlases.isEmpty()) throw new IllegalStateException("No texture atlases were reset");
      metadata.put("atlasAnimationReset", atlasMetadata);
      metadata.put("removedNonPlayerMobs", removedMobs);
      metadata.put("worldTicksFrozen", true);
      metadata.put("particlesCleared", true);
      metadata.put("randomTickSpeed", 0);
      metadata.put("mobSpawning", false);
      metadata.put("settlingPackFrames", SETTLING_FRAMES);
      metadata.put("captureCount", CAPTURES);
      metadata.put("shaderClockStepSeconds", 1.0 / 60.0);
      metadata.put("renderStateGameTime", 4000);
      metadata.put("captureOrdinals", captureMetadata);
      active = this;
      armed = true;
      resetPending = true;
      System.out.println(
          "[Shader benchmark] Visual comparison armed; resetting temporal history and settling 128"
              + " pack frames");
      return false;
    }
    if (client.level.tickRateManager().runsNormally())
      throw new IllegalStateException("Visual comparison world unexpectedly resumed");
    for (TextureAtlas atlas : atlases) ((AtlasControl) atlas).benchmark$assertAnimationsReset();
    if (completedOrdinal >= nextCapture && nextCapture < SETTLING_FRAMES + CAPTURES) {
      if (completedOrdinal != nextCapture)
        throw new IllegalStateException("Missed visual-comparison frame " + nextCapture);
      Files.createDirectories(output);
      String file = label.replaceAll("[^a-zA-Z0-9._-]", "_") + "-frame-" + nextCapture + ".png";
      CompletableFuture<Void> saved = new CompletableFuture<>();
      Screenshot.takeScreenshot(
          client.gameRenderer.mainRenderTarget(),
          image -> {
            try (image) {
              image.writeToFile(output.resolve(file));
              saved.complete(null);
            } catch (Throwable failure) {
              saved.completeExceptionally(failure);
            }
          });
      captures.add(saved);
      captureMetadata.add(
          Map.of(
              "ordinal",
              nextCapture,
              "frameCounter",
              nextCapture,
              "shaderSeconds",
              nextCapture / 60.0,
              "file",
              file));
      nextCapture++;
    }
    if (captures.size() != CAPTURES || captures.stream().anyMatch(capture -> !capture.isDone()))
      return false;
    captures.forEach(CompletableFuture::join);
    metadata.put("status", "complete");
    metadata.put("validationPassed", true);
    metadata.put(
        "determinismAssessment",
        "Same initialized scene and temporal sequence. Compare repeated safe-mode images before"
            + " attributing any image difference to an optimization.");
    return true;
  }

  public static boolean consumeHistoryReset() {
    if (active == null || !active.resetPending) return false;
    active.resetPending = false;
    return true;
  }

  public static long shaderNanoTime() {
    return active == null ? System.nanoTime() : EPOCH + active.ordinal * 1_000_000_000L / 60;
  }

  public static long shaderEpoch() {
    return EPOCH;
  }

  public static void packFrameCompleted() {
    if (active == null) return;
    active.completedOrdinal = active.ordinal++;
  }

  public static void beforeWorldRender(Minecraft client) {
    if (active == null) return;
    // Vanilla cloud geometry uses elapsed world ticks independently of the celestial clock.
    var state = client.gameRenderer.gameRenderState().levelRenderState;
    state.gameTime = 4000;
    state.worldPartialTicks = 1;
  }

  public interface AtlasControl {
    int benchmark$resetAnimations();

    void benchmark$assertAnimationsReset();
  }

  public interface AnimationControl {
    void benchmark$reset();

    boolean benchmark$isReset();
  }
}
