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
import com.embedded.argame.environment.GridDisplayMode
import com.embedded.argame.environment.UnknownCostPolicy
import com.embedded.argame.perception.AnchorStatus
import com.embedded.argame.perception.ArSessionManager
import com.embedded.argame.perception.DepthStatus
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
        tvPerformance = findViewById(R.id.tvPerformance)
        permissionRationaleContainer = findViewById(R.id.permissionRationaleContainer)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

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

            // Display Performance Telemetry
            tvPerformance.text = String.format(
                Locale.US,
                "FPS: %.1f | Samsung Galaxy Tab S8+",
                currentFps
            )
        }
    }
}
