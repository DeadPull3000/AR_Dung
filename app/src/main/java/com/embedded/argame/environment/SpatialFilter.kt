package com.embedded.argame.environment

/**
 * Conservative spatial noise filter for the 80x80 2.5D occupancy grid.
 *
 * Distinguishes genuine physical furniture and walls from transient sensor noise.
 * Preserves thin structures (e.g. chair/table legs) by checking connectivity and evidence,
 * while eliminating isolated single-cell artifacts.
 *
 * CRITICAL INVARIANT:
 * UNKNOWN is strictly preserved and NEVER converted to FREE during filtering.
 */
class SpatialFilter(
    val minOccupiedNeighbors: Int = 1,
    val strongEvidenceThreshold: Float = 4.0f
) {

    // Pre-allocated neighbor offsets for 8-connectivity to eliminate allocations
    private val neighborDeltas = intArrayOf(
        -1, -1,
         0, -1,
         1, -1,
        -1,  0,
         1,  0,
        -1,  1,
         0,  1,
         1,  1
    )

    /**
     * Filters raw occupancy states into filteredStates.
     * Operates purely on primitive byte/float arrays without heap allocations.
     *
     * @return Number of isolated noise cells removed.
     */
    fun filter(
        numCellsX: Int,
        numCellsZ: Int,
        rawStates: ByteArray,
        occupiedEvidence: FloatArray,
        freeEvidence: FloatArray,
        outFilteredStates: ByteArray
    ): Int {
        var removedNoiseCount = 0

        for (r in 0 until numCellsZ) {
            val rowOffset = r * numCellsX
            for (c in 0 until numCellsX) {
                val idx = rowOffset + c
                val state = rawStates[idx]

                when (state) {
                    GridCellState.UNKNOWN.code -> {
                        // Strict Invariant: UNKNOWN is never converted to FREE
                        outFilteredStates[idx] = GridCellState.UNKNOWN.code
                    }
                    GridCellState.FREE.code -> {
                        outFilteredStates[idx] = GridCellState.FREE.code
                    }
                    GridCellState.OCCUPIED.code -> {
                        // Count occupied neighbors in 8-connected neighborhood
                        var occupiedNeighbors = 0
                        var freeNeighbors = 0

                        for (n in 0 until 8) {
                            val nc = c + neighborDeltas[n * 2]
                            val nr = r + neighborDeltas[n * 2 + 1]

                            if (nc in 0 until numCellsX && nr in 0 until numCellsZ) {
                                val nIdx = nr * numCellsX + nc
                                if (rawStates[nIdx] == GridCellState.OCCUPIED.code) {
                                    occupiedNeighbors++
                                } else if (rawStates[nIdx] == GridCellState.FREE.code) {
                                    freeNeighbors++
                                }
                            }
                        }

                        if (occupiedNeighbors >= minOccupiedNeighbors) {
                            // Part of a multi-cell obstacle cluster: Keep as OCCUPIED
                            outFilteredStates[idx] = GridCellState.OCCUPIED.code
                        } else if (occupiedEvidence[idx] >= strongEvidenceThreshold) {
                            // Isolated single cell, but backed by persistent strong evidence (e.g. solid chair leg)
                            outFilteredStates[idx] = GridCellState.OCCUPIED.code
                        } else {
                            // Transient single-cell noise artifact: Remove it
                            removedNoiseCount++
                            // If surrounded mostly by known free floor, revert to FREE; otherwise revert to UNKNOWN
                            if (freeNeighbors >= 3) {
                                outFilteredStates[idx] = GridCellState.FREE.code
                            } else {
                                outFilteredStates[idx] = GridCellState.UNKNOWN.code
                            }
                        }
                    }
                }
            }
        }

        return removedNoiseCount
    }
}
