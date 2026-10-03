package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

final class MetalRenderPass implements RenderPassBackend {
  private final MetalDevice device;
  private final RenderPass.RenderArea area;
  private Object[] uniforms = new Object[0];
  private MetalRenderPipeline pipeline;
  private GpuBuffer indices;
  private IndexType indexType;
  private boolean bindingsDirty = true;

  MetalRenderPass(MetalDevice device, RenderPassDescriptor descriptor, int width, int height) {
    this.device = device;
    area =
        descriptor.renderArea() == null
            ? new RenderPass.RenderArea(0, 0, width, height)
            : descriptor.renderArea();
  }

  private long h() {
    return device.handle();
  }

  public void pushDebugGroup(Supplier<String> label) {
    MetalNative.pushDebugGroup(h(), label.get());
  }

  public void popDebugGroup() {
    MetalNative.popDebugGroup(h());
  }

  public void setPipeline(BackendRenderPipeline info) {
    if (!(info instanceof MetalRenderPipeline metal))
      throw new IllegalArgumentException("Pipeline must belong to the Metal backend");
    MetalNative.bindPipeline(h(), metal.handle());
    pipeline = metal;
    uniforms = new Object[pipeline.bindings().size()];
    bindingsDirty = true;
  }

  public void setUniform(int index, Object value) {
    if (index < 0 || index >= uniforms.length)
      throw new IllegalArgumentException("Uniform binding out of range: " + index);
    uniforms[index] = value;
    bindingsDirty = true;
  }

  public void pushConstants(ByteBuffer value) {
    if (pipeline == null) throw new IllegalStateException("Push constants require a pipeline");
    int size = pipeline.pushConstantsSize();
    if (value.remaining() < size)
      throw new IllegalArgumentException("Not enough values for push constants");
    if (size != 0 && pipeline.pushConstantsStageMask() != 0)
      MetalNative.pushConstants(
          h(),
          pipeline.pushConstantsStageMask(),
          MetalShaderCompiler.PUSH_CONSTANT_BUFFER,
          value,
          value.position(),
          size);
  }

  public void enableScissor(int x, int y, int w, int height) {
    int left = Math.max(x, area.x()), top = Math.max(y, area.y());
    int right = Math.min(x + w, area.x() + area.width()),
        bottom = Math.min(y + height, area.y() + area.height());
    MetalNative.scissor(h(), left, top, Math.max(0, right - left), Math.max(0, bottom - top));
  }

  public void disableScissor() {
    enableScissor(area.x(), area.y(), area.width(), area.height());
  }

  public void setVertexBuffer(int slot, GpuBufferSlice vertex) {
    MetalNative.bindVertexBuffer(
        h(),
        slot,
        vertex == null ? 0 : MetalCommandEncoder.buffer(vertex.buffer()),
        vertex == null ? 0 : vertex.offset());
  }

  public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
    indices = buffer;
    indexType = type;
  }

  private void prepare() {
    if (pipeline == null || pipeline.isClosed())
      throw new IllegalStateException("Draw requires a compiled pipeline");
    if (!bindingsDirty) return;
    for (int index = 0; index < pipeline.bindings().size(); index++) {
      var binding = pipeline.bindings().get(index);
      if (binding.stageMask() == 0) continue;
      Object value = uniforms[index];
      if (value == null)
        throw new IllegalStateException(
            "Missing uniform " + binding.name() + " for " + pipeline.name());
      switch (binding.kind()) {
        case TEXTURE -> {
          if (!(value instanceof TextureViewAndSampler texture))
            throw new IllegalArgumentException(
                "Expected texture and sampler for " + binding.name());
          MetalNative.bindTexture(
              h(),
              binding.stageMask(),
              binding.index(),
              ((MetalGpuTextureView) texture.view()).handle(),
              ((MetalGpuSampler) texture.sampler()).handle());
        }
        case UNIFORM_BUFFER, TEXEL_BUFFER -> {
          if (!(value instanceof GpuBufferSlice buffer))
            throw new IllegalArgumentException("Expected buffer slice for " + binding.name());
          if (binding.kind() == MetalRenderPipeline.Kind.TEXEL_BUFFER)
            MetalNative.bindTexelBuffer(
                h(),
                binding.stageMask(),
                binding.index(),
                MetalCommandEncoder.buffer(buffer.buffer()),
                buffer.offset(),
                buffer.length(),
                binding.gpuFormat().name());
          else
            MetalNative.bindUniform(
                h(),
                binding.stageMask(),
                binding.index(),
                MetalCommandEncoder.buffer(buffer.buffer()),
                buffer.offset(),
                buffer.length());
        }
      }
    }
    bindingsDirty = false;
  }

  public void drawIndexed(
      int count, int instances, int firstIndex, int baseVertex, int baseInstance) {
    prepare();
    if (indices == null || indexType == null)
      throw new IllegalStateException("Index buffer missing");
    MetalNative.drawIndexed(
        h(),
        pipeline.primitiveTopology(),
        MetalCommandEncoder.buffer(indices),
        indexType == IndexType.INT,
        (long) firstIndex * (indexType == IndexType.INT ? 4 : 2),
        count,
        baseVertex,
        instances,
        baseInstance);
  }

  public void draw(int count, int instances, int firstVertex, int baseInstance) {
    prepare();
    MetalNative.draw(
        h(), pipeline.primitiveTopology(), firstVertex, count, instances, baseInstance);
  }

  public void multiDrawIndexed(IntBuffer parameters, int instances, int baseInstance, int count) {
    for (int i = 0; i < count; i++) {
      int p = parameters.position() + i * 3;
      drawIndexed(
          parameters.get(p + 1), instances, parameters.get(p), parameters.get(p + 2), baseInstance);
    }
  }

  public void multiDrawIndexed(
      PointerBuffer offsets, IntBuffer counts, IntBuffer vertices, int count) {
    for (int i = 0; i < count; i++)
      drawIndexed(
          counts.get(counts.position() + i),
          1,
          Math.toIntExact(
              offsets.get(offsets.position() + i) / (indexType == IndexType.INT ? 4 : 2)),
          vertices == null ? 0 : vertices.get(vertices.position() + i),
          0);
  }

  public void multiDraw(IntBuffer parameters, int instances, int baseInstance, int count) {
    for (int i = 0; i < count; i++) {
      int p = parameters.position() + i * 2;
      draw(parameters.get(p + 1), instances, parameters.get(p), baseInstance);
    }
  }

  public void multiDraw(IntBuffer first, IntBuffer counts, int count) {
    for (int i = 0; i < count; i++)
      draw(counts.get(counts.position() + i), 1, first.get(first.position() + i), 0);
  }

  public void drawIndirect(GpuBufferSlice parameters, int count) {
    prepare();
    MetalNative.drawIndirect(
        h(),
        pipeline.primitiveTopology(),
        MetalCommandEncoder.buffer(parameters.buffer()),
        parameters.offset(),
        count,
        0,
        false);
  }

  public void drawIndexedIndirect(GpuBufferSlice parameters, int count) {
    prepare();
    if (indices == null) throw new IllegalStateException("Index buffer missing");
    MetalNative.drawIndirect(
        h(),
        pipeline.primitiveTopology(),
        MetalCommandEncoder.buffer(parameters.buffer()),
        parameters.offset(),
        count,
        MetalCommandEncoder.buffer(indices),
        indexType == IndexType.INT);
  }

  public void writeTimestamp(GpuQueryPool pool, int index) {
    ((MetalQueryPool) pool).write(index);
  }
}
