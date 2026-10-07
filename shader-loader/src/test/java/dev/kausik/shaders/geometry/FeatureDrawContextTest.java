package dev.kausik.shaders.geometry;

import dev.kausik.shaders.runtime.ShadowRenderer;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** Verifies overlay decoding and material batching without disturbing translucent draw order. */
public final class FeatureDrawContextTest {
  private record Node(int index) implements SubmitNode {
    @Override
    public FeatureRendererType<? extends SubmitNode> featureType() {
      return null;
    }
  }

  public static void main(String[] args) {
    // The directory name "pipeline/" contains "line". Never confuse it with a line primitive.
    if (ShadowRenderer.skipPipeline(RenderPipelines.SOLID_TERRAIN)
        || ShadowRenderer.skipPipeline(RenderPipelines.SOLID_TERRAIN_MULTIDRAW)
        || ShadowRenderer.skipPipeline(RenderPipelines.ENTITY_SOLID)
        || ShadowRenderer.skipPipeline(RenderPipelines.ENTITY_SOLID_GLINT)
        || ShadowRenderer.skipPipeline(RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT)
        || ShadowRenderer.skipPipeline(RenderPipelines.ITEM_TRANSLUCENT_GLINT)
        || ShadowRenderer.skipPipeline(RenderPipelines.SOLID_BLOCK)) {
      throw new AssertionError("Real geometry was excluded from shadow rendering");
    }
    if (!ShadowRenderer.skipPipeline(RenderPipelines.LINES)) {
      throw new AssertionError("Debug lines were accepted as shadow casters");
    }
    if (!ShadowRenderer.skipPipeline(RenderPipelines.GLINT)) {
      throw new AssertionError("Standalone glint overlay was accepted as a shadow caster");
    }
    if (!ShadowRenderer.skipPipeline(RenderPipelines.CRUMBLING)) {
      throw new AssertionError("Block-breaking decals were accepted as solid shadow casters");
    }
    if (!ShadowRenderer.skipPipeline(RenderPipelines.BEACON_BEAM_OPAQUE)
        || !ShadowRenderer.skipPipeline(RenderPipelines.BEACON_BEAM_TRANSLUCENT)) {
      throw new AssertionError("Emissive beacon beams were accepted as solid shadow casters");
    }
    if (!ShadowRenderer.skipPipeline(RenderPipelines.LIGHTNING)
        || !ShadowRenderer.skipPipeline(RenderPipelines.DRAGON_RAYS)) {
      throw new AssertionError(
          "Additive lightning and dragon rays were accepted as shadow casters");
    }
    var normal =
        new FeatureDrawContext.DrawTag(
            FeatureDrawContext.Kind.ENTITY, 10101, -1, 0, 0, 0, 0, false);
    var hurt = normal.withOverlay(3 << 16);
    if (hurt.red() != 1 || hurt.green() != 0 || Math.abs(hurt.alpha() - 77 / 255.0f) > 1e-6)
      throw new AssertionError("Actual red overlay texture was not decoded");
    var white = normal.withOverlay((10 << 16) | 15);
    if (white.red() != 1
        || white.green() != 1
        || white.blue() != 1
        || Math.abs(white.alpha() - 192 / 255.0f) > 1e-6)
      throw new AssertionError("Actual white overlay texture was not decoded");
    if (normal.withOverlay(10 << 16) != normal)
      throw new AssertionError("No-overlay state changed");
    Node a = new Node(1), b = new Node(2), c = new Node(3);
    FeatureDrawContext.record(a, normal);
    FeatureDrawContext.record(b, hurt);
    FeatureDrawContext.record(c, normal);
    var opaque = FeatureDrawContext.partition(List.of(a, b, c), false);
    if (!opaque.equals(List.of(List.of(a, c), List.of(b))))
      throw new AssertionError("Equal-tag opaque submits did not batch");
    var translucent = FeatureDrawContext.partition(List.of(a, b, c), true);
    if (!translucent.equals(List.of(List.of(a), List.of(b), List.of(c))))
      throw new AssertionError("Translucent depth order changed");
    FeatureDrawContext.beginExtraction();
    if (FeatureDrawContext.tag(a) != FeatureDrawContext.NONE)
      throw new AssertionError("Previous frame tags leaked");
    System.out.println(
        "Feature tags passed: actual overlays, equal-material batching, strict order, frame"
            + " lifetime, shadow pipeline filtering.");
  }
}
