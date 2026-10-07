package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.VisualComparison;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(SpriteContents.AnimationState.class)
public abstract class VisualAnimationMixin implements VisualComparison.AnimationControl {
  @Shadow private int frame;
  @Shadow private int subFrame;
  @Shadow private boolean isDirty;

  public void benchmark$reset() {
    frame = 0;
    subFrame = 0;
    isDirty = true;
  }

  public boolean benchmark$isReset() {
    return frame == 0 && subFrame == 0;
  }
}
