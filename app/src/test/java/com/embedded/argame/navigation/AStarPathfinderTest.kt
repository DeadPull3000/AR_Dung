package com.embedded.argame.navigation

import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.environment.UnknownCostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic unit-style verification tests for A* Pathfinding (Milestone 8, Section 22).
 */
class AStarPathfinderTest {

    private val width = 80
    private val height = 80
    private val totalCells = width * height
    private lateinit var pathfinder: AStarPathfinder
    private lateinit var costGrid: TraversalCostGrid
    private lateinit var inflatedStates: ByteArray

    @Before
    fun setUp() {
        pathfinder = AStarPathfinder(numCellsX = width, numCellsZ = height, cellSizeMeters = 0.10f)
        costGrid = TraversalCostGrid(totalCells = totalCells, unknownPolicy = UnknownCostPolicy.BLOCKED)
        inflatedStates = ByteArray(totalCells) { NavigationCellState.FREE.code }
        costGrid.updateCosts(inflatedStates)
    }

    private fun setObstacle(col: Int, row: Int) {
        val idx = row * width + col
        inflatedStates[idx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        costGrid.updateCosts(inflatedStates)
    }

    private fun setUnknown(col: Int, row: Int) {
        val idx = row * width + col
        inflatedStates[idx] = NavigationCellState.UNKNOWN.code
        costGrid.updateCosts(inflatedStates)
    }

    /**
     * Test 1 — Empty grid: Expect direct shortest path from Start to Goal.
     */
    @Test
    fun test1_emptyGrid_findsShortestPath() {
        val startCol = 10
        val startRow = 10
        val goalCol = 20
        val goalRow = 10

        val result = pathfinder.findPath(startCol, startRow, goalCol, goalRow, costGrid, inflatedStates)

        assertEquals(PathStatus.SUCCESS, result.status)
        assertTrue(result.rawPath.isNotEmpty())
        assertEquals(GridWaypoint(startCol, startRow), result.rawPath.first())
        assertEquals(GridWaypoint(goalCol, goalRow), result.rawPath.last())
        // Straight line: 10 cells of 0.10m = 1.0m
        assertEquals(1.0f, result.totalCost, 0.01f)
    }

    /**
     * Test 2 — Wall: Blocked wall between start and goal, expect path around the wall.
     */
    @Test
    fun test2_wallObstacle_navigatesAround() {
        val startCol = 10
        val startRow = 10
        val goalCol = 16
        val goalRow = 10

        // Place a vertical wall blocking direct passage at col 13 from row 8 to row 12
        for (r in 8..12) {
            setObstacle(13, r)
        }

        val result = pathfinder.findPath(startCol, startRow, goalCol, goalRow, costGrid, inflatedStates)

        assertEquals(PathStatus.SUCCESS, result.status)
        // Verify path does not intersect any wall cell
        for (wp in result.rawPath) {
            assertFalse("Path intersected wall at (${wp.col}, ${wp.row})", wp.col == 13 && wp.row in 8..12)
        }
        // Path must go around (e.g. via row 7 or row 13)
        val hasDetour = result.rawPath.any { it.row < 8 || it.row > 12 }
        assertTrue("Path should detour around the wall", hasDetour)
    }

    /**
     * Test 3 — Corner cutting: Construct two diagonally touching obstacles.
     * Verify diagonal shortcut between them is rejected.
     */
    @Test
    fun test3_cornerCutting_touchingObstaclesRejected() {
        // Obstacle 1 at (10, 11), Obstacle 2 at (11, 10)
        // Start at (10, 10), Goal at (11, 11)
        // Stepping directly from (10, 10) to (11, 11) cuts through the touching corners!
        setObstacle(10, 11)
        setObstacle(11, 10)

        // Surround the outside so the ONLY way to reach (11, 11) would be the diagonal squeeze
        for (r in 9..12) {
            setObstacle(9, r)
            setObstacle(12, r)
        }
        setObstacle(10, 9)
        setObstacle(11, 9)
        setObstacle(10, 12)
        setObstacle(11, 12)

        val result = pathfinder.findPath(10, 10, 11, 11, costGrid, inflatedStates)

        // Since all alternative routes are blocked, diagonal squeeze MUST be rejected -> NO_PATH
        assertEquals(PathStatus.NO_PATH, result.status)
    }

    /**
     * Test 4 — No path: Surround the goal with blocked cells. Expect NO_PATH.
     */
    @Test
    fun test4_noPath_completelyEnclosedGoal() {
        val goalCol = 30
        val goalRow = 30

        // Enclose goal completely
        for (dc in -1..1) {
            for (dr in -1..1) {
                if (dc != 0 || dr != 0) {
                    setObstacle(goalCol + dc, goalRow + dr)
                }
            }
        }

        val result = pathfinder.findPath(10, 10, goalCol, goalRow, costGrid, inflatedStates)
        assertEquals(PathStatus.NO_PATH, result.status)
    }

    /**
     * Test 5 — Start blocked: Expect immediate rejection (START_BLOCKED).
     */
    @Test
    fun test5_startBlocked_rejectedImmediately() {
        setObstacle(5, 5)
        val result = pathfinder.findPath(5, 5, 20, 20, costGrid, inflatedStates)
        assertEquals(PathStatus.START_BLOCKED, result.status)
        assertEquals(0, result.nodesExpanded)
    }

    /**
     * Test 6 — Goal blocked: Expect immediate rejection (GOAL_BLOCKED).
     */
    @Test
    fun test6_goalBlocked_rejectedImmediately() {
        setObstacle(20, 20)
        val result = pathfinder.findPath(5, 5, 20, 20, costGrid, inflatedStates)
        assertEquals(PathStatus.GOAL_BLOCKED, result.status)
        assertEquals(0, result.nodesExpanded)
    }

    /**
     * Test 7 — Unknown blocked: Verify unknown cells cannot be traversed under conservative policy.
     */
    @Test
    fun test7_unknownBlocked_cannotTraverse() {
        costGrid.unknownPolicy = UnknownCostPolicy.BLOCKED
        // Create an unknown barrier between start and goal
        for (r in 0 until height) {
            setUnknown(25, r)
        }

        val result = pathfinder.findPath(10, 20, 40, 20, costGrid, inflatedStates)
        assertEquals(PathStatus.NO_PATH, result.status)
    }

    /**
     * Test 8 — Unknown expensive: Switch to EXPENSIVE_PENALTY.
     * Verify unknown route is taken when detour around free space is far too long.
     */
    @Test
    fun test8_unknownExpensive_allowsExploratoryDetour() {
        costGrid.unknownPolicy = UnknownCostPolicy.EXPENSIVE_PENALTY
        costGrid.updateCosts(inflatedStates)

        // Block entire column with obstacles except for 1 unknown cell at row 10
        for (r in 0 until height) {
            if (r == 10) {
                setUnknown(20, r)
            } else {
                setObstacle(20, r)
            }
        }

        val result = pathfinder.findPath(18, 10, 22, 10, costGrid, inflatedStates)
        assertEquals(PathStatus.SUCCESS, result.status)
        // Path should cross through (20, 10)
        val crossedUnknown = result.rawPath.any { it.col == 20 && it.row == 10 }
        assertTrue("Path should cross the unknown penalty cell", crossedUnknown)
        // Cost should include 15.0f penalty * 0.10m = 1.50f
        assertTrue("Path cost should reflect unknown penalty", result.totalCost > 1.5f)
    }

    /**
     * Test 9 — Diagonal route: Verify diagonal movement is shorter in cost and Euclidean length
     * than an unnecessarily constrained Manhattan path.
     */
    @Test
    fun test9_diagonalRoute_shorterThanManhattan() {
        val startCol = 10
        val startRow = 10
        val goalCol = 20
        val goalRow = 20

        val result = pathfinder.findPath(startCol, startRow, goalCol, goalRow, costGrid, inflatedStates)

        assertEquals(PathStatus.SUCCESS, result.status)
        // 10 diagonal steps: 10 * sqrt(2) * 0.10m ≈ 1.414m
        // Manhattan would be: 20 steps of 0.10m = 2.0m
        val expectedDiagonalCost = 10 * 1.41421356f * 0.10f
        assertEquals(expectedDiagonalCost, result.totalCost, 0.05f)
        assertTrue("Diagonal path cost should be strictly less than Manhattan (2.0f)", result.totalCost < 1.9f)
    }

    /**
     * Test 10 — Dynamic obstacle: Generate path, modify grid, then compute second path.
     * Verify path dynamically changes.
     */
    @Test
    fun test10_dynamicObstacle_recomputesNewRoute() {
        val startCol = 10
        val startRow = 10
        val goalCol = 20
        val goalRow = 10

        // 1. Initial straight path
        val initialResult = pathfinder.findPath(startCol, startRow, goalCol, goalRow, costGrid, inflatedStates)
        assertEquals(PathStatus.SUCCESS, initialResult.status)
        assertEquals(1.0f, initialResult.totalCost, 0.01f)

        // 2. Place dynamic obstacle directly in the middle of the path
        for (r in 8..12) {
            setObstacle(15, r)
        }

        // 3. Recompute path
        val replannedResult = pathfinder.findPath(startCol, startRow, goalCol, goalRow, costGrid, inflatedStates)
        assertEquals(PathStatus.SUCCESS, replannedResult.status)
        assertNotEquals(initialResult.totalCost, replannedResult.totalCost)
        assertTrue("Replanned path cost must be higher due to obstacle avoidance", replannedResult.totalCost > initialResult.totalCost)
        for (wp in replannedResult.rawPath) {
            assertFalse("Replanned path must not pass through obstacle", wp.col == 15 && wp.row in 8..12)
        }
    }
}
