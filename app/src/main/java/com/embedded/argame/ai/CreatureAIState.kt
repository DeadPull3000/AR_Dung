package com.embedded.argame.ai

/**
 * High-level behavioral states of the reactive creature AI state machine (Milestone 10).
 *
 * Gameplay loop:
 * INITIALIZING -> PATROLLING -> CHASING -> SEARCHING -> RETURNING -> PATROLLING
 * with safe recovery states BLOCKED and PAUSED.
 */
enum class CreatureAIState {
    /** Awaiting valid AR tracking, stable floor reference, and traversable navigation grid. */
    INITIALIZING,

    /** Autonomously visiting reachable floor regions; pauses briefly at waypoints before choosing next. */
    PATROLLING,

    /** Active pursuit of the player proxy; continuously paths around obstacles toward player position. */
    CHASING,

    /** Investigating last known player location and surrounding candidate cells after losing sight. */
    SEARCHING,

    /** Navigating back toward valid patrol area after search timed out without rediscovering player. */
    RETURNING,

    /** Safe recovery state when no traversable route exists or all candidate targets are blocked. */
    BLOCKED,

    /** Simulation suspended (AI disabled, user paused, or AR tracking temporarily lost). */
    PAUSED;

    val isAlertState: Boolean
        get() = this == CHASING || this == SEARCHING
}

/**
 * Classification of the current navigation destination targeted by the creature.
 */
enum class TargetType {
    NONE,
    PATROL,
    CHASE_PLAYER,
    SEARCH_LAST_KNOWN,
    SEARCH_CANDIDATE,
    RETURN_PATROL
}

/**
 * Immutable thread-safe snapshot of the creature AI state for UI diagnostics and GL rendering.
 */
data class CreatureAISnapshot(
    val state: CreatureAIState = CreatureAIState.INITIALIZING,
    val isEnabled: Boolean = false,
    val targetType: TargetType = TargetType.NONE,
    val targetCol: Int = -1,
    val targetRow: Int = -1,
    val targetFloorX: Float = 0f,
    val targetFloorZ: Float = 0f,
    val isPlayerDetected: Boolean = false,
    val isLineOfSightClear: Boolean = false,
    val playerDistanceMeters: Float = 0f,
    val playerCellCol: Int = -1,
    val playerCellRow: Int = -1,
    val lastKnownPlayerCol: Int = -1,
    val lastKnownPlayerRow: Int = -1,
    val lastKnownPlayerFloorX: Float = 0f,
    val lastKnownPlayerFloorZ: Float = 0f,
    val searchTimeRemainingSec: Float = 0f,
    val activeRequestId: Long = 0L,
    val lastStatusMessage: String = "Initializing",
    val visibilityState: VisibilityState = VisibilityState.UNKNOWN,
    val visibilityReason: VisibilityReason = VisibilityReason.DEPTH_NOT_AVAILABLE,
    val visibilityConfidence: Float = 0f,
    val observedDepthMeters: Float = 0f,
    val expectedDepthMeters: Float = 0f,
    val currentSearchCandidateIndex: Int = 0,
    val totalSearchCandidates: Int = 0,
    val lastKnownLocationAgeMs: Long = 0L,
    val timestampMs: Long = 0L
)
