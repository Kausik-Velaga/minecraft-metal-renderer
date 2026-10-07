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
import java.util.ArrayList;
import java.util.List;

/** Executes mip generation on Metal and verifies every result against known filtered pixels. */
public final class PackMipmapsTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var raster = new PackMipmaps(device, false);
        var nativeMips = new PackMipmaps(device, true)) {
      for (var mipmaps : List.of(raster, nativeMips)) {
        verify(device, mipmaps, 4, 4);
        verify(device, mipmaps, 4, 1);
        verify(device, mipmaps, 1, 4);
        verifyHdr(device, mipmaps);
      }
      List<String> differences = new ArrayList<>();
      for (GpuFormat format :
          List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT, GpuFormat.RG11B10_FLOAT)) {
        for (int[] size :
            new int[][] {
              {1, 1}, {4, 4}, {32, 16}, {4, 1}, {1, 4}, {32, 1}, {1, 32}, {31, 17}, {17, 31},
              {31, 1}, {1, 17}, {3, 3}, {62, 34}, {64, 40}, {62, 1}
            }) {
          compareChains(device, raster, nativeMips, format, size[0], size[1], differences);
        }
      }
      if (!differences.isEmpty())
        throw new AssertionError(
            "Native mip generation is not equivalent to the raster reference (one format ULP"
                + " tolerance):\n"
                + String.join("\n", differences));
      System.out.println(
          "PASS: raster/native RGBA8, RGBA16F and RG11B10F full chains match within one format ULP;"
              + " rectangular, one-axis, power-of-two and NPOT inputs preserve every base byte");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void compareChains(
      FrontendGpuDevice device,
      PackMipmaps raster,
      PackMipmaps nativeMips,
      GpuFormat format,
      int width,
      int height,
      List<String> differences) {
    int levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    int pixelBytes = format == GpuFormat.RGBA16_FLOAT ? 8 : 4;
    int chainBytes = 0;
    for (int mip = 0; mip < levels; mip++)
      chainBytes += Math.max(1, width >> mip) * Math.max(1, height >> mip) * pixelBytes;
    ByteBuffer input = pattern(format, width, height);
    String description = format + " " + width + "x" + height;
    try (var reference =
            new PackTexture(device, "raster " + description, format, width, height, levels);
        var actual =
            new PackTexture(device, "native " + description, format, width, height, levels);
        var readback =
            device.createBuffer(
                () -> "Raster/native mip comparison",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                chainBytes * 2L)) {
      var encoder = device.createCommandEncoder();
      encoder.writeToTexture(reference.texture, input.duplicate(), 0, 0, 0, 0, width, height);
      encoder.writeToTexture(actual.texture, input.duplicate(), 0, 0, 0, 0, width, height);
      raster.generate(reference);
      nativeMips.generate(actual);
      int offset = 0;
      for (int mip = 0; mip < levels; mip++) {
        encoder.copyTextureToBuffer(reference.texture, readback, offset, () -> {}, mip);
        encoder.copyTextureToBuffer(
            actual.texture, readback, chainBytes + (long) offset, () -> {}, mip);
        offset += Math.max(1, width >> mip) * Math.max(1, height >> mip) * pixelBytes;
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("Raster/native mip comparison timed out: " + description);
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
        for (int i = 0; i < input.remaining(); i++) {
          if (data.get(i) != input.get(i) || data.get(chainBytes + i) != input.get(i))
            throw new AssertionError(
                "Raster/native generation modified base byte " + i + ": " + description);
        }
        offset = width * height * pixelBytes;
        for (int mip = 1; mip < levels; mip++) {
          int w = Math.max(1, width >> mip), h = Math.max(1, height >> mip);
          int unequal = 0;
          double maxAbsolute = 0, maxUnits = 0;
          String first = null;
          for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
              int address = offset + (y * w + x) * pixelBytes;
              for (int channel = 0;
                  channel < (format == GpuFormat.RG11B10_FLOAT ? 3 : 4);
                  channel++) {
                float expected = channel(data, address, format, channel);
                float value = channel(data, chainBytes + address, format, channel);
                if (!Float.isFinite(expected) || !Float.isFinite(value))
                  throw new AssertionError(
                      "Nonfinite mip pixel in " + description + " level " + mip);
                double error = Math.abs(value - expected);
                double step =
                    formatStep(format, channel, Math.max(Math.abs(expected), Math.abs(value)));
                maxAbsolute = Math.max(maxAbsolute, error);
                maxUnits = Math.max(maxUnits, error / step);
                // Fixed tolerance at every level: do not conceal NPOT kernel differences with
                // relative percentages or a tolerance that increases down the chain.
                if (error > step * 1.00001) {
                  unequal++;
                  if (first == null)
                    first =
                        x
                            + ","
                            + y
                            + " channel "
                            + channel
                            + " raster="
                            + expected
                            + " native="
                            + value;
                }
              }
            }
          }
          String result =
              description
                  + " level="
                  + mip
                  + " size="
                  + w
                  + "x"
                  + h
                  + " differingChannels="
                  + unequal
                  + " maxAbs="
                  + maxAbsolute
                  + " maxFormatULP="
                  + maxUnits;
          System.out.println((unequal == 0 ? "MATCH " : "DIFFERENT ") + result);
          if (unequal != 0) differences.add(result + "; first=" + first);
          offset += w * h * pixelBytes;
        }
      }
    }
  }

  /** Exactly representable HDR inputs expose both filtering and the packed 11/11/10-bit layout. */
  private static ByteBuffer pattern(GpuFormat format, int width, int height) {
    ByteBuffer pixels =
        ByteBuffer.allocateDirect(width * height * (format == GpuFormat.RGBA16_FLOAT ? 8 : 4))
            .order(ByteOrder.nativeOrder());
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int seed = x * 73 + y * 151 + x * y * 11;
        if (format == GpuFormat.RGBA8_UNORM) {
          for (int c = 0; c < 4; c++) pixels.put((byte) (seed + c * 47));
        } else {
          float red = (seed & 63) * 0.25f;
          float green = ((seed + 21) & 63) * 0.25f;
          float blue = ((seed + 43) & 63) * 0.25f;
          if (format == GpuFormat.RGBA16_FLOAT) {
            pixels
                .putShort(Float.floatToFloat16(red))
                .putShort(Float.floatToFloat16(green))
                .putShort(Float.floatToFloat16(blue))
                .putShort(Float.floatToFloat16((seed & 7) * 0.125f));
          } else {
            // Unsigned 5-bit exponent, 6/6/5-bit mantissas. Inputs are positive dyadic values,
            // so discarding the unused half-float mantissa bits requires no rounding.
            int r = Short.toUnsignedInt(Float.floatToFloat16(red)) >> 4;
            int g = Short.toUnsignedInt(Float.floatToFloat16(green)) >> 4;
            int b = Short.toUnsignedInt(Float.floatToFloat16(blue)) >> 5;
            pixels.putInt(r | (g << 11) | (b << 22));
          }
        }
      }
    }
    return pixels.flip();
  }

  private static float channel(ByteBuffer data, int pixel, GpuFormat format, int channel) {
    if (format == GpuFormat.RGBA8_UNORM)
      return Byte.toUnsignedInt(data.get(pixel + channel)) / 255.0f;
    if (format == GpuFormat.RGBA16_FLOAT)
      return Float.float16ToFloat(data.getShort(pixel + channel * 2));
    int packed = data.getInt(pixel);
    int bits =
        channel == 0 ? packed & 0x7ff : channel == 1 ? (packed >>> 11) & 0x7ff : packed >>> 22;
    return Float.float16ToFloat((short) (bits << (channel == 2 ? 5 : 4)));
  }

  private static double formatStep(GpuFormat format, int channel, float magnitude) {
    if (format == GpuFormat.RGBA8_UNORM) return 1.0 / 255;
    int mantissaBits = format == GpuFormat.RGBA16_FLOAT ? 10 : channel == 2 ? 5 : 6;
    return Math.scalb(1.0, Math.max(-14, Math.getExponent(magnitude)) - mantissaBits);
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
