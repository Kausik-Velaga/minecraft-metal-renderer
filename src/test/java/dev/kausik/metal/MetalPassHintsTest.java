package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import org.joml.Vector4f;

/** CPU-only ownership/eligibility tests: hints cannot escape their descriptor or creation scope. */
public final class MetalPassHintsTest {
  public static void main(String[] args) throws Exception {
    var view = new View(0, 1, 8, 8);
    var a = RenderPassDescriptor.builder(() -> "a").withColorAttachment(view).build();
    var b = new RenderPassDescriptor(a.label(), a.colorAttachments(), null, a.renderArea());
    if (!a.equals(b)) throw new AssertionError("Identity fixture must be structurally equal");
    try (var outer = MetalPassHints.openDiscardColors(a, 1)) {
      expect(b, 0);
      var observed = new java.util.concurrent.atomic.AtomicInteger(-1);
      Thread unrelated = new Thread(() -> observed.set(MetalPassHints.consumeDiscardColors(a)));
      unrelated.start();
      unrelated.join();
      if (observed.get() != 0) throw new AssertionError("Hint leaked to another thread");
      try (var inner = MetalPassHints.openDiscardColors(b, 1)) {
        expect(a, 0);
        expect(b, 1);
        expect(b, 0);
      }
      expect(a, 1);
      expect(a, 0);
    }
    expect(a, 0);
    try (var ignored = MetalPassHints.openDiscardColors(a, 1)) {
      // An unconsumed hint expires when a synchronous creation path exits exceptionally.
    }
    expect(a, 0);
    checked(
        RenderPassDescriptor.builder(() -> "partial")
            .withColorAttachment(view)
            .withRenderArea(new RenderPass.RenderArea(0, 0, 7, 8))
            .build(),
        0);
    checked(
        RenderPassDescriptor.builder(() -> "offset")
            .withColorAttachment(view)
            .withRenderArea(new RenderPass.RenderArea(1, 0, 8, 8))
            .build(),
        0);
    checked(
        RenderPassDescriptor.builder(() -> "depth")
            .withColorAttachment(view)
            .withDepthAttachment(view)
            .build(),
        0);
    checked(
        RenderPassDescriptor.builder(() -> "mip").withColorAttachment(new View(1, 1, 4, 4)).build(),
        0);
    checked(
        RenderPassDescriptor.builder(() -> "array")
            .withColorAttachment(new View(0, 2, 8, 8))
            .build(),
        0);
    checked(
        RenderPassDescriptor.builder(() -> "mixed size")
            .withColorAttachment(view)
            .withColorAttachment(new View(0, 1, 4, 4))
            .build(),
        0);
    var sparse =
        RenderPassDescriptor.builder(() -> "sparse clear priority")
            .withColorAttachment(view, Optional.of(new Vector4f(1)))
            .withUnusedColorAttachment()
            .withColorAttachment(view)
            .build();
    checked(sparse, 4);
    checked(
        RenderPassDescriptor.builder(() -> "explicit full area")
            .withColorAttachment(view)
            .withRenderArea(new RenderPass.RenderArea(0, 0, 8, 8))
            .build(),
        1);
    System.out.println(
        "PASS: discard hints are identity-bound, one-shot, nested/thread-local and"
            + " expire at scope exit; partial/depth/mip/array passes rejected, clears preserved");
  }

  private static void checked(RenderPassDescriptor descriptor, int expected) {
    try (var ignored = MetalPassHints.openDiscardColors(descriptor, 255)) {
      expect(descriptor, expected);
      expect(descriptor, 0);
    }
  }

  private static void expect(RenderPassDescriptor descriptor, int expected) {
    int value = MetalPassHints.consumeDiscardColors(descriptor);
    if (value != expected) throw new AssertionError("Discard mask " + value + " != " + expected);
  }

  private record Texture(int layers, int width, int height) implements GpuTexture {
    public int getWidth(int mip) {
      return width >> mip;
    }

    public int getHeight(int mip) {
      return height >> mip;
    }

    public int getDepthOrLayers() {
      return layers;
    }

    public int getMipLevels() {
      return 4;
    }

    public GpuFormat getFormat() {
      return GpuFormat.RGBA8_UNORM;
    }

    public int usage() {
      return USAGE_RENDER_ATTACHMENT;
    }

    public String getLabel() {
      return "hint test";
    }

    public boolean isClosed() {
      return false;
    }

    public void close() {}
  }

  private record View(int baseMipLevel, int layers, int width, int height)
      implements GpuTextureView {
    public GpuTexture texture() {
      return new Texture(layers, width << baseMipLevel, height << baseMipLevel);
    }

    public int mipLevels() {
      return 1;
    }

    public int getWidth(int mip) {
      return width >> mip;
    }

    public int getHeight(int mip) {
      return height >> mip;
    }

    public boolean isClosed() {
      return false;
    }

    public void close() {}
  }
}
