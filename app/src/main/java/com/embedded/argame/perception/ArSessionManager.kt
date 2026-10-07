package com.embedded.argame.perception

import android.app.Activity
import android.util.Log
import android.view.Display
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
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
     * Updates ARCore state and returns the current Frame.
     */
    fun updateFrame(): Frame? {
        val currentSession = session ?: return null
        return try {
            val frame = currentSession.update()
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
     * Returns a thread-safe snapshot of currently active, non-subsumed planes.
     */
    fun getActivePlanes(): List<Plane> {
        synchronized(activePlanesLock) {
            return activePlanesList.toList()
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
            planes = planeDiagnostics
        )

        listener?.onTrackingUpdated(diagnostics)
    }

    private fun notifyError(message: String, fatal: Boolean) {
        Log.e(TAG, "ARCore Session Error: $message (fatal=$fatal)")
        listener?.onSessionError(message, fatal)
    }
}
