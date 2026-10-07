package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.benchmark.VisualComparison;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureAtlas.class)
public abstract class VisualAtlasMixin implements VisualComparison.AtlasControl {
  @Shadow private List<SpriteContents.AnimationState> animatedTexturesStates;

  @Shadow
  protected abstract void uploadAnimationFrames();

  public int benchmark$resetAnimations() {
    for (var animation : animatedTexturesStates)
      ((VisualComparison.AnimationControl) animation).benchmark$reset();
    // Reuse Minecraft's upload for every mip, including interpolated animations at subframe zero.
    uploadAnimationFrames();
    return animatedTexturesStates.size();
  }

  public void benchmark$assertAnimationsReset() {
    for (var animation : animatedTexturesStates)
      if (!((VisualComparison.AnimationControl) animation).benchmark$isReset())
        throw new IllegalStateException("Atlas animation advanced during frozen visual comparison");
  }
}
