package dev.kausik.shaders.runtime;

import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.ShaderPack;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Water must read a distinct opaque snapshot; biome transitions must remain finite and smooth. */
public final class CanopyPackTest {
  public static void main(String[] args) throws Exception {
    ShaderPack pack = ShaderPack.load(Path.of(args[0]));
    pack.profileOverrides("BALANCED");
    pack.profileOverrides("FAST");
    for (String dimension :
        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
      try (var programs = new PackPrograms(pack, Map.of(), dimension)) {
        require(
            programs.find("deferred").directives().drawBuffers().equals(List.of(1)),
            "opaque snapshot writes only colortex1");
        require(
            programs.find("gbuffers_water").directives().drawBuffers().equals(List.of(0)),
            "water writes only scene color");
        require(
            programs.readsSampler("colortex1") && programs.readsSampler("depthtex1"),
            "water reads separate opaque color and depth");
        require(
            Pattern.compile("for\\s*\\(")
                .matcher(programs.find("gbuffers_water").translated().preprocessedFragment())
                .find(),
            "BALANCED includes bounded reflection search");
        require(!programs.postReadsSampler("depthtex0"), "no unnecessary hand-depth merge");
        require(
            "RGBA16F".equals(programs.globals.constants().get("colortex1Format")),
            "snapshot preserves HDR highlights");
        if (dimension.equals("minecraft:overworld"))
          require(
              programs.find("shadow").directives().drawBuffers().isEmpty(),
              "shadow pass is depth-only");
        else require(programs.find("shadow") == null, "no shadow pass in other dimensions");
      }
    }
    try (var fast =
        new PackPrograms(pack, Map.of("WATER_REFLECTIONS", "false"), "minecraft:overworld")) {
      require(
          !Pattern.compile("for\\s*\\(")
              .matcher(fast.find("gbuffers_water").translated().preprocessedFragment())
              .find(),
          "FAST removes reflection ray loop");
    }
    CustomUniforms uniforms =
        CustomUniforms.compile(pack.properties(Map.of(), PackEnvironment.definitions()));
    Map<String, Double> inputs = new HashMap<>();
    double id = 1;
    for (String input : uniforms.requiredInputs())
      inputs.put(input, input.startsWith("BIOME_") ? id++ : 0.0);
    inputs.put("temperature", 0.8);
    inputs.put("sunAngle", 0.25);
    Map<String, Double> initial = uniforms.evaluate(inputs, 0.016);
    require(
        initial.get("canDay") == 1 && initial.get("canPale") == 0,
        "ordinary day initializes correctly");
    inputs.put("biome", inputs.get("BIOME_PALE_GARDEN"));
    Map<String, Double> transitioning = uniforms.evaluate(inputs, 0.5);
    require(
        transitioning.get("canPale") > 0 && transitioning.get("canPale") < 1,
        "biome grading changes gradually");
    inputs.put("sunAngle", 0.75);
    require(uniforms.evaluate(inputs, 0.016).get("canDay") == 0, "night uses moon palette");
    for (double value : transitioning.values())
      require(Double.isFinite(value), "finite custom uniforms");
    System.out.println(
        "PASS: Canopy water snapshot isolation, HDR, dimensions, fast reflection path and smoothed"
            + " biome uniforms");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
