package Portal.code;

import io.netty.util.internal.EmptyPriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jline.utils.Log;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.apigame.ShipTeleportData;
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountingEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShipTeleportationUtils {

    public record BlockSnapshot(BlockPos pos, BlockState state) {}
    private record TeleportData(Vector3d newPos, Quaterniond rotation, Vector3d velocity, Vector3d omega) {}
    private record MountingData(Entity vehicle, Vec3 relativeMountPos, float yawOffset) {}

    private static final double ENTITY_COLLECT_RANGE = .5;
    private static final double SHIP_COLLECT_RANGE = .5;

    private final Map<Long, TeleportData> ships = new ConcurrentHashMap<>();
    private final Map<Entity, Vec3> entityToPos = new ConcurrentHashMap<>();
    private final Map<Entity, Entity> oldToNewEntity = new ConcurrentHashMap<>();
    private final Map<Entity, MountingData> entityMountingData = new ConcurrentHashMap<>();
    private ServerLevel oldLevel;
    private ServerLevel newLevel;

    public void reset(final ServerLevel oldLevel, final ServerLevel newLevel) {
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
        this.ships.clear();
        this.entityToPos.clear();
        this.oldToNewEntity.clear();
        this.entityMountingData.clear();
    }

    public static void teleportShipWithFallback(Ship ship, ServerLevel currentWorld, ServerLevel targetWorld, Vector3d exactTargetPos, float rotationAngle) {
        ShipTeleportationUtils handler = new ShipTeleportationUtils();
        handler.reset(currentWorld, targetWorld);

        var currentPos = ship.getTransform().getPositionInWorld();

        Logger.sendMessage("[Portal Skies] Teleporting ship from " +
                String.format("%.2f, %.2f, %.2f", currentPos.x(), currentPos.y(), currentPos.z()) +
                " to exact position " +
                String.format("%.2f, %.2f, %.2f", exactTargetPos.x, exactTargetPos.y, exactTargetPos.z), true);

        double nudgeY = 0.1;
        Vector3d targetPosition = new Vector3d(exactTargetPos.x, exactTargetPos.y + nudgeY, exactTargetPos.z);

        Quaterniond newRotation = new Quaterniond(ship.getTransform().getShipToWorldRotation());
        Quaterniond additionalRotation = new Quaterniond();

        if (rotationAngle != 0.0f) {
            Logger.sendMessage("[Portal Skies] Applying " + rotationAngle + "° rotation to ship", true);
            additionalRotation = new Quaterniond().rotateY(Math.toRadians(rotationAngle));
            newRotation = newRotation.mul(additionalRotation);
            Logger.sendMessage("[Portal Skies] DEBUG: New combined rotation: " + newRotation, false);
        }

        if (!handler.isTeleportLocationSafe((ServerShip) ship, targetPosition, newRotation, targetWorld, currentWorld)) {
            Logger.sendMessage("Teleportation cancelled due to collision risk", true);
            return;
        }

        Vector3d velocity = new Vector3d(ship.getVelocity());
        Vector3d omega = new Vector3d(ship.getOmega());

        Logger.sendMessage("Starting ship teleport from " + currentWorld.dimension().location() +
                " to " + targetWorld.dimension().location(), true);

        handler.addShip((ServerShip) ship, targetPosition, newRotation, velocity, omega, additionalRotation, rotationAngle);
        handler.finalizeTeleport();

        // Create new log file for next teleport attempt

    }
    private boolean isTeleportLocationSafe(final ServerShip ship, final Vector3d targetPos, final Quaterniond targetRotation, final ServerLevel targetWorld, final ServerLevel currentWorld) {
        try {
            // Get ship's local AABB for dimensions (in ship-local coordinates)
            var shipAABB = ship.getShipAABB();
            if (shipAABB == null) {
                Logger.sendMessage("Cannot check collision: Ship local AABB is null", true);
                return false;
            }

            // Get ship dimensions from local AABB
            double shipWidth = shipAABB.maxX() - shipAABB.minX();
            double shipHeight = shipAABB.maxY() - shipAABB.minY();
            double shipLength = shipAABB.maxZ() - shipAABB.minZ();

            Logger.sendMessage("[Portal Skies] Ship dimensions (local): " +
                    String.format("%.1fx%.1fx%.1f", shipWidth, shipHeight, shipLength), false);

            // Calculate half-dimensions (offsets from center to edges)
            double halfWidth = shipWidth / 2.0;
            double halfHeight = shipHeight / 2.0;
            double halfLength = shipLength / 2.0;

            // Create a local AABB centered at origin with ship's dimensions
            // This represents the ship's collision box relative to its center
            AABBd localAABB = new AABBd(
                    -halfWidth, -halfHeight, -halfLength,
                    halfWidth, halfHeight, halfLength
            );

            Logger.sendMessage("[Portal Skies] Local AABB centered at origin:", false);
            Logger.sendMessage("[Portal Skies] - Min: " +
                    String.format("%.1f, %.1f, %.1f", localAABB.minX, localAABB.minY, localAABB.minZ), false);
            Logger.sendMessage("[Portal Skies] - Max: " +
                    String.format("%.1f, %.1f, %.1f", localAABB.maxX, localAABB.maxY, localAABB.maxZ), false);

            // SIMPLIFIED: Use only the target rotation since we're checking collision at destination
            // The ship's current rotation doesn't matter for collision detection at target location
            AABBd targetWorldAABB = transformCenteredLocalAABBToWorld(localAABB, targetRotation, targetPos);

            Logger.sendMessage("[Portal Skies] Collision check:", false);
            Logger.sendMessage("[Portal Skies] - Target pos: " +
                    String.format("%.1f, %.1f, %.1f", targetPos.x, targetPos.y, targetPos.z), false);
            Logger.sendMessage("[Portal Skies] - Target AABB min: " +
                    String.format("%.1f, %.1f, %.1f", targetWorldAABB.minX, targetWorldAABB.minY, targetWorldAABB.minZ), false);
            Logger.sendMessage("[Portal Skies] - Target AABB max: " +
                    String.format("%.1f, %.1f, %.1f", targetWorldAABB.maxX, targetWorldAABB.maxY, targetWorldAABB.maxZ), false);

            // Check for block collisions in the target world
            if (checkBlockCollision(targetWorldAABB, targetWorld, currentWorld)) {
                return false;
            }

            // Check for ship collisions in the target world
            if (checkShipCollision(ship.getId(), targetWorldAABB, targetWorld, currentWorld)) {
                return false;
            }

            Logger.sendMessage("Teleport location is safe - no collisions detected", true);
            return true;

        } catch (Exception e) {
            Logger.sendMessage("Error checking teleport safety: " + e.getMessage(), true);
            e.printStackTrace();
            return false;
        }
    }

    private AABBd transformCenteredLocalAABBToWorld(final AABBd localAABB, final Quaterniond targetRotation, final Vector3d targetPos) {
        double minX = localAABB.minX;
        double minY = localAABB.minY;
        double minZ = localAABB.minZ;
        double maxX = localAABB.maxX;
        double maxY = localAABB.maxY;
        double maxZ = localAABB.maxZ;

        Vector3d[] localCorners = new Vector3d[] {
                new Vector3d(minX, minY, minZ), // bottom back left
                new Vector3d(maxX, minY, minZ), // bottom back right
                new Vector3d(minX, maxY, minZ), // top back left
                new Vector3d(maxX, maxY, minZ), // top back right
                new Vector3d(minX, minY, maxZ), // bottom front left
                new Vector3d(maxX, minY, maxZ), // bottom front right
                new Vector3d(minX, maxY, maxZ), // top front left
                new Vector3d(maxX, maxY, maxZ)  // top front right
        };

        double minWorldX = Double.MAX_VALUE;
        double minWorldY = Double.MAX_VALUE;
        double minWorldZ = Double.MAX_VALUE;
        double maxWorldX = -Double.MAX_VALUE;
        double maxWorldY = -Double.MAX_VALUE;
        double maxWorldZ = -Double.MAX_VALUE;

        for (Vector3d corner : localCorners) {
            // Apply target rotation to get the ship's orientation at destination
            Vector3d rotatedCorner = new Vector3d(corner);
            targetRotation.transform(rotatedCorner);

            // Position at target location (centered around targetPos)
            Vector3d worldCorner = new Vector3d(
                    targetPos.x + rotatedCorner.x,
                    targetPos.y + rotatedCorner.y,
                    targetPos.z + rotatedCorner.z
            );

            minWorldX = Math.min(minWorldX, worldCorner.x);
            minWorldY = Math.min(minWorldY, worldCorner.y);
            minWorldZ = Math.min(minWorldZ, worldCorner.z);
            maxWorldX = Math.max(maxWorldX, worldCorner.x);
            maxWorldY = Math.max(maxWorldY, worldCorner.y);
            maxWorldZ = Math.max(maxWorldZ, worldCorner.z);
        }

        // Add a small margin to prevent floating-point precision issues
        double margin = 0.05;
        return new AABBd(
                minWorldX - margin, minWorldY - margin, minWorldZ - margin,
                maxWorldX + margin, maxWorldY + margin, maxWorldZ + margin
        );
    }

    private boolean checkBlockCollision(final AABBd worldAABB, final ServerLevel targetWorld, final ServerLevel currentWorld) {
        AABB minecraftAABB = new AABB(
                worldAABB.minX, worldAABB.minY, worldAABB.minZ,
                worldAABB.maxX, worldAABB.maxY, worldAABB.maxZ
        );

        Logger.sendMessage("[Portal Skies] Scanning ALL blocks in ship AABB:", false);
        Logger.sendMessage("[Portal Skies] - Min: " +
                String.format("%.1f, %.1f, %.1f", minecraftAABB.minX, minecraftAABB.minY, minecraftAABB.minZ), false);
        Logger.sendMessage("[Portal Skies] - Max: " +
                String.format("%.1f, %.1f, %.1f", minecraftAABB.maxX, minecraftAABB.maxY, minecraftAABB.maxZ), false);

        int minX = (int) Math.floor(minecraftAABB.minX);
        int maxX = (int) Math.floor(minecraftAABB.maxX);
        int minY = (int) Math.floor(minecraftAABB.minY);
        int maxY = (int) Math.floor(minecraftAABB.maxY);
        int minZ = (int) Math.floor(minecraftAABB.minZ);
        int maxZ = (int) Math.floor(minecraftAABB.maxZ);

        Logger.sendMessage("[Portal Skies] Checking blocks from " +
                minX + "," + minY + "," + minZ + " to " + maxX + "," + maxY + "," + maxZ, false);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = targetWorld.getBlockState(pos);

                    if (isBlockTrulySolid(state)) {
                        Logger.sendMessage("FOUND SOLID BLOCK: " + state.getBlock() + " at " + pos, true);
                        Logger.sendMessage("Cancelling teleport - solid block detected", true);
                        return true;
                    }
                }
            }
        }

        Logger.sendMessage("No solid blocks found", false);
        return false;
    }

    private boolean isBlockTrulySolid(BlockState state) {
        if (state.isAir()) {
            return false;
        }

        boolean isSolid = state.isSolid();
        boolean blocksMotion = state.blocksMotion();

        if (isSolid && blocksMotion) {
            String blockName = state.getBlock().getDescriptionId().toLowerCase();

            if (blockName.contains("leaves") ||
                    blockName.contains("slab") ||
                    blockName.contains("stairs") ||
                    blockName.contains("fence") ||
                    blockName.contains("wall") ||
                    blockName.contains("pane") ||
                    blockName.contains("chain") ||
                    blockName.contains("iron_bars") ||
                    blockName.contains("sign") ||
                    blockName.contains("banner") ||
                    blockName.contains("torch") ||
                    blockName.contains("carpet") ||
                    blockName.contains("pressure_plate") ||
                    blockName.contains("button") ||
                    blockName.contains("flower") ||
                    blockName.contains("grass") ||
                    blockName.contains("vine") ||
                    blockName.contains("snow")) {
                return false;
            }

            return true;
        }

        return false;
    }

    private boolean checkShipCollision(final long currentShipId, final AABBd worldAABB, final ServerLevel targetWorld, final ServerLevel currentWorld) {
        var shipWorld = VSGameUtilsKt.getShipObjectWorld(targetWorld);
        if (shipWorld == null) {
            return false;
        }

        int collisionCount = 0;

        for (final Ship otherShip : shipWorld.getAllShips()) {
            if (otherShip.getId() == currentShipId) {
                continue;
            }

            AABBdc otherAABB = otherShip.getWorldAABB();
            if (otherAABB != null && otherAABB.intersectsAABB(worldAABB)) {
                collisionCount++;
                Logger.sendMessage("Ship would collide with ship ID: " + otherShip.getId(), true);

                if (collisionCount > 1) {
                    return true;
                }
            }
        }

        return collisionCount > 0;
    }

    public void addShip(final ServerShip ship, final Vector3d newPos, final Quaterniond rotation, final Vector3d velocity, final Vector3d omega, final Quaterniond additionalRotation, float rotationAngle) {
        final long shipId = ship.getId();
        if (this.ships.containsKey(shipId)) {
            return;
        }

        final List<ServerShip> collected = new ArrayList<>();
        final Vector3d origin = (Vector3d) ship.getTransform().getPositionInWorld();

        this.collectMainShip(shipId, origin, newPos, rotation, velocity, omega, collected);
        this.collectNearbyShips(collected, origin, newPos, additionalRotation, rotationAngle);
        this.collectNearbyEntities(collected, origin, newPos, additionalRotation);
    }

    private void collectMainShip(
            final long shipId,
            final Vector3d origin,
            final Vector3d newPos,
            final Quaterniond finalRotation,
            final Vector3d velocity,
            final Vector3d omega, List<ServerShip> collected
    ) {
        if (this.ships.containsKey(shipId)) {
            return;
        }
        final ServerShip ship = this.getShip(shipId);
        if (ship == null) {
            return;
        }
        collected.add(ship);

        final Vector3d finalPos = new Vector3d(newPos);
        final Vector3d newVelocity = finalRotation.transform(velocity, new Vector3d());

        this.ships.put(
                shipId,
                new TeleportData(
                        finalPos,
                        finalRotation,
                        newVelocity,
                        omega
                )
        );

        Logger.sendMessage("Preparing to teleport MAIN ship ID: " + shipId + " to " +
                String.format("%.2f, %.2f, %.2f", finalPos.x, finalPos.y, finalPos.z), false);
    }

    private void collectShipAndConnected(
            final long shipId,
            final Vector3d origin,
            final Vector3d newPos,
            final Quaterniond additionalRotation,
            final Vector3d velocity,
            final Vector3d omega,
            final List<ServerShip> collected,
            final float rotationAngle
    ) {
        if (this.ships.containsKey(shipId)) {
            return;
        }
        final ServerShip ship = this.getShip(shipId);
        if (ship == null) {
            return;
        }

        collected.add(ship);

        final Vector3d pos = (Vector3d) ship.getTransform().getPositionInWorld();
        final Vector3d relPos = new Vector3d(
                pos.x - origin.x,
                pos.y - origin.y,
                pos.z - origin.z
        );

        additionalRotation.transform(relPos);

        final Vector3d finalPos = new Vector3d(
                newPos.x + relPos.x,
                newPos.y + relPos.y,
                newPos.z + relPos.z
        );

        final Quaterniond currentRotation = new Quaterniond(ship.getTransform().getShipToWorldRotation());
        final Quaterniond newRotation = new Quaterniond(currentRotation).mul(additionalRotation);
        final Vector3d newVelocity = additionalRotation.transform(velocity, new Vector3d());

        this.ships.put(
                shipId,
                new TeleportData(
                        finalPos,
                        newRotation,
                        newVelocity,
                        omega
                )
        );
    }

    private void collectNearbyShips(final List<ServerShip> collected, final Vector3d origin, final Vector3d newPos, final Quaterniond additionalRotation, final float rotationAngle) {
        var shipWorld = VSGameUtilsKt.getShipObjectWorld(oldLevel);
        if (shipWorld == null) return;

        for (int i = 0; i < collected.size(); i++) {
            final AABBdc shipBox = collected.get(i).getWorldAABB();
            if (shipBox == null) continue;

            final AABBd box = new AABBd(
                    shipBox.minX() - SHIP_COLLECT_RANGE, shipBox.minY() - SHIP_COLLECT_RANGE, shipBox.minZ() - SHIP_COLLECT_RANGE,
                    shipBox.maxX() + SHIP_COLLECT_RANGE, shipBox.maxY() + SHIP_COLLECT_RANGE, shipBox.maxZ() + SHIP_COLLECT_RANGE);

            for (final Ship nearbyShip : shipWorld.getAllShips()) {
                if (nearbyShip.getWorldAABB() != null && nearbyShip.getWorldAABB().intersectsAABB(box)) {
                    if (!this.ships.containsKey(nearbyShip.getId()) && nearbyShip instanceof ServerShip serverShip) {
                        this.collectShipAndConnected(serverShip.getId(), origin, newPos, additionalRotation,
                                new Vector3d(nearbyShip.getVelocity()),
                                new Vector3d(nearbyShip.getOmega()),
                                collected, rotationAngle);
                    }
                }
            }
        }
    }

    private void collectNearbyEntities(final List<ServerShip> collected, final Vector3d origin, final Vector3d newPos, final Quaterniond additionalRotation) {
        for (final ServerShip ship : collected) {
            this.collectEntities(ship, origin, newPos, additionalRotation);
        }
    }

    private void collectEntities(final ServerShip ship, final Vector3d origin, final Vector3d newPos, final Quaterniond additionalRotation) {
        final AABBd shipBox = new AABBd(ship.getWorldAABB());

        final AABB collectionBox = new AABB(
                shipBox.minX - ENTITY_COLLECT_RANGE, shipBox.minY - ENTITY_COLLECT_RANGE, shipBox.minZ - ENTITY_COLLECT_RANGE,
                shipBox.maxX + ENTITY_COLLECT_RANGE, shipBox.maxY + ENTITY_COLLECT_RANGE, shipBox.maxZ + ENTITY_COLLECT_RANGE
        );

        List<Entity> foundEntities = this.oldLevel.getEntities((Entity) null, collectionBox,
                (entity) -> !this.entityToPos.containsKey(entity) && shouldTeleportEntity(entity));

        Set<Entity> allEntitiesToTeleport = new HashSet<>();

        for (final Entity entity : foundEntities) {
            collectEntityAndAllRelated(entity, allEntitiesToTeleport);
        }

        for (final Entity entity : allEntitiesToTeleport) {
            this.collectEntityDirectly(entity, origin, newPos, additionalRotation);
        }
    }

    private void collectEntityAndAllRelated(Entity entity, Set<Entity> collected) {
        if (collected.contains(entity)) {
            return;
        }

        if (!shouldTeleportEntity(entity)) {
            return;
        }

        collected.add(entity);

        if (entity.isPassenger() && entity.getVehicle() != null) {
            Entity vehicle = entity.getVehicle();
            Vec3 relativePos = entity.position().subtract(vehicle.position());
            float yawOffset = entity.getYRot() - vehicle.getYRot();
            entityMountingData.put(entity, new MountingData(vehicle, relativePos, yawOffset));

            Logger.sendMessage("[Portal Skies] Tracked mounting: " + entity.getType() +
                  " relative pos: " + String.format("%.2f, %.2f, %.2f", relativePos.x, relativePos.y, relativePos.z) +
                " yaw offset: " + yawOffset, false);
        }

        for (Entity passenger : entity.getPassengers()) {
            collectEntityAndAllRelated(passenger, collected);
        }

        if (entity.isPassenger() && entity.getVehicle() != null) {
            collectEntityAndAllRelated(entity.getVehicle(), collected);
        }
    }

    private void collectEntityDirectly(final Entity entity, final Vector3d origin, final Vector3d newPos, final Quaterniond additionalRotation) {
        Vector3d entityWorldPos = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        Vector3d relPos = new Vector3d(
                entityWorldPos.x - origin.x,
                entityWorldPos.y - origin.y,
                entityWorldPos.z - origin.z
        );

        additionalRotation.transform(relPos);

        Vector3d finalPos = new Vector3d(
                newPos.x + relPos.x,
                newPos.y + relPos.y,
                newPos.z + relPos.z
        );

        this.entityToPos.put(entity, new Vec3(finalPos.x, finalPos.y, finalPos.z));

        float newYaw = calculateEntityNewYaw(entity, additionalRotation);
        entity.setYRot(newYaw);
        entity.setYHeadRot(newYaw);
    }

    private float calculateEntityNewYaw(Entity entity, Quaterniond rotation) {
        float currentYaw = entity.getYRot();
        double rad = Math.toRadians(currentYaw);
        Vector3d currentForward = new Vector3d(-Math.sin(rad), 0, Math.cos(rad));

        rotation.transform(currentForward);

        double newYawRad = Math.atan2(-currentForward.x, currentForward.z);
        float newYaw = (float) Math.toDegrees(newYawRad);

        newYaw = newYaw % 360;
        if (newYaw < 0) newYaw += 360;

        return newYaw;
    }

    // Convert world position to shipyard coordinates
    private Vector3d convertWorldToShipyardCoordinates(Vec3 worldPos, ServerShip ship) {
        var transform = ship.getTransform();
        Vector3d worldVec = new Vector3d(worldPos.x, worldPos.y, worldPos.z);
        Vector3d shipyardPos = new Vector3d();
        transform.getWorldToShip().transformPosition(worldVec, shipyardPos);
        return shipyardPos;
    }

    // IMPROVED: Force helm block interaction with detailed debugging

    // IMPROVED: Force block interaction with detailed debugging
    private void forceBlockInteraction(ServerPlayer player, BlockPos blockPos) {
        try {
             Logger.sendMessage("[Portal Skies] >>> Starting block interaction debug", false);
            Logger.sendMessage("[Portal Skies] Target block position: " + blockPos, false);

            BlockState blockState = newLevel.getBlockState(blockPos);
            Logger.sendMessage("[Portal Skies] Block state: " + blockState, false);
            Logger.sendMessage("[Portal Skies] Block is air: " + blockState.isAir(), false);

            // Calculate positions
            Vec3 hitVec = new Vec3(
                    blockPos.getX() + 0.5,
                    blockPos.getY() + 1.0,
                    blockPos.getZ() + 0.5
            );

            Vec3 playerPos = new Vec3(
                    blockPos.getX() + 0.5,
                    blockPos.getY() + 1.5,
                    blockPos.getZ() + 0.5
            );

            Logger.sendMessage("[Portal Skies] Player teleport position: " +
            String.format("%.2f, %.2f, %.2f", playerPos.x, playerPos.y, playerPos.z), false);
            Logger.sendMessage("[Portal Skies] Hit vector: " +
            String.format("%.2f, %.2f, %.2f", hitVec.x, hitVec.y, hitVec.z), false);

            // Create hit result
            BlockHitResult hitResult = new BlockHitResult(
                    hitVec,
                    net.minecraft.core.Direction.UP,
                    blockPos,
                    false
            );

            Logger.sendMessage("[Portal Skies] Hit result direction: " + hitResult.getDirection(), false);

            // Teleport player
            Logger.sendMessage("[Portal Skies] Teleporting player to block...", false);
            player.teleportTo(newLevel, playerPos.x, playerPos.y, playerPos.z, player.getYRot(), player.getXRot());
            player.connection.resetPosition();

            Logger.sendMessage("[Portal Skies] Player actual position after teleport: " +
             String.format("%.2f, %.2f, %.2f", player.getX(), player.getY(), player.getZ()), false);

            // Small delay
            Logger.sendMessage("[Portal Skies] Waiting for client sync...", false);
            try { Thread.sleep(50); } catch (InterruptedException e) {}

            // Attempt interaction
            Logger.sendMessage("[Portal Skies] Attempting main hand interaction...", false);
            var interactionManager = player.gameMode;
            InteractionResult result = interactionManager.useItemOn(player, newLevel, player.getMainHandItem(),
                    InteractionHand.MAIN_HAND, hitResult);

              Logger.sendMessage("[Portal Skies] Main hand interaction result: " + result, false);
             Logger.sendMessage("[Portal Skies] Consumes action: " + result.consumesAction(), false);
            Logger.sendMessage("[Portal Skies] Should swing arm: " + result.shouldSwing(), false);
            Logger.sendMessage("[Portal Skies] Is success: " + (result == InteractionResult.SUCCESS), false);
            Logger.sendMessage("[Portal Skies] Is consumable: " + (result == InteractionResult.CONSUME), false);

            if (result.consumesAction()) {
                  Logger.sendMessage("✓ Main hand interaction successful - player should be mounted", false);
            } else {
                Logger.sendMessage("[Portal Skies] Attempting off-hand interaction...", false);
                result = interactionManager.useItemOn(player, newLevel, player.getOffhandItem(),
                        InteractionHand.OFF_HAND, hitResult);

                  Logger.sendMessage("[Portal Skies] Off-hand interaction result: " + result, false);

                if (result.consumesAction()) {
                    Logger.sendMessage("✓ Off-hand interaction successful", false);
                } else {
                    Logger.sendMessage("✗ Both interaction attempts failed", false);
                }
            }

            // Check if player is mounted after interaction
            Logger.sendMessage("[Portal Skies] Player mounting status after interaction:", false);
            Logger.sendMessage("[Portal Skies] - Is passenger: " + player.isPassenger(), false);
            Logger.sendMessage("[Portal Skies] - Has vehicle: " + (player.getVehicle() != null), false);
            if (player.getVehicle() != null) {
                  Logger.sendMessage("[Portal Skies] - Vehicle type: " + player.getVehicle().getType(), false);
            }

            //Logger.sendMessage("[Portal Skies] <<< End block interaction debug", false);

        } catch (Exception e) {
            Logger.sendMessage("ERROR during block interaction: " + e.getMessage(), false);
            e.printStackTrace();
        }
    }

    // Find ship for entity
    private ServerShip findShipForEntity(Entity entity) {
        var shipWorld = VSGameUtilsKt.getShipObjectWorld(newLevel);
        if (shipWorld == null) return null;

        for (Ship ship : shipWorld.getAllShips()) {
            if (ship instanceof ServerShip serverShip) {
                AABBdc shipAABB = ship.getWorldAABB();
                if (shipAABB != null && shipAABB.containsPoint(entity.getX(), entity.getY(), entity.getZ())) {
                    return serverShip;
                }
            }
        }
        return null;
    }

    // UPDATED: Force remount with better player tracking
    private void forceRemountEntities() {
        int remountedCount = 0;
        int playerRemounts = 0;
        int entityRemounts = 0;

        // Logger.sendMessage("[Portal Skies] === START REMOUNT DEBUG ===", false);
        Logger.sendMessage("[Portal Skies] Total mounting entries: " + entityMountingData.size(), false);

        for (Map.Entry<Entity, MountingData> entry : entityMountingData.entrySet()) {
            Entity oldPassenger = entry.getKey();
            MountingData mountData = entry.getValue();

            Entity newPassenger = oldToNewEntity.get(oldPassenger);
            Entity newVehicle = oldToNewEntity.get(mountData.vehicle());

              Logger.sendMessage("[Portal Skies] Processing mount: " +
                   oldPassenger.getType() + " -> " + mountData.vehicle().getType(), false);
             Logger.sendMessage("[Portal Skies] - New passenger found: " + (newPassenger != null), false);
            Logger.sendMessage("[Portal Skies] - New vehicle found: " + (newVehicle != null), false);
            Logger.sendMessage("[Portal Skies] - Already passenger: " + (newPassenger != null && newPassenger.isPassenger()), false);
            Logger.sendMessage("[Portal Skies] - Original vehicle was ShipMountingEntity: " + (mountData.vehicle() instanceof ShipMountingEntity), false);

            if (newPassenger != null && !newPassenger.isPassenger()) {
                // Check if the original vehicle was a ShipMountingEntity
                if (mountData.vehicle() instanceof ShipMountingEntity && newPassenger instanceof ServerPlayer) {
                        Logger.sendMessage("[Portal Skies] Original was ShipMountingEntity - finding helm block for player...", false);

                    // Find the ship that should contain the helm block
                    ServerShip ship = findShipForEntity(newPassenger);
                    if (ship != null) {
                              Logger.sendMessage("[Portal Skies] Found ship for player: " + ship.getId(), false);

                        // Convert the original mounting entity position to find the helm block
                        Vec3 originalMountPos = mountData.vehicle().position();
                        Vector3d shipyardPos = new Vector3d( originalMountPos.x, originalMountPos.y, originalMountPos.z );

                        BlockPos helmBlockPos = new BlockPos(
                                (int) Math.floor(shipyardPos.x),
                                (int) Math.floor(shipyardPos.y),
                                (int) Math.floor(shipyardPos.z)
                        );

                          Logger.sendMessage("[Portal Skies] Original mount world pos: " +
                                String.format("%.2f, %.2f, %.2f", originalMountPos.x, originalMountPos.y, originalMountPos.z), false);
                        Logger.sendMessage("[Portal Skies] Converted shipyard pos: " +
                              String.format("%.2f, %.2f, %.2f", shipyardPos.x, shipyardPos.y, shipyardPos.z), false);
                        Logger.sendMessage("[Portal Skies] Calculated helm block: " + helmBlockPos, false);

                        // Try to find the actual helm block by scanning nearby
                        BlockPos actualHelmBlock = findActualHelmBlock(ship, helmBlockPos);
                        if (actualHelmBlock != null) {
                              Logger.sendMessage("Found actual helm block at: " + actualHelmBlock, false);
                            forceBlockInteraction((ServerPlayer) newPassenger, actualHelmBlock);
                            playerRemounts++;
                        } else {
                                Logger.sendMessage("No helm block found, using calculated position", false);
                            forceBlockInteraction((ServerPlayer) newPassenger, helmBlockPos);
                            playerRemounts++;
                        }
                    } else {
                         Logger.sendMessage("Could not find ship for player", false);
                    }
                }
                // For regular entity mounts (non-ShipMountingEntity)
                else if (newVehicle != null) {
                    Logger.sendMessage("[Portal Skies] Attempting direct entity mount...", false);
                    boolean success = newPassenger.startRiding(newVehicle, true);
                    Logger.sendMessage("[Portal Skies] - Direct mount result: " + success, false);
                    if (success) {
                        if (newPassenger instanceof ServerPlayer) {
                            playerRemounts++;
                        } else {
                            entityRemounts++;
                        }
                    }
                }

                // Check if remount was successful
                if (newPassenger.isPassenger()) {
                    remountedCount++;
                    Logger.sendMessage("✓ Successfully remounted", false);
                    if (newPassenger.getVehicle() != null) {
                        Logger.sendMessage("[Portal Skies] Now riding: " + newPassenger.getVehicle().getType(), false);
                    }
                } else {
                    Logger.sendMessage("✗ Failed to remount", false);
                }
            } else {
                Logger.sendMessage("[Portal Skies] Skipping - passenger null or already mounted", false);
            }
            Logger.sendMessage("[Portal Skies] ---", false);
        }

        Logger.sendMessage("[Portal Skies] === END REMOUNT DEBUG ===", false);
        Logger.sendMessage("[Portal Skies] Remounted " + remountedCount + " entities total", false);
        Logger.sendMessage("[Portal Skies] - Players (helm interaction): " + playerRemounts, false);
        Logger.sendMessage("[Portal Skies] - Other entities: " + entityRemounts, false);
    }

    // NEW: Helper method to find the actual helm block by scanning nearby blocks
    private BlockPos findActualHelmBlock(ServerShip ship, BlockPos searchCenter) {
        int searchRadius = 5;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos checkPos = searchCenter.offset(x, y, z);
                    BlockState state = newLevel.getBlockState(checkPos);

                    if (isHelmBlock(state)) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }

    // NEW: Check if a block is a helm block
    private boolean isHelmBlock(BlockState state) {
        if (state.isAir()) return false;

        String blockName = state.getBlock().getDescriptionId().toLowerCase();
        return blockName.contains("helm") ||
                blockName.contains("wheel") ||
                blockName.contains("controller") ||
                blockName.contains("pilot") ||
                blockName.contains("ship_helm") ||
                blockName.contains("ship_pilot");
    }

    public void finalizeTeleport() {
        final int size = this.ships.size();
        if (size == 0) {
            Logger.sendMessage("No ships to teleport!", true);
            return;
        }

        ejectPassengersFromShipMountingEntities();

        Logger.sendMessage("[DEBUG] Step 2: Teleporting ships via VS API with collision forcing...", false);
        this.ships.forEach(this::handleShipTeleport);

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {}

        Logger.sendMessage("[DEBUG] Step 3: Teleporting entities with passengers...", false);
        this.teleportEntitiesRecursive();

        Logger.sendMessage("[DEBUG] Step 4: Forcing entity remounting...", false);
        this.forceRemountEntities();

        this.ships.clear();
        this.entityToPos.clear();
        this.oldToNewEntity.clear();
        this.entityMountingData.clear();

        Logger.sendMessage("[Portal Skies] DEBUG: teleportShipWithFallback completed", false);
    }

    private void ejectPassengersFromShipMountingEntities() {
        for (Map.Entry<Entity, MountingData> entry : entityMountingData.entrySet()) {
            Entity passenger = entry.getKey();
            MountingData mountData = entry.getValue();

            if (mountData.vehicle() instanceof ShipMountingEntity) {
                Logger.sendMessage("[Portal Skies] Ejecting " + passenger.getType() + " from ShipMountingEntity", false);
                passenger.stopRiding();
            }
        }
    }

    private void teleportEntitiesRecursive() {
        Map<Entity, Vec3> entitiesToTeleport = new HashMap<>(this.entityToPos);
        Map<ServerPlayer, Vec3> playersToTeleort = new HashMap<>();
        for (Map.Entry<Entity,Vec3> entry:new HashMap <Entity,Vec3>(entitiesToTeleport).entrySet()) {
            Entity entity = entry.getKey();
            Vec3 vec = entry.getValue();
            if (entity instanceof ServerPlayer) {
                playersToTeleort.put((ServerPlayer) entity,vec);
                entitiesToTeleport.remove(entity);
                this.oldToNewEntity.put(entity, entity);
            }
            if(entity instanceof ShipMountingEntity){
                entitiesToTeleport.remove(entity);
                 entity.kill();



            }
        }
        Logger.sendMessage("Players in players to teleport= "+playersToTeleort.size(), false);

        for (Map.Entry<Entity, Vec3> entry : entitiesToTeleport.entrySet()) {
            Entity entity = entry.getKey();
            Vec3 newPos = entry.getValue();

            if (entity.isRemoved()) {
                continue;
            }

            Entity teleportedEntity;
              if (isRideableEntity(entity)) {
                teleportedEntity = teleportRideableEntityWithReset(entity, newLevel, newPos);
            } else {
                teleportedEntity = teleportToWithPassengers(entity, newLevel, newPos);
            }

            if (teleportedEntity != null) {
                this.oldToNewEntity.put(entity, teleportedEntity);
            }
        }
            teleportPlayerWithDoubleTeleport(playersToTeleort,newLevel);

    }

    private Entity teleportRideableEntityWithReset(final Entity entity, final ServerLevel newLevel, final Vec3 newPos) {
        Entity newEntity = entity.getType().create(newLevel);
        if (newEntity == null) {
            return null;
        }

        newEntity.restoreFrom(entity);
        newEntity.moveTo(newPos.x, newPos.y+1, newPos.z, entity.getYRot(), entity.getXRot());
        newEntity.setYHeadRot(entity.getYHeadRot());
        newEntity.setYBodyRot(entity.getVisualRotationYInDegrees());

        resetEntityStateForUsability(newEntity);
        newLevel.addDuringTeleport(newEntity);
        entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);

        return newEntity;
    }

    private static Entity teleportToWithPassengers(final Entity entity, final ServerLevel newLevel, final Vec3 newPos) {
        final Vec3 oldPos = entity.position();
        final List<Entity> passengers = new ArrayList<>(entity.getPassengers());

        final Entity newEntity;
        if (entity instanceof ServerPlayer player) {
            player.teleportTo(newLevel, newPos.x, newPos.y+1, newPos.z, player.getYRot(), player.getXRot());
            newEntity = player;
        } else {
            newEntity = entity.getType().create(newLevel);
            if (newEntity == null) {
                return null;
            }
            entity.ejectPassengers();
            newEntity.restoreFrom(entity);
            newEntity.moveTo(newPos.x, newPos.y, newPos.z, entity.getYRot(), entity.getXRot());
            newEntity.setYHeadRot(entity.getYHeadRot());
            newEntity.setYBodyRot(entity.getVisualRotationYInDegrees());
            newLevel.addDuringTeleport(newEntity);
            entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        }

        for (final Entity passenger : passengers) {
            final Entity newPassenger = teleportToWithPassengers(passenger, newLevel, passenger.position().subtract(oldPos).add(newPos));
            if (newPassenger != null) {
                newPassenger.startRiding(newEntity, true);
            }
        }

        return newEntity;
    }

    private void resetEntityStateForUsability(Entity entity) {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.hurtMarked = true;
        entity.setInvulnerable(false);
        entity.setPos(entity.getX(), entity.getY(), entity.getZ());
        entity.resetFallDistance();
        entity.clearFire();

        if (entity.getPassengers().isEmpty()) {
            try {
                entity.setOnGround(true);
            } catch (Exception e) {}
        }
    }

    private boolean isRideableEntity(Entity entity) {
        if (!entity.getPassengers().isEmpty()) {
            return true;
        }

        String entityType = entity.getType().toString().toLowerCase();
        return entityType.contains("boat") ||
                entityType.contains("minecart") ||
                entityType.contains("horse") ||
                entityType.contains("pig") ||
                entityType.contains("strider") ||
                entityType.contains("camel") ||
                entityType.contains("llama") ||
                entityType.contains("donkey") ||
                entityType.contains("mule") ;
    }

    private void teleportPlayerWithDoubleTeleport(final Map<ServerPlayer,Vec3> players, final ServerLevel newLevel) {

        for (Map.Entry<ServerPlayer,Vec3> entry:players.entrySet()){
            ServerPlayer player = entry.getKey();
            Vec3 newPos = entry.getValue();


        player.teleportTo(newLevel, newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
        player.connection.resetPosition();
        player.setPos(newPos.x, newPos.y + 1, newPos.z);
        player.connection.teleport(newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
    }




        try { Thread.sleep(300); } catch (InterruptedException e) {}

        for (Map.Entry<ServerPlayer,Vec3> entry:players.entrySet()){
            ServerPlayer player = entry.getKey();
            Vec3 newPos = entry.getValue();


            player.teleportTo(newLevel, newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
            player.connection.resetPosition();
            player.setPos(newPos.x, newPos.y + 1, newPos.z);
            player.connection.teleport(newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
        }

        try { Thread.sleep(300); } catch (InterruptedException e) {}
        for (Map.Entry<ServerPlayer,Vec3> entry:players.entrySet()){
            ServerPlayer player = entry.getKey();
            Vec3 newPos = entry.getValue();


            player.teleportTo(newLevel, newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
            player.connection.resetPosition();
            player.setPos(newPos.x, newPos.y + 1, newPos.z);
            player.connection.teleport(newPos.x, newPos.y + 1, newPos.z, player.getYRot(), player.getXRot());
        }

    }

    private void handleShipTeleport(final long id, final TeleportData data) {
        final String dimensionId = VSGameUtilsKt.getDimensionId(this.newLevel);
        final Vector3d newPos = data.newPos();
        final Quaterniond rotation = data.rotation();
        final Vector3d velocity = data.velocity();
        final Vector3d omega = data.omega();

        var targetShipWorld = VSGameUtilsKt.getShipObjectWorld(newLevel);
        if (targetShipWorld == null) {
            return;
        }

        final LoadedServerShip ship = targetShipWorld.getLoadedShips().getById(id);
        if (ship != null) {
            ship.setStatic(false);
            final ShipTeleportData teleportData = new ShipTeleportDataImpl(newPos, rotation, velocity, omega, dimensionId, null);
            targetShipWorld.teleportShip(ship, teleportData);

            try { Thread.sleep(100); } catch (InterruptedException e) {}
        }
    }

    private ServerShip getShip(final long shipId) {
        var shipWorld = VSGameUtilsKt.getShipObjectWorld(oldLevel);
        if (shipWorld == null) return null;

        final LoadedServerShip loadedShip = shipWorld.getLoadedShips().getById(shipId);
        if (loadedShip != null) {
            return loadedShip;
        }

        return (ServerShip) shipWorld.getAllShips().getById(shipId);
    }

    private boolean shouldTeleportEntity(Entity entity) {
        return entity instanceof ServerPlayer ||
                entity.isAlive() ||
                entity.getType().toString().contains("item_frame") ||
                entity.getType().toString().contains("painting") ||
                entity.getType().toString().contains("boat") ||
                entity.getType().toString().contains("minecart");
    }
}