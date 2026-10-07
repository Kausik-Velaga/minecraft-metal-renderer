package dev.kausik.sceneoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kausik.scene.ScopedSectionSelection;
import dev.kausik.sceneoptimizer.DrawMetadataMetrics;
import dev.kausik.sceneoptimizer.DrawWorkMetrics;
import dev.kausik.sceneoptimizer.PreparedMeshDraws;
import dev.kausik.sceneoptimizer.PreparedSectionInfo;
import net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps vanilla ordering, grouping, readiness checks, fade calculation and GPU lifetime handling.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDrawMetadataMixin {
  @WrapOperation(
      method = "extractSectionDrawGroups",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"))
  private RenderSectionBufferSlice scene$retainedSlice(
      SectionRenderDispatcher dispatcher,
      SectionMesh mesh,
      ChunkSectionLayer layer,
      Operation<RenderSectionBufferSlice> original) {
    if (!DrawMetadataMetrics.ENABLED
        || mesh.getClass() != CompiledSectionMesh.class
        || !(mesh instanceof PreparedMeshDraws prepared))
      return original.call(dispatcher, mesh, layer);
    var cache = prepared.scene$drawCache();
    var snapshot = cache.allocations();
    var retained = cache.findSlice(snapshot, dispatcher, layer);
    if (retained != null) return retained.slice();
    var result = original.call(dispatcher, mesh, layer);
    cache.rememberSlice(snapshot, dispatcher, layer, result);
    return result;
  }

  @WrapOperation(
      method = "extractSectionDrawGroups",
      at = @At(value = "NEW", target = "net/minecraft/client/renderer/DynamicGpuData$IndexedDraw"))
  private IndexedDraw scene$retainedDraw(
      int indexCount,
      int instanceCount,
      int firstIndex,
      int baseVertex,
      int baseInstance,
      Operation<IndexedDraw> original,
      @Local SectionMesh mesh,
      @Local ChunkSectionLayer layer) {
    if (DrawWorkMetrics.ENABLED) {
      DrawWorkMetrics.record(
          ScopedSectionSelection.viewId((LevelRenderer) (Object) this),
          layer, indexCount, instanceCount);
    }
    if (!DrawMetadataMetrics.ENABLED
        || mesh.getClass() != CompiledSectionMesh.class
        || !(mesh instanceof PreparedMeshDraws prepared)) {
      return original.call(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
    }
    var cache = prepared.scene$drawCache();
    var retained =
        cache.findDraw(layer, indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
    if (retained != null) return retained;
    var result = original.call(indexCount, instanceCount, firstIndex, baseVertex, baseInstance);
    cache.rememberDraw(layer, result);
    return result;
  }

  @WrapOperation(
      method = "extractSectionDrawGroups",
      at =
          @At(
              value = "NEW",
              target = "net/minecraft/client/renderer/DynamicGpuData$ChunkSectionInfo"))
  private ChunkSectionInfo scene$retainedSectionInfo(
      int x,
      int y,
      int z,
      float visibility,
      Operation<ChunkSectionInfo> original,
      @Local RenderSection section) {
    if (!DrawMetadataMetrics.ENABLED
        || section.getClass() != RenderSection.class
        || !(section instanceof PreparedSectionInfo prepared))
      return original.call(x, y, z, visibility);
    var cache = prepared.scene$sectionInfoCache();
    var retained = cache.find(x, y, z, visibility);
    if (retained != null) return retained;
    var result = original.call(x, y, z, visibility);
    cache.remember(result);
    return result;
  }
}
