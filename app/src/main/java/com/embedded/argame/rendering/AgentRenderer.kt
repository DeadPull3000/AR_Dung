package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.embedded.argame.environment.FloorReference
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hardware-accelerated OpenGL ES 3.0 renderer for the autonomous virtual agent.
 *
 * Renders:
 * 1. 3D Cylindrical / Polygonal Body (16 cm diameter, 12 cm height) resting on the floor.
 * 2. Prominent Forward Direction Indicator (vivid neon arrow pointing along heading).
 * 3. Dynamic color coding reflecting agent state (Cyan=Navigating, Yellow=Replanning,
 *    Green=Arrived, Red=Blocked, Gray=Idle/Paused).
 * 4. High-contrast wireframe edge outlines for AR depth clarity.
 *
 * Preallocates GPU-accessible direct FloatBuffers and transformation matrices to
 * ensure ZERO heap allocations in the 60 FPS render loop.
 */
class AgentRenderer {

    companion object {
        private const val TAG = "AgentRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 3
        private const val COLOR_COMPONENTS_PER_VERTEX = 4

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

        // Agent Body Dimensions
        private const val BODY_RADIUS = 0.075f      // 15 cm diameter
        private const val BODY_HEIGHT = 0.10f       // 10 cm height
        private const val DOME_HEIGHT = 0.03f       // 3 cm top dome
        private const val NUM_SIDES = 8             // Octagonal prism
    }

    private var program = 0
    private var mvpUniform = 0
    private var positionAttrib = 0
    private var colorAttrib = 0

    // Body Geometry Buffers
    private val bodyFaceVertexBuffer: FloatBuffer
    private val bodyFaceColorBuffer: FloatBuffer
    private val bodyOutlineVertexBuffer: FloatBuffer
    private val bodyOutlineColorBuffer: FloatBuffer
    private val bodyFaceVertexCount: Int
    private val bodyOutlineVertexCount: Int

    // Forward Arrow Geometry Buffers
    private val arrowFaceVertexBuffer: FloatBuffer
    private val arrowFaceColorBuffer: FloatBuffer
    private val arrowOutlineVertexBuffer: FloatBuffer
    private val arrowOutlineColorBuffer: FloatBuffer
    private val arrowFaceVertexCount: Int
    private val arrowOutlineVertexCount: Int

    // Preallocated transformation matrices
    private val agentFloorMatrix = FloatArray(16)
    private val floorToWorldMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Cached agent pose for thread-safe rendering
    private val poseLock = Any()
    private var hasActiveAgent = false
    private var cachedFloorX = 0f
    private var cachedFloorZ = 0f
    private var cachedHeadingRadians = 0f
    private var cachedState = AgentState.IDLE

    init {
        // 1. Generate Body Geometry
        val (bFaces, bFaceColors, bLines, bLineColors) = generateBodyGeometry()
        bodyFaceVertexCount = bFaces.size / COORDS_PER_VERTEX
        bodyOutlineVertexCount = bLines.size / COORDS_PER_VERTEX

        val bfBuf = ByteBuffer.allocateDirect(bFaces.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        bodyFaceVertexBuffer = bfBuf.asFloatBuffer()
        bodyFaceVertexBuffer.put(bFaces).position(0)

        val bfcBuf = ByteBuffer.allocateDirect(bFaceColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        bodyFaceColorBuffer = bfcBuf.asFloatBuffer()
        bodyFaceColorBuffer.put(bFaceColors).position(0)

        val boBuf = ByteBuffer.allocateDirect(bLines.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        bodyOutlineVertexBuffer = boBuf.asFloatBuffer()
        bodyOutlineVertexBuffer.put(bLines).position(0)

        val bocBuf = ByteBuffer.allocateDirect(bLineColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        bodyOutlineColorBuffer = bocBuf.asFloatBuffer()
        bodyOutlineColorBuffer.put(bLineColors).position(0)

        // 2. Generate Forward Arrow Geometry
        val (aFaces, aFaceColors, aLines, aLineColors) = generateArrowGeometry()
        arrowFaceVertexCount = aFaces.size / COORDS_PER_VERTEX
        arrowOutlineVertexCount = aLines.size / COORDS_PER_VERTEX

        val afBuf = ByteBuffer.allocateDirect(aFaces.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        arrowFaceVertexBuffer = afBuf.asFloatBuffer()
        arrowFaceVertexBuffer.put(aFaces).position(0)

        val afcBuf = ByteBuffer.allocateDirect(aFaceColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        arrowFaceColorBuffer = afcBuf.asFloatBuffer()
        arrowFaceColorBuffer.put(aFaceColors).position(0)

        val aoBuf = ByteBuffer.allocateDirect(aLines.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        arrowOutlineVertexBuffer = aoBuf.asFloatBuffer()
        arrowOutlineVertexBuffer.put(aLines).position(0)

        val aocBuf = ByteBuffer.allocateDirect(aLineColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        arrowOutlineColorBuffer = aocBuf.asFloatBuffer()
        arrowOutlineColorBuffer.put(aLineColors).position(0)
    }

    /**
     * Compiles GL shaders and links program. Call on GL thread.
     */
    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("AgentRenderer GL program linking failed: $log")
        }

        mvpUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")

        Log.i(TAG, "AgentRenderer GL resources initialized successfully.")
    }

    /**
     * Updates the cached agent pose for GL rendering. Call on simulation or render thread.
     */
    fun updateAgentPose(pose: AgentPose?) {
        synchronized(poseLock) {
            if (pose != null && pose.isSpawned) {
                hasActiveAgent = true
                cachedFloorX = pose.floorX
                cachedFloorZ = pose.floorZ
                cachedHeadingRadians = pose.headingRadians
                cachedState = pose.state
            } else {
                hasActiveAgent = false
            }
        }
    }

    /**
     * Clears the agent from visualization.
     */
    fun clear() {
        synchronized(poseLock) {
            hasActiveAgent = false
        }
    }

    /**
     * Renders the 3D agent anchored to the physical floor. Call on GL draw frame.
     */
    fun draw(viewProjectionMatrix: FloatArray, floorReference: FloorReference?) {
        val active: Boolean
        val fx: Float
        val fz: Float
        val heading: Float
        val st: AgentState

        synchronized(poseLock) {
            active = hasActiveAgent
            fx = cachedFloorX
            fz = cachedFloorZ
            heading = cachedHeadingRadians
            st = cachedState
        }

        if (!active || floorReference == null || !floorReference.isTracking) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(colorAttrib)

        // 1. Construct Agent Floor Model Matrix
        // Translate to [fx, visualElevation, fz] on floor plane, rotate around floor +Y normal
        Matrix.setIdentityM(agentFloorMatrix, 0)
        Matrix.translateM(agentFloorMatrix, 0, fx, 0.015f, fz)

        // Convert heading to degrees and apply clockwise rotation around +Y
        val headingDegrees = -Math.toDegrees(heading.toDouble()).toFloat()
        Matrix.rotateM(agentFloorMatrix, 0, headingDegrees, 0f, 1f, 0f)

        // 2. Multiply by Floor-to-World matrix to anchor to real room
        floorReference.getFloorToWorldMatrix(floorToWorldMatrix, 0)
        Matrix.multiplyMM(modelMatrix, 0, floorToWorldMatrix, 0, agentFloorMatrix, 0)

        // 3. Compute Final MVP Matrix
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        // 4. Update Dynamic Body Color according to AgentState
        updateBodyColors(st)

        // 5. Draw Body Faces
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, bodyFaceVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, bodyFaceColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, bodyFaceVertexCount)

        // 6. Draw Body Wireframe Outlines
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, bodyOutlineVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, bodyOutlineColorBuffer)
        GLES20.glLineWidth(3.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, bodyOutlineVertexCount)

        // 7. Draw Forward Indicator Arrow Faces
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, arrowFaceVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, arrowFaceColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, arrowFaceVertexCount)

        // 8. Draw Forward Indicator Wireframe Outlines
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, arrowOutlineVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, arrowOutlineColorBuffer)
        GLES20.glLineWidth(4.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, arrowOutlineVertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun updateBodyColors(state: AgentState) {
        val (r, g, b, a) = when (state) {
            AgentState.NAVIGATING -> floatArrayOf(0.0f, 0.90f, 1.0f, 0.85f)    // Electric Cyan
            AgentState.REPLANNING -> floatArrayOf(1.0f, 0.75f, 0.0f, 0.85f)    // Amber Yellow
            AgentState.ARRIVED -> floatArrayOf(0.0f, 0.95f, 0.40f, 0.85f)       // Emerald Green
            AgentState.BLOCKED, AgentState.NO_PATH -> floatArrayOf(1.0f, 0.15f, 0.25f, 0.85f) // Crimson Red
            AgentState.PAUSED, AgentState.IDLE, AgentState.PLANNING -> floatArrayOf(0.55f, 0.60f, 0.65f, 0.80f) // Slate Gray
        }

        bodyFaceColorBuffer.position(0)
        for (i in 0 until bodyFaceVertexCount) {
            // Slight vertical lighting gradient (top slightly brighter)
            val shade = if (i % 3 == 0) 1.0f else 0.85f
            bodyFaceColorBuffer.put(r * shade).put(g * shade).put(b * shade).put(a)
        }
        bodyFaceColorBuffer.position(0)
    }

    private fun generateBodyGeometry(): GeometryPack {
        val r = BODY_RADIUS
        val h = BODY_HEIGHT
        val dome = DOME_HEIGHT
        val sides = NUM_SIDES

        val faceVerts = ArrayList<Float>()
        val faceColors = ArrayList<Float>()
        val lineVerts = ArrayList<Float>()
        val lineColors = ArrayList<Float>()

        fun addTri(v1: FloatArray, v2: FloatArray, v3: FloatArray) {
            for (v in listOf(v1, v2, v3)) {
                faceVerts.add(v[0]); faceVerts.add(v[1]); faceVerts.add(v[2])
                faceColors.add(0.2f); faceColors.add(0.7f); faceColors.add(1.0f); faceColors.add(0.85f)
            }
        }

        fun addLine(v1: FloatArray, v2: FloatArray) {
            for (v in listOf(v1, v2)) {
                lineVerts.add(v[0]); lineVerts.add(v[1]); lineVerts.add(v[2])
                lineColors.add(1.0f); lineColors.add(1.0f); lineColors.add(1.0f); lineColors.add(1.0f) // White edge
            }
        }

        val botRing = Array(sides) { i ->
            val angle = i * 2.0 * Math.PI / sides
            floatArrayOf((r * cos(angle)).toFloat(), 0f, (r * sin(angle)).toFloat())
        }
        val topRing = Array(sides) { i ->
            val angle = i * 2.0 * Math.PI / sides
            floatArrayOf((r * cos(angle)).toFloat(), h, (r * sin(angle)).toFloat())
        }
        val domeApex = floatArrayOf(0f, h + dome, 0f)

        // Side quads (2 triangles per side) & vertical edge lines
        for (i in 0 until sides) {
            val next = (i + 1) % sides
            val b0 = botRing[i]
            val b1 = botRing[next]
            val t0 = topRing[i]
            val t1 = topRing[next]

            // Two triangles for side quad
            addTri(b0, b1, t1)
            addTri(b0, t1, t0)

            // Outline edges
            addLine(b0, b1) // Bottom ring
            addLine(t0, t1) // Top ring
            addLine(b0, t0) // Vertical edge
            addLine(t0, domeApex) // Dome edge
        }

        // Top dome triangles
        for (i in 0 until sides) {
            val next = (i + 1) % sides
            addTri(topRing[i], topRing[next], domeApex)
        }

        return GeometryPack(
            faceVerts.toFloatArray(),
            faceColors.toFloatArray(),
            lineVerts.toFloatArray(),
            lineColors.toFloatArray()
        )
    }

    private fun generateArrowGeometry(): GeometryPack {
        // High-contrast 3D forward arrow pointing along +X axis
        val faceVerts = ArrayList<Float>()
        val faceColors = ArrayList<Float>()
        val lineVerts = ArrayList<Float>()
        val lineColors = ArrayList<Float>()

        val y = BODY_HEIGHT + 0.01f // Hovering slightly above the body
        val tip = floatArrayOf(BODY_RADIUS + 0.07f, y, 0f) // Tip extends forward (+X)
        val left = floatArrayOf(BODY_RADIUS - 0.01f, y, -0.035f)
        val right = floatArrayOf(BODY_RADIUS - 0.01f, y, +0.035f)
        val center = floatArrayOf(BODY_RADIUS + 0.01f, y, 0f)

        fun addTri(v1: FloatArray, v2: FloatArray, v3: FloatArray) {
            for (v in listOf(v1, v2, v3)) {
                faceVerts.add(v[0]); faceVerts.add(v[1]); faceVerts.add(v[2])
                // Neon Orange/Amber arrow (#FF9100)
                faceColors.add(1.0f); faceColors.add(0.55f); faceColors.add(0.0f); faceColors.add(1.0f)
            }
        }

        fun addLine(v1: FloatArray, v2: FloatArray) {
            for (v in listOf(v1, v2)) {
                lineVerts.add(v[0]); lineVerts.add(v[1]); lineVerts.add(v[2])
                lineColors.add(1.0f); lineColors.add(1.0f); lineColors.add(0.2f); lineColors.add(1.0f) // Bright yellow edge
            }
        }

        // Arrowhead triangles (top and bottom slight thickness)
        val yTop = y + 0.015f
        val tipTop = floatArrayOf(tip[0], yTop, tip[2])
        val leftTop = floatArrayOf(left[0], yTop, left[2])
        val rightTop = floatArrayOf(right[0], yTop, right[2])

        addTri(tip, right, left)
        addTri(tipTop, leftTop, rightTop)
        addTri(left, right, rightTop)
        addTri(left, rightTop, leftTop)
        addTri(right, tip, tipTop)
        addTri(right, tipTop, rightTop)
        addTri(tip, left, leftTop)
        addTri(tip, leftTop, tipTop)

        // Arrowhead outline edges
        addLine(tip, right)
        addLine(right, left)
        addLine(left, tip)
        addLine(tipTop, rightTop)
        addLine(rightTop, leftTop)
        addLine(leftTop, tipTop)

        return GeometryPack(
            faceVerts.toFloatArray(),
            faceColors.toFloatArray(),
            lineVerts.toFloatArray(),
            lineColors.toFloatArray()
        )
    }

    private data class GeometryPack(
        val faceVertices: FloatArray,
        val faceColors: FloatArray,
        val outlineVertices: FloatArray,
        val outlineColors: FloatArray
    )

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("AgentRenderer shader compilation failed (type=$type): $log")
        }
        return shader
    }
}
