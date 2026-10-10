package com.embedded.argame

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.embedded.argame.ai.CreatureAIState
import com.embedded.argame.game.GameState
import com.embedded.argame.environment.GridDisplayMode
import com.embedded.argame.environment.UnknownCostPolicy
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.PathStatus
import com.embedded.argame.perception.AnchorStatus
import com.embedded.argame.perception.ArSessionManager
import com.embedded.argame.perception.DepthStatus
import com.embedded.argame.perception.NavSelectionMode
import com.embedded.argame.perception.TrackedPlaneType
import com.embedded.argame.perception.TrackingDiagnostics
import com.embedded.argame.perception.TrackingStatus
import com.embedded.argame.rendering.ArRenderer
import com.google.ar.core.Session
import java.util.Locale

class MainActivity : AppCompatActivity(), ArSessionManager.SessionListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val DIAGNOSTIC_UPDATE_INTERVAL_MS = 100L // 10 Hz UI updates
    }

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var sessionManager: ArSessionManager
    private lateinit var arRenderer: ArRenderer

    // UI Overlay elements
    private lateinit var tvArcoreStatus: TextView
    private lateinit var tvTrackingStatus: TextView
    private lateinit var tvPosePosition: TextView
    private lateinit var tvPoseRotation: TextView
    private lateinit var tvPlanesSummary: TextView
    private lateinit var tvLargestPlane: TextView
    private lateinit var tvCandidateFloor: TextView
    private lateinit var tvAnchorStatus: TextView
    private lateinit var tvAnchorPose: TextView
    private lateinit var tvAnchorSurface: TextView
    private lateinit var btnResetAnchor: Button
    private lateinit var tvDepthStatus: TextView
    private lateinit var tvDepthMetrics: TextView
    private lateinit var tvDepthRanges: TextView
    private lateinit var tvDepthRawIntrinsics: TextView
    private lateinit var btnToggleDepthView: Button
    private lateinit var tvGridStatus: TextView
    private lateinit var tvGridCounts: TextView
    private lateinit var tvGridMetrics: TextView
    private lateinit var btnToggleGridView: Button
    private lateinit var btnResetGrid: Button
    private lateinit var btnGridDisplayMode: Button
    private lateinit var btnToggleUnknownPolicy: Button
    private lateinit var tvNavStatus: TextView
    private lateinit var tvNavEndpoints: TextView
    private lateinit var tvNavMetrics: TextView
    private lateinit var btnNavMode: Button
    private lateinit var btnReplanPath: Button
    private lateinit var btnClearPath: Button
    private lateinit var tvAgentStatus: TextView
    private lateinit var tvAgentPose: TextView
    private lateinit var tvAgentNav: TextView
    private lateinit var btnSpawnAgent: Button
    private lateinit var btnPauseAgent: Button
    private lateinit var btnResetAgent: Button
    private lateinit var tvAiStatus: TextView
    private lateinit var tvAiDetails: TextView
    private lateinit var tvAiPerception: TextView
    private lateinit var btnToggleAi: Button
    private lateinit var btnResetAi: Button
    private lateinit var tvMissionStatus: TextView
    private lateinit var tvMissionDetails: TextView
    private lateinit var tvMissionGuidance: TextView
    private lateinit var btnGenerateMission: Button
    private lateinit var btnStartMission: Button
    private lateinit var btnPauseMission: Button
    private lateinit var btnRestartMission: Button
    private lateinit var tvPerformance: TextView
    private lateinit var permissionRationaleContainer: View
    private lateinit var btnGrantPermission: Button

    private var hasCameraPermission = false
    private var lastUiUpdateTime = 0L
    private var currentFps = 0.0f

    // Camera permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            Log.i(TAG, "Camera permission granted by user")
            permissionRationaleContainer.visibility = View.GONE
            resumeArPipeline()
        } else {
            Log.w(TAG, "Camera permission denied by user")
            handlePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        sessionManager = ArSessionManager(this).apply {
            listener = this@MainActivity
        }

        setupGlSurfaceView()
    }

    private fun initViews() {
        surfaceView = findViewById(R.id.surfaceView)
        tvArcoreStatus = findViewById(R.id.tvArcoreStatus)
        tvTrackingStatus = findViewById(R.id.tvTrackingStatus)
        tvPosePosition = findViewById(R.id.tvPosePosition)
        tvPoseRotation = findViewById(R.id.tvPoseRotation)
        tvPlanesSummary = findViewById(R.id.tvPlanesSummary)
        tvLargestPlane = findViewById(R.id.tvLargestPlane)
        tvCandidateFloor = findViewById(R.id.tvCandidateFloor)
        tvAnchorStatus = findViewById(R.id.tvAnchorStatus)
        tvAnchorPose = findViewById(R.id.tvAnchorPose)
        tvAnchorSurface = findViewById(R.id.tvAnchorSurface)
        btnResetAnchor = findViewById(R.id.btnResetAnchor)
        tvDepthStatus = findViewById(R.id.tvDepthStatus)
        tvDepthMetrics = findViewById(R.id.tvDepthMetrics)
        tvDepthRanges = findViewById(R.id.tvDepthRanges)
        tvDepthRawIntrinsics = findViewById(R.id.tvDepthRawIntrinsics)
        btnToggleDepthView = findViewById(R.id.btnToggleDepthView)
        tvGridStatus = findViewById(R.id.tvGridStatus)
        tvGridCounts = findViewById(R.id.tvGridCounts)
        tvGridMetrics = findViewById(R.id.tvGridMetrics)
        btnToggleGridView = findViewById(R.id.btnToggleGridView)
        btnResetGrid = findViewById(R.id.btnResetGrid)
        btnGridDisplayMode = findViewById(R.id.btnGridDisplayMode)
        btnToggleUnknownPolicy = findViewById(R.id.btnToggleUnknownPolicy)
        tvNavStatus = findViewById(R.id.tvNavStatus)
        tvNavEndpoints = findViewById(R.id.tvNavEndpoints)
        tvNavMetrics = findViewById(R.id.tvNavMetrics)
        btnNavMode = findViewById(R.id.btnNavMode)
        btnReplanPath = findViewById(R.id.btnReplanPath)
        btnClearPath = findViewById(R.id.btnClearPath)
        tvAgentStatus = findViewById(R.id.tvAgentStatus)
        tvAgentPose = findViewById(R.id.tvAgentPose)
        tvAgentNav = findViewById(R.id.tvAgentNav)
        btnSpawnAgent = findViewById(R.id.btnSpawnAgent)
        btnPauseAgent = findViewById(R.id.btnPauseAgent)
        btnResetAgent = findViewById(R.id.btnResetAgent)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        tvAiDetails = findViewById(R.id.tvAiDetails)
        tvAiPerception = findViewById(R.id.tvAiPerception)
        btnToggleAi = findViewById(R.id.btnToggleAi)
        btnResetAi = findViewById(R.id.btnResetAi)
        tvMissionStatus = findViewById(R.id.tvMissionStatus)
        tvMissionDetails = findViewById(R.id.tvMissionDetails)
        tvMissionGuidance = findViewById(R.id.tvMissionGuidance)
        btnGenerateMission = findViewById(R.id.btnGenerateMission)
        btnStartMission = findViewById(R.id.btnStartMission)
        btnPauseMission = findViewById(R.id.btnPauseMission)
        btnRestartMission = findViewById(R.id.btnRestartMission)
        tvPerformance = findViewById(R.id.tvPerformance)
        permissionRationaleContainer = findViewById(R.id.permissionRationaleContainer)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

        btnGenerateMission.setOnClickListener {
            val success = sessionManager.generateMission()
            if (!success) {
                val msg = sessionManager.getMissionSnapshot().statusMessage
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        btnStartMission.setOnClickListener {
            sessionManager.startMission()
        }

        btnPauseMission.setOnClickListener {
            sessionManager.toggleMissionPause()
        }

        btnRestartMission.setOnClickListener {
            sessionManager.restartMission()
        }

        btnSpawnAgent.setOnClickListener {
            sessionManager.spawnOrStartAgent()
        }

        btnPauseAgent.setOnClickListener {
            sessionManager.toggleAgentPause()
        }

        btnResetAgent.setOnClickListener {
            sessionManager.resetAgent()
        }

        btnToggleAi.setOnClickListener {
            val enabled = sessionManager.toggleCreatureAi()
            btnToggleAi.text = if (enabled) "AI: ENABLED" else "AI: DISABLED"
            btnToggleAi.setTextColor(if (enabled) getColor(R.color.accent_purple) else getColor(R.color.text_secondary))
        }

        btnResetAi.setOnClickListener {
            sessionManager.resetCreatureAi()
        }

        btnNavMode.setOnClickListener {
            val mode = sessionManager.cycleNavMode()
            btnNavMode.text = when (mode) {
                NavSelectionMode.SET_START -> "NAV: SET START"
                NavSelectionMode.SET_GOAL -> "NAV: SET GOAL"
                NavSelectionMode.OFF -> "NAV: OFF (ANCHOR)"
            }
            when (mode) {
                NavSelectionMode.SET_START -> btnNavMode.setTextColor(getColor(R.color.accent_green))
                NavSelectionMode.SET_GOAL -> btnNavMode.setTextColor(getColor(android.R.color.holo_red_light))
                NavSelectionMode.OFF -> btnNavMode.setTextColor(getColor(R.color.text_secondary))
            }
        }

        btnReplanPath.setOnClickListener {
            sessionManager.replanPath()
        }

        btnClearPath.setOnClickListener {
            sessionManager.clearPath()
        }

        btnResetAnchor.setOnClickListener {
            sessionManager.resetAnchor()
        }

        btnToggleDepthView.setOnClickListener {
            sessionManager.isDepthViewEnabled = !sessionManager.isDepthViewEnabled
            btnToggleDepthView.text = if (sessionManager.isDepthViewEnabled) {
                "DEPTH HEATMAP: ON"
            } else {
                "DEPTH HEATMAP: OFF"
            }
        }

        btnToggleGridView.setOnClickListener {
            sessionManager.isGridViewEnabled = !sessionManager.isGridViewEnabled
            btnToggleGridView.text = if (sessionManager.isGridViewEnabled) {
                "FLOOR GRID: ON"
            } else {
                "FLOOR GRID: OFF"
            }
        }

        btnResetGrid.setOnClickListener {
            sessionManager.resetGrid()
        }

        btnGridDisplayMode.setOnClickListener {
            val newMode = sessionManager.cycleGridDisplayMode()
            btnGridDisplayMode.text = "MODE: ${newMode.name}"
            when (newMode) {
                GridDisplayMode.RAW -> btnGridDisplayMode.setTextColor(getColor(android.R.color.holo_red_light))
                GridDisplayMode.FILTERED -> btnGridDisplayMode.setTextColor(getColor(android.R.color.holo_orange_light))
                GridDisplayMode.INFLATED -> btnGridDisplayMode.setTextColor(getColor(R.color.accent_yellow))
                GridDisplayMode.COST -> btnGridDisplayMode.setTextColor(getColor(R.color.accent_cyan))
            }
        }

        btnToggleUnknownPolicy.setOnClickListener {
            val newPolicy = sessionManager.toggleUnknownPolicy()
            btnToggleUnknownPolicy.text = if (newPolicy == UnknownCostPolicy.BLOCKED) "UNK: BLOCKED" else "UNK: PENALTY"
        }

        btnGrantPermission.setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestCameraPermission()
            } else {
                // Open App Settings if permission was permanently denied
                openAppSettings()
            }
        }
    }

    private fun setupGlSurfaceView() {
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(3) // OpenGL ES 3.0

        arRenderer = ArRenderer(this, sessionManager) { fps ->
            currentFps = fps
        }

        surfaceView.setRenderer(arRenderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Handle touch tap gestures on the AR camera surface
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                sessionManager.queueTap(e.x, e.y)
                return true
            }
            override fun onDown(e: MotionEvent): Boolean = true
        })

        surfaceView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            v.performClick()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestCameraPermission()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        sessionManager.pauseSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.destroySession()
    }

    private fun checkAndRequestCameraPermission() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCameraPermission) {
            permissionRationaleContainer.visibility = View.GONE
            resumeArPipeline()
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun handlePermissionDenied() {
        permissionRationaleContainer.visibility = View.VISIBLE
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            btnGrantPermission.text = "Open App Settings"
        } else {
            btnGrantPermission.text = "Grant Camera Permission"
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun resumeArPipeline() {
        val ready = sessionManager.resumeSession()
        if (ready) {
            surfaceView.onResume()
            tvArcoreStatus.text = "ARCore: READY (Active Session)"
        }
    }

    // --- ArSessionManager.SessionListener Implementation ---

    override fun onSessionInitialized(session: Session) {
        runOnUiThread {
            tvArcoreStatus.text = "ARCore: INITIALIZED"
        }
    }

    override fun onSessionError(error: String, fatal: Boolean) {
        runOnUiThread {
            tvArcoreStatus.text = "ARCore Error: $error"
            tvArcoreStatus.setTextColor(getColor(android.R.color.holo_red_light))
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onTrackingUpdated(diagnostics: TrackingDiagnostics) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdateTime < DIAGNOSTIC_UPDATE_INTERVAL_MS) {
            return
        }
        lastUiUpdateTime = now

        runOnUiThread {
            when (diagnostics.status) {
                TrackingStatus.TRACKING -> {
                    tvTrackingStatus.text = "Tracking: TRACKING"
                    tvTrackingStatus.setTextColor(getColor(R.color.accent_green))
                }
                TrackingStatus.PAUSED -> {
                    val reason = if (diagnostics.failureReason.isNotEmpty()) {
                        "PAUSED (${diagnostics.failureReason})"
                    } else {
                        "PAUSED"
                    }
                    tvTrackingStatus.text = "Tracking: $reason"
                    tvTrackingStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                }
                TrackingStatus.STOPPED -> {
                    tvTrackingStatus.text = "Tracking: STOPPED"
                    tvTrackingStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
                TrackingStatus.NOT_INITIALIZED -> {
                    tvTrackingStatus.text = "Tracking: NOT INITIALIZED"
                    tvTrackingStatus.setTextColor(getColor(R.color.text_secondary))
                }
            }

            // Display Position Translation (in meters relative to world origin)
            tvPosePosition.text = String.format(
                Locale.US,
                "X: %+.3f  Y: %+.3f  Z: %+.3f m",
                diagnostics.posX,
                diagnostics.posY,
                diagnostics.posZ
            )

            // Display Orientation Quaternion
            tvPoseRotation.text = String.format(
                Locale.US,
                "Q: [%.2f, %.2f, %.2f, %.2f]",
                diagnostics.qX,
                diagnostics.qY,
                diagnostics.qZ,
                diagnostics.qW
            )

            // Display Spatial Planes Telemetry
            val p = diagnostics.planes
            tvPlanesSummary.text = String.format(
                Locale.US,
                "Total: %d (Horiz ↑: %d | Horiz ↓: %d | Vert: %d)",
                p.totalPlanes,
                p.horizontalUpwardCount,
                p.horizontalDownwardCount,
                p.verticalCount
            )

            tvLargestPlane.text = String.format(
                Locale.US,
                "Largest: W: %.2fm  D: %.2fm  Area: %.2f m²",
                p.largestPlaneWidth,
                p.largestPlaneDepth,
                p.largestPlaneArea
            )

            if (p.hasCandidateLargeSurface) {
                tvCandidateFloor.text = String.format(
                    Locale.US,
                    "Candidate Surface: YES (Y: %+.2fm, Area: %.2fm²)",
                    p.candidateSurfaceHeightY,
                    p.largestPlaneArea
                )
                tvCandidateFloor.setTextColor(getColor(R.color.accent_green))
            } else {
                tvCandidateFloor.text = "Candidate Surface: NO (Scanning...)"
                tvCandidateFloor.setTextColor(getColor(R.color.text_secondary))
            }

            // Display Spatial Anchor & 3D Marker Telemetry
            val a = diagnostics.anchor
            when (a.status) {
                AnchorStatus.TRACKING -> {
                    tvAnchorStatus.text = "Anchor: TRACKING"
                    tvAnchorStatus.setTextColor(getColor(R.color.accent_green))
                    tvAnchorPose.text = String.format(
                        Locale.US,
                        "Pos: X: %+.3f  Y: %+.3f  Z: %+.3f m",
                        a.posX,
                        a.posY,
                        a.posZ
                    )
                    val surfaceName = when (a.surfaceType) {
                        TrackedPlaneType.HORIZONTAL_UPWARD_FACING -> "Horizontal ↑"
                        TrackedPlaneType.HORIZONTAL_DOWNWARD_FACING -> "Horizontal ↓"
                        TrackedPlaneType.VERTICAL -> "Vertical"
                        else -> "Plane"
                    }
                    tvAnchorSurface.text = String.format(
                        Locale.US,
                        "Surface: %s  Dist: %.2f m",
                        surfaceName,
                        a.distanceMeters
                    )
                    btnResetAnchor.visibility = View.VISIBLE
                }
                AnchorStatus.PAUSED -> {
                    tvAnchorStatus.text = "Anchor: PAUSED (${a.lastHitMessage})"
                    tvAnchorStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    btnResetAnchor.visibility = View.VISIBLE
                }
                AnchorStatus.STOPPED -> {
                    tvAnchorStatus.text = "Anchor: STOPPED (${a.lastHitMessage})"
                    tvAnchorStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    tvAnchorPose.text = "Pos: X: ---  Y: ---  Z: ---"
                    tvAnchorSurface.text = "Surface: NONE  Dist: ---"
                    btnResetAnchor.visibility = View.GONE
                }
                else -> {
                    if (a.lastHitMessage.isNotEmpty() && a.lastHitMessage != "Tap detected plane to place 3D marker") {
                        tvAnchorStatus.text = "Anchor: NONE (${a.lastHitMessage})"
                        tvAnchorStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    } else {
                        tvAnchorStatus.text = "Anchor: NONE (Tap surface to place)"
                        tvAnchorStatus.setTextColor(getColor(R.color.text_primary))
                    }
                    tvAnchorPose.text = "Pos: X: ---  Y: ---  Z: ---"
                    tvAnchorSurface.text = "Surface: NONE  Dist: ---"
                    btnResetAnchor.visibility = View.GONE
                }
            }

            // Display Depth Perception Telemetry
            val d = diagnostics.depth
            val depthSuppText = if (d.isSupported) "SUPPORTED" else "UNSUPPORTED"
            val depthStateText = when (d.status) {
                DepthStatus.READY -> "READY"
                DepthStatus.WAITING -> "WAITING"
                DepthStatus.TRACKING_PAUSED -> "PAUSED (tracking paused)"
                DepthStatus.UNSUPPORTED -> "UNSUPPORTED"
                DepthStatus.ERROR -> "ERROR (${d.statusMessage})"
            }
            tvDepthStatus.text = "Depth: $depthSuppText | State: $depthStateText"
            when (d.status) {
                DepthStatus.READY -> tvDepthStatus.setTextColor(getColor(R.color.accent_green))
                DepthStatus.WAITING -> tvDepthStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                DepthStatus.TRACKING_PAUSED -> tvDepthStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                DepthStatus.UNSUPPORTED -> tvDepthStatus.setTextColor(getColor(R.color.text_secondary))
                DepthStatus.ERROR -> tvDepthStatus.setTextColor(getColor(android.R.color.holo_red_light))
            }

            if (d.imageWidth > 0 && d.imageHeight > 0) {
                tvDepthMetrics.text = String.format(
                    Locale.US,
                    "Image: %d×%d | Valid: %.1f%% | Rate: %.1f Hz",
                    d.imageWidth,
                    d.imageHeight,
                    d.validSamplePercent,
                    d.depthUpdateHz
                )
            } else {
                tvDepthMetrics.text = String.format(
                    Locale.US,
                    "Image: --- | Valid: --- | Rate: %.1f Hz",
                    d.depthUpdateHz
                )
            }

            if (d.status == DepthStatus.READY && d.validSamplePercent > 0f) {
                val centerStr = if (d.centerDepthMeters > 0f) String.format(Locale.US, "%.2fm", d.centerDepthMeters) else "---"
                tvDepthRanges.text = String.format(
                    Locale.US,
                    "Center: %s | Min: %.2fm | Max: %.2fm | Mean: %.2fm",
                    centerStr,
                    d.minDepthMeters,
                    d.maxDepthMeters,
                    d.meanDepthMeters
                )
            } else {
                tvDepthRanges.text = "Center: --- | Min: --- | Max: --- | Mean: ---"
            }

            val rawStr = if (d.rawDepthAvailable) {
                String.format(Locale.US, "AVAILABLE (%.0f%%)", d.rawDepthValidPercent)
            } else {
                "UNAVAILABLE"
            }
            val intr = d.intrinsics
            if (intr.fx > 0f) {
                tvDepthRawIntrinsics.text = String.format(
                    Locale.US,
                    "Raw: %s | fx: %.1f fy: %.1f (w:%d h:%d)",
                    rawStr,
                    intr.fx,
                    intr.fy,
                    intr.width,
                    intr.height
                )
            } else {
                tvDepthRawIntrinsics.text = String.format(
                    Locale.US,
                    "Raw: %s | Intrinsics: waiting...",
                    rawStr
                )
            }

            // Display 2.5D Occupancy Grid Telemetry
            val g = diagnostics.grid
            if (g.isFloorTracking) {
                tvGridStatus.text = String.format(
                    Locale.US,
                    "Grid: %d×%d (%.2fm) | Floor: Y:%+.2fm (%.2fm²)",
                    g.widthCells,
                    g.depthCells,
                    g.cellSizeMeters,
                    g.floorHeightY,
                    g.floorPlaneArea
                )
                tvGridStatus.setTextColor(getColor(R.color.accent_green))
            } else {
                tvGridStatus.text = "Grid: ${g.widthCells}×${g.depthCells} (0.10m) | Floor: ${g.floorStatus}"
                tvGridStatus.setTextColor(getColor(android.R.color.holo_orange_light))
            }

            tvGridCounts.text = String.format(
                Locale.US,
                "RawOcc:%d | Filt:%d | InflBlk:%d | Free:%d (Noise:-%d)",
                g.rawOccupiedCount,
                g.filteredOccupiedCount,
                g.inflatedBlockedCount,
                g.freeCount,
                g.removedNoiseCount
            )

            tvGridMetrics.text = String.format(
                Locale.US,
                "Mode: %s | Radius: %.2fm (%d cells) | Unk: %s | Time: %.1fms (%.1fHz)",
                g.displayMode.name,
                g.agentRadiusMeters,
                g.inflationRadiusCells,
                g.unknownCostPolicy.name,
                g.processingTimeMs,
                g.updateHz
            )

            // Display A* Navigation Pathfinding Telemetry
            val navMode = sessionManager.navSelectionMode
            val pathResult = sessionManager.latestPathResult

            if (pathResult != null) {
                when (pathResult.status) {
                    PathStatus.SUCCESS, PathStatus.START_EQUALS_GOAL -> {
                        tvNavStatus.text = "Status: ${pathResult.status.name} | Ver: #${pathResult.gridVersion}"
                        tvNavStatus.setTextColor(getColor(R.color.accent_cyan))
                    }
                    PathStatus.START_BLOCKED, PathStatus.GOAL_BLOCKED -> {
                        tvNavStatus.text = "Status: ${pathResult.status.name}"
                        tvNavStatus.setTextColor(getColor(android.R.color.holo_red_light))
                    }
                    PathStatus.START_UNKNOWN, PathStatus.GOAL_UNKNOWN -> {
                        tvNavStatus.text = "Status: ${pathResult.status.name} (UNK Impassable)"
                        tvNavStatus.setTextColor(getColor(R.color.accent_purple))
                    }
                    PathStatus.NO_PATH -> {
                        tvNavStatus.text = "Status: NO PATH (Obstacles block goal)"
                        tvNavStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                    }
                    else -> {
                        tvNavStatus.text = "Status: ${pathResult.status.name}"
                        tvNavStatus.setTextColor(getColor(R.color.text_secondary))
                    }
                }

                tvNavMetrics.text = String.format(
                    Locale.US,
                    "Path: %d cells (%d smooth, %.2fm) | Cost: %.2f | Exp: %d (%.2fms)",
                    pathResult.rawCellCount,
                    pathResult.smoothedCellCount,
                    pathResult.totalDistanceMeters,
                    pathResult.totalCost,
                    pathResult.nodesExpanded,
                    pathResult.searchTimeMs
                )
            } else {
                tvNavStatus.text = when (navMode) {
                    NavSelectionMode.SET_START -> "A* Nav: Tap floor for START point"
                    NavSelectionMode.SET_GOAL -> "A* Nav: Tap floor for GOAL point"
                    NavSelectionMode.OFF -> "A* Nav: IDLE (Anchor mode active)"
                }
                tvNavStatus.setTextColor(getColor(R.color.text_primary))
                tvNavMetrics.text = "Path: 0 cells (0 smooth, 0.0m) | Cost: 0.0 | Time: 0.0ms"
            }

            btnNavMode.text = when (navMode) {
                NavSelectionMode.SET_START -> "NAV: SET START"
                NavSelectionMode.SET_GOAL -> "NAV: SET GOAL"
                NavSelectionMode.OFF -> "NAV: OFF (ANCHOR)"
            }
            when (navMode) {
                NavSelectionMode.SET_START -> btnNavMode.setTextColor(getColor(R.color.accent_green))
                NavSelectionMode.SET_GOAL -> btnNavMode.setTextColor(getColor(android.R.color.holo_red_light))
                NavSelectionMode.OFF -> btnNavMode.setTextColor(getColor(R.color.text_secondary))
            }

            val sCol = sessionManager.navStartCol
            val sRow = sessionManager.navStartRow
            val gCol = sessionManager.navGoalCol
            val gRow = sessionManager.navGoalRow

            val startStr = if (sessionManager.hasNavStart) {
                val sState = sessionManager.occupancyGrid.getNavigationCellState(sCol, sRow)
                "[$sCol, $sRow] (${sState.name})"
            } else "[--, --]"

            val goalStr = if (sessionManager.hasNavGoal) {
                val gState = sessionManager.occupancyGrid.getNavigationCellState(gCol, gRow)
                "[$gCol, $gRow] (${gState.name})"
            } else "[--, --]"

            tvNavEndpoints.text = "Start: $startStr | Goal: $goalStr"

            // Display Autonomous Virtual Agent Telemetry
            val agentPose = sessionManager.agentController.getPoseSnapshot(diagnostics.grid.gridVersion)
            tvAgentStatus.text = when (agentPose.state) {
                AgentState.NAVIGATING -> "Agent: NAVIGATING"
                AgentState.REPLANNING -> "Agent: REPLANNING (Obstacle detected)"
                AgentState.ARRIVED -> "Agent: ARRIVED (Goal reached)"
                AgentState.BLOCKED -> "Agent: BLOCKED (Path obstructed)"
                AgentState.NO_PATH -> "Agent: NO PATH (Unreachable)"
                AgentState.PAUSED -> "Agent: PAUSED"
                AgentState.PLANNING -> "Agent: PLANNING A*..."
                AgentState.IDLE -> if (agentPose.isSpawned) "Agent: IDLE (Spawned at start)" else "Agent: IDLE (Select Start & Goal)"
            }

            when (agentPose.state) {
                AgentState.NAVIGATING -> tvAgentStatus.setTextColor(getColor(R.color.accent_cyan))
                AgentState.REPLANNING -> tvAgentStatus.setTextColor(getColor(R.color.accent_yellow))
                AgentState.ARRIVED -> tvAgentStatus.setTextColor(getColor(R.color.accent_green))
                AgentState.BLOCKED, AgentState.NO_PATH -> tvAgentStatus.setTextColor(getColor(android.R.color.holo_red_light))
                AgentState.PAUSED -> tvAgentStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                else -> tvAgentStatus.setTextColor(getColor(R.color.text_secondary))
            }

            btnPauseAgent.text = if (agentPose.state == AgentState.PAUSED) "RESUME" else "PAUSE"
            if (agentPose.state == AgentState.PAUSED) {
                btnPauseAgent.setTextColor(getColor(R.color.accent_green))
            } else {
                btnPauseAgent.setTextColor(getColor(R.color.accent_yellow))
            }

            if (agentPose.isSpawned) {
                tvAgentPose.text = String.format(
                    Locale.US,
                    "Pos: (%+.2f, %+.2f)m [c%d, r%d] | Head: %.1f° | Spd: %.2fm/s",
                    agentPose.floorX,
                    agentPose.floorZ,
                    agentPose.cellCol,
                    agentPose.cellRow,
                    agentPose.headingDegrees,
                    agentPose.speedMps
                )

                val wpIdxStr = if (agentPose.totalWaypoints > 0) "${agentPose.currentWaypointIndex + 1}/${agentPose.totalWaypoints}" else "0/0"
                tvAgentNav.text = String.format(
                    Locale.US,
                    "Wp: %s | Dist: %.2fm | Grid: v%d | Path: v%d | Replans: %d",
                    wpIdxStr,
                    agentPose.distanceToGoalMeters,
                    agentPose.currentGridVersion,
                    agentPose.pathGridVersion,
                    agentPose.replansCount
                )
            } else {
                tvAgentPose.text = "Pos: (---, ---) [---] | Head: ---° | Spd: 0.00 m/s"
                tvAgentNav.text = "Wp: 0/0 | Dist: --- | Grid: v${diagnostics.grid.gridVersion} | Replans: 0"
            }

            // Display Reactive Creature AI Telemetry (Milestone 10)
            val aiSnapshot = sessionManager.getCreatureAiSnapshot()
            val aiStateStr = when (aiSnapshot.state) {
                CreatureAIState.INITIALIZING -> "INITIALIZING (Scanning room)"
                CreatureAIState.PATROLLING -> "PATROLLING (Exploring floor)"
                CreatureAIState.CHASING -> "CHASING PLAYER! (Pursuing proxy)"
                CreatureAIState.SEARCHING -> String.format(Locale.US, "SEARCHING (Investigating area: %.1fs)", aiSnapshot.searchTimeRemainingSec)
                CreatureAIState.RETURNING -> "RETURNING (Back to patrol)"
                CreatureAIState.BLOCKED -> "BLOCKED (Path recovery)"
                CreatureAIState.PAUSED -> "PAUSED (AI Suspended)"
            }
            tvAiStatus.text = "Creature: $aiStateStr"
            when (aiSnapshot.state) {
                CreatureAIState.CHASING -> tvAiStatus.setTextColor(getColor(android.R.color.holo_red_light))
                CreatureAIState.SEARCHING -> tvAiStatus.setTextColor(getColor(R.color.accent_yellow))
                CreatureAIState.RETURNING -> tvAiStatus.setTextColor(getColor(R.color.accent_purple))
                CreatureAIState.PATROLLING -> tvAiStatus.setTextColor(getColor(R.color.accent_cyan))
                CreatureAIState.INITIALIZING -> tvAiStatus.setTextColor(getColor(R.color.accent_green))
                else -> tvAiStatus.setTextColor(getColor(R.color.text_secondary))
            }

            val targetCellStr = if (aiSnapshot.targetCol >= 0) "[c${aiSnapshot.targetCol}, r${aiSnapshot.targetRow}]" else "[-, -]"
            val lastKnownAgeSec = aiSnapshot.lastKnownLocationAgeMs / 1000f
            val lastKnownStr = if (aiSnapshot.lastKnownPlayerCol >= 0) {
                String.format(Locale.US, "[c%d, r%d] (%.1fs ago)", aiSnapshot.lastKnownPlayerCol, aiSnapshot.lastKnownPlayerRow, lastKnownAgeSec)
            } else "[-, -]"
            val candStr = if (aiSnapshot.totalSearchCandidates > 0) {
                "${aiSnapshot.currentSearchCandidateIndex + 1}/${aiSnapshot.totalSearchCandidates}"
            } else "0/0"

            tvAiDetails.text = String.format(
                Locale.US,
                "Target: %s %s (Cand %s) | LastKnown: %s | Req #%d",
                aiSnapshot.targetType.name,
                targetCellStr,
                candStr,
                lastKnownStr,
                aiSnapshot.activeRequestId
            )

            val playerDistStr = if (aiSnapshot.playerDistanceMeters < 100f) String.format(Locale.US, "%.2fm", aiSnapshot.playerDistanceMeters) else "---m"
            val losStr = if (aiSnapshot.isLineOfSightClear) "CLEAR" else "BLOCKED"
            val visStateStr = aiSnapshot.visibilityState.name
            val visReasonStr = aiSnapshot.visibilityReason.name
            val depthInfoStr = if (aiSnapshot.observedDepthMeters > 0f) {
                String.format(Locale.US, "Obs: %.2fm / Exp: %.2fm", aiSnapshot.observedDepthMeters, aiSnapshot.expectedDepthMeters)
            } else {
                String.format(Locale.US, "Exp: %.2fm", aiSnapshot.expectedDepthMeters)
            }

            tvAiPerception.text = String.format(
                Locale.US,
                "Vis: %s (%s, %.0f%%) [%s] | GridLoS: %s | Dist: %s",
                visStateStr,
                visReasonStr,
                aiSnapshot.visibilityConfidence * 100f,
                depthInfoStr,
                losStr,
                playerDistStr
            )

            // Display Milestone 12 Playable Mission Progression
            val mission = sessionManager.getMissionSnapshot()
            val extStatusStr = if (mission.isExtractionUnlocked) "ACTIVE" else "LOCKED"
            tvMissionStatus.text = "Mission: ${mission.state.name} | ${mission.relicsCollected}/${mission.totalRelics} Relics | Ext: $extStatusStr"
            tvMissionDetails.text = String.format(
                Locale.US,
                "Time: %s | Threat: %.0f%% | Navigable: %d cells",
                mission.formattedElapsedTime,
                mission.captureProgressPercent,
                mission.traversableCellCount
            )
            tvMissionGuidance.text = "> ${mission.guidancePrompt} <"

            when (mission.state) {
                GameState.WON -> tvMissionStatus.setTextColor(getColor(R.color.accent_green))
                GameState.LOST -> tvMissionStatus.setTextColor(getColor(android.R.color.holo_red_light))
                GameState.PLAYING -> tvMissionStatus.setTextColor(getColor(R.color.accent_yellow))
                GameState.PAUSED -> tvMissionStatus.setTextColor(getColor(R.color.accent_cyan))
                GameState.READY -> tvMissionStatus.setTextColor(getColor(R.color.accent_purple))
                GameState.SCANNING -> tvMissionStatus.setTextColor(getColor(R.color.text_secondary))
            }

            btnGenerateMission.isEnabled = (mission.state == GameState.SCANNING && mission.isNavigableSufficient) || mission.state == GameState.READY
            btnStartMission.isEnabled = mission.state == GameState.READY
            btnPauseMission.isEnabled = mission.state == GameState.PLAYING || mission.state == GameState.PAUSED
            btnPauseMission.text = if (mission.state == GameState.PAUSED) "RESUME" else "PAUSE"
            btnRestartMission.isEnabled = mission.state != GameState.SCANNING

            // Display Performance Telemetry
            tvPerformance.text = String.format(
                Locale.US,
                "FPS: %.1f | Samsung Galaxy Tab S8+",
                currentFps
            )
        }
    }
}
