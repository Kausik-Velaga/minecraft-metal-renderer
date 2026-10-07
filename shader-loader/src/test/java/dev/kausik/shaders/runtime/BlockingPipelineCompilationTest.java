package dev.kausik.shaders.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reproduces worker starvation without loading Minecraft, native code, or a GPU. */
public final class BlockingPipelineCompilationTest {
  public static void main(String[] args) throws Exception {
    var workers = Executors.newFixedThreadPool(2);
    var workersOccupied = new CountDownLatch(2);
    var renderUploadDrain = new CountDownLatch(1);
    try {
      for (int i = 0; i < 2; i++) {
        workers.execute(
            () -> {
              workersOccupied.countDown();
              try {
                renderUploadDrain.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
      }
      if (!workersOccupied.await(2, TimeUnit.SECONDS))
        throw new AssertionError("Could not fill the simulated chunk worker pool");
      var queuedControl = CompletableFuture.supplyAsync(() -> "queued compile", workers);
      if (queuedControl.isDone())
        throw new AssertionError("Control unexpectedly bypassed occupied chunk workers");

      Thread renderThread = Thread.currentThread();
      var blockingCompile =
          CompletableFuture.supplyAsync(
              Thread::currentThread, BlockingPipelineCompilation.executor(workers, true, true));
      if (blockingCompile.get(2, TimeUnit.SECONDS) != renderThread)
        throw new AssertionError(
            "Blocking cache miss did not compile on its calling render thread");
      if (queuedControl.isDone())
        throw new AssertionError("Compilation should finish before chunk upload capacity is freed");

      // Finishing the render-thread compile permits uploads to drain and the worker pool to resume.
      renderUploadDrain.countDown();
      if (!queuedControl.get(2, TimeUnit.SECONDS).equals("queued compile"))
        throw new AssertionError("Original worker queue did not resume");
      if (BlockingPipelineCompilation.executor(workers, false, true) != workers
          || BlockingPipelineCompilation.executor(workers, true, false) != workers
          || BlockingPipelineCompilation.executor(workers, false, false) != workers)
        throw new AssertionError("Unselected packs or non-render callers changed executor");

      var failure = new IllegalStateException("compile failure");
      var failed =
          CompletableFuture.supplyAsync(
              () -> {
                throw failure;
              },
              BlockingPipelineCompilation.executor(workers, true, true));
      try {
        failed.join();
        throw new AssertionError("Compilation failure was lost");
      } catch (java.util.concurrent.CompletionException expected) {
        if (expected.getCause() != failure) throw expected;
      }
      System.out.println(
          "PASS: blocking pipeline compilation progresses with exhausted chunk workers; scope and"
              + " failures preserved");
    } finally {
      renderUploadDrain.countDown();
      workers.shutdownNow();
      if (!workers.awaitTermination(2, TimeUnit.SECONDS))
        throw new AssertionError("Simulated workers did not terminate");
    }
  }
}
