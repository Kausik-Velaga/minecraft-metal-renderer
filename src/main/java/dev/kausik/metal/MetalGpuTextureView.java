package dev.kausik.metal;


public final class MetalGpuTextureView
    extends com.mojang.renderpearl.backend.common.BaseGpuTextureView {
  private long handle;

  public MetalGpuTextureView(MetalGpuTexture texture, int baseMip, int mipCount) {
    super(texture, baseMip, mipCount);
    if (baseMip < 0 || mipCount < 1 || baseMip > texture.getMipLevels() - mipCount) {
      throw new IllegalArgumentException("Texture view mip range exceeds texture");
    }
    handle = MetalNative.createTextureView(texture.handle(), baseMip, mipCount);
    if (handle == 0)
      throw new IllegalStateException(
          "Metal texture view allocation failed: " + texture.getLabel());
  }

  public long handle() {
    if (isClosed()) throw new IllegalStateException("Texture view is closed");
    return handle;
  }

  @Override
  public boolean isClosed() {
    return handle == 0 || texture().isClosed();
  }

  @Override
  public void close() {
    if (handle == 0) return;
    MetalNative.release(handle);
    handle = 0;
  }
}
