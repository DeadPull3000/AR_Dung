package com.embedded.argame.ai

import kotlin.math.hypot

/**
 * Categorization of gameplay-level virtual noise events (Milestone 13).
 */
enum class NoiseEventType(
    val defaultIntensity: Float,
    val defaultRadiusMeters: Float,
    val defaultDurationMs: Long
) {
    /** Emitted when the player proxy collects a mission relic. */
    RELIC_COLLECTED(
        defaultIntensity = 1.0f,
        defaultRadiusMeters = 4.5f,
        defaultDurationMs = 5000L
    ),

    /** Emitted when the player proxy interacts with the extraction portal or objective. */
    OBJECTIVE_INTERACTION(
        defaultIntensity = 0.75f,
        defaultRadiusMeters = 3.5f,
        defaultDurationMs = 4000L
    ),

    /** Emitted when rapid player proxy displacement exceeds physical stealth thresholds. */
    PLAYER_MOVEMENT(
        defaultIntensity = 0.40f,
        defaultRadiusMeters = 2.5f,
        defaultDurationMs = 3000L
    ),

    /** Emitted by active player distraction interaction. */
    DISTRACTION(
        defaultIntensity = 1.0f,
        defaultRadiusMeters = 5.5f,
        defaultDurationMs = 6000L
    )
}

/**
 * Immutable data model representing a virtual in-game noise event (Milestone 13).
 *
 * Operates purely on computational gameplay and spatial grid coordinates;
 * does not access microphone or audio sensor hardware.
 */
data class NoiseEvent(
    val id: Long,
    val type: NoiseEventType,
    val floorX: Float,
    val floorZ: Float,
    val cellCol: Int,
    val cellRow: Int,
    val timestampMs: Long,
    val initialIntensity: Float = type.defaultIntensity,
    val effectiveRadiusMeters: Float = type.defaultRadiusMeters,
    val durationMs: Long = type.defaultDurationMs,
    val isInvestigated: Boolean = false
) {
    /**
     * Checks if this noise event has expired based on current simulation time.
     */
    fun isExpired(currentTimeMs: Long): Boolean =
        (currentTimeMs - timestampMs) >= durationMs

    /**
     * Calculates the distance-attenuated intensity perceived at a given observer floor position.
     *
     * @param observerFloorX Observer's X coordinate on floor plane.
     * @param observerFloorZ Observer's Z coordinate on floor plane.
     * @param currentTimeMs Current simulation timestamp in milliseconds.
     * @param isOccludedByObstacle Whether a physical obstacle on the 2.5D grid occludes line of sight.
     * @return Perceived intensity normalized in [0.0, 1.0]; returns 0.0 if out of range or expired.
     */
    fun getPerceivedIntensity(
        observerFloorX: Float,
        observerFloorZ: Float,
        currentTimeMs: Long,
        isOccludedByObstacle: Boolean = false
    ): Float {
        if (isExpired(currentTimeMs)) return 0f

        val dist = hypot(observerFloorX - floorX, observerFloorZ - floorZ)
        if (dist >= effectiveRadiusMeters) return 0f

        // Linear distance attenuation
        val distanceFactor = (1f - (dist / effectiveRadiusMeters)).coerceIn(0f, 1f)
        // Obstacle damping reduces intensity by 50% across major physical barriers
        val obstacleFactor = if (isOccludedByObstacle) 0.50f else 1.0f

        return (initialIntensity * distanceFactor * obstacleFactor).coerceIn(0f, 1f)
    }
}
