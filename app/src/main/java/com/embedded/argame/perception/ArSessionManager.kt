package com.embedded.argame.perception

import android.app.Activity
import android.util.Log
import android.view.Display
import android.graphics.PointF
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the ARCore Session lifecycle, configuration, frame updates,
 * and tracks detected spatial planes.
 * Keeps ARCore perception logic cleanly separated from Activity and Renderer.
 */
class ArSessionManager(private val activity: Activity) {

    companion object {
        private const val TAG = "ArSessionManager"
    }

    var session: Session? = null
        private set

    private var installRequested = false
    private var isSessionConfigured = false

    // Cached diagnostics to prevent object allocation churn
    private val lastPoseTranslation = FloatArray(3)
    private val lastPoseRotation = FloatArray(4)

    // Thread-safe list of active planes for rendering
    private val activePlanesList = mutableListOf<Plane>()
    private val activePlanesLock = Any()

    // Hit testing and single test Anchor state
    private val pendingTap = AtomicReference<PointF?>(null)
    private var activeAnchor: Anchor? = null
    private var activeAnchorPlaneType = TrackedPlaneType.UNKNOWN
    private var activeAnchorStatus = AnchorStatus.NONE
    private var lastHitMessage = "Tap detected plane to place 3D marker"
    private val anchorLock = Any()

    interface SessionListener {
        fun onSessionInitialized(session: Session)
        fun onSessionError(error: String, fatal: Boolean)
        fun onTrackingUpdated(diagnostics: TrackingDiagnostics)
    }

    var listener: SessionListener? = null

    /**
     * Attempts to create and configure the ARCore session.
     * Handles ARCore APK installation check and requests install if needed.
     * Returns true if session is ready to be resumed.
     */
    fun resumeSession(): Boolean {
        if (session == null) {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            Log.d(TAG, "ARCore availability: $availability")

            try {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        Log.i(TAG, "ARCore install requested from Google Play Services")
                        installRequested = true
                        return false
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Log.i(TAG, "ARCore is installed. Creating Session.")
                    }
                }

                // Create native session
                val newSession = Session(activity)
                configureSession(newSession)
                session = newSession
                listener?.onSessionInitialized(newSession)
                Log.i(TAG, "ARCore Session created successfully.")
            } catch (e: UnavailableArcoreNotInstalledException) {
                notifyError("ARCore is not installed.", true)
                return false
            } catch (e: UnavailableUserDeclinedInstallationException) {
                notifyError("User declined ARCore installation.", true)
                return false
            } catch (e: UnavailableApkTooOldException) {
                notifyError("ARCore APK is outdated. Please update Google Play Services for AR.", true)
                return false
            } catch (e: UnavailableSdkTooOldException) {
                notifyError("App SDK is too old for this ARCore version.", true)
                return false
            } catch (e: UnavailableDeviceNotCompatibleException) {
                notifyError("This device does not support ARCore.", true)
                return false
            } catch (e: Exception) {
                notifyError("Failed to create ARCore session: ${e.message}", true)
                return false
            }
        }

        // Resume active session
        try {
            session?.resume()
            Log.i(TAG, "ARCore Session resumed.")
            return true
        } catch (e: CameraNotAvailableException) {
            notifyError("Camera not available. Another app may be using the camera.", false)
            session = null
            return false
        } catch (e: Exception) {
            notifyError("Failed to resume ARCore session: ${e.message}", false)
            return false
        }
    }

    /**
     * Configures the Session with horizontal and vertical plane finding and auto-focus.
     */
    private fun configureSession(session: Session) {
        val config = Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
        }
        session.configure(config)
        isSessionConfigured = true
        Log.i(TAG, "ARCore Session configured: planeFinding=HORIZONTAL_AND_VERTICAL, focus=AUTO")
    }

    /**
     * Pauses the ARCore session and releases the camera resource.
     */
    fun pauseSession() {
        session?.let {
            try {
                it.pause()
                Log.i(TAG, "ARCore Session paused.")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing ARCore session", e)
            }
        }
    }

    /**
     * Destroys and closes the ARCore session.
     */
    fun destroySession() {
        session?.let {
            try {
                it.close()
                Log.i(TAG, "ARCore Session closed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing ARCore session", e)
            }
        }
        session = null
        synchronized(activePlanesLock) {
            activePlanesList.clear()
        }
        synchronized(anchorLock) {
            activeAnchor?.detach()
            activeAnchor = null
            activeAnchorStatus = AnchorStatus.NONE
        }
    }

    /**
     * Updates display geometry (viewport dimensions and screen orientation).
     */
    fun setDisplayGeometry(rotation: Int, width: Int, height: Int) {
        session?.setDisplayGeometry(rotation, width, height)
    }

    /**
     * Sets the external OES texture name for the camera feed.
     */
    fun setCameraTextureName(textureId: Int) {
        session?.setCameraTextureName(textureId)
    }

    /**
     * Called on the GL render thread on every frame.
     * Updates ARCore state, processes queued screen taps, and returns the current Frame.
     */
    fun updateFrame(): Frame? {
        val currentSession = session ?: return null
        return try {
            val frame = currentSession.update()
            processQueuedTap(frame)
            extractDiagnostics(frame, currentSession)
            frame
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available during frame update")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during ARCore session.update()", e)
            null
        }
    }

    /**
     * Enqueues a screen tap coordinate (in pixels) for hit-testing on the GL render thread.
     */
    fun queueTap(x: Float, y: Float) {
        pendingTap.set(PointF(x, y))
    }

    /**
     * Safely detaches and clears the active spatial Anchor.
     */
    fun resetAnchor() {
        synchronized(anchorLock) {
            activeAnchor?.detach()
            activeAnchor = null
            activeAnchorPlaneType = TrackedPlaneType.UNKNOWN
            activeAnchorStatus = AnchorStatus.NONE
            lastHitMessage = "Marker reset by user"
            Log.i(TAG, "Anchor reset by user.")
        }
    }

    /**
     * Returns the active ARCore Anchor if one exists and is tracking.
     */
    fun getActiveAnchor(): Anchor? {
        synchronized(anchorLock) {
            return activeAnchor
        }
    }

    /**
     * Returns a thread-safe snapshot of currently active, non-subsumed planes.
     */
    fun getActivePlanes(): List<Plane> {
        synchronized(activePlanesLock) {
            return activePlanesList.toList()
        }
    }

    /**
     * Processes any user tap queued from the UI thread by performing an ARCore hit-test.
     */
    private fun processQueuedTap(frame: Frame) {
        val tap = pendingTap.getAndSet(null) ?: return

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            synchronized(anchorLock) {
                lastHitMessage = "Tracking not ready (PAUSED)"
            }
            Log.w(TAG, "Tap ignored: Camera is not in TRACKING state")
            return
        }

        val hitResults = frame.hitTest(tap.x, tap.y)
        if (hitResults.isEmpty()) {
            synchronized(anchorLock) {
                lastHitMessage = "No surface detected at tap"
            }
            Log.i(TAG, "Hit test returned 0 results at screen (${tap.x}, ${tap.y})")
            return
        }

        data class PlaneHitCandidate(
            val hitResult: HitResult,
            val plane: Plane,
            val planeType: TrackedPlaneType,
            val priority: Int
        )

        val candidates = mutableListOf<PlaneHitCandidate>()

        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.trackingState == TrackingState.TRACKING && trackable.subsumedBy == null) {
                val inPolygon = trackable.isPoseInPolygon(hit.hitPose)
                val inExtents = trackable.isPoseInExtents(hit.hitPose)
                if (!inPolygon && !inExtents) continue

                val type = when (trackable.type) {
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> TrackedPlaneType.HORIZONTAL_UPWARD_FACING
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> TrackedPlaneType.HORIZONTAL_DOWNWARD_FACING
                    Plane.Type.VERTICAL -> TrackedPlaneType.VERTICAL
                    else -> TrackedPlaneType.UNKNOWN
                }

                val priority = when {
                    inPolygon && trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING -> 1
                    inPolygon && trackable.type == Plane.Type.VERTICAL -> 2
                    inPolygon && trackable.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING -> 3
                    inPolygon -> 4
                    else -> 5
                }

                candidates.add(PlaneHitCandidate(hit, trackable, type, priority))
            }
        }

        if (candidates.isEmpty()) {
            synchronized(anchorLock) {
                lastHitMessage = "Hit not on tracked plane boundary"
            }
            Log.i(TAG, "Hit test had ${hitResults.size} hits, but none on an active non-subsumed Plane")
            return
        }

        val bestCandidate = candidates.minWithOrNull(compareBy({ it.priority }, { it.hitResult.distance }))
        if (bestCandidate != null) {
            synchronized(anchorLock) {
                activeAnchor?.detach()

                val newAnchor = bestCandidate.hitResult.createAnchor()
                activeAnchor = newAnchor
                activeAnchorPlaneType = bestCandidate.planeType
                activeAnchorStatus = AnchorStatus.TRACKING
                val p = newAnchor.pose
                lastHitMessage = String.format(Locale.US, "Anchor on %s (%.2fm)", bestCandidate.planeType, bestCandidate.hitResult.distance)
                Log.i(TAG, "Created Anchor at world pose [X=%.3f, Y=%.3f, Z=%.3f] on %s".format(Locale.US, p.tx(), p.ty(), p.tz(), bestCandidate.planeType))
            }
        }
    }

    /**
     * Extracts camera pose, tracking state, and plane geometry.
     */
    private fun extractDiagnostics(frame: Frame, currentSession: Session) {
        val camera: Camera = frame.camera
        val arTrackingState = camera.trackingState

        val status = when (arTrackingState) {
            TrackingState.TRACKING -> TrackingStatus.TRACKING
            TrackingState.PAUSED -> TrackingStatus.PAUSED
            TrackingState.STOPPED -> TrackingStatus.STOPPED
            else -> TrackingStatus.NOT_INITIALIZED
        }

        var failureReason = ""
        if (arTrackingState == TrackingState.PAUSED) {
            failureReason = when (camera.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Insufficient Light"
                TrackingFailureReason.EXCESSIVE_MOTION -> "Excessive Motion"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Insufficient Features"
                TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera Unavailable"
                TrackingFailureReason.BAD_STATE -> "Bad State"
                else -> "Initializing Tracking"
            }
        }

        val cameraPose: Pose = camera.pose
        cameraPose.getTranslation(lastPoseTranslation, 0)
        cameraPose.getRotationQuaternion(lastPoseRotation, 0)
        val cameraY = lastPoseTranslation[1]

        // --- Spatial Plane Processing ---
        val allPlanes = currentSession.getAllTrackables(Plane::class.java)
        var totalActivePlanes = 0
        var horizUpCount = 0
        var horizDownCount = 0
        var vertCount = 0

        var maxArea = 0f
        var maxExtentX = 0f
        var maxExtentZ = 0f
        var candidateFloorHeightY = 0f
        var hasCandidateFloor = false

        val currentActiveList = mutableListOf<Plane>()

        for (plane in allPlanes) {
            // Filter out planes that have been subsumed (merged into a larger plane)
            // or are not currently actively tracking
            if (plane.subsumedBy != null || plane.trackingState != TrackingState.TRACKING) {
                continue
            }

            totalActivePlanes++
            currentActiveList.add(plane)

            when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> {
                    horizUpCount++
                    val area = plane.extentX * plane.extentZ
                    if (area > maxArea) {
                        maxArea = area
                        maxExtentX = plane.extentX
                        maxExtentZ = plane.extentZ

                        // Candidate large surface heuristic:
                        // Large upward horizontal plane situated below the camera
                        val planeCenterY = plane.centerPose.ty()
                        if (area >= 0.20f && planeCenterY < cameraY) {
                            hasCandidateFloor = true
                            candidateFloorHeightY = planeCenterY
                        }
                    }
                }
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> horizDownCount++
                Plane.Type.VERTICAL -> vertCount++
                else -> {}
            }
        }

        synchronized(activePlanesLock) {
            activePlanesList.clear()
            activePlanesList.addAll(currentActiveList)
        }

        val planeDiagnostics = PlaneDiagnostics(
            totalPlanes = totalActivePlanes,
            horizontalUpwardCount = horizUpCount,
            horizontalDownwardCount = horizDownCount,
            verticalCount = vertCount,
            largestPlaneWidth = maxExtentX,
            largestPlaneDepth = maxExtentZ,
            largestPlaneArea = maxArea,
            hasCandidateLargeSurface = hasCandidateFloor,
            candidateSurfaceHeightY = candidateFloorHeightY
        )

        // --- Spatial Anchor Telemetry ---
        val anchorDiagnostics: AnchorDiagnostics
        synchronized(anchorLock) {
            val anchor = activeAnchor
            if (anchor != null) {
                val anchorTracking = anchor.trackingState
                if (anchorTracking == TrackingState.TRACKING) {
                    val aPose = anchor.pose
                    val ax = aPose.tx()
                    val ay = aPose.ty()
                    val az = aPose.tz()
                    val dx = ax - lastPoseTranslation[0]
                    val dy = ay - lastPoseTranslation[1]
                    val dz = az - lastPoseTranslation[2]
                    val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                    anchorDiagnostics = AnchorDiagnostics(
                        status = AnchorStatus.TRACKING,
                        posX = ax,
                        posY = ay,
                        posZ = az,
                        distanceMeters = dist,
                        surfaceType = activeAnchorPlaneType,
                        lastHitMessage = lastHitMessage
                    )
                } else if (anchorTracking == TrackingState.STOPPED) {
                    anchor.detach()
                    activeAnchor = null
                    activeAnchorStatus = AnchorStatus.STOPPED
                    anchorDiagnostics = AnchorDiagnostics(
                        status = AnchorStatus.STOPPED,
                        surfaceType = activeAnchorPlaneType,
                        lastHitMessage = "Anchor STOPPED tracking"
                    )
                } else {
                    anchorDiagnostics = AnchorDiagnostics(
                        status = AnchorStatus.PAUSED,
                        surfaceType = activeAnchorPlaneType,
                        lastHitMessage = "Anchor tracking PAUSED"
                    )
                }
            } else {
                anchorDiagnostics = AnchorDiagnostics(
                    status = activeAnchorStatus,
                    lastHitMessage = lastHitMessage
                )
            }
        }

        val diagnostics = TrackingDiagnostics(
            status = status,
            failureReason = failureReason,
            posX = lastPoseTranslation[0],
            posY = lastPoseTranslation[1],
            posZ = lastPoseTranslation[2],
            qX = lastPoseRotation[0],
            qY = lastPoseRotation[1],
            qZ = lastPoseRotation[2],
            qW = lastPoseRotation[3],
            frameTimestampNs = frame.timestamp,
            planes = planeDiagnostics,
            anchor = anchorDiagnostics
        )

        listener?.onTrackingUpdated(diagnostics)
    }

    private fun notifyError(message: String, fatal: Boolean) {
        Log.e(TAG, "ARCore Session Error: $message (fatal=$fatal)")
        listener?.onSessionError(message, fatal)
    }
}
