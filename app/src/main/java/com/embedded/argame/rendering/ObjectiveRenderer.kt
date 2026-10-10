package com.embedded.argame.rendering

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.embedded.argame.environment.FloorReference
import com.embedded.argame.game.MissionObjective
import com.embedded.argame.game.ObjectiveStatus
import com.embedded.argame.game.ObjectiveType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hardware-accelerated OpenGL ES 3.0 renderer for mission objectives (Milestone 12).
 *
 * Renders:
 * 1. Collectible Relics: Stylized 3D faceted gemstones (floating diamond bipyramids)
 *    in brilliant golden amber with high-contrast outlines and subtle floating animation.
 * 2. Extraction Portal: Concentric floor ring and central beacon marker displaying
 *    distinct states (Dim Crimson/Orange when LOCKED, Radiant Neon Cyan/Emerald when ACTIVE).
 *
 * Preallocates all GPU buffers and transformation matrices to guarantee ZERO heap allocations
 * in the 60 FPS render loop.
 */
class ObjectiveRenderer {

    companion object {
        private const val TAG = "ObjectiveRenderer"
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

        // Relic Diamond Dimensions
        private const val RELIC_RADIUS = 0.06f       // 12 cm width
        private const val RELIC_WAIST_Y = 0.12f      // 12 cm above floor
        private const val RELIC_TOP_Y = 0.20f        // 20 cm top peak
        private const val RELIC_BOTTOM_Y = 0.05f     // 5 cm bottom tip
        private const val RELIC_SIDES = 8

        // Extraction Portal Dimensions
        private const val PORTAL_INNER_RADIUS = 0.22f
        private const val PORTAL_OUTER_RADIUS = 0.32f
        private const val PORTAL_HEIGHT_Y = 0.015f
        private const val PORTAL_BEACON_RADIUS = 0.04f
        private const val PORTAL_BEACON_HEIGHT = 0.30f
        private const val PORTAL_SEGMENTS = 16
    }

    private var program = 0
    private var mvpUniform = 0
    private var positionAttrib = 0
    private var colorAttrib = 0

    // Relic Geometry Buffers
    private val relicFaceVertexBuffer: FloatBuffer
    private val relicFaceColorBuffer: FloatBuffer
    private val relicOutlineVertexBuffer: FloatBuffer
    private val relicOutlineColorBuffer: FloatBuffer
    private val relicFaceVertexCount: Int
    private val relicOutlineVertexCount: Int

    // Extraction Geometry Buffers (Locked vs Active)
    private val portalLockedFaceVertexBuffer: FloatBuffer
    private val portalLockedFaceColorBuffer: FloatBuffer
    private val portalActiveFaceColorBuffer: FloatBuffer
    private val portalOutlineVertexBuffer: FloatBuffer
    private val portalLockedOutlineColorBuffer: FloatBuffer
    private val portalActiveOutlineColorBuffer: FloatBuffer
    private val portalFaceVertexCount: Int
    private val portalOutlineVertexCount: Int

    // Preallocated transformation matrices
    private val localModelMatrix = FloatArray(16)
    private val floorToWorldMatrix = FloatArray(16)
    private val worldModelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Cached objectives for thread-safe rendering
    private val objectivesLock = Any()
    private val cachedObjectives = mutableListOf<MissionObjective>()

    init {
        // 1. Generate Relic Diamond Geometry
        val (rFaces, rFaceColors, rLines, rLineColors) = generateRelicGeometry()
        relicFaceVertexCount = rFaces.size / COORDS_PER_VERTEX
        relicOutlineVertexCount = rLines.size / COORDS_PER_VERTEX

        val rfBuf = ByteBuffer.allocateDirect(rFaces.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        relicFaceVertexBuffer = rfBuf.asFloatBuffer().apply { put(rFaces).position(0) }

        val rfcBuf = ByteBuffer.allocateDirect(rFaceColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        relicFaceColorBuffer = rfcBuf.asFloatBuffer().apply { put(rFaceColors).position(0) }

        val roBuf = ByteBuffer.allocateDirect(rLines.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        relicOutlineVertexBuffer = roBuf.asFloatBuffer().apply { put(rLines).position(0) }

        val rocBuf = ByteBuffer.allocateDirect(rLineColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        relicOutlineColorBuffer = rocBuf.asFloatBuffer().apply { put(rLineColors).position(0) }

        // 2. Generate Extraction Portal Geometry
        val (pFaces, pLockedFaceColors, pActiveFaceColors, pLines, pLockedLineColors, pActiveLineColors) = generatePortalGeometry()
        portalFaceVertexCount = pFaces.size / COORDS_PER_VERTEX
        portalOutlineVertexCount = pLines.size / COORDS_PER_VERTEX

        val pfBuf = ByteBuffer.allocateDirect(pFaces.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalLockedFaceVertexBuffer = pfBuf.asFloatBuffer().apply { put(pFaces).position(0) }

        val plfcBuf = ByteBuffer.allocateDirect(pLockedFaceColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalLockedFaceColorBuffer = plfcBuf.asFloatBuffer().apply { put(pLockedFaceColors).position(0) }

        val pafcBuf = ByteBuffer.allocateDirect(pActiveFaceColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalActiveFaceColorBuffer = pafcBuf.asFloatBuffer().apply { put(pActiveFaceColors).position(0) }

        val poBuf = ByteBuffer.allocateDirect(pLines.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalOutlineVertexBuffer = poBuf.asFloatBuffer().apply { put(pLines).position(0) }

        val plocBuf = ByteBuffer.allocateDirect(pLockedLineColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalLockedOutlineColorBuffer = plocBuf.asFloatBuffer().apply { put(pLockedLineColors).position(0) }

        val paocBuf = ByteBuffer.allocateDirect(pActiveLineColors.size * FLOAT_SIZE).order(ByteOrder.nativeOrder())
        portalActiveOutlineColorBuffer = paocBuf.asFloatBuffer().apply { put(pActiveLineColors).position(0) }
    }

    /**
     * Compiles GL shaders and links program. Call on GL render thread.
     */
    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(prog)
                GLES20.glDeleteProgram(prog)
                throw RuntimeException("ObjectiveRenderer GL program linking failed: $log")
            }

            mvpUniform = GLES20.glGetUniformLocation(prog, "u_ModelViewProjection")
            positionAttrib = GLES20.glGetAttribLocation(prog, "a_Position")
            colorAttrib = GLES20.glGetAttribLocation(prog, "a_Color")
        }

        Log.i(TAG, "ObjectiveRenderer created on GL thread.")
    }

    /**
     * Updates the cached objective snapshots. Safe to call from perception or worker thread.
     */
    fun updateObjectives(objectives: List<MissionObjective>) {
        synchronized(objectivesLock) {
            cachedObjectives.clear()
            cachedObjectives.addAll(objectives)
        }
    }

    /**
     * Draws active mission objectives into the AR scene. Call on GL render thread.
     */
    fun draw(viewProjectionMatrix: FloatArray, floorReference: FloorReference) {
        if (program == 0 || !floorReference.isTracking) return

        val snapshotList: List<MissionObjective>
        synchronized(objectivesLock) {
            if (cachedObjectives.isEmpty()) return
            snapshotList = cachedObjectives.toList()
        }

        floorReference.getFloorToWorldMatrix(floorToWorldMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glEnableVertexAttribArray(colorAttrib)

        val timeSeconds = (System.currentTimeMillis() % 100_000L) / 1000f

        for (obj in snapshotList) {
            when (obj.type) {
                ObjectiveType.RELIC -> {
                    // Only render relics that are still AVAILABLE
                    if (obj.isAvailable) {
                        drawRelic(obj, viewProjectionMatrix, timeSeconds)
                    }
                }
                ObjectiveType.EXTRACTION -> {
                    drawExtractionPortal(obj, viewProjectionMatrix, timeSeconds)
                }
            }
        }

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawRelic(
        relic: MissionObjective,
        viewProjectionMatrix: FloatArray,
        timeSeconds: Float
    ) {
        // Floating bobbing effect and smooth spin
        val bobOffset = 0.02f * sin(3.0f * timeSeconds + relic.id)
        val spinDegrees = (timeSeconds * 45f + relic.id * 90f) % 360f

        Matrix.setIdentityM(localModelMatrix, 0)
        Matrix.translateM(localModelMatrix, 0, relic.floorX, bobOffset, relic.floorZ)
        Matrix.rotateM(localModelMatrix, 0, spinDegrees, 0f, 1f, 0f)

        // worldModelMatrix = floorToWorld * localModel
        Matrix.multiplyMM(worldModelMatrix, 0, floorToWorldMatrix, 0, localModelMatrix, 0)
        // mvpMatrix = viewProjection * worldModel
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, worldModelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        // Draw diamond faces
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, relicFaceVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, relicFaceColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, relicFaceVertexCount)

        // Draw diamond wireframe outline
        GLES20.glLineWidth(2.5f)
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, relicOutlineVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, relicOutlineColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, relicOutlineVertexCount)
    }

    private fun drawExtractionPortal(
        portal: MissionObjective,
        viewProjectionMatrix: FloatArray,
        timeSeconds: Float
    ) {
        Matrix.setIdentityM(localModelMatrix, 0)
        Matrix.translateM(localModelMatrix, 0, portal.floorX, 0f, portal.floorZ)

        val spinDegrees = (timeSeconds * 20f) % 360f
        Matrix.rotateM(localModelMatrix, 0, spinDegrees, 0f, 1f, 0f)

        Matrix.multiplyMM(worldModelMatrix, 0, floorToWorldMatrix, 0, localModelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, viewProjectionMatrix, 0, worldModelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvpMatrix, 0)

        val isActive = portal.status == ObjectiveStatus.ACTIVE
        val faceColorBuffer = if (isActive) portalActiveFaceColorBuffer else portalLockedFaceColorBuffer
        val outlineColorBuffer = if (isActive) portalActiveOutlineColorBuffer else portalLockedOutlineColorBuffer

        // Draw portal disc and beacon faces
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, portalLockedFaceVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, faceColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, portalFaceVertexCount)

        // Draw portal outlines
        GLES20.glLineWidth(2.5f)
        GLES20.glVertexAttribPointer(positionAttrib, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, portalOutlineVertexBuffer)
        GLES20.glVertexAttribPointer(colorAttrib, COLOR_COMPONENTS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, outlineColorBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, portalOutlineVertexCount)
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("ObjectiveRenderer Shader compilation failed: $log")
        }
        return shader
    }

    // --- Geometry Generation Helpers ---

    private data class MeshData(
        val faces: FloatArray,
        val faceColors: FloatArray,
        val lines: FloatArray,
        val lineColors: FloatArray
    )

    private fun generateRelicGeometry(): MeshData {
        val faces = mutableListOf<Float>()
        val faceColors = mutableListOf<Float>()
        val lines = mutableListOf<Float>()
        val lineColors = mutableListOf<Float>()

        val topApex = floatArrayOf(0f, RELIC_TOP_Y, 0f)
        val bottomApex = floatArrayOf(0f, RELIC_BOTTOM_Y, 0f)

        // Waist vertices
        val waist = Array(RELIC_SIDES) { i ->
            val angle = 2.0 * Math.PI * i / RELIC_SIDES
            floatArrayOf(
                (RELIC_RADIUS * cos(angle)).toFloat(),
                RELIC_WAIST_Y,
                (RELIC_RADIUS * sin(angle)).toFloat()
            )
        }

        // Golden amber palette
        val upperGold = floatArrayOf(1.0f, 0.85f, 0.15f, 0.95f)
        val upperShade = floatArrayOf(0.95f, 0.72f, 0.10f, 0.95f)
        val lowerGold = floatArrayOf(0.90f, 0.60f, 0.05f, 0.95f)
        val lowerShade = floatArrayOf(0.80f, 0.50f, 0.05f, 0.95f)
        val outlineGold = floatArrayOf(1.0f, 1.0f, 0.60f, 1.0f)

        for (i in 0 until RELIC_SIDES) {
            val next = (i + 1) % RELIC_SIDES
            val vCurr = waist[i]
            val vNext = waist[next]

            // Upper pyramid triangle
            faces.addAll(listOf(topApex[0], topApex[1], topApex[2]))
            faces.addAll(listOf(vCurr[0], vCurr[1], vCurr[2]))
            faces.addAll(listOf(vNext[0], vNext[1], vNext[2]))

            val uColor = if (i % 2 == 0) upperGold else upperShade
            repeat(3) { faceColors.addAll(uColor.toList()) }

            // Lower pyramid triangle
            faces.addAll(listOf(bottomApex[0], bottomApex[1], bottomApex[2]))
            faces.addAll(listOf(vNext[0], vNext[1], vNext[2]))
            faces.addAll(listOf(vCurr[0], vCurr[1], vCurr[2]))

            val lColor = if (i % 2 == 0) lowerGold else lowerShade
            repeat(3) { faceColors.addAll(lColor.toList()) }

            // Wireframe outlines
            // Waist edge
            lines.addAll(listOf(vCurr[0], vCurr[1], vCurr[2], vNext[0], vNext[1], vNext[2]))
            repeat(2) { lineColors.addAll(outlineGold.toList()) }

            // Top ridge
            lines.addAll(listOf(topApex[0], topApex[1], topApex[2], vCurr[0], vCurr[1], vCurr[2]))
            repeat(2) { lineColors.addAll(outlineGold.toList()) }

            // Bottom ridge
            lines.addAll(listOf(bottomApex[0], bottomApex[1], bottomApex[2], vCurr[0], vCurr[1], vCurr[2]))
            repeat(2) { lineColors.addAll(outlineGold.toList()) }
        }

        return MeshData(faces.toFloatArray(), faceColors.toFloatArray(), lines.toFloatArray(), lineColors.toFloatArray())
    }

    private data class PortalMeshData(
        val faces: FloatArray,
        val lockedFaceColors: FloatArray,
        val activeFaceColors: FloatArray,
        val lines: FloatArray,
        val lockedLineColors: FloatArray,
        val activeLineColors: FloatArray
    )

    private fun generatePortalGeometry(): PortalMeshData {
        val faces = mutableListOf<Float>()
        val lockedFaceColors = mutableListOf<Float>()
        val activeFaceColors = mutableListOf<Float>()
        val lines = mutableListOf<Float>()
        val lockedLineColors = mutableListOf<Float>()
        val activeLineColors = mutableListOf<Float>()

        val lockedFace = floatArrayOf(0.85f, 0.20f, 0.10f, 0.70f)
        val activeFace = floatArrayOf(0.0f, 0.90f, 0.75f, 0.85f)
        val lockedBeacon = floatArrayOf(0.95f, 0.30f, 0.15f, 0.90f)
        val activeBeacon = floatArrayOf(0.20f, 1.0f, 0.95f, 0.95f)

        val lockedOutline = floatArrayOf(1.0f, 0.40f, 0.20f, 1.0f)
        val activeOutline = floatArrayOf(0.60f, 1.0f, 0.90f, 1.0f)

        // 1. Concentric Floor Disc / Ring
        for (i in 0 until PORTAL_SEGMENTS) {
            val a0 = 2.0 * Math.PI * i / PORTAL_SEGMENTS
            val a1 = 2.0 * Math.PI * (i + 1) / PORTAL_SEGMENTS

            val ix0 = (PORTAL_INNER_RADIUS * cos(a0)).toFloat()
            val iz0 = (PORTAL_INNER_RADIUS * sin(a0)).toFloat()
            val ox0 = (PORTAL_OUTER_RADIUS * cos(a0)).toFloat()
            val oz0 = (PORTAL_OUTER_RADIUS * sin(a0)).toFloat()

            val ix1 = (PORTAL_INNER_RADIUS * cos(a1)).toFloat()
            val iz1 = (PORTAL_INNER_RADIUS * sin(a1)).toFloat()
            val ox1 = (PORTAL_OUTER_RADIUS * cos(a1)).toFloat()
            val oz1 = (PORTAL_OUTER_RADIUS * sin(a1)).toFloat()

            val y = PORTAL_HEIGHT_Y

            // Quad as 2 triangles (inner0, outer0, outer1) and (inner0, outer1, inner1)
            faces.addAll(listOf(ix0, y, iz0, ox0, y, oz0, ox1, y, oz1))
            faces.addAll(listOf(ix0, y, iz0, ox1, y, oz1, ix1, y, iz1))

            repeat(6) {
                lockedFaceColors.addAll(lockedFace.toList())
                activeFaceColors.addAll(activeFace.toList())
            }

            // Outlines: Inner edge and outer edge
            lines.addAll(listOf(ix0, y, iz0, ix1, y, iz1))
            lines.addAll(listOf(ox0, y, oz0, ox1, y, oz1))
            repeat(4) {
                lockedLineColors.addAll(lockedOutline.toList())
                activeLineColors.addAll(activeOutline.toList())
            }
        }

        // 2. Central Beacon Column
        for (i in 0 until PORTAL_SEGMENTS) {
            val a0 = 2.0 * Math.PI * i / PORTAL_SEGMENTS
            val a1 = 2.0 * Math.PI * (i + 1) / PORTAL_SEGMENTS

            val x0 = (PORTAL_BEACON_RADIUS * cos(a0)).toFloat()
            val z0 = (PORTAL_BEACON_RADIUS * sin(a0)).toFloat()
            val x1 = (PORTAL_BEACON_RADIUS * cos(a1)).toFloat()
            val z1 = (PORTAL_BEACON_RADIUS * sin(a1)).toFloat()

            val yBot = PORTAL_HEIGHT_Y
            val yTop = PORTAL_BEACON_HEIGHT

            // Beacon quad (2 triangles)
            faces.addAll(listOf(x0, yBot, z0, x1, yBot, z1, x1, yTop, z1))
            faces.addAll(listOf(x0, yBot, z0, x1, yTop, z1, x0, yTop, z0))

            repeat(6) {
                lockedFaceColors.addAll(lockedBeacon.toList())
                activeFaceColors.addAll(activeBeacon.toList())
            }

            // Top outline edge
            lines.addAll(listOf(x0, yTop, z0, x1, yTop, z1))
            repeat(2) {
                lockedLineColors.addAll(lockedOutline.toList())
                activeLineColors.addAll(activeOutline.toList())
            }
        }

        return PortalMeshData(
            faces.toFloatArray(),
            lockedFaceColors.toFloatArray(),
            activeFaceColors.toFloatArray(),
            lines.toFloatArray(),
            lockedLineColors.toFloatArray(),
            activeLineColors.toFloatArray()
        )
    }
}
