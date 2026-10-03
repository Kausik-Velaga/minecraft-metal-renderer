package dev.kausik.shaders.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java contract tests; an optional argument exercises a user-supplied BSL ZIP without bundling
 * it.
 */
public final class ShaderPackSmokeTest {
  private static int checks;

  public static void main(String[] args) throws Exception {
    Path temporary = Files.createTempDirectory("shader-pack-test-");
    try {
      archiveAndOptions(temporary);
      invalidPacks(temporary);
      properties();
      directives();
      alphaTests();
      materialMappings();
      customUniforms();
      if (args.length > 0) bsl(Path.of(args[0]));
      System.out.println("Shader pack checks passed: " + checks);
    } finally {
      try (var paths = Files.walk(temporary)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static void archiveAndOptions(Path temporary) throws Exception {
    Path zip = temporary.resolve("sample.zip");
    writeZip(
        zip,
        Map.of(
            "sample/shaders/shaders.properties",
                """
                screen=SHADOW shadowMapResolution AO
                profile.LOW=!SHADOW shadowMapResolution=512
                profile.HIGH=profile.LOW SHADOW AO
                program.world0/shadow.enabled=SHADOW && AO
                """,
            "sample/shaders/dimension.properties",
                "dimension.world0=*\ndimension.world-1=minecraft:the_nether\n",
            "sample/shaders/world0/final.vsh",
                "#version 120\n#include \"/lib/settings.glsl\"\n#include \"../lib/color.glsl\"\n",
            "sample/shaders/world0/final.fsh", "#version 120\n#include \"/lib/settings.glsl\"\n",
            "sample/shaders/final.vsh", "#version 120\n",
            "sample/shaders/lib/settings.glsl",
                """
                /*
                #define FAKE 1 //[0 1]
                #include "/not-a-file.glsl"
                */
                #define SHADOW
                //#define AO
                const int shadowMapResolution = 1024; //[512 1024]
                #ifdef SHADOW
                #endif
                #ifndef AO
                #endif
                """,
            "sample/shaders/lib/color.glsl", "vec4 color = vec4(1.0);\n"));
    ShaderPack pack = ShaderPack.load(zip);
    check(pack.dimensionFolder("example:moon").equals("world0"), "Wildcard dimension mapping");
    check(
        pack.dimensionFolder("minecraft:the_nether").equals("world-1"),
        "Exact dimension mapping wins");
    check(
        pack.program("world-1", "final").isEmpty(), "Dimension programs do not fall back to root");
    check(pack.programs("world0").size() == 1, "Stage pair grouped into one program");
    check(
        pack.program("world0", "final").orElseThrow().paths().size() == 2, "Both stages available");
    check(!pack.options().containsKey("FAKE"), "Block-comment examples are not options");
    check(pack.optionValues(Map.of()).get("AO").equals("false"), "Commented switch is disabled");
    check(
        pack.profileOverrides("HIGH").get("SHADOW").equals("true"),
        "Child profile overrides inherited switch");
    check(
        pack.profileOverrides("HIGH").get("shadowMapResolution").equals("512"),
        "Profile inherits numeric option");
    String source =
        pack.source(
            "world0/final.vsh", pack.profileOverrides("HIGH"), Map.of("MC_VERSION", "260300"));
    check(source.contains("const int shadowMapResolution = 512;"), "Constant option replaced");
    check(source.contains("#define AO\n"), "Disabled switch can be enabled");
    check(
        source.indexOf("#version") < source.indexOf("#define MC_VERSION"),
        "Environment follows version");
    check(
        source.contains("vec4 color = vec4(1.0);"), "Relative include remains inside shaders root");
    check(
        pack.expand("world0/final.vsh", Map.of(), Map.of()).sourceFiles().size() == 3,
        "Diagnostic source table");
    check(
        !pack.properties(Map.of(), Map.of())
            .programEnabled("world0/shadow", pack.definitions(Map.of(), Map.of())),
        "Default disabled AO gates program");
    check(
        pack.properties(pack.profileOverrides("HIGH"), Map.of())
            .programEnabled(
                "world0/shadow", pack.definitions(pack.profileOverrides("HIGH"), Map.of())),
        "Profile enables program");
    byte[] copy = pack.bytes("lib/color.glsl");
    copy[0] = 'X';
    check(pack.text("lib/color.glsl").startsWith("vec4"), "Binary accessor cannot mutate snapshot");
    Files.delete(zip);
    check(
        pack.text("world0/final.vsh").contains("#version"),
        "ZIP handle closed; snapshot survives deletion");
    expectFailure(
        () -> pack.source("world0/final.vsh", Map.of("AO", "perhaps"), Map.of()),
        "Invalid switch rejected");
    expectFailure(
        () -> pack.source("world0/final.vsh", Map.of("UNKNOWN", "true"), Map.of()),
        "Unknown option rejected");

    Path directory = temporary.resolve("directory");
    Files.createDirectories(directory.resolve("shaders/world-1"));
    Files.writeString(directory.resolve("shaders/final.vsh"), "#version 120\n");
    ShaderPack legacy = ShaderPack.load(directory);
    check(
        legacy.dimensionFolder("overworld").equals("world0"),
        "Legacy dimension directories exclude root programs");
    check(legacy.program("world-1", "final").isEmpty(), "Empty dimension folder disables shaders");
  }

  private static void invalidPacks(Path temporary) throws Exception {
    Path traversal = temporary.resolve("traversal.zip");
    writeZip(
        traversal, Map.of("shaders/../outside.txt", "bad", "shaders/final.vsh", "#version 120\n"));
    expectFailure(() -> ShaderPack.load(traversal), "ZIP traversal rejected");
    Path cycle = temporary.resolve("cycle.zip");
    writeZip(
        cycle,
        Map.of(
            "shaders/final.vsh",
            "#version 120\n#include \"a.glsl\"\n",
            "shaders/a.glsl",
            "#include \"final.vsh\"\n"));
    expectFailure(() -> ShaderPack.load(cycle).source("final.vsh"), "Include cycle rejected");
    Path escape = temporary.resolve("escape.zip");
    writeZip(escape, Map.of("shaders/final.vsh", "#version 120\n#include \"../outside.txt\"\n"));
    expectFailure(() -> ShaderPack.load(escape).source("final.vsh"), "Include traversal rejected");
    Path directory = temporary.resolve("symlink");
    Files.createDirectories(directory.resolve("shaders"));
    Files.createSymbolicLink(directory.resolve("shaders/final.vsh"), escape);
    expectFailure(() -> ShaderPack.load(directory), "Directory symlink rejected");
  }

  private static void properties() throws Exception {
    ShaderProperties parsed =
        ShaderProperties.parse(
            """
            #if MC_VERSION >= 11800
            selected=modern
            #else
            selected=legacy
            #endif
            #ifdef OPTIONAL
            image.volume=large
            #elif (SIZE == 128) && !defined(OPTIONAL)
            size=small
            #else
            size=other
            #endif
            #if 0
            #if 1 / 0
            unreachable=bad
            #endif
            #endif
            continued=one \
                      two
            """,
            Map.of("MC_VERSION", "260300", "SIZE", "128"));
    check(
        parsed.get("selected", "").equals("modern"), "Version condition selects modern properties");
    check(!parsed.values().containsKey("image.volume"), "Inactive image directive omitted");
    check(parsed.get("size", "").equals("small"), "Nested boolean and numeric comparison");
    check(parsed.get("continued", "").contains("two"), "Property line continuation retained");
    check(ShaderProperties.evaluate("1 || (1 / 0)", Map.of()), "OR short-circuits invalid RHS");
    check(!ShaderProperties.evaluate("0 && (1 / 0)", Map.of()), "AND short-circuits invalid RHS");
    check(
        ShaderProperties.evaluate("(2 << 2) == 8 && (3 & 1) == 1", Map.of()),
        "Operator precedence");
    check(ShaderProperties.evaluate("0xFF == 255", Map.of()), "Hex digits are not float suffixes");
    expectFailure(
        () -> ShaderProperties.parse("#if 1\nvalue=1\n", Map.of()),
        "Unclosed conditional rejected");
    expectFailure(
        () -> ShaderProperties.evaluate("function(1)", Map.of()),
        "Unsupported expressions fail explicitly");
  }

  private static void directives() throws Exception {
    ShaderDirectives directives =
        ShaderDirectives.parse(
            """
            /* DRAWBUFFERS:0367 */
            /*
            const int colortex0Format = R11F_G11F_B10F;
            const int gaux1Format = R8;
            */
            // const int colortex0Format = RGBA8;
            const bool colortex2Clear = false;
            const vec4 colortex3ClearColor = vec4(1.0, 0.0, 0.0, 0.5);
            const bool colortex1MipmapEnabled = true;
            const int shadowMapResolution = 2048;
            const float sunPathRotation = - 40.0;
            """);
    check(
        directives.drawBuffers().equals(List.of(0, 3, 6, 7)),
        "Sparse draw buffers retain shader output order");
    check(
        directives.bufferFormats().get(0).equals("R11F_G11F_B10F"),
        "Block-comment formats are host metadata");
    check(directives.bufferFormats().get(4).equals("R8"), "Legacy gaux alias resolved");
    check(!directives.bufferClear().get(2), "Temporal retention directive parsed");
    check(
        directives.bufferClearColors().get(3).equals(List.of(1f, 0f, 0f, .5f)),
        "Clear color parsed");
    check(directives.mipmapBuffers().contains(1), "Mipmap request parsed");
    check(directives.intConstant("shadowMapResolution", 1024) == 2048, "Shadow size parsed");
    check(
        directives.floatConstant("sunPathRotation", 0) == -40,
        "Shaderc-spaced unary constant parsed");
    check(
        ShaderDirectives.parse("/* RENDERTARGETS: 0, 12 */").drawBuffers().equals(List.of(0, 12)),
        "High target indices parsed");
    expectFailure(
        () -> ShaderDirectives.parse("/* DRAWBUFFERS:00 */"), "Duplicate attachments rejected");
    expectFailure(
        () -> ShaderDirectives.parse("/* DRAWBUFFERS:0 */\n/* DRAWBUFFERS:1 */"),
        "Unfiltered/conflicting directives rejected");
  }

  private static void bsl(Path path) throws Exception {
    ShaderPack pack = ShaderPack.load(path);
    Map<String, String> environment = Map.of("MC_VERSION", "260300");
    check(pack.dimensionFolder("overworld").equals("world0"), "BSL Overworld directory");
    check(pack.dimensionFolder("the_nether").equals("world-1"), "BSL Nether directory");
    check(pack.dimensionFolder("the_end").equals("world1"), "BSL End directory");
    Map<String, String> defaults = pack.optionValues(Map.of());
    check(defaults.get("SHADOW").equals("true"), "BSL shadows enabled by default");
    check(
        defaults.get("MULTICOLORED_BLOCKLIGHT").equals("false"),
        "BSL colored-light compute disabled by default");
    check(defaults.get("shadowMapResolution").equals("2048"), "BSL default shadow resolution");
    ShaderProperties properties = pack.properties(Map.of(), environment);
    Map<String, String> definitions = pack.definitions(Map.of(), environment);
    check(properties.programEnabled("world0/shadow", definitions), "BSL Overworld shadows enabled");
    check(
        !properties.programEnabled("world-1/shadow", definitions),
        "BSL Nether shadows disabled by default");
    check(
        !properties.programEnabled("world0/shadowcomp", definitions),
        "BSL compute program disabled by default");
    check(
        !properties.programEnabled("world0/composite2", definitions),
        "BSL motion blur disabled by default");
    check(
        properties.values().keySet().stream().noneMatch(key -> key.startsWith("image.")),
        "BSL default allocates no optional volumes");
    check(
        pack.properties(Map.of("MULTICOLORED_BLOCKLIGHT", "true"), environment)
            .get("image.voxelimg", "")
            .endsWith("256 256 256"),
        "BSL opt-in volume dimensions selected correctly");
    check(
        pack.profileOverrides("LOW").get("shadowMapResolution").equals("1024"),
        "BSL Low profile resolves inheritance");
    MaterialMappings materials = MaterialMappings.load(pack, Map.of(), environment);
    check(
        materials.blockId("minecraft:short_grass", Map.of()) == 10000,
        "BSL plant material mapping");
    check(
        materials.blockId("minecraft:lantern", Map.of("hanging", "false")) == 15114,
        "BSL standing lantern state");
    check(
        materials.blockId("minecraft:lantern", Map.of("hanging", "true")) == 15614,
        "BSL hanging lantern state");
    check(materials.itemId("minecraft:torch") == 139, "BSL held light mapping");
    check(materials.entityId("minecraft:lightning_bolt") == 10101, "BSL entity mapping");
    CustomUniforms custom = CustomUniforms.compile(properties);
    Map<String, Double> inputs = new HashMap<>();
    int biomeId = 1;
    for (String input : new java.util.TreeSet<>(custom.requiredInputs())) {
      inputs.put(input, input.startsWith("BIOME_") ? (double) biomeId++ : 0.0);
    }
    inputs.put("cameraPosition.y", 64.0);
    inputs.put("sunAngle", .25);
    inputs.put("frameCounter", 17.0);
    inputs.put("biome", inputs.get("BIOME_DESERT"));
    Map<String, Double> uniforms = custom.evaluate(inputs, 1.0 / 60);
    check(uniforms.get("framemod8") == 1, "BSL temporal phase expression");
    check(uniforms.get("isDesert") == 1 && uniforms.get("isCold") == 0, "BSL biome expressions");
    check(
        uniforms.get("timeBrightness") > .9 && uniforms.get("shadowFade") == 1,
        "BSL noon light expressions");
    check(uniforms.size() == 17, "All BSL custom uniforms evaluated");
    int stages = 0;
    for (String folder : List.of("world0", "world-1", "world1")) {
      for (ShaderProgram program : pack.programs(folder)) {
        for (String stage : program.paths().values()) {
          check(
              !pack.source(stage, Map.of(), environment).isEmpty(),
              "BSL includes expand: " + stage);
          stages++;
        }
      }
    }
    System.out.println(
        "BSL snapshot: "
            + pack.files().size()
            + " files, "
            + pack.options().size()
            + " options, "
            + stages
            + " shader stages expanded; rendering not exercised");
  }

  private static void materialMappings() throws Exception {
    MaterialMappings mappings =
        MaterialMappings.parse(
            """
            #if MC_VERSION >= 11300
            block.100=grass minecraft:lantern:hanging=false
            block.200=minecraft:lantern:hanging=true oak_log:axis=x,z
            block.300=minecraft:stone
            #else
            block.1=123
            #endif
            """,
            "item.10=minecraft:torch\n",
            "entity.30=minecraft:shulker\n",
            Map.of("MC_VERSION", "260300"));
    check(
        mappings.blockId("minecraft:grass", Map.of()) == 100,
        "Unqualified block name uses minecraft namespace");
    check(
        mappings.blockId("minecraft:lantern", Map.of("hanging", "true")) == 200,
        "Block property selector");
    check(
        mappings.blockId("minecraft:oak_log", Map.of("axis", "z")) == 200,
        "Alternative property values");
    check(
        mappings.blockId("minecraft:oak_log", Map.of("axis", "y")) == -1,
        "Unmatched state remains unmapped");
    check(
        mappings.itemId("torch") == 10 && mappings.entityId("minecraft:shulker") == 30,
        "Item and entity IDs");
    expectFailure(
        () -> MaterialMappings.parse("block.1=minecraft:stone[axis=x]", "", "", Map.of()),
        "Unsupported selector syntax rejected");
  }

  private static void alphaTests() throws Exception {
    AlphaTestPolicy fallback = AlphaTestPolicy.greater(.1f);
    check(
        AlphaTestPolicy.parse(null, fallback) == fallback,
        "Missing alpha override preserves the scene policy");
    check(
        AlphaTestPolicy.parse("  off  ", fallback) == AlphaTestPolicy.OFF,
        "Explicit alpha off disables the host test");
    check(
        !AlphaTestPolicy.OFF.enabled() && AlphaTestPolicy.parse("GREATER 0", fallback).enabled(),
        "GREATER zero is distinct from disabled alpha testing");
    AlphaTestPolicy water = AlphaTestPolicy.parse("GREATER 0.001", fallback);
    check(
        water.function() == AlphaTestPolicy.Function.GREATER && water.reference() == .001f,
        "BSL water threshold is preserved exactly");
    check(
        AlphaTestPolicy.parse("GEQUAL\t0.5", fallback).function()
            == AlphaTestPolicy.Function.GEQUAL,
        "Inclusive GL alpha comparison accepted");
    check(
        AlphaTestPolicy.parse("NEVER 0", fallback).passCondition("a", "ref").equals("false"),
        "NEVER rejects independent of alpha value");
    check(
        AlphaTestPolicy.parse("ALWAYS 1", fallback).passCondition("a", "ref").equals("true"),
        "ALWAYS retains fragments independent of alpha value");
    check(
        !AlphaTestPolicy.parse("GL_ALWAYS 0", fallback).enabled(),
        "Documented GL_ALWAYS spelling accepted");
    check(
        AlphaTestPolicy.parse("LESS -1", fallback).reference() == 0
            && AlphaTestPolicy.parse("GREATER 2", fallback).reference() == 1,
        "GL reference values clamp to the unit interval");
    for (String invalid :
        List.of(
            "GREATER",
            "GREATER 0 extra",
            "GREATER NaN",
            "GREATER Infinity",
            "GREATER_EQUAL 0.1",
            "off 0"))
      expectFailure(
          () -> AlphaTestPolicy.parse(invalid, fallback),
          "Invalid alpha policy rejected: " + invalid);
  }

  private static void customUniforms() throws Exception {
    CustomUniforms custom =
        CustomUniforms.compile(
            ShaderProperties.parse(
                """
                variable.float.angle=frac(sunAngle - 0.1) * 2*pi
                uniform.float.brightness=max(sin(angle), 0)
                uniform.float.blind=if(blindness > 0, clamp(blindness, 0, 1), 0)
                uniform.float.biomeFade=smooth(4, if(in(biome, BIOME_DESERT), 1, 0), 10, 20)
                uniform.int.phase=frameCounter % 8
                uniform.float.lazy=if(true, 1, missingInput / 0)
                """,
                Map.of()));
    Map<String, Double> inputs =
        new HashMap<>(
            Map.of(
                "sunAngle",
                .35,
                "blindness",
                .25,
                "biome",
                0.0,
                "BIOME_DESERT",
                1.0,
                "frameCounter",
                17.0));
    Map<String, Double> first = custom.evaluate(inputs, 0);
    check(
        Math.abs(first.get("brightness") - 1) < 1e-9,
        "Custom expression dependency and trigonometry");
    check(
        first.get("phase") == 1 && first.get("blind") == .25,
        "Custom integer/coercion and conditional");
    check(first.get("lazy") == 1, "Conditional branch is lazy");
    check(first.get("biomeFade") == 0, "Smooth initializes from first sample");
    inputs.put("biome", 1.0);
    check(
        Math.abs(custom.evaluate(inputs, .5).get("biomeFade") - .5) < 1e-9,
        "Smooth uses tick half-life, independent of FPS");
    check(
        Math.abs(custom.evaluate(inputs, .5).get("biomeFade") - .75) < 1e-9,
        "Smooth retains frame history");
    custom.reset();
    check(custom.evaluate(inputs, .01).get("biomeFade") == 1, "World reset discards stale history");
    expectFailure(() -> custom.evaluate(Map.of(), .1), "Missing custom input fails explicitly");
    expectFailure(
        () ->
            CustomUniforms.compile(
                ShaderProperties.parse("variable.float.a=b\nuniform.float.b=a\n", Map.of())),
        "Custom dependency cycle rejected before rendering");
    expectFailure(
        () ->
            CustomUniforms.compile(
                ShaderProperties.parse("uniform.vec3.a=vec3(0,1,2)\n", Map.of())),
        "Unsupported custom type diagnosed");
    expectFailure(
        () ->
            CustomUniforms.compile(
                ShaderProperties.parse("uniform.float.a=arbitrary(1)\n", Map.of())),
        "Unknown custom function diagnosed");
  }

  private static void writeZip(Path zip, Map<String, String> files) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (var entry : files.entrySet()) {
        output.putNextEntry(new ZipEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
  }

  private static void check(boolean condition, String label) {
    checks++;
    if (!condition) throw new AssertionError(label);
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private static void expectFailure(CheckedAction action, String label) throws Exception {
    try {
      action.run();
    } catch (ShaderPackException expected) {
      checks++;
      return;
    }
    throw new AssertionError(label);
  }
}
