package dev.kausik.shaders.runtime;

import dev.kausik.shaders.pack.ShaderPack;
import java.nio.file.Path;
import java.util.Map;

/** Protects the minimal render graph, including depth-only shadows and disabled effects. */
public final class SolsticePackTest {
  public static void main(String[] args) throws Exception {
    ShaderPack pack = ShaderPack.load(Path.of(args[0]));
    for (String dimension :
        new String[] {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
      try (var programs = new PackPrograms(pack, Map.of(), dimension)) {
        require(programs.readsSampler("depthtex1"), "water requires opaque depth");
        require(!programs.readsSampler("depthtex2"), "no third depth snapshot");
        require(!programs.readsSampler("shadowtex1"), "no opaque-only shadow copy");
        require(!programs.postReadsSampler("depthtex0"), "no hand depth merge for final");
        require(programs.postReadsSampler("colortex0"), "final must read scene color");
        require("RGBA16F".equals(programs.globals.constants().get("colortex0Format")), "HDR scene");
        require(
            programs.find("final").directives().booleanConstant("colortex0MipmapEnabled", false),
            "bloom mips");
        if (dimension.equals("minecraft:overworld")) {
          require(programs.find("shadow") != null, "daytime shadows enabled");
          require(
              programs.find("shadow").directives().drawBuffers().isEmpty(),
              "depth-only shadow attachments");
        } else {
          require(programs.find("shadow") == null, "no shadow pass in other dimensions");
        }
      }
    }
    try (var lite =
        new PackPrograms(
            pack, Map.of("SHADOWS", "false", "BLOOM", "false"), "minecraft:overworld")) {
      require(lite.find("shadow") == null, "disabled shadows skip caster pass");
      require(!lite.readsSampler("shadowtex0"), "disabled shadows skip sampling");
      require(
          !lite.find("final").directives().booleanConstant("colortex0MipmapEnabled", false),
          "disabled bloom skips mipmaps");
    }
    System.out.println(
        "PASS: Solstice HDR, depth-only shadows, resource dependencies and disabled effects");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
