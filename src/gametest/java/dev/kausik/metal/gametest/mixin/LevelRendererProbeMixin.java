package dev.kausik.metal.gametest.mixin;

import dev.kausik.metal.gametest.MetalGameplayTest;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation of the actual depth-only boat water-mask rendering path. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererProbeMixin {
  @Inject(method = "executeOitWaterMask", at = @At("TAIL"))
  private void metalTest$waterMaskRecorded(CallbackInfo callback) {
    MetalGameplayTest.recordWaterMaskPass();
  }
}
