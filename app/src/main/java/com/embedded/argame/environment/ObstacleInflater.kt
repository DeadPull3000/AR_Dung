package com.embedded.argame.environment

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Inflates physical obstacle boundaries by a configurable agent radius.
 *
 * Converts physical obstacles into a navigation-safe forbidden envelope
 * for an agent of non-zero physical dimension.
 *
 * Crucial Invariant:
 * Distinguishes physical obstacles (PHYSICAL_OBSTACLE) from the inflated
 * clearance envelope (INFLATED_BLOCKED) without overwriting physical occupancy semantics.
 */
class ObstacleInflater(
    val cellSizeMeters: Float = 0.10f,
    initialAgentRadiusMeters: Float = 0.20f
) {

    var agentRadiusMeters: Float = initialAgentRadiusMeters
        private set

    var inflationRadiusCells: Int = 0
        private set

    // Precomputed Euclidean circle offsets (pairs of dc, dr)
    private var kernelDeltas = IntArray(0)
    private var kernelPairsCount = 0

    init {
        setAgentRadius(initialAgentRadiusMeters)
    }

    /**
     * Updates the agent radius and recomputes the Euclidean inflation kernel.
     */
    fun setAgentRadius(radiusMeters: Float) {
        agentRadiusMeters = radiusMeters.coerceIn(0.0f, 1.0f)
        inflationRadiusCells = ceil(agentRadiusMeters / cellSizeMeters).toInt()

        val tempDeltas = ArrayList<Int>()
        val maxCells = inflationRadiusCells
        val maxDistSq = (agentRadiusMeters * agentRadiusMeters) + 1e-4f

        for (dr in -maxCells..maxCells) {
            for (dc in -maxCells..maxCells) {
                if (dc == 0 && dr == 0) continue // Center cell is the obstacle itself
                val distSq = (dc * cellSizeMeters) * (dc * cellSizeMeters) + (dr * cellSizeMeters) * (dr * cellSizeMeters)
                if (distSq <= maxDistSq) {
                    tempDeltas.add(dc)
                    tempDeltas.add(dr)
                }
            }
        }

        kernelPairsCount = tempDeltas.size / 2
        kernelDeltas = IntArray(tempDeltas.size) { tempDeltas[it] }
    }

    /**
     * Inflates filtered physical obstacles into outInflatedStates.
     *
     * @return Number of cells marked as INFLATED_BLOCKED.
     */
    fun inflate(
        numCellsX: Int,
        numCellsZ: Int,
        filteredStates: ByteArray,
        outInflatedStates: ByteArray
    ): Int {
        val totalCells = numCellsX * numCellsZ

        // 1. Initial pass: map filtered states to navigation states
        for (i in 0 until totalCells) {
            outInflatedStates[i] = when (filteredStates[i]) {
                GridCellState.OCCUPIED.code -> NavigationCellState.PHYSICAL_OBSTACLE.code
                GridCellState.FREE.code -> NavigationCellState.FREE.code
                else -> NavigationCellState.UNKNOWN.code
            }
        }

        if (kernelPairsCount == 0) return 0

        var inflatedBlockedCount = 0

        // 2. Second pass: stamp the circular agent radius around each physical obstacle
        for (r in 0 until numCellsZ) {
            val rowOffset = r * numCellsX
            for (c in 0 until numCellsX) {
                val idx = rowOffset + c

                if (filteredStates[idx] == GridCellState.OCCUPIED.code) {
                    for (k in 0 until kernelPairsCount) {
                        val nc = c + kernelDeltas[k * 2]
                        val nr = r + kernelDeltas[k * 2 + 1]

                        if (nc in 0 until numCellsX && nr in 0 until numCellsZ) {
                            val nIdx = nr * numCellsX + nc
                            if (outInflatedStates[nIdx] != NavigationCellState.PHYSICAL_OBSTACLE.code) {
                                if (outInflatedStates[nIdx] != NavigationCellState.INFLATED_BLOCKED.code) {
                                    inflatedBlockedCount++
                                }
                                outInflatedStates[nIdx] = NavigationCellState.INFLATED_BLOCKED.code
                            }
                        }
                    }
                }
            }
        }

        return inflatedBlockedCount
    }
}
