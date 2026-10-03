package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/**
 * Checks geometry projection, fragment coordinates and screen texture sampling in one GPU chain.
 */
public final class PackCoordinatesTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var translator = new ShaderCompatibilityCompiler()) {
      var geometry =
          translator.translate(
              "#version 120\n"
                  + "varying vec2 position; void"
                  + " main(){gl_Position=gl_Vertex;position=gl_Vertex.xy*0.5+0.5;}",
              "#version 120\n"
                  + "varying vec2 position; void"
                  + " main(){gl_FragColor=vec4(position,gl_FragCoord.y/4.0,1.0);}",
              "coordinate_geometry");
      var screen =
          translator.translate(
              "#version 120\n"
                  + "varying vec2 uv; void main(){gl_Position=gl_Vertex;uv=gl_MultiTexCoord0.xy;}",
              "#version 130\n"
                  + "varying vec2 uv; uniform sampler2D colortex0; void main(){vec4"
                  + " sampled=texture2D(colortex0,uv);vec4"
                  + " exact=texelFetch(colortex0,ivec2(gl_FragCoord.xy),0);"
                  + "gl_FragColor=vec4(sampled.rgb,float(all(lessThan(abs(sampled-exact),vec4(0.001)))));}",
              "coordinate_screen",
              ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
      try (var geometryPipeline = compile(device, geometry, true);
          var screenPipeline = compile(device, screen, false);
          var intermediate =
              new PackTexture(device, "coordinate intermediate", GpuFormat.RGBA8_UNORM, 4, 4, 1);
          var result =
              new PackTexture(device, "coordinate result", GpuFormat.RGBA8_UNORM, 4, 4, 1);
          var vertices =
              device.createBuffer(
                  () -> "coordinate geometry",
                  GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                  48);
          var readback =
              device.createBuffer(
                  () -> "coordinate readback",
                  GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                  128);
          var sampler =
              device.createSampler(
                  AddressMode.CLAMP_TO_EDGE,
                  AddressMode.CLAMP_TO_EDGE,
                  FilterMode.NEAREST,
                  FilterMode.NEAREST,
                  1,
                  OptionalDouble.of(0))) {
        ByteBuffer data = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        for (float value : new float[] {-1, -1, 0, 1, 3, -1, 0, 1, -1, 3, 0, 1})
          data.putFloat(value);
        data.flip();
        var encoder = device.createCommandEncoder();
        encoder.writeToBuffer(vertices.slice(), data);
        try (var pass =
            encoder.createRenderPass(
                () -> "coordinate geometry", intermediate.level(0), Optional.empty())) {
          pass.setPipeline(geometryPipeline);
          pass.setVertexBuffer(0, vertices.slice());
          pass.draw(3, 1, 0, 0);
        }
        try (var pass =
            encoder.createRenderPass(
                () -> "coordinate screen", result.level(0), Optional.empty())) {
          pass.setPipeline(screenPipeline);
          pass.setUniform("colortex0", intermediate.sampled, sampler);
          pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(intermediate.texture, readback, 0, () -> {}, 0);
        encoder.copyTextureToBuffer(result.texture, readback, 64, () -> {}, 0);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(5_000_000_000L))
            throw new AssertionError("Coordinate GPU timeout");
        }
        try (var mapped = readback.map(true, false)) {
          for (int stage = 0; stage < 2; stage++)
            for (int y = 0; y < 4; y++)
              for (int x = 0; x < 4; x++) {
                int[] expected = {
                  Math.round((x + .5f) / 4 * 255),
                  Math.round((y + .5f) / 4 * 255),
                  Math.round((y + .5f) / 4 * 255),
                  255
                };
                for (int channel = 0; channel < 4; channel++) {
                  int actual =
                      Byte.toUnsignedInt(mapped.data().get(stage * 64 + (y * 4 + x) * 4 + channel));
                  if (Math.abs(actual - expected[channel]) > 1)
                    throw new AssertionError(
                        "Pack coordinate mismatch at stage="
                            + stage
                            + ", x="
                            + x
                            + ", y="
                            + y
                            + ", channel="
                            + channel
                            + ": "
                            + actual);
                }
              }
        }
      }
      System.out.println(
          "PASS: geometry projection, gl_FragCoord and fullscreen texture UVs share one Y"
              + " convention");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static CompiledRenderPipeline compile(
      FrontendGpuDevice device, TranslatedProgram program, boolean geometry) {
    var pipeline =
        RenderPipeline.builder()
            .withLocation("shader_test/" + program.label())
            .withVertexShader("shader_test/" + program.label())
            .withFragmentShader("shader_test/" + program.label())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT);
    if (geometry)
      pipeline.withVertexBinding(
          0, VertexFormat.builder(0).addAttribute("sl_Vertex", GpuFormat.RGBA32_FLOAT).build());
    else
      pipeline.withBindGroupLayout(
          BindGroupLayout.builder()
              .withUniform("colortex0", UniformType.COMBINED_IMAGE_SAMPLER)
              .build());
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType stage) {
            return stage == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    return device.compilePipeline(pipeline.build(), source, Runnable::run).join().finishCompile();
  }
}
