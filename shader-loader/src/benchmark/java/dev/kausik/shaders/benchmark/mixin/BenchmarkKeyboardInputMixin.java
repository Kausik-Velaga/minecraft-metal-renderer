package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.ShaderBenchmark;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** KeyMapping updates from the benchmark still take Minecraft's ordinary player input path. */
@Mixin(KeyboardHandler.class)
public abstract class BenchmarkKeyboardInputMixin {
  @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
  private void benchmark$physicalInput(CallbackInfo ci) {
    if (ShaderBenchmark.ownsInput()) ci.cancel();
  }
}
