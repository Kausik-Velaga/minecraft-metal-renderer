package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/** Persistent shared upload arenas retire only after their submission fence has completed. */
final class MetalTransientMemory implements TransientMemory, AutoCloseable {
  private static final int ARENA_USAGE =
      GpuBuffer.USAGE_MAP_READ
          | GpuBuffer.USAGE_MAP_WRITE
          | GpuBuffer.USAGE_HINT_CLIENT_STORAGE
          | GpuBuffer.USAGE_COPY_DST
          | GpuBuffer.USAGE_COPY_SRC
          | GpuBuffer.USAGE_VERTEX
          | GpuBuffer.USAGE_INDEX
          | GpuBuffer.USAGE_UNIFORM
          | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
          | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
  private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpu =
      new TransientBlockAllocator<>(
          1 << 20, 16, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
  private final TransientBlockAllocator<GpuBlock> gpu;

  private record GpuBlock(MetalGpuBuffer buffer)
      implements TransientBlockAllocator.Allocator.Block {
    @Override
    public boolean suboptimal() {
      return false;
    }
  }

  private long submission;
  private boolean closed;

  MetalTransientMemory(MetalDevice device) {
    gpu =
        new TransientBlockAllocator<>(
            1 << 20,
            1L << 30,
            TransientBlockAllocator.Allocator.create(
                size ->
                    new GpuBlock(
                        new MetalGpuBuffer(device, ARENA_USAGE, size, "Metal transient arena")),
                block -> block.buffer().close()));
  }

  Runnable retire() {
    requireOpen();
    submission++;
    Runnable cpuRetire = cpu.rotate();
    Runnable gpuRetire = gpu.rotate();
    return () -> {
      cpuRetire.run();
      gpuRetire.run();
    };
  }

  @Override
  public ByteBuffer allocateCpu(long size, long alignment, long minimum, long element) {
    validateAllocation(size, alignment, minimum, element);
    var allocation = cpu.allocate(size, alignment, minimum, element);
    return MemoryUtil.memByteBuffer(
            allocation.block().address() + allocation.offset(), Math.toIntExact(allocation.size()))
        .order(ByteOrder.nativeOrder());
  }

  @Override
  public GpuBufferSlice.MappedView allocateStaging(
      long size, long alignment, int usage, long minimum, long element) {
    return allocateGpuMapped(size, alignment, usage, minimum, element);
  }

  @Override
  public GpuBufferSlice allocateGpu(
      long size, long alignment, int usage, long minimum, long element) {
    validateAllocation(size, alignment, minimum, element);
    var allocation = gpu.allocate(size, metalAlignment(alignment), minimum, element);
    return new GpuBufferSlice(
        new TransientBuffer(allocation.block().buffer(), usage),
        allocation.offset(),
        allocation.size());
  }

  @Override
  public GpuBufferSlice.MappedView allocateGpuMapped(
      long size, long alignment, int usage, long minimum, long element) {
    GpuBufferSlice slice = allocateGpu(size, alignment, usage, minimum, element);
    // The arena itself is permanently mapped. Public transient views deliberately cannot map
    // or own the arena; the returned pointer has the same submission lifetime as the slice.
    ByteBuffer data =
        MetalNative.mapBuffer(
                ((MetalGpuBuffer) slice.buffer()).handle(), slice.offset(), slice.length())
            .order(ByteOrder.nativeOrder());
    return new GpuBufferSlice.MappedView(slice, data, () -> {});
  }

  private void validateAllocation(long size, long alignment, long minimum, long element) {
    requireOpen();
    if (size < 0
        || size > Integer.MAX_VALUE
        || minimum < 0
        || minimum > size
        || alignment <= 0
        || element <= 0)
      throw new IllegalArgumentException(
          "Invalid transient allocation size/alignment/minimum/element: "
              + size
              + "/"
              + alignment
              + "/"
              + minimum
              + "/"
              + element);
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Transient memory is closed");
  }

  private static long metalAlignment(long requested) {
    // max(requested, 16) breaks non-power-of-two alignments, e.g. 24 or packed vertex strides.
    long a = requested, b = 16;
    while (b != 0) {
      long remainder = a % b;
      a = b;
      b = remainder;
    }
    return Math.multiplyExact(requested / a, 16);
  }

  private static long align(long value, long alignment) {
    return Math.multiplyExact(
        Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment);
  }

  @Override
  public GpuBufferSlice uploadStaging(
      List<ByteBuffer> data, long alignment, int usage, long minimum, long element) {
    return uploadGpu(data, alignment, usage, minimum, element);
  }

  @Override
  public GpuBufferSlice uploadGpu(
      List<ByteBuffer> data, long alignment, int usage, long minimum, long element) {
    if (alignment <= 0) throw new IllegalArgumentException("Upload alignment must be positive");
    long total = 0;
    for (ByteBuffer bytes : data) total = align(Math.addExact(total, bytes.remaining()), alignment);
    try (var mapped = allocateGpuMapped(total, alignment, usage, minimum, element)) {
      long offset = 0;
      for (ByteBuffer bytes : data) {
        if (offset >= mapped.slice().length()) break;
        int count = (int) Math.min(bytes.remaining(), mapped.slice().length() - offset);
        if (count == 0) continue; // An empty source must not discard later nonempty sources.
        ByteBuffer source = bytes.duplicate();
        source.limit(source.position() + count);
        mapped.data().duplicate().position(Math.toIntExact(offset)).put(source);
        offset = align(offset + count, alignment);
      }
      return mapped.slice();
    }
  }

  @Override
  public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
    return multiUploadGpu(data, alignment, usage);
  }

  @Override
  public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
    List<GpuBufferSlice> result = new ArrayList<>(data.size());
    for (ByteBuffer bytes : data) result.add(uploadGpu(bytes, alignment, usage));
    return result;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    submission++;
    cpu.close();
    gpu.close();
  }

  /** API allocations expire at submit; closing one allocation never destroys its shared arena. */
  private final class TransientBuffer extends MetalGpuBuffer {
    private final MetalGpuBuffer arena;
    private final long allocatedSubmission = submission;
    private boolean released;

    private TransientBuffer(MetalGpuBuffer arena, int usage) {
      super(usage, arena.size());
      this.arena = arena;
    }

    @Override
    public long handle() {
      if (isClosed())
        throw new IllegalStateException(
            "Transient buffer was closed or belongs to an earlier submission");
      return arena.handle();
    }

    @Override
    public boolean isClosed() {
      return released || closed || submission != allocatedSubmission || arena.isClosed();
    }

    @Override
    public void close() {
      released = true;
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
      throw new IllegalStateException(
          "Cannot remap transient memory; use allocateGpuMapped or allocateStaging");
    }

    @Override
    public GpuBufferSlice slice() {
      throw new IllegalStateException(
          "Cannot slice a transient arena buffer; slice the allocation instead");
    }

    @Override
    public GpuBufferSlice slice(long offset, long length) {
      throw new IllegalStateException(
          "Cannot slice a transient arena buffer; slice the allocation instead");
    }
  }
}
