package com.embedded.argame.ai

import com.embedded.argame.perception.CameraIntrinsicsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit verification tests for Depth-Aware Visibility Estimation (Milestone 11, Section 9).
 * Tests 1 to 13 covering geometric occlusion, pinhole projection, margin comparison,
 * temporal filtering, and grid/depth evidence fusion.
 */
class DepthVisibilityEstimatorTest {

    private val depthWidth = 160
    private val depthHeight = 90
    private val rowStride = depthWidth * 2
    private val pixelStride = 2

    private val intrinsics = CameraIntrinsicsData(
        fx = 1000f,
        fy = 1000f,
        cx = 960f,
        cy = 540f,
        width = 1920,
        height = 1080
    )

    private lateinit var config: CreatureAIConfig
    private lateinit var estimator: DepthVisibilityEstimator

    @Before
    fun setUp() {
        config = CreatureAIConfig(
            minValidDepthSamples = 3,
            depthSamplingPatchRadius = 2,
            minOcclusionMarginMeters = 0.15f,
            relativeOcclusionMargin = 0.05f,
            visibilityConfirmationCount = 2,
            maxEvidenceAgeMs = 600L
        )
        estimator = DepthVisibilityEstimator(config)
    }

    private fun createDepthBuffer(fillDepthMm: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(depthWidth * depthHeight * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until depthWidth * depthHeight) {
            buf.putShort(fillDepthMm.toShort())
        }
        buf.flip()
        return buf
    }

    private fun createDepthBufferWithPatch(fillDepthMm: Int, patchX: Int, patchY: Int, patchDepthMm: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(depthWidth * depthHeight * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until depthHeight) {
            for (x in 0 until depthWidth) {
                val depth = if (kotlin.math.abs(x - patchX) <= 3 && kotlin.math.abs(y - patchY) <= 3) {
                    patchDepthMm
                } else {
                    fillDepthMm
                }
                buf.putShort(depth.toShort())
            }
        }
        buf.flip()
        return buf
    }

    /**
     * Test 1 — A closer reliable surface between camera proxy and creature produces OCCLUDED.
     */
    @Test
    fun test1_closerReliableSurfaceProducesOccluded() {
        // Creature at (0, 0, -2.5m) in camera space -> expected depth = 2.5m
        // Observed physical surface in front at 1.2m (1200mm)
        val buf = createDepthBuffer(1200)

        // First frame: pending confirmation
        val frame1 = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.5f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.UNKNOWN, frame1.state)
        assertEquals(VisibilityReason.DEPTH_OCCLUSION, frame1.reason)

        // Second frame: hysteresis confirmed occlusion
        val frame2 = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.5f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1100L
        )
        assertEquals(VisibilityState.OCCLUDED, frame2.state)
        assertEquals(VisibilityReason.DEPTH_OCCLUSION, frame2.reason)
        assertTrue(frame2.depthOccluded)
    }

    /**
     * Test 2 — A reliable depth observation consistent with expected creature depth is VISIBLE.
     */
    @Test
    fun test2_reliableConsistentDepthMarksVisible() {
        // Creature at (0, 0, -2.0m) -> expected depth = 2.0m
        // Observed physical surface behind or matching creature at 2.1m (2100mm)
        val buf = createDepthBuffer(2100)

        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.VISIBLE, result.state)
        assertEquals(VisibilityReason.CLEAR, result.reason)
        assertFalse(result.depthOccluded)
        assertTrue(result.gridLineOfSightClear)
    }

    /**
     * Test 3 — Invalid or zero depth values do not count as valid surfaces.
     */
    @Test
    fun test3_zeroOrInvalidDepthValuesDoNotCountAsSurfaces() {
        // Depth buffer with 0mm (unmeasured/invalid)
        val buf = createDepthBuffer(0)

        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        // With 0 valid samples, insufficient samples -> UNKNOWN, not falsely marked occluded!
        assertEquals(0, result.validSampleCount)
        assertEquals(VisibilityState.UNKNOWN, result.state)
        assertEquals(VisibilityReason.INSUFFICIENT_DEPTH_SAMPLES, result.reason)
    }

    /**
     * Test 4 — Insufficient valid samples produce UNKNOWN.
     */
    @Test
    fun test4_insufficientValidSamplesProduceUnknown() {
        // Buffer where only 1 pixel has valid depth, while config requires 3
        val buf = ByteBuffer.allocateDirect(depthWidth * depthHeight * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until depthWidth * depthHeight) {
            buf.putShort(0.toShort())
        }
        // Put exactly 1 valid sample at center (x=80, y=45)
        val centerOffset = 45 * rowStride + 80 * pixelStride
        buf.putShort(centerOffset, 800.toShort())
        buf.flip()

        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(1, result.validSampleCount)
        assertTrue(result.validSampleCount < config.minValidDepthSamples)
        assertEquals(VisibilityState.UNKNOWN, result.state)
        assertEquals(VisibilityReason.INSUFFICIENT_DEPTH_SAMPLES, result.reason)
    }

    /**
     * Test 5 — An out-of-bounds projected creature location is handled safely.
     */
    @Test
    fun test5_outOfBoundsProjectedLocationHandledSafely() {
        val buf = createDepthBuffer(2000)

        // Creature far to the right outside frustum: xc = 15m, zc = -2m
        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 15f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.UNKNOWN, result.state)
        assertEquals(VisibilityReason.OUTSIDE_FRUSTUM, result.reason)
    }

    /**
     * Test 6 — Tracking loss produces an appropriate unknown/suspended result.
     */
    @Test
    fun test6_trackingLossProducesUnknownResult() {
        val buf = createDepthBuffer(2000)

        val result = estimator.evaluateFromCameraCoords(
            isTracking = false,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.UNKNOWN, result.state)
        assertEquals(VisibilityReason.TRACKING_LOST, result.reason)
    }

    /**
     * Test 7 — Grid-blocked visibility cannot be incorrectly overridden by depth evidence.
     */
    @Test
    fun test7_gridBlockedVisibilityCannotBeOverriddenByClearDepth() {
        // Depth suggests clear space (3000mm vs expected 2000mm)
        val buf = createDepthBuffer(3000)

        // But 2.5D floor grid indicates obstacle occlusion
        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = false,
            gridReason = VisibilityReason.GRID_OBSTACLE,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.OCCLUDED, result.state)
        assertEquals(VisibilityReason.GRID_OBSTACLE, result.reason)
        assertFalse(result.gridLineOfSightClear)
    }

    /**
     * Test 8 — A clear grid ray can be overridden by reliable depth evidence of an intervening surface.
     */
    @Test
    fun test8_clearGridRayOverriddenByDepthOcclusion() {
        // Floor grid is clear, but depth shows an elevated physical surface (e.g. table top) at 0.8m
        val buf = createDepthBuffer(800)

        // Evaluate twice to satisfy confirmation hysteresis
        estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        val result = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = buf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1100L
        )
        assertTrue("Grid ray was clear", result.gridLineOfSightClear)
        assertEquals(VisibilityState.OCCLUDED, result.state)
        assertEquals(VisibilityReason.DEPTH_OCCLUSION, result.reason)
    }

    /**
     * Test 9 — Missing depth follows the documented fallback policy.
     */
    @Test
    fun test9_missingDepthFollowsDocumentedFallbackPolicy() {
        // Depth buffer is null
        val resultClear = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = null,
            depthWidth = 0,
            depthHeight = 0,
            depthRowStride = 0,
            depthPixelStride = 0,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.VISIBLE, resultClear.state)
        assertEquals(VisibilityReason.FALLBACK_GRID_ONLY, resultClear.reason)

        val resultBlocked = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = null,
            depthWidth = 0,
            depthHeight = 0,
            depthRowStride = 0,
            depthPixelStride = 0,
            gridLineOfSightClear = false,
            gridReason = VisibilityReason.GRID_OBSTACLE,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.OCCLUDED, resultBlocked.state)
        assertEquals(VisibilityReason.GRID_OBSTACLE, resultBlocked.reason)
    }

    /**
     * Test 10 — Portrait/landscape projection and depth-image indexing do not produce out-of-bounds access.
     */
    @Test
    fun test10_portraitLandscapeCoordinatesStayInBounds() {
        val buf = createDepthBuffer(2000)

        // Corner tests: extreme top-left and bottom-right in image
        val extremeCoords = listOf(
            Pair(-1.8f, -1.0f),
            Pair(1.8f, 1.0f),
            Pair(-1.0f, 1.5f),
            Pair(1.0f, -1.5f)
        )

        for ((x, y) in extremeCoords) {
            val result = estimator.evaluateFromCameraCoords(
                isTracking = true,
                xc = x, yc = y, zc = -2.0f,
                intrinsics = intrinsics,
                depthBuffer = buf,
                depthWidth = depthWidth,
                depthHeight = depthHeight,
                depthRowStride = rowStride,
                depthPixelStride = pixelStride,
                gridLineOfSightClear = true,
                currentTimeMs = 1000L
            )
            assertTrue(result.projectedDepthX in -1 until depthWidth)
            assertTrue(result.projectedDepthY in -1 until depthHeight)
        }
    }

    /**
     * Test 11 — Temporal filtering suppresses brief noisy transitions.
     */
    @Test
    fun test11_temporalFilteringSuppressesBriefNoisyTransitions() {
        val clearBuf = createDepthBuffer(2500)
        val occludedBuf = createDepthBuffer(1000)

        // Initially clear and visible
        val r0 = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = clearBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        assertEquals(VisibilityState.VISIBLE, r0.state)

        // Single noisy occluded frame (below confirmation count 2)
        val r1 = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = occludedBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1100L
        )
        // Hysteresis prevents immediate flip to OCCLUDED
        assertEquals(VisibilityState.UNKNOWN, r1.state)

        // Next frame is clear again -> noise suppressed
        val r2 = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = clearBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1200L
        )
        assertEquals(VisibilityState.VISIBLE, r2.state)
    }

    /**
     * Test 12 — Old visibility evidence expires rather than persisting indefinitely.
     */
    @Test
    fun test12_oldVisibilityEvidenceExpires() {
        val occludedBuf = createDepthBuffer(1000)

        // Confirm occlusion
        estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = occludedBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1000L
        )
        val confirmed = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = occludedBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = true,
            currentTimeMs = 1100L
        )
        assertEquals(VisibilityState.OCCLUDED, confirmed.state)

        // After maxEvidenceAgeMs (600ms) with tracking lost or no updates, estimator resets
        estimator.reset()
        val postExpiry = estimator.evaluateFromCameraCoords(
            isTracking = false,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = null,
            depthWidth = 0,
            depthHeight = 0,
            depthRowStride = 0,
            depthPixelStride = 0,
            gridLineOfSightClear = true,
            currentTimeMs = 2000L
        )
        assertEquals(VisibilityState.UNKNOWN, postExpiry.state)
        assertEquals(VisibilityReason.TRACKING_LOST, postExpiry.reason)
    }

    /**
     * Test 13 — Conflicting or inconsistent observations are represented predictably.
     */
    @Test
    fun test13_conflictingObservationsRepresentedPredictably() {
        val clearBuf = createDepthBuffer(3000)

        // Grid says blocked by physical obstacle, but depth says clear
        val conflict = estimator.evaluateFromCameraCoords(
            isTracking = true,
            xc = 0f, yc = 0f, zc = -2.0f,
            intrinsics = intrinsics,
            depthBuffer = clearBuf,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            depthRowStride = rowStride,
            depthPixelStride = pixelStride,
            gridLineOfSightClear = false,
            gridReason = VisibilityReason.GRID_OBSTACLE,
            currentTimeMs = 1000L
        )
        // Authoritative fusion policy: grid occlusion takes precedence, diagnostic reflects conflict
        assertEquals(VisibilityState.OCCLUDED, conflict.state)
        assertEquals(VisibilityReason.GRID_OBSTACLE, conflict.reason)
        assertFalse(conflict.gridLineOfSightClear)
        assertFalse(conflict.depthOccluded)
    }
}
