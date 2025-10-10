package Portal.code;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.apigame.ShipTeleportData;
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShipTeleportationUtils {

    public record BlockSnapshot(BlockPos pos, BlockState state) {}
    private record TeleportData(Vector3d newPos, Quaterniond rotation, Vector3d velocity, Vector3d omega) {}

    private static final double ENTITY_COLLECT_RANGE = 5.0;
    private static final double SHIP_COLLECT_RANGE = 2.0;

    private final Map<Long, TeleportData> ships = new ConcurrentHashMap<>();
    private final Map<Entity, Vec3> entityToPos = new ConcurrentHashMap<>();
    private final Map<Entity, List<Entity>> entityPassengers = new ConcurrentHashMap<>(); // NEW: Store passengers at collection time
    private ServerLevel oldLevel;
    private ServerLevel newLevel;

    public void reset(final ServerLevel oldLevel, final ServerLevel newLevel) {
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
        this.ships.clear();
        this.entityToPos.clear();
        this.entityPassengers.clear();
    }

    public static void teleportShipWithFallback(Ship ship, ServerLevel currentWorld, ServerLevel targetWorld, Vector3d exactTargetPos) {
        ShipTeleportationUtils handler = new ShipTeleportationUtils();
        handler.reset(currentWorld, targetWorld);

        var currentPos = ship.getTransform().getPositionInWorld();

        sendMessageToAllPlayers(currentWorld, "§6[Portal Skies] §eTeleporting ship from " +
                String.format("%.2f, %.2f, %.2f", currentPos.x(), currentPos.y(), currentPos.z()) +
                " to exact position " +
                String.format("%.2f, %.2f, %.2f", exactTargetPos.x, exactTargetPos.y, exactTargetPos.z));

        // Nudge upwards to avoid ground collision
        double nudgeY = 0.1;
        Vector3d targetPosition = new Vector3d(exactTargetPos.x, exactTargetPos.y + nudgeY, exactTargetPos.z);

        // Get ship's current rotation and velocity
        Quaterniond rotation = new Quaterniond(ship.getTransform().getShipToWorldRotation());
        Vector3d velocity = new Vector3d(ship.getVelocity());
        Vector3d omega = new Vector3d(ship.getOmega());

        sendMessageToAllPlayers(currentWorld, "§aStarting ship teleport from " + currentWorld.dimension().location() +
                " to " + targetWorld.dimension().location());

        // Add the main ship
        handler.addShip((ServerShip) ship, targetPosition, rotation, velocity, omega);

        // Execute the teleportation
        handler.finalizeTeleport();
    }

    public static void teleportShipWithFallback(Ship ship, ServerLevel currentWorld, ServerLevel targetWorld, BlockPos targetPortalPos) {
        Vector3d exactPos = new Vector3d(
                targetPortalPos.getX() + 0.5,
                targetPortalPos.getY() + 0.5,
                targetPortalPos.getZ() + 0.5
        );
        teleportShipWithFallback(ship, currentWorld, targetWorld, exactPos);
    }

    public void addShip(final ServerShip ship, final Vector3d newPos, final Quaterniond rotation, final Vector3d velocity, final Vector3d omega) {
        final long shipId = ship.getId();
        if (this.ships.containsKey(shipId)) {
            return;
        }

        final List<ServerShip> collected = new ArrayList<>();
        final Vector3d origin = (Vector3d) ship.getTransform().getPositionInWorld();

        this.collectShipAndConnected(shipId, origin, newPos, rotation, velocity, omega, collected);
        this.collectNearbyShips(collected, origin, newPos, rotation);
        this.collectNearbyEntities(collected, origin, newPos, rotation);
    }

    private void collectShipAndConnected(
            final long shipId,
            final Vector3d origin,
            final Vector3d newPos,
            final Quaterniond rotation,
            final Vector3d velocity,
            final Vector3d omega,
            final List<ServerShip> collected
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
        final Quaterniond newRotation = new Quaterniond(ship.getTransform().getShipToWorldRotation());

        // Calculate relative position
        final Vector3d relPos = pos.sub(origin, new Vector3d());
        rotation.transform(relPos);
        relPos.add(newPos);

        // Transform velocity
        final Vector3d newVelocity = rotation.transform(velocity, new Vector3d());

        this.ships.put(
                shipId,
                new TeleportData(
                        relPos,
                        newRotation,
                        newVelocity,
                        omega
                )
        );

        sendMessageToAllPlayers(oldLevel, "§aPreparing to teleport ship ID: " + shipId + " to " +
                String.format("%.2f, %.2f, %.2f", relPos.x, relPos.y, relPos.z));
    }

    private void collectNearbyShips(final List<ServerShip> collected, final Vector3d origin, final Vector3d newPos, final Quaterniond rotation) {
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
                        sendMessageToAllPlayers(oldLevel, "§aFound nearby ship ID: " + nearbyShip.getId() + ", adding to teleport");
                        this.collectShipAndConnected(serverShip.getId(), origin, newPos, rotation,
                                new Vector3d(nearbyShip.getVelocity()),
                                new Vector3d(nearbyShip.getOmega()),
                                collected);
                    }
                }
            }
        }
    }

    private void collectNearbyEntities(final List<ServerShip> collected, final Vector3d origin, final Vector3d newPos, final Quaterniond rotation) {
        for (final ServerShip ship : collected) {
            this.collectEntities(ship, origin, newPos, rotation);
        }
    }

    private void collectEntities(final ServerShip ship, final Vector3d origin, final Vector3d newPos, final Quaterniond rotation) {
        final AABBd shipBox = new AABBd(ship.getWorldAABB());

        final AABB collectionBox = new AABB(
                shipBox.minX - ENTITY_COLLECT_RANGE, shipBox.minY - ENTITY_COLLECT_RANGE, shipBox.minZ - ENTITY_COLLECT_RANGE,
                shipBox.maxX + ENTITY_COLLECT_RANGE, shipBox.maxY + ENTITY_COLLECT_RANGE, shipBox.maxZ + ENTITY_COLLECT_RANGE
        );

        List<Entity> foundEntities = this.oldLevel.getEntities((Entity) null, collectionBox,
                (entity) -> !this.entityToPos.containsKey(entity) && shouldTeleportEntity(entity));

        sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eFound " + foundEntities.size() + " entities in collection box");

        for (final Entity entity : foundEntities) {
            // Only collect ROOT vehicles, not passengers
            Entity rootVehicle = entity.getRootVehicle();

            sendMessageToAllPlayers(oldLevel, "IDEBUG1 Entity: " + entity.getName().getString() +
                    " at " + String.format("%.2f, %.2f, %.2f", entity.getX(), entity.getY(), entity.getZ()) +
                    " mounted: " + (entity.getVehicle() != null) +
                    " root: " + rootVehicle.getName().getString() +
                    " passengers: " + entity.getPassengers().size());

            // Only add the root vehicle to avoid duplicate teleportation
            if (entity == rootVehicle || !this.entityToPos.containsKey(rootVehicle)) {
                this.collectEntityDirectly(rootVehicle, origin, newPos, rotation);
            } else {
                sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §cSkipping " + entity.getName().getString() +
                        " - root vehicle already collected: " + rootVehicle.getName().getString());
            }
        }
    }
    private void collectEntityDirectly(final Entity entity, final Vector3d origin, final Vector3d newPos, final Quaterniond rotation) {
        Vec3 pos = entity.position();

        sendMessageToAllPlayers(oldLevel, "IDEBUG1 Original position: " +
                String.format("%.2f, %.2f, %.2f", pos.x, pos.y, pos.z));

        sendMessageToAllPlayers(oldLevel, "IDEBUG1 Origin (ship transform): " +
                String.format("%.2f, %.2f, %.2f", origin.x, origin.y, origin.z));
        sendMessageToAllPlayers(oldLevel, "IDEBUG1 NewPos (target): " +
                String.format("%.2f, %.2f, %.2f", newPos.x, newPos.y, newPos.z));

        // Calculate position relative to ship transform
        final Vector3d relPos = new Vector3d(pos.x, pos.y, pos.z).sub(origin);

        sendMessageToAllPlayers(oldLevel, "IDEBUG1 Relative position: " +
                String.format("%.2f, %.2f, %.2f", relPos.x, relPos.y, relPos.z));

        rotation.transform(relPos);
        relPos.add(newPos);
        pos = new Vec3(relPos.x, relPos.y, relPos.z);

        sendMessageToAllPlayers(oldLevel, "IDEBUG1 New position: " +
                String.format("%.2f, %.2f, %.2f", pos.x, pos.y, pos.z));

        // Rest of the method...
    }

    public void finalizeTeleport() {
        final int size = this.ships.size();
        if (size == 0) {
            sendMessageToAllPlayers(oldLevel, "§cNo ships to teleport!");
            return;
        }

        sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §e=== STARTING TELEPORT ===");
        sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eShips to teleport: " + this.ships.size());
        sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eEntities to teleport: " + this.entityToPos.size());

        // List all entities that will be teleported
        for (Entity entity : this.entityToPos.keySet()) {
            List<Entity> passengers = this.entityPassengers.getOrDefault(entity, new ArrayList<>());
            sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eWill teleport: " + entity.getName().getString() +
                    " (Player: " + (entity instanceof ServerPlayer) + ") with " + passengers.size() + " passengers");
        }

        // STEP 1: Teleport ships via VS API
        sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eTeleporting ships via VS API...");
        this.ships.forEach(this::handleShipTeleport);

        // STEP 2: Wait a moment for ships to be positioned
        try {
            Thread.sleep(50); // Small delay to ensure ships are positioned
        } catch (InterruptedException e) {}

        // STEP 3: Teleport entities recursively with stored passenger data
        sendMessageToAllPlayers(oldLevel, "=== STARTING ENTITY TELEPORTATION ===");
        sendMessageToAllPlayers(oldLevel, "Entities to teleport: " + entityToPos.size());

        this.teleportEntitiesRecursively();

        this.ships.clear();
        this.entityToPos.clear();
        this.entityPassengers.clear();
    }

    /**
     * Recursive entity teleportation that uses stored passenger data
     */
    private void teleportEntitiesRecursively() {
        int successCount = 0;
        int failCount = 0;

        // Create a copy to avoid concurrent modification
        Set<Entity> entitiesToTeleport = new HashSet<>(this.entityToPos.keySet());

        for (Entity entity : entitiesToTeleport) {
            if (this.entityToPos.containsKey(entity)) {
                Vec3 newPos = this.entityToPos.get(entity);
                List<Entity> passengers = this.entityPassengers.getOrDefault(entity, new ArrayList<>());

                sendMessageToAllPlayers(oldLevel, "§6[DEBUG] §eTeleporting entity: " + entity.getName().getString() +
                        " with " + passengers.size() + " passengers (using stored data)");

                Entity result = teleportToWithStoredPassengers(entity, this.newLevel, newPos, passengers);
                if (result != null) {
                    successCount++;
                    // Remove from map to avoid double teleportation
                    this.entityToPos.remove(entity);
                    sendMessageToAllPlayers(newLevel, "§aSuccessfully teleported: " + entity.getName().getString());
                } else {
                    failCount++;
                    sendMessageToAllPlayers(newLevel, "§cFailed to teleport: " + entity.getName().getString());
                }
            }
        }

        sendMessageToAllPlayers(newLevel, "=== ENTITY TELEPORTATION COMPLETE ===");
        sendMessageToAllPlayers(newLevel, "Success: " + successCount + " | Failed: " + failCount);
    }

    /**
     * Recursive teleportation that uses stored passenger relationships
     */
    private static <T extends Entity> T teleportToWithStoredPassengers(final T entity, final ServerLevel newLevel, final Vec3 newPos, final List<Entity> storedPassengers) {
        try {
            sendMessageToAllPlayers(newLevel, "§6[DEBUG] §e=== STARTING RECURSIVE TELEPORT ===");
            sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eEntity: " + entity.getName().getString());
            sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eStored passengers: " + storedPassengers.size());

            final Vec3 oldPos = entity.position();

            // Teleport the main entity first
            final T newEntity;
            if (entity instanceof ServerPlayer player) {
                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eTeleporting PLAYER entity");
                player.teleportTo(newLevel, newPos.x, newPos.y, newPos.z, player.getYRot(), player.getXRot());
                newEntity = entity;
                sendMessageToAllPlayers(newLevel, "§aPlayer teleported successfully");
            } else {
                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eCreating NEW entity instance");
                newEntity = (T) entity.getType().create(newLevel);
                if (newEntity == null) {
                    sendMessageToAllPlayers(newLevel, "§cFAILED to create new entity");
                    return null;
                }

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eRestoring entity data");
                newEntity.restoreFrom(entity);

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eMoving entity to new position");
                newEntity.moveTo(newPos.x, newPos.y, newPos.z, entity.getYRot(), entity.getXRot());
                newEntity.setYHeadRot(entity.getYHeadRot());
                newEntity.setYBodyRot(entity.getVisualRotationYInDegrees());

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eAdding entity to level");
                newLevel.addDuringTeleport(newEntity);

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eRemoving old entity");
                entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);

                sendMessageToAllPlayers(newLevel, "§aEntity recreated successfully: " + newEntity.getName().getString());
            }

            // Recursively teleport all stored passengers and remount them
            sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eProcessing " + storedPassengers.size() + " stored passengers");
            for (final Entity passenger : storedPassengers) {
                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §e=== PROCESSING STORED PASSENGER ===");
                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §ePassenger: " + passenger.getName().getString());
                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eVehicle: " + newEntity.getName().getString());

                // Calculate passenger's new position relative to the vehicle
                Vec3 passengerOldPos = passenger.position();
                Vec3 relativeOffset = passengerOldPos.subtract(oldPos);
                Vec3 passengerNewPos = newPos.add(relativeOffset);

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §ePassenger offset: " + String.format("%.2f, %.2f, %.2f", relativeOffset.x, relativeOffset.y, relativeOffset.z));

                // Get stored passengers for this passenger (for nested mounts)
                List<Entity> passengerPassengers = new ArrayList<>(passenger.getPassengers());

                sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eRecursively teleporting passenger");
                final Entity newPassenger = teleportToWithStoredPassengers(passenger, newLevel, passengerNewPos, passengerPassengers);

                if (newPassenger != null) {
                    sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eAttempting to mount passenger to vehicle");

                    boolean mountSuccess = newPassenger.startRiding(newEntity, true);

                    if (mountSuccess) {
                        sendMessageToAllPlayers(newLevel, "§aSUCCESS: Mounted " + newPassenger.getName().getString() + " to " + newEntity.getName().getString());
                    } else {
                        sendMessageToAllPlayers(newLevel, "§cFAILED: Could not mount " + newPassenger.getName().getString() + " to " + newEntity.getName().getString());
                        sendMessageToAllPlayers(newLevel, "§6[DEBUG] §eKeeping passenger at relative position");
                        newPassenger.teleportTo(passengerNewPos.x, passengerNewPos.y, passengerNewPos.z);
                    }
                } else {
                    sendMessageToAllPlayers(newLevel, "§cFAILED: Could not teleport passenger: " + passenger.getName().getString());
                }
            }

            sendMessageToAllPlayers(newLevel, "§6[DEBUG] §e=== RECURSIVE TELEPORT COMPLETE ===");
            return newEntity;
        } catch (Exception e) {
            sendMessageToAllPlayers(newLevel, "§cERROR in teleportToWithStoredPassengers: " + e.getMessage());
            e.printStackTrace();
            return null;
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
            sendMessageToAllPlayers(newLevel, "§cTarget ShipWorld is null");
            return;
        }

        final Ship ship = targetShipWorld.getAllShips().getById(id);
        if (ship instanceof ServerShip serverShip) {
            sendMessageToAllPlayers(newLevel, "IDEBUG1 Mill teleport: entity.valkurienskies.ship_mounting_entity ID: " + serverShip.getId());

            final ShipTeleportData teleportData = new ShipTeleportDataImpl(newPos, rotation, velocity, omega, dimensionId, null);
            targetShipWorld.teleportShip(serverShip, teleportData);

            sendMessageToAllPlayers(newLevel, "§aShip teleported successfully: ID " + serverShip.getId());
        }
    }

    private ServerShip getShip(final long shipId) {
        var shipWorld = VSGameUtilsKt.getShipObjectWorld(oldLevel);
        if (shipWorld == null) return null;

        final ServerShip ship = shipWorld.getLoadedShips().getById(shipId);
        if (ship != null) {
            return ship;
        }
        return (ServerShip) shipWorld.getAllShips().getById(shipId);
    }

    private boolean shouldTeleportEntity(Entity entity) {
        return entity instanceof ServerPlayer ||
                entity.isAlive() ||
                entity.getType().toString().contains("item_frame") ||
                entity.getType().toString().contains("painting");
    }

    private static double getVerticalOffset(Entity entity) {
        if (entity instanceof ServerPlayer) {
            return 2.5;
        } else if (entity.getBbHeight() > 0.0) {
            return entity.getBbHeight() + 0.5;
        } else {
            return 0.5;
        }
    }

    private static void sendMessageToAllPlayers(ServerLevel world, String message) {
        if (world != null) {
            for (ServerPlayer player : world.players()) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    private static void sendMessageToPlayer(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }
}