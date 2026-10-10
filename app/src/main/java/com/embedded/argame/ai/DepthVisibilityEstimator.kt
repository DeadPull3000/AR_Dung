package com.embedded.argame.ai

import com.embedded.argame.perception.CameraIntrinsicsData
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Tri-state visibility classification for virtual creature perception (Milestone 11, Section 2).
 */
enum class VisibilityState {
    /** Reliable evidence confirms creature is visually unobscured. */
    VISIBLE,

    /** Reliable evidence confirms a closer real-world surface or obstacle occludes the creature. */
    OCCLUDED,

    /** Tracking, depth, field of view, or evidence count is insufficient to determine visibility. */
    UNKNOWN
}

/**
 * Underlying diagnostic reason for the visibility classification.
 */
enum class VisibilityReason {
    /** Line-of-sight is unobstructed across both 2.5D grid and metric depth. */
    CLEAR,

    /** An intervening physical surface in the depth image is closer than the creature. */
    DEPTH_OCCLUSION,

    /** 2.5D occupancy grid ray is obstructed by a physical obstacle. */
    GRID_OBSTACLE,

    /** 2.5D occupancy grid ray crosses unobserved unknown space under conservative policy. */
    GRID_UNKNOWN_SPACE,

    /** Creature position lies behind the camera optical plane (Z <= 0). */
    BEHIND_CAMERA,

    /** Creature projected image coordinates lie outside the camera/depth frustum. */
    OUTSIDE_FRUSTUM,

    /** Local depth patch has fewer than [CreatureAIConfig.minValidDepthSamples] valid metric samples. */
    INSUFFICIENT_DEPTH_SAMPLES,

    /** 16-bit metric depth image was not available on this frame. */
    DEPTH_NOT_AVAILABLE,

    /** ARCore camera 6-DoF tracking is lost or paused. */
    TRACKING_LOST,

    /** Depth was unavailable or unknown; visibility was provisionally determined by grid ray fallback. */
    FALLBACK_GRID_ONLY
}

/**
 * Immutable thread-safe snapshot of depth-aware visibility estimation.
 */
data class DepthVisibilitySnapshot(
    val state: VisibilityState = VisibilityState.UNKNOWN,
    val reason: VisibilityReason = VisibilityReason.DEPTH_NOT_AVAILABLE,
    val confidence: Float = 0f,
    val expectedDepthMeters: Float = 0f,
    val observedDepthMeters: Float = 0f,
    val depthDiffMeters: Float = 0f,
    val patchSampleCount: Int = 0,
    val validSampleCount: Int = 0,
    val projectedDepthX: Int = -1,
    val projectedDepthY: Int = -1,
    val gridLineOfSightClear: Boolean = false,
    val depthOccluded: Boolean = false,
    val timestampMs: Long = 0L
)

/**
 * Depth-Aware Visibility Estimator (Milestone 11, Section 4).
 *
 * Combines 2.5D floor-grid line-of-sight ray traversal with real-time ARCore 16-bit metric depth
 * sampling to detect physical occluders (e.g. tables, chairs, partial walls, elevated barriers).
 */
class DepthVisibilityEstimator(
    val config: CreatureAIConfig = CreatureAIConfig()
) {
    // Reusable buffers to guarantee ZERO heap allocations on the 60 FPS GL evaluation path
    private val tempCamPoint = FloatArray(3)
    private val tempWorldPoint = FloatArray(3)
    private val tempValidSamples = FloatArray(25) // Up to 5x5 patch

    // Hysteresis & temporal confirmation tracking
    private var consecutiveOcclusionCount = 0
    private var lastConfirmedState = VisibilityState.UNKNOWN
    private var lastConfirmedReason = VisibilityReason.DEPTH_NOT_AVAILABLE
    private var lastEvaluationTimeMs = 0L

    /**
     * Resets estimator temporal hysteresis state.
     */
    fun reset() {
        consecutiveOcclusionCount = 0
        lastConfirmedState = VisibilityState.UNKNOWN
        lastConfirmedReason = VisibilityReason.DEPTH_NOT_AVAILABLE
        lastEvaluationTimeMs = 0L
    }

    /**
     * Evaluates depth-aware visibility for the virtual creature given an ARCore Pose.
     */
    fun evaluate(
        isTracking: Boolean,
        creatureWorldX: Float,
        creatureWorldY: Float,
        creatureWorldZ: Float,
        cameraPose: Pose?,
        intrinsics: CameraIntrinsicsData,
        depthBuffer: ByteBuffer?,
        depthWidth: Int,
        depthHeight: Int,
        depthRowStride: Int,
        depthPixelStride: Int,
        gridLineOfSightClear: Boolean,
        gridReason: VisibilityReason = if (gridLineOfSightClear) VisibilityReason.CLEAR else VisibilityReason.GRID_OBSTACLE,
        currentTimeMs: Long = System.currentTimeMillis()
    ): DepthVisibilitySnapshot {
        if (!isTracking || cameraPose == null) {
            consecutiveOcclusionCount = 0
            lastConfirmedState = VisibilityState.UNKNOWN
            lastConfirmedReason = VisibilityReason.TRACKING_LOST
            lastEvaluationTimeMs = currentTimeMs
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.TRACKING_LOST,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        tempWorldPoint[0] = creatureWorldX
        tempWorldPoint[1] = creatureWorldY
        tempWorldPoint[2] = creatureWorldZ

        // Transform creature world position into camera space
        val camPoint = cameraPose.inverse().transformPoint(tempWorldPoint)
        val xc = camPoint[0]
        val yc = camPoint[1]
        val zc = camPoint[2]

        return evaluateFromCameraCoords(
            isTracking = true,
            xc = xc,
            yc = yc,
            zc = zc,
            intrinsics = intrinsics,
            depthBuffer = depthBuffer,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = depthRowStride,
            depthPixelStride = depthPixelStride,
            gridLineOfSightClear = gridLineOfSightClear,
            gridReason = gridReason,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Overloaded evaluator accepting explicit camera-space coordinates or world-to-cam matrix,
     * enabling 100% deterministic JVM unit testing without ARCore Pose mocks.
     */
    fun evaluate(
        isTracking: Boolean,
        creatureWorldX: Float,
        creatureWorldY: Float,
        creatureWorldZ: Float,
        worldToCamMatrix: FloatArray?,
        intrinsics: CameraIntrinsicsData,
        depthBuffer: ByteBuffer?,
        depthWidth: Int,
        depthHeight: Int,
        depthRowStride: Int,
        depthPixelStride: Int,
        gridLineOfSightClear: Boolean,
        gridReason: VisibilityReason = if (gridLineOfSightClear) VisibilityReason.CLEAR else VisibilityReason.GRID_OBSTACLE,
        currentTimeMs: Long = System.currentTimeMillis()
    ): DepthVisibilitySnapshot {
        if (!isTracking) {
            consecutiveOcclusionCount = 0
            lastConfirmedState = VisibilityState.UNKNOWN
            lastConfirmedReason = VisibilityReason.TRACKING_LOST
            lastEvaluationTimeMs = currentTimeMs
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.TRACKING_LOST,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        val xc: Float
        val yc: Float
        val zc: Float

        if (worldToCamMatrix != null && worldToCamMatrix.size >= 16) {
            val m = worldToCamMatrix
            xc = m[0] * creatureWorldX + m[4] * creatureWorldY + m[8] * creatureWorldZ + m[12]
            yc = m[1] * creatureWorldX + m[5] * creatureWorldY + m[9] * creatureWorldZ + m[13]
            zc = m[2] * creatureWorldX + m[6] * creatureWorldY + m[10] * creatureWorldZ + m[14]
        } else {
            // Default identity camera at origin looking along -Z
            xc = creatureWorldX
            yc = creatureWorldY
            zc = creatureWorldZ
        }

        return evaluateFromCameraCoords(
            isTracking = true,
            xc = xc,
            yc = yc,
            zc = zc,
            intrinsics = intrinsics,
            depthBuffer = depthBuffer,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = depthRowStride,
            depthPixelStride = depthPixelStride,
            gridLineOfSightClear = gridLineOfSightClear,
            gridReason = gridReason,
            currentTimeMs = currentTimeMs
        )
    }

    /**
     * Core perception evaluation given 3D coordinates in camera space.
     */
    fun evaluateFromCameraCoords(
        isTracking: Boolean,
        xc: Float,
        yc: Float,
        zc: Float,
        intrinsics: CameraIntrinsicsData,
        depthBuffer: ByteBuffer?,
        depthWidth: Int,
        depthHeight: Int,
        depthRowStride: Int,
        depthPixelStride: Int,
        gridLineOfSightClear: Boolean,
        gridReason: VisibilityReason = if (gridLineOfSightClear) VisibilityReason.CLEAR else VisibilityReason.GRID_OBSTACLE,
        currentTimeMs: Long = System.currentTimeMillis()
    ): DepthVisibilitySnapshot {
        if (!isTracking) {
            consecutiveOcclusionCount = 0
            lastConfirmedState = VisibilityState.UNKNOWN
            lastConfirmedReason = VisibilityReason.TRACKING_LOST
            lastEvaluationTimeMs = currentTimeMs
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.TRACKING_LOST,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        // 1. Evidence Age Check
        if (lastEvaluationTimeMs > 0L && (currentTimeMs - lastEvaluationTimeMs) > config.maxEvidenceAgeMs) {
            consecutiveOcclusionCount = 0
            lastConfirmedState = VisibilityState.UNKNOWN
            lastConfirmedReason = VisibilityReason.DEPTH_NOT_AVAILABLE
        }
        lastEvaluationTimeMs = currentTimeMs

        // 2. Optical Distance Check along viewing axis
        // In ARCore/OpenGL camera frame, camera looks along -Z.
        // If zc is negative, zMetric = -zc; if test provides positive zc, zMetric = zc.
        val zMetric = if (zc < 0f) -zc else zc
        if (zMetric < 0.05f) {
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.BEHIND_CAMERA,
                expectedDepthMeters = zMetric,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        // 3. Pinhole Camera Projection
        if (intrinsics.fx <= 0f || intrinsics.fy <= 0f || intrinsics.width <= 0 || intrinsics.height <= 0) {
            return handleMissingDepthFallback(gridLineOfSightClear, gridReason, zMetric, currentTimeMs)
        }

        val u = (xc * intrinsics.fx / zMetric) + intrinsics.cx
        // In OpenGL camera space with -Z forward, +Y is up, so image v = cy - (yc * fy / zMetric)
        val v = if (zc < 0f) {
            intrinsics.cy - (yc * intrinsics.fy / zMetric)
        } else {
            (yc * intrinsics.fy / zMetric) + intrinsics.cy
        }

        val uNorm = u / intrinsics.width.toFloat()
        val vNorm = v / intrinsics.height.toFloat()

        // 4. Frustum Bounds Check
        if (uNorm < 0f || uNorm > 1f || vNorm < 0f || vNorm > 1f) {
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.OUTSIDE_FRUSTUM,
                expectedDepthMeters = zMetric,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        // 5. Depth Image Bounds Check
        if (depthBuffer == null || depthWidth <= 0 || depthHeight <= 0 || depthRowStride <= 0 || depthPixelStride <= 0) {
            return handleMissingDepthFallback(gridLineOfSightClear, gridReason, zMetric, currentTimeMs)
        }

        val xd = (uNorm * depthWidth).toInt().coerceIn(0, depthWidth - 1)
        val yd = (vNorm * depthHeight).toInt().coerceIn(0, depthHeight - 1)

        // 6. Local Neighbourhood Depth Patch Sampling
        val radius = config.depthSamplingPatchRadius.coerceIn(0, 4)
        var validCount = 0
        var totalPatchCount = 0

        val minX = (xd - radius).coerceIn(0, depthWidth - 1)
        val maxX = (xd + radius).coerceIn(0, depthWidth - 1)
        val minY = (yd - radius).coerceIn(0, depthHeight - 1)
        val maxY = (yd + radius).coerceIn(0, depthHeight - 1)

        for (py in minY..maxY) {
            for (px in minX..maxX) {
                totalPatchCount++
                val offset = py * depthRowStride + px * depthPixelStride
                if (offset + 1 < depthBuffer.capacity()) {
                    val depthMm = depthBuffer.getShort(offset).toInt() and 0xFFFF
                    // Metric depth range: 0.10 m (100 mm) to 20.0 m (20000 mm)
                    if (depthMm in 100..20000) {
                        if (validCount < tempValidSamples.size) {
                            tempValidSamples[validCount++] = depthMm / 1000f
                        }
                    }
                }
            }
        }

        // 7. Check Sufficient Sample Count
        if (validCount < config.minValidDepthSamples) {
            if (!gridLineOfSightClear) {
                return DepthVisibilitySnapshot(
                    state = VisibilityState.OCCLUDED,
                    reason = gridReason,
                    confidence = 0.85f,
                    expectedDepthMeters = zMetric,
                    patchSampleCount = totalPatchCount,
                    validSampleCount = validCount,
                    projectedDepthX = xd,
                    projectedDepthY = yd,
                    gridLineOfSightClear = false,
                    timestampMs = currentTimeMs
                )
            }
            return DepthVisibilitySnapshot(
                state = VisibilityState.UNKNOWN,
                reason = VisibilityReason.INSUFFICIENT_DEPTH_SAMPLES,
                confidence = 0.2f,
                expectedDepthMeters = zMetric,
                patchSampleCount = totalPatchCount,
                validSampleCount = validCount,
                projectedDepthX = xd,
                projectedDepthY = yd,
                gridLineOfSightClear = gridLineOfSightClear,
                timestampMs = currentTimeMs
            )
        }

        // 8. Robust Median Depth Calculation
        tempValidSamples.sort(0, validCount)
        val observedDepth = tempValidSamples[validCount / 2]
        val depthDiff = observedDepth - zMetric

        // 9. Margin & Occlusion Comparison
        val margin = max(config.minOcclusionMarginMeters, zMetric * config.relativeOcclusionMargin)
        val isRawDepthOccluded = observedDepth < (zMetric - margin)

        // 10. Temporal Confirmation (Hysteresis)
        if (isRawDepthOccluded) {
            consecutiveOcclusionCount++
        } else {
            consecutiveOcclusionCount = 0
        }

        val isConfirmedDepthOccluded = consecutiveOcclusionCount >= config.visibilityConfirmationCount

        // 11. Evidence Fusion Policy (Section 4.5)
        val fusedState: VisibilityState
        val fusedReason: VisibilityReason
        val confidence: Float

        when {
            // Rule 1: A reliably blocking grid ray is authoritative occlusion under the grid model
            !gridLineOfSightClear -> {
                fusedState = VisibilityState.OCCLUDED
                fusedReason = gridReason
                confidence = 0.95f
            }
            // Rule 2: Reliable depth showing a nearer intervening surface establishes occlusion
            isConfirmedDepthOccluded -> {
                fusedState = VisibilityState.OCCLUDED
                fusedReason = VisibilityReason.DEPTH_OCCLUSION
                confidence = (validCount.toFloat() / totalPatchCount).coerceIn(0.7f, 1.0f)
            }
            // Rule 3: Depth is pending confirmation (hysteresis active)
            isRawDepthOccluded && !isConfirmedDepthOccluded -> {
                fusedState = VisibilityState.UNKNOWN
                fusedReason = VisibilityReason.DEPTH_OCCLUSION
                confidence = 0.5f
            }
            // Rule 4: Both grid ray and depth confirm no intervening surface
            else -> {
                fusedState = VisibilityState.VISIBLE
                fusedReason = VisibilityReason.CLEAR
                confidence = (validCount.toFloat() / totalPatchCount).coerceIn(0.7f, 1.0f)
            }
        }

        lastConfirmedState = fusedState
        lastConfirmedReason = fusedReason

        return DepthVisibilitySnapshot(
            state = fusedState,
            reason = fusedReason,
            confidence = confidence,
            expectedDepthMeters = zMetric,
            observedDepthMeters = observedDepth,
            depthDiffMeters = depthDiff,
            patchSampleCount = totalPatchCount,
            validSampleCount = validCount,
            projectedDepthX = xd,
            projectedDepthY = yd,
            gridLineOfSightClear = gridLineOfSightClear,
            depthOccluded = isConfirmedDepthOccluded,
            timestampMs = currentTimeMs
        )
    }

    private fun handleMissingDepthFallback(
        gridLineOfSightClear: Boolean,
        gridReason: VisibilityReason,
        expectedDepthMeters: Float,
        currentTimeMs: Long
    ): DepthVisibilitySnapshot {
        return if (!gridLineOfSightClear) {
            DepthVisibilitySnapshot(
                state = VisibilityState.OCCLUDED,
                reason = gridReason,
                confidence = 0.85f,
                expectedDepthMeters = expectedDepthMeters,
                gridLineOfSightClear = false,
                timestampMs = currentTimeMs
            )
        } else {
            DepthVisibilitySnapshot(
                state = VisibilityState.VISIBLE,
                reason = VisibilityReason.FALLBACK_GRID_ONLY,
                confidence = 0.5f,
                expectedDepthMeters = expectedDepthMeters,
                gridLineOfSightClear = true,
                timestampMs = currentTimeMs
            )
        }
    }
}
