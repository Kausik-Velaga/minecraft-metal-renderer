package dev.kausik.shaders.benchmark.mixin;

import dev.kausik.shaders.runtime.FrameUniforms;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the exact FP32 matrix values published to the pack. */
@Mixin(value = FrameUniforms.class, remap = false)
public interface FrameUniformsMatricesAccessor {
  @Accessor("matrices")
  Map<String, float[]> benchmark$matrices();

  @Accessor("scalars")
  Map<String, Double> benchmark$scalars();
}
