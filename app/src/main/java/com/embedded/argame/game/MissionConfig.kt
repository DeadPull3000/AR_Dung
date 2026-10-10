package com.embedded.argame.game

/**
 * Configuration parameters governing the Relic Hunt mission loop (Milestone 12).
 * Distances are in meters, durations in milliseconds.
 */
data class MissionConfig(
    /** Minimum count of traversable floor cells required before mission generation is permitted. */
    val minNavigableCellsForMission: Int = 40,

    /** Number of collectible relics generated per mission. */
    val relicCount: Int = 3,

    /** Radius around a relic within which the camera-based player proxy collects it (meters). */
    val relicCollectionRadiusMeters: Float = 0.40f,

    /** Radius around the extraction marker within which the player proxy completes extraction (meters). */
    val extractionRadiusMeters: Float = 0.45f,

    /** Minimum spatial separation between any two spawned mission objectives (meters). */
    val minObjectiveSeparationMeters: Float = 0.80f,

    /** Minimum spatial separation between player proxy start location and any spawned objective (meters). */
    val minPlayerSeparationMeters: Float = 0.60f,

    /** Proximity threshold between virtual creature and player proxy to initiate capture (meters). */
    val captureRadiusMeters: Float = 0.55f,

    /** Continuous pursuit duration within [captureRadiusMeters] required to trigger defeat (milliseconds). */
    val captureDurationRequiredMs: Long = 800L,

    /** Rate at which capture progress decays when player proxy escapes [captureRadiusMeters] (ms per second). */
    val captureDecayRateMsPerSec: Float = 1000f,

    /** Minimum time interval between background objective reachability checks (milliseconds). */
    val reachabilityCheckIntervalMs: Long = 1000L
)
