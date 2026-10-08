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
import com.google.ar.core.Coordinates2d
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.GridDiagnostics
import com.embedded.argame.environment.GridDisplayMode
import com.embedded.argame.environment.UnknownCostPolicy
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.rendering.DepthHeatmapRenderer
import com.embedded.argame.rendering.OccupancyGridRenderer
import com.embedded.argame.rendering.PathRenderer
import com.embedded.argame.navigation.AStarPathfinder
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

enum class NavSelectionMode {
    OFF,
    SET_START,
    SET_GOAL;

    fun next(): NavSelectionMode = when (this) {
        SET_START -> SET_GOAL
        SET_GOAL -> OFF
        OFF -> SET_START
    }
}

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

    // Depth perception state
    var isDepthSupported = false
        private set
    var isDepthViewEnabled = false

    private var currentDepthDiagnostics = DepthDiagnostics(status = DepthStatus.WAITING)
    private var lastDepthSampleTimeNs = 0L
    private val depthSampleIntervalNs = 100_000_000L // 10 Hz throttled depth sampling
    private var depthUpdateFrameCount = 0
    private var lastDepthRateTimestampNs = 0L
    private var currentDepthHz = 0f

    // Heatmap buffer for DepthHeatmapRenderer (max 320x240x4 bytes)
    private val heatmapByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(320 * 240 * 4).order(ByteOrder.nativeOrder())
    private var heatmapWidth = 0
    private var heatmapHeight = 0
    private var hasNewHeatmapData = false
    private val heatmapLock = Any()

    // Persistent Raw Depth diagnostic status
    private var lastRawDepthAvailable = false
    private var lastRawDepthValidPercent = 0f
    private var lastRawDepthTimestampNs = 0L

    // Cached point sampling arrays to prevent allocation churn
    private val sampleInCoords = FloatArray(2)
    private val sampleOutCoords = FloatArray(2)

    // 2.5D Occupancy Grid and Floor Reference
    val floorReference = FloorReference()
    val occupancyGrid = OccupancyGrid(cellSizeMeters = 0.10f, widthMeters = 8.0f, depthMeters = 8.0f)
    var isGridViewEnabled = true
    private var currentGridDiagnostics = GridDiagnostics()

    // Milestone 8 A* Navigation Engine
    val pathfinder = AStarPathfinder(occupancyGrid.numCellsX, occupancyGrid.numCellsZ, occupancyGrid.cellSizeMeters)
    var navSelectionMode: NavSelectionMode = NavSelectionMode.SET_START
    var navStartCol: Int = -1
    var navStartRow: Int = -1
    var navGoalCol: Int = -1
    var navGoalRow: Int = -1
    var hasNavStart: Boolean = false
    var hasNavGoal: Boolean = false
    private val navStartWorld = FloatArray(3)
    private val navGoalWorld = FloatArray(3)
    var latestPathResult: PathResult? = null
        private set
    private var hasNewPathData = false
    private val pathLock = Any()
    private val navExecutor = Executors.newSingleThreadExecutor()

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
     * Configures the Session with horizontal/vertical planes, auto-focus, and Depth API (if supported).
     */
    private fun configureSession(session: Session) {
        isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            depthMode = if (isDepthSupported) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
        }
        session.configure(config)
        isSessionConfigured = true
        Log.i(TAG, "ARCore Session configured: planeFinding=HORIZONTAL_AND_VERTICAL, focus=AUTO, depthMode=${config.depthMode}, isDepthSupported=$isDepthSupported")
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
        floorReference.reset()
        occupancyGrid.reset()
        clearPath()
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
     * Updates ARCore state, processes queued screen taps, samples depth, and returns the current Frame.
     */
    fun updateFrame(): Frame? {
        val currentSession = session ?: return null
        return try {
            val frame = currentSession.update()
            processQueuedTap(frame)
            processDepthFrame(frame)
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
     * Synchronizes the latest processed false-color depth buffer with the GL DepthHeatmapRenderer.
     */
    fun syncDepthHeatmap(renderer: DepthHeatmapRenderer) {
        synchronized(heatmapLock) {
            if (hasNewHeatmapData && heatmapWidth > 0 && heatmapHeight > 0) {
                renderer.updateTexture(heatmapWidth, heatmapHeight, heatmapByteBuffer)
                hasNewHeatmapData = false
            }
        }
    }

    /**
     * Synchronizes the latest processed occupancy grid texture buffer with the GL OccupancyGridRenderer.
     */
    fun syncOccupancyGrid(renderer: OccupancyGridRenderer) {
        renderer.updateTexture(occupancyGrid)
    }

    /**
     * Resets the 2.5D occupancy grid map evidence and states.
     */
    fun resetGrid() {
        occupancyGrid.reset()
        floorReference.reset()
        clearPath()
        Log.i(TAG, "Occupancy grid, floor reference, and navigation path reset by user.")
    }

    /**
     * Cycles the active navigation selection mode: SET_START -> SET_GOAL -> OFF -> SET_START.
     */
    fun cycleNavMode(): NavSelectionMode {
        navSelectionMode = navSelectionMode.next()
        synchronized(anchorLock) {
            lastHitMessage = when (navSelectionMode) {
                NavSelectionMode.SET_START -> "NAV: Tap floor for START point"
                NavSelectionMode.SET_GOAL -> "NAV: Tap floor for GOAL point"
                NavSelectionMode.OFF -> "ANCHOR: Tap for 3D marker"
            }
        }
        return navSelectionMode
    }

    /**
     * Executes A* pathfinding asynchronously on the navigation executor pool.
     */
    fun requestPathSearch() {
        if (!hasNavStart || !hasNavGoal) return
        val sCol = navStartCol
        val sRow = navStartRow
        val gCol = navGoalCol
        val gRow = navGoalRow

        navExecutor.execute {
            val result = occupancyGrid.withLock {
                pathfinder.findPath(
                    startCol = sCol,
                    startRow = sRow,
                    goalCol = gCol,
                    goalRow = gRow,
                    costGrid = occupancyGrid.traversalCostGrid,
                    inflatedStates = occupancyGrid.inflatedCellStates,
                    occupancyGrid = occupancyGrid,
                    floorReference = floorReference,
                    gridVersion = occupancyGrid.gridVersion
                )
            }
            synchronized(pathLock) {
                latestPathResult = result
                hasNewPathData = true
            }
            synchronized(anchorLock) {
                lastHitMessage = "A* " + result.status.name + " (" + result.searchTimeMs + "ms)"
            }
            Log.i(TAG, "A* path calculated: status=${result.status}, raw=${result.rawCellCount}, smoothed=${result.smoothedCellCount}, cost=${result.totalCost}, time=${result.searchTimeMs}ms")
        }
    }

    /**
     * Re-runs A* pathfinding with current start and goal against the latest traversal cost grid.
     */
    fun replanPath() {
        if (hasNavStart && hasNavGoal) {
            requestPathSearch()
        }
    }

    /**
     * Clears start, goal, and active path.
     */
    fun clearPath() {
        synchronized(pathLock) {
            hasNavStart = false
            hasNavGoal = false
            navStartCol = -1
            navStartRow = -1
            navGoalCol = -1
            navGoalRow = -1
            latestPathResult = null
            hasNewPathData = true
        }
        synchronized(anchorLock) {
            lastHitMessage = "Navigation path cleared"
        }
    }

    /**
     * Synchronizes latest computed path and endpoints with PathRenderer on GL thread.
     */
    fun syncPath(renderer: PathRenderer) {
        synchronized(pathLock) {
            if (hasNewPathData) {
                val res = latestPathResult
                if (res != null && res.status.isSuccessful && res.worldCoordinates.isNotEmpty()) {
                    renderer.updatePath(res)
                } else {
                    renderer.clear()
                    val startCoords = if (hasNavStart) navStartWorld else null
                    val goalCoords = if (hasNavGoal) navGoalWorld else null
                    renderer.setEndpoints(startCoords, goalCoords)
                }
                hasNewPathData = false
            }
        }
    }

    /**
     * Cycles the active visualization mode of the occupancy grid.
     */
    fun cycleGridDisplayMode(): GridDisplayMode = occupancyGrid.cycleDisplayMode()

    /**
     * Toggles the traversal cost policy for unobserved (UNKNOWN) grid space.
     */
    fun toggleUnknownPolicy(): UnknownCostPolicy = occupancyGrid.toggleUnknownPolicy()

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
            val hitPose = bestCandidate.hitResult.hitPose

            if (navSelectionMode != NavSelectionMode.OFF) {
                if (!floorReference.isTracking || floorReference.referencePose == null) {
                    synchronized(anchorLock) {
                        lastHitMessage = "Floor reference not yet established"
                    }
                    return
                }

                val refPose = floorReference.referencePose!!
                val localPose = refPose.inverse().compose(hitPose)
                val xf = localPose.tx()
                val zf = localPose.tz()
                val cell = occupancyGrid.floorToCell(xf, zf)

                if (cell == null) {
                    synchronized(anchorLock) {
                        lastHitMessage = "Tap outside 8m x 8m grid bounds"
                    }
                    return
                }

                val (c, r) = cell
                val cellState = occupancyGrid.getNavigationCellState(c, r)

                when (navSelectionMode) {
                    NavSelectionMode.SET_START -> {
                        navStartCol = c
                        navStartRow = r
                        hasNavStart = true
                        navStartWorld[0] = hitPose.tx()
                        navStartWorld[1] = hitPose.ty()
                        navStartWorld[2] = hitPose.tz()
                        navSelectionMode = NavSelectionMode.SET_GOAL
                        synchronized(anchorLock) {
                            lastHitMessage = String.format(Locale.US, "Start: [%d, %d] (%s). Tap floor for Goal.", c, r, cellState.name)
                        }
                        synchronized(pathLock) { hasNewPathData = true }
                        if (hasNavGoal) {
                            requestPathSearch()
                        }
                    }
                    NavSelectionMode.SET_GOAL -> {
                        navGoalCol = c
                        navGoalRow = r
                        hasNavGoal = true
                        navGoalWorld[0] = hitPose.tx()
                        navGoalWorld[1] = hitPose.ty()
                        navGoalWorld[2] = hitPose.tz()
                        navSelectionMode = NavSelectionMode.SET_START
                        synchronized(anchorLock) {
                            lastHitMessage = String.format(Locale.US, "Goal: [%d, %d] (%s). Planning A*...", c, r, cellState.name)
                        }
                        synchronized(pathLock) { hasNewPathData = true }
                        if (hasNavStart) {
                            requestPathSearch()
                        }
                    }
                    NavSelectionMode.OFF -> { /* Unreachable */ }
                }
                return
            }

            // Normal anchor placement when navSelectionMode == OFF
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

        // Update Floor Reference using the latest active planes list and camera pose
        floorReference.updateFloorReference(currentActiveList, cameraPose)

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
            anchor = anchorDiagnostics,
            depth = currentDepthDiagnostics,
            grid = currentGridDiagnostics.copy(
                isFloorTracking = floorReference.isTracking,
                floorStatus = if (floorReference.isTracking) "TRACKING" else "WAITING",
                floorHeightY = floorReference.floorHeightY,
                floorPlaneArea = floorReference.floorArea
            )
        )

        listener?.onTrackingUpdated(diagnostics)
    }

    /**
     * Acquires and samples the 16-bit depth image, computes depth statistics,
     * extracts point samples and camera intrinsics, and generates false-color heatmap pixels.
     * Throttled to 10 Hz to maintain ~60 FPS rendering.
     */
    private fun processDepthFrame(frame: Frame) {
        val now = System.nanoTime()
        if (now - lastDepthSampleTimeNs < depthSampleIntervalNs) {
            return
        }
        lastDepthSampleTimeNs = now

        if (!isDepthSupported) {
            currentDepthDiagnostics = DepthDiagnostics(
                isSupported = false,
                status = DepthStatus.UNSUPPORTED,
                statusMessage = "Depth API unsupported"
            )
            return
        }

        if (frame.camera.trackingState != TrackingState.TRACKING) {
            currentDepthDiagnostics = DepthDiagnostics(
                isSupported = true,
                status = DepthStatus.TRACKING_PAUSED,
                statusMessage = "Tracking paused"
            )
            return
        }

        try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val plane = depthImage.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder())
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val imgWidth = depthImage.width
                val imgHeight = depthImage.height
                val timestamp = depthImage.timestamp

                if (depthUpdateFrameCount % 10 == 0) {
                    val off = (imgHeight / 2) * rowStride + (imgWidth / 2) * pixelStride
                    val b0 = buffer.get(off).toInt() and 0xFF
                    val b1 = buffer.get(off + 1).toInt() and 0xFF
                    val le = (b1 shl 8) or b0
                    val be = (b0 shl 8) or b1
                    Log.i(TAG, "Depth bytes at center: b0=0x%02X, b1=0x%02X => LE=%d mm (%.2fm), BE=%d mm (%.2fm)".format(
                        Locale.US, b0, b1, le, le / 1000f, be, be / 1000f
                    ))
                }

                // 1. Grid downsampling (step = 2) for heatmap texture and depth statistics
                val step = 2
                val outW = imgWidth / step
                val outH = imgHeight / step

                var validSamples = 0
                var totalSamples = 0
                var minDepthMm = Int.MAX_VALUE
                var maxDepthMm = 0
                var sumDepthMm = 0L

                synchronized(heatmapLock) {
                    heatmapByteBuffer.clear()
                    for (y in 0 until imgHeight step step) {
                        for (x in 0 until imgWidth step step) {
                            totalSamples++
                            val offset = y * rowStride + x * pixelStride
                            val depthMm = buffer.getShort(offset).toInt() and 0xFFFF

                            // Filter valid metric depth range (0.1m to 20m)
                            if (depthMm in 100..20000) {
                                validSamples++
                                if (depthMm < minDepthMm) minDepthMm = depthMm
                                if (depthMm > maxDepthMm) maxDepthMm = depthMm
                                sumDepthMm += depthMm

                                // Calibrated False-Color Heatmap:
                                // Near (<0.7m): Red/Orange -> Mid (0.7-1.6m): Amber/Yellow -> Mid-Far (1.6-3.0m): Green/Cyan -> Far (>3.0m): Blue/Purple
                                val r: Byte
                                val g: Byte
                                val b: Byte
                                val a: Byte = 210.toByte()

                                when {
                                    depthMm < 700 -> {
                                        // 0.1m - 0.7m (Near foreground / Red -> Orange)
                                        val t = (depthMm - 100).coerceIn(0, 600) / 600f
                                        r = 255.toByte()
                                        g = (t * 130).toInt().toByte()
                                        b = 20.toByte()
                                    }
                                    depthMm < 1600 -> {
                                        // 0.7m - 1.6m (Tables, desk, nearby furniture / Orange -> Yellow)
                                        val t = (depthMm - 700) / 900f
                                        r = ((1f - t * 0.25f) * 255).toInt().toByte()
                                        g = 230.toByte()
                                        b = 30.toByte()
                                    }
                                    depthMm < 3000 -> {
                                        // 1.6m - 3.0m (Floor, middle range / Yellow-Green -> Emerald)
                                        val t = (depthMm - 1600) / 1400f
                                        r = ((1f - t) * 190).toInt().toByte()
                                        g = 240.toByte()
                                        b = (t * 180).toInt().toByte()
                                    }
                                    depthMm < 4500 -> {
                                        // 3.0m - 4.5m (Room walls / Cyan -> Blue)
                                        val t = (depthMm - 3000) / 1500f
                                        r = 20.toByte()
                                        g = ((1f - t * 0.7f) * 240).toInt().toByte()
                                        b = 255.toByte()
                                    }
                                    else -> {
                                        // > 4.5m (Far background / Indigo -> Purple)
                                        val t = ((depthMm - 4500) / 3000f).coerceIn(0f, 1f)
                                        r = (t * 180 + 30).toInt().toByte()
                                        g = 40.toByte()
                                        b = 255.toByte()
                                    }
                                }
                                heatmapByteBuffer.put(r).put(g).put(b).put(a)
                            } else {
                                // Invalid / no depth estimate: transparent pixel
                                heatmapByteBuffer.put(0.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())
                            }
                        }
                    }
                    heatmapByteBuffer.flip()
                    heatmapWidth = outW
                    heatmapHeight = outH
                    hasNewHeatmapData = true
                }

                val validPercent = if (totalSamples > 0) (validSamples * 100f / totalSamples) else 0f
                val minMeters = if (validSamples > 0) minDepthMm / 1000f else 0f
                val maxMeters = if (validSamples > 0) maxDepthMm / 1000f else 0f
                val meanMeters = if (validSamples > 0) (sumDepthMm / validSamples) / 1000f else 0f

                // 2. Specific Screen Point Sampling (Center, TL, TR, BL, BR)
                val testPoints = listOf(
                    Pair("Center", Pair(0.5f, 0.5f)),
                    Pair("Top-Left", Pair(0.25f, 0.25f)),
                    Pair("Top-Right", Pair(0.75f, 0.25f)),
                    Pair("Bottom-Left", Pair(0.25f, 0.75f)),
                    Pair("Bottom-Right", Pair(0.75f, 0.75f))
                )

                val pointSamples = mutableListOf<DepthPointSample>()
                var centerDepthMeters = 0f

                for ((label, normCoord) in testPoints) {
                    sampleInCoords[0] = normCoord.first
                    sampleInCoords[1] = normCoord.second
                    frame.transformCoordinates2d(
                        Coordinates2d.VIEW_NORMALIZED,
                        sampleInCoords,
                        Coordinates2d.IMAGE_PIXELS,
                        sampleOutCoords
                    )
                    val px = sampleOutCoords[0].toInt().coerceIn(0, imgWidth - 1)
                    val py = sampleOutCoords[1].toInt().coerceIn(0, imgHeight - 1)
                    val sampleOffset = py * rowStride + px * pixelStride
                    val ptMm = buffer.getShort(sampleOffset).toInt() and 0xFFFF
                    val ptM = ptMm / 1000f
                    val isValid = ptMm in 100..20000
                    if (label == "Center" && isValid) {
                        centerDepthMeters = ptM
                    }
                    pointSamples.add(DepthPointSample(label, normCoord.first, normCoord.second, ptM, isValid))
                }

                // 3. Update sampling frequency
                depthUpdateFrameCount++
                val rateElapsed = now - lastDepthRateTimestampNs
                if (rateElapsed >= 1_000_000_000L) {
                    currentDepthHz = (depthUpdateFrameCount * 1_000_000_000f) / rateElapsed
                    depthUpdateFrameCount = 0
                    lastDepthRateTimestampNs = now
                }

                // 4. Raw Depth Investigation (Check every 5 frames = ~2 Hz)
                if (depthUpdateFrameCount % 5 == 0) {
                    try {
                        frame.acquireRawDepthImage16Bits().use { rawImage ->
                            val rPlane = rawImage.planes[0]
                            val rBuf = rPlane.buffer.order(ByteOrder.nativeOrder())
                            val rRowStride = rPlane.rowStride
                            val rPixStride = rPlane.pixelStride
                            var rValid = 0
                            var rTotal = 0
                            for (ry in 0 until rawImage.height step 4) {
                                for (rx in 0 until rawImage.width step 4) {
                                    rTotal++
                                    val rOff = ry * rRowStride + rx * rPixStride
                                    val rDepth = rBuf.getShort(rOff).toInt() and 0xFFFF
                                    if (rDepth in 100..20000) {
                                        rValid++
                                    }
                                }
                            }
                            lastRawDepthValidPercent = if (rTotal > 0) (rValid * 100f / rTotal) else 0f
                            lastRawDepthAvailable = true
                            lastRawDepthTimestampNs = rawImage.timestamp

                            // Verify confidence image acquisition and release
                            try {
                                frame.acquireRawDepthConfidenceImage().use { }
                            } catch (ignored: Exception) {}
                        }
                    } catch (e: Exception) {
                        // Raw depth may not yet be available on this frame; retain last status
                    }
                }

                // 5. Camera Intrinsics
                val intrinsics = frame.camera.imageIntrinsics
                val intrinsicsData = CameraIntrinsicsData(
                    fx = intrinsics.focalLength[0],
                    fy = intrinsics.focalLength[1],
                    cx = intrinsics.principalPoint[0],
                    cy = intrinsics.principalPoint[1],
                    width = intrinsics.imageDimensions[0],
                    height = intrinsics.imageDimensions[1]
                )

                currentDepthDiagnostics = DepthDiagnostics(
                    isSupported = true,
                    status = DepthStatus.READY,
                    imageWidth = imgWidth,
                    imageHeight = imgHeight,
                    validSamplePercent = validPercent,
                    minDepthMeters = minMeters,
                    maxDepthMeters = maxMeters,
                    meanDepthMeters = meanMeters,
                    centerDepthMeters = centerDepthMeters,
                    rawDepthAvailable = lastRawDepthAvailable,
                    rawDepthValidPercent = lastRawDepthValidPercent,
                    depthUpdateHz = currentDepthHz,
                    timestampNs = timestamp,
                    pointSamples = pointSamples,
                    intrinsics = intrinsicsData,
                    statusMessage = "Depth READY (${imgWidth}x${imgHeight})"
                )

                // 6. Update 2.5D Occupancy Grid with 16-bit depth & floor frame
                if (floorReference.isTracking) {
                    currentGridDiagnostics = occupancyGrid.updateWithDepth(
                        depthBuffer = buffer,
                        depthWidth = imgWidth,
                        depthHeight = imgHeight,
                        rowStride = rowStride,
                        pixelStride = pixelStride,
                        intrinsics = intrinsicsData,
                        matrixCamToFloor = floorReference.matrixCamToFloor,
                        floorExtentX = floorReference.floorExtentX,
                        floorExtentZ = floorReference.floorExtentZ,
                        floorTracking = true
                    )
                } else {
                    currentGridDiagnostics = GridDiagnostics(
                        isFloorTracking = false,
                        floorStatus = "WAITING"
                    )
                }
            }
        } catch (e: NotYetAvailableException) {
            currentDepthDiagnostics = DepthDiagnostics(
                isSupported = true,
                status = DepthStatus.WAITING,
                statusMessage = "Depth WAITING"
            )
        } catch (e: NotTrackingException) {
            currentDepthDiagnostics = DepthDiagnostics(
                isSupported = true,
                status = DepthStatus.TRACKING_PAUSED,
                statusMessage = "Tracking paused"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Exception during depth acquisition: ${e.message}")
            currentDepthDiagnostics = DepthDiagnostics(
                isSupported = isDepthSupported,
                status = DepthStatus.ERROR,
                statusMessage = "Depth: ${e.message}"
            )
        }
    }

    private fun notifyError(message: String, fatal: Boolean) {
        Log.e(TAG, "ARCore Session Error: $message (fatal=$fatal)")
        listener?.onSessionError(message, fatal)
    }
}
