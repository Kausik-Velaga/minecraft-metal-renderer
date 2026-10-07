package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.geometry.DynamicBlockGeometry;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderStage;
import dev.kausik.shaders.runtime.PackEnvironment;
import dev.kausik.shaders.runtime.PackPrograms;
import dev.kausik.shaders.runtime.SceneProgram;
import dev.kausik.shaders.runtime.ShadowRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Compiles a user-supplied pack through the real RenderPearl linker and Metal driver. */
public final class PackShaderSmokeTest {
  private static int earlyAlphaPrograms;
  private static final ShaderCompatibilityCompiler.ShadowComparison SHADOW_COMPARISON =
      Boolean.parseBoolean(System.getProperty("minecraftShaders.hardwareShadowComparison", "true"))
          ? ShaderCompatibilityCompiler.ShadowComparison.HARDWARE
          : ShaderCompatibilityCompiler.ShadowComparison.EMULATED;

  public static void main(String[] args) throws Exception {
    if (args.length < 1
        || args.length > 2
        || (args.length == 2 && !args[1].equals("--translate-only")))
      throw new IllegalArgumentException(
          "Provide a shader-pack ZIP/directory and optional --translate-only");
    boolean translateOnly = args.length == 2;
    checkProgramRoutes();
    ShaderPack pack = ShaderPack.load(Path.of(args[0]));
    Map<String, String> environment = PackEnvironment.definitions();
    FrontendGpuDevice device = null;
    FragmentOptimizationSmokeCompiler optimizedCompiler = null;
    if (!translateOnly) {
      RenderSystem.initRenderThread();
      device = new FrontendGpuDevice(new MetalDevice());
      RenderSystem.initRenderer(device);
      if (ShadercFragmentOptimization.enabled())
        optimizedCompiler = FragmentOptimizationSmokeCompiler.install(device);
    }
    System.out.println("Pack smoke shadow comparison mode: " + SHADOW_COMPARISON);
    int count = 0;
    long start = System.nanoTime();
    try (var translator = new ShaderCompatibilityCompiler(SHADOW_COMPARISON)) {
      for (String dimension :
          List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
        for (var program : pack.programs(pack.dimensionFolder(dimension))) {
          if (program.name().startsWith("dh_")
              || !program.paths().containsKey(ShaderStage.VERTEX)
              || !program.paths().containsKey(ShaderStage.FRAGMENT)) continue;
          // Also compile option-disabled entry points with the current option values. This
          // checks their default interfaces, not the code paths gated by disabled options.
          var translated =
              translator.translate(
                  pack.source(program.paths().get(ShaderStage.VERTEX), Map.of(), environment),
                  pack.source(program.paths().get(ShaderStage.FRAGMENT), Map.of(), environment),
                  program.identifier());
          compile(device, translated);
          count++;
        }
        var sky = pack.program(pack.dimensionFolder(dimension), "gbuffers_skybasic");
        if (sky.isPresent()) {
          compile(
              device,
              translator.translate(
                  pack.source(sky.get().paths().get(ShaderStage.VERTEX), Map.of(), environment),
                  pack.source(sky.get().paths().get(ShaderStage.FRAGMENT), Map.of(), environment),
                  "background/" + pack.dimensionFolder(dimension),
                  MinecraftVertexAdapter.skyBackground()));
          count++;
        }
      }
      TerrainShaderGeometry.configure(state -> -1);
      count += checkRegisteredRoutes(pack, device);
      // Vanilla clouds are an optional BSL setting; retain an explicit enabled-path check.
      if (pack.options().containsKey("CLOUDS")) {
        var cloud = pack.program("world0", "gbuffers_clouds").orElseThrow();
        compile(
            device,
            translator.translate(
                pack.source(
                    cloud.paths().get(ShaderStage.VERTEX), Map.of("CLOUDS", "3"), environment),
                pack.source(
                    cloud.paths().get(ShaderStage.FRAGMENT), Map.of("CLOUDS", "3"), environment),
                "adapter/vanilla_cloud_option",
                MinecraftVertexAdapter.adapter(RenderPipelines.CLOUDS, false)),
            RenderPipelines.CLOUDS);
        count++;
      }
      if (count == 0) throw new AssertionError("No compatible shader pairs found in supplied pack");
      if (optimizedCompiler != null) optimizedCompiler.assertActivated(count);
      boolean expectEarlyAlpha =
          Boolean.getBoolean("minecraftShaders.earlyAlphaDemote")
              && dev.kausik.shaders.runtime.ShadowCullingEligibility.assess(
                      pack, Map.of(), "minecraft:overworld", environment)
                  .eligible();
      if ((earlyAlphaPrograms > 0) != expectEarlyAlpha)
        throw new AssertionError(
            "Wrong early-alpha coverage: expected enabled="
                + expectEarlyAlpha
                + ", actual variants="
                + earlyAlphaPrograms);
      System.out.println(
          "Verified early-alpha demotion in " + earlyAlphaPrograms + " eligible terrain variants");
      if (translateOnly && ShadercFragmentOptimization.enabled())
        System.out.println(
            "SPIR-V optimization is not exercised by --translate-only; run GPU smoke to validate"
                + " frontend compilation");
      System.out.printf(
          "PASS: %d shader-pack pipelines, including every eligible registered Minecraft route, %s"
              + " in %.3f s%n",
          count,
          translateOnly ? "translated without GPU initialization" : "compiled by Metal",
          (System.nanoTime() - start) / 1e9);
    } finally {
      TerrainShaderGeometry.disable();
      if (!translateOnly) RenderSystem.shutdownRenderer();
    }
  }

  private static void compile(FrontendGpuDevice device, TranslatedProgram program) {
    compile(device, program, null);
  }

  private static void compile(
      FrontendGpuDevice device, TranslatedProgram program, RenderPipeline vanilla) {
    if (program.fragmentSource().contains("demote;")) {
      if (!program.label().startsWith("world0/gbuffers_terrain/early_alpha_demote"))
        throw new AssertionError(
            "Early-alpha escaped the audited terrain scope: " + program.label());
      earlyAlphaPrograms++;
    }
    if (device == null) return;
    var bindings = BindGroupLayout.builder();
    if (program.uniforms().byteSize() > 0)
      bindings.withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
    for (var sampler : program.samplers())
      bindings.withUniform(
          ShaderCompatibilityCompiler.samplerShaderName(sampler.name()),
          UniformType.COMBINED_IMAGE_SAMPLER);
    if (vanilla != null)
      for (var binding : BindGroupLayout.flattenUniforms(vanilla.getBindGroupLayouts())) {
        if (binding.type() == UniformType.TEXEL_BUFFER)
          bindings.withUniform(binding.name(), binding.type(), binding.gpuFormat());
        else bindings.withUniform(binding.name(), binding.type());
      }
    var pipeline =
        RenderPipeline.builder()
            .withLocation("shader_test/" + program.label())
            .withVertexShader(Identifier.parse(ShadercFragmentOptimization.PACK_SHADER))
            .withFragmentShader(Identifier.parse(ShadercFragmentOptimization.PACK_SHADER))
            .withBindGroupLayout(bindings.build())
            .withPrimitiveTopology(
                vanilla == null ? PrimitiveTopology.TRIANGLES : vanilla.getPrimitiveTopology())
            .withCull(false);
    if (vanilla != null) {
      boolean terrain = MinecraftVertexAdapter.uniformBlocks(vanilla).contains("TerrainUniform");
      for (int slot = 0; slot < vanilla.getVertexFormatBindings().size(); slot++) {
        var format = vanilla.getVertexFormatBinding(slot);
        if (format != null)
          pipeline.withVertexBinding(
              slot, terrain && slot == 0 ? TerrainShaderGeometry.FORMAT : format);
      }
    } else if (!program.attributes().isEmpty()) {
      var format = VertexFormat.builder(0);
      for (var attribute : program.attributes()) {
        GpuFormat gpuFormat =
            switch (attribute.type()) {
              case "vec2" -> GpuFormat.RG32_FLOAT;
              case "vec3" -> GpuFormat.RGB32_FLOAT;
              case "vec4" -> GpuFormat.RGBA32_FLOAT;
              default ->
                  throw new UnsupportedOperationException("Test attribute " + attribute.type());
            };
        format.addAttribute(attribute.shaderName(), gpuFormat);
      }
      pipeline.withVertexBinding(0, format.build());
    }
    for (int i = 0; i < program.drawBuffers().size(); i++) {
      if (program.drawBuffers().get(i) < 0) pipeline.withUnusedColorTargetState(i);
      else pipeline.withColorTargetState(i, ColorTargetState.DEFAULT);
    }
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType type) {
            return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    try (var compiled =
        device.compilePipeline(pipeline.build(), source, Runnable::run).join().finishCompile()) {
      if (compiled == null || compiled.isClosed())
        throw new AssertionError("Invalid compiled pipeline " + program.label());
    } catch (RuntimeException failure) {
      throw new IllegalArgumentException("Metal shader-pack pipeline " + program.label(), failure);
    }
  }

  private static void checkProgramRoutes() {
    checkRoute(RenderPipelines.LEASH, false, false, false, "gbuffers_basic");
    checkRoute(RenderPipelines.WATER_MASK, false, false, false, "gbuffers_basic");
    checkRoute(RenderPipelines.GLINT, true, false, false, "gbuffers_armor_glint");
    checkRoute(RenderPipelines.ENTITY_SOLID_GLINT, false, false, false, "gbuffers_entities");
    checkRoute(
        RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT, false, false, false, "gbuffers_entities");
    checkRoute(
        RenderPipelines.ITEM_TRANSLUCENT_GLINT,
        false,
        false,
        false,
        "gbuffers_entities_translucent");
    checkRoute(RenderPipelines.ENTITY_SOLID_GLINT, false, false, true, "gbuffers_block");
    checkRoute(RenderPipelines.BEACON_BEAM_TRANSLUCENT, false, false, true, "gbuffers_beaconbeam");
    checkRoute(
        RenderPipelines.ENTITY_TRANSLUCENT, false, false, false, "gbuffers_entities_translucent");
    checkRoute(
        RenderPipelines.ENTITY_TRANSLUCENT_CULL,
        false,
        false,
        false,
        "gbuffers_entities_translucent");
    checkRoute(
        RenderPipelines.ITEM_TRANSLUCENT, false, false, false, "gbuffers_entities_translucent");
    checkRoute(RenderPipelines.ENTITY_TRANSLUCENT, false, false, true, "gbuffers_block");
    checkRoute(RenderPipelines.ENTITY_TRANSLUCENT, true, false, false, "gbuffers_hand_water");
    checkRoute(RenderPipelines.ENTITY_TRANSLUCENT, false, true, false, "shadow");
    // Spectral highlighting and emissive overlays are distinct shader-pack contracts.
    checkRoute(
        RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE, false, false, false, "gbuffers_entities");
  }

  private static void checkRoute(
      RenderPipeline pipeline, boolean hand, boolean shadow, boolean blockEntity, String expected) {
    String actual =
        dev.kausik.shaders.runtime.SceneProgram.select(pipeline, hand, shadow, blockEntity).name();
    if (!actual.equals(expected))
      throw new AssertionError(
          pipeline.getLocation() + " routes to " + actual + ", expected " + expected);
  }

  /** Identity is essential: several vanilla item/glint variants share one location string. */
  private static Map<RenderPipeline, String> registeredPipelines()
      throws ReflectiveOperationException {
    Map<RenderPipeline, String> names = new IdentityHashMap<>();
    for (var field : RenderPipelines.class.getFields()) {
      if (field.getType() == RenderPipeline.class)
        names.put((RenderPipeline) field.get(null), field.getName());
    }
    for (RenderPipeline pipeline : RenderPipelines.requiredPipelines())
      names.putIfAbsent(pipeline, pipeline.getLocation().getPath().replace('/', '_'));
    for (RenderPipeline pipeline : RenderPipelines.optionalPipelines())
      names.putIfAbsent(pipeline, pipeline.getLocation().getPath().replace('/', '_'));
    return names;
  }

  private static int checkRegisteredRoutes(ShaderPack pack, FrontendGpuDevice device)
      throws Exception {
    Map<RenderPipeline, String> catalog = registeredPipelines();
    List<PipelineRoute> routes = new ArrayList<>();
    Map<String, List<String>> excluded = new TreeMap<>();
    int worldPipelines = 0;
    for (var entry : catalog.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList()) {
      RenderPipeline pipeline = DynamicBlockGeometry.pipeline(entry.getKey());
      String reason = excludedReason(pipeline);
      if (reason != null) {
        excluded.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(entry.getValue());
        continue;
      }
      worldPipelines++;
      String vertex = pipeline.getShaders().get(ShaderType.VERTEX).getPath();
      String path = pipeline.getLocation().getPath();
      boolean screenEffect =
          path.endsWith("/block_screen_effect") || path.endsWith("/fire_screen_effect");
      if (!screenEffect) {
        routes.add(new PipelineRoute(pipeline, entry.getValue(), "scene", false, false, false));
        if (Set.of("core/entity", "core/item", "core/block").contains(vertex)
            && !SceneProgram.select(pipeline, false, false, false)
                .name()
                .equals(SceneProgram.select(pipeline, false, false, true).name()))
          routes.add(
              new PipelineRoute(pipeline, entry.getValue(), "block_entity", false, false, true));
        // Shadow replay executes prepared chunks and feature draws, never the sky pass.
        if (!skyPipeline(pipeline) && !ShadowRenderer.skipPipeline(pipeline))
          routes.add(new PipelineRoute(pipeline, entry.getValue(), "shadow", false, true, false));
      }
      // Held models, maps, block-entity items and 3D crosshair draws occur after beginHand.
      if (screenEffect
          || Set.of(
                  "core/entity",
                  "core/item",
                  "core/block",
                  "core/glint",
                  "core/text",
                  "core/rendertype_lines")
              .contains(vertex))
        routes.add(new PipelineRoute(pipeline, entry.getValue(), "hand", true, false, false));
    }
    System.out.printf(
        "Minecraft pipeline catalog: %d distinct objects, %d world/hand pipelines, %d route"
            + " variants%n",
        catalog.size(), worldPipelines, routes.size());
    excluded.forEach(
        (reason, names) ->
            System.out.println("Explicit pipeline exclusions (" + reason + "): " + names));
    List<Throwable> failures = new ArrayList<>();
    int count = 0;
    for (String dimension :
        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")) {
      int dimensionCount = 0;
      try (var programs = new PackPrograms(pack, Map.of(), dimension, SHADOW_COMPARISON)) {
        for (PipelineRoute route : routes) {
          if (route.shadow() && programs.find("shadow") == null) continue;
          if (!existsInDimension(route.pipeline(), dimension)) continue;
          String label = dimension + "/" + route.field() + "/" + route.context();
          try {
            var selection =
                SceneProgram.select(
                    route.pipeline(), route.hand(), route.shadow(), route.blockEntity());
            var source = programs.resolve(selection.name());
            float reference =
                Float.parseFloat(
                    route
                        .pipeline()
                        .getShaderDefines()
                        .values()
                        .getOrDefault("ALPHA_CUTOUT", "0.1"));
            boolean waterMask = MinecraftVertexAdapter.isWaterMask(route.pipeline());
            AlphaTestPolicy alphaTest =
                waterMask
                    ? AlphaTestPolicy.OFF
                    : AlphaTestPolicy.parse(
                        programs.properties.get("alphaTest." + source.name(), ""),
                        AlphaTestPolicy.greater(reference));
            var translated =
                programs.geometry(
                    source,
                    MinecraftVertexAdapter.adapter(route.pipeline(), route.shadow(), route.hand()),
                    alphaTest);
            if (waterMask) translated = MinecraftVertexAdapter.waterMaskProgram(translated);
            translated = MinecraftTextureAdapter.adapt(route.pipeline(), translated);
            compile(device, translated, route.pipeline());
            count++;
            dimensionCount++;
          } catch (RuntimeException | java.io.IOException failure) {
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            System.err.println(
                "Pipeline route failure "
                    + label
                    + " -> "
                    + SceneProgram.select(
                            route.pipeline(), route.hand(), route.shadow(), route.blockEntity())
                        .name()
                    + ": "
                    + root.getMessage());
            failures.add(new IllegalStateException(label, failure));
          }
        }
      }
      System.out.printf(
          "Verified %d registered pipeline routes for %s%n", dimensionCount, dimension);
    }
    if (!failures.isEmpty()) {
      AssertionError error =
          new AssertionError(failures.size() + " registered Minecraft shader routes failed");
      failures.forEach(error::addSuppressed);
      throw error;
    }
    return count;
  }

  private static String excludedReason(RenderPipeline pipeline) {
    String path = pipeline.getLocation().getPath();
    String leaf = path.substring(path.lastIndexOf('/') + 1);
    if (pipeline.getShaderDefines().flags().contains("OIT") || leaf.startsWith("oit_"))
      return "improved transparency is disabled while a pack is active";
    if (leaf.startsWith("gui")
        || Set.of("crosshair", "vignette", "mojang_logo", "panorama").contains(leaf))
      return "2D GUI/menu rendering occurs after the shader frame";
    if (leaf.startsWith("animate_sprite") || leaf.equals("lightmap"))
      return "texture updates render to separate targets";
    if (leaf.startsWith("blit_") || leaf.equals("integrate_depth"))
      return "vanilla depth-only or OIT transfer pass is not a scene color draw";
    if (Set.of("entity_outline_blit", "tracy_blit").contains(leaf))
      return "post-world presentation occurs after endFrame";
    return null;
  }

  private static boolean skyPipeline(RenderPipeline pipeline) {
    String path = pipeline.getLocation().getPath();
    return Set.of("sky", "stars", "sunrise_sunset", "celestial", "end_sky")
        .contains(path.substring(path.lastIndexOf('/') + 1));
  }

  private static boolean existsInDimension(RenderPipeline pipeline, String dimension) {
    if (!skyPipeline(pipeline)) return true;
    // SkyRenderer extracts nothing for Skybox.NONE (vanilla Nether). Skybox.END renders
    // END_SKY and CELESTIAL for End flashes; the Overworld renders the remaining sky draws.
    String path = pipeline.getLocation().getPath();
    if (dimension.equals("minecraft:the_nether")) return false;
    if (dimension.equals("minecraft:the_end"))
      return path.endsWith("/end_sky") || path.endsWith("/celestial");
    return !path.endsWith("/end_sky");
  }

  private record PipelineRoute(
      RenderPipeline pipeline,
      String field,
      String context,
      boolean hand,
      boolean shadow,
      boolean blockEntity) {}
}
