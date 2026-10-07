package dev.kausik.shaders.gametest;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real entity-name and text-display submissions, including occlusion and uniform-font selection.
 */
@SuppressWarnings("UnstableApiUsage")
final class ShaderNameTagScenario {
  private static final FontDescription UNIFORM_FONT =
      new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));
  private static final String VISIBLE_NAME = "Visible default";
  private static final String OCCLUDED_NAME = "Occluded uniform";
  private static final String DISPLAY_TEXT = "Uniform display";

  private record Previous(boolean chatOnly, double backgroundOpacity, String teleport) {}

  private record Fixture(
      Map<BlockPos, BlockState> blocks,
      Map<BlockPos, BlockState> wall,
      List<UUID> entities,
      int visibleId,
      int occludedId,
      int displayId) {}

  private ShaderNameTagScenario() {}

  static void run(ClientGameTestContext context, TestSingleplayerContext world) {
    Previous previous =
        context.computeOnClient(
            client ->
                new Previous(
                    client.options.backgroundForChatOnly().get(),
                    client.options.textBackgroundOpacity().get(),
                    String.format(
                        Locale.ROOT,
                        "tp @a %.6f %.6f %.6f %.5f %.5f",
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.player.getXRot())));
    var server = world.getServer();
    Fixture fixture = server.computeOnServer(ShaderNameTagScenario::createFixture);
    try {
      context.runOnClient(
          client -> {
            client.options.backgroundForChatOnly().set(false);
            client.options.textBackgroundOpacity().set(0.6);
          });
      server.runCommand("tp @a 11.5 -54.0 15.5 180 -3");
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitFor(
          client ->
              client.level.getEntity(fixture.visibleId()) != null
                  && client.level.getEntity(fixture.occludedId()) != null
                  && client.level.getEntity(fixture.displayId()) != null,
          200);
      context.waitTicks(40);
      capture(context, "13a-bsl-nametags-occluded-uniform-background");
      context.runOnClient(
          client -> {
            var visible = client.level.getEntity(fixture.visibleId());
            var occluded = client.level.getEntity(fixture.occludedId());
            var display = client.level.getEntity(fixture.displayId());
            if (!visible.isCustomNameVisible()
                || !VISIBLE_NAME.equals(visible.getCustomName().getString())
                || !occluded.isCustomNameVisible()
                || !OCCLUDED_NAME.equals(occluded.getCustomName().getString()))
              throw new AssertionError("Fixture name labels did not reach the client");
            if (!UNIFORM_FONT.equals(occluded.getCustomName().getStyle().getFont()))
              throw new AssertionError("Occluded label lost its uniform-font selection");
            if (!(display instanceof Display.TextDisplay text)
                || !DISPLAY_TEXT.equals(text.getText().getString())
                || !UNIFORM_FONT.equals(text.getText().getStyle().getFont())
                || (text.getFlags() & Display.TextDisplay.FLAG_SEE_THROUGH) == 0
                || (text.getBackgroundColor() >>> 24) == 0)
              throw new AssertionError(
                  "See-through text display or its background was not synchronized");
            if (!client.level.getBlockState(new BlockPos(13, -52, 12)).is(Blocks.SMOOTH_STONE))
              throw new AssertionError("Occluded name label has no opaque wall in front of it");
            assertTextPipelines();
          });
      // The second image exposes the same named entity and text paths without the occluder.
      server.runOnServer(
          instance ->
              fixture
                  .wall()
                  .forEach(
                      (position, state) ->
                          instance.getLevel(Level.OVERWORLD).setBlock(position, state, 3)));
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
      context.waitTicks(20);
      capture(context, "13b-bsl-nametags-visible-uniform-background");
      context.runOnClient(client -> assertTextPipelines());
    } finally {
      server.runOnServer(
          instance -> {
            var level = instance.getLevel(Level.OVERWORLD);
            for (UUID id : fixture.entities()) {
              var entity = level.getEntityInAnyDimension(id);
              if (entity != null) entity.discard();
            }
            fixture.blocks().forEach((position, state) -> level.setBlock(position, state, 3));
          });
      context.runOnClient(
          client -> {
            client.options.backgroundForChatOnly().set(previous.chatOnly());
            client.options.textBackgroundOpacity().set(previous.backgroundOpacity());
          });
      server.runCommand(previous.teleport());
      world.getConnection().waitForClientboundPackets();
      world.getConnection().waitForChunksRender();
    }
  }

  private static Fixture createFixture(MinecraftServer server) {
    var level = server.getLevel(Level.OVERWORLD);
    Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    Map<BlockPos, BlockState> wall = new LinkedHashMap<>();
    for (int x = 8; x <= 14; x++)
      for (int z = 9; z <= 11; z++) {
        BlockPos position = new BlockPos(x, -55, z);
        blocks.put(position, level.getBlockState(position));
        level.setBlock(position, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
      }
    for (int x = 12; x <= 14; x++)
      for (int y = -54; y <= -51; y++) {
        BlockPos position = new BlockPos(x, y, 12);
        BlockState original = level.getBlockState(position);
        blocks.put(position, original);
        wall.put(position, original);
        level.setBlock(position, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
      }
    ArmorStand visible = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
    ArmorStand occluded = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
    Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
    if (visible == null || occluded == null || display == null)
      throw new AssertionError("Could not create text fixture entities");
    visible.setPos(9.5, -54, 10.5);
    visible.setNoGravity(true);
    visible.setCustomName(Component.literal(VISIBLE_NAME));
    visible.setCustomNameVisible(true);
    occluded.setPos(13.5, -54, 10.5);
    occluded.setNoGravity(true);
    occluded.setCustomName(
        Component.literal(OCCLUDED_NAME).withStyle(style -> style.withFont(UNIFORM_FONT)));
    occluded.setCustomNameVisible(true);
    display.setPos(11.2, -53.2, 11.5);
    display.setText(
        Component.literal(DISPLAY_TEXT).withStyle(style -> style.withFont(UNIFORM_FONT)));
    display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
    display.setFlags(
        (byte) (Display.TextDisplay.FLAG_SHADOW | Display.TextDisplay.FLAG_SEE_THROUGH));
    display.setBackgroundColor(0xc0002040);
    display.setBrightnessOverride(new Brightness(15, 15));
    if (!level.addFreshEntity(visible)
        || !level.addFreshEntity(occluded)
        || !level.addFreshEntity(display))
      throw new AssertionError("Could not add text fixture entities");
    return new Fixture(
        blocks,
        wall,
        List.of(visible.getUUID(), occluded.getUUID(), display.getUUID()),
        visible.getId(),
        occluded.getId(),
        display.getId());
  }

  private static void assertTextPipelines() {
    try {
      var field = ShaderRuntime.class.getDeclaredField("geometry");
      field.setAccessible(true);
      Map<?, ?> variants = (Map<?, ?>) field.get(ShaderRuntime.get());
      Set<String> seen = new HashSet<>();
      for (Object variant : variants.keySet()) {
        var shadow = variant.getClass().getDeclaredMethod("shadow");
        var original = variant.getClass().getDeclaredMethod("original");
        shadow.setAccessible(true);
        original.setAccessible(true);
        if ((boolean) shadow.invoke(variant)) continue;
        String path = ((RenderPipeline) original.invoke(variant)).getLocation().getPath();
        seen.add(path.substring(path.lastIndexOf('/') + 1));
      }
      // Minecraft 26.3 UnihexProvider uploads RGBA and reports isColored=true, including the
      // uniform font. Genuine R8 grayscale atlases are covered directly by TextGeometryTest.
      Set<String> required = Set.of("text", "text_see_through");
      if (!seen.containsAll(required)) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(seen);
        throw new AssertionError("World text fixture did not execute " + missing + "; saw " + seen);
      }
      System.out.println(
          "World text fixture executed ordinary and see-through default/uniform glyph pipelines"
              + " with name/display backgrounds: "
              + required);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Could not inspect world text draw coverage", failure);
    }
  }

  private static void capture(ClientGameTestContext context, String name) {
    context.runOnClient(
        client -> {
          client.gui.hud.getChat().clearMessages(false);
          client.gui.toastManager().clear();
        });
    context.waitTick();
    Path screenshot = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    System.out.println("World text visual evidence: " + screenshot.toAbsolutePath());
  }
}
