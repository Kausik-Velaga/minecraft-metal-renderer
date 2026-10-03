package dev.kausik.shaders.gametest;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.Level;

/** Natural terrain and dimension changes with the supplied pack's unmodified default options. */
@SuppressWarnings("UnstableApiUsage")
public final class ShaderNaturalScenario {
  private ShaderNaturalScenario() {}

  public static void run(ClientGameTestContext context) {
    context.runOnClient(
        client -> {
          if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()))
            throw new AssertionError("Natural shader validation requires Metal");
          if (ShaderRuntime.get() == null) throw new AssertionError("No shader pack selected");
          client.options.renderDistance().set(8);
          client.options.simulationDistance().set(5);
          client.options.cloudStatus().set(CloudStatus.FANCY);
        });
    context.getInput().resizeWindow(1280, 720);
    try (TestSingleplayerContext world =
        context
            .worldBuilder()
            .setUseConsistentSettings(false)
            .adjustSettings(
                settings -> {
                  settings.setName("Shader natural terrain validation");
                  settings.setSeed("1");
                  settings.setGenerateStructures(false);
                  settings.setAllowCommands(true);
                })
            .create()) {
      var server = world.getServer();
      server.runCommand("gamerule minecraft:send_command_feedback false");
      server.runCommand("gamerule minecraft:advance_time false");
      server.runCommand("gamemode spectator @a");
      server.runCommand("time set 4000");
      server.runCommand("weather clear");
      server.runCommand("execute as @a at @s run tp @s ~ ~24 ~ -25 24");
      settle(context, world);
      screenshot(context, "natural-01-day", "world0", true);

      context.getInput().holdKeyFor(options -> options.keyUp, 60);
      settle(context, world);
      screenshot(context, "natural-02-flight", "world0", true);
      String returnCommand =
          context.computeOnClient(
              client ->
                  String.format(
                      Locale.ROOT,
                      "execute in minecraft:overworld run tp @a %.4f %.4f %.4f %.3f %.3f",
                      client.player.getX(),
                      client.player.getY(),
                      client.player.getZ(),
                      client.player.getYRot(),
                      client.player.getXRot()));

      server.runCommand("time set midnight");
      context.waitTicks(100);
      screenshot(context, "natural-03-night", "world0", true);

      server.runCommand("execute in minecraft:the_nether run tp @a 0 80 0 -30 15");
      context.waitFor(
          client -> client.level != null && client.level.dimension().equals(Level.NETHER), 1200);
      settle(context, world);
      int[] cavern =
          server.computeOnServer(
              instance -> {
                var level = instance.getLevel(Level.NETHER);
                for (int y = 90; y >= 40; y -= 5)
                  for (int x = -24; x <= 24; x += 8)
                    for (int z = -24; z <= 24; z += 8) {
                      boolean open = true;
                      for (int offset = -6; offset <= 6; offset += 3) {
                        if (!level.getBlockState(new BlockPos(x + offset, y, z)).isAir()
                            || !level.getBlockState(new BlockPos(x, y, z + offset)).isAir()
                            || !level.getBlockState(new BlockPos(x, y + offset / 3, z)).isAir()) {
                          open = false;
                          break;
                        }
                      }
                      if (open) return new int[] {x, y, z};
                    }
                throw new AssertionError("Seed 1 Nether fixture needs an open cavern viewpoint");
              });
      server.runCommand(
          "execute in minecraft:the_nether run tp @a "
              + cavern[0]
              + " "
              + cavern[1]
              + " "
              + cavern[2]
              + " -30 12");
      settle(context, world);
      screenshot(context, "natural-04-nether", "world-1", false);

      server.runCommand("execute in minecraft:the_end run tp @a 20 90 30 145 24");
      context.waitFor(
          client -> client.level != null && client.level.dimension().equals(Level.END), 1200);
      settle(context, world);
      screenshot(context, "natural-05-end", "world1", false);

      server.runCommand(returnCommand);
      server.runCommand("time set 4000");
      context.waitFor(
          client -> client.level != null && client.level.dimension().equals(Level.OVERWORLD), 1200);
      settle(context, world);
      screenshot(context, "natural-06-overworld-return", "world0", true);
      context.runOnClient(
          client ->
              System.out.println(
                  "Shader natural diagnostic (validation and test scheduling enabled): "
                      + client.getFps()
                      + " reported FPS; "
                      + client.getFrameTimeNs()
                      + " ns last CPU frame"));
    }
    context.waitTicks(10);
  }

  private static void settle(ClientGameTestContext context, TestSingleplayerContext world) {
    world.getConnection().waitForClientboundPackets();
    // Normal worlds stream Minecraft's rounded tracking area, rather than the complete square
    // assumed by the test API's default download predicate.
    context.waitFor(
        client -> {
          if (client.level == null || client.player == null) return false;
          var center = client.player.chunkPosition();
          int distance = client.options.getEffectiveRenderDistance();
          for (int x = center.x() - distance - 1; x <= center.x() + distance + 1; x++) {
            for (int z = center.z() - distance - 1; z <= center.z() + distance + 1; z++) {
              if (ChunkTrackingView.isInViewDistance(center.x(), center.z(), distance, x, z)
                  && !client.level.getChunkSource().hasChunk(x, z)) return false;
            }
          }
          return true;
        },
        1200);
    world.getConnection().waitForChunksRender(false, 1200);
    context.waitTicks(100);
  }

  private static void screenshot(
      ClientGameTestContext context, String name, String directory, boolean overworld) {
    context.runOnClient(
        client -> {
          client.gui.hud.getChat().clearMessages(false);
          client.gui.toastManager().clear();
          var runtime = ShaderRuntime.get();
          if (runtime == null
              || runtime.targets() == null
              || !runtime.programs().directory.equals(directory))
            throw new AssertionError("Shader dimension graph was not rebuilt for " + directory);
          int sections = client.levelRenderer.visibleSections().size();
          if (sections < (overworld ? 10 : 1))
            throw new AssertionError("No visible natural terrain: " + sections);
          if (!client.levelRenderer.isChunkRenderingUsingMultiDrawIndirect())
            throw new AssertionError("Expected actual indexed indirect terrain rendering");
          if (runtime.targets().width != 1280 || runtime.targets().height != 720)
            throw new AssertionError("Natural validation must render at 1280x720");
          if (client.options.renderDistance().get() != 8
              || client.options.cloudStatus().get() != CloudStatus.FANCY)
            throw new AssertionError("Natural scene quality settings changed during validation");
          assertDepth(runtime, name);
          System.out.println(
              "Shader natural evidence "
                  + name
                  + ": "
                  + sections
                  + " visible sections, indexed indirect terrain, "
                  + directory);
        });
    context.waitTick();
    Path file = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    System.out.println("Shader natural screenshot: " + file.toAbsolutePath());
    if (overworld || directory.equals("world1"))
      context.runOnClient(
          client ->
              ShaderImageDiagnostics.capture(
                  ShaderRuntime.get(),
                  FabricLoader.getInstance().getGameDir().resolve("shader-diagnostics"),
                  name));
  }

  private static void assertDepth(ShaderRuntime runtime, String label) {
    var target = runtime.targets();
    int pixels = target.width * target.height;
    runtime.suspend();
    try (var readback =
        runtime
            .device()
            .createBuffer(
                () -> "Natural scene depth validation",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                pixels * 4)) {
      var encoder = runtime.device().createCommandEncoder();
      encoder.copyTextureToBuffer(target.depth(0).texture, readback, 0, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(10_000_000_000L))
          throw new AssertionError("Natural depth GPU timeout");
      }
      int geometry = 0;
      try (var mapped = readback.map(true, false)) {
        var data = mapped.data().order(ByteOrder.nativeOrder());
        for (int pixel = 0; pixel < pixels; pixel++) {
          float depth = data.getFloat(pixel * 4);
          if (!Float.isFinite(depth) || depth < 0 || depth > 1)
            throw new AssertionError("Invalid GPU depth in " + label + ": " + depth);
          if (depth < 1 - 1e-6f) geometry++;
        }
      }
      if (geometry < pixels / 100)
        throw new AssertionError("Natural scene rendered less than 1% geometry coverage: " + label);
    }
  }
}
