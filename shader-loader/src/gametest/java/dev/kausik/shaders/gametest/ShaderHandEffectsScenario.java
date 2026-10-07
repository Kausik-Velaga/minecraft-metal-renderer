package dev.kausik.shaders.gametest;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Exercises actual first-person enchanted-item and fire submissions in the disposable world. */
@SuppressWarnings("UnstableApiUsage")
final class ShaderHandEffectsScenario {
  private record ClientState(CameraType camera, double glintStrength) {}

  private record PlayerState(
      ItemStack held,
      GameType gameMode,
      int fireTicks,
      float health,
      float absorption,
      int food,
      float saturation,
      boolean invulnerable,
      boolean flying) {
    static PlayerState snapshot(ServerPlayer player) {
      return new PlayerState(
          player.getMainHandItem().copy(),
          player.gameMode(),
          player.getRemainingFireTicks(),
          player.getHealth(),
          player.getAbsorptionAmount(),
          player.getFoodData().getFoodLevel(),
          player.getFoodData().getSaturationLevel(),
          player.isPermanentlyInvulnerable(),
          player.getAbilities().flying);
    }

    void restore(ServerPlayer player) {
      player.setGameMode(gameMode);
      player.setItemSlot(EquipmentSlot.MAINHAND, held.copy());
      player.setRemainingFireTicks(fireTicks);
      player.setHealth(health);
      player.setAbsorptionAmount(absorption);
      player.getFoodData().setFoodLevel(food);
      player.getFoodData().setSaturation(saturation);
      player.setPermanentlyInvulnerable(invulnerable);
      player.getAbilities().flying = flying;
      player.onUpdateAbilities();
      player.inventoryMenu.broadcastChanges();
    }
  }

  private ShaderHandEffectsScenario() {}

  static void run(ClientGameTestContext context, TestSingleplayerContext world) {
    var server = world.getServer();
    PlayerState previous =
        server.computeOnServer(
            instance -> PlayerState.snapshot(instance.getPlayerList().getPlayers().getFirst()));
    ClientState clientState =
        context.computeOnClient(
            client ->
                new ClientState(
                    client.options.getCameraType(), client.options.glintStrength().get()));
    try {
      if (!previous.held().is(Items.DIAMOND_SWORD))
        throw new AssertionError("Hand fixture requires the existing held diamond sword");
      context.runOnClient(
          client -> {
            client.options.setCameraType(CameraType.FIRST_PERSON);
            client.options.glintStrength().set(1.0);
          });
      server.runCommand("enchant @a minecraft:unbreaking 1");
      world.getConnection().waitForClientboundPackets();
      context.waitFor(client -> client.player.getMainHandItem().hasFoil(), 100);
      context.waitTicks(20);
      capture(context, "13c-bsl-enchanted-held-sword");
      context.runOnClient(client -> assertDraws(false));

      server.runOnServer(
          instance -> {
            var player = instance.getPlayerList().getPlayers().getFirst();
            // Creative players clamp fire to one tick. Survival plus the entity-level immunity
            // keeps the real fire animation active without damage or changes to world blocks.
            player.setGameMode(GameType.SURVIVAL);
            player.setPermanentlyInvulnerable(true);
            player.igniteForTicks(200);
          });
      world.getConnection().waitForClientboundPackets();
      context.waitFor(client -> client.player.isOnFire(), 100);
      context.waitTicks(10);
      capture(context, "13d-bsl-first-person-fire-enchanted-sword");
      context.runOnClient(
          client -> {
            if (!client.player.isOnFire())
              throw new AssertionError("Fire fixture expired before its screenshot");
            assertDraws(true);
          });
    } finally {
      server.runOnServer(
          instance -> previous.restore(instance.getPlayerList().getPlayers().getFirst()));
      world.getConnection().waitForClientboundPackets();
      context.runOnClient(
          client -> {
            client.options.setCameraType(clientState.camera());
            client.options.glintStrength().set(clientState.glintStrength());
          });
      context.waitTicks(2);
    }
  }

  private static void assertDraws(boolean requireFire) {
    try {
      var field = ShaderRuntime.class.getDeclaredField("geometry");
      field.setAccessible(true);
      Map<?, ?> variants = (Map<?, ?>) field.get(ShaderRuntime.get());
      boolean enchantedHand = false;
      boolean fire = false;
      for (Object variant : variants.keySet()) {
        var shadow = variant.getClass().getDeclaredMethod("shadow");
        var hand = variant.getClass().getDeclaredMethod("hand");
        var original = variant.getClass().getDeclaredMethod("original");
        shadow.setAccessible(true);
        hand.setAccessible(true);
        original.setAccessible(true);
        if ((boolean) shadow.invoke(variant)) continue;
        RenderPipeline pipeline = (RenderPipeline) original.invoke(variant);
        String path = pipeline.getLocation().getPath();
        // 26.3 item shaders fuse glint into the base material; their identifier is shared with
        // unenchanted item_cutout. The GLINT flag distinguishes the real enchanted submission.
        enchantedHand |=
            (boolean) hand.invoke(variant) && pipeline.getShaderDefines().flags().contains("GLINT");
        fire |= path.endsWith("/fire_screen_effect") || path.equals("fire_screen_effect");
      }
      if (!enchantedHand || (requireFire && !fire))
        throw new AssertionError(
            "Missing first-person fixture draw: enchanted hand="
                + enchantedHand
                + ", fire="
                + fire);
      System.out.println(
          "First-person fixture compiled an actual GLINT item variant"
              + (requireFire ? " and fire_screen_effect" : "")
              + "; this is draw-path coverage, not an assertion of fused-glint visual parity");
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect first-person draw coverage", failure);
    }
  }

  private static void capture(ClientGameTestContext context, String name) {
    context.runOnClient(
        client -> {
          client.gui.hud.getChat().clearMessages(false);
          client.gui.toastManager().clear();
        });
    context.waitTick();
    var screenshot = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    System.out.println("First-person visual evidence: " + screenshot.toAbsolutePath());
  }
}
