package com.embedded.argame.perception

/**
 * High-level status of the ARCore Depth subsystem.
 */
enum class DepthStatus {
    UNSUPPORTED,
    WAITING,
    TRACKING_PAUSED,
    READY,
    ERROR
}

/**
 * Diagnostic depth point sample at a designated normalized screen position.
 */
data class DepthPointSample(
    val label: String,
    val normalizedX: Float,
    val normalizedY: Float,
    val depthMeters: Float,
    val isValid: Boolean
)

/**
 * Camera calibration intrinsics required for 3D point cloud backprojection.
 */
data class CameraIntrinsicsData(
    val fx: Float = 0f,
    val fy: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Decoupled immutable snapshot of the depth perception system.
 * Updated at 5–10 Hz on the GL perception thread.
 */
data class DepthDiagnostics(
    val isSupported: Boolean = false,
    val status: DepthStatus = DepthStatus.WAITING,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val validSamplePercent: Float = 0f,
    val minDepthMeters: Float = 0f,
    val maxDepthMeters: Float = 0f,
    val meanDepthMeters: Float = 0f,
    val centerDepthMeters: Float = 0f,
    val rawDepthAvailable: Boolean = false,
    val rawDepthValidPercent: Float = 0f,
    val depthUpdateHz: Float = 0f,
    val timestampNs: Long = 0L,
    val pointSamples: List<DepthPointSample> = emptyList(),
    val intrinsics: CameraIntrinsicsData = CameraIntrinsicsData(),
    val statusMessage: String = ""
)
