package dev.kausik.shaders.benchmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Normal input-driven actions. Selection reads natural terrain; it never builds or carves a scene.
 */
final class BenchmarkActions {
  enum Kind {
    STATIC,
    FOREST_WALK,
    CAVERN_BREAK
  }

  record Viewpoint(double x, double y, double z, float yaw, float pitch) {
    Map<String, Object> values() {
      return Map.of("x", x, "y", y, "z", z, "yaw", yaw, "pitch", pitch, "fov", 70);
    }
  }

  record MiningTarget(BlockPos block, Vec3 point, Direction face) {
    Map<String, Object> values() {
      return Map.of("block", block.toShortString(), "point", position(point), "face", face.name());
    }
  }

  record Fixture(
      Viewpoint start,
      List<BlockPos> path,
      List<MiningTarget> targets,
      Map<String, Object> evidence) {}

  record Sample(
      String phase,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      int destroyStage,
      boolean meshingPending,
      int loadedChunks) {}

  private final Kind kind;
  private volatile Fixture fixture;
  private final List<Map<String, Object>> markers = new ArrayList<>();
  private final ArrayDeque<Map<String, Object>> snapshots = new ArrayDeque<>();
  private String phase = "observe_before";
  private int waypoint = 1,
      direction = 1,
      completedWalkLegs,
      target,
      confirmedBreaks,
      maximumCrackStage = -1;
  private Vec3 previousPosition;
  private double walkedDistance;
  private long previousElapsed, nextServerCheck, lastWalkProgress;
  private boolean clientRemoved;
  private CompletableFuture<Boolean> serverCheck;
  private long nextSnapshot, predictedRemovedAt;
  private int requestedTarget = -1;
  private float requestedYaw, requestedPitch;
  private boolean aimReady, targetHit;
  private volatile String lastServerBlockState = "unchecked";

  BenchmarkActions(String action) {
    kind =
        switch (action) {
          case "static" -> Kind.STATIC;
          case "forestWalk" -> Kind.FOREST_WALK;
          case "cavernBreak" -> Kind.CAVERN_BREAK;
          default ->
              throw new IllegalArgumentException(
                  "benchmark action must be static, forestWalk or cavernBreak");
        };
  }

  boolean enabled() {
    return kind != Kind.STATIC;
  }

  boolean stationaryMining() {
    return kind == Kind.CAVERN_BREAK;
  }

  Fixture fixture() {
    return fixture;
  }

  String phase() {
    return phase;
  }

  Fixture prepare(MinecraftServer server, double centerX, double centerZ) {
    ServerLevel level = server.getLevel(Level.OVERWORLD);
    if (level.getSeed() != 1)
      throw new IllegalStateException(
          "Action benchmarks require the disposable seed-1 natural world");
    fixture =
        kind == Kind.FOREST_WALK
            ? findForest(level, (int) Math.floor(centerX), (int) Math.floor(centerZ))
            : findCavern(level, (int) Math.floor(centerX), (int) Math.floor(centerZ));
    var player = server.getPlayerList().getPlayers().getFirst();
    player.setItemSlot(
        EquipmentSlot.MAINHAND,
        kind == Kind.CAVERN_BREAK ? new ItemStack(Items.WOODEN_PICKAXE) : ItemStack.EMPTY);
    // Immunity prevents incidental mob damage from terminating the disposable action sequence.
    // It does not change movement, mining speed, terrain, weather, or pack quality.
    player.setPermanentlyInvulnerable(true);
    player.inventoryMenu.broadcastChanges();
    return fixture;
  }

  void advance(Minecraft client, long elapsed, long duration) {
    long margin = Math.min(5_000_000_000L, duration / 5);
    String next =
        elapsed < margin
            ? "observe_before"
            : elapsed < duration - margin
                ? kind == Kind.FOREST_WALK
                    ? "forest_walk"
                    : target < fixture.targets().size() ? "cavern_break" : "cavern_observe"
                : "observe_after";
    if (!phase.equals(next)) {
      phase = next;
      mark(elapsed, "phase", Map.of("name", phase));
    }
    Vec3 position = client.player.position();
    if (stationaryMining()
        && position.distanceTo(
                new Vec3(fixture.start().x(), fixture.start().y(), fixture.start().z()))
            > .35) {
      throw new IllegalStateException(
          "Stationary cavern mining viewpoint was displaced: " + position);
    }
    if (previousPosition != null && kind == Kind.FOREST_WALK)
      walkedDistance +=
          Math.hypot(position.x - previousPosition.x, position.z - previousPosition.z);
    previousPosition = position;
    double delta = Math.min(.1, Math.max(0, (elapsed - previousElapsed) / 1e9));
    previousElapsed = elapsed;
    if (kind == Kind.CAVERN_BREAK) confirmBreak(client, elapsed);
    if (phase.equals("forest_walk")) walk(client, delta, elapsed);
    else if (phase.equals("cavern_break")) mine(client, delta, elapsed);
    else {
      release(client);
      if (kind == Kind.CAVERN_BREAK && !phase.equals("observe_before"))
        turn(client, fixture.start().yaw(), fixture.start().pitch(), delta);
    }
    if (elapsed >= nextSnapshot) {
      Map<String, Object> snapshot = currentState(client, elapsed);
      if (snapshots.size() == 120) snapshots.removeFirst();
      snapshots.addLast(snapshot);
      nextSnapshot = elapsed + 2_000_000_000L;
      System.out.println("[Shader benchmark action] " + snapshot);
    }
  }

  private void walk(Minecraft client, double delta, long elapsed) {
    var player = client.player;
    BlockPos next = fixture.path().get(waypoint);
    Vec3 goal = Vec3.atBottomCenterOf(next);
    if (lastWalkProgress == 0) lastWalkProgress = elapsed;
    if (elapsed - lastWalkProgress > 10_000_000_000L)
      throw new IllegalStateException(
          "Forest walk stalled before waypoint " + waypoint + " at " + next);
    if (Math.hypot(goal.x - player.getX(), goal.z - player.getZ()) < .55) {
      lastWalkProgress = elapsed;
      if (waypoint == fixture.path().size() - 1 || waypoint == 0) {
        completedWalkLegs++;
        direction = -direction;
        mark(elapsed, "walk_turn", Map.of("waypoint", waypoint, "distance", walkedDistance));
      }
      waypoint += direction;
      next = fixture.path().get(waypoint);
      goal = Vec3.atBottomCenterOf(next);
    }
    float desiredYaw =
        (float) Math.toDegrees(Math.atan2(-(goal.x - player.getX()), goal.z - player.getZ()));
    boolean aimed = turn(client, desiredYaw, 5, delta);
    client.options.keyUp.setDown(aimed);
    client.options.keyJump.setDown(aimed && player.onGround() && next.getY() > player.getY() + .25);
    if (player.getY() < next.getY() - 3 || player.getY() > next.getY() + 4)
      throw new IllegalStateException("Forest walk left its naturally walkable ground route");
  }

  private void mine(Minecraft client, double delta, long elapsed) {
    if (target >= fixture.targets().size() || clientRemoved) {
      aimReady = false;
      targetHit = false;
      client.options.keyAttack.setDown(false);
      return;
    }
    MiningTarget requested = fixture.targets().get(target);
    BlockPos block = requested.block();
    if (requestedTarget != target) {
      requestedTarget = target;
      lastServerBlockState = "unchecked";
      mark(elapsed, "target_requested", requested.values());
    }
    if (client.level.getBlockState(block).isAir()) {
      clientRemoved = true;
      predictedRemovedAt = elapsed;
      client.options.keyAttack.setDown(false);
      mark(elapsed, "client_block_removed", Map.of("target", block.toShortString()));
      return;
    }
    // Aim inside an exposed face, not at a voxel-edge ray toward the block's center. Tiny
    // yaw/pitch rounding differences can otherwise pick a neighboring block forever.
    Vec3 difference = requested.point().subtract(client.player.getEyePosition());
    float yaw = (float) Math.toDegrees(Math.atan2(-difference.x, difference.z));
    float pitch =
        (float) -Math.toDegrees(Math.atan2(difference.y, Math.hypot(difference.x, difference.z)));
    requestedYaw = yaw;
    requestedPitch = pitch;
    aimReady = turn(client, yaw, pitch, delta);
    targetHit =
        client.hitResult instanceof BlockHitResult result
            && result.getType() == HitResult.Type.BLOCK
            && result.getBlockPos().equals(block);
    client.options.keyAttack.setDown(aimReady && targetHit);
    if (client.gameMode.isDestroying()) {
      int crack = client.gameMode.getDestroyStage();
      if (crack > maximumCrackStage) {
        maximumCrackStage = crack;
        mark(elapsed, "crack_stage", Map.of("stage", crack, "target", block.toShortString()));
      }
    }
  }

  private void confirmBreak(Minecraft client, long elapsed) {
    if (!clientRemoved || target >= fixture.targets().size()) return;
    BlockPos block = fixture.targets().get(target).block();
    if (serverCheck != null && serverCheck.isDone()) {
      if (serverCheck.join()) {
        confirmedBreaks++;
        mark(
            elapsed,
            "server_block_removed",
            Map.of("target", block.toShortString(), "count", confirmedBreaks));
        target++;
        clientRemoved = false;
      } else if (!client.level.getBlockState(block).isAir()) {
        // A predicted client removal is not a completed break. If the server restores the
        // block, resume ordinary mining instead of remaining stuck in the acknowledgement gate.
        clientRemoved = false;
        mark(
            elapsed,
            "server_restored_target",
            Map.of("target", block.toShortString(), "serverBlockState", lastServerBlockState));
      } else if (elapsed - predictedRemovedAt > 3_000_000_000L) {
        throw new IllegalStateException(
            "Client-predicted removal was neither confirmed nor restored: "
                + block.toShortString()
                + "; server state="
                + lastServerBlockState);
      }
      serverCheck = null;
    }
    if (clientRemoved && serverCheck == null && elapsed >= nextServerCheck) {
      CompletableFuture<Boolean> check = new CompletableFuture<>();
      serverCheck = check;
      nextServerCheck = elapsed + 100_000_000L;
      client
          .getSingleplayerServer()
          .execute(
              () -> {
                try {
                  var state =
                      client.getSingleplayerServer().getLevel(Level.OVERWORLD).getBlockState(block);
                  lastServerBlockState = state.toString();
                  check.complete(state.isAir());
                } catch (Throwable failure) {
                  check.completeExceptionally(failure);
                }
              });
    }
  }

  private static boolean turn(Minecraft client, float yaw, float pitch, double delta) {
    var player = client.player;
    float difference = net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot());
    float step = (float) (100 * delta);
    player.setYRot(player.getYRot() + Math.clamp(difference, -step, step));
    player.setXRot(player.getXRot() + Math.clamp(pitch - player.getXRot(), -step, step));
    return Math.abs(difference) < 6 && Math.abs(pitch - player.getXRot()) < 5;
  }

  Sample sample(Minecraft client) {
    var player = client.player;
    return new Sample(
        phase,
        player.getX(),
        player.getY(),
        player.getZ(),
        player.getYRot(),
        player.getXRot(),
        client.gameMode.isDestroying() ? client.gameMode.getDestroyStage() : -1,
        !client.levelRenderer.hasRenderedAllSections(),
        client.level.getChunkSource().getLoadedChunksCount());
  }

  void validate() {
    if (kind == Kind.FOREST_WALK && (walkedDistance < 20 || completedWalkLegs == 0))
      throw new IllegalStateException(
          "Forest benchmark did not complete a natural ground route: "
              + walkedDistance
              + " blocks, "
              + completedWalkLegs
              + " completed legs");
    if (kind == Kind.CAVERN_BREAK && (confirmedBreaks < 3 || maximumCrackStage < 1))
      throw new IllegalStateException(
          "Cavern benchmark needs 3 server-confirmed natural breaks and a crack overlay; got "
              + confirmedBreaks
              + " breaks, stage "
              + maximumCrackStage);
  }

  Map<String, Object> result() {
    return Map.of(
        "kind",
        kind.name(),
        "fixture",
        fixture == null ? Map.of() : fixture.evidence(),
        "markers",
        List.copyOf(markers),
        "walkedDistanceBlocks",
        walkedDistance,
        "serverConfirmedBreaks",
        confirmedBreaks,
        "maximumCrackStage",
        maximumCrackStage,
        "completedWalkLegs",
        completedWalkLegs,
        "controlSnapshots",
        List.copyOf(snapshots));
  }

  /** Failure diagnostics are action evidence only; they deliberately contain no FPS summary. */
  Map<String, Object> diagnostics(Minecraft client, long elapsed) {
    return Map.of("evidence", result(), "currentState", currentState(client, elapsed));
  }

  private Map<String, Object> currentState(Minecraft client, long elapsed) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("elapsedSeconds", elapsed / 1e9);
    state.put("kind", kind.name());
    state.put("phase", phase);
    state.put("targetIndex", target);
    state.put("targetCount", fixture == null ? 0 : fixture.targets().size());
    state.put("serverConfirmedBreaks", confirmedBreaks);
    state.put("maximumCrackStage", maximumCrackStage);
    state.put("clientPredictedRemoved", clientRemoved);
    state.put(
        "serverCheck", serverCheck == null ? "idle" : serverCheck.isDone() ? "ready" : "pending");
    state.put("lastServerBlockState", lastServerBlockState);
    if (fixture != null && target < fixture.targets().size()) {
      MiningTarget requested = fixture.targets().get(target);
      state.put("requestedTarget", requested.values());
      if (client.level != null)
        state.put("clientTargetState", client.level.getBlockState(requested.block()).toString());
    }
    state.put("requestedYaw", requestedYaw);
    state.put("requestedPitch", requestedPitch);
    state.put("aimReady", aimReady);
    state.put("targetHit", targetHit);
    if (client.hitResult != null) {
      state.put("crosshairType", client.hitResult.getType().name());
      state.put("crosshairPosition", position(client.hitResult.getLocation()));
      if (client.hitResult instanceof BlockHitResult hit) {
        state.put("crosshairBlock", hit.getBlockPos().toShortString());
        state.put("crosshairFace", hit.getDirection().name());
      }
    }
    if (client.player != null) {
      state.put("playerPosition", position(client.player.position()));
      state.put("eyePosition", position(client.player.getEyePosition()));
      state.put("yaw", client.player.getYRot());
      state.put("pitch", client.player.getXRot());
      state.put("onGround", client.player.onGround());
      state.put("heldItem", client.player.getMainHandItem().toString());
    }
    state.put("keyAttack", client.options.keyAttack.isDown());
    state.put("keyUp", client.options.keyUp.isDown());
    state.put("keyJump", client.options.keyJump.isDown());
    state.put("mouseGrabbed", client.mouseHandler.isMouseGrabbed());
    state.put("windowFocused", client.getWindow().isFocused());
    state.put("paused", client.isPaused());
    state.put(
        "screen", client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
    state.put(
        "overlay",
        client.gui.overlay() == null ? "none" : client.gui.overlay().getClass().getName());
    state.put(
        "destroyStage",
        client.gameMode != null && client.gameMode.isDestroying()
            ? client.gameMode.getDestroyStage()
            : -1);
    return state;
  }

  private static Map<String, Double> position(Vec3 position) {
    return Map.of("x", position.x, "y", position.y, "z", position.z);
  }

  void release(Minecraft client) {
    client.options.keyUp.setDown(false);
    client.options.keyJump.setDown(false);
    client.options.keyAttack.setDown(false);
  }

  private void mark(long elapsed, String event, Map<String, Object> details) {
    if (markers.size() == 512) markers.removeFirst();
    markers.add(Map.of("elapsedSeconds", elapsed / 1e9, "event", event, "details", details));
    System.out.println(
        "[Shader benchmark action] " + event + " at " + elapsed / 1e9 + "s: " + details);
  }

  private static Fixture findForest(ServerLevel level, int centerX, int centerZ) {
    String fixedDirectionName =
        System.getProperty("minecraftShaders.benchmark.forestDirection", "");
    Direction fixedDirection =
        switch (fixedDirectionName) {
          case "" -> null;
          case "north" -> Direction.NORTH;
          case "south" -> Direction.SOUTH;
          case "east" -> Direction.EAST;
          case "west" -> Direction.WEST;
          default ->
              throw new IllegalArgumentException(
                  "forestDirection must be north, south, east or west");
        };
    final int searchRadius = fixedDirection == null ? 96 : 0;
    List<BlockPos> bestPath = null;
    Direction bestDirection = null;
    ForestDensity bestDensity = null;
    int walkableCandidates = 0, interiorCandidates = 0;
    // Inspect the whole bounded area. Returning the first route biases selection toward bare beach
    // corridors: forest grass is walkable, while trunks and low branches should remain obstacles.
    for (int radius = 0; radius <= searchRadius; radius += 4)
      for (int dx = -radius; dx <= radius; dx += 4)
        for (int dz = -radius; dz <= radius; dz += 4) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
          for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (fixedDirection != null && direction != fixedDirection) continue;
            List<BlockPos> path = new ArrayList<>();
            int previousY = Integer.MIN_VALUE;
            for (int step = 0; step <= 24; step++) {
              int x = centerX + dx + direction.getStepX() * step;
              int z = centerZ + dz + direction.getStepZ() * step;
              int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
              BlockPos feet = new BlockPos(x, y, z);
              var soil = level.getBlockState(feet.below());
              if (!forestGround(soil)
                  || !level.getBiome(feet).is(BiomeTags.IS_FOREST)
                  || !forestStandable(level, feet)
                  || (previousY != Integer.MIN_VALUE && Math.abs(y - previousY) > 1)) break;
              previousY = y;
              path.add(feet);
            }
            if (path.size() != 25) continue;
            walkableCandidates++;
            ForestDensity density = forestDensity(level, path, direction);
            if (!density.interior()) continue;
            interiorCandidates++;
            if (bestDensity == null || density.score() > bestDensity.score()) {
              bestPath = List.copyOf(path);
              bestDirection = direction;
              bestDensity = density;
            }
          }
        }
    if (bestPath == null)
      throw new IllegalStateException(
          "No 24-block natural interior forest route in bounded search; "
              + walkableCandidates
              + " walkable forest-soil routes, "
              + interiorCandidates
              + " canopy-qualified routes");
    BlockPos start = bestPath.getFirst();
    float yaw =
        (float) Math.toDegrees(Math.atan2(-bestDirection.getStepX(), bestDirection.getStepZ()));
    return new Fixture(
        new Viewpoint(start.getX() + .5, start.getY(), start.getZ() + .5, yaw, 5),
        bestPath,
        List.of(),
        Map.ofEntries(
            Map.entry("seed", 1),
            Map.entry(
                "selector",
                fixedDirection == null
                    ? "ranked_natural_forest_interior"
                    : "verified_fixed_natural_forest_route"),
            Map.entry("route", bestPath.stream().map(BlockPos::toShortString).toList()),
            Map.entry("nearbyLeafSamples", bestDensity.leaves()),
            Map.entry("minimumStationLeafSamples", bestDensity.minimumStationLeaves()),
            Map.entry("leftLeafSamples", bestDensity.leftLeaves()),
            Map.entry("rightLeafSamples", bestDensity.rightLeaves()),
            Map.entry("canopyColumns", bestDensity.canopyColumns()),
            Map.entry("sampledCanopyColumns", 63),
            Map.entry("selectionScore", bestDensity.score()),
            Map.entry("walkableCandidates", walkableCandidates),
            Map.entry("interiorCandidates", interiorCandidates),
            Map.entry("searchRadiusBlocks", searchRadius),
            Map.entry("terrainEdits", 0)));
  }

  private record ForestDensity(
      int leaves,
      int minimumStationLeaves,
      int leftLeaves,
      int rightLeaves,
      int canopyColumns,
      boolean forestOnBothSides) {
    boolean interior() {
      return forestOnBothSides
          && leaves >= 250
          && minimumStationLeaves >= 24
          && Math.min(leftLeaves, rightLeaves) >= 80
          && canopyColumns >= 25;
    }

    int score() {
      // Favor continuous surrounding foliage and canopy over one dense tree at an endpoint.
      return minimumStationLeaves * 12
          + Math.min(leftLeaves, rightLeaves) * 2
          + canopyColumns * 12
          + leaves;
    }
  }

  private static ForestDensity forestDensity(
      ServerLevel level, List<BlockPos> path, Direction forward) {
    int leaves = 0, minimum = Integer.MAX_VALUE, left = 0, right = 0, canopy = 0;
    Direction side = forward.getClockWise();
    boolean forestOnBothSides = true;
    for (int step = 0; step < path.size(); step += 4) {
      BlockPos feet = path.get(step);
      forestOnBothSides &=
          level.getBiome(feet.relative(side, 8)).is(BiomeTags.IS_FOREST)
              && level.getBiome(feet.relative(side.getOpposite(), 8)).is(BiomeTags.IS_FOREST);
      int station = 0;
      for (int x = -8; x <= 8; x += 2)
        for (int z = -8; z <= 8; z += 2)
          for (int y = 1; y <= 11; y += 2)
            if (level.getBlockState(feet.offset(x, y, z)).is(BlockTags.LEAVES)) {
              station++;
              int lateral = x * side.getStepX() + z * side.getStepZ();
              if (lateral < 0) left++;
              else if (lateral > 0) right++;
            }
      leaves += station;
      minimum = Math.min(minimum, station);
      for (int x = -3; x <= 3; x += 3)
        for (int z = -3; z <= 3; z += 3)
          for (int y = 2; y <= 12; y++)
            if (level.getBlockState(feet.offset(x, y, z)).is(BlockTags.LEAVES)) {
              canopy++;
              break;
            }
    }
    return new ForestDensity(leaves, minimum, left, right, canopy, forestOnBothSides);
  }

  private static boolean forestGround(net.minecraft.world.level.block.state.BlockState soil) {
    return soil.is(Blocks.GRASS_BLOCK)
        || soil.is(Blocks.DIRT)
        || soil.is(Blocks.COARSE_DIRT)
        || soil.is(Blocks.PODZOL)
        || soil.is(Blocks.ROOTED_DIRT)
        || soil.is(Blocks.MOSS_BLOCK);
  }

  private static boolean forestStandable(ServerLevel level, BlockPos feet) {
    // The route crosses one-block steps in both directions: standing clearance alone
    // admits low leaves that stop the jump. Require headroom above the standing player.
    return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
        && level.getBlockState(feet.above(2)).getCollisionShape(level, feet.above(2)).isEmpty()
        && level.getFluidState(feet).isEmpty()
        && level.getFluidState(feet.above()).isEmpty()
        && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
  }

  private static Fixture findCavern(ServerLevel level, int centerX, int centerZ) {
    var player = level.getServer().getPlayerList().getPlayers().getFirst();
    for (int radius = 0; radius <= 96; radius += 8)
      for (int dx = -radius; dx <= radius; dx += 8)
        for (int dz = -radius; dz <= radius; dz += 8) {
          if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
          for (int y = 40; y >= -48; y--) {
            BlockPos feet = new BlockPos(centerX + dx, y, centerZ + dz);
            if (!standable(level, feet) || level.canSeeSky(feet.above())) continue;
            int ceiling = 0;
            while (ceiling < 10 && level.getBlockState(feet.above(ceiling)).isAir()) ceiling++;
            if (ceiling < 5) continue;
            Direction view = null;
            int longest = 0;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
              int length = 0;
              while (length < 24
                  && level.getBlockState(feet.above().relative(direction, length + 1)).isAir())
                length++;
              if (length > longest) {
                longest = length;
                view = direction;
              }
            }
            if (longest < 18) continue;
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0, 1.62, 0);
            List<MiningTarget> targets = new ArrayList<>();
            for (int x = -3; x <= 3; x++)
              for (int z = -3; z <= 3; z++)
                for (int h = 0; h <= 2; h++) {
                  BlockPos block = feet.offset(x, h, z);
                  if (x * view.getStepX() + z * view.getStepZ() < 0) continue;
                  var state = level.getBlockState(block);
                  if (!state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE)) continue;
                  // Full-cube natural stone must offer an unobstructed face interior. Center
                  // rays can graze an intervening voxel edge and are not reliable client aims.
                  MiningTarget aim = visibleFace(level, player, eye, block);
                  if (aim != null) targets.add(aim);
                }
            if (targets.size() < 3) continue;
            int open = 0, volume = 0;
            for (int x = -12; x <= 12; x += 2)
              for (int z = -12; z <= 12; z += 2)
                for (int h = 0; h <= 8; h += 2) {
                  volume++;
                  if (level.getBlockState(feet.offset(x, h, z)).isAir()) open++;
                }
            if (open < volume * .45) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-view.getStepX(), view.getStepZ()));
            return new Fixture(
                new Viewpoint(feet.getX() + .5, feet.getY(), feet.getZ() + .5, yaw, 0),
                List.of(),
                List.copyOf(targets.subList(0, Math.min(12, targets.size()))),
                Map.of(
                    "seed",
                    1,
                    "selector",
                    "natural_enclosed_cavern",
                    "searchRadiusBlocks",
                    96,
                    "openVolumeSamples",
                    open,
                    "volumeSamples",
                    volume,
                    "clearViewBlocks",
                    longest,
                    "ceilingClearanceBlocks",
                    ceiling,
                    "targets",
                    targets.stream().limit(12).map(MiningTarget::values).toList(),
                    "terrainEditsBeforeMeasurement",
                    0));
          }
        }
    throw new IllegalStateException(
        "No large natural cavern with 3 reachable original stone targets in bounded search");
  }

  private static MiningTarget visibleFace(
      ServerLevel level, net.minecraft.world.entity.Entity player, Vec3 eye, BlockPos block) {
    MiningTarget best = null;
    double nearest = Double.POSITIVE_INFINITY;
    for (Direction face : Direction.values()) {
      Vec3 point =
          Vec3.atCenterOf(block)
              .add(face.getStepX() * .499, face.getStepY() * .499, face.getStepZ() * .499);
      double distance = point.distanceTo(eye);
      if (distance > 4.2 || distance >= nearest) continue;
      var hit =
          level.clip(
              new ClipContext(
                  eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      if (hit.getType() != HitResult.Type.BLOCK
          || !hit.getBlockPos().equals(block)
          || hit.getDirection() != face) continue;
      Vec3 local = hit.getLocation().subtract(block.getX(), block.getY(), block.getZ());
      boolean interior =
          switch (face.getAxis()) {
            case X -> local.y > .2 && local.y < .8 && local.z > .2 && local.z < .8;
            case Y -> local.x > .2 && local.x < .8 && local.z > .2 && local.z < .8;
            case Z -> local.x > .2 && local.x < .8 && local.y > .2 && local.y < .8;
          };
      if (interior) {
        best = new MiningTarget(block.immutable(), point, face);
        nearest = distance;
      }
    }
    return best;
  }

  private static boolean standable(ServerLevel level, BlockPos feet) {
    return level.getBlockState(feet).isAir()
        && level.getBlockState(feet.above()).isAir()
        && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
        && level.getFluidState(feet.below()).isEmpty();
  }
}
