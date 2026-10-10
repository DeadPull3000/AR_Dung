package com.embedded.argame.game

/**
 * Immutable representation of a physical room mission objective (Relic or Extraction portal).
 *
 * Positions are stored in floor-relative coordinates ([floorX], [floorZ]) and discrete grid cells ([col], [row])
 * to ensure consistency across coordinate transformations and dynamic grid re-evaluations.
 */
data class MissionObjective(
    val id: Int,
    val type: ObjectiveType,
    val col: Int,
    val row: Int,
    val floorX: Float,
    val floorZ: Float,
    val status: ObjectiveStatus,
    val interactionRadiusMeters: Float
) {
    val isCollected: Boolean
        get() = status == ObjectiveStatus.COLLECTED

    val isAvailable: Boolean
        get() = status == ObjectiveStatus.AVAILABLE

    val isLocked: Boolean
        get() = status == ObjectiveStatus.LOCKED

    val isExtractionActive: Boolean
        get() = type == ObjectiveType.EXTRACTION && status == ObjectiveStatus.ACTIVE
}
