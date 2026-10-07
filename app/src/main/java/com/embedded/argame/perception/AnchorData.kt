package com.embedded.argame.perception

/**
 * Tracking status of the test spatial Anchor.
 */
enum class AnchorStatus {
    NONE,
    TRACKING,
    PAUSED,
    STOPPED,
    NO_SURFACE_DETECTED
}

/**
 * Decoupled immutable diagnostics for the active spatial Anchor and hit-testing telemetry.
 * Expressed in ARCore world coordinates (meters).
 */
data class AnchorDiagnostics(
    val status: AnchorStatus = AnchorStatus.NONE,
    val posX: Float = 0f,
    val posY: Float = 0f,
    val posZ: Float = 0f,
    val distanceMeters: Float = 0f,
    val surfaceType: TrackedPlaneType = TrackedPlaneType.UNKNOWN,
    val lastHitMessage: String = ""
)
