package dev.kausik.shaders.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.kausik.shaders.mixin.ViewAreaShaderAccessor;
import dev.kausik.shaders.pack.ShaderPackException;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Reuses uploaded vanilla chunk meshes and prepared features from the light's point of view. */
public final class ShadowRenderer {
  private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> extractionCache =
      new ObjectArrayList<>();
  private static final Set<SectionRenderDispatcher.RenderSection> extractionIncluded =
      new HashSet<>();
  private static LevelRenderer extractionRenderer;

  private ShadowRenderer() {}

  public interface LevelAccess {
    ViewArea shaders$viewArea();
  }

  public static void updateMatrices(ShaderRuntime runtime) throws ShaderPackException {
    float distance = runtime.programs().globals.floatConstant("shadowDistance", 160);
    if (!(distance > 0)) throw new ShaderPackException("Shadow distance must be positive");
    Matrix4f view = lightView(runtime.uniforms().shadowDirectionWorld(), distance);
    float interval = runtime.programs().globals.floatConstant("shadowIntervalSize", 2);
    var camera =
        Minecraft.getInstance()
            .gameRenderer
            .gameRenderState()
            .levelRenderState
            .cameraRenderState
            .pos;
    if (interval > 0) {
      // World-anchored sampling prevents every tiny camera movement from sliding the shadow grid.
      view.translate(
          (float) (camera.x - Math.floor(camera.x / interval) * interval),
          (float) (camera.y - Math.floor(camera.y / interval) * interval),
          (float) (camera.z - Math.floor(camera.z / interval) * interval));
    }
    Matrix4f projection =
        new Matrix4f().ortho(-distance, distance, -distance, distance, 0.05f, distance * 4);
    runtime.uniforms().setShadowMatrices(projection, view);
  }

  /** Never choose another light here: the uniform and the depth map must describe the same one. */
  static Matrix4f lightView(Vector3fc direction, float distance) {
    Vector3f up = Math.abs(direction.y()) > 0.99f ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
    return new Matrix4f()
        .lookAt(
            direction.x() * distance * 2,
            direction.y() * distance * 2,
            direction.z() * distance * 2,
            0,
            0,
            0,
            up.x,
            up.y,
            up.z);
  }

  public static Frustum frustum() {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime == null || runtime.programs() == null || runtime.programs().find("shadow") == null)
      return null;
    Frustum result =
        new Frustum(
            runtime.uniforms().shadowModelView(),
            new Matrix4f(runtime.uniforms().shadowProjection()));
    var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
    result.prepare(camera.x, camera.y, camera.z);
    return result;
  }

  public static void beginExtraction() {
    extractionRenderer = null;
    extractionCache.clear();
    extractionIncluded.clear();
  }

  public static Frustum withShadowFrustum(Frustum cameraFrustum) {
    Frustum light = frustum();
    if (light == null) return cameraFrustum;
    return new Frustum(cameraFrustum) {
      @Override
      public boolean isVisible(AABB bounds) {
        return cameraFrustum.isVisible(bounds) || light.isVisible(bounds.inflate(16));
      }
    };
  }

  /** Include off-camera casters in normal asynchronous chunk compilation and feature extraction. */
  public static ObjectArrayList<SectionRenderDispatcher.RenderSection> extractionSections(
      LevelRenderer renderer) {
    var normal = renderer.visibleSections();
    Frustum shadowFrustum = frustum();
    ViewArea area = ((LevelAccess) renderer).shaders$viewArea();
    if (shadowFrustum == null || area == null) return normal;
    if (extractionRenderer == renderer) return extractionCache;
    extractionCache.addAll(normal);
    extractionIncluded.addAll(normal);
    for (var section : ((ViewAreaShaderAccessor) area).shaders$sections()) {
      // One section of padding covers extraction/render camera motion without dropping casters.
      if (!extractionIncluded.contains(section)
          && shadowFrustum.isVisible(section.getBoundingBox().inflate(16))) {
        extractionCache.add(section);
      }
    }
    extractionRenderer = renderer;
    return extractionCache;
  }

  public static void render(
      LevelRenderer renderer,
      ViewArea area,
      FeatureRenderDispatcher.PreparedFrame features,
      GpuBufferSlice terrainFog) {
    ShaderRuntime runtime = ShaderRuntime.get();
    if (runtime == null || area == null || !runtime.beginShadow()) return;
    try {
      Frustum lightFrustum = frustum();
      var visible = renderer.visibleSections();
      var saved = new ObjectArrayList<>(visible);
      ChunkSectionsToRender chunks;
      try {
        visible.clear();
        for (var section : ((ViewAreaShaderAccessor) area).shaders$sections()) {
          if (section.getSectionMesh().hasRenderableLayers()
              && lightFrustum.isVisible(section.getBoundingBox())) {
            visible.add(section);
          }
        }
        // Adapter converts main-view geometry to light space. Supplying light space here doubles
        // it.
        chunks =
            renderer.isChunkRenderingUsingMultiDrawIndirect()
                ? renderer.prepareChunkRendersIndirect(runtime.uniforms().modelView(), false)
                : renderer.prepareChunkRenders(runtime.uniforms().modelView(), false);
      } finally {
        visible.clear();
        visible.addAll(saved);
      }
      var minecraft = Minecraft.getInstance();
      var atlas =
          minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
      var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
      RenderSystem.setShaderFog(terrainFog);
      try (ShaderRenderPass pass = new ShaderRenderPass(runtime)) {
        RenderSystem.bindDefaultUniforms(pass);
        chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, atlas, false);
        features.executeSolid(pass);
        pass.suspend();
        runtime.targets().copyShadowDepth();
        chunks.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, pass, sampler, atlas, false);
        features.executeTranslucent(pass);
        features.executeTranslucentAfterTerrain(pass);
      }
    } finally {
      runtime.endShadow();
    }
  }

  /** UI-like scene features and vanilla blob shadows are not sunlight occluders. */
  public static boolean skipPipeline(RenderPipeline pipeline) {
    String path = pipeline.getLocation().getPath();
    path = path.substring(path.lastIndexOf('/') + 1);
    return path.contains("entity_shadow")
        || path.contains("text")
        || path.contains("outline")
        || path.contains("lines")
        || path.contains("line_strip")
        || path.contains("debug")
        || path.contains("particle")
        || path.contains("beacon_beam")
        || path.contains("glint")
        || path.contains("water_mask")
        || path.contains("weather")
        || path.contains("world_border")
        || path.contains("sky")
        || path.contains("cloud");
  }
}
