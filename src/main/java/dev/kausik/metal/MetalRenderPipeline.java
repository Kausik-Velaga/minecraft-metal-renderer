package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.util.List;

/** Native pipeline state plus the frontend's numeric resource-binding table. */
public final class MetalRenderPipeline implements BackendRenderPipeline {
  public enum Kind {
    UNIFORM_BUFFER,
    TEXTURE,
    TEXEL_BUFFER
  }

  public record Binding(int stageMask, int index, Kind kind, GpuFormat gpuFormat, String name) {}

  private final String name;
  private final List<Binding> bindings;
  private final int primitiveTopology;
  private final int pushConstantsSize;
  private final int pushConstantsStageMask;
  private long handle;

  MetalRenderPipeline(CreateInfo info, long handle, List<Binding> bindings, int pushStages) {
    name = info.name();
    this.handle = handle;
    this.bindings = List.copyOf(bindings);
    primitiveTopology = MetalMappings.primitiveTopology(info.primitiveTopology());
    pushConstantsSize = info.pushConstantsSize();
    pushConstantsStageMask = pushStages;
  }

  public String name() {
    return name;
  }

  public long handle() {
    if (handle == 0) throw new IllegalStateException("Pipeline is closed: " + name);
    return handle;
  }

  public List<Binding> bindings() {
    return bindings;
  }

  public int primitiveTopology() {
    return primitiveTopology;
  }

  public int pushConstantsSize() {
    return pushConstantsSize;
  }

  public int pushConstantsStageMask() {
    return pushConstantsStageMask;
  }

  @Override
  public boolean isClosed() {
    return handle == 0;
  }

  @Override
  public void close() {
    if (handle != 0) {
      MetalNative.release(handle);
      handle = 0;
    }
  }
}
