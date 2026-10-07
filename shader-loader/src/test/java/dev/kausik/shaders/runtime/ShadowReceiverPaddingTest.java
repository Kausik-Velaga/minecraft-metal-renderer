package dev.kausik.shaders.runtime;

import java.util.Random;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Numeric checks of the documented bounded-terrain proof; not rendered-image validation. */
final class ShadowReceiverPaddingTest {
  static void run() {
    if (ShadowRenderer.receiverPadding(false, true) != 64
        || ShadowRenderer.receiverPadding(true, false) != 64
        || ShadowRenderer.receiverPadding(false, false) != 64
        || ShadowRenderer.receiverPadding(true, true) != 32)
      throw new AssertionError("Tight padding escaped its request/eligibility gate");
    if (filterBound(.3, .00175) >= 1.739
        || filterBound(Math.sqrt(2), .0007) >= 13.895
        || filterBound(Math.sqrt(2), 1.0 / 2048) >= 11.716)
      throw new AssertionError("Documented filter bound changed");
    double total = filterBound(Math.sqrt(2), .0007) + 6 + 1
        + .0512 / 2048 * (4 * 256 - .05) / .2;
    if (total >= 21.023 || 32 - total <= 10)
      throw new AssertionError("Tight padding lost its numerical slack");
    verifyTriangleInterpolation();
  }

  private static double filterBound(double radius, double offset) {
    double distorted = radius / (.9 * radius + .1);
    // Bilinear support reaches almost a full texel per axis, not half a texel.
    double sampled = distorted + 2 * (offset + Math.sqrt(2) / 2048);
    return 256 * (.1 * sampled / (1 - .9 * sampled) - radius);
  }

  private static void verifyTriangleInterpolation() {
    Random random = new Random(0x32b0a1d);
    var rotation = new Matrix4d().rotateY(.71).rotateX(.37);
    for (int sample = 0; sample < 4000; sample++) {
      double centerX = random.nextDouble() * 4 - 2;
      double centerY = random.nextDouble() * 4 - 2;
      double[][] points = new double[3][2];
      double[] weights = new double[3];
      double weightSum = 0;
      for (int i = 0; i < 3; i++) {
        var vertex = new Vector3d(random.nextDouble() * 16,
            random.nextDouble() * 16, random.nextDouble() * 16);
        var wave = new Vector3d(random.nextDouble() - .5,
            random.nextDouble() - .5, random.nextDouble() - .5);
        rotation.transformPosition(vertex.add(wave));
        points[i][0] = centerX + vertex.x / 256;
        points[i][1] = centerY + vertex.y / 256;
        weights[i] = random.nextDouble();
        weightSum += weights[i];
      }
      double qx = 0, qy = 0, adjustedSum = 0;
      double diameterSquared = 0;
      for (int i = 0; i < 3; i++) {
        double factor = .9 * Math.hypot(points[i][0], points[i][1]) + .1;
        double adjusted = weights[i] / weightSum / factor;
        qx += adjusted * points[i][0];
        qy += adjusted * points[i][1];
        adjustedSum += adjusted;
        for (int j = 0; j < i; j++) {
          double dx = points[i][0] - points[j][0];
          double dy = points[i][1] - points[j][1];
          diameterSquared = Math.max(diameterSquared, dx * dx + dy * dy);
        }
      }
      double inverseScale = .1 / (1 - .9 * Math.hypot(qx, qy));
      double displacement = Math.hypot(qx * inverseScale - qx / adjustedSum,
          qy * inverseScale - qy / adjustedSum);
      double bound = .9 * diameterSquared / (6 * .1);
      if (displacement > bound + 1e-10 || 256 * bound >= 6)
        throw new AssertionError("Distorted triangle exceeded its bounded-section proof");
    }
  }
}
