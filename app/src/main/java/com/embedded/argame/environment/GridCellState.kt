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
