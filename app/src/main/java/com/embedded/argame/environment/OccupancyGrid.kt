package com.embedded.argame.environment

import android.util.Log
import com.embedded.argame.perception.CameraIntrinsicsData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounded 2.5D Occupancy Grid representing walkable, blocked, and unknown space
 * near the physical floor.
 *
 * Implements the Milestone 7 Spatial Perception Pipeline:
 * Raw Occupancy -> Spatial Filtering -> Obstacle Inflation -> Traversal Cost Matrix.
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

    // Milestone 7 Modular Pipeline Processors
    val spatialFilter = SpatialFilter(minOccupiedNeighbors = 1, strongEvidenceThreshold = 4.0f)
    val obstacleInflater = ObstacleInflater(cellSizeMeters, initialAgentRadiusMeters = 0.20f)
    val traversalCostGrid = TraversalCostGrid(totalCells, unknownPolicy = UnknownCostPolicy.BLOCKED)

    // Flat compact arrays to eliminate GC allocation during 10 Hz perception loops
    val rawCellStates = ByteArray(totalCells)
    val filteredCellStates = ByteArray(totalCells)
    val inflatedCellStates = ByteArray(totalCells)
    private val freeEvidence = FloatArray(totalCells)
    private val occupiedEvidence = FloatArray(totalCells)

    // Active visualization mode
    var displayMode: GridDisplayMode = GridDisplayMode.INFLATED
        private set

    // RGBA texture buffer for OpenGL ES 3.0 visualization
    val textureByteBuffer: ByteBuffer = ByteBuffer.allocateDirect(totalCells * 4).order(ByteOrder.nativeOrder())
    private val gridLock = Any()
    var hasNewTextureData: Boolean = false
        private set

    // Monotonically increasing version counter for dynamic replanning detection
    var gridVersion: Long = 0L
        private set

    fun <T> withLock(action: () -> T): T = synchronized(gridLock) { action() }

    // Diagnostic metrics
    private var updateFrameCount = 0
    private var lastRateTimestampNs = 0L
    private var currentUpdateHz = 0f
    private var lastProcessingDurationMs = 0f

    init {
        reset()
        Log.i(TAG, "OccupancyGrid initialized: %dx%d cells (%d total), resolution=%.2fm, bounds=%.1fx%.1fm, agentRadius=%.2fm".format(
            numCellsX, numCellsZ, totalCells, cellSizeMeters, widthMeters, depthMeters, obstacleInflater.agentRadiusMeters
        ))
    }

    /**
     * Cycles through the visualization display modes: RAW -> FILTERED -> INFLATED -> COST -> RAW.
     */
    fun cycleDisplayMode(): GridDisplayMode {
        synchronized(gridLock) {
            displayMode = displayMode.next()
            updateTextureBuffer()
            return displayMode
        }
    }

    /**
     * Sets the visualization display mode.
     */
    fun setDisplayMode(mode: GridDisplayMode) {
        synchronized(gridLock) {
            displayMode = mode
            updateTextureBuffer()
        }
    }

    /**
     * Toggles the unknown space traversal cost policy (BLOCKED vs EXPENSIVE_PENALTY).
     */
    fun toggleUnknownPolicy(): UnknownCostPolicy {
        synchronized(gridLock) {
            traversalCostGrid.unknownPolicy = traversalCostGrid.unknownPolicy.toggle()
            traversalCostGrid.updateCosts(inflatedCellStates)
            gridVersion++
            if (displayMode == GridDisplayMode.COST) {
                updateTextureBuffer()
            }
            return traversalCostGrid.unknownPolicy
        }
    }

    /**
     * Adjusts the navigation agent inflation radius.
     */
    fun setAgentRadius(radiusMeters: Float) {
        synchronized(gridLock) {
            obstacleInflater.setAgentRadius(radiusMeters)
            obstacleInflater.inflate(numCellsX, numCellsZ, filteredCellStates, inflatedCellStates)
            traversalCostGrid.updateCosts(inflatedCellStates)
            gridVersion++
            updateTextureBuffer()
        }
    }

    /**
     * Resets all cells to UNKNOWN and clears evidence, filtered states, inflated states, and costs.
     */
    fun reset() {
        synchronized(gridLock) {
            rawCellStates.fill(GridCellState.UNKNOWN.code)
            filteredCellStates.fill(GridCellState.UNKNOWN.code)
            inflatedCellStates.fill(NavigationCellState.UNKNOWN.code)
            freeEvidence.fill(0f)
            occupiedEvidence.fill(0f)
            traversalCostGrid.reset()
            gridVersion++
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

    fun getRawCellState(col: Int, row: Int): GridCellState {
        synchronized(gridLock) {
            val idx = cellToIndex(col, row)
            return GridCellState.fromCode(rawCellStates[idx])
        }
    }

    fun getFilteredCellState(col: Int, row: Int): GridCellState {
        synchronized(gridLock) {
            val idx = cellToIndex(col, row)
            return GridCellState.fromCode(filteredCellStates[idx])
        }
    }

    fun getNavigationCellState(col: Int, row: Int): NavigationCellState {
        synchronized(gridLock) {
            val idx = cellToIndex(col, row)
            return NavigationCellState.fromCode(inflatedCellStates[idx])
        }
    }

    /**
     * Integrates 16-bit depth measurements and detected floor polygon into the occupancy grid,
     * then executes spatial filtering, obstacle inflation, and traversal cost synthesis.
     * Throttled to 5–10 Hz on the perception worker thread.
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
        val startTimeNs = System.nanoTime()

        if (!floorTracking || intrinsics.fx <= 0f) {
            return generateDiagnostics(floorTracking, 0, 0, 0, 0, totalCells, 0, 0f, startTimeNs)
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

            // 4. Update Tri-State Raw Cell Classifications
            var rawFreeCount = 0
            var rawOccCount = 0
            var rawUnkCount = 0

            for (i in 0 until totalCells) {
                val occ = occupiedEvidence[i]
                val free = freeEvidence[i]

                val state = when {
                    occ >= OCCUPIED_THRESHOLD -> GridCellState.OCCUPIED
                    free >= FREE_THRESHOLD -> GridCellState.FREE
                    else -> GridCellState.UNKNOWN
                }

                rawCellStates[i] = state.code
                when (state) {
                    GridCellState.FREE -> rawFreeCount++
                    GridCellState.OCCUPIED -> rawOccCount++
                    GridCellState.UNKNOWN -> rawUnkCount++
                }
            }

            // 5. Stage 2: Spatial Filtering (conservative noise removal)
            val removedNoiseCount = spatialFilter.filter(
                numCellsX = numCellsX,
                numCellsZ = numCellsZ,
                rawStates = rawCellStates,
                occupiedEvidence = occupiedEvidence,
                freeEvidence = freeEvidence,
                outFilteredStates = filteredCellStates
            )

            var filteredOccCount = 0
            for (i in 0 until totalCells) {
                if (filteredCellStates[i] == GridCellState.OCCUPIED.code) {
                    filteredOccCount++
                }
            }

            // 6. Stage 3: Obstacle Inflation (agent radius safety margin)
            val inflatedBlockedCount = obstacleInflater.inflate(
                numCellsX = numCellsX,
                numCellsZ = numCellsZ,
                filteredStates = filteredCellStates,
                outInflatedStates = inflatedCellStates
            )

            // 7. Stage 4: Traversal Cost Matrix Generation
            traversalCostGrid.updateCosts(inflatedCellStates)
            gridVersion++

            // 8. Update GPU Texture Buffer with current display mode
            updateTextureBuffer()

            // 9. Measure processing timing
            val endTimeNs = System.nanoTime()
            lastProcessingDurationMs = (endTimeNs - startTimeNs) / 1_000_000.0f

            // 10. Calculate update rate
            updateFrameCount++
            val elapsedRate = endTimeNs - lastRateTimestampNs
            if (elapsedRate >= 1_000_000_000L) {
                currentUpdateHz = (updateFrameCount * 1_000_000_000f) / elapsedRate
                updateFrameCount = 0
                lastRateTimestampNs = endTimeNs
            }

            return generateDiagnostics(
                floorTracking = floorTracking,
                free = rawFreeCount,
                rawOcc = rawOccCount,
                filtOcc = filteredOccCount,
                inflatedBlk = inflatedBlockedCount,
                unk = rawUnkCount,
                removedNoise = removedNoiseCount,
                processingTimeMs = lastProcessingDurationMs,
                timestamp = endTimeNs
            )
        }
    }

    /**
     * Synthesizes RGBA texture pixels for OpenGL ES 3.0 visualization
     * according to the active GridDisplayMode.
     */
    private fun updateTextureBuffer() {
        textureByteBuffer.clear()

        when (displayMode) {
            GridDisplayMode.RAW -> {
                for (i in 0 until totalCells) {
                    when (rawCellStates[i]) {
                        GridCellState.FREE.code -> {
                            // Emerald Green (Walkable)
                            textureByteBuffer.put(0.toByte()).put(230.toByte()).put(118.toByte()).put(200.toByte())
                        }
                        GridCellState.OCCUPIED.code -> {
                            // Vivid Crimson Red (Raw Obstacle)
                            textureByteBuffer.put(255.toByte()).put(23.toByte()).put(68.toByte()).put(230.toByte())
                        }
                        else -> {
                            // Dark Charcoal Gray (Unknown)
                            textureByteBuffer.put(24.toByte()).put(32.toByte()).put(40.toByte()).put(80.toByte())
                        }
                    }
                }
            }

            GridDisplayMode.FILTERED -> {
                for (i in 0 until totalCells) {
                    when (filteredCellStates[i]) {
                        GridCellState.FREE.code -> {
                            // Emerald Green (Walkable)
                            textureByteBuffer.put(0.toByte()).put(230.toByte()).put(118.toByte()).put(200.toByte())
                        }
                        GridCellState.OCCUPIED.code -> {
                            // Vivid Crimson Red (Spatially Credible Obstacle)
                            textureByteBuffer.put(255.toByte()).put(23.toByte()).put(68.toByte()).put(230.toByte())
                        }
                        else -> {
                            // Dark Charcoal Gray (Unknown)
                            textureByteBuffer.put(24.toByte()).put(32.toByte()).put(40.toByte()).put(80.toByte())
                        }
                    }
                }
            }

            GridDisplayMode.INFLATED -> {
                for (i in 0 until totalCells) {
                    when (inflatedCellStates[i]) {
                        NavigationCellState.PHYSICAL_OBSTACLE.code -> {
                            // Vivid Crimson Red (Physical Obstacle Center)
                            textureByteBuffer.put(255.toByte()).put(23.toByte()).put(68.toByte()).put(240.toByte())
                        }
                        NavigationCellState.INFLATED_BLOCKED.code -> {
                            // Amber / Orange (Agent Clearance Margin)
                            textureByteBuffer.put(255.toByte()).put(152.toByte()).put(0.toByte()).put(215.toByte())
                        }
                        NavigationCellState.FREE.code -> {
                            // Emerald Green (Safe Traversable Space)
                            textureByteBuffer.put(0.toByte()).put(230.toByte()).put(118.toByte()).put(200.toByte())
                        }
                        else -> {
                            // Dark Charcoal Gray (Unknown Space)
                            textureByteBuffer.put(24.toByte()).put(32.toByte()).put(40.toByte()).put(80.toByte())
                        }
                    }
                }
            }

            GridDisplayMode.COST -> {
                val costs = traversalCostGrid.costMatrix
                for (i in 0 until totalCells) {
                    val cost = costs[i]
                    when {
                        cost.isInfinite() -> {
                            // Deep Maroon / Red (Impassable: cost = inf)
                            textureByteBuffer.put(183.toByte()).put(28.toByte()).put(28.toByte()).put(220.toByte())
                        }
                        cost > 1.5f -> {
                            // Violet / Purple (High Exploratory Penalty: cost = 15.0)
                            textureByteBuffer.put(124.toByte()).put(77.toByte()).put(255.toByte()).put(180.toByte())
                        }
                        else -> {
                            // Vibrant Cyan (Optimal Free: cost = 1.0)
                            textureByteBuffer.put(0.toByte()).put(229.toByte()).put(255.toByte()).put(200.toByte())
                        }
                    }
                }
            }
        }

        textureByteBuffer.flip()
        hasNewTextureData = true
    }

    private fun generateDiagnostics(
        floorTracking: Boolean,
        free: Int,
        rawOcc: Int,
        filtOcc: Int,
        inflatedBlk: Int,
        unk: Int,
        removedNoise: Int,
        processingTimeMs: Float,
        timestamp: Long
    ): GridDiagnostics {
        val observed = free + rawOcc
        val coverage = if (totalCells > 0) (observed * 100f / totalCells) else 0f
        return GridDiagnostics(
            isFloorTracking = floorTracking,
            floorStatus = if (floorTracking) "TRACKING" else "WAITING",
            widthCells = numCellsX,
            depthCells = numCellsZ,
            cellSizeMeters = cellSizeMeters,
            totalCells = totalCells,
            freeCount = free,
            occupiedCount = filtOcc,
            unknownCount = unk,
            rawOccupiedCount = rawOcc,
            filteredOccupiedCount = filtOcc,
            inflatedBlockedCount = inflatedBlk,
            removedNoiseCount = removedNoise,
            displayMode = displayMode,
            agentRadiusMeters = obstacleInflater.agentRadiusMeters,
            inflationRadiusCells = obstacleInflater.inflationRadiusCells,
            unknownCostPolicy = traversalCostGrid.unknownPolicy,
            processingTimeMs = processingTimeMs,
            coveragePercent = coverage,
            updateHz = currentUpdateHz,
            lastUpdateTimestampNs = timestamp,
            minObstacleHeight = minObstacleHeight,
            maxNavigationHeight = maxNavigationHeight,
            gridVersion = gridVersion
        )
    }

    fun markTextureConsumed() {
        hasNewTextureData = false
    }
}
