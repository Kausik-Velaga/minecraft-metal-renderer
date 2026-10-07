package dev.kausik.shaders.compile;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.kausik.metal.MetalDevice;
import dev.kausik.shaders.runtime.PackDepthMerge;
import dev.kausik.shaders.runtime.PackRenderTargets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.joml.Vector4f;

/** Readback validates depth ownership and temporal invalidation on the real Metal GPU. */
public final class PackDepthMergeTest {
  public static void main(String[] args) {
    RenderSystem.initRenderThread();
    FrontendGpuDevice device = new FrontendGpuDevice(new MetalDevice());
    RenderSystem.initRenderer(device);
    try (var merge = new PackDepthMerge(device)) {
      verifyDepths(device, merge);
      verifyDepthRotation(device, merge);
      verifyHistoryReset(device);
      System.out.println(
          "PASS: late hand depth merges preserve all three depth meanings; history resets both"
              + " color sides");
    } finally {
      RenderSystem.shutdownRenderer();
    }
  }

  private static void verifyDepthRotation(FrontendGpuDevice device, PackDepthMerge merge) {
    try (var targets = new PackRenderTargets(device, Map.of(), 1, 1, 1);
        var readback =
            device.createBuffer(
                () -> "Depth rotation readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                24)) {
      for (int frame = 0; frame < 4; frame++) {
        var encoder = device.createCommandEncoder();
        targets.beginFrame(new Vector4f());
        for (int index = 0; index < 3; index++) {
          if (targets.depth(index) == targets.depth((index + 1) % 3))
            throw new AssertionError("Depth roles alias after frame " + frame);
          encoder.copyTextureToBuffer(
              targets.depth(index).texture, readback, index * 4L, () -> {}, 0);
        }
        float scene = .8f - frame * .15f, hand = .2f + frame * .15f, opaque = .95f;
        float[] depths = {scene, hand, opaque};
        for (int index = 0; index < 3; index++)
          encoder.writeToTexture(
              targets.depth(index).texture, floats(new float[] {depths[index]}), 0, 0, 0, 0, 1, 1);
        var oldScene = targets.depth(0);
        merge.merge(targets);
        if (targets.depth(0) == oldScene)
          throw new AssertionError("Merged depth did not rotate ownership");
        for (int index = 0; index < 3; index++)
          encoder.copyTextureToBuffer(
              targets.depth(index).texture, readback, 12 + index * 4L, () -> {}, 0);
        try (var fence = encoder.createFence()) {
          encoder.submit();
          if (!fence.awaitCompletion(5_000_000_000L))
            throw new AssertionError("Depth rotation timeout");
        }
        try (var mapped = readback.map(true, false)) {
          ByteBuffer data = mapped.data().order(ByteOrder.nativeOrder());
          for (int index = 0; index < 3; index++)
            same(data.getFloat(index * 4), 1, "rotated clear", frame);
          same(data.getFloat(12), Math.min(scene, hand), "rotated merged depth", frame);
          same(data.getFloat(16), hand, "rotated hand depth", frame);
          same(data.getFloat(20), opaque, "rotated opaque depth", frame);
        }
      }
    }
  }

  private static void verifyDepths(FrontendGpuDevice device, PackDepthMerge merge) {
    int width = 4, height = 2, bytes = width * height * Float.BYTES;
    // Asymmetric rows catch coordinate inversion. Very close scene pixels must never be classified
    // as hand using a threshold; the nearest depth is selected regardless of geometry identity.
    float[] opaqueWorld = {1, .85f, .1f, .999f, .04f, .7f, .55f, 1};
    float[] scene = {1, .4f, .1f, .999f, .02f, .7f, .5f, .15f};
    float[] opaqueHand = {1, .3f, .1f, .999f, .04f, .2f, .55f, .1f};
    try (var targets = new PackRenderTargets(device, Map.of(), width, height, 1);
        var readback =
            device.createBuffer(
                () -> "Depth ownership readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                bytes * 5)) {
      var encoder = device.createCommandEncoder();
      encoder.writeToTexture(
          targets.depth(0).texture, floats(opaqueWorld), 0, 0, 0, 0, width, height);
      targets.snapshotOpaqueDepth();
      encoder.copyTextureToBuffer(targets.depth(1).texture, readback, bytes * 3L, () -> {}, 0);
      encoder.copyTextureToBuffer(targets.depth(2).texture, readback, bytes * 4L, () -> {}, 0);
      encoder.writeToTexture(targets.depth(0).texture, floats(scene), 0, 0, 0, 0, width, height);
      encoder.writeToTexture(
          targets.depth(1).texture, floats(opaqueHand), 0, 0, 0, 0, width, height);
      merge.merge(targets);
      // The operation must remain stable if a later consumer requests the merge again.
      merge.merge(targets);
      for (int index = 0; index < 3; index++)
        encoder.copyTextureToBuffer(
            targets.depth(index).texture, readback, bytes * (long) index, () -> {}, 0);
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("Depth merge GPU timeout");
      }
      try (var mapped = readback.map(true, false)) {
        ByteBuffer result = mapped.data().order(ByteOrder.nativeOrder());
        for (int pixel = 0; pixel < opaqueWorld.length; pixel++) {
          same(
              result.getFloat(pixel * 4),
              Math.min(scene[pixel], opaqueHand[pixel]),
              "merged depth",
              pixel);
          same(result.getFloat(bytes + pixel * 4), opaqueHand[pixel], "opaque+hand depth", pixel);
          same(
              result.getFloat(bytes * 2 + pixel * 4),
              opaqueWorld[pixel],
              "opaque-only depth",
              pixel);
          same(
              result.getFloat(bytes * 3 + pixel * 4),
              opaqueWorld[pixel],
              "opaque snapshot 1",
              pixel);
          same(
              result.getFloat(bytes * 4 + pixel * 4),
              opaqueWorld[pixel],
              "opaque snapshot 2",
              pixel);
        }
      }
    }
  }

  private static void verifyHistoryReset(FrontendGpuDevice device) {
    var persistent =
        new PackRenderTargets.BufferSpec(
            GpuFormat.RGBA8_UNORM, false, new Vector4f(.2f, .4f, .6f, .8f), false);
    try (var targets = new PackRenderTargets(device, Map.of(2, persistent), 1, 1, 1);
        var readback =
            device.createBuffer(
                () -> "History reset readback",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                24)) {
      var encoder = device.createCommandEncoder();
      for (int index = 0; index < 3; index++) {
        encoder.clearColorTexture(targets.color(index).texture, new Vector4f(.9f));
        encoder.clearColorTexture(targets.alternate(index).texture, new Vector4f(.1f));
      }
      targets.flip(List.of(2));
      var historySide = targets.color(2);
      targets.resetHistory(new Vector4f(.4f, .2f, 0, 1));
      if (targets.color(2) != historySide)
        throw new AssertionError("History invalidation changed flip ownership");
      for (int index = 0; index < 3; index++) {
        encoder.copyTextureToBuffer(
            targets.color(index).texture, readback, index * 8L, () -> {}, 0);
        encoder.copyTextureToBuffer(
            targets.alternate(index).texture, readback, index * 8L + 4, () -> {}, 0);
      }
      try (var fence = encoder.createFence()) {
        encoder.submit();
        if (!fence.awaitCompletion(5_000_000_000L))
          throw new AssertionError("History reset GPU timeout");
      }
      int[][] expected = {{102, 51, 0, 255}, {255, 255, 255, 255}, {51, 102, 153, 204}};
      try (var mapped = readback.map(true, false)) {
        for (int index = 0; index < 3; index++)
          for (int side = 0; side < 2; side++)
            for (int channel = 0; channel < 4; channel++) {
              int actual = Byte.toUnsignedInt(mapped.data().get(index * 8 + side * 4 + channel));
              if (actual != expected[index][channel])
                throw new AssertionError(
                    "History reset color "
                        + index
                        + " side "
                        + side
                        + " channel "
                        + channel
                        + " = "
                        + actual);
            }
      }
    }
  }

  private static ByteBuffer floats(float[] values) {
    ByteBuffer result =
        ByteBuffer.allocateDirect(values.length * Float.BYTES).order(ByteOrder.nativeOrder());
    for (float value : values) result.putFloat(value);
    return result.flip();
  }

  private static void same(float actual, float expected, String label, int pixel) {
    if (Float.floatToIntBits(actual) != Float.floatToIntBits(expected))
      throw new AssertionError(
          label + " pixel " + pixel + " = " + actual + ", expected " + expected);
  }
}
