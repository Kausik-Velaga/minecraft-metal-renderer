package dev.kausik.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Stable JNI boundary. Resource handles own retained native objects; zero is never a valid handle.
 */
public final class MetalNative {
  static {
    try {
      String resource = "/native/macos-arm64/libminecraft_metal.dylib";
      byte[] bytes;
      try (var input = MetalNative.class.getResourceAsStream(resource)) {
        if (input == null) throw new IOException("Missing " + resource + "; run ./gradlew build");
        bytes = input.readAllBytes();
      }
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Path directory = Path.of(System.getProperty("user.home"), ".cache", "minecraft-metal", hash);
      Files.createDirectories(directory);
      Path library = directory.resolve("libminecraft_metal.dylib");
      if (!Files.exists(library) || !MessageDigest.isEqual(Files.readAllBytes(library), bytes)) {
        Path temporary = Files.createTempFile(directory, "native-", ".dylib");
        Files.write(temporary, bytes);
        Files.move(
            temporary,
            library,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      }
      System.load(library.toAbsolutePath().toString());
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private MetalNative() {}

  public static void load() {}

  public static native long createDevice();

  /** Number of live JNI-owned resource handles, for lifecycle validation. */
  public static native int liveResourceCount();

  public static native String deviceName(long device);

  public static native void destroyDevice(long device);

  public static native void release(long resource);

  public static native long createBuffer(long device, long size, boolean shared, String label);

  public static native ByteBuffer mapBuffer(long buffer, long offset, long length);

  public static native void writeBuffer(
      long device, long buffer, long offset, ByteBuffer data, int position, int length);

  public static native void copyBuffer(
      long device, long source, long sourceOffset, long target, long targetOffset, long length);

  /** Formats use exact GpuFormat enum names, not unstable ordinal values. */
  public static native long createTexture(
      long device,
      String format,
      int width,
      int height,
      int layers,
      int mips,
      int usage,
      String label);

  public static native long createTextureView(long texture, int baseMip, int mipCount);

  public static native long createSampler(
      long device,
      boolean repeatU,
      boolean repeatV,
      boolean linearMin,
      boolean linearMag,
      int anisotropy,
      double maxLod);

  public static native void uploadTexture(
      long device,
      long texture,
      ByteBuffer data,
      int position,
      int mip,
      int layer,
      int x,
      int y,
      int width,
      int height);

  public static native void bufferToTexture(
      long device,
      long buffer,
      long offset,
      int bytesPerRow,
      int rowsPerImage,
      long texture,
      int mip,
      int layer,
      int x,
      int y,
      int width,
      int height);

  public static native void textureToBuffer(
      long device,
      long texture,
      int mip,
      int x,
      int y,
      int width,
      int height,
      long buffer,
      long offset);

  public static native void copyTexture(
      long device,
      long source,
      long target,
      int mip,
      int sourceX,
      int sourceY,
      int targetX,
      int targetY,
      int width,
      int height);

  public static native void clearAll(
      long device, long color, float r, float g, float b, float a, long depth, double depthValue);

  /** Clears a rectangle in a particular mip, leaving other levels unchanged. */
  public static native void clearMip(
      long device,
      long color,
      float r,
      float g,
      float b,
      float a,
      long depth,
      double depthValue,
      int x,
      int y,
      int width,
      int height,
      int mip);

  public static native void clear(
      long device,
      long color,
      float r,
      float g,
      float b,
      float a,
      long depth,
      double depthValue,
      int x,
      int y,
      int width,
      int height);

  /** clearColors entries are RGBA, with NaN red meaning LOAD. clearDepth NaN means LOAD. */
  public static native void beginRenderPass(
      long device,
      String label,
      long[] colors,
      float[] clearColors,
      long depth,
      double clearDepth,
      int x,
      int y,
      int width,
      int height);

  public static native void endRenderPass(long device);

  public static native void pushDebugGroup(long device, String label);

  public static native void popDebugGroup(long device);

  public static native void bindPipeline(long device, long pipeline);

  /** stageMask: 1 vertex, 2 fragment. */
  public static native void bindUniform(
      long device, int stageMask, int index, long buffer, long offset, long length);

  /**
   * Copies push constants into immutable per-submission storage; shader compiler reserves slot 30.
   */
  public static native void pushConstants(
      long device, int stageMask, int index, ByteBuffer data, int position, int length);

  public static native void bindTexture(
      long device, int stageMask, int index, long textureView, long sampler);

  public static native void bindVertexBuffer(long device, int index, long buffer, long offset);

  public static native void scissor(long device, int x, int y, int width, int height);

  public static native void draw(
      long device, int topology, int firstVertex, int vertexCount, int instances, int baseInstance);

  public static native void drawIndexed(
      long device,
      int topology,
      long indices,
      boolean index32,
      long indexOffset,
      int indexCount,
      int baseVertex,
      int instances,
      int baseInstance);

  public static native void drawIndirect(
      long device,
      int topology,
      long parameters,
      long offset,
      int count,
      long indices,
      boolean index32);

  public static native void submit(long device);

  public static native long createFence(long device);

  public static native boolean awaitFence(long fence, long timeoutNanos);

  public static native long createSurface(long device, long metalLayer);

  public static native void configureSurface(long surface, int width, int height, boolean vsync);

  public static native void acquireSurface(long surface);

  public static native void blitSurface(long device, long surface, long textureView);

  public static native void presentSurface(long surface);

  /**
   * attributes: [location, buffer, offset, format], layouts: [buffer, stride, stepRate]. colors:
   * [pixelFormat, writeMask, blend, srcRGB, dstRGB, opRGB, srcA, dstA, opA].
   */
  public static native long createPipeline(
      long device,
      String label,
      String vertexMsl,
      String fragmentMsl,
      int[] attributes,
      int[] layouts,
      int[] colors,
      int depthCompare,
      boolean depthWrite,
      boolean cull,
      boolean wireframe,
      float depthBias,
      float depthSlope);

  /** Uses a translated fragment variant with depth output disabled for passes without depth. */
  public static native long createPipelineWithDepthVariants(
      long device,
      String label,
      String vertexMsl,
      String fragmentMsl,
      String fragmentWithoutDepthMsl,
      int[] attributes,
      int[] layouts,
      int[] colors,
      int depthCompare,
      boolean depthWrite,
      boolean cull,
      boolean wireframe,
      float depthBias,
      float depthSlope);

  public static native void bindTexelBuffer(
      long device,
      int stageMask,
      int index,
      long buffer,
      long offset,
      long length,
      String gpuFormat);

  public static native long createQueryPool(long device, int size);

  public static native void writeTimestamp(long device, long pool, int index);

  public static native long queryValue(long pool, int index);

  public static native long timestampNow(long device);

  /** Maps Metal timestamps into the JVM's System.nanoTime clock without assuming either origin. */
  public static long timestampCalibrationOffset(long device) {
    long bestDuration = Long.MAX_VALUE;
    long bestOffset = 0;
    for (int attempt = 0; attempt < 8; attempt++) {
      long before = System.nanoTime();
      long gpu = timestampNow(device);
      long after = System.nanoTime();
      long duration = after - before;
      if (duration < bestDuration) {
        bestDuration = duration;
        bestOffset = before + duration / 2 - gpu;
      }
    }
    return bestOffset;
  }
}
