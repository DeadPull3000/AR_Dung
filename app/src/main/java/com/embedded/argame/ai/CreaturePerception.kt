package com.embedded.argame.ai

import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.UnknownCostPolicy
import com.embedded.argame.perception.CameraIntrinsicsData
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Immutable evaluation snapshot of the player's spatial state relative to the creature.
 */
data class PlayerPerceptionSnapshot(
    val isTrackingValid: Boolean = false,
    val isPlayerInGrid: Boolean = false,
    val playerWorldX: Float = 0f,
    val playerWorldY: Float = 0f,
    val playerWorldZ: Float = 0f,
    val playerFloorX: Float = 0f,
    val playerFloorZ: Float = 0f,
    val playerCellCol: Int = -1,
    val playerCellRow: Int = -1,
    val distanceToCreatureMeters: Float = Float.MAX_VALUE,
    val isLineOfSightClear: Boolean = false,
    val isPlayerDetected: Boolean = false,
    val depthVisibility: DepthVisibilitySnapshot = DepthVisibilitySnapshot(),
    val timestampMs: Long = 0L
)

/**
 * Perception subsystem for reactive creature AI (Milestones 10 & 11).
 *
 * Estimates the player's floor position using ARCore camera pose as player proxy,
 * determines horizontal Euclidean proximity, evaluates geometric line of sight
 * across the 2.5D occupancy grid, and fuses 16-bit metric depth visibility.
 */
class CreaturePerception(
    val config: CreatureAIConfig = CreatureAIConfig()
) {
    // Embedded depth-aware visibility estimator (Milestone 11)
    val depthEstimator: DepthVisibilityEstimator = DepthVisibilityEstimator(config)

    // Reusable temp arrays to avoid heap allocations in frame perception loop
    private val tempFloorPoint = FloatArray(3)
    private val tempWorldPoint = FloatArray(3)

    /**
     * Evaluates the current perception state from camera pose and room references.
     *
     * @param cameraPose ARCore camera pose (player proxy).
     * @param trackingState ARCore camera tracking state.
     * @param floorReference Active FloorReference transform.
     * @param occupancyGrid Active 2.5D OccupancyGrid.
     * @param creatureFloorX Creature's current floor-space X coordinate.
     * @param creatureFloorZ Creature's current floor-space Z coordinate.
     * @param creatureCellCol Creature's current discrete grid column [0..79].
     * @param creatureCellRow Creature's current discrete grid row [0..79].
     * @param depthBuffer Direct ByteBuffer of the 16-bit metric depth image (optional).
     * @param depthWidth Depth image width in pixels.
     * @param depthHeight Depth image height in pixels.
     * @param depthRowStride Depth image row stride in bytes.
     * @param depthPixelStride Depth image pixel stride in bytes.
     * @param intrinsics Camera image intrinsics for pinhole projection.
     * @param currentTimeMs System timestamp in milliseconds.
     */
    fun evaluate(
        cameraPose: Pose?,
        trackingState: TrackingState?,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        creatureFloorX: Float,
        creatureFloorZ: Float,
        creatureCellCol: Int,
        creatureCellRow: Int,
        depthBuffer: ByteBuffer? = null,
        depthWidth: Int = 0,
        depthHeight: Int = 0,
        depthRowStride: Int = 0,
        depthPixelStride: Int = 0,
        intrinsics: CameraIntrinsicsData = CameraIntrinsicsData(),
        currentTimeMs: Long = System.currentTimeMillis()
    ): PlayerPerceptionSnapshot {
        val isTracking = (trackingState == TrackingState.TRACKING) &&
                (floorReference != null && floorReference.isTracking) &&
                (cameraPose != null)

        val camX = cameraPose?.tx() ?: 0f
        val camY = cameraPose?.ty() ?: 0f
        val camZ = cameraPose?.tz() ?: 0f

        return evaluate(
            isTracking = isTracking,
            playerWorldX = camX,
            playerWorldY = camY,
            playerWorldZ = camZ,
            floorReference = floorReference,
            occupancyGrid = occupancyGrid,
            creatureFloorX = creatureFloorX,
            creatureFloorZ = creatureFloorZ,
            creatureCellCol = creatureCellCol,
            creatureCellRow = creatureCellRow,
            cameraPose = cameraPose,
            depthBuffer = depthBuffer,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = depthRowStride,
            depthPixelStride = depthPixelStride,
            intrinsics = intrinsics,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Backward-compatible perception evaluation using explicit coordinates (Milestone 10).
     */
    fun evaluate(
        isTracking: Boolean,
        playerWorldX: Float,
        playerWorldY: Float,
        playerWorldZ: Float,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        creatureFloorX: Float,
        creatureFloorZ: Float,
        creatureCellCol: Int,
        creatureCellRow: Int,
        currentTimeMs: Long = System.currentTimeMillis()
    ): PlayerPerceptionSnapshot {
        return evaluate(
            isTracking = isTracking,
            playerWorldX = playerWorldX,
            playerWorldY = playerWorldY,
            playerWorldZ = playerWorldZ,
            floorReference = floorReference,
            occupancyGrid = occupancyGrid,
            creatureFloorX = creatureFloorX,
            creatureFloorZ = creatureFloorZ,
            creatureCellCol = creatureCellCol,
            creatureCellRow = creatureCellRow,
            cameraPose = null,
            depthBuffer = null,
            depthWidth = 0,
            depthHeight = 0,
            depthRowStride = 0,
            depthPixelStride = 0,
            worldToCamMatrix = null,
            intrinsics = CameraIntrinsicsData(),
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Overloaded evaluation method using explicit coordinates and optional depth (Milestone 11).
     */
    fun evaluate(
        isTracking: Boolean,
        playerWorldX: Float,
        playerWorldY: Float,
        playerWorldZ: Float,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        creatureFloorX: Float,
        creatureFloorZ: Float,
        creatureCellCol: Int,
        creatureCellRow: Int,
        cameraPose: Pose? = null,
        depthBuffer: ByteBuffer? = null,
        depthWidth: Int = 0,
        depthHeight: Int = 0,
        depthRowStride: Int = 0,
        depthPixelStride: Int = 0,
        worldToCamMatrix: FloatArray? = null,
        intrinsics: CameraIntrinsicsData = CameraIntrinsicsData(),
        currentTimeMs: Long = System.currentTimeMillis()
    ): PlayerPerceptionSnapshot {
        if (!isTracking || occupancyGrid == null) {
            val depthSnapshot = if (!isTracking) {
                DepthVisibilitySnapshot(
                    state = VisibilityState.UNKNOWN,
                    reason = VisibilityReason.TRACKING_LOST,
                    timestampMs = currentTimeMs
                )
            } else {
                DepthVisibilitySnapshot(
                    state = VisibilityState.UNKNOWN,
                    reason = VisibilityReason.FALLBACK_GRID_ONLY,
                    timestampMs = currentTimeMs
                )
            }
            return PlayerPerceptionSnapshot(
                isTrackingValid = false,
                depthVisibility = depthSnapshot,
                timestampMs = currentTimeMs
            )
        }

        val playerFloorX: Float
        val playerFloorZ: Float
        if (floorReference != null && floorReference.isTracking) {
            floorReference.worldToFloorPoint(playerWorldX, playerWorldY, playerWorldZ, tempFloorPoint, 0)
            playerFloorX = tempFloorPoint[0]
            playerFloorZ = tempFloorPoint[2]
        } else {
            playerFloorX = playerWorldX
            playerFloorZ = playerWorldZ
        }

        // 2. Query discrete grid cell for player proxy
        val cell = occupancyGrid.floorToCell(playerFloorX, playerFloorZ)
        val inGrid = cell != null
        val pCol = cell?.first ?: -1
        val pRow = cell?.second ?: -1

        // 3. Compute horizontal distance between creature and player proxy
        val dx = playerFloorX - creatureFloorX
        val dz = playerFloorZ - creatureFloorZ
        val distance = sqrt(dx * dx + dz * dz)

        // 4. Evaluate geometric line-of-sight if in grid and within detection radius
        val withinRange = distance <= config.detectionRadiusMeters
        val losClear = if (inGrid && withinRange && creatureCellCol >= 0 && creatureCellRow >= 0) {
            checkLineOfSight(
                startCol = creatureCellCol,
                startRow = creatureCellRow,
                targetCol = pCol,
                targetRow = pRow,
                occupancyGrid = occupancyGrid
            )
        } else {
            false
        }

        // 5. Evaluate Depth-Aware Visibility (Capability A)
        val creatureWorldX: Float
        val creatureWorldY: Float
        val creatureWorldZ: Float
        if (floorReference != null && floorReference.isTracking) {
            // Representative body center is 0.15m above floor contact plane
            floorReference.floorToWorldPoint(creatureFloorX, 0.15f, creatureFloorZ, tempWorldPoint, 0)
            creatureWorldX = tempWorldPoint[0]
            creatureWorldY = tempWorldPoint[1]
            creatureWorldZ = tempWorldPoint[2]
        } else {
            creatureWorldX = creatureFloorX
            creatureWorldY = 0.15f
            creatureWorldZ = creatureFloorZ
        }

        val depthSnapshot = if (cameraPose != null) {
            depthEstimator.evaluate(
                isTracking = isTracking,
                creatureWorldX = creatureWorldX,
                creatureWorldY = creatureWorldY,
                creatureWorldZ = creatureWorldZ,
                cameraPose = cameraPose,
                intrinsics = intrinsics,
                depthBuffer = depthBuffer,
                depthWidth = depthWidth,
                depthHeight = depthHeight,
                depthRowStride = depthRowStride,
                depthPixelStride = depthPixelStride,
                gridLineOfSightClear = losClear,
                gridReason = if (losClear) VisibilityReason.CLEAR else VisibilityReason.GRID_OBSTACLE,
                currentTimeMs = currentTimeMs
            )
        } else {
            depthEstimator.evaluate(
                isTracking = isTracking,
                creatureWorldX = creatureWorldX,
                creatureWorldY = creatureWorldY,
                creatureWorldZ = creatureWorldZ,
                worldToCamMatrix = worldToCamMatrix,
                intrinsics = intrinsics,
                depthBuffer = depthBuffer,
                depthWidth = depthWidth,
                depthHeight = depthHeight,
                depthRowStride = depthRowStride,
                depthPixelStride = depthPixelStride,
                gridLineOfSightClear = losClear,
                gridReason = if (losClear) VisibilityReason.CLEAR else VisibilityReason.GRID_OBSTACLE,
                currentTimeMs = currentTimeMs
            )
        }

        val detected = isTracking && inGrid && withinRange && (depthSnapshot.state == VisibilityState.VISIBLE)

        return PlayerPerceptionSnapshot(
            isTrackingValid = true,
            isPlayerInGrid = inGrid,
            playerWorldX = playerWorldX,
            playerWorldY = playerWorldY,
            playerWorldZ = playerWorldZ,
            playerFloorX = playerFloorX,
            playerFloorZ = playerFloorZ,
            playerCellCol = pCol,
            playerCellRow = pRow,
            distanceToCreatureMeters = distance,
            isLineOfSightClear = losClear,
            isPlayerDetected = detected,
            depthVisibility = depthSnapshot,
            timestampMs = currentTimeMs
        )
    }

    /**
     * Inspects intermediate grid cells along the ray from (startCol, startRow) to (targetCol, targetRow)
     * using Bresenham's integer grid-ray traversal algorithm.
     *
     * Intermediate cells containing PHYSICAL_OBSTACLE block the ray.
     * Unknown cells block the ray if unknown policy is BLOCKED or config specifies unknownCellsBlockLineOfSight.
     * Start and target endpoint cells are deliberately excluded from occlusion.
     */
    fun checkLineOfSight(
        startCol: Int,
        startRow: Int,
        targetCol: Int,
        targetRow: Int,
        occupancyGrid: OccupancyGrid
    ): Boolean {
        // Immediate clear if endpoints coincide
        if (startCol == targetCol && startRow == targetRow) return true

        // Ensure endpoints are in bounds
        if (startCol !in 0 until occupancyGrid.numCellsX || startRow !in 0 until occupancyGrid.numCellsZ) return false
        if (targetCol !in 0 until occupancyGrid.numCellsX || targetRow !in 0 until occupancyGrid.numCellsZ) return false

        val unknownBlocks = config.unknownCellsBlockLineOfSight &&
                (occupancyGrid.traversalCostGrid.unknownPolicy == UnknownCostPolicy.BLOCKED)

        var x0 = startCol
        var y0 = startRow
        val x1 = targetCol
        val y1 = targetRow

        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy

        while (true) {
            if (x0 == x1 && y0 == y1) {
                break
            }

            // Exclude start and target endpoints from occlusion checks
            val isStart = (x0 == startCol && y0 == startRow)
            val isTarget = (x0 == targetCol && y0 == targetRow)

            if (!isStart && !isTarget) {
                val state = occupancyGrid.getNavigationCellState(x0, y0)
                if (state == NavigationCellState.PHYSICAL_OBSTACLE) {
                    return false
                }
                if (state == NavigationCellState.UNKNOWN && unknownBlocks) {
                    return false
                }
            }

            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x0 += sx
            }
            if (e2 < dx) {
                err += dx
                y0 += sy
            }
        }

        return true
    }
}
