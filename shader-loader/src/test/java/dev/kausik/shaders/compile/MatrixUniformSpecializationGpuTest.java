package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.runtime.PackPrograms;
import dev.kausik.shaders.runtime.PackTexture;
import dev.kausik.shaders.runtime.PackTextures;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Exercises the unchanged supplied AO algorithm against D32 depth and its actual blue-noise image.
 */
public final class MatrixUniformSpecializationGpuTest {
  private static final int WIDTH = 64, HEIGHT = 48, CASES = 24;

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException(
          "Supply the local BSL ZIP; no pack algorithms are embedded in this test");
    if (!ShadercFragmentOptimization.enabled())
      throw new IllegalArgumentException("Enable minecraftShaders.optimizeFragmentSpirv");
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try {
      var optimizer = FragmentOptimizationSmokeCompiler.install(device);
      var pack = ShaderPack.load(Path.of(args[0]));
      try (var programs =
              new PackPrograms(
                  pack,
                  Map.of(),
                  "minecraft:overworld",
                  ShaderCompatibilityCompiler.ShadowComparison.HARDWARE);
          var textures =
              new PackTextures(
                  device,
                  pack,
                  programs.properties,
                  ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
        var original = programs.find("deferred").translated();
        var result =
            MatrixUniformSpecialization.apply(
                original, MatrixUniformSpecialization.PROJECTION_INVERSE);
        MatrixUniformSpecializationTest.check(result.applied(), result.reason());
        boolean floating =
            verify(device, original, result.program(), textures, GpuFormat.RGBA32_FLOAT, 16);
        boolean stored =
            verify(device, original, result.program(), textures, GpuFormat.R8_UNORM, 1);
        MatrixUniformSpecializationTest.check(
            floating && stored,
            "AO specialization exceeded explicit equivalence bounds; both formats were measured"
                + " above");
      }
      optimizer.assertActivated(4);
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static boolean verify(
      FrontendGpuDevice device,
      TranslatedProgram original,
      TranslatedProgram candidate,
      PackTextures textures,
      GpuFormat format,
      int pixelBytes) {
    int imageBytes = WIDTH * HEIGHT * pixelBytes;
    try (var dense = pipeline(device, original, format);
        var sparse = pipeline(device, candidate, format);
        var depth =
            new PackTexture(device, "AO input depth", GpuFormat.D32_FLOAT, WIDTH, HEIGHT, 1);
        var output = new PackTexture(device, "AO equivalence", format, WIDTH, HEIGHT, 1);
        var uniform =
            device.createBuffer(
                () -> "AO projection uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                original.uniforms().byteSize());
        var readback =
            device.createBuffer(
                () -> "AO matrix readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) imageBytes * 2 * CASES)) {
      var encoder = device.createCommandEncoder();
      int sparseCases = 0, fallbackCases = 0;
      for (int fixture = 0; fixture < CASES; fixture++) {
        float fov = 30 + (fixture % 9) * 10;
        Matrix4f nativeProjection =
            new Matrix4f()
                .perspective((float) Math.toRadians(fov), (float) WIDTH / HEIGHT, 1024, .05f, true);
        if (fixture % 8 == 7 && fixture >= 16) nativeProjection.m20(.017f);
        Matrix4f projection = new Matrix4f().m22(-2).m32(1).mul(nativeProjection);
        float[] inverse = new Matrix4f(projection).invert().get(new float[16]);
        boolean matches = MatrixUniformSpecialization.PROJECTION_INVERSE.matches(inverse);
        if (matches) sparseCases++;
        else fallbackCases++;
        encoder.writeToTexture(
            depth.texture, depths(projection, fixture), 0, 0, 0, 0, WIDTH, HEIGHT);
        encoder.writeToBuffer(
            uniform.slice(), uniforms(original.uniforms(), projection, inverse, fixture));
        for (int variant = 0; variant < 2; variant++) {
          try (var pass =
              encoder.createRenderPass(
                  RenderPassDescriptor.builder(() -> "AO matrix equivalence")
                      .withColorAttachment(output.level(0), Optional.of(new Vector4f(-1)))
                      .build())) {
            pass.setPipeline(variant == 1 && matches ? sparse : dense);
            pass.setUniform(UniformLayout.BLOCK_NAME, uniform);
            for (var sampler : original.samplers()) {
              boolean isDepth = sampler.name().equals("depthtex0");
              MatrixUniformSpecializationTest.check(
                  isDepth || sampler.name().equals("noisetex"),
                  "Unexpected AO input " + sampler.name());
              pass.setUniform(
                  ShaderCompatibilityCompiler.samplerShaderName(sampler.name()),
                  isDepth ? depth.sampled : textures.noise.sampled,
                  isDepth ? textures.nearest : textures.repeat);
            }
            pass.draw(3, 1, 0, 0);
          }
          encoder.copyTextureToBuffer(
              output.texture, readback, (long) (fixture * 2 + variant) * imageBytes, () -> {}, 0);
        }
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        MatrixUniformSpecializationTest.check(
            fence.awaitCompletion(10_000_000_000L), "AO matrix GPU timeout");
      }
      long changed = 0, strict = 0, maxUlp = 0;
      double maxAbsolute = 0;
      try (var mapping = readback.map(true, false)) {
        var bytes = mapping.data().order(ByteOrder.nativeOrder());
        for (int fixture = 0; fixture < CASES; fixture++) {
          int before = fixture * 2 * imageBytes, after = before + imageBytes;
          for (int offset = 0; offset < imageBytes; offset += pixelBytes == 1 ? 1 : 4) {
            if (pixelBytes == 1) {
              if (bytes.get(before + offset) != bytes.get(after + offset)) changed++;
              continue;
            }
            int a = bytes.getInt(before + offset), b = bytes.getInt(after + offset);
            if (a == b) continue;
            float x = Float.intBitsToFloat(a), y = Float.intBitsToFloat(b);
            if (Float.isNaN(x) && Float.isNaN(y)) continue;
            changed++;
            if (!Float.isFinite(x) || !Float.isFinite(y) || (a ^ b) < 0) strict++;
            else {
              maxUlp = Math.max(maxUlp, Math.abs((long) a - b));
              maxAbsolute = Math.max(maxAbsolute, Math.abs((double) x - y));
            }
          }
        }
      }
      System.out.println(
          "Actual BSL AO sparse projection "
              + format
              + ": sparseCases="
              + sparseCases
              + ", denseFallbackCases="
              + fallbackCases
              + ", changed="
              + changed
              + ", maxULP="
              + maxUlp
              + ", maxAbs="
              + maxAbsolute
              + ", strictFailures="
              + strict);
      MatrixUniformSpecializationTest.check(
          sparseCases > 0 && fallbackCases > 0, "Variant guard not exercised");
      int allowed = Integer.getInteger("minecraftShaders.sparseProjectionMaxUlp", 0);
      return strict == 0 && (pixelBytes == 1 ? changed == 0 : maxUlp <= allowed);
    }
  }

  private static ByteBuffer depths(Matrix4f projection, int fixture) {
    var bytes = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder());
    for (int y = 0; y < HEIGHT; y++)
      for (int x = 0; x < WIDTH; x++) {
        float distance =
            switch (fixture % 8) {
              case 0 -> 8;
              case 1 -> 2 + x * .08f + y * .04f;
              case 2 -> x < WIDTH / 2 ? 2 : 20;
              case 3 -> 100 + x * 12;
              case 4 -> .06f + (x % 5) * .006f;
              case 5 -> 4;
              case 6 -> .1f + Math.floorMod(x * 3121 + y * 7919, 5000) * .02f;
              default -> (x & 3) == 0 ? 2 : 25;
            };
        float z =
            (projection.m22() * -distance + projection.m32())
                    / (projection.m23() * -distance + projection.m33())
                    * .5f
                + .5f;
        if (fixture % 8 == 5 && (x + y) % 7 != 0 || x == 0 || y == HEIGHT - 1) z = 1;
        bytes.putFloat(Math.clamp(z, 0, 1));
      }
    return bytes.flip();
  }

  private static ByteBuffer uniforms(
      UniformLayout layout, Matrix4f projection, float[] inverse, int fixture) {
    var bytes = layout.allocate();
    for (var field : layout.fields()) {
      if (field.type().equals("mat4"))
        layout.putFloats(
            bytes,
            field.name(),
            switch (field.name()) {
              case "gbufferProjection" -> projection.get(new float[16]);
              case "gbufferProjectionInverse" -> inverse;
              default -> new Matrix4f().get(new float[16]);
            });
      else if (field.type().equals("int")) layout.putInts(bytes, field.name(), fixture * 13);
      else
        layout.putFloats(
            bytes,
            field.name(),
            switch (field.name()) {
              case "viewWidth" -> WIDTH;
              case "viewHeight" -> HEIGHT;
              case "aspectRatio" -> (float) WIDTH / HEIGHT;
              case "near" -> .05f;
              case "far" -> 1024;
              default -> fixture * .125f;
            });
    }
    return bytes;
  }

  private static CompiledRenderPipeline pipeline(
      FrontendGpuDevice device, TranslatedProgram source, GpuFormat format) {
    var bindings = new ArrayList<BindGroupLayout.UniformDescription>();
    bindings.add(
        new BindGroupLayout.UniformDescription(
            UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER));
    for (var sampler : source.samplers())
      bindings.add(
          new BindGroupLayout.UniformDescription(
              ShaderCompatibilityCompiler.samplerShaderName(sampler.name()),
              UniformType.COMBINED_IMAGE_SAMPLER));
    var description =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "minecraft_shader_loader",
                    source.label().replaceAll("[^a-z0-9/._-]", "_")
                        + "/"
                        + format.name().toLowerCase(java.util.Locale.ROOT)))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withBindGroupLayout(new BindGroupLayout(bindings))
            .withColorTargetState(
                0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
            .build();
    var compiled =
        device
            .compilePipeline(
                description,
                new ShaderSource() {
                  public String getShader(Identifier id, ShaderType type) {
                    return type == ShaderType.VERTEX
                        ? source.vertexSource()
                        : source.fragmentSource();
                  }

                  public CachedIncludeSource getInclude(Identifier id) {
                    return null;
                  }

                  public void close() {}
                },
                Runnable::run)
            .join()
            .finishCompile();
    MatrixUniformSpecializationTest.check(compiled != null, "AO shader compile failed");
    return compiled;
  }
}
