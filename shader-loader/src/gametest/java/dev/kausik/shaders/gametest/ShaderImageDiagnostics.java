package dev.kausik.shaders.gametest;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Test-only batched GPU readback; images retain native texture row zero at the top. */
public final class ShaderImageDiagnostics {
  private record Capture(String label, GpuTexture texture, int width, int height, int offset) {}

  private ShaderImageDiagnostics() {}

  public static void capture(ShaderRuntime runtime, Path directory, String name) {
    if (runtime == null || runtime.targets() == null) return;
    var targets = runtime.targets();
    List<Capture> captures = new ArrayList<>();
    int offset = 0;
    GpuTexture[] textures = {
      targets.shadowDepth(0).texture,
      targets.depth(0).texture,
      targets.color(0).texture,
      targets.shadowColor(0).texture
    };
    String[] labels = {"shadowtex0", "depthtex0", "colortex0", "shadowcolor0"};
    for (int index = 0; index < textures.length; index++) {
      boolean shadow = labels[index].startsWith("shadow");
      int width = shadow ? targets.shadowSize : targets.width;
      int height = shadow ? targets.shadowSize : targets.height;
      captures.add(new Capture(labels[index], textures[index], width, height, offset));
      offset = (offset + width * height * textures[index].getFormat().blockSize() + 255) & ~255;
    }
    StringBuilder report =
        new StringBuilder(
            "Native texture row 0 appears at image top; these diagnostics are not"
                + " presentation-flipped.\n");
    try (var buffer =
        runtime
            .device()
            .createBuffer(
                () -> "Shader image diagnostics",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                offset)) {
      runtime.suspend();
      var encoder = runtime.device().createCommandEncoder();
      for (Capture capture : captures)
        encoder.copyTextureToBuffer(capture.texture, buffer, capture.offset, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(10_000_000_000L))
          throw new AssertionError("Shader diagnostics GPU readback timed out");
      }
      Files.createDirectories(directory);
      boolean shadowPopulated = true;
      try (var mapped = buffer.map(true, false)) {
        ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
        for (Capture capture : captures)
          shadowPopulated &= write(capture, data, directory, name, report);
      }
      report
          .append("Submitted feature tags: ")
          .append(FeatureDrawContext.submittedTagCounts())
          .append('\n');
      report
          .append("Shadow projection: ")
          .append(Arrays.toString(runtime.uniforms().shadowProjection().get(new float[16])))
          .append('\n');
      report
          .append("Shadow model view: ")
          .append(Arrays.toString(runtime.uniforms().shadowModelView().get(new float[16])))
          .append('\n');
      report
          .append("Sun world direction: ")
          .append(runtime.uniforms().sunDirectionWorld())
          .append('\n');
      report
          .append("Shadow world direction: ")
          .append(runtime.uniforms().shadowDirectionWorld())
          .append('\n');
      Files.writeString(directory.resolve(name + "-diagnostics.txt"), report);
      System.out.println("Shader image diagnostics " + name + ":\n" + report);
      if (runtime.programs().find("shadow") != null && !shadowPopulated) {
        throw new AssertionError(
            "Expected scene geometry to populate shadowtex0; readback was entirely clear: " + name);
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static boolean write(
      Capture capture, ByteBuffer data, Path directory, String name, StringBuilder report)
      throws IOException {
    GpuFormat format = capture.texture.getFormat();
    boolean depth = format == GpuFormat.D32_FLOAT;
    int pixels = capture.width * capture.height;
    int stride = format.blockSize();
    float minimum = Float.POSITIVE_INFINITY, maximum = Float.NEGATIVE_INFINITY;
    float drawnMinimum = Float.POSITIVE_INFINITY, drawnMaximum = Float.NEGATIVE_INFINITY;
    int nonClear = 0, nonFinite = 0;
    BufferedImage raw =
        new BufferedImage(capture.width, capture.height, BufferedImage.TYPE_INT_RGB);
    for (int pixel = 0; pixel < pixels; pixel++) {
      int start = capture.offset + pixel * stride;
      float red = component(data, start, format, 0);
      float green = depth ? red : component(data, start, format, 1);
      float blue = depth ? red : component(data, start, format, 2);
      if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)) nonFinite++;
      else {
        minimum = Math.min(minimum, Math.min(red, Math.min(green, blue)));
        maximum = Math.max(maximum, Math.max(red, Math.max(green, blue)));
      }
      if (depth && Float.isFinite(red) && red < 1 - 1e-6f) {
        nonClear++;
        drawnMinimum = Math.min(drawnMinimum, red);
        drawnMaximum = Math.max(drawnMaximum, red);
      }
      raw.setRGB(pixel % capture.width, pixel / capture.width, rgb(red, green, blue));
    }
    ImageIO.write(
        raw, "png", directory.resolve(name + "-" + capture.label + "-raw-row0.png").toFile());
    report.append(
        String.format(
            Locale.ROOT,
            "%s %s %dx%d min=%.9f max=%.9f nonFinite=%d",
            capture.label,
            format,
            capture.width,
            capture.height,
            minimum,
            maximum,
            nonFinite));
    if (depth) {
      report.append(
          String.format(
              Locale.ROOT,
              " nonClear=%d/%d drawnMin=%.9f drawnMax=%.9f",
              nonClear,
              pixels,
              drawnMinimum,
              drawnMaximum));
      BufferedImage range =
          new BufferedImage(capture.width, capture.height, BufferedImage.TYPE_INT_RGB);
      for (int pixel = 0; pixel < pixels; pixel++) {
        float value = data.getFloat(capture.offset + pixel * stride);
        float intensity =
            value >= 1 - 1e-6f
                ? 1
                : (value - drawnMinimum) / Math.max(1e-8f, drawnMaximum - drawnMinimum);
        range.setRGB(
            pixel % capture.width, pixel / capture.width, rgb(intensity, intensity, intensity));
      }
      ImageIO.write(
          range,
          "png",
          directory.resolve(name + "-" + capture.label + "-drawn-range-row0.png").toFile());
    }
    report.append('\n');
    return !capture.label.equals("shadowtex0") || nonClear > 0;
  }

  private static float component(ByteBuffer data, int offset, GpuFormat format, int component) {
    return switch (format) {
      case D32_FLOAT -> data.getFloat(offset);
      case RGBA8_UNORM -> Byte.toUnsignedInt(data.get(offset + component)) / 255.0f;
      case RGBA16_FLOAT -> Float.float16ToFloat(data.getShort(offset + component * 2));
      case RGBA32_FLOAT -> data.getFloat(offset + component * 4);
      case RG11B10_FLOAT -> {
        int packed = data.getInt(offset);
        int bits =
            component == 0 ? packed & 2047 : component == 1 ? packed >>> 11 & 2047 : packed >>> 22;
        yield unsignedFloat(bits, component == 2 ? 5 : 6);
      }
      default -> throw new UnsupportedOperationException("Diagnostic color readback for " + format);
    };
  }

  private static float unsignedFloat(int bits, int mantissaBits) {
    int exponent = bits >>> mantissaBits;
    int mantissa = bits & ((1 << mantissaBits) - 1);
    if (exponent == 31) return mantissa == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
    if (exponent == 0) return Math.scalb((float) mantissa, -14 - mantissaBits);
    return Math.scalb(1 + mantissa / (float) (1 << mantissaBits), exponent - 15);
  }

  private static int rgb(float red, float green, float blue) {
    return channel(red) << 16 | channel(green) << 8 | channel(blue);
  }

  private static int channel(float value) {
    return Math.clamp(Math.round(value * 255), 0, 255);
  }
}
