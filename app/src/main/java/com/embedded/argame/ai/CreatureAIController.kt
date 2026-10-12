package com.embedded.argame.ai

import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import com.embedded.argame.perception.CameraIntrinsicsData
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
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

    // Milestone 13 Subsystems
    val suspicionModel: SuspicionModel = SuspicionModel(config.suspicionConfig)
    val noiseEventManager: NoiseEventManager = NoiseEventManager()
    val difficultyController: AdaptiveDifficultyController = AdaptiveDifficultyController(config.difficultyConfig)

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
    var frozenSearchCol: Int = -1
        private set
    var frozenSearchRow: Int = -1
        private set
    var frozenSearchFloorX: Float = 0f
        private set
    var frozenSearchFloorZ: Float = 0f
        private set
    val visitedSearchCells = mutableSetOf<Pair<Int, Int>>()

    // Blocked recovery
    private var lastBlockedTimeMs: Long = 0L

    // Investigation state (Milestone 13)
    var activeNoiseTarget: NoiseEvent? = null
        private set
    private var isPausingAtInvestigationPoint: Boolean = false
    private var investigationPauseStartTimeMs: Long = 0L
    private var lastInvestigationTargetSwitchTimeMs: Long = 0L
    private var lastInvestigatedNoiseType: NoiseEventType? = null
    private var noiseFeedbackMessage: String = ""
    private var lastUpdateTimeMs: Long = 0L

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
            frozenSearchCol = -1
            frozenSearchRow = -1
            frozenSearchFloorX = 0f
            frozenSearchFloorZ = 0f
            visitedSearchCells.clear()
            lastSeenPlayerTimeMs = 0L
            lastChasePathRequestTimeMs = 0L
            isPausingAtPatrolPoint = false
            searchCandidates.clear()
            currentSearchCandidateIndex = 0
            isWaitingAtSearchCandidate = false
            activeNoiseTarget = null
            isPausingAtInvestigationPoint = false
            investigationPauseStartTimeMs = 0L
            lastInvestigationTargetSwitchTimeMs = 0L
            lastInvestigatedNoiseType = null
            noiseFeedbackMessage = ""
            lastUpdateTimeMs = 0L
            suspicionModel.reset()
            noiseEventManager.clear()
            difficultyController.reset()
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
     * @param depthBuffer Direct ByteBuffer of the 16-bit metric depth image (optional).
     * @param depthWidth Depth image width in pixels.
     * @param depthHeight Depth image height in pixels.
     * @param depthRowStride Depth image row stride in bytes.
     * @param depthPixelStride Depth image pixel stride in bytes.
     * @param intrinsics Camera image intrinsics for pinhole projection.
     * @param currentTimeMs Current system timestamp in milliseconds.
     */
    fun update(
        cameraPose: Pose?,
        trackingState: TrackingState?,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        agentPose: AgentPose,
        depthBuffer: ByteBuffer? = null,
        depthWidth: Int = 0,
        depthHeight: Int = 0,
        depthRowStride: Int = 0,
        depthPixelStride: Int = 0,
        intrinsics: CameraIntrinsicsData = CameraIntrinsicsData(),
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
            cameraPose = cameraPose,
            depthBuffer = depthBuffer,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = depthRowStride,
            depthPixelStride = depthPixelStride,
            worldToCamMatrix = null,
            intrinsics = intrinsics,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Backward-compatible update tick accepting explicit coordinates and tracking state (Milestone 10).
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
        update(
            isTracking = isTracking,
            playerWorldX = playerWorldX,
            playerWorldY = playerWorldY,
            playerWorldZ = playerWorldZ,
            floorReference = floorReference,
            occupancyGrid = occupancyGrid,
            agentPose = agentPose,
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
     * Overloaded update tick accepting explicit coordinates, depth buffer, and camera state (Milestone 11).
     */
    fun update(
        isTracking: Boolean,
        playerWorldX: Float,
        playerWorldY: Float,
        playerWorldZ: Float,
        floorReference: FloorReference?,
        occupancyGrid: OccupancyGrid?,
        agentPose: AgentPose,
        cameraPose: Pose? = null,
        depthBuffer: ByteBuffer? = null,
        depthWidth: Int = 0,
        depthHeight: Int = 0,
        depthRowStride: Int = 0,
        depthPixelStride: Int = 0,
        worldToCamMatrix: FloatArray? = null,
        intrinsics: CameraIntrinsicsData = CameraIntrinsicsData(),
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

            // 1. Evaluate Player Perception (with 2.5D grid LOS + 16-bit metric depth fusion)
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
                cameraPose = cameraPose,
                depthBuffer = depthBuffer,
                depthWidth = depthWidth,
                depthHeight = depthHeight,
                depthRowStride = depthRowStride,
                depthPixelStride = depthPixelStride,
                worldToCamMatrix = worldToCamMatrix,
                intrinsics = intrinsics,
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

            // Milestone 13: Continuous time progression for suspicion, noise events, and difficulty
            val dtMs = if (lastUpdateTimeMs > 0L && currentTimeMs > lastUpdateTimeMs) {
                (currentTimeMs - lastUpdateTimeMs).coerceAtMost(500L)
            } else 0L
            lastUpdateTimeMs = currentTimeMs

            val adaptiveParams = difficultyController.getActiveParameters()

            // Suspicion decay & visual tracking update
            suspicionModel.update(
                currentTimeMs = currentTimeMs,
                dtMs = dtMs,
                isVisualConfirmed = perceptionSnapshot.isPlayerDetected,
                isTrackingValid = perceptionSnapshot.isTrackingValid,
                decayMultiplier = adaptiveParams.suspicionDecayMultiplier
            )

            // Adaptive difficulty evaluation (cooldown & hysteresis guarded)
            difficultyController.evaluate(
                currentTimeMs = currentTimeMs,
                missionElapsedSec = 0f,
                isPausedOrTerminal = (state == CreatureAIState.PAUSED)
            )

            // Prune expired noise events
            noiseEventManager.pruneExpired(currentTimeMs)

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

                CreatureAIState.INVESTIGATING -> {
                    handleInvestigating(perceptionSnapshot, occupancyGrid, agentPose, currentTimeMs)
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
    @Suppress("UNUSED_PARAMETER")
    fun onPathResult(
        result: PathResult,
        requestId: Long,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?,
        currentTimeMs: Long = System.currentTimeMillis()
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
                handlePathFailure(result.status, currentTimeMs)
            }
        }
    }

    // --- Private State Machine Handlers ---

    @Suppress("UNUSED_PARAMETER")
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
        // Condition 1: Player Detected -> Transition to CHASING immediately!
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        // Condition 2: Noise investigation check (Milestone 13)
        val noiseTarget = selectNoiseTarget(agentPose, occupancyGrid, currentTimeMs)
        if (noiseTarget != null) {
            val suspicion = suspicionModel.getSnapshot(currentTimeMs)
            // Trigger investigation if suspicion is above threshold or noise event is salient
            if (suspicion.level >= config.suspicionConfig.moderateThreshold || noiseTarget.initialIntensity >= 0.35f) {
                transitionToInvestigating(noiseTarget, occupancyGrid, agentPose, currentTimeMs)
                return
            }
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
                val adaptiveParams = difficultyController.getActiveParameters()
                val adjustedPauseSec: Float = config.patrolPauseDurationSec * adaptiveParams.patrolPauseMultiplier
                val pauseElapsedSec: Float = (currentTimeMs - patrolPauseStartTimeMs) / 1000f
                if (pauseElapsedSec >= adjustedPauseSec) {
                    isPausingAtPatrolPoint = false
                    requestNewPatrolTarget(occupancyGrid, agentPose)
                }
            }
        } else if (targetType != TargetType.PATROL || agentPose.state == AgentState.IDLE) {
            requestNewPatrolTarget(occupancyGrid, agentPose)
        }
    }

    private fun handleInvestigating(
        perception: PlayerPerceptionSnapshot,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        // Priority 1: Direct Visual Detection -> Transition to CHASING immediately!
        if (perception.isPlayerDetected) {
            transitionToChasing(perception, occupancyGrid, agentPose, currentTimeMs)
            return
        }

        val currentTarget = activeNoiseTarget
        if (currentTarget == null) {
            // No active noise target: check for another target or return to patrol
            val nextTarget = selectNoiseTarget(agentPose, occupancyGrid, currentTimeMs)
            if (nextTarget != null) {
                transitionToInvestigating(nextTarget, occupancyGrid, agentPose, currentTimeMs)
            } else {
                state = CreatureAIState.PATROLLING
                lastStatusMessage = "Investigation complete: returning to patrol"
                requestNewPatrolTarget(occupancyGrid, agentPose)
            }
            return
        }

        // Priority 2: Preemption by stronger / newer noise event (rate-limited)
        if (currentTimeMs - lastInvestigationTargetSwitchTimeMs >= config.minInvestigateTargetSwitchIntervalMs) {
            val challenger = selectNoiseTarget(agentPose, occupancyGrid, currentTimeMs)
            if (challenger != null && challenger.id != currentTarget.id) {
                val challengerScore = challenger.getPerceivedIntensity(agentPose.floorX, agentPose.floorZ, currentTimeMs)
                val currentScore = currentTarget.getPerceivedIntensity(agentPose.floorX, agentPose.floorZ, currentTimeMs)
                if (challengerScore > currentScore * 1.3f) {
                    lastInvestigationTargetSwitchTimeMs = currentTimeMs
                    transitionToInvestigating(challenger, occupancyGrid, agentPose, currentTimeMs)
                    return
                }
            }
        }

        // Priority 3: Arrival and on-site investigation
        val arrivedAtTarget = (targetType == TargetType.INVESTIGATE_NOISE) &&
                (agentPose.state == AgentState.ARRIVED || isNearTarget(agentPose, targetFloorX, targetFloorZ, config.investigationArrivalRadiusMeters))

        if (arrivedAtTarget) {
            if (!isPausingAtInvestigationPoint) {
                isPausingAtInvestigationPoint = true
                investigationPauseStartTimeMs = currentTimeMs
                lastStatusMessage = "Investigating disturbance at (${targetCol}, ${targetRow})"
            } else {
                val adaptiveParams = difficultyController.getActiveParameters()
                val adjustedPauseSec: Float = 1.5f / adaptiveParams.investigationUrgencyMultiplier
                val elapsedSec: Float = (currentTimeMs - investigationPauseStartTimeMs) / 1000f

                if (elapsedSec >= adjustedPauseSec) {
                    isPausingAtInvestigationPoint = false
                    noiseEventManager.markInvestigated(currentTarget.id)
                    suspicionModel.addEvidence(
                        type = EvidenceType.INVESTIGATION_CLEARED,
                        strength = 1.0f,
                        floorX = agentPose.floorX,
                        floorZ = agentPose.floorZ,
                        currentTimeMs = currentTimeMs
                    )
                    activeNoiseTarget = null

                    val nextTarget = selectNoiseTarget(agentPose, occupancyGrid, currentTimeMs)
                    if (nextTarget != null) {
                        transitionToInvestigating(nextTarget, occupancyGrid, agentPose, currentTimeMs)
                    } else {
                        state = CreatureAIState.PATROLLING
                        lastStatusMessage = "Investigation cleared: resuming patrol"
                        requestNewPatrolTarget(occupancyGrid, agentPose)
                    }
                }
            }
        } else if (targetType != TargetType.INVESTIGATE_NOISE || agentPose.state == AgentState.IDLE) {
            requestInvestigationPath(currentTarget, occupancyGrid, agentPose)
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

        // Search duration timeout check (scaled by adaptive difficulty tier)
        val adaptiveParams = difficultyController.getActiveParameters()
        val adjustedMaxSearchDurationSec: Float = config.maxSearchDurationSec * adaptiveParams.searchDurationMultiplier
        val searchDurationSec: Float = (currentTimeMs - searchStartTimeMs) / 1000f
        if (searchDurationSec >= adjustedMaxSearchDurationSec) {
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
                lastStatusMessage = "Searching area (${(adjustedMaxSearchDurationSec - searchDurationSec).toInt()}s left)"
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

    private fun handlePathFailure(
        status: PathStatus,
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        when (state) {
            CreatureAIState.CHASING -> {
                // If direct path to player failed (e.g. player in corner), remain in chase and wait for movement
                lastStatusMessage = "Chase path blocked: ${status.name}"
            }
            CreatureAIState.SEARCHING -> {
                // Skip blocked candidate
                currentSearchCandidateIndex++
            }
            CreatureAIState.INVESTIGATING -> {
                // Mark blocked noise target as investigated so we don't repeat-loop on it
                activeNoiseTarget?.let { noiseEventManager.markInvestigated(it.id) }
                activeNoiseTarget = null
                state = CreatureAIState.BLOCKED
                lastBlockedTimeMs = currentTimeMs
                lastStatusMessage = "Investigation path blocked: entering recovery"
                onStopAgentRequested?.invoke()
            }
            CreatureAIState.PATROLLING, CreatureAIState.RETURNING -> {
                state = CreatureAIState.BLOCKED
                lastBlockedTimeMs = currentTimeMs
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
        isPausingAtInvestigationPoint = false
        isWaitingAtSearchCandidate = false
        searchCandidates.clear()
        activeNoiseTarget = null

        // Milestone 13: Confirmed player observation adds maximum suspicion evidence
        suspicionModel.addEvidence(
            type = EvidenceType.VISUAL_CONFIRMED,
            strength = 1.0f,
            floorX = perception.playerFloorX,
            floorZ = perception.playerFloorZ,
            currentTimeMs = currentTimeMs
        )

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
        isPausingAtInvestigationPoint = false
        activeNoiseTarget = null

        // Milestone 13: Player escaped pursuit -> signal adaptive difficulty
        difficultyController.recordPlayerEscape()

        // Suspicion retains last-known observation memory
        suspicionModel.addEvidence(
            type = EvidenceType.VISUAL_LAST_KNOWN,
            strength = 0.8f,
            floorX = lastKnownPlayerFloorX,
            floorZ = lastKnownPlayerFloorZ,
            currentTimeMs = currentTimeMs
        )

        lastStatusMessage = "LOST SIGHT: Searching cover around last known position"

        // Freeze last known player position (Milestone 11, Section 5.1)
        frozenSearchCol = lastKnownPlayerCol
        frozenSearchRow = lastKnownPlayerRow
        frozenSearchFloorX = lastKnownPlayerFloorX
        frozenSearchFloorZ = lastKnownPlayerFloorZ

        // Generate bounded cover-aware search candidates
        searchCandidates.clear()
        visitedSearchCells.clear()

        if (frozenSearchCol in 0 until occupancyGrid.numCellsX &&
            frozenSearchRow in 0 until occupancyGrid.numCellsZ
        ) {
            val generated = generateCoverAwareSearchCandidates(frozenSearchCol, frozenSearchRow, occupancyGrid)
            searchCandidates.addAll(generated)
        }

        currentSearchCandidateIndex = 0
        if (searchCandidates.isNotEmpty()) {
            val first = searchCandidates[0]
            visitedSearchCells.add(first)
            dispatchSearchCandidate(first, TargetType.SEARCH_LAST_KNOWN, occupancyGrid, agentPose)
        } else {
            transitionToReturning(occupancyGrid, agentPose, currentTimeMs)
        }
    }

    private fun advanceToNextSearchCandidate(
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose
    ) {
        currentSearchCandidateIndex++
        while (currentSearchCandidateIndex < searchCandidates.size &&
            visitedSearchCells.contains(searchCandidates[currentSearchCandidateIndex])
        ) {
            currentSearchCandidateIndex++
        }

        if (currentSearchCandidateIndex < searchCandidates.size) {
            val candidate = searchCandidates[currentSearchCandidateIndex]
            visitedSearchCells.add(candidate)
            dispatchSearchCandidate(candidate, TargetType.SEARCH_CANDIDATE, occupancyGrid, agentPose)
        } else {
            // All search candidates visited: transition to returning
            transitionToReturning(occupancyGrid, agentPose, System.currentTimeMillis())
        }
    }

    /**
     * Generates cover-aware search candidate cells around the last known player position (Milestone 11, Section 5.2).
     *
     * Candidate selection principles:
     * 1. Target 0 is the last known player position (or nearest traversable cell if direct cell is in obstacle/inflation zone).
     * 2. Remaining candidates are chosen from traversable cells within [CreatureAIConfig.searchRadiusMeters] of the last known position.
     * 3. Cells adjacent to physical obstacle boundaries receive preference weighting ([CreatureAIConfig.coverBoundaryWeight]).
     * 4. Multi-directional angular sampling prioritizes alternative flanking approaches around cover.
     * 5. Enforces minimum separation ([CreatureAIConfig.minCandidateSeparationMeters]) between candidates.
     * 6. Strictly bounded to [CreatureAIConfig.maxSearchCandidates].
     */
    fun generateCoverAwareSearchCandidates(
        centerCol: Int,
        centerRow: Int,
        occupancyGrid: OccupancyGrid
    ): List<Pair<Int, Int>> {
        val candidates = mutableListOf<Pair<Int, Int>>()
        if (centerCol !in 0 until occupancyGrid.numCellsX || centerRow !in 0 until occupancyGrid.numCellsZ) {
            return candidates
        }

        val costGrid = occupancyGrid.traversalCostGrid
        val cellSize = occupancyGrid.cellSizeMeters
        val radiusCells = (config.searchRadiusMeters / cellSize).toInt().coerceAtLeast(1)
        val minSepMeters = config.minCandidateSeparationMeters

        // 1. Candidate 0: Last known location itself (or nearest traversable)
        val centerIdx = occupancyGrid.cellToIndex(centerCol, centerRow)
        val primaryCell: Pair<Int, Int> = if (costGrid.isTraversable(centerIdx)) {
            Pair(centerCol, centerRow)
        } else {
            findBestApproachCell(centerCol, centerRow, centerCol, centerRow, occupancyGrid)
        }
        candidates.add(primaryCell)

        if (config.maxSearchCandidates <= 1) {
            return candidates
        }

        // Helper to check separation from all selected candidates
        fun isSeparated(col: Int, row: Int): Boolean {
            val (fx, fz) = occupancyGrid.cellToFloorCoord(col, row)
            for (cand in candidates) {
                val (cx, cz) = occupancyGrid.cellToFloorCoord(cand.first, cand.second)
                val d = distance(fx, fz, cx, cz)
                if (d < minSepMeters) return false
            }
            return true
        }

        // Helper to check if a cell borders a physical obstacle (cover boundary)
        fun hasAdjacentObstacle(col: Int, row: Int): Boolean {
            for (dr in -1..1) {
                for (dc in -1..1) {
                    if (dc == 0 && dr == 0) continue
                    val nc = col + dc
                    val nr = row + dr
                    if (nc in 0 until occupancyGrid.numCellsX && nr in 0 until occupancyGrid.numCellsZ) {
                        if (occupancyGrid.getNavigationCellState(nc, nr) == NavigationCellState.PHYSICAL_OBSTACLE) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        // 2. Multi-directional angular sampling around the last known position
        // 8 cardinal and diagonal rays at varied distances to find flanking routes around cover
        data class ScoredCandidate(val cell: Pair<Int, Int>, val score: Float)
        val scoredList = mutableListOf<ScoredCandidate>()

        val numRays = 8
        val minRadiusCells = (0.4f / cellSize).toInt().coerceAtLeast(2)
        val maxRadiusCells = radiusCells

        for (ray in 0 until numRays) {
            val angle = (ray * 2.0 * Math.PI / numRays).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            for (rDist in maxRadiusCells downTo minRadiusCells) {
                val col = centerCol + (cosA * rDist).toInt()
                val row = centerRow + (sinA * rDist).toInt()

                if (col in 0 until occupancyGrid.numCellsX && row in 0 until occupancyGrid.numCellsZ) {
                    val idx = occupancyGrid.cellToIndex(col, row)
                    if (costGrid.isTraversable(idx)) {
                        val isCover = hasAdjacentObstacle(col, row)
                        var score = 1.0f
                        if (isCover) score *= config.coverBoundaryWeight
                        // Distance factor: slight preference for distinct perimeter coverage
                        score += (rDist.toFloat() / maxRadiusCells) * 0.5f

                        scoredList.add(ScoredCandidate(Pair(col, row), score))
                        break // Take furthest traversable along this ray
                    }
                }
            }
        }

        // Sort scored candidates descending by score
        scoredList.sortByDescending { it.score }

        // Select candidates respecting minimum separation
        for (item in scoredList) {
            if (candidates.size >= config.maxSearchCandidates) break
            if (isSeparated(item.cell.first, item.cell.second)) {
                candidates.add(item.cell)
            }
        }

        // 3. Fallback scan if angular rays didn't fill maxSearchCandidates
        if (candidates.size < config.maxSearchCandidates) {
            for (dr in -radiusCells..radiusCells step 2) {
                for (dc in -radiusCells..radiusCells step 2) {
                    if (candidates.size >= config.maxSearchCandidates) break
                    val col = centerCol + dc
                    val row = centerRow + dr
                    if (col in 0 until occupancyGrid.numCellsX && row in 0 until occupancyGrid.numCellsZ) {
                        val idx = occupancyGrid.cellToIndex(col, row)
                        if (costGrid.isTraversable(idx) && isSeparated(col, row)) {
                            candidates.add(Pair(col, row))
                        }
                    }
                }
            }
        }

        return candidates
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

    @Suppress("UNUSED_PARAMETER")
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

    private fun requestInvestigationPath(
        target: NoiseEvent,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose
    ) {
        val approachCell = findBestApproachCell(
            targetCol = target.cellCol,
            targetRow = target.cellRow,
            creatureCol = agentPose.cellCol,
            creatureRow = agentPose.cellRow,
            occupancyGrid = occupancyGrid
        )

        val destCol = approachCell.first
        val destRow = approachCell.second
        val (fx, fz) = occupancyGrid.cellToFloorCoord(destCol, destRow)

        targetType = TargetType.INVESTIGATE_NOISE
        targetCol = destCol
        targetRow = destRow
        targetFloorX = fx
        targetFloorZ = fz

        activeRequestId++
        val reqId = activeRequestId
        onNavigationGoalRequested?.invoke(
            agentPose.cellCol,
            agentPose.cellRow,
            destCol,
            destRow,
            reqId,
            TargetType.INVESTIGATE_NOISE
        )
    }

    private fun transitionToInvestigating(
        target: NoiseEvent,
        occupancyGrid: OccupancyGrid,
        agentPose: AgentPose,
        currentTimeMs: Long
    ) {
        state = CreatureAIState.INVESTIGATING
        activeNoiseTarget = target
        lastInvestigatedNoiseType = target.type
        isPausingAtPatrolPoint = false
        isPausingAtInvestigationPoint = false
        isWaitingAtSearchCandidate = false
        searchCandidates.clear()
        lastInvestigationTargetSwitchTimeMs = currentTimeMs
        lastStatusMessage = "INVESTIGATING: Heard ${target.type.name} at (${target.cellCol}, ${target.cellRow})"
        requestInvestigationPath(target, occupancyGrid, agentPose)
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

    private fun isNearTarget(
        agentPose: AgentPose,
        targetX: Float,
        targetZ: Float,
        thresholdMeters: Float = config.approachPlayerDistanceMeters
    ): Boolean {
        val d = distance(agentPose.floorX, agentPose.floorZ, targetX, targetZ)
        return d <= thresholdMeters
    }

    private fun distance(x1: Float, z1: Float, x2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dz = z2 - z1
        return sqrt(dx * dx + dz * dz)
    }

    /**
     * Selects the highest-priority reachable noise event for investigation (Milestone 13).
     */
    fun selectNoiseTarget(
        agentPose: AgentPose,
        occupancyGrid: OccupancyGrid,
        currentTimeMs: Long
    ): NoiseEvent? {
        val adaptiveParams = difficultyController.getActiveParameters()
        val minThreshold = (0.15f / adaptiveParams.noiseSensitivityMultiplier).coerceIn(0.05f, 0.5f)

        return noiseEventManager.findBestInvestigationTarget(
            creatureFloorX = agentPose.floorX,
            creatureFloorZ = agentPose.floorZ,
            currentTimeMs = currentTimeMs,
            minPerceivedThreshold = minThreshold,
            isOccluded = { fx, fz ->
                val cell = occupancyGrid.floorToCell(fx, fz)
                if (cell != null) {
                    val (c, r) = cell
                    if (c in 0 until occupancyGrid.numCellsX && r in 0 until occupancyGrid.numCellsZ) {
                        occupancyGrid.getNavigationCellState(c, r) == NavigationCellState.PHYSICAL_OBSTACLE
                    } else false
                } else false
            }
        )
    }

    /**
     * Emits a virtual gameplay noise event to influence creature suspicion and investigation (Milestone 13).
     */
    fun emitNoiseEvent(
        type: NoiseEventType,
        floorX: Float,
        floorZ: Float,
        cellCol: Int,
        cellRow: Int,
        currentTimeMs: Long = System.currentTimeMillis()
    ): NoiseEvent? {
        synchronized(lock) {
            val event = noiseEventManager.emitEvent(
                type = type,
                floorX = floorX,
                floorZ = floorZ,
                cellCol = cellCol,
                cellRow = cellRow,
                currentTimeMs = currentTimeMs
            ) ?: return null

            val evidenceType = when (type) {
                NoiseEventType.RELIC_COLLECTED -> EvidenceType.NOISE_RELIC
                NoiseEventType.DISTRACTION -> EvidenceType.NOISE_DISTRACTION
                NoiseEventType.PLAYER_MOVEMENT -> EvidenceType.NOISE_MOVEMENT
                NoiseEventType.OBJECTIVE_INTERACTION -> EvidenceType.NOISE_RELIC
            }

            suspicionModel.addEvidence(
                type = evidenceType,
                strength = event.initialIntensity,
                floorX = floorX,
                floorZ = floorZ,
                currentTimeMs = currentTimeMs
            )

            noiseFeedbackMessage = "NOISE DETECTED: ${type.name}"
            return event
        }
    }

    /**
     * Updates adaptive difficulty progression signals from mission state (Milestone 13).
     */
    fun updateDifficultyProgression(
        relicsCollected: Int,
        threatPercent: Float
    ) {
        synchronized(lock) {
            difficultyController.updateProgression(relicsCollected, threatPercent)
        }
    }

    /**
     * Produces an immutable, thread-safe snapshot of the creature AI state for UI diagnostics and GL rendering.
     */
    fun getSnapshot(): CreatureAISnapshot {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val adaptiveParams = difficultyController.getActiveParameters()
            val adjustedMaxSearchSec = config.maxSearchDurationSec * adaptiveParams.searchDurationMultiplier
            val searchTimeRem = if (state == CreatureAIState.SEARCHING) {
                (adjustedMaxSearchSec - (now - searchStartTimeMs) / 1000f).coerceAtLeast(0f)
            } else 0f

            val lastKnownAgeMs = if (lastSeenPlayerTimeMs > 0L) {
                (now - lastSeenPlayerTimeMs).coerceAtLeast(0L)
            } else 0L

            val depthVis = lastPerception.depthVisibility
            val suspicionSnap = suspicionModel.getSnapshot(now)
            val difficultySnap = difficultyController.evaluate(now, 0f, state == CreatureAIState.PAUSED)
            val activeNoiseCount = noiseEventManager.getActiveEvents(now).size

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
                visibilityState = depthVis.state,
                visibilityReason = depthVis.reason,
                visibilityConfidence = depthVis.confidence,
                observedDepthMeters = depthVis.observedDepthMeters,
                expectedDepthMeters = depthVis.expectedDepthMeters,
                currentSearchCandidateIndex = currentSearchCandidateIndex,
                totalSearchCandidates = searchCandidates.size,
                lastKnownLocationAgeMs = lastKnownAgeMs,
                suspicionSnapshot = suspicionSnap,
                difficultyLevel = difficultySnap.level,
                activeNoiseEventCount = activeNoiseCount,
                lastInvestigatedNoiseType = lastInvestigatedNoiseType,
                noiseFeedbackMessage = noiseFeedbackMessage,
                timestampMs = now
            )
        }
    }
}
