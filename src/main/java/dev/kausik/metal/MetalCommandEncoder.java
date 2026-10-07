package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.joml.Vector4fc;

final class MetalCommandEncoder implements CommandEncoderBackend, AutoCloseable {
  private final MetalDevice device;
  private final MetalTransientMemory transientMemory;
  private final ArrayDeque<Retirement> retirements = new ArrayDeque<>();
  private final List<Runnable> callbacks = new ArrayList<>();
  private boolean inPass;

  private record Retirement(GpuFence fence, Runnable action) {}

  MetalCommandEncoder(MetalDevice device) {
    this.device = device;
    transientMemory = new MetalTransientMemory(device);
  }

  private long h() {
    return device.handle();
  }

  static long buffer(GpuBuffer b) {
    return ((MetalGpuBuffer) b).handle();
  }

  static long texture(GpuTexture t) {
    return ((MetalGpuTexture) t).handle();
  }

  static long view(GpuTextureView v) {
    return ((MetalGpuTextureView) v).handle();
  }

  public void submit() {
    if (inPass) throw new IllegalStateException("Close render pass before submit");
    GpuFence fence = createFence();
    Runnable retiredMemory = transientMemory.retire();
    List<Runnable> completed = List.copyOf(callbacks);
    callbacks.clear();
    MetalNative.submit(h());
    retirements.add(
        new Retirement(
            fence,
            () -> {
              retiredMemory.run();
              completed.forEach(Runnable::run);
            }));
    drain(false);
  }

  private void drain(boolean all) {
    while (!retirements.isEmpty()) {
      Retirement next = retirements.peek();
      long timeout = all || retirements.size() > 3 ? 5_000_000_000L : 0;
      if (!next.fence.awaitCompletion(timeout)) {
        if (timeout != 0)
          throw new IllegalStateException("Metal GPU submission timed out after 5 seconds");
        break;
      }
      retirements.remove();
      next.fence.close();
      next.action.run();
    }
  }

  public TransientMemory transientMemory() {
    return transientMemory;
  }

  public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
    if (inPass) throw new IllegalStateException("Render pass already open");
    int count = descriptor.colorAttachments().size();
    long[] colors = new long[count];
    float[] clears = new float[count * 4];
    Arrays.fill(clears, Float.NaN);
    int width = 0, height = 0;
    for (int i = 0; i < count; i++) {
      var a = descriptor.colorAttachments().get(i);
      if (a == null) continue;
      colors[i] = view(a.textureView());
      width = a.textureView().getWidth(0);
      height = a.textureView().getHeight(0);
      if (a.clearValue().isPresent()) {
        Vector4fc c = a.clearValue().get();
        clears[i * 4] = c.x();
        clears[i * 4 + 1] = c.y();
        clears[i * 4 + 2] = c.z();
        clears[i * 4 + 3] = c.w();
      }
    }
    long depth = 0;
    double clearDepth = Double.NaN;
    if (descriptor.depthAttachment() != null) {
      var a = descriptor.depthAttachment();
      depth = view(a.textureView());
      clearDepth = a.clearValue().orElse(Double.NaN);
      width = a.textureView().getWidth(0);
      height = a.textureView().getHeight(0);
    }
    var area =
        descriptor.renderArea() != null
            ? descriptor.renderArea()
            : new RenderPass.RenderArea(0, 0, width, height);
    MetalNative.beginRenderPassWithDiscard(
        h(),
        descriptor.label().get(),
        colors,
        clears,
        depth,
        clearDepth,
        area.x(),
        area.y(),
        area.width(),
        area.height(),
        MetalPassHints.consumeDiscardColors(descriptor));
    inPass = true;
    return new MetalRenderPass(device, descriptor, width, height);
  }

  public void submitRenderPass() {
    if (!inPass) throw new IllegalStateException("No open render pass");
    MetalNative.endRenderPass(h());
    inPass = false;
  }

  public void clearColorTexture(GpuTexture t, Vector4fc c) {
    clearAll(t, c, null, 1);
  }

  public void clearColorAndDepthTextures(GpuTexture t, Vector4fc c, GpuTexture depth, double d) {
    clearAll(t, c, depth, d);
  }

  public void clearColorAndDepthTextures(
      GpuTexture t,
      Vector4fc c,
      GpuTexture depth,
      double d,
      int x,
      int y,
      int w,
      int height,
      int mip) {
    MetalNative.clearMip(
        h(), texture(t), c.x(), c.y(), c.z(), c.w(), texture(depth), d, x, y, w, height, mip);
  }

  public void clearDepthTexture(GpuTexture depth, double d) {
    clearAll(null, null, depth, d);
  }

  private void clearAll(GpuTexture t, Vector4fc c, GpuTexture depth, double d) {
    MetalNative.clearAll(
        h(),
        t == null ? 0 : texture(t),
        c == null ? 0 : c.x(),
        c == null ? 0 : c.y(),
        c == null ? 0 : c.z(),
        c == null ? 0 : c.w(),
        depth == null ? 0 : texture(depth),
        d);
  }

  public void writeToBuffer(GpuBufferSlice target, ByteBuffer data) {
    MetalNative.writeBuffer(
        h(), buffer(target.buffer()), target.offset(), data, data.position(), data.remaining());
  }

  public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
    MetalNative.copyBuffer(
        h(),
        buffer(source.buffer()),
        source.offset(),
        buffer(target.buffer()),
        target.offset(),
        source.length());
  }

  public void writeToTexture(
      GpuTexture target, ByteBuffer data, int mip, int layer, int x, int y, int w, int height) {
    MetalNative.uploadTexture(
        h(), texture(target), data, data.position(), mip, layer, x, y, w, height);
  }

  public void copyBufferToTexture(
      GpuBufferSlice source,
      int sx,
      int sy,
      int sw,
      int sh,
      GpuTexture target,
      int dx,
      int dy,
      int w,
      int height,
      int mip,
      int layer) {
    int texel = target.getFormat().blockSize();
    MetalNative.bufferToTexture(
        h(),
        buffer(source.buffer()),
        source.offset() + ((long) sx + (long) sy * sw) * texel,
        sw * texel,
        sh,
        texture(target),
        mip,
        layer,
        dx,
        dy,
        w,
        height);
  }

  public void copyTextureToBuffer(
      GpuTexture source, GpuBuffer target, long offset, Runnable callback, int mip) {
    copyTextureToBuffer(
        source, target, offset, callback, mip, 0, 0, source.getWidth(mip), source.getHeight(mip));
  }

  public void copyTextureToBuffer(
      GpuTexture source,
      GpuBuffer target,
      long offset,
      Runnable callback,
      int mip,
      int x,
      int y,
      int w,
      int height) {
    MetalNative.textureToBuffer(h(), texture(source), mip, x, y, w, height, buffer(target), offset);
    callbacks.add(callback);
  }

  public void copyTextureToTexture(
      GpuTexture source,
      GpuTexture target,
      int mip,
      int dx,
      int dy,
      int sx,
      int sy,
      int w,
      int height) {
    MetalNative.copyTexture(h(), texture(source), texture(target), mip, sx, sy, dx, dy, w, height);
  }

  public GpuFence createFence() {
    return new MetalFence(h());
  }

  public void writeTimestamp(GpuQueryPool pool, int index) {
    ((MetalQueryPool) pool).write(index);
  }

  public void close() {
    submit();
    drain(true);
    transientMemory.close();
  }
}
