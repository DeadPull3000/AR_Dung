package com.embedded.argame.ai

import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-level behavioral controller and state machine for the reactive AR creature (Milestone 10).
 *
 * Coordinates:
 * - Creature Perception (player proxy tracking, proximity, line of sight).
 * - High-level AI state machine:
 *     INITIALIZING -> PATROLLING -> CHASING -> SEARCHING -> RETURNING -> PATROLLING
 *     with BLOCKED and PAUSED recovery modes.
 * - Monotonically increasing request IDs to prevent stale asynchronous path results from corrupting newer states.
 * - Integration with existing AgentController and AStarPathfinder without duplicating navigation or GL logic.
 */
class CreatureAIController(
    val config: CreatureAIConfig = CreatureAIConfig(),
    val perception: CreaturePerception = CreaturePerception(config),
    private val rng: Random = Random(42) // Injectable RNG for deterministic testing
) {
    private val lock = Any()

    // Master enable toggle
    var isEnabled: Boolean = true
        private set

    // Current state machine state
    var state: CreatureAIState = CreatureAIState.INITIALIZING
        private set

    // Active goal destination
    var targetType: TargetType = TargetType.NONE
        private set
    var targetCol: Int = -1
        private set
    var targetRow: Int = -1
        private set
    var targetFloorX: Float = 0f
        private set
    var targetFloorZ: Float = 0f
        private set

    // Monotonically increasing request ID token
    var activeRequestId: Long = 0L
        private set

    // Perception tracking
    var lastPerception: PlayerPerceptionSnapshot = PlayerPerceptionSnapshot()
        private set

    // Player pursuit & search memory
    var lastKnownPlayerCol: Int = -1
        private set
    var lastKnownPlayerRow: Int = -1
        private set
    var lastKnownPlayerFloorX: Float = 0f
        private set
    var lastKnownPlayerFloorZ: Float = 0f
        private set
    private var lastSeenPlayerTimeMs: Long = 0L
    private var lastChasePathRequestTimeMs: Long = 0L
    private var lastChaseTargetCol: Int = -1
    private var lastChaseTargetRow: Int = -1

    // Patrol state
    private var patrolPauseStartTimeMs: Long = 0L
    private var isPausingAtPatrolPoint: Boolean = false

    // Search state
    private var searchStartTimeMs: Long = 0L
    private val searchCandidates = mutableListOf<Pair<Int, Int>>()
    private var currentSearchCandidateIndex: Int = 0
    private var searchCandidateWaitStartTimeMs: Long = 0L
    private var isWaitingAtSearchCandidate: Boolean = false

    // Blocked recovery
    private var lastBlockedTimeMs: Long = 0L

    // Decision rate-limiting
    private var lastDecisionTimeMs: Long = 0L
    private var lastStatusMessage: String = "Initializing creature AI"

    /**
     * Callback dispatched to the navigation subsystem to request an A* path.
     * Arguments: (fromCol, fromRow, toCol, toRow, requestId, targetType)
     */
    var onNavigationGoalRequested: ((fromCol: Int, fromRow: Int, toCol: Int, toRow: Int, requestId: Long, targetType: TargetType) -> Unit)? = null

    /**
     * Callback dispatched to halt the agent's movement.
     */
    var onStopAgentRequested: (() -> Unit)? = null

    /**
     * Toggles the creature AI on or off.
     */
    fun toggleEnabled(): Boolean {
        synchronized(lock) {
            isEnabled = !isEnabled
            if (!isEnabled) {
                state = CreatureAIState.PAUSED
                activeRequestId++ // Invalidate any pending in-flight path results
                onStopAgentRequested?.invoke()
                lastStatusMessage = "Creature AI disabled"
            } else {
                state = CreatureAIState.INITIALIZING
                lastStatusMessage = "Creature AI enabled"
            }
            return isEnabled
        }
    }

    /**
     * Resets creature AI state machine to INITIALIZING and invalidates pending requests.
     */
    fun reset() {
        synchronized(lock) {
            activeRequestId++ // Invalidate pending asynchronous path results
            state = CreatureAIState.INITIALIZING
            targetType = TargetType.NONE
            targetCol = -1
            targetRow = -1
            targetFloorX = 0f
            targetFloorZ = 0f
            lastKnownPlayerCol = -1
            lastKnownPlayerRow = -1
            lastKnownPlayerFloorX = 0f
            lastKnownPlayerFloorZ = 0f
            lastSeenPlayerTimeMs = 0L
            lastChasePathRequestTimeMs = 0L
            isPausingAtPatrolPoint = false
            searchCandidates.clear()
            currentSearchCandidateIndex = 0
            isWaitingAtSearchCandidate = false
            onStopAgentRequested?.invoke()
            lastStatusMessage = "Creature AI reset"
        }
    }

    /**
     * Main simulation update tick for Creature AI. Call at ~100-200 ms interval from frame or simulation loop.
     *
     * @param cameraPose ARCore camera pose proxying player.
     * @param trackingState ARCore camera tracking state.
     * @param floorReference Active FloorReference.
     * @param occupancyGrid Active OccupancyGrid.
     * @param agentPose Current snapshot of the moving agent.
     * @param currentTimeMs Current system timestamp in milliseconds.
     */
    fun update(
        cameraPose: Pose?,
        trackingState: TrackingState?,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        agentPose: AgentPose,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        val isTracking = (trackingState == TrackingState.TRACKING) &&
                (floorReference != null && floorReference.isTracking) &&
                (cameraPose != null)

        val camX = cameraPose?.tx() ?: 0f
        val camY = cameraPose?.ty() ?: 0f
        val camZ = cameraPose?.tz() ?: 0f

        update(
            isTracking = isTracking,
            playerWorldX = camX,
            playerWorldY = camY,
            playerWorldZ = camZ,
            floorReference = floorReference,
            occupancyGrid = occupancyGrid,
            agentPose = agentPose,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Overloaded update tick accepting explicit coordinates and tracking state,
     * enabling fully deterministic unit tests without ARCore Pose mocks.
     */
    fun update(
        isTracking: Boolean,
        playerWorldX: Float,
        playerWorldY: Float,
        playerWorldZ: Float,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        agentPose: AgentPose,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            if (!isEnabled) {
                if (state != CreatureAIState.PAUSED) {
                    state = CreatureAIState.PAUSED
                    onStopAgentRequested?.invoke()
                }
                return
            }

            // 1. Evaluate Player Perception
            val perceptionSnapshot = perception.evaluate(
                isTracking = isTracking,
                playerWorldX = playerWorldX,
                playerWorldY = playerWorldY,
                playerWorldZ = playerWorldZ,
                floorReference = floorReference,
                occupancyGrid = occupancyGrid,
                creatureFloorX = agentPose.floorX,
                creatureFloorZ = agentPose.floorZ,
                creatureCellCol = agentPose.cellCol,
                creatureCellRow = agentPose.cellRow,
                currentTimeMs = currentTimeMs
            )
            lastPerception = perceptionSnapshot

            // 2. Tracking Interruption Check
            if (!perceptionSnapshot.isTrackingValid) {
                if (state != CreatureAIState.PAUSED && state != CreatureAIState.INITIALIZING) {
                    state = CreatureAIState.PAUSED
                    onStopAgentRequested?.invoke()
                    lastStatusMessage = "AR tracking lost or paused"
                }
                return
            }

            // If resuming from PAUSED due to restored tracking:
            if (state == CreatureAIState.PAUSED) {
                state = CreatureAIState.INITIALIZING
            }

            // 3. Rate-Limit AI Decisions
            if (currentTimeMs - lastDecisionTimeMs < config.aiDecisionIntervalMs) {
                return
            }
            lastDecisionTimeMs = currentTimeMs

            if (occupancyGrid == null) {
                state = CreatureAIState.INITIALIZING
                return
            }

            // 4. State Machine Execution
            when (state) {
                CreatureAIState.INITIALIZING -> {
                    handleInitializing(occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.PATROLLING -> {
                    handlePatrolling(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.CHASING -> {
                    handleChasing(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.SEARCHING -> {
                    handleSearching(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.RETURNING -> {
                    handleReturning(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.BLOCKED -> {
                    handleBlocked(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
                }

                CreatureAIState.PAUSED -> {
                    // Handled above
                }
            }
        }
    }

    /**
     * Ingests the result of an asynchronous A* path search dispatched for this AI controller.
     */
    fun onPathResult(
        result: PathResult,
        requestId: Long,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?
    ) {
        synchronized(lock) {
            // Drop stale asynchronous results
            if (requestId != activeRequestId || !isEnabled) {
                return
            }

            if (result.status.isSuccessful) {
                lastStatusMessage = "Path found for $targetType (#$requestId)"
            } else {
                lastStatusMessage = "Path failed for $targetType: ${result.status.name}"
                handlePathFailure(result.status)
            }
        }
    }

    // --- Private State Machine Handlers ---

    private fun handleInitializing(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // Wait until creature has a valid floor cell and occupancy grid has at least some free cells
        if (agentPose.cellCol in 0 until occupancyGrid.numCellsX &&
            agentPose.cellRow in 0 until occupancyGrid.numCellsZ
        ) {
            val costGrid = occupancyGrid.traversalCostGrid
            val curIdx = occupancyGrid.cellToIndex(agentPose.cellCol, agentPose.cellRow)
            if (costGrid.isTraversable(curIdx)) {
                state = CreatureAIState.PATROLLING
                lastStatusMessage = "Initialized: starting patrol"
                requestNewPatrolTarget(occupancyGrid, agentPose)
            }
        }
    }

    private fun handlePatrolling(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // Condition: Player Detected -> Transition to CHASING immediately!
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        // Check if agent reached active patrol target
        val arrivedAtTarget = (targetType == TargetType.PATROL) &&
                (agentPose.state == AgentState.ARRIVED || isNearTarget(agentPose, targetFloorX, targetFloorZ))

        if (arrivedAtTarget) {
            if (!isPausingAtPatrolPoint) {
                isPausingAtPatrolPoint = true
                patrolPauseStartTimeMs = currentTimeMs
                lastStatusMessage = "Patrol target reached; pausing"
            } else {
                val pauseElapsedSec = (currentTimeMs - patrolPauseStartTimeMs) / 1000f
                if (pauseElapsedSec >= config.patrolPauseDurationSec) {
                    isPausingAtPatrolPoint = false
                    requestNewPatrolTarget(occupancyGrid, agentPose)
                }
            }
        } else if (targetType != TargetType.PATROL || agentPose.state == AgentState.IDLE) {
            requestNewPatrolTarget(occupancyGrid, agentPose)
        }
    }

    private fun handleChasing(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        if (perception.isPlayerDetected) {
            // Player visible and detected: refresh last seen memory
            lastSeenPlayerTimeMs = currentTimeMs
            lastKnownPlayerCol = perception.playerCellCol
            lastKnownPlayerRow = perception.playerCellRow
            lastKnownPlayerFloorX = perception.playerFloorX
            lastKnownPlayerFloorZ = perception.playerFloorZ

            // Rate-limit chase path requests
            val timeSinceLastRequest = currentTimeMs - lastChasePathRequestTimeMs
            val cellChanged = (perception.playerCellCol != lastChaseTargetCol || perception.playerCellRow != lastChaseTargetRow)
            val distChanged = distance(targetFloorX, targetFloorZ, perception.playerFloorX, perception.playerFloorZ) >= config.chaseGoalChangeThresholdMeters

            if (timeSinceLastRequest >= config.chaseReplanIntervalMs && (cellChanged || distChanged)) {
                requestChaseGoal(perception, occupancyGrid, agentPose, currentTimeMs)
            }
        } else {
            // Player not detected: check grace period
            val elapsedLostMs = currentTimeMs - lastSeenPlayerTimeMs
            if (elapsedLostMs >= config.lostVisibilityGracePeriodMs) {
                // Grace period expired: transition to SEARCHING
                transitionToSearching(occupancyGrid, agentPose, currentTimeMs)
            } else {
                lastStatusMessage = "Lost sight of player (grace: ${config.lostVisibilityGracePeriodMs - elapsedLostMs}ms)"
            }
        }
    }

    private fun handleSearching(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // Rediscovery check: transition back to CHASING immediately!
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        // Search duration timeout check
        val searchDurationSec = (currentTimeMs - searchStartTimeMs) / 1000f
        if (searchDurationSec >= config.maxSearchDurationSec) {
            transitionToReturning(occupancyGrid, agentPose, currentTimeMs)
            return
        }

        // Check search candidate advancement
        val arrivedAtCandidate = (targetType == TargetType.SEARCH_LAST_KNOWN || targetType == TargetType.SEARCH_CANDIDATE) &&
                (agentPose.state == AgentState.ARRIVED || isNearTarget(agentPose, targetFloorX, targetFloorZ))

        if (arrivedAtCandidate) {
            if (!isWaitingAtSearchCandidate) {
                isWaitingAtSearchCandidate = true
                searchCandidateWaitStartTimeMs = currentTimeMs
                lastStatusMessage = "Searching area (${(config.maxSearchDurationSec - searchDurationSec).toInt()}s left)"
            } else {
                val waitElapsed = (currentTimeMs - searchCandidateWaitStartTimeMs) / 1000f
                if (waitElapsed >= config.searchCandidateWaitSec) {
                    isWaitingAtSearchCandidate = false
                    advanceToNextSearchCandidate(occupancyGrid, agentPose)
                }
            }
        }
    }

    private fun handleReturning(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // Rediscovery check: transition back to CHASING immediately!
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        val arrivedAtReturn = (targetType == TargetType.RETURN_PATROL) &&
                (agentPose.state == AgentState.ARRIVED || isNearTarget(agentPose, targetFloorX, targetFloorZ))

        if (arrivedAtReturn || agentPose.state == AgentState.IDLE) {
            state = CreatureAIState.PATROLLING
            lastStatusMessage = "Returned to patrol area"
            requestNewPatrolTarget(occupancyGrid, agentPose)
        }
    }

    private fun handleBlocked(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // If player spotted while blocked, attempt pursuit
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        // Wait for blocked cooldown before retrying safe patrol
        if (currentTimeMs - lastBlockedTimeMs >= config.blockedCooldownMs) {
            state = CreatureAIState.PATROLLING
            lastStatusMessage = "Recovering from blocked state"
            requestNewPatrolTarget(occupancyGrid, agentPose)
        }
    }

    private fun handlePathFailure(status: PathStatus) {
        when (state) {
            CreatureAIState.CHASING -> {
                // If direct path to player failed (e.g. player in corner), remain in chase and wait for movement
                lastStatusMessage = "Chase path blocked: ${status.name}"
            }
            CreatureAIState.SEARCHING -> {
                // Skip blocked candidate
                currentSearchCandidateIndex++
            }
            CreatureAIState.PATROLLING, CreatureAIState.RETURNING -> {
                state = CreatureAIState.BLOCKED
                lastBlockedTimeMs = System.currentTimeMillis()
                lastStatusMessage = "Navigation blocked: entering recovery"
                onStopAgentRequested?.invoke()
            }
            else -> {}
        }
    }

    // --- State Transitions ---

    private fun transitionToChasing(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        state = CreatureAIState.CHASING
        lastSeenPlayerTimeMs = currentTimeMs
        lastKnownPlayerCol = perception.playerCellCol
        lastKnownPlayerRow = perception.playerCellRow
        lastKnownPlayerFloorX = perception.playerFloorX
        lastKnownPlayerFloorZ = perception.playerFloorZ
        isPausingAtPatrolPoint = false
        isWaitingAtSearchCandidate = false
        searchCandidates.clear()
        lastStatusMessage = "PLAYER DETECTED! Initiating pursuit"
        requestChaseGoal(perception, occupancyGrid, agentPose, currentTimeMs)
    }

    private fun transitionToSearching(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        state = CreatureAIState.SEARCHING
        searchStartTimeMs = currentTimeMs
        isWaitingAtSearchCandidate = false
        lastStatusMessage = "LOST SIGHT: Searching last known position"

        // Generate bounded search candidates around last known location
        searchCandidates.clear()
        if (lastKnownPlayerCol in 0 until occupancyGrid.numCellsX &&
            lastKnownPlayerRow in 0 until occupancyGrid.numCellsZ
        ) {
            // First candidate is the last known position itself
            searchCandidates.add(Pair(lastKnownPlayerCol, lastKnownPlayerRow))

            // Sample up to (maxSearchCandidates - 1) traversable neighbor candidates
            val radiusCells = (config.searchRadiusMeters / occupancyGrid.cellSizeMeters).toInt().coerceAtLeast(1)
            val offsets = arrayOf(
                Pair(radiusCells, 0),
                Pair(-radiusCells, 0),
                Pair(0, radiusCells),
                Pair(0, -radiusCells),
                Pair((radiusCells * 0.7f).toInt(), (radiusCells * 0.7f).toInt()),
                Pair(-(radiusCells * 0.7f).toInt(), -(radiusCells * 0.7f).toInt())
            )

            val costGrid = occupancyGrid.traversalCostGrid
            for (offset in offsets) {
                if (searchCandidates.size >= config.maxSearchCandidates) break
                val c = lastKnownPlayerCol + offset.first
                val r = lastKnownPlayerRow + offset.second
                if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                    val idx = occupancyGrid.cellToIndex(c, r)
                    if (costGrid.isTraversable(idx)) {
                        searchCandidates.add(Pair(c, r))
                    }
                }
            }
        }

        currentSearchCandidateIndex = 0
        if (searchCandidates.isNotEmpty()) {
            dispatchSearchCandidate(searchCandidates[0], TargetType.SEARCH_LAST_KNOWN, occupancyGrid, agentPose)
        } else {
            transitionToReturning(occupancyGrid, agentPose, currentTimeMs)
        }
    }

    private fun advanceToNextSearchCandidate(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose
    ) {
        currentSearchCandidateIndex++
        if (currentSearchCandidateIndex < searchCandidates.size) {
            val candidate = searchCandidates[currentSearchCandidateIndex]
            dispatchSearchCandidate(candidate, TargetType.SEARCH_CANDIDATE, occupancyGrid, agentPose)
        } else {
            // All search candidates visited: transition to returning
            transitionToReturning(occupancyGrid, agentPose, System.currentTimeMillis())
        }
    }

    private fun dispatchSearchCandidate(
        candidate: Pair<Int, Int>,
        type: TargetType,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose
    ) {
        val (c, r) = candidate
        val (fx, fz) = occupancyGrid.cellToFloorCoord(c, r)
        targetType = type
        targetCol = c
        targetRow = r
        targetFloorX = fx
        targetFloorZ = fz

        activeRequestId++
        val reqId = activeRequestId
        onNavigationGoalRequested?.invoke(agentPose.cellCol, agentPose.cellRow, c, r, reqId, type)
    }

    private fun transitionToReturning(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        state = CreatureAIState.RETURNING
        lastStatusMessage = "Search expired: returning to patrol"
        requestNewPatrolTarget(occupancyGrid, agentPose, TargetType.RETURN_PATROL)
    }

    // --- Goal Request Dispatchers ---

    private fun requestChaseGoal(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        lastChasePathRequestTimeMs = currentTimeMs
        lastChaseTargetCol = perception.playerCellCol
        lastChaseTargetRow = perception.playerCellRow

        // Find best approach cell so creature doesn't path directly into occupied player cell
        val approachCell = findBestApproachCell(
            targetCol = perception.playerCellCol,
            targetRow = perception.playerCellRow,
            creatureCol = agentPose.cellCol,
            creatureRow = agentPose.cellRow,
            occupancyGrid = occupancyGrid
        )

        val destCol = approachCell.first
        val destRow = approachCell.second
        val (fx, fz) = occupancyGrid.cellToFloorCoord(destCol, destRow)

        targetType = TargetType.CHASE_PLAYER
        targetCol = destCol
        targetRow = destRow
        targetFloorX = fx
        targetFloorZ = fz

        activeRequestId++
        val reqId = activeRequestId
        onNavigationGoalRequested?.invoke(agentPose.cellCol, agentPose.cellRow, destCol, destRow, reqId, TargetType.CHASE_PLAYER)
    }

    private fun requestNewPatrolTarget(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        targetTypeToUse: TargetType = TargetType.PATROL
    ) {
        val candidate = selectPatrolDestination(occupancyGrid, agentPose)
        if (candidate != null) {
            val (c, r) = candidate
            val (fx, fz) = occupancyGrid.cellToFloorCoord(c, r)
            targetType = targetTypeToUse
            targetCol = c
            targetRow = r
            targetFloorX = fx
            targetFloorZ = fz

            activeRequestId++
            val reqId = activeRequestId
            onNavigationGoalRequested?.invoke(agentPose.cellCol, agentPose.cellRow, c, r, reqId, targetTypeToUse)
        } else {
            state = CreatureAIState.BLOCKED
            lastBlockedTimeMs = System.currentTimeMillis()
            lastStatusMessage = "No reachable patrol target found"
        }
    }

    // --- Target Selection Helpers ---

    /**
     * Selects a valid, reachable patrol destination separated from the creature by at least
     * [minPatrolDistanceMeters] and at most [maxPatrolDistanceMeters].
     */
    fun selectPatrolDestination(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose
    ): Pair<Int, Int>? {
        val costGrid = occupancyGrid.traversalCostGrid
        val curCol = agentPose.cellCol
        val curRow = agentPose.cellRow
        val minCells = (config.minPatrolDistanceMeters / occupancyGrid.cellSizeMeters).toInt()
        val maxCells = (config.maxPatrolDistanceMeters / occupancyGrid.cellSizeMeters).toInt()

        // 1. Random annular sampling with bounded attempts
        for (i in 0 until config.maxPatrolSampleAttempts) {
            val angle = rng.nextFloat() * 2f * Math.PI.toFloat()
            val distCells = minCells + rng.nextInt((maxCells - minCells).coerceAtLeast(1))
            val c = curCol + (cos(angle) * distCells).toInt()
            val r = curRow + (sin(angle) * distCells).toInt()

            if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                val idx = occupancyGrid.cellToIndex(c, r)
                if (costGrid.isTraversable(idx)) {
                    val state = occupancyGrid.getNavigationCellState(c, r)
                    if (state == NavigationCellState.FREE) {
                        return Pair(c, r)
                    }
                }
            }
        }

        // 2. Deterministic concentric fallback scan if sampling yielded no cell
        for (dist in minCells..maxCells) {
            for (dc in -dist..dist) {
                for (dr in -dist..dist) {
                    val c = curCol + dc
                    val r = curRow + dr
                    if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                        val d = sqrt((dc * dc + dr * dr).toFloat())
                        if (d >= minCells && d <= maxCells) {
                            val idx = occupancyGrid.cellToIndex(c, r)
                            if (costGrid.isTraversable(idx)) {
                                val state = occupancyGrid.getNavigationCellState(c, r)
                                if (state == NavigationCellState.FREE) {
                                    return Pair(c, r)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Fallback scan for constrained mapped spaces (down to 0.4m)
        val minCellsFallback = ((config.minPatrolDistanceMeters * 0.5f) / occupancyGrid.cellSizeMeters).toInt().coerceAtLeast(2)
        for (dist in minCellsFallback until minCells) {
            for (dc in -dist..dist) {
                for (dr in -dist..dist) {
                    val c = curCol + dc
                    val r = curRow + dr
                    if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                        val d = sqrt((dc * dc + dr * dr).toFloat())
                        if (d >= minCellsFallback && d < minCells) {
                            val idx = occupancyGrid.cellToIndex(c, r)
                            if (costGrid.isTraversable(idx)) {
                                val state = occupancyGrid.getNavigationCellState(c, r)
                                if (state == NavigationCellState.FREE) {
                                    return Pair(c, r)
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Finds the nearest traversable approach cell to the player if the player's direct cell
     * is blocked or if maintaining [approachPlayerDistanceMeters] standoff is desired.
     */
    private fun findBestApproachCell(
        targetCol: Int,
        targetRow: Int,
        creatureCol: Int,
        creatureRow: Int,
        occupancyGrid: OccupancyGrid
    ): Pair<Int, Int> {
        val costGrid = occupancyGrid.traversalCostGrid

        // If target cell is valid and traversable, use it directly
        if (targetCol in 0 until occupancyGrid.numCellsX && targetRow in 0 until occupancyGrid.numCellsZ) {
            val idx = occupancyGrid.cellToIndex(targetCol, targetRow)
            if (costGrid.isTraversable(idx)) {
                return Pair(targetCol, targetRow)
            }
        }

        // Target cell blocked or out of bounds: scan 8-connected neighbors ordered by proximity to creature
        var bestCell: Pair<Int, Int>? = null
        var bestDist = Float.MAX_VALUE

        val deltas = arrayOf(
            Pair(0, 1), Pair(0, -1), Pair(1, 0), Pair(-1, 0),
            Pair(1, 1), Pair(-1, 1), Pair(1, -1), Pair(-1, -1),
            Pair(0, 2), Pair(0, -2), Pair(2, 0), Pair(-2, 0)
        )

        for (d in deltas) {
            val c = targetCol + d.first
            val r = targetRow + d.second
            if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                val idx = occupancyGrid.cellToIndex(c, r)
                if (costGrid.isTraversable(idx)) {
                    val dist = distance(c.toFloat(), r.toFloat(), creatureCol.toFloat(), creatureRow.toFloat())
                    if (dist < bestDist) {
                        bestDist = dist
                        bestCell = Pair(c, r)
                    }
                }
            }
        }

        return bestCell ?: Pair(targetCol, targetRow)
    }

    private fun isNearTarget(agentPose: AgentPose, targetX: Float, targetZ: Float): Boolean {
        val d = distance(agentPose.floorX, agentPose.floorZ, targetX, targetZ)
        return d <= config.approachPlayerDistanceMeters
    }

    private fun distance(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }

    /**
     * Produces an immutable, thread-safe snapshot of the creature AI state for UI diagnostics and GL rendering.
     */
    fun getSnapshot(): CreatureAISnapshot {
        synchronized(lock) {
            val searchTimeRem = if (state == CreatureAIState.SEARCHING) {
                (config.maxSearchDurationSec - (System.currentTimeMillis() - searchStartTimeMs) / 1000f).coerceAtLeast(0f)
            } else 0f

            return CreatureAISnapshot(
                state = state,
                isEnabled = isEnabled,
                targetType = targetType,
                targetCol = targetCol,
                targetRow = targetRow,
                targetFloorX = targetFloorX,
                targetFloorZ = targetFloorZ,
                isPlayerDetected = lastPerception.isPlayerDetected,
                isLineOfSightClear = lastPerception.isLineOfSightClear,
                playerDistanceMeters = lastPerception.distanceToCreatureMeters,
                playerCellCol = lastPerception.playerCellCol,
                playerCellRow = lastPerception.playerCellRow,
                lastKnownPlayerCol = lastKnownPlayerCol,
                lastKnownPlayerRow = lastKnownPlayerRow,
                lastKnownPlayerFloorX = lastKnownPlayerFloorX,
                lastKnownPlayerFloorZ = lastKnownPlayerFloorZ,
                searchTimeRemainingSec = searchTimeRem,
                activeRequestId = activeRequestId,
                lastStatusMessage = lastStatusMessage,
                timestampMs = System.currentTimeMillis()
            )
        }
    }
}
