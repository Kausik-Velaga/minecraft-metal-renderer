package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.ShaderBenchmark;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Disposable benchmark clients receive scripted input only. Never packaged with either mod. */
@Mixin(MouseHandler.class)
public abstract class BenchmarkMouseInputMixin {
  @Inject(
      method = {"onMove", "onButton", "onScroll"},
      at = @At("HEAD"),
      cancellable = true)
  private void benchmark$physicalInput(CallbackInfo ci) {
    if (ShaderBenchmark.ownsInput()) ci.cancel();
  }
}
