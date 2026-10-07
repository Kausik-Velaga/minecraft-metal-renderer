package dev.kausik.shaders.runtime;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU-only shader-use and physical ping-pong lifetime regressions. */
public final class UnusedMipmapsTest {
  private static final String VERTEX = "void main() { gl_Position = vec4(0); }";
  private static final String ZERO = "textureLod(colortex1, uv, 0.0)";
  private static final String IMPLICIT = "texture(colortex1, uv)";

  public static void main(String[] args) {
    uses();
    lifetimes();
    System.out.println(
        "PASS: conservative mip use, aliases, all phases, physical ping-pong history and full"
            + " cycles");
  }

  private static void uses() {
    base("uniform sampler2D colortex1; void main() {}", true);
    base(fragment(ZERO), true);
    base(
        fragment("textureLod(colortex1, mix(vec2(1,2), other(texture(x, uv).xy), .5), 0.0)"), true);
    base(fragment("texelFetch(colortex1, ivec2(1,2), 0)"), true);
    base(fragment("textureLod(colortex1, uv, 0e-3f)"), true);
    base("/* texture(colortex1,uv) */ " + fragment(ZERO) + " // colortex1", true);
    base(fragment(IMPLICIT), false);
    base(fragment("textureLod(colortex1, uv, 1.0)"), false);
    base(fragment("textureLod(colortex1, uv, level)"), false);
    base(fragment("textureLod(colortex1, uv, 0.0 + level)"), false);
    base(fragment("textureGrad(colortex1, uv, vec2(0), vec2(0))"), false);
    base(fragment("sampleHelper(colortex1, uv)"), false);
    base(
        "vec4 sampleHelper(sampler2D value, vec2 p) { return textureLod(value,p,0); }"
            + fragment("sampleHelper(colortex1, uv)"),
        false);
    base(
        "vec4 textureLod(sampler2D value, vec2 p, float l) { return texture(value,p); }"
            + fragment(ZERO),
        false);
    base(
        "uniform sampler2D colortex1[2]; void main(){ vec4 c=textureLod(colortex1[0],uv,0); }",
        false);
    base(fragment("textureSize(colortex1, 0)"), false);
    base(fragment(ZERO) + " /* unterminated", false);
  }

  private static void base(String source, boolean expected) {
    check(UnusedMipmaps.baseLevelOnly(source, "colortex1") == expected, "sampler proof: " + source);
  }

  private static void lifetimes() {
    var six = program("composite6", ZERO, Set.of(1), Set.of(1));
    var seven = program("composite7", ZERO, Set.of(1), Set.of(1));
    var last = program("final", ZERO, Set.of(), Set.of());
    // The first request's physical side is current again at frame clear. The second survives
    // as alternate and next frame's composite1 can read its lower levels after a level-0 write.
    var graph =
        List.of(
            program("composite", IMPLICIT, Set.of(1), Set.of()),
            program("composite1", IMPLICIT, Set.of(0), Set.of()),
            program("composite5", "", Set.of(1), Set.of()),
            six,
            seven,
            last);
    var analysis = UnusedMipmaps.analyze(graph, Map.of());
    check(analysis.skips("composite6", 1), "chain dies at full clear after two flips");
    check(
        !analysis.skips("composite7", 1),
        "future frame implicit consumer retains alternate mip chain");
    check(
        !UnusedMipmaps.analyze(graph, Map.of(1, false)).skips("composite6", 1),
        "Clear=false preserves an otherwise dead chain for future geometry/composite reads");

    check(
        UnusedMipmaps.analyze(
                List.of(program("composite", ZERO, Set.of(), Set.of(1)), last), Map.of(1, false))
            .skips("composite", 1),
        "full cycle ends at same-side regeneration");
    check(
        !UnusedMipmaps.analyze(
                List.of(
                    program("composite", ZERO, Set.of(1), Set.of(1)),
                    program("final", IMPLICIT, Set.of(), Set.of())),
                Map.of(1, false))
            .skips("composite", 1),
        "second frame parity reads surviving alternate after a level-zero overwrite");
    check(
        UnusedMipmaps.analyze(
                List.of(
                    program("composite", ZERO, Set.of(), Set.of(1)),
                    program("composite1", IMPLICIT, Set.of(), Set.of(1)),
                    last),
                Map.of(1, false))
            .skips("composite", 1),
        "later mandatory regeneration precedes nonzero read");

    for (String consumer : List.of("begin", "prepare", "deferred", "gbuffers_terrain", "shadow")) {
      var result =
          UnusedMipmaps.analyze(
              List.of(
                  program(consumer, IMPLICIT, Set.of(), Set.of()),
                  program("composite", ZERO, Set.of(), Set.of(1)),
                  last),
              Map.of(1, false));
      check(!result.skips("composite", 1), "consumer in phase " + consumer);
    }
    check(
        !UnusedMipmaps.analyze(
                List.of(
                    program("prepare", ZERO, Set.of(1), Set.of(1)),
                    program("composite", ZERO, Set.of(1), Set.of(1)),
                    last),
                Map.of(1, false))
            .skips("composite", 1),
        "optional prepare flip cannot prove a lifetime");
    check(
        !UnusedMipmaps.analyze(
                List.of(program("future_stage", "", Set.of(), Set.of()), six, seven, last),
                Map.of())
            .skips("composite6", 1),
        "unknown stage keeps eager behavior");
    check(
        !UnusedMipmaps.analyze(
                List.of(
                    program("composite", ZERO, Set.of(), Set.of(1)),
                    program("composite0", ZERO, Set.of(), Set.of()),
                    last),
                Map.of())
            .skips("composite", 1),
        "equal stage ordinals have no guaranteed runtime order");

    var aliases =
        new UnusedMipmaps.Program(
            "composite",
            VERTEX,
            "uniform sampler2D colortex1; uniform sampler2D gdepth; void main(){"
                + "vec4 a=textureLod(colortex1,uv,0); vec4 b=texture(gdepth,uv);}",
            List.of("colortex1", "gdepth"),
            Set.of(),
            Set.of(1));
    check(
        !UnusedMipmaps.analyze(List.of(aliases, last), Map.of()).skips("composite", 1),
        "every alias of the physical buffer must pass");
    var vertexRead =
        new UnusedMipmaps.Program(
            "composite",
            fragment("textureLod(colortex1,uv,2)"),
            fragment(ZERO),
            List.of("colortex1"),
            Set.of(),
            Set.of(1));
    check(
        !UnusedMipmaps.analyze(List.of(vertexRead, last), Map.of()).skips("composite", 1),
        "vertex sampler access cannot be ignored");
    var other =
        new UnusedMipmaps.Program(
            "composite",
            VERTEX,
            "uniform sampler2D gaux1; void main(){vec4 a=textureLod(gaux1,uv,0);}",
            List.of("gaux1"),
            Set.of(),
            Set.of(4));
    check(
        UnusedMipmaps.analyze(List.of(other), Map.of()).skips("composite", 4),
        "proof is independent of buffer number and source name");
  }

  private static UnusedMipmaps.Program program(
      String name, String read, Set<Integer> outputs, Set<Integer> requested) {
    return new UnusedMipmaps.Program(
        name, VERTEX, fragment(read), List.of("colortex1"), outputs, requested);
  }

  private static String fragment(String expression) {
    return "uniform sampler2D colortex1; void main(){"
        + (expression.isEmpty() ? "" : "vec4 color = " + expression + ";")
        + "}";
  }

  private static void check(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }
}
