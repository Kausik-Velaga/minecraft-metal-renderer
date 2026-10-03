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
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderStage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Compiles a user-supplied pack through the real RenderPearl linker and Metal driver. */
public final class BslShaderSmokeTest {
  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Provide a shader-pack ZIP or directory");
    checkProgramRoutes();
    ShaderPack pack = ShaderPack.load(Path.of(args[0]));
    Map<String, String> environment =
        Map.of(
            "MC_VERSION",
            "260300",
            "MC_GL_VERSION",
            "460",
            "MC_GLSL_VERSION",
            "460",
            "MC_RENDER_STAGE_STARS",
            "6");
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    int count = 0;
    long start = System.nanoTime();
    try (var translator = new ShaderCompatibilityCompiler()) {
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
      for (var fixture :
          List.of(
              new AdapterFixture(RenderPipelines.SOLID_TERRAIN, "gbuffers_terrain", false),
              new AdapterFixture(
                  RenderPipelines.SOLID_TERRAIN_MULTIDRAW, "gbuffers_terrain", false),
              new AdapterFixture(
                  RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW, "gbuffers_water", false),
              new AdapterFixture(RenderPipelines.ENTITY_SOLID, "gbuffers_entities", false),
              new AdapterFixture(
                  RenderPipelines.ENTITY_TRANSLUCENT, "gbuffers_entities_translucent", false),
              new AdapterFixture(
                  RenderPipelines.ENTITY_TRANSLUCENT_CULL, "gbuffers_entities_translucent", false),
              new AdapterFixture(
                  RenderPipelines.ITEM_TRANSLUCENT, "gbuffers_entities_translucent", false),
              new AdapterFixture(RenderPipelines.OPAQUE_PARTICLE, "gbuffers_textured", false),
              new AdapterFixture(RenderPipelines.SKY, "gbuffers_skybasic", false),
              new AdapterFixture(RenderPipelines.STARS, "gbuffers_skybasic", false),
              new AdapterFixture(RenderPipelines.CELESTIAL, "gbuffers_skytextured", false),
              new AdapterFixture(RenderPipelines.WEATHER, "gbuffers_weather", false),
              new AdapterFixture(RenderPipelines.TEXT, "gbuffers_textured", false),
              new AdapterFixture(RenderPipelines.LINES, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.LINES_TRANSLUCENT, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.DEBUG_QUADS, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.LIGHTNING, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.DEBUG_POINTS, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.OUTLINE_CULL, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.CRUMBLING, "gbuffers_damagedblock", false),
              new AdapterFixture(RenderPipelines.LEASH, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.LEASH, "shadow", true),
              new AdapterFixture(RenderPipelines.WATER_MASK, "gbuffers_basic", false),
              new AdapterFixture(RenderPipelines.BEACON_BEAM_OPAQUE, "gbuffers_beaconbeam", false),
              new AdapterFixture(
                  RenderPipelines.BEACON_BEAM_TRANSLUCENT, "gbuffers_beaconbeam", false),
              new AdapterFixture(RenderPipelines.WORLD_BORDER, "gbuffers_textured", false),
              new AdapterFixture(
                  DynamicBlockGeometry.pipeline(RenderPipelines.END_PORTAL),
                  "gbuffers_block",
                  false),
              new AdapterFixture(
                  DynamicBlockGeometry.pipeline(RenderPipelines.END_GATEWAY),
                  "gbuffers_block",
                  false),
              new AdapterFixture(
                  DynamicBlockGeometry.pipeline(RenderPipelines.END_PORTAL), "shadow", true),
              new AdapterFixture(RenderPipelines.CLOUDS, "gbuffers_clouds", false),
              new AdapterFixture(RenderPipelines.ITEM_CUTOUT, "gbuffers_hand", false),
              new AdapterFixture(RenderPipelines.ENTITY_SOLID, "shadow", true),
              new AdapterFixture(RenderPipelines.ITEM_CUTOUT, "shadow", true),
              new AdapterFixture(
                  DynamicBlockGeometry.pipeline(RenderPipelines.SOLID_BLOCK),
                  "gbuffers_block",
                  false),
              new AdapterFixture(
                  DynamicBlockGeometry.pipeline(RenderPipelines.SOLID_BLOCK), "shadow", true),
              new AdapterFixture(RenderPipelines.SOLID_TERRAIN_MULTIDRAW, "shadow", true))) {
        var program =
            pack.program(pack.dimensionFolder("minecraft:overworld"), fixture.program())
                .orElseThrow();
        Map<String, String> options =
            fixture.program().equals("gbuffers_clouds") ? Map.of("CLOUDS", "3") : Map.of();
        var translated =
            translator.translate(
                pack.source(program.paths().get(ShaderStage.VERTEX), options, environment),
                pack.source(program.paths().get(ShaderStage.FRAGMENT), options, environment),
                "adapter/" + fixture.pipeline().getLocation().getPath() + "/" + fixture.program(),
                MinecraftVertexAdapter.adapter(
                    fixture.pipeline(),
                    fixture.shadow(),
                    fixture.program().startsWith("gbuffers_hand")));
        if (MinecraftVertexAdapter.isWaterMask(fixture.pipeline()))
          translated = MinecraftVertexAdapter.waterMaskProgram(translated);
        compile(device, translated, fixture.pipeline());
        count++;
      }
      if (count == 0) throw new AssertionError("No compatible shader pairs found in supplied pack");
      System.out.printf(
          "PASS: %d shader-pack pipelines, including Minecraft vertex adapters, compiled by Metal"
              + " in %.3f s%n",
          count, (System.nanoTime() - start) / 1e9);
    } finally {
      TerrainShaderGeometry.disable();
      RenderSystem.shutdownRenderer();
    }
  }

  private static void compile(FrontendGpuDevice device, TranslatedProgram program) {
    compile(device, program, null);
  }

  private static void compile(
      FrontendGpuDevice device, TranslatedProgram program, RenderPipeline vanilla) {
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
            .withVertexShader("shader_test/" + program.label())
            .withFragmentShader("shader_test/" + program.label())
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

  private record AdapterFixture(RenderPipeline pipeline, String program, boolean shadow) {}
}
