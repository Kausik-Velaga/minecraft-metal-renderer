package dev.kausik.metal;

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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Runs the real RenderPearl GLSL frontend and native Metal compiler for every vanilla variant. */
public final class ShaderSmokeTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    long start = System.nanoTime();
    List<RenderPipeline> pipelines = pipelines();
    int count = 0;
    try {
      try (AssetShaderSource source = new AssetShaderSource()) {
        count += compileAll(device, pipelines, source, Runnable::run);
      }
      // Fresh source/include ownership models resource reload. Compile concurrently as the
      // game's pipeline cache does, exercising shared MSL caching and independent PSO ownership.
      try (AssetShaderSource source = new AssetShaderSource();
          var workers = Executors.newFixedThreadPool(4)) {
        count += compileAll(device, pipelines, source, workers);
      }
      compilePushConstants(device);
      System.out.printf(
          "PASS: %d vanilla pipeline variants (including all OIT stages and optional terrain)"
              + " compiled twice, %d total; numeric uniforms + shared push constants rendered/read"
              + " back, %.3f s%n",
          pipelines.size(), count, (System.nanoTime() - start) / 1e9);
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static List<RenderPipeline> pipelines() {
    Set<RenderPipeline> unique = Collections.newSetFromMap(new IdentityHashMap<>());
    unique.addAll(RenderPipelines.requiredPipelines());
    unique.addAll(RenderPipelines.optionalPipelines());
    // Some public variants intentionally have the same resource location. The registered
    // map keeps only the last one; all public pipeline objects must still be supported.
    try {
      for (var field : RenderPipelines.class.getFields()) {
        if (!Modifier.isStatic(field.getModifiers())) continue;
        Object value = field.get(null);
        if (value instanceof RenderPipeline pipeline) unique.add(pipeline);
        if (value instanceof OitPipelineSet oit) {
          unique.add(oit.depthBoundsPipeline());
          unique.add(oit.transmittancePipeline());
          unique.add(oit.accumulatePipeline());
        }
      }
    } catch (ReflectiveOperationException error) {
      throw new AssertionError("Cannot enumerate vanilla pipelines", error);
    }
    List<RenderPipeline> result = new ArrayList<>(unique);
    result.sort(java.util.Comparator.comparing(p -> p.getLocation().toString()));
    return result;
  }

  private static int compileAll(
      FrontendGpuDevice device,
      List<RenderPipeline> pipelines,
      ShaderSource source,
      Executor executor) {
    var pending =
        pipelines.stream()
            .map(pipeline -> device.compilePipeline(pipeline, source, executor))
            .toList();
    // Wait for every compile before closing include storage, including a failing run.
    java.util.concurrent.CompletableFuture.allOf(
            pending.toArray(java.util.concurrent.CompletableFuture[]::new))
        .join();
    int count = 0;
    for (int i = 0; i < pending.size(); i++) {
      var pipeline = pipelines.get(i);
      try (var compiled = pending.get(i).join().finishCompile()) {
        if (compiled == null || compiled.isClosed())
          throw new AssertionError("Invalid vanilla pipeline " + pipeline.getLocation());
        count++;
      } catch (Exception error) {
        throw new AssertionError("Vanilla pipeline failed: " + pipeline.getLocation(), error);
      }
    }
    return count;
  }

  private static void compilePushConstants(FrontendGpuDevice device) {
    var pipeline =
        RenderPipeline.builder()
            .withLocation("metal_test/push_constants")
            .withVertexShader("metal_test/push_constants")
            .withFragmentShader("metal_test/push_constants")
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withBindGroupLayout(
                BindGroupLayout.builder().withUniform("Tint", UniformType.UNIFORM_BUFFER).build())
            .withCull(false)
            .withPushConstantSize(20)
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            String common =
                "#version 450\n"
                    + "layout(push_constant) uniform Params { vec4 color; float scale; } pc;\n"
                    + "layout(std140) uniform Tint { vec4 tint; };\n";
            return common
                + (type == ShaderType.VERTEX
                    ? "void main() { vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);"
                          + " gl_Position = vec4((p * 2.0 - 1.0) * pc.scale * tint.a, 0.0, 1.0);"
                          + " }\n"
                    : "layout(location=0) out vec4 color; void main() { color = pc.color * tint;"
                          + " }\n");
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    try (var compiled =
        device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile()) {
      if (!(compiled instanceof FrontendRenderPipeline frontend)
          || !(frontend.backendRenderPipeline() instanceof MetalRenderPipeline metal)
          || metal.pushConstantsStageMask() != 3
          || metal.pushConstantsSize() != 20)
        throw new AssertionError("Both shader stages must bind the 20-byte push constant range");
      if (metal.bindings().size() != 1 || metal.bindings().getFirst().stageMask() != 3)
        throw new AssertionError("Both shader stages must share the numeric Tint buffer binding");
      ByteBuffer tint = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
      for (int i = 0; i < 4; i++) tint.putFloat(1);
      tint.flip();
      ByteBuffer constants = ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder());
      constants.position(4);
      constants.putFloat(1).putFloat(0).putFloat(0).putFloat(1).putFloat(1);
      constants.flip().position(4);
      var encoder = device.createCommandEncoder();
      try (var buffer =
              device.createBuffer(
                  () -> "Shared numeric uniform",
                  GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                  tint);
          var texture =
              device.createTexture(
                  "Frontend push-constant readback",
                  GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                  GpuFormat.RGBA8_UNORM,
                  8,
                  4,
                  1,
                  1);
          var view = device.createTextureView(texture);
          var readback =
              device.createBuffer(
                  () -> "Frontend shader readback",
                  GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                  8 * 4 * 4)) {
        try (var pass =
            encoder.createRenderPass(
                () -> "Numeric bindings and push constants", view, Optional.of(new Vector4f()))) {
          pass.setUniform("Tint", buffer);
          pass.setPipeline(compiled);
          pass.enableScissor(0, 0, 4, 4);
          pass.pushConstants(constants);
          pass.draw(3, 1, 0, 0);
          // Rebinding clears the backend's numeric slots; the frontend must replay names.
          pass.setPipeline(compiled);
          pass.enableScissor(4, 0, 4, 4);
          constants.putFloat(4, 0).putFloat(8, 1);
          pass.pushConstants(constants);
          pass.draw(3, 1, 0, 0);
        }
        if (constants.position() != 4 || constants.limit() != 24)
          throw new AssertionError("Pushing constants changed the caller's buffer bounds");
        encoder.copyTextureToBuffer(texture, readback, 0, () -> {}, 0);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(5_000_000_000L))
            throw new AssertionError("Frontend shader readback timed out");
        }
        try (var mapped = readback.map(true, false)) {
          ByteBuffer pixels = mapped.data();
          for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 8; x++) {
              int offset = (y * 8 + x) * 4;
              int red = Byte.toUnsignedInt(pixels.get(offset));
              int green = Byte.toUnsignedInt(pixels.get(offset + 1));
              int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
              int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
              if (red != (x < 4 ? 255 : 0)
                  || green != (x < 4 ? 0 : 255)
                  || blue != 0
                  || alpha != 255)
                throw new AssertionError(
                    "Incorrect frontend push-constant pixel at "
                        + x
                        + ","
                        + y
                        + ": "
                        + red
                        + ","
                        + green
                        + ","
                        + blue
                        + ","
                        + alpha);
            }
          }
        }
      }
    }
  }

  private static final class AssetShaderSource implements ShaderSource {
    private final Map<Identifier, CachedIncludeSource> includes = new ConcurrentHashMap<>();

    @Override
    public String getShader(Identifier id, ShaderType type) {
      return read(
          "assets/"
              + id.getNamespace()
              + "/shaders/"
              + id.getPath()
              + (type == ShaderType.VERTEX ? ".vsh" : ".fsh"));
    }

    @Override
    public CachedIncludeSource getInclude(Identifier id) {
      return includes.computeIfAbsent(
          id,
          include ->
              CachedIncludeSource.create(
                  include,
                  read(
                      "assets/"
                          + include.getNamespace()
                          + "/shaders/include/"
                          + include.getPath())));
    }

    @Override
    public void close() {
      includes.values().forEach(CachedIncludeSource::close);
      includes.clear();
    }
  }

  private static String read(String asset) {
    try (InputStream input = ShaderSmokeTest.class.getClassLoader().getResourceAsStream(asset)) {
      if (input == null) throw new IllegalStateException("Missing vanilla asset " + asset);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read vanilla asset " + asset, error);
    }
  }
}
