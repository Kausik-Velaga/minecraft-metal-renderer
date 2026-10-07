package dev.kausik.sceneoptimizer;

import net.minecraft.client.renderer.DynamicGpuData.ChunkSectionInfo;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;

/** Exact-key, allocation-generation and lifecycle tests; does not claim to exercise GPU hooks. */
public final class DrawMetadataCacheTest {
  public static void main(String[] args) {
    run();
    System.out.println(
        "Draw metadata cache passed: exact keys, null allocation misses, replacement,"
            + " stale publication, bounded retention and disposal.");
  }

  static void run() {
    var before = DrawMetadataMetrics.snapshot();
    testAllocationReplacementAndNullMisses();
    testEveryDrawArgument();
    testTwoViewsAndBoundedHistory();
    testSectionPositionAndFade();
    var after = DrawMetadataMetrics.snapshot();
    if (after.retainedSliceEntries() != before.retainedSliceEntries()
        || after.retainedDrawEntries() != before.retainedDrawEntries()
        || after.retainedSectionInfos() != before.retainedSectionInfos()) {
      throw new AssertionError("Closing caches retained metadata or allocation references");
    }
  }

  private static void testAllocationReplacementAndNullMisses() {
    Object firstDispatcher = new Object();
    Object secondDispatcher = new Object();
    var layer = ChunkSectionLayer.TRANSLUCENT;
    // These are metadata sentinels, not GPU buffers or rendering evidence.
    var oldSlice = new RenderSectionBufferSlice(null, 16, null, 32);
    var newSlice = new RenderSectionBufferSlice(null, 48, null, 96);
    try (var cache = new MeshDrawCache()) {
      var empty = cache.allocations();
      cache.rememberSlice(empty, firstDispatcher, layer, null);
      var missing = cache.findSlice(cache.allocations(), firstDispatcher, layer);
      if (missing == null || missing.slice() != null) {
        throw new AssertionError("A known missing allocation was not retained");
      }
      cache.invalidateAllocations();
      if (cache.findSlice(cache.allocations(), firstDispatcher, layer) != null) {
        throw new AssertionError("Publishing a new allocation did not invalidate a null miss");
      }
      cache.rememberSlice(cache.allocations(), firstDispatcher, layer, oldSlice);
      if (cache.findSlice(cache.allocations(), firstDispatcher, layer).slice() != oldSlice) {
        throw new AssertionError("Stable allocation slice was not reused");
      }
      var beforeResort = cache.allocations();
      cache.invalidateAllocations();
      cache.rememberSlice(beforeResort, firstDispatcher, layer, oldSlice);
      if (cache.findSlice(cache.allocations(), firstDispatcher, layer) != null) {
        throw new AssertionError("An obsolete lookup republished a freed/resorted allocation");
      }
      cache.rememberSlice(cache.allocations(), firstDispatcher, layer, newSlice);
      if (cache.findSlice(cache.allocations(), firstDispatcher, layer).slice() != newSlice) {
        throw new AssertionError("Resort replacement reused the old slice");
      }
      if (cache.findSlice(cache.allocations(), secondDispatcher, layer) != null) {
        throw new AssertionError("Another dispatcher reused the first dispatcher's allocation");
      }
      cache.rememberSlice(cache.allocations(), secondDispatcher, layer, oldSlice);
      if (cache.findSlice(cache.allocations(), firstDispatcher, layer) != null) {
        throw new AssertionError("Changing dispatchers left old allocation ownership reachable");
      }
      cache.close();
      if (cache.findSlice(cache.allocations(), secondDispatcher, layer) != null) {
        throw new AssertionError("Disposal retained a slice");
      }
    }
  }

  private static void testEveryDrawArgument() {
    var layer = ChunkSectionLayer.CUTOUT;
    try (var cache = new MeshDrawCache()) {
      var expected = new IndexedDraw(180, 1, 32, 64, 17);
      cache.rememberDraw(layer, expected);
      if (cache.findDraw(layer, 180, 1, 32, 64, 17) != expected) {
        throw new AssertionError("An exact immutable record was not reused");
      }
      if (cache.findDraw(layer, 181, 1, 32, 64, 17) != null
          || cache.findDraw(layer, 180, 2, 32, 64, 17) != null
          || cache.findDraw(layer, 180, 1, 33, 64, 17) != null
          || cache.findDraw(layer, 180, 1, 32, 65, 17) != null
          || cache.findDraw(layer, 180, 1, 32, 64, 18) != null
          || cache.findDraw(ChunkSectionLayer.SOLID, 180, 1, 32, 64, 17) != null) {
        throw new AssertionError("A draw key omitted an argument or layer");
      }
    }
  }

  private static void testTwoViewsAndBoundedHistory() {
    long start = DrawMetadataMetrics.snapshot().retainedDrawEntries();
    try (var cache = new MeshDrawCache()) {
      for (var layer : ChunkSectionLayer.values()) {
        var camera = new IndexedDraw(300, 1, 10, 400, 9);
        var shadow = new IndexedDraw(300, 1, 10, 400, 127);
        cache.rememberDraw(layer, camera);
        cache.rememberDraw(layer, shadow);
        for (int frame = 0; frame < 20; frame++) {
          if (cache.findDraw(layer, 300, 1, 10, 400, 9) != camera
              || cache.findDraw(layer, 300, 1, 10, 400, 127) != shadow) {
            throw new AssertionError("Alternating view instance IDs invalidated each other");
          }
        }
        for (int membership = 0; membership < 1000; membership++) {
          cache.rememberDraw(layer, new IndexedDraw(300, 1, membership, 400, membership));
        }
        if (cache.findDraw(layer, 300, 1, 10, 400, 9) != null) {
          throw new AssertionError("Unbounded old view history was retained");
        }
      }
      if (DrawMetadataMetrics.snapshot().retainedDrawEntries() - start
          != 2L * ChunkSectionLayer.values().length) {
        throw new AssertionError("Draw-record history exceeded two entries per layer");
      }
    }
  }

  private static void testSectionPositionAndFade() {
    try (var cache = new SectionInfoCache()) {
      var steady = new ChunkSectionInfo(16, -64, 32, 1);
      cache.remember(steady);
      if (cache.find(16, -64, 32, 1) != steady) throw new AssertionError("Stable info missed");
      if (cache.find(32, -64, 32, 1) != null
          || cache.find(16, -48, 32, 1) != null
          || cache.find(16, -64, 48, 1) != null
          || cache.find(16, -64, 32, 0.9f) != null) {
        throw new AssertionError("Section movement or live fade was omitted from the key");
      }
      cache.remember(new ChunkSectionInfo(0, 0, 0, -0.0f));
      if (cache.find(0, 0, 0, 0.0f) != null) throw new AssertionError("Signed zero bits changed");
      float firstNan = Float.intBitsToFloat(0x7fc00001);
      float secondNan = Float.intBitsToFloat(0x7fc00002);
      cache.remember(new ChunkSectionInfo(0, 0, 0, firstNan));
      if (cache.find(0, 0, 0, firstNan) == null || cache.find(0, 0, 0, secondNan) != null) {
        throw new AssertionError("Visibility payload was not preserved exactly");
      }
      cache.close();
      if (cache.find(0, 0, 0, firstNan) != null) throw new AssertionError("Reset retained info");
    }
  }
}
