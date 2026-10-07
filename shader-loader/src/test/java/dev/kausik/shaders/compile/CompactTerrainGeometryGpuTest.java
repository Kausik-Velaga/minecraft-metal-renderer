package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import dev.kausik.shaders.geometry.TerrainVertexWriter;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.Random;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * Actual terrain writer -> vertex fetch -> adapter -> shader attribute equality for both layouts.
 */
public final class CompactTerrainGeometryGpuTest {
  private static final String FLAG = "minecraftShaders.compactTerrainVertices";
  private static final int SIDE = 128, VERTICES = SIDE * SIDE, QUADS = VERTICES / 4;
  private static final int OBSERVATIONS = 8, IMAGE_BYTES = VERTICES * 16;
  private static final int[] MATERIALS = {
    Short.MIN_VALUE, -12345, -1, 0, 1, 10000, 25318, Short.MAX_VALUE
  };
  private static final String VERTEX =
      """
      #version 120
      attribute vec4 mc_Entity, mc_midTexCoord, at_tangent;
      attribute vec3 at_midBlock;
      flat varying vec4 attributes[8];
      void main() {
        attributes[0]=mc_Entity;
        attributes[1]=vec4(at_midBlock,1.0);
        attributes[2]=mc_midTexCoord;
        attributes[3]=at_tangent;
        attributes[4]=vec4(gl_Normal,1.0);
        attributes[5]=gl_Vertex;
        attributes[6]=gl_Color;
        attributes[7]=vec4(gl_MultiTexCoord0.xy,gl_MultiTexCoord1.xy);
        // Observe every emitted quad vertex independently: interpolation cannot hide a difference.
        vec2 pixel=vec2(gl_VertexIndex%128,gl_VertexIndex/128)+0.5;
        gl_Position=vec4(pixel/128.0*2.0-1.0,0.0,1.0);
        gl_PointSize=1.0;
      }
      """;
  private static final String FRAGMENT =
      """
      #version 120
      uniform int observation;
      flat varying vec4 attributes[8];
      void main(){gl_FragColor=attributes[observation];}
      """;

  public static void main(String[] args) {
    if (!ShadercFragmentOptimization.enabled())
      throw new IllegalArgumentException(
          "Enable minecraftShaders.optimizeFragmentSpirv; also test with optimizeVertexSpirv"
              + " enabled");
    String previous = System.getProperty(FLAG);
    System.setProperty(FLAG, "true");
    ByteBuffer full = vertices(TerrainShaderGeometry.Layout.FULL);
    ByteBuffer compact = vertices(TerrainShaderGeometry.Layout.COMPACT);
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var translator = new ShaderCompatibilityCompiler()) {
      var optimized = FragmentOptimizationSmokeCompiler.install(device);
      for (boolean indirect : new boolean[] {false, true}) {
        for (boolean shadow : new boolean[] {false, true}) {
          RenderPipeline vanilla =
              indirect ? RenderPipelines.SOLID_TERRAIN_MULTIDRAW : RenderPipelines.SOLID_TERRAIN;
          TerrainShaderGeometry.configure(state -> -1, true, false);
          RenderPipeline fullDescription = TerrainShaderGeometry.pipeline(vanilla);
          TerrainShaderGeometry.configure(state -> -1, true, true);
          RenderPipeline compactDescription = TerrainShaderGeometry.pipeline(vanilla);
          var a =
              translator.translate(
                  VERTEX,
                  FRAGMENT,
                  "compact_terrain/full_" + indirect + "_" + shadow,
                  MinecraftVertexAdapter.adapter(fullDescription, shadow, false, false),
                  AlphaTestPolicy.OFF);
          var b =
              translator.translate(
                  VERTEX,
                  FRAGMENT,
                  "compact_terrain/compact_" + indirect + "_" + shadow,
                  MinecraftVertexAdapter.adapter(compactDescription, shadow, false, false),
                  AlphaTestPolicy.OFF);
          MatrixUniformSpecializationTest.check(
              a.uniforms().equals(b.uniforms()), "Vertex layouts changed uniform ABI");
          try (var fullPipeline = pipeline(device, fullDescription, a);
              var compactPipeline = pipeline(device, compactDescription, b)) {
            verify(device, a, fullPipeline, compactPipeline, full, compact, indirect, shadow);
          }
        }
      }
      optimized.assertActivated(8);
      System.out.println(
          "PASS: every shader-visible terrain attribute matches across FULL/COMPACT for 16384"
              + " vertices, all 4096 block centers, signed IDs, both render types, direct/instanced"
              + " and world/shadow adapters");
    } finally {
      TerrainShaderGeometry.disable();
      if (previous == null) System.clearProperty(FLAG);
      else System.setProperty(FLAG, previous);
      MemoryUtil.memFree(full);
      MemoryUtil.memFree(compact);
      RenderSystem.shutdownRenderer();
    }
  }

  private static ByteBuffer vertices(TerrainShaderGeometry.Layout layout) {
    int[] material = {-1};
    TerrainShaderGeometry.configure(
        state -> material[0], true, layout == TerrainShaderGeometry.Layout.COMPACT);
    var previous = TerrainShaderGeometry.beginSection();
    ByteBuffer bytes =
        MemoryUtil.memCalloc(VERTICES * layout.stride()).order(ByteOrder.nativeOrder());
    long start = MemoryUtil.memAddress(bytes);
    Random random = new Random(847917);
    float[] boundaries = {
      0,
      -0.0f,
      Float.MIN_VALUE,
      -Float.MIN_VALUE,
      Math.nextDown(.5f),
      .5f,
      Math.nextUp(.5f),
      Math.nextDown(15.5f),
      15.5f,
      Math.nextUp(15.5f),
      -8191.125f,
      32768.03125f,
      Float.MAX_VALUE / 128,
      -Float.MAX_VALUE / 128,
      Float.MAX_VALUE,
      -Float.MAX_VALUE,
      Float.NaN,
      Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY
    };
    try {
      for (int quad = 0; quad < QUADS; quad++) {
        material[0] = MATERIALS[quad % MATERIALS.length];
        boolean fluid = ((quad / MATERIALS.length) & 1) != 0;
        int x = quad & 15, y = (quad >>> 4) & 15, z = (quad >>> 8) & 15;
        TerrainShaderGeometry.beginBlock(null, new BlockPos(x - 32, y + 48, z - 128), fluid);
        float[] origin = {
          x + random.nextFloat() * 8 - 4,
          y + random.nextFloat() * 8 - 4,
          z + random.nextFloat() * 8 - 4
        };
        if (quad < boundaries.length * 3) origin[quad % 3] = boundaries[quad / 3];
        else if ((quad & 31) == 4 || (quad & 31) == 8 || (quad & 31) == 12) {
          int axis = quad % 3;
          float center = ((quad >>> (axis * 4)) & 15) + .5f;
          origin[axis] =
              switch (quad & 31) {
                case 4 -> Math.nextDown(center);
                case 8 -> center;
                default -> Math.nextUp(center);
              };
        } else if ((quad & 7) == 0) {
          int bits = random.nextInt();
          if ((bits & 0x7f800000) == 0x7f800000) bits &= ~0x00800000;
          origin[quad % 3] = Float.intBitsToFloat(bits);
        }
        for (int vertex = 0; vertex < 4; vertex++) {
          long pointer = start + (long) (quad * 4 + vertex) * layout.stride();
          float u = vertex == 1 || vertex == 2 ? 1 : 0, v = vertex >= 2 ? 1 : 0;
          float[] position = origin.clone();
          // Include true degenerate quads and positions one ULP from their block center.
          if ((quad & 3) != 0 && quad >= boundaries.length * 3) {
            position[quad % 3] += u * 1.234567f;
            position[(quad + 1) % 3] += v * .7654321f;
            position[(quad + 2) % 3] += u * .125f + v * .375f;
          }
          for (int axis = 0; axis < 3; axis++)
            MemoryUtil.memPutFloat(pointer + axis * 4L, position[axis]);
          MemoryUtil.memPutInt(pointer + 12, 0x7f214365 ^ (quad * 113));
          boolean mirror = (quad & 1) != 0;
          MemoryUtil.memPutFloat(pointer + 16, (mirror ? 1 - u : u) * .654321f + .012345f);
          MemoryUtil.memPutFloat(pointer + 20, (quad & 15) == 0 ? .12345f : v * .98765f + .00123f);
          MemoryUtil.memPutShort(pointer + 24, (short) ((quad & 15) * 16));
          MemoryUtil.memPutShort(pointer + 26, (short) (((quad >>> 4) & 15) * 16));
          TerrainVertexWriter.putNormal(
              pointer + 28, quad % 3 == 0 ? 1 : 0, quad % 3 == 1 ? 1 : 0, quad % 3 == 2 ? 1 : 0);
          TerrainVertexWriter.writeMetadata(pointer, position[0], position[1], position[2], layout);
        }
        TerrainVertexWriter.finishQuad(start + (long) (quad * 4 + 3) * layout.stride(), layout);
      }
      return bytes;
    } catch (Throwable failure) {
      MemoryUtil.memFree(bytes);
      throw failure;
    } finally {
      TerrainShaderGeometry.endSection(previous);
    }
  }

  private static void verify(
      FrontendGpuDevice device,
      TranslatedProgram program,
      CompiledRenderPipeline fullPipeline,
      CompiledRenderPipeline compactPipeline,
      ByteBuffer fullData,
      ByteBuffer compactData,
      boolean indirect,
      boolean shadow) {
    try (var output =
            new PackTexture(
                device, "compact attribute result", GpuFormat.RGBA32_FLOAT, SIDE, SIDE, 1);
        var full =
            device.createBuffer(
                () -> "full terrain",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                fullData.capacity());
        var compact =
            device.createBuffer(
                () -> "compact terrain",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                compactData.capacity());
        var chunk =
            device.createBuffer(
                () -> "terrain chunk",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                16);
        var terrain =
            device.createBuffer(
                () -> "terrain transform", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 80);
        var globals =
            device.createBuffer(
                () -> "terrain globals", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 48);
        var projection =
            device.createBuffer(
                () -> "terrain projection",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                64);
        var frame =
            device.createBuffer(
                () -> "terrain attribute selector",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                program.uniforms().byteSize());
        var readback =
            device.createBuffer(
                () -> "terrain attribute readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) IMAGE_BYTES * OBSERVATIONS * 2)) {
      var encoder = device.createCommandEncoder();
      encoder.writeToBuffer(full.slice(), fullData.duplicate());
      encoder.writeToBuffer(compact.slice(), compactData.duplicate());
      ByteBuffer model = buffer(80);
      new Matrix4f().get(0, model);
      model.putInt(64, 1024).putInt(68, 1024);
      encoder.writeToBuffer(terrain.slice(), model);
      ByteBuffer projectionValues = buffer(64);
      new Matrix4f().get(0, projectionValues);
      encoder.writeToBuffer(projection.slice(), projectionValues);
      ByteBuffer globalsValues = buffer(48);
      globalsValues.putFloat(32, SIDE).putFloat(36, SIDE);
      encoder.writeToBuffer(globals.slice(), globalsValues);
      ByteBuffer chunkValues = buffer(16);
      chunkValues.putFloat(12, 1);
      encoder.writeToBuffer(chunk.slice(), chunkValues);
      ByteBuffer frameValues = program.uniforms().allocate();
      for (var field : program.uniforms().fields()) {
        if (field.type().equals("mat4"))
          program
              .uniforms()
              .putFloats(frameValues, field.name(), new Matrix4f().get(new float[16]));
      }
      for (int observation = 0; observation < OBSERVATIONS; observation++) {
        program.uniforms().putInts(frameValues, "observation", observation);
        encoder.writeToBuffer(frame.slice(), frameValues);
        for (int variant = 0; variant < 2; variant++) {
          try (var pass =
              encoder.createRenderPass(
                  () -> "terrain vertex attribute equivalence",
                  output.level(0),
                  Optional.of(new Vector4f(-777)))) {
            pass.setPipeline(variant == 0 ? fullPipeline : compactPipeline);
            pass.setUniform("TerrainUniform", terrain);
            pass.setUniform("Globals", globals);
            pass.setUniform("Projection", projection);
            pass.setUniform(UniformLayout.BLOCK_NAME, frame);
            if (!indirect) pass.setUniform("ChunkSection", chunk);
            pass.setVertexBuffer(0, variant == 0 ? full.slice() : compact.slice());
            if (indirect) pass.setVertexBuffer(1, chunk.slice());
            pass.draw(VERTICES, 1, 0, 0);
          }
          encoder.copyTextureToBuffer(
              output.texture,
              readback,
              (long) (observation * 2 + variant) * IMAGE_BYTES,
              () -> {},
              0);
        }
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        MatrixUniformSpecializationTest.check(
            fence.awaitCompletion(10_000_000_000L), "Compact terrain GPU timeout");
      }
      long changed = 0, strict = 0, maxUlp = 0;
      try (var mapping = readback.map(true, false)) {
        var bytes = mapping.data().order(ByteOrder.nativeOrder());
        for (int observation = 0; observation < OBSERVATIONS; observation++) {
          int a = observation * 2 * IMAGE_BYTES, b = a + IMAGE_BYTES;
          for (int vertex = 0; vertex < VERTICES; vertex++) {
            if (observation == 0) {
              MatrixUniformSpecializationTest.check(
                  bytes.getFloat(a + vertex * 16) == MATERIALS[(vertex / 4) % MATERIALS.length],
                  "Full layout material or point coverage is wrong at vertex " + vertex);
              MatrixUniformSpecializationTest.check(
                  bytes.getFloat(a + vertex * 16 + 12) == 1,
                  "Point rasterization left an unobserved vertex");
            }
            for (int component = 0; component < 4; component++) {
              int offset = vertex * 16 + component * 4;
              int expected = bytes.getInt(a + offset), actual = bytes.getInt(b + offset);
              if (expected == actual) continue;
              float x = Float.intBitsToFloat(expected), y = Float.intBitsToFloat(actual);
              if (Float.isNaN(x) && Float.isNaN(y)) continue;
              changed++;
              if (!Float.isFinite(x) || !Float.isFinite(y) || (expected ^ actual) < 0) strict++;
              else maxUlp = Math.max(maxUlp, Math.abs((long) expected - actual));
            }
          }
        }
      }
      System.out.println(
          "Compact terrain GPU indirect="
              + indirect
              + ", shadow="
              + shadow
              + ": changed="
              + changed
              + ", maxULP="
              + maxUlp
              + ", strictFailures="
              + strict);
      MatrixUniformSpecializationTest.check(
          changed == 0 && strict == 0, "Compact terrain changed shader-visible values");
    }
  }

  private static CompiledRenderPipeline pipeline(
      FrontendGpuDevice device, RenderPipeline original, TranslatedProgram program) {
    var bindings =
        BindGroupLayout.builder().withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
    for (String name : MinecraftVertexAdapter.uniformBlocks(original))
      bindings.withUniform(name, UniformType.UNIFORM_BUFFER);
    var description =
        RenderPipeline.builder()
            .withLocation(
                Identifier.fromNamespaceAndPath("minecraft_shader_loader", program.label()))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("minecraft_shader_loader", "pack"))
            .withBindGroupLayout(bindings.build())
            .withPrimitiveTopology(PrimitiveTopology.POINTS)
            .withCull(false)
            .withColorTargetState(
                new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
    for (int slot = 0; slot < original.getVertexFormatBindings().size(); slot++) {
      var format = original.getVertexFormatBindings().get(slot);
      if (format != null) description.withVertexBinding(slot, format);
    }
    var compiled =
        device
            .compilePipeline(
                description.build(),
                new ShaderSource() {
                  public String getShader(Identifier id, ShaderType type) {
                    return type == ShaderType.VERTEX
                        ? program.vertexSource()
                        : program.fragmentSource();
                  }

                  public CachedIncludeSource getInclude(Identifier id) {
                    return null;
                  }

                  public void close() {}
                },
                Runnable::run)
            .join()
            .finishCompile();
    MatrixUniformSpecializationTest.check(
        compiled != null, "Compact terrain GPU shader failed to compile");
    return compiled;
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
