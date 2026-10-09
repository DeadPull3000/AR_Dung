package com.embedded.argame.navigation

import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic floor-plane kinematics controller and autonomous navigation driver.
 *
 * Responsibilities:
 * 1. Executes delta-time based continuous motion on the detected physical floor plane.
 * 2. Pure-pursuit directional steering with angular turn-rate clamping (MAX_TURN_RATE).
 * 3. Waypoint advancement and precise arrival detection.
 * 4. Local ahead-collision verification against TraversalCostGrid.
 * 5. Event-driven dynamic replanning when environment gridVersion changes or path is obstructed.
 * 6. Always preserves goal and replans from CURRENT agent position, never original start.
 * 7. Thread-safe pose snapshot publication for 60 FPS GL rendering and UI HUD.
 */
class AgentController(
    val config: AgentConfig = AgentConfig()
) {
    private val lock = Any()

    // Active navigation state
    var state: AgentState = AgentState.IDLE
        private set

    // Floor-plane metric coordinates [-4.0m, +4.0m]
    var floorX: Float = 0f
        private set
    var floorZ: Float = 0f
        private set

    // World coordinates (ARCore room reference frame)
    var worldX: Float = 0f
        private set
    var worldY: Float = 0f
        private set
    var worldZ: Float = 0f
        private set

    // Discrete grid coordinates [0..79]
    var cellCol: Int = -1
        private set
    var cellRow: Int = -1
        private set

    // Heading orientation on floor plane (radians)
    var headingRadians: Float = 0f
        private set

    // Current scalar travel speed (m/s)
    var speedMps: Float = 0f
        private set

    // Goal coordinates
    var hasGoal: Boolean = false
        private set
    var goalCol: Int = -1
        private set
    var goalRow: Int = -1
        private set
    var goalFloorX: Float = 0f
        private set
    var goalFloorZ: Float = 0f
        private set
    var goalWorldX: Float = 0f
        private set
    var goalWorldY: Float = 0f
        private set
    var goalWorldZ: Float = 0f
        private set

    // Waypoint tracking
    private var waypoints: List<GridWaypoint> = emptyList()
    private var floorWaypointsX = FloatArray(0)
    private var floorWaypointsZ = FloatArray(0)
    var currentWaypointIndex: Int = 0
        private set

    // Dynamic replanning telemetry
    var replansCount: Int = 0
        private set
    var pathGridVersion: Long = 0L
        private set
    private var lastReplanRequestTimeMs: Long = 0L

    // Temporary buffer to avoid allocations during floor-to-world conversion
    private val tempWorldPoint = FloatArray(3)

    /**
     * Listener invoked when the controller needs an A* replan.
     * Passes (currentCol, currentRow, goalCol, goalRow).
     */
    var onReplanRequested: ((fromCol: Int, fromRow: Int, toCol: Int, toRow: Int) -> Unit)? = null

    /**
     * Spawns the agent at the specified discrete grid cell on the floor plane.
     */
    fun spawnAt(
        col: Int,
        row: Int,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?
    ) {
        synchronized(lock) {
            cellCol = col
            cellRow = row

            if (occupancyGrid != null) {
                val (fx, fz) = occupancyGrid.cellToFloorCoord(col, row)
                floorX = fx
                floorZ = fz
            } else {
                floorX = 0f
                floorZ = 0f
            }

            speedMps = 0f
            state = AgentState.IDLE
            waypoints = emptyList()
            floorWaypointsX = FloatArray(0)
            floorWaypointsZ = FloatArray(0)
            currentWaypointIndex = 0

            updateWorldPosition(floorReference)
        }
    }

    /**
     * Ingests a new PathResult from A* pathfinding.
     * Transitions state to NAVIGATING if path is valid, or safe failure state if blocked/no-path.
     */
    fun applyPathResult(
        result: PathResult,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?
    ) {
        synchronized(lock) {
            pathGridVersion = result.gridVersion
            goalCol = result.goalCol
            goalRow = result.goalRow

            if (occupancyGrid != null && goalCol in 0..79 && goalRow in 0..79) {
                val (gfx, gfz) = occupancyGrid.cellToFloorCoord(goalCol, goalRow)
                goalFloorX = gfx
                goalFloorZ = gfz
                hasGoal = true
            }

            when (result.status) {
                PathStatus.SUCCESS -> {
                    // Prefer smoothed waypoints if available, otherwise raw
                    val wps = if (result.smoothedPath.isNotEmpty()) result.smoothedPath else result.rawPath
                    waypoints = wps
                    currentWaypointIndex = 0

                    // Precompute floor waypoint coordinates
                    val count = wps.size
                    floorWaypointsX = FloatArray(count)
                    floorWaypointsZ = FloatArray(count)
                    for (i in 0 until count) {
                        if (occupancyGrid != null) {
                            val (fx, fz) = occupancyGrid.cellToFloorCoord(wps[i].col, wps[i].row)
                            floorWaypointsX[i] = fx
                            floorWaypointsZ[i] = fz
                        }
                    }

                    // Orient heading toward initial waypoint if available
                    if (count > 1) {
                        val dx = floorWaypointsX[1] - floorX
                        val dz = floorWaypointsZ[1] - floorZ
                        if (dx * dx + dz * dz > 0.0001f) {
                            headingRadians = atan2(dz, dx)
                        }
                    }

                    state = AgentState.NAVIGATING
                    speedMps = config.moveSpeedMps
                }

                PathStatus.START_EQUALS_GOAL -> {
                    waypoints = listOf(GridWaypoint(cellCol, cellRow))
                    floorWaypointsX = floatArrayOf(floorX)
                    floorWaypointsZ = floatArrayOf(floorZ)
                    currentWaypointIndex = 0
                    speedMps = 0f
                    state = AgentState.ARRIVED
                }

                PathStatus.START_BLOCKED, PathStatus.GOAL_BLOCKED, PathStatus.START_UNKNOWN, PathStatus.GOAL_UNKNOWN -> {
                    speedMps = 0f
                    state = AgentState.BLOCKED
                }

                PathStatus.NO_PATH, PathStatus.OUT_OF_BOUNDS -> {
                    speedMps = 0f
                    state = AgentState.NO_PATH
                }

                else -> {
                    speedMps = 0f
                    state = AgentState.IDLE
                }
            }
        }
    }

    /**
     * Deterministic simulation update step. Call at ~20-60 Hz from the simulation or frame loop.
     *
     * @param deltaTimeSec Frame delta time in seconds.
     * @param currentGridVersion Latest grid version from OccupancyGrid.
     * @param costGrid TraversalCostGrid for forward obstacle detection.
     * @param occupancyGrid OccupancyGrid for coordinate conversions.
     * @param floorReference FloorReference for 3D world space transformation.
     */
    fun update(
        deltaTimeSec: Float,
        currentGridVersion: Long,
        costGrid: TraversalCostGrid?,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?
    ) {
        synchronized(lock) {
            // Freeze simulation if not in active movement states
            if (state != AgentState.NAVIGATING && state != AgentState.REPLANNING) {
                speedMps = 0f
                return
            }

            // Clamp delta-time to avoid teleporting across frames if app stutters
            val dt = min(max(deltaTimeSec, 0f), config.maxDeltaTimeSec)
            if (dt <= 0f) return

            // 1. Check Goal Arrival
            if (hasGoal) {
                val distToGoal = distance(floorX, floorZ, goalFloorX, goalFloorZ)
                if (distToGoal <= config.arrivalThresholdMeters) {
                    floorX = goalFloorX
                    floorZ = goalFloorZ
                    speedMps = 0f
                    state = AgentState.ARRIVED
                    updateWorldPosition(floorReference)
                    return
                }
            }

            if (waypoints.isEmpty() || floorWaypointsX.isEmpty()) {
                speedMps = 0f
                state = if (hasGoal) AgentState.ARRIVED else AgentState.IDLE
                return
            }

            // 2. Waypoint Advancement
            while (currentWaypointIndex < waypoints.size) {
                val targetWpX = floorWaypointsX[currentWaypointIndex]
                val targetWpZ = floorWaypointsZ[currentWaypointIndex]
                val distToWp = distance(floorX, floorZ, targetWpX, targetWpZ)

                if (distToWp <= config.waypointThresholdMeters && currentWaypointIndex < waypoints.size - 1) {
                    currentWaypointIndex++
                } else {
                    break
                }
            }

            // 3. Directional Steering (Pure Pursuit Angle Clamping)
            val targetX = floorWaypointsX[currentWaypointIndex]
            val targetZ = floorWaypointsZ[currentWaypointIndex]
            val dirX = targetX - floorX
            val dirZ = targetZ - floorZ
            val distToTarget = sqrt(dirX * dirX + dirZ * dirZ)

            if (distToTarget > 0.001f) {
                val desiredHeading = atan2(dirZ, dirX)
                var angleDiff = desiredHeading - headingRadians

                // Normalize angle difference to [-PI, +PI]
                while (angleDiff > Math.PI.toFloat()) angleDiff -= (2.0 * Math.PI).toFloat()
                while (angleDiff < -Math.PI.toFloat()) angleDiff += (2.0 * Math.PI).toFloat()

                // Clamp angular turn rate by MAX_TURN_RATE * dt
                val maxTurn = config.maxTurnRateRadPerSec * dt
                val turn = min(max(angleDiff, -maxTurn), maxTurn)
                headingRadians += turn

                // Keep heading in [-PI, +PI]
                while (headingRadians > Math.PI.toFloat()) headingRadians -= (2.0 * Math.PI).toFloat()
                while (headingRadians < -Math.PI.toFloat()) headingRadians += (2.0 * Math.PI).toFloat()
            }

            // 4. Smooth Linear Speed Modulation around Sharp Turns
            // Scale speed down slightly when facing away from target to ensure smooth pivoting
            val targetHeading = atan2(dirZ, dirX)
            var facingDiff = targetHeading - headingRadians
            while (facingDiff > Math.PI.toFloat()) facingDiff -= (2.0 * Math.PI).toFloat()
            while (facingDiff < -Math.PI.toFloat()) facingDiff += (2.0 * Math.PI).toFloat()
            val turnSpeedFactor = max(0.25f, cos(facingDiff))
            val currentSpeed = config.moveSpeedMps * turnSpeedFactor

            // 5. Projected Forward Step
            val moveStep = currentSpeed * dt
            val nextFloorX = floorX + cos(headingRadians) * moveStep
            val nextFloorZ = floorZ + sin(headingRadians) * moveStep

            // 6. Ahead Obstacle & Boundary Verification
            var isForwardBlocked = false
            if (occupancyGrid != null && costGrid != null) {
                val lookaheadDist = max(moveStep, config.agentRadiusMeters * 0.5f)
                val lookaheadX = floorX + cos(headingRadians) * lookaheadDist
                val lookaheadZ = floorZ + sin(headingRadians) * lookaheadDist

                val nextCell = occupancyGrid.floorToCell(nextFloorX, nextFloorZ)
                val aheadCell = occupancyGrid.floorToCell(lookaheadX, lookaheadZ)

                if (nextCell == null || aheadCell == null) {
                    // Out of grid bounds
                    isForwardBlocked = true
                } else {
                    val nextIdx = occupancyGrid.cellToIndex(nextCell.first, nextCell.second)
                    val aheadIdx = occupancyGrid.cellToIndex(aheadCell.first, aheadCell.second)
                    if (!costGrid.isTraversable(nextIdx) || !costGrid.isTraversable(aheadIdx)) {
                        isForwardBlocked = true
                    }
                }
            }

            // 7. Dynamic Grid Invalidation Check
            // If the navigation environment changed since this path was computed,
            // verify whether the remaining path waypoints are still traversable.
            var isPathInvalidated = false
            if (currentGridVersion != pathGridVersion && occupancyGrid != null && costGrid != null) {
                for (w in currentWaypointIndex until waypoints.size) {
                    val wp = waypoints[w]
                    val idx = occupancyGrid.cellToIndex(wp.col, wp.row)
                    if (!costGrid.isTraversable(idx)) {
                        isPathInvalidated = true
                        break
                    }
                }
            }

            // 8. Handle Blockage or Invalidation -> Trigger Replan
            if (isForwardBlocked || isPathInvalidated) {
                speedMps = 0f
                state = AgentState.REPLANNING
                triggerReplanIfNeeded()
                return
            }

            // 9. Execute Kinematic Movement
            floorX = nextFloorX
            floorZ = nextFloorZ
            speedMps = currentSpeed

            if (occupancyGrid != null) {
                val currentCell = occupancyGrid.floorToCell(floorX, floorZ)
                if (currentCell != null) {
                    cellCol = currentCell.first
                    cellRow = currentCell.second
                }
            }

            updateWorldPosition(floorReference)
        }
    }

    /**
     * Pauses autonomous navigation.
     */
    fun pause() {
        synchronized(lock) {
            if (state.isMoving) {
                state = AgentState.PAUSED
                speedMps = 0f
            }
        }
    }

    /**
     * Resumes autonomous navigation from paused state.
     */
    fun resume() {
        synchronized(lock) {
            if (state == AgentState.PAUSED) {
                state = AgentState.NAVIGATING
                speedMps = config.moveSpeedMps
            }
        }
    }

    /**
     * Resets agent navigation and halts movement.
     */
    fun reset() {
        synchronized(lock) {
            state = AgentState.IDLE
            speedMps = 0f
            waypoints = emptyList()
            floorWaypointsX = FloatArray(0)
            floorWaypointsZ = FloatArray(0)
            currentWaypointIndex = 0
            hasGoal = false
            goalCol = -1
            goalRow = -1
        }
    }

    /**
     * Dispatches replan request from agent's CURRENT position to goal if cooldown has elapsed.
     */
    private fun triggerReplanIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastReplanRequestTimeMs >= config.replanCooldownMs && hasGoal) {
            lastReplanRequestTimeMs = now
            replansCount++
            val curCol = cellCol
            val curRow = cellRow
            val targetCol = goalCol
            val targetRow = goalRow
            onReplanRequested?.invoke(curCol, curRow, targetCol, targetRow)
        }
    }

    /**
     * Manually triggers an explicit replan from current agent cell to goal.
     */
    fun requestManualReplan() {
        synchronized(lock) {
            if (hasGoal && cellCol >= 0 && cellRow >= 0) {
                state = AgentState.REPLANNING
                speedMps = 0f
                lastReplanRequestTimeMs = 0L // Bypass cooldown for explicit user request
                triggerReplanIfNeeded()
            }
        }
    }

    /**
     * Transforms current floor position (floorX, floorZ) to ARCore 3D room coordinates.
     */
    fun updateWorldPosition(floorReference: FloorReference?) {
        if (floorReference != null && floorReference.isTracking) {
            floorReference.floorToWorldPoint(floorX, config.visualElevationMeters, floorZ, tempWorldPoint, 0)
            worldX = tempWorldPoint[0]
            worldY = tempWorldPoint[1]
            worldZ = tempWorldPoint[2]
        }
    }

    /**
     * Produces an immutable, thread-safe snapshot of the agent's current state.
     */
    fun getPoseSnapshot(currentGridVersion: Long = 0L): AgentPose {
        synchronized(lock) {
            val distToGoal = if (hasGoal) distance(floorX, floorZ, goalFloorX, goalFloorZ) else 0f
            return AgentPose(
                worldX = worldX,
                worldY = worldY,
                worldZ = worldZ,
                floorX = floorX,
                floorZ = floorZ,
                cellCol = cellCol,
                cellRow = cellRow,
                headingRadians = headingRadians,
                speedMps = speedMps,
                state = state,
                currentWaypointIndex = currentWaypointIndex,
                totalWaypoints = waypoints.size,
                distanceToGoalMeters = distToGoal,
                replansCount = replansCount,
                pathGridVersion = pathGridVersion,
                currentGridVersion = currentGridVersion
            )
        }
    }

    private fun distance(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }
}
