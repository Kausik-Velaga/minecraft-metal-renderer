package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.ShaderCompatibilityCompiler.ShadowComparison;
import dev.kausik.shaders.pack.ShaderPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Exercises real source discovery; filenames and explicit non-default options cannot enable it. */
public final class RelaxedFragmentMathPolicyTest {
  private static final String FLAG = "minecraftShaders.relaxedFragmentMath";

  public static void main(String[] args) throws Exception {
    String previous = System.getProperty(FLAG);
    Path temporary = Files.createTempDirectory("relaxed-fragment-policy-");
    try {
      System.setProperty(FLAG, "true");
      Path fake = temporary.resolve("BSL_v10.1.8/shaders/world0");
      Files.createDirectories(fake);
      Files.writeString(
          fake.resolve("gbuffers_terrain.vsh"),
          "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n");
      Files.writeString(
          fake.resolve("gbuffers_terrain.fsh"),
          "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}\n");
      try (var programs =
          new PackPrograms(
              ShaderPack.load(fake.getParent().getParent()),
              Map.of(),
              "minecraft:overworld",
              ShadowComparison.HARDWARE)) {
        check(
            !programs.relaxedFragmentMath
                && !programs.find("gbuffers_terrain").relaxedFragmentMath(),
            "Unrecognized content inherited relaxed policy by filename");
      }
      if (args.length > 0) {
        ShaderPack pack = ShaderPack.load(Path.of(args[0]));
        verify(pack, Map.of(), "minecraft:overworld", ShadowComparison.HARDWARE, true);
        verify(
            pack,
            pack.optionValues(Map.of()),
            "minecraft:overworld",
            ShadowComparison.HARDWARE,
            true);
        verify(
            pack,
            Map.of("AO_METHOD", "1"),
            "minecraft:overworld",
            ShadowComparison.HARDWARE,
            false);
        verify(pack, Map.of(), "minecraft:the_nether", ShadowComparison.HARDWARE, false);
        verify(pack, Map.of(), "minecraft:the_end", ShadowComparison.HARDWARE, false);
        verify(pack, Map.of(), "example:overworld", ShadowComparison.HARDWARE, false);
        verify(pack, Map.of(), "minecraft:overworld", ShadowComparison.EMULATED, false);
        System.setProperty(FLAG, "false");
        verify(pack, Map.of(), "minecraft:overworld", ShadowComparison.HARDWARE, false);
      }
      System.out.println(
          "PASS: fragment math policy is opt-in, immutable per source, exact audited"
              + " content/defaults/Overworld/hardware only; compatibility sources remain Safe");
    } finally {
      if (previous == null) System.clearProperty(FLAG);
      else System.setProperty(FLAG, previous);
      try (var paths = Files.walk(temporary)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void verify(
      ShaderPack pack,
      Map<String, String> options,
      String dimension,
      ShadowComparison comparison,
      boolean expected)
      throws Exception {
    try (var programs = new PackPrograms(pack, options, dimension, comparison)) {
      check(
          programs.relaxedFragmentMath == expected,
          "Wrong pack policy for " + dimension + " " + options + " " + comparison);
      for (String name :
          List.of("gbuffers_terrain", "shadow", "deferred", "deferred1", "composite", "final")) {
        var source = programs.find(name);
        if (source == null) continue; // A dimension or option may disable a pack pass.
        check(source.relaxedFragmentMath() == expected, "Wrong source policy for " + name);
        var copied =
            new PackPrograms.Source(
                source.name(),
                source.vertex(),
                source.fragment(),
                source.translated(),
                source.directives());
        check(!copied.relaxedFragmentMath(), "Compatibility constructor implicitly opted in");
      }
      String previous = System.getProperty(FLAG);
      System.setProperty(FLAG, Boolean.toString(!expected));
      check(
          programs.find("gbuffers_terrain").relaxedFragmentMath() == expected,
          "Active source policy changed with a process property");
      System.setProperty(FLAG, previous);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
