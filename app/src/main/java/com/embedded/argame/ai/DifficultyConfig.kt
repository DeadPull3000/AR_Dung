package com.embedded.argame.ai

/**
 * Discrete difficulty tiers representing adaptive gameplay challenge (Milestone 13).
 */
enum class DifficultyLevel {
    EASY,
    STANDARD,
    CHALLENGING
}

/**
 * Concrete gameplay parameter set adjusted per difficulty tier.
 * Strictly preserves pathfinding safety margins, obstacle inflation, and tracking guarantees.
 */
data class AdaptiveParameters(
    /** Multiplier applied to configured patrol waypoint pause duration. */
    val patrolPauseMultiplier: Float = 1.0f,

    /** Multiplier applied to configured suspicion decay rate. */
    val suspicionDecayMultiplier: Float = 1.0f,

    /** Multiplier applied to configured max search duration. */
    val searchDurationMultiplier: Float = 1.0f,

    /** Multiplier applied to investigation urgency (higher urgency = shorter pause before clearing). */
    val investigationUrgencyMultiplier: Float = 1.0f,

    /** Multiplier applied to creature noise sensitivity. */
    val noiseSensitivityMultiplier: Float = 1.0f
)

/**
 * Configuration parameters governing adaptive difficulty adjustments.
 */
data class DifficultyConfig(
    val initialLevel: DifficultyLevel = DifficultyLevel.STANDARD,

    /** Minimum time interval between permitted difficulty adjustments (milliseconds). */
    val adjustmentCooldownMs: Long = 5000L,

    /** Score thresholds with hysteresis margin for tier switching */
    val easyToStandardScore: Float = 35f,
    val standardToChallengingScore: Float = 70f,
    val hysteresisMargin: Float = 6f,

    /** Parameter sets for each discrete tier */
    val easyParameters: AdaptiveParameters = AdaptiveParameters(
        patrolPauseMultiplier = 1.4f,
        suspicionDecayMultiplier = 1.5f,
        searchDurationMultiplier = 0.7f,
        investigationUrgencyMultiplier = 0.7f,
        noiseSensitivityMultiplier = 0.7f
    ),

    val standardParameters: AdaptiveParameters = AdaptiveParameters(
        patrolPauseMultiplier = 1.0f,
        suspicionDecayMultiplier = 1.0f,
        searchDurationMultiplier = 1.0f,
        investigationUrgencyMultiplier = 1.0f,
        noiseSensitivityMultiplier = 1.0f
    ),

    val challengingParameters: AdaptiveParameters = AdaptiveParameters(
        patrolPauseMultiplier = 0.7f,
        suspicionDecayMultiplier = 0.7f,
        searchDurationMultiplier = 1.4f,
        investigationUrgencyMultiplier = 1.4f,
        noiseSensitivityMultiplier = 1.4f
    )
)
