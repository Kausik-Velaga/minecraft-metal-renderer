package dev.kausik.metal.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.kausik.metal.MetalBackend;
import dev.kausik.metal.MetalMod;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Select Metal before SDL window creation; normal game rendering uses RenderPearl. */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {
  @Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
  private void minecraftMetal$selectBackend(CallbackInfoReturnable<GpuBackend[]> callback) {
    if (MetalMod.isEnabled()) {
      callback.setReturnValue(new GpuBackend[] {new MetalBackend()});
    }
  }
}
