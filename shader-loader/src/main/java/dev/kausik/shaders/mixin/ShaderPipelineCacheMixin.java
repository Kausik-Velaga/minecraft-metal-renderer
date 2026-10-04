package dev.kausik.shaders.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import dev.kausik.shaders.runtime.ScenePipelineCompilation;
import dev.kausik.shaders.runtime.ShaderRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Covers both resource-pack and fallback caches, including lazily created pipeline variants. */
@Mixin(PipelineCache.class)
public abstract class ShaderPipelineCacheMixin {
  @WrapOperation(
      method = "get",
      at = @At(
          value = "INVOKE",
          target = "Lcom/mojang/renderpearl/api/device/GpuDevice;compilePipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/pipeline/ShaderSource;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
  private CompletableFuture<CompiledRenderPipeline.Pending> shaders$compileSceneMiss(
      GpuDevice device,
      RenderPipeline pipeline,
      ShaderSource source,
      Executor background,
      Operation<CompletableFuture<CompiledRenderPipeline.Pending>> original) {
    Executor executor = ScenePipelineCompilation.executor(
        background, ShaderRuntime.isRendering(), RenderSystem.isOnRenderThread());
    if (executor != background && Boolean.getBoolean("minecraftShaders.logPipelineMisses")) {
      LoggerFactory.getLogger("minecraft_shader_loader")
          .info("Compiling scene cache miss inline: {} (defines={})",
              pipeline.getLocation(), pipeline.getShaderDefines());
    }
    return original.call(device, pipeline, source, executor);
  }
}
