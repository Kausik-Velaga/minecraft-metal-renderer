package dev.kausik.sceneoptimizer;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Borrowed read-only layer array, preserving vanilla enum order. */
public interface PreparedSectionLayers {
  ChunkSectionLayer[] scene$drawLayers();
}
