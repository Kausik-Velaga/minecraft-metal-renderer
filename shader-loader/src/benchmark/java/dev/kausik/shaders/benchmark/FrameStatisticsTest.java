package dev.kausik.shaders.benchmark;

/** Runs without Minecraft, Fabric, native libraries, or a game scheduler. */
public final class FrameStatisticsTest {
  public static void main(String[] args) {
    long[] intervals = {1_000_000, 2_000_000, 3_000_000, 94_000_000, 0};
    FrameStatistics result = FrameStatistics.calculate(intervals, 4);
    if (result.frames() != 4
        || result.meanFps() != 40
        || result.meanMs() != 25
        || result.p50Ms() != 2
        || result.p95Ms() != 94
        || result.p99Ms() != 94
        || result.maxMs() != 94)
      throw new AssertionError("Incorrect interval statistics: " + result);
    if (intervals[0] != 1_000_000 || intervals[3] != 94_000_000)
      throw new AssertionError("Statistics changed raw frame order");
    try {
      FrameStatistics.calculate(new long[] {0}, 1);
    } catch (IllegalArgumentException expected) {
      System.out.println(
          "PASS: benchmark statistics retain stalls and calculate throughput from elapsed time");
      return;
    }
    throw new AssertionError("Zero interval accepted");
  }
}
