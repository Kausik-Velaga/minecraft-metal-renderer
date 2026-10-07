package dev.kausik.metal;

/** Verifies passive completion telemetry without activating timestamp ordering or leaking handles. */
public final class ExecutionStatisticsTest {
  public static void main(String[] args) {
    int resources = MetalNative.liveResourceCount();
    long device = MetalNative.createDevice();
    long texture = 0;
    long fence = 0;
    try {
      String mode = MetalNative.hazardSynchronizationMode(device);
      long[] initial = snapshot(device);
      if (initial[0] != 0 || initial[1] != 0 || initial[3] != 0)
        throw new AssertionError("A new device has no submitted or completed work");
      texture = MetalNative.createTexture(device, "RGBA8_UNORM", 64, 64, 1, 1, 12, "Timing");
      for (int i = 0; i < 8; i++) {
        MetalNative.clearAll(device, texture, i / 8f, 0.5f, 1, 1, 0, Double.NaN);
        fence = MetalNative.createFence(device);
        MetalNative.submit(device);
        if (!MetalNative.awaitFence(fence, 5_000_000_000L))
          throw new AssertionError("Telemetry test GPU submission timed out");
        MetalNative.release(fence);
        fence = 0;
        long[] current = snapshot(device);
        if (current[0] != i + 1 || current[1] != i + 1 || current[8] != 0)
          throw new AssertionError("Submission/completion counts do not match known work");
        if (current[4] != i + 1 || current[6] != 0 || current[7] != 0 || current[11] != 0)
          throw new AssertionError("Headless work must acquire only upload-ring slots");
        if (!mode.equals(MetalNative.hazardSynchronizationMode(device)))
          throw new AssertionError("Passive telemetry changed encoder synchronization");
      }
      long[] complete = snapshot(device);
      if (complete[2] > 0 && complete[3] <= 0)
        throw new AssertionError("Timed GPU submissions must have a positive total span");
      System.out.println(
          "Execution telemetry passed: " + complete[2] + " timed command buffers in " + mode);
    } finally {
      if (fence != 0) MetalNative.release(fence);
      if (texture != 0) MetalNative.release(texture);
      MetalNative.destroyDevice(device);
    }
    if (MetalNative.liveResourceCount() != resources)
      throw new AssertionError("Execution telemetry leaked native handles");
  }

  private static long[] snapshot(long device) {
    long[] result = MetalNative.executionStatistics(device);
    if (result.length != 12) throw new AssertionError("Unexpected telemetry schema");
    for (long value : result)
      if (value < 0) throw new AssertionError("Telemetry counters must be nonnegative");
    if (result[1] != result[2] + result[9])
      throw new AssertionError("Completed GPU timing tuple is inconsistent");
    if (result[1] > result[0] || result[8] > result[1] || result[10] > result[4])
      throw new AssertionError("Telemetry subset counts exceed their totals");
    return result;
  }
}
