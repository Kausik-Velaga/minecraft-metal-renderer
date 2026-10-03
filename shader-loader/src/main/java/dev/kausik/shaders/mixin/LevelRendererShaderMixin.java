package dev.kausik.shaders.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.kausik.shaders.runtime.ShaderRuntime;
import dev.kausik.shaders.runtime.ShadowRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places true shadow and deferred passes at the boundaries of Minecraft's scene graph. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererShaderMixin implements ShadowRenderer.LevelAccess {
  @Shadow private ViewArea viewArea;

  @Override
  public ViewArea shaders$viewArea() {
    return viewArea;
  }

  @Inject(method = "addMainPass", at = @At("HEAD"))
  private void shaders$renderShadows(
      FrameGraphBuilder frame,
      FeatureRenderDispatcher.PreparedFrame features,
      GpuBufferSlice terrainFog,
      ChunkSectionsToRender sceneChunks,
      boolean consistentDepthRequired,
      CallbackInfo callback) {
    if (ShaderRuntime.isRendering()) {
      ShadowRenderer.render((LevelRenderer) (Object) this, viewArea, features, terrainFog);
    }
  }

  @Inject(method = "executeClassicTransparency", at = @At("HEAD"))
  private void shaders$deferredBeforeTranslucents(
      ChunkSectionsToRender chunks,
      FeatureRenderDispatcher.PreparedFrame features,
      RenderPass pass,
      CallbackInfo callback) {
    if (ShaderRuntime.isRendering()) ShaderRuntime.get().beforeTranslucents();
  }
}
