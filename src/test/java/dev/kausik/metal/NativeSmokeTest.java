package dev.kausik.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Actual GPU checks, runnable without launching Minecraft or creating a window. */
public final class NativeSmokeTest {
  private static final int RGBA8 = 70;
  private static final int TEXTURE_USAGE = 1 | 2 | 4 | 8;

  public static void main(String[] args) {
    equal(0, MetalNative.liveResourceCount(), "initial native handles");
    long device = MetalNative.createDevice();
    if (device == 0) throw new AssertionError("No Metal device");
    try {
      System.out.println("Native smoke test GPU: " + MetalNative.deviceName(device));
      verifyBuffers(device);
      verifyTextures(device);
      verifyDepth(device);
      verifyRectangleClear(device);
      verifyMipClear(device);
      verifyMipRectangleClear(device);
      verifyPushConstants(device);
      verifyFloatRenderTargets(device);
      verifyOptionalDepthOutput(device);
      verifyTimestampCalibration(device);
      verifyRendering(device);
      verifyTexelBuffers(device);
      verifyQueryReuse(device);
      verifyRepeatedDisposal(device);
      equal(1, MetalNative.liveResourceCount(), "only the device handle remains");
      System.out.println(
          "PASS: Metal copies, mip clears, push constants, float MRT blending/depth output,"
              + " draw/scissor, texel reads, calibrated GPU timestamps, and zero leaked handles");
    } finally {
      MetalNative.destroyDevice(device);
      equal(0, MetalNative.liveResourceCount(), "native handles after device destruction");
    }
  }

  private static void verifyBuffers(long device) {
    ByteBuffer sourceBytes = bytes(300);
    for (int i = 0; i < sourceBytes.capacity(); i++) sourceBytes.put(i, (byte) (i * 37 + 11));
    try (Resource source = buffer(device, 512, false);
        Resource output = buffer(device, 512, true)) {
      MetalNative.mapBuffer(output.id, 0, 512).put(new byte[512]);
      MetalNative.writeBuffer(device, source.id, 32, sourceBytes, 7, 257);
      MetalNative.copyBuffer(device, source.id, 32, output.id, 96, 257);
      // Objects referenced by a recorded command must survive Java-side destruction.
      source.close();
      await(device);
      ByteBuffer result = MetalNative.mapBuffer(output.id, 0, 512);
      for (int i = 0; i < 512; i++) {
        int expected = i >= 96 && i < 353 ? Byte.toUnsignedInt(sourceBytes.get(i - 96 + 7)) : 0;
        equal(expected, Byte.toUnsignedInt(result.get(i)), "buffer byte " + i);
      }
    }
  }

  private static void verifyTextures(long device) {
    ByteBuffer pattern = bytes(8 * 8 * 4);
    for (int y = 0; y < 8; y++)
      for (int x = 0; x < 8; x++) {
        pattern.put((byte) (x * 31)).put((byte) (y * 29)).put((byte) 73).put((byte) 255);
      }
    try (Resource texture = texture(device, "RGBA8_UNORM", 8, 8, 4);
        Resource destination = texture(device, "RGBA8_UNORM", 8, 8, 4);
        Resource view = resource(MetalNative.createTextureView(texture.id, 1, 2));
        Resource sampler =
            resource(MetalNative.createSampler(device, false, true, false, true, 1, 3));
        Resource readback = buffer(device, 8 * 8 * 4, true)) {
      MetalNative.uploadTexture(device, texture.id, pattern, 0, 0, 0, 0, 0, 8, 8);
      MetalNative.copyTexture(device, texture.id, destination.id, 0, 0, 0, 0, 0, 8, 8);
      MetalNative.textureToBuffer(device, destination.id, 0, 0, 0, 8, 8, readback.id, 0);
      await(device);
      ByteBuffer actual = MetalNative.mapBuffer(readback.id, 0, pattern.capacity());
      for (int i = 0; i < pattern.capacity(); i++) {
        equal(
            Byte.toUnsignedInt(pattern.get(i)),
            Byte.toUnsignedInt(actual.get(i)),
            "texture byte " + i);
      }
      ByteBuffer mip = bytes(4 * 4 * 4);
      for (int i = 0; i < mip.capacity(); i++) mip.put(i, (byte) (i * 3));
      MetalNative.uploadTexture(device, texture.id, mip, 0, 1, 0, 0, 0, 4, 4);
      MetalNative.textureToBuffer(device, texture.id, 1, 0, 0, 4, 4, readback.id, 16);
      await(device);
      actual = MetalNative.mapBuffer(readback.id, 16, mip.capacity());
      for (int i = 0; i < mip.capacity(); i++) {
        equal(Byte.toUnsignedInt(mip.get(i)), Byte.toUnsignedInt(actual.get(i)), "mip byte " + i);
      }
    }
  }

  private static void verifyDepth(long device) {
    try (Resource depth = texture(device, "D32_FLOAT", 4, 4, 1);
        Resource readback = buffer(device, 4 * 4 * 4, true)) {
      MetalNative.clear(device, 0, 0, 0, 0, 0, depth.id, 0.25, 0, 0, 4, 4);
      MetalNative.textureToBuffer(device, depth.id, 0, 0, 0, 4, 4, readback.id, 0);
      await(device);
      ByteBuffer actual = MetalNative.mapBuffer(readback.id, 0, 64).order(ByteOrder.nativeOrder());
      for (int i = 0; i < 16; i++) {
        if (Math.abs(actual.getFloat(i * 4) - 0.25f) > 0.00001f) {
          throw new AssertionError("Depth clear mismatch at " + i + ": " + actual.getFloat(i * 4));
        }
      }
    }
  }

  private static void verifyRectangleClear(long device) {
    try (Resource color = texture(device, "RGBA8_UNORM", 4, 4, 1);
        Resource depth = texture(device, "D32_FLOAT", 4, 4, 1);
        Resource readback = buffer(device, 128, true)) {
      MetalNative.clear(device, color.id, 0, 0, 1, 1, depth.id, 0.25, 0, 0, 4, 4);
      MetalNative.clear(device, color.id, 1, 0, 0, 1, depth.id, 0.75, 1, 1, 2, 2);
      MetalNative.textureToBuffer(device, color.id, 0, 0, 0, 4, 4, readback.id, 0);
      MetalNative.textureToBuffer(device, depth.id, 0, 0, 0, 4, 4, readback.id, 64);
      await(device);
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 128).order(ByteOrder.nativeOrder());
      pixel(result, 4, 0, 0, 0, 0, 255, "outside rectangle clear");
      pixel(result, 4, 1, 1, 255, 0, 0, "inside rectangle clear");
      for (int y = 0; y < 4; y++)
        for (int x = 0; x < 4; x++) {
          float expected = x >= 1 && x < 3 && y >= 1 && y < 3 ? 0.75f : 0.25f;
          float actual = result.getFloat(64 + (y * 4 + x) * 4);
          if (actual != expected)
            throw new AssertionError("Rectangle depth clear: " + actual + " != " + expected);
        }
    }
  }

  private static void verifyMipClear(long device) {
    try (Resource color = texture(device, "RGBA8_UNORM", 8, 8, 4);
        Resource depth = texture(device, "D32_FLOAT", 8, 8, 4);
        Resource readback = buffer(device, 512, true)) {
      MetalNative.clearAll(device, color.id, 0, 1, 0, 1, depth.id, 0.5);
      for (int mip = 0; mip < 4; mip++) {
        int size = 8 >> mip;
        MetalNative.textureToBuffer(device, color.id, mip, 0, 0, size, size, readback.id, 0);
        MetalNative.textureToBuffer(device, depth.id, mip, 0, 0, size, size, readback.id, 256);
        await(device);
        ByteBuffer result =
            MetalNative.mapBuffer(readback.id, 0, 512).order(ByteOrder.nativeOrder());
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            pixel(result, size, x, y, 0, 255, 0, "cleared color mip " + mip);
            float value = result.getFloat(256 + (y * size + x) * 4);
            if (value != 0.5f)
              throw new AssertionError("Depth mip " + mip + " was not cleared: " + value);
          }
      }
    }
  }

  private static void verifyMipRectangleClear(long device) {
    try (Resource color = texture(device, "RGBA8_UNORM", 8, 8, 4);
        Resource depth = texture(device, "D32_FLOAT", 8, 8, 4);
        Resource readback = buffer(device, 512, true)) {
      MetalNative.clearAll(device, color.id, 0, 0, 1, 1, depth.id, 0.25);
      MetalNative.clearMip(device, color.id, 1, 0, 0, 1, depth.id, 0.75, 1, 1, 2, 2, 1);
      for (int mip = 0; mip < 4; mip++) {
        int size = 8 >> mip;
        MetalNative.textureToBuffer(device, color.id, mip, 0, 0, size, size, readback.id, 0);
        MetalNative.textureToBuffer(device, depth.id, mip, 0, 0, size, size, readback.id, 256);
        await(device);
        ByteBuffer result =
            MetalNative.mapBuffer(readback.id, 0, 512).order(ByteOrder.nativeOrder());
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) {
            boolean inside = mip == 1 && x >= 1 && x < 3 && y >= 1 && y < 3;
            pixel(
                result, size, x, y, inside ? 255 : 0, 0, inside ? 0 : 255, "mip rectangle " + mip);
            near(
                inside ? 0.75f : 0.25f,
                result.getFloat(256 + (y * size + x) * 4),
                "mip rectangle depth");
          }
      }
    }
  }

  private static final String FULLSCREEN_VERTEX =
      """
      #include <metal_stdlib>
      using namespace metal;
      vertex float4 main0(uint index [[vertex_id]]) {
          const float2 positions[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
          return float4(positions[index], 0.5, 1);
      }
      """;

  private static void verifyPushConstants(long device) {
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Push { float3 color; };
        fragment float4 main0(constant Push& value [[buffer(30)]]) { return float4(value.color,1); }
        """;
    ByteBuffer values = bytes(32);
    try (Resource target = texture(device, "RGBA8_UNORM", 8, 8, 1);
        Resource readback = buffer(device, 256, true);
        Resource query = resource(MetalNative.createQueryPool(device, 1));
        Resource pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "push-constants",
                    FULLSCREEN_VERTEX,
                    fragment,
                    new int[0],
                    new int[0],
                    new int[] {RGBA8, 15, 0, 1, 0, 0, 1, 0, 0},
                    -1,
                    false,
                    false,
                    false,
                    0,
                    0))) {
      MetalNative.beginRenderPass(
          device,
          "push constants",
          new long[] {target.id},
          new float[] {0, 0, 0, 1},
          0,
          Double.NaN,
          0,
          0,
          8,
          8);
      MetalNative.bindPipeline(device, pipeline.id);
      values.putFloat(4, 1).putFloat(8, 0).putFloat(12, 0);
      MetalNative.pushConstants(device, 2, 30, values, 4, 12);
      MetalNative.scissor(device, 0, 0, 4, 8);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      values.putFloat(4, 0).putFloat(8, 1);
      MetalNative.pushConstants(device, 2, 30, values, 4, 12);
      MetalNative.writeTimestamp(device, query.id, 0);
      MetalNative.scissor(device, 4, 0, 4, 8);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      values.putFloat(8, 0).putFloat(12, 1);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, target.id, 0, 0, 0, 8, 8, readback.id, 0);
      await(device);
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 256);
      pixel(result, 8, 1, 1, 255, 0, 0, "first immutable push constant");
      pixel(result, 8, 6, 6, 0, 255, 0, "push constants after encoder resume");
    }
  }

  private static void verifyFloatRenderTargets(long device) {
    String mrt =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 bounds [[color(0)]]; float4 moments [[color(1)]]; float depth [[depth(any)]]; };
        fragment Output main0() { return {float4(-2,3,4,0.5),float4(0.25,-0.5,2,0.25),0.625}; }
        """;
    String depthOnly =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float depth [[depth(any)]]; };
        fragment Output main0() { return {0.875}; }
        """;
    try (Resource bounds = texture(device, "RGBA32_FLOAT", 4, 4, 1);
        Resource moments = texture(device, "RGBA16_FLOAT", 4, 4, 1);
        Resource depth = texture(device, "D32_FLOAT", 4, 4, 1);
        Resource readback = buffer(device, 512, true);
        Resource pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "float MRT",
                    FULLSCREEN_VERTEX,
                    mrt,
                    new int[0],
                    new int[0],
                    new int[] {125, 15, 1, 1, 1, 4, 1, 1, 4, 115, 15, 1, 1, 1, 0, 1, 1, 0},
                    7,
                    true,
                    false,
                    false,
                    0,
                    0));
        Resource depthPipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "depth output",
                    FULLSCREEN_VERTEX,
                    depthOnly,
                    new int[0],
                    new int[0],
                    new int[0],
                    7,
                    true,
                    false,
                    false,
                    0,
                    0))) {
      MetalNative.beginRenderPass(
          device,
          "signed float MRT",
          new long[] {bounds.id, moments.id},
          new float[] {-Float.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0},
          depth.id,
          0,
          0,
          0,
          4,
          4);
      MetalNative.bindPipeline(device, pipeline.id);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, bounds.id, 0, 0, 0, 4, 4, readback.id, 0);
      MetalNative.textureToBuffer(device, moments.id, 0, 0, 0, 4, 4, readback.id, 256);
      MetalNative.textureToBuffer(device, depth.id, 0, 0, 0, 4, 4, readback.id, 384);
      await(device);
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 512).order(ByteOrder.nativeOrder());
      for (int pixel = 0; pixel < 16; pixel++) {
        near(-2, result.getFloat(pixel * 16), "signed MAX blend");
        near(3, result.getFloat(pixel * 16 + 4), "float green");
        near(4, result.getFloat(pixel * 16 + 8), "unclamped float blue");
        near(0.5f, result.getFloat(pixel * 16 + 12), "MAX alpha");
        near(0.5f, Float.float16ToFloat(result.getShort(256 + pixel * 8)), "MRT additive red");
        near(
            -1,
            Float.float16ToFloat(result.getShort(258 + pixel * 8)),
            "MRT signed additive green");
        near(4, Float.float16ToFloat(result.getShort(260 + pixel * 8)), "MRT additive blue");
        near(0.625f, result.getFloat(384 + pixel * 4), "fragment depth with color outputs");
      }
      MetalNative.beginRenderPass(
          device, "depth-only", new long[0], new float[0], depth.id, 0, 0, 0, 4, 4);
      MetalNative.bindPipeline(device, depthPipeline.id);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, depth.id, 0, 0, 0, 4, 4, readback.id, 0);
      await(device);
      for (int pixel = 0; pixel < 16; pixel++)
        near(0.875f, result.getFloat(pixel * 4), "depth-only fragment output");
    }
  }

  private static void verifyTimestampCalibration(long device) {
    long offset = MetalNative.timestampCalibrationOffset(device);
    long before = System.nanoTime();
    long calibrated = MetalNative.timestampNow(device) + offset;
    long after = System.nanoTime();
    if (calibrated < before - 10_000_000L || calibrated > after + 10_000_000L)
      throw new AssertionError(
          "Calibrated GPU timestamp differs from System.nanoTime by over 10 ms");
  }

  private static void verifyOptionalDepthOutput(long device) {
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 color [[color(0)]]; float depth [[depth(any)]]; };
        fragment Output main0() { return {float4(0,1,0,1),0.625}; }
        """;
    String fragmentWithoutDepth =
        """
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0() { return float4(0,1,0,1); }
        """;
    try (Resource color = texture(device, "RGBA8_UNORM", 4, 4, 1);
        Resource depth = texture(device, "D32_FLOAT", 4, 4, 1);
        Resource readback = buffer(device, 128, true);
        Resource pipeline =
            resource(
                MetalNative.createPipelineWithDepthVariants(
                    device,
                    "optional depth output",
                    FULLSCREEN_VERTEX,
                    fragment,
                    fragmentWithoutDepth,
                    new int[0],
                    new int[0],
                    new int[] {RGBA8, 15, 0, 1, 0, 0, 1, 0, 0},
                    7,
                    true,
                    false,
                    false,
                    0,
                    0))) {
      for (long attachment : new long[] {depth.id, 0}) {
        MetalNative.beginRenderPass(
            device,
            "optional depth",
            new long[] {color.id},
            new float[] {0, 0, 0, 1},
            attachment,
            attachment == 0 ? Double.NaN : 0,
            0,
            0,
            4,
            4);
        MetalNative.bindPipeline(device, pipeline.id);
        MetalNative.draw(device, 3, 0, 3, 1, 0);
        MetalNative.endRenderPass(device);
        MetalNative.textureToBuffer(device, color.id, 0, 0, 0, 4, 4, readback.id, 0);
        if (attachment != 0)
          MetalNative.textureToBuffer(device, depth.id, 0, 0, 0, 4, 4, readback.id, 64);
        await(device);
        ByteBuffer result =
            MetalNative.mapBuffer(readback.id, 0, 128).order(ByteOrder.nativeOrder());
        pixel(result, 4, 2, 2, 0, 255, 0, "optional depth color");
        if (attachment != 0) near(0.625f, result.getFloat(64), "optional depth output");
      }
    }
  }

  private static void near(float expected, float actual, String label) {
    if (!Float.isFinite(actual) || Math.abs(expected - actual) > 0.0001f)
      throw new AssertionError(label + ": " + actual + " != " + expected);
  }

  private static void verifyRendering(long device) {
    String vertex =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct VertexOutput { float4 position [[position]]; };
        vertex VertexOutput main0(uint index [[vertex_id]]) {
            const float2 positions[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
            return {float4(positions[index], 0.5, 1)};
        }
        """;
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct VertexOutput { float4 position [[position]]; };
        fragment float4 main0(VertexOutput input [[stage_in]]) {
            return input.position.y < 4 ? float4(1,0,0,1) : float4(0,0,1,1);
        }
        """;
    try (Resource target = texture(device, "RGBA8_UNORM", 8, 8, 1);
        Resource view = resource(MetalNative.createTextureView(target.id, 0, 1));
        Resource readback = buffer(device, 8 * 8 * 4, true);
        Resource queries = resource(MetalNative.createQueryPool(device, 4));
        Resource pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "native-smoke",
                    vertex,
                    fragment,
                    new int[0],
                    new int[0],
                    new int[] {RGBA8, 15, 0, 1, 0, 0, 1, 0, 0},
                    7,
                    false,
                    false,
                    false,
                    0,
                    0))) {
      for (int frame = 0; frame < 32; frame++) {
        MetalNative.writeTimestamp(device, queries.id, 0);
        MetalNative.beginRenderPass(
            device,
            "orientation",
            new long[] {view.id},
            new float[] {0, 0, 0, 1},
            0,
            Double.NaN,
            0,
            0,
            8,
            8);
        MetalNative.bindPipeline(device, pipeline.id);
        MetalNative.writeTimestamp(device, queries.id, 1);
        MetalNative.draw(device, 3, 0, 3, 1, 0);
        MetalNative.writeTimestamp(device, queries.id, 2);
        MetalNative.endRenderPass(device);
        MetalNative.writeTimestamp(device, queries.id, 3);
        MetalNative.textureToBuffer(device, target.id, 0, 0, 0, 8, 8, readback.id, 0);
        await(device);
        ByteBuffer iterationResult = MetalNative.mapBuffer(readback.id, 0, 256);
        pixel(iterationResult, 8, 1, 1, 255, 0, 0, "top-left fragment coordinate");
        pixel(iterationResult, 8, 6, 6, 0, 0, 255, "bottom-right fragment coordinate");
        long previous = 0;
        for (int query = 0; query < 4; query++) {
          long timestamp = waitQuery(queries.id, query);
          if (timestamp <= 0 || timestamp < previous)
            throw new AssertionError(
                "Non-monotonic GPU timestamp " + query + ": " + timestamp + " after " + previous);
          previous = timestamp;
        }
      }
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 256);

      MetalNative.beginRenderPass(
          device,
          "scissor",
          new long[] {view.id},
          new float[] {0, 0, 0, 1},
          0,
          Double.NaN,
          0,
          0,
          8,
          8);
      MetalNative.bindPipeline(device, pipeline.id);
      MetalNative.scissor(device, 0, 0, 4, 4);
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, target.id, 0, 0, 0, 8, 8, readback.id, 0);
      await(device);
      pixel(result, 8, 1, 1, 255, 0, 0, "inside scissor");
      pixel(result, 8, 6, 1, 0, 0, 0, "right of scissor");
      pixel(result, 8, 1, 6, 0, 0, 0, "below scissor");
    }
  }

  private static void verifyTexelBuffers(long device) {
    String vertex =
        """
        #include <metal_stdlib>
        using namespace metal;
        vertex float4 main0(uint index [[vertex_id]]) {
            const float2 positions[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
            return float4(positions[index], 0.5, 1);
        }
        """;
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        fragment float4 main0(float4 position [[position]], texture_buffer<uint> values [[texture(0)]]) {
            uint value = values.read(position.x < 4 ? 0u : 90908u).r;
            return float4(value == 7 ? 1 : 0, value == 11 ? 1 : 0, 0, 1);
        }
        """;
    // Odd texel counts occur in real Minecraft terrain index buffers.
    ByteBuffer content = bytes(181818);
    content.putShort(0, (short) 7).putShort(181816, (short) 11);
    try (Resource input = buffer(device, content.capacity(), false);
        Resource target = texture(device, "RGBA8_UNORM", 8, 8, 1);
        Resource readback = buffer(device, 256, true);
        Resource pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "texel-buffer-smoke",
                    vertex,
                    fragment,
                    new int[0],
                    new int[0],
                    new int[] {RGBA8, 15, 0, 1, 0, 0, 1, 0, 0},
                    -1,
                    false,
                    false,
                    false,
                    0,
                    0))) {
      MetalNative.writeBuffer(device, input.id, 0, content, 0, content.capacity());
      MetalNative.beginRenderPass(
          device,
          "texel buffer",
          new long[] {target.id},
          new float[] {0, 0, 0, 1},
          0,
          Double.NaN,
          0,
          0,
          8,
          8);
      MetalNative.bindPipeline(device, pipeline.id);
      MetalNative.bindTexelBuffer(device, 2, 0, input.id, 0, content.capacity(), "R16_UINT");
      MetalNative.draw(device, 3, 0, 3, 1, 0);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, target.id, 0, 0, 0, 8, 8, readback.id, 0);
      input.close(); // Cached native texture views must also survive until the draw completes.
      await(device);
      ByteBuffer result = MetalNative.mapBuffer(readback.id, 0, 256);
      pixel(result, 8, 1, 1, 255, 0, 0, "first texel");
      pixel(result, 8, 6, 6, 0, 255, 0, "last texel");
    }
  }

  private static void verifyQueryReuse(long device) {
    try (Resource queries = resource(MetalNative.createQueryPool(device, 1))) {
      long lowerBound = 0;
      for (int frame = 0; frame < 64; frame++) {
        lowerBound = MetalNative.timestampNow(device);
        MetalNative.writeTimestamp(device, queries.id, 0);
        MetalNative.submit(device);
        long published = MetalNative.queryValue(queries.id, 0);
        if (published >= 0 && published < lowerBound) {
          throw new AssertionError("Reused query published an older submission's timestamp");
        }
      }
      await(device);
      if (waitQuery(queries.id, 0) < lowerBound) {
        throw new AssertionError("Final reused query did not report the latest submission");
      }
    }
  }

  private static void verifyRepeatedDisposal(long device) {
    ByteBuffer input = bytes(64);
    try (Resource output = buffer(device, 64, true)) {
      for (int frame = 0; frame < 64; frame++) {
        input.putInt(0, frame);
        try (Resource transientBuffer = buffer(device, 64, false)) {
          MetalNative.writeBuffer(device, transientBuffer.id, 0, input, 0, 64);
          MetalNative.copyBuffer(device, transientBuffer.id, 0, output.id, 0, 64);
        }
        MetalNative.submit(device);
      }
      await(device);
      equal(
          63,
          MetalNative.mapBuffer(output.id, 0, 64).order(ByteOrder.nativeOrder()).getInt(0),
          "final frame after repeated allocation/disposal");
    }
  }

  private static Resource buffer(long device, int size, boolean shared) {
    return resource(MetalNative.createBuffer(device, size, shared, "smoke-buffer"));
  }

  private static Resource texture(long device, String format, int width, int height, int mips) {
    return resource(
        MetalNative.createTexture(
            device, format, width, height, 1, mips, TEXTURE_USAGE, "smoke-texture"));
  }

  private static ByteBuffer bytes(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }

  private static Resource resource(long id) {
    if (id == 0) throw new AssertionError("Native resource allocation returned zero");
    return new Resource(id);
  }

  private static void await(long device) {
    try (Resource fence = resource(MetalNative.createFence(device))) {
      if (MetalNative.awaitFence(fence.id, 0))
        throw new AssertionError("Unsubmitted fence was already signalled");
      MetalNative.submit(device);
      if (!MetalNative.awaitFence(fence.id, 10_000_000_000L))
        throw new AssertionError("GPU timed out after 10 seconds");
    }
  }

  private static void pixel(
      ByteBuffer data, int width, int x, int y, int red, int green, int blue, String label) {
    int position = (y * width + x) * 4;
    equal(red, Byte.toUnsignedInt(data.get(position)), label + " red");
    equal(green, Byte.toUnsignedInt(data.get(position + 1)), label + " green");
    equal(blue, Byte.toUnsignedInt(data.get(position + 2)), label + " blue");
    equal(255, Byte.toUnsignedInt(data.get(position + 3)), label + " alpha");
  }

  private static void equal(int expected, int actual, String label) {
    if (expected != actual)
      throw new AssertionError(label + ": expected " + expected + ", got " + actual);
  }

  private static long waitQuery(long pool, int index) {
    long deadline = System.nanoTime() + 1_000_000_000L;
    long value;
    while ((value = MetalNative.queryValue(pool, index)) < 0) {
      if (System.nanoTime() > deadline)
        throw new AssertionError("GPU query result was not published");
      Thread.onSpinWait();
    }
    return value;
  }

  private static final class Resource implements AutoCloseable {
    private long id;

    private Resource(long id) {
      this.id = id;
    }

    @Override
    public void close() {
      if (id != 0) {
        MetalNative.release(id);
        id = 0;
      }
    }
  }
}
