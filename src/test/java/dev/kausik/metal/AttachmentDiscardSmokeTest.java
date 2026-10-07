package dev.kausik.metal;

import java.util.ArrayList;
import java.util.Arrays;

/** Native load elision must preserve complete writes and reject partial/depth passes and clears. */
public final class AttachmentDiscardSmokeTest {
  private static final int SIZE = 8, BYTES = SIZE * SIZE * 4;
  private static final String VERTEX =
      """
      #include <metal_stdlib>
      using namespace metal;
      vertex float4 main0(uint id [[vertex_id]]) {
        float2 p=float2((id<<1)&2,id&2)*2.0-1.0;
        return float4(p,0,1);
      }
      """;
  private static final String FRAGMENT =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Color { float4 first [[color(0)]]; float4 second [[color(1)]]; };
      fragment Color main0() { return {float4(1,0,0,1),float4(0,1,0,1)}; }
      """;

  public static void main(String[] args) {
    int count = MetalNative.liveResourceCount();
    long device = MetalNative.createDevice();
    var owned = new ArrayList<Long>();
    try {
      long color =
          own(
              owned,
              MetalNative.createTexture(
                  device, "RGBA8_UNORM", SIZE, SIZE, 1, 1, 12, "discard first"));
      long other =
          own(
              owned,
              MetalNative.createTexture(
                  device, "RGBA8_UNORM", SIZE, SIZE, 1, 1, 12, "discard second"));
      long depth =
          own(
              owned,
              MetalNative.createTexture(
                  device, "D32_FLOAT", SIZE, SIZE, 1, 1, 12, "discard depth"));
      long buffer =
          own(owned, MetalNative.createBuffer(device, BYTES * 2, true, "discard readback"));
      long pipeline =
          own(
              owned,
              MetalNative.createPipeline(
                  device,
                  "discard full overwrite",
                  VERTEX,
                  FRAGMENT,
                  new int[0],
                  new int[0],
                  new int[] {70, 15, 0, 1, 0, 0, 1, 0, 0, 70, 15, 0, 1, 0, 0, 1, 0, 0},
                  -1,
                  false,
                  false,
                  false,
                  0,
                  0));
      for (int mask : new int[] {0, 1, 2, 3}) {
        MetalNative.clearAll(device, color, .25f, .25f, .25f, 1, 0, Double.NaN);
        MetalNative.clearAll(device, other, .5f, .5f, .5f, 1, 0, Double.NaN);
        MetalNative.beginRenderPassWithDiscard(
            device,
            "full overwrite",
            new long[] {color, other},
            new float[] {Float.NaN, 0, 0, 0, Float.NaN, 0, 0, 0},
            0,
            Double.NaN,
            0,
            0,
            SIZE,
            SIZE,
            mask);
        MetalNative.bindPipeline(device, pipeline);
        MetalNative.draw(device, 3, 0, 3, 1, 0);
        MetalNative.endRenderPass(device);
        byte[] values = read(device, color, other, buffer);
        require(values, 0, 255, 0, 0);
        require(values, BYTES, 0, 255, 0);
      }
      // No draws here: an invalid discard request must retain earlier attachment contents.
      MetalNative.beginRenderPassWithDiscard(
          device, "partial", new long[] {color}, null, 0, Double.NaN, 1, 0, SIZE - 1, SIZE, 1);
      MetalNative.endRenderPass(device);
      require(read(device, color, other, buffer), 0, 255, 0, 0);
      MetalNative.clearAll(device, 0, 0, 0, 0, 0, depth, 1);
      MetalNative.beginRenderPassWithDiscard(
          device,
          "depth rejected",
          new long[] {color},
          null,
          depth,
          Double.NaN,
          0,
          0,
          SIZE,
          SIZE,
          1);
      MetalNative.endRenderPass(device);
      require(read(device, color, other, buffer), 0, 255, 0, 0);
      MetalNative.beginRenderPassWithDiscard(
          device,
          "clear wins",
          new long[] {color},
          new float[] {0, 0, 1, 1},
          0,
          Double.NaN,
          0,
          0,
          SIZE,
          SIZE,
          1);
      MetalNative.endRenderPass(device);
      require(read(device, color, other, buffer), 0, 0, 0, 255);
      System.out.println(
          "PASS: complete two-target overwrite with all discard masks; partial/depth"
              + " requests retain contents and explicit clear wins");
    } finally {
      for (int i = owned.size() - 1; i >= 0; i--) MetalNative.release(owned.get(i));
      MetalNative.destroyDevice(device);
    }
    if (MetalNative.liveResourceCount() != count)
      throw new AssertionError("Discard test leaked handles");
  }

  private static long own(ArrayList<Long> handles, long value) {
    handles.add(value);
    return value;
  }

  private static byte[] read(long device, long color, long other, long buffer) {
    MetalNative.textureToBuffer(device, color, 0, 0, 0, SIZE, SIZE, buffer, 0);
    MetalNative.textureToBuffer(device, other, 0, 0, 0, SIZE, SIZE, buffer, BYTES);
    long fence = MetalNative.createFence(device);
    try {
      MetalNative.submit(device);
      if (!MetalNative.awaitFence(fence, 10_000_000_000L))
        throw new AssertionError("Discard GPU timeout");
    } finally {
      MetalNative.release(fence);
    }
    byte[] result = new byte[BYTES * 2];
    MetalNative.mapBuffer(buffer, 0, result.length).get(result);
    return result;
  }

  private static void require(byte[] values, int offset, int r, int g, int b) {
    int[] expected = {r, g, b, 255};
    for (int i = 0; i < BYTES; i++)
      if (Byte.toUnsignedInt(values[offset + i]) != expected[i % 4])
        throw new AssertionError(
            "Discard readback changed byte "
                + (offset + i)
                + " expected="
                + Arrays.toString(expected));
  }
}
