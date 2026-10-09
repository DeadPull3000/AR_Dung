package com.embedded.argame.ai

/**
 * Configuration parameters governing the reactive creature AI subsystem (Milestone 10).
 * All distances are in meters, times in seconds or milliseconds, and speeds in meters/second.
 */
data class CreatureAIConfig(
    /** Horizontal distance within which the creature can detect the player (meters). */
    val detectionRadiusMeters: Float = 3.0f,

    /** Minimum distance to maintain from player proxy so creature does not try to occupy exact player cell (meters). */
    val approachPlayerDistanceMeters: Float = 0.40f,

    /** Grace period after losing line of sight before transitioning from CHASING to SEARCHING (milliseconds). */
    val lostVisibilityGracePeriodMs: Long = 500L,

    /** Maximum duration the creature spends actively searching around last known position before RETURNING (seconds). */
    val maxSearchDurationSec: Float = 5.0f,

    /** Radius around last known position within which to sample candidate search cells (meters). */
    val searchRadiusMeters: Float = 1.0f,

    /** Wait duration at each search candidate cell before investigating next (seconds). */
    val searchCandidateWaitSec: Float = 1.0f,

    /** Maximum number of candidate locations to visit during a single search phase. */
    val maxSearchCandidates: Int = 3,

    /** Pause duration at a reached patrol waypoint before picking the next patrol destination (seconds). */
    val patrolPauseDurationSec: Float = 1.5f,

    /** Minimum separation distance from current position when selecting patrol destinations (meters). */
    val minPatrolDistanceMeters: Float = 0.8f,

    /** Maximum separation distance from current position when selecting patrol destinations (meters). */
    val maxPatrolDistanceMeters: Float = 3.5f,

    /** Maximum attempts to randomly sample a valid traversable patrol destination before fallback. */
    val maxPatrolSampleAttempts: Int = 32,

    /** Minimum time interval between high-level AI behavioral decision evaluations (milliseconds). */
    val aiDecisionIntervalMs: Long = 150L,

    /** Minimum time interval between pursuit path re-evaluations during active chase (milliseconds). */
    val chaseReplanIntervalMs: Long = 300L,

    /** Minimum distance player must move to trigger a new pursuit path search during chase (meters). */
    val chaseGoalChangeThresholdMeters: Float = 0.25f,

    /** Cooldown duration after encountering an unreachable target before retrying or recovering (milliseconds). */
    val blockedCooldownMs: Long = 1000L,

    /** Conservative policy: whether unobserved UNKNOWN cells block creature line of sight. */
    val unknownCellsBlockLineOfSight: Boolean = true
)
