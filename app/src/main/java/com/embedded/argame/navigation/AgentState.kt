package com.embedded.argame.navigation

/**
 * State machine governing the autonomous virtual agent's navigation lifecycle.
 */
enum class AgentState {
    /** Agent is spawned and resting at start/current position, awaiting navigation command. */
    IDLE,

    /** Agent has requested an initial A* path and is waiting for planner output. */
    PLANNING,

    /** Agent is actively executing deterministic floor-plane kinematics along waypoints. */
    NAVIGATING,

    /** Agent detected path obstruction or grid change and is computing a new path while safe. */
    REPLANNING,

    /** Agent cannot make forward progress because path is impassable or replanning failed. */
    BLOCKED,

    /** Agent reached the goal within arrival threshold. Velocity is zeroed and pose preserved. */
    ARRIVED,

    /** No valid path exists between agent's current position and goal. */
    NO_PATH,

    /** Simulation is paused by user. Kinematics updates are frozen. */
    PAUSED;

    val isMoving: Boolean
        get() = this == NAVIGATING || this == REPLANNING

    val isTerminalOrStopped: Boolean
        get() = this == IDLE || this == BLOCKED || this == ARRIVED || this == NO_PATH || this == PAUSED
}
