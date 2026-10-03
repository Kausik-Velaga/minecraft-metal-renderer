package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;

public final class MetalGpuTexture extends com.mojang.renderpearl.backend.common.BaseGpuTexture {
  private long handle;

  public MetalGpuTexture(
      MetalDevice device,
      int usage,
      String label,
      GpuFormat format,
      int width,
      int height,
      int layers,
      int mipLevels) {
    super(usage, label, format, width, height, layers, mipLevels);
    handle =
        MetalNative.createTexture(
            device.handle(), format.name(), width, height, layers, mipLevels, usage, label);
    if (handle == 0) throw new IllegalStateException("Metal texture allocation failed: " + label);
  }

  public long handle() {
    if (isClosed()) throw new IllegalStateException("Texture is closed: " + getLabel());
    return handle;
  }

  @Override
  public boolean isClosed() {
    return handle == 0;
  }

  @Override
  public void close() {
    if (handle == 0) return;
    MetalNative.release(handle);
    handle = 0;
  }
}
