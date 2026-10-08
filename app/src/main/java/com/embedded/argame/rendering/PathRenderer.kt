package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.embedded.argame.navigation.PathResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer that visualizes the A* navigation path and endpoints in 3D AR space:
 *
 * 1. Start Marker: 3D vertical diamond (emerald green, 12 cm high) centered at the start world pose.
 * 2. Goal Marker: 3D vertical diamond (crimson red, 12 cm high) centered at the goal world pose.
 * 3. Navigation Path: Glowing line strip (gold / amber, slightly elevated above floor) connecting waypoints.
 * 4. Waypoint Nodes: Prominent endpoint markers.
 *
 * Preallocates GPU-accessible direct FloatBuffers to ensure zero GC churn in the 60 FPS render loop.
 */
class PathRenderer {

    companion object {
        private const val TAG = "PathRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 3
        private const val COLOR_COMPONENTS_PER_VERTEX = 4
        private const val MAX_PATH_VERTICES = 1000

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            void main() {
                v_Color = a_Color;
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec4 v_Color;
            void main() {
                gl_FragColor = v_Color;
            }
        """

        // Marker Dimensions (Meters)
        private const val PIN_HALF_WIDTH = 0.04f   // 8 cm wide
        private const val PIN_HEIGHT = 0.14f       // 14 cm tall
        private const val PIN_BASE_Y = 0.015f      // Rests slightly above floor
    }

    private var program = 0
    private var mvpUniform = 0
    private var positionAttrib = 0
    private var colorAttrib = 0

    // Dynamic Path Vertex & Color Buffers
    private val pathVertexBuffer: FloatBuffer
    private val pathColorBuffer: FloatBuffer
    private var pathVertexCount = 0

    // Static 3D Marker Geometry (Octahedral diamond: 24 vertices for solid faces + 24 for wireframe)
    private val markerFaceVertexBuffer: FloatBuffer
    private val markerFaceColorBuffer: FloatBuffer
    private val markerOutlineVertexBuffer: FloatBuffer
    private val markerOutlineColorBuffer: FloatBuffer
    private val markerFaceVertexCount: Int
    private val markerOutlineVertexCount: Int

    // Preallocated transformation matrices
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16)

    // Cached start and goal world coordinates
    private var hasStartMarker = false
    private var startWorldX = 0f
    private var startWorldY = 0f
    private var startWorldZ = 0f

    private var hasGoalMarker = false
    private var goalWorldX = 0f
    private var goalWorldY = 0f
    private var goalWorldZ = 0f

    private val pathLock = Any()

    init {
        Matrix.setIdentityM(identityMatrix, 0)

        // 1. Allocate dynamic path line strip buffers
        val pBuf = ByteBuffer.allocateDirect(MAX_PATH_VERTICES * COORDS_PER_VERTEX * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        pathVertexBuffer = pBuf.asFloatBuffer()

        val cBuf = ByteBuffer.allocateDirect(MAX_PATH_VERTICES * COLOR_COMPONENTS_PER_VERTEX * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        pathColorBuffer = cBuf.asFloatBuffer()

        // 2. Generate 3D Diamond Marker Geometry
        val (faceVerts, faceColors) = generateDiamondFaceData()
        markerFaceVertexCount = faceVerts.size / COORDS_PER_VERTEX
        markerFaceVertexBuffer = createDirectFloatBuffer(faceVerts)
        markerFaceColorBuffer = createDirectFloatBuffer(faceColors)

        val (lineVerts, lineColors) = generateDiamondOutlineData()
        markerOutlineVertexCount = lineVerts.size / COORDS_PER_VERTEX
        markerOutlineVertexBuffer = createDirectFloatBuffer(lineVerts)
        markerOutlineColorBuffer = createDirectFloatBuffer(lineColors)
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(it)
                GLES20.glDeleteProgram(it)
                throw RuntimeException("PathRenderer GL program linking failed: $error")
            }
        }

        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")

        Log.i(TAG, "PathRenderer initialized on GL thread.")
    }

    /**
     * Updates the path polyline and endpoint markers from the latest PathResult.
     * Safe to call from perception or navigation threads.
     */
    fun updatePath(pathResult: PathResult?) {
        synchronized(pathLock) {
            if (pathResult == null || !pathResult.status.isSuccessful || pathResult.worldCoordinates.isEmpty()) {
                pathVertexCount = 0
                hasStartMarker = false
                hasGoalMarker = false
                return
            }

            val coords = pathResult.worldCoordinates
            val totalVerts = (coords.size / 3).coerceAtMost(MAX_PATH_VERTICES)
            pathVertexCount = totalVerts

            // Copy coordinates into direct FloatBuffer
            pathVertexBuffer.position(0)
            pathVertexBuffer.put(coords, 0, totalVerts * 3)
            pathVertexBuffer.position(0)

            // Fill color buffer: Glowing Gold / Amber (R=1.0, G=0.82, B=0.1, A=1.0)
            pathColorBuffer.position(0)
            for (i in 0 until totalVerts) {
                pathColorBuffer.put(1.0f) // R
                pathColorBuffer.put(0.82f) // G
                pathColorBuffer.put(0.10f) // B
                pathColorBuffer.put(1.0f) // A
            }
            pathColorBuffer.position(0)

            // Record Start and Goal 3D world positions
            if (totalVerts > 0) {
                hasStartMarker = true
                startWorldX = coords[0]
                startWorldY = coords[1]
                startWorldZ = coords[2]

                hasGoalMarker = true
                val lastIdx = (totalVerts - 1) * 3
                goalWorldX = coords[lastIdx]
                goalWorldY = coords[lastIdx + 1]
                goalWorldZ = coords[lastIdx + 2]
            }
        }
    }

    /**
     * Directly updates endpoint marker positions even before path is calculated.
     */
    fun setEndpoints(
        startWorld: FloatArray?,
        goalWorld: FloatArray?
    ) {
        synchronized(pathLock) {
            if (startWorld != null && startWorld.size >= 3) {
                hasStartMarker = true
                startWorldX = startWorld[0]
                startWorldY = startWorld[1]
                startWorldZ = startWorld[2]
            } else {
                hasStartMarker = false
            }

            if (goalWorld != null && goalWorld.size >= 3) {
                hasGoalMarker = true
                goalWorldX = goalWorld[0]
                goalWorldY = goalWorld[1]
                goalWorldZ = goalWorld[2]
            } else {
                hasGoalMarker = false
            }
        }
    }

    fun clear() {
        synchronized(pathLock) {
            pathVertexCount = 0
            hasStartMarker = false
            hasGoalMarker = false
        }
    }

    /**
     * Renders the path line strip and 3D endpoint markers.
     */
    fun draw(viewProjectionMatrix: FloatArray) {
        synchronized(pathLock) {
            if (program == 0) return

            GLES20.glUseProgram(program)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glEnableVertexAttribArray(positionAttrib)
            GLES20.glEnableVertexAttribArray(colorAttrib)

            // 1. Draw Path Polyline (GL_LINE_STRIP)
            if (pathVertexCount > 1) {
                GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjectionMatrix, 0)
                GLES20.glLineWidth(8.0f)

                pathVertexBuffer.position(0)
                GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, pathVertexBuffer)

                pathColorBuffer.position(0)
                GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, pathColorBuffer)

                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pathVertexCount)
            }

            // 2. Draw Start Marker (Emerald Green Pin)
            if (hasStartMarker) {
                drawMarker(startWorldX, startWorldY, startWorldZ, 0.1f, 1.0f, 0.3f, viewProjectionMatrix)
            }

            // 3. Draw Goal Marker (Crimson Red Pin)
            if (hasGoalMarker) {
                drawMarker(goalWorldX, goalWorldY, goalWorldZ, 1.0f, 0.15f, 0.25f, viewProjectionMatrix)
            }

            GLES20.glDisableVertexAttribArray(positionAttrib)
            GLES20.glDisableVertexAttribArray(colorAttrib)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun drawMarker(
        wx: Float, wy: Float, wz: Float,
        r: Float, g: Float, b: Float,
        viewProjectionMatrix: FloatArray
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, wx, wy, wz)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        // Draw Solid Faces (semi-transparent)
        markerFaceVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, markerFaceVertexBuffer)

        // Set uniform color attribute by disabling color array or setting custom color buffer
        markerFaceColorBuffer.position(0)
        // Tint the face color buffer with the marker color
        for (i in 0 until markerFaceVertexCount) {
            markerFaceColorBuffer.put(r * 0.9f)
            markerFaceColorBuffer.put(g * 0.9f)
            markerFaceColorBuffer.put(b * 0.9f)
            markerFaceColorBuffer.put(0.85f)
        }
        markerFaceColorBuffer.position(0)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, markerFaceColorBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, markerFaceVertexCount)

        // Draw Crisp Edge Outlines (Opaque)
        markerOutlineVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, markerOutlineVertexBuffer)

        markerOutlineColorBuffer.position(0)
        for (i in 0 until markerOutlineVertexCount) {
            markerOutlineColorBuffer.put(1.0f)
            markerOutlineColorBuffer.put(1.0f)
            markerOutlineColorBuffer.put(1.0f)
            markerOutlineColorBuffer.put(1.0f)
        }
        markerOutlineColorBuffer.position(0)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, markerOutlineColorBuffer)

        GLES20.glLineWidth(4.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, markerOutlineVertexCount)
    }

    private fun generateDiamondFaceData(): Pair<FloatArray, FloatArray> {
        // Octahedral 3D diamond: 8 triangular faces (24 vertices)
        val w = PIN_HALF_WIDTH
        val midY = PIN_BASE_Y + PIN_HEIGHT * 0.5f
        val topY = PIN_BASE_Y + PIN_HEIGHT
        val botY = PIN_BASE_Y

        val top = floatArrayOf(0f, topY, 0f)
        val bot = floatArrayOf(0f, botY, 0f)
        val north = floatArrayOf(0f, midY, -w)
        val south = floatArrayOf(0f, midY, w)
        val east = floatArrayOf(w, midY, 0f)
        val west = floatArrayOf(-w, midY, 0f)

        val verts = ArrayList<Float>()
        fun addTri(v1: FloatArray, v2: FloatArray, v3: FloatArray) {
            verts.addAll(v1.toList())
            verts.addAll(v2.toList())
            verts.addAll(v3.toList())
        }

        // Top pyramid (4 faces)
        addTri(top, east, north)
        addTri(top, north, west)
        addTri(top, west, south)
        addTri(top, south, east)

        // Bottom pyramid (4 faces)
        addTri(bot, north, east)
        addTri(bot, west, north)
        addTri(bot, south, west)
        addTri(bot, east, south)

        val vertArr = verts.toFloatArray()
        val colorArr = FloatArray(vertArr.size / 3 * 4) { 1.0f }
        return Pair(vertArr, colorArr)
    }

    private fun generateDiamondOutlineData(): Pair<FloatArray, FloatArray> {
        // Wireframe edges: 12 lines (24 vertices)
        val w = PIN_HALF_WIDTH
        val midY = PIN_BASE_Y + PIN_HEIGHT * 0.5f
        val topY = PIN_BASE_Y + PIN_HEIGHT
        val botY = PIN_BASE_Y

        val top = floatArrayOf(0f, topY, 0f)
        val bot = floatArrayOf(0f, botY, 0f)
        val north = floatArrayOf(0f, midY, -w)
        val south = floatArrayOf(0f, midY, w)
        val east = floatArrayOf(w, midY, 0f)
        val west = floatArrayOf(-w, midY, 0f)

        val lines = ArrayList<Float>()
        fun addLine(v1: FloatArray, v2: FloatArray) {
            lines.addAll(v1.toList())
            lines.addAll(v2.toList())
        }

        // Equator ring
        addLine(north, east)
        addLine(east, south)
        addLine(south, west)
        addLine(west, north)

        // Top edges
        addLine(top, north)
        addLine(top, east)
        addLine(top, south)
        addLine(top, west)

        // Bottom edges
        addLine(bot, north)
        addLine(bot, east)
        addLine(bot, south)
        addLine(bot, west)

        val lineArr = lines.toFloatArray()
        val colorArr = FloatArray(lineArr.size / 3 * 4) { 1.0f }
        return Pair(lineArr, colorArr)
    }

    private fun createDirectFloatBuffer(data: FloatArray): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        return buf.asFloatBuffer().apply {
            put(data)
            position(0)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("PathRenderer shader compilation failed ($type): $error")
            }
        }
    }
}
