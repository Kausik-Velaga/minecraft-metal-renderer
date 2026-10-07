package dev.kausik.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Checks tracked rendering and the permanent, state-preserving first-timestamp transition. */
public final class HazardSynchronizationTest {
  private static final String VERTEX =
      """
      #include <metal_stdlib>
      using namespace metal;
      vertex float4 main0(uint vertexId [[vertex_id]]) {
        const float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};
        return float4(p[vertexId],0,1);
      }
      """;
  private static final String FRAGMENT =
      """
      #include <metal_stdlib>
      using namespace metal;
      fragment float4 main0(texture2d<float> a [[texture(0)]],
                            texture2d<float> b [[texture(1)]], sampler s [[sampler(0)]]) {
        return .5*(a.sample(s,float2(.5))+b.sample(s,float2(.5)));
      }
      """;

  public static void main(String[] args) {
    boolean tracked = "1".equals(System.getenv("MINECRAFT_METAL_TRACKED_HAZARDS"));
    long device = MetalNative.createDevice();
    try (var pool = resource(MetalNative.createQueryPool(device, 2));
        var a = texture(device);
        var b = texture(device);
        var output = texture(device);
        var sampler =
            resource(MetalNative.createSampler(device, false, false, false, false, 1, 0));
        var readback = resource(MetalNative.createBuffer(device, 3 * 64, true, "Hazard readback"));
        var pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "Hazard transition",
                    VERTEX,
                    FRAGMENT,
                    new int[0],
                    new int[0],
                    new int[] {70, 15, 0, 1, 0, 0, 1, 0, 0},
                    -1,
                    false,
                    false,
                    false,
                    0,
                    0))) {
      checkMode(device, tracked ? "tracked-resources" : "global-fences");
      // These independent upload destinations precede the first marker in the same command buffer.
      upload(device, a.id, 255, 0, 0);
      upload(device, b.id, 0, 0, 255);
      MetalNative.beginRenderPass(
          device,
          "Hazard first timestamp inside pass",
          new long[] {output.id},
          new float[] {0, 1, 0, 1},
          0,
          Double.NaN,
          0,
          0,
          4,
          4);
      MetalNative.bindPipeline(device, pipeline.id);
      MetalNative.bindTexture(device, 2, 0, a.id, sampler.id);
      MetalNative.bindTexture(device, 2, 1, b.id, sampler.id);
      MetalNative.pushDebugGroup(device, "survives timestamp transition");
      MetalNative.scissor(device, 0, 0, 2, 4);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.writeTimestamp(device, pool.id, 0);
      checkMode(device, "global-fences-timestamps");
      // No rebind: the resumed pass must preserve resources, pipeline, and prior color pixels.
      MetalNative.scissor(device, 2, 0, 2, 4);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.writeTimestamp(device, pool.id, 1);
      MetalNative.popDebugGroup(device);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, a.id, 0, 0, 0, 4, 4, readback.id, 0);
      MetalNative.textureToBuffer(device, b.id, 0, 0, 0, 4, 4, readback.id, 64);
      MetalNative.textureToBuffer(device, output.id, 0, 0, 0, 4, 4, readback.id, 128);
      try (var fence = resource(MetalNative.createFence(device))) {
        MetalNative.submit(device);
        if (!MetalNative.awaitFence(fence.id, 10_000_000_000L))
          throw new AssertionError("Hazard transition GPU timeout");
      }
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 192);
      int[][] expected = {{255, 0, 0, 255}, {0, 0, 255, 255}, {128, 0, 128, 255}};
      for (int image = 0; image < 3; image++)
        for (int pixel = 0; pixel < 16; pixel++)
          for (int channel = 0; channel < 4; channel++) {
            int value = Byte.toUnsignedInt(result.get(image * 64 + pixel * 4 + channel));
            if (Math.abs(value - expected[image][channel]) > 1)
              throw new AssertionError(
                  "Hazard transition changed image "
                      + image
                      + " pixel "
                      + pixel
                      + " channel "
                      + channel
                      + ": "
                      + value);
          }
      long start = MetalNative.queryValue(pool.id, 0), end = MetalNative.queryValue(pool.id, 1);
      if (start <= 0 || end < start)
        throw new AssertionError("Timestamp ordering was not preserved");
      pool.close();
      checkMode(device, "global-fences-timestamps");
      System.out.println(
          "PASS: "
              + (tracked ? "tracked resources" : "default global fences")
              + ", dormant timestamp pool, active-pass transition, multiple resources, ordered"
              + " markers and permanent timestamp fallback");
    } finally {
      MetalNative.destroyDevice(device);
      if (MetalNative.liveResourceCount() != 0) throw new AssertionError("Leaked native resources");
    }
  }

  private static void checkMode(long device, String expected) {
    String actual = MetalNative.hazardSynchronizationMode(device);
    if (!actual.equals(expected))
      throw new AssertionError("Hazard mode " + actual + " expected " + expected);
  }

  private static Resource texture(long device) {
    return resource(
        MetalNative.createTexture(device, "RGBA8_UNORM", 4, 4, 1, 1, 15, "Hazard fixture"));
  }

  private static void upload(long device, long texture, int r, int g, int b) {
    ByteBuffer pixels = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
    for (int i = 0; i < 16; i++) pixels.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
    MetalNative.uploadTexture(device, texture, pixels.flip(), 0, 0, 0, 0, 0, 4, 4);
  }

  private static Resource resource(long id) {
    if (id == 0) throw new AssertionError("Native allocation failed");
    return new Resource(id);
  }

  private static final class Resource implements AutoCloseable {
    final long id;
    boolean closed;

    Resource(long id) {
      this.id = id;
    }

    public void close() {
      if (!closed) {
        MetalNative.release(id);
        closed = true;
      }
    }
  }
}
