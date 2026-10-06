package com.embedded.argame.perception

enum class TrackingStatus {
    NOT_INITIALIZED,
    TRACKING,
    PAUSED,
    STOPPED
}

data class TrackingDiagnostics(
    val status: TrackingStatus = TrackingStatus.NOT_INITIALIZED,
    val failureReason: String = "",
    val posX: Float = 0f,
    val posY: Float = 0f,
    val posZ: Float = 0f,
    val qX: Float = 0f,
    val qY: Float = 0f,
    val qZ: Float = 0f,
    val qW: Float = 1f,
    val fps: Float = 0f,
    val frameTimestampNs: Long = 0L
)
