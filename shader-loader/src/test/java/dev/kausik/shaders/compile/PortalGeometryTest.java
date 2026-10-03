package dev.kausik.shaders.compile;

import dev.kausik.shaders.geometry.PortalGeometry;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Checks all six real portal face windings after translation, rotation and nonuniform scale. */
public final class PortalGeometryTest {
  public static void main(String[] args) {
    var memory = MemoryUtil.memCalloc(4 * PortalGeometry.STRIDE);
    try {
      long pointer = MemoryUtil.memAddress(memory);
      var transform =
          new Matrix4f()
              .translation(27.25f, -6.5f, 13)
              .rotateXYZ(.3f, .7f, -.4f)
              .scale(1, .375f, 1);
      var normalMatrix = new Matrix3f(transform).invert().transpose();
      for (Direction direction : Direction.values()) {
        var face = FaceInfo.fromFacing(direction);
        for (int corner = 0; corner < 4; corner++) {
          var position = face.getVertexInfo(corner).select(new Vector3f(), new Vector3f(1));
          transform.transformPosition(position);
          long vertex = pointer + (long) corner * PortalGeometry.STRIDE;
          MemoryUtil.memPutFloat(vertex, position.x);
          MemoryUtil.memPutFloat(vertex + 4, position.y);
          MemoryUtil.memPutFloat(vertex + 8, position.z);
        }
        PortalGeometry.finishQuad(pointer + 3L * PortalGeometry.STRIDE);
        var expected = normalMatrix.transform(new Vector3f(direction.getUnitVec3f())).normalize();
        for (int corner = 0; corner < 4; corner++) {
          long normal =
              pointer + (long) corner * PortalGeometry.STRIDE + PortalGeometry.NORMAL_OFFSET;
          for (int axis = 0; axis < 3; axis++)
            if (Math.abs(MemoryUtil.memGetByte(normal + axis) / 127f - expected.get(axis)) > .011f)
              throw new AssertionError(
                  "Portal face normal differs for " + direction + " axis " + axis);
        }
      }
    } finally {
      MemoryUtil.memFree(memory);
    }
    System.out.println(
        "PASS: all six portal face normals survive actual face winding and transformed geometry");
  }
}
