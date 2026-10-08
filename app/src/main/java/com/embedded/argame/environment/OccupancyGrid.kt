package com.embedded.argame.environment

import android.util.Log
import com.embedded.argame.perception.CameraIntrinsicsData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded 2.5D Occupancy Grid representing walkable, blocked, and unknown space
 * near the physical floor.
 *
 * Coordinates:
 * - Defined in Floor Space relative to the detected floor plane center.
 * - Center is (0, 0).
 * - X spans [-widthMeters / 2, +widthMeters / 2]
 * - Z spans [-depthMeters / 2, +depthMeters / 2]
 * - Each cell covers cellSizeMeters x cellSizeMeters.
 */
class OccupancyGrid(
    val cellSizeMeters: Float = 0.10f,
    val widthMeters: Float = 8.0f,
    val depthMeters: Float = 8.0f,
    val floorHeightTolerance: Float = 0.08f,
    val minObstacleHeight: Float = 0.10f,
    val maxNavigationHeight: Float = 1.50f,
    val maxDropTolerance: Float = -0.15f
) {

    companion object {
        private const val TAG = "OccupancyGrid"
        private const val OCCUPIED_THRESHOLD = 2.0f
        private const val FREE_THRESHOLD = 2.0f
        private const val MAX_EVIDENCE = 10.0f
        private const val EVIDENCE_DECAY = 0.985f
    }

    val numCellsX: Int = (widthMeters / cellSizeMeters).toInt()
    val numCellsZ: Int = (depthMeters / cellSizeMeters).toInt()
    val totalCells: Int = numCellsX * numCellsZ

    // Flat compact arrays to eliminate GC allocation during 10 Hz perception loops
    private val cellStates = ByteArray(totalCells)
    private val freeEvidence = FloatArray(totalCells)
    private val occupiedEvidence = FloatArray(totalCells)

    // RGBA texture buffer for OpenGL ES 3.0 visualization
    val textureByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(totalCells * 4).order(ByteOrder.nativeOrder())
    private val gridLock = Any()
    var hasNewTextureData: Boolean = false
        private set

    // Diagnostic metrics
    private var updateFrameCount = 0
    private var lastRateTimestampNs = 0L
    private var currentUpdateHz = 0f

    init {
        reset()
        Log.i(TAG, "OccupancyGrid initialized: %dx%d cells (%d total), resolution=%.2fm, bounds=%.1fx%.1fm".format(
            numCellsX, numCellsZ, totalCells, cellSizeMeters, widthMeters, depthMeters
        ))
    }

    /**
     * Resets all cells to UNKNOWN and clears evidence.
     */
    fun reset() {
        synchronized(gridLock) {
            cellStates.fill(GridCellState.UNKNOWN.code)
            freeEvidence.fill(0f)
            occupiedEvidence.fill(0f)
            updateTextureBuffer()
        }
    }

    /**
     * Converts floor-relative coordinates (xf, zf in meters) to grid cell indices (col, row).
     * Returns null if coordinates lie outside grid bounds.
     */
    fun floorToCell(xf: Float, zf: Float): Pair<Int, Int>? {
        val col = ((xf + widthMeters / 2f) / cellSizeMeters).toInt()
        val row = ((zf + depthMeters / 2f) / cellSizeMeters).toInt()
        if (col in 0 until numCellsX && row in 0 until numCellsZ) {
            return Pair(col, row)
        }
        return null
    }

    /**
     * Converts grid cell indices (col, row) to floor-relative coordinates (xf, zf in meters) at cell center.
     */
    fun cellToFloorCoord(col: Int, row: Int): Pair<Float, Float> {
        val xf = (col + 0.5f) * cellSizeMeters - widthMeters / 2f
        val zf = (row + 0.5f) * cellSizeMeters - depthMeters / 2f
        return Pair(xf, zf)
    }

    fun cellToIndex(col: Int, row: Int): Int = row * numCellsX + col

    fun getCellState(col: Int, row: Int): GridCellState {
        synchronized(gridLock) {
            val idx = cellToIndex(col, row)
            return GridCellState.fromCode(cellStates[idx])
        }
    }

    /**
     * Integrates 16-bit depth measurements and detected floor polygon into the occupancy grid.
     * Throttled to 5–10 Hz on the GL perception thread.
     */
    fun updateWithDepth(
        depthBuffer: ByteBuffer,
        depthWidth: Int,
        depthHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        intrinsics: CameraIntrinsicsData,
        matrixCamToFloor: FloatArray,
        floorExtentX: Float,
        floorExtentZ: Float,
        floorTracking: Boolean
    ): GridDiagnostics {
        val now = System.nanoTime()

        if (!floorTracking || intrinsics.fx <= 0f) {
            return generateDiagnostics(floorTracking, 0, 0, totalCells, now)
        }

        synchronized(gridLock) {
            // 1. Apply gentle temporal evidence decay to allow moving obstacles to clear
            for (i in 0 until totalCells) {
                freeEvidence[i] *= EVIDENCE_DECAY
                occupiedEvidence[i] *= EVIDENCE_DECAY
            }

            // 2. Mark detected floor plane extent as baseline FREE space
            if (floorExtentX > 0.2f && floorExtentZ > 0.2f) {
                val halfX = floorExtentX / 2f
                val halfZ = floorExtentZ / 2f
                val minCol = (((-halfX + widthMeters / 2f) / cellSizeMeters).toInt()).coerceIn(0, numCellsX - 1)
                val maxCol = (((halfX + widthMeters / 2f) / cellSizeMeters).toInt()).coerceIn(0, numCellsX - 1)
                val minRow = (((-halfZ + depthMeters / 2f) / cellSizeMeters).toInt()).coerceIn(0, numCellsZ - 1)
                val maxRow = (((halfZ + depthMeters / 2f) / cellSizeMeters).toInt()).coerceIn(0, numCellsZ - 1)

                for (r in minRow..maxRow) {
                    for (c in minCol..maxCol) {
                        val idx = cellToIndex(c, r)
                        // Add baseline free evidence on tracked floor
                        freeEvidence[idx] = (freeEvidence[idx] + 0.35f).coerceAtMost(MAX_EVIDENCE)
                    }
                }
            }

            // 3. Backproject depth points and classify as Obstacle or Floor
            val step = 2 // 2x grid downsampling (80x45 = 3,600 samples)
            val fx = intrinsics.fx
            val fy = intrinsics.fy
            val cx = intrinsics.cx
            val cy = intrinsics.cy

            val m = matrixCamToFloor

            for (y in 0 until depthHeight step step) {
                for (x in 0 until depthWidth step step) {
                    val offset = y * rowStride + x * pixelStride
                    val depthMm = depthBuffer.getShort(offset).toInt() and 0xFFFF

                    // Accept valid metric depth readings (0.10m to 8.0m)
                    if (depthMm in 100..8000) {
                        val zMeters = depthMm / 1000.0f

                        // 3D Point in Camera Space (Pinhole inverse projection)
                        val xc = (x - cx) * zMeters / fx
                        val yc = (y - cy) * zMeters / fy
                        val zc = zMeters

                        // Transform directly to Floor Space using column-major matrix
                        val xf = m[0] * xc + m[4] * yc + m[8] * zc + m[12]
                        val yf = m[1] * xc + m[5] * yc + m[9] * zc + m[13]
                        val zf = m[2] * xc + m[6] * yc + m[10] * zc + m[14]

                        val cell = floorToCell(xf, zf) ?: continue
                        val idx = cellToIndex(cell.first, cell.second)

                        when {
                            // Geometric Obstacle: height above floor between minObstacleHeight and maxNavigationHeight
                            yf in minObstacleHeight..maxNavigationHeight -> {
                                occupiedEvidence[idx] = (occupiedEvidence[idx] + 1.0f).coerceAtMost(MAX_EVIDENCE)
                                freeEvidence[idx] = (freeEvidence[idx] - 0.25f).coerceAtLeast(0f)
                            }
                            // Geometric Floor: height within floor tolerance
                            yf in -floorHeightTolerance..floorHeightTolerance -> {
                                freeEvidence[idx] = (freeEvidence[idx] + 1.0f).coerceAtMost(MAX_EVIDENCE)
                                occupiedEvidence[idx] = (occupiedEvidence[idx] - 0.25f).coerceAtLeast(0f)
                            }
                            // yf > maxNavigationHeight: above ceiling/head clearance, do not block floor
                            // yf < maxDropTolerance: deep pit or noise below floor, ignore
                        }
                    }
                }
            }

            // 4. Update Tri-State Cell Classifications
            var freeCount = 0
            var occCount = 0
            var unkCount = 0

            for (i in 0 until totalCells) {
                val occ = occupiedEvidence[i]
                val free = freeEvidence[i]

                val state = when {
                    occ >= OCCUPIED_THRESHOLD -> GridCellState.OCCUPIED
                    free >= FREE_THRESHOLD -> GridCellState.FREE
                    else -> GridCellState.UNKNOWN
                }

                cellStates[i] = state.code
                when (state) {
                    GridCellState.FREE -> freeCount++
                    GridCellState.OCCUPIED -> occCount++
                    GridCellState.UNKNOWN -> unkCount++
                }
            }

            // 5. Update GPU Texture Buffer
            updateTextureBuffer()

            // 6. Calculate update rate
            updateFrameCount++
            val elapsedRate = now - lastRateTimestampNs
            if (elapsedRate >= 1_000_000_000L) {
                currentUpdateHz = (updateFrameCount * 1_000_000_000f) / elapsedRate
                updateFrameCount = 0
                lastRateTimestampNs = now
            }

            return generateDiagnostics(floorTracking, freeCount, occCount, unkCount, now)
        }
    }

    /**
     * Synthesizes RGBA texture pixels for OpenGL ES visualization.
     */
    private fun updateTextureBuffer() {
        textureByteBuffer.clear()
        for (i in 0 until totalCells) {
            when (cellStates[i]) {
                GridCellState.FREE.code -> {
                    // Emerald Green (Walkable)
                    textureByteBuffer.put(0.toByte()).put(230.toByte()).put(118.toByte()).put(200.toByte())
                }
                GridCellState.OCCUPIED.code -> {
                    // Vivid Crimson Red (Blocked)
                    textureByteBuffer.put(255.toByte()).put(23.toByte()).put(68.toByte()).put(230.toByte())
                }
                else -> {
                    // Dark Charcoal Gray (Unknown / Unobserved)
                    textureByteBuffer.put(24.toByte()).put(32.toByte()).put(40.toByte()).put(80.toByte())
                }
            }
        }
        textureByteBuffer.flip()
        hasNewTextureData = true
    }

    private fun generateDiagnostics(
        floorTracking: Boolean,
        free: Int,
        occ: Int,
        unk: Int,
        timestamp: Long
    ): GridDiagnostics {
        val observed = free + occ
        val coverage = if (totalCells > 0) (observed * 100f / totalCells) else 0f
        return GridDiagnostics(
            isFloorTracking = floorTracking,
            floorStatus = if (floorTracking) "TRACKING" else "WAITING",
            widthCells = numCellsX,
            depthCells = numCellsZ,
            cellSizeMeters = cellSizeMeters,
            totalCells = totalCells,
            freeCount = free,
            occupiedCount = occ,
            unknownCount = unk,
            coveragePercent = coverage,
            updateHz = currentUpdateHz,
            lastUpdateTimestampNs = timestamp,
            minObstacleHeight = minObstacleHeight,
            maxNavigationHeight = maxNavigationHeight
        )
    }

    fun markTextureConsumed() {
        hasNewTextureData = false
    }
}
