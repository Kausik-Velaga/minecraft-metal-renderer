package dev.kausik.shaders.geometry;

import java.nio.ByteBuffer;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;

/** Real quad data verifies normal orientation, mirrored UV handedness, and material context. */
public final class TerrainGeometryTest {
  public static void main(String[] args) {
    if (TerrainShaderGeometry.FORMAT.getVertexSize() != TerrainShaderGeometry.STRIDE) {
      throw new AssertionError("Terrain format stride and arena stride differ");
    }
    testFlatAndMirroredQuad(false);
    testFlatAndMirroredQuad(true);
    testSlopingWater();
    testMaterialContext();
    System.out.println("Terrain geometry passed: normals, UV handedness, quad centers, materials.");
  }

  private static void testFlatAndMirroredQuad(boolean mirrorU) {
    ByteBuffer vertices = MemoryUtil.memCalloc(4 * TerrainShaderGeometry.STRIDE);
    try {
      long first = MemoryUtil.memAddress(vertices);
      float[] positions = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
      float[] uvs = {0, 0, 1, 0, 1, 1, 0, 1};
      writeQuad(first, positions, uvs, mirrorU);
      TerrainVertexWriter.finishQuad(first + 3L * TerrainShaderGeometry.STRIDE);
      for (int vertex = 0; vertex < 4; vertex++) {
        long pointer = first + (long) vertex * TerrainShaderGeometry.STRIDE;
        near(snorm(pointer + TerrainShaderGeometry.NORMAL_OFFSET), 0, "normal x");
        near(snorm(pointer + TerrainShaderGeometry.NORMAL_OFFSET + 1), 0, "normal y");
        near(snorm(pointer + TerrainShaderGeometry.NORMAL_OFFSET + 2), 1, "normal z");
        near(snorm(pointer + TerrainShaderGeometry.TANGENT_OFFSET), mirrorU ? -1 : 1, "tangent");
        near(
            snorm(pointer + TerrainShaderGeometry.TANGENT_OFFSET + 3),
            mirrorU ? 1 : -1,
            "handedness for cross(tangent, normal)");
        near(MemoryUtil.memGetFloat(pointer + TerrainShaderGeometry.MID_UV_OFFSET), 0.5f, "mid U");
        near(
            MemoryUtil.memGetFloat(pointer + TerrainShaderGeometry.MID_UV_OFFSET + 4),
            0.5f,
            "mid V");
      }
    } finally {
      MemoryUtil.memFree(vertices);
    }
  }

  private static void testSlopingWater() {
    ByteBuffer vertices = MemoryUtil.memCalloc(4 * TerrainShaderGeometry.STRIDE);
    try {
      long first = MemoryUtil.memAddress(vertices);
      float[] positions = {0, 0, 0, 0, 0, 1, 1, 0.5f, 1, 1, 0.5f, 0};
      float[] uvs = {0, 0, 0, 1, 1, 1, 1, 0};
      writeQuad(first, positions, uvs, false);
      TerrainVertexWriter.finishQuad(first + 3L * TerrainShaderGeometry.STRIDE);
      near(snorm(first + TerrainShaderGeometry.NORMAL_OFFSET), -0.4472136f, "slope normal x");
      near(snorm(first + TerrainShaderGeometry.NORMAL_OFFSET + 1), 0.8944272f, "slope normal y");
      near(snorm(first + TerrainShaderGeometry.NORMAL_OFFSET + 2), 0, "slope normal z");
    } finally {
      MemoryUtil.memFree(vertices);
    }
  }

  private static void testMaterialContext() {
    TerrainShaderGeometry.configure(state -> 20000, true);
    var previous = TerrainShaderGeometry.beginSection();
    ByteBuffer vertex = MemoryUtil.memCalloc(TerrainShaderGeometry.STRIDE);
    try {
      TerrainShaderGeometry.beginBlock(null, new BlockPos(-1, 32, -17), true);
      long pointer = MemoryUtil.memAddress(vertex);
      TerrainVertexWriter.writeMetadata(pointer, 15, 0, 15);
      near(
          MemoryUtil.memGetFloat(pointer + TerrainShaderGeometry.ENTITY_OFFSET), 20000, "material");
      near(
          MemoryUtil.memGetFloat(pointer + TerrainShaderGeometry.ENTITY_OFFSET + 4),
          1,
          "fluid type");
      for (int axis = 0; axis < 3; axis++) {
        near(
            MemoryUtil.memGetFloat(pointer + TerrainShaderGeometry.MID_BLOCK_OFFSET + axis * 4),
            32,
            "block center offset");
      }
      TerrainShaderGeometry.endBlock();
      try {
        TerrainShaderGeometry.currentBlock();
        throw new AssertionError("Missing block context was silently replaced with invented data");
      } catch (IllegalStateException expected) {
        // A missed integration hook must fail visibly rather than tag geometry with incorrect IDs.
      }
    } finally {
      TerrainShaderGeometry.endSection(previous);
      TerrainShaderGeometry.disable();
      MemoryUtil.memFree(vertex);
    }
    if (TerrainShaderGeometry.hasSection()) throw new AssertionError("Worker context leaked");
  }

  private static void writeQuad(long pointer, float[] positions, float[] uvs, boolean mirrorU) {
    for (int vertex = 0; vertex < 4; vertex++) {
      long target = pointer + (long) vertex * TerrainShaderGeometry.STRIDE;
      for (int axis = 0; axis < 3; axis++) {
        MemoryUtil.memPutFloat(target + axis * 4L, positions[vertex * 3 + axis]);
      }
      MemoryUtil.memPutFloat(target + 16, mirrorU ? 1 - uvs[vertex * 2] : uvs[vertex * 2]);
      MemoryUtil.memPutFloat(target + 20, uvs[vertex * 2 + 1]);
    }
  }

  private static float snorm(long pointer) {
    return MemoryUtil.memGetByte(pointer) / 127.0f;
  }

  private static void near(float actual, float expected, String label) {
    if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.012f) {
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
  }
}
