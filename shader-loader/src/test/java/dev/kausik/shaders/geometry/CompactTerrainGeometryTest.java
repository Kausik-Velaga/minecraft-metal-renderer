package dev.kausik.shaders.geometry;

import dev.kausik.shaders.compile.MinecraftVertexAdapter;
import java.nio.ByteBuffer;
import java.util.Random;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;

/** Validates complete metadata round trips and quad writes across both immutable arena layouts. */
public final class CompactTerrainGeometryTest {
  private static final String FLAG = "minecraftShaders.compactTerrainVertices";

  public static void main(String[] args) {
    String previous = System.getProperty(FLAG);
    try {
      System.setProperty(FLAG, "true");
      layoutSelection();
      packedMetadata();
      quadEquivalence();
      adapterLayoutLifetime();
      System.out.println(
          "Compact terrain passed: 48-byte layout, signed IDs, block centers, quad attributes,"
              + " fallback and layout lifetime.");
    } finally {
      TerrainShaderGeometry.disable();
      if (previous == null) System.clearProperty(FLAG);
      else System.setProperty(FLAG, previous);
    }
  }

  private static void layoutSelection() {
    for (var layout : TerrainShaderGeometry.Layout.values()) {
      check(layout.format().getVertexSize() == layout.stride(), "Arena stride mismatch");
      check(TerrainShaderGeometry.layoutFor(layout.format()) == layout, "Layout identity mismatch");
    }
    TerrainShaderGeometry.configure(state -> 40000, true, false);
    check(
        TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.FULL,
        "Unproven ID range was compacted");
    TerrainShaderGeometry.configure(state -> 10000, true);
    check(
        TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.FULL,
        "Generic resolver was assumed bounded");
    System.setProperty(FLAG, "false");
    TerrainShaderGeometry.configure(state -> 10000, true, true);
    check(
        TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.FULL,
        "Compact mode enabled without opt-in");
    System.setProperty(FLAG, "true");
    TerrainShaderGeometry.configure(state -> 10000, true, true);
    check(
        TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.COMPACT,
        "Proven compact mode not selected");
  }

  private static void packedMetadata() {
    int[] material = {-1};
    TerrainShaderGeometry.configure(state -> material[0], true, true);
    var previous = TerrainShaderGeometry.beginSection();
    ByteBuffer memory = MemoryUtil.memCalloc(64);
    long pointer = MemoryUtil.memAddress(memory);
    try {
      // All signed IDs and all 4096 block-center combinations, with both render types.
      for (int id = Short.MIN_VALUE; id <= Short.MAX_VALUE; id++) {
        material[0] = id;
        int x = id & 15;
        int y = (id >>> 4) & 15;
        int z = (id >>> 8) & 15;
        for (boolean fluid : new boolean[] {false, true}) {
          TerrainShaderGeometry.beginBlock(null, new BlockPos(x - 32, y + 32, z - 16), fluid);
          TerrainVertexWriter.writeMetadata(pointer, -123.25f, 456.125f, Float.NaN);
          int packed = MemoryUtil.memGetInt(pointer + 32);
          check((short) packed == id, "Signed material ID changed");
          check(((packed >>> 16) & 1) == (fluid ? 1 : 0), "Render type changed");
          check(
              ((packed >>> 17) & 15) == x
                  && ((packed >>> 21) & 15) == y
                  && ((packed >>> 25) & 15) == z,
              "Block center changed");
          check((packed >>> 29) == 0, "Reserved bits written");
          for (int i = 36; i < 64; i++)
            check(MemoryUtil.memGetByte(pointer + i) == 0, "Metadata write crossed its field");
        }
      }
      // Float positions are never quantized or used to infer the emitting block. Reconstruct with
      // exactly the same subtraction and scale; NaN classification is meaningful, payload is not.
      Random random = new Random(7219);
      float[] exceptional = {
        0,
        -0.0f,
        Float.MIN_VALUE,
        -Float.MIN_VALUE,
        Float.MAX_VALUE,
        -Float.MAX_VALUE,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
        0.5f,
        Math.nextUp(0.5f),
        Math.nextDown(15.5f)
      };
      for (int i = 0; i < 10000; i++) {
        int coordinate = i & 15;
        float position =
            i < exceptional.length ? exceptional[i] : Float.intBitsToFloat(random.nextInt());
        float expected = ((coordinate + 0.5f) - position) * 64.0f;
        int packed = (coordinate << 17);
        float decoded = ((((packed >>> 17) & 15) + 0.5f) - position) * 64.0f;
        check(
            Float.floatToIntBits(expected) == Float.floatToIntBits(decoded),
            "Mid-block reconstruction changed float semantics");
      }
      material[0] = 32768;
      try {
        TerrainShaderGeometry.beginBlock(null, BlockPos.ZERO, false);
        throw new AssertionError("Broken material range proof was silently truncated");
      } catch (IllegalStateException expected) {
        // Real BlockMaterialMap is immutable. Detect a contract violation before vertex emission.
      }
      try {
        TerrainShaderGeometry.currentBlock();
        throw new AssertionError("Failed block initialization left stale metadata valid");
      } catch (IllegalStateException expected) {
      }
    } finally {
      MemoryUtil.memFree(memory);
      TerrainShaderGeometry.endSection(previous);
    }
  }

  private static void quadEquivalence() {
    float[][] positions = {
      {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
      {-1.25f, 0, 0, -1.25f, 0, 1, 2.75f, 0.5f, 1, 2.75f, 0.5f, 0},
      {Float.NaN, 0, 0, Float.POSITIVE_INFINITY, 0, 0, 0, Float.NEGATIVE_INFINITY, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
    };
    for (float[] quad : positions) {
      for (boolean mirrored : new boolean[] {false, true}) {
        ByteBuffer full = writeQuad(TerrainShaderGeometry.Layout.FULL, quad, mirrored);
        ByteBuffer compact = writeQuad(TerrainShaderGeometry.Layout.COMPACT, quad, mirrored);
        try {
          long a = MemoryUtil.memAddress(full);
          long b = MemoryUtil.memAddress(compact);
          for (int vertex = 0; vertex < 4; vertex++) {
            equalBytes(a + vertex * 64L, b + vertex * 48L, 32);
            equalBytes(a + vertex * 64L + 40, b + vertex * 48L + 36, 12);
          }
          for (int i = 4 * 48; i < compact.capacity(); i++)
            check(compact.get(i) == (byte) 0x5a, "Compact quad crossed allocation boundary");
        } finally {
          MemoryUtil.memFree(full);
          MemoryUtil.memFree(compact);
        }
      }
    }
  }

  private static ByteBuffer writeQuad(
      TerrainShaderGeometry.Layout layout, float[] positions, boolean mirrored) {
    TerrainShaderGeometry.configure(
        state -> -32768, false, layout == TerrainShaderGeometry.Layout.COMPACT);
    var previous = TerrainShaderGeometry.beginSection();
    ByteBuffer memory = MemoryUtil.memAlloc(4 * layout.stride() + 16);
    long start = MemoryUtil.memAddress(memory);
    MemoryUtil.memSet(start, 0x5a, memory.capacity());
    try {
      TerrainShaderGeometry.beginBlock(null, new BlockPos(-1, 32, -17), true);
      for (int vertex = 0; vertex < 4; vertex++) {
        long pointer = start + (long) vertex * layout.stride();
        MemoryUtil.memSet(pointer, 0, 32);
        for (int axis = 0; axis < 3; axis++)
          MemoryUtil.memPutFloat(pointer + axis * 4L, positions[vertex * 3 + axis]);
        MemoryUtil.memPutInt(pointer + 12, 0x7f214365);
        MemoryUtil.memPutFloat(
            pointer + 16, ((vertex == 1 || vertex == 2) ^ mirrored) ? 0.98765f : 0.12345f);
        MemoryUtil.memPutFloat(pointer + 20, vertex >= 2 ? 0.45678f : 0.01234f);
        MemoryUtil.memPutInt(pointer + 24, 0x00f000a0);
        TerrainVertexWriter.putNormal(pointer + 28, 0, 1, 0);
        TerrainVertexWriter.writeMetadata(
            pointer,
            positions[vertex * 3],
            positions[vertex * 3 + 1],
            positions[vertex * 3 + 2],
            layout);
      }
      TerrainVertexWriter.finishQuad(start + 3L * layout.stride(), layout);
      return memory;
    } catch (Throwable failure) {
      MemoryUtil.memFree(memory);
      throw failure;
    } finally {
      TerrainShaderGeometry.endSection(previous);
    }
  }

  private static void adapterLayoutLifetime() {
    TerrainShaderGeometry.configure(state -> -1, true, false);
    var original = RenderPipelines.SOLID_TERRAIN;
    var full = TerrainShaderGeometry.pipeline(original);
    check(
        full.getVertexFormatBindings().get(0) == TerrainShaderGeometry.FORMAT,
        "Full pipeline format mismatch");
    TerrainShaderGeometry.configure(state -> -1, true, true);
    var compact = TerrainShaderGeometry.pipeline(original);
    check(
        full != compact
            && compact.getVertexFormatBindings().get(0) == TerrainShaderGeometry.COMPACT_FORMAT,
        "Pipeline cache retained old arena layout");
    var fullAdapter = MinecraftVertexAdapter.adapter(full, false);
    var compactAdapter = MinecraftVertexAdapter.adapter(compact, false);
    check(
        fullAdapter.declarations().contains("in vec2 PackEntity;"),
        "Previously compiled full pipeline changed format");
    check(
        compactAdapter.declarations().contains("in uint PackMetadata;"),
        "Compact metadata is not an integer vertex input");
    check(
        compactAdapter.legacyExpressions().get("at_midBlock").contains("- Position"),
        "Mid-block reconstruction omitted raw position");
    var previous = TerrainShaderGeometry.beginSection();
    try {
      TerrainShaderGeometry.beginBlock(null, BlockPos.ZERO, false);
      var captured = TerrainShaderGeometry.currentBlock();
      // Simulate a configuration generation replacement without real dispatcher workers. Runtime
      // still requires full dispatcher disposal; a context itself must remain immutable regardless.
      TerrainShaderGeometry.configure(state -> 40000, true, false);
      check(
          captured.layout() == TerrainShaderGeometry.Layout.COMPACT
              && TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.COMPACT,
          "Section snapshot changed layout");
      TerrainShaderGeometry.beginBlock(null, BlockPos.ZERO, false);
      check(
          TerrainShaderGeometry.currentBlock().materialId == -1,
          "Section resolver changed generation");
    } finally {
      TerrainShaderGeometry.endSection(previous);
    }
    check(
        TerrainShaderGeometry.layout() == TerrainShaderGeometry.Layout.FULL,
        "Render thread did not see new generation");
    var reconfigured = TerrainShaderGeometry.pipeline(original);
    check(reconfigured != full, "Configuration did not invalidate pipeline cache");
    TerrainShaderGeometry.disable();
    check(
        TerrainShaderGeometry.pipeline(original) == original,
        "Disabled layout retained shader pipeline");
  }

  private static void equalBytes(long a, long b, int size) {
    for (int i = 0; i < size; i++)
      check(
          MemoryUtil.memGetByte(a + i) == MemoryUtil.memGetByte(b + i),
          "Shared vertex attribute changed at byte " + i);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
