package dev.kausik.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Pixel regression for partial binding updates and reordered pipeline interfaces. */
public final class UniformBindingSmokeTest {
  private static final int WIDTH = 14, HEIGHT = 2, COMMANDS = 64;
  private static final String COMMON =
      """
      #version 450
      layout(std140) uniform Tint { vec4 tint; };
      layout(std140) uniform Bias { vec4 bias; };
      uniform sampler2D Source;
      uniform sampler2D Accent;
      """;
  private static final String VERTEX =
      COMMON
          + """
          layout(location=0) out vec4 incomingTint;
          void main() {
            vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2)*2.0-1.0;
            incomingTint=tint;
            // Every binding is live in both stages. All supplied alphas are exactly one.
            float w=textureLod(Source,vec2(.375,.5),0.0).a
                  *textureLod(Accent,vec2(.5),0.0).a*tint.a;
            gl_Position=vec4(p*bias.a,0.0,w);
          }
          """;
  private static final String FRAGMENT =
      COMMON
          + """
          layout(location=0) in vec4 incomingTint;
          layout(location=0) out vec4 color;
          void main() {
            vec3 source=textureLod(Source,vec2(.375,.5),0.0).rgb;
            vec3 accent=textureLod(Accent,vec2(.5),0.0).rgb;
            color=vec4(source*incomingTint.rgb+accent*bias.rgb,tint.a*bias.a);
          }
          """;

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try {
      verify(device);
      System.out.println(
          "PASS: unchanged bindings survive individual UBO/texture/sampler updates, repeated draws"
              + " and reordered pipeline switches; exact direct/indirect pixel rows agree (ICB"
              + " requested="
              + Boolean.getBoolean("minecraftMetal.indirectCommandBuffers")
              + ")");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(FrontendGpuDevice device) {
    int textureUsage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
    try (var first = pipeline(device, false);
        var reordered = pipeline(device, true);
        var tintWhite = uniform(device, "white tint", 1, 1, 1, 1);
        var tintCyan = uniform(device, "cyan tint", 0, 1, 1, 1);
        var biasZero = uniform(device, "zero bias", 0, 0, 0, 1);
        var biasRed = uniform(device, "red bias", 1, 0, 0, 1);
        var biasBlue = uniform(device, "blue bias", 0, 0, 1, 1);
        var sourceA =
            device.createTexture(
                "red/green source", textureUsage, GpuFormat.RGBA8_UNORM, 2, 1, 1, 1);
        var sourceB =
            device.createTexture(
                "blue/white source", textureUsage, GpuFormat.RGBA8_UNORM, 2, 1, 1, 1);
        var white =
            device.createTexture("white accent", textureUsage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var black =
            device.createTexture("black accent", textureUsage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var sourceAView = device.createTextureView(sourceA);
        var sourceBView = device.createTextureView(sourceB);
        var whiteView = device.createTextureView(white);
        var blackView = device.createTextureView(black);
        var nearest =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0));
        var linear =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.of(0));
        var output =
            device.createTexture(
                "binding update pixels",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1);
        var outputView = device.createTextureView(output);
        var readback =
            device.createBuffer(
                () -> "binding readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                WIDTH * HEIGHT * 4);
        var commands =
            device.createBuffer(
                () -> "binding indirect draws",
                GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
                COMMANDS * 16)) {
      assertDifferentIndices(first, reordered);
      var encoder = device.createCommandEncoder();
      encoder.writeToTexture(sourceA, pixels(255, 0, 0, 255, 0, 255, 0, 255), 0, 0, 0, 0, 2, 1);
      encoder.writeToTexture(sourceB, pixels(0, 0, 255, 255, 255, 255, 255, 255), 0, 0, 0, 0, 2, 1);
      encoder.writeToTexture(white, pixels(255, 255, 255, 255), 0, 0, 0, 0, 1, 1);
      encoder.writeToTexture(black, pixels(0, 0, 0, 255), 0, 0, 0, 0, 1, 1);
      ByteBuffer arguments = bytes(COMMANDS * 16);
      // One visible command followed by empty draws reaches the normal ICB batch threshold
      // without changing coverage or expected pixels. The disabled mode uses the same commands.
      arguments.putInt(0, 3).putInt(4, 1);
      encoder.writeToBuffer(commands.slice(), arguments);
      try (var pass =
          encoder.createRenderPass(
              () -> "partial uniform binding updates", outputView, Optional.of(new Vector4f()))) {
        for (int row = 0; row < HEIGHT; row++) {
          pass.setUniform("Tint", tintWhite);
          pass.setUniform("Bias", biasZero);
          pass.setUniform("Source", sourceAView, nearest);
          pass.setUniform("Accent", whiteView, nearest);
          pass.setPipeline(first);
          draw(pass, commands, 0, row); // red
          pass.setUniform("Tint", tintCyan);
          draw(pass, commands, 1, row); // black, untouched Bias/Source/Accent live
          pass.setUniform("Source", sourceBView, nearest);
          draw(pass, commands, 2, row); // blue, untouched UBOs/Accent live
          draw(pass, commands, 3, row); // unchanged repeated draw
          pass.setUniform("Bias", biasRed);
          draw(pass, commands, 4, row); // magenta
          pass.setUniform("Source", sourceAView, nearest);
          draw(pass, commands, 5, row); // red
          pass.setUniform("Source", sourceAView, linear);
          draw(pass, commands, 6, row); // red + quarter green, sampler-only change
          pass.setPipeline(reordered);
          draw(pass, commands, 7, row); // same values through different native slots
          pass.setUniform("Bias", biasZero);
          draw(pass, commands, 8, row); // quarter green
          pass.setPipeline(first);
          draw(pass, commands, 9, row); // same values after switching back
          pass.setUniform("Tint", tintWhite);
          draw(pass, commands, 10, row); // three-quarter red + quarter green
          pass.setUniform("Bias", biasBlue);
          draw(pass, commands, 11, row); // plus blue
          pass.setUniform("Accent", blackView, nearest);
          draw(pass, commands, 12, row); // changed second texture removes blue
          pass.setUniform("Source", sourceAView, nearest);
          draw(pass, commands, 13, row); // nearest returns solid red again
        }
      }
      encoder.copyTextureToBuffer(output, readback, 0, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(10_000_000_000L))
          throw new AssertionError("Uniform binding GPU timeout");
      }
      int[][] expected = {
        {255, 0, 0}, {0, 0, 0}, {0, 0, 255}, {0, 0, 255}, {255, 0, 255}, {255, 0, 0},
        {255, 64, 0}, {255, 64, 0}, {0, 64, 0}, {0, 64, 0}, {191, 64, 0}, {191, 64, 255},
        {191, 64, 0}, {255, 0, 0}
      };
      try (var mapped = readback.map(true, false)) {
        ByteBuffer actual = mapped.data();
        for (int row = 0; row < HEIGHT; row++) {
          for (int x = 0; x < WIDTH; x++) {
            for (int channel = 0; channel < 4; channel++) {
              int value = Byte.toUnsignedInt(actual.get((row * WIDTH + x) * 4 + channel));
              int wanted = channel == 3 ? 255 : expected[x][channel];
              if (value != wanted)
                throw new AssertionError(
                    "Binding update pixel mismatch at step="
                        + x
                        + " indirect="
                        + (row == 1)
                        + " channel="
                        + channel
                        + " actual="
                        + value
                        + " expected="
                        + wanted);
            }
          }
        }
      }
    }
  }

  private static void draw(RenderPass pass, GpuBuffer commands, int column, int row) {
    pass.enableScissor(column, row, 1, 1);
    if (row == 0) pass.draw(3, 1, 0, 0);
    else pass.drawIndirect(commands.slice(), COMMANDS);
  }

  private static GpuBuffer uniform(FrontendGpuDevice device, String name, float... values) {
    ByteBuffer data = bytes(16);
    for (float value : values) data.putFloat(value);
    return device.createBuffer(
        () -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data.flip());
  }

  private static ByteBuffer pixels(int... values) {
    ByteBuffer data = bytes(values.length);
    for (int value : values) data.put((byte) value);
    return data.flip();
  }

  private static ByteBuffer bytes(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }

  private static CompiledRenderPipeline pipeline(FrontendGpuDevice device, boolean reordered) {
    var layout = BindGroupLayout.builder();
    for (String name :
        reordered
            ? List.of("Accent", "Bias", "Source")
            : List.of("Tint", "Source", "Bias", "Accent"))
      layout.withUniform(
          name,
          name.equals("Tint") || name.equals("Bias")
              ? UniformType.UNIFORM_BUFFER
              : UniformType.COMBINED_IMAGE_SAMPLER);
    var description =
        RenderPipeline.builder()
            .withLocation("metal_test/partial_bindings/" + reordered)
            .withVertexShader("partial_bindings")
            .withFragmentShader("partial_bindings")
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(layout.build())
            .withCull(false)
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            String code = type == ShaderType.VERTEX ? VERTEX : FRAGMENT;
            // The frontend canonicalizes layout order. Specialize the currently cyan tint so this
            // pipeline has one fewer UBO and Bias must move into Tint's former native slot.
            return reordered
                ? code.replace(
                    "layout(std140) uniform Tint { vec4 tint; };", "const vec4 tint=vec4(0,1,1,1);")
                : code;
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    return device.compilePipeline(description, source, Runnable::run).join().finishCompile();
  }

  private static void assertDifferentIndices(
      CompiledRenderPipeline first, CompiledRenderPipeline second) {
    var a = (MetalRenderPipeline) ((FrontendRenderPipeline) first).backendRenderPipeline();
    var b = (MetalRenderPipeline) ((FrontendRenderPipeline) second).backendRenderPipeline();
    boolean moved = false;
    for (String name : List.of("Bias", "Source", "Accent")) {
      var x =
          a.bindings().stream()
              .filter(binding -> binding.name().equals(name))
              .findFirst()
              .orElseThrow();
      var y =
          b.bindings().stream()
              .filter(binding -> binding.name().equals(name))
              .findFirst()
              .orElseThrow();
      moved |= x.index() != y.index();
      if (x.stageMask() != 3 || y.stageMask() != 3)
        throw new AssertionError(
            "Fixture failed to move live vertex/fragment resource slots: "
                + name
                + ": "
                + x
                + " / "
                + y);
    }
    if (!moved) throw new AssertionError("Fixture did not move any native binding indices");
  }
}
