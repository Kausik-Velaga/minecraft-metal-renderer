package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.runtime.PackPrograms;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** CPU compilation and conservative eligibility checks; supplied pack code is loaded at runtime. */
public final class UniformInitializerLiftingTest {
  public static void main(String[] args) throws Exception {
    long compiler = shaderc_compiler_initialize();
    try (var translator = new ShaderCompatibilityCompiler()) {
      var source = fixture(translator);
      var lifted = UniformInitializerLifting.apply(source);
      check(lifted.lifted().contains("tone"), "Uniform dependency chain not lifted");
      check(lifted.lifted().size() <= 6, "Exceeded varying budget");
      check(lifted.program().fragmentSource().contains("flat in"), "Interpolated uniform result");
      check(
          lifted.program().fragmentSource().contains("vec4 tone = sl_lift_value_tone"),
          "Original global scope changed");
      check(lifted.program().uniforms().equals(source.uniforms()), "Uniform ABI changed");
      validate(compiler, source, lifted.program());
      for (String operation :
          List.of("tone.x += 1.;", "tone[0]++;", "--tone.y;", "mutate(tone);")) {
        String fragment =
            source.fragmentSource().replace("void main() {", "void main() { " + operation);
        var rejected = UniformInitializerLifting.apply(withFragment(source, fragment));
        check(!rejected.lifted().contains("tone"), "Accepted mutable dependency: " + operation);
      }
      for (String expression :
          List.of(
              "vec4(texCoord,0.,1.)",
              "vec4(dFdx(params.x))",
              "texture(atlas,vec2(.5))",
              "helper(params)")) {
        String fragment =
            source.fragmentSource().replace("sqrt(abs(initialValue)) + vec4(0.125)", expression);
        var rejected = UniformInitializerLifting.apply(withFragment(source, fragment));
        check(!rejected.lifted().contains("tone"), "Accepted unproven expression: " + expression);
      }
      // A locally shadowed name is retained at its use site: only the global initializer moves.
      var shadowed =
          UniformInitializerLifting.apply(
              withFragment(
                  source,
                  source
                      .fragmentSource()
                      .replace("void main() {", "void main() { { vec4 tone=vec4(9.); }")));
      validate(compiler, source, shadowed.program());
      if (args.length > 0) bsl(compiler, Path.of(args[0]));
      System.out.println(
          "PASS: uniform-only fullscreen initializers compile with flat transport; mutation,"
              + " sampler, derivative and helper dependencies remain in fragments");
    } finally {
      shaderc_compiler_release(compiler);
    }
  }

  static TranslatedProgram fixture(ShaderCompatibilityCompiler translator) {
    return translator.translate(
        """
        #version 120
        varying vec2 texCoord;
        void main(){texCoord=gl_MultiTexCoord0.xy;gl_Position=gl_Vertex;}
        """,
        """
        #version 120
        uniform vec4 params;
        uniform int index;
        uniform sampler2D atlas;
        varying vec2 texCoord;
        const float weights[3]=float[3](.375,.625,1.125);
        vec4 initialValue = params * weights[index] + params.wzyx * .25;
        vec4 tone = sqrt(abs(initialValue)) + vec4(0.125);
        vec4 helper(vec4 p) { return p * p; }
        void mutate(inout vec4 p) { p *= 2.; }
        void main() { gl_FragColor=tone + vec4(texCoord,.25,.5); }
        """,
        "uniform_lifting_fixture",
        ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
  }

  private static void bsl(long compiler, Path path) throws Exception {
    var pack = ShaderPack.load(path);
    int count = 0;
    try (var programs =
        new PackPrograms(
            pack,
            Map.of(),
            "minecraft:overworld",
            ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
      for (String name :
          List.of(
              "deferred",
              "deferred1",
              "composite",
              "composite1",
              "composite4",
              "composite5",
              "composite6",
              "composite7",
              "final")) {
        var source = programs.find(name).translated();
        var lifted = UniformInitializerLifting.apply(source);
        validate(compiler, source, lifted.program());
        if (!lifted.applied()) continue;
        count++;
        byte[] before = compile(compiler, source.fragmentSource(), shaderc_fragment_shader);
        byte[] after =
            compile(compiler, lifted.program().fragmentSource(), shaderc_fragment_shader);
        System.out.println(
            name
                + " lifted="
                + lifted.lifted()
                + " expressionOps="
                + lifted.expressionOperations()
                + " fragmentSPIRV="
                + before.length
                + " -> "
                + after.length);
        if (name.equals("deferred1") || name.equals("composite")) {
          check(
              lifted.lifted().contains("weatherCol"),
              "Expensive uniform biome blend stayed in " + name);
          check(after.length < before.length, "No optimized fragment reduction in " + name);
        }
      }
    }
    check(count >= 3, "Supplied BSL did not exercise several fullscreen stages");
  }

  static void validate(long compiler, TranslatedProgram original, TranslatedProgram lifted) {
    check(original.drawBuffers().equals(lifted.drawBuffers()), "Changed output graph");
    compile(compiler, lifted.vertexSource(), shaderc_vertex_shader);
    compile(compiler, lifted.fragmentSource(), shaderc_fragment_shader);
  }

  static byte[] compile(long compiler, String source, int stage) {
    long options = shaderc_compile_options_initialize();
    try {
      shaderc_compile_options_set_target_env(
          options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
      shaderc_compile_options_set_auto_bind_uniforms(options, true);
      shaderc_compile_options_set_optimization_level(
          options, shaderc_optimization_level_performance);
      long result =
          shaderc_compile_into_spv(compiler, source, stage, "uniform_lifting", "main", options);
      try {
        check(
            shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success,
            shaderc_result_get_error_message(result));
        var bytes = shaderc_result_get_bytes(result);
        byte[] copy = new byte[bytes.remaining()];
        bytes.get(copy);
        return copy;
      } finally {
        shaderc_result_release(result);
      }
    } finally {
      shaderc_compile_options_release(options);
    }
  }

  static TranslatedProgram withFragment(TranslatedProgram source, String fragment) {
    return new TranslatedProgram(
        source.label(),
        source.vertexSource(),
        fragment,
        source.uniforms(),
        source.samplers(),
        source.attributes(),
        source.drawBuffers(),
        source.preprocessedVertex(),
        source.preprocessedFragment());
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
