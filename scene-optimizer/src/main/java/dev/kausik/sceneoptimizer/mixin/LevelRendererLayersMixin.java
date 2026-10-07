package dev.kausik.sceneoptimizer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.kausik.sceneoptimizer.PreparedSectionLayers;
import dev.kausik.sceneoptimizer.SectionLayerSets;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies to ordinary and pack views and to both vanilla direct and indirect draw preparation. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererLayersMixin {
  @WrapOperation(
      method = "extractSectionDrawGroups",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"))
  private ChunkSectionLayer[] scene$preparedLayers(
      Operation<ChunkSectionLayer[]> original, @Local SectionMesh mesh) {
    if (mesh.getClass() == CompiledSectionMesh.class
        && mesh instanceof PreparedSectionLayers prepared) return prepared.scene$drawLayers();
    if (mesh == CompiledSectionMesh.EMPTY || mesh == CompiledSectionMesh.UNCOMPILED) {
      return SectionLayerSets.forMask(0);
    }
    // Other mods can provide mutable/custom meshes; their behavior does not inherit our proof.
    return original.call();
  }
}
