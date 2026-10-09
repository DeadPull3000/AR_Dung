package com.embedded.argame.navigation

/**
 * Immutable thread-safe snapshot of the virtual agent's physical pose, kinematics,
 * and navigation progress. Safely published from the simulation loop to the
 * OpenGL ES render thread and Android UI thread.
 */
data class AgentPose(
    /** World-space X coordinate in meters (ARCore room reference frame). */
    val worldX: Float = 0f,

    /** World-space Y coordinate in meters (ARCore room reference frame). */
    val worldY: Float = 0f,

    /** World-space Z coordinate in meters (ARCore room reference frame). */
    val worldZ: Float = 0f,

    /** Floor-space X coordinate in meters on horizontal plane [-4.0m, +4.0m]. */
    val floorX: Float = 0f,

    /** Floor-space Z coordinate in meters on horizontal plane [-4.0m, +4.0m]. */
    val floorZ: Float = 0f,

    /** Current column index on 80x80 occupancy grid [0..79]. */
    val cellCol: Int = -1,

    /** Current row index on 80x80 occupancy grid [0..79]. */
    val cellRow: Int = -1,

    /** Heading orientation angle in radians around floor plane +Y axis (0 = +X axis). */
    val headingRadians: Float = 0f,

    /** Current scalar movement velocity in meters per second. */
    val speedMps: Float = 0f,

    /** Active state machine status. */
    val state: AgentState = AgentState.IDLE,

    /** 0-based index of the waypoint currently being pursued. */
    val currentWaypointIndex: Int = 0,

    /** Total count of waypoints in active path. */
    val totalWaypoints: Int = 0,

    /** Euclidean distance in meters to the final goal on the floor plane. */
    val distanceToGoalMeters: Float = 0f,

    /** Number of dynamic A* replanning operations executed during navigation. */
    val replansCount: Int = 0,

    /** Grid version against which the active path was calculated. */
    val pathGridVersion: Long = 0L,

    /** Latest observed grid version from the spatial occupancy map. */
    val currentGridVersion: Long = 0L
) {
    val headingDegrees: Float
        get() = Math.toDegrees(headingRadians.toDouble()).toFloat()

    val isSpawned: Boolean
        get() = cellCol >= 0 && cellRow >= 0
}
