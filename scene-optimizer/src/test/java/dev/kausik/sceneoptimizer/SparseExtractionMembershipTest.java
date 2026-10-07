package dev.kausik.sceneoptimizer;

import dev.kausik.sceneoptimizer.mixin.SectionDirtyStateMixin;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.client.SectionUpdateTracker.SectionDirtyState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** CPU lifecycle proofs; the packaged launch separately verifies actual mixin installation. */
public final class SparseExtractionMembershipTest {
  public static void main(String[] args) throws Exception {
    run();
    System.out.println("Sparse extraction membership passed dirty, mesh and ordering transitions.");
  }

  static void run() throws Exception {
    testVanillaDirtyTransitions();
    testMeshPublicationAndOrder();
    testPublicationDuringDrain();
  }

  private static void testVanillaDirtyTransitions() throws Exception {
    var constructor = SectionDirtyState.class.getDeclaredConstructor(boolean.class, boolean.class, long.class);
    constructor.setAccessible(true);
    var state = constructor.newInstance(true, false, -17L);
    var bridge = new SectionDirtyStateMixin() {
      @Override public long getSectionNode() { return state.getSectionNode(); }
      @Override public boolean isDirty() { return state.isDirty(); }
    };
    var first = new DirtySectionMembership();
    bridge.scene$bindDirtyMembership(first);
    require(first.nodes().contains(-17), "Initial uncompiled dirty state was omitted");

    Method update = SectionDirtyStateMixin.class.getDeclaredMethod("scene$updateMembership", CallbackInfo.class);
    Method move = SectionDirtyStateMixin.class.getDeclaredMethod("scene$removeOldNode", long.class, CallbackInfo.class);
    update.setAccessible(true);
    move.setAccessible(true);
    state.setNotDirty();
    update.invoke(bridge, new Object[] {null});
    require(first.nodes().isEmpty(), "Completed extraction remained dirty");
    state.setDirty(true);
    update.invoke(bridge, new Object[] {null});
    state.setDirty(false);
    update.invoke(bridge, new Object[] {null});
    require(first.nodes().size() == 1 && state.isDirtyFromPlayer(), "Repeated dirty marks changed priority or membership");

    move.invoke(bridge, 29L, null);
    state.setSectionNode(29L);
    update.invoke(bridge, new Object[] {null});
    require(!first.nodes().contains(-17) && first.nodes().contains(29), "Storage rotation retained the old node");
    require(!state.isDirtyFromPlayer(), "Vanilla node reset behavior changed");
    var replacement = new DirtySectionMembership();
    bridge.scene$bindDirtyMembership(replacement);
    require(first.nodes().isEmpty() && replacement.nodes().contains(29), "Tracker rebind leaked old membership");
    state.setNotDirty();
    update.invoke(bridge, new Object[] {null});
    require(replacement.nodes().isEmpty(), "Rebound state did not clear");

    Object recycled = new Object(), newOwner = new Object();
    first.update(5, recycled, true);
    first.update(5, newOwner, true);
    first.remove(5, recycled);
    require(first.nodes().contains(5), "Recycled owner removed another state's new node");
  }

  private static void testMeshPublicationAndOrder() {
    var camera = new Slot(List.of("chest"));
    var empty = new Slot(List.of());
    var shadow = new Slot(List.of("sign"));
    var source = List.of(shadow, empty, camera);
    var membership = new EventSectionMembership<>(source);
    AtomicInteger inspected = new AtomicInteger();
    Predicate<Slot> hasFeatures = slot -> {
      inspected.incrementAndGet();
      return !slot.mesh.isEmpty();
    };
    membership.initialize(hasFeatures);
    require(inspected.get() == 3, "Initial membership did not inspect the source exactly once");
    inspected.set(0);
    equivalent(source, membership.snapshot(hasFeatures));
    require(inspected.get() == 0, "Stable membership traversed every stored section again");

    empty.mesh = List.of("new chest");
    membership.changed(empty);
    membership.changed(empty); // Duplicate uploads coalesce into one live-state read.
    shadow.mesh = List.of(); // Replacement/reset removes the old mesh's features.
    membership.changed(shadow);
    equivalent(source, membership.snapshot(hasFeatures));
    require(inspected.get() == 2, "Mesh changes were not coalesced to the affected sections");
    inspected.set(0);
    membership.changed(camera); // Transparency resort can leave the feature list unchanged.
    equivalent(source, membership.snapshot(hasFeatures));
    require(inspected.get() == 1, "Resort triggered a complete rescan");

    // Old frame snapshots and old-world events cannot alter new-world membership.
    BitSet previous = membership.snapshot(hasFeatures);
    empty.mesh = List.of();
    membership.changed(empty);
    equivalent(source, membership.snapshot(hasFeatures));
    require(previous.get(1), "An independent prior selection was mutated");
    var newSlot = new Slot(List.of("new world"));
    var newSource = List.of(newSlot);
    var newWorld = new EventSectionMembership<>(newSource);
    newWorld.initialize(hasFeatures);
    newWorld.changed(camera);
    equivalent(newSource, newWorld.snapshot(hasFeatures));
    require(newWorld.ordinal(camera) == -1, "World replacement retained old section identities");
  }

  private static void testPublicationDuringDrain() {
    var section = new Slot(List.of());
    var membership = new EventSectionMembership<>(List.of(section));
    membership.initialize(slot -> false);
    membership.changed(section);
    // Simulate publication after the old mesh was read but before that query finishes.
    membership.snapshot(slot -> {
      section.mesh = List.of("published during drain");
      membership.changed(section);
      return false;
    });
    require(membership.snapshot(slot -> !slot.mesh.isEmpty()).get(0), "A racing publication was lost after clearing its dirty bit");
  }

  private static void equivalent(List<Slot> source, BitSet membership) {
    var expected = source.stream().filter(slot -> !slot.mesh.isEmpty()).toList();
    var actual = new ArrayList<Slot>();
    for (int i = membership.nextSetBit(0); i >= 0; i = membership.nextSetBit(i + 1)) {
      if (!source.get(i).mesh.isEmpty()) actual.add(source.get(i));
    }
    require(actual.equals(expected), "Sparse membership changed vanilla feature eligibility/order");
  }

  private static final class Slot {
    private List<String> mesh;
    private Slot(List<String> mesh) { this.mesh = mesh; }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
