package dev.kausik.shaders.compile;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.kausik.shaders.geometry.DynamicBlockGeometry;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import net.minecraft.client.renderer.RenderPipelines;

/** Exercises vanilla's actual named-element writer, including signed model normals. */
public final class DynamicBlockGeometryTest {
  public static void main(String[] args) {
    if (DynamicBlockGeometry.FORMAT.getVertexSize() != DynamicBlockGeometry.STRIDE)
      throw new AssertionError("Dynamic block vertex stride");
    try (var memory = new ByteBufferBuilder(128)) {
      var builder = new BufferBuilder(memory, PrimitiveTopology.QUADS, DynamicBlockGeometry.FORMAT);
      for (int corner = 0; corner < 4; corner++) {
        builder.addVertex(
            corner & 1,
            corner >> 1,
            0,
            0xff123456,
            corner & 1,
            corner >> 1,
            0,
            0x00f000f0,
            -0.6f,
            0.8f,
            0);
      }
      try (var mesh = builder.buildOrThrow()) {
        var vertices = mesh.vertexBuffer();
        if (vertices.remaining() != 4 * DynamicBlockGeometry.STRIDE)
          throw new AssertionError("Vanilla mesher used the wrong stride");
        for (int corner = 0; corner < 4; corner++) {
          int offset = corner * DynamicBlockGeometry.STRIDE + DynamicBlockGeometry.NORMAL_OFFSET;
          if (Math.abs(vertices.get(offset) / 127f + 0.6f) > 0.01f
              || Math.abs(vertices.get(offset + 1) / 127f - 0.8f) > 0.01f
              || vertices.get(offset + 2) != 0)
            throw new AssertionError("Vanilla mesher discarded the model normal");
        }
      }
    }
    TerrainShaderGeometry.configure(state -> -1);
    try {
      var extended = DynamicBlockGeometry.pipeline(RenderPipelines.SOLID_BLOCK);
      if (extended == RenderPipelines.SOLID_BLOCK
          || extended.getVertexFormatBinding(0) != DynamicBlockGeometry.FORMAT
          || extended != DynamicBlockGeometry.pipeline(RenderPipelines.SOLID_BLOCK))
        throw new AssertionError("Dynamic block pipelines must retain cached layout identity");
      if (DynamicBlockGeometry.pipeline(RenderPipelines.ENTITY_SOLID)
          != RenderPipelines.ENTITY_SOLID)
        throw new AssertionError("Entity geometry must keep its existing normal stream");
      var oit = DynamicBlockGeometry.pipelines(RenderPipelines.OIT_TRANSLUCENT_BLOCK);
      for (var pipeline :
          java.util.List.of(
              oit.depthBoundsPipeline(), oit.transmittancePipeline(), oit.accumulatePipeline()))
        if (pipeline.getVertexFormatBinding(0) != DynamicBlockGeometry.FORMAT)
          throw new AssertionError("OIT draw and dynamic block mesher formats differ");
    } finally {
      TerrainShaderGeometry.disable();
    }
    if (DynamicBlockGeometry.pipeline(RenderPipelines.SOLID_BLOCK) != RenderPipelines.SOLID_BLOCK)
      throw new AssertionError("Disabled shader packs must retain vanilla format");
    System.out.println(
        "PASS: vanilla dynamic block mesher retains actual normals and consistent draw formats");
  }
}
