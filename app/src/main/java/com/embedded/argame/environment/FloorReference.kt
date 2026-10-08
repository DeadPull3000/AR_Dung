package com.embedded.argame.environment

import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState

/**
 * Tracks and maintains a stable floor reference coordinate frame
 * anchored to an ARCore upward-facing horizontal plane.
 *
 * Coordinates in Floor Space:
 * - Origin (0, 0, 0): Center of the locked floor plane.
 * - +Y: Upward floor normal (perpendicular to floor).
 * - +X, +Z: Planar horizontal axes spanning the floor surface.
 */
class FloorReference {

    companion object {
        private const val TAG = "FloorReference"
        private const val MIN_FLOOR_AREA_SQ_METERS = 0.15f
        private const val PREFERRED_FLOOR_AREA_SQ_METERS = 0.30f
    }

    var isTracking: Boolean = false
        private set

    var floorHeightY: Float = 0f
        private set

    var floorArea: Float = 0f
        private set

    var floorExtentX: Float = 0f
        private set

    var floorExtentZ: Float = 0f
        private set

    private var lockedPlane: Plane? = null
    var referencePose: Pose? = null
        private set

    // Pre-allocated matrices to avoid GC allocation during perception loops
    private val matrixFloorToWorld = FloatArray(16)
    private val matrixWorldToFloor = FloatArray(16)
    private val matrixCamToWorld = FloatArray(16)
    val matrixCamToFloor = FloatArray(16)

    /**
     * Evaluates available ARCore planes and updates or establishes the floor reference.
     * Returns true if a valid floor frame is currently active.
     */
    fun updateFloorReference(allPlanes: Collection<Plane>, cameraPose: Pose): Boolean {
        val cameraY = cameraPose.ty()

        // 1. If currently locked, check if locked plane is still healthy or subsumed
        lockedPlane?.let { plane ->
            // If the plane was subsumed by a larger merged plane, follow the subsuming parent
            val subsumedBy = plane.subsumedBy
            if (subsumedBy != null && subsumedBy.trackingState == TrackingState.TRACKING) {
                Log.i(TAG, "Floor plane subsumed. Migrating to parent plane.")
                lockedPlane = subsumedBy
            }

            val active = lockedPlane!!
            if (active.trackingState == TrackingState.TRACKING) {
                val pose = active.centerPose
                referencePose = pose
                floorHeightY = pose.ty()
                floorExtentX = active.extentX
                floorExtentZ = active.extentZ
                floorArea = floorExtentX * floorExtentZ
                isTracking = true

                // Update transformation matrices
                pose.toMatrix(matrixFloorToWorld, 0)
                pose.inverse().toMatrix(matrixWorldToFloor, 0)
                cameraPose.toMatrix(matrixCamToWorld, 0)
                Matrix.multiplyMM(matrixCamToFloor, 0, matrixWorldToFloor, 0, matrixCamToWorld, 0)
                return true
            } else if (active.trackingState == TrackingState.STOPPED) {
                Log.w(TAG, "Locked floor plane tracking STOPPED. Searching for new candidate.")
                lockedPlane = null
                isTracking = false
            }
        }

        // 2. Search for the best candidate floor plane using deterministic geometric criteria:
        // - Upward-facing horizontal plane
        // - Currently TRACKING and not subsumed
        // - Below the camera height
        // - Lowest ground-like Y position among planes with sufficient area
        val candidates = allPlanes.filter { p ->
            p.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            p.trackingState == TrackingState.TRACKING &&
            p.subsumedBy == null &&
            (p.extentX * p.extentZ) >= MIN_FLOOR_AREA_SQ_METERS &&
            p.centerPose.ty() < cameraY
        }

        if (candidates.isEmpty()) {
            isTracking = false
            return false
        }

        // Prefer planes with significant area (>= 0.30 m²), selecting the lowest Y (floor-level).
        // If none have >= 0.30 m², take the lowest Y among all candidates.
        val largePlanes = candidates.filter { (it.extentX * it.extentZ) >= PREFERRED_FLOOR_AREA_SQ_METERS }
        val bestFloor = if (largePlanes.isNotEmpty()) {
            largePlanes.minByOrNull { it.centerPose.ty() }!!
        } else {
            candidates.minByOrNull { it.centerPose.ty() }!!
        }

        lockedPlane = bestFloor
        val pose = bestFloor.centerPose
        referencePose = pose
        floorHeightY = pose.ty()
        floorExtentX = bestFloor.extentX
        floorExtentZ = bestFloor.extentZ
        floorArea = floorExtentX * floorExtentZ
        isTracking = true

        pose.toMatrix(matrixFloorToWorld, 0)
        pose.inverse().toMatrix(matrixWorldToFloor, 0)
        cameraPose.toMatrix(matrixCamToWorld, 0)
        Matrix.multiplyMM(matrixCamToFloor, 0, matrixWorldToFloor, 0, matrixCamToWorld, 0)

        Log.i(TAG, "Established floor reference: Y=%.3fm, Area=%.2fm²".format(floorHeightY, floorArea))
        return true
    }

    /**
     * Returns the 4x4 Floor-to-World model matrix for rendering world-space floor elements.
     */
    fun getFloorToWorldMatrix(outMatrix: FloatArray, offset: Int = 0) {
        System.arraycopy(matrixFloorToWorld, 0, outMatrix, offset, 16)
    }

    /**
     * Resets the floor reference lock.
     */
    fun reset() {
        lockedPlane = null
        referencePose = null
        isTracking = false
        floorHeightY = 0f
        floorArea = 0f
    }
}
