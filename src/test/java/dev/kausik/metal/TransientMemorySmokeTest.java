package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.util.List;

/** Regression checks for arena alignment, empty uploads, ownership, and in-flight reuse. */
public final class TransientMemorySmokeTest {
  public static void main(String[] args) {
    MetalDevice device = new MetalDevice();
    try (MetalTransientMemory memory = new MetalTransientMemory(device)) {
      GpuBufferSlice first = memory.allocateGpu(1, 16, GpuBuffer.USAGE_VERTEX);
      GpuBufferSlice packed = memory.allocateGpu(12, 12, GpuBuffer.USAGE_VERTEX);
      check(packed.offset() % 12 == 0, "Caller alignment of 12 bytes was lost");
      check(packed.offset() % 16 == 0, "Metal's minimum alignment was lost");
      GpuBufferSlice stride = memory.allocateGpu(24, 24, GpuBuffer.USAGE_VERTEX);
      check(stride.offset() % 24 == 0, "Caller alignment of 24 bytes was lost");
      check(
          stride.buffer().usage() == GpuBuffer.USAGE_VERTEX, "Transient usage leaked arena flags");

      ByteBuffer source = ByteBuffer.wrap(new byte[] {99, 1, 2, 3, 88});
      source.position(1).limit(4);
      GpuBufferSlice uploaded =
          memory.uploadGpu(
              List.of(
                  ByteBuffer.allocate(0),
                  source,
                  ByteBuffer.allocate(0),
                  ByteBuffer.wrap(new byte[] {4, 5})),
              4,
              GpuBuffer.USAGE_COPY_SRC);
      ByteBuffer read =
          MetalNative.mapBuffer(handle(uploaded), uploaded.offset(), uploaded.length());
      check(
          read.get(0) == 1
              && read.get(1) == 2
              && read.get(2) == 3
              && read.get(4) == 4
              && read.get(5) == 5,
          "An empty upload discarded subsequent data");
      check(source.position() == 1 && source.limit() == 4, "Upload changed source buffer bounds");
      check(
          memory.uploadGpu(List.of(), 4, GpuBuffer.USAGE_COPY_SRC).length() == 0,
          "Empty upload has nonzero size");
      check(
          memory
                  .multiUploadGpu(
                      List.of(ByteBuffer.allocate(0), ByteBuffer.wrap(new byte[] {7})),
                      4,
                      GpuBuffer.USAGE_COPY_SRC)
                  .get(1)
                  .length()
              == 4,
          "Multi-upload failed after an empty source");

      long liveArena = handle(packed);
      first.buffer().close();
      check(first.buffer().isClosed(), "Closing transient view did not close that view");
      check(handle(packed) == liveArena, "Closing a transient view destroyed its sibling's arena");
      expectFailure(() -> packed.map(false, true), "Transient buffer allowed remapping");
      expectFailure(() -> packed.buffer().slice(), "Transient buffer exposed the entire arena");

      try (MetalGpuBuffer output =
          new MetalGpuBuffer(
              device,
              GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
              uploaded.length(),
              "Transient smoke readback")) {
        long oldArena = handle(uploaded);
        MetalNative.copyBuffer(
            device.handle(), oldArena, uploaded.offset(), output.handle(), 0, uploaded.length());
        Runnable retired = memory.retire();
        check(uploaded.buffer().isClosed(), "Previous submission's transient view remained live");
        expectFailure(() -> handle(uploaded), "Expired transient handle remained usable");
        GpuBufferSlice next =
            memory.uploadGpu(
                ByteBuffer.wrap(new byte[] {42, 42, 42, 42}), 4, GpuBuffer.USAGE_COPY_SRC);
        check(handle(next) != oldArena, "Arena was reused before its submission completed");
        long fence = MetalNative.createFence(device.handle());
        MetalNative.submit(device.handle());
        check(
            MetalNative.awaitFence(fence, 5_000_000_000L),
            "GPU did not complete the transient copy");
        MetalNative.release(fence);
        retired.run();
        try (var mapped = output.map(true, false)) {
          check(
              mapped.data().get(0) == 1 && mapped.data().get(4) == 4,
              "Later submission overwrote in-flight transient data");
        }
      }
      System.out.println(
          "PASS: transient alignment, empty uploads, source slices, independent view ownership,"
              + " expiration, and fenced GPU lifetime");
    } finally {
      device.close();
    }
  }

  private static long handle(GpuBufferSlice slice) {
    return ((MetalGpuBuffer) slice.buffer()).handle();
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void expectFailure(Runnable operation, String message) {
    try {
      operation.run();
    } catch (IllegalStateException expected) {
      return;
    }
    throw new AssertionError(message);
  }
}
