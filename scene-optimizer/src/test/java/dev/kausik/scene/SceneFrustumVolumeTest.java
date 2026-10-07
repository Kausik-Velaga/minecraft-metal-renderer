package dev.kausik.scene;

import dev.kausik.sceneoptimizer.SpatialSectionIndex;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Compares bulk acceptance directly with vanilla's public predicate, including float conversion.
 */
public final class SceneFrustumVolumeTest {
  private static final MethodHandle VANILLA_CLASSIFIER = vanillaClassifier();

  public static void run() {
    testGenericAndCustomFallback();
    testTouchingPlanesAndExpandedBoxes();
    testRandomViewsAndRefinement();
    testWorkerSnapshotIsolation();
  }

  private static void testGenericAndCustomFallback() {
    SceneVolume generic = bounds -> true;
    AABB box = new AABB(-16, -16, -16, 16, 16, 16);
    if (generic.classify(box) != SceneVolume.Classification.INTERSECT) {
      throw new AssertionError("An unknown predicate inferred full containment");
    }
    Frustum custom =
        new Frustum(new Matrix4f(), new Matrix4f()) {
          @Override
          public boolean isVisible(AABB bounds) {
            return bounds.maxX > 100;
          }
        };
    var customVolume = new SceneFrustumVolume(custom, 16);
    if (generic.workerSnapshot() != null || customVolume.workerSnapshot() != null)
      throw new AssertionError("Unsupported volume lost its thread-bound semantics");
    if (customVolume.classify(box) != SceneVolume.Classification.OUTSIDE
        || customVolume.classify(new AABB(200, 0, 0, 216, 16, 16))
            != SceneVolume.Classification.INTERSECT) {
      throw new AssertionError("Custom Frustum semantics inherited vanilla containment");
    }
  }

  private static void testWorkerSnapshotIsolation() {
    Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f().ortho(-64, 64, -32, 32, 1, 256));
    frustum.prepare(-1_000_003.25, 23.5, 1_000_007.75);
    var original = new SceneFrustumVolume(frustum, 16);
    SceneVolume snapshot = original.workerSnapshot();
    if (snapshot == null) throw new AssertionError("Vanilla frustum cannot be snapshotted");
    var boxes = new ArrayList<AABB>();
    var expected = new ArrayList<Boolean>();
    for (int x = -8; x < 8; x++) {
      var box = new AABB(-1_000_003.25 + x * 16, 0, 999_951.75,
          -1_000_003.25 + x * 16 + 16, 32, 999_967.75);
      boxes.add(box);
      expected.add(original.intersects(box));
      if (snapshot.intersects(box) != original.intersects(box))
        throw new AssertionError("Snapshot changed padding/float conversion");
    }
    frustum.prepare(4_000_000, 0, -4_000_000);
    for (int i = 0; i < boxes.size(); i++)
      if (snapshot.intersects(boxes.get(i)) != expected.get(i))
        throw new AssertionError("Worker snapshot retained mutable camera origin");
  }

  private static void testTouchingPlanesAndExpandedBoxes() {
    Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f().ortho(-64, 64, -32, 32, 1, 256));
    frustum.prepare(0, 0, 0);
    List<AABB> boxes =
        List.of(
            new AABB(-80, -16, -32, -64, 0, -16),
            new AABB(-64, -16, -32, -48, 0, -16),
            new AABB(64, 0, -32, 80, 16, -16),
            new AABB(-16, -48, -32, 0, -32, -16),
            new AABB(-16, 32, -32, 0, 48, -16),
            new AABB(-16, -16, -272, 0, 0, -256),
            new AABB(-16, -16, -1, 0, 0, 15),
            new AABB(-0.0, -0.0, -16, 0.0, 0.0, -16));
    for (double padding : new double[] {0, 0.5, 16, 64}) {
      var volume = adapted(frustum, padding);
      for (AABB box : boxes) assertClassification(frustum, volume, padding, box);
      for (AABB box : boxes) {
        assertClassification(frustum, volume, padding, box.move(-0.000001, 0, 0));
        assertClassification(frustum, volume, padding, box.move(0.000001, 0, 0));
      }
    }
  }

  private static void testRandomViewsAndRefinement() {
    Random random = new Random(0x31c011);
    long skippedTests = 0;
    for (int viewIndex = 0; viewIndex < 90; viewIndex++) {
      double cameraX = viewIndex % 3 == 0 ? -1_000_003.25 : random.nextDouble() * 320 - 160;
      double cameraY = random.nextDouble() * 128 - 64;
      double cameraZ = viewIndex % 3 == 0 ? 1_000_007.75 : random.nextDouble() * 320 - 160;
      var view =
          new Matrix4f()
              .rotateX((float) (random.nextDouble() - 0.5))
              .rotateY((float) (random.nextDouble() * Math.PI * 2));
      var projection =
          viewIndex % 2 == 0
              ? new Matrix4f().ortho(-128, 128, -96, 96, 0.05f, 640)
              : new Matrix4f()
                  .perspective(
                      (float) Math.toRadians(50 + random.nextDouble() * 60), 1.6f, 0.05f, 640);
      Frustum frustum = new Frustum(view, projection);
      frustum.prepare(cameraX, cameraY, cameraZ);
      double padding = viewIndex % 3 == 0 ? 16 : viewIndex % 3 == 1 ? 0 : 0.25;
      var volume = adapted(frustum, padding);
      var entries = new ArrayList<SpatialSectionIndex.Entry<Integer>>();
      double baseX = Math.floor(cameraX / 16) * 16;
      double baseY = Math.floor(cameraY / 16) * 16;
      double baseZ = Math.floor(cameraZ / 16) * 16;
      for (int x = -8; x <= 8; x++) {
        for (int y = -4; y <= 4; y++) {
          for (int z = -12; z <= 8; z++) {
            double minX = baseX + x * 16;
            double minY = baseY + y * 16;
            double minZ = baseZ + z * 16;
            entries.add(
                new SpatialSectionIndex.Entry<>(
                    entries.size(), new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16)));
          }
        }
      }
      Collections.shuffle(entries, random);
      var index = new SpatialSectionIndex<>(entries);
      var query = index.query(volume);
      var expected = new ArrayList<Integer>();
      var actual = new ArrayList<Integer>();
      // A second, oblique volume must still be applied to bulk-accepted primary candidates.
      SceneVolume refinement =
          bounds ->
              bounds.maxX - cameraX + 0.4 * (bounds.maxY - cameraY) - (bounds.minZ - cameraZ)
                  >= -10;
      for (var entry : entries) {
        if (frustum.isVisible(entry.bounds().inflate(padding))
            && refinement.intersects(entry.bounds())) expected.add(entry.value());
      }
      for (int i = query.candidates().nextSetBit(0);
          i >= 0;
          i = query.candidates().nextSetBit(i + 1)) {
        var entry = index.entry(i);
        boolean primary;
        if (query.fullyInside().get(i)) {
          primary = true;
          skippedTests++;
          if (!frustum.isVisible(entry.bounds().inflate(padding))) {
            throw new AssertionError("Cell containment accepted a vanilla-rejected child");
          }
        } else primary = volume.intersects(entry.bounds());
        if (primary && refinement.intersects(entry.bounds())) actual.add(entry.value());
      }
      if (!expected.equals(actual)) {
        throw new AssertionError(
            "Bulk selection changed exact membership/order for view " + viewIndex);
      }
      for (int i = 0; i < 40; i++) {
        AABB box =
            entries.get(random.nextInt(entries.size())).bounds().inflate(random.nextDouble());
        assertClassification(frustum, volume, padding, box);
      }
    }
    if (skippedTests < 10_000) {
      throw new AssertionError(
          "Containment path failed to eliminate meaningful section tests: " + skippedTests);
    }
  }

  private static void assertClassification(
      Frustum frustum, SceneFrustumVolume volume, double padding, AABB bounds) {
    boolean expected = frustum.isVisible(bounds.inflate(padding));
    if (volume.intersects(bounds) != expected)
      throw new AssertionError("Boundary predicate changed");
    SceneVolume.Classification classification = volume.classify(bounds);
    if ((classification == SceneVolume.Classification.OUTSIDE && expected)
        || (classification == SceneVolume.Classification.INSIDE && !expected)) {
      throw new AssertionError("Classification contradicted vanilla on touching/expanded bounds");
    }
  }

  private static SceneFrustumVolume adapted(Frustum frustum, double padding) {
    return new SceneFrustumVolume(
        frustum,
        padding,
        (minX, minY, minZ, maxX, maxY, maxZ) -> {
          try {
            return (int)
                VANILLA_CLASSIFIER.invokeExact(frustum, minX, minY, minZ, maxX, maxY, maxZ);
          } catch (Throwable failure) {
            throw new AssertionError("Cannot invoke vanilla classifier for comparison", failure);
          }
        });
  }

  private static MethodHandle vanillaClassifier() {
    try {
      return MethodHandles.privateLookupIn(Frustum.class, MethodHandles.lookup())
          .findVirtual(
              Frustum.class,
              "cubeInFrustum",
              MethodType.methodType(
                  int.class,
                  double.class,
                  double.class,
                  double.class,
                  double.class,
                  double.class,
                  double.class));
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError("Vanilla classifier API changed", failure);
    }
  }
}
