package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
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
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.metal.MetalGpuSampler;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.runtime.PackPrograms;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Float readback of original fragment expressions and their flat vertex transport. */
public final class UniformInitializerLiftingGpuTest {
  private static final int WIDTH = 17, HEIGHT = 11, IMAGE_BYTES = WIDTH * HEIGHT * 16, CASES = 24;

  public static void main(String[] args) throws Exception {
    if (!ShadercFragmentOptimization.enabled())
      throw new IllegalArgumentException("Run with -DminecraftShaders.optimizeFragmentSpirv=true");
    if (Boolean.getBoolean("minecraftShaders.liftUniformInitializers"))
      throw new IllegalArgumentException(
          "The readback test needs an unmodified reference; leave automatic lifting off");
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    int pipelines = 0;
    List<Difference> differences = new ArrayList<>();
    try (var translator = new ShaderCompatibilityCompiler()) {
      var optimized = FragmentOptimizationSmokeCompiler.install(device);
      var fixture = UniformInitializerLiftingTest.fixture(translator);
      differences.addAll(
          verify(device, fixture, UniformInitializerLifting.apply(fixture).program(), "fixture"));
      pipelines += 2;
      if (args.length > 0) {
        var pack = ShaderPack.load(Path.of(args[0]));
        try (var programs =
            new PackPrograms(
                pack,
                Map.of(),
                "minecraft:overworld",
                ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
          for (String name : List.of("deferred1", "composite", "composite5")) {
            var original = programs.find(name).translated();
            var lifted = UniformInitializerLifting.apply(original);
            UniformInitializerLiftingTest.check(lifted.applied(), "No BSL transfer for " + name);
            differences.addAll(
                verify(
                    device,
                    observe(original, lifted.lifted()),
                    observe(lifted.program(), lifted.lifted()),
                    name));
            pipelines += 2;
          }
        }
      }
      optimized.assertActivated(pipelines);
      long maxUlp = differences.stream().mapToLong(value -> value.maxUlp).max().orElse(0);
      long strict = differences.stream().mapToLong(value -> value.strictFailures).sum();
      int allowedUlp = Integer.getInteger("minecraftShaders.uniformLiftMaxUlp", 0);
      System.out.println(
          "GPU lifting summary: cases="
              + CASES
              + ", maximum ULP="
              + maxUlp
              + ", strict nonfinite/sign mismatches="
              + strict
              + ", configured ULP bound="
              + allowedUlp);
      UniformInitializerLiftingTest.check(
          strict == 0 && maxUlp <= allowedUlp,
          "Transferred values exceed the configured bound; all cases and outputs were measured"
              + " above");
      System.out.println(
          "PASS: transferred values stay within the explicit "
              + allowedUlp
              + " ULP bound; this is bit-exact only when the reported maximum is zero");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  /**
   * Keep pack declarations/functions, but observe the transferred values instead of scene color.
   */
  private static TranslatedProgram observe(TranslatedProgram source, List<String> names) {
    String fragment =
        source
            .fragmentSource()
            .replaceAll(
                "layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*out\\s+vec4\\s+(sl_FragData\\d+)\\s*;",
                "vec4 $1;");
    var main = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{").matcher(fragment);
    UniformInitializerLiftingTest.check(main.find(), "Missing fragment entry");
    int start = main.start(), cursor = main.end(), depth = 1;
    for (; cursor < fragment.length() && depth > 0; cursor++) {
      if (fragment.charAt(cursor) == '{') depth++;
      else if (fragment.charAt(cursor) == '}') depth--;
    }
    StringBuilder declarations = new StringBuilder(), body = new StringBuilder("void main(){\n");
    for (int index = 0; index < names.size(); index++) {
      String name = names.get(index);
      var type =
          Pattern.compile("\\b(float|int|uint|[iu]?vec[234])\\s+" + Pattern.quote(name) + "\\s*=")
              .matcher(fragment);
      UniformInitializerLiftingTest.check(type.find(), "Missing transferred global " + name);
      String vector =
          switch (type.group(1)) {
            case "vec2", "ivec2", "uvec2" -> "vec4(vec2(" + name + "),0.,1.)";
            case "vec3", "ivec3", "uvec3" -> "vec4(vec3(" + name + "),1.)";
            case "vec4", "ivec4", "uvec4" -> "vec4(" + name + ")";
            default -> "vec4(float(" + name + "))";
          };
      declarations
          .append("layout(location=")
          .append(index)
          .append(") out vec4 proof")
          .append(index)
          .append(";\n");
      body.append("proof").append(index).append('=').append(vector).append(";\n");
    }
    String result =
        fragment.substring(0, start) + declarations + body + "}\n" + fragment.substring(cursor);
    return new TranslatedProgram(
        source.label() + "/observed",
        source.vertexSource(),
        result,
        source.uniforms(),
        source.samplers(),
        source.attributes(),
        java.util.stream.IntStream.range(0, names.size()).boxed().toList(),
        source.preprocessedVertex(),
        source.preprocessedFragment());
  }

  private static List<Difference> verify(
      FrontendGpuDevice device, TranslatedProgram original, TranslatedProgram lifted, String name) {
    int outputs = original.drawBuffers().size();
    List<Difference> differences = new ArrayList<>();
    for (int index = 0; index < outputs; index++) differences.add(new Difference(name, index));
    List<PackTexture> colors = new ArrayList<>();
    try (var reference = pipeline(device, original);
        var candidate = pipeline(device, lifted);
        var dummyColor =
            new PackTexture(device, "unused initializer sampler", GpuFormat.RGBA32_FLOAT, 1, 1, 1);
        var dummyDepth =
            new PackTexture(
                device, "unused initializer shadow sampler", GpuFormat.D32_FLOAT, 1, 1, 1);
        var nearest =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0));
        var comparison = MetalGpuSampler.comparisonVariant(nearest).orElseThrow();
        var uniform =
            device.createBuffer(
                () -> "lifted initializer uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                original.uniforms().byteSize());
        var readback =
            device.createBuffer(
                () -> "lifted initializer readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) IMAGE_BYTES * outputs * CASES * 2)) {
      for (int index = 0; index < outputs; index++)
        colors.add(
            new PackTexture(
                device, "initializer value " + index, GpuFormat.RGBA32_FLOAT, WIDTH, HEIGHT, 1));
      var encoder = device.createCommandEncoder();
      encoder.clearColorTexture(dummyColor.texture, new Vector4f(.25f, .5f, .75f, 1));
      encoder.clearDepthTexture(dummyDepth.texture, .5);
      for (int fixture = 0; fixture < CASES; fixture++) {
        encoder.writeToBuffer(uniform.slice(), uniforms(original.uniforms(), fixture));
        for (int variant = 0; variant < 2; variant++) {
          var descriptor = RenderPassDescriptor.builder(() -> "uniform initializer equality");
          for (var color : colors)
            descriptor.withColorAttachment(color.level(0), Optional.of(new Vector4f(-1)));
          try (var pass = encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(variant == 0 ? reference : candidate);
            pass.setUniform(UniformLayout.BLOCK_NAME, uniform);
            for (var sampler : original.samplers())
              pass.setUniform(
                  ShaderCompatibilityCompiler.samplerShaderName(sampler.name()),
                  sampler.comparison() ? dummyDepth.sampled : dummyColor.sampled,
                  sampler.comparison() ? comparison : nearest);
            pass.draw(3, 1, 0, 0);
          }
          for (int index = 0; index < outputs; index++)
            encoder.copyTextureToBuffer(
                colors.get(index).texture,
                readback,
                (long) ((fixture * 2 + variant) * outputs + index) * IMAGE_BYTES,
                () -> {},
                0);
        }
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        UniformInitializerLiftingTest.check(
            fence.awaitCompletion(10_000_000_000L), "Uniform initializer GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        var bytes = mapped.data().order(ByteOrder.nativeOrder());
        for (int fixture = 0; fixture < CASES; fixture++) {
          int a = fixture * 2 * outputs * IMAGE_BYTES, b = a + outputs * IMAGE_BYTES;
          for (int offset = 0; offset < outputs * IMAGE_BYTES; offset += 4) {
            int expected = bytes.getInt(a + offset), actual = bytes.getInt(b + offset);
            differences.get(offset / IMAGE_BYTES).add(expected, actual, fixture);
          }
        }
      }
      differences.forEach(value -> System.out.println(value));
      return differences;
    } finally {
      colors.forEach(PackTexture::close);
    }
  }

  private static final class Difference {
    final String name;
    final int output;
    long samples, changed, strictFailures, maxUlp;
    double maxAbsolute, maxRelative;
    int worstCase;

    Difference(String name, int output) {
      this.name = name;
      this.output = output;
    }

    void add(int expectedBits, int actualBits, int fixture) {
      samples++;
      if (expectedBits == actualBits) return;
      changed++;
      float expected = Float.intBitsToFloat(expectedBits),
          actual = Float.intBitsToFloat(actualBits);
      if (!Float.isFinite(expected) || !Float.isFinite(actual) || (expectedBits ^ actualBits) < 0) {
        strictFailures++;
        return;
      }
      long ulp = Math.abs((long) expectedBits - actualBits);
      double absolute = Math.abs((double) expected - actual);
      double relative = absolute / Math.max(Float.MIN_NORMAL, Math.abs((double) expected));
      if (ulp > maxUlp) {
        maxUlp = ulp;
        worstCase = fixture;
      }
      maxAbsolute = Math.max(maxAbsolute, absolute);
      maxRelative = Math.max(maxRelative, relative);
    }

    @Override
    public String toString() {
      return "GPU lifting "
          + name
          + " output="
          + output
          + " changed="
          + changed
          + "/"
          + samples
          + " maxULP="
          + maxUlp
          + " maxAbs="
          + maxAbsolute
          + " maxRelative="
          + maxRelative
          + " worstCase="
          + worstCase
          + " strictFailures="
          + strictFailures;
    }
  }

  private static ByteBuffer uniforms(UniformLayout layout, int fixture) {
    var bytes = layout.allocate();
    for (var field : layout.fields()) {
      int width =
          field.type().matches(".*vec[234]")
              ? Integer.parseInt(field.type().substring(field.type().length() - 1))
              : field.type().startsWith("mat")
                  ? (int) Math.pow(Integer.parseInt(field.type().substring(3)), 2)
                  : 1;
      int count = width * Math.max(1, field.arrayLength());
      if (field.type().equals("float")
          || field.type().startsWith("vec")
          || field.type().startsWith("mat")) {
        float[] values = new float[count];
        if (field.type().startsWith("mat")) {
          int side = (int) Math.sqrt(width);
          for (int index = 0; index < count; index++)
            if (index % width % (side + 1) == 0) values[index] = 1;
        } else
          for (int index = 0; index < count; index++)
            values[index] = ((fixture + index * 3) % 17) / 16f;
        if (field.name().equals("viewWidth")) values[0] = WIDTH;
        if (field.name().equals("viewHeight")) values[0] = HEIGHT;
        if (field.name().equals("aspectRatio")) values[0] = (float) WIDTH / HEIGHT;
        if (field.name().equals("far")) values[0] = 512;
        if (field.name().equals("near")) values[0] = .05f;
        layout.putFloats(bytes, field.name(), values);
      } else {
        int[] values = new int[count];
        if (field.name().equals("moonPhase")) values[0] = fixture % 8;
        if (field.name().equals("index")) values[0] = fixture % 3;
        if (field.name().equals("eyeBrightnessSmooth"))
          java.util.Arrays.fill(values, (fixture * 11) % 241);
        layout.putInts(bytes, field.name(), values);
      }
    }
    return bytes;
  }

  private static CompiledRenderPipeline pipeline(
      FrontendGpuDevice device, TranslatedProgram source) {
    List<BindGroupLayout.UniformDescription> bindings = new ArrayList<>();
    bindings.add(
        new BindGroupLayout.UniformDescription(
            UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER));
    for (var sampler : source.samplers())
      bindings.add(
          new BindGroupLayout.UniformDescription(
              ShaderCompatibilityCompiler.samplerShaderName(sampler.name()),
              UniformType.COMBINED_IMAGE_SAMPLER));
    var builder =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "minecraft_shader_loader", source.label().replaceAll("[^a-z0-9/._-]", "_")))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withBindGroupLayout(new BindGroupLayout(bindings));
    for (int index = 0; index < source.drawBuffers().size(); index++)
      builder.withColorTargetState(
          index,
          new ColorTargetState(
              Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
    var shader =
        new ShaderSource() {
          @Override
          public String getShader(Identifier identifier, ShaderType type) {
            return type == ShaderType.VERTEX ? source.vertexSource() : source.fragmentSource();
          }

          @Override
          public CachedIncludeSource getInclude(Identifier identifier) {
            return null;
          }

          @Override
          public void close() {}
        };
    var compiled =
        device.compilePipeline(builder.build(), shader, Runnable::run).join().finishCompile();
    UniformInitializerLiftingTest.check(compiled != null, "Cannot compile initializer pipeline");
    return compiled;
  }
}
