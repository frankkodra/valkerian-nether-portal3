package Portal.code;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.ships.Ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.lang.reflect.Field;
import java.util.*;

@Mod("portalskies")
public class netherPortalShipListener {

    private final Map<Long, Integer> portalCooldownMap = new HashMap<>();
    private static final int PORTAL_COOLDOWN_TICKS = 100; // 5 seconds at 20 TPS
    private static final double PORTAL_THRESHOLD_PERCENTAGE = 0.30; // 30% minimum

    public netherPortalShipListener() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // Check all loaded worlds
        for (ServerLevel world : server.getAllLevels()) {
            var shipWorld = VSGameUtilsKt.getShipObjectWorld(world);
            if (shipWorld == null) continue;

            // FIX: Create a copy of the ship collection to avoid ConcurrentModificationException
            List<Ship> shipsCopy = new ArrayList<>();
            for (Ship ship : shipWorld.getLoadedShips()) {
                shipsCopy.add(ship);
            }

            // Now iterate over the copy instead of the original collection
            for (Ship ship : shipsCopy) {
                long shipId = ship.getId();

                // Handle cooldown to prevent rapid teleportation loops
                int cooldown = portalCooldownMap.getOrDefault(shipId, 0);
                if (cooldown > 0) {
                    portalCooldownMap.put(shipId, cooldown - 1);
                    continue;
                }

                // Check if ship meets the 30% portal threshold
                PortalCheckResult portalResult = isShipInPortalWithThreshold(ship, world);
                if (portalResult.isInPortal) {
                    // Determine target world and scale
                    double scale = 1.0;
                    ServerLevel targetWorld = null;

                    var currentDim = world.dimension().location().toString();

                    if (currentDim.equals("minecraft:the_nether")) {
                        targetWorld = server.getLevel(Level.OVERWORLD);
                        scale = 8.0;
                        sendMessageToAllPlayers(world, "§6[Portal Skies] §aShip detected in Nether portal - preparing to teleport to Overworld!");
                    } else if (currentDim.equals("minecraft:overworld")) {
                        targetWorld = server.getLevel(Level.NETHER);
                        scale = 0.125;
                        sendMessageToAllPlayers(world, "§6[Portal Skies] §aShip detected in Nether portal - preparing to teleport to Nether!");
                    } else {
                        continue; // Skip unsupported dimensions
                    }

                    if (targetWorld != null) {
                        try {
                            // Find portal on the other side and check if it's big enough
                            PortalInfo targetPortalInfo = findAndValidatePortal(portalResult.portalCenter, world, targetWorld, scale, ship);

                            if (targetPortalInfo != null && targetPortalInfo.isValid) {
                                // Calculate ship approach direction and final position
                                TeleportPositionResult positionResult = calculateTeleportPosition(targetPortalInfo, ship, portalResult.portalCenter, world, targetWorld);

                                if (positionResult.hasEnoughSpace) {
                                    sendMessageToAllPlayers(world, "§6[Portal Skies] §aTeleporting ship " + shipId + " from " + getDimensionDisplayName(currentDim) +
                                            " to " + getDimensionDisplayName(targetWorld.dimension().location().toString()));

                                    sendMessageToAllPlayers(world, "§6[Portal Skies] §eTeleporting to exact position: " +
                                            String.format("%.2f, %.2f, %.2f",
                                                    positionResult.exactTeleportPos.x,
                                                    positionResult.exactTeleportPos.y,
                                                    positionResult.exactTeleportPos.z) +
                                            " (facing: " + positionResult.exitDirection + ")");

                                    // Use ShipTeleportationUtils with EXACT double precision coordinates
                                    ShipTeleportationUtils.teleportShipWithFallback(ship, world, targetWorld, positionResult.exactTeleportPos);
                                    portalCooldownMap.put(shipId, PORTAL_COOLDOWN_TICKS);
                                } else {
                                    sendMessageToAllPlayers(world, "§6[Portal Skies] §cNot enough space on other side of portal for ship " + shipId);
                                }
                            } else {
                                if (targetPortalInfo == null) {
                                    sendMessageToAllPlayers(world, "§6[Portal Skies] §cNo suitable portal found for ship " + shipId);
                                } else {
                                    sendMessageToAllPlayers(world, "§6[Portal Skies] §cTarget portal too small for ship " + shipId +
                                            " (needs " + targetPortalInfo.requiredWidth + "x" + targetPortalInfo.requiredHeight + ", has " +
                                            targetPortalInfo.actualWidth + "x" + targetPortalInfo.actualHeight + ")");
                                }
                            }
                        } catch (Exception e) {
                            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError during portal processing: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }
    /**
     * Calculate the final teleport position based on ship direction and portal orientation
     */
    private TeleportPositionResult calculateTeleportPosition(PortalInfo portalInfo, Ship ship, BlockPos sourcePortalPos, ServerLevel sourceWorld, ServerLevel targetWorld) {
        TeleportPositionResult result = new TeleportPositionResult();

        try {
            // Get ship dimensions
            var shipAABB = ship.getWorldAABB();
            if (shipAABB == null) {
                result.hasEnoughSpace = false;
                return result;
            }

            double shipWidth = shipAABB.maxX() - shipAABB.minX();
            double shipHeight = shipAABB.maxY() - shipAABB.minY();
            double shipLength = shipAABB.maxZ() - shipAABB.minZ();
            double shipLongestDimension = Math.max(shipWidth, shipLength);

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eShip dimensions: " +
                    String.format("%.1fx%.1fx%.1f", shipWidth, shipHeight, shipLength) +
                    " (longest: " + shipLongestDimension + ")");

            // Calculate ship's approach direction relative to source portal
            Direction approachDirection = calculateShipApproachDirection(ship, sourcePortalPos, portalInfo.axis);
            Direction exitDirection = approachDirection.getOpposite();

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eShip approaching from: " + approachDirection +
                    ", will exit to: " + exitDirection);

            // Calculate EXACT portal center position using measured bounds
            Vector3d exactPortalCenter = calculateExactPortalCenter(portalInfo, sourceWorld);

            // Calculate ship exit position based on exact portal center
            Vector3d exactExitPosition = calculateExactExitPosition(exactPortalCenter, exitDirection, ship, portalInfo);

            // Convert to block position for backward compatibility
            BlockPos exitPosition = new BlockPos(
                    (int) Math.floor(exactExitPosition.x),
                    (int) Math.floor(exactExitPosition.y),
                    (int) Math.floor(exactExitPosition.z)
            );

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eExact exit position: " +
                    String.format("%.2f, %.2f, %.2f", exactExitPosition.x, exactExitPosition.y, exactExitPosition.z));

            // Check if there's enough space at the destination
            result.hasEnoughSpace = checkDestinationSpace(exactExitPosition, ship, exitDirection, sourceWorld, targetWorld);
            result.teleportPos = exitPosition;
            result.exactTeleportPos = exactExitPosition;
            result.exitDirection = exitDirection;

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eFinal position calculation:");
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Portal center: " +
                    String.format("%.2f, %.2f, %.2f", exactPortalCenter.x, exactPortalCenter.y, exactPortalCenter.z));
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Exit direction: " + exitDirection);
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Final block position: " + exitPosition);
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Exact final position: " +
                    String.format("%.2f, %.2f, %.2f", exactExitPosition.x, exactExitPosition.y, exactExitPosition.z));
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Enough space: " + result.hasEnoughSpace);

        } catch (Exception e) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cError calculating teleport position: " + e.getMessage());
            result.hasEnoughSpace = false;
        }

        return result;
    }

    /**
     * Calculate the EXACT center of the portal using measured bounds in world coordinates
     */
    private Vector3d calculateExactPortalCenter(PortalInfo portalInfo, ServerLevel world) {
        try {
            // Use the measured bounds to calculate true geometric center in WORLD coordinates
            // World coordinates = block position + 0.5 for center alignment
            double centerX, centerY, centerZ;

            if (portalInfo.axis == Direction.Axis.X) {
                // X-axis portal: width runs along X axis, height along Y axis
                centerX = portalInfo.minX + (portalInfo.actualWidth / 2.0);
                centerY = portalInfo.minY + (portalInfo.actualHeight / 2.0);
                centerZ = portalInfo.portalCenter.getZ() + 0.5;
            } else {
                // Z-axis portal: width runs along Z axis, height along Y axis
                centerX = portalInfo.portalCenter.getX() + 0.5;
                centerY = portalInfo.minY + (portalInfo.actualHeight / 2.0);
                centerZ = portalInfo.minZ + (portalInfo.actualWidth / 2.0);
            }

            sendMessageToAllPlayers(world, "§6[Portal Skies] §ePortal bounds - X:" + portalInfo.minX + "-" + portalInfo.maxX +
                    " Y:" + portalInfo.minY + "-" + portalInfo.maxY + " Z:" + portalInfo.minZ + "-" + portalInfo.maxZ);
            sendMessageToAllPlayers(world, "§6[Portal Skies] §ePortal dimensions: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight);
            sendMessageToAllPlayers(world, "§6[Portal Skies] §eWorld coordinate center: " +
                    String.format("%.2f, %.2f, %.2f", centerX, centerY, centerZ));

            return new Vector3d(centerX, centerY, centerZ);

        } catch (Exception e) {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError calculating exact portal center: " + e.getMessage());
            // Fallback
            return new Vector3d(
                    portalInfo.portalCenter.getX() + 0.5,
                    portalInfo.portalCenter.getY() + 0.5,
                    portalInfo.portalCenter.getZ() + 0.5
            );
        }
    }
    /**
     * Calculate exact exit position based on portal center, ship dimensions, and exit direction
     */
    private Vector3d calculateExactExitPosition(Vector3d portalCenter, Direction exitDirection, Ship ship, PortalInfo portalInfo) {
        var shipAABB = ship.getWorldAABB();
        double shipWidth = shipAABB.maxX() - shipAABB.minX();
        double shipLength = shipAABB.maxZ() - shipAABB.minZ();

        // Determine which ship dimension is relevant for clearance
        double shipClearanceDimension;
        if (portalInfo.axis == Direction.Axis.X) {
            // X-axis portal: ship width needs to clear
            shipClearanceDimension = shipWidth;
        } else {
            // Z-axis portal: ship length needs to clear
            shipClearanceDimension = shipLength;
        }

        // Calculate offset: half ship dimension + sufficient clearance to prevent re-triggering
        double offsetDistance = (shipClearanceDimension / 2.0) + Math.max(2.5, shipClearanceDimension * 0.3);

        Vector3d exitPosition = new Vector3d(portalCenter);

        // Apply offset in exit direction
        switch (exitDirection) {
            case NORTH:
                exitPosition.z -= offsetDistance;
                break;
            case SOUTH:
                exitPosition.z += offsetDistance;
                break;
            case EAST:
                exitPosition.x += offsetDistance;
                break;
            case WEST:
                exitPosition.x -= offsetDistance;
                break;
            default:
                // Default to south if unknown
                exitPosition.z += offsetDistance;
        }

        return exitPosition;
    }

    /**
     * Calculate which direction the ship is approaching the portal from
     */
    private Direction calculateShipApproachDirection(Ship ship, BlockPos sourcePortalPos, Direction.Axis portalAxis) {
        Vector3d shipPos = (Vector3d) ship.getTransform().getPositionInWorld();
        Vector3d portalPos = new Vector3d(sourcePortalPos.getX() + 0.5, sourcePortalPos.getY(), sourcePortalPos.getZ() + 0.5);

        // Calculate vector from portal to ship
        Vector3d approachVector = shipPos.sub(portalPos, new Vector3d());

        // Determine approach direction based on portal orientation
        if (portalAxis == Direction.Axis.X) {
            // X-axis portal: faces North/South
            return approachVector.z() > 0 ? Direction.SOUTH : Direction.NORTH;
        } else {
            // Z-axis portal: faces East/West
            return approachVector.x() > 0 ? Direction.EAST : Direction.WEST;
        }
    }

    /**
     * Check if there's enough space at the destination for the ship using collision detection
     */
    /**
     * Check if there's enough space at the destination for the ship using the ship's local AABB
     */
    /**
     * Check if there's enough space at the destination for the ship using the ship's local AABB
     */
    private boolean checkDestinationSpace(Vector3d exactPosition, Ship ship, Direction exitDirection, ServerLevel sourceWorld, ServerLevel targetWorld) {
        try {
            var shipAABB = ship.getShipAABB();
            if (shipAABB == null) {
                sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cCould not get ship's local AABB");
                return false;
            }

            // Transform the ship's local AABB to world space at the destination position
            AABBd worldOrientedAABB = transformLocalAABBToWorld(shipAABB, ship.getTransform(), exactPosition, sourceWorld);

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eShip oriented bounds at destination:");
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Min: " +
                    String.format("%.2f, %.2f, %.2f", worldOrientedAABB.minX(), worldOrientedAABB.minY(), worldOrientedAABB.minZ()));
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Max: " +
                    String.format("%.2f, %.2f, %.2f", worldOrientedAABB.maxX(), worldOrientedAABB.maxY(), worldOrientedAABB.maxZ()));
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §e- Center: " +
                    String.format("%.2f, %.2f, %.2f", exactPosition.x, exactPosition.y, exactPosition.z));

            // Check for collisions with solid blocks in the ship's oriented bounds
            boolean hasCollision = checkForSolidBlocksInAABB(targetWorld, worldOrientedAABB, sourceWorld);

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eSpace check result: " +
                    (hasCollision ? "§cCOLLISION DETECTED" : "§aCLEAR PATH"));

            return !hasCollision;

        } catch (Exception e) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cError checking destination space: " + e.getMessage());
            return false;
        }
    }

    /**
     * Transform the ship's local AABB to world space at the destination position
     /**
     * Transform the ship's local AABB to world space at the destination position
     */
    private AABBd transformLocalAABBToWorld(AABBic localAABB, ShipTransform shipTransform, Vector3d destination, ServerLevel sourceWorld) {
        try {
            // Use the working method for transformation matrix
            Matrix4dc shipToWorld = shipTransform.getShipToWorld();


            // Extract position from the transformation matrix (last column)
            Vector3d positionInWorld = new Vector3d();
            shipToWorld.getTranslation(positionInWorld);

            // Get local AABB bounds (these are in ship coordinates)
            int localMinX = localAABB.minX();
            int localMinY = localAABB.minY();
            int localMinZ = localAABB.minZ();
            int localMaxX = localAABB.maxX();
            int localMaxY = localAABB.maxY();
            int localMaxZ = localAABB.maxZ();

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eLocal ship AABB: " +
                    localMinX + "," + localMinY + "," + localMinZ + " to " +
                    localMaxX + "," + localMaxY + "," + localMaxZ);
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eCurrent ship position: " +
                    String.format("%.2f, %.2f, %.2f", positionInWorld.x, positionInWorld.y, positionInWorld.z));

            // Transform all 8 corners of the local AABB to world space
            Vector3d[] worldCorners = new Vector3d[8];

            // Corner 0: (minX, minY, minZ)
            worldCorners[0] = transformPoint(shipToWorld, localMinX, localMinY, localMinZ, destination, positionInWorld);
            // Corner 1: (minX, minY, maxZ)
            worldCorners[1] = transformPoint(shipToWorld, localMinX, localMinY, localMaxZ, destination, positionInWorld);
            // Corner 2: (minX, maxY, minZ)
            worldCorners[2] = transformPoint(shipToWorld, localMinX, localMaxY, localMinZ, destination, positionInWorld);
            // Corner 3: (minX, maxY, maxZ)
            worldCorners[3] = transformPoint(shipToWorld, localMinX, localMaxY, localMaxZ, destination, positionInWorld);
            // Corner 4: (maxX, minY, minZ)
            worldCorners[4] = transformPoint(shipToWorld, localMaxX, localMinY, localMinZ, destination, positionInWorld);
            // Corner 5: (maxX, minY, maxZ)
            worldCorners[5] = transformPoint(shipToWorld, localMaxX, localMinY, localMaxZ, destination, positionInWorld);
            // Corner 6: (maxX, maxY, minZ)
            worldCorners[6] = transformPoint(shipToWorld, localMaxX, localMaxY, localMinZ, destination, positionInWorld);
            // Corner 7: (maxX, maxY, maxZ)
            worldCorners[7] = transformPoint(shipToWorld, localMaxX, localMaxY, localMaxZ, destination, positionInWorld);

            // Find the world-aligned bounds that contain all transformed corners
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

            for (Vector3d corner : worldCorners) {
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }

            return new AABBd(minX, minY, minZ, maxX, maxY, maxZ);

        } catch (Exception e) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cError transforming local AABB: " + e.getMessage());
            // Fallback: use simple AABB centered at destination
            double size = 2.0; // arbitrary size
            return new AABBd(
                    destination.x - size, destination.y - size, destination.z - size,
                    destination.x + size, destination.y + size, destination.z + size
            );
        }
    }

    /**
     * Transform a point from ship coordinates to world coordinates at destination
     */
    private Vector3d transformPoint(Matrix4dc shipToWorld, double localX, double localY, double localZ, Vector3d destination, Vector3d currentShipPosition) {
        // Transform the point from ship coordinates to world coordinates
        Vector3d worldPoint = new Vector3d(localX, localY, localZ);
        shipToWorld.transformPosition(worldPoint);

        // Calculate the offset from the ship's current center to the destination
        Vector3d currentCenter = new Vector3d();
        currentCenter.x = currentShipPosition.x;
        currentCenter.y = currentShipPosition.y;
        currentCenter.z = currentShipPosition.z;

        // Move the point to the destination
        worldPoint.add(destination.x - currentCenter.x,
                destination.y - currentCenter.y,
                destination.z - currentCenter.z);

        return worldPoint;
    }


    /**
     * Check if any solid blocks intersect with the given AABBd, ignoring portal blocks
     */
    private boolean checkForSolidBlocksInAABB(ServerLevel world, AABBd bounds, ServerLevel sourceWorld) {
        try {
            // Get the integer bounds of the AABB using method calls
            int minX = (int) Math.floor(bounds.minX());
            int minY = (int) Math.floor(bounds.minY());
            int minZ = (int) Math.floor(bounds.minZ());
            int maxX = (int) Math.ceil(bounds.maxX());
            int maxY = (int) Math.ceil(bounds.maxY());
            int maxZ = (int) Math.ceil(bounds.maxZ());

            int solidBlocksFound = 0;

            // Check all blocks within the AABB
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos checkPos = new BlockPos(x, y, z);

                        // Skip if outside world bounds
                        if (checkPos.getY() < world.getMinBuildHeight() || checkPos.getY() > world.getMaxBuildHeight()) {
                            continue;
                        }

                        BlockState state = world.getBlockState(checkPos);

                        // Skip portal blocks and air
                        if (state.getBlock() instanceof NetherPortalBlock || state.isAir()) {
                            continue;
                        }

                        // Check if this specific block intersects with the ship's AABB
                        net.minecraft.world.phys.AABB blockBounds = new net.minecraft.world.phys.AABB(
                                checkPos.getX(), checkPos.getY(), checkPos.getZ(),
                                checkPos.getX() + 1, checkPos.getY() + 1, checkPos.getZ() + 1
                        );

                        net.minecraft.world.phys.AABB shipBoundsMC = new net.minecraft.world.phys.AABB(
                                bounds.minX(), bounds.minY(), bounds.minZ(),
                                bounds.maxX(), bounds.maxY(), bounds.maxZ()
                        );

                        if (shipBoundsMC.intersects(blockBounds)) {
                            solidBlocksFound++;
                            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cCollision with block at: " +
                                    x + "," + y + "," + z + " (" + state.getBlock().getName().getString() + ")");
                        }
                    }
                }
            }

            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eCollision check: " +
                    solidBlocksFound + " solid blocks intersecting ship bounds");

            return solidBlocksFound > 0;

        } catch (Exception e) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cError in collision detection: " + e.getMessage());
            return true;
        }
    }
    private PortalCheckResult isShipInPortalWithThreshold(Ship ship, ServerLevel level) {
        try {
            var shipAABB = ship.getWorldAABB();
            if (shipAABB == null) return new PortalCheckResult(false, BlockPos.ZERO);

            // Calculate ship volume for percentage calculation
            double shipVolume = (shipAABB.maxX() - shipAABB.minX()) *
                    (shipAABB.maxY() - shipAABB.minY()) *
                    (shipAABB.maxZ() - shipAABB.minZ());

            if (shipVolume <= 0) return new PortalCheckResult(false, BlockPos.ZERO);

            int minX = (int) Math.floor(shipAABB.minX());
            int minY = (int) Math.floor(shipAABB.minY());
            int minZ = (int) Math.floor(shipAABB.minZ());
            int maxX = (int) Math.ceil(shipAABB.maxX());
            int maxY = (int) Math.ceil(shipAABB.maxY());
            int maxZ = (int) Math.ceil(shipAABB.maxZ());

            int portalBlocks = 0;
            int totalX = 0;
            int totalY = 0;
            int totalZ = 0;
            int portalBlockCount = 0;

            // Check all blocks within the ship's bounding box
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (level.getBlockState(pos).getBlock() instanceof NetherPortalBlock) {
                            portalBlocks++;
                            // Accumulate position for center calculation
                            totalX += x;
                            totalY += y;
                            totalZ += z;
                            portalBlockCount++;
                        }
                    }
                }
            }

            BlockPos portalCenter = BlockPos.ZERO;
            if (portalBlockCount > 0) {
                // Calculate average portal position
                portalCenter = new BlockPos(
                        totalX / portalBlockCount,
                        totalY / portalBlockCount,
                        totalZ / portalBlockCount
                );
            }

            // Calculate percentage of ship volume that's in portal
            double blockVolume = 1.0; // Each block is 1 cubic meter
            double portalVolume = portalBlocks * blockVolume;
            double portalPercentage = portalVolume / shipVolume;

            boolean meetsThreshold = portalPercentage >= PORTAL_THRESHOLD_PERCENTAGE;

            if (meetsThreshold) {
                sendMessageToAllPlayers(level, "§6[Portal Skies] §aShip " + ship.getId() + " has " + String.format("%.1f", portalPercentage * 100) +
                        "% portal coverage (" + portalBlocks + " portal blocks)");
            }

            return new PortalCheckResult(meetsThreshold, portalCenter);
        } catch (Exception e) {
            sendMessageToAllPlayers(level, "§6[Portal Skies] §cError checking portal threshold: " + e.getMessage());
            return new PortalCheckResult(false, BlockPos.ZERO);
        }
    }
    private void testingMethod(ServerLevel sourceWorld) {
        sendMessageToAllPlayers(sourceWorld, " test method working" );
    }
    private PortalInfo findAndValidatePortal(BlockPos sourcePortalPos, ServerLevel sourceWorld, ServerLevel targetWorld, double scale, Ship ship) {
        PortalInfo portalInfo ;
        BlockPos scaledPos = calculateScaledPosition(sourcePortalPos, scale, targetWorld);
        portalInfo = findExistingPortalVanillaStyle(targetWorld, scaledPos,128 ,ship );
            // Calculate the expected position in the target dimension using vanilla scaling


            sendMessageToAllPlayers  (sourceWorld, "§6[Portal Skies] §eSource portal at: " +
                    sourcePortalPos.getX() + ", " + sourcePortalPos.getY() + ", " + sourcePortalPos.getZ());
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eScaled target position: " +
                    scaledPos.getX() + ", " + scaledPos.getY() + ", " + scaledPos.getZ() + " (scale: " + scale + ")");

            // FIRST: Try to find portal using normal scaled coordinates (8:1 or 1:8) with 32 radius

           //  findExistingPortalVanillaStyle(targetWorld, scaledPos,128 ,ship );
                testingMethod(sourceWorld);


            if (portalInfo != null) {
                sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eFound existing portal at: " +
                        portalInfo.portalCenter.getX() + ", " + portalInfo.portalCenter.getY() + ", " + portalInfo.portalCenter.getZ() +
                        " Size: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight +
                        " Axis: " + portalInfo.axis);
                return portalInfo;

            }



            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cNo suitable portal found in target dimension");
            return null;


           // sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cError finding portal: " + e.getMessage());
            //e.printStackTrace();


    }

    /**
     * Calculate scaled position using vanilla Minecraft rules
     */
    private BlockPos calculateScaledPosition(BlockPos sourcePos, double scale, ServerLevel targetWorld) {
        int scaledX = (int) Math.floor(sourcePos.getX() * scale);
        int scaledZ = (int) Math.floor(sourcePos.getZ() * scale);

        // VANILLA RULES:
        int scaledY;
        if (scale == 0.125) { // Going to Nether (Overworld → Nether)
            // IGNORE source Y - use middle of Nether range for search center
            scaledY = 64; // Middle of Nether (1-127)
        } else { // Going to Overworld (Nether → Overworld)
            // PRESERVE Y coordinate from Nether
            scaledY = sourcePos.getY();
        }

        return new BlockPos(scaledX, scaledY, scaledZ);
    }

    /**
     * Vanilla-style search for existing portals - with optimized 2x2x3 scanning
     */

    private PortalInfo findExistingPortalVanillaStyle(ServerLevel world, BlockPos center, int searchRadius, Ship ship) {
        sendMessageToAllPlayers(world, "§6[Portal Skies] §eExpanding portal search around "
                + center.getX() + ", " + center.getY() + ", " + center.getZ() + " (step: 2x2x3, radius: 128)+");


            sendMessageToAllPlayers(world, "§6[Portal Skies] §eExpanding portal search around "
                   + center.getX() + ", " + center.getY() + ", " + center.getZ() + " (step: 2x2x3, radius: 128)+");

            // Use 128 block radius for both dimensions
          //  searchRadius = 128;

            // Determine vertical search range based on dimension
            int minY, maxY;
            if (world.dimension().location().toString().equals("minecraft:the_nether")) {
                // Nether: search full height range (1-127)
                minY = 1;
                maxY =127;
               // git remote add valkerian-nether-portal https://github.com/frankkodra/valkerian-nether-portal.git
               // git push -u valkerian-nether-portal main

            } else {
                // Overworld: search massive vertical range
                minY=-59;
                maxY=310;
            }

            sendMessageToAllPlayers(world, "§6[Portal Skies] §eVertical range: " + minY + " to " + maxY);

            // Expand outward from center in layers
            for (int currentRadius = 0; currentRadius <= searchRadius; currentRadius += 2) {
                sendMessageToAllPlayers(world, "§6[Portal Skies] §eSearching radius: " + currentRadius + " blocks");

                // Calculate current search bounds for this layer
                int currentMinX = center.getX() - currentRadius;
                int currentMaxX = center.getX() + currentRadius;
                int currentMinZ = center.getZ() - currentRadius;
                int currentMaxZ = center.getZ() + currentRadius;

                int blocksCheckedThisLayer = 0;
                int portalsFoundThisLayer = 0;

                // Scan the entire volume for this layer with 2x2x3 stepping
                for (int y = minY; y <= maxY; y += 3) {
                    for (int x = currentMinX; x <= currentMaxX; x += 2) {
                        for (int z = currentMinZ; z <= currentMaxZ; z += 2) {
                            // Skip if this position is inside a smaller radius we already checked
                            if (currentRadius > 0) {
                                int distX = Math.abs(x - center.getX());
                                int distZ = Math.abs(z - center.getZ());
                                if (distX < currentRadius && distZ < currentRadius) {
                                    continue; // Already checked in previous layer
                                }
                            }

                            blocksCheckedThisLayer++;
                            BlockPos checkPos = new BlockPos(x, y, z);

                            // Skip if outside world bounds
                            if (checkPos.getY() < world.getMinBuildHeight() || checkPos.getY() > world.getMaxBuildHeight()) {
                                continue;
                            }

                            if (isValidPortalBlock(world, checkPos)) {
                                portalsFoundThisLayer++;
                                PortalInfo portalInfo = analyzePortalSize(world, checkPos);
                                if (portalInfo != null && portalInfo.isValid) {
                                    // EARLY EXIT: Check if ship can fit through this portal
                                    if (validatePortalForShip(portalInfo, ship, world)) {
                                        sendMessageToAllPlayers(world, "§6[Portal Skies] §aFound valid portal at layer " + currentRadius +
                                                ": " + checkPos.getX() + ", " + checkPos.getY() + ", " + checkPos.getZ() +
                                                " Size: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight +
                                                " (checked " + blocksCheckedThisLayer + " blocks this layer)");
                                        return portalInfo;
                                    } else {
                                        sendMessageToAllPlayers(world, "§6[Portal Skies] §eFound portal but too small: " +
                                                checkPos.getX() + ", " + checkPos.getY() + ", " + checkPos.getZ() +
                                                " Size: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight);
                                    }
                                }
                            }
                        }
                    }
                }

                sendMessageToAllPlayers(world, "§6[Portal Skies] §eLayer " + currentRadius +
                        ": checked " + blocksCheckedThisLayer + " blocks, found " + portalsFoundThisLayer + " portals");

                // Early exit if we've reached a reasonable distance without finding anything
                if (currentRadius >= 64 && portalsFoundThisLayer == 0) {
                    sendMessageToAllPlayers(world, "§6[Portal Skies] §eNo portals found within 64 blocks, continuing to 128...");
                }
            }

            sendMessageToAllPlayers(world, "§6[Portal Skies] §cNo valid portals found within 128 block radius");
            return null;

          //  sendMessageToAllPlayers(world, "§6[Portal Skies] §cError in portal search: " + e.getMessage());


    }
    /**
     * Search a horizontal ring with optimized 2x2 stepping
     */


    /**
     * Validate if a portal is large enough for the ship
     */
    private boolean validatePortalForShip(PortalInfo portalInfo, Ship ship, ServerLevel sourceWorld) {
        var shipAABB = ship.getWorldAABB();
        if (shipAABB == null) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cCould not get ship dimensions");
            return false;
        }

        double shipWidth = shipAABB.maxX() - shipAABB.minX();
        double shipHeight = shipAABB.maxY() - shipAABB.minY();
        double shipLength = shipAABB.maxZ() - shipAABB.minZ();

        sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eShip size: " +
                String.format("%.1fx%.1fx%.1f", shipWidth, shipHeight, shipLength));

        if (portalInfo.axis == null) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cPortal orientation: NULL - this shouldn't happen!");
            return false;
        }

        // Determine which ship dimension needs to fit through the portal
        double shipDimensionToFit;
        if (portalInfo.axis == Direction.Axis.X) {
            // Portal runs east-west (faces north-south)
            // Ship needs to fit its WIDTH through the portal
            shipDimensionToFit = shipWidth;
        } else {
            // Portal runs north-south (faces east-west)
            // Ship needs to fit its LENGTH through the portal
            shipDimensionToFit = shipLength;
        }

        // Set requirements with small buffer
        double buffer = 0.2;
        portalInfo.requiredWidth = (int) Math.ceil(shipDimensionToFit + buffer);
        portalInfo.requiredHeight = (int) Math.ceil(shipHeight + buffer);

        // Debug info
        sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eShip dimension to fit: " + shipDimensionToFit);
        sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eRequired portal: " +
                portalInfo.requiredWidth + "x" + portalInfo.requiredHeight);
        sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §eActual portal size: " +
                portalInfo.actualWidth + "x" + portalInfo.actualHeight);

        // Check if ship fits through the portal
        portalInfo.isValid = (portalInfo.actualWidth >= portalInfo.requiredWidth &&
                portalInfo.actualHeight >= portalInfo.requiredHeight);

        if (portalInfo.isValid) {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §aPortal is large enough for ship!");
        } else {
            sendMessageToAllPlayers(sourceWorld, "§6[Portal Skies] §cPortal too small: " +
                    portalInfo.actualWidth + "x" + portalInfo.actualHeight + " (needs " +
                    portalInfo.requiredWidth + "x" + portalInfo.requiredHeight + ")");
        }

        return portalInfo.isValid;
    }

    /**
     * Check if a block is a valid nether portal block
     */
    private boolean isValidPortalBlock(ServerLevel world, BlockPos pos) {
        try {
            return world.getBlockState(pos).getBlock() instanceof NetherPortalBlock;
        } catch (Exception e) {
            return false;
        }
    }

    private PortalInfo analyzePortalSize(ServerLevel world, BlockPos portalBlock) {
        try {
            // Determine portal orientation from the block state
            BlockState state = world.getBlockState(portalBlock);
            Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);

            PortalInfo info = new PortalInfo();
            info.portalCenter = portalBlock; // Just use the found block as center
            info.axis = axis;

            sendMessageToAllPlayers(world, "§6[Portal Skies] §ePortal axis detected: " + axis);

            // Use directional expansion to measure portal dimensions
            info = measurePortalDimensions(world, portalBlock, axis);

            return info;
        } catch (Exception e) {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError analyzing portal: " + e.getMessage());
            return null;
        }
    }

    private PortalInfo measurePortalDimensions(ServerLevel world, BlockPos start, Direction.Axis expectedAxis) {
        PortalInfo info = new PortalInfo();
        info.portalCenter = start;
        info.axis = expectedAxis;

        try {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §eStarting portal measurement at: " +
                    start.getX() + "," + start.getY() + "," + start.getZ() + " axis: " + expectedAxis);

            if (expectedAxis == Direction.Axis.X) {
                // X-axis portal: width is along X axis (east-west direction)
                return measureXAxisPortal(world, start);
            } else {
                // Z-axis portal: width is along Z axis (north-south direction)
                return measureZAxisPortal(world, start);
            }

        } catch (Exception e) {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError measuring portal: " + e.getMessage());
            info.isValid = false;
            return info;
        }
    }

    private PortalInfo measureXAxisPortal(ServerLevel world, BlockPos start)  {
        PortalInfo info = new PortalInfo();
        info.axis = Direction.Axis.X;
        info.portalCenter = start;

        try {
            // X-axis portal runs along X axis (east-west)
            // So width is measured along X axis (east-west direction)
            // Height is measured along Y axis (up-down direction)

            // Measure width along X axis
            int minX = start.getX();
            int maxX = start.getX();

            // Check west (negative X)
            BlockPos current = start;
            while (isPortalBlockWithAxis(world, current.west(), Direction.Axis.X)) {
                current = current.west();
                minX = current.getX();
            }

            // Check east (positive X)
            current = start;
            while (isPortalBlockWithAxis(world, current.east(), Direction.Axis.X)) {
                current = current.east();
                maxX = current.getX();
            }

            int width = maxX - minX + 1;

            // Measure height along Y axis
            int minY = start.getY();
            int maxY = start.getY();

            // Move down
            current = start;
            while (isPortalBlockWithAxis(world, current.below(), Direction.Axis.X)) {
                current = current.below();
                minY = current.getY();
            }

            // Move up
            current = start;
            while (isPortalBlockWithAxis(world, current.above(), Direction.Axis.X)) {
                current = current.above();
                maxY = current.getY();
            }

            int height = maxY - minY + 1;

            // Store bounds for center calculation
            info.minX = minX;
            info.maxX = maxX;
            info.minY = minY;
            info.maxY = maxY;
            info.minZ = start.getZ();
            info.maxZ = start.getZ();

            info.actualWidth = width;
            info.actualHeight = height;
            info.isValid = true;

            sendMessageToAllPlayers(world, "§6[Portal Skies] §eX-axis portal - Width (X): " + width + " Height (Y): " + height);

        } catch (Exception e) {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError measuring X-axis portal: " + e.getMessage());
            info.isValid = false;
        }

        return info;
    }

    private PortalInfo measureZAxisPortal(ServerLevel world, BlockPos start) {
        PortalInfo info = new PortalInfo();
        info.axis = Direction.Axis.Z;
        info.portalCenter = start;

        try {
            // Z-axis portal runs along Z axis (north-south)
            // So width is measured along Z axis (north-south direction)
            // Height is measured along Y axis (up-down direction)

            // Measure width along Z axis
            int minZ = start.getZ();
            int maxZ = start.getZ();

            // Check north (negative Z)
            BlockPos current = start;
            while (isPortalBlockWithAxis(world, current.north(), Direction.Axis.Z)) {
                current = current.north();
                minZ = current.getZ();
            }

            // Check south (positive Z)
            current = start;
            while (isPortalBlockWithAxis(world, current.south(), Direction.Axis.Z)) {
                current = current.south();
                maxZ = current.getZ();
            }

            int width = maxZ - minZ + 1;

            // Measure height along Y axis
            int minY = start.getY();
            int maxY = start.getY();

            // Move down
            current = start;
            while (isPortalBlockWithAxis(world, current.below(), Direction.Axis.Z)) {
                current = current.below();
                minY = current.getY();
            }

            // Move up
            current = start;
            while (isPortalBlockWithAxis(world, current.above(), Direction.Axis.Z)) {
                current = current.above();
                maxY = current.getY();
            }

            int height = maxY - minY + 1;

            // Store bounds for center calculation
            info.minX = start.getX();
            info.maxX = start.getX();
            info.minY = minY;
            info.maxY = maxY;
            info.minZ = minZ;
            info.maxZ = maxZ;

            info.actualWidth = width;
            info.actualHeight = height;
            info.isValid = true;

            sendMessageToAllPlayers(world, "§6[Portal Skies] §eZ-axis portal - Width (Z): " + width + " Height (Y): " + height);

        } catch (Exception e) {
            sendMessageToAllPlayers(world, "§6[Portal Skies] §cError measuring Z-axis portal: " + e.getMessage());
            info.isValid = false;
        }

        return info;
    }

    private boolean isPortalBlockWithAxis(ServerLevel world, BlockPos pos, Direction.Axis expectedAxis) {
        try {
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof NetherPortalBlock) {
                Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);
                return axis == expectedAxis;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send message to all players in a world
     */
    private static void sendMessageToAllPlayers(ServerLevel world, String message) {
        if (world != null) {
            for (ServerPlayer player : world.players()) {
                player.sendSystemMessage(Component.literal(message));
            }
        }
    }

    /**
     * Convert dimension ID to display name
     */
    private static String getDimensionDisplayName(String dimensionId) {
        switch (dimensionId) {
            case "minecraft:overworld":
                return "Overworld";
            case "minecraft:the_nether":
                return "Nether";
            case "minecraft:the_end":
                return "The End";
            default:
                return dimensionId;
        }
    }

    // Helper class to return portal status and center position
    private static class PortalCheckResult {
        public final boolean isInPortal;
        public final BlockPos portalCenter;

        public PortalCheckResult(boolean isInPortal, BlockPos portalCenter) {
            this.isInPortal = isInPortal;
            this.portalCenter = portalCenter;
        }
    }

    // Helper class to store portal information
    private static class PortalInfo {
        public BlockPos portalCenter;
        public Direction.Axis axis;
        public int actualWidth;
        public int actualHeight;
        public int requiredWidth;
        public int requiredHeight;
        public boolean isValid = false;

        // Bounds storage for center calculation
        public int minX, maxX, minY, maxY, minZ, maxZ;
    }

    /**
     * Helper class to return teleport position results
     */
    private static class TeleportPositionResult {
        public BlockPos teleportPos;        // For backward compatibility
        public Vector3d exactTeleportPos;   // NEW: For precise positioning
        public Direction exitDirection;
        public boolean hasEnoughSpace = false;
    }
}