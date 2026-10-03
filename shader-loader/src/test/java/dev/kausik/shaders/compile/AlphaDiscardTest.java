package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteOrder;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/**
 * Verifies rejected geometry cannot write color or depth, including early-return pack fragments.
 */
public final class AlphaDiscardTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var translator = new ShaderCompatibilityCompiler()) {
      for (AlphaTestPolicy.Function function : AlphaTestPolicy.Function.values()) {
        var policy = new AlphaTestPolicy(function, .5f);
        var translated =
            translator.translate(
                "#version 120\nvoid main(){gl_Position=gl_Vertex;gl_Position.z=0.0;}",
                "#version 120\nvoid main(){float x=gl_FragCoord.x;"
                    + "if(x<1.0){gl_FragColor=vec4(1,0,0,0);return;}"
                    + "float a=x<2.0?0.25:x<3.0?0.5:1.0;gl_FragColor=vec4(1,0,0,a);}",
                "alpha_" + function.name().toLowerCase(),
                MinecraftVertexAdapter.skyBackground(),
                policy);
        verify(device, translated, policy);
      }
      System.out.println(
          "PASS: every alpha comparator preserves color/depth rejection and early-return fragment"
              + " semantics");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(
      FrontendGpuDevice device, TranslatedProgram program, AlphaTestPolicy policy) {
    var builder =
        RenderPipeline.builder()
            .withLocation("shader_test/" + program.label())
            .withVertexShader("shader_test/" + program.label())
            .withFragmentShader("shader_test/" + program.label())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0, 0));
    if (policy.enabled())
      builder.withBindGroupLayout(
          BindGroupLayout.builder()
              .withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER)
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
    try (var pipeline =
            device.compilePipeline(builder.build(), source, Runnable::run).join().finishCompile();
        var color = new PackTexture(device, "alpha color", GpuFormat.RGBA8_UNORM, 4, 1, 1);
        var depth = new PackTexture(device, "alpha depth", GpuFormat.D32_FLOAT, 4, 1, 1);
        var uniform =
            device.createBuffer(
                () -> "alpha uniform",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                Math.max(16, program.uniforms().byteSize()));
        var readback =
            device.createBuffer(
                () -> "alpha readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 32)) {
      var encoder = device.createCommandEncoder();
      encoder.clearColorTexture(color.texture, new Vector4f(0, 1, 0, 1));
      encoder.clearDepthTexture(depth.texture, 1);
      if (policy.enabled()) {
        var values = program.uniforms().allocate();
        program.uniforms().putFloats(values, "alphaTestRef", policy.reference());
        encoder.writeToBuffer(uniform.slice(), values);
      }
      try (var pass =
          encoder.createRenderPass(
              RenderPassDescriptor.builder(() -> "alpha geometry")
                  .withColorAttachment(color.level(0))
                  .withDepthAttachment(depth.level(0))
                  .build())) {
        pass.setPipeline(pipeline);
        if (policy.enabled()) pass.setUniform(UniformLayout.BLOCK_NAME, uniform);
        pass.draw(3, 1, 0, 0);
      }
      encoder.copyTextureToBuffer(color.texture, readback, 0, () -> {}, 0);
      encoder.copyTextureToBuffer(depth.texture, readback, 16, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L)) throw new AssertionError("Alpha GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        var pixels = mapped.data().order(ByteOrder.nativeOrder());
        float[] alpha = {0, .25f, .5f, 1};
        for (int x = 0; x < 4; x++) {
          boolean expected =
              switch (policy.function()) {
                case NEVER -> false;
                case ALWAYS -> true;
                case LESS -> alpha[x] < .5f;
                case LEQUAL -> alpha[x] <= .5f;
                case EQUAL -> alpha[x] == .5f;
                case NOTEQUAL -> alpha[x] != .5f;
                case GREATER -> alpha[x] > .5f;
                case GEQUAL -> alpha[x] >= .5f;
              };
          if (Byte.toUnsignedInt(pixels.get(x * 4)) != (expected ? 255 : 0)
              || Byte.toUnsignedInt(pixels.get(x * 4 + 1)) != (expected ? 0 : 255)
              || pixels.getFloat(16 + x * 4) != (expected ? .5f : 1))
            throw new AssertionError(
                "Alpha color/depth mismatch " + policy.function() + " at alpha=" + alpha[x]);
        }
      }
    }
  }
}
