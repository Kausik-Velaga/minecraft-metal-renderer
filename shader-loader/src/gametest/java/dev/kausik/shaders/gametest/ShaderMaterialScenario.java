package dev.kausik.shaders.gametest;

import dev.kausik.shaders.runtime.ShaderRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/**
 * Disposable material gallery, including live texture reload and steady-state generation checks.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ShaderMaterialScenario {
  public static void run(ClientGameTestContext context) {
    context.getInput().resizeWindow(1280, 720);
    try (var world =
        context
            .worldBuilder()
            .adjustSettings(
                settings -> {
                  settings.setName("Material atlas disposable validation");
                  settings.setAllowCommands(true);
                })
            .create()) {
      var server = world.getServer();
      server.runCommand("gamerule minecraft:send_command_feedback false");
      server.runCommand("gamerule minecraft:advance_time false");
      server.runCommand("gamemode creative @a");
      server.runCommand("time set 4000");
      server.runCommand("weather clear");
      server.runCommand("fill -12 -60 -6 12 -60 12 minecraft:smooth_stone");
      String[] blocks = {"iron_ore", "gold_ore", "diamond_ore", "redstone_lamp", "redstone_lamp"};
      for (int i = 0; i < blocks.length; i++) {
        int x = -8 + i * 4;
        server.runCommand(
            "fill "
                + x
                + " -59 0 "
                + (x + 2)
                + (i < 3 ? " -57 2 minecraft:" : " -57 0 minecraft:")
                + blocks[i]);
      }
      server.runCommand("fill 8 -59 -1 10 -57 -1 minecraft:redstone_block");
      server.runCommand("fill -8 -59 6 -6 -59 8 minecraft:iron_block");
      server.runCommand("fill -4 -59 6 -2 -59 8 minecraft:gold_block");
      server.runCommand("fill 0 -59 6 2 -59 8 minecraft:copper_block");
      server.runCommand("fill 4 -59 6 6 -59 8 minecraft:blue_ice");
      server.runCommand("fill 8 -60 6 10 -60 8 minecraft:lava");
      server.runCommand("fill 1 -53 17 3 -53 19 minecraft:smooth_stone");
      server.runCommand("tp @a 2 -52 18 180 24");
      server.runCommand("give @a minecraft:gold_ore");
      world.getConnection().waitForChunksRender();
      context.waitTicks(80);
      context.runOnClient(
          client -> {
            client.gui.hud.getChat().clearMessages(false);
            client.gui.toastManager().clear();
            if (Math.abs(client.player.getY() + 52) > .1)
              throw new AssertionError("Gallery camera moved");
          });
      var before = context.computeOnClient(client -> ShaderRuntime.get().materialStats());
      if (before.mapped() != 50 || before.skipped() != 0 || before.bytes() <= 0)
        throw new AssertionError("Unexpected material atlas: " + before);
      context.takeScreenshot(TestScreenshotOptions.of("materials-01-day"));
      var reload = context.computeOnClient(client -> client.reloadResourcePacks());
      context.waitFor(client -> reload.isDone(), 1200);
      reload.join();
      context.waitTicks(60);
      var after = context.computeOnClient(client -> ShaderRuntime.get().materialStats());
      if (after.generations() != before.generations() + 1
          || after.mapped() != before.mapped()
          || after.bytes() != before.bytes())
        throw new AssertionError("Atlas reload mismatch: " + after);
      context.takeScreenshot(TestScreenshotOptions.of("materials-02-reloaded"));
      server.runCommand("time set midnight");
      context.waitTicks(80);
      if (context.computeOnClient(client -> ShaderRuntime.get().materialStats().generations())
          != after.generations())
        throw new AssertionError("Material textures regenerated during ordinary frames");
      context.takeScreenshot(TestScreenshotOptions.of("materials-03-night"));
      resourceOverride(context);
      System.out.println("PASS: material gallery, live reload, stable per-frame reuse: " + after);
    }
  }

  private static void resourceOverride(ClientGameTestContext context) {
    java.nio.file.Path folder = null;
    var selected =
        context.computeOnClient(
            client -> java.util.List.copyOf(client.getResourcePackRepository().getSelectedIds()));
    try {
      var root =
          net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("resourcepacks");
      java.nio.file.Files.createDirectories(root);
      folder = java.nio.file.Files.createTempDirectory(root, "material-validation-");
      java.nio.file.Files.writeString(
          folder.resolve("pack.mcmeta"),
          """
          {"pack":{"description":"Disposable material override test","min_format":[97,1],"max_format":[97,1]}}
          """);
      var changed =
          new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      var material =
          new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
          changed.setRGB(x, y, 0xff888888);
          material.setRGB(
              x, y, 0x00ff0040); // M=1, E=0, R=64/255, S=0, deliberately zero PNG alpha.
        }
      var albedoPath = folder.resolve("assets/minecraft/textures/block/iron_ore.png");
      var materialPath = folder.resolve("assets/minecraft/shader_materials/block/gold_ore.png");
      java.nio.file.Files.createDirectories(albedoPath.getParent());
      java.nio.file.Files.createDirectories(materialPath.getParent());
      javax.imageio.ImageIO.write(changed, "PNG", albedoPath.toFile());
      javax.imageio.ImageIO.write(material, "PNG", materialPath.toFile());
      String id = "file/" + folder.getFileName();
      context.runOnClient(
          client -> {
            var repository = client.getResourcePackRepository();
            repository.reload();
            if (!repository.addPack(id))
              throw new AssertionError("Could not select test resource pack");
          });
      reload(context);
      var overridden = context.computeOnClient(client -> ShaderRuntime.get().materialStats());
      if (overridden.overrides() != 1 || overridden.skipped() != 1 || overridden.mapped() != 49)
        throw new AssertionError("Material override or source guard failed: " + overridden);
      context.takeScreenshot(TestScreenshotOptions.of("materials-04-resource-override"));
      System.out.println(
          "PASS: changed albedo disables stale mask; explicit material override loaded: "
              + overridden);
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    } finally {
      context.runOnClient(client -> client.getResourcePackRepository().setSelected(selected));
      reload(context);
      if (folder != null)
        try (var paths = java.nio.file.Files.walk(folder)) {
          for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            java.nio.file.Files.delete(path);
        } catch (java.io.IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
    }
    var restored = context.computeOnClient(client -> ShaderRuntime.get().materialStats());
    if (restored.overrides() != 0 || restored.skipped() != 0 || restored.mapped() != 50)
      throw new AssertionError(
          "Material atlas did not restore after override removal: " + restored);
  }

  private static void reload(ClientGameTestContext context) {
    var reload = context.computeOnClient(client -> client.reloadResourcePacks());
    context.waitFor(client -> reload.isDone(), 1200);
    reload.join();
    context.waitTicks(40);
  }
}
