package com.embedded.argame.ai

import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.GridWaypoint
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Unit verification tests for Cover-Aware Search Behaviour (Milestone 11, Section 9).
 * Tests 14 to 25 covering candidate bounds, obstacle avoidance, candidate separation,
 * state transitions, rediscovery, timeout, and request token concurrency.
 */
class CoverAwareSearchTest {

    private val width = 80
    private val depth = 80
    private val cellSize = 0.10f

    private lateinit var occupancyGrid: OccupancyGrid
    private lateinit var costGrid: TraversalCostGrid
    private lateinit var config: CreatureAIConfig
    private lateinit var controller: CreatureAIController

    @Before
    fun setUp() {
        occupancyGrid = OccupancyGrid(cellSizeMeters = cellSize, widthMeters = 8.0f, depthMeters = 8.0f)
        costGrid = occupancyGrid.traversalCostGrid

        // Set entire grid as safe and traversable by default
        occupancyGrid.inflatedCellStates.fill(NavigationCellState.FREE.code)
        costGrid.updateCosts(occupancyGrid.inflatedCellStates)

        config = CreatureAIConfig(
            detectionRadiusMeters = 3.0f,
            approachPlayerDistanceMeters = 0.40f,
            lostVisibilityGracePeriodMs = 500L,
            maxSearchDurationSec = 5.0f,
            searchRadiusMeters = 1.0f,
            searchCandidateWaitSec = 0.5f,
            maxSearchCandidates = 4,
            minCandidateSeparationMeters = 0.35f,
            coverBoundaryWeight = 1.5f,
            aiDecisionIntervalMs = 50L,
            chaseReplanIntervalMs = 100L,
            chaseGoalChangeThresholdMeters = 0.20f,
            blockedCooldownMs = 500L
        )

        controller = CreatureAIController(
            config = config,
            perception = CreaturePerception(config),
            rng = Random(42)
        )
    }

    private fun createPose(col: Int = 40, row: Int = 40, state: AgentState = AgentState.IDLE): AgentPose {
        val (fx, fz) = occupancyGrid.cellToFloorCoord(col, row)
        return AgentPose(
            worldX = fx, worldY = 0f, worldZ = fz,
            floorX = fx, floorZ = fz,
            cellCol = col, cellRow = row,
            headingRadians = 0f, speedMps = 0f,
            state = state
        )
    }

    private fun markObstacle(col: Int, row: Int) {
        val idx = occupancyGrid.cellToIndex(col, row)
        occupancyGrid.inflatedCellStates[idx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        costGrid.updateCosts(occupancyGrid.inflatedCellStates)
    }

    /**
     * Test 14 — Search candidates stay inside grid bounds.
     */
    @Test
    fun test14_searchCandidatesStayInsideGridBounds() {
        // Center near edge of the 80x80 grid: col = 2, row = 2
        val candidates = controller.generateCoverAwareSearchCandidates(2, 2, occupancyGrid)

        assertTrue("Should produce candidates near edge", candidates.isNotEmpty())
        for (c in candidates) {
            assertTrue("Col ${c.first} should be in [0, 79]", c.first in 0 until width)
            assertTrue("Row ${c.second} should be in [0, 79]", c.second in 0 until depth)
        }
    }

    /**
     * Test 15 — Candidates that are occupied or non-traversable are rejected.
     */
    @Test
    fun test15_occupiedOrNonTraversableCandidatesRejected() {
        // Place obstacles around (40, 40)
        markObstacle(40, 41)
        markObstacle(40, 42)
        markObstacle(41, 40)

        val candidates = controller.generateCoverAwareSearchCandidates(40, 40, occupancyGrid)

        for (c in candidates) {
            val idx = occupancyGrid.cellToIndex(c.first, c.second)
            assertTrue("Candidate at ($c) must be traversable", costGrid.isTraversable(idx))
            assertNotEquals("Candidate must not be an obstacle", NavigationCellState.PHYSICAL_OBSTACLE, occupancyGrid.getNavigationCellState(c.first, c.second))
        }
    }

    /**
     * Test 16 — Candidate generation remains bounded.
     */
    @Test
    fun test16_candidateGenerationRemainsBounded() {
        val candidates = controller.generateCoverAwareSearchCandidates(40, 40, occupancyGrid)
        assertTrue("Candidates count should not exceed maxSearchCandidates", candidates.size <= config.maxSearchCandidates)
        assertTrue("Candidates count should be positive", candidates.isNotEmpty())
    }

    /**
     * Test 17 — Nearby candidate locations are selected without repeatedly choosing the same cell.
     */
    @Test
    fun test17_candidateLocationsSelectedWithoutDuplicates() {
        val candidates = controller.generateCoverAwareSearchCandidates(40, 40, occupancyGrid)
        val uniqueSet = candidates.toSet()
        assertEquals("All generated candidates must be unique", candidates.size, uniqueSet.size)

        // Verify minimum separation between secondary candidates
        for (i in 1 until candidates.size) {
            val (fx1, fz1) = occupancyGrid.cellToFloorCoord(candidates[i].first, candidates[i].second)
            for (j in 0 until i) {
                val (fx2, fz2) = occupancyGrid.cellToFloorCoord(candidates[j].first, candidates[j].second)
                val dx = fx1 - fx2
                val dz = fz1 - fz2
                val dist = kotlin.math.sqrt(dx * dx + dz * dz)
                assertTrue("Distance $dist should be >= minCandidateSeparation (${config.minCandidateSeparationMeters})",
                    dist >= config.minCandidateSeparationMeters - 0.05f)
            }
        }
    }

    /**
     * Test 18 — Candidate choice respects the agent's ability to reach the target.
     */
    @Test
    fun test18_candidateChoiceRespectsTraversability() {
        val candidates = controller.generateCoverAwareSearchCandidates(35, 35, occupancyGrid)
        for (c in candidates) {
            val idx = occupancyGrid.cellToIndex(c.first, c.second)
            assertTrue("Each candidate must be traversable on cost grid", costGrid.isTraversable(idx))
        }
    }

    /**
     * Test 19 — A failed route advances or terminates the search safely.
     */
    @Test
    fun test19_failedRouteAdvancesOrTerminatesSearchSafely() {
        val pose = createPose(40, 40)
        // 1. Initialize patrol
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)

        // 2. Player detected -> CHASING
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // 3. Lose player -> enter SEARCHING after grace period
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        val initialCandidateIndex = controller.getSnapshot().currentSearchCandidateIndex
        val reqId = controller.activeRequestId

        // 4. Dispatch path failure for search candidate
        val failResult = PathResult(status = PathStatus.NO_PATH, gridVersion = 1L)
        controller.onPathResult(failResult, reqId, occupancyGrid, null)

        // Status reflects failure and candidate index advanced
        val postSnapshot = controller.getSnapshot()
        assertTrue("Candidate index should advance after failed path",
            postSnapshot.currentSearchCandidateIndex > initialCandidateIndex || controller.state == CreatureAIState.RETURNING)
    }

    /**
     * Test 20 — Repeated visibility noise does not repeatedly reset the search lifecycle.
     */
    @Test
    fun test20_visibilityNoiseDoesNotResetSearchLifecycle() {
        val pose = createPose(40, 40)
        // 1. Initialize patrol and detect player
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // 2. Transition to SEARCHING
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)
        val frozenCol = controller.frozenSearchCol
        val frozenRow = controller.frozenSearchRow

        // 3. Weak unconfirmed / distant observation does NOT reset search or overwrite frozen location
        controller.update(true, pfx + 4.5f, 0f, pfz, null, occupancyGrid, pose, 2500L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertEquals("Frozen search col must be preserved", frozenCol, controller.frozenSearchCol)
        assertEquals("Frozen search row must be preserved", frozenRow, controller.frozenSearchRow)
    }

    /**
     * Test 21 — Rediscovery during searching returns to CHASING.
     */
    @Test
    fun test21_rediscoveryDuringSearchingReturnsToChasing() {
        val pose = createPose(40, 40)
        // 1. Initialize patrol and chase player
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // 2. Lose player -> SEARCHING
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        // 3. Player reappears within detection range with clear line of sight
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 2500L)
        assertEquals("Creature must immediately resume CHASING on rediscovery", CreatureAIState.CHASING, controller.state)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
    }

    /**
     * Test 22 — Rediscovery during returning returns to CHASING.
     */
    @Test
    fun test22_rediscoveryDuringReturningReturnsToChasing() {
        val pose = createPose(40, 40)
        // 1. Enter CHASING then SEARCHING
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        // 2. Search budget expires (>5.0s: 2000ms + 5200ms = 7200ms) -> RETURNING
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 8000L)
        assertEquals(CreatureAIState.RETURNING, controller.state)

        // 3. Player proxy reappears close to creature -> immediately re-enters CHASING!
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 8500L)
        assertEquals("Creature must immediately resume CHASING on rediscovery during RETURNING",
            CreatureAIState.CHASING, controller.state)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
    }

    /**
     * Test 23 — Search timeout or exhausted candidates leads to RETURNING.
     */
    @Test
    fun test23_searchTimeoutLeadsToReturning() {
        val pose = createPose(40, 40)
        // 1. Chase then search
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        // 2. Advance time past maxSearchDurationSec (5.0s -> 2000ms + 5200ms = 7200ms)
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 7500L)
        assertEquals("Creature must transition to RETURNING after search budget expires",
            CreatureAIState.RETURNING, controller.state)
        assertEquals(TargetType.RETURN_PATROL, controller.targetType)
    }

    /**
     * Test 24 — Invalid tracking does not cause a false detection or corrupt last known position.
     */
    @Test
    fun test24_invalidTrackingDoesNotCorruptLastKnownPosition() {
        val pose = createPose(40, 40)
        // 1. Establish valid pursuit
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        val knownCol = controller.lastKnownPlayerCol
        val knownRow = controller.lastKnownPlayerRow
        assertTrue("Must have known player col", knownCol >= 0)

        // 2. Tracking lost with spurious coordinate inputs
        controller.update(false, 999f, 999f, 999f, null, occupancyGrid, pose, 1500L)
        assertEquals(CreatureAIState.PAUSED, controller.state)
        // Last known location remains pristine
        assertEquals(knownCol, controller.lastKnownPlayerCol)
        assertEquals(knownRow, controller.lastKnownPlayerRow)
    }

    /**
     * Test 25 — Stale navigation results cannot overwrite a newer search or chase target.
     */
    @Test
    fun test25_staleNavigationResultsCannotOverwriteNewerTarget() {
        val pose = createPose(40, 40)
        // 1. Chase player
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.CHASING, controller.state)
        val chaseReqId = controller.activeRequestId

        // 2. Lose sight -> enter SEARCHING (creates new request ID)
        controller.update(true, pfx + 5.0f, 0f, pfz, null, occupancyGrid, pose, 2000L)
        val searchReqId = controller.activeRequestId
        assertTrue("searchReqId should be greater than chaseReqId", searchReqId > chaseReqId)

        // 3. Stale path result from chase arrived late
        val staleResult = PathResult(
            status = PathStatus.SUCCESS,
            rawPath = listOf(GridWaypoint(40, 40), GridWaypoint(45, 45)),
            gridVersion = 1L
        )
        controller.onPathResult(staleResult, chaseReqId, occupancyGrid, null)

        // State remains SEARCHING, target type remains SEARCH_LAST_KNOWN
        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertEquals(TargetType.SEARCH_LAST_KNOWN, controller.targetType)
    }
}
