package dev.kausik.sceneoptimizer;

import dev.kausik.scene.SceneFrustumVolumeTest;
import dev.kausik.scene.ScenePreparationIndexTest;
import dev.kausik.scene.SceneVolume;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.phys.AABB;

/** Broad-phase equivalence and ordering against exhaustive independent section selection. */
public final class SceneOptimizerTest {
  public static void main(String[] args) throws Exception {
    testRandomViewsAndOrdering();
    testNegativeAndStraddlingBounds();
    testSparseViewRejectsGroups();
    testIndependentQueriesAndSourceSnapshot();
    testLayerSets();
    LatestIndexBuildTest.run();
    SelectionPrefetchTest.run();
    DrawMetadataCacheTest.run();
    DrawWorkMetricsTest.run();
    SceneFrustumVolumeTest.run();
    SparseExtractionMembershipTest.run();
    ScenePreparationIndexTest.run();
    System.out.println(
        "Scene optimizer passed: exhaustive view equivalence, ordering, independent selections,"
            + " negative/straddling cells, broad-phase rejection, layer membership and draw"
            + " metadata, bounded background builds, same-frame selection and stale-result rejection.");
  }

  private static void testRandomViewsAndOrdering() {
    Random random = new Random(0x5ce1e);
    var entries = new ArrayList<SpatialSectionIndex.Entry<Integer>>();
    for (int x = -16; x <= 16; x++) {
      for (int y = -4; y < 12; y++) {
        for (int z = -8; z <= 8; z++) {
          entries.add(
              new SpatialSectionIndex.Entry<>(
                  entries.size(),
                  new AABB(x * 16, y * 16, z * 16, x * 16 + 16, y * 16 + 16, z * 16 + 16)));
        }
      }
    }
    // A rotated storage's order is not spatially sorted; the provider must retain that order.
    Collections.shuffle(entries, random);
    var index = new SpatialSectionIndex<>(entries);
    for (int i = 0; i < 250; i++) {
      double x = random.nextDouble() * 768 - 384;
      double y = random.nextDouble() * 320 - 128;
      double z = random.nextDouble() * 512 - 256;
      var clip =
          new AABB(
              x,
              y,
              z,
              x + random.nextDouble() * 200,
              y + random.nextDouble() * 128,
              z + random.nextDouble() * 160);
      SceneVolume volume = clip::intersects;
      assertEquivalent(entries, index, volume);
      assertEquivalent(entries, index, box -> clip.intersects(box.inflate(16)));
      // Oblique half-space exercises conservative union bounds, independently of axis boxes.
      double distance = random.nextDouble() * 300 - 150;
      assertEquivalent(entries, index, box -> box.maxX + 0.7 * box.maxY - box.minZ > distance);
    }
  }

  private static void testNegativeAndStraddlingBounds() {
    List<SpatialSectionIndex.Entry<Integer>> entries =
        List.of(
            new SpatialSectionIndex.Entry<>(0, new AABB(-65, -1, -65, 70, 18, 80)),
            new SpatialSectionIndex.Entry<>(1, new AABB(-64, -64, -64, -48, -48, -48)),
            new SpatialSectionIndex.Entry<>(2, new AABB(-16, 0, -16, 0, 16, 0)),
            new SpatialSectionIndex.Entry<>(3, new AABB(64, 64, 64, 80, 80, 80)));
    var index = new SpatialSectionIndex<>(entries);
    assertEquivalent(entries, index, new AABB(65, 10, 65, 66, 11, 66)::intersects);
    assertEquivalent(
        entries, index, new AABB(-64.1, -64.1, -64.1, -63.9, -63.9, -63.9)::intersects);
    assertEquivalent(entries, index, new AABB(-0.1, 0, -0.1, 0.1, 1, 0.1)::intersects);
  }

  private static void testSparseViewRejectsGroups() {
    var entries = new ArrayList<SpatialSectionIndex.Entry<Integer>>();
    for (int i = 0; i < 4096; i++) {
      int x = (i % 64) * 16;
      int z = (i / 64) * 16;
      entries.add(new SpatialSectionIndex.Entry<>(i, new AABB(x, 0, z, x + 16, 16, z + 16)));
    }
    var index = new SpatialSectionIndex<>(entries);
    var clip = new AABB(1, 1, 1, 31, 15, 31);
    var query = index.query(clip::intersects);
    if (query.candidates().cardinality() > 16 || query.coarseTests() >= entries.size() / 4) {
      throw new AssertionError("Spatial grouping did not eliminate repeated section tests");
    }
    assertEquivalent(entries, index, clip::intersects);
  }

  private static void testIndependentQueriesAndSourceSnapshot() {
    var entries = new ArrayList<SpatialSectionIndex.Entry<Integer>>();
    entries.add(new SpatialSectionIndex.Entry<>(0, new AABB(0, 0, 0, 16, 16, 16)));
    entries.add(new SpatialSectionIndex.Entry<>(1, new AABB(512, 0, 0, 528, 16, 16)));
    var index = new SpatialSectionIndex<>(entries);
    entries.clear();
    if (index.size() != 2) throw new AssertionError("Index retained mutable caller membership");
    var camera = index.query(new AABB(0, 0, 0, 32, 32, 32)::intersects);
    var light = index.query(new AABB(500, 0, 0, 540, 32, 32)::intersects);
    if (!camera.candidates().get(0)
        || camera.candidates().get(1)
        || light.candidates().get(0)
        || !light.candidates().get(1)) {
      throw new AssertionError("View selections contaminated each other");
    }
    camera.candidates().clear();
    if (!index.query(box -> true).candidates().get(0)) {
      throw new AssertionError("A query mutated the spatial snapshot");
    }
  }

  private static void testLayerSets() {
    var vanilla = ChunkSectionLayer.values();
    for (int mask = 0; mask < 1 << vanilla.length; mask++) {
      var selected = SectionLayerSets.forMask(mask);
      if (selected.length != Integer.bitCount(mask))
        throw new AssertionError("Layer count changed");
      int next = 0;
      for (ChunkSectionLayer layer : vanilla) {
        if ((mask & (1 << layer.ordinal())) != 0 && selected[next++] != layer) {
          throw new AssertionError("Layer iteration order changed");
        }
      }
      if (selected != SectionLayerSets.forMask(mask)) {
        throw new AssertionError("A draw preparation call allocated a layer array");
      }
    }
  }

  private static void assertEquivalent(
      List<SpatialSectionIndex.Entry<Integer>> entries,
      SpatialSectionIndex<Integer> index,
      SceneVolume volume) {
    var expected = new ArrayList<Integer>();
    for (var entry : entries) if (volume.intersects(entry.bounds())) expected.add(entry.value());
    var actual = new ArrayList<Integer>();
    var query = index.query(volume);
    for (int i = query.candidates().nextSetBit(0);
        i >= 0;
        i = query.candidates().nextSetBit(i + 1)) {
      var entry = index.entry(i);
      if (volume.intersects(entry.bounds())) actual.add(entry.value());
    }
    if (!expected.equals(actual)) {
      throw new AssertionError(
          "Broad-phase result/order differs: expected "
              + expected.size()
              + " entries, got "
              + actual.size());
    }
  }
}
