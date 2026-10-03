package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Tests linear shadow comparison interpolation and edge clamping through real GPU readback. */
public final class ShadowComparisonTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var translator = new ShaderCompatibilityCompiler()) {
      var translated =
          translator.translate(
              "#version 120\n"
                  + "varying vec2 uv; void main(){uv=gl_MultiTexCoord0.xy;gl_Position=gl_Vertex;}",
              "#version 120\nvarying vec2 uv;uniform sampler2DShadow shadowtex0;\n"
                  + "void main(){gl_FragColor=vec4(shadow2D(shadowtex0,vec3(uv,0.5)).xxx,1.0);}",
              "shadow_compare",
              ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
      var pipeline =
          RenderPipeline.builder()
              .withLocation("shader_test/shadow_compare")
              .withVertexShader("shader_test/shadow_compare")
              .withFragmentShader("shader_test/shadow_compare")
              .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
              .withCull(false)
              .withColorTargetState(ColorTargetState.DEFAULT)
              .withBindGroupLayout(
                  BindGroupLayout.builder()
                      .withUniform("shadowtex0", UniformType.COMBINED_IMAGE_SAMPLER)
                      .build())
              .build();
      ShaderSource source =
          new ShaderSource() {
            public String getShader(Identifier id, ShaderType type) {
              return type == ShaderType.VERTEX
                  ? translated.vertexSource()
                  : translated.fragmentSource();
            }

            public CachedIncludeSource getInclude(Identifier id) {
              return null;
            }

            public void close() {}
          };
      try (var compiled =
              device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
          var sampled =
              new PackTexture(device, "shadow comparison pattern", GpuFormat.R32_FLOAT, 2, 2, 1);
          var target =
              new PackTexture(device, "shadow comparison result", GpuFormat.RGBA8_UNORM, 4, 4, 1);
          var sampler =
              device.createSampler(
                  AddressMode.CLAMP_TO_EDGE,
                  AddressMode.CLAMP_TO_EDGE,
                  FilterMode.NEAREST,
                  FilterMode.NEAREST,
                  1,
                  OptionalDouble.of(0));
          var readback =
              device.createBuffer(
                  () -> "Shadow comparison readback",
                  GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                  64)) {
        ByteBuffer depths = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        depths.putFloat(0.25f).putFloat(0.75f).putFloat(0.75f).putFloat(0.25f).flip();
        var encoder = device.createCommandEncoder();
        encoder.writeToTexture(sampled.texture, depths, 0, 0, 0, 0, 2, 2);
        try (var pass =
            encoder.createRenderPass(
                () -> "Shadow comparison fixture", target.level(0), Optional.empty())) {
          pass.setPipeline(compiled);
          pass.setUniform("shadowtex0", sampled.sampled, sampler);
          pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(target.texture, readback, 0, () -> {}, 0);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(5_000_000_000L))
            throw new AssertionError("Shadow comparison GPU timeout");
        }
        float[][] expected = {
          {0, .25f, .75f, 1},
          {.25f, .375f, .625f, .75f},
          {.75f, .625f, .375f, .25f},
          {1, .75f, .25f, 0}
        };
        try (var mapped = readback.map(true, false)) {
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int value = Byte.toUnsignedInt(mapped.data().get((y * 4 + x) * 4));
              if (Math.abs(value - Math.round(expected[y][x] * 255)) > 1)
                throw new AssertionError("Shadow comparison " + x + "," + y + " = " + value);
            }
        }
      }
      System.out.println(
          "PASS: linear shadow comparisons interpolate four depth comparisons and clamp edges"
              + " correctly");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }
}
