package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.runtime.PackTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Actual font formats prove full-bright see-through text, lit world text and red-only coverage. */
public final class TextGeometryTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    var device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var compiler = new ShaderCompatibilityCompiler()) {
      for (var original :
          List.of(
              RenderPipelines.TEXT,
              RenderPipelines.TEXT_GRAYSCALE,
              RenderPipelines.TEXT_POLYGON_OFFSET,
              RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET,
              RenderPipelines.TEXT_SEE_THROUGH,
              RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH,
              RenderPipelines.GUI_TEXT,
              RenderPipelines.GUI_TEXT_GRAYSCALE,
              RenderPipelines.BLOCK_SCREEN_EFFECT,
              RenderPipelines.FIRE_SCREEN_EFFECT,
              RenderPipelines.GLINT)) {
        boolean glint = original == RenderPipelines.GLINT;
        if (glint
            && !dev.kausik.shaders.runtime.SceneProgram.select(original, true, false)
                .name()
                .equals("gbuffers_armor_glint"))
          throw new AssertionError("First-person standalone glint lost its dedicated pack program");
        String vertex =
            """
            #version 130
            varying vec2 uv, lm;
            varying vec4 color;
            varying vec3 normal;
            void main() {
              gl_Position=ftransform(); uv=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;
            """
                + (glint
                    ? "lm=uv; normal=vec3(0,0,1);"
                    : "lm=(gl_TextureMatrix[1]*gl_MultiTexCoord1).xy; normal=gl_Normal;")
                + "color=gl_Color;}";
        var program =
            compiler.translate(
                vertex,
                """
                #version 130
                varying vec2 uv, lm;
                varying vec4 color;
                varying vec3 normal;
                uniform sampler2D texture, lightmap;
                void main() {
                  ivec2 extent=textureSize(texture,0);
                  vec4 glyph=0.5*(texture2D(texture,uv)+texelFetch(texture,clamp(ivec2(uv*vec2(extent)),ivec2(0),extent-1),0));
                  gl_FragData[0]=glyph*color;
                  gl_FragData[1]=vec4(lm,texture2D(lightmap,uv).b,float(extent.x)/4.0+length(normal-vec3(0,0,1)));
                }
                """,
                "text/" + original.getLocation().getPath(),
                MinecraftVertexAdapter.adapter(original, false, handFixture(original)),
                AlphaTestPolicy.greater(.1f));
        var adapted = MinecraftTextureAdapter.adapt(original, program);
        if (!original.getShaderDefines().flags().contains("IS_GRAYSCALE") && adapted != program)
          throw new AssertionError("Color font or overlay source was modified");
        verify(device, original, adapted);
      }
      System.out.println(
          "PASS: eight vanilla text formats, two first-person overlays and standalone glint retain"
              + " lightmap, fullbright, glyph, normal and texture-matrix semantics");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static boolean handFixture(RenderPipeline original) {
    return original == RenderPipelines.GLINT
        || original == RenderPipelines.FIRE_SCREEN_EFFECT
        || original == RenderPipelines.BLOCK_SCREEN_EFFECT;
  }

  private static void verify(
      FrontendGpuDevice device, RenderPipeline original, TranslatedProgram program) {
    var format = original.getVertexFormatBinding(0);
    boolean grayscale = original.getShaderDefines().flags().contains("IS_GRAYSCALE");
    boolean fullbright = !format.contains("UV2");
    boolean glint = original == RenderPipelines.GLINT;
    var bindings =
        BindGroupLayout.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform(UniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER)
            .withUniform("sl_texture", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("lightmap", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();
    var pipeline =
        RenderPipeline.builder()
            .withLocation("text_gpu_test")
            .withVertexShader("text_gpu_test")
            .withFragmentShader("text_gpu_test")
            .withVertexBinding(0, format)
            .withBindGroupLayout(bindings)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(0, ColorTargetState.DEFAULT)
            .withColorTargetState(
                1,
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
    try (var compiled =
            device.compilePipeline(pipeline, source, Runnable::run).join().finishCompile();
        var atlas =
            new PackTexture(
                device,
                "font glyphs",
                grayscale ? GpuFormat.R8_UNORM : GpuFormat.RGBA8_UNORM,
                4,
                1,
                1);
        var unrelated =
            new PackTexture(device, "unrelated lightmap", GpuFormat.RGBA8_UNORM, 1, 1, 1);
        var color = new PackTexture(device, "font color", GpuFormat.RGBA8_UNORM, 4, 1, 1);
        var diagnostics =
            new PackTexture(device, "font coordinates", GpuFormat.RGBA32_FLOAT, 4, 1, 1);
        var vertices =
            device.createBuffer(
                () -> "actual text vertices",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                format.getVertexSize() * 3);
        var dynamic =
            device.createBuffer(
                () -> "text transforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 160);
        var projection =
            device.createBuffer(
                () -> "text projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
        var uniforms =
            device.createBuffer(
                () -> "text pack uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                program.uniforms().byteSize());
        var sampler =
            device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0));
        var readback =
            device.createBuffer(
                () -> "text readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, 80)) {
      var encoder = device.createCommandEncoder();
      encoder.clearColorTexture(color.texture, new Vector4f(0, 1, 0, 1));
      encoder.clearColorTexture(diagnostics.texture, new Vector4f(-1));
      int[][] texels = {
        {0, 64, 192, 0}, {64, 96, 192, 128}, {128, 160, 192, 192}, {255, 224, 192, 255}
      };
      ByteBuffer pixels = buffer(grayscale ? 4 : 16);
      for (int[] texel : texels) {
        if (grayscale) pixels.put((byte) texel[0]);
        else for (int value : texel) pixels.put((byte) value);
      }
      encoder.writeToTexture(atlas.texture, pixels.flip(), 0, 0, 0, 0, 4, 1);
      encoder.writeToTexture(
          unrelated.texture,
          buffer(4).put(new byte[] {17, 33, (byte) 211, (byte) 255}).flip(),
          0,
          0,
          0,
          0,
          1,
          1);
      ByteBuffer data = buffer(format.getVertexSize() * 3);
      float[][] corners = {{-1, -1}, {3, -1}, {-1, 3}};
      for (int i = 0; i < 3; i++) {
        int base = i * format.getVertexSize();
        int position = base + format.getElement("Position").offset();
        data.putFloat(position, corners[i][0])
            .putFloat(position + 4, corners[i][1])
            .putFloat(position + 8, 0);
        if (format.contains("Color")) data.putInt(base + format.getElement("Color").offset(), -1);
        int uv = base + format.getElement("UV0").offset();
        data.putFloat(uv, (corners[i][0] + 1) * .5f).putFloat(uv + 4, (corners[i][1] + 1) * .5f);
        if (!fullbright) {
          int light = base + format.getElement("UV2").offset();
          data.putShort(light, (short) 64).putShort(light + 2, (short) 128);
        }
      }
      encoder.writeToBuffer(vertices.slice(), data);
      ByteBuffer transform = buffer(160);
      new Matrix4f().get(0, transform);
      new Matrix4f().translation(.25f, 0, 0).get(64, transform);
      for (int i = 0; i < 4; i++) transform.putFloat(128 + i * 4, 1);
      encoder.writeToBuffer(dynamic.slice(), transform);
      ByteBuffer proj = buffer(64);
      new Matrix4f().get(0, proj);
      encoder.writeToBuffer(projection.slice(), proj);
      var values = program.uniforms().allocate();
      if (!handFixture(original)) {
        program.uniforms().putFloats(values, "sl_CameraEffect", new Matrix4f().get(new float[16]));
        program
            .uniforms()
            .putFloats(values, "sl_CameraEffectInverse", new Matrix4f().get(new float[16]));
      }
      program.uniforms().putFloats(values, "alphaTestRef", .1f);
      encoder.writeToBuffer(uniforms.slice(), values);
      try (var pass =
          encoder.createRenderPass(
              RenderPassDescriptor.builder(() -> "font semantics")
                  .withColorAttachment(color.level(0))
                  .withColorAttachment(diagnostics.level(0))
                  .build())) {
        pass.setPipeline(compiled);
        pass.setVertexBuffer(0, vertices.slice());
        pass.setUniform("DynamicTransforms", dynamic);
        pass.setUniform("Projection", projection);
        pass.setUniform(UniformLayout.BLOCK_NAME, uniforms);
        pass.setUniform("sl_texture", atlas.sampled, sampler);
        pass.setUniform("lightmap", unrelated.sampled, sampler);
        pass.draw(3, 1, 0, 0);
      }
      encoder.copyTextureToBuffer(color.texture, readback, 0, () -> {}, 0);
      encoder.copyTextureToBuffer(diagnostics.texture, readback, 16, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L)) throw new AssertionError("Font GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        var output = mapped.data().order(ByteOrder.nativeOrder());
        for (int x = 0; x < 4; x++) {
          int sampled = glint ? Math.min(x + 1, 3) : x;
          for (int channel = 0; channel < 4; channel++) {
            int expected =
                sampled == 0
                    ? (channel == 1 || channel == 3 ? 255 : 0)
                    : grayscale ? texels[sampled][0] : texels[sampled][channel];
            if (Byte.toUnsignedInt(output.get(x * 4 + channel)) != expected)
              throw new AssertionError(
                  original.getLocation() + " glyph coverage/color mismatch at" + x + "/" + channel);
          }
          for (int channel = 0; channel < 4; channel++) {
            float expected =
                sampled == 0
                    ? -1
                    : channel == 0
                        ? glint ? (x + .5f) / 4f + .25f : (fullbright ? 248 : 72) / 256f
                        : channel == 1
                            ? glint ? .5f : (fullbright ? 248 : 136) / 256f
                            : channel == 2 ? 211 / 255f : 1;
            if (Math.abs(output.getFloat(16 + x * 16 + channel * 4) - expected) > .00001f)
              throw new AssertionError(
                  original.getLocation()
                      + " lightmap/other-sampler/query mismatch at"
                      + x
                      + "/"
                      + channel);
          }
        }
      }
    }
  }

  private static ByteBuffer buffer(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}
