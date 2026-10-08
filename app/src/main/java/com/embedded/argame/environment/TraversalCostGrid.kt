package com.embedded.argame.environment

/**
 * Compact traversal-cost representation for downstream A* pathfinding (Milestone 9).
 *
 * Cost Model:
 * - FREE: Finite base traversal cost (1.0f).
 * - PHYSICAL_OBSTACLE / INFLATED_BLOCKED: Infinite cost (impassable).
 * - UNKNOWN:
 *     - If unknownPolicy == BLOCKED: Infinite cost (conservative safety policy).
 *     - If unknownPolicy == EXPENSIVE_PENALTY: 15.0f (exploratory policy).
 *
 * Allows toggling between conservative and exploratory path planning without rewriting algorithms.
 */
class TraversalCostGrid(
    val totalCells: Int = 6400,
    var unknownPolicy: UnknownCostPolicy = UnknownCostPolicy.BLOCKED,
    val unknownPenaltyCost: Float = 15.0f,
    val freeBaseCost: Float = 1.0f
) {

    companion object {
        const val COST_IMPASSABLE = Float.POSITIVE_INFINITY
    }

    // Flat compact float array for zero GC churn
    val costMatrix = FloatArray(totalCells)

    init {
        costMatrix.fill(COST_IMPASSABLE)
    }

    /**
     * Updates numerical traversal costs based on the inflated navigation states.
     */
    fun updateCosts(inflatedStates: ByteArray) {
        val unknownCost = if (unknownPolicy == UnknownCostPolicy.BLOCKED) {
            COST_IMPASSABLE
        } else {
            unknownPenaltyCost
        }

        for (i in 0 until totalCells) {
            costMatrix[i] = when (inflatedStates[i]) {
                NavigationCellState.PHYSICAL_OBSTACLE.code,
                NavigationCellState.INFLATED_BLOCKED.code -> COST_IMPASSABLE

                NavigationCellState.FREE.code -> freeBaseCost

                else -> unknownCost
            }
        }
    }

    fun isTraversable(index: Int): Boolean = index in 0 until totalCells && !costMatrix[index].isInfinite()

    fun getCost(index: Int): Float = if (index in 0 until totalCells) costMatrix[index] else COST_IMPASSABLE

    fun reset() {
        costMatrix.fill(COST_IMPASSABLE)
    }
}
