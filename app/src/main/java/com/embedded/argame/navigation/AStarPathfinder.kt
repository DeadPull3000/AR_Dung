package com.embedded.argame.navigation

import com.embedded.argame.environment.FloorReference
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Custom high-performance A* pathfinding engine operating directly on TraversalCostGrid.
 *
 * Architectural Features:
 * 1. Zero Heap Allocation: All search arrays (gCost, parent, visited, closed) and the priority
 *    queue (IndexedMinHeap) are pre-allocated and reused across searches.
 * 2. Generation Counter: O(1) state reset without clearing 6,400 array entries between searches.
 * 3. 8-Connected Grid Movement: Straight moves (1.0 x cell_size) and diagonal moves (sqrt(2) x cell_size).
 * 4. Corner-Cutting Prevention: Strictly prevents diagonal movement between two touching obstacles
 *    or clipping an obstacle corner.
 * 5. Admissible & Consistent Octile Heuristic: Never overestimates physical grid distance.
 * 6. Supercover Line-of-Sight Path Smoothing: Simplifies staircase grid paths into clean direct segments.
 * 7. World-Space Conversion: Transforms 2D grid waypoints directly into ARCore 3D world coordinates.
 */
class AStarPathfinder(
    val numCellsX: Int = 80,
    val numCellsZ: Int = 80,
    val cellSizeMeters: Float = 0.10f,
    var cornerCuttingPolicy: CornerCuttingPolicy = CornerCuttingPolicy.STRICT_NO_CORNER_CUTTING
) {
    val totalCells: Int = numCellsX * numCellsZ

    companion object {
        private const val SQRT2 = 1.41421356f
        private const val SQRT2_MINUS_ONE = 0.41421356f
        private const val WAYPOINT_Y_OFFSET_METERS = 0.015f // 1.5 cm above floor to prevent z-fighting
    }

    // Preallocated Priority Queue (Zero GC Churn)
    private val openSet = IndexedMinHeap(totalCells)

    // Preallocated flat search buffers
    private val gCost = FloatArray(totalCells)
    private val parent = IntArray(totalCells)
    private val visitedIteration = IntArray(totalCells)
    private val closedIteration = IntArray(totalCells)
    private var searchIteration = 1

    // Reusable buffer for path reconstruction
    private val reconstructionBuffer = IntArray(totalCells)

    // Preallocated matrix for world coordinate transformation
    private val floorToWorldMatrix = FloatArray(16)

    // 8-Direction offsets and distance multipliers
    // Cardinal: (dx, dz, isDiag, orth1_dx, orth1_dz, orth2_dx, orth2_dz)
    private val dirDx = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val dirDz = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    private val dirIsDiag = booleanArrayOf(false, false, false, false, true, true, true, true)
    private val dirOrth1Dx = intArrayOf(0, 0, 0, 0, 1, 1, -1, -1)
    private val dirOrth1Dz = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
    private val dirOrth2Dx = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
    private val dirOrth2Dz = intArrayOf(0, 0, 0, 0, 1, -1, 1, -1)

    fun cellToIndex(col: Int, row: Int): Int = row * numCellsX + col

    fun indexToCol(index: Int): Int = index % numCellsX

    fun indexToRow(index: Int): Int = index / numCellsX

    /**
     * Finds the optimal path between grid coordinates using the provided TraversalCostGrid.
     * Can optionally convert to world space if occupancyGrid and floorReference are provided.
     */
    fun findPath(
        startCol: Int,
        startRow: Int,
        goalCol: Int,
        goalRow: Int,
        costGrid: TraversalCostGrid,
        inflatedStates: ByteArray? = null,
        occupancyGrid: OccupancyGrid? = null,
        floorReference: FloorReference? = null,
        gridVersion: Long = 0L
    ): PathResult {
        val startTimeNs = System.nanoTime()

        // 1. Validate Input Bounds
        if (startCol !in 0 until numCellsX || startRow !in 0 until numCellsZ ||
            goalCol !in 0 until numCellsX || goalRow !in 0 until numCellsZ
        ) {
            return PathResult.failure(PathStatus.OUT_OF_BOUNDS, startCol, startRow, goalCol, goalRow, gridVersion)
        }

        val startIndex = cellToIndex(startCol, startRow)
        val goalIndex = cellToIndex(goalCol, goalRow)

        // 2. Validate Start and Goal Traversability
        if (!costGrid.isTraversable(startIndex)) {
            val status = if (inflatedStates != null && inflatedStates[startIndex] == NavigationCellState.UNKNOWN.code) {
                PathStatus.START_UNKNOWN
            } else {
                PathStatus.START_BLOCKED
            }
            return PathResult.failure(status, startCol, startRow, goalCol, goalRow, gridVersion)
        }

        if (!costGrid.isTraversable(goalIndex)) {
            val status = if (inflatedStates != null && inflatedStates[goalIndex] == NavigationCellState.UNKNOWN.code) {
                PathStatus.GOAL_UNKNOWN
            } else {
                PathStatus.GOAL_BLOCKED
            }
            return PathResult.failure(status, startCol, startRow, goalCol, goalRow, gridVersion)
        }

        // 3. Trivial Case: Start equals Goal
        if (startIndex == goalIndex) {
            val singleWaypoint = listOf(GridWaypoint(startCol, startRow))
            val worldCoords = buildWorldCoordinates(singleWaypoint, occupancyGrid, floorReference)
            val durationMs = (System.nanoTime() - startTimeNs) / 1_000_000.0f
            return PathResult(
                status = PathStatus.START_EQUALS_GOAL,
                startCol = startCol,
                startRow = startRow,
                goalCol = goalCol,
                goalRow = goalRow,
                rawPath = singleWaypoint,
                smoothedPath = singleWaypoint,
                worldCoordinates = worldCoords,
                totalCost = 0f,
                totalDistanceMeters = 0f,
                searchTimeMs = durationMs,
                nodesExpanded = 0,
                gridVersion = gridVersion
            )
        }

        // 4. Initialize Search Generation Counter for O(1) state reset
        searchIteration++
        if (searchIteration == Int.MAX_VALUE) {
            visitedIteration.fill(0)
            closedIteration.fill(0)
            searchIteration = 1
        }
        val currentIteration = searchIteration

        openSet.clear()

        // 5. Seed Start Node
        gCost[startIndex] = 0f
        parent[startIndex] = -1
        visitedIteration[startIndex] = currentIteration
        val startH = computeHeuristic(startCol, startRow, goalCol, goalRow)
        openSet.push(startIndex, startH)

        var nodesExpanded = 0
        var foundGoal = false

        // 6. Main A* Search Loop
        while (openSet.isNotEmpty()) {
            val currentIdx = openSet.pollMin()
            closedIteration[currentIdx] = currentIteration
            nodesExpanded++

            if (currentIdx == goalIndex) {
                foundGoal = true
                break
            }

            val currentCol = indexToCol(currentIdx)
            val currentRow = indexToRow(currentIdx)
            val currentG = gCost[currentIdx]

            // Expand 8 neighbors
            for (dir in 0 until 8) {
                val nCol = currentCol + dirDx[dir]
                val nRow = currentRow + dirDz[dir]

                // Bounds check
                if (nCol !in 0 until numCellsX || nRow !in 0 until numCellsZ) continue

                val nIdx = cellToIndex(nCol, nRow)

                // Skip if already in CLOSED set
                if (closedIteration[nIdx] == currentIteration) continue

                // Skip impassable cells
                val neighborTraversalCost = costGrid.getCost(nIdx)
                if (neighborTraversalCost.isInfinite()) continue

                // Check Diagonal Corner-Cutting Prevention
                if (dirIsDiag[dir]) {
                    val orth1Col = currentCol + dirOrth1Dx[dir]
                    val orth1Row = currentRow + dirOrth1Dz[dir]
                    val orth2Col = currentCol + dirOrth2Dx[dir]
                    val orth2Row = currentRow + dirOrth2Dz[dir]

                    val orth1Idx = cellToIndex(orth1Col, orth1Row)
                    val orth2Idx = cellToIndex(orth2Col, orth2Row)

                    val orth1Traversable = costGrid.isTraversable(orth1Idx)
                    val orth2Traversable = costGrid.isTraversable(orth2Idx)

                    when (cornerCuttingPolicy) {
                        CornerCuttingPolicy.STRICT_NO_CORNER_CUTTING -> {
                            // Both orthogonal neighbors must be traversable
                            if (!orth1Traversable || !orth2Traversable) continue
                        }
                        CornerCuttingPolicy.PREVENT_SQUEEZE_ONLY -> {
                            // Reject only if both are impassable (squeezing between two touching obstacles)
                            if (!orth1Traversable && !orth2Traversable) continue
                        }
                    }
                }

                // Compute edge transition cost
                val stepDistance = if (dirIsDiag[dir]) cellSizeMeters * SQRT2 else cellSizeMeters
                val tentativeG = currentG + stepDistance * neighborTraversalCost

                val isVisited = visitedIteration[nIdx] == currentIteration

                if (!isVisited || tentativeG < gCost[nIdx]) {
                    gCost[nIdx] = tentativeG
                    parent[nIdx] = currentIdx
                    visitedIteration[nIdx] = currentIteration

                    val h = computeHeuristic(nCol, nRow, goalCol, goalRow)
                    val f = tentativeG + h

                    if (openSet.contains(nIdx)) {
                        openSet.decreaseKey(nIdx, f)
                    } else {
                        openSet.push(nIdx, f)
                    }
                }
            }
        }

        val durationMs = (System.nanoTime() - startTimeNs) / 1_000_000.0f

        // 7. No Path Found
        if (!foundGoal) {
            return PathResult(
                status = PathStatus.NO_PATH,
                startCol = startCol,
                startRow = startRow,
                goalCol = goalCol,
                goalRow = goalRow,
                searchTimeMs = durationMs,
                nodesExpanded = nodesExpanded,
                gridVersion = gridVersion
            )
        }

        // 8. Reconstruct Raw Path from Goal to Start
        var curr = goalIndex
        var pathLen = 0
        while (curr != -1 && pathLen < totalCells) {
            reconstructionBuffer[pathLen++] = curr
            curr = parent[curr]
        }

        // Reverse reconstructionBuffer[0 until pathLen] to obtain Start -> Goal
        val rawPath = ArrayList<GridWaypoint>(pathLen)
        for (i in (pathLen - 1) downTo 0) {
            val idx = reconstructionBuffer[i]
            rawPath.add(GridWaypoint(indexToCol(idx), indexToRow(idx)))
        }

        // 9. Apply Supercover Line-of-Sight Path Smoothing
        val smoothedPath = smoothPath(rawPath, costGrid)

        // 10. Compute Physical Path Distance & Cost
        val totalPathCost = gCost[goalIndex]
        var totalDistanceMeters = 0f
        for (i in 0 until smoothedPath.size - 1) {
            val p0 = smoothedPath[i]
            val p1 = smoothedPath[i + 1]
            val dCol = (p1.col - p0.col).toFloat()
            val dRow = (p1.row - p0.row).toFloat()
            totalDistanceMeters += sqrt(dCol * dCol + dRow * dRow) * cellSizeMeters
        }

        // 11. Build 3D World-Space Coordinates
        val worldCoords = buildWorldCoordinates(smoothedPath, occupancyGrid, floorReference)

        return PathResult(
            status = PathStatus.SUCCESS,
            startCol = startCol,
            startRow = startRow,
            goalCol = goalCol,
            goalRow = goalRow,
            rawPath = rawPath,
            smoothedPath = smoothedPath,
            worldCoordinates = worldCoords,
            totalCost = totalPathCost,
            totalDistanceMeters = totalDistanceMeters,
            searchTimeMs = durationMs,
            nodesExpanded = nodesExpanded,
            gridVersion = gridVersion
        )
    }

    /**
     * Admissible & consistent Octile Distance heuristic.
     * Computes physical minimum travel distance on an 8-connected grid scaled by base traversal cost.
     *
     * h = cellSize * (max(dx, dz) + (sqrt(2) - 1) * min(dx, dz)) * freeBaseCost
     */
    fun computeHeuristic(col: Int, row: Int, goalCol: Int, goalRow: Int): Float {
        val dx = abs(goalCol - col)
        val dz = abs(goalRow - row)
        val maxD = max(dx, dz).toFloat()
        val minD = min(dx, dz).toFloat()
        return cellSizeMeters * (maxD + SQRT2_MINUS_ONE * minD)
    }

    /**
     * Line-of-sight path smoothing (string-pulling).
     * Eliminates staircase grid steps by checking unobstructed line-of-sight between non-adjacent waypoints.
     */
    fun smoothPath(rawPath: List<GridWaypoint>, costGrid: TraversalCostGrid): List<GridWaypoint> {
        if (rawPath.size <= 2) return rawPath

        val smoothed = ArrayList<GridWaypoint>()
        smoothed.add(rawPath[0])

        var currentIndex = 0
        while (currentIndex < rawPath.size - 1) {
            var furthestVisible = currentIndex + 1
            for (candidate in (rawPath.size - 1) downTo (currentIndex + 2)) {
                if (hasLineOfSight(rawPath[currentIndex], rawPath[candidate], costGrid)) {
                    furthestVisible = candidate
                    break
                }
            }
            smoothed.add(rawPath[furthestVisible])
            currentIndex = furthestVisible
        }

        return smoothed
    }

    /**
     * Line-of-sight raycasting between two grid cells using supercover grid traversal.
     * Guarantees that no blocked or inflated obstacle cell is traversed by the continuous segment.
     */
    fun hasLineOfSight(p0: GridWaypoint, p1: GridWaypoint, costGrid: TraversalCostGrid): Boolean {
        var x = p0.col
        var z = p0.row
        val x1 = p1.col
        val z1 = p1.row

        val dx = abs(x1 - x)
        val dz = abs(z1 - z)
        val sx = if (x < x1) 1 else -1
        val sz = if (z < z1) 1 else -1

        var err = dx - dz
        val maxSteps = dx + dz

        for (step in 0..maxSteps) {
            val idx = cellToIndex(x, z)
            if (!costGrid.isTraversable(idx)) return false

            if (x == x1 && z == z1) break

            val e2 = 2 * err
            if (e2 > -dz && e2 < dx) {
                // Diagonal step: check orthogonal neighbors to prevent clipping obstacle corners
                val orth1Idx = cellToIndex(x + sx, z)
                val orth2Idx = cellToIndex(x, z + sz)
                if (!costGrid.isTraversable(orth1Idx) || !costGrid.isTraversable(orth2Idx)) {
                    return false
                }
                err -= dz
                x += sx
                err += dx
                z += sz
            } else if (e2 > -dz) {
                err -= dz
                x += sx
            } else {
                err += dx
                z += sz
            }
        }
        return true
    }

    /**
     * Converts a sequence of 2D grid waypoints into flat 3D world coordinates [x0, y0, z0, x1, y1, z1, ...]
     * using the detected physical floor plane transform.
     */
    private fun buildWorldCoordinates(
        waypoints: List<GridWaypoint>,
        occupancyGrid: OccupancyGrid?,
        floorReference: FloorReference?
    ): FloatArray {
        if (waypoints.isEmpty() || occupancyGrid == null || floorReference == null || !floorReference.isTracking) {
            return FloatArray(0)
        }

        floorReference.getFloorToWorldMatrix(floorToWorldMatrix, 0)
        val m = floorToWorldMatrix

        val coords = FloatArray(waypoints.size * 3)
        var offset = 0

        for (wp in waypoints) {
            val (xf, zf) = occupancyGrid.cellToFloorCoord(wp.col, wp.row)
            val yf = WAYPOINT_Y_OFFSET_METERS // slightly elevated above floor plane

            // Transform [xf, yf, zf, 1] by Column-Major Floor-to-World model matrix
            val xw = m[0] * xf + m[4] * yf + m[8] * zf + m[12]
            val yw = m[1] * xf + m[5] * yf + m[9] * zf + m[13]
            val zw = m[2] * xf + m[6] * yf + m[10] * zf + m[14]

            coords[offset] = xw
            coords[offset + 1] = yw
            coords[offset + 2] = zw
            offset += 3
        }

        return coords
    }
}
