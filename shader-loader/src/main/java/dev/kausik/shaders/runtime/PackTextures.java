package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Decodes user pack images once; GPU textures and samplers are reused for every frame. */
public final class PackTextures implements AutoCloseable {
  public final GpuSampler linear, nearest, repeat;
  public final PackTexture white, black, normal, noise;
  private final Map<String, PackTexture> custom = new HashMap<>();
  private final List<PackTexture> owned = new ArrayList<>();
  private final GpuDevice device;

  public PackTextures(GpuDevice device, ShaderPack pack, ShaderProperties properties)
      throws IOException {
    this.device = device;
    GpuSampler createdLinear = null, createdNearest = null, createdRepeat = null;
    try {
      createdLinear =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE,
              AddressMode.CLAMP_TO_EDGE,
              FilterMode.LINEAR,
              FilterMode.LINEAR,
              1,
              OptionalDouble.empty());
      createdNearest =
          device.createSampler(
              AddressMode.CLAMP_TO_EDGE,
              AddressMode.CLAMP_TO_EDGE,
              FilterMode.NEAREST,
              FilterMode.NEAREST,
              1,
              OptionalDouble.of(0));
      createdRepeat =
          device.createSampler(
              AddressMode.REPEAT,
              AddressMode.REPEAT,
              FilterMode.LINEAR,
              FilterMode.LINEAR,
              1,
              OptionalDouble.of(0));
      linear = createdLinear;
      nearest = createdNearest;
      repeat = createdRepeat;
      white = solid("white", 255, 255, 255, 255);
      black = solid("black", 0, 0, 0, 255);
      normal = solid("normal", 128, 128, 255, 255);
      for (var property : properties.values().entrySet()) {
        if (!property.getKey().startsWith("texture.")) continue;
        String path = property.getValue();
        if (path.contains(" "))
          throw new UnsupportedOperationException(
              "Raw custom texture declaration: " + property.getKey());
        custom.put(property.getKey().substring(8), image(pack, path));
      }
      PackTexture suppliedNoise = custom.get("noise");
      noise = suppliedNoise == null ? generatedNoise() : suppliedNoise;
    } catch (IOException | RuntimeException failure) {
      closeTextures();
      if (createdRepeat != null) createdRepeat.close();
      if (createdNearest != null) createdNearest.close();
      if (createdLinear != null) createdLinear.close();
      throw failure;
    }
  }

  private PackTexture solid(String name, int r, int g, int b, int a) {
    ByteBuffer rgba =
        ByteBuffer.allocateDirect(4).put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
    return upload(name, 1, 1, rgba);
  }

  private PackTexture generatedNoise() {
    int size = 256;
    java.util.Random random = new java.util.Random(0x5EED);
    ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
    while (pixels.hasRemaining())
      pixels
          .put((byte) random.nextInt(256))
          .put((byte) random.nextInt(256))
          .put((byte) random.nextInt(256))
          .put((byte) 255);
    return upload("noise", size, size, pixels.flip());
  }

  private PackTexture image(ShaderPack pack, String path) throws IOException {
    BufferedImage image = decodeImage(pack.bytes(path), path);
    int width = image.getWidth(), height = image.getHeight();
    ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int argb = image.getRGB(x, y);
        pixels
            .put((byte) (argb >> 16))
            .put((byte) (argb >> 8))
            .put((byte) argb)
            .put((byte) (argb >> 24));
      }
    return upload(path, width, height, pixels.flip());
  }

  /** Inspect the encoded dimensions before an image reader can allocate decoded pixel storage. */
  static BufferedImage decodeImage(byte[] bytes, String path) throws IOException {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new IOException("Unsupported pack texture " + path);
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1 || height < 1 || (long) width * height > 16_777_216L)
          throw new IOException("Pack image exceeds texture size limit: " + path);
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  private PackTexture upload(String name, int width, int height, ByteBuffer pixels) {
    PackTexture result = new PackTexture(device, name, GpuFormat.RGBA8_UNORM, width, height, 1);
    owned.add(result);
    device.createCommandEncoder().writeToTexture(result.texture, pixels, 0, 0, 0, 0, width, height);
    return result;
  }

  public PackTexture custom(String program, String sampler) {
    String group =
        program.startsWith("gbuffers_")
            ? "gbuffers"
            : program.startsWith("deferred")
                ? "deferred"
                : program.startsWith("composite") || program.equals("final")
                    ? "composite"
                    : program.startsWith("shadow") ? "shadow" : program.replaceAll("[0-9]+$", "");
    return custom.get(group + "." + sampler);
  }

  @Override
  public void close() {
    closeTextures();
    repeat.close();
    nearest.close();
    linear.close();
  }

  private void closeTextures() {
    for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    owned.clear();
    custom.clear();
  }
}
