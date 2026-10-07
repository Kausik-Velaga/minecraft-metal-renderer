package dev.kausik.shaders.benchmark.mixin;

import java.util.Map;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextureManager.class)
public interface BenchmarkTextureManagerAccessor {
  @Accessor("byPath")
  Map<Identifier, AbstractTexture> benchmark$textures();
}
