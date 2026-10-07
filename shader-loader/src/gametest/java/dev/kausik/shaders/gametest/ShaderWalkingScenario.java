package dev.kausik.shaders.gametest;

import dev.kausik.shaders.runtime.ShaderRuntime;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Actual ground walking at fixed sunrise, with depth-reconstruction checks on rendered matrices.
 */
@SuppressWarnings("UnstableApiUsage")
final class ShaderWalkingScenario {
  private record Previous(boolean bob, String mode, long time, String teleport) {}

  private ShaderWalkingScenario() {}

  static void run(ClientGameTestContext context, TestSingleplayerContext world) {
    var previous =
        context.computeOnClient(
            client ->
                new Previous(
                    client.options.bobView().get(),
                    client.gameMode.getPlayerMode().getName(),
                    client.level.getOverworldClockTime(),
                    String.format(
                        Locale.ROOT,
                        "tp @a %.6f %.6f %.6f %.5f %.5f",
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.player.getXRot())));
    var server = world.getServer();
    try {
      context.runOnClient(client -> client.options.bobView().set(true));
      server.runCommand("time set 499");
      server.runCommand("gamemode survival @a");
      // Ground-level view faces the existing log and its long sunrise shadow. No fixture blocks
      // are changed, and the walk remains on the original stone floor away from the pool.
      server.runCommand("tp @a 7.5 -59 7.5 150 5");
      world.getConnection().waitForClientboundPackets();
      context.waitFor(
          client -> client.player.onGround() && !client.player.getAbilities().flying, 100);
      context.waitTicks(40);
      capture(context, "12a-bsl-sunrise-standing-log", false);
      context.getInput().holdKey(options -> options.keyUp);
      context.waitTicks(5);
      capture(context, "12b-bsl-sunrise-walking-phase1", true);
      context.waitTicks(4);
      capture(context, "12c-bsl-sunrise-walking-phase2", true);
      context.waitTicks(4);
      capture(context, "12d-bsl-sunrise-walking-phase3", true);
      context.getInput().releaseKey(options -> options.keyUp);
      context.waitTicks(40);
      capture(context, "12e-bsl-sunrise-standing-after-walk", false);
    } finally {
      context.getInput().releaseKey(options -> options.keyUp);
      context.runOnClient(client -> client.options.bobView().set(previous.bob()));
      server.runCommand("gamemode " + previous.mode() + " @a");
      server.runCommand("time set " + previous.time());
      server.runCommand(previous.teleport());
      world.getConnection().waitForClientboundPackets();
      context.waitTicks(20);
    }
  }

  private static void capture(ClientGameTestContext context, String name, boolean walking) {
    context.runOnClient(
        client -> {
          client.gui.hud.getChat().clearMessages(false);
          client.gui.toastManager().clear();
        });
    context.waitTick();
    context.runOnClient(client -> verifyCameraReconstruction(client, name, walking));
    Path path = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
    System.out.println("Shader sunrise walking screenshot: " + path.toAbsolutePath());
  }

  private static void verifyCameraReconstruction(Minecraft client, String name, boolean walking) {
    if (!client.options.bobView().get() || client.level.getOverworldClockTime() != 499)
      throw new AssertionError("Walking fixture must keep view bob enabled and time frozen at499");
    if (!client.player.onGround() || client.player.getAbilities().flying)
      throw new AssertionError("Walking fixture left the ground");
    var uniforms = ShaderRuntime.get().uniforms();
    Matrix4f cameraEffect =
        new Matrix4f(uniforms.modelView())
            .mul(new Matrix4f(uniforms.cameraViewRotation()).invert());
    float[] values = cameraEffect.get(new float[16]);
    float effectSize = 0;
    for (int i = 0; i < values.length; i++)
      effectSize = Math.max(effectSize, Math.abs(values[i] - (i % 5 == 0 ? 1 : 0)));
    if (walking && effectSize < 0.005f)
      throw new AssertionError(
          "Walking capture did not exercise a nonidentity camera effect: " + effectSize);
    var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
    Matrix4f projection = new Matrix4f(uniforms.projection());
    Matrix4f inverseProjection = new Matrix4f(projection).invert();
    Matrix4f modelView = new Matrix4f(uniforms.modelView());
    Matrix4f inverseView = new Matrix4f(modelView).invert();
    Matrix4f raster = new Matrix4f(projection).mul(modelView);
    Matrix4f shadow = new Matrix4f(uniforms.shadowProjection()).mul(uniforms.shadowModelView());
    float largestError = 0;
    // Fixed points on the existing tree trunk and floor, expressed relative to this frame's camera.
    for (Vector3f point : new Vector3f[] {new Vector3f(3.5f, -57, 0.5f), new Vector3f(5, -59, 1)}) {
      point.sub((float) camera.x, (float) camera.y, (float) camera.z);
      Vector3f ndc = raster.transformProject(new Vector3f(point));
      float w = inverseProjection.m23() * ndc.z + inverseProjection.m33();
      Vector3f view =
          new Vector3f(
                  inverseProjection.m00() * ndc.x + inverseProjection.m30(),
                  inverseProjection.m11() * ndc.y + inverseProjection.m31(),
                  inverseProjection.m22() * ndc.z + inverseProjection.m32())
              .div(w);
      Vector3f restored = inverseView.transformPosition(view);
      float error = restored.distance(point);
      largestError = Math.max(largestError, error);
      if (!restored.isFinite() || error > 0.005f)
        throw new AssertionError(
            "Rendered camera matrices move reconstructed terrain by " + error + " blocks");
      Vector3f expectedShadow = shadow.transformPosition(new Vector3f(point));
      Vector3f reconstructedShadow = shadow.transformPosition(restored);
      if (expectedShadow.distance(reconstructedShadow) > 0.0001f)
        throw new AssertionError("Walking camera moved the reconstructed shadow lookup");
    }
    System.out.printf(
        Locale.ROOT,
        "Shader sunrise walking %s: camera=(%.5f,%.5f,%.5f), onGround=true, cameraEffect=%.7f,"
            + " reconstructionError=%.7f blocks%n",
        name,
        camera.x,
        camera.y,
        camera.z,
        effectSize,
        largestError);
  }
}
