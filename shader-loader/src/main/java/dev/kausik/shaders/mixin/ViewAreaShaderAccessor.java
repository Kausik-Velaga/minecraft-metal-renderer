package dev.kausik.shaders.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Enumerates loaded sections without allocating or looking up a cubic grid each frame. */
@Mixin(ViewArea.class)
public interface ViewAreaShaderAccessor {
  @Accessor("sections")
  RotatingSectionStorage<SectionRenderDispatcher.RenderSection> shaders$sections();
}
