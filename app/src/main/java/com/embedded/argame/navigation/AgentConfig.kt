package com.embedded.argame.navigation

/**
 * Kinematic and physical configuration parameters for the autonomous virtual agent.
 * Exposes configurable thresholds without burying magic numbers in movement code.
 */
data class AgentConfig(
    /** Physical radius of agent in meters. Matches Milestone 7 obstacle inflation (0.20m). */
    val agentRadiusMeters: Float = 0.20f,

    /** Linear travel speed along the floor plane in meters per second. */
    val moveSpeedMps: Float = 0.50f,

    /** Maximum angular turning rate in radians per second (default: PI rad/s = 180 deg/s). */
    val maxTurnRateRadPerSec: Float = 3.1415927f,

    /** Radial distance threshold to goal cell/point in meters to declare arrival. */
    val arrivalThresholdMeters: Float = 0.10f,

    /** Radial distance threshold to intermediate waypoints in meters to advance index. */
    val waypointThresholdMeters: Float = 0.15f,

    /** Maximum clamped delta-time in seconds to prevent teleportation on stalled frames. */
    val maxDeltaTimeSec: Float = 0.10f,

    /** Minimum cooldown period between dynamic replanning requests in milliseconds. */
    val replanCooldownMs: Long = 400L,

    /** Visual elevation above floor plane in meters to ensure agent mesh rests visibly on floor. */
    val visualElevationMeters: Float = 0.05f
)
