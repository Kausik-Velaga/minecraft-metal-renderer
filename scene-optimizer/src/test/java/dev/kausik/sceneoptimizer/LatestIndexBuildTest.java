package dev.kausik.sceneoptimizer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.AABB;

/** Worker isolation, bounded replacement, cancellation and publication without a game or GPU. */
final class LatestIndexBuildTest {
  static void run() throws Exception {
    Thread caller = Thread.currentThread();
    try (var build = new LatestIndexBuild<SpatialSectionIndex<Object>>()) {
      Object section =
          new Object() {
            @Override
            public String toString() {
              throw new AssertionError("Worker inspected section");
            }
          };
      var snapshot =
          List.of(new SpatialSectionIndex.Entry<>(section, new AABB(0, 0, 0, 16, 16, 16)));
      build.submit(
          "world-material-position-a",
          () -> {
            if (Thread.currentThread() == caller) throw new AssertionError("Build ran on caller");
            return new SpatialSectionIndex<>(snapshot);
          });
      if (build.poll("different-world") != null || build.poll("different-materials") != null)
        throw new AssertionError("Published under an incompatible world/material identity");
      var index = await(build);
      if (index.entry(0).value() != section
          || index.query(box -> true).candidates().cardinality() != 1)
        throw new AssertionError("Snapshot result changed");
      if (build.poll("world-material-position-a") != null)
        throw new AssertionError("Result published twice");
    }

    // Even a task that fails to cooperate with interruption cannot publish its stale result,
    // and repeated replacements cannot accumulate a queue of world snapshots.
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger executed = new AtomicInteger();
    try (var build = new LatestIndexBuild<Integer>()) {
      build.submit(
          "world-material-position-a",
          () -> {
            entered.countDown();
            boolean done = false;
            while (!done) {
              try {
                done = release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                /* Deliberately uncooperative fixture. */
              }
            }
            return -1;
          });
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
      for (int i = 0; i < 100; i++) {
        int value = i;
        build.submit(
            "world-material-position-a",
            () -> {
              executed.incrementAndGet();
              return value;
            });
      }
      release.countDown();
      if (await(build) != 99 || executed.get() != 1)
        throw new AssertionError("Obsolete replacement ran or published");
      build.submit("world-material-position-a", () -> 100);
      build.cancel();
      if (build.pending() || build.poll("world-material-position-a") != null)
        throw new AssertionError("Unload retained result");
      build.submit(
          "world-material-position-a",
          () -> {
            throw new IllegalArgumentException("fixture failure");
          });
      try {
        await(build);
        throw new AssertionError("Failure hidden");
      } catch (IllegalStateException expected) {
        if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected;
      }
      build.submit("world-material-position-a", () -> 101);
      if (await(build) != 101) throw new AssertionError("Worker did not recover for new identity");
    } finally {
      release.countDown();
    }

    Thread.currentThread().interrupt();
    try {
      new SpatialSectionIndex<>(
          List.of(new SpatialSectionIndex.Entry<>(0, new AABB(0, 0, 0, 16, 16, 16))));
      throw new AssertionError("Obsolete construction ignored cancellation");
    } catch (java.util.concurrent.CancellationException expected) {
      // The caller owns the interrupt status; the builder must not clear it.
      if (!Thread.currentThread().isInterrupted()) throw new AssertionError("Interrupt swallowed");
    } finally {
      Thread.interrupted();
    }
  }

  private static <T> T await(LatestIndexBuild<T> build) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      T result = build.poll("world-material-position-a");
      if (result != null) return result;
      Thread.sleep(1);
    }
    throw new AssertionError("Worker did not publish before deadline");
  }
}
