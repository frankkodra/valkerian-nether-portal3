package Portal.code;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.joml.primitives.AABBic;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.Ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NetherPortalBlock;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.core.apigame.world.ServerShipWorldCore;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.*;

@Mod("valkerian_nether_portals")
public class netherPortalShipListener {

    private final Map<Long, Integer> portalCooldownMap = new HashMap<>();
    private final Map<Long, BlockPos> lastKnownPositions = new HashMap<>();
    private final Map<BlockPos, Long> portalCache = new HashMap<>();
    private long lastCacheClear = System.currentTimeMillis();


    public netherPortalShipListener() {
        MinecraftForge.EVENT_BUS.register(this);
        Config.load();

    }
    private static int tickcount = 1;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
       // if (event.phase != TickEvent.Phase.END) return;

        // Reduce check frequency using config value
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if ( tickcount% Config.CHECK_INTERVAL_TICKS != 0){tickcount++;Logger.sendMessage("returning due to not time yet "+tickcount+" server ticks= "+server.getTickCount(),false); return;}
        tickcount=1;
        Logger.sendMessage("------time to check do the checks now-----",false);
        // Clear cache periodically using config value
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCacheClear > Config.CACHE_CLEAR_INTERVAL) {
            portalCache.clear();

            lastCacheClear = currentTime;
        }

        for (ServerLevel world : server.getAllLevels()) {
           if(world.dimension()!=Level.OVERWORLD&&world.dimension()!=Level.NETHER){
               continue;
            }
            ServerShipWorldCore shipWorld = VSGameUtilsKt.getShipObjectWorld(world);
            if (shipWorld == null) continue;

            // RESTORED: Original ship copying method
            List<Ship> shipsCopy = new ArrayList<>();
            Logger.sendMessage("ships in dimension "+world.dimension().toString()+"\n"+shipWorld.getLoadedShips().size(),false);
            for (Ship ship : shipWorld.getLoadedShips()) {
                shipsCopy.add(ship);
            }

            for (Ship ship : shipsCopy) {
                long shipId = ship.getId();

               // if(!isShipInLoadedChunks(ship, world)) {
               //     continue;
               // }
                // Skip ships on cooldown using config value
                int cooldown = portalCooldownMap.getOrDefault(shipId, 0);
                Logger.sendMessage("checking ship is in portal: id ="+shipId+" Cool down= "+cooldown ,true);

                if (cooldown > 0) {
                    portalCooldownMap.put(shipId, portalCooldownMap.get(shipId) - Config.CHECK_INTERVAL_TICKS);
                    continue;
                }



                PortalCheckResult portalResult = isShipInPortalWithThreshold(ship, world);
                if (portalResult.isInPortal) {
                    // SIMPLE CHECK: Skip if this ship is inside any other ship in the same dimension
                    if (checkIfShipIsInBiggerShip(ship, shipWorld, world)) {
                        Logger.sendMessage("[Portal Skies] Skipping ship " + shipId + " - it's inside a larger ship", false);
                        continue;
                    }

                    // Proceed with teleportation...
                    double scale = 1.0;
                    ServerLevel targetWorld = null;

                    var currentDim = world.dimension().location().toString();

                    if (currentDim.equals("minecraft:the_nether")) {
                        targetWorld = server.getLevel(Level.OVERWORLD);
                        scale = 8.0;
                        Logger.sendMessage("[Portal Skies] Ship detected in Nether portal - preparing to teleport to Overworld!", true);
                    } else if (currentDim.equals("minecraft:overworld")) {
                        targetWorld = server.getLevel(Level.NETHER);
                        scale = 0.125;
                        Logger.sendMessage("[Portal Skies] Ship detected in Nether portal - preparing to teleport to Nether!", true);
                    } else {
                        continue;
                    }

                    PortalInfo portalInfo = analyzePortalSize(world, portalResult.portalCenter);
                    Vector3d portalCentVect = calculateExactPortalCenter(portalInfo, world);
                    BlockPos portalcenter = new BlockPos((int)portalCentVect.x, (int)portalCentVect.y, (int)portalCentVect.z);

                    PortalInfo currentWorldPortal = validatePortalForShip(portalInfo, ship, world, portalcenter);


                    if (targetWorld != null && currentWorldPortal.isValid) {
                        try {
                            PortalInfo targetPortalInfo = findAndValidatePortal(portalInfo,portalcenter, world, targetWorld, scale, ship);

                            if (targetPortalInfo != null && targetPortalInfo.isValid) {
                                TeleportPositionResult positionResult = calculateTeleportPosition(targetPortalInfo, ship, targetPortalInfo.portalCenter, world, targetWorld);

                                Logger.sendMessage("[Portal Skies] Teleporting ship " + shipId + " from " + getDimensionDisplayName(currentDim) +
                                        " to " + getDimensionDisplayName(targetWorld.dimension().location().toString()), true);

                                Logger.sendMessage("[Portal Skies] Teleporting to exact position: " +
                                        String.format("%.2f, %.2f, %.2f",
                                                positionResult.exactTeleportPos.x,
                                                positionResult.exactTeleportPos.y,
                                                positionResult.exactTeleportPos.z) +
                                        " (facing: " + positionResult.exitDirection + ")", true);

                                Direction.Axis sourceAxis = getPortalAxisAt(world, portalResult.portalCenter);
                                Direction.Axis targetAxis = targetPortalInfo.axis;

                                float rotationAngle = calculateOptimalRotation(ship, sourceAxis, targetAxis, positionResult.exitDirection);

                                Logger.sendMessage("[Portal Skies] - Target portal axis: " + targetAxis, true);
                                Logger.sendMessage("[Portal Skies] - Applying rotation: " + rotationAngle + "°", true);

                                ShipTeleportationUtils.teleportShipWithFallback(ship, world, targetWorld, positionResult.exactTeleportPos, rotationAngle);

                                Logger.sendMessage("[Portal Skies] DEBUG: Calling teleportShipWithFallback with rotation: " + rotationAngle + "°", true);
                                Logger.sendMessage("[Portal Skies] DEBUG: teleportShipWithFallback completed", true);
                                portalCooldownMap.put(shipId, Config.PORTAL_COOLDOWN_TICKS);
                                Logger.startNewLogFile();

                            } else {
                                if (targetPortalInfo == null) {
                                    Logger.sendMessage("[Portal Skies] No suitable portal found for ship " + shipId, true);
                                } else {
                                    Logger.sendMessage("[Portal Skies] Target portal too small for ship " + shipId +
                                            " (needs " + targetPortalInfo.requiredWidth + "x" + targetPortalInfo.requiredHeight + ", has " +
                                            targetPortalInfo.actualWidth + "x" + targetPortalInfo.actualHeight + ")", true);
                                }
                            }
                        } catch (Exception e) {
                            Logger.sendMessage("[Portal Skies] Error during portal processing: " + e.getMessage(), true);
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
    }
    private boolean isShipInLoadedChunks(Ship ship, ServerLevel level) {
        try {
            // VS2 tracks which chunks ships are in - use their system
            var activeChunksSet = ship.getActiveChunksSet();
            if (activeChunksSet == null) return false;

            // Check if the ship has any active chunks (meaning it's loaded)
            return true;

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error checking VS2 chunk loading: " + e.getMessage(), false);
            return false;
        }
    }
    // Updated isShipMoving method using config value
    private boolean isShipMoving(Ship ship) {
        Vector3dc velocity = ship.getVelocity();
        double speedSquared = velocity.x() * velocity.x() + velocity.y() * velocity.y() + velocity.z() * velocity.z();

        // Skip ships moving very slowly using config value
        if (speedSquared < Config.MIN_MOVEMENT_THRESHOLD * Config.MIN_MOVEMENT_THRESHOLD) {
            return false;
        }

        // Check position change
        BlockPos currentPos = new BlockPos(
                (int)ship.getTransform().getPositionInWorld().x(),
                (int)ship.getTransform().getPositionInWorld().y(),
                (int)ship.getTransform().getPositionInWorld().z()
        );

        BlockPos lastPos = lastKnownPositions.get(ship.getId());
        lastKnownPositions.put(ship.getId(), currentPos);

        return lastPos == null || !lastPos.equals(currentPos);
    }
    /**
     * Simple check: Is this ship inside any other ship in the same dimension?
     */
    private boolean checkIfShipIsInBiggerShip(Ship currentShip, ServerShipWorldCore shipWorld, ServerLevel world) {
        try {
            AABBdc currentShipAABB = currentShip.getWorldAABB();
            if (currentShipAABB == null) return false;

            // Get all ships in the same dimension
            Collection<LoadedServerShip> allShipsInDimension = shipWorld.getLoadedShips();

            for (Ship otherShip : allShipsInDimension) {
                if (otherShip.getId() == currentShip.getId()) continue;

                AABBdc otherShipAABB = otherShip.getWorldAABB();
                if (otherShipAABB == null) continue;

                // Check if current ship is completely inside this other ship
                if (isAABBCompletelyInside(currentShipAABB, otherShipAABB)) {
                    Logger.sendMessage("[Portal Skies] Ship " + currentShip.getId() + " is inside ship " + otherShip.getId(), false);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error checking ship containment: " + e.getMessage(), false);
            return false;
        }
    }

    /**
     * Checks if AABB A is completely inside AABB B
     */
    private boolean isAABBCompletelyInside(AABBdc innerAABB, AABBdc outerAABB) {
        double margin = 0.1; // Small margin for floating point precision

        return innerAABB.minX() >= outerAABB.minX() - margin &&
                innerAABB.maxX() <= outerAABB.maxX() + margin &&
                innerAABB.minY() >= outerAABB.minY() - margin &&
                innerAABB.maxY() <= outerAABB.maxY() + margin &&
                innerAABB.minZ() >= outerAABB.minZ() - margin &&
                innerAABB.maxZ() <= outerAABB.maxZ() + margin;
    }

    // Calculate rotation DELTA values based on portal axis and exit direction
    private float calculateOptimalRotation(Ship ship, Direction.Axis sourceAxis, Direction.Axis targetAxis, Direction exitDirection) {
        Logger.sendMessage("[Portal Skies] === ROTATION DELTA CALCULATION ===", true);
        Logger.sendMessage("[Portal Skies] Source axis: " + sourceAxis + ", Target axis: " + targetAxis + ", Exit direction: " + exitDirection, true);

        float rotationDelta = 0.0f;

        if (sourceAxis == targetAxis) {
            // Same axis - no rotation needed
            rotationDelta = 0.0f;
            Logger.sendMessage("[Portal Skies] Same axis - no rotation needed", true);
        } else {
            // Different axis - always rotate 90° but direction depends on exit
            if (sourceAxis == Direction.Axis.X && targetAxis == Direction.Axis.Z) {
                // X→Z axis change
                switch (exitDirection) {
                    case NORTH:
                    case SOUTH:
                        rotationDelta = 90.0f;   // Exit north/south = rotate +90°
                        break;
                    case EAST:
                    case WEST:
                        rotationDelta = -90.0f;  // Exit east/west = rotate -90°
                        break;
                }
            } else if (sourceAxis == Direction.Axis.Z && targetAxis == Direction.Axis.X) {
                // Z→X axis change
                switch (exitDirection) {
                    case NORTH:
                    case SOUTH:
                        rotationDelta = -90.0f;  // Exit north/south = rotate -90°
                        break;
                    case EAST:
                    case WEST:
                        rotationDelta = 90.0f;   // Exit east/west = rotate +90°
                        break;
                }
            }
            Logger.sendMessage("[Portal Skies] " + sourceAxis + "→" + targetAxis + " exit " + exitDirection + " = " + rotationDelta + "°", true);
        }

        Logger.sendMessage("[Portal Skies] Rotation delta to apply: " + rotationDelta + "°", true);
        return rotationDelta;
    }

    private ShipOrientation getShipOrientationRelativeToPortal(Ship ship, Direction.Axis portalAxis, ServerLevel world, BlockPos portalPos) {
        ShipTransform transform = ship.getTransform();

        // Get ship's local axes in world space
        Vector3d shipForward = new Vector3d(0, 0, 1);  // Local Z+ (length)
        Vector3d shipRight = new Vector3d(1, 0, 0);    // Local X+ (width)
        transform.getShipToWorld().transformDirection(shipForward);
        transform.getShipToWorld().transformDirection(shipRight);

        // Get portal normal vector (perpendicular to portal face)
        Vector3d portalNormal;
        if (portalAxis == Direction.Axis.X) {
            portalNormal = new Vector3d(1, 0, 0); // X-axis portal normal
        } else {
            portalNormal = new Vector3d(0, 0, 1); // Z-axis portal normal
        }

        // Calculate dot products to see which ship axis is more parallel to portal
        double forwardDot = Math.abs(shipForward.dot(portalNormal));
        double rightDot = Math.abs(shipRight.dot(portalNormal));

        boolean isWidthParallel = rightDot > forwardDot;
        double angleToPortal = Math.toDegrees(Math.acos(isWidthParallel ? rightDot : forwardDot));

        // ADDED DEBUG LOGS
        Logger.sendMessage("[Portal Skies] === ORIENTATION DEBUG ===", true);
        Logger.sendMessage("[Portal Skies] - Dimension: " + world.dimension().location().toString(), true);
        Logger.sendMessage("[Portal Skies] - Portal Axis: " + portalAxis, true);
        Logger.sendMessage("[Portal Skies] - Portal Position: " + portalPos.getX() + ", " + portalPos.getY() + ", " + portalPos.getZ(), true);
        Logger.sendMessage("[Portal Skies] - Ship Forward (world): " +
                String.format("%.3f, %.3f, %.3f", shipForward.x, shipForward.y, shipForward.z), true);
        Logger.sendMessage("[Portal Skies] - Ship Right (world): " +
                String.format("%.3f, %.3f, %.3f", shipRight.x, shipRight.y, shipRight.z), true);
        Logger.sendMessage("[Portal Skies] - Portal Normal: " +
                String.format("%.3f, %.3f, %.3f", portalNormal.x, portalNormal.y, portalNormal.z), true);
        Logger.sendMessage("[Portal Skies] - Forward Dot: " + String.format("%.6f", forwardDot), true);
        Logger.sendMessage("[Portal Skies] - Right Dot: " + String.format("%.6f", rightDot), true);
        Logger.sendMessage("[Portal Skies] - Dot Difference: " + String.format("%.6f", Math.abs(forwardDot - rightDot)), true);
        Logger.sendMessage("[Portal Skies] - Width Parallel: " + isWidthParallel, true);
        Logger.sendMessage("[Portal Skies] - Angle to Portal: " + String.format("%.1f°", angleToPortal), true);

        // Log which side is parallel
        if (isWidthParallel) {
            Logger.sendMessage("[Portal Skies] - RESULT: WIDTH (X) parallel to portal", true);
        } else {
            Logger.sendMessage("[Portal Skies] - RESULT: LENGTH (Z) parallel to portal", true);
        }
        Logger.sendMessage("[Portal Skies] === END ORIENTATION DEBUG ===", true);

        return new ShipOrientation(
                isWidthParallel ? "WIDTH" : "LENGTH",
                angleToPortal,
                isWidthParallel
        );
    }

    private Direction.Axis getPortalAxisAt(ServerLevel world, BlockPos portalPos) {
        BlockState state = world.getBlockState(portalPos);
        if (state.getBlock() instanceof NetherPortalBlock) {
            return state.getValue(NetherPortalBlock.AXIS);
        }
        return Direction.Axis.X;
    }

    private TeleportPositionResult calculateTeleportPosition(PortalInfo portalInfo, Ship ship, BlockPos sourcePortalPos, ServerLevel sourceWorld, ServerLevel targetWorld) {
        TeleportPositionResult result = new TeleportPositionResult();

        try {
            // Use ShipAABB for dimension calculations (local coordinates, not rotated)
            var shipAABB = ship.getShipAABB();
            if (shipAABB == null) {
                result.hasEnoughSpace = false;
                return result;
            }

            double shipWidth = shipAABB.maxX() - shipAABB.minX();
            double shipHeight = shipAABB.maxY() - shipAABB.minY();
            double shipLength = shipAABB.maxZ() - shipAABB.minZ();
            double shipLongestDimension = Math.max(shipWidth, shipLength);

            Logger.sendMessage("[Portal Skies] Ship dimensions (local): " +
                    String.format("%.1fx%.1fx%.1f", shipWidth, shipHeight, shipLength) +
                    " (longest: " + shipLongestDimension + ")", false);
            Direction.Axis sourceAxis = getPortalAxisAt(sourceWorld, sourcePortalPos);

            Direction approachDirection = calculateShipApproachDirection(ship, sourcePortalPos, portalInfo.axis, sourceWorld, sourceAxis);
            result.approachDirection = approachDirection;

            Logger.sendMessage("[Portal Skies] Ship approaching from: " + approachDirection, false);

            Vector3d exactPortalCenter = calculateExactPortalCenter(portalInfo, sourceWorld);

            // Get source portal axis
            Direction adjustedExitDirection = approachDirection;
            if (sourceAxis != portalInfo.axis && approachDirection == Direction.WEST) {
                Logger.sendMessage("[Portal Skies] - Adjusting exit direction: " + adjustedExitDirection, false);
            }
            if (sourceAxis != portalInfo.axis && approachDirection == Direction.SOUTH) {
                Logger.sendMessage("[Portal Skies] - Adjusting exit direction: " + adjustedExitDirection, false);
            }

            Vector3d exactExitPosition = calculateExactExitPosition(exactPortalCenter, adjustedExitDirection, ship, portalInfo, sourceWorld, sourceAxis, portalInfo.axis);

            BlockPos exitPosition = new BlockPos(
                    (int) Math.floor(exactExitPosition.x),
                    (int) Math.floor(exactExitPosition.y),
                    (int) Math.floor(exactExitPosition.z)
            );

            Logger.sendMessage("[Portal Skies] Exact exit position: " +
                    String.format("%.2f, %.2f, %.2f", exactExitPosition.x, exactExitPosition.y, exactExitPosition.z), false);

            result.teleportPos = exitPosition;
            result.exactTeleportPos = exactExitPosition;
            result.exitDirection = adjustedExitDirection;

            Logger.sendMessage("[Portal Skies] Final position calculation:", false);
            Logger.sendMessage("[Portal Skies] - Portal center: " +
                    String.format("%.2f, %.2f, %.2f", exactPortalCenter.x, exactPortalCenter.y, exactPortalCenter.z), false);
            Logger.sendMessage("[Portal Skies] - Exit direction: " + adjustedExitDirection, false);
            Logger.sendMessage("[Portal Skies] - Final block position: " + exitPosition, false);
            Logger.sendMessage("[Portal Skies] - Exact final position: " +
                    String.format("%.2f, %.2f, %.2f", exactExitPosition.x, exactExitPosition.y, exactExitPosition.z), false);
            Logger.sendMessage("[Portal Skies] - Enough space: " + result.hasEnoughSpace, false);

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error calculating teleport position: " + e.getMessage(), false);
        }

        return result;
    }

    private Vector3d calculateExactPortalCenter(PortalInfo portalInfo, ServerLevel world) {
        try {
            double centerX, centerY, centerZ;

            if (portalInfo.axis == Direction.Axis.X) {
                centerX = portalInfo.minX + (portalInfo.actualWidth / 2.0);
                centerY = portalInfo.minY + (portalInfo.actualHeight / 2.0);
                centerZ = portalInfo.portalCenter.getZ() + 0.5;
            } else {
                centerX = portalInfo.portalCenter.getX() + 0.5;
                centerY = portalInfo.minY + (portalInfo.actualHeight / 2.0);
                centerZ = portalInfo.minZ + (portalInfo.actualWidth / 2.0);
            }

            Logger.sendMessage("[Portal Skies] Portal bounds - X:" + portalInfo.minX + "-" + portalInfo.maxX +
                    " Y:" + portalInfo.minY + "-" + portalInfo.maxY + " Z:" + portalInfo.minZ + "-" + portalInfo.maxZ, false);
            Logger.sendMessage("[Portal Skies] Portal dimensions: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight, false);
            Logger.sendMessage("[Portal Skies] World coordinate center: " +
                    String.format("%.2f, %.2f, %.2f", centerX, centerY, centerZ), false);

            return new Vector3d(centerX, centerY, centerZ);

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error calculating exact portal center: " + e.getMessage(), false);
            return new Vector3d(
                    portalInfo.portalCenter.getX() + 0.5,
                    portalInfo.portalCenter.getY() + 0.5,
                    portalInfo.portalCenter.getZ() + 0.5
            );
        }
    }

    private Vector3d calculateExactExitPosition(Vector3d portalCenter, Direction exitDirection, Ship ship, PortalInfo portalInfo, ServerLevel sourceWorld, Direction.Axis sourceAxis, Direction.Axis targetAxis) {
        // Use ShipAABB for dimension calculations
        var shipAABB = ship.getShipAABB();
        double shipLength = shipAABB.maxZ() - shipAABB.minZ();
        double shipWidth = shipAABB.maxX() - shipAABB.minX();
        double shipHeight = shipAABB.maxY() - shipAABB.minY();

        double requiredClearance = (shipLength / 2.0) + 2.0;

        Logger.sendMessage("[Portal Skies] Ship dimensions:", false);
        Logger.sendMessage("[Portal Skies] - Length: " + shipLength, false);
        Logger.sendMessage("[Portal Skies] - Width: " + shipWidth, false);
        Logger.sendMessage("[Portal Skies] - Height: " + shipHeight, false);
        Logger.sendMessage("[Portal Skies] - Required clearance: " + requiredClearance, false);
        Logger.sendMessage("[Portal Skies] - Exit direction: " + exitDirection, false);
        Logger.sendMessage("[Portal Skies] - Portal axis: " + sourceAxis + " → " + targetAxis, false);

        Vector3d exitPosition = new Vector3d(portalCenter);

        // Perfect horizontal alignment based on portal axis
        if (targetAxis == Direction.Axis.X) {
            // X-axis portal: center ship horizontally (Z coordinate)
            exitPosition.z = portalCenter.z; // Perfect Z alignment
        } else {
            // Z-axis portal: center ship horizontally (X coordinate)
            exitPosition.x = portalCenter.x; // Perfect X alignment
        }

        // FIXED: Use ship height to calculate proper vertical positioning
        double shipBottomOffset = shipHeight / 2.0; // Half the ship height

        // Position ship so its center is at portal center Y
        exitPosition.y = portalCenter.y;

        Logger.sendMessage("[Portal Skies] Vertical alignment:", false);
        Logger.sendMessage("[Portal Skies] - Portal center Y: " + portalCenter.y, false);
        Logger.sendMessage("[Portal Skies] - Ship height: " + shipHeight, false);
        Logger.sendMessage("[Portal Skies] - Ship bottom offset: " + shipBottomOffset, false);

        // Apply clearance in exit direction
        switch (exitDirection) {
            case NORTH:
                exitPosition.z -= requiredClearance;
                break;
            case SOUTH:
                exitPosition.z += requiredClearance;
                break;
            case EAST:
                exitPosition.x += requiredClearance;
                break;
            case WEST:
                exitPosition.x -= requiredClearance;
                break;
            default:
                exitPosition.z += requiredClearance;
        }

        Logger.sendMessage("[Portal Skies] Final exit position: " +
                String.format("%.2f, %.2f, %.2f", exitPosition.x, exitPosition.y, exitPosition.z), false);

        return exitPosition;
    }

    private Direction calculateShipApproachDirection(Ship ship, BlockPos sourcePortalPos, Direction.Axis portalAxis, ServerLevel sourceWorld, Direction.Axis sourceAxis) {
        Vector3d shipPos = (Vector3d) ship.getTransform().getPositionInWorld();
        Vector3d portalPos = new Vector3d(sourcePortalPos.getX() + 0.5, sourcePortalPos.getY(), sourcePortalPos.getZ() + 0.5);

        Vector3d approachVector = shipPos.sub(portalPos, new Vector3d());

        Logger.sendMessage("[Portal Skies] Approach vector: " +
                String.format("%.2f, %.2f, %.2f", approachVector.x(), approachVector.y(), approachVector.z()), false);

        Logger.sendMessage("[Portal Skies] Portal axes - Source: " + sourceAxis + " Target: " + portalAxis, false);

        // First, determine the approach direction in the SOURCE portal
        Direction sourceApproachDir;
        if (sourceAxis == Direction.Axis.X) {
            // X-axis portal: approach determined by Z coordinate
            sourceApproachDir = approachVector.z() > 0 ? Direction.NORTH: Direction.SOUTH;
        } else {
            // Z-axis portal: approach determined by X coordinate
            sourceApproachDir = approachVector.x() > 0 ? Direction.WEST : Direction.EAST;
        }

        Logger.sendMessage("[Portal Skies] Source approach direction: " + sourceApproachDir, false);

        // If portals have same axis, keep the same approach direction
        if (portalAxis == sourceAxis) {
            Logger.sendMessage("[Portal Skies] Same axis - keeping direction: " + sourceApproachDir, false);
            return sourceApproachDir;
        }

        // Different axes: map the approach direction
        Direction targetApproachDir;

        if (sourceAxis == Direction.Axis.X && portalAxis == Direction.Axis.Z) {
            // X→Z mapping (Overworld→Nether)
            switch (sourceApproachDir) {
                case NORTH: targetApproachDir = Direction.EAST;  break;  // North in X → West in Z
                case SOUTH: targetApproachDir = Direction.WEST;  break;  // South in X → East in Z
                default:    targetApproachDir = sourceApproachDir; break;
            }
            Logger.sendMessage("[Portal Skies] X→Z axis mapping - " + sourceApproachDir + "→" + targetApproachDir, false);
        } else {
            // Z→X mapping (Nether→Overworld)
            switch (sourceApproachDir) {
                case EAST:  targetApproachDir = Direction.SOUTH; break;  // East in Z → South in X
                case WEST:  targetApproachDir = Direction.NORTH; break;  // West in Z → North in X
                default:    targetApproachDir = sourceApproachDir; break;
            }
            Logger.sendMessage("[Portal Skies] Z→X axis mapping - " + sourceApproachDir + "→" + targetApproachDir, false);
        }

        return targetApproachDir;
    }

    private PortalCheckResult isShipInPortalWithThreshold(Ship ship, ServerLevel level) {
        try {
            var shipAABB = ship.getShipAABB();
            if (shipAABB == null) return new PortalCheckResult(false, BlockPos.ZERO);

            ShipTransform transform = ship.getTransform();

            // Use configurable sampling
            Vector3d[] collisionPoints = getConfigurableCollisionPoints(shipAABB);

            Logger.sendMessage("[Portal Skies] DEBUG: Checking " + collisionPoints.length + " points for ship " + ship.getId(), false);

            int portalBlockCount = 0;

            // Check each collision point in world space
            for (int i = 0; i < collisionPoints.length; i++) {
                Vector3d localPoint = collisionPoints[i];
                Vector3d worldPoint = new Vector3d(localPoint);
                transform.getShipToWorld().transformPosition(worldPoint);

                BlockPos worldPos = new BlockPos(
                        (int) Math.floor(worldPoint.x),
                        (int) Math.floor(worldPoint.y),
                        (int) Math.floor(worldPoint.z)
                );

                // Quick bounds check
                if (!level.isInWorldBounds(worldPos)) continue;

                if (level.getBlockState(worldPos).getBlock() instanceof NetherPortalBlock) {
                    portalBlockCount++;


                    Logger.sendMessage("[Portal Skies] Ship collision point in portal at: " +
                            worldPos.getX() + ", " + worldPos.getY() + ", " + worldPos.getZ(), false);

                    return new PortalCheckResult(true, worldPos);
                }
            }

            Logger.sendMessage("[Portal Skies] DEBUG: No portal blocks found at sampling points", false);
            return new PortalCheckResult(false, BlockPos.ZERO);

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error checking portal with ShipAABB: " + e.getMessage(), false);
            return new PortalCheckResult(false, BlockPos.ZERO);
        }
    }

    private Vector3d[] getConfigurableCollisionPoints(AABBic shipAABB) {
        int minX = shipAABB.minX();
        int minY = shipAABB.minY();
        int minZ = shipAABB.minZ();
        int maxX = shipAABB.maxX();
        int maxY = shipAABB.maxY();
        int maxZ = shipAABB.maxZ();

        List<Vector3d> points = new ArrayList<>();

        double centerX = (minX + maxX) / 2.0;
        double centerY = (minY + maxY) / 2.0;
        double centerZ = (minZ + maxZ) / 2.0;

        int samples = Config.PORTAL_SAMPLES_PER_FACE;
        int skipInterval = Config.PORTAL_FACE_SKIP_INTERVAL;

        // Define all 6 faces of the ship
        Face[] faces = {
                new Face("FRONT", minX, maxX, minY, maxY, maxZ, maxZ),  // Z max
                new Face("BACK", minX, maxX, minY, maxY, minZ, minZ),   // Z min
                new Face("RIGHT", maxX, maxX, minY, maxY, minZ, maxZ),  // X max
                new Face("LEFT", minX, minX, minY, maxY, minZ, maxZ),   // X min
                new Face("TOP", minX, maxX, maxY, maxY, minZ, maxZ),    // Y max
                new Face("BOTTOM", minX, maxX, minY, minY, minZ, maxZ)  // Y min
        };

        // Always add front and back faces if configured
        if (Config.PORTAL_ALWAYS_CHECK_FRONT_BACK) {
            addFacePoints(points, faces[0], samples); // FRONT
            addFacePoints(points, faces[1], samples); // BACK
        }

        // Add other faces based on skip interval
        for (int i = 2; i < faces.length; i++) { // Start from 2 to skip front/back (already added)
            if (skipInterval == 0 || i % (skipInterval + 1) == 0) {
                addFacePoints(points, faces[i], samples);
            }
        }

        // Always add center points
        points.add(new Vector3d(centerX, centerY, maxZ)); // Front center
        points.add(new Vector3d(centerX, centerY, minZ)); // Back center
        points.add(new Vector3d(centerX, centerY, centerZ)); // Ship center

        Logger.sendMessage("[Portal Skies] DEBUG: Generated " + points.size() + " sampling points " +
                "(samples: " + samples + ", skip: " + skipInterval + ")", false);

        return points.toArray(new Vector3d[0]);
    }

    private void addFacePoints(List<Vector3d> points, Face face, int samples) {
        for (int row = 0; row < samples; row++) {
            for (int col = 0; col < samples; col++) {
                double x = face.minX + (face.maxX - face.minX) * (col + 0.5) / samples;
                double y = face.minY + (face.maxY - face.minY) * (row + 0.5) / samples;
                double z = face.minZ + (face.maxZ - face.minZ) * 0.5; // Use face Z coordinate

                points.add(new Vector3d(x, y, z));
            }
        }
    }

    // Helper class to represent a ship face
    private static class Face {
        public final String name;
        public final double minX, maxX, minY, maxY, minZ, maxZ;

        public Face(String name, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
            this.name = name;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    // Fast portal block check with caching
    private boolean isPortalBlockFast(ServerLevel world, BlockPos pos) {
        try {
            // Quick bounds check - prevents trying to access chunks that don't exist
            if (!world.isInWorldBounds(pos)) return false;

            BlockState state = world.getBlockState(pos);
            return state.getBlock() instanceof NetherPortalBlock;
        } catch (Exception e) {
            return false;
        }
    }

    private PortalInfo findAndValidatePortal(PortalInfo sourcePortal,BlockPos sourcePortalPos, ServerLevel sourceWorld, ServerLevel targetWorld, double scale, Ship ship) {
        PortalInfo portalInfo = null;

        BlockPos scaledPos = calculateScaledPosition(sourcePortalPos, scale);

        Logger.sendMessage("[Portal Skies] Source portal at: " +
                sourcePortalPos.getX() + ", " + sourcePortalPos.getY() + ", " + sourcePortalPos.getZ(), true);
        Logger.sendMessage("[Portal Skies] Scaled target position: " +
                scaledPos.getX() + ", " + scaledPos.getY() + ", " + scaledPos.getZ() + " (scale: " + scale + ")", true);

        portalInfo = findExistingPortalVanillaStyle(sourcePortal,sourceWorld, targetWorld, scaledPos, 128, ship);

        if (portalInfo != null) {
            Logger.sendMessage("[Portal Skies] Found existing portal at: " +
                    portalInfo.portalCenter.getX() + ", " + portalInfo.portalCenter.getY() + ", " + portalInfo.portalCenter.getZ() +
                    " Size: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight +
                    " Axis: " + portalInfo.axis, true);
            return portalInfo;
        }

        Logger.sendMessage("[Portal Skies] No suitable portal found in target dimension", true);
        return null;
    }

    private BlockPos calculateScaledPosition(BlockPos sourcePos, double scale) {
        int scaledX = (int) Math.floor(sourcePos.getX() * scale);
        int scaledZ = (int) Math.floor(sourcePos.getZ() * scale);

        int scaledY;
        if (scale == 0.125) {
            scaledY = 64;
        } else {
            scaledY = sourcePos.getY();
        }

        return new BlockPos(scaledX, scaledY, scaledZ);
    }

    private PortalInfo findExistingPortalVanillaStyle(PortalInfo sourcePortal,ServerLevel currentWorld, ServerLevel targetWorld, BlockPos center, int searchRadius, Ship ship) {
        if (targetWorld .dimension().location().toString().equals("minecraft:the_nether")) {
            searchRadius = 24;
        } else {
            searchRadius = 136;
        }

        Logger.sendMessage("[Portal Skies] Vanilla portal search around "
                + center.getX() + ", " + center.getY() + ", " + center.getZ() + " (radius: " + searchRadius + ")", false);

        for (int radius = 0; radius <= searchRadius; radius++) {
            Logger.sendMessage("[Portal Skies] Searching radius " + radius, false);

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }

                    // RESTORED: Original Y-level checking (full world height)
                    for (int y = targetWorld.getMinBuildHeight(); y <= targetWorld.getMaxBuildHeight(); y += 3) {
                        BlockPos checkPos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);

                        if (isPortalBlockFast(targetWorld, checkPos)) {
                            PortalInfo portalInfo = analyzePortalSize(targetWorld, checkPos);
                            if (portalInfo != null && isTargetPortalLargeEnough(portalInfo,sourcePortal)) {
                                double distance = Math.sqrt(center.distSqr(checkPos));
                                Logger.sendMessage("[Portal Skies] Found valid portal at: " + checkPos.getX() + ", " +
                                        checkPos.getY() + ", " + checkPos.getZ() + " (distance: " +
                                        String.format("%.1f", distance) + ", radius: " + radius + ")", false);
                                return portalInfo;
                            }
                        }
                    }
                }
            }
        }

        Logger.sendMessage("[Portal Skies] No valid portals found in vanilla search area", false);
        return null;
    }
    private boolean isTargetPortalLargeEnough(PortalInfo targetPortal, PortalInfo sourcePortal) {
        boolean isLargeEnough = targetPortal.actualWidth >= sourcePortal.requiredWidth &&
                targetPortal.actualHeight >= sourcePortal.requiredHeight;

        Logger.sendMessage("[Portal Skies] Target portal size check:", true);
        Logger.sendMessage("[Portal Skies] - Source: " + sourcePortal.actualWidth + "x" + sourcePortal.actualHeight, true);
        Logger.sendMessage("[Portal Skies] - Target: " + targetPortal.actualWidth + "x" + targetPortal.actualHeight, true);
        Logger.sendMessage("[Portal Skies] - Large enough: " + isLargeEnough, true);

        return isLargeEnough;
    }

    // Use the parallel side as width for portal validation
    private  PortalInfo validatePortalForShip(PortalInfo portalInfo, Ship ship, ServerLevel sourceWorld, BlockPos portalPos) {
        var shipAABB = ship.getShipAABB();

        if (shipAABB == null) {

           portalInfo.isValid=false;
            Logger.sendMessage("[Portal Skies] Could not get ship dimensions", false);
            return portalInfo;

        }

        double aabbWidth = shipAABB.maxX() - shipAABB.minX();
        double aabbHeight = shipAABB.maxY() - shipAABB.minY();
        double aabbLength = shipAABB.maxZ() - shipAABB.minZ();

        // Determine which dimension is parallel to the portal (this becomes the "width" for portal fitting)
        Direction.Axis portalAxis = getPortalAxisAt(sourceWorld, portalPos);
        ShipOrientation orientation = getShipOrientationRelativeToPortal(ship, portalAxis, sourceWorld, portalPos);

        double shipWidthForPortal;
        if (orientation.isWidthParallel) {
            // Ship width (X) is parallel to portal - use width dimension
            shipWidthForPortal = aabbWidth;
            Logger.sendMessage("[Portal Skies] Ship WIDTH parallel to portal, using width: " + shipWidthForPortal, false);
        } else {
            // Ship length (Z) is parallel to portal - use length dimension
            shipWidthForPortal = aabbLength;
            Logger.sendMessage("[Portal Skies] Ship LENGTH parallel to portal, using length as width: " + shipWidthForPortal, false);
        }

        double shipHeight = aabbHeight;

        Logger.sendMessage("[Portal Skies] === SHIP DIMENSION DEBUG ===", false);
        Logger.sendMessage("[Portal Skies] AABB dimensions: " +
                String.format("%.1fx%.1fx%.1f", aabbWidth, aabbHeight, aabbLength), false);
        Logger.sendMessage("[Portal Skies] Portal-fitting width: " + shipWidthForPortal, false);
        Logger.sendMessage("[Portal Skies] Ship orientation: " + orientation.parallelSide + " parallel to portal", false);

        double buffer = 0.2;
        portalInfo.requiredWidth = (int) Math.ceil(shipWidthForPortal + buffer);
        portalInfo.requiredHeight = (int) Math.ceil(shipHeight + buffer);

        boolean isValid = portalInfo.requiredWidth <= portalInfo.actualWidth &&
                portalInfo.requiredHeight <= portalInfo.actualHeight;

        portalInfo.isValid = isValid;

        Logger.sendMessage("[Portal Skies] Portal validation:", false);
        Logger.sendMessage("[Portal Skies] - Required: " + portalInfo.requiredWidth + "x" + portalInfo.requiredHeight, false);
        Logger.sendMessage("[Portal Skies] - Actual: " + portalInfo.actualWidth + "x" + portalInfo.actualHeight, false);
        Logger.sendMessage("[Portal Skies] - Valid: " + isValid, false);

        return portalInfo;
    }

    private boolean isValidPortalBlock(ServerLevel world, BlockPos pos) {
        try {
            return world.getBlockState(pos).getBlock() instanceof NetherPortalBlock;
        } catch (Exception e) {
            return false;
        }
    }

    private PortalInfo analyzePortalSize(ServerLevel world, BlockPos portalBlock) {
        try {
            BlockState state = world.getBlockState(portalBlock);
            Direction.Axis axis = state.getValue(NetherPortalBlock.AXIS);

            PortalInfo info = new PortalInfo();
            info.portalCenter = portalBlock;
            info.axis = axis;

            Logger.sendMessage("[Portal Skies] Portal axis detected: " + axis, false);

            info = measurePortalDimensions(world, portalBlock, axis);

            return info;
        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error analyzing portal: " + e.getMessage(), false);
            return null;
        }
    }

    private PortalInfo measurePortalDimensions(ServerLevel world, BlockPos start, Direction.Axis expectedAxis) {
        PortalInfo info = new PortalInfo();
        info.portalCenter = start;
        info.axis = expectedAxis;

        try {
            Logger.sendMessage("[Portal Skies] Starting portal measurement at: " +
                    start.getX() + "," + start.getY() + "," + start.getZ() + " axis: " + expectedAxis, false);

            if (expectedAxis == Direction.Axis.X) {
                return measureXAxisPortal(world, start);
            } else {
                return measureZAxisPortal(world, start);
            }

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error measuring portal: " + e.getMessage(), false);
            info.isValid = false;
            return info;
        }
    }

    private PortalInfo measureXAxisPortal(ServerLevel world, BlockPos start)  {
        PortalInfo info = new PortalInfo();
        info.axis = Direction.Axis.X;
        info.portalCenter = start;

        try {
            int minX = start.getX();
            int maxX = start.getX();

            BlockPos current = start;
            while (isPortalBlockWithAxis(world, current.west(), Direction.Axis.X)) {
                current = current.west();
                minX = current.getX();
            }

            current = start;
            while (isPortalBlockWithAxis(world, current.east(), Direction.Axis.X)) {
                current = current.east();
                maxX = current.getX();
            }

            int width = maxX - minX + 1;

            int minY = start.getY();
            int maxY = start.getY();

            current = start;
            while (isPortalBlockWithAxis(world, current.below(), Direction.Axis.X)) {
                current = current.below();
                minY = current.getY();
            }

            current = start;
            while (isPortalBlockWithAxis(world, current.above(), Direction.Axis.X)) {
                current = current.above();
                maxY = current.getY();
            }

            int height = maxY - minY + 1;

            info.minX = minX;
            info.maxX = maxX;
            info.minY = minY;
            info.maxY = maxY;
            info.minZ = start.getZ();
            info.maxZ = start.getZ();

            info.actualWidth = width;
            info.actualHeight = height;
            info.isValid = true;

            Logger.sendMessage("[Portal Skies] X-axis portal - Width (X): " + width + " Height (Y): " + height, false);

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error measuring X-axis portal: " + e.getMessage(), false);
            info.isValid = false;
        }

        return info;
    }

    private PortalInfo measureZAxisPortal(ServerLevel world, BlockPos start) {
        PortalInfo info = new PortalInfo();
        info.axis = Direction.Axis.Z;
        info.portalCenter = start;

        try {
            int minZ = start.getZ();
            int maxZ = start.getZ();

            BlockPos current = start;
            while (isPortalBlockWithAxis(world, current.north(), Direction.Axis.Z)) {
                current = current.north();
                minZ = current.getZ();
            }

            current = start;
            while (isPortalBlockWithAxis(world, current.south(), Direction.Axis.Z)) {
                current = current.south();
                maxZ = current.getZ();
            }

            int width = maxZ - minZ + 1;

            int minY = start.getY();
            int maxY = start.getY();

            current = start;
            while (isPortalBlockWithAxis(world, current.below(), Direction.Axis.Z)) {
                current = current.below();
                minY = current.getY();
            }

            current = start;
            while (isPortalBlockWithAxis(world, current.above(), Direction.Axis.Z)) {
                current = current.above();
                maxY = current.getY();
            }

            int height = maxY - minY + 1;

            info.minX = start.getX();
            info.maxX = start.getX();
            info.minY = minY;
            info.maxY = maxY;
            info.minZ = minZ;
            info.maxZ = maxZ;

            info.actualWidth = width;
            info.actualHeight = height;
            info.isValid = true;

            Logger.sendMessage("[Portal Skies] Z-axis portal - Width (Z): " + width + " Height (Y): " + height, false);

        } catch (Exception e) {
            Logger.sendMessage("[Portal Skies] Error measuring Z-axis portal: " + e.getMessage(), false);
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

    // Helper class for ship orientation
    private static class ShipOrientation {
        public final String parallelSide;
        public final double angleToPortal;
        public final boolean isWidthParallel;

        public ShipOrientation(String parallelSide, double angleToPortal, boolean isWidthParallel) {
            this.parallelSide = parallelSide;
            this.angleToPortal = angleToPortal;
            this.isWidthParallel = isWidthParallel;
        }
    }

    private static class PortalCheckResult {
        public final boolean isInPortal;
        public final BlockPos portalCenter;

        public PortalCheckResult(boolean isInPortal, BlockPos portalCenter) {
            this.isInPortal = isInPortal;
            this.portalCenter = portalCenter;
        }
    }

    private static class PortalInfo {
        public BlockPos portalCenter;
        public Direction.Axis axis;
        public int actualWidth;
        public int actualHeight;
        public int requiredWidth;
        public int requiredHeight;
        public boolean isValid = false;

        public int minX, maxX, minY, maxY, minZ, maxZ;
    }

    private static class TeleportPositionResult {
        public BlockPos teleportPos;
        public Vector3d exactTeleportPos;
        public Direction exitDirection;
        public Direction approachDirection;
        public boolean hasEnoughSpace = false;
    }
}