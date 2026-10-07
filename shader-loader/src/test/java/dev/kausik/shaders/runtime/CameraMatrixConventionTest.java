package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.UniformLayout;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Checks legacy diagonal depth reconstruction against actual bobbed world rasterization. */
public final class CameraMatrixConventionTest {
  private static final String[] NAMES = {
    "gbufferProjection",
    "gbufferProjectionInverse",
    "gbufferModelView",
    "gbufferModelViewInverse",
    "sl_CameraEffect",
    "sl_CameraEffectInverse",
    "sl_TerrainModelViewProjection",
    "sl_ShadowModelViewProjection"
  };

  public static void main(String[] args) {
    var fields = new ArrayList<UniformLayout.Field>();
    for (int i = 0; i < NAMES.length; i++)
      fields.add(new UniformLayout.Field(NAMES[i], "mat4", 0, i * 64, 64, 0, 16));
    var layout = new UniformLayout(fields, NAMES.length * 64);
    Matrix4f baseNative =
        new Matrix4f().perspective((float) Math.toRadians(70), 3456f / 2168, 1024, 0.05f, true);
    Matrix4f conversion = new Matrix4f().m22(-2).m32(1);
    Matrix4f rotation = new Matrix4f().rotateX(0.27f).rotateY(-0.8f);
    Matrix4f inverseRotation = new Matrix4f(rotation).invert();
    List<Matrix4f> effects = new ArrayList<>();
    effects.add(new Matrix4f());
    // The game applies walking translation, roll and pitch to its base projection.
    for (float phase : new float[] {0, 0.25f, 0.5f, 0.75f}) {
      float bob = 0.1f;
      float sin = (float) Math.sin(phase * Math.PI), cos = (float) Math.cos(phase * Math.PI);
      effects.add(
          new Matrix4f()
              .translate(sin * bob * 0.5f, -Math.abs(cos * bob), 0)
              .rotateZ((float) Math.toRadians(sin * bob * 3))
              .rotateX(
                  (float) Math.toRadians(Math.abs(Math.cos(phase * Math.PI - 0.2) * bob) * 5)));
    }
    effects.add(new Matrix4f().rotateY(-0.6f).rotateZ(0.17f).rotateY(0.6f));
    Vector3f axis = new Vector3f(0, 1, 1).normalize();
    effects.add(new Matrix4f().rotate(0.8f, axis).scale(1.06f, 1, 1).rotate(-0.8f, axis));
    float largestLegacyError = 0;
    for (Matrix4f effect : effects) {
      var uniforms = new FrameUniforms();
      Matrix4f combinedNative = new Matrix4f(baseNative).mul(effect);
      uniforms.updateCameraMatrices(baseNative, combinedNative, rotation);
      Matrix4f light = ShadowRenderer.lightView(new Vector3f(1, 0.1f, 0.08f).normalize(), 256);
      Matrix4f lightProjection = new Matrix4f().ortho(-256, 256, -256, 256, .05f, 1024);
      uniforms.setShadowMatrices(lightProjection, light);
      ByteBuffer buffer = layout.allocate();
      uniforms.write(layout, buffer, false, false);
      Matrix4f projection = matrix(buffer, 0), inverseProjection = matrix(buffer, 1);
      Matrix4f modelView = matrix(buffer, 2), inverseModelView = matrix(buffer, 3);
      Matrix4f cameraEffect = matrix(buffer, 4);
      Matrix4f inverseEffect = matrix(buffer, 5);
      Matrix4f frameTerrainMvp = matrix(buffer, 6), frameShadowMvp = matrix(buffer, 7);
      Matrix4f nativeRaster = new Matrix4f(conversion).mul(combinedNative).mul(rotation);
      Matrix4f packRaster = new Matrix4f(projection).mul(modelView);
      Matrix4f perDrawRaster =
          new Matrix4f(conversion)
              .mul(combinedNative)
              .mul(inverseEffect)
              .mul(cameraEffect)
              .mul(rotation);
      Matrix4f oldInverseProjection = new Matrix4f(conversion).mul(combinedNative).invert();
      for (Vector3f viewPoint : List.of(new Vector3f(4, 2, -12), new Vector3f(-6, -4, -30))) {
        Vector3f world = inverseRotation.transformPosition(new Vector3f(viewPoint));
        Vector3f ndc = nativeRaster.transformProject(new Vector3f(world));
        near(packRaster.transformProject(new Vector3f(world)), ndc, 1e-5f, "Rasterization changed");
        near(frameTerrainMvp.transformProject(new Vector3f(world)), ndc, 1e-5f,
            "Precomputed terrain MVP changed rasterization");
        near(frameShadowMvp.transformProject(new Vector3f(world)),
            new Matrix4f(lightProjection).mul(light).transformProject(new Vector3f(world)),
            1e-5f, "Precomputed shadow MVP changed rasterization");
        near(
            perDrawRaster.transformProject(new Vector3f(world)),
            ndc,
            1e-5f,
            "Per-draw projection changed after moving the camera effect");
        Vector3f restored =
            inverseModelView.transformPosition(diagonalReconstruction(inverseProjection, ndc));
        near(restored, world, 0.005f, "Legacy depth reconstruction moved the surface");
        Vector3f broken =
            inverseRotation.transformPosition(diagonalReconstruction(oldInverseProjection, ndc));
        largestLegacyError = Math.max(largestLegacyError, broken.distance(world));

        // The shadow replay receives ordinary vanilla V, never the already adapted B*V.
        Matrix4f adaptedShadow =
            new Matrix4f(light)
                .mul(inverseModelView)
                .mul(cameraEffect)
                .mul(uniforms.cameraViewRotation());
        near(
            adaptedShadow.transformPosition(new Vector3f(world)),
            light.transformPosition(new Vector3f(world)),
            0.0002f,
            "Camera effect leaked into shadow geometry");
      }
    }
    if (largestLegacyError < 1)
      throw new AssertionError("Regression scene failed to expose the old combined-projection bug");
    System.out.println(
        "PASS: bob, hurt and nausea preserve rasterization, legacy depth reconstruction and stable"
            + " shadow geometry; old maximum error="
            + largestLegacyError
            + " blocks");
  }

  private static Matrix4f matrix(ByteBuffer buffer, int index) {
    float[] values = new float[16];
    for (int i = 0; i < values.length; i++) values[i] = buffer.getFloat(index * 64 + i * 4);
    return new Matrix4f().set(values);
  }

  private static Vector3f diagonalReconstruction(Matrix4f inverse, Vector3f ndc) {
    float w = inverse.m23() * ndc.z + inverse.m33();
    return new Vector3f(
            inverse.m00() * ndc.x + inverse.m30(),
            inverse.m11() * ndc.y + inverse.m31(),
            inverse.m22() * ndc.z + inverse.m32())
        .div(w);
  }

  private static void near(Vector3f actual, Vector3f expected, float tolerance, String message) {
    if (!actual.isFinite() || actual.distance(expected) > tolerance)
      throw new AssertionError(message + ": " + actual + " versus " + expected);
  }
}
