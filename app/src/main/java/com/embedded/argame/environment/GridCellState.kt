package com.embedded.argame.environment

/**
 * Tri-state occupancy classification for 2.5D grid cells.
 *
 * UNKNOWN: Insufficient sensor evidence or unobserved region.
 *          Missing depth is NEVER assumed to be free or occupied.
 * FREE: Verified traversable floor surface without obstacles.
 * OCCUPIED: Detected obstacle geometry within the navigation height clearance envelope.
 */
enum class GridCellState(val code: Byte) {
    UNKNOWN(0),
    FREE(1),
    OCCUPIED(2);

    companion object {
        fun fromCode(code: Byte): GridCellState = when (code) {
            1.toByte() -> FREE
            2.toByte() -> OCCUPIED
            else -> UNKNOWN
        }
    }
}

/**
 * Navigation occupancy classification incorporating obstacle inflation.
 * Distinguishes physical obstacles from the agent clearance envelope.
 */
enum class NavigationCellState(val code: Byte) {
    UNKNOWN(0),
    FREE(1),
    INFLATED_BLOCKED(2),
    PHYSICAL_OBSTACLE(3);

    companion object {
        fun fromCode(code: Byte): NavigationCellState = when (code) {
            1.toByte() -> FREE
            2.toByte() -> INFLATED_BLOCKED
            3.toByte() -> PHYSICAL_OBSTACLE
            else -> UNKNOWN
        }
    }
}

/**
 * Visualization modes for the 2.5D OpenGL ES floor quad overlay.
 */
enum class GridDisplayMode {
    RAW,
    FILTERED,
    INFLATED,
    COST;

    fun next(): GridDisplayMode = when (this) {
        RAW -> FILTERED
        FILTERED -> INFLATED
        INFLATED -> COST
        COST -> RAW
    }
}

/**
 * Configurable traversal policy for unobserved (UNKNOWN) grid space.
 */
enum class UnknownCostPolicy {
    BLOCKED,           // Conservative: UNKNOWN treated as impassable (infinite cost)
    EXPENSIVE_PENALTY; // Exploratory: UNKNOWN treated as traversable with high penalty

    fun toggle(): UnknownCostPolicy = when (this) {
        BLOCKED -> EXPENSIVE_PENALTY
        EXPENSIVE_PENALTY -> BLOCKED
    }
}
