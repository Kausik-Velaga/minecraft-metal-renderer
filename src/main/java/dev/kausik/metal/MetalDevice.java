package dev.kausik.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/** Native Metal implementation underneath Minecraft's RenderPearl frontend. */
public final class MetalDevice implements GpuDeviceBackend {
  private long handle;
  private final MetalShaderCompiler compiler;
  private final MetalCommandEncoder encoder;
  private final DeviceInfo info;

  public MetalDevice() {
    handle = MetalNative.createDevice();
    MetalShaderCompiler createdCompiler = null;
    try {
      MetalNative.configureIndirectCommands(
          handle,
          Boolean.getBoolean("minecraftMetal.indirectCommandBuffers"),
          Math.max(1, Integer.getInteger("minecraftMetal.indirectCommandBufferThreshold", 64)));
      createdCompiler = new MetalShaderCompiler();
      compiler = createdCompiler;
      encoder = new MetalCommandEncoder(this);
      String name = MetalNative.deviceName(handle);
      info =
          new DeviceInfo(
              name,
              "Apple",
              System.getProperty("os.version"),
              true,
              "Metal",
              1.0f,
              new DeviceLimits(16, 256, 16384, 1L << 30, 65535, 8, 65535),
              new DeviceFeatures(true, false, true, true, true, true, true, true),
              Set.of(
                  "CAMetalLayer",
                  "MSL",
                  "SPIRV-Cross",
                  "depth-comparison-lequal",
                  "vertex-position-invariance",
                  "color-attachment-load-discard"),
              new HintsAndWorkarounds(false, false, true, false),
              DeviceType.INTEGRATED);
      LoggerFactory.getLogger("MinecraftMetal")
          .info("Metal device created: {}. Native SDL presentation, no OpenGL context.", name);
    } catch (RuntimeException | Error failure) {
      if (createdCompiler != null) createdCompiler.close();
      MetalNative.destroyDevice(handle);
      handle = 0;
      throw failure;
    }
  }

  public long handle() {
    if (handle == 0) throw new IllegalStateException("Metal device closed");
    return handle;
  }

  @Override
  public GpuSurfaceBackend createSurface(long window, BooleanSupplier isIconified) {
    return new MetalSurface(this, window, isIconified);
  }

  @Override
  public CommandEncoderBackend createCommandEncoder() {
    return encoder;
  }

  @Override
  public GpuSampler createSampler(
      AddressMode u,
      AddressMode v,
      FilterMode min,
      FilterMode mag,
      int anisotropy,
      OptionalDouble maxLod) {
    return new MetalGpuSampler(this, u, v, min, mag, anisotropy, maxLod);
  }

  @Override
  public GpuTexture createTexture(
      String label, int usage, GpuFormat format, int w, int h, int layers, int mips) {
    return new MetalGpuTexture(
        this, usage, label == null ? "Metal texture" : label, format, w, h, layers, mips);
  }

  @Override
  public GpuTextureView createTextureView(GpuTexture texture, int base, int count) {
    return new MetalGpuTextureView((MetalGpuTexture) texture, base, count);
  }

  @Override
  public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
    return new MetalGpuBuffer(this, usage, size, label == null ? "Metal buffer" : label.get());
  }

  @Override
  public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
    GpuBuffer buffer = createBuffer(label, usage, data.remaining());
    try {
      encoder.writeToBuffer(buffer.slice(), data);
      return buffer;
    } catch (Throwable t) {
      buffer.close();
      throw t;
    }
  }

  @Override
  public List<String> getLastDebugMessages() {
    return List.of();
  }

  @Override
  public boolean isDebuggingEnabled() {
    return "1".equals(System.getenv("MTL_DEBUG_LAYER"));
  }

  @Override
  public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
    MetalRenderPipeline compiled = compiler.compile(handle(), info);
    return () -> compiled;
  }

  @Override
  public GpuQueryPool createTimestampQueryPool(int count) {
    return new MetalQueryPool(this, count);
  }

  @Override
  public long getTimestampCalibrationOffset() {
    return MetalNative.timestampCalibrationOffset(handle());
  }

  @Override
  public DeviceInfo getDeviceInfo() {
    return info;
  }

  @Override
  public void close() {
    if (handle == 0) return;
    try {
      encoder.close();
    } finally {
      try {
        compiler.close();
      } finally {
        long released = handle;
        handle = 0;
        MetalNative.destroyDevice(released);
      }
    }
  }
}
