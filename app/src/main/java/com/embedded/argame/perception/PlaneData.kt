package com.embedded.argame.perception

/**
 * High-level classification of tracked planar surfaces.
 * Decoupled from direct ARCore dependencies.
 */
enum class TrackedPlaneType {
    HORIZONTAL_UPWARD_FACING,
    HORIZONTAL_DOWNWARD_FACING,
    VERTICAL,
    UNKNOWN
}

/**
 * Decoupled immutable snapshot of an active spatial plane.
 * Coordinates are expressed in ARCore 3D World space (meters, right-handed Y-up).
 */
data class PlaneSnapshot(
    val id: String,
    val type: TrackedPlaneType,
    val trackingStatus: TrackingStatus,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val extentX: Float,
    val extentZ: Float,
    val estimatedArea: Float,
    val isCandidateLargeSurface: Boolean = false
)

/**
 * Summary telemetry data for currently tracked spatial planes.
 * Used for live on-screen HUD diagnostics.
 */
data class PlaneDiagnostics(
    val totalPlanes: Int = 0,
    val horizontalUpwardCount: Int = 0,
    val horizontalDownwardCount: Int = 0,
    val verticalCount: Int = 0,
    val largestPlaneWidth: Float = 0f,
    val largestPlaneDepth: Float = 0f,
    val largestPlaneArea: Float = 0f,
    val hasCandidateLargeSurface: Boolean = false,
    val candidateSurfaceHeightY: Float = 0f
)
