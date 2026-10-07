package dev.kausik.shaders.runtime;

import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.kausik.shaders.compile.ShaderCompatibilityCompiler;
import dev.kausik.shaders.pack.ShaderPack;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Checks that unsupported host autofocus cannot silently render with an artificial far depth. */
public final class PackProgramsValidationTest {
  public static void main(String[] args) throws Exception {
    verifySamplerRollback();
    verifyMipmapRollback();
    verifyImagePreflight();
    verifyUnsupportedRendering();
    verifySamplerDependencies();
    String vertex = "#version 120\nvoid main(){ gl_Position=gl_Vertex; }";
    String fragment =
        """
        #version 120
        uniform float centerDepthSmooth;
        void main() {
        #if defined(DOF) && DOF_FOCUS_MODE == 0
          gl_FragColor = vec4(centerDepthSmooth);
        #else
          gl_FragColor = vec4(1.0);
        #endif
        }
        """;
    try (var compiler = new ShaderCompatibilityCompiler()) {
      var unused = compiler.translate(vertex, fragment, "unused declaration");
      PackPrograms.validateFocusSupport(unused, Map.of("DOF", "true", "DOF_FOCUS_MODE", "0"));
      String autofocus =
          fragment.replace("#version 120", "#version 120\n#define DOF\n#define DOF_FOCUS_MODE 0");
      var active = compiler.translate(vertex, autofocus, "host autofocus");
      expectUnsupported(
          () ->
              PackPrograms.validateFocusSupport(
                  active, Map.of("DOF", "true", "DOF_FOCUS_MODE", "0")));
      PackPrograms.validateFocusSupport(active, Map.of("DOF", "false", "DOF_FOCUS_MODE", "0"));
      String pointFocus =
          fragment.replace("#version 120", "#version 120\n#define DOF\n#define DOF_FOCUS_MODE 1");
      PackPrograms.validateFocusSupport(
          compiler.translate(vertex, pointFocus, "pack focus point"),
          Map.of("DOF", "true", "DOF_FOCUS_MODE", "1"));
    }
    if (args.length > 0) {
      ShaderPack pack = ShaderPack.load(Path.of(args[0]));
      if (!"false".equals(pack.optionValues(Map.of()).get("DOF")))
        throw new AssertionError("Expected the supplied BSL default to disable DOF");
      if (!"0".equals(pack.optionValues(Map.of()).get("DOF_FOCUS_MODE")))
        throw new AssertionError("Expected the supplied BSL default to use host autofocus");
      for (String dimension :
          new String[] {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
        try (var defaults = new PackPrograms(pack, Map.of(), dimension)) {
          if (defaults.find("composite3") != null)
            throw new AssertionError("Default DOF program is enabled");
        }
      }
      expectUnsupported(
          () -> {
            try (var ignored =
                new PackPrograms(pack, Map.of("DOF", "true"), "minecraft:overworld")) {}
          });
      try (var point =
          new PackPrograms(
              pack, Map.of("DOF", "true", "DOF_FOCUS_MODE", "1"), "minecraft:overworld")) {
        if (point.find("composite3") == null)
          throw new AssertionError("Pack-managed focus program is missing");
      }
    }
    System.out.println(
        "PASS: active unsupported rendering declarations are rejected; failed sampler/pipeline"
            + " creation releases resources; image dimensions are checked before decoding");
  }

  private static void verifySamplerDependencies() throws Exception {
    Path folder = Files.createTempDirectory("pack-depth-dependencies");
    try {
      Path shaders = Files.createDirectories(folder.resolve("shaders"));
      String vertex = "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n";
      for (String name : new String[] {"gbuffers_basic", "composite", "final"}) {
        Files.writeString(shaders.resolve(name + ".vsh"), vertex);
        String sampler =
            switch (name) {
              case "gbuffers_basic" -> "depthtex2";
              case "composite" -> "depthtex0";
              default -> "shadowtex1";
            };
        Files.writeString(
            shaders.resolve(name + ".fsh"),
            "#version 120\nuniform sampler2D "
                + sampler
                + ";\nvoid main(){gl_FragColor=texture2D("
                + sampler
                + ",vec2(0.5));}\n");
      }
      try (var programs =
          new PackPrograms(ShaderPack.load(folder), Map.of(), "minecraft:overworld")) {
        if (!programs.readsSampler("depthtex2")
            || programs.postReadsSampler("depthtex2")
            || !programs.postReadsSampler("depthtex0")
            || !programs.readsSampler("shadowtex1"))
          throw new AssertionError("Required scene/post depth resources were pruned");
      }
      Files.writeString(shaders.resolve("shaders.properties"), "program.composite.enabled=false\n");
      try (var programs =
          new PackPrograms(ShaderPack.load(folder), Map.of(), "minecraft:overworld")) {
        if (programs.readsSampler("depthtex0") || programs.postReadsSampler("depthtex0"))
          throw new AssertionError("Disabled passes must not require a hand-depth merge");
      }
    } finally {
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void verifyUnsupportedRendering() throws Exception {
    String vertex = "#version 120\nvoid main(){gl_Position=gl_Vertex;}\n";
    String fragment = "#version 120\nvoid main(){gl_FragColor=vec4(1.0);}\n";
    Map<String, String> base = Map.of("composite.vsh", vertex, "composite.fsh", fragment);
    for (String declaration :
        new String[] {
          "scale.composite=0.5", "flip.composite.colortex0=false", "size.buffer.colortex0=0.5 0.5"
        }) {
      Map<String, String> files = new HashMap<>(base);
      files.put("shaders.properties", declaration);
      verifyFixture(files, declaration.substring(0, declaration.indexOf('=')));
    }
    verifyFixture(
        Map.of(
            "world0/composite.vsh",
            vertex,
            "world0/composite.fsh",
            fragment,
            "shaders.properties",
            "flip.world0/composite.colortex0=false"),
        "flip.world0/composite.colortex0");

    // Disabled program properties and shader branches never become active graph requirements.
    Map<String, String> disabled = new HashMap<>(base);
    disabled.put(
        "shaders.properties",
        "program.composite.enabled=false\n"
            + "scale.composite=0.5\n"
            + "flip.composite.colortex0=false\n"
            + "program.setup.enabled=false\n");
    disabled.put("setup.csh", "This disabled source must never reach a compiler");
    verifyFixture(disabled, null);
    Map<String, String> setup = new HashMap<>(base);
    setup.put("setup1.vsh", vertex);
    setup.put("setup1.fsh", fragment);
    verifyFixture(setup, "setup passes");

    for (String declaration :
        new String[] {
          "int shadowcolor0Format = RGBA16F",
          "bool shadowcolor0Clear = false",
          "vec4 shadowcolor0ClearColor = vec4(0.0)",
          "bool shadowcolor0Nearest = true",
          "bool shadowcolor0Mipmap = true",
          "bool shadowColor1Mipmap = true",
          "bool generateShadowColorMipmap = true"
        }) {
      String name = declaration.split(" ")[1];
      Map<String, String> files = new HashMap<>(base);
      files.put("composite.fsh", fragment + "/*\nconst " + declaration + ";\n*/\n");
      verifyFixture(files, name);
      files.put("composite.fsh", fragment + "#if 0\n/*\nconst " + declaration + ";\n*/\n#endif\n");
      verifyFixture(files, null);
    }
  }

  private static void verifyFixture(Map<String, String> sources, String unsupported)
      throws Exception {
    Path folder = Files.createTempDirectory("pack-rendering-validation");
    try {
      for (var entry : sources.entrySet()) {
        Path destination = folder.resolve("shaders").resolve(entry.getKey());
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, entry.getValue());
      }
      try (var ignored =
          new PackPrograms(ShaderPack.load(folder), Map.of(), "minecraft:overworld")) {
        if (unsupported != null) throw new AssertionError("Accepted unsupported " + unsupported);
      } catch (UnsupportedOperationException expected) {
        if (unsupported == null || !expected.getMessage().contains(unsupported))
          throw new AssertionError("Unexpected rendering rejection", expected);
      }
    } finally {
      try (var paths = Files.walk(folder)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void verifyMipmapRollback() {
    for (int failureAt : new int[] {0, 3, 11}) {
      int[] compiled = {0}, released = {0}, samplerReleased = {0};
      GpuDevice device =
          (GpuDevice)
              Proxy.newProxyInstance(
                  GpuDevice.class.getClassLoader(),
                  new Class<?>[] {GpuDevice.class},
                  (instance, method, arguments) -> {
                    if (method.getName().equals("createSampler")) {
                      return Proxy.newProxyInstance(
                          GpuSampler.class.getClassLoader(),
                          new Class<?>[] {GpuSampler.class},
                          (sampler, operation, values) -> {
                            if (!operation.getName().equals("close"))
                              throw new AssertionError(
                                  "Unexpected sampler operation: " + operation);
                            if (++samplerReleased[0] != 1)
                              throw new AssertionError("Sampler released twice");
                            return null;
                          });
                    }
                    if (method.getName().equals("compilePipeline")) {
                      CompiledRenderPipeline pipeline =
                          compiled[0] == failureAt
                              ? null
                              : new CompiledRenderPipeline() {
                                private boolean closed;

                                @Override
                                public boolean isClosed() {
                                  return closed;
                                }

                                @Override
                                public void close() {
                                  if (closed) throw new AssertionError("Pipeline released twice");
                                  closed = true;
                                  released[0]++;
                                }
                              };
                      compiled[0]++;
                      return CompletableFuture.completedFuture(
                          (CompiledRenderPipeline.Pending) () -> pipeline);
                    }
                    throw new AssertionError("Unexpected device operation: " + method);
                  });
      try {
        new PackMipmaps(device);
      } catch (IllegalStateException expected) {
        if (!expected.getMessage().startsWith("Mipmap pipeline compilation failed for "))
          throw new AssertionError("Missing pipeline compilation diagnostic", expected);
        if (released[0] != failureAt || samplerReleased[0] != 1)
          throw new AssertionError("Resources leaked after failed pipeline " + failureAt);
        continue;
      }
      throw new AssertionError("Null pipeline compilation was silently accepted");
    }
  }

  private static void verifyImagePreflight() throws IOException {
    BufferedImage original = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
    original.setRGB(0, 0, 0xff123456);
    original.setRGB(1, 0, 0x80765432);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(original, "PNG", output);
    byte[] png = output.toByteArray();
    BufferedImage decoded = PackTextures.decodeImage(png, "valid.png");
    if (decoded.getRGB(0, 0) != original.getRGB(0, 0)
        || decoded.getRGB(1, 0) != original.getRGB(1, 0))
      throw new AssertionError("Image preflight changed decoded color or alpha");

    // Change only the PNG header and its checksum. The tiny compressed payload cannot satisfy
    // these dimensions, so the explicit size-limit error proves rejection precedes pixel decode.
    ByteBuffer.wrap(png).putInt(16, 65_535).putInt(20, 65_535);
    CRC32 checksum = new CRC32();
    checksum.update(png, 12, 17);
    ByteBuffer.wrap(png).putInt(29, (int) checksum.getValue());
    try {
      PackTextures.decodeImage(png, "oversized.png");
    } catch (IOException expected) {
      if (!expected.getMessage().equals("Pack image exceeds texture size limit: oversized.png"))
        throw new AssertionError("Oversized image reached pixel decoding", expected);
      return;
    }
    throw new AssertionError("Oversized image header was accepted");
  }

  private static void verifySamplerRollback() throws Exception {
    // Fail each sampler creation and then the first texture creation. A constructor which never
    // returned has no owner to call close(), so it must release every preceding successful sampler.
    for (int failureAt = 0; failureAt < 4; failureAt++) {
      int failAt = failureAt;
      int[] created = {0}, released = {0};
      RuntimeException allocationFailure = new IllegalStateException("Injected allocation failure");
      GpuDevice device =
          (GpuDevice)
              Proxy.newProxyInstance(
                  GpuDevice.class.getClassLoader(),
                  new Class<?>[] {GpuDevice.class},
                  (instance, method, arguments) -> {
                    if (method.getName().equals("createSampler")) {
                      if (created[0] == failAt) throw allocationFailure;
                      created[0]++;
                      boolean[] closed = {false};
                      return Proxy.newProxyInstance(
                          GpuSampler.class.getClassLoader(),
                          new Class<?>[] {GpuSampler.class},
                          (sampler, samplerMethod, samplerArguments) -> {
                            if (samplerMethod.getName().equals("close")) {
                              if (closed[0]) throw new AssertionError("Sampler released twice");
                              closed[0] = true;
                              released[0]++;
                              return null;
                            }
                            if (samplerMethod.getName().equals("isClosed")) return closed[0];
                            throw new AssertionError(
                                "Unexpected sampler operation: " + samplerMethod);
                          });
                    }
                    if (method.getName().equals("createTexture")) throw allocationFailure;
                    throw new AssertionError("Unexpected device operation: " + method);
                  });
      try {
        new PackTextures(device, null, null);
      } catch (RuntimeException expected) {
        if (expected != allocationFailure)
          throw new AssertionError("Allocation failure was replaced", expected);
        if (released[0] != created[0])
          throw new AssertionError("Leaked samplers at allocation " + failAt);
        continue;
      }
      throw new AssertionError("Failure injection did not execute");
    }
  }

  private static void expectUnsupported(CheckedAction action) throws Exception {
    try {
      action.run();
    } catch (UnsupportedOperationException expected) {
      if (!expected.getMessage().contains("centerDepthSmooth")
          || !expected.getMessage().contains("DOF_FOCUS_MODE=1"))
        throw new AssertionError("Missing actionable autofocus diagnostic", expected);
      return;
    }
    throw new AssertionError("Unsupported autofocus was silently accepted");
  }

  private interface CheckedAction {
    void run() throws Exception;
  }
}
