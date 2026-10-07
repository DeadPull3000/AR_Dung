package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer that visualizes a 3D diagnostic marker attached to an ARCore Anchor.
 *
 * Renders:
 * 1. A 3D solid cube (8 cm) resting on top of the physical anchor plane surface (Y in [0.0, 0.08] m).
 * 2. High-contrast wireframe edge outlines for sharp depth visibility against real-world video.
 * 3. 3-axis coordinate gizmo lines (X=Red, Y=Green, Z=Blue, 14 cm) centered at the Anchor origin.
 *
 * All geometry is pre-allocated and cached in GPU-accessible FloatBuffers to ensure zero GC churn.
 */
class AnchorMarkerRenderer {

    companion object {
        private const val TAG = "AnchorMarkerRenderer"
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

        // Cube dimensions (meters)
        private const val HALF_WIDTH = 0.04f   // 8 cm wide (-0.04 to +0.04)
        private const val HALF_DEPTH = 0.04f   // 8 cm deep (-0.04 to +0.04)
        private const val HEIGHT = 0.08f       // 8 cm high (0.0 to 0.08, resting on surface)
        private const val AXIS_LENGTH = 0.14f  // 14 cm coordinate axis lines
    }

    private var program = 0
    private var mvpUniform = 0
    private var positionAttrib = 0
    private var colorAttrib = 0

    // Model and MVP matrices (reused per frame)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Buffers for Cube Faces (GL_TRIANGLES, 36 vertices)
    private val cubeFaceVertexBuffer: FloatBuffer
    private val cubeFaceColorBuffer: FloatBuffer
    private val cubeFaceVertexCount = 36

    // Buffers for Cube Outlines (GL_LINES, 24 vertices)
    private val cubeLineVertexBuffer: FloatBuffer
    private val cubeLineColorBuffer: FloatBuffer
    private val cubeLineVertexCount = 24

    // Buffers for Coordinate Axes (GL_LINES, 6 vertices)
    private val axisVertexBuffer: FloatBuffer
    private val axisColorBuffer: FloatBuffer
    private val axisVertexCount = 6

    init {
        // --- 1. Generate Cube Face Geometry & Colors (36 vertices) ---
        val (faceVerts, faceColors) = generateCubeFaceData()
        cubeFaceVertexBuffer = createDirectFloatBuffer(faceVerts)
        cubeFaceColorBuffer = createDirectFloatBuffer(faceColors)

        // --- 2. Generate Cube Line Outline Geometry & Colors (24 vertices) ---
        val (lineVerts, lineColors) = generateCubeOutlineData()
        cubeLineVertexBuffer = createDirectFloatBuffer(lineVerts)
        cubeLineColorBuffer = createDirectFloatBuffer(lineColors)

        // --- 3. Generate Coordinate Axes (6 vertices) ---
        val (axisVerts, axisColors) = generateAxisData()
        axisVertexBuffer = createDirectFloatBuffer(axisVerts)
        axisColorBuffer = createDirectFloatBuffer(axisColors)
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
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program, "a_Color")

        Log.i(TAG, "AnchorMarkerRenderer initialized on GL thread.")
    }

    /**
     * Renders the 3D diagnostic marker at the specified anchor pose in ARCore world space.
     */
    fun draw(anchorPose: Pose, viewProjectionMatrix: FloatArray) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(colorAttrib)

        // Compute Marker Model-View-Projection Matrix
        anchorPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        // 1. Draw 3D Translucent Cube Faces
        GLES20.glVertexAttribPointer(
            positionAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            cubeFaceVertexBuffer
        )
        GLES20.glVertexAttribPointer(
            colorAttrib,
            COLOR_COMPONENTS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            cubeFaceColorBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cubeFaceVertexCount)

        // 2. Draw Sharp High-Contrast Wireframe Outlines
        GLES20.glVertexAttribPointer(
            positionAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            cubeLineVertexBuffer
        )
        GLES20.glVertexAttribPointer(
            colorAttrib,
            COLOR_COMPONENTS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            cubeLineColorBuffer
        )
        GLES20.glLineWidth(3.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, cubeLineVertexCount)

        // 3. Draw RGB Coordinate Axes (X=Red, Y=Green, Z=Blue)
        GLES20.glVertexAttribPointer(
            positionAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            axisVertexBuffer
        )
        GLES20.glVertexAttribPointer(
            colorAttrib,
            COLOR_COMPONENTS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            axisColorBuffer
        )
        GLES20.glLineWidth(5.0f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, axisVertexCount)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
    }

    private fun generateCubeFaceData(): Pair<FloatArray, FloatArray> {
        val hw = HALF_WIDTH
        val hd = HALF_DEPTH
        val h = HEIGHT

        // 8 corner points of cube
        val p0 = floatArrayOf(-hw, 0f, -hd)
        val p1 = floatArrayOf(hw, 0f, -hd)
        val p2 = floatArrayOf(hw, 0f, hd)
        val p3 = floatArrayOf(-hw, 0f, hd)
        val p4 = floatArrayOf(-hw, h, -hd)
        val p5 = floatArrayOf(hw, h, -hd)
        val p6 = floatArrayOf(hw, h, hd)
        val p7 = floatArrayOf(-hw, h, hd)

        // Colors per face (RGBA with alpha 0.80 for 3D body visibility)
        val cTop = floatArrayOf(0.0f, 0.95f, 1.0f, 0.80f)     // Top: Bright Cyan
        val cBottom = floatArrayOf(0.0f, 0.40f, 0.50f, 0.80f)  // Bottom: Dark Teal
        val cFront = floatArrayOf(0.0f, 0.80f, 0.90f, 0.80f)   // Front (+Z): Vibrant Cyan
        val cBack = floatArrayOf(0.0f, 0.50f, 0.65f, 0.80f)    // Back (-Z): Medium Teal
        val cRight = floatArrayOf(0.2f, 0.90f, 1.0f, 0.80f)    // Right (+X): Aqua
        val cLeft = floatArrayOf(0.0f, 0.60f, 0.75f, 0.80f)    // Left (-X): Deep Cyan

        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()

        fun addQuad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, col: FloatArray) {
            // Triangle 1: a, b, c
            vertices.addAll(listOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]))
            // Triangle 2: a, c, d
            vertices.addAll(listOf(a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2]))
            for (i in 0 until 6) {
                colors.addAll(listOf(col[0], col[1], col[2], col[3]))
            }
        }

        // Top (+Y)
        addQuad(p4, p5, p6, p7, cTop)
        // Bottom (-Y)
        addQuad(p0, p3, p2, p1, cBottom)
        // Front (+Z)
        addQuad(p3, p2, p6, p7, cFront)
        // Back (-Z)
        addQuad(p1, p0, p4, p5, cBack)
        // Right (+X)
        addQuad(p2, p1, p5, p6, cRight)
        // Left (-X)
        addQuad(p0, p3, p7, p4, cLeft)

        return Pair(vertices.toFloatArray(), colors.toFloatArray())
    }

    private fun generateCubeOutlineData(): Pair<FloatArray, FloatArray> {
        val hw = HALF_WIDTH
        val hd = HALF_DEPTH
        val h = HEIGHT

        val p0 = floatArrayOf(-hw, 0f, -hd)
        val p1 = floatArrayOf(hw, 0f, -hd)
        val p2 = floatArrayOf(hw, 0f, hd)
        val p3 = floatArrayOf(-hw, 0f, hd)
        val p4 = floatArrayOf(-hw, h, -hd)
        val p5 = floatArrayOf(hw, h, -hd)
        val p6 = floatArrayOf(hw, h, hd)
        val p7 = floatArrayOf(-hw, h, hd)

        val cLine = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // Crisp White Edges

        val vertices = mutableListOf<Float>()
        val colors = mutableListOf<Float>()

        fun addLine(a: FloatArray, b: FloatArray) {
            vertices.addAll(listOf(a[0], a[1], a[2], b[0], b[1], b[2]))
            colors.addAll(listOf(cLine[0], cLine[1], cLine[2], cLine[3]))
            colors.addAll(listOf(cLine[0], cLine[1], cLine[2], cLine[3]))
        }

        // Bottom 4 edges
        addLine(p0, p1); addLine(p1, p2); addLine(p2, p3); addLine(p3, p0)
        // Top 4 edges
        addLine(p4, p5); addLine(p5, p6); addLine(p6, p7); addLine(p7, p4)
        // Vertical 4 edges
        addLine(p0, p4); addLine(p1, p5); addLine(p2, p6); addLine(p3, p7)

        return Pair(vertices.toFloatArray(), colors.toFloatArray())
    }

    private fun generateAxisData(): Pair<FloatArray, FloatArray> {
        val len = AXIS_LENGTH
        val vertices = floatArrayOf(
            // +X Axis (Red)
            0.0f, 0.0f, 0.0f,   len, 0.0f, 0.0f,
            // +Y Axis (Green - Surface Normal)
            0.0f, 0.0f, 0.0f,   0.0f, len, 0.0f,
            // +Z Axis (Blue)
            0.0f, 0.0f, 0.0f,   0.0f, 0.0f, len
        )

        val colors = floatArrayOf(
            // Red
            1.0f, 0.15f, 0.15f, 1.0f,   1.0f, 0.15f, 0.15f, 1.0f,
            // Green
            0.15f, 1.0f, 0.25f, 1.0f,   0.15f, 1.0f, 0.25f, 1.0f,
            // Blue
            0.20f, 0.60f, 1.0f, 1.0f,   0.20f, 0.60f, 1.0f, 1.0f
        )

        return Pair(vertices, colors)
    }

    private fun createDirectFloatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.position(0)
        return buffer
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)

            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val info = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compilation failed: $info")
            }
        }
    }
}
