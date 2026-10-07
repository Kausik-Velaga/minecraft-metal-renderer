package dev.kausik.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Matches GPU-generated command batches against the existing indirect draw loop. */
public final class IcbSmokeTest {
  private static final int WIDTH = 64, HEIGHT = 32, IMAGE_BYTES = WIDTH * HEIGHT * 4;
  private static final String VERTEX =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Output { float4 position [[position]]; float4 color; };
      vertex Output main0(uint vertexId [[vertex_id]], uint instanceId [[instance_id]],
                          const device float2* vertices [[buffer(0)]],
                          constant float4& tint [[buffer(16)]], constant float4& transform [[buffer(30)]]) {
          uint row = vertexId / 4;
          float2 position = float2(-1.0 + (float(instanceId) + 0.5) * 0.25,
                                   -1.0 + (float(row) + 0.5) * 0.5);
          position += vertices[vertexId] * float2(0.115, 0.23);
          return {float4(position,transform.z,1), float4((row+1)*0.2,(instanceId+1)*0.1,0.75,1)*tint};
      }
      """;
  private static final String DIRECT_FRAGMENT =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Output { float4 position [[position]]; float4 color; };
      fragment float4 main0(Output in [[stage_in]], texture2d<float> image [[texture(0)]],
                            sampler filtering [[sampler(0)]], constant float4& tint [[buffer(16)]]) {
          return in.color * tint * image.sample(filtering,float2(0.25,0.25));
      }
      """;

  private static final String FRAGMENT =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Output { float4 position [[position]]; float4 color; };
      struct Resources {
        texture2d<float> image [[id(0)]]; depth2d<float> shadow [[id(1)]];
        sampler filtering [[id(128)]]; sampler comparison [[id(129)]];
      };
      fragment float4 main0(Output in [[stage_in]], constant Resources& resources [[buffer(29)]],
                            constant float4& tint [[buffer(16)]]) {
          return in.color * tint * resources.image.sample(resources.filtering,float2(0.25,0.25))
              * resources.shadow.sample_compare(resources.comparison,float2(0.5),0.5);
      }
      """;

  public static void main(String[] args) {
    if (MetalNative.liveResourceCount() != 0)
      throw new AssertionError("Preexisting native resources");
    long device = MetalNative.createDevice();
    try {
      // Pipeline support is immutable. Compile it with ICB support, then compare both paths.
      MetalNative.configureIndirectCommands(device, true, 1);
      try (var pipeline =
              resource(
                  MetalNative.createPipelineWithArgumentBuffers(
                      device,
                      "ICB contract",
                      VERTEX,
                      FRAGMENT,
                      null,
                      new int[0],
                      new int[0],
                      new int[] {70, 15, 0, 1, 0, 0, 1, 0, 0},
                      3,
                      true,
                      false,
                      false,
                      0,
                      0,
                      new int[] {2, 2, 0, 1, 2, 2, 1, 1}));
          var directPipeline =
              resource(
                  MetalNative.createPipeline(
                      device,
                      "ICB direct texture fallback",
                      VERTEX,
                      DIRECT_FRAGMENT,
                      new int[0],
                      new int[0],
                      new int[] {70, 15, 0, 1, 0, 0, 1, 0, 0},
                      3,
                      true,
                      false,
                      false,
                      0,
                      0));
          var color = texture(device, "RGBA8_UNORM", WIDTH, HEIGHT);
          var depth = texture(device, "D32_FLOAT", WIDTH, HEIGHT);
          var image = texture(device, "RGBA8_UNORM", 1, 1);
          var shadowMap = texture(device, "D32_FLOAT", 1, 1);
          var comparison =
              resource(
                  MetalNative.createComparisonSampler(device, false, false, true, true, 1, 0));
          var sampler =
              resource(MetalNative.createSampler(device, false, false, false, false, 1, 0));
          var vertices = buffer(device, 32 + 16 * 8, false);
          var uniform = buffer(device, 80, false);
          var readback = buffer(device, IMAGE_BYTES * 2, true)) {
        MetalNative.clearAll(device, 0, 0, 0, 0, 0, shadowMap.id, 1);
        ByteBuffer white = bytes(4).putInt(-1).flip();
        MetalNative.uploadTexture(device, image.id, white, 0, 0, 0, 0, 0, 1, 1);
        ByteBuffer positions = bytes(16 * 8);
        for (int group = 0; group < 4; group++)
          for (float value : new float[] {-1, -1, 1, -1, -1, 1, 1, 1}) positions.putFloat(value);
        MetalNative.writeBuffer(device, vertices.id, 32, positions.flip(), 0, positions.capacity());
        ByteBuffer tint = bytes(16).putFloat(.8f).putFloat(.9f).putFloat(1).putFloat(1).flip();
        MetalNative.writeBuffer(device, uniform.id, 64, tint, 0, 16);
        for (int frame = 0; frame < 8; frame++) {
          byte[] expected =
              render(
                  device,
                  pipeline.id,
                  color.id,
                  depth.id,
                  image.id,
                  sampler.id,
                  shadowMap.id,
                  comparison.id,
                  vertices.id,
                  uniform.id,
                  readback.id,
                  false,
                  1,
                  frame,
                  true);
          byte[] actual =
              render(
                  device,
                  pipeline.id,
                  color.id,
                  depth.id,
                  image.id,
                  sampler.id,
                  shadowMap.id,
                  comparison.id,
                  vertices.id,
                  uniform.id,
                  readback.id,
                  true,
                  1,
                  frame,
                  true);
          if (!Arrays.equals(expected, actual)) {
            int i = Arrays.mismatch(expected, actual);
            throw new AssertionError(
                "ICB pixel/depth mismatch in frame "
                    + frame
                    + " byte "
                    + i
                    + ": "
                    + Byte.toUnsignedInt(actual[i])
                    + " expected "
                    + Byte.toUnsignedInt(expected[i]));
          }
          if (frame == 0) {
            int colored = 0;
            for (int i = 0; i < IMAGE_BYTES; i += 4)
              if (expected[i] != 0 || expected[i + 1] != 0 || expected[i + 2] != 0) colored++;
            if (colored < 300)
              throw new AssertionError("Indirect fixture did not draw its geometry: " + colored);
          }
        }
        byte[] expected =
            render(
                device,
                pipeline.id,
                color.id,
                depth.id,
                image.id,
                sampler.id,
                shadowMap.id,
                comparison.id,
                vertices.id,
                uniform.id,
                readback.id,
                false,
                1,
                0,
                true);
        byte[] fallback =
            render(
                device,
                pipeline.id,
                color.id,
                depth.id,
                image.id,
                sampler.id,
                shadowMap.id,
                comparison.id,
                vertices.id,
                uniform.id,
                readback.id,
                true,
                1000,
                0,
                true);
        if (!Arrays.equals(expected, fallback))
          throw new AssertionError("Small batch fallback changed pixels");
        byte[] unsupported =
            render(
                device,
                directPipeline.id,
                color.id,
                depth.id,
                image.id,
                sampler.id,
                shadowMap.id,
                comparison.id,
                vertices.id,
                uniform.id,
                readback.id,
                true,
                1,
                0,
                false);
        if (!Arrays.equals(expected, unsupported))
          throw new AssertionError("Unsupported interface fallback changed pixels");
      }
      verifyBlendOrder(device);
      verifySampledResourceRetirement(device);
      if (MetalNative.liveResourceCount() != 1) throw new AssertionError("Leaked native wrappers");
      System.out.println(
          "PASS: GPU ICB and loop pixels/depth are identical across indexed16/32, nonindexed,"
              + " command offsets, signed base vertices, base instances, zero draws, inherited"
              + " state, ring reuse, sampled/texel resource retirement and threshold fallback");
    } finally {
      MetalNative.destroyDevice(device);
      if (MetalNative.liveResourceCount() != 0)
        throw new AssertionError("Native resources survived device close");
    }
  }

  private static byte[] render(
      long device,
      long pipeline,
      long color,
      long depth,
      long image,
      long sampler,
      long shadowMap,
      long comparison,
      long vertices,
      long uniform,
      long readback,
      boolean enabled,
      int threshold,
      int frame,
      boolean supportsIcb) {
    MetalNative.configureIndirectCommands(device, enabled, threshold);
    long before = MetalNative.indirectCommandExecutions(device);
    long[] splitBefore = MetalNative.indirectSplitStatistics(device);
    try (var shortIndices = buffer(device, 18 * 2, false);
        var intIndices = buffer(device, 18 * 4, false);
        var parameters = buffer(device, 64 + 6 * 20 + 2 * 20 + 3 * 16, false)) {
      ByteBuffer shorts = bytes(36), ints = bytes(72);
      for (int group = 0; group < 3; group++)
        for (int v : new int[] {0, 1, 2, 2, 1, 3}) {
          shorts.putShort((short) (group * 4 + v));
          ints.putInt(group * 4 + v);
        }
      MetalNative.writeBuffer(device, shortIndices.id, 0, shorts.flip(), 0, 36);
      MetalNative.writeBuffer(device, intIndices.id, 0, ints.flip(), 0, 72);
      ByteBuffer commands = bytes(6 * 20 + 2 * 20 + 3 * 16);
      boolean empty = (frame & 1) != 0;
      indexed(commands, empty ? 0 : 6, 1, 0, 0, 0);
      indexed(commands, 6, 2, 0, 4, 1);
      indexed(commands, 6, 1, 6, -4, 4);
      indexed(commands, 6, 1, 6, 4, 6);
      indexed(commands, 0, 1, 0x70000000, 0, 0);
      indexed(commands, 6, 0, 0x70000000, 0, 0);
      indexed(commands, 6, 1, 12, -4, 7);
      indexed(commands, empty ? 0 : 6, 1, 12, 4, 3);
      direct(commands, 4, 1, 12, 5);
      direct(commands, 0, 1, 0x70000000, 0);
      direct(commands, 4, 0, 0x70000000, 0);
      MetalNative.writeBuffer(device, parameters.id, 64, commands.flip(), 0, commands.capacity());
      MetalNative.beginRenderPass(
          device,
          "ICB state preservation",
          new long[] {color},
          new float[] {0, 0, 0, 1},
          depth,
          1,
          0,
          0,
          WIDTH,
          HEIGHT);
      MetalNative.bindPipeline(device, pipeline);
      MetalNative.bindVertexBuffer(device, 0, vertices, 32);
      MetalNative.bindUniform(device, 3, 16, uniform, 64, 16);
      MetalNative.bindTexture(device, 2, 0, image, sampler);
      MetalNative.bindTexture(device, 2, 1, shadowMap, comparison);
      ByteBuffer transform = bytes(16).putFloat(0).putFloat(0).putFloat(.375f).putFloat(1).flip();
      MetalNative.pushConstants(device, 1, 30, transform, 0, 16);
      MetalNative.pushDebugGroup(device, "ICB nested group");
      MetalNative.draw(device, 4, 0, 4, 1, 7);
      MetalNative.scissor(device, 1, 1, WIDTH - 2, HEIGHT - 2);
      MetalNative.drawIndirect(device, 3, parameters.id, 64, 6, shortIndices.id, false);
      MetalNative.drawIndirect(device, 3, parameters.id, 64 + 6 * 20, 2, intIndices.id, true);
      MetalNative.drawIndirect(device, 4, parameters.id, 64 + 8 * 20, 3, 0, false);
      MetalNative.drawIndirect(device, 3, parameters.id, 64, 0, shortIndices.id, false);
      // Direct work after all resume operations verifies the restored pipeline and bindings.
      MetalNative.draw(device, 4, 12, 4, 1, 0);
      MetalNative.popDebugGroup(device);
      MetalNative.endRenderPass(device);
      MetalNative.textureToBuffer(device, color, 0, 0, 0, WIDTH, HEIGHT, readback, 0);
      MetalNative.textureToBuffer(device, depth, 0, 0, 0, WIDTH, HEIGHT, readback, IMAGE_BYTES);
      // GPU-written commands keep native resources resident after callers release their handles.
      shortIndices.close();
      intIndices.close();
      parameters.close();
      await(device);
    }
    long executions = MetalNative.indirectCommandExecutions(device) - before;
    long expected = enabled && supportsIcb && threshold <= 2 ? 3 : 0;
    if (executions != expected)
      throw new AssertionError("Expected " + expected + " ICB batches, observed " + executions);
    assertSplitDelta(device, splitBefore, 0, expected, 2L * WIDTH * HEIGHT * 8);
    byte[] result = new byte[IMAGE_BYTES * 2];
    MetalNative.mapBuffer(readback, 0, result.length).get(result);
    return result;
  }

  private static void verifyBlendOrder(long device) {
    MetalNative.configureIndirectCommands(device, true, 1);
    String vertex =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 position [[position]]; float4 color; };
        vertex Output main0(uint vertexId [[vertex_id]],uint instanceId [[instance_id]]) {
          const float2 p[4]={float2(-1,-1),float2(1,-1),float2(-1,1),float2(1,1)};
          return {float4(p[vertexId],0,1),instanceId==0?float4(1,0,0,.5):float4(0,0,1,.5)};
        }
        """;
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 position [[position]]; float4 color; };
        fragment float4 main0(Output in [[stage_in]]) {return in.color;}
        """;
    try (var pipeline =
            resource(
                MetalNative.createPipeline(
                    device,
                    "ICB blending order",
                    vertex,
                    fragment,
                    new int[0],
                    new int[0],
                    new int[] {70, 15, 1, 4, 5, 0, 1, 5, 0},
                    -1,
                    false,
                    false,
                    false,
                    0,
                    0));
        var color = texture(device, "RGBA8_UNORM", 4, 4);
        var parameters = buffer(device, 48, false);
        var readback = buffer(device, 128, true)) {
      ByteBuffer commands = bytes(48);
      direct(commands, 4, 1, 0, 0);
      direct(commands, 0, 1, 0, 99);
      direct(commands, 4, 1, 0, 1);
      MetalNative.writeBuffer(device, parameters.id, 0, commands.flip(), 0, 48);
      for (int variant = 0; variant < 2; variant++) {
        MetalNative.configureIndirectCommands(device, variant == 1, 1);
        long before = MetalNative.indirectCommandExecutions(device);
        MetalNative.beginRenderPass(
            device,
            "ICB transparent order",
            new long[] {color.id},
            new float[] {0, 0, 0, 0},
            0,
            Double.NaN,
            0,
            0,
            4,
            4);
        MetalNative.bindPipeline(device, pipeline.id);
        MetalNative.drawIndirect(device, 4, parameters.id, 0, 3, 0, false);
        MetalNative.endRenderPass(device);
        MetalNative.textureToBuffer(device, color.id, 0, 0, 0, 4, 4, readback.id, variant * 64);
        if (MetalNative.indirectCommandExecutions(device) - before != variant)
          throw new AssertionError("Blend fixture did not select expected path");
      }
      await(device);
      ByteBuffer pixels = MetalNative.mapBuffer(readback.id, 0, 128);
      for (int i = 0; i < 64; i++)
        if (pixels.get(i) != pixels.get(64 + i))
          throw new AssertionError("ICB reordered blended primitives");
      if (Math.abs(Byte.toUnsignedInt(pixels.get(0)) - 64) > 1
          || Math.abs(Byte.toUnsignedInt(pixels.get(2)) - 128) > 1)
        throw new AssertionError("Blended fixture did not produce blue over red");
    }
  }

  private static void verifySampledResourceRetirement(long device) {
    MetalNative.configureIndirectCommands(device, true, 1);
    String vertex =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 position [[position]]; float4 color; };
        struct Resources {
          texture2d<float> image [[id(0)]]; texture_buffer<float> values [[id(2)]];
          sampler filtering [[id(128)]];
        };
        vertex Output main0(uint vertexId [[vertex_id]],
                            constant Resources& resources [[buffer(29)]]) {
          const float2 p[4]={float2(-1,-1),float2(1,-1),float2(-1,1),float2(1,1)};
          float4 color = resources.image.sample(resources.filtering,float2(.5,.5),level(0.0))
              + resources.values.read(0u);
          return {float4(p[vertexId],0,1),color};
        }
        """;
    String fragment =
        """
        #include <metal_stdlib>
        using namespace metal;
        struct Output { float4 position [[position]]; float4 color; };
        struct Resources {
          texture2d<float> image [[id(1)]]; texture_buffer<float> values [[id(3)]];
          sampler filtering [[id(129)]];
        };
        fragment float4 main0(Output in [[stage_in]],
                              constant Resources& resources [[buffer(29)]]) {
          return (in.color + resources.image.sample(resources.filtering,float2(1.25,.5),level(0.0))
              + resources.values.read(0u)) * .25;
        }
        """;
    try (var pipeline =
            resource(
                MetalNative.createPipelineWithArgumentBuffers(
                    device,
                    "ICB sampled resource retirement",
                    vertex,
                    fragment,
                    null,
                    new int[0],
                    new int[0],
                    new int[] {70, 15, 0, 1, 0, 0, 1, 0, 0},
                    -1,
                    false,
                    false,
                    false,
                    0,
                    0,
                    new int[] {1, 1, 0, 1, 2, 2, 1, 1, 1, 1, 2, 0, 2, 2, 3, 0}));
        var color = texture(device, "RGBA8_UNORM", 24, 8);
        var emptyTarget = texture(device, "RGBA8_UNORM", 1, 1);
        var readback = buffer(device, 24 * 8 * 4, true)) {
      for (int round = 0; round < 4; round++) {
        byte[] expected =
            renderRetiredResources(
                device, pipeline.id, color.id, emptyTarget.id, readback.id, false);
        byte[] actual =
            renderRetiredResources(
                device, pipeline.id, color.id, emptyTarget.id, readback.id, true);
        if (!Arrays.equals(expected, actual))
          throw new AssertionError(
              "ICB changed a rebound sampled/texel resource in round " + round);
      }
    }
  }

  private static byte[] renderRetiredResources(
      long device, long pipeline, long color, long emptyTarget, long readback, boolean enabled) {
    MetalNative.configureIndirectCommands(device, enabled, 1);
    long before = MetalNative.indirectCommandExecutions(device);
    long[] splitBefore = MetalNative.indirectSplitStatistics(device);
    // Upload before the render pass. Every draw uses different textures, sampler settings, and
    // offset texel-buffer views; later bindings must not mutate an earlier draw's argument record.
    SampledFixture[] fixtures = new SampledFixture[4];
    try (var parameters = buffer(device, 32, false)) {
      for (int phase = 0; phase < fixtures.length; phase++)
        fixtures[phase] = sampledFixture(device, phase);
      ByteBuffer commands = bytes(32);
      direct(commands, 4, 1, 0, 0);
      direct(commands, 0, 1, 0, 0);
      MetalNative.writeBuffer(device, parameters.id, 0, commands.flip(), 0, 32);
      MetalNative.beginRenderPass(
          device,
          "ICB sample rebinding",
          new long[] {color},
          new float[] {0, 0, 0, 0},
          0,
          Double.NaN,
          0,
          0,
          24,
          8);
      MetalNative.bindPipeline(device, pipeline);
      for (int phase = 0; phase < 3; phase++) {
        fixtures[phase].bind(device);
        if (phase > 0) fixtures[phase - 1].close();
        MetalNative.scissor(device, phase * 8, 0, 8, 8);
        MetalNative.drawIndirect(device, 4, parameters.id, 0, 2, 0, false);
      }
      // Remove the last drawn bindings from RenderState before releasing their handles. A fresh
      // pass also drops its strong texture/view/sampler references before the GPU is submitted.
      fixtures[3].bind(device);
      fixtures[2].close();
      MetalNative.endRenderPass(device);
      MetalNative.beginRenderPass(
          device,
          "ICB release retained state",
          new long[] {emptyTarget},
          new float[] {0, 0, 0, 0},
          0,
          Double.NaN,
          0,
          0,
          1,
          1);
      MetalNative.endRenderPass(device);
      fixtures[3].close();
      MetalNative.textureToBuffer(device, color, 0, 0, 0, 24, 8, readback, 0);
      parameters.close();
      await(device);
      long executions = MetalNative.indirectCommandExecutions(device) - before;
      if (executions != (enabled ? 3 : 0))
        throw new AssertionError(
            "Retirement fixture selected the wrong command path: " + executions);
      assertSplitDelta(device, splitBefore, enabled ? 1 : 0, enabled ? 2 : 0, 2L * 24 * 8 * 4);
      byte[] result = new byte[24 * 8 * 4];
      MetalNative.mapBuffer(readback, 0, result.length).get(result);
      for (int y = 0; y < 8; y++)
        for (int x = 0; x < 24; x++)
          for (int channel = 0; channel < 4; channel++) {
            int actual = Byte.toUnsignedInt(result[(y * 24 + x) * 4 + channel]);
            int expected = fixtures[x / 8].expected[channel];
            if (Math.abs(actual - expected) > 1)
              throw new AssertionError(
                  "Retired resource changed pixel "
                      + x
                      + ","
                      + y
                      + " channel "
                      + channel
                      + ": "
                      + actual
                      + " expected "
                      + expected);
          }
      return result;
    } finally {
      for (SampledFixture fixture : fixtures) if (fixture != null) fixture.close();
    }
  }

  private static SampledFixture sampledFixture(long device, int phase) {
    int[] vertexLeft = {16 + phase * 8, 32, 48}, vertexRight = {96, 112 + phase * 8, 128};
    int[] fragmentLeft = {144, 160, 176 - phase * 8}, fragmentRight = {192, 208, 224};
    int[] vertexValues = {phase * 16, 48, 80}, fragmentValues = {64, 112 - phase * 8, 32};
    boolean linear = (phase & 1) != 0, repeat = (phase & 1) == 0;
    var fixture =
        new SampledFixture(
            texture(device, "RGBA8_UNORM", 2, 1),
            texture(device, "RGBA8_UNORM", 2, 1),
            buffer(device, 512, false),
            buffer(device, 512, false),
            resource(MetalNative.createSampler(device, false, false, linear, linear, 1, 0)),
            resource(MetalNative.createSampler(device, repeat, false, false, false, 1, 0)),
            new int[4]);
    uploadPair(device, fixture.vertexImage.id, vertexLeft, vertexRight);
    uploadPair(device, fixture.fragmentImage.id, fragmentLeft, fragmentRight);
    for (int stage = 0; stage < 2; stage++) {
      ByteBuffer values = bytes(16);
      for (int value : stage == 0 ? vertexValues : fragmentValues) values.putFloat(value / 255f);
      values.putFloat(1).flip();
      MetalNative.writeBuffer(
          device,
          stage == 0 ? fixture.vertexValues.id : fixture.fragmentValues.id,
          256,
          values,
          0,
          16);
    }
    for (int channel = 0; channel < 3; channel++) {
      float vertexSample =
          linear ? (vertexLeft[channel] + vertexRight[channel]) * .5f : vertexRight[channel];
      int fragmentSample = repeat ? fragmentLeft[channel] : fragmentRight[channel];
      fixture.expected[channel] =
          Math.round(
              (vertexSample + fragmentSample + vertexValues[channel] + fragmentValues[channel])
                  * .25f);
    }
    fixture.expected[3] = 255;
    return fixture;
  }

  private static void uploadPair(long device, long image, int[] left, int[] right) {
    ByteBuffer pixels = bytes(8);
    for (int[] texel : new int[][] {left, right}) {
      for (int channel : texel) pixels.put((byte) channel);
      pixels.put((byte) 255);
    }
    MetalNative.uploadTexture(device, image, pixels.flip(), 0, 0, 0, 0, 0, 2, 1);
  }

  private record SampledFixture(
      Resource vertexImage,
      Resource fragmentImage,
      Resource vertexValues,
      Resource fragmentValues,
      Resource vertexSampler,
      Resource fragmentSampler,
      int[] expected)
      implements AutoCloseable {
    void bind(long device) {
      MetalNative.bindTexture(device, 1, 0, vertexImage.id, vertexSampler.id);
      MetalNative.bindTexture(device, 2, 1, fragmentImage.id, fragmentSampler.id);
      MetalNative.bindTexelBuffer(device, 1, 2, vertexValues.id, 256, 16, "RGBA32_FLOAT");
      MetalNative.bindTexelBuffer(device, 2, 3, fragmentValues.id, 256, 16, "RGBA32_FLOAT");
    }

    public void close() {
      vertexImage.close();
      fragmentImage.close();
      vertexValues.close();
      fragmentValues.close();
      vertexSampler.close();
      fragmentSampler.close();
    }
  }

  private static void indexed(
      ByteBuffer out, int count, int instances, int first, int baseVertex, int baseInstance) {
    out.putInt(count).putInt(instances).putInt(first).putInt(baseVertex).putInt(baseInstance);
  }

  private static void assertSplitDelta(
      long device, long[] before, long first, long after, long attachmentBytesPerSplit) {
    long[] actual = MetalNative.indirectSplitStatistics(device);
    long[] expected = {
      first, after, first * attachmentBytesPerSplit, after * attachmentBytesPerSplit
    };
    for (int i = 0; i < expected.length; i++)
      if (actual[i] - before[i] != expected[i])
        throw new AssertionError(
            "ICB split diagnostic "
                + i
                + ": "
                + (actual[i] - before[i])
                + " expected "
                + expected[i]);
  }

  private static void direct(
      ByteBuffer out, int count, int instances, int first, int baseInstance) {
    out.putInt(count).putInt(instances).putInt(first).putInt(baseInstance);
  }

  private static void await(long device) {
    try (var fence = resource(MetalNative.createFence(device))) {
      MetalNative.submit(device);
      if (!MetalNative.awaitFence(fence.id, 10_000_000_000L))
        throw new AssertionError("ICB GPU timeout");
    }
  }

  private static ByteBuffer bytes(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }

  private static Resource buffer(long device, int size, boolean shared) {
    return resource(MetalNative.createBuffer(device, size, shared, "ICB fixture"));
  }

  private static Resource texture(long device, String format, int w, int h) {
    return resource(MetalNative.createTexture(device, format, w, h, 1, 1, 15, "ICB fixture"));
  }

  private static Resource resource(long handle) {
    if (handle == 0) throw new AssertionError("Allocation failed");
    return new Resource(handle);
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
