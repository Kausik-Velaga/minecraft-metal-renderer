package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
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
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackAlbedoSamplers;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;

/** GPU evidence that crisp atlas magnification retains minification and mip blending. */
public final class TerrainAlbedoSamplingTest {
  private static final String VERTEX =
      """
      #version 450
      layout(location=0) out vec2 uv;
      void main() {
        uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
        gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
      }
      """;

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var atlas = new PackTexture(device, "pixel-art atlas", GpuFormat.RGBA8_UNORM, 8, 8, 4);
        var entity = new PackTexture(device, "other albedo", GpuFormat.RGBA8_UNORM, 1, 1, 1);
        var supplied = sampler(device, FilterMode.LINEAR, 1, OptionalDouble.empty());
        var equivalent = sampler(device, FilterMode.LINEAR, 1, OptionalDouble.empty());
        var capped = sampler(device, FilterMode.LINEAR, 4, OptionalDouble.of(1));
        var alreadyNearest = sampler(device, FilterMode.NEAREST, 1, OptionalDouble.empty());
        var variants = new PackAlbedoSamplers(device);
        var implicit = compile(device, false);
        var fractional = compile(device, true)) {
      GpuSampler sharp = variants.forTexture(atlas.sampled, atlas.texture, supplied);
      GpuSampler sharpCapped = variants.forTexture(atlas.sampled, atlas.texture, capped);
      assertSettings(supplied, sharp);
      assertSettings(capped, sharpCapped);
      if (variants.forTexture(atlas.level(0), atlas.texture, equivalent) != sharp)
        throw new AssertionError(
            "Equivalent sampler settings or atlas views did not reuse a variant");
      if (variants.forTexture(entity.sampled, atlas.texture, supplied) != supplied)
        throw new AssertionError("Non-atlas entity texture filtering was changed");
      if (variants.forTexture(atlas.sampled, atlas.texture, alreadyNearest) != alreadyNearest)
        throw new AssertionError("Existing nearest shadow/atlas sampler was replaced");

      var encoder = device.createCommandEncoder();
      for (int level = 0; level < 4; level++) {
        int size = 8 >> level;
        ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            int checker = ((x + y) & 1) * 255;
            pixels.put((byte) (level == 0 ? checker : level == 1 ? 255 : 0));
            pixels.put((byte) (level == 0 ? checker : level == 2 ? 255 : 0));
            pixels.put((byte) (level == 0 ? checker : level == 3 ? 255 : 0));
            pixels.put((byte) 255);
          }
        encoder.writeToTexture(atlas.texture, pixels.flip(), level, 0, 0, 0, size, size);
      }

      byte[] magnified = render(device, implicit, atlas, sharp, 32);
      byte[] smeared = render(device, implicit, atlas, supplied, 32);
      int intermediateOriginalPixels = 0;
      for (int y = 0; y < 32; y++)
        for (int x = 0; x < 32; x++) {
          int index = (y * 32 + x) * 4;
          int value = Byte.toUnsignedInt(magnified[index]);
          if (value != 0 && value != 255)
            throw new AssertionError(
                "Magnified atlas texel was interpolated at " + x + "," + y + ": " + value);
          if (magnified[index + 1] != magnified[index]
              || magnified[index + 2] != magnified[index]
              || Byte.toUnsignedInt(magnified[index + 3]) != 255)
            throw new AssertionError("Magnification changed pixel color or alpha");
          int original = Byte.toUnsignedInt(smeared[index]);
          if (original > 0 && original < 255) intermediateOriginalPixels++;
        }
      if (intermediateOriginalPixels < 256)
        throw new AssertionError("Linear control did not reproduce magnification blur");

      // 8 source texels / 2 destination pixels selects level 2 by real screen derivatives.
      assertColor(render(device, implicit, atlas, sharp, 2), 0, 255, 0, "implicit minification");
      // Explicit fractional LOD retains interpolation between red level 1 and green level 2.
      assertColor(
          render(device, fractional, atlas, sharp, 2), 128, 128, 0, "fractional mip blending");
      // The source's finite max LOD and anisotropy survive the magnification-only variant.
      assertColor(render(device, fractional, atlas, sharpCapped, 2), 255, 0, 0, "source max LOD");
      variants.close();
      if (!sharp.isClosed() || !sharpCapped.isClosed())
        throw new AssertionError("Owned atlas sampler variants were not closed");
      if (supplied.isClosed() || capped.isClosed() || alreadyNearest.isClosed())
        throw new AssertionError("Borrowed vanilla samplers were closed");
      System.out.println(
          "PASS: block-atlas magnification stays crisp; minification, mip blending, max LOD,"
              + " addressing, anisotropy and sampler ownership are retained");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static GpuSampler sampler(
      GpuDevice device, FilterMode magnification, int anisotropy, OptionalDouble maxLod) {
    return device.createSampler(
        AddressMode.REPEAT,
        AddressMode.CLAMP_TO_EDGE,
        FilterMode.LINEAR,
        magnification,
        anisotropy,
        maxLod);
  }

  private static void assertSettings(GpuSampler supplied, GpuSampler sharp) {
    if (sharp.getMagFilter() != FilterMode.NEAREST
        || sharp.getMinFilter() != supplied.getMinFilter()
        || sharp.getAddressModeU() != supplied.getAddressModeU()
        || sharp.getAddressModeV() != supplied.getAddressModeV()
        || sharp.getMaxAnisotropy() != supplied.getMaxAnisotropy()
        || !sharp.getMaxLod().equals(supplied.getMaxLod()))
      throw new AssertionError("Atlas magnification variant changed unrelated sampler settings");
  }

  private static CompiledRenderPipeline compile(GpuDevice device, boolean fractionalLod) {
    String fragment =
        "#version 450\nlayout(location=0) in vec2 uv;\n"
            + "layout(location=0) out vec4 color; uniform sampler2D Atlas;\n"
            + "void main(){ color="
            + (fractionalLod ? "textureLod(Atlas,uv,1.5)" : "texture(Atlas,uv)")
            + ";}\n";
    var pipeline =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath(
                    "shader_test", fractionalLod ? "atlas_mips" : "atlas_implicit"))
            .withVertexShader(Identifier.fromNamespaceAndPath("shader_test", "atlas"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("shader_test", "atlas"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("Atlas", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier identifier, ShaderType type) {
            return type == ShaderType.VERTEX ? VERTEX : fragment;
          }

          public CachedIncludeSource getInclude(Identifier identifier) {
            return null;
          }

          public void close() {}
        };
    CompiledRenderPipeline compiled =
        device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
    if (compiled == null) throw new AssertionError("Could not compile atlas sampling test");
    return compiled;
  }

  private static byte[] render(
      GpuDevice device,
      CompiledRenderPipeline pipeline,
      PackTexture atlas,
      GpuSampler sampler,
      int size) {
    try (var target =
            new PackTexture(device, "atlas sample output", GpuFormat.RGBA8_UNORM, size, size, 1);
        var readback =
            device.createBuffer(
                () -> "atlas sampling readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                size * size * 4)) {
      var encoder = device.createCommandEncoder();
      try (var pass =
          encoder.createRenderPass(() -> "atlas sampling", target.level(0), Optional.empty())) {
        pass.setPipeline(pipeline);
        pass.setUniform("Atlas", atlas.sampled, sampler);
        pass.draw(3, 1, 0, 0);
      }
      encoder.copyTextureToBuffer(target.texture, readback, 0, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("Atlas sampling GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        byte[] bytes = new byte[size * size * 4];
        mapped.data().get(bytes);
        return bytes;
      }
    }
  }

  private static void assertColor(byte[] pixels, int red, int green, int blue, String context) {
    int[] expected = {red, green, blue, 255};
    for (int index = 0; index < pixels.length; index++)
      if (Math.abs(Byte.toUnsignedInt(pixels[index]) - expected[index % 4]) > 1)
        throw new AssertionError(
            context
                + " changed channel "
                + index
                + ": "
                + Byte.toUnsignedInt(pixels[index])
                + " expected "
                + expected[index % 4]);
  }
}
