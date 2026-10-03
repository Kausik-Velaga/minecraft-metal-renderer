package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Shared allocations support persistent mapping; static geometry can remain GPU-private. */
public class MetalGpuBuffer extends com.mojang.renderpearl.backend.common.BaseGpuBuffer {
  private long handle;
  private int mappings;

  /** Non-owning subclasses provide their own handle and lifetime, e.g. transient arena views. */
  MetalGpuBuffer(int usage, long size) {
    super(usage, size);
  }

  public MetalGpuBuffer(MetalDevice device, int usage, long size, String label) {
    super(usage, size);
    if (size < 0) throw new IllegalArgumentException("Negative buffer size");
    boolean shared = (usage & (USAGE_MAP_READ | USAGE_MAP_WRITE | USAGE_HINT_CLIENT_STORAGE)) != 0;
    handle = MetalNative.createBuffer(device.handle(), size, shared, label);
    if (handle == 0) throw new IllegalStateException("Metal buffer allocation failed: " + label);
  }

  public long handle() {
    if (isClosed()) throw new IllegalStateException("Buffer is closed");
    return handle;
  }

  @Override
  public boolean isClosed() {
    return handle == 0;
  }

  @Override
  public void close() {
    if (handle == 0) return;
    if (mappings != 0)
      throw new IllegalStateException("Cannot close a buffer with active mapped views");
    MetalNative.release(handle);
    handle = 0;
  }

  @Override
  public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
    long nativeHandle = handle();
    if (!read && !write)
      throw new IllegalArgumentException("Mapping must enable reading or writing");
    if (read && (usage() & USAGE_MAP_READ) == 0)
      throw new IllegalStateException("Buffer is not readable");
    if (write && (usage() & USAGE_MAP_WRITE) == 0)
      throw new IllegalStateException("Buffer is not writable");
    if (offset < 0 || length < 0 || offset > size() || length > size() - offset) {
      throw new IllegalArgumentException("Mapped range exceeds buffer bounds");
    }
    if (length > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Mapped view exceeds Java's 2 GB limit");
    // As in Blaze3D's Vulkan backend, the caller must fence GPU writes before CPU reads.
    ByteBuffer bytes =
        MetalNative.mapBuffer(nativeHandle, offset, length).order(ByteOrder.nativeOrder());
    if (!write) bytes = bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    mappings++;
    return new GpuBufferSlice.MappedView(
        slice(offset, length),
        bytes,
        new Runnable() {
          private boolean closed;

          @Override
          public void run() {
            if (!closed) {
              closed = true;
              mappings--;
            }
          }
        });
  }
}
