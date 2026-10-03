package dev.kausik.shaders.compile;

import static org.lwjgl.util.shaderc.Shaderc.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real shaderc compilation and byte-layout checks; no Minecraft window is required. */
public final class ShaderCompatibilityTest {
  public static void main(String[] args) throws Exception {
    testStd140();
    try (var compiler = new ShaderCompatibilityCompiler()) {
      String vertex =
          "#version 120\nvarying vec2 uv; uniform mat4 gbufferProjection;\n"
              + "void main(){ uv=gl_MultiTexCoord0.xy; gl_Position=ftransform(); }";
      String fragment =
          "#version 120\n"
              + "varying vec2 uv; uniform sampler2D texture;\n"
              + "uniform vec3 tint; uniform float intensity;\n"
              + "#if 0\n"
              + "/*DRAWBUFFERS:012345*/\n"
              + " uniform image3D forbidden;\n"
              + "#endif\n"
              + "/* const int colortex0Format = RGBA16F; */\n"
              + "void main(){ bool new = uv.x > 0.5; /*DRAWBUFFERS:03*/"
              + " gl_FragData[0]=texture2D(texture, uv) *"
              + " float(new);gl_FragData[1]=vec4(tint*intensity,1); }";
      var translated =
          compiler.translate(
              vertex, fragment, "fixture", ShaderCompatibilityCompiler.VertexMode.FULLSCREEN);
      check(translated.drawBuffers().equals(List.of(0, 3)), "Conditional draw buffers");
      check(translated.samplers().size() == 1, "Inactive resource must not create binding");
      check(translated.attributes().isEmpty(), "Fullscreen triangle must not need vertex buffer");
      check(
          translated.fragmentSource().contains("bool sl_cpp_new"),
          "Metal reserved identifiers are escaped");
      check(
          translated.preprocessedFragment().contains("colortex0Format = RGBA16F"),
          "Block-comment format directive survives preprocessing");
      compile(translated);
      var adapter =
          new ShaderCompatibilityCompiler.VertexAdapter(
              "layout(location=0) in vec3 Position; layout(location=1) in vec2 UV0;\n"
                  + "layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; };\n",
              "",
              Map.of(
                  "gl_Vertex",
                  "vec4(Position, 1.0)",
                  "gl_MultiTexCoord0",
                  "vec4(UV0,0,1)",
                  "gl_ModelViewProjectionMatrix",
                  "gbufferProjection * ModelViewMat"),
              List.of());
      var adapted = compiler.translate(vertex, fragment, "adapter", adapter);
      check(
          adapted.uniforms().fields().stream()
              .noneMatch(f -> f.name().equals("sl_ModelViewProjectionMatrix")),
          "Adapted vanilla transforms must not need per-draw uniform readback");
      compile(adapted);
      var flat =
          compiler.translate(
              "#version 120\n"
                  + "flat varying vec4 color; void main(){color=gl_Color;gl_Position=gl_Vertex;}",
              "#version 120\nflat varying vec4 color; void main(){gl_FragColor=color;}",
              "flat varying");
      check(
          flat.vertexSource().contains("flat out vec4 color")
              && flat.fragmentSource().contains("flat in vec4 color"),
          "Explicit flat interpolation is retained");
      compile(flat);
      boolean rejected = false;
      try {
        compiler.translate(
            vertex,
            fragment.replace("uniform sampler2D texture;", "uniform sampler3D texture;"),
            "unsupported");
      } catch (UnsupportedOperationException expected) {
        rejected = true;
      }
      check(rejected, "Unsupported active 3D resources must fail explicitly");
      int count = 0;
      if (args.length != 0) {
        Path directory = Path.of(args[0]);
        try (var files = Files.walk(directory)) {
          for (Path v : files.filter(p -> p.toString().endsWith(".vsh")).sorted().toList()) {
            Path f = Path.of(v.toString().replaceFirst("\\.vsh$", ".fsh"));
            if (!Files.exists(f)) continue;
            String label = directory.relativize(v).toString();
            try {
              var pack = compiler.translate(Files.readString(v), Files.readString(f), label);
              compile(pack);
              count++;
            } catch (RuntimeException error) {
              throw new AssertionError("Pack compilation failed: " + label, error);
            }
          }
        }
      }
      System.out.println(
          "PASS: GLSL interface lowering, inactive feature rejection, std140 writes, adapter"
              + " compilation; "
              + count
              + " supplied shader pairs compiled");
    }
  }

  public static void compile(TranslatedProgram program) {
    long compiler = shaderc_compiler_initialize();
    long options = shaderc_compile_options_initialize();
    try {
      shaderc_compile_options_set_auto_bind_uniforms(options, true);
      shaderc_compile_options_set_auto_map_locations(options, true);
      compileStage(
          compiler,
          options,
          program.vertexSource(),
          shaderc_vertex_shader,
          program.label() + ".vert");
      compileStage(
          compiler,
          options,
          program.fragmentSource(),
          shaderc_fragment_shader,
          program.label() + ".frag");
    } finally {
      shaderc_compile_options_release(options);
      shaderc_compiler_release(compiler);
    }
  }

  private static void compileStage(
      long compiler, long options, String source, int kind, String name) {
    var sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
    var nameBytes = org.lwjgl.system.MemoryUtil.memUTF8(name);
    var entryBytes = org.lwjgl.system.MemoryUtil.memUTF8("main");
    long result =
        shaderc_compile_into_spv(compiler, sourceBytes, kind, nameBytes, entryBytes, options);
    try {
      if (result == 0
          || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
        Path dump = Path.of("/tmp/shader-loader-failed-" + name.replaceAll("[^A-Za-z0-9_.-]", "_"));
        try {
          Files.writeString(dump, source);
        } catch (Exception ignored) {
        }
        throw new IllegalArgumentException(
            "Shader compilation "
                + name
                + ": "
                + (result == 0 ? "no result" : shaderc_result_get_error_message(result))
                + "; source "
                + dump);
      }
    } finally {
      if (result != 0) shaderc_result_release(result);
      org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
      org.lwjgl.system.MemoryUtil.memFree(nameBytes);
      org.lwjgl.system.MemoryUtil.memFree(entryBytes);
    }
  }

  private static void testStd140() {
    Map<String, UniformLayout.Declaration> declarations = new LinkedHashMap<>();
    declarations.put("vector", new UniformLayout.Declaration("vec3", 0));
    declarations.put("scalar", new UniformLayout.Declaration("float", 0));
    declarations.put("matrix", new UniformLayout.Declaration("mat3", 0));
    declarations.put("indices", new UniformLayout.Declaration("ivec2", 2));
    var layout = UniformLayout.of(declarations);
    check(
        layout.field("scalar").offset() == 12
            && layout.field("matrix").offset() == 16
            && layout.field("indices").offset() == 64
            && layout.byteSize() == 96,
        "std140 packing");
    ByteBuffer buffer = layout.allocate().order(ByteOrder.nativeOrder());
    layout.putFloats(buffer, "matrix", 1, 2, 3, 4, 5, 6, 7, 8, 9);
    layout.putInts(buffer, "indices", 11, 12, 13, 14);
    check(
        buffer.getFloat(16) == 1 && buffer.getFloat(32) == 4 && buffer.getFloat(48) == 7,
        "Column-major matrix stride");
    check(
        buffer.getInt(64) == 11 && buffer.getInt(80) == 13 && buffer.position() == 0,
        "Array stride and absolute writes");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
