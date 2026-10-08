package com.embedded.argame.environment

/**
 * Decoupled immutable snapshot of the 2.5D Occupancy Grid telemetry.
 * Dispatched to the UI thread at 5–10 Hz.
 */
data class GridDiagnostics(
    val isFloorTracking: Boolean = false,
    val floorStatus: String = "INITIALIZING",
    val floorHeightY: Float = 0f,
    val floorPlaneArea: Float = 0f,
    val widthCells: Int = 80,
    val depthCells: Int = 80,
    val cellSizeMeters: Float = 0.10f,
    val totalCells: Int = 6400,
    val freeCount: Int = 0,
    val occupiedCount: Int = 0,
    val unknownCount: Int = 6400,
    val coveragePercent: Float = 0f,
    val updateHz: Float = 0f,
    val lastUpdateTimestampNs: Long = 0L,
    val minObstacleHeight: Float = 0.10f,
    val maxNavigationHeight: Float = 1.50f
)
