package com.embedded.argame.ai

import com.embedded.argame.environment.GridCellState
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.environment.UnknownCostPolicy
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.GridWaypoint
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Deterministic unit verification tests for Milestone 10:
 * Reactive Creature AI, Player Awareness & Behaviour State Machine (Section 9).
 */
class CreatureAIControllerTest {

    private val width = 80
    private val depth = 80
    private val cellSize = 0.10f
    private val totalCells = width * depth

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
            maxSearchCandidates = 3,
            patrolPauseDurationSec = 1.0f,
            minPatrolDistanceMeters = 0.8f,
            maxPatrolDistanceMeters = 3.0f,
            aiDecisionIntervalMs = 50L,
            chaseReplanIntervalMs = 100L,
            chaseGoalChangeThresholdMeters = 0.20f,
            blockedCooldownMs = 500L
        )

        controller = CreatureAIController(
            config = config,
            perception = CreaturePerception(config),
            rng = Random(12345)
        )
    }

    private fun createPose(
        col: Int = 40,
        row: Int = 40,
        state: AgentState = AgentState.IDLE
    ): AgentPose {
        val (fx, fz) = occupancyGrid.cellToFloorCoord(col, row)
        return AgentPose(
            worldX = fx,
            worldY = 0f,
            worldZ = fz,
            floorX = fx,
            floorZ = fz,
            cellCol = col,
            cellRow = row,
            headingRadians = 0f,
            speedMps = 0f,
            state = state
        )
    }

    private fun createDummyPathResult(
        startCol: Int,
        startRow: Int,
        goalCol: Int,
        goalRow: Int,
        status: PathStatus = PathStatus.SUCCESS
    ): PathResult {
        return PathResult(
            status = status,
            rawPath = listOf(GridWaypoint(startCol, startRow), GridWaypoint(goalCol, goalRow)),
            smoothedPath = listOf(GridWaypoint(startCol, startRow), GridWaypoint(goalCol, goalRow)),
            worldCoordinates = FloatArray(0),
            totalDistanceMeters = 1.0f,
            totalCost = 1.0f,
            nodesExpanded = 10,
            searchTimeMs = 0.5f,
            gridVersion = 1L,
            startCol = startCol,
            startRow = startRow,
            goalCol = goalCol,
            goalRow = goalRow
        )
    }

    /**
     * Test 1 — AI waits when tracking or navigation data is not ready.
     */
    @Test
    fun test1_aiWaitsWhenTrackingOrNavigationNotReady() {
        var requested = false
        controller.onNavigationGoalRequested = { _, _, _, _, _, _ -> requested = true }

        val pose = createPose(40, 40)
        // Update with tracking = false
        controller.update(
            isTracking = false,
            playerWorldX = 0f,
            playerWorldY = 0f,
            playerWorldZ = 0f,
            floorReference = null,
            occupancyGrid = occupancyGrid,
            agentPose = pose,
            currentTimeMs = 1000L
        )

        assertEquals(CreatureAIState.INITIALIZING, controller.state)
        assertFalse("AI should not request paths while tracking is invalid", requested)
    }

    /**
     * Test 2 — A valid, sufficiently distant player does not trigger a chase.
     */
    @Test
    fun test2_distantPlayerDoesNotTriggerChase() {
        val pose = createPose(40, 40)
        // Creature at (0.05, 0.05) floor coords. Player at (4.0, 0.0) -> distance ~ 3.95m > 3.0m
        controller.update(
            isTracking = true,
            playerWorldX = 3.95f,
            playerWorldY = 0f,
            playerWorldZ = 0.05f,
            floorReference = null,
            occupancyGrid = occupancyGrid,
            agentPose = pose,
            currentTimeMs = 1000L
        )

        // Should transition to PATROLLING, not CHASING
        assertEquals(CreatureAIState.PATROLLING, controller.state)
        assertFalse(controller.lastPerception.isPlayerDetected)
    }

    /**
     * Test 3 — A nearby visible player triggers CHASING.
     */
    @Test
    fun test3_nearbyVisiblePlayerTriggersChase() {
        val pose = createPose(40, 40)
        // First tick to initialize patrol
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)

        // Player appears 1.5m away with clear LoS (col 55, row 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(55, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)

        assertEquals(CreatureAIState.CHASING, controller.state)
        assertTrue(controller.lastPerception.isPlayerDetected)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
    }

    /**
     * Test 4 — A player outside the detection radius is not pursued.
     */
    @Test
    fun test4_playerOutsideDetectionRadiusNotPursued() {
        val pose = createPose(40, 40)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)

        // Player 3.5m away (detection radius is 3.0m)
        val (cfx, cfz) = occupancyGrid.cellToFloorCoord(40, 40)
        controller.update(true, cfx + 3.5f, 0f, cfz, null, occupancyGrid, pose, 1200L)

        assertEquals(CreatureAIState.PATROLLING, controller.state)
        assertFalse(controller.lastPerception.isPlayerDetected)
    }

    /**
     * Test 5 — A blocked line of sight prevents new detection.
     */
    @Test
    fun test5_blockedLineOfSightPreventsDetection() {
        val pose = createPose(40, 40)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)

        // Place a physical obstacle wall at col 45 (between creature at 40 and player at 50)
        for (r in 35..45) {
            val idx = occupancyGrid.cellToIndex(45, r)
            occupancyGrid.inflatedCellStates[idx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        }
        costGrid.updateCosts(occupancyGrid.inflatedCellStates)

        // Player is at col 50 (distance 1.0m < 3.0m), but wall is in between
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)

        assertFalse("Line of sight should be blocked by wall", controller.lastPerception.isLineOfSightClear)
        assertFalse("Player must not be detected when LoS is blocked", controller.lastPerception.isPlayerDetected)
        assertEquals(CreatureAIState.PATROLLING, controller.state)
    }

    /**
     * Test 6 — Unknown cells follow the configured visibility policy.
     */
    @Test
    fun test6_unknownCellsFollowConfiguredVisibilityPolicy() {
        // Put UNKNOWN cells in the corridor between col 40 and 50
        for (c in 42..48) {
            val idx = occupancyGrid.cellToIndex(c, 40)
            occupancyGrid.inflatedCellStates[idx] = NavigationCellState.UNKNOWN.code
        }

        // Policy 1: UNKNOWN = BLOCKED -> LoS should be blocked
        costGrid.unknownPolicy = UnknownCostPolicy.BLOCKED
        val losBlocked = controller.perception.checkLineOfSight(40, 40, 50, 40, occupancyGrid)
        assertFalse("Under BLOCKED policy, UNKNOWN cells must block LoS", losBlocked)

        // Policy 2: UNKNOWN = EXPENSIVE_PENALTY -> LoS is NOT blocked
        costGrid.unknownPolicy = UnknownCostPolicy.EXPENSIVE_PENALTY
        val losClear = controller.perception.checkLineOfSight(40, 40, 50, 40, occupancyGrid)
        assertTrue("Under EXPENSIVE_PENALTY policy, UNKNOWN cells do not block LoS", losClear)
    }

    /**
     * Test 7 — Brief loss of visibility does not immediately end pursuit (grace period).
     */
    @Test
    fun test7_briefLossOfVisibilityDoesNotImmediatelyEndPursuit() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        // Start chase
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Move player behind obstacle or out of range for 200ms (< 500ms grace period)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1300L)

        // Still CHASING due to active grace period!
        assertEquals("Creature should remain in CHASING during visibility grace period", CreatureAIState.CHASING, controller.state)
    }

    /**
     * Test 8 — Sustained loss of visibility transitions to SEARCHING.
     */
    @Test
    fun test8_sustainedLossOfVisibilityTransitionsToSearching() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        // Start chase at t=1000ms
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Player lost at t=1100ms. Update at t=1700ms (600ms > 500ms grace period)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1700L)

        assertEquals("Sustained loss of sight should transition to SEARCHING", CreatureAIState.SEARCHING, controller.state)
    }

    /**
     * Test 9 — The last known player location is preserved when required.
     */
    @Test
    fun test9_lastKnownPlayerLocationPreserved() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(52, 43)

        // Player spotted at col 52, row 43
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Player disappears beyond grace period
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1800L)

        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertEquals(52, controller.lastKnownPlayerCol)
        assertEquals(43, controller.lastKnownPlayerRow)
        assertEquals(TargetType.SEARCH_LAST_KNOWN, controller.targetType)
        assertEquals(52, controller.targetCol)
        assertEquals(43, controller.targetRow)
    }

    /**
     * Test 10 — A valid reachable patrol destination is selected.
     */
    @Test
    fun test10_validReachablePatrolDestinationSelected() {
        val pose = createPose(40, 40)
        var requestedTargetCol = -1
        var requestedTargetRow = -1
        controller.onNavigationGoalRequested = { _, _, toCol, toRow, _, _ ->
            requestedTargetCol = toCol
            requestedTargetRow = toRow
        }

        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)

        assertEquals(CreatureAIState.PATROLLING, controller.state)
        assertTrue("Patrol target col should be in bounds", requestedTargetCol in 0 until width)
        assertTrue("Patrol target row should be in bounds", requestedTargetRow in 0 until depth)
        val idx = occupancyGrid.cellToIndex(requestedTargetCol, requestedTargetRow)
        assertTrue("Patrol destination must be traversable", costGrid.isTraversable(idx))
    }

    /**
     * Test 11 — Invalid, occupied, and out-of-bounds patrol targets are rejected.
     */
    @Test
    fun test11_invalidOccupiedAndOutOfBoundsPatrolTargetsRejected() {
        val pose = createPose(40, 40)

        // Block all cells except a single valid candidate at (50, 40) (distance = 10 cells = 1.0m >= minPatrolDistance 0.8m)
        for (i in 0 until totalCells) {
            occupancyGrid.inflatedCellStates[i] = NavigationCellState.PHYSICAL_OBSTACLE.code
        }
        val targetIdx = occupancyGrid.cellToIndex(50, 40)
        occupancyGrid.inflatedCellStates[targetIdx] = NavigationCellState.FREE.code
        costGrid.updateCosts(occupancyGrid.inflatedCellStates)

        val candidate = controller.selectPatrolDestination(occupancyGrid, pose)
        assertNotNull("Should select only the valid free candidate", candidate)
        assertEquals(50, candidate!!.first)
        assertEquals(40, candidate.second)
    }

    /**
     * Test 12 — Patrol resumes with another target after arrival.
     */
    @Test
    fun test12_patrolResumesWithAnotherTargetAfterArrival() {
        val pose = createPose(40, 40)
        var target2Col = -1
        var requestCount = 0

        controller.onNavigationGoalRequested = { _, _, toCol, _, _, _ ->
            requestCount++
            if (requestCount == 2) target2Col = toCol
        }

        // Start patrol at t=1000ms with player far away (10m, 10m)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        assertEquals(1, requestCount)

        // Agent arrives at target at t=1200ms
        val arrivedPose = createPose(controller.targetCol, controller.targetRow, AgentState.ARRIVED)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 1200L)

        // Pauses at patrol waypoint for patrolPauseDurationSec (1.0s)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 1700L)
        assertEquals("Should not pick next target until pause duration elapses", 1, requestCount)

        // At t=2400ms (> 1.0s pause), picks next destination
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 2400L)
        assertEquals(2, requestCount)
        assertNotEquals(-1, target2Col)
    }

    /**
     * Test 13 — Searching selects only bounded, valid candidate locations.
     */
    @Test
    fun test13_searchingSelectsBoundedValidCandidateLocations() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        // Sustained loss of sight
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1800L)

        assertEquals(CreatureAIState.SEARCHING, controller.state)
        val snapshot = controller.getSnapshot()
        assertTrue("Search target col must be in bounds", snapshot.targetCol in 0 until width)
        assertTrue("Search target row must be in bounds", snapshot.targetRow in 0 until depth)
        val targetIdx = occupancyGrid.cellToIndex(snapshot.targetCol, snapshot.targetRow)
        assertTrue("Search candidate must be traversable", costGrid.isTraversable(targetIdx))
    }

    /**
     * Test 14 — Search timeout transitions to RETURNING.
     */
    @Test
    fun test14_searchTimeoutTransitionsToReturning() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        // Enters SEARCHING at t=1700ms
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1700L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        // After maxSearchDurationSec (5.0s, so t >= 6700ms), search expires
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 7000L)
        assertEquals(CreatureAIState.RETURNING, controller.state)
        assertEquals(TargetType.RETURN_PATROL, controller.targetType)
    }

    /**
     * Test 15 — Rediscovering the player during searching returns to CHASING.
     */
    @Test
    fun test15_rediscoveringPlayerDuringSearchingReturnsToChasing() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1700L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)

        // Player reappears at t=2500ms
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 2500L)
        assertEquals(CreatureAIState.CHASING, controller.state)
        assertTrue(controller.lastPerception.isPlayerDetected)
    }

    /**
     * Test 16 — Rediscovering the player during returning returns to CHASING.
     */
    @Test
    fun test16_rediscoveringPlayerDuringReturningReturnsToChasing() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)

        // Transition: CHASING -> SEARCHING -> RETURNING
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1700L)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 7000L)
        assertEquals(CreatureAIState.RETURNING, controller.state)

        // Player rediscovered during RETURNING
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 7500L)
        assertEquals(CreatureAIState.CHASING, controller.state)
        assertTrue(controller.lastPerception.isPlayerDetected)
    }

    /**
     * Test 17 — An unreachable target leads to a safe blocked/recovery condition.
     */
    @Test
    fun test17_unreachableTargetLeadsToSafeBlockedCondition() {
        val pose = createPose(40, 40)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val reqId = controller.activeRequestId

        // Deliver a NO_PATH failure result for active request
        val failureResult = createDummyPathResult(40, 40, controller.targetCol, controller.targetRow, PathStatus.NO_PATH)
        controller.onPathResult(failureResult, reqId, occupancyGrid, null)

        assertEquals("Failed patrol path should transition to BLOCKED", CreatureAIState.BLOCKED, controller.state)
    }

    /**
     * Test 18 — A stale asynchronous path result cannot replace a newer goal.
     */
    @Test
    fun test18_staleAsynchronousPathResultCannotReplaceNewerGoal() {
        val pose = createPose(40, 40)
        // 1. Initial patrol request (#1)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val staleRequestId = controller.activeRequestId

        // 2. Player appears: new chase request (#2)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(50, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)
        val activeRequestId = controller.activeRequestId
        assertTrue("activeRequestId must increment for newer goal", activeRequestId > staleRequestId)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // 3. Stale path result from request #1 arrives late
        val staleResult = createDummyPathResult(40, 40, 20, 20, PathStatus.SUCCESS)
        controller.onPathResult(staleResult, staleRequestId, occupancyGrid, null)

        // Must remain in CHASING with newer goal!
        assertEquals("Stale patrol result must not overwrite active CHASING state", CreatureAIState.CHASING, controller.state)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
    }

    /**
     * Test 19 — Disabling or resetting the AI prevents obsolete requests from continuing to control the agent.
     */
    @Test
    fun test19_disablingOrResettingAiPreventsObsoleteRequestsFromControllingAgent() {
        val pose = createPose(40, 40)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        val pendingRequestId = controller.activeRequestId

        // Reset AI
        controller.reset()
        assertEquals(CreatureAIState.INITIALIZING, controller.state)
        assertTrue("Reset must advance activeRequestId to invalidate pending results", controller.activeRequestId > pendingRequestId)

        // Obsolete path result arrives
        val obsoleteResult = createDummyPathResult(40, 40, 45, 45, PathStatus.SUCCESS)
        controller.onPathResult(obsoleteResult, pendingRequestId, occupancyGrid, null)

        // Must remain in INITIALIZING, not adopt obsolete target
        assertEquals(CreatureAIState.INITIALIZING, controller.state)
        assertEquals(TargetType.NONE, controller.targetType)
    }

    /**
     * Test 20 — Invalid tracking safely pauses without false detections.
     */
    @Test
    fun test20_invalidTrackingSafelyPausesWithoutFalseDetections() {
        val pose = createPose(40, 40)
        controller.update(true, 0f, 0f, 0f, null, occupancyGrid, pose, 1000L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)

        // Tracking lost abruptly
        controller.update(false, 0f, 0f, 0f, null, occupancyGrid, pose, 1200L)

        assertEquals("Loss of tracking must transition to PAUSED", CreatureAIState.PAUSED, controller.state)
        assertFalse("Lost tracking must not yield valid perception", controller.lastPerception.isTrackingValid)
        assertFalse("Lost tracking must not detect player", controller.lastPerception.isPlayerDetected)
    }
}
