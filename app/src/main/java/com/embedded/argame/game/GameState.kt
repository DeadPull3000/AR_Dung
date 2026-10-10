package com.embedded.argame.game

/**
 * State machine governing the high-level mission lifecycle (Milestone 12).
 * Operates independently from Agent movement kinematics and Creature AI state.
 */
enum class GameState {
    /** Waiting for floor plane detection and sufficient room mapping coverage. */
    SCANNING,

    /** Room geometry is sufficiently mapped; mission generated and ready to start. */
    READY,

    /** Active mission gameplay (relic collection, extraction tracking, creature pursuit). */
    PLAYING,

    /** Gameplay suspended (manual pause or AR tracking loss). */
    PAUSED,

    /** Victory: all relics collected and active extraction point reached. */
    WON,

    /** Defeat: creature caught the player proxy. */
    LOST;

    val isTerminal: Boolean
        get() = this == WON || this == LOST

    val isInteractive: Boolean
        get() = this == PLAYING
}

/**
 * Type of mission objective placed in the mapped physical environment.
 */
enum class ObjectiveType {
    RELIC,
    EXTRACTION
}

/**
 * Current interactive state of an objective.
 */
enum class ObjectiveStatus {
    /** Relic is active and available for collection. */
    AVAILABLE,

    /** Relic has been collected by the player proxy. */
    COLLECTED,

    /** Extraction point is visible on the floor but locked (relics remaining). */
    LOCKED,

    /** Extraction point is unlocked and active for victory extraction. */
    ACTIVE
}
