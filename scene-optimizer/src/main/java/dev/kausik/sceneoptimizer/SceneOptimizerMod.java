package dev.kausik.sceneoptimizer;

import dev.kausik.scene.SceneViews;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.LoggerFactory;

/** Optional scene preparation implementation, independent of shader-pack interpretation. */
public final class SceneOptimizerMod implements ClientModInitializer {
  private static final SpatialSceneProvider PROVIDER = new SpatialSceneProvider();

  @Override
  public void onInitializeClient() {
    SceneViews.register(PROVIDER);
    LoggerFactory.getLogger("minecraft_scene_optimizer")
        .info("Scene optimizer ready: bounded spatial-index worker, view selection and retained layer sets.");
  }

  public static SpatialSceneProvider.Stats stats() {
    return PROVIDER.stats();
  }

  public static DrawMetadataMetrics.Stats drawMetadataStats() {
    return DrawMetadataMetrics.snapshot();
  }

  public static DrawWorkMetrics.Stats drawWorkStats() {
    return DrawWorkMetrics.snapshot();
  }
}
