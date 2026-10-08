package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.OccupancyGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer that visualizes the 2.5D Occupancy Grid directly in ARCore 3D world space,
 * anchored horizontally to the detected physical floor plane.
 */
class OccupancyGridRenderer {

    companion object {
        private const val TAG = "OccupancyGridRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 3
        private const val TEX_COORDS_PER_VERTEX = 2

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform float u_Alpha;
            varying vec2 v_TexCoord;
            void main() {
                vec4 texColor = texture2D(u_Texture, v_TexCoord);
                gl_FragColor = vec4(texColor.rgb, texColor.a * u_Alpha);
            }
        """
    }

    private var program = 0
    private var mvpMatrixUniform = 0
    private var textureUniform = 0
    private var alphaUniform = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0

    private var textureId = -1
    private var isInitialized = false

    private val quadVertexBuffer: FloatBuffer
    private val quadTexCoordBuffer: FloatBuffer

    // Pre-allocated matrices
    private val floorModelMatrix = FloatArray(16)
    private val modelViewProjectionMatrix = FloatArray(16)

    init {
        // Horizontal Quad centered at floor origin (X in [-4, +4], Z in [-4, +4], Y = +0.005m)
        val halfW = 4.0f
        val halfD = 4.0f
        val yOffset = 0.005f // Elevate slightly above floor to prevent Z-fighting

        val vertices = floatArrayOf(
            -halfW, yOffset, -halfD, // 0: Top-Left
             halfW, yOffset, -halfD, // 1: Top-Right
            -halfW, yOffset,  halfD, // 2: Bottom-Left
             halfW, yOffset,  halfD  // 3: Bottom-Right
        )

        val texCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        val vBuf = ByteBuffer.allocateDirect(vertices.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        quadVertexBuffer = vBuf.asFloatBuffer().apply {
            put(vertices)
            position(0)
        }

        val tBuf = ByteBuffer.allocateDirect(texCoords.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        quadTexCoordBuffer = tBuf.asFloatBuffer().apply {
            put(texCoords)
            position(0)
        }
    }

    fun createOnGlThread(widthCells: Int = 80, depthCells: Int = 80) {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        mvpMatrixUniform = GLES20.glGetUniformLocation(program, "u_ModelViewProjection")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")

        // Create 2D dynamic texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        // Initialize texture memory
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            widthCells,
            depthCells,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        isInitialized = true
        Log.i(TAG, "OccupancyGridRenderer initialized on GL thread. Texture ID: $textureId")
    }

    /**
     * Uploads the latest occupancy grid texture buffer to the GPU.
     */
    fun updateTexture(grid: OccupancyGrid) {
        if (!isInitialized || textureId < 0) return

        if (grid.hasNewTextureData) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            grid.textureByteBuffer.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                grid.numCellsX,
                grid.numCellsZ,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                grid.textureByteBuffer
            )
            grid.markTextureConsumed()
        }
    }

    /**
     * Renders the 2.5D Occupancy Grid quad in 3D world space resting on the floor plane.
     */
    fun draw(viewProjectionMatrix: FloatArray, floorRef: FloorReference, alpha: Float = 0.65f) {
        if (!isInitialized || textureId < 0 || !floorRef.isTracking || alpha <= 0.01f) return

        // Compute Model-View-Projection Matrix: M_MVP = M_VP * M_FloorToWorld
        floorRef.getFloorToWorldMatrix(floorModelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, viewProjectionMatrix, 0, floorModelMatrix, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false) // Disable depth write to avoid obscuring foreground virtual objects

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform1f(alphaUniform, alpha)
        GLES20.glUniformMatrix4fv(mvpMatrixUniform, 1, false, modelViewProjectionMatrix, 0)

        quadVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            positionAttrib,
            COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadVertexBuffer
        )
        GLES20.glEnableVertexAttribArray(positionAttrib)

        quadTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            texCoordAttrib,
            TEX_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoordBuffer
        )
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("OccupancyGridRenderer Shader compilation failed: $error")
            }
        }
    }
}
