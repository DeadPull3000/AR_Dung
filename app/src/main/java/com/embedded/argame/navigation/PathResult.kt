package com.embedded.argame.navigation

/**
 * Status code representing the outcome of an A* path search.
 */
enum class PathStatus {
    SUCCESS,
    START_BLOCKED,
    GOAL_BLOCKED,
    START_UNKNOWN,
    GOAL_UNKNOWN,
    START_EQUALS_GOAL,
    NO_PATH,
    OUT_OF_BOUNDS,
    NO_FLOOR_REFERENCE;

    val isSuccessful: Boolean
        get() = this == SUCCESS || this == START_EQUALS_GOAL
}

/**
 * Policy governing diagonal corner-cutting around obstacle cells.
 *
 * STRICT_NO_CORNER_CUTTING:
 * Requires BOTH orthogonal neighbors to be traversable. Prevents cutting diagonally around
 * an obstacle corner and prevents squeezing between two diagonally touching obstacles.
 *
 * PREVENT_SQUEEZE_ONLY:
 * Rejects diagonal movement ONLY when BOTH orthogonal neighbors are impassable (squeezing
 * between two touching obstacle corners). Allows cutting around a single corner.
 */
enum class CornerCuttingPolicy {
    STRICT_NO_CORNER_CUTTING,
    PREVENT_SQUEEZE_ONLY
}

/**
 * 2D integer cell coordinates on the 2.5D occupancy grid.
 */
data class GridWaypoint(val col: Int, val row: Int)

/**
 * Comprehensive result model produced by the A* pathfinding engine.
 * Encapsulates search status, raw grid path, smoothed grid path, world-space polyline,
 * cost metrics, and search telemetry.
 */
data class PathResult(
    val status: PathStatus,
    val startCol: Int = -1,
    val startRow: Int = -1,
    val goalCol: Int = -1,
    val goalRow: Int = -1,
    val rawPath: List<GridWaypoint> = emptyList(),
    val smoothedPath: List<GridWaypoint> = emptyList(),
    val worldCoordinates: FloatArray = FloatArray(0),
    val totalCost: Float = 0f,
    val totalDistanceMeters: Float = 0f,
    val searchTimeMs: Float = 0f,
    val nodesExpanded: Int = 0,
    val gridVersion: Long = 0L
) {
    val rawCellCount: Int get() = rawPath.size
    val smoothedCellCount: Int get() = smoothedPath.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PathResult
        if (status != other.status) return false
        if (startCol != other.startCol) return false
        if (startRow != other.startRow) return false
        if (goalCol != other.goalCol) return false
        if (goalRow != other.goalRow) return false
        if (rawPath != other.rawPath) return false
        if (smoothedPath != other.smoothedPath) return false
        if (!worldCoordinates.contentEquals(other.worldCoordinates)) return false
        if (totalCost != other.totalCost) return false
        if (totalDistanceMeters != other.totalDistanceMeters) return false
        if (searchTimeMs != other.searchTimeMs) return false
        if (nodesExpanded != other.nodesExpanded) return false
        if (gridVersion != other.gridVersion) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + startCol
        result = 31 * result + startRow
        result = 31 * result + goalCol
        result = 31 * result + goalRow
        result = 31 * result + rawPath.hashCode()
        result = 31 * result + smoothedPath.hashCode()
        result = 31 * result + worldCoordinates.contentHashCode()
        result = 31 * result + totalCost.hashCode()
        result = 31 * result + totalDistanceMeters.hashCode()
        result = 31 * result + searchTimeMs.hashCode()
        result = 31 * result + nodesExpanded
        result = 31 * result + gridVersion.hashCode()
        return result
    }

    companion object {
        fun failure(status: PathStatus, startCol: Int = -1, startRow: Int = -1, goalCol: Int = -1, goalRow: Int = -1, gridVersion: Long = 0L): PathResult {
            return PathResult(
                status = status,
                startCol = startCol,
                startRow = startRow,
                goalCol = goalCol,
                goalRow = goalRow,
                gridVersion = gridVersion
            )
        }
    }
}
