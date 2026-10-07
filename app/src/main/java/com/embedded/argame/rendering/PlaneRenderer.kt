package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Renders detected ARCore planes in 3D world space using OpenGL ES 3.0.
 * Visualizes plane polygons with a translucent filled interior and a crisp
 * boundary line, color-coded by plane orientation (Horizontal Upward,
 * Horizontal Downward, Vertical).
 */
class PlaneRenderer {

    companion object {
        private const val TAG = "PlaneRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 3
        private const val MAX_VERTICES_PER_PLANE = 1024

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            attribute vec3 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * vec4(a_Position, 1.0);
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """

        // Color definitions: RGB + Alpha
        // Horizontal Upward (e.g. floor/table candidate): Cyan
        private val COLOR_HORIZ_UP = floatArrayOf(0.0f, 0.85f, 1.0f, 1.0f)
        // Horizontal Downward (e.g. ceiling): Amber/Orange
        private val COLOR_HORIZ_DOWN = floatArrayOf(1.0f, 0.55f, 0.0f, 1.0f)
        // Vertical (e.g. wall/door): Magenta
        private val COLOR_VERTICAL = floatArrayOf(0.88f, 0.25f, 0.98f, 1.0f)
        // Default / Unknown: White
        private val COLOR_DEFAULT = floatArrayOf(0.8f, 0.8f, 0.8f, 1.0f)
    }

    private var program = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var positionAttrib = 0

    // Pre-allocated buffers and arrays to avoid per-frame GC churn
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val currentColor = FloatArray(4)

    // Reusable vertex buffer for triangle fan and line loop
    private val vertexBuffer: FloatBuffer

    init {
        val byteBuffer = ByteBuffer.allocateDirect(MAX_VERTICES_PER_PLANE * COORDS_PER_VERTEX * FLOAT_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())
        vertexBuffer = byteBuffer.asFloatBuffer()
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")

        Log.i(TAG, "PlaneRenderer initialized on GL thread.")
    }

    /**
     * Renders all currently tracking planes using the camera's view-projection matrix.
     */
    fun draw(planes: Collection<Plane>, viewProjectionMatrix: FloatArray) {
        if (planes.isEmpty()) return

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        for (plane in planes) {
            // Only draw actively tracking planes that have not been merged/subsumed
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) {
                continue
            }

            val polygon = plane.polygon
            val num2dPoints = polygon.limit() / 2
            if (num2dPoints < 3) continue

            // Select color based on plane orientation type
            val baseColor = when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING -> COLOR_HORIZ_UP
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> COLOR_HORIZ_DOWN
                Plane.Type.VERTICAL -> COLOR_VERTICAL
                else -> COLOR_DEFAULT
            }

            // Compute Plane MVP = Camera ViewProjection * Plane ModelMatrix
            plane.centerPose.toMatrix(modelMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

            // --- 1. Draw Translucent Filled Interior (Triangle Fan) ---
            vertexBuffer.clear()
            // Center vertex at (0, 0, 0) in plane local space
            vertexBuffer.put(0.0f)
            vertexBuffer.put(0.0f)
            vertexBuffer.put(0.0f)

            polygon.rewind()
            while (polygon.hasRemaining()) {
                val x = polygon.get()
                val z = polygon.get()
                vertexBuffer.put(x)
                vertexBuffer.put(0.0f) // Plane normal is Y, local surface is Y=0
                vertexBuffer.put(z)
            }
            // Loop back to first polygon vertex to close the fan
            polygon.rewind()
            vertexBuffer.put(polygon.get())
            vertexBuffer.put(0.0f)
            vertexBuffer.put(polygon.get())

            val fanVertexCount = num2dPoints + 2
            if (fanVertexCount <= MAX_VERTICES_PER_PLANE) {
                vertexBuffer.position(0)
                GLES20.glVertexAttribPointer(
                    positionAttrib,
                    COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer
                )

                // Translucent fill: alpha = 0.20
                currentColor[0] = baseColor[0]
                currentColor[1] = baseColor[1]
                currentColor[2] = baseColor[2]
                currentColor[3] = 0.20f
                GLES20.glUniform4fv(colorUniform, 1, currentColor, 0)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, fanVertexCount)
            }

            // --- 2. Draw Solid Boundary Outline (Line Loop) ---
            vertexBuffer.clear()
            polygon.rewind()
            while (polygon.hasRemaining()) {
                val x = polygon.get()
                val z = polygon.get()
                vertexBuffer.put(x)
                vertexBuffer.put(0.0f)
                vertexBuffer.put(z)
            }

            if (num2dPoints <= MAX_VERTICES_PER_PLANE) {
                vertexBuffer.position(0)
                GLES20.glVertexAttribPointer(
                    positionAttrib,
                    COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT,
                    false,
                    0,
                    vertexBuffer
                )

                // Solid outline: alpha = 0.95
                currentColor[0] = baseColor[0]
                currentColor[1] = baseColor[1]
                currentColor[2] = baseColor[2]
                currentColor[3] = 0.95f
                GLES20.glUniform4fv(colorUniform, 1, currentColor, 0)

                GLES20.glLineWidth(4.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, num2dPoints)
            }
        }

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error compiling plane shader: $error")
        }
        return shader
    }
}
