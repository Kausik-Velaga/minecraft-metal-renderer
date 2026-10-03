package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.kausik.shaders.compile.MinecraftVertexAdapter;
import dev.kausik.shaders.compile.UniformLayout;
import dev.kausik.shaders.geometry.FeatureDrawContext;
import dev.kausik.shaders.pack.AlphaTestPolicy;
import dev.kausik.shaders.pack.CustomUniforms;
import dev.kausik.shaders.pack.ShaderPack;
import dev.kausik.shaders.pack.ShaderProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.slf4j.LoggerFactory;

/** Owns the pack graph for one game instance. All GPU methods run on the render thread. */
public final class ShaderRuntime implements AutoCloseable {
  private static ShaderRuntime instance;
  private final ShaderPack pack;
  private final Map<String, String> options;
  private final ShaderProperties properties;
  private final FrameUniforms uniforms = new FrameUniforms();
  private final Map<Variant, PackPipeline> geometry = new HashMap<>();
  private final Map<String, PackPipeline> screens = new HashMap<>();
  private final Map<UniformKey, GpuBufferSlice> frameBuffers = new HashMap<>();
  private PackPrograms programs;
  private PackRenderTargets targets;
  private PackTextures textures;
  private PackMipmaps mipmaps;
  private PackDepthMerge depthMerge;
  private String dimension;
  private ShaderRenderPass openPass;
  private boolean active, hand, shadow, raw, deferred;

  public ShaderRuntime(ShaderPack pack, Map<String, String> options, ShaderProperties properties) {
    this.pack = pack;
    this.options = Map.copyOf(options);
    this.properties = properties;
  }

  public static void install(ShaderRuntime runtime) {
    instance = runtime;
  }

  public static ShaderRuntime get() {
    return instance;
  }

  public static boolean isRendering() {
    return instance != null && instance.active;
  }

  public GpuDevice device() {
    return RenderSystem.getDevice();
  }

  public FrameUniforms uniforms() {
    return uniforms;
  }

  public PackPrograms programs() {
    return programs;
  }

  public ShaderProperties properties() {
    return properties;
  }

  public PackRenderTargets targets() {
    return targets;
  }

  public void beginFrame() {
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.level == null) return;
    var main = minecraft.gameRenderer.mainRenderTarget();
    String nextDimension = minecraft.level.dimension().identifier().toString();
    try {
      if (programs == null || !nextDimension.equals(dimension)) {
        disposeGraph();
        programs = new PackPrograms(pack, options, nextDimension);
        uniforms.setCustomUniforms(CustomUniforms.compile(programs.properties));
        textures = new PackTextures(device(), pack, programs.properties);
        mipmaps = new PackMipmaps(device());
        depthMerge = new PackDepthMerge(device());
        dimension = nextDimension;
      }
      if (targets == null || targets.width != main.width || targets.height != main.height) {
        // Pipeline state depends on attachment formats, not the size of their textures.
        frameBuffers.clear();
        if (targets != null) targets.close();
        Map<Integer, PackRenderTargets.BufferSpec> specs = programs.bufferSpecifications();
        targets =
            new PackRenderTargets(
                device(),
                specs,
                main.width,
                main.height,
                programs.globals.intConstant("shadowMapResolution", 1024));
        // Allocate before any native scene pass opens, never in the middle of a draw batch.
        for (int index : specs.keySet()) targets.color(index);
        targets.shadowColor(0);
        targets.shadowColor(1);
        for (String phase : new String[] {"setup", "begin", "prepare", "deferred", "composite"}) {
          for (var source : programs.sequence(phase)) screenPipeline(source);
        }
        if (programs.find("final") != null) screenPipeline(programs.find("final"));
        LoggerFactory.getLogger("minecraft_shader_loader")
            .info(
                "Pack {} initialized in {} at {}x{}",
                pack.name(),
                nextDimension,
                main.width,
                main.height);
      }
      frameBuffers.clear();
      uniforms.beginFrame(minecraft, main.width, main.height, programs.globals);
      if (uniforms.historyReset())
        targets.resetHistory(
            minecraft.gameRenderer.gameRenderState()
                .levelRenderState
                .cameraRenderState
                .fogData
                .color);
      targets.beginFrame(
          minecraft.gameRenderer.gameRenderState()
              .levelRenderState
              .cameraRenderState
              .fogData
              .color);
      updateShadowMatrices();
      active = true;
      hand = false;
      shadow = false;
      deferred = false;
      execute("begin");
      PackPrograms.Source sky = programs.find("gbuffers_skybasic");
      if (sky != null)
        execute(
            screens.computeIfAbsent(
                "sky_background",
                ignored ->
                    PackPipeline.compile(
                        device(),
                        sky,
                        programs.geometry(sky, MinecraftVertexAdapter.skyBackground()),
                        null,
                        targets,
                        false,
                        false,
                        0,
                        PackEnvironment.stage("SKY"),
                        0)));
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot initialize shader pack " + pack.name(), failure);
    }
  }

  private void updateShadowMatrices() throws IOException {
    ShadowRenderer.updateMatrices(this);
  }

  public boolean isShadow() {
    return shadow;
  }

  public boolean beginShadow() {
    if (!active || programs.find("shadow") == null) return false;
    suspend();
    shadow = true;
    frameBuffers.clear();
    return true;
  }

  public void endShadow() {
    suspend();
    shadow = false;
    frameBuffers.clear();
    execute("prepare");
  }

  public void setProjection(Matrix4fc projection) {
    if (!active) return;
    uniforms.setProjection(projection);
    frameBuffers.clear();
  }

  public void beforeTranslucents() {
    if (!active || shadow || deferred) return;
    suspend();
    targets.snapshotOpaqueDepth();
    execute("deferred");
    deferred = true;
  }

  public void beginHand() {
    if (!active) return;
    beforeTranslucents();
    suspend();
    hand = true;
  }

  public void endFrame() {
    if (!active) return;
    try {
      suspend();
      beforeTranslucents();
      if (hand) depthMerge.merge(targets);
      execute("composite");
      PackPrograms.Source finalProgram = programs.find("final");
      if (finalProgram == null)
        throw new UnsupportedOperationException("Pack requires a final output program");
      execute(screenPipeline(finalProgram));
      uniforms.endFrame();
    } finally {
      active = false;
      hand = false;
      shadow = false;
      OriginalPipelines.prune();
    }
  }

  public void abortFrame() {
    suspend();
    active = false;
    hand = false;
    shadow = false;
  }

  public boolean intercept(RenderPassDescriptor descriptor) {
    if (!active || raw || descriptor.colorAttachments().isEmpty()) return false;
    var first = descriptor.colorAttachments().getFirst();
    return first != null
        && first.textureView().texture()
            == Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();
  }

  public void opened(ShaderRenderPass pass) {
    if (openPass != null) throw new IllegalStateException("Overlapping logical scene passes");
    openPass = pass;
  }

  public void closed(ShaderRenderPass pass) {
    if (openPass == pass) openPass = null;
  }

  public void suspend() {
    if (openPass != null) openPass.suspend();
  }

  public PackPipeline pipeline(RenderPipeline original) {
    boolean blockEntity =
        FeatureDrawContext.currentDrawTag().kind() == FeatureDrawContext.Kind.BLOCK_ENTITY;
    Variant key = new Variant(original, hand, shadow, blockEntity);
    return geometry.computeIfAbsent(
        key,
        variant -> {
          SceneProgram selection = SceneProgram.select(original, hand, shadow, blockEntity);
          var source = programs.resolve(selection.name());
          float alpha =
              original.getShaderDefines().values().containsKey("ALPHA_CUTOUT")
                  ? Float.parseFloat(original.getShaderDefines().values().get("ALPHA_CUTOUT"))
                  : 0.1f;
          dev.kausik.shaders.compile.TranslatedProgram translated;
          AlphaTestPolicy alphaTest;
          try {
            boolean waterMask = MinecraftVertexAdapter.isWaterMask(original);
            alphaTest =
                waterMask
                    ? AlphaTestPolicy.OFF
                    : AlphaTestPolicy.parse(
                        programs.properties.get("alphaTest." + source.name(), ""),
                        AlphaTestPolicy.greater(alpha));
            translated =
                programs.geometry(
                    source, MinecraftVertexAdapter.adapter(original, shadow, hand), alphaTest);
            if (waterMask) translated = MinecraftVertexAdapter.waterMaskProgram(translated);
          } catch (IOException failure) {
            throw new UncheckedIOException("Invalid alpha test for " + source.name(), failure);
          } catch (RuntimeException failure) {
            throw new IllegalStateException(
                "Cannot adapt Minecraft pipeline "
                    + original.getLocation()
                    + " to pack program "
                    + source.name(),
                failure);
          }
          int depthTarget = hand && !selection.name().equals("gbuffers_hand_water") ? 1 : 0;
          return PackPipeline.compile(
              device(),
              source,
              translated,
              original,
              targets,
              false,
              shadow,
              depthTarget,
              selection.stage(),
              alphaTest.reference());
        });
  }

  private PackPipeline screenPipeline(PackPrograms.Source source) {
    return screens.computeIfAbsent(
        source.name(),
        name ->
            PackPipeline.compile(
                device(),
                source,
                source.translated(),
                null,
                targets,
                name.equals("final"),
                false,
                0,
                0,
                0));
  }

  public void prepare(PackPipeline pipeline) {
    try {
      for (int index : pipeline.source().directives().mipmapBuffers())
        mipmaps.generate(targets.color(index));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  public RenderPass open(PackPipeline pipeline) {
    RenderPassDescriptor descriptor =
        pipeline.source().name().equals("final")
            ? RenderPassDescriptor.builder(() -> "Shader pack final")
                .withColorAttachment(
                    Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView())
                .build()
            : targets.descriptor(
                "Shader pack " + pipeline.source().name(),
                pipeline.translated().drawBuffers(),
                pipeline.fullscreen(),
                pipeline.shadow(),
                pipeline.depth());
    if (pipeline.depth() && !pipeline.shadow() && pipeline.depthTarget() != 0) {
      descriptor =
          new RenderPassDescriptor(
              descriptor.label(),
              descriptor.colorAttachments(),
              new RenderPassDescriptor.Attachment<>(
                  targets.depth(pipeline.depthTarget()).level(0), java.util.OptionalDouble.empty()),
              descriptor.renderArea());
    }
    raw = true;
    try {
      return device().createCommandEncoder().createRenderPass(descriptor);
    } finally {
      raw = false;
    }
  }

  public void bind(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    bindUniforms(pipeline, pass);
    bindTextures(pipeline, pass, supplied);
  }

  public void bindUniforms(PackPipeline pipeline, RenderPass pass) {
    if (pipeline.translated().uniforms().byteSize() > 0) {
      var tag =
          pipeline.fullscreen() ? FeatureDrawContext.NONE : FeatureDrawContext.currentDrawTag();
      GpuBufferSlice buffer =
          frameBuffers.computeIfAbsent(
              new UniformKey(pipeline, tag),
              key -> {
                uniforms.setDrawState(
                    pipeline.renderStage(),
                    pipeline.alphaTestRef(),
                    tag.entityId(),
                    tag.blockEntityId());
                uniforms.setEntityColor(tag.red(), tag.green(), tag.blue(), tag.alpha());
                try (var allocation =
                    device()
                        .createCommandEncoder()
                        .transientMemory()
                        .allocateGpuMapped(
                            pipeline.translated().uniforms().byteSize(),
                            256,
                            GpuBuffer.USAGE_UNIFORM)) {
                  uniforms.write(
                      pipeline.translated().uniforms(),
                      allocation.data(),
                      pipeline.fullscreen(),
                      pipeline.shadow());
                  return allocation.slice();
                }
              });
      pass.setUniform(UniformLayout.BLOCK_NAME, buffer);
    }
  }

  public void bindTextures(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied) {
    for (var sampler : pipeline.samplerBindings()) bindTexture(pipeline, pass, supplied, sampler);
  }

  /** Only aliases of a changed vanilla material texture need rebinding within a scene pass. */
  public void bindSuppliedTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      String changed) {
    for (var sampler : pipeline.samplerBindings()) {
      boolean affected =
          sampler.kind() == PackPipeline.SamplerKind.ALBEDO
                  && changed.equals(pipeline.primarySampler())
              || sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP && changed.equals("Sampler2");
      if (affected && textures.custom(pipeline.source().name(), sampler.name()) == null)
        bindTexture(pipeline, pass, supplied, sampler);
    }
  }

  private void bindTexture(
      PackPipeline pipeline,
      RenderPass pass,
      Map<String, ShaderRenderPass.TextureBinding> supplied,
      PackPipeline.SamplerBinding sampler) {
    PackTexture texture = textures.custom(pipeline.source().name(), sampler.name());
    GpuSampler filter = textures.linear;
    GpuTextureView view = null;
    if (texture == null) {
      switch (sampler.kind()) {
        case COLOR -> texture = targets.color(sampler.index());
        case DEPTH -> {
          texture = targets.depth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_DEPTH -> {
          texture = targets.shadowDepth(sampler.index());
          filter = textures.nearest;
        }
        case SHADOW_COLOR -> texture = targets.shadowColor(sampler.index());
        case NOISE -> {
          texture = textures.noise;
          filter = textures.repeat;
        }
        case NORMAL -> texture = textures.normal;
        case SPECULAR -> texture = textures.black;
        case ALBEDO, LIGHTMAP -> {
          var original =
              supplied.get(
                  sampler.kind() == PackPipeline.SamplerKind.LIGHTMAP
                      ? "Sampler2"
                      : pipeline.primarySampler());
          if (original != null && original.texture() != null) {
            view = original.texture();
            filter = original.sampler();
          } else texture = textures.white;
        }
        case CUSTOM ->
            throw new UnsupportedOperationException(
                "Unbound pack sampler " + sampler.name() + " in " + pipeline.source().name());
      }
    }
    if (view == null) view = texture.sampled;
    pass.setUniform(sampler.shaderName(), view, filter);
  }

  private void execute(String phase) {
    for (var source : programs.sequence(phase)) execute(screenPipeline(source));
  }

  private void execute(PackPipeline pipeline) {
    prepare(pipeline);
    try (RenderPass pass = open(pipeline)) {
      pass.setPipeline(pipeline.compiled());
      bind(pipeline, pass, Map.of());
      pass.draw(3, 1, 0, 0);
    }
    if (!pipeline.source().name().equals("final"))
      targets.flip(pipeline.translated().drawBuffers());
  }

  private void disposePipelines() {
    geometry.values().forEach(PackPipeline::close);
    geometry.clear();
    screens.values().forEach(PackPipeline::close);
    screens.clear();
    frameBuffers.clear();
  }

  private void disposeGraph() {
    disposePipelines();
    if (targets != null) {
      targets.close();
      targets = null;
    }
    if (textures != null) {
      textures.close();
      textures = null;
    }
    if (mipmaps != null) {
      mipmaps.close();
      mipmaps = null;
    }
    if (depthMerge != null) {
      depthMerge.close();
      depthMerge = null;
    }
    if (programs != null) {
      programs.close();
      programs = null;
    }
  }

  @Override
  public void close() {
    active = false;
    suspend();
    disposeGraph();
    OriginalPipelines.clear();
  }

  private record Variant(
      RenderPipeline original, boolean hand, boolean shadow, boolean blockEntity) {}

  private record UniformKey(PackPipeline pipeline, FeatureDrawContext.DrawTag tag) {
    @Override
    public int hashCode() {
      return 31 * System.identityHashCode(pipeline) + tag.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof UniformKey key && pipeline == key.pipeline && tag.equals(key.tag);
    }
  }
}
