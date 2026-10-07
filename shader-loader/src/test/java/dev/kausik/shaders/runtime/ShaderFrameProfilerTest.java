package dev.kausik.shaders.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU-only verification that profiling never reuses unresolved GPU queries or waits for results.
 */
public final class ShaderFrameProfilerTest {
  private static final class Queries implements ShaderFrameProfiler.Queries {
    final long[] values;
    boolean ready, closed;
    int writes;

    Queries(int size) {
      values = new long[size];
    }

    public void mark(int index) {
      if (closed) throw new AssertionError("Closed query pool reused");
      values[index] = (++writes) * 100_000L;
      ready = false;
    }

    public OptionalLong value(int index) {
      return ready ? OptionalLong.of(values[index]) : OptionalLong.empty();
    }

    public void close() {
      closed = true;
    }
  }

  public static void main(String[] args) {
    List<Queries> pools = new ArrayList<>();
    List<ShaderFrameProfiler.Sample> samples = new ArrayList<>();
    AtomicLong cpu = new AtomicLong();
    var profiler =
        new ShaderFrameProfiler(
            1,
            count -> {
              var pool = new Queries(count);
              pools.add(pool);
              return pool;
            },
            () -> cpu.addAndGet(10_000),
            samples::add);
    for (int frame = 0; frame < 4; frame++) {
      profiler.beginFrame(null, "minecraft:overworld", 3456, 2168);
      profiler.mark("shadow.end");
      profiler.endFrame();
    }
    if (pools.size() != 3 || !samples.isEmpty())
      throw new AssertionError("A busy ring did not skip sampling without waiting");
    for (Queries pool : pools) {
      if (pool.writes != 3) throw new AssertionError("Unresolved queries were overwritten");
      pool.ready = true;
    }
    profiler.beginFrame(null, "minecraft:the_end", 1920, 1080);
    if (samples.size() != 3) throw new AssertionError("Completed ring did not resolve");
    var first = samples.getFirst();
    if (first.frame() != 0
        || first.width() != 3456
        || first.skippedSamples() != 1
        || Math.abs(first.gpuMs() - 0.2) > 1e-9
        || Math.abs(first.cpuSubmitMs() - 0.04) > 1e-9
        || Math.abs(first.markerCpuMs() - 0.03) > 1e-9)
      throw new AssertionError("Incorrect asynchronous sample metadata or duration: " + first);
    if (!first.stages().getFirst().to().equals("shadow.end"))
      throw new AssertionError("Stage labels lost");
    for (int i = 0; i < 200; i++) profiler.mark("extra." + i);
    profiler.endFrame();
    pools.forEach(pool -> pool.ready = true);
    profiler.beginFrame(null, "minecraft:the_end", 1920, 1080);
    var overflow = samples.getLast();
    if (!overflow.truncated()
        || overflow.markers() != 128
        || !overflow.stages().getLast().to().equals("frame.end"))
      throw new AssertionError("Long graphs lost the reserved frame-end timestamp");
    profiler.abortFrame();
    pools.forEach(pool -> pool.ready = true);
    profiler.beginFrame(null, "minecraft:the_end", 1920, 1080);
    if (samples.size() != 4) throw new AssertionError("Aborted frame was published");
    profiler.close();
    if (pools.stream().anyMatch(pool -> !pool.closed))
      throw new AssertionError("Query pools leaked");
    System.out.println("Shader profiler passed: asynchronous ring, durations, overflow and aborts");
  }
}
