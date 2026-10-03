package dev.kausik.metal;

import com.mojang.renderpearl.api.commands.GpuFence;

final class MetalFence implements GpuFence {
  private long handle;

  MetalFence(long device) {
    handle = MetalNative.createFence(device);
  }

  public boolean awaitCompletion(long timeoutNanos) {
    return handle == 0 || MetalNative.awaitFence(handle, timeoutNanos);
  }

  public void close() {
    if (handle != 0) {
      MetalNative.release(handle);
      handle = 0;
    }
  }
}
