package dev.kausik.shaders.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Material semantics, source-identity guards and atlas borders survive generation and reduction.
 */
public final class MaterialPaletteTest {
  public static void main(String[] args) throws Exception {
    String source = Files.readString(Path.of(args[0]));
    var palette = MaterialPalette.parse(source);
    require(palette.tiles().size() == 50, "Expected reviewed Canopy library");
    var ore = palette.tiles().get("minecraft:block/gold_ore");
    require(ore.rgba().length == 256 && !ore.uniform(), "Ore has a per-pixel mask");
    int stone = 0, metal = 0;
    for (int pixel : ore.rgba()) {
      if ((pixel >>> 24) == 0) stone++;
      else metal++;
      require((pixel >>> 16 & 255) == 0, "Neither gold nor stone emits light");
    }
    require(stone > 0 && metal > 0, "Mixed surface is not treated as entirely metal");
    for (int pixel : palette.tiles().get("minecraft:block/redstone_lamp").rgba())
      require((pixel >>> 16 & 255) == 0, "Unpowered lamp cannot emit");
    var lava = palette.tiles().get("minecraft:block/lava_still");
    require(
        lava.matches(new byte[0], 32, 32, true),
        "Uniform material supports animated and resized art");
    byte[] albedo = {1, 2, 3};
    var tile =
        new MaterialPalette.Tile(
            2,
            2,
            new int[] {0x0000ff00, 0xff004000, 0x00ff8000, 0x0000ff80},
            MaterialPalette.sha256(albedo));
    require(tile.matches(albedo, 2, 2, false), "Matching albedo activates mask");
    require(
        !tile.matches(new byte[] {4}, 2, 2, false), "Resource override invalidates original mask");
    require(!tile.matches(albedo, 4, 4, false), "Changed dimensions invalidate mask");
    require(!tile.matches(albedo, 2, 2, true), "Varying animated mask cannot freeze on a frame");
    ByteBuffer atlas = ByteBuffer.allocate(8 * 8 * 4);
    MaterialAtlas.blit(atlas, 8, 8, 3, 3, 2, 2, 1, tile);
    require(atlas.getInt((2 * 8 + 2) * 4) == tile.rgba()[0], "Top-left padding repeats edge");
    require(atlas.getInt((5 * 8 + 5) * 4) == tile.rgba()[3], "Bottom-right padding repeats edge");
    require(atlas.getInt(0) == 0, "Unannotated region remains sentinel");
    ByteBuffer next = ByteBuffer.allocate(4);
    ByteBuffer input = ByteBuffer.allocate(16);
    for (int value : tile.rgba()) input.putInt(value);
    MaterialAtlas.downsample(input, 2, 2, next);
    require(
        Byte.toUnsignedInt(next.get(0)) == 64
            && Byte.toUnsignedInt(next.get(1)) == 64
            && Byte.toUnsignedInt(next.get(2)) == 176
            && Byte.toUnsignedInt(next.get(3)) == 32,
        "All four material channels reduce linearly, without color gamma or alpha"
            + " premultiplication");
    reject(source.replace("\"roughness\": 1", "\"roughness\": 0"));
    reject(source.replace("\"metalness\": 0.9", "\"metalness\": 1.5"));
    reject(source.replace("\"preset\": \"iron\"", "\"preset\": \"missing\""));
    System.out.println(
        "PASS: material masks, emission separation, source and animation guards, padding, linear"
            + " mipmaps and invalid recipes");
  }

  private static void reject(String json) throws Exception {
    try {
      MaterialPalette.parse(json);
    } catch (IOException expected) {
      return;
    }
    throw new AssertionError("Invalid material recipe accepted");
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
