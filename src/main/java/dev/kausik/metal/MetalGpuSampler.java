package dev.kausik.metal;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public final class MetalGpuSampler implements GpuSampler {
  private final AddressMode addressU;
  private final AddressMode addressV;
  private final FilterMode minFilter;
  private final FilterMode magFilter;
  private final int maxAnisotropy;
  private final OptionalDouble maxLod;
  private long handle;

  public MetalGpuSampler(
      MetalDevice device,
      AddressMode addressU,
      AddressMode addressV,
      FilterMode minFilter,
      FilterMode magFilter,
      int maxAnisotropy,
      OptionalDouble maxLod) {
    this.addressU = addressU;
    this.addressV = addressV;
    this.minFilter = minFilter;
    this.magFilter = magFilter;
    this.maxAnisotropy = maxAnisotropy;
    this.maxLod = maxLod;
    handle =
        MetalNative.createSampler(
            device.handle(),
            addressU == AddressMode.REPEAT,
            addressV == AddressMode.REPEAT,
            minFilter == FilterMode.LINEAR,
            magFilter == FilterMode.LINEAR,
            maxAnisotropy,
            maxLod.orElse(Double.POSITIVE_INFINITY));
    if (handle == 0) throw new IllegalStateException("Metal sampler allocation failed");
  }

  public long handle() {
    if (handle == 0) throw new IllegalStateException("Sampler is closed");
    return handle;
  }

  @Override
  public AddressMode getAddressModeU() {
    return addressU;
  }

  @Override
  public AddressMode getAddressModeV() {
    return addressV;
  }

  @Override
  public FilterMode getMinFilter() {
    return minFilter;
  }

  @Override
  public FilterMode getMagFilter() {
    return magFilter;
  }

  @Override
  public int getMaxAnisotropy() {
    return maxAnisotropy;
  }

  @Override
  public OptionalDouble getMaxLod() {
    return maxLod;
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
