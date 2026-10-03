package dev.kausik.shaders.benchmark;

import java.util.Arrays;

/** Nearest-rank percentiles of every recorded interval; stalls and GC are never filtered out. */
public record FrameStatistics(
    int frames,
    double seconds,
    double meanFps,
    double meanMs,
    double p50Ms,
    double p95Ms,
    double p99Ms,
    double maxMs) {
  public static FrameStatistics calculate(long[] intervals, int count) {
    if (count <= 0 || count > intervals.length)
      throw new IllegalArgumentException("No valid frame samples");
    long[] sorted = Arrays.copyOf(intervals, count);
    double total = 0;
    for (long value : sorted) {
      if (value <= 0) throw new IllegalArgumentException("Non-positive frame interval");
      total += value;
    }
    Arrays.sort(sorted);
    return new FrameStatistics(
        count,
        total / 1e9,
        count * 1e9 / total,
        total / count / 1e6,
        percentile(sorted, .5),
        percentile(sorted, .95),
        percentile(sorted, .99),
        sorted[count - 1] / 1e6);
  }

  private static double percentile(long[] sorted, double fraction) {
    return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)] / 1e6;
  }
}
