package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackMipmaps;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Executes mip generation on Metal and verifies every result against known filtered pixels. */
public final class PackMipmapsTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var mipmaps = new PackMipmaps(device)) {
      verify(device, mipmaps, 4, 4);
      verify(device, mipmaps, 4, 1);
      verify(device, mipmaps, 1, 4);
      verifyHdr(device, mipmaps);
      System.out.println(
          "PASS: mip pipelines compile for all pack color formats; square, one-axis, and HDR mip"
              + " chains match GPU readback");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(FrontendGpuDevice device, PackMipmaps mipmaps, int width, int height) {
    int mipCount = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    int baseBytes = width * height * 4;
    int byteSize = baseBytes;
    for (int level = 1; level < mipCount; level++)
      byteSize += Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
    try (var texture =
            new PackTexture(
                device, "mipmap readback", GpuFormat.RGBA8_UNORM, width, height, mipCount);
        var readback =
            device.createBuffer(
                () -> "Mipmap readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                byteSize)) {
      ByteBuffer pixels =
          ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
      for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++)
          pixels.put((byte) (64 * x)).put((byte) (64 * y)).put((byte) 128).put((byte) 255);
      pixels.flip();
      var encoder = device.createCommandEncoder();
      encoder.writeToTexture(texture.texture, pixels, 0, 0, 0, 0, width, height);
      mipmaps.generate(texture);
      encoder.copyTextureToBuffer(texture.texture, readback, 0, () -> {}, 0);
      long offset = baseBytes;
      for (int level = 1; level < mipCount; level++) {
        encoder.copyTextureToBuffer(texture.texture, readback, offset, () -> {}, level);
        offset += (long) Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("Mipmap readback timed out");
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer result = mapped.data();
        for (int i = 0; i < baseBytes; i++) {
          if (pixels.get(i) != result.get(i))
            throw new AssertionError("Mipmap generation altered source level at byte " + i);
        }
        int at = baseBytes;
        for (int level = 1; level < mipCount; level++) {
          int w = Math.max(1, width >> level), h = Math.max(1, height >> level), scale = 1 << level;
          for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
              int red = width == 1 ? 0 : 64 * x * scale + 32 * (scale - 1);
              int green = height == 1 ? 0 : 64 * y * scale + 32 * (scale - 1);
              int[] expected = {red, green, 128, 255};
              for (int channel = 0; channel < 4; channel++) {
                int actual = Byte.toUnsignedInt(result.get(at++));
                if (Math.abs(actual - expected[channel]) > 1)
                  throw new AssertionError(
                      "Incorrect mip "
                          + level
                          + " at "
                          + x
                          + ","
                          + y
                          + " channel "
                          + channel
                          + ": "
                          + actual
                          + " expected "
                          + expected[channel]
                          + "; complete readback "
                          + java.util.HexFormat.of().formatHex(bytes(result)));
              }
            }
        }
      }
    }
  }

  private static byte[] bytes(ByteBuffer buffer) {
    byte[] result = new byte[buffer.remaining()];
    buffer.duplicate().get(result);
    return result;
  }

  private static void verifyHdr(FrontendGpuDevice device, PackMipmaps mipmaps) {
    try (var texture =
            new PackTexture(device, "HDR mipmap readback", GpuFormat.RGBA16_FLOAT, 2, 1, 2);
        var readback =
            device.createBuffer(
                () -> "HDR mipmap readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                8)) {
      ByteBuffer pixels = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
      // Half floats: (4,2,0,1) and (8,6,0,1), averaging to (6,4,0,1).
      for (int value : new int[] {0x4400, 0x4000, 0, 0x3c00, 0x4800, 0x4600, 0, 0x3c00})
        pixels.putShort((short) value);
      pixels.flip();
      var encoder = device.createCommandEncoder();
      encoder.writeToTexture(texture.texture, pixels, 0, 0, 0, 0, 2, 1);
      mipmaps.generate(texture);
      encoder.copyTextureToBuffer(texture.texture, readback, 0, () -> {}, 1);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("HDR mipmap GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer result = mapped.data().order(ByteOrder.nativeOrder());
        int[] expected = {0x4600, 0x4400, 0, 0x3c00};
        for (int channel = 0; channel < expected.length; channel++)
          if (Short.toUnsignedInt(result.getShort(channel * 2)) != expected[channel])
            throw new AssertionError("HDR mipmap changed precision/range at channel " + channel);
      }
    }
  }
}
