package dev.kausik.metal;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.Arrays;
import net.minecraft.resources.Identifier;

/** Real frontend/SPIR-V/native policy selection, precision fallback and finite/NaN/Inf pixels. */
public final class ScopedFragmentMathSmokeTest {
  private static final int WIDTH = 4, HEIGHT = 2, BYTES = WIDTH * HEIGHT * 4;

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var backend = new MetalDevice();
    var device = new FrontendGpuDevice(backend);
    RenderSystem.initRenderer(device);
    long color = 0, readback = 0;
    try {
      long d = backend.handle();
      boolean supported =
          Integer.parseInt(System.getProperty("os.version").split("\\.")[0]) >= 15
              && System.getProperty("os.arch").equals("aarch64");
      int expectedRelaxed = supported ? 1 : 0;
      int legacy =
          "relaxed".equals(System.getenv("MINECRAFT_METAL_MATH_MODE")) ? expectedRelaxed : 0;
      var before = MetalPipelineMath.snapshot();
      color = MetalNative.createTexture(d, "RGBA8_UNORM", WIDTH, HEIGHT, 1, 1, 15, "math result");
      readback = MetalNative.createBuffer(d, BYTES, true, "math readback");
      var relaxedDescription = description("relaxed");
      try (var scope =
              MetalPipelineMath.openRelaxedFragment(relaxedDescription.getLocation().toString());
          var unrelated = compile(device, description("unrelated"), false, false);
          var relaxed = compile(device, relaxedDescription, false, false)) {
        modes(unrelated, legacy, legacy);
        modes(relaxed, 0, expectedRelaxed);
        byte[] expected = render(d, unrelated, color, readback);
        byte[] actual = render(d, relaxed, color, readback);
        if (!Arrays.equals(expected, actual))
          throw new AssertionError("Relaxed fixture pixels differ");
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++)
          if (actual[pixel * 4 + 1] != (byte) 255 || actual[pixel * 4 + 2] != (byte) 255)
            throw new AssertionError("Relaxed fragment did not preserve Inf/NaN at pixel " + pixel);
      }
      var preciseDescription = description("precise");
      try (var scope =
              MetalPipelineMath.openRelaxedFragment(preciseDescription.getLocation().toString());
          var precise = compile(device, preciseDescription, true, false)) {
        modes(precise, 0, 0);
        render(d, precise, color, readback);
      }
      var depthDescription = description("depth_variant");
      try (var scope =
              MetalPipelineMath.openRelaxedFragment(depthDescription.getLocation().toString());
          var depth = compile(device, depthDescription, false, true)) {
        modes(depth, 0, expectedRelaxed);
        // No depth attachment: exercise the separately compiled fragment-without-depth variant.
        render(d, depth, color, readback);
      }
      try (var outside = compile(device, description("outside"), false, false)) {
        modes(outside, legacy, legacy);
      }
      var after = MetalPipelineMath.snapshot();
      if (after.scopedCompilations() - before.scopedCompilations() != 3
          || after.precisionFallbacks() - before.precisionFallbacks() != 1
          || after.relaxedFragments() - before.relaxedFragments() != (supported ? 2 : 0)
          || after.platformFallbacks() - before.platformFallbacks() != (supported ? 0 : 2))
        throw new AssertionError("Incorrect actual compile-policy counters: " + after);
      System.out.println(
          "PASS: scoped fragment policy, Safe vertices/precise fallback, depth variant,"
              + " exact finite/Inf/NaN fixture, legacy isolation; "
              + after);
    } finally {
      if (color != 0) MetalNative.release(color);
      if (readback != 0) MetalNative.release(readback);
      RenderSystem.shutdownRenderer();
    }
  }

  private static RenderPipeline description(String name) {
    return RenderPipeline.builder()
        .withLocation("metal_test/scoped_math/" + name)
        .withVertexShader("scoped_math")
        .withFragmentShader("scoped_math")
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();
  }

  private static CompiledRenderPipeline compile(
      FrontendGpuDevice device, RenderPipeline description, boolean precise, boolean writesDepth) {
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            if (type == ShaderType.VERTEX)
              return "#version 450\n"
                         + "void main(){vec2"
                         + " p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2)*2.0-1.0;"
                         + "gl_Position=vec4(p,0,1);}";
            return "#version 450\nlayout(location=0) out vec4 color;void main(){"
                + (precise ? "precise " : "")
                + "float value=gl_FragCoord.x*.125+.0625;"
                + "float zero=gl_FragCoord.y-floor(gl_FragCoord.y)-.5;"
                + "float infinity=1.0/zero;float nanValue=zero/zero;"
                + "color=vec4(value,isinf(infinity)?1:0,isnan(nanValue)?1:0,1);"
                + (writesDepth ? "gl_FragDepth=.25;" : "")
                + "}";
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    var result = device.compilePipeline(description, source, Runnable::run).join().finishCompile();
    if (result == null) throw new AssertionError("Math fixture compilation failed");
    return result;
  }

  private static long handle(CompiledRenderPipeline pipeline) {
    return ((MetalRenderPipeline) ((FrontendRenderPipeline) pipeline).backendRenderPipeline())
        .handle();
  }

  private static void modes(CompiledRenderPipeline pipeline, int vertex, int fragment) {
    int[] modes = MetalNative.pipelineMathModes(handle(pipeline));
    if (!Arrays.equals(modes, new int[] {vertex, fragment}))
      throw new AssertionError("Wrong applied arithmetic modes: " + Arrays.toString(modes));
  }

  private static byte[] render(long d, CompiledRenderPipeline pipeline, long color, long readback) {
    MetalNative.beginRenderPass(
        d,
        "math fixture",
        new long[] {color},
        new float[] {0, 0, 0, 0},
        0,
        Double.NaN,
        0,
        0,
        WIDTH,
        HEIGHT);
    MetalNative.bindPipeline(d, handle(pipeline));
    MetalNative.draw(d, 3, 0, 3, 1, 0);
    MetalNative.endRenderPass(d);
    MetalNative.textureToBuffer(d, color, 0, 0, 0, WIDTH, HEIGHT, readback, 0);
    long fence = MetalNative.createFence(d);
    try {
      MetalNative.submit(d);
      if (!MetalNative.awaitFence(fence, 10_000_000_000L))
        throw new AssertionError("Math GPU timeout");
    } finally {
      MetalNative.release(fence);
    }
    byte[] result = new byte[BYTES];
    MetalNative.mapBuffer(readback, 0, BYTES).get(result);
    return result;
  }
}
