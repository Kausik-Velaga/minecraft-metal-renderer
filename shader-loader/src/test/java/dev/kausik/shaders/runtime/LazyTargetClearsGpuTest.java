package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Paired real-GPU readback of attachment clears, sampled reads, history, and depth snapshots. */
public final class LazyTargetClearsGpuTest {
  private static final Vector4f FOG_A = new Vector4f(.125f, .25f, .5f, 1);
  private static final Vector4f FOG_B = new Vector4f(.75f, .5f, .25f, 1);

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var writer = writer(device)) {
      var eager = exercise(device, writer, false);
      var lazy = exercise(device, writer, true);
      if (!eager.keySet().equals(lazy.keySet())) throw new AssertionError("Missing observations");
      eager.forEach(
          (name, expected) -> {
            if (!Arrays.equals(expected, lazy.get(name)))
              throw new AssertionError("Lazy target clear changed GPU contents: " + name);
          });
      System.out.println(
          "PASS: "
              + eager.size()
              + " exact eager/lazy GPU observations across all mips, partial draws, pre-write"
              + " reads, depth snapshots, shadow copies, late allocation, flips and history"
              + " resets");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static Map<String, byte[]> exercise(
      GpuDevice device, CompiledRenderPipeline writer, boolean lazy) {
    var observations = new LinkedHashMap<String, byte[]>();
    var specs =
        Map.of(
            0, new PackRenderTargets.BufferSpec(GpuFormat.RGBA8_UNORM, true, null, true),
            1, new PackRenderTargets.BufferSpec(GpuFormat.RGBA8_UNORM, true, FOG_B, false),
            2,
                new PackRenderTargets.BufferSpec(
                    GpuFormat.RGBA8_UNORM, false, new Vector4f(.25f), true));
    try (var targets = new PackRenderTargets(device, specs, 8, 8, 4, lazy)) {
      check(targets.needsColorDefinition(0), "Unallocated color query");
      check(targets.needsShadowColorDefinition(0), "Unallocated shadow color query");
      for (int index : specs.keySet()) targets.color(index);
      targets.shadowColor(0);
      targets.shadowColor(1);
      var encoder = device.createCommandEncoder();
      encoder.clearColorTexture(targets.color(2).texture, new Vector4f(.625f));
      encoder.clearColorTexture(targets.alternate(2).texture, new Vector4f(.875f));
      targets.beginFrame(FOG_A);
      check(targets.needsColorDefinition(0) == lazy, "Queued color query");
      check(!targets.needsColorDefinition(2), "Retained history query");
      check(targets.needsDepthDefinition(1) == lazy, "Queued depth query");
      check(targets.needsShadowDepthDefinition(0) == lazy, "Queued shadow depth query");

      var geometry = targets.descriptor("Partial scene", List.of(0), false, false, true);
      check(geometry.colorAttachments().getFirst().clearValue().isPresent() == lazy, "Color fold");
      check(geometry.depthAttachment().clearValue().isPresent() == lazy, "Depth fold");
      try (var pass = encoder.createRenderPass(geometry)) {
        pass.setPipeline(writer);
        pass.draw(3, 1, 0, 0);
      }
      observe(device, targets, observations, "partial-color", targets.color(0));
      observe(device, targets, observations, "partial-depth", targets.depth(0));
      // Destination clears must not erase these complete copies when first sampled later.
      targets.snapshotOpaqueDepth();
      observe(device, targets, observations, "opaque-depth1", targets.depth(1));
      observe(device, targets, observations, "opaque-depth2", targets.depth(2));
      check(
          Arrays.equals(observations.get("partial-depth/0"), observations.get("opaque-depth1/0")),
          "Opaque depth1 copy");
      check(
          Arrays.equals(observations.get("partial-depth/0"), observations.get("opaque-depth2/0")),
          "Opaque depth2 copy");

      observe(device, targets, observations, "read-before-write", targets.color(1));
      check(!targets.needsColorDefinition(1), "Resolved sampled read query");
      var readFirst = targets.descriptor("Already read", List.of(1), false, false, false);
      check(readFirst.colorAttachments().getFirst().clearValue().isEmpty(), "Read flushed clear");
      try (var ignored = encoder.createRenderPass(readFirst)) {}
      observe(device, targets, observations, "read-after-write", targets.color(1));
      observe(device, targets, observations, "history-first", targets.color(2));

      // Pair parity can change independently per output; Clear=false retains both physical sides.
      targets.flip(List.of(0, 0, 2));
      targets.beginFrame(FOG_B);
      observe(device, targets, observations, "flipped-mips-read-before-write", targets.color(0));
      observe(device, targets, observations, "history-second", targets.color(2));
      observe(device, targets, observations, "history-first-retained", targets.alternate(2));
      var alreadyDefined = targets.descriptor("Defined mips", List.of(0), false, false, false);
      check(alreadyDefined.colorAttachments().getFirst().clearValue().isEmpty(), "Mips read flush");
      try (var ignored = encoder.createRenderPass(alreadyDefined)) {}

      // The chosen hand attachment must consume depth1's clear and leave depth0/depth2 pending.
      var hand = targets.descriptor("Hand only", List.of(), false, false, true, 1);
      check(hand.depthAttachment().textureView() == targets.depth(1).level(0), "Hand depth target");
      check(hand.depthAttachment().clearValue().isPresent() == lazy, "Hand clear fold");
      try (var ignored = encoder.createRenderPass(hand)) {}
      var scene = targets.descriptor("Scene only", List.of(), false, false, true, 0);
      check(scene.depthAttachment().clearValue().isPresent() == lazy, "Scene clear not consumed");
      try (var ignored = encoder.createRenderPass(scene)) {}
      observe(device, targets, observations, "hand-depth", targets.depth(1));
      observe(device, targets, observations, "scene-depth", targets.depth(0));
      observe(device, targets, observations, "untouched-depth2", targets.depth(2));

      // Copying before any render must read a defined source, rather than last frame's depth.
      targets.beginFrame(FOG_A);
      targets.snapshotOpaqueDepth();
      targets.copyShadowDepth();
      observe(device, targets, observations, "clear-only-copy1", targets.depth(1));
      observe(device, targets, observations, "clear-only-copy2", targets.depth(2));
      observe(device, targets, observations, "clear-only-shadow-copy", targets.shadowDepth(1));
      var shadow = targets.descriptor("Shadow", List.of(0), false, true, true);
      check(shadow.colorAttachments().getFirst().clearValue().isPresent() == lazy, "Shadow fold");
      check(shadow.depthAttachment().clearValue().isEmpty(), "Shadow copy resolved source");
      try (var ignored = encoder.createRenderPass(shadow)) {}
      observe(device, targets, observations, "shadow-color", targets.shadowColor(0));

      // A history reset supersedes pending values on both sides, including Clear=false targets.
      targets.resetHistory(FOG_B);
      for (int index : specs.keySet()) {
        observe(device, targets, observations, "reset-current-" + index, targets.color(index));
        observe(
            device, targets, observations, "reset-alternate-" + index, targets.alternate(index));
      }
      targets.color(7);
      observe(device, targets, observations, "late-current", targets.color(7));
      observe(device, targets, observations, "late-alternate", targets.alternate(7));

      // The merge rotates physical ownership; clearing the next frame must follow that rotation.
      targets.ensureDefined(targets.depth(0));
      targets.ensureDefined(targets.depth(1));
      encoder.clearDepthTexture(targets.depthMergeTarget().texture, .1875);
      targets.commitMergedDepth();
      observe(device, targets, observations, "merged-depth", targets.depth(0));
      targets.beginFrame(FOG_A);
      targets.snapshotOpaqueDepth();
      observe(device, targets, observations, "rotated-depth-next-frame", targets.depth(0));
      observe(device, targets, observations, "rotated-copy-next-frame", targets.depth(1));
    }
    return observations;
  }

  private static void observe(
      GpuDevice device,
      PackRenderTargets targets,
      Map<String, byte[]> results,
      String name,
      PackTexture texture) {
    targets.ensureDefined(texture);
    for (int mip = 0; mip < texture.texture.getMipLevels(); mip++) {
      int bytes = texture.texture.getWidth(mip) * texture.texture.getHeight(mip) * 4;
      try (var readback =
          device.createBuffer(
              () -> "Clear comparison readback",
              GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
              bytes)) {
        var encoder = device.createCommandEncoder();
        encoder.copyTextureToBuffer(texture.texture, readback, 0, () -> {}, mip);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(10_000_000_000L))
            throw new AssertionError("Clear GPU timeout");
        }
        byte[] data = new byte[bytes];
        try (var mapped = readback.map(true, false)) {
          mapped.data().get(data);
        }
        results.put(name + "/" + mip, data);
      }
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static CompiledRenderPipeline writer(GpuDevice device) {
    String vertex =
        "#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);"
            + "gl_Position=vec4(p*2.0-1.0,0.0,1.0);}";
    String fragment =
        "#version 450\nlayout(location=0)out vec4 c;void main(){"
            + "if(gl_FragCoord.x<3.0)discard;c=vec4(.75,.25,.5,1);gl_FragDepth=.375;}";
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX ? vertex : fragment;
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    var pipeline =
        device
            .compilePipeline(
                RenderPipeline.builder()
                    .withLocation("shader_test/lazy_clear")
                    .withVertexShader("shader_test/lazy_clear")
                    .withFragmentShader("shader_test/lazy_clear")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withColorTargetState(
                        new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                    .build(),
                source,
                Runnable::run)
            .join()
            .finishCompile();
    if (pipeline == null) throw new AssertionError("Clear test pipeline failed");
    return pipeline;
  }
}
