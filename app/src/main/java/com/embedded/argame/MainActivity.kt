package com.embedded.argame

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.embedded.argame.perception.ArSessionManager
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
        tvPerformance = findViewById(R.id.tvPerformance)
        permissionRationaleContainer = findViewById(R.id.permissionRationaleContainer)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)

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

            // Display Performance Telemetry
            tvPerformance.text = String.format(
                Locale.US,
                "FPS: %.1f | Samsung Galaxy Tab S8+",
                currentFps
            )
        }
    }
}
