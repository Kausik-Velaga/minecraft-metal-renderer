package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.metal.MetalGpuSampler;
import dev.kausik.shaders.compile.ShaderCompatibilityCompiler.ShadowComparison;
import dev.kausik.shaders.runtime.PackTexture;
import dev.kausik.shaders.runtime.PackTextures;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** Actual D32 rasterization plus manual/native PCF readback, including depth equality and edges. */
public final class HardwareShadowComparisonTest {
  private static final float[] COORDINATES = {
    -.4f, 0, .01f, .125f, .25f, .3333f, .375f, .5f, .625f, .75f, .875f, .99f, 1, 1.4f
  };
  private static final float[] REFERENCES = {-.1f, 0, .24999f, .25f, .25001f, .5f, .75f, 1, 1.1f};
  private static final float[] DEPTHS = {0, .25f, .75f, 1};
  private static final int COUNT = COORDINATES.length * COORDINATES.length * REFERENCES.length;
  private static final String VERTEX = "#version 120\nvoid main(){gl_Position=gl_Vertex;}";

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try {
      checkStrategy(device);
      var linear =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE,
              AddressMode.CLAMP_TO_EDGE,
              FilterMode.LINEAR,
              FilterMode.LINEAR,
              1,
              OptionalDouble.of(0));
      try (linear;
          var nearest =
              device.createSampler(
                  AddressMode.CLAMP_TO_EDGE,
                  AddressMode.CLAMP_TO_EDGE,
                  FilterMode.NEAREST,
                  FilterMode.NEAREST,
                  1,
                  OptionalDouble.of(0));
          var hardware = MetalGpuSampler.comparisonVariant(linear).orElseThrow();
          var depth =
              new PackTexture(device, "comparison D32 pattern", GpuFormat.D32_FLOAT, 4, 4, 1);
          var output =
              new PackTexture(device, "comparison output", GpuFormat.RGBA32_FLOAT, COUNT, 1, 1);
          var readback =
              device.createBuffer(
                  () -> "Comparison result",
                  GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                  COUNT * 16L * 2);
          var writer = compileDepthWriter(device)) {
        var encoder = device.createCommandEncoder();
        try (var pass =
            encoder.createRenderPass(
                RenderPassDescriptor.builder(() -> "D32 comparison pattern")
                    .withDepthAttachment(depth.level(0), OptionalDouble.of(1))
                    .build())) {
          pass.setPipeline(writer);
          pass.draw(3, 1, 0, 0);
        }
        int variant = 0;
        for (var mode : ShadowComparison.values()) {
          try (var translator = new ShaderCompatibilityCompiler(mode)) {
            var translated =
                translator.translate(
                    VERTEX,
                    fragment(),
                    "comparison_" + mode.name().toLowerCase(),
                    ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
            if (!translated.samplers().getFirst().comparison())
              throw new AssertionError("Comparison metadata was lost");
            if (mode == ShadowComparison.HARDWARE
                && translated.fragmentSource().contains("texelFetch"))
              throw new AssertionError("Hardware path retained emulated PCF");
            try (var pipeline = compileComparison(device, translated)) {
              try (var pass =
                  encoder.createRenderPass(
                      () -> "Compare " + mode, output.level(0), Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.setUniform(
                    "shadowtex0",
                    depth.sampled,
                    mode == ShadowComparison.HARDWARE ? hardware : nearest);
                pass.setUniform("shadowtex1", depth.sampled, nearest);
                pass.draw(3, 1, 0, 0);
              }
              encoder.copyTextureToBuffer(
                  output.texture, readback, variant++ * COUNT * 16L, () -> {}, 0);
            }
          }
        }
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(10_000_000_000L))
            throw new AssertionError("Comparison GPU timeout");
        }
        float maximumError = 0;
        try (var mapped = readback.map(true, false)) {
          var data = mapped.data().order(ByteOrder.nativeOrder());
          for (int i = 0; i < COUNT; i++) {
            float u = COORDINATES[i % COORDINATES.length];
            float v = COORDINATES[i / COORDINATES.length % COORDINATES.length];
            float reference = REFERENCES[i / (COORDINATES.length * COORDINATES.length)];
            float expected = compare(u, v, reference);
            float emulated = data.getFloat(i * 16);
            float nativeValue = data.getFloat((COUNT + i) * 16);
            close(emulated, expected, .00001f, "Emulated PCF", i);
            // Hardware filter weight precision is implementation-defined, not necessarily float32.
            close(nativeValue, expected, .005f, "Native PCF", i);
            maximumError = Math.max(maximumError, Math.abs(nativeValue - emulated));
            float raw = depthAt((int) Math.floor(u * 4), (int) Math.floor(v * 4));
            for (int offset : new int[] {i * 16, (COUNT + i) * 16}) {
              close(data.getFloat(offset + 4), raw, .000001f, "Ordinary depth sampler", i);
              close(
                  data.getFloat(offset + 8),
                  data.getFloat(offset),
                  .000001f,
                  "Sampler parameter overload",
                  i);
              close(data.getFloat(offset + 12), 1, 0, "Alpha", i);
            }
          }
        }
        hardware.close();
        if (linear.isClosed())
          throw new AssertionError("Closing comparison sampler closed ordinary sampler");
        System.out.println(
            "PASS: "
                + COUNT
                + " native/manual D32 comparisons, equality, clamped edges, fractional and"
                + " outside-range coordinates; ordinary depth samples unchanged; maximum PCF error "
                + maximumError);
      }
      if (!MetalGpuSampler.comparisonVariant(linear).isEmpty())
        throw new AssertionError("Closed sampler created a comparison variant");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void checkStrategy(FrontendGpuDevice device) {
    String name = "minecraftShaders.hardwareShadowComparison", saved = System.getProperty(name);
    try {
      System.setProperty(name, "true");
      if (PackTextures.shadowComparison(device) != ShadowComparison.HARDWARE)
        throw new AssertionError("Metal comparison capability absent");
      System.setProperty(name, "false");
      if (PackTextures.shadowComparison(device) != ShadowComparison.EMULATED)
        throw new AssertionError("Manual comparison override ignored");
    } finally {
      if (saved == null) System.clearProperty(name);
      else System.setProperty(name, saved);
    }
  }

  private static String fragment() {
    return "#version 120\nuniform sampler2DShadow shadowtex0; uniform sampler2D shadowtex1;\n"
        + array("coordinates", COORDINATES)
        + array("references", REFERENCES)
        + "float throughParameter(sampler2DShadow s, vec3 p){return texture(s,p);}\n"
        + "void main(){int i=int(gl_FragCoord.x);vec2 uv=vec2(coordinates[i%"
        + COORDINATES.length
        + "],coordinates[(i/"
        + COORDINATES.length
        + ")%"
        + COORDINATES.length
        + "]);"
        + "vec3 p=vec3(uv,references[i/"
        + (COORDINATES.length * COORDINATES.length)
        + "]);gl_FragColor=vec4(shadow2D(shadowtex0,p).x,texture2D(shadowtex1,uv).r,throughParameter(shadowtex0,p),1);}";
  }

  private static String array(String name, float[] values) {
    StringBuilder text =
        new StringBuilder(
            "const float " + name + "[" + values.length + "]=float[" + values.length + "](");
    for (int i = 0; i < values.length; i++) {
      if (i > 0) text.append(',');
      text.append(Float.toString(values[i]));
    }
    return text.append(");\n").toString();
  }

  private static CompiledRenderPipeline compileComparison(
      FrontendGpuDevice device, TranslatedProgram program) {
    return device
        .compilePipeline(
            RenderPipeline.builder()
                .withLocation("shader_test/" + program.label())
                .withVertexShader("shader_test/compare")
                .withFragmentShader("shader_test/compare")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withColorTargetState(
                    new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
                .withBindGroupLayout(
                    BindGroupLayout.builder()
                        .withUniform("shadowtex0", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("shadowtex1", UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .build(),
            source(program.vertexSource(), program.fragmentSource()),
            Runnable::run)
        .join()
        .finishCompile();
  }

  private static CompiledRenderPipeline compileDepthWriter(FrontendGpuDevice device) {
    return device
        .compilePipeline(
            RenderPipeline.builder()
                .withLocation("shader_test/comparison_depth")
                .withVertexShader("shader_test/depth")
                .withFragmentShader("shader_test/depth")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .build(),
            source(
                "#version 450\n"
                    + "void main(){vec2"
                    + " uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}",
                "#version 450\n"
                    + "const float depths[4]=float[4](0,.25,.75,1);void main(){ivec2"
                    + " pixel=ivec2(gl_FragCoord.xy);gl_FragDepth=depths[(pixel.x+pixel.y)%4];}"),
            Runnable::run)
        .join()
        .finishCompile();
  }

  private static ShaderSource source(String vertex, String fragment) {
    return new ShaderSource() {
      public String getShader(Identifier id, ShaderType type) {
        return type == ShaderType.VERTEX ? vertex : fragment;
      }

      public CachedIncludeSource getInclude(Identifier id) {
        return null;
      }

      public void close() {}
    };
  }

  private static float depthAt(int x, int y) {
    return DEPTHS[(Math.clamp(x, 0, 3) + Math.clamp(y, 0, 3)) % 4];
  }

  private static float compare(float u, float v, float reference) {
    float px = u * 4 - .5f, py = v * 4 - .5f;
    int x = (int) Math.floor(px), y = (int) Math.floor(py);
    float fx = px - x, fy = py - y;
    float a = reference <= depthAt(x, y) ? 1 : 0, b = reference <= depthAt(x + 1, y) ? 1 : 0;
    float c = reference <= depthAt(x, y + 1) ? 1 : 0,
        d = reference <= depthAt(x + 1, y + 1) ? 1 : 0;
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
  }

  private static void close(
      float actual, float expected, float tolerance, String label, int index) {
    if (!Float.isFinite(actual) || Math.abs(actual - expected) > tolerance)
      throw new AssertionError(label + " at " + index + ": " + actual + " expected " + expected);
  }
}
