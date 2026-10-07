package com.embedded.argame.rendering

import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.0 renderer that visualizes a real-time false-color depth heatmap overlay.
 *
 * Samples 16-bit metric depth data into a dynamic RGBA texture and projects it
 * over the live AR camera view using ARCore's screen-to-image coordinate alignment.
 *
 * Color Ramp:
 * - 0 mm / Invalid: Transparent (Alpha = 0.0)
 * - 0.2m - 0.8m (Near foreground): Vivid Red -> Orange
 * - 0.8m - 1.5m (Mid-near, tables/chairs): Amber -> Yellow
 * - 1.5m - 2.5m (Mid range, floor/furniture): Yellow-Green -> Emerald
 * - 2.5m - 4.0m (Mid-far): Cyan -> Blue
 * - > 4.0m (Far background/walls): Deep Indigo -> Purple
 */
class DepthHeatmapRenderer {

    companion object {
        private const val TAG = "DepthHeatmapRenderer"
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 2
        private const val TEX_COORDS_PER_VERTEX = 2

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                v_TexCoord = a_TexCoord;
                gl_Position = a_Position;
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

        // Fullscreen Normalized Device Coordinates (NDC) Quad [-1, 1]
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
    }

    private var program = 0
    private var positionAttrib = 0
    private var texCoordAttrib = 0
    private var textureUniform = 0
    private var alphaUniform = 0

    private var textureId = -1
    private var currentTextureWidth = 0
    private var currentTextureHeight = 0
    private var hasUploadedTexture = false

    // Geometry buffers pre-allocated
    private val quadVertexBuffer: FloatBuffer
    private val ndcCoordsBuffer: FloatBuffer
    private val transformedTexCoordsBuffer: FloatBuffer

    init {
        val byteBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())
        quadVertexBuffer = byteBuffer.asFloatBuffer()
        quadVertexBuffer.put(QUAD_COORDS)
        quadVertexBuffer.position(0)

        val ndcBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        ndcBuffer.order(ByteOrder.nativeOrder())
        ndcCoordsBuffer = ndcBuffer.asFloatBuffer()
        ndcCoordsBuffer.put(QUAD_COORDS)
        ndcCoordsBuffer.position(0)

        val texBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
        texBuffer.order(ByteOrder.nativeOrder())
        transformedTexCoordsBuffer = texBuffer.asFloatBuffer()
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        alphaUniform = GLES20.glGetUniformLocation(program, "u_Alpha")

        // Allocate 2D Texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        Log.i(TAG, "DepthHeatmapRenderer initialized on GL thread. Texture ID: $textureId")
    }

    /**
     * Uploads downsampled or full RGBA false-color depth buffer into the GPU texture.
     */
    fun updateTexture(width: Int, height: Int, rgbaPixelBuffer: ByteBuffer) {
        if (textureId < 0) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        rgbaPixelBuffer.position(0)

        if (width != currentTextureWidth || height != currentTextureHeight) {
            // Texture dimensions changed: reallocate texture storage
            currentTextureWidth = width
            currentTextureHeight = height
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                width,
                height,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgbaPixelBuffer
            )
            Log.i(TAG, "Depth heatmap texture initialized: ${width}x${height}")
        } else {
            // Dimensions unchanged: fast sub-image update
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                rgbaPixelBuffer
            )
        }
        hasUploadedTexture = true
    }

    /**
     * Renders the depth heatmap overlay aligned to the camera background.
     */
    fun draw(frame: Frame, alpha: Float = 0.50f) {
        if (!hasUploadedTexture || textureId < 0 || alpha <= 0.01f) return

        // Align NDC quad coordinates to depth camera image coordinates
        ndcCoordsBuffer.position(0)
        transformedTexCoordsBuffer.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcCoordsBuffer,
            Coordinates2d.IMAGE_NORMALIZED,
            transformedTexCoordsBuffer
        )

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glUniform1f(alphaUniform, alpha)

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

        transformedTexCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(
            texCoordAttrib,
            TEX_COORDS_PER_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            transformedTexCoordsBuffer
        )
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
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
                throw RuntimeException("Depth shader compilation failed: $info")
            }
        }
    }
}
