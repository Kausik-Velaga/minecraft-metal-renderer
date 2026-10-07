package dev.kausik.shaders.runtime;

import dev.kausik.scene.SceneGeneration;
import dev.kausik.scene.SceneSelectionTicket;
import dev.kausik.scene.SceneViewRequest;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Exact input identity, independent coordinate origins and pending-token cleanup without a GPU. */
public final class ShadowSelectionSnapshotTest {
  public static void main(String[] args) throws Exception {
    testCopiesAndExactChanges();
    testIndependentOrigins();
    testDiscardLifecycle();
    System.out.println("Shadow selection snapshots passed exact invalidation, origins and cleanup.");
  }

  private static void testCopiesAndExactChanges() {
    var projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.6f, 0.05f, 256);
    var view = new Matrix4f().rotateX(0.3f).rotateY(-0.7f);
    var shadowProjection = new Matrix4f().ortho(-160, 160, -160, 160, 0.05f, 640);
    var shadowView = new Matrix4f().rotateX(-0.5f);
    var light = new Vector3f(0.2f, 0.8f, -0.4f);
    Vec3 primary = new Vec3(-1_000_003.25, 63.5, 1_000_007.75);
    Vec3 receiver = new Vec3(primary.x + 0.125, primary.y - 0.25, primary.z + 0.5);
    var snapshot = new ShadowRenderer.SelectionInputs(
        projection, view, shadowProjection, shadowView, light, primary, receiver);
    require(snapshot.matches(projection, view, shadowProjection, shadowView, light, primary, receiver),
        "Unchanged frame was rejected");
    Matrix4f[] matrices = {projection, view, shadowProjection, shadowView};
    for (Matrix4f matrix : matrices) {
      float before = matrix.m21();
      matrix.m21(Math.nextUp(before));
      require(!snapshot.matches(projection, view, shadowProjection, shadowView, light, primary, receiver),
          "A one-ULP matrix change reused an old frame query");
      matrix.m21(before);
    }
    light.x = Math.nextUp(light.x);
    require(!snapshot.matches(projection, view, shadowProjection, shadowView, light, primary, receiver),
        "Light change reused an old frame query");
    light.x = 0.2f;
    require(!snapshot.matches(projection, view, shadowProjection, shadowView, light,
        new Vec3(Math.nextUp(primary.x), primary.y, primary.z), receiver),
        "Primary camera movement reused an old frame query");
    require(!snapshot.matches(projection, view, shadowProjection, shadowView, light, primary,
        new Vec3(receiver.x, Math.nextUp(receiver.y), receiver.z)),
        "Receiver camera movement reused an old frame query");
    require(!snapshot.matches(projection, view, shadowProjection, shadowView, light, receiver, primary),
        "Two coordinate origins were conflated");

    var expectedProjection = new Matrix4f(projection);
    var expectedView = new Matrix4f(view);
    var expectedShadowProjection = new Matrix4f(shadowProjection);
    var expectedShadowView = new Matrix4f(shadowView);
    var expectedLight = new Vector3f(light);
    projection.zero();
    view.zero();
    shadowProjection.zero();
    shadowView.zero();
    light.zero();
    require(snapshot.matches(expectedProjection, expectedView, expectedShadowProjection,
        expectedShadowView, expectedLight, primary, receiver), "Snapshot shared mutable matrix/vector storage");
  }

  private static void testIndependentOrigins() {
    var projection = new Matrix4f().perspective((float) Math.toRadians(70), 1.6f, 0.05f, 256);
    var view = new Matrix4f();
    var shadowProjection = new Matrix4f().ortho(-160, 160, -160, 160, 0.05f, 640);
    var shadowView = new Matrix4f().rotateX(-0.5f);
    var light = new Vector3f(0.2f, 0.8f, -0.4f);
    Vec3 primary = new Vec3(-1_000_003.25, 63.5, 1_000_007.75);
    Vec3 receiverOrigin = new Vec3(primary.x + 8, primary.y - 4, primary.z + 12);
    var input = new ShadowRenderer.SelectionInputs(
        projection, view, shadowProjection, shadowView, light, primary, receiverOrigin);
    Frustum actualPrimary = input.frustum();
    Frustum expectedPrimary = new Frustum(shadowView, new Matrix4f(shadowProjection));
    expectedPrimary.prepare(primary.x, primary.y, primary.z);
    ShadowCasterVolume receiver = ShadowCasterVolume.create(projection, view, light, 64);
    var refinement = new ShadowRenderer.ReceiverRefinement(receiver, receiverOrigin);
    require(refinement.workerSnapshot() == refinement, "Immutable receiver cannot be sent to worker");
    Random random = new Random(0x501ec7);
    int accepted = 0, rejected = 0;
    for (int i = 0; i < 300; i++) {
      double x = primary.x + random.nextDouble() * 1024 - 512;
      double y = primary.y + random.nextDouble() * 1024 - 512;
      double z = primary.z + random.nextDouble() * 1024 - 512;
      var box = new AABB(x, y, z, x + 16, y + 16, z + 16);
      require(actualPrimary.isVisible(box) == expectedPrimary.isVisible(box),
          "Copied primary frustum changed original float/camera semantics");
      boolean expected = receiver.intersects(box.minX - receiverOrigin.x, box.minY - receiverOrigin.y,
          box.minZ - receiverOrigin.z, box.maxX - receiverOrigin.x, box.maxY - receiverOrigin.y,
          box.maxZ - receiverOrigin.z);
      require(refinement.intersects(box) == expected, "Worker refinement changed relative coordinates");
      if (expected) accepted++;
      else rejected++;
    }
    require(accepted > 0 && rejected > 0, "Receiver fixture failed to exercise both outcomes");
  }

  private static void testDiscardLifecycle() throws Exception {
    Class<?> pending = Class.forName(ShadowRenderer.class.getName() + "$PendingSelection");
    var constructor = pending.getDeclaredConstructor(LevelRenderer.class, ViewArea.class,
        ShaderRuntime.class, PackPrograms.class, SceneGeneration.class, long.class,
        ShadowRenderer.SelectionInputs.class, SceneViewRequest.class, SceneSelectionTicket.class,
        boolean.class, double.class, String.class);
    constructor.setAccessible(true);
    Field slot = ShadowRenderer.class.getDeclaredField("pendingSelection");
    slot.setAccessible(true);
    AtomicInteger closes = new AtomicInteger();
    SceneSelectionTicket ticket = closes::incrementAndGet;
    Object fixture = constructor.newInstance(null, null, null, null, new SceneGeneration(0, 0),
        0L, null, null, ticket, false, 64.0, "fixture");
    try {
      slot.set(null, fixture);
      ShadowRenderer.beginFrame();
      require(slot.get(null) == null && closes.get() == 1,
          "Begin-frame cleanup depended on a live Minecraft world");
      ShadowRenderer.discardSelection();
      require(closes.get() == 1, "Discard closed a removed token twice");
      slot.set(null, fixture);
      ShadowRenderer.beginExtraction();
      require(slot.get(null) == null && closes.get() == 2, "Extraction/world reset retained pending work");
    } finally {
      ShadowRenderer.discardSelection();
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
