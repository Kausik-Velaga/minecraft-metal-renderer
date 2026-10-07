package dev.kausik.sceneoptimizer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

/** Main-thread tracker ownership; moving recycled states cannot remove another state's new node. */
public final class DirtySectionMembership {
  private final Long2ObjectOpenHashMap<Object> dirty = new Long2ObjectOpenHashMap<>();

  public void update(long node, Object state, boolean isDirty) {
    if (isDirty) dirty.put(node, state);
    else remove(node, state);
  }

  public void remove(long node, Object state) {
    if (dirty.get(node) == state) dirty.remove(node);
  }

  /** Consumed completely before vanilla extraction starts clearing dirty states. */
  public LongSet nodes() {
    return dirty.keySet();
  }
}
