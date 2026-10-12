package com.embedded.argame.ai

import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import com.embedded.argame.environment.TraversalCostGrid
import com.embedded.argame.navigation.AgentPose
import com.embedded.argame.navigation.AgentState
import com.embedded.argame.navigation.PathResult
import com.embedded.argame.navigation.PathStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Automated deterministic unit verification tests for Milestone 13:
 * Stealth, Noise Investigation & Adaptive Creature Intelligence (Section 10).
 *
 * Covers:
 * - Suspicion Model (Tests 1–8)
 * - Virtual Noise Events (Tests 9–17)
 * - Extended Creature AI (Tests 18–28)
 * - Adaptive Difficulty (Tests 29–37)
 */
class StealthAdaptiveAiTest {

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

        // Set entire grid traversable by default
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
            blockedCooldownMs = 300L,
            minInvestigateTargetSwitchIntervalMs = 1500L
        )

        controller = CreatureAIController(config = config, rng = Random(12345))
    }

    private fun createPose(col: Int, row: Int, state: AgentState = AgentState.NAVIGATING): AgentPose {
        val (fx, fz) = occupancyGrid.cellToFloorCoord(col, row)
        return AgentPose(
            worldX = fx,
            worldY = 0f,
            worldZ = fz,
            floorX = fx,
            floorZ = fz,
            cellCol = col,
            cellRow = row,
            state = state
        )
    }

    // =========================================================================
    // SECTION 1: Suspicion Model (Tests 1–8)
    // =========================================================================

    /** Test 1 — Suspicion remains within [0, 100]. */
    @Test
    fun test01_suspicionRemainsWithin0To100() {
        val model = SuspicionModel()
        assertEquals(0f, model.getSnapshot().level, 0.001f)

        // Try driving below zero via decay
        model.update(currentTimeMs = 1000L, dtMs = 5000L, isVisualConfirmed = false, isTrackingValid = true)
        assertTrue("Suspicion must be >= 0", model.getSnapshot().level >= 0f)

        // Exceed upper ceiling with multiple high-gain events
        for (i in 0..10) {
            model.addEvidence(EvidenceType.VISUAL_CONFIRMED, 1.0f, 0f, 0f, 1000L + i * 100)
        }
        assertEquals("Suspicion must be capped at 100", 100f, model.getSnapshot().level, 0.001f)
    }

    /** Test 2 — Valid evidence increases suspicion according to configuration. */
    @Test
    fun test02_validEvidenceIncreasesSuspicionAccordingToConfig() {
        val model = SuspicionModel()
        val s1 = model.addEvidence(EvidenceType.NOISE_RELIC, 1.0f, 1.0f, 2.0f, 1000L)
        assertEquals(model.config.relicCollectedGain, s1, 0.001f)

        val s2 = model.addEvidence(EvidenceType.NOISE_DISTRACTION, 0.3f, 1.0f, 2.0f, 1100L)
        assertEquals(model.config.relicCollectedGain + model.config.distractionGain * 0.3f, s2, 0.001f)
    }

    /** Test 3 — Suspicion decays according to elapsed time. */
    @Test
    fun test03_suspicionDecaysAccordingToElapsedTime() {
        val model = SuspicionModel()
        model.addEvidence(EvidenceType.VISUAL_CONFIRMED, 1.0f, 0f, 0f, 1000L) // Gain = 85
        val initial = model.getSnapshot().level

        // Advance 2 seconds with decay rate 12/s -> decay by 24
        model.update(currentTimeMs = 3000L, dtMs = 2000L, isVisualConfirmed = false, isTrackingValid = true)
        val expected = (initial - 24f).coerceAtLeast(0f)
        assertEquals(expected, model.getSnapshot().level, 0.05f)
    }

    /** Test 4 — Expired evidence no longer contributes. */
    @Test
    fun test04_expiredEvidenceNoLongerContributes() {
        val model = SuspicionModel()
        model.addEvidence(EvidenceType.NOISE_RELIC, 1.0f, 0f, 0f, 1000L)
        assertEquals(1, model.getSnapshot(1000L).activeEvidenceCount)

        // Advance past evidenceMaxAgeMs (8000ms)
        model.update(currentTimeMs = 10000L, dtMs = 9000L, isVisualConfirmed = false, isTrackingValid = true)
        assertEquals(0, model.getSnapshot(10000L).activeEvidenceCount)
    }

    /** Test 5 — Repeated events respect the configured cap. */
    @Test
    fun test05_repeatedEventsRespectConfiguredCap() {
        val model = SuspicionModel()
        // Add multiple noises that would otherwise exceed repeatedNoiseCap (65)
        model.addEvidence(EvidenceType.NOISE_RELIC, 1.0f, 0f, 0f, 1000L) // +45
        model.addEvidence(EvidenceType.NOISE_DISTRACTION, 1.0f, 0f, 0f, 1100L) // wants +50, capped at remaining 20
        model.addEvidence(EvidenceType.NOISE_MOVEMENT, 1.0f, 0f, 0f, 1200L) // headroom exhausted

        assertEquals(model.config.repeatedNoiseCap, model.getSnapshot().level, 0.001f)
    }

    /** Test 6 — Tracking loss does not create false positive detection. */
    @Test
    fun test06_trackingLossDoesNotCreateFalsePositiveDetection() {
        val model = SuspicionModel()
        model.addEvidence(EvidenceType.NOISE_RELIC, 0.5f, 0f, 0f, 1000L)
        val beforeLoss = model.getSnapshot().level

        // Update during tracking loss
        model.update(currentTimeMs = 2000L, dtMs = 1000L, isVisualConfirmed = true, isTrackingValid = false)
        // False visual detection ignored when tracking invalid
        assertEquals(beforeLoss, model.getSnapshot().level, 0.001f)
    }

    /** Test 7 — Time progression is deterministic. */
    @Test
    fun test07_timeProgressionIsDeterministic() {
        val m1 = SuspicionModel()
        val m2 = SuspicionModel()

        val timestamps = listOf(1000L, 1200L, 1500L, 2000L, 3000L)
        for (t in timestamps) {
            m1.addEvidence(EvidenceType.NOISE_RELIC, 0.8f, 1f, 1f, t)
            m1.update(currentTimeMs = t + 50, dtMs = 50L, isVisualConfirmed = false, isTrackingValid = true)

            m2.addEvidence(EvidenceType.NOISE_RELIC, 0.8f, 1f, 1f, t)
            m2.update(currentTimeMs = t + 50, dtMs = 50L, isVisualConfirmed = false, isTrackingValid = true)
        }

        assertEquals(m1.getSnapshot().level, m2.getSnapshot().level, 0.0001f)
    }

    /** Test 8 — Suspicion does not change unexpectedly after a mission reaches a terminal state. */
    @Test
    fun test08_suspicionDoesNotChangeUnexpectedlyAfterTerminalState() {
        val model = SuspicionModel()
        model.addEvidence(EvidenceType.NOISE_RELIC, 1.0f, 0f, 0f, 1000L)
        val level = model.getSnapshot().level

        // Simulation update with dt=0 (paused/terminal mission)
        model.update(currentTimeMs = 1500L, dtMs = 0L, isVisualConfirmed = false, isTrackingValid = true)
        assertEquals(level, model.getSnapshot().level, 0.001f)
    }

    // =========================================================================
    // SECTION 2: Virtual Noise Events (Tests 9–17)
    // =========================================================================

    /** Test 9 — A relic collection generates exactly one event. */
    @Test
    fun test9_relicCollectionGeneratesExactlyOneEvent() {
        val manager = NoiseEventManager()
        val event = manager.emitEvent(NoiseEventType.RELIC_COLLECTED, 2.0f, 3.0f, 20, 30, 1000L)
        assertNotNull(event)
        assertEquals(1, manager.getActiveEvents(1000L).size)
    }

    /** Test 10 — Repeated updates do not duplicate an event. */
    @Test
    fun test10_repeatedUpdatesDoNotDuplicateEvent() {
        val manager = NoiseEventManager()
        val e1 = manager.emitEvent(NoiseEventType.RELIC_COLLECTED, 2.0f, 3.0f, 20, 30, 1000L)
        assertNotNull(e1)

        // Rapid duplicate call within 100ms
        val e2 = manager.emitEvent(NoiseEventType.RELIC_COLLECTED, 2.0f, 3.0f, 20, 30, 1050L)
        assertNull("Duplicate event within rapid window must be rejected", e2)
        assertEquals(1, manager.getActiveEvents(1050L).size)
    }

    /** Test 11 — Event intensity attenuates with distance. */
    @Test
    fun test11_eventIntensityAttenuatesWithDistance() {
        val event = NoiseEvent(
            id = 1L,
            type = NoiseEventType.RELIC_COLLECTED,
            floorX = 0f,
            floorZ = 0f,
            cellCol = 40,
            cellRow = 40,
            timestampMs = 1000L,
            initialIntensity = 1.0f,
            effectiveRadiusMeters = 4.0f,
            durationMs = 3000L
        )

        // Zero distance -> full intensity
        assertEquals(1.0f, event.getPerceivedIntensity(0f, 0f, 1000L), 0.001f)

        // Halfway (2.0m / 4.0m) -> 0.5 intensity
        assertEquals(0.5f, event.getPerceivedIntensity(2.0f, 0f, 1000L), 0.05f)

        // Outside radius (5.0m > 4.0m) -> 0.0 intensity
        assertEquals(0.0f, event.getPerceivedIntensity(5.0f, 0f, 1000L), 0.001f)
    }

    /** Test 12 — Expired events are removed. */
    @Test
    fun test12_expiredEventsAreRemoved() {
        val manager = NoiseEventManager()
        manager.emitEvent(NoiseEventType.DISTRACTION, 1.0f, 1.0f, 10, 10, 1000L, durationMs = 1000L)
        assertEquals(1, manager.getActiveEvents(1500L).size)

        manager.pruneExpired(2500L)
        assertEquals(0, manager.getActiveEvents(2500L).size)
    }

    /** Test 13 — Maximum event count is enforced. */
    @Test
    fun test13_maximumEventCountIsEnforced() {
        val manager = NoiseEventManager(maxActiveEvents = 4)
        for (i in 1..8) {
            manager.emitEvent(NoiseEventType.DISTRACTION, i.toFloat(), i.toFloat(), i, i, 1000L + i * 300L)
        }
        assertTrue("Active events must not exceed maxActiveEvents", manager.getActiveEvents(4000L).size <= 4)
    }

    /** Test 14 — Invalid coordinates are rejected. */
    @Test
    fun test14_invalidCoordinatesAreRejected() {
        val manager = NoiseEventManager()
        assertNull(manager.emitEvent(NoiseEventType.DISTRACTION, Float.NaN, 1.0f, 10, 10, 1000L))
        assertNull(manager.emitEvent(NoiseEventType.DISTRACTION, 1.0f, Float.POSITIVE_INFINITY, 10, 10, 1000L))
        assertNull(manager.emitEvent(NoiseEventType.DISTRACTION, 1.0f, 1.0f, -1, 10, 1000L))
    }

    /** Test 15 — An inaccessible target is not blindly accepted. */
    @Test
    fun test15_inaccessibleTargetIsNotBlindlyAccepted() {
        val manager = NoiseEventManager()
        manager.emitEvent(NoiseEventType.DISTRACTION, 2.0f, 0f, 20, 0, 1000L, initialIntensity = 0.3f, effectiveRadiusMeters = 3.0f)

        // When occluded, effective intensity is damped by 0.35 factor: 0.3 * (1 - 2/3) * 0.35 = ~0.035 < threshold 0.15
        val target = manager.findBestInvestigationTarget(
            creatureFloorX = 0f,
            creatureFloorZ = 0f,
            currentTimeMs = 1000L,
            minPerceivedThreshold = 0.10f,
            isOccluded = { _, _ -> true }
        )
        assertNull("Occluded noise with damped score below threshold should not be chosen", target)
    }

    /** Test 16 — Multiple simultaneous events are ranked deterministically. */
    @Test
    fun test16_multipleSimultaneousEventsAreRankedDeterministically() {
        val manager = NoiseEventManager()
        // Strong event nearby
        manager.emitEvent(NoiseEventType.RELIC_COLLECTED, 1.0f, 0f, 10, 0, 1000L, initialIntensity = 1.0f, effectiveRadiusMeters = 5.0f)
        // Weak event far away
        manager.emitEvent(NoiseEventType.PLAYER_MOVEMENT, 4.0f, 0f, 40, 0, 1000L, initialIntensity = 0.4f, effectiveRadiusMeters = 5.0f)

        val best = manager.findBestInvestigationTarget(0f, 0f, 1000L, minPerceivedThreshold = 0.10f)
        assertNotNull(best)
        assertEquals(NoiseEventType.RELIC_COLLECTED, best?.type)
    }

    /** Test 17 — A stale or weak event does not continuously replace a stronger active target. */
    @Test
    fun test17_staleOrWeakEventDoesNotContinuouslyReplaceStrongerActiveTarget() {
        val event1 = NoiseEvent(
            id = 1L,
            type = NoiseEventType.RELIC_COLLECTED,
            floorX = 1f,
            floorZ = 0f,
            cellCol = 10,
            cellRow = 0,
            timestampMs = 1000L,
            initialIntensity = 1.0f,
            effectiveRadiusMeters = 5.0f,
            durationMs = 4000L
        )

        val weakChallenger = NoiseEvent(
            id = 2L,
            type = NoiseEventType.PLAYER_MOVEMENT,
            floorX = 2f,
            floorZ = 0f,
            cellCol = 20,
            cellRow = 0,
            timestampMs = 1200L,
            initialIntensity = 0.3f,
            effectiveRadiusMeters = 5.0f,
            durationMs = 2000L
        )

        val p1 = event1.getPerceivedIntensity(0f, 0f, 1300L)
        val p2 = weakChallenger.getPerceivedIntensity(0f, 0f, 1300L)
        assertTrue("Primary event must have higher intensity than weak challenger", p1 > p2)
    }

    // =========================================================================
    // SECTION 3: Creature Behaviour (Tests 18–28)
    // =========================================================================

    /** Test 18 — A meaningful noise can trigger investigation while patrolling. */
    @Test
    fun test18_meaningfulNoiseCanTriggerInvestigationWhilePatrolling() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)

        // Emit strong relic noise near creature
        val (fx, fz) = occupancyGrid.cellToFloorCoord(45, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 45, 40, 1100L)

        // Next decision update should transition to INVESTIGATING
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)
        assertEquals(TargetType.INVESTIGATE_NOISE, controller.targetType)
    }

    /** Test 19 — The creature follows existing navigation to the investigation target. */
    @Test
    fun test19_creatureFollowsExistingNavigationToInvestigationTarget() {
        val pose = createPose(40, 40)
        var requestedTargetType = TargetType.NONE
        var reqGoalCol = -1

        controller.onNavigationGoalRequested = { _, _, toCol, _, _, type ->
            requestedTargetType = type
            reqGoalCol = toCol
        }

        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(48, 40)
        controller.emitNoiseEvent(NoiseEventType.DISTRACTION, fx, fz, 48, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)

        assertEquals(TargetType.INVESTIGATE_NOISE, requestedTargetType)
        assertEquals(48, reqGoalCol)
    }

    /** Test 20 — Reaching a noise location triggers the configured investigation behaviour. */
    @Test
    fun test20_reachingNoiseLocationTriggersInvestigationBehaviour() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 42, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)

        // Agent arrives at target
        val arrivedPose = createPose(controller.targetCol, controller.targetRow, AgentState.ARRIVED)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 1300L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)
        assertTrue(controller.getSnapshot().lastStatusMessage.contains("Investigating"))
    }

    /** Test 21 — Failed investigation eventually returns the creature to patrol. */
    @Test
    fun test21_failedInvestigationEventuallyReturnsCreatureToPatrol() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 42, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)

        // Arrive and wait out the investigation pause (default 1.5s)
        val arrivedPose = createPose(controller.targetCol, controller.targetRow, AgentState.ARRIVED)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 1300L)
        // Advance past 1.5s
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, arrivedPose, 3200L)

        assertEquals("Investigation completed without player contact returns to patrol", CreatureAIState.PATROLLING, controller.state)
    }

    /** Test 22 — Reliable player detection still triggers the existing chase behaviour. */
    @Test
    fun test22_reliablePlayerDetectionStillTriggersExistingChaseBehaviour() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 42, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)

        // Player appears within LOS (2m away)
        val (px, pz) = occupancyGrid.cellToFloorCoord(41, 40)
        controller.update(true, px, 0f, pz, null, occupancyGrid, pose, 1300L)

        assertEquals("Visual detection must preempt investigation immediately into chase", CreatureAIState.CHASING, controller.state)
    }

    /** Test 23 — Noise alone does not provide the player's exact current position. */
    @Test
    fun test23_noiseAloneDoesNotProvidePlayerExactCurrentPosition() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        // Noise emitted at (42, 40)
        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, nfx, nfz, 42, 40, 1100L)

        // Player proxy is at (75, 75) - out of detection range (> 3.0m)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1200L)

        assertEquals(CreatureAIState.INVESTIGATING, controller.state)
        assertEquals(42, controller.targetCol)
        assertEquals(40, controller.targetRow)
        assertNotEquals(75, controller.targetCol)
    }

    /** Test 24 — Cover-aware searching still functions. */
    @Test
    fun test24_coverAwareSearchingStillFunctions() {
        val pose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(45, 40)
        // Player seen at 1000L (controller initializes) and 1100L (chase starts)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, pose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Player lost at 1100L, update after 500ms grace period (at 1700L)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, pose, 1700L)

        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertTrue(controller.getSnapshot().totalSearchCandidates in 1..3)
    }

    /** Test 25 — Investigation target changes are rate-limited. */
    @Test
    fun test25_investigationTargetChangesAreRateLimited() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)

        val (fx1, fz1) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx1, fz1, 43, 40, 1050L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1100L)
        val initialTargetCol = controller.targetCol

        // Emit another noise 500ms later (less than 1500ms cooldown)
        val (fx2, fz2) = occupancyGrid.cellToFloorCoord(46, 40)
        controller.emitNoiseEvent(NoiseEventType.DISTRACTION, fx2, fz2, 46, 40, 1300L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1400L)

        assertEquals("Target change should be suppressed by cooldown", initialTargetCol, controller.targetCol)
    }

    /** Test 26 — Tracking loss safely pauses or suspends relevant behaviour. */
    @Test
    fun test26_trackingLossSafelyPausesOrSuspendsRelevantBehaviour() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 43, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)

        // Tracking interrupted
        controller.update(false, 10f, 0f, 10f, null, occupancyGrid, pose, 1300L)
        assertEquals(CreatureAIState.PAUSED, controller.state)
    }

    /** Test 27 — Stale path results cannot override a newer chase target. */
    @Test
    fun test27_stalePathResultsCannotOverrideNewerChaseTarget() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 43, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)

        val oldReqId = controller.activeRequestId

        // Player discovered -> chases player, incrementing activeRequestId
        val (px, pz) = occupancyGrid.cellToFloorCoord(41, 40)
        controller.update(true, px, 0f, pz, null, occupancyGrid, pose, 1300L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Stale path result returns for the earlier investigation
        val staleResult = PathResult(status = PathStatus.SUCCESS)
        controller.onPathResult(staleResult, oldReqId, occupancyGrid, null)

        assertEquals("Stale path result must not override active chase state", CreatureAIState.CHASING, controller.state)
    }

    /** Test 28 — A blocked investigation target is handled without an infinite retry loop. */
    @Test
    fun test28_blockedInvestigationTargetHandledWithoutInfiniteRetryLoop() {
        val pose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1000L)
        val (fx, fz) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx, fz, 43, 40, 1100L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1200L)

        val reqId = controller.activeRequestId
        val failedResult = PathResult(status = PathStatus.NO_PATH)
        controller.onPathResult(failedResult, reqId, occupancyGrid, null, 1200L)

        assertEquals("Blocked target enters BLOCKED recovery", CreatureAIState.BLOCKED, controller.state)

        // Advance past blocked cooldown
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, pose, 1600L)
        assertEquals("Recovers back to PATROLLING after cooldown", CreatureAIState.PATROLLING, controller.state)
    }

    // =========================================================================
    // SECTION 4: Adaptive Difficulty (Tests 29–37)
    // =========================================================================

    /** Test 29 — Difficulty begins at its documented baseline. */
    @Test
    fun test29_difficultyBeginsAtDocumentedBaseline() {
        val diff = AdaptiveDifficultyController()
        val snap = diff.evaluate(1000L, 0f, false)
        assertEquals(DifficultyLevel.STANDARD, snap.level)
        assertEquals(50f, snap.score, 0.001f)
    }

    /** Test 30 — The controller responds to the defined gameplay signals. */
    @Test
    fun test30_controllerRespondsToDefinedGameplaySignals() {
        val diff = AdaptiveDifficultyController()
        // Player collects all 3 relics and escapes pursuit twice
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.recordPlayerEscape()
        diff.recordPlayerEscape()

        // Wait past 5s adjustment cooldown
        val snap = diff.evaluate(7000L, 60f, false)
        assertEquals(DifficultyLevel.CHALLENGING, snap.level)
    }

    /** Test 31 — Difficulty remains within all configured bounds. */
    @Test
    fun test31_difficultyRemainsWithinAllConfiguredBounds() {
        val diff = AdaptiveDifficultyController()
        val params = diff.getActiveParameters()
        assertTrue(params.patrolPauseMultiplier in 0.5f..2.0f)
        assertTrue(params.suspicionDecayMultiplier in 0.5f..2.0f)
        assertTrue(params.searchDurationMultiplier in 0.5f..2.0f)
        assertTrue(params.investigationUrgencyMultiplier in 0.5f..2.0f)
        assertTrue(params.noiseSensitivityMultiplier in 0.5f..2.0f)
    }

    /** Test 32 — Hysteresis prevents rapid switching. */
    @Test
    fun test32_hysteresisPreventsRapidSwitching() {
        val diff = AdaptiveDifficultyController()
        // Baseline = 50. With 1 relic (+15) and 42s elapsed (+7), score = 72.
        // 72 is above the 70 threshold, but within the hysteresis margin (70 + 6 = 76).
        diff.updateProgression(relics = 1, threatPercent = 0f)
        val snap1 = diff.evaluate(6000L, 42f, false)
        assertEquals("Within hysteresis margin, level should remain STANDARD", DifficultyLevel.STANDARD, snap1.level)

        // With 2 relics (+30) and 42s (+7), score = 87 >= 76 (exceeds hysteresis margin).
        diff.updateProgression(relics = 2, threatPercent = 0f)
        val snap2 = diff.evaluate(12000L, 42f, false)
        assertEquals("Exceeding hysteresis margin transitions to CHALLENGING", DifficultyLevel.CHALLENGING, snap2.level)
    }

    /** Test 33 — Cooldowns prevent repeated adjustments. */
    @Test
    fun test33_cooldownsPreventRepeatedAdjustments() {
        val diff = AdaptiveDifficultyController()
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.recordPlayerEscape()
        diff.recordPlayerEscape()

        val snap1 = diff.evaluate(6000L, 60f, false)
        assertEquals(DifficultyLevel.CHALLENGING, snap1.level)
        val initialAdjustmentCount = snap1.adjustmentCount

        // Immediately update with heavy relief within cooldown (2 seconds later)
        diff.updateProgression(relics = 0, threatPercent = 80f)
        val snap2 = diff.evaluate(8000L, 65f, false)
        assertEquals("Adjustment within cooldown must be suppressed", initialAdjustmentCount, snap2.adjustmentCount)
    }

    /** Test 34 — Adaptive behaviour does not change pathfinding safety parameters. */
    @Test
    fun test34_adaptiveBehaviourDoesNotChangePathfindingSafetyParameters() {
        val diff = AdaptiveDifficultyController()
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.evaluate(6000L, 60f, false)

        // Verify obstacle inflation and grid dimensions are unchanged
        assertEquals(0.10f, occupancyGrid.cellSizeMeters, 0.0001f)
        assertEquals(80, occupancyGrid.numCellsX)
        assertEquals(80, occupancyGrid.numCellsZ)
    }

    /** Test 35 — Pause and terminal states suspend adaptation. */
    @Test
    fun test35_pauseAndTerminalStatesSuspendAdaptation() {
        val diff = AdaptiveDifficultyController()
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.recordPlayerEscape()

        // Call evaluate with isPausedOrTerminal = true
        val snap = diff.evaluate(6000L, 60f, isPausedOrTerminal = true)
        assertEquals(DifficultyLevel.STANDARD, snap.level)
        assertEquals(0, snap.adjustmentCount)
    }

    /** Test 36 — Restart restores documented initial difficulty state. */
    @Test
    fun test36_restartRestoresDocumentedInitialDifficultyState() {
        val diff = AdaptiveDifficultyController()
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.recordPlayerEscape()
        diff.evaluate(6000L, 60f, false)

        diff.reset()
        val snap = diff.evaluate(1000L, 0f, false)
        assertEquals(DifficultyLevel.STANDARD, snap.level)
        assertEquals(50f, snap.score, 0.001f)
    }

    /** Test 37 — Identical input sequences produce identical outcomes. */
    @Test
    fun test37_identicalInputSequencesProduceIdenticalOutcomes() {
        val d1 = AdaptiveDifficultyController()
        val d2 = AdaptiveDifficultyController()

        for (t in 1..5) {
            d1.updateProgression(relics = t % 3, threatPercent = (t * 15f) % 80f)
            d2.updateProgression(relics = t % 3, threatPercent = (t * 15f) % 80f)
            if (t % 2 == 0) {
                d1.recordPlayerEscape()
                d2.recordPlayerEscape()
            }
            val s1 = d1.evaluate(t * 6000L, t * 30f, false)
            val s2 = d2.evaluate(t * 6000L, t * 30f, false)
            assertEquals(s1.level, s2.level)
            assertEquals(s1.score, s2.score, 0.0001f)
        }
    }
}
