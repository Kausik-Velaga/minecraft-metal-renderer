package dev.kausik.metal;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Optional, read-only reflection facts for clients of the Metal renderer. */
public final class MetalPipelineResources {
  private MetalPipelineResources() {}

  /**
   * Returns an immutable snapshot of names used by either compiled shader stage. An empty Optional
   * means this pipeline's backend is unknown: callers must retain their dependencies. A present
   * empty set means the known pipeline uses no named resources. Reflection uncertainty in the Metal
   * compiler conservatively marks every declared resource active.
   *
   * <p>This does not change binding slots or the frontend's declared-binding validation contract.
   */
  public static Optional<Set<String>> activeResourceNames(CompiledRenderPipeline pipeline) {
    if (!(pipeline instanceof FrontendRenderPipeline frontend)
        || !(frontend.backendRenderPipeline() instanceof MetalRenderPipeline metal))
      return Optional.empty();
    return Optional.of(
        metal.bindings().stream()
            .filter(binding -> binding.stageMask() != 0)
            .map(MetalRenderPipeline.Binding::name)
            .collect(Collectors.toUnmodifiableSet()));
  }
}
