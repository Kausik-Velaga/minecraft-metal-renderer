package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.pack.ShaderDirectives;
import dev.kausik.shaders.runtime.PackPipeline;
import dev.kausik.shaders.runtime.PackPrograms;
import dev.kausik.shaders.runtime.PackRenderTargets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Exercises the real vanilla water-mask layout and pack pipeline with depth as its sole attachment.
 */
public final class WaterMaskTest {
  public static void main(String[] args) throws Exception {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var compiler = new ShaderCompatibilityCompiler();
        var targets = new PackRenderTargets(device, Map.of(), 4, 4, 4)) {
      String vertex = "#version 120\nvoid main(){gl_Position=ftransform();}";
      // A pack's color-fragment rejection must not erase the host's boat depth mask.
      String fragment = "#version 120\nvoid main(){gl_FragColor=vec4(1,0,0,1);discard;}";
      var geometry =
          compiler.translate(
              vertex,
              fragment,
              "water_mask_gpu",
              MinecraftVertexAdapter.adapter(RenderPipelines.WATER_MASK, false));
      var translated = MinecraftVertexAdapter.waterMaskProgram(geometry);
      var source =
          new PackPrograms.Source(
              "gbuffers_basic", vertex, fragment, geometry, ShaderDirectives.parse(""));
      try (var pipeline =
              PackPipeline.compile(
                  device,
                  source,
                  translated,
                  RenderPipelines.WATER_MASK,
                  targets,
                  false,
                  false,
                  0,
                  11,
                  0);
          var vertices =
              device.createBuffer(
                  () -> "Water mask vertices",
                  GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                  36);
          var transforms =
              device.createBuffer(
                  () -> "Water mask transforms",
                  GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                  160);
          var projection =
              device.createBuffer(
                  () -> "Water mask projection",
                  GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                  64);
          var readback =
              device.createBuffer(
                  () -> "Water mask readback",
                  GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                  128)) {
        var descriptor =
            targets.descriptor(
                "Water mask depth only", translated.drawBuffers(), false, false, pipeline.depth());
        if (!descriptor.colorAttachments().isEmpty()
            || !translated.drawBuffers().isEmpty()
            || !pipeline.depth())
          throw new AssertionError("Water mask must have depth and zero color attachments");
        var encoder = device.createCommandEncoder();
        encoder.clearColorTexture(targets.color(0).texture, new Vector4f(0, 1, 0, 1));
        encoder.clearDepthTexture(targets.depth(0).texture, 1);
        ByteBuffer positions = buffer(36);
        for (float value : new float[] {-1, -1, .75f, 3, -1, .75f, -1, 3, .75f})
          positions.putFloat(value);
        encoder.writeToBuffer(vertices.slice(), positions.flip());
        ByteBuffer dynamic = buffer(160);
        new Matrix4f().get(0, dynamic);
        new Matrix4f().get(64, dynamic);
        for (int i = 0; i < 4; i++) dynamic.putFloat(128 + i * 4, 1);
        encoder.writeToBuffer(transforms.slice(), dynamic);
        ByteBuffer proj = buffer(64);
        new Matrix4f().get(0, proj);
        encoder.writeToBuffer(projection.slice(), proj);
        try (var pass = encoder.createRenderPass(descriptor)) {
          pass.setPipeline(pipeline.compiled());
          pass.setUniform("DynamicTransforms", transforms);
          pass.setUniform("Projection", projection);
          pass.setVertexBuffer(0, vertices.slice());
          pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(targets.color(0).texture, readback, 0, () -> {}, 0);
        encoder.copyTextureToBuffer(targets.depth(0).texture, readback, 64, () -> {}, 0);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(5_000_000_000L))
            throw new AssertionError("Water mask GPU timeout");
        }
        try (var mapped = readback.map(true, false)) {
          var pixels = mapped.data().order(ByteOrder.nativeOrder());
          for (int pixel = 0; pixel < 16; pixel++) {
            if (pixels.get(pixel * 4) != 0
                || Byte.toUnsignedInt(pixels.get(pixel * 4 + 1)) != 255
                || pixels.get(pixel * 4 + 2) != 0
                || Byte.toUnsignedInt(pixels.get(pixel * 4 + 3)) != 255)
              throw new AssertionError("Water mask changed scene color");
            if (Math.abs(pixels.getFloat(64 + pixel * 4) - .25f) > 1e-6)
              throw new AssertionError("Water mask failed to preserve conventional scene depth");
          }
        }
      }
      System.out.println(
          "PASS: actual vanilla boat mask writes pack depth while preserving every scene color"
              + " pixel");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
