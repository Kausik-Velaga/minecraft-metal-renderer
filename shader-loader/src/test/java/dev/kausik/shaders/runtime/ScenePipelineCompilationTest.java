package dev.kausik.shaders.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Reproduces the dependency cycle without chunk generation, a graphics device, or timing luck. */
public final class ScenePipelineCompilationTest {
  public static void main(String[] args) throws Exception {
    var background = Executors.newSingleThreadExecutor();
    var workerStarted = new CountDownLatch(1);
    var frameUpload = new CountDownLatch(1);
    try {
      background.execute(() -> {
        workerStarted.countDown();
        try {
          frameUpload.await();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
        }
      });
      if (!workerStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
      Thread renderThread = Thread.currentThread();
      // Repeat for a cold scene, another late variant, and a cache replaced by resource reload.
      for (int miss = 0; miss < 3; miss++) {
        var result = CompletableFuture.supplyAsync(Thread::currentThread,
            ScenePipelineCompilation.executor(background, true, true));
        if (result.get(1, TimeUnit.SECONDS) != renderThread)
          throw new AssertionError("Scene compilation must finish on the caller before frame upload");
      }
      if (ScenePipelineCompilation.executor(background, false, true) != background
          || ScenePipelineCompilation.executor(background, true, false) != background
          || ScenePipelineCompilation.executor(background, false, false) != background)
        throw new AssertionError("Non-scene compilation policy changed");
    } finally {
      frameUpload.countDown();
      background.shutdownNow();
      if (!background.awaitTermination(5, TimeUnit.SECONDS))
        throw new AssertionError("Worker did not stop");
    }
    System.out.println("PASS: scene cache misses finish while chunk workers wait for frame upload");
  }
}
