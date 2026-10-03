package dev.kausik.metal;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.OptionalLong;

final class MetalQueryPool implements GpuQueryPool {
  private final MetalDevice device;
  private final int size;
  private long handle;

  MetalQueryPool(MetalDevice device, int size) {
    this.device = device;
    this.size = size;
    handle = MetalNative.createQueryPool(device.handle(), size);
  }

  public int size() {
    return size;
  }

  private void check(int index) {
    if (handle == 0) throw new IllegalStateException("Query pool closed");
    if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
  }

  void write(int index) {
    check(index);
    MetalNative.writeTimestamp(device.handle(), handle, index);
  }

  public OptionalLong getValue(int index) {
    check(index);
    long value = MetalNative.queryValue(handle, index);
    return value < 0 ? OptionalLong.empty() : OptionalLong.of(value);
  }

  public OptionalLong[] getValues(int first, int count) {
    OptionalLong[] values = new OptionalLong[count];
    for (int i = 0; i < count; i++) values[i] = getValue(first + i);
    return values;
  }

  public void close() {
    if (handle != 0) {
      MetalNative.release(handle);
      handle = 0;
    }
  }
}
