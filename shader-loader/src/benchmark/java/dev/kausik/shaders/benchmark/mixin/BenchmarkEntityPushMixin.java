package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.ShaderBenchmark;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops mobs displacing the player during warmup and the stationary cavern mining fixture. */
@Mixin(Entity.class)
public abstract class BenchmarkEntityPushMixin {
  @Inject(
      method = "push(Lnet/minecraft/world/entity/Entity;)V",
      at = @At("HEAD"),
      cancellable = true)
  private void benchmark$protectPreparationPose(Entity other, CallbackInfo callback) {
    if (ShaderBenchmark.protectsPreparationPlayer((Entity) (Object) this, other)) callback.cancel();
  }
}
