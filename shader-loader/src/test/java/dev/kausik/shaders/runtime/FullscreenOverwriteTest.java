package dev.kausik.shaders.runtime;

import dev.kausik.shaders.compile.ShaderCompatibilityCompiler;
import dev.kausik.shaders.compile.TranslatedProgram;
import dev.kausik.shaders.compile.UniformInitializerLifting;
import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.pack.ShaderPack;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Fullscreen load-discard proofs are CPU-only; backend bounds have separate pixel tests. */
public final class FullscreenOverwriteTest {
  private static final String VERTEX = "void main() { gl_Position = ftransform(); }";
  private static final String STORE = "sl_FragData0 = vec4(1.0);";
  private static final String OUTPUT = "layout(location=0) out vec4 sl_FragData0;\n";

  public static void main(String[] args) throws Exception {
    accept(VERTEX, "void main() { " + STORE + " }", 1);
    accept(VERTEX, "float helper(float x) { if (x>0.) return x; return 0.; }"
        + "void main() { float x=helper(1.); if(x>0.) { x*=2.; } " + STORE + " }", 1);
    accept("/* #define gl_Position anything\n discard */ " + VERTEX,
        "// discard; gl_FragDepth=0;\nvoid main() { " + STORE + " }", 1);
    var holes = program(VERTEX, OUTPUT + "layout(location=2) out vec4 sl_FragData2;\n"
        + "void main() { " + STORE + " sl_FragData2=vec4(2.); }", List.of(4, -1, 7));
    check(FullscreenOverwrite.analyze(holes).colorMask() == 5, "Masks use locations, not logical targets");
    reject(program(VERTEX, OUTPUT + "layout(location=1) out vec4 sl_FragData1;\n"
        + "void main() { " + STORE + " sl_FragData1=vec4(2.); }", List.of(0, 0)), "aliased target");
    for (String vertex : List.of(
        "void main() { if (true) gl_Position=ftransform(); }",
        "void main() { if (true) { gl_Position=ftransform(); } }",
        "void main() { if (true) return; gl_Position=ftransform(); }",
        "void main() { gl_Position=ftransform(); gl_Position.x*=0.5; }",
        "void main() { gl_Position=vec4(0.); }",
        "void main() { gl_Vertex.x=0.; gl_Position=ftransform(); }",
        "void main() { sl_Vertex.x=0.; gl_Position=ftransform(); }",
        "void mutate(inout vec4 x){x.x=0.;} void main(){mutate(sl_Vertex);gl_Position=ftransform();}",
        "void main() { gl_ClipDistance[0]=-1.; gl_Position=ftransform(); }",
        "void main() { gl_ViewportMask[0]=0; gl_Position=ftransform(); }",
        "#define ftransform() vec4(0.)\n" + VERTEX)) {
      reject(program(vertex, OUTPUT + "void main() { " + STORE + " }", List.of(0)), vertex);
    }
    for (String fragment : List.of(
        "void main() { if (true) { " + STORE + " } }",
        "void main() { if (true) " + STORE + " }",
        "void main() { if (true) {} else " + STORE + " }",
        "void main() { for (int i=0;i<1;i++) " + STORE + " }",
        "void main() { do " + STORE + " while(false); }",
        "void main() { if (true) return; " + STORE + " }",
        "void main() { sl_FragData0.rgb=vec3(1.); }",
        "void main() { sl_FragData0+=vec4(1.); }",
        "void main() { " + STORE + STORE + " }",
        "void main() { " + STORE + " float x=sl_FragData0.r; }",
        "void helper(out vec4 c){c=vec4(1.);} void main(){helper(sl_FragData0);}",
        "void main() { vec4 sl_FragData0=vec4(1.); }",
        "void main() {}",
        "void main() { discard; " + STORE + " }",
        "void main() { demote; " + STORE + " }",
        "void main() { terminateInvocation; " + STORE + " }",
        "void kill(){discard;} void main() { kill(); " + STORE + " }",
        "void main() { gl_FragDepth=0.; " + STORE + " }",
        "void main() { gl_SampleMask[0]=0; " + STORE + " }",
        "void main() { imageStore(dst,ivec2(0),vec4(1.)); " + STORE + " }",
        "#define SOMETHING discard\nvoid main() { " + STORE + " }")) {
      reject(program(VERTEX, OUTPUT + fragment, List.of(0)), fragment);
    }
    translated();
    if (args.length > 0) suppliedPack(Path.of(args[0]));
    System.out.println("PASS: fullscreen overwrite proof rejects partial coverage, control flow,"
        + " attachment aliasing and shader side effects; accepts full unconditional MRT stores");
  }

  private static void translated() {
    try (var translator = new ShaderCompatibilityCompiler()) {
      var program = translator.translate(
          "#version 120\nvarying vec2 uv;\nvoid main(){uv=gl_MultiTexCoord0.xy;gl_Position=ftransform();}",
          "#version 120\nuniform vec4 params;\nvarying vec2 uv;\n"
              + "vec4 tone=sqrt(abs(params));\nvoid main(){gl_FragColor=tone+vec4(uv,0.,1.);}",
          "fullscreen_overwrite_fixture", ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
      check(FullscreenOverwrite.analyze(program).colorMask() == 1, "Actual compiler output must qualify");
      check(FullscreenOverwrite.analyze(UniformInitializerLifting.apply(program).program()).colorMask() == 1,
          "Uniform initializer lifting does not change coverage");
    }
  }

  private static void suppliedPack(Path path) throws Exception {
    var pack = ShaderPack.load(path);
    int eligible = 0, total = 0;
    try (var programs = new PackPrograms(pack, Map.of(), "minecraft:overworld",
        ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
      for (String phase : List.of("setup", "begin", "prepare", "deferred", "composite", "final")) {
        var sequence = phase.equals("final")
            ? programs.find("final") == null ? List.<PackPrograms.Source>of() : List.of(programs.find("final"))
            : programs.sequence(phase);
        for (var source : sequence) {
          var result = FullscreenOverwrite.analyze(source.translated());
          System.out.println(source.name() + " fullscreen overwrite mask=" + result.colorMask() + " " + result.reason());
          if (result.eligible()) eligible++;
          total++;
        }
      }
    }
    check(eligible > 0, "Supplied pack had no proven fullscreen overwrite stages out of " + total);
  }

  private static void accept(String vertex, String body, int expected) {
    var result = FullscreenOverwrite.analyze(program(vertex, OUTPUT + body, List.of(0)));
    check(result.colorMask() == expected, "Unexpected rejection: " + result.reason());
  }

  private static void reject(TranslatedProgram program, String scenario) {
    check(!FullscreenOverwrite.analyze(program).eligible(), "Unsafe proof accepted: " + scenario);
  }

  private static TranslatedProgram program(String vertex, String fragment, List<Integer> targets) {
    return new TranslatedProgram("fullscreen_overwrite_fixture", vertex, fragment,
        new UniformLayout(List.of(), 0), List.of(), List.of(), targets, vertex, fragment);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
