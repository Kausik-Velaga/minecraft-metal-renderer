package dev.kausik.sceneoptimizer.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Disabled controls do not install constructor wrappers or their operation/boxing overhead. */
public final class SceneOptimizerMixinPlugin implements IMixinConfigPlugin {
  private static final Set<String> DRAW_METADATA_MIXINS =
      Set.of(
          "CompiledMeshDrawsMixin",
          "RenderSectionInfoMixin",
          "TerrainAllocationEventsMixin",
          "LevelRendererDrawMetadataMixin");

  @Override
  public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
    if (name.equals("LevelRendererDrawMetadataMixin")
        && Boolean.getBoolean("minecraftScene.drawWorkCounters")) return true;
    return !DRAW_METADATA_MIXINS.contains(name)
        || Boolean.getBoolean("minecraftScene.drawMetadataCache");
  }

  @Override
  public void onLoad(String mixinPackage) {}

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

  @Override
  public List<String> getMixins() {
    return null;
  }

  @Override
  public void preApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

  @Override
  public void postApply(
      String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
