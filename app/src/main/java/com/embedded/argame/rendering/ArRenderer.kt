package com.embedded.argame.rendering

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.embedded.argame.perception.ArSessionManager
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Custom GLSurfaceView.Renderer that drives the AR frame update loop,
 * renders the live camera background, renders detected 3D spatial planes,
 * and measures real-time performance telemetry.
 */
class ArRenderer(
    private val context: Context,
    private val sessionManager: ArSessionManager,
    private val onFrameRendered: (fps: Float) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "ArRenderer"
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 100.0f
    }

    private val backgroundRenderer = BackgroundRenderer()
    private val planeRenderer = PlaneRenderer()
    private val anchorMarkerRenderer = AnchorMarkerRenderer()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var displayRotation = 0

    // Matrices pre-allocated to avoid GC churn during frame render loop
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewProjectionMatrix = FloatArray(16)

    // FPS calculation variables
    private var frameCount = 0
    private var lastFpsTimestamp = System.nanoTime()
    private var currentFps = 0.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.05f, 1.0f)
        backgroundRenderer.createOnGlThread()
        planeRenderer.createOnGlThread()
        anchorMarkerRenderer.createOnGlThread()

        sessionManager.setCameraTextureName(backgroundRenderer.textureId)
        Log.i(TAG, "GL Surface created. Background, Plane, and Anchor Marker renderers initialized.")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display: Display? = windowManager.defaultDisplay
        displayRotation = display?.rotation ?: 0

        sessionManager.setDisplayGeometry(displayRotation, width, height)
        Log.i(TAG, "GL Surface changed: ${width}x${height}, rotation=$displayRotation")
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Ensure camera texture is bound to session
        if (backgroundRenderer.textureId >= 0) {
            sessionManager.setCameraTextureName(backgroundRenderer.textureId)
        }

        // Retrieve and update the ARCore Frame
        val frame = sessionManager.updateFrame()

        if (frame != null) {
            // 1. Render camera video feed background
            backgroundRenderer.draw(frame)

            // 2. Render 3D spatial planes & 3D anchor marker if camera is tracking
            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)
                Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                // Render detected planes
                val activePlanes = sessionManager.getActivePlanes()
                planeRenderer.draw(activePlanes, viewProjectionMatrix)

                // Render 3D diagnostic marker attached to active Anchor
                val activeAnchor = sessionManager.getActiveAnchor()
                if (activeAnchor != null && activeAnchor.trackingState == TrackingState.TRACKING) {
                    anchorMarkerRenderer.draw(activeAnchor.pose, viewProjectionMatrix)
                }
            }
        }

        // Calculate and publish FPS
        frameCount++
        val now = System.nanoTime()
        val elapsed = now - lastFpsTimestamp
        if (elapsed >= 1_000_000_000L) { // Update every 1 second
            currentFps = (frameCount * 1_000_000_000.0f) / elapsed
            frameCount = 0
            lastFpsTimestamp = now
            onFrameRendered(currentFps)
        }
    }
}
