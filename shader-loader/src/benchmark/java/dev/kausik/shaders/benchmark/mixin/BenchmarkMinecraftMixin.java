package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.ShaderBenchmark;
import dev.kausik.shaders.benchmark.VisualComparison;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the normal game loop, including presentation/backpressure, without driving game ticks. */
@Mixin(Minecraft.class)
public abstract class BenchmarkMinecraftMixin {
  @Inject(
      method = "renderFrame",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
  private void benchmark$freezeRenderTime(boolean advanceTime, CallbackInfo callback) {
    VisualComparison.beforeWorldRender((Minecraft) (Object) this);
  }

  @Inject(method = "runTick", at = @At("HEAD"))
  private void benchmark$frameStart(boolean advanceTime, CallbackInfo callback) {
    ShaderBenchmark.frameStart((Minecraft) (Object) this, advanceTime);
  }

  @Inject(method = "runTick", at = @At("RETURN"))
  private void benchmark$frameEnd(boolean advanceTime, CallbackInfo callback) {
    ShaderBenchmark.frameEnd();
  }
}
