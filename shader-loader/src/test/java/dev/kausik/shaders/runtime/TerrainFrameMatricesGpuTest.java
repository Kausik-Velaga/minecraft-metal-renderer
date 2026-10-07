package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.compile.MinecraftVertexAdapter;
import dev.kausik.shaders.compile.ShaderCompatibilityCompiler;
import dev.kausik.shaders.compile.TranslatedProgram;
import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.geometry.TerrainShaderGeometry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Real vertex-stage comparison against the previous per-draw terrain matrix expressions. */
public final class TerrainFrameMatricesGpuTest {
  private static final int SIZE = 4, IMAGE_BYTES = SIZE * SIZE * 16;

  private record Fixture(Matrix4f effect, Matrix4f rotation, int chunk, String description) {}

  public static void main(String[] args) {
    checkAdapterScope();
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    int count = 0;
    try (var compiler = new ShaderCompatibilityCompiler()) {
      for (boolean indirect : new boolean[] {false, true}) {
        RenderPipeline original = indirect ? RenderPipelines.SOLID_TERRAIN_MULTIDRAW : RenderPipelines.SOLID_TERRAIN;
        for (boolean shadow : new boolean[] {false, true}) {
          for (boolean optimized : new boolean[] {false, true}) {
            String label = "terrain_frame_matrices/" + indirect + "/" + shadow + "/" + optimized;
            TranslatedProgram program = compiler.translate(
                vertex(shadow),
                "#version 120\nvarying vec4 errors;void main(){gl_FragColor=errors;}\n",
                label, MinecraftVertexAdapter.adapter(original, shadow, false, optimized));
            try (var pipeline = compile(device, original, program)) {
              count += verify(device, original, program, pipeline, indirect, shadow, optimized);
            }
          }
        }
      }
      System.out.println("PASS: " + count + " real GPU terrain transforms/normals match per-draw reference across bob, hurt, nausea, camera rotations, chunk positions, direct/instanced and world/shadow modes");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static String vertex(boolean shadow) {
    return """
        #version 120
        varying vec4 errors;
        uniform mat4 sl_CameraEffect, sl_CameraEffectInverse;
        uniform mat4 gbufferModelViewInverse, shadowModelView, shadowProjection;
        float maxComponent(vec4 v) { return max(max(abs(v.x),abs(v.y)),max(abs(v.z),abs(v.w))); }
        void main() {
          vec4 point = gl_Vertex;
        """
        + (shadow
            ? "mat4 referenceView=shadowModelView*gbufferModelViewInverse*sl_CameraEffect*ModelViewMat;\nmat4 referenceProjection=shadowProjection;\n"
            : "mat4 referenceView=sl_CameraEffect*ModelViewMat;\nmat4 referenceProjection=sl_ToOpenGLProjection(ProjMat)*sl_CameraEffectInverse;\n")
        + """
          vec4 referencePosition = referenceView * point;
          vec4 referenceClip = referenceProjection * referencePosition;
          vec4 actualPosition = gl_ModelViewMatrix * point;
          vec4 actualClip = gl_ProjectionMatrix * actualPosition;
          vec3 referenceNormal = transpose(inverse(mat3(referenceView))) * gl_Normal;
          vec3 actualNormal = gl_NormalMatrix * gl_Normal;
          float clipScale = max(abs(referenceClip.w), 1.0);
          errors = vec4(maxComponent(actualPosition-referencePosition),
                        maxComponent(actualClip-referenceClip)/clipScale,
                        maxComponent(ftransform()-referenceClip)/clipScale,
                        length(actualNormal-referenceNormal));
          // Rasterize an independent fullscreen triangle so near/behind-camera sample points
          // are still measured. All three vertices carry the same actual terrain input point.
          vec2 corner=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);
          gl_Position=vec4(corner*2.0-1.0,0.0,1.0);
        }
        """;
  }

  private static int verify(
      FrontendGpuDevice device, RenderPipeline original, TranslatedProgram program,
      CompiledRenderPipeline pipeline, boolean indirect, boolean shadow, boolean optimized) {
    List<Fixture> fixtures = fixtures();
    try (var output = new PackTexture(device, "terrain transform errors", GpuFormat.RGBA32_FLOAT, SIZE, SIZE, 1);
        var vertices = device.createBuffer(() -> "extended terrain vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, TerrainShaderGeometry.STRIDE * 3);
        var chunk = device.createBuffer(() -> "terrain chunk data", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
        var terrain = device.createBuffer(() -> "terrain matrices", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 80);
        var globals = device.createBuffer(() -> "terrain globals", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 48);
        var projection = device.createBuffer(() -> "terrain projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        var frame = device.createBuffer(() -> "terrain pack frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, program.uniforms().byteSize());
        var readback = device.createBuffer(() -> "terrain transform readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, IMAGE_BYTES * fixtures.size())) {
      var encoder = device.createCommandEncoder();
      for (int index = 0; index < fixtures.size(); index++) {
        Fixture fixture = fixtures.get(index);
        Matrix4f baseNative = new Matrix4f().perspective((float) Math.toRadians(70), 3456f / 2168, 1024, .05f, true);
        Matrix4f combined = new Matrix4f(baseNative).mul(fixture.effect);
        FrameUniforms values = new FrameUniforms();
        values.updateCameraMatrices(baseNative, combined, fixture.rotation);
        Vector3f light = switch (index % 3) {
          case 0 -> new Vector3f(1, .01f, .08f).normalize();
          case 1 -> new Vector3f(0, .766f, .642f).normalize();
          default -> new Vector3f(0, -.766f, -.642f).normalize();
        };
        Matrix4f lightView = ShadowRenderer.lightView(light, 256).translate(.4f, .7f, 1.2f);
        values.setShadowMatrices(new Matrix4f().ortho(-256, 256, -256, 256, .05f, 1024), lightView);
        ByteBuffer positions = buffer(TerrainShaderGeometry.STRIDE * 3);
        for (int vertex = 0; vertex < 3; vertex++) {
          int at = vertex * TerrainShaderGeometry.STRIDE;
          positions.putFloat(at, 3.25f).putFloat(at + 4, 11.5f).putFloat(at + 8, 7.75f);
          positions.putInt(at + 12, -1);
          positions.put(at + TerrainShaderGeometry.NORMAL_OFFSET + index % 3, (byte) 127);
        }
        encoder.writeToBuffer(vertices.slice(), positions);
        ByteBuffer model = buffer(80);
        fixture.rotation.get(0, model);
        model.putInt(64, 1024).putInt(68, 1024);
        encoder.writeToBuffer(terrain.slice(), model);
        // Large absolute coordinates cancel as integers before conversion to float, exactly as
        // vanilla terrain does. Relative chunks cover near and distant positive/negative axes.
        int origin = fixture.chunk == 2 ? 29_999_000 : fixture.chunk == 1 ? -27_000_000 : 0;
        int delta = (fixture.chunk - 1) * 128;
        ByteBuffer chunkValues = buffer(16);
        chunkValues.putInt(0, origin + delta).putInt(4, 64 + delta / 2).putInt(8, origin - delta).putFloat(12, 1);
        encoder.writeToBuffer(chunk.slice(), chunkValues);
        ByteBuffer globalsValues = buffer(48);
        globalsValues.putInt(0, origin).putInt(4, 64).putInt(8, origin);
        globalsValues.putFloat(16, -.375f).putFloat(20, -.625f).putFloat(24, -.125f);
        globalsValues.putFloat(32, SIZE).putFloat(36, SIZE);
        encoder.writeToBuffer(globals.slice(), globalsValues);
        ByteBuffer projectionValues = buffer(64);
        combined.get(0, projectionValues);
        encoder.writeToBuffer(projection.slice(), projectionValues);
        ByteBuffer frameValues = program.uniforms().allocate();
        values.write(program.uniforms(), frameValues, false, shadow);
        encoder.writeToBuffer(frame.slice(), frameValues);
        try (var pass = encoder.createRenderPass(() -> "terrain matrix equivalence", output.level(0), Optional.of(new Vector4f(-1)))) {
          pass.setPipeline(pipeline);
          pass.setUniform("TerrainUniform", terrain);
          pass.setUniform("Globals", globals);
          pass.setUniform("Projection", projection);
          pass.setUniform(UniformLayout.BLOCK_NAME, frame);
          if (!indirect) pass.setUniform("ChunkSection", chunk);
          pass.setVertexBuffer(0, vertices.slice());
          if (indirect) pass.setVertexBuffer(1, chunk.slice());
          pass.draw(3, 1, 0, 0);
        }
        encoder.copyTextureToBuffer(output.texture, readback, (long) index * IMAGE_BYTES, () -> {}, 0);
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(10_000_000_000L)) throw new AssertionError("Terrain matrix GPU test timeout");
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
        for (int index = 0; index < fixtures.size(); index++) {
          for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
            for (int channel = 0; channel < 4; channel++) {
              float error = data.getFloat(index * IMAGE_BYTES + pixel * 16 + channel * 4);
              float tolerance = channel == 0 ? .001f : .00002f;
              if (!Float.isFinite(error) || error < 0 || error > tolerance)
                throw new AssertionError("Terrain matrix mismatch indirect=" + indirect + " shadow=" + shadow
                    + " optimized=" + optimized + " fixture=" + fixtures.get(index).description
                    + " chunk=" + fixtures.get(index).chunk + " channel=" + channel + " error=" + error);
            }
          }
        }
      }
    }
    return fixtures.size();
  }

  private static List<Fixture> fixtures() {
    List<Matrix4f> effects = new ArrayList<>();
    effects.add(new Matrix4f());
    for (float phase : new float[] {0, .25f, .5f, .75f}) {
      float sin = (float) Math.sin(phase * Math.PI), cos = (float) Math.cos(phase * Math.PI);
      effects.add(new Matrix4f().translate(sin * .05f, -Math.abs(cos * .1f), 0)
          .rotateZ((float) Math.toRadians(sin * .3f))
          .rotateX((float) Math.toRadians(Math.abs(Math.cos(phase * Math.PI - .2) * .1) * 5)));
    }
    effects.add(new Matrix4f().rotateY(-.6f).rotateZ(.17f).rotateY(.6f));
    Vector3f axis = new Vector3f(0, 1, 1).normalize();
    effects.add(new Matrix4f().rotate(.8f, axis).scale(1.06f, 1, 1).rotate(-.8f, axis));
    List<Fixture> result = new ArrayList<>();
    for (int effect = 0; effect < effects.size(); effect++) {
      for (int orientation = 0; orientation < 3; orientation++) {
        Matrix4f rotation = new Matrix4f().rotateX(-.45f + orientation * .35f).rotateY(-1.2f + orientation * 1.5f);
        for (int chunk = 0; chunk < 3; chunk++)
          result.add(new Fixture(effects.get(effect), rotation, chunk, "effect=" + effect + ", orientation=" + orientation));
      }
    }
    return result;
  }

  private static CompiledRenderPipeline compile(FrontendGpuDevice device, RenderPipeline original, TranslatedProgram program) {
    var bindings = BindGroupLayout.builder().withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
    for (String block : MinecraftVertexAdapter.uniformBlocks(original)) bindings.withUniform(block, UniformType.UNIFORM_BUFFER);
    var description = RenderPipeline.builder().withLocation("shader_test/" + program.label())
        .withVertexShader("terrain_frame_test").withFragmentShader("terrain_frame_test")
        .withBindGroupLayout(bindings.build()).withVertexBinding(0, TerrainShaderGeometry.FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false)
        .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
    if (original.getVertexFormatBindings().size() > 1)
      description.withVertexBinding(1, original.getVertexFormatBinding(1));
    ShaderSource source = new ShaderSource() {
      public String getShader(Identifier id, ShaderType type) { return type == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource(); }
      public CachedIncludeSource getInclude(Identifier id) { return null; }
      public void close() {}
    };
    return device.compilePipeline(description.build(), source, Runnable::run).join().finishCompile();
  }

  private static void checkAdapterScope() {
    for (RenderPipeline original : List.of(RenderPipelines.WATER_MASK, RenderPipelines.ENTITY_SOLID, RenderPipelines.LINES)) {
      for (boolean hand : new boolean[] {false, true}) {
        var before = MinecraftVertexAdapter.adapter(original, false, hand, false);
        var after = MinecraftVertexAdapter.adapter(original, false, hand, true);
        if (!before.equals(after)) throw new AssertionError("Terrain optimization changed another draw contract: " + original.getLocation());
      }
    }
    for (boolean shadow : new boolean[] {false, true}) {
      var adapter = MinecraftVertexAdapter.adapter(RenderPipelines.SOLID_TERRAIN, shadow, false, true);
      if (adapter.legacyExpressions().get("gl_NormalMatrix").contains("inverse("))
        throw new AssertionError("Optimized terrain still calculates a per-vertex matrix inverse");
      if (!adapter.legacyExpressions().get("gl_ModelViewProjectionMatrix").startsWith("sl_"))
        throw new AssertionError("Optimized terrain does not use the precomputed MVP");
    }
  }

  private static ByteBuffer buffer(int bytes) {
    return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
  }
}
