package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/** Color pairs survive across frames. Geometry writes current; screen passes write alternate. */
public final class PackRenderTargets implements AutoCloseable {
  public record BufferSpec(GpuFormat format, boolean clear, Vector4fc clearColor, boolean mipmaps) {
    public BufferSpec {
      clearColor = clearColor == null ? null : new Vector4f(clearColor);
    }
  }

  private static final BufferSpec DEFAULT =
      new BufferSpec(GpuFormat.RGBA8_UNORM, true, null, false);
  private final GpuDevice device;
  private final Map<Integer, BufferSpec> specifications;
  private final Map<Integer, Pair> colors = new HashMap<>();
  private final List<PackTexture> owned = new ArrayList<>();
  private final PackTexture[] depths = new PackTexture[3];
  private final PackTexture mergedDepth;
  private final PackTexture[] shadowDepths = new PackTexture[2];
  private final Map<Integer, PackTexture> shadowColors = new HashMap<>();
  public final int width, height, shadowSize;
  private Vector4fc fogColor = new Vector4f();

  public PackRenderTargets(
      GpuDevice device,
      Map<Integer, BufferSpec> specifications,
      int width,
      int height,
      int shadowSize) {
    if (width < 1 || height < 1 || shadowSize < 1)
      throw new IllegalArgumentException("Invalid target size");
    this.device = device;
    this.specifications = Map.copyOf(specifications);
    this.width = width;
    this.height = height;
    this.shadowSize = shadowSize;
    try {
      for (int i = 0; i < depths.length; i++)
        depths[i] = allocate("depthtex" + i, GpuFormat.D32_FLOAT, width, height, 1);
      mergedDepth = allocate("merged hand depth", GpuFormat.D32_FLOAT, width, height, 1);
      for (int i = 0; i < shadowDepths.length; i++)
        shadowDepths[i] = allocate("shadowtex" + i, GpuFormat.D32_FLOAT, shadowSize, shadowSize, 1);
    } catch (RuntimeException failure) {
      close();
      throw failure;
    }
  }

  private PackTexture allocate(String name, GpuFormat format, int w, int h, int mips) {
    PackTexture result = new PackTexture(device, name, format, w, h, mips);
    owned.add(result);
    return result;
  }

  public BufferSpec specification(int index) {
    return specifications.getOrDefault(index, DEFAULT);
  }

  public GpuFormat format(int index) {
    return specification(index).format();
  }

  private Pair pair(int index) {
    if (index < 0 || index > 31)
      throw new IllegalArgumentException("Color attachment outside 0..31");
    return colors.computeIfAbsent(
        index,
        key -> {
          BufferSpec spec = specification(key);
          int mips =
              spec.mipmaps() ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
          PackTexture a = allocate("colortex" + key + " A", spec.format(), width, height, mips);
          PackTexture b = allocate("colortex" + key + " B", spec.format(), width, height, mips);
          // Allocation can occur on first program use after beginFrame. Both sides must be defined.
          CommandEncoder encoder = device.createCommandEncoder();
          encoder.clearColorTexture(a.texture, clearColor(key));
          encoder.clearColorTexture(b.texture, clearColor(key));
          return new Pair(a, b);
        });
  }

  private Vector4fc clearColor(int index) {
    Vector4fc explicit = specification(index).clearColor();
    if (explicit != null) return explicit;
    return index == 0 ? fogColor : index == 1 ? new Vector4f(1) : new Vector4f();
  }

  public PackTexture color(int index) {
    return pair(index).read();
  }

  public PackTexture alternate(int index) {
    return pair(index).write();
  }

  public PackTexture depth(int index) {
    return depths[index];
  }

  PackTexture depthMergeTarget() {
    return mergedDepth;
  }

  void commitMergedDepth() {
    copy(mergedDepth, depths[0], width, height);
  }

  public PackTexture shadowDepth(int index) {
    return shadowDepths[index];
  }

  public PackTexture shadowColor(int index) {
    return shadowColors.computeIfAbsent(
        index,
        key -> {
          if (key < 0 || key > 7) throw new IllegalArgumentException("Shadow color outside 0..7");
          PackTexture texture =
              allocate("shadowcolor" + key, GpuFormat.RGBA8_UNORM, shadowSize, shadowSize, 1);
          device.createCommandEncoder().clearColorTexture(texture.texture, new Vector4f(1));
          return texture;
        });
  }

  public void beginFrame(Vector4fc fog) {
    fogColor = new Vector4f(fog);
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      if (!specification(entry.getKey()).clear()) continue;
      // Only the readable side survives into geometry. Screen passes fully overwrite their outputs.
      encoder.clearColorTexture(entry.getValue().read().texture, clearColor(entry.getKey()));
    }
    for (PackTexture depth : depths) encoder.clearDepthTexture(depth.texture, 1);
    for (PackTexture depth : shadowDepths) encoder.clearDepthTexture(depth.texture, 1);
    for (PackTexture color : shadowColors.values())
      encoder.clearColorTexture(color.texture, new Vector4f(1));
  }

  /** Discard temporal images after a camera discontinuity, including targets with Clear=false. */
  public void resetHistory(Vector4fc fog) {
    fogColor = new Vector4f(fog);
    CommandEncoder encoder = device.createCommandEncoder();
    for (var entry : colors.entrySet()) {
      Vector4fc clear = clearColor(entry.getKey());
      encoder.clearColorTexture(entry.getValue().read().texture, clear);
      encoder.clearColorTexture(entry.getValue().write().texture, clear);
    }
  }

  public RenderPassDescriptor descriptor(
      String name, List<Integer> outputs, boolean screen, boolean shadow, boolean withDepth) {
    RenderPassDescriptor.Builder result = RenderPassDescriptor.builder(() -> name);
    for (int output : outputs) {
      if (output < 0) result.withUnusedColorAttachment();
      else
        result.withColorAttachment(
            (shadow ? shadowColor(output) : screen ? alternate(output) : color(output)).level(0));
    }
    if (withDepth) result.withDepthAttachment((shadow ? shadowDepths[0] : depths[0]).level(0));
    return result.build();
  }

  public void flip(List<Integer> outputs) {
    // A logical attachment changes sides once even if malformed metadata repeats it.
    outputs.stream().filter(index -> index >= 0).distinct().forEach(index -> pair(index).flip());
  }

  /**
   * Capture once after opaque world geometry, before any transparent or hand draw. depthtex2 must
   * retain this image; depthtex1 can subsequently receive opaque-hand depth independently.
   */
  public void snapshotOpaqueDepth() {
    copyDepth(1);
    copyDepth(2);
  }

  public void copyDepth(int destination) {
    copy(depths[0], depths[destination], width, height);
  }

  public void copyShadowDepth() {
    copy(shadowDepths[0], shadowDepths[1], shadowSize, shadowSize);
  }

  private void copy(PackTexture from, PackTexture to, int w, int h) {
    device
        .createCommandEncoder()
        .copyTextureToTexture(from.texture, to.texture, 0, 0, 0, 0, 0, w, h);
  }

  @Override
  public void close() {
    for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    owned.clear();
    colors.clear();
    shadowColors.clear();
  }

  private static final class Pair {
    private final PackTexture a, b;
    private boolean flipped;

    Pair(PackTexture a, PackTexture b) {
      this.a = a;
      this.b = b;
    }

    PackTexture read() {
      return flipped ? b : a;
    }

    PackTexture write() {
      return flipped ? a : b;
    }

    void flip() {
      flipped = !flipped;
    }
  }
}
