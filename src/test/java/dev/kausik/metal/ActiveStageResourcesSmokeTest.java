package dev.kausik.metal;

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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Keeps fixed binding slots while proving unused and stage-specific resources stay independent. */
public final class ActiveStageResourcesSmokeTest {
  private static final int WIDTH = 6, HEIGHT = 2, COMMANDS = 64;
  private static final String COMMON =
      """
      #version 450
      layout(std140) uniform UnusedData { vec4 unusedValue; };
      layout(std140) uniform VertexData { vec4 vertexValue; };
      layout(std140) uniform FragmentData { vec4 fragmentValue; };
      layout(std140) uniform SharedData { vec4 sharedValue; };
      uniform sampler2D UnusedTexture;
      uniform sampler2D VertexTexture;
      uniform sampler2D FragmentTexture;
      uniform sampler2D SharedTexture;
      """;

  public static void main(String[] args) {
    assertUnknownReflectionFallback();
    RenderSystem.initRenderThread();
    var backend = new MetalDevice();
    var device = new FrontendGpuDevice(backend);
    RenderSystem.initRenderer(device);
    try {
      verify(device);
      if ("1".equals(System.getenv("MINECRAFT_METAL_RENDER_STAGE_TIMINGS"))) {
        var stats =
            com.google.gson.JsonParser.parseString(
                    MetalNative.renderStageStatistics(backend.handle()))
                .getAsJsonObject();
        if (!stats.get("requested").getAsBoolean()
            || !stats.get("supported").getAsBoolean()
            || stats.get("sampledSubmissions").getAsLong() < 1
            || stats.getAsJsonObject("stages").isEmpty())
          throw new AssertionError("Render stage diagnostics did not record drawn work: " + stats);
        if (stats.get("emptyPasses").getAsLong() < 1
            || stats.getAsJsonObject("stages").has("active stage clear only"))
          throw new AssertionError("Clear-only work was attributed shader timings: " + stats);
        if (!"tracked-resources".equals(MetalNative.hazardSynchronizationMode(backend.handle())))
          throw new AssertionError("Stage counters must retain tracked-resource synchronization");
        System.out.println("Render stage diagnostics: " + stats);
      }
      System.out.println(
          "PASS: active stage masks, unused layout holes, no-texture vertex stage, shared"
              + " resources, stage-swapping pipeline switches and partial updates; direct and"
              + " indirect readback pixels agree (ICB requested="
              + Boolean.getBoolean("minecraftMetal.indirectCommandBuffers")
              + ")");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(FrontendGpuDevice device) {
    int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
    try (var normal = pipeline(device, 0);
        var swapped = pipeline(device, 1);
        var noVertexTextures = pipeline(device, 2);
        var white = uniform(device, "white", 1, 1, 1, 1);
        var zero = uniform(device, "unused poison", 0, 0, 0, 0);
        var red = device.createTexture("fragment red", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var blue = device.createTexture("vertex blue", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var green = device.createTexture("shared green", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var black = device.createTexture("shared black", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        var redView = device.createTextureView(red);
        var blueView = device.createTextureView(blue);
        var greenView = device.createTextureView(green);
        var blackView = device.createTextureView(black);
        var sampler =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0));
        var output =
            device.createTexture(
                "active resource result",
                GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_COPY_SRC
                    | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1);
        var outputView = device.createTextureView(output);
        var readback =
            device.createBuffer(
                () -> "active stage readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                WIDTH * HEIGHT * 4L);
        var commands =
            device.createBuffer(
                () -> "active stage indirect arguments",
                GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST,
                COMMANDS * 16L)) {
      assertStages(normal, 0);
      assertStages(swapped, 1);
      assertStages(noVertexTextures, 2);
      assertActiveNames(normal, false);
      assertActiveNames(swapped, false);
      assertActiveNames(noVertexTextures, true);
      assertDeclaredSlots(normal);
      assertDeclaredSlots(swapped);
      assertDeclaredSlots(noVertexTextures);
      var encoder = device.createCommandEncoder();
      try (var ignored =
          encoder.createRenderPass(
              () -> "active stage clear only", outputView, Optional.of(new Vector4f()))) {
        // No shader invocation: its sample slots must never contribute to stage timings.
      }
      encoder.writeToTexture(red, pixels(255, 0, 0, 255), 0, 0, 0, 0, 1, 1);
      encoder.writeToTexture(blue, pixels(0, 0, 255, 255), 0, 0, 0, 0, 1, 1);
      encoder.writeToTexture(green, pixels(0, 255, 0, 255), 0, 0, 0, 0, 1, 1);
      encoder.writeToTexture(black, pixels(0, 0, 0, 255), 0, 0, 0, 0, 1, 1);
      var indirect = bytes(COMMANDS * 16);
      indirect.putInt(0, 3).putInt(4, 1);
      encoder.writeToBuffer(commands.slice(), indirect);
      try (var pass =
          encoder.createRenderPass(
              () -> "active stage resources", outputView, Optional.of(new Vector4f()))) {
        for (int row = 0; row < HEIGHT; row++) {
          pass.setUniform("UnusedData", zero);
          pass.setUniform("VertexData", white);
          pass.setUniform("FragmentData", white);
          pass.setUniform("SharedData", white);
          // An unused texture aliases the target on purpose: it must occupy its original slot
          // without being advertised as a shader read or accessed by either compiled stage.
          pass.setUniform("UnusedTexture", outputView, sampler);
          pass.setUniform("VertexTexture", blueView, sampler);
          pass.setUniform("FragmentTexture", redView, sampler);
          pass.setUniform("SharedTexture", greenView, sampler);
          for (int step = 0; step < WIDTH; step++) {
            pass.setPipeline(
                switch (step) {
                  case 1, 4 -> swapped;
                  case 2 -> noVertexTextures;
                  default -> normal;
                });
            if (step == 3) pass.setUniform("SharedTexture", blackView, sampler);
            if (step == 5) pass.setUniform("SharedTexture", greenView, sampler);
            pass.enableScissor(step, row, 1, 1);
            if (row == 0) pass.draw(3, 1, 0, 0);
            else pass.drawIndirect(commands.slice(), COMMANDS);
          }
        }
      }
      encoder.copyTextureToBuffer(output, readback, 0, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(10_000_000_000L))
          throw new AssertionError("Active-stage GPU timeout");
      }
      int[][] expected = {
        {255, 255, 0}, {0, 255, 255}, {255, 255, 0}, {255, 0, 0}, {0, 0, 255}, {255, 255, 0}
      };
      try (var mapped = readback.map(true, false)) {
        var actual = mapped.data();
        for (int row = 0; row < HEIGHT; row++)
          for (int step = 0; step < WIDTH; step++)
            for (int component = 0; component < 4; component++) {
              int found = Byte.toUnsignedInt(actual.get((row * WIDTH + step) * 4 + component));
              int wanted = component == 3 ? 255 : expected[step][component];
              if (found != wanted)
                throw new AssertionError(
                    "Active-stage pixel: row="
                        + row
                        + " step="
                        + step
                        + " component="
                        + component
                        + " wanted="
                        + wanted
                        + " actual="
                        + found);
            }
      }
    }
  }

  private static CompiledRenderPipeline pipeline(FrontendGpuDevice device, int variant) {
    var layout = BindGroupLayout.builder();
    for (String name : List.of("UnusedData", "VertexData", "FragmentData", "SharedData"))
      layout.withUniform(name, UniformType.UNIFORM_BUFFER);
    for (String name :
        List.of("UnusedTexture", "VertexTexture", "FragmentTexture", "SharedTexture"))
      layout.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
    var description =
        RenderPipeline.builder()
            .withLocation("metal_test/active_stages/" + variant)
            .withVertexShader("active_stages")
            .withFragmentShader("active_stages")
            .withBindGroupLayout(layout.build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withCull(false)
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX) {
              String texture = variant == 1 ? "FragmentTexture" : "VertexTexture";
              String value = variant == 1 ? "fragmentValue" : "vertexValue";
              return COMMON
                  + "void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2)*2.0-1.0;"
                  + "float w="
                  + value
                  + ".a*sharedValue.a;"
                  + (variant == 2
                      ? ""
                      : "w*=textureLod("
                          + texture
                          + ",vec2(.5),0).a*textureLod(SharedTexture,vec2(.5),0).a;")
                  + "gl_Position=vec4(p,0,w);}";
            }
            String texture = variant == 1 ? "VertexTexture" : "FragmentTexture";
            String value = variant == 1 ? "vertexValue" : "fragmentValue";
            return COMMON
                + "layout(location=0) out vec4 color;void main(){color=vec4("
                + "texture("
                + texture
                + ",vec2(.5)).rgb*"
                + value
                + ".rgb"
                + "+texture(SharedTexture,vec2(.5)).rgb*sharedValue.rgb,1);}";
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    var result = device.compilePipeline(description, source, Runnable::run).join().finishCompile();
    if (result == null) throw new AssertionError("Active-stage pipeline failed");
    return result;
  }

  private static MetalRenderPipeline metal(CompiledRenderPipeline pipeline) {
    return (MetalRenderPipeline) ((FrontendRenderPipeline) pipeline).backendRenderPipeline();
  }

  private static void assertActiveNames(CompiledRenderPipeline pipeline, boolean noVertexTextures) {
    var expected =
        new java.util.HashSet<>(
            Set.of(
                "VertexData",
                "FragmentData",
                "SharedData",
                "VertexTexture",
                "FragmentTexture",
                "SharedTexture"));
    if (noVertexTextures) expected.remove("VertexTexture");
    var names = MetalPipelineResources.activeResourceNames(pipeline).orElseThrow();
    if (!names.equals(expected))
      throw new AssertionError(
          "Wrong active resource snapshot: " + names + " expected=" + expected);
    try {
      names.add("cannot mutate reflection");
      throw new AssertionError("Active resource names must be immutable");
    } catch (UnsupportedOperationException expectedFailure) {
      // A consumer cannot mutate the backend's reflection through this API.
    }
    if (!MetalPipelineResources.activeResourceNames(pipeline).orElseThrow().equals(expected))
      throw new AssertionError("A consumer changed the pipeline's activity facts");
    if (pipeline.isClosed()) throw new AssertionError("Read-only reflection closed the pipeline");
  }

  private static void assertUnknownReflectionFallback() {
    CompiledRenderPipeline unknown =
        new CompiledRenderPipeline() {
          public boolean isClosed() {
            throw new AssertionError("Unknown pipeline must not be inspected");
          }

          public void close() {
            throw new AssertionError("Reflection must not close pipelines");
          }
        };
    BackendRenderPipeline nonMetal =
        new BackendRenderPipeline() {
          public boolean isClosed() {
            throw new AssertionError("Unknown backend must not be inspected");
          }

          public void close() {
            throw new AssertionError("Reflection must not close backends");
          }
        };
    var wrapped =
        new FrontendRenderPipeline(
            "unknown backend",
            nonMetal,
            List.of(),
            new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>(),
            List.of(),
            List.of(),
            false,
            0);
    if (MetalPipelineResources.activeResourceNames(unknown).isPresent()
        || MetalPipelineResources.activeResourceNames(wrapped).isPresent())
      throw new AssertionError("Unknown resource activity must preserve caller dependencies");
  }

  private static void assertStages(CompiledRenderPipeline pipeline, int variant) {
    Map<String, Integer> expected =
        Map.of(
            "UnusedData",
            0,
            "UnusedTexture",
            0,
            "VertexData",
            variant == 1 ? 2 : 1,
            "FragmentData",
            variant == 1 ? 1 : 2,
            "SharedData",
            3,
            "VertexTexture",
            variant == 1 ? 2 : variant == 2 ? 0 : 1,
            "FragmentTexture",
            variant == 1 ? 1 : 2,
            "SharedTexture",
            variant == 2 ? 2 : 3);
    for (var entry : expected.entrySet()) {
      var binding =
          metal(pipeline).bindings().stream()
              .filter(b -> b.name().equals(entry.getKey()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "Declared resource lost its layout slot: " + entry.getKey()));
      if (binding.stageMask() != entry.getValue())
        throw new AssertionError(
            "Wrong active stages: " + binding + " expected=" + entry.getValue());
    }
    System.out.println("Active resource stages " + variant + ": " + metal(pipeline).bindings());
  }

  private static void assertDeclaredSlots(CompiledRenderPipeline pipeline) {
    var frontend = (FrontendRenderPipeline) pipeline;
    var declared = frontend.uniforms();
    var bindings = metal(pipeline).bindings();
    if (declared.size() != bindings.size())
      throw new AssertionError("Active reflection removed a declared frontend layout entry");
    var indices = frontend.uniformIndices();
    var seen = new java.util.HashSet<Integer>();
    // uniforms() retains the requested declaration order, while uniformIndices() is the
    // shader-linked table actually used by FrontendRenderPass.setUniform. Compare by that map.
    for (var description : declared) {
      int i = indices.getOrDefault(description.name(), -1);
      if (i < 0 || i >= bindings.size() || !seen.add(i))
        throw new AssertionError("Invalid linked uniform map: " + indices);
      var binding = bindings.get(i);
      boolean buffer = description.type() == UniformType.UNIFORM_BUFFER;
      int expectedSlot = buffer ? 16 : 0;
      for (var previous : declared)
        if (previous.type() == description.type() && indices.getOrDefault(previous.name(), -1) < i)
          expectedSlot++;
      var expectedKind =
          buffer ? MetalRenderPipeline.Kind.UNIFORM_BUFFER : MetalRenderPipeline.Kind.TEXTURE;
      if (!description.name().equals(binding.name())
          || binding.kind() != expectedKind
          || binding.index() != expectedSlot)
        throw new AssertionError(
            "Active reflection changed declared slot "
                + i
                + ": "
                + description
                + " -> "
                + binding
                + ", expected Metal slot="
                + expectedSlot);
    }
  }

  private static GpuBuffer uniform(FrontendGpuDevice device, String label, float... values) {
    var data = bytes(16);
    for (float value : values) data.putFloat(value);
    return device.createBuffer(
        () -> label, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, data.flip());
  }

  private static ByteBuffer pixels(int... values) {
    var data = bytes(values.length);
    for (int value : values) data.put((byte) value);
    return data.flip();
  }

  private static ByteBuffer bytes(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
