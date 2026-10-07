package dev.kausik.sceneoptimizer;

import dev.kausik.scene.SceneProvider;
import dev.kausik.scene.SceneSelection;
import dev.kausik.scene.SceneSelectionTicket;
import dev.kausik.scene.SceneViewRequest;
import dev.kausik.scene.SceneVolume;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;

/** Exact geometry/order/counters plus nonblocking, single-use, latest-frame publication. */
final class SelectionPrefetchTest {
  static void run() throws Exception {
    testGeometryAndLiveReadiness();
    testPublicationAndIdentity();
    testMissAndReplacement();
    testCancellationAndFailure();
    testUnsupportedProvider();
  }

  private static void testGeometryAndLiveReadiness() {
    Random random = new Random(0x51ec710);
    var entries = new ArrayList<SpatialSectionIndex.Entry<Integer>>();
    for (int x = -8; x < 8; x++)
      for (int z = -6; z < 6; z++)
        entries.add(new SpatialSectionIndex.Entry<>(entries.size(),
            new AABB(x * 16, -16, z * 16, x * 16 + 16, 0, z * 16 + 16)));
    Collections.shuffle(entries, random);
    var index = new SpatialSectionIndex<>(entries);
    Thread caller = Thread.currentThread();
    for (int view = 0; view < 50; view++) {
      double x = random.nextDouble() * 192 - 96;
      double z = random.nextDouble() * 128 - 64;
      var clip = new AABB(x, -32, z, x + 96, 32, z + 112);
      SceneVolume primary = boxVolume(clip);
      SceneVolume refinement = view % 3 == 0 ? null : box -> box.maxX - box.minZ > 10;
      var geometry = GeometricSelection.compute(index, primary, refinement);
      boolean[] live = new boolean[entries.size()];
      for (int update = 0; update < 3; update++) {
        // Meshes become ready/empty AFTER geometry capture, including previously empty casters.
        for (int i = 0; i < live.length; i++) live[i] = random.nextBoolean();
        Predicate<Integer> readiness = value -> {
          require(Thread.currentThread() == caller, "Read live readiness on worker");
          return live[value];
        };
        var actual = geometry.filter(readiness);
        var expected = new ArrayList<Integer>();
        for (var entry : entries)
          if (readiness.test(entry.value()) && primary.intersects(entry.bounds())
              && (refinement == null || refinement.intersects(entry.bounds())))
            expected.add(entry.value());
        require(actual.sections().equals(expected), "Geometry changed exhaustive membership/order");
        var query = index.query(primary);
        int candidates = 0, tested = 0, inside = 0;
        for (int i = query.candidates().nextSetBit(0); i >= 0;
            i = query.candidates().nextSetBit(i + 1)) {
          if (!readiness.test(index.entry(i).value())) continue;
          if (query.fullyInside().get(i)) inside++;
          else {
            tested++;
            if (!primary.intersects(index.entry(i).bounds())) continue;
          }
          candidates++;
        }
        require(actual.candidates() == candidates && actual.tested() == tested
            && actual.inside() == inside && actual.coarseTests() == query.coarseTests(),
            "Async filtering changed original live-readiness counters");
      }
    }
  }

  private static void testPublicationAndIdentity() throws Exception {
    Object opaqueSection = new Object() {
      @Override public String toString() { throw new AssertionError("Worker inspected section"); }
    };
    var index = one(opaqueSection);
    Object request = new Object();
    var identity = new Identity(index, new Object(), 1, 2, 3);
    Thread caller = Thread.currentThread();
    SceneVolume workerVolume = bounds -> {
      require(Thread.currentThread() != caller, "Geometry ran on render caller");
      return true;
    };
    try (var worker = new SelectionPrefetch<Object>()) {
      var ticket = worker.submit(request, identity, index, workerVolume, null);
      await(worker, ticket);
      var result = worker.consume(ticket, request, identity);
      require(result != null && result.filter(value -> true).sections().getFirst() == opaqueSection,
          "Ready query was not published");
      require(worker.consume(ticket, request, identity) == null, "Ticket consumed twice");
      ticket.close();
      // Each lifecycle component independently prevents obsolete publication.
      for (Identity changed : List.of(
          new Identity(one(opaqueSection), identity.area(), 1, 2, 3),
          new Identity(index, new Object(), 1, 2, 3),
          new Identity(index, identity.area(), 9, 2, 3),
          new Identity(index, identity.area(), 1, 9, 3),
          new Identity(index, identity.area(), 1, 2, 9))) {
        var stale = worker.submit(request, identity, index, workerVolume, null);
        await(worker, stale);
        require(worker.consume(stale, request, changed) == null, "Stale identity published");
      }
      var wrongRequest = worker.submit(request, identity, index, workerVolume, null);
      await(worker, wrongRequest);
      require(worker.consume(wrongRequest, new Object(), identity) == null,
          "Equivalent-looking different request consumed token");
      require(worker.stats().ready() == 1 && worker.stats().stale() == 7,
          "Publication lifecycle counters changed");
    }
  }

  private static void testMissAndReplacement() throws Exception {
    var index = one(1);
    Object request = new Object(), identity = new Object();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var worker = new SelectionPrefetch<Integer>()) {
      var unfinished = worker.submit(request, identity, index, blocking(entered, release), null);
      require(entered.await(5, TimeUnit.SECONDS), "Worker did not start");
      require(worker.consume(unfinished, request, identity) == null,
          "Unfinished query should trigger immediate fallback");
      require(worker.stats().missed() == 1, "Miss was not recorded");
      AtomicInteger executed = new AtomicInteger();
      SceneSelectionTicket replacement = null;
      for (int i = 0; i < 100; i++) {
        var previous = replacement;
        replacement = worker.submit(request, identity, index,
            bounds -> { executed.incrementAndGet(); return true; }, null);
        if (previous != null) previous.close(); // Must not cancel its replacement.
      }
      unfinished.close();
      release.countDown();
      await(worker, replacement);
      require(worker.consume(replacement, request, identity) != null,
          "Old close cancelled a replacement");
      // One cell classification plus one per-section geometry test; only newest queued task runs.
      require(executed.get() == 2, "Replacement queue accumulated obsolete query work");
    } finally {
      release.countDown();
    }
  }

  private static void testCancellationAndFailure() throws Exception {
    var index = one(1);
    Object request = new Object(), identity = new Object();
    try (var worker = new SelectionPrefetch<Integer>(); var other = new SelectionPrefetch<Integer>()) {
      var closed = worker.submit(request, identity, index, bounds -> true, null);
      closed.close();
      closed.close();
      require(worker.consume(closed, request, identity) == null, "Closed query published");
      var invalidated = worker.submit(request, identity, index, bounds -> true, null);
      worker.cancel();
      require(worker.consume(invalidated, request, identity) == null, "Unload query published");
      var failed = worker.submit(request, identity, index,
          bounds -> { throw new IllegalArgumentException("fixture failure"); }, null);
      await(worker, failed);
      require(worker.consume(failed, request, identity) == null && worker.stats().failed() == 1,
          "Worker failure did not fall back");
      var recovered = worker.submit(request, identity, index, bounds -> true, null);
      await(worker, recovered);
      require(other.consume(recovered, request, identity) == null, "Another owner used ticket");
      require(worker.consume(recovered, request, identity) != null,
          "Failure or foreign consume broke subsequent work");
    }
  }

  private static void testUnsupportedProvider() {
    AtomicInteger calls = new AtomicInteger();
    SceneProvider ordinary = new SceneProvider() {
      @Override public String id() { return "fixture"; }
      @Override public SceneSelection select(SceneViewRequest request) {
        calls.incrementAndGet();
        return null;
      }
    };
    require(ordinary.prefetch(null) == null, "Unsupported provider invented a ticket");
    ordinary.select(null, null);
    require(calls.get() == 1, "Default provider did not use ordinary selection");
    require(((SceneVolume) bounds -> true).workerSnapshot() == null,
        "Arbitrary predicate became eligible for worker reads");
  }

  private static SceneVolume blocking(CountDownLatch entered, CountDownLatch release) {
    return bounds -> {
      entered.countDown();
      boolean done = false;
      while (!done) {
        try { done = release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { /* Deliberately uncooperative old query. */ }
      }
      return true;
    };
  }

  private static SceneVolume boxVolume(AABB clip) {
    return new SceneVolume() {
      @Override public boolean intersects(AABB box) { return clip.intersects(box); }
      @Override public Classification classify(AABB box) {
        if (!intersects(box)) return Classification.OUTSIDE;
        return clip.minX <= box.minX && clip.minY <= box.minY && clip.minZ <= box.minZ
            && clip.maxX >= box.maxX && clip.maxY >= box.maxY && clip.maxZ >= box.maxZ
            ? Classification.INSIDE : Classification.INTERSECT;
      }
    };
  }

  private static <T> SpatialSectionIndex<T> one(T value) {
    return new SpatialSectionIndex<>(List.of(
        new SpatialSectionIndex.Entry<>(value, new AABB(0, 0, 0, 16, 16, 16))));
  }

  private static void await(SelectionPrefetch<?> worker, SceneSelectionTicket ticket)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!worker.isDone(ticket) && System.nanoTime() < deadline) Thread.sleep(1);
    require(worker.isDone(ticket), "Query did not finish before test deadline");
  }

  private record Identity(Object index, Object area, long world, long material, long position) {}

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
