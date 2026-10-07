package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Real GPU regression for pack depth reconstruction with Minecraft's moving camera effects. */
public final class CameraProjectionGpuTest {
  private static final int SIZE = 32;

  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var compiler = new ShaderCompatibilityCompiler()) {
      for (int fixture = 0; fixture < 3; fixture++) {
        boolean hand = fixture == 2;
        boolean specialProjection = fixture == 1;
        var adapter = MinecraftVertexAdapter.adapter(RenderPipelines.WATER_MASK, false, hand);
        String vertex =
            """
            #version 120
            varying vec3 localPosition, packViewPosition, packNormal;
            varying vec4 referenceClip;
            void main() {
              localPosition = gl_Vertex.xyz;
              packViewPosition = (gl_ModelViewMatrix * gl_Vertex).xyz;
              packNormal = normalize(gl_NormalMatrix * gl_Normal);
              referenceClip = sl_ToOpenGLProjection(ProjMat) * ModelViewMat * gl_Vertex;
            """
                + (hand ? "referenceClip.z *= 0.125;\n" : "")
                + "gl_Position=ftransform();}\n";
        String fragment =
            """
            #version 120
            varying vec3 localPosition, packViewPosition, packNormal;
            varying vec4 referenceClip;
            uniform mat4 gbufferProjectionInverse, gbufferModelViewInverse;
            void main() {
              vec3 actual = vec3(gl_FragCoord.xy / 32.0, gl_FragCoord.z);
              vec3 reference = referenceClip.xyz / referenceClip.w * 0.5 + 0.5;
              float clipError = length(actual - reference);
              vec3 ndc = actual * 2.0 - 1.0;
              // Standard symmetric-perspective reconstruction used by legacy packs.
              vec4 view = vec4(gbufferProjectionInverse[0].x * ndc.x,
                               gbufferProjectionInverse[1].y * ndc.y,
                               gbufferProjectionInverse[2].z * ndc.z,
                               gbufferProjectionInverse[2].w * ndc.z)
                        + gbufferProjectionInverse[3];
              view /= view.w;
              vec3 world = (gbufferModelViewInverse * view).xyz;
            """
                + (hand || specialProjection
                    ? "gl_FragColor=vec4(0.0,0.0,0.0,clipError);}"
                    : "vec3 expectedNormal=normalize(transpose(mat3(gbufferModelViewInverse))*vec3(0,0,1));vec3"
                          + " error=max(abs(world-localPosition),abs(view.xyz-packViewPosition));"
                          + "gl_FragColor=vec4(max(error,abs(expectedNormal-packNormal)),clipError);}");
        var translated =
            compiler.translate(vertex, fragment, "camera_projection/" + fixture, adapter);
        try (var pipeline = compile(device, translated)) {
          for (float phase : new float[] {0, .25f, .5f, .75f})
            verify(device, translated, pipeline, phase, hand, specialProjection);
        }
      }
      System.out.println(
          "PASS: real Metal world/special-projection/hand clip coordinates are unchanged while"
              + " bobbed pack depth reconstructs the actual surface");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verify(
      FrontendGpuDevice device,
      TranslatedProgram program,
      CompiledRenderPipeline pipeline,
      float phase,
      boolean hand,
      boolean specialProjection) {
    Matrix4f projection =
        new Matrix4f().perspective((float) Math.toRadians(70), 3456f / 2168, .05f, 512);
    float sin = (float) Math.sin(phase * Math.PI), cos = (float) Math.cos(phase * Math.PI);
    Matrix4f effect =
        new Matrix4f()
            .translate(sin * .05f, -Math.abs(cos * .1f), 0)
            .rotateZ((float) Math.toRadians(sin * .3f))
            .rotateX((float) Math.toRadians(Math.abs(Math.cos(phase * Math.PI - .2) * .1) * 5));
    Matrix4f view = new Matrix4f().rotateX(.15f).rotateY(-.1f);
    Matrix4f nativeProjection = new Matrix4f().m22(-.5f).m32(.5f).mul(projection);
    // A draw can use its own projection (for example a sky pass). The adapter must retain it.
    if (specialProjection) nativeProjection.scale(1.23f, .81f, 1);
    if (hand) view = new Matrix4f(effect).mul(view);
    else nativeProjection.mul(effect);
    Matrix4f packView = hand ? new Matrix4f(view) : new Matrix4f(effect).mul(view);
    try (var color =
            new PackTexture(
                device, "camera reconstruction", GpuFormat.RGBA32_FLOAT, SIZE, SIZE, 1);
        var vertices =
            device.createBuffer(
                () -> "camera vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 36);
        var transforms =
            device.createBuffer(
                () -> "camera transforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                160);
        var proj =
            device.createBuffer(
                () -> "camera projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        var pack =
            device.createBuffer(
                () -> "pack camera",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                program.uniforms().byteSize());
        var readback =
            device.createBuffer(
                () -> "camera readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                SIZE * SIZE * 16)) {
      var encoder = device.createCommandEncoder();
      encoder.clearColorTexture(color.texture, new Vector4f(-1));
      ByteBuffer positions = buffer(36);
      for (float v : new float[] {-60, -50, -12, 60, -50, -12, 0, 70, -12}) positions.putFloat(v);
      encoder.writeToBuffer(vertices.slice(), positions.flip());
      ByteBuffer dynamic = buffer(160);
      view.get(0, dynamic);
      new Matrix4f().get(64, dynamic);
      for (int i = 0; i < 4; i++) dynamic.putFloat(128 + i * 4, 1);
      encoder.writeToBuffer(transforms.slice(), dynamic);
      ByteBuffer p = buffer(64);
      nativeProjection.get(0, p);
      encoder.writeToBuffer(proj.slice(), p);
      var values = program.uniforms().allocate();
      if (!hand) {
        program.uniforms().putFloats(values, "sl_CameraEffect", effect.get(new float[16]));
        program
            .uniforms()
            .putFloats(
                values, "sl_CameraEffectInverse", new Matrix4f(effect).invert().get(new float[16]));
      }
      program
          .uniforms()
          .putFloats(
              values,
              "gbufferProjectionInverse",
              new Matrix4f(projection).invert().get(new float[16]));
      program
          .uniforms()
          .putFloats(
              values,
              "gbufferModelViewInverse",
              new Matrix4f(packView).invert().get(new float[16]));
      encoder.writeToBuffer(pack.slice(), values);
      try (var pass =
          encoder.createRenderPass(() -> "camera projection", color.level(0), Optional.empty())) {
        pass.setPipeline(pipeline);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Projection", proj);
        pass.setUniform(UniformLayout.BLOCK_NAME, pack);
        pass.setVertexBuffer(0, vertices.slice());
        pass.draw(3, 1, 0, 0);
      }
      encoder.copyTextureToBuffer(color.texture, readback, 0, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L)) throw new AssertionError("Camera test timeout");
      }
      try (var mapped = readback.map(true, false)) {
        var data = mapped.data().order(ByteOrder.nativeOrder());
        int count = 0;
        for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
          if (data.getFloat(pixel * 16) < 0) continue;
          count++;
          for (int channel = 0; channel < 4; channel++) {
            float error = data.getFloat(pixel * 16 + channel * 4);
            float tolerance = channel == 3 ? .000002f : .003f;
            if (!Float.isFinite(error) || error > tolerance)
              throw new AssertionError(
                  "Camera reconstruction hand="
                      + hand
                      + " phase="
                      + phase
                      + " channel="
                      + channel
                      + " error="
                      + error);
          }
        }
        if (count < SIZE * SIZE * .9)
          throw new AssertionError("Insufficient camera fixture coverage: " + count);
      }
    }
  }

  private static CompiledRenderPipeline compile(
      FrontendGpuDevice device, TranslatedProgram program) {
    var bindings =
        BindGroupLayout.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER)
            .build();
    var pipeline =
        RenderPipeline.builder()
            .withLocation("camera_projection_gpu")
            .withVertexShader("camera_projection_gpu")
            .withFragmentShader("camera_projection_gpu")
            .withBindGroupLayout(bindings)
            .withVertexBinding(0, RenderPipelines.WATER_MASK.getVertexFormatBinding(0))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(
                new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .build();
    ShaderSource source =
        new ShaderSource() {
          public String getShader(Identifier id, ShaderType stage) {
            return stage == ShaderType.VERTEX ? program.vertexSource() : program.fragmentSource();
          }

          public CachedIncludeSource getInclude(Identifier id) {
            return null;
          }

          public void close() {}
        };
    return device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
