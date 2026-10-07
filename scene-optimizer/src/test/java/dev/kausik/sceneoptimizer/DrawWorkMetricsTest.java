package dev.kausik.sceneoptimizer;

import dev.kausik.sceneoptimizer.mixin.SceneOptimizerMixinPlugin;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

final class DrawWorkMetricsTest {
  private DrawWorkMetricsTest() {}

  static void run() {
    var totals = new DrawWorkMetrics.Accumulator();
    totals.record(null, ChunkSectionLayer.SOLID, 12, 1);
    totals.record(null, ChunkSectionLayer.SOLID, 12, 1);
    totals.record("shadow-render", ChunkSectionLayer.CUTOUT, 30, 1);
    totals.record("shadow-render", ChunkSectionLayer.TRANSLUCENT, 6, 2);
    totals.record(null, ChunkSectionLayer.CUTOUT, 0, 1);
    totals.record(null, ChunkSectionLayer.CUTOUT, 6, 0);
    var before = totals.snapshot(true);
    check(before.preparedDraws().get("camera.solid") == 2
            && before.preparedIndices().get("camera.solid") == 24,
        "Repeated prepared descriptors were not counted independently");
    check(before.preparedDraws().get("shadow.cutout") == 1
            && before.preparedIndices().get("shadow.cutout") == 30
            && before.preparedIndices().get("shadow.translucent") == 12
            && before.preparedDraws().get("camera.cutout") == 0,
        "View/layer/empty/instance accounting changed");
    for (int i = 0; i < 100; i++) totals.record("unknown-" + i, ChunkSectionLayer.SOLID, 6, 1);
    totals.record("unknown", ChunkSectionLayer.CUTOUT, Integer.MAX_VALUE, 2);
    var after = totals.snapshot(true);
    check(after.preparedDraws().size() == 9 && after.preparedIndices().size() == 9,
        "Arbitrary view names grew retained history");
    check(after.preparedDraws().get("other.solid") == 100
            && after.preparedIndices().get("other.cutout") == 2L * Integer.MAX_VALUE
            && before.preparedDraws().get("other.solid") == 0,
        "Cumulative totals overflowed or changed an earlier snapshot");
    try {
      after.preparedDraws().clear();
      throw new AssertionError("Mutable draw-work snapshot");
    } catch (UnsupportedOperationException expected) {
      // A benchmark baseline remains a stable value while rendering continues.
    }
    pluginWithoutCache();
  }

  private static void pluginWithoutCache() {
    String cache = System.getProperty("minecraftScene.drawMetadataCache");
    String counters = System.getProperty("minecraftScene.drawWorkCounters");
    try {
      System.setProperty("minecraftScene.drawMetadataCache", "false");
      System.setProperty("minecraftScene.drawWorkCounters", "true");
      var plugin = new SceneOptimizerMixinPlugin();
      check(plugin.shouldApplyMixin("ignored", "test.LevelRendererDrawMetadataMixin"),
          "Draw-work diagnostics require the independent metadata-cache flag");
      check(!plugin.shouldApplyMixin("ignored", "test.CompiledMeshDrawsMixin"),
          "Counters unexpectedly enabled mesh retention");
      System.setProperty("minecraftScene.drawWorkCounters", "false");
      check(!plugin.shouldApplyMixin("ignored", "test.LevelRendererDrawMetadataMixin"),
          "Disabled diagnostics retained constructor wrappers");
    } finally {
      restore("minecraftScene.drawMetadataCache", cache);
      restore("minecraftScene.drawWorkCounters", counters);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
