package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.VisualComparison;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional loader target: renderer-only benchmark runs do not contain FrameUniforms. */
@Pseudo
@Mixin(targets = "dev.kausik.shaders.runtime.FrameUniforms", remap = false)
public abstract class VisualFrameUniformsMixin {
  @Shadow private boolean hasHistory;
  @Shadow private long previousNanos;
  @Shadow private long startedNanos;
  @Shadow private int frameCounter;

  @Inject(method = "beginFrame", at = @At("HEAD"))
  private void benchmark$resetHistory(CallbackInfo callback) {
    if (!VisualComparison.consumeHistoryReset()) return;
    hasHistory = false;
    previousNanos = 0;
    startedNanos = VisualComparison.shaderEpoch();
    frameCounter = 0;
  }

  @Redirect(
      method = "beginFrame",
      at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"))
  private long benchmark$shaderClock() {
    return VisualComparison.shaderNanoTime();
  }

  @Inject(method = "endFrame", at = @At("RETURN"))
  private void benchmark$completedFrame(CallbackInfo callback) {
    VisualComparison.packFrameCompleted();
  }
}
