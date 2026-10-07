package dev.kausik.metal;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** CPU ICB population/reuse, source publication, live state, and conservative invalidation. */
public final class IcbReuseSmokeTest {
  private static final int SIZE = 8, BYTES = SIZE * SIZE * 4, OFFSET = 64;
  private static final int CPU_INDIRECT =
      GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_INDIRECT_PARAMETERS;
  private static final String VERTEX =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Output { float4 position [[position]]; float4 color; };
      vertex Output main0(uint vertexId [[vertex_id]], uint instanceId [[instance_id]],
                          constant float4& tint [[buffer(16)]]) {
        const float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)};
        return {float4(p[vertexId%3],0,1),
            float4(.2+.1*float(vertexId/3),.2+.1*float(instanceId%5),.8,1)*tint};
      }
      """;
  private static final String FRAGMENT =
      """
      #include <metal_stdlib>
      using namespace metal;
      struct Output { float4 position [[position]]; float4 color; };
      struct Resources { texture2d<float> image [[id(0)]]; sampler filtering [[id(128)]]; };
      fragment float4 main0(Output in [[stage_in]], constant Resources& r [[buffer(29)]]) {
        return in.color*r.image.sample(r.filtering,float2(1.25,.5));
      }
      """;

  public static void main(String[] args) {
    boolean enabled = "1".equals(System.getenv("MINECRAFT_METAL_ICB_REUSE"));
    boolean cpuEnabled = "1".equals(System.getenv("MINECRAFT_METAL_CPU_ICB"));
    boolean publicationEnabled = enabled || cpuEnabled;
    int checks = 0;
    var owner = new MetalDevice();
    try {
      long d = owner.handle();
      MetalNative.configureIndirectCommands(d, true, 1);
      if ((MetalNative.indirectReuseStatistics(d)[0] == 1) != enabled)
        throw new AssertionError("Unexpected native reuse mode");
      if ((MetalNative.cpuIndirectStatistics(d)[0] == 1) != cpuEnabled)
        throw new AssertionError("Unexpected native CPU ICB mode");
      try (var pipeline =
              resource(
                  MetalNative.createPipelineWithArgumentBuffers(
                      d,
                      "ICB reuse",
                      VERTEX,
                      FRAGMENT,
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
                      new int[] {2, 2, 0, 1}));
          var color = texture(d, SIZE, SIZE);
          var readback = buffer(d, BYTES, true);
          var indices = indices(d, false);
          var otherIndices = indices(d, false);
          var wideIndices = indices(d, true);
          var shiftedIndices = indices(d, false, 3);
          var parameters =
              new MetalGpuBuffer(owner, CPU_INDIRECT, 256, "CPU indirect parameters")) {
        ByteBuffer base = commands(true, 0, 0, 0);
        for (int round = 0; round < 6; round++) {
          publish(parameters, base);
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              parameters.handle(),
              indices.id,
              false,
              3,
              2,
              round,
              publicationEnabled,
              round >= 3 ? 1 : 0,
              () -> {});
          checks++;
        }
        // A new source allocation with identical CPU bytes may reuse the immutable
        // commands.
        try (var replacement = new MetalGpuBuffer(owner, CPU_INDIRECT, 256, "replacement source")) {
          publish(replacement, base);
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              replacement.handle(),
              indices.id,
              false,
              3,
              2,
              6,
              publicationEnabled,
              1,
              replacement::close);
          checks++;
        }
        // All command words, count, topology, index width, and index allocation are cache
        // keys.
        Object[][] changes = {
          {commands(true, 1, 0, 0), indices.id, false, 3, 2},
          {commands(true, 1, 3, 0), indices.id, false, 3, 2},
          {commands(true, 1, 3, 3), indices.id, false, 3, 2},
          {commands(true, 1, 3, 3), indices.id, false, 4, 2},
          {commands(true, 1, 3, 3), indices.id, false, 4, 1},
          {commands(true, 1, 3, 3), otherIndices.id, false, 4, 1},
          {commands(true, 1, 3, 3), wideIndices.id, true, 4, 1},
          {commands(false, 2, 0, 0), 0L, false, 3, 2},
          {commands(true, 1, 0, -3), shiftedIndices.id, false, 3, 2},
          {orderedCommands(1, 3), indices.id, false, 3, 2},
          {orderedCommands(3, 1), indices.id, false, 3, 2}
        };
        int round = 7;
        for (Object[] change : changes) {
          for (int repeat = 0; repeat < 4; repeat++) {
            publish(parameters, (ByteBuffer) change[0]);
            pair(
                d,
                pipeline.id,
                color.id,
                readback.id,
                parameters.handle(),
                (long) change[1],
                (boolean) change[2],
                (int) change[3],
                (int) change[4],
                round++,
                publicationEnabled,
                repeat >= 3 ? 1 : 0,
                () -> {});
            checks++;
          }
        }
        // Camera commands are published first. Appending shadow commands to the same buffer
        // must not remove the earlier immutable camera snapshot.
        ByteBuffer camera = commands(true, 1, 0, 6);
        publish(parameters, camera);
        publish(parameters, 144, commands(true, 3, 0, 9));
        pair(
            d,
            pipeline.id,
            color.id,
            readback.id,
            parameters.handle(),
            indices.id,
            false,
            3,
            2,
            round++,
            publicationEnabled,
            -1,
            () -> {});
        checks++;
        // While a disjoint map is open, the conservative whole-buffer active-map gate applies.
        // Closing that map must restore eligibility of the earlier, untouched publication.
        try (var active = parameters.map(144, camera.remaining(), false, true)) {
          active.data().put(camera.duplicate());
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              parameters.handle(),
              indices.id,
              false,
              3,
              2,
              round++,
              false,
              -1,
              () -> {});
          checks++;
        }
        pair(
            d,
            pipeline.id,
            color.id,
            readback.id,
            parameters.handle(),
            indices.id,
            false,
            3,
            2,
            round++,
            publicationEnabled,
            -1,
            () -> {});
        checks++;
        // Partially overwrite the second command. The old full publication is dropped, and
        // the new 20-byte publication cannot prove a 40-byte draw by stitching stale pieces.
        publish(parameters, OFFSET + 20, commands(true, 4, 0, 0).limit(20));
        pair(
            d,
            pipeline.id,
            color.id,
            readback.id,
            parameters.handle(),
            indices.id,
            false,
            3,
            2,
            round++,
            false,
            -1,
            () -> {});
        checks++;
        publish(parameters, camera);
        pair(
            d,
            pipeline.id,
            color.id,
            readback.id,
            parameters.handle(),
            indices.id,
            false,
            3,
            2,
            round++,
            publicationEnabled,
            -1,
            () -> {});
        checks++;
        // Range-cap eviction is a performance fallback, never authority to reuse stale bytes.
        try (var capped = new MetalGpuBuffer(owner, CPU_INDIRECT, 8192, "bounded snapshots")) {
          long evictions = MetalNative.indirectSnapshotStatistics(d)[5];
          for (int i = 0; i < 65; i++) publish(capped, OFFSET + i * 64, camera);
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              capped.handle(),
              indices.id,
              false,
              3,
              2,
              round++,
              false,
              -1,
              () -> {});
          if (publicationEnabled && MetalNative.indirectSnapshotStatistics(d)[5] <= evictions)
            throw new AssertionError("Fixture did not exercise snapshot capacity eviction");
          publish(capped, camera);
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              capped.handle(),
              indices.id,
              false,
              3,
              2,
              round++,
              publicationEnabled,
              -1,
              () -> {});
          checks += 2;
        }
        // An open mapping invalidates publication even when its current bytes are
        // unchanged.
        try (var active = parameters.map(OFFSET, base.remaining(), false, true)) {
          active.data().put(base.duplicate());
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              parameters.handle(),
              indices.id,
              false,
              3,
              2,
              round++,
              false,
              0,
              () -> {});
          checks++;
        }
        // Native external maps permanently invalidate tracking; retaining that pointer is
        // allowed.
        MetalNative.mapBuffer(parameters.handle(), OFFSET, base.remaining()).put(base.duplicate());
        publish(parameters, base);
        pair(
            d,
            pipeline.id,
            color.id,
            readback.id,
            parameters.handle(),
            indices.id,
            false,
            3,
            2,
            round++,
            false,
            0,
            () -> {});
        checks++;
        // GPU uploads/copies can be outstanding. Reuse must never inspect their mapped
        // bytes.
        for (boolean copy : new boolean[] {false, true}) {
          try (var target = new MetalGpuBuffer(owner, CPU_INDIRECT, 256, "poisoned indirect");
              var source = buffer(d, 256, false)) {
            publish(target, base);
            ByteBuffer changed = commands(true, 4, 0, 3);
            if (copy) {
              MetalNative.writeBuffer(d, source.id, OFFSET, changed, 0, changed.remaining());
              MetalNative.copyBuffer(
                  d, source.id, OFFSET, target.handle(), OFFSET, changed.remaining());
            } else
              MetalNative.writeBuffer(d, target.handle(), OFFSET, changed, 0, changed.remaining());
            pair(
                d,
                pipeline.id,
                color.id,
                readback.id,
                target.handle(),
                indices.id,
                false,
                3,
                2,
                round++,
                false,
                0,
                () -> {});
            // Even a later legal CPU publication cannot rehabilitate a GPU-written
            // allocation.
            publish(target, base);
            pair(
                d,
                pipeline.id,
                color.id,
                readback.id,
                target.handle(),
                indices.id,
                false,
                3,
                2,
                round++,
                false,
                0,
                () -> {});
            checks += 2;
          }
        }
        // The renderer-facing usage gate excludes mixed GPU destination buffers.
        try (var mixed =
            new MetalGpuBuffer(
                owner, CPU_INDIRECT | GpuBuffer.USAGE_COPY_DST, 256, "mixed indirect")) {
          publish(mixed, base);
          pair(
              d,
              pipeline.id,
              color.id,
              readback.id,
              mixed.handle(),
              indices.id,
              false,
              3,
              2,
              round++,
              false,
              0,
              () -> {});
          checks++;
        }
        // Warm all slots, then change actual index contents (same allocation). Commands
        // must read
        // the new contents, while inherited uniforms/textures continue changing on cache
        // hits.
        try (var source = new MetalGpuBuffer(owner, CPU_INDIRECT, 256, "index lifetime source");
            var mutableIndices = indices(d, false)) {
          publish(source, base);
          for (int i = 0; i < 6; i++) {
            if (i == 3) {
              ByteBuffer reordered =
                  bytes(12)
                      .putShort((short) 3)
                      .putShort((short) 4)
                      .putShort((short) 5)
                      .putShort((short) 0)
                      .putShort((short) 1)
                      .putShort((short) 2)
                      .flip();
              MetalNative.writeBuffer(d, mutableIndices.id, 0, reordered, 0, 12);
            }
            boolean last = i == 5;
            pair(
                d,
                pipeline.id,
                color.id,
                readback.id,
                source.handle(),
                mutableIndices.id,
                false,
                3,
                2,
                round++,
                publicationEnabled,
                i >= 3 ? 1 : 0,
                last
                    ? () -> {
                      mutableIndices.close();
                      source.close();
                    }
                    : () -> {});
            checks++;
          }
        }
        if (enabled && MetalNative.indirectReuseStatistics(d)[2] < 10)
          throw new AssertionError("Fixture did not exercise enough actual cache hits");
        if (cpuEnabled && MetalNative.cpuIndirectStatistics(d)[1] < 10)
          throw new AssertionError("Fixture did not exercise enough CPU ICB population");
        // A late scene view can need a larger shared sequential index buffer. Keep rejecting
        // the old allocation, then prove the same published commands work after replacement.
        if (cpuEnabled) {
          // The earlier escaped-mapping fixture permanently revoked parameters' authority.
          try (var growthParameters =
              new MetalGpuBuffer(owner, CPU_INDIRECT, 256, "late scene growth parameters")) {
            publish(growthParameters, commands(true, 0, 6, 0));
            expectRangeFailure(
                d,
                pipeline.id,
                color.id,
                () ->
                    MetalNative.drawIndirect(
                        d, 3, growthParameters.handle(), OFFSET, 2, indices.id, false),
                "CPU indirect index command 0",
                "indexStart=6",
                "capacity=16");
            try (var grownIndices = buffer(d, 24, false)) {
              ByteBuffer values = bytes(24);
              for (int i = 0; i < 12; i++) values.putShort((short) (i % 3));
              MetalNative.writeBuffer(d, grownIndices.id, 0, values.flip(), 0, 24);
              pair(
                  d, pipeline.id, color.id, readback.id, growthParameters.handle(), grownIndices.id,
                  false, 3, 2, round++, true, 0, () -> {});
            }
          }
          checks += 2;
        }
        expectRangeFailure(
            d,
            pipeline.id,
            color.id,
            () -> MetalNative.drawIndirect(d, 3, parameters.handle(), 240, 2, indices.id, false),
            "Indirect argument commands",
            "count=2",
            "offset=240");
        checks++;
      }
    } finally {
      owner.close();
    }
    if (MetalNative.liveResourceCount() != 0) throw new AssertionError("ICB reuse resource leak");
    System.out.println(
        "ICB "
            + checks
            + " GPU equivalence/publication checks passed; reuse="
            + enabled
            + ", CPU population="
            + cpuEnabled);
  }

  private static void expectRangeFailure(
      long device, long pipeline, long color, Runnable draw, String... details) {
    MetalNative.configureIndirectCommands(device, true, 1);
    MetalNative.beginRenderPass(
        device, "indirect bounds rejection", new long[] {color}, new float[] {0, 0, 0, 0},
        0, Double.NaN, 0, 0, SIZE, SIZE);
    try {
      MetalNative.bindPipeline(device, pipeline);
      try {
        draw.run();
        throw new AssertionError("Out-of-bounds indirect draw was accepted");
      } catch (IllegalStateException expected) {
        for (String detail : details)
          if (!expected.getMessage().contains(detail))
            throw new AssertionError("Missing diagnostic " + detail + ": " + expected.getMessage());
      }
    } finally {
      MetalNative.endRenderPass(device);
    }
  }

  private static void pair(
      long d,
      long pipeline,
      long color,
      long readback,
      long parameters,
      long indices,
      boolean index32,
      int topology,
      int count,
      int round,
      boolean eligible,
      int expectedHit,
      Runnable retire) {
    byte[] expected =
        render(
            d,
            pipeline,
            color,
            readback,
            parameters,
            indices,
            index32,
            topology,
            count,
            round,
            false,
            () -> {});
    long[] before = MetalNative.indirectReuseStatistics(d);
    long[] cpuBefore = MetalNative.cpuIndirectStatistics(d);
    long[] snapshotBefore = MetalNative.indirectSnapshotStatistics(d);
    long[] splits = MetalNative.indirectSplitStatistics(d);
    byte[] actual =
        render(
            d,
            pipeline,
            color,
            readback,
            parameters,
            indices,
            index32,
            topology,
            count,
            round,
            true,
            retire);
    if (!Arrays.equals(expected, actual))
      throw new AssertionError(
          "Reuse pixels changed at round " + round + " byte " + Arrays.mismatch(expected, actual));
    boolean enabled = before[0] == 1;
    long[] after = MetalNative.indirectReuseStatistics(d);
    long[] snapshotAfter = MetalNative.indirectSnapshotStatistics(d);
    boolean publicationEnabled = enabled || cpuBefore[0] == 1;
    if (snapshotAfter.length != 7
        || snapshotAfter[0] - snapshotBefore[0] != (publicationEnabled && eligible ? 1 : 0))
      throw new AssertionError("Incorrect snapshot coverage at round " + round);
    long misses = 0;
    for (int i = 1; i <= 4; i++) misses += snapshotAfter[i] - snapshotBefore[i];
    if (misses != (publicationEnabled && !eligible ? 1 : 0))
      throw new AssertionError("Missing snapshot ineligibility reason at round " + round);
    long observedHit = after[2] - before[2];
    if (observedHit < 0 || observedHit > 1)
      throw new AssertionError("A batch can reuse at most one immutable ICB");
    long hit = enabled && eligible ? (expectedHit < 0 ? observedHit : expectedHit) : 0;
    long[] expectedDelta = {
      eligible && enabled ? 1 : 0,
      hit,
      eligible && enabled ? 1 - hit : 0,
      !eligible && enabled ? 1 : 0
    };
    for (int i = 0; i < 4; i++)
      if (after[i + 1] - before[i + 1] != expectedDelta[i])
        throw new AssertionError(
            "Reuse diagnostic "
                + (i + 1)
                + " round "
                + round
                + ": "
                + (after[i + 1] - before[i + 1])
                + " expected "
                + expectedDelta[i]);
    long[] splitAfter = MetalNative.indirectSplitStatistics(d);
    boolean cpuPopulated = cpuBefore[0] == 1 && eligible && hit == 0;
    long[] cpuAfter = MetalNative.cpuIndirectStatistics(d);
    if (cpuAfter[1] - cpuBefore[1] != (cpuPopulated ? 1 : 0)
        || cpuAfter[2] - cpuBefore[2] != (cpuPopulated ? count : 0)
        || cpuAfter[3] < cpuBefore[3])
      throw new AssertionError("Unexpected CPU ICB population at round " + round);
    if (splitAfter[0] - splits[0] != (cpuPopulated ? 0 : 1 - hit) || splitAfter[1] != splits[1])
      throw new AssertionError("CPU population/cache hit split render pass at round " + round);
    boolean visible = false;
    for (int i = 0; i < expected.length; i += 4)
      visible |= expected[i] != 0 || expected[i + 1] != 0 || expected[i + 2] != 0;
    if (!visible) throw new AssertionError("Empty reuse fixture at round " + round);
  }

  private static byte[] render(
      long d,
      long pipeline,
      long color,
      long readback,
      long parameters,
      long indices,
      boolean index32,
      int topology,
      int count,
      int round,
      boolean icb,
      Runnable retire) {
    MetalNative.configureIndirectCommands(d, icb, 1);
    try (var image = texture(d, 2, 1);
        var sampler =
            resource(MetalNative.createSampler(d, (round & 1) != 0, false, false, false, 1, 0));
        var scratch = texture(d, 1, 1)) {
      ByteBuffer pixels = bytes(8).putInt(0xff80ffff).putInt(0xffff80ff).flip();
      MetalNative.uploadTexture(d, image.id, pixels, 0, 0, 0, 0, 0, 2, 1);
      MetalNative.beginRenderPass(
          d,
          "reuse draw",
          new long[] {color},
          new float[] {0, 0, 0, 0},
          0,
          Double.NaN,
          0,
          0,
          SIZE,
          SIZE);
      MetalNative.bindPipeline(d, pipeline);
      MetalNative.bindTexture(d, 2, 0, image.id, sampler.id);
      ByteBuffer tint =
          bytes(16)
              .putFloat((round & 1) == 0 ? 1 : .5f)
              .putFloat(1)
              .putFloat(.75f)
              .putFloat(1)
              .flip();
      MetalNative.pushConstants(d, 1, 16, tint, 0, 16);
      MetalNative.drawIndirect(d, topology, parameters, OFFSET, count, indices, index32);
      MetalNative.endRenderPass(d);
      MetalNative.textureToBuffer(d, color, 0, 0, 0, SIZE, SIZE, readback, 0);
      // Clear retained encoder state and retire sampled resources before GPU completion.
      MetalNative.beginRenderPass(
          d,
          "retire reuse bindings",
          new long[] {scratch.id},
          new float[] {0, 0, 0, 0},
          0,
          Double.NaN,
          0,
          0,
          1,
          1);
      MetalNative.endRenderPass(d);
      image.close();
      sampler.close();
      retire.run();
      try (var fence = resource(MetalNative.createFence(d))) {
        MetalNative.submit(d);
        if (!MetalNative.awaitFence(fence.id, 10_000_000_000L))
          throw new AssertionError("Reuse GPU timeout");
      }
    }
    byte[] result = new byte[BYTES];
    MetalNative.mapBuffer(readback, 0, BYTES).get(result);
    return result;
  }

  private static ByteBuffer commands(boolean indexed, int instance, int first, int baseVertex) {
    ByteBuffer result = bytes(indexed ? 40 : 32);
    result.putInt(3).putInt(1).putInt(first);
    if (indexed) result.putInt(baseVertex);
    result.putInt(instance);
    result.putInt(0).putInt(1).putInt(0);
    if (indexed) result.putInt(0);
    return result.putInt(0).flip();
  }

  private static ByteBuffer orderedCommands(int first, int second) {
    return bytes(40)
        .putInt(3)
        .putInt(1)
        .putInt(0)
        .putInt(0)
        .putInt(first)
        .putInt(3)
        .putInt(1)
        .putInt(0)
        .putInt(0)
        .putInt(second)
        .flip();
  }

  private static void publish(MetalGpuBuffer target, ByteBuffer commands) {
    publish(target, OFFSET, commands);
  }

  private static void publish(MetalGpuBuffer target, int offset, ByteBuffer commands) {
    try (var map = target.map(offset, commands.remaining(), false, true)) {
      map.data().put(commands.duplicate());
    }
  }

  private static Resource indices(long d, boolean wide) {
    return indices(d, wide, 0);
  }

  private static Resource indices(long d, boolean wide, int base) {
    var result = buffer(d, wide ? 24 : 12, false);
    ByteBuffer data = bytes(wide ? 24 : 12);
    for (int value : new int[] {0, 1, 2, 2, 1, 0}) {
      if (wide) data.putInt(value + base);
      else data.putShort((short) (value + base));
    }
    MetalNative.writeBuffer(d, result.id, 0, data.flip(), 0, data.remaining());
    return result;
  }

  private static ByteBuffer bytes(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }

  private static Resource buffer(long d, int size, boolean shared) {
    return resource(MetalNative.createBuffer(d, size, shared, "reuse fixture"));
  }

  private static Resource texture(long d, int w, int h) {
    return resource(MetalNative.createTexture(d, "RGBA8_UNORM", w, h, 1, 1, 15, "reuse fixture"));
  }

  private static Resource resource(long id) {
    if (id == 0) throw new AssertionError("Allocation failed");
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
