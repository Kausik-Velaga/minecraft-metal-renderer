package dev.kausik.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import sun.misc.Unsafe;

/** Exercises real vanilla sequential-index growth through the independent-view API, without GPU. */
public final class ScenePreparationIndexTest {
  private ScenePreparationIndexTest() {}

  public static void run() throws Exception {
    Field device = field(RenderSystem.class, "DEVICE");
    Field thread = field(RenderSystem.class, "renderThread");
    Object oldDevice = device.get(null), oldThread = thread.get(null);
    if (oldDevice != null) throw new AssertionError("CPU fixture must not replace a live GPU");
    thread.set(null, Thread.currentThread());
    var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
    Field buffer = field(indices.getClass(), "buffer");
    Field type = field(indices.getClass(), "type");
    Field count = field(indices.getClass(), "indexCount");
    Field requested = field(indices.getClass(), "maxRequestedIndexCount");
    Object oldBuffer = buffer.get(indices), oldType = type.get(indices);
    int oldCount = count.getInt(indices), oldRequested = requested.getInt(indices);
    int[] allocations = {0};
    try {
      buffer.set(indices, null);
      type.set(indices, IndexType.SHORT);
      count.setInt(indices, 0);
      requested.setInt(indices, 0);
      device.set(null, Proxy.newProxyInstance(GpuDevice.class.getClassLoader(),
          new Class<?>[] {GpuDevice.class}, (proxy, method, args) -> {
            if (method.getName().equals("createBuffer") && args[2] instanceof ByteBuffer data) {
              allocations[0]++;
              return new CpuBuffer(data.remaining(), (Integer) args[1]);
            }
            throw new AssertionError("Unexpected GPU operation: " + method);
          }));
      // Vanilla's earlier camera preparation committed a small mesh.
      indices.requestIndexCount(6);
      indices.resizeToRequestedIndexCount();
      GpuBuffer cameraBuffer = indices.getBuffer();
      var renderer = (PreparingRenderer) unsafe().allocateInstance(PreparingRenderer.class);
      renderer.area = (ViewArea) unsafe().allocateInstance(ViewArea.class);
      var selection = new SceneSelection(renderer.area, SceneViews.generation(), "late-shadow",
          List.of(), 0, 0, 0, "fixture");

      // A late indirect view sees a larger off-camera section. Requesting alone leaves the old
      // buffer undersized; the public prepare boundary must commit it before returning to draw.
      renderer.indirect = true;
      renderer.requested = 96;
      check(!indices.hasStorage(renderer.requested), "Fixture did not require index growth");
      SceneViews.prepare(renderer, selection, new Matrix4f(), false);
      check("late-shadow".equals(renderer.preparedView)
              && ScopedSectionSelection.viewId(renderer) == null,
          "Preparation view tag leaked outside its scope");
      check(indices.hasStorage(96) && indices.getBuffer().size() >= 96L * indices.type().bytes,
          "Late indirect preparation returned an undersized index buffer");
      check(cameraBuffer.isClosed(), "Growing storage did not retire the previous buffer");

      // The same boundary covers direct views and must realize the index-width transition too.
      renderer.indirect = false;
      renderer.requested = 100_002;
      SceneViews.prepare(renderer, selection, new Matrix4f(), false);
      check(indices.type() == IndexType.INT && indices.hasStorage(renderer.requested),
          "Late direct preparation retained the previous index width or capacity");
      check(indices.getBuffer().size() >= (long) renderer.requested * IndexType.INT.bytes,
          "Wider sequential indices do not fit their buffer");
      int previousAllocations = allocations[0];
      renderer.requested = 6;
      SceneViews.prepare(renderer, selection, new Matrix4f(), false);
      check(allocations[0] == previousAllocations, "Prepared views unnecessarily rebuilt storage");
      check(renderer.directCalls == 2 && renderer.indirectCalls == 1,
          "Independent view selected the wrong vanilla preparation path");
    } finally {
      if (indices.getBuffer() instanceof CpuBuffer temporary) temporary.close();
      buffer.set(indices, oldBuffer);
      type.set(indices, oldType);
      count.setInt(indices, oldCount);
      requested.setInt(indices, oldRequested);
      device.set(null, oldDevice);
      thread.set(null, oldThread);
    }
  }

  private static Field field(Class<?> owner, String name) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static Unsafe unsafe() throws Exception {
    return (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  /** Only the preparation result is faked; vanilla's storage, growth and index typing are real. */
  private static final class PreparingRenderer extends LevelRenderer {
    private ViewArea area;
    private boolean indirect;
    private int requested, directCalls, indirectCalls;
    private String preparedView;

    private PreparingRenderer() {
      super(null, null, null, null, null, null, null, 0, 0);
      throw new AssertionError("Fixture must bypass unrelated renderer construction");
    }

    @Override public ViewArea viewArea() { return area; }
    @Override public boolean isChunkRenderingUsingMultiDrawIndirect() { return indirect; }

    @Override public ChunkSectionsToRender prepareChunkRendersIndirect(Matrix4fc view, boolean sort) {
      indirectCalls++;
      return request();
    }

    @Override public ChunkSectionsToRender prepareChunkRenders(Matrix4fc view, boolean sort) {
      directCalls++;
      return request();
    }

    private ChunkSectionsToRender request() {
      preparedView = ScopedSectionSelection.viewId(this);
      RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(requested);
      return new ChunkSectionsToRender.DrawIndirect(null,
          new EnumMap<>(ChunkSectionLayer.class), requested, null);
    }
  }

  private static final class CpuBuffer implements GpuBuffer {
    private final long size;
    private final int usage;
    private boolean closed;

    private CpuBuffer(long size, int usage) { this.size = size; this.usage = usage; }
    @Override public long size() { return size; }
    @Override public int usage() { return usage; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; }
    @Override public GpuBufferSlice.MappedView map(long offset, long size, boolean read, boolean write) {
      throw new AssertionError("Sequential-index creation should upload initial data directly");
    }
  }
}
