package dev.kausik.shaders.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Disposable, deterministic scene for visual review of an externally supplied shader pack. */
@SuppressWarnings("UnstableApiUsage")
public final class ShaderGameplayTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()))
            throw new AssertionError("Shader test must execute on Metal");
          if (ShaderRuntime.get() == null) throw new AssertionError("No shader pack selected");
          client.options.renderDistance().set(5);
          client.options.simulationDistance().set(5);
          // The loader must select its compatible draw order without mutating the saved preference.
          client.options.improvedTransparency().set(true);
        });
    if ("materials".equals(System.getProperty("minecraftShaders.testScenario"))) {
      ShaderMaterialScenario.run(context);
      complete("Material masks, atlas reload and steady-state reuse");
      return;
    }
    if ("natural".equals(System.getProperty("minecraftShaders.testScenario"))) {
      ShaderNaturalScenario.run(context);
      complete("Natural terrain, movement, night, Nether and End transitions");
      return;
    }
    context.getInput().resizeWindow(960, 600);
    try (var world =
        context
            .worldBuilder()
            .adjustSettings(
                settings -> {
                  settings.setName("Shader pack disposable validation");
                  settings.setAllowCommands(true);
                })
            .create()) {
      var server = world.getServer();
      server.runCommand("gamerule minecraft:send_command_feedback false");
      server.runCommand("gamerule minecraft:advance_time false");
      server.runCommand("gamemode creative @a");
      server.runCommand("time set 4000");
      server.runCommand("weather clear");
      server.runCommand("fill -10 -60 -10 10 -60 10 minecraft:smooth_stone");
      server.runCommand("fill -9 -63 -7 -2 -60 4 minecraft:water");
      server.runCommand("fill 2 -59 -4 6 -56 -4 minecraft:cyan_stained_glass");
      server.runCommand("fill 3 -59 0 3 -54 0 minecraft:oak_log");
      server.runCommand("fill 1 -55 -2 5 -53 2 minecraft:oak_leaves[persistent=true]");
      server.runCommand("fill -1 -59 -8 1 -55 -8 minecraft:white_concrete");
      server.runCommand("setblock -1 -59 2 minecraft:sea_lantern");
      server.runCommand("summon minecraft:pig 1 -59 5 {NoAI:1b}");
      server.runCommand("give @a minecraft:diamond_sword");
      server.runCommand("fill 9 -60 12 13 -55 16 minecraft:smooth_stone");
      server.runCommand("tp @a 11 -54 14 145 27");
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitTicks(80);
      screenshot(context, "01-bsl-day-water-glass-shadow");
      server.runCommand("particle minecraft:flame 0 -57 3 2 1 2 0.01 60 force");
      context.waitTicks(2);
      screenshot(context, "02-bsl-particles");
      context.getInput().holdKeyFor(options -> options.keyUp, 15);
      context.waitTicks(20);
      screenshot(context, "03-bsl-movement-history");
      server.runCommand("time set midnight");
      context.waitTicks(40);
      screenshot(context, "04-bsl-night");
      server.runCommand("time set 4000");
      server.runCommand("weather rain");
      context.waitTicks(60);
      screenshot(context, "05-bsl-rain");
      context.getInput().resizeWindow(1280, 720);
      context.waitTicks(40);
      screenshot(context, "06-bsl-resized");
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone(), 1200);
      reload.join();
      context.waitTicks(40);
      screenshot(context, "07-bsl-resource-reload");
      server.runCommand("weather clear");
      server.runCommand("gamemode spectator @a");
      server.runCommand("tp @a -5 -62.5 0 170 5");
      context.waitTicks(40);
      screenshot(context, "08-bsl-underwater");
      server.runCommand("tp @a 11 -54 14 145 27");
      server.runCommand("gamemode creative @a");
      server.runCommand("setblock 0 -59 0 minecraft:chest");
      server.runCommand("setblock -1 -59 -1 minecraft:end_portal");
      server.runCommand("summon minecraft:end_crystal 0 -58 -5 {ShowBottom:0b}");
      context.waitTicks(40);
      screenshot(context, "09-bsl-block-entities-portal");
      server.runCommand("damage @e[type=minecraft:pig,limit=1] 1 minecraft:generic");
      world.getConnection().waitForClientboundPackets();
      context.waitTick();
      screenshot(context, "10-bsl-hurt-entity");
      var fixture = server.computeOnServer(ShaderGameplayTest::addFeatureFixture);
      try {
        world.getConnection().waitForClientboundPackets();
        world.getConnection().waitForChunksRender();
        // Beacon beam scanning and level activation are spread over server ticks.
        context.waitTicks(100);
        screenshot(context, "11-bsl-beacon-boat-leash");
        context.runOnClient(client -> assertFeaturePipelines());
      } finally {
        server.runOnServer(instance -> removeFeatureFixture(instance, fixture));
        world.getConnection().waitForClientboundPackets();
        world.getConnection().waitForChunksRender();
      }
      breakLeafFixture(context, world);
      ShaderWalkingScenario.run(context, world);
      ShaderNameTagScenario.run(context, world);
      ShaderHandEffectsScenario.run(context, world);
      context.runOnClient(
          client -> {
            if (!client.options.improvedTransparency().get())
              throw new AssertionError("Saved transparency option changed");
          });
    }
    context.waitTicks(5);
    screenshot(context, "14-bsl-menu");
    complete(
        "Day/night, water, glass, shadows, entities, hurt overlays, particles, movement, rain,"
            + " resize, reload, underwater, portals, beacon beams, boat water masks, leashes and"
            + " survival leaf breaking, sunrise walking with view bob, visible/occluded name"
            + " labels, uniform-font text displays/backgrounds, enchanted hand submission, fire"
            + " overlay and menu");
  }

  private static void breakLeafFixture(
      ClientGameTestContext context, TestSingleplayerContext world) {
    var server = world.getServer();
    BlockPos target = new BlockPos(11, -53, 12);
    BlockPos retained = new BlockPos(12, -53, 12);
    Map<BlockPos, BlockState> previous =
        server.computeOnServer(
            instance -> {
              var level = instance.getLevel(Level.OVERWORLD);
              return Map.of(
                  target, level.getBlockState(target), retained, level.getBlockState(retained));
            });
    try {
      server.runCommand("setblock 11 -53 12 minecraft:oak_leaves[persistent=true]");
      server.runCommand("setblock 12 -53 12 minecraft:oak_leaves[persistent=true]");
      server.runCommand("clear @a");
      server.runCommand("gamemode survival @a");
      // Preserve a real mining crack for several rendered frames without synthesizing a decal.
      server.runCommand("effect give @a minecraft:mining_fatigue 30 1 true");
      server.runCommand("tp @a 11.5 -54.0 15.5 180 0");
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.getInput().lookAt(target);
      context.waitTicks(20);
      context.runOnClient(
          client -> {
            if (!(client.hitResult instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(target))
              throw new AssertionError(
                  "Leaf-breaking fixture did not target its leaf: " + client.hitResult);
            if (!client.player.getMainHandItem().isEmpty()
                || client.gameMode.getPlayerMode().isCreative())
              throw new AssertionError("Leaf-breaking fixture must use an empty survival hand");
          });
      context.getInput().holdKey(options -> options.keyAttack);
      context.waitFor(
          client -> client.gameMode.isDestroying() && client.gameMode.getDestroyStage() >= 3, 200);
      context.runOnClient(
          client -> {
            if (!client.level.getBlockState(target).is(Blocks.OAK_LEAVES))
              throw new AssertionError(
                  "Leaf disappeared before its crack overlay could be captured");
          });
      screenshot(context, "12-bsl-leaf-breaking-cracks");
      context.runOnClient(client -> assertLeafBreakingPipelines());
      context.waitFor(client -> client.level.getBlockState(target).isAir(), 200);
      context.getInput().releaseKey(options -> options.keyAttack);
      server.waitFor(
          instance -> instance.getLevel(Level.OVERWORLD).getBlockState(target).isAir(), 200);
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitTicks(10);
      context.runOnClient(
          client -> {
            if (!client.level.getBlockState(target).isAir()
                || !client.level.getBlockState(retained).is(Blocks.OAK_LEAVES))
              throw new AssertionError(
                  "Leaf mining did not remove exactly the targeted fixture block");
            assertLeafBreakingPipelines();
          });
      screenshot(context, "13-bsl-leaf-removed-caster-retained");
    } finally {
      context.getInput().releaseKey(options -> options.keyAttack);
      server.runCommand("effect clear @a minecraft:mining_fatigue");
      server.runCommand("gamemode creative @a");
      server.runCommand("give @a minecraft:diamond_sword");
      server.runCommand("tp @a 11.5 -54.0 14.5 145 27");
      server.runOnServer(
          instance ->
              previous.forEach(
                  (position, state) ->
                      instance.getLevel(Level.OVERWORLD).setBlock(position, state, 3)));
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
    }
  }

  private static void assertLeafBreakingPipelines() {
    try {
      var field = ShaderRuntime.class.getDeclaredField("geometry");
      field.setAccessible(true);
      Map<?, ?> variants = (Map<?, ?>) field.get(ShaderRuntime.get());
      boolean mainCrumbling = false, normalShadowCaster = false;
      for (Object variant : variants.keySet()) {
        var shadowAccessor = variant.getClass().getDeclaredMethod("shadow");
        var originalAccessor = variant.getClass().getDeclaredMethod("original");
        shadowAccessor.setAccessible(true);
        originalAccessor.setAccessible(true);
        boolean shadow = (boolean) shadowAccessor.invoke(variant);
        RenderPipeline pipeline = (RenderPipeline) originalAccessor.invoke(variant);
        String path = pipeline.getLocation().getPath();
        if (path.endsWith("/crumbling")) {
          if (shadow)
            throw new AssertionError("Block damage decal was compiled as a shadow caster");
          mainCrumbling = true;
        }
        if (shadow && path.contains("terrain")) normalShadowCaster = true;
      }
      if (!mainCrumbling || !normalShadowCaster)
        throw new AssertionError(
            "Missing real leaf-break draw coverage: main crumbling="
                + mainCrumbling
                + ", ordinary terrain shadow="
                + normalShadowCaster);
      System.out.println(
          "Leaf-breaking fixture: real main CRUMBLING draw, no shadow CRUMBLING, normal terrain"
              + " shadows retained");
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect leaf-breaking draw coverage", failure);
    }
  }

  private record FeatureFixture(Map<BlockPos, BlockState> blocks, List<UUID> entities) {}

  private static FeatureFixture addFeatureFixture(MinecraftServer server) {
    ServerLevel level = server.getLevel(Level.OVERWORLD);
    Map<BlockPos, BlockState> previous = new LinkedHashMap<>();
    for (int x = 4; x <= 6; x++)
      for (int z = 4; z <= 6; z++)
        replace(level, previous, new BlockPos(x, -60, z), Blocks.IRON_BLOCK.defaultBlockState());
    replace(level, previous, new BlockPos(5, -59, 5), Blocks.BEACON.defaultBlockState());
    BlockPos fence = new BlockPos(0, -59, 5);
    replace(level, previous, fence, Blocks.OAK_FENCE.defaultBlockState());
    List<UUID> entities = new ArrayList<>();
    var boat = EntityTypes.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);
    if (boat == null) throw new AssertionError("Could not create fixture boat");
    boat.setPos(-5, -58.7, 0);
    boat.setYRot(35);
    if (!level.addFreshEntity(boat)) throw new AssertionError("Could not add fixture boat");
    entities.add(boat.getUUID());
    var knot = LeashFenceKnotEntity.getOrCreateKnot(level, fence);
    entities.add(knot.getUUID());
    var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
    if (pig == null) throw new AssertionError("Could not create leash fixture pig");
    pig.setPos(2.5, -59, 5.5);
    pig.setNoAi(true);
    pig.setPersistenceRequired();
    if (!level.addFreshEntity(pig)) throw new AssertionError("Could not add leash fixture pig");
    entities.add(pig.getUUID());
    pig.setLeashedTo(knot, true);
    if (!pig.isLeashed()) throw new AssertionError("Fixture pig did not acquire its leash");
    return new FeatureFixture(previous, entities);
  }

  private static void replace(
      ServerLevel level, Map<BlockPos, BlockState> previous, BlockPos position, BlockState state) {
    previous.put(position, level.getBlockState(position));
    level.setBlock(position, state, 3);
  }

  private static void removeFeatureFixture(MinecraftServer server, FeatureFixture fixture) {
    var level = server.getLevel(Level.OVERWORLD);
    for (UUID id : fixture.entities()) {
      var entity = level.getEntityInAnyDimension(id);
      if (entity instanceof Leashable leash) leash.removeLeash();
    }
    for (UUID id : fixture.entities()) {
      var entity = level.getEntityInAnyDimension(id);
      if (entity != null) entity.discard();
    }
    fixture.blocks().forEach((position, state) -> level.setBlock(position, state, 3));
  }

  private static void assertFeaturePipelines() {
    // Geometry variants are compiled lazily by actual scene draws. Inspecting the test's runtime
    // cache proves these draw categories ran without adding production counters or altering draws.
    try {
      var field = ShaderRuntime.class.getDeclaredField("geometry");
      field.setAccessible(true);
      Map<?, ?> variants = (Map<?, ?>) field.get(ShaderRuntime.get());
      Set<String> seen = new HashSet<>();
      for (Object variant : variants.keySet()) {
        var shadow = variant.getClass().getDeclaredMethod("shadow");
        shadow.setAccessible(true);
        if ((boolean) shadow.invoke(variant)) continue;
        var original = variant.getClass().getDeclaredMethod("original");
        original.setAccessible(true);
        RenderPipeline pipeline = (RenderPipeline) original.invoke(variant);
        String path = pipeline.getLocation().getPath();
        seen.add(path.substring(path.lastIndexOf('/') + 1));
      }
      Set<String> required =
          Set.of("beacon_beam_opaque", "beacon_beam_translucent", "leash", "water_mask");
      if (!seen.containsAll(required)) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(seen);
        throw new AssertionError(
            "Fixture renderer categories did not execute: " + missing + "; saw " + seen);
      }
      System.out.println("Shader feature fixture executed real geometry pipelines: " + required);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect shader fixture draw coverage", failure);
    }
  }

  private static void complete(String scenarios) {
    try {
      Files.writeString(
          FabricLoader.getInstance().getGameDir().resolve("shader-gametest-complete.txt"),
          "Shader client validation completed: "
              + scenarios
              + ". Visual inspection is separately required.\n");
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static void screenshot(ClientGameTestContext context, String name) {
    context.runOnClient(
        client -> {
          client.gui.hud.getChat().clearMessages(false);
          client.gui.toastManager().clear();
        });
    context.waitTick();
    Path file = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    System.out.println("Shader visual evidence: " + file.toAbsolutePath());
    if (name.startsWith("01-")
        || name.startsWith("04-")
        || name.startsWith("09-")
        || name.startsWith("10-")
        || name.startsWith("12-")
        || name.startsWith("13-")) {
      context.runOnClient(
          client ->
              ShaderImageDiagnostics.capture(
                  ShaderRuntime.get(),
                  FabricLoader.getInstance().getGameDir().resolve("shader-diagnostics"),
                  name));
    }
  }
}
