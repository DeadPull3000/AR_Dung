package com.embedded.argame.navigation

import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.environment.UnknownCostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deterministic unit-style verification tests for Autonomous AR Agent Navigation & Kinematics
 * (Milestone 9, Section 31).
 */
class AgentControllerTest {

    private val width = 80
    private val depth = 80
    private val cellSize = 0.10f
    private val totalCells = width * depth

    private lateinit var occupancyGrid: OccupancyGrid
    private lateinit var costGrid: TraversalCostGrid
    private lateinit var inflatedStates: ByteArray

    @Before
    fun setUp() {
        occupancyGrid = OccupancyGrid(cellSizeMeters = cellSize, widthMeters = 8.0f, depthMeters = 8.0f)
        costGrid = occupancyGrid.traversalCostGrid
        inflatedStates = ByteArray(totalCells) { NavigationCellState.FREE.code }
        costGrid.updateCosts(inflatedStates)
    }

    private fun createStraightPathResult(
        startCol: Int,
        startRow: Int,
        goalCol: Int,
        goalRow: Int,
        gridVersion: Long = 1L
    ): PathResult {
        val waypoints = mutableListOf<GridWaypoint>()
        val dx = if (goalCol > startCol) 1 else if (goalCol < startCol) -1 else 0
        val dz = if (goalRow > startRow) 1 else if (goalRow < startRow) -1 else 0

        var c = startCol
        var r = startRow
        waypoints.add(GridWaypoint(c, r))
        while (c != goalCol || r != goalRow) {
            c += dx
            r += dz
            waypoints.add(GridWaypoint(c, r))
        }

        return PathResult(
            status = PathStatus.SUCCESS,
            rawPath = waypoints,
            smoothedPath = waypoints,
            worldCoordinates = FloatArray(0),
            totalDistanceMeters = (waypoints.size - 1) * cellSize,
            totalCost = (waypoints.size - 1) * 1.0f,
            nodesExpanded = waypoints.size,
            searchTimeMs = 0.5f,
            gridVersion = gridVersion,
            startCol = startCol,
            startRow = startRow,
            goalCol = goalCol,
            goalRow = goalRow
        )
    }

    /**
     * Test 1 — Straight waypoint following: Agent moves toward a waypoint and reaches it.
     */
    @Test
    fun test1_straightWaypointFollowing() {
        val controller = AgentController(AgentConfig(moveSpeedMps = 0.50f, arrivalThresholdMeters = 0.10f))
        controller.spawnAt(40, 40, occupancyGrid, null)

        val path = createStraightPathResult(40, 40, 45, 40)
        controller.applyPathResult(path, occupancyGrid, null)

        assertEquals(AgentState.NAVIGATING, controller.state)
        val initialFloorX = controller.floorX

        // Run simulation for 2.0 seconds at 50 Hz (100 steps)
        for (i in 0 until 100) {
            controller.update(
                deltaTimeSec = 0.02f,
                currentGridVersion = 1L,
                costGrid = costGrid,
                occupancyGrid = occupancyGrid,
                floorReference = null
            )
            if (controller.state == AgentState.ARRIVED) break
        }

        assertTrue("Agent should make positive progress along X axis", controller.floorX > initialFloorX)
        assertEquals(AgentState.ARRIVED, controller.state)
        assertEquals(0f, controller.speedMps, 0.001f)
    }

    /**
     * Test 2 — Orientation: Agent rotates toward the direction of travel.
     */
    @Test
    fun test2_orientationRotatesTowardTarget() {
        val controller = AgentController(AgentConfig(moveSpeedMps = 0.50f, maxTurnRateRadPerSec = 3.14f))
        controller.spawnAt(40, 40, occupancyGrid, null)

        // Path moving purely in +Z direction (should face PI/2 = ~1.57 rad)
        val path = createStraightPathResult(40, 40, 40, 48)
        controller.applyPathResult(path, occupancyGrid, null)

        // Simulate 0.5s of turning/moving
        for (i in 0 until 25) {
            controller.update(0.02f, 1L, costGrid, occupancyGrid, null)
        }

        val expectedHeading = Math.PI.toFloat() / 2f
        val diff = abs(controller.headingRadians - expectedHeading)
        assertTrue("Heading should be near PI/2 (+Z direction), actual: ${controller.headingRadians}", diff < 0.25f)
    }

    /**
     * Test 3 — Turn-rate limit: Heading never changes faster than MAX_TURN_RATE.
     */
    @Test
    fun test3_turnRateLimitClamped() {
        val maxTurn = 1.0f // 1.0 rad/s
        val controller = AgentController(AgentConfig(maxTurnRateRadPerSec = maxTurn))
        controller.spawnAt(40, 40, occupancyGrid, null)

        // Goal directly behind or at 90 deg
        val path = createStraightPathResult(40, 40, 40, 48)
        controller.applyPathResult(path, occupancyGrid, null)

        val headingBefore = controller.headingRadians
        val dt = 0.10f
        controller.update(dt, 1L, costGrid, occupancyGrid, null)
        val headingAfter = controller.headingRadians

        val deltaHeading = abs(headingAfter - headingBefore)
        val allowedMax = maxTurn * dt + 0.001f
        assertTrue("Heading change ($deltaHeading rad) must not exceed MAX_TURN_RATE * dt ($allowedMax rad)", deltaHeading <= allowedMax)
    }

    /**
     * Test 4 — Arrival: Agent enters ARRIVED once within the goal threshold.
     */
    @Test
    fun test4_arrivalWithinThreshold() {
        val threshold = 0.15f
        val controller = AgentController(AgentConfig(arrivalThresholdMeters = threshold, moveSpeedMps = 0.50f))
        controller.spawnAt(40, 40, occupancyGrid, null)

        // Goal only 2 cells away (0.20m)
        val path = createStraightPathResult(40, 40, 42, 40)
        controller.applyPathResult(path, occupancyGrid, null)

        for (i in 0 until 50) {
            controller.update(0.02f, 1L, costGrid, occupancyGrid, null)
            if (controller.state == AgentState.ARRIVED) break
        }

        assertEquals(AgentState.ARRIVED, controller.state)
        assertEquals(0f, controller.speedMps, 0.001f)
        val (goalX, goalZ) = occupancyGrid.cellToFloorCoord(42, 40)
        val finalDist = sqrt((controller.floorX - goalX) * (controller.floorX - goalX) + (controller.floorZ - goalZ) * (controller.floorZ - goalZ))
        assertTrue("Final distance ($finalDist) must be within threshold ($threshold)", finalDist <= threshold)
    }

    /**
     * Test 5 — Waypoint advancement: Agent advances through a multi-waypoint path correctly.
     */
    @Test
    fun test5_waypointAdvancement() {
        val controller = AgentController(AgentConfig(moveSpeedMps = 0.80f, waypointThresholdMeters = 0.12f))
        controller.spawnAt(40, 40, occupancyGrid, null)

        val path = createStraightPathResult(40, 40, 46, 40) // 7 waypoints: 40..46
        controller.applyPathResult(path, occupancyGrid, null)

        assertEquals(0, controller.currentWaypointIndex)

        // Simulate step by step and track waypoint index progress
        var advanced = false
        for (i in 0 until 60) {
            controller.update(0.02f, 1L, costGrid, occupancyGrid, null)
            if (controller.currentWaypointIndex > 0) {
                advanced = true
            }
            if (controller.state == AgentState.ARRIVED) break
        }

        assertTrue("Waypoint index should advance from 0", advanced)
        assertEquals(AgentState.ARRIVED, controller.state)
    }

    /**
     * Test 6 — Replanning: Grid version changes and agent requests a new path.
     */
    @Test
    fun test6_dynamicReplanningOnGridVersionChange() {
        val controller = AgentController(AgentConfig(replanCooldownMs = 0L))
        controller.spawnAt(40, 40, occupancyGrid, null)

        val path = createStraightPathResult(40, 40, 50, 40, gridVersion = 5L)
        controller.applyPathResult(path, occupancyGrid, null)

        var replanTriggered = false
        controller.onReplanRequested = { _, _, _, _ ->
            replanTriggered = true
        }

        // Block a waypoint along the remaining path
        val blockedIdx = occupancyGrid.cellToIndex(46, 40)
        inflatedStates[blockedIdx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        costGrid.updateCosts(inflatedStates)

        // Update with changed grid version (6L != 5L)
        controller.update(0.02f, 6L, costGrid, occupancyGrid, null)

        assertTrue("Dynamic replanning should trigger when grid version changes and path is blocked", replanTriggered)
        assertEquals(AgentState.REPLANNING, controller.state)
        assertEquals(0f, controller.speedMps, 0.001f)
    }

    /**
     * Test 7 — Replanning origin: Replan begins from the agent's current cell rather than original start.
     */
    @Test
    fun test7_replanningOriginIsCurrentAgentCellNotOriginalStart() {
        val controller = AgentController(AgentConfig(moveSpeedMps = 1.0f, replanCooldownMs = 0L))
        controller.spawnAt(30, 30, occupancyGrid, null)

        val path = createStraightPathResult(30, 30, 50, 30, gridVersion = 1L)
        controller.applyPathResult(path, occupancyGrid, null)

        // Move agent forward several steps so it reaches cell (33, 30)
        for (i in 0 until 18) {
            controller.update(0.02f, 1L, costGrid, occupancyGrid, null)
        }

        val curCellCol = controller.cellCol
        val curCellRow = controller.cellRow
        assertTrue("Agent should have moved beyond original start (col > 30)", curCellCol > 30)

        var requestedFromCol = -1
        var requestedFromRow = -1
        var requestedToCol = -1
        var requestedToRow = -1

        controller.onReplanRequested = { fromCol, fromRow, toCol, toRow ->
            requestedFromCol = fromCol
            requestedFromRow = fromRow
            requestedToCol = toCol
            requestedToRow = toRow
        }

        // Invalidate path
        val blockedIdx = occupancyGrid.cellToIndex(48, 30)
        inflatedStates[blockedIdx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        costGrid.updateCosts(inflatedStates)

        controller.update(0.02f, 2L, costGrid, occupancyGrid, null)

        assertEquals("Replan origin must match agent's CURRENT cell column", curCellCol, requestedFromCol)
        assertEquals("Replan origin must match agent's CURRENT cell row", curCellRow, requestedFromRow)
        assertEquals(50, requestedToCol)
        assertEquals(30, requestedToRow)
        assertNotEquals("Replan origin must NOT be the original start cell (30)", 30, requestedFromCol)
    }

    /**
     * Test 8 — No path: A failed A* result causes safe stopping.
     */
    @Test
    fun test8_noPathTransitionsToSafeStop() {
        val controller = AgentController()
        controller.spawnAt(40, 40, occupancyGrid, null)

        val noPathResult = PathResult(
            status = PathStatus.NO_PATH,
            rawPath = emptyList(),
            smoothedPath = emptyList(),
            worldCoordinates = FloatArray(0),
            totalDistanceMeters = 0f,
            totalCost = Float.POSITIVE_INFINITY,
            nodesExpanded = 10,
            searchTimeMs = 0.2f,
            gridVersion = 1L,
            startCol = 40,
            startRow = 40,
            goalCol = 60,
            goalRow = 60
        )

        controller.applyPathResult(noPathResult, occupancyGrid, null)

        assertEquals(AgentState.NO_PATH, controller.state)
        assertEquals(0f, controller.speedMps, 0.001f)
        assertFalse(controller.state.isMoving)
    }

    /**
     * Test 9 — Path invalidation: Current path becomes blocked and agent transitions to replanning/blocking.
     */
    @Test
    fun test9_pathInvalidationHaltsMotion() {
        val controller = AgentController(AgentConfig(moveSpeedMps = 0.50f))
        controller.spawnAt(40, 40, occupancyGrid, null)

        val path = createStraightPathResult(40, 40, 45, 40, gridVersion = 1L)
        controller.applyPathResult(path, occupancyGrid, null)

        // Block the immediate next step cell (41, 40)
        val nextIdx = occupancyGrid.cellToIndex(41, 40)
        inflatedStates[nextIdx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        costGrid.updateCosts(inflatedStates)

        controller.update(0.05f, 2L, costGrid, occupancyGrid, null)

        assertEquals(AgentState.REPLANNING, controller.state)
        assertEquals(0f, controller.speedMps, 0.001f)
    }

    /**
     * Test 10 — Delta time: Equivalent simulated motion at different frame rates produces approximately the same trajectory.
     */
    @Test
    fun test10_deltaTimeInvariantTrajectory() {
        val config = AgentConfig(moveSpeedMps = 0.60f, maxTurnRateRadPerSec = 3.14f)

        // Agent A at 60 FPS for 1.0 second (60 steps of dt = 1/60s)
        val agent60 = AgentController(config)
        agent60.spawnAt(20, 20, occupancyGrid, null)
        val pathA = createStraightPathResult(20, 20, 35, 20)
        agent60.applyPathResult(pathA, occupancyGrid, null)

        val dt60 = 1.0f / 60.0f
        for (i in 0 until 60) {
            agent60.update(dt60, 1L, costGrid, occupancyGrid, null)
        }

        // Agent B at 30 FPS for 1.0 second (30 steps of dt = 1/30s)
        val agent30 = AgentController(config)
        agent30.spawnAt(20, 20, occupancyGrid, null)
        val pathB = createStraightPathResult(20, 20, 35, 20)
        agent30.applyPathResult(pathB, occupancyGrid, null)

        val dt30 = 1.0f / 30.0f
        for (i in 0 until 30) {
            agent30.update(dt30, 1L, costGrid, occupancyGrid, null)
        }

        val diffX = abs(agent60.floorX - agent30.floorX)
        val diffZ = abs(agent60.floorZ - agent30.floorZ)
        assertTrue("Positions at 60 FPS and 30 FPS must match within 0.02m (actual diffX=$diffX, diffZ=$diffZ)", diffX < 0.02f && diffZ < 0.02f)
    }
}
