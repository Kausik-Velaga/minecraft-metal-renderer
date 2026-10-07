package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;

public final class MetalGpuTexture extends com.mojang.renderpearl.backend.common.BaseGpuTexture {
  private final MetalDevice device;
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
    this.device = device;
    handle =
        MetalNative.createTexture(
            device.handle(), format.name(), width, height, layers, mipLevels, usage, label);
    if (handle == 0) throw new IllegalStateException("Metal texture allocation failed: " + label);
  }

  public long handle() {
    if (isClosed()) throw new IllegalStateException("Texture is closed: " + getLabel());
    return handle;
  }

  /** Backend extension for generating a full mip chain without separate raster passes. */
  public void generateMipmaps(int levels) {
    MetalNative.generateMipmaps(device.handle(), handle(), levels);
  }

  @Override
  public int getWidth(int mipLevel) {
    // The smaller axis reaches one before the larger axis in a rectangular mip chain.
    return Math.max(1, super.getWidth(mipLevel));
  }

  @Override
  public int getHeight(int mipLevel) {
    return Math.max(1, super.getHeight(mipLevel));
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
