package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.ShaderPack;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Verifies that pack lighting and the actual depth camera agree, including the End's lower light.
 */
public final class ShadowLightConventionTest {
  private static final UniformLayout LAYOUT =
      new UniformLayout(
          List.of(
              new UniformLayout.Field("sunPosition", "vec3", 0, 0, 12, 0, 0),
              new UniformLayout.Field("moonPosition", "vec3", 0, 16, 12, 0, 0),
              new UniformLayout.Field("shadowLightPosition", "vec3", 0, 32, 12, 0, 0),
              new UniformLayout.Field("sunAngle", "float", 0, 48, 4, 0, 0),
              new UniformLayout.Field("shadowAngle", "float", 0, 52, 4, 0, 0)),
          64);

  public static void main(String[] args) throws Exception {
    float path = (float) Math.toRadians(-40);
    Vector3f up = new Vector3f(0, (float) Math.cos(path), (float) -Math.sin(path));
    verify(0, (float) Math.PI, path, false, up, 0.25f);
    verify((float) Math.PI, 0, path, false, up, 0.75f);
    // Separate moon environment tracks must supply the real moon, not the negated sun.
    verify(
        (float) Math.PI,
        (float) Math.toRadians(45),
        path,
        false,
        new Vector3f(-0.70710677f, 0.70710677f * up.y, 0.70710677f * up.z),
        0.75f);
    verify(0, 0, path, true, new Vector3f(up).negate(), 0.25f);
    // A vertical light must also produce a finite, correctly oriented camera.
    verify(0, (float) Math.PI, 0, false, new Vector3f(0, 1, 0), 0.25f);
    if (args.length > 0) verifyBslCycle(Path.of(args[0]));
    System.out.println(
        "PASS: day, night and fixed End light uniforms match shadow camera direction and occlusion"
            + " depth");
  }

  private static void verify(
      float sky, float moon, float path, boolean end, Vector3f expected, float expectedCycle) {
    FrameUniforms uniforms = new FrameUniforms();
    Matrix4f mainView = new Matrix4f().rotateX(0.37f).rotateY(-1.12f);
    uniforms.updateCelestial(sky, moon, path, end, mainView);
    ByteBuffer buffer = LAYOUT.allocate();
    uniforms.write(LAYOUT, buffer, false, false);
    near(buffer.getFloat(48), expectedCycle, "cycle angle");
    near(
        buffer.getFloat(52),
        expectedCycle > 0.5f ? expectedCycle - 0.5f : expectedCycle,
        "shadow angle");
    Vector3f reported = vector(buffer, 32);
    near(reported.length(), 100, "view-space light length");
    new Matrix4f(mainView).invert().transformDirection(reported).normalize();
    if (reported.distance(expected) > 1.0e-5f)
      throw new AssertionError(
          "Reported world light differs from dimension convention: " + reported);
    if (reported.distance(uniforms.shadowDirectionWorld()) > 1.0e-5f)
      throw new AssertionError("Uniform and shadow camera use different lights");
    Vector3f expectedView = vector(buffer, end || expectedCycle <= 0.5f ? 0 : 16);
    if (expectedView.distance(vector(buffer, 32)) > 1.0e-4f)
      throw new AssertionError("Shadow light differs from the selected sun/moon uniform");
    Matrix4f shadowView = ShadowRenderer.lightView(uniforms.shadowDirectionWorld(), 160);
    Vector3f lightAxis = shadowView.transformDirection(new Vector3f(reported));
    if (lightAxis.distance(new Vector3f(0, 0, 1)) > 1.0e-5f)
      throw new AssertionError("Shadow camera faces away from the reported light: " + lightAxis);
    Matrix4f projection = new Matrix4f().ortho(-160, 160, -160, 160, 0.05f, 640).mul(shadowView);
    Vector3f receiver = projection.transformPosition(new Vector3f());
    Vector3f occluder = projection.transformPosition(new Vector3f(reported).mul(10));
    near(receiver.x, occluder.x, "occluder projection X");
    near(receiver.y, occluder.y, "occluder projection Y");
    if (!(occluder.z < receiver.z))
      throw new AssertionError("Occluder toward the light does not precede receiver in depth");
  }

  private static void verifyBslCycle(Path path) throws Exception {
    ShaderPack pack = ShaderPack.load(path);
    CustomUniforms expressions =
        CustomUniforms.compile(pack.properties(Map.of(), PackEnvironment.definitions()));
    Map<String, Double> inputs = new HashMap<>();
    for (String name : expressions.requiredInputs()) inputs.put(name, 0.0);
    inputs.put("sunAngle", 0.25);
    double timeAngle = expressions.evaluate(inputs, 1.0 / 60).get("timeAngle");
    if (!(timeAngle < 0.5325 || timeAngle > 0.9675))
      throw new AssertionError("BSL's End custom cycle unexpectedly reverses its fixed light");
    near((float) timeAngle, 0.25f, "BSL End cycle");
  }

  private static Vector3f vector(ByteBuffer buffer, int offset) {
    return new Vector3f(
        buffer.getFloat(offset), buffer.getFloat(offset + 4), buffer.getFloat(offset + 8));
  }

  private static void near(float actual, float expected, String what) {
    if (!Float.isFinite(actual) || Math.abs(actual - expected) > 1.0e-4f)
      throw new AssertionError(what + ": " + actual + " != " + expected);
  }
}
