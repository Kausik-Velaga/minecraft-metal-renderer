package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Mixed live/helper quads must preserve explicit derivatives, implicit LOD, color and depth. */
public final class EarlyAlphaDemotionGpuTest {
  private static final int WIDTH = 32, HEIGHT = 8, PIXELS = WIDTH * HEIGHT;
  private static final String VERTEX =
      "#version 120\nvarying vec2 uv;"
          + "void main(){gl_Position=gl_Vertex;gl_Position.z=0.0;uv=gl_Vertex.xy*0.5+0.5;}";
  private static final String FRAGMENT =
      "#version 120\n"
          + "varying vec2 uv;uniform sampler2D atlas,detail;void lighting(inout vec3 rgb){vec2"
          + " coord=uv*7.0+rgb.xy*0.25;vec3"
          + " sampled=texture2D(detail,coord).rgb;rgb=sampled+vec3(dFdx(coord.x)*0.1,dFdy(coord.y)*0.1,fwidth(coord.x)*0.05);}\n"
          + "void main(){vec4"
          + " pigment=texture2D(atlas,uv);lighting(pigment.rgb);gl_FragData[0]=pigment;}";

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var compiler = new ShaderCompatibilityCompiler()) {
      for (var function : AlphaTestPolicy.Function.values()) {
        var policy = new AlphaTestPolicy(function, .5f);
        var original =
            compiler.translate(
                VERTEX,
                FRAGMENT,
                "alpha_late_" + function,
                MinecraftVertexAdapter.skyBackground(),
                policy);
        var early =
            compiler.translate(
                VERTEX,
                FRAGMENT,
                "alpha_early_" + function,
                MinecraftVertexAdapter.skyBackground(),
                policy,
                true);
        if (early.fragmentSource().contains("demote;") != policy.enabled())
          throw new AssertionError("Demote fixture failed to activate: " + function);
        verify(device, original, early, policy);
      }
      System.out.println(
          "PASS: early alpha demotion matches late testing for all eight comparators,"
              + " transparent/opaque/mixed quads, threshold equality/NaN, explicit derivatives,"
              + " implicit mip LOD, color/depth");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(
      FrontendGpuDevice device,
      TranslatedProgram original,
      TranslatedProgram early,
      AlphaTestPolicy policy) {
    try (var latePipeline = compile(device, original, policy.enabled());
        var earlyPipeline = compile(device, early, policy.enabled());
        var atlas =
            new PackTexture(device, "alpha mixed quads", GpuFormat.RGBA32_FLOAT, WIDTH, HEIGHT, 1);
        var detail = new PackTexture(device, "alpha mip detail", GpuFormat.RGBA8_UNORM, 64, 64, 7);
        var lateColor =
            new PackTexture(device, "late alpha color", GpuFormat.RGBA32_FLOAT, WIDTH, HEIGHT, 1);
        var earlyColor =
            new PackTexture(device, "early alpha color", GpuFormat.RGBA32_FLOAT, WIDTH, HEIGHT, 1);
        var lateDepth =
            new PackTexture(device, "late alpha depth", GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1);
        var earlyDepth =
            new PackTexture(device, "early alpha depth", GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1);
        var uniforms =
            device.createBuffer(
                () -> "alpha demotion uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                Math.max(16, original.uniforms().byteSize()));
        var readback =
            device.createBuffer(
                () -> "alpha demotion readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                PIXELS * 40);
        var nearest =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0));
        var mipmapped =
            device.createSampler(
                AddressMode.REPEAT,
                AddressMode.REPEAT,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty())) {
      var encoder = device.createCommandEncoder();
      ByteBuffer pixels = buffer(PIXELS * 16);
      for (int y = 0; y < HEIGHT; y++)
        for (int x = 0; x < WIDTH; x++)
          pixels
              .putFloat((x * 13 % 31) / 31f)
              .putFloat((y * 5 % 7) / 7f)
              .putFloat(.25f)
              .putFloat(alpha(x, y));
      encoder.writeToTexture(atlas.texture, pixels.flip(), 0, 0, 0, 0, WIDTH, HEIGHT);
      for (int level = 0; level < 7; level++) {
        int side = 64 >> level;
        ByteBuffer mip = buffer(side * side * 4);
        for (int y = 0; y < side; y++)
          for (int x = 0; x < side; x++)
            mip.put((byte) (level * 32 + (x & 7)))
                .put((byte) (220 - level * 24 + (y & 7)))
                .put((byte) (20 + ((x + y) & 15)))
                .put((byte) 255);
        encoder.writeToTexture(detail.texture, mip.flip(), level, 0, 0, 0, side, side);
      }
      if (policy.enabled()) {
        var values = original.uniforms().allocate();
        original.uniforms().putFloats(values, "alphaTestRef", policy.reference());
        encoder.writeToBuffer(uniforms.slice(), values);
      }
      PackTexture[] colors = {lateColor, earlyColor}, depths = {lateDepth, earlyDepth};
      CompiledRenderPipeline[] pipelines = {latePipeline, earlyPipeline};
      for (int variant = 0; variant < 2; variant++) {
        encoder.clearColorTexture(colors[variant].texture, new Vector4f(-4, -5, -6, -7));
        encoder.clearDepthTexture(depths[variant].texture, 1);
        try (var pass =
            encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "alpha helper equivalence")
                    .withColorAttachment(colors[variant].level(0))
                    .withDepthAttachment(depths[variant].level(0))
                    .build())) {
          pass.setPipeline(pipelines[variant]);
          if (policy.enabled()) pass.setUniform(UniformLayout.BLOCK_NAME, uniforms);
          pass.setUniform("atlas", atlas.sampled, nearest);
          pass.setUniform("detail", detail.sampled, mipmapped);
          pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(
            colors[variant].texture, readback, variant * PIXELS * 20L, () -> {}, 0);
        encoder.copyTextureToBuffer(
            depths[variant].texture, readback, variant * PIXELS * 20L + PIXELS * 16L, () -> {}, 0);
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("Alpha demotion GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer result = mapped.data().order(ByteOrder.nativeOrder());
        for (int y = 0; y < HEIGHT; y++)
          for (int x = 0; x < WIDTH; x++) {
            int pixel = y * WIDTH + x;
            boolean keep = keep(policy.function(), alpha(x, y));
            for (int variant = 0; variant < 2; variant++) {
              int offset = variant * PIXELS * 20;
              same(
                  result.getFloat(offset + PIXELS * 16 + pixel * 4),
                  keep ? .5f : 1,
                  policy,
                  pixel,
                  "depth/" + variant);
              if (!keep)
                for (int c = 0; c < 4; c++)
                  same(
                      result.getFloat(offset + pixel * 16 + c * 4),
                      -4 - c,
                      policy,
                      pixel,
                      "discarded-color/" + c);
            }
            for (int c = 0; c < 4; c++)
              same(
                  result.getFloat(pixel * 16 + c * 4),
                  result.getFloat(PIXELS * 20 + pixel * 16 + c * 4),
                  policy,
                  pixel,
                  "derivative-color/" + c);
          }
      }
    }
  }

  private static float alpha(int x, int y) {
    if (x < 8) return 0;
    if (x < 16) return 1;
    if (x < 24) return ((x + y) & 1) == 0 ? 0 : 1;
    return switch (x & 3) {
      case 0 -> .25f;
      case 1 -> .5f;
      case 2 -> .75f;
      default -> Float.NaN;
    };
  }

  private static boolean keep(AlphaTestPolicy.Function function, float alpha) {
    return switch (function) {
      case NEVER -> false;
      case ALWAYS -> true;
      case LESS -> alpha < .5f;
      case LEQUAL -> alpha <= .5f;
      case EQUAL -> alpha == .5f;
      case NOTEQUAL -> alpha != .5f;
      case GREATER -> alpha > .5f;
      case GEQUAL -> alpha >= .5f;
    };
  }

  private static void same(float a, float b, AlphaTestPolicy policy, int pixel, String what) {
    if (Float.isNaN(a) && Float.isNaN(b)) return;
    if (!Float.isFinite(a) || !Float.isFinite(b) || Math.abs(a - b) > 0.00001f)
      throw new AssertionError(
          policy.function() + " " + what + " pixel=" + pixel + ": " + a + " != " + b);
  }

  private static ByteBuffer buffer(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }

  private static CompiledRenderPipeline compile(
      FrontendGpuDevice device, TranslatedProgram program, boolean alpha) {
    var bindings =
        BindGroupLayout.builder()
            .withUniform("atlas", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("detail", UniformType.COMBINED_IMAGE_SAMPLER);
    if (alpha) bindings.withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
    var pipeline =
        RenderPipeline.builder()
            .withLocation("shader_test/" + program.label().toLowerCase())
            .withVertexShader("shader_test/alpha_demotion")
            .withFragmentShader("shader_test/alpha_demotion")
            .withBindGroupLayout(bindings.build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(
                new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 0, 0))
            .build();
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
    return device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
  }
}
