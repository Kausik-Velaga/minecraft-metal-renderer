package dev.kausik.metal.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.lwjgl.sdl.SDLVideo;

/**
 * Creates only a disposable test world, drives normal gameplay rendering, and saves review images.
 */
@SuppressWarnings("UnstableApiUsage")
public final class MetalGameplayTest implements FabricClientGameTest {
  private static final AtomicInteger WATER_MASK_PASSES = new AtomicInteger();

  /** Called by the test-only renderer probe after a water-mask pass finishes recording. */
  public static void recordWaterMaskPass() {
    WATER_MASK_PASSES.incrementAndGet();
  }

  @Override
  public void runTest(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) {
            throw new AssertionError("The client test must actually run through Metal");
          }
          client.options.renderDistance().set(5);
          client.options.simulationDistance().set(5);
          client.options.improvedTransparency().set(false);
        });
    context.getInput().resizeWindow(960, 600);
    if ("natural".equals(System.getProperty("metal.gametest.scenario"))) {
      runNatural(context);
      complete(
          "indirect natural terrain, transparency off/on, movement, resource reload, SDL"
              + " fullscreen");
      return;
    }
    context.waitTicks(40);
    screenshot(context, "01-title");
    try (TestSingleplayerContext world =
        context
            .worldBuilder()
            .adjustSettings(
                settings -> {
                  settings.setName("Metal renderer validation");
                  settings.setAllowCommands(true);
                })
            .create()) {
      world.getConnection().waitForChunksRender();
      var server = world.getServer();
      server.runCommand("gamerule minecraft:send_command_feedback false");
      server.runCommand("gamemode creative @a");
      server.runCommand("time set noon");
      server.runCommand("weather clear");
      server.runCommand("tp @a 0 -59 9 180 18");
      server.runCommand("fill -7 -60 -4 -2 -60 3 minecraft:water");
      server.runCommand("fill 2 -59 -2 5 -57 -2 minecraft:cyan_stained_glass");
      server.runCommand("fill 3 -59 1 4 -58 2 minecraft:oak_leaves[persistent=true]");
      server.runCommand("setblock -1 -59 -3 minecraft:sea_lantern");
      server.runCommand("summon minecraft:pig 0 -59 0 {NoAI:1b}");
      server.runCommand("summon minecraft:sheep -1 -59 2 {NoAI:1b,Color:14b}");
      server.runCommand("give @a minecraft:diamond_sword");
      server.runCommand("give @a minecraft:oak_log 32");
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitTicks(20);
      screenshot(context, "02-terrain-water-glass-entities");

      server.runCommand("particle minecraft:flame 0 -57 1 2 1 2 0.01 80 force");
      context.waitTicks(2);
      screenshot(context, "03-particles");

      context.getInput().pressKey(options -> options.keyInventory);
      context.waitTicks(5);
      screenshot(context, "04-inventory");
      context.getInput().pressKey(InputConstants.KEY_ESCAPE);
      context.waitTicks(5);
      context.getInput().resizeWindow(1280, 720);
      context.waitTicks(10);
      screenshot(context, "05-resized");

      runTransparency(context, world);

      server.runCommand("fill -5 -60 -5 5 -52 5 minecraft:stone");
      server.runCommand("fill -4 -59 -4 4 -53 4 minecraft:air");
      server.runCommand("setblock -3 -59 -3 minecraft:torch");
      server.runCommand("setblock 3 -59 -3 minecraft:chest");
      server.runCommand("tp @a 0 -59 3 180 5");
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitTicks(20);
      screenshot(context, "06-indoor-lighting");
    }
    context.waitTicks(5);
    screenshot(context, "07-return-to-menu");
    complete(
        "flat terrain, transparency off/on/off, boat water-mask passes, water, glass, entities,"
            + " particles, inventory, resize, indoor lighting");
  }

  private static void runTransparency(
      ClientGameTestContext context, TestSingleplayerContext world) {
    var server = world.getServer();
    // Deliberately overlapping transparent surfaces, with opaque geometry behind them.
    server.runCommand("fill 17 -60 -7 31 -60 8 minecraft:white_concrete");
    server.runCommand("fill 20 -59 -5 28 -55 -5 minecraft:gold_block");
    server.runCommand("fill 20 -59 -3 28 -55 -3 minecraft:blue_stained_glass");
    server.runCommand("fill 22 -59 0 26 -55 0 minecraft:red_stained_glass");
    server.runCommand("fill 20 -59 3 28 -55 3 minecraft:cyan_stained_glass");
    server.runCommand("fill 17 -59 -3 19 -56 3 minecraft:glass");
    server.runCommand("fill 18 -59 -2 18 -57 2 minecraft:water");
    server.runCommand("fill 20 -60 4 24 -60 8 minecraft:water");
    server.runCommand("summon minecraft:oak_boat 22 -59 6 {Rotation:[90.0f,0.0f]}");
    server.runCommand("summon minecraft:slime 24 -59 -1 {NoAI:1b,Size:1}");
    server.runCommand("tp @a 30 -58 12 155 12");
    world.getConnection().waitForClientboundPackets();
    context.waitTicks(60);
    setTransparency(context, false);
    screenshot(context, "13-transparency-off");
    context.runOnClient(
        client -> {
          boolean floatingBoat = false;
          for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof AbstractBoat boat && !boat.isUnderWater()) {
              floatingBoat = true;
              break;
            }
          }
          if (!floatingBoat) throw new AssertionError("The OIT scene needs a surfaced boat");
        });
    int waterMaskPassesBefore = WATER_MASK_PASSES.get();
    setTransparency(context, true);
    context.waitFor(client -> WATER_MASK_PASSES.get() > waterMaskPassesBefore, 400);
    System.out.println(
        "Metal gameplay OIT boat water-mask passes recorded: "
            + (WATER_MASK_PASSES.get() - waterMaskPassesBefore));
    screenshot(context, "14-transparency-on-boat-water-mask");
    server.runCommand("particle minecraft:flame 24 -56 1 3 1 3 0.01 120 force");
    world.getConnection().waitForClientboundPackets();
    context.waitTicks(2);
    screenshot(context, "15-transparency-on-particles");
    setTransparency(context, false);
    screenshot(context, "16-transparency-off-restored");
  }

  private static void setTransparency(ClientGameTestContext context, boolean enabled) {
    context.runOnClient(client -> client.options.improvedTransparency().set(enabled));
    context.waitFor(client -> client.gameRenderer.useImprovedTransparency() == enabled, 400);
    // Changing this option rebuilds section geometry. Let the new mesh uploads retire before
    // capture.
    context.waitTicks(60);
    context.runOnClient(
        client -> {
          if (client.options.improvedTransparency().get() != enabled
              || client.gameRenderer.useImprovedTransparency() != enabled) {
            throw new AssertionError("Improved Transparency did not switch to " + enabled);
          }
          System.out.println("Metal gameplay Improved Transparency active: " + enabled);
        });
  }

  private static void runNatural(ClientGameTestContext context) {
    context.runOnClient(client -> client.options.renderDistance().set(8));
    try (TestSingleplayerContext natural =
        context
            .worldBuilder()
            .setUseConsistentSettings(false)
            .adjustSettings(
                settings -> {
                  settings.setName("Metal natural terrain validation");
                  settings.setSeed("1");
                  settings.setGenerateStructures(false);
                  settings.setAllowCommands(true);
                })
            .create()) {
      context.waitTicks(80);
      var server = natural.getServer();
      server.runCommand("gamerule minecraft:send_command_feedback false");
      server.runCommand("gamemode spectator @a");
      server.runCommand("time set noon");
      server.runCommand("weather clear");
      server.runCommand("execute as @a at @s run tp @s ~ ~20 ~ -25 25");
      natural.getConnection().waitForClientboundPackets();
      context.waitTicks(80);
      context.runOnClient(
          client -> {
            int sections = client.levelRenderer.visibleSections().size();
            if (sections < 10)
              throw new AssertionError(
                  "Expected visible natural terrain sections, got " + sections);
            System.out.println("Metal natural terrain visible sections: " + sections);
            if (!client.levelRenderer.isChunkRenderingUsingMultiDrawIndirect()) {
              throw new AssertionError("Expected the 26.3 indirect terrain rendering path");
            }
            System.out.println("Metal natural terrain uses indexed indirect draws: true");
          });
      screenshot(context, "08-natural-terrain");
      setTransparency(context, true);
      screenshot(context, "17-natural-terrain-transparency-on");
      context.getInput().holdKeyFor(options -> options.keyUp, 40);
      context.waitTicks(80);
      screenshot(context, "09-natural-terrain-after-flight");

      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone(), 1200);
      reload.join();
      context.waitTicks(40);
      screenshot(context, "10-resource-reload");

      context.runOnClient(client -> client.getWindow().setFullscreen(true));
      context.waitFor(
          client ->
              (SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                      & SDLVideo.SDL_WINDOW_FULLSCREEN)
                  != 0);
      context.waitTicks(30);
      screenshot(context, "11-fullscreen");
      context.runOnClient(client -> client.getWindow().setFullscreen(false));
      context.waitFor(
          client ->
              (SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                      & SDLVideo.SDL_WINDOW_FULLSCREEN)
                  == 0);
      context.getInput().resizeWindow(1280, 720);
      context.waitTicks(90);
      screenshot(context, "12-windowed-after-fullscreen");
      setTransparency(context, false);
      screenshot(context, "18-natural-terrain-transparency-off-restored");
      context.runOnClient(
          client ->
              System.out.println(
                  "Metal gameplay diagnostic (test scheduling, validation enabled): "
                      + client.getFps()
                      + " reported FPS, "
                      + client.getFrameTimeNs()
                      + " ns last CPU frame"));
    }
  }

  private static void complete(String scenarios) {
    try {
      Files.writeString(
          FabricLoader.getInstance().getGameDir().resolve("metal-gametest-complete.txt"),
          "Metal gameplay validation completed: " + scenarios + ".\n");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
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
    System.out.println("Metal gameplay evidence: " + file.toAbsolutePath());
  }
}
