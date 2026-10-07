package dev.kausik.sceneoptimizer;

/** Cache storage belongs to the mesh, never to a global identity history. */
public interface PreparedMeshDraws {
  MeshDrawCache scene$drawCache();

  /** Allocation changes need not allocate a cache for a mesh that has never been drawn. */
  void scene$invalidateDrawAllocations();
}
