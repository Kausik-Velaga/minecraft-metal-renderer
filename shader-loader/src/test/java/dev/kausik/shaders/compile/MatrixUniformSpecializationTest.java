package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.runtime.FrameUniforms;
import dev.kausik.shaders.runtime.PackPrograms;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;

/**
 * Exact guard/lifetime checks plus compilation of supplied pack algorithms, without copying them.
 */
public final class MatrixUniformSpecializationTest {
  public static void main(String[] args) throws Exception {
    long compiler = shaderc_compiler_initialize();
    try (var translator = new ShaderCompatibilityCompiler()) {
      for (float fov : new float[] {30, 70, 110}) {
        for (float aspect : new float[] {0.7f, 1, 1.5941f, 2.4f}) {
          Matrix4f nativeProjection =
              new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 1024, .05f, true);
          FrameUniforms values = new FrameUniforms();
          values.setProjection(nativeProjection);
          check(
              values.matchesMatrix(MatrixUniformSpecialization.PROJECTION_INVERSE),
              "Normal camera missed sparse guard");
          float[] inverse =
              new Matrix4f().m22(-2).m32(1).mul(nativeProjection).invert().get(new float[16]);
          for (int index = 0; index < 16; index++) {
            float[] modified = inverse.clone();
            modified[index] = Float.NaN;
            check(
                !MatrixUniformSpecialization.PROJECTION_INVERSE.matches(modified),
                "Nonfinite matrix accepted");
            if ((MatrixUniformSpecialization.PROJECTION_INVERSE.zeroMask() & (1 << index)) != 0) {
              modified[index] =
                  Float.intBitsToFloat(Float.floatToRawIntBits(inverse[index]) ^ 0x80000000);
              check(
                  !MatrixUniformSpecialization.PROJECTION_INVERSE.matches(modified),
                  "Changed zero sign accepted");
              modified[index] = Float.MIN_VALUE;
              check(
                  !MatrixUniformSpecialization.PROJECTION_INVERSE.matches(modified),
                  "Tiny off-axis term treated as zero");
            }
          }
          values.setProjection(new Matrix4f(nativeProjection).m20(.01f));
          check(
              !values.matchesMatrix(MatrixUniformSpecialization.PROJECTION_INVERSE),
              "Off-axis projection did not fall back");
          values.setProjection(new Matrix4f().ortho(-1, 1, -1, 1, .05f, 1024));
          check(
              !values.matchesMatrix(MatrixUniformSpecialization.PROJECTION_INVERSE),
              "Orthographic projection did not fall back");
        }
      }
      var source =
          translator.translate(
              "#version 120\n"
                  + "varying vec2 uv; void main(){uv=gl_MultiTexCoord0.xy;gl_Position=gl_Vertex;}",
              "#version 120\n"
                  + "uniform mat4 gbufferProjectionInverse; varying vec2 uv; void"
                  + " main(){gl_FragColor=gbufferProjectionInverse*vec4(uv,.5,1.);}",
              "sparse_matrix_fixture",
              ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
      var result =
          MatrixUniformSpecialization.apply(source, MatrixUniformSpecialization.PROJECTION_INVERSE);
      check(result.applied(), "Valid matrix not specialized");
      validate(compiler, source, result.program());
      for (String fragment :
          List.of(
              source
                  .fragmentSource()
                  .replace(
                      "void main()", "void local(mat4 gbufferProjectionInverse) {}\nvoid main()"),
              source
                  .fragmentSource()
                  .replace(
                      "void main()",
                      "struct Named { mat4 gbufferProjectionInverse; };\nvoid main()"),
              source
                  .fragmentSource()
                  .replace(
                      "void main()", "#define LOOKUP gbufferProjectionInverse\nvoid main()"))) {
        check(
            !MatrixUniformSpecialization.apply(
                    withFragment(source, fragment), MatrixUniformSpecialization.PROJECTION_INVERSE)
                .applied(),
            "Ambiguous scope was rewritten");
      }
      if (args.length > 0) {
        var pack = ShaderPack.load(Path.of(args[0]));
        try (var programs =
            new PackPrograms(
                pack,
                Map.of(),
                "minecraft:overworld",
                ShaderCompatibilityCompiler.ShadowComparison.HARDWARE)) {
          for (String name :
              List.of("deferred", "deferred1", "composite", "composite5", "composite7")) {
            var original = programs.find(name).translated();
            var sparse =
                MatrixUniformSpecialization.apply(
                    original, MatrixUniformSpecialization.PROJECTION_INVERSE);
            if (name.equals("composite5")) {
              check(!sparse.applied(), "Unused projection unexpectedly specialized");
              continue;
            }
            check(
                sparse.applied(),
                "Supplied pack did not exercise matrix specialization: "
                    + name
                    + " "
                    + sparse.reason());
            validate(compiler, original, sparse.program());
            int before =
                UniformInitializerLiftingTest.compile(
                        compiler, original.fragmentSource(), shaderc_fragment_shader)
                    .length;
            int after =
                UniformInitializerLiftingTest.compile(
                        compiler, sparse.program().fragmentSource(), shaderc_fragment_shader)
                    .length;
            System.out.println(name + " sparse projection SPIRV=" + before + " -> " + after);
          }
        }
      }
      System.out.println(
          "PASS: finite exact matrix guard, signed zeros, unusual-projection fallback, scope"
              + " rejection and shader compilation");
    } finally {
      shaderc_compiler_release(compiler);
    }
  }

  private static void validate(
      long compiler, TranslatedProgram original, TranslatedProgram modified) {
    check(
        original.uniforms().equals(modified.uniforms())
            && original.samplers().equals(modified.samplers())
            && original.drawBuffers().equals(modified.drawBuffers())
            && original.attributes().equals(modified.attributes()),
        "Shader ABI or graph changed");
    UniformInitializerLiftingTest.validate(compiler, original, modified);
  }

  private static TranslatedProgram withFragment(TranslatedProgram p, String fragment) {
    return new TranslatedProgram(
        p.label(),
        p.vertexSource(),
        fragment,
        p.uniforms(),
        p.samplers(),
        p.attributes(),
        p.drawBuffers(),
        p.preprocessedVertex(),
        p.preprocessedFragment());
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
