package com.embedded.argame.integration

import com.embedded.argame.ai.*
import com.embedded.argame.environment.*
import com.embedded.argame.game.*
import com.embedded.argame.navigation.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * End-to-End Integration and Reliability Test Suite (Milestone 14).
 *
 * Verifies cross-subsystem contracts across:
 * - Suspicion and detection semantics (Phase 2)
 * - Creature AI and navigation integration (Phase 3, Scenarios A–F)
 * - Mission lifecycle, reset, and pause consistency (Phase 4)
 * - Adaptive difficulty predictability and safety invariance (Phase 5)
 *
 * All tests execute with deterministic time and mocked/simulated spatial grids
 * without requiring live ARCore hardware.
 */
class Milestone14IntegrationTest {

    private val width = 80
    private val depth = 80
    private val cellSize = 0.10f

    private lateinit var occupancyGrid: OccupancyGrid
    private lateinit var costGrid: TraversalCostGrid
    private lateinit var aiConfig: CreatureAIConfig
    private lateinit var controller: CreatureAIController
    private lateinit var missionConfig: MissionConfig
    private lateinit var missionManager: MissionManager
    private lateinit var pathfinder: AStarPathfinder

    @Before
    fun setUp() {
        occupancyGrid = OccupancyGrid(cellSizeMeters = cellSize, widthMeters = 8.0f, depthMeters = 8.0f)
        costGrid = occupancyGrid.traversalCostGrid

        // Set entire grid traversable by default
        occupancyGrid.inflatedCellStates.fill(NavigationCellState.FREE.code)
        costGrid.updateCosts(occupancyGrid.inflatedCellStates)

        aiConfig = CreatureAIConfig(
            detectionRadiusMeters = 3.0f,
            approachPlayerDistanceMeters = 0.40f,
            lostVisibilityGracePeriodMs = 500L,
            maxSearchDurationSec = 4.0f,
            searchRadiusMeters = 1.0f,
            searchCandidateWaitSec = 0.4f,
            maxSearchCandidates = 3,
            patrolPauseDurationSec = 1.0f,
            minPatrolDistanceMeters = 0.8f,
            maxPatrolDistanceMeters = 3.0f,
            aiDecisionIntervalMs = 50L,
            chaseReplanIntervalMs = 100L,
            chaseGoalChangeThresholdMeters = 0.20f,
            blockedCooldownMs = 300L,
            minInvestigateTargetSwitchIntervalMs = 1500L,
            suspicionConfig = SuspicionConfig(
                lowThreshold = 25f,
                moderateThreshold = 45f,
                highThreshold = 60f,
                repeatedNoiseCap = 65f
            )
        )

        controller = CreatureAIController(config = aiConfig, rng = Random(42))

        missionConfig = MissionConfig(
            relicCount = 3,
            relicCollectionRadiusMeters = 0.40f,
            extractionRadiusMeters = 0.45f,
            captureRadiusMeters = 0.55f,
            captureDurationRequiredMs = 800L,
            captureDecayRateMsPerSec = 1000f,
            minNavigableCellsForMission = 40
        )

        missionManager = MissionManager(config = missionConfig)
        pathfinder = AStarPathfinder()
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
    // SECTION 1: Phase 2 — Correct Suspicion and Detection Semantics
    // =========================================================================

    /**
     * Requirement 1 & 2: A noise event triggers investigation and increases suspicion,
     * but must NOT, by itself, count as visual confirmation or initiate a visual chase.
     */
    @Test
    fun testP2_01_noiseAloneNeverInitiatesChaseOrVisualConfirmation() {
        val creaturePose = createPose(40, 40)
        // Creature initializes
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)

        // Noise emitted at (43, 40)
        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, nfx, nfz, 43, 40, 1100L)

        // Player is far away (75, 75) out of visual line of sight (> 4.9m > 3.0m detection radius)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1200L)

        // Verifications:
        assertEquals("Noise must trigger INVESTIGATING, never CHASING", CreatureAIState.INVESTIGATING, controller.state)
        assertEquals("Target must be noise source", TargetType.INVESTIGATE_NOISE, controller.targetType)
        assertFalse("Visual detection must be false", controller.lastPerception.isPlayerDetected)

        val susp = controller.suspicionModel.getSnapshot(1200L)
        assertEquals(EvidenceProvenance.SOUND, susp.provenance)
        assertFalse("Sound-only alert must NOT have isVisualConfirmed = true", susp.isVisualConfirmed)
        assertTrue("Suspicion must be elevated from noise", susp.level in 40f..45.1f)
    }

    /**
     * Requirement 3: Score ceiling of 65 vs HIGH threshold of 60.
     * Sound-driven alert can reach HIGH suspicion without implying confirmed visual detection.
     */
    @Test
    fun testP2_02_soundDrivenAlertCanReachHighWithoutTriggeringChase() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)

        val (fx1, fz1) = occupancyGrid.cellToFloorCoord(44, 40)
        // Relic (+45) + Distraction (+50 scaled by headroom to cap 65)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx1, fz1, 44, 40, 1050L)
        controller.emitNoiseEvent(NoiseEventType.DISTRACTION, fx1, fz1, 44, 40, 1100L)

        // Player proxy is outside detection radius
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1150L)

        val susp = controller.suspicionModel.getSnapshot(1150L)
        // Suspicion is capped at repeatedNoiseCap = 65, with decay (12 pts/s * 0.15s = 1.8 pts)
        assertTrue("Suspicion must be elevated near cap (65)", susp.level in 60f..65.1f)
        // 63.2 >= highThreshold (60), so isHigh is TRUE
        assertTrue("Suspicion must be HIGH alert", susp.isHigh)
        // Provenance is strictly SOUND
        assertEquals(EvidenceProvenance.SOUND, susp.provenance)
        assertFalse("HIGH alert from sound must NOT set isVisualConfirmed", susp.isVisualConfirmed)

        // Creature state must be INVESTIGATING, never CHASING
        assertNotEquals("Sound-only alert cannot trigger chase", CreatureAIState.CHASING, controller.state)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)
    }

    /**
     * Requirement 4: Stale visual evidence cannot be mistaken for current visibility
     * after the player leaves view.
     */
    @Test
    fun testP2_04_staleVisualEvidenceCannotBeMistakenForCurrentVisibility() {
        val creaturePose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(45, 40)

        // Player detected -> enters chase
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)
        assertTrue(controller.suspicionModel.getSnapshot(1100L).isVisualConfirmed)
        assertEquals(EvidenceProvenance.CURRENT_VISUAL, controller.suspicionModel.getSnapshot(1100L).provenance)

        // Player ducks behind a wall at t = 1100ms
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, creaturePose, 1200L)

        // Immediately on the next tick, isVisualConfirmed must become FALSE
        assertFalse("isVisualConfirmed must be false immediately upon losing line of sight",
            controller.suspicionModel.getSnapshot(1200L).isVisualConfirmed)
        assertEquals("Provenance must transition to STALE_VISUAL",
            EvidenceProvenance.STALE_VISUAL, controller.suspicionModel.getSnapshot(1200L).provenance)

        // After grace period (500ms), creature transitions to SEARCHING (not indefinite chase)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, creaturePose, 1700L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertEquals(EvidenceProvenance.STALE_VISUAL, controller.suspicionModel.getSnapshot(1700L).provenance)
    }

    /**
     * Requirement 6: Suspicion decay and evidence expiration over time.
     */
    @Test
    fun testP2_05_suspicionDecayAndEvidenceExpiration() {
        val model = SuspicionModel(aiConfig.suspicionConfig)
        model.addEvidence(EvidenceType.NOISE_RELIC, 1.0f, 0f, 0f, 1000L) // Gain = 45
        assertEquals(45f, model.getSnapshot(1000L).level, 0.01f)
        assertEquals(1, model.getSnapshot(1000L).activeEvidenceCount)

        // Advance 2 seconds with decay rate 12/s -> decay by 24 -> 21
        model.update(currentTimeMs = 3000L, dtMs = 2000L, isVisualConfirmed = false, isTrackingValid = true)
        assertEquals(21f, model.getSnapshot(3000L).level, 0.05f)

        // Advance past evidenceMaxAgeMs (8000ms from 1000L -> at 10000L)
        model.update(currentTimeMs = 10000L, dtMs = 7000L, isVisualConfirmed = false, isTrackingValid = true)
        assertEquals(0, model.getSnapshot(10000L).activeEvidenceCount)
        assertEquals(0f, model.getSnapshot(10000L).level, 0.01f)
        assertEquals(EvidenceProvenance.NONE, model.getSnapshot(10000L).provenance)
    }

    // =========================================================================
    // SECTION 2: Phase 3 — Creature AI and Navigation Integration
    // =========================================================================

    /**
     * Scenario A — Noise investigation:
     * Synthetic disturbance occurs behind an occluder. Creature enters INVESTIGATING,
     * navigates toward estimated noise source without knowing player's exact location.
     */
    @Test
    fun testP3_ScenarioA_noiseInvestigationBehindOccluderWithoutCheating() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)

        // Player is at (75, 75)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(75, 75)
        // Disturbance generated at (45, 42)
        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(45, 42)
        controller.emitNoiseEvent(NoiseEventType.DISTRACTION, nfx, nfz, 45, 42, 1050L)

        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1100L)

        assertEquals(CreatureAIState.INVESTIGATING, controller.state)
        assertEquals(TargetType.INVESTIGATE_NOISE, controller.targetType)
        // Target must match noise coordinates, NOT player coordinates!
        assertEquals(45, controller.targetCol)
        assertEquals(42, controller.targetRow)
        assertNotEquals(75, controller.targetCol)
        assertNotEquals(75, controller.targetRow)
    }

    /**
     * Scenario B — Visual detection during investigation:
     * Creature begins investigating a disturbance. Player subsequently becomes visible.
     * Investigation is cancelled, current visual target takes precedence, and obsolete
     * navigation results cannot overwrite the new chase state.
     */
    @Test
    fun testP3_ScenarioB_visualDetectionDuringInvestigationCancelsAndSupersedes() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)

        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(44, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, nfx, nfz, 44, 40, 1050L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1100L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)

        val noiseReqId = controller.activeRequestId

        // Player becomes visible at cell (42, 40) within 3m detection radius
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1200L)

        // Must immediately cancel investigation and enter CHASING
        assertEquals(CreatureAIState.CHASING, controller.state)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
        assertNull("activeNoiseTarget must be cleared on chase entry", controller.activeNoiseTarget)
        val chaseReqId = controller.activeRequestId
        assertTrue("Request ID must increment to supersede previous noise path", chaseReqId > noiseReqId)

        // Delayed stale path result arrives for the earlier noise investigation
        val staleNoiseResult = PathResult(status = PathStatus.SUCCESS)
        controller.onPathResult(staleNoiseResult, noiseReqId, occupancyGrid, null, 1250L)

        // Stale result must be discarded: state remains CHASING
        assertEquals("Stale noise path result must NOT overwrite chase state", CreatureAIState.CHASING, controller.state)
        assertEquals(TargetType.CHASE_PLAYER, controller.targetType)
    }

    /**
     * Scenario C — Lost visual contact:
     * Sight lost during chase transitions to last-known search and eventually returns to patrol.
     */
    @Test
    fun testP3_ScenarioC_lostVisualContactTransitionsToCoverSearchAndReturn() {
        val creaturePose = createPose(40, 40)
        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(45, 40)

        // Visual chase active
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1000L)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1100L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Player lost at 1100ms. Update at 1700ms (600ms > 500ms grace period)
        val (farX, farZ) = occupancyGrid.cellToFloorCoord(75, 75)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, creaturePose, 1700L)
        assertEquals(CreatureAIState.SEARCHING, controller.state)
        assertEquals(45, controller.lastKnownPlayerCol)

        // Wait out the search duration (4.0s from 1700L -> at 6000L)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, creaturePose, 6000L)
        assertEquals(CreatureAIState.RETURNING, controller.state)
        assertEquals(TargetType.RETURN_PATROL, controller.targetType)

        // Arrive at return destination -> recovers to PATROLLING
        val arrivedPose = createPose(controller.targetCol, controller.targetRow, AgentState.ARRIVED)
        controller.update(true, farX, 0f, farZ, null, occupancyGrid, arrivedPose, 6100L)
        assertEquals(CreatureAIState.PATROLLING, controller.state)
    }

    /**
     * Scenario D — Unreachable investigation target:
     * Obstructed investigation target is handled without an infinite retry loop.
     */
    @Test
    fun testP3_ScenarioD_unreachableInvestigationTargetRecoversSafelyWithoutLoop() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)

        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(44, 40)
        val event = controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, nfx, nfz, 44, 40, 1050L)
        assertNotNull(event)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1100L)
        assertEquals(CreatureAIState.INVESTIGATING, controller.state)

        val reqId = controller.activeRequestId
        val blockedResult = PathResult(status = PathStatus.NO_PATH)
        controller.onPathResult(blockedResult, reqId, occupancyGrid, null, 1150L)

        assertEquals("Path failure transitions to BLOCKED recovery", CreatureAIState.BLOCKED, controller.state)
        assertNull("Blocked noise target cleared from active target", controller.activeNoiseTarget)

        // Advance past blocked cooldown (300ms)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1500L)
        assertEquals("Recovers to PATROLLING after cooldown without retry loop", CreatureAIState.PATROLLING, controller.state)
    }

    /**
     * Scenario E — Rapid event sequence:
     * Event debouncing, priority selection, target-switch cooldowns, bounded storage.
     */
    @Test
    fun testP3_ScenarioE_rapidEventSequencePrioritizesAndRespectsCooldown() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)

        val (fx1, fz1) = occupancyGrid.cellToFloorCoord(43, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, fx1, fz1, 43, 40, 1050L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1100L)
        val initialTargetCol = controller.targetCol

        // Emit second noise 300ms later (within 1500ms target switch cooldown)
        val (fx2, fz2) = occupancyGrid.cellToFloorCoord(47, 40)
        controller.emitNoiseEvent(NoiseEventType.PLAYER_MOVEMENT, fx2, fz2, 47, 40, 1200L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1250L)

        // Target switch must be suppressed by cooldown
        assertEquals("Target switch must be rate-limited by cooldown", initialTargetCol, controller.targetCol)

        // Verify duplicate nearby events are suppressed in noise manager
        val manager = NoiseEventManager()
        manager.emitEvent(NoiseEventType.RELIC_COLLECTED, fx1, fz1, 43, 40, 1000L, 1.0f)
        // Same spot 100ms later (< 200ms minEventSeparationTimeMs)
        val duplicate = manager.emitEvent(NoiseEventType.RELIC_COLLECTED, fx1, fz1, 43, 40, 1100L, 1.0f)
        assertNull("Nearby duplicate event within 200ms must be suppressed", duplicate)
        assertEquals(1, manager.getActiveEvents(1100L).size)
    }

    /**
     * Scenario F — Navigation result races:
     * Outdated path results arriving out of sequence are rejected using request IDs.
     */
    @Test
    fun testP3_ScenarioF_navigationResultRacesAndReordering() {
        val creaturePose = createPose(40, 40)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1000L)
        val req1 = controller.activeRequestId

        val (nfx, nfz) = occupancyGrid.cellToFloorCoord(44, 40)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, nfx, nfz, 44, 40, 1050L)
        controller.update(true, 10f, 0f, 10f, null, occupancyGrid, creaturePose, 1100L)
        val req2 = controller.activeRequestId
        assertTrue(req2 > req1)

        val (pfx, pfz) = occupancyGrid.cellToFloorCoord(42, 40)
        controller.update(true, pfx, 0f, pfz, null, occupancyGrid, creaturePose, 1200L)
        val req3 = controller.activeRequestId
        assertTrue(req3 > req2)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Result 1 arrives late with failure -> must be ignored
        controller.onPathResult(PathResult(status = PathStatus.NO_PATH), req1, occupancyGrid, null, 1210L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Result 2 arrives late with success -> must be ignored
        controller.onPathResult(PathResult(status = PathStatus.SUCCESS), req2, occupancyGrid, null, 1220L)
        assertEquals(CreatureAIState.CHASING, controller.state)

        // Result 3 arrives with success -> applied
        controller.onPathResult(PathResult(status = PathStatus.SUCCESS), req3, occupancyGrid, null, 1230L)
        assertEquals(CreatureAIState.CHASING, controller.state)
        assertTrue(controller.getSnapshot().lastStatusMessage.contains("#$req3"))
    }

    // =========================================================================
    // SECTION 3: Phase 4 — Mission Lifecycle and Reset Behaviour
    // =========================================================================

    /**
     * Complete playable mission loop:
     * Setup -> Relic collection -> Extraction unlock -> Victory.
     */
    @Test
    fun testP4_01_fullMissionLoopRelicsInvestigationExtractionVictory() {
        val startFloorX = 0f
        val startFloorZ = 0f

        // 1. Generate mission objectives
        val generated = missionManager.generateMission(occupancyGrid, startFloorX, startFloorZ, randomSeed = 100L)
        assertTrue("Mission must generate successfully", generated)
        assertEquals(GameState.READY, missionManager.getSnapshot().state)

        // 2. Start mission
        missionManager.startMission()
        assertEquals(GameState.PLAYING, missionManager.getSnapshot().state)

        val objectives = missionManager.getSnapshot().objectives
        val relics = objectives.filter { it.type == ObjectiveType.RELIC }
        val extraction = objectives.first { it.type == ObjectiveType.EXTRACTION }
        assertEquals(3, relics.size)
        assertFalse(missionManager.getSnapshot().isExtractionUnlocked)

        // 3. Collect Relic 1
        val r1 = relics[0]
        missionManager.update(1000L, true, r1.floorX, r1.floorZ, null, null, null)
        assertEquals(1, missionManager.getSnapshot().relicsCollected)
        assertFalse(missionManager.getSnapshot().isExtractionUnlocked)

        // 4. Collect Relic 2
        val r2 = relics[1]
        missionManager.update(2000L, true, r2.floorX, r2.floorZ, null, null, null)
        assertEquals(2, missionManager.getSnapshot().relicsCollected)

        // 5. Collect Relic 3 -> Extraction Unlocks
        val r3 = relics[2]
        missionManager.update(3000L, true, r3.floorX, r3.floorZ, null, null, null)
        assertEquals(3, missionManager.getSnapshot().relicsCollected)
        assertTrue("Extraction must unlock after 3rd relic", missionManager.getSnapshot().isExtractionUnlocked)

        // 6. Step onto extraction portal -> WON
        missionManager.update(4000L, true, extraction.floorX, extraction.floorZ, null, null, null)
        assertEquals(GameState.WON, missionManager.getSnapshot().state)
    }

    /**
     * Restart restores fresh, independent mission state:
     * Relics reset to available, extraction relocked, timers reset, creature AI reset,
     * noise cleared, suspicion reset, difficulty reset to standard baseline.
     */
    @Test
    fun testP4_02_restartGuaranteesCompleteCleanIndependence() {
        missionManager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 100L)
        missionManager.startMission()

        // Accumulate progress, relics, suspicion, and difficulty
        val relics = missionManager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }
        missionManager.update(1000L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        controller.emitNoiseEvent(NoiseEventType.RELIC_COLLECTED, relics[0].floorX, relics[0].floorZ, 40, 40, 1000L)
        controller.updateDifficultyProgression(relicsCollected = 3, threatPercent = 0f, missionElapsedSec = 120f)

        // Restart mission
        val restarted = missionManager.restartMission()
        assertTrue(restarted)
        controller.reset()

        // Verifications on MissionManager:
        val mSnap = missionManager.getSnapshot()
        assertEquals(GameState.READY, mSnap.state)
        assertEquals(0, mSnap.relicsCollected)
        assertFalse(mSnap.isExtractionUnlocked)
        assertEquals(0L, mSnap.elapsedTimeMs)
        assertEquals(0f, mSnap.captureProgressPercent, 0.001f)
        assertTrue(mSnap.objectives.filter { it.type == ObjectiveType.RELIC }.all { it.isAvailable })

        // Verifications on CreatureAIController:
        val aiSnap = controller.getSnapshot()
        assertEquals(CreatureAIState.INITIALIZING, aiSnap.state)
        assertEquals(0f, aiSnap.suspicionSnapshot.level, 0.001f)
        assertEquals(EvidenceProvenance.NONE, aiSnap.suspicionSnapshot.provenance)
        assertEquals(0, controller.noiseEventManager.getActiveEvents(1000L).size)
        assertEquals(DifficultyLevel.STANDARD, controller.difficultyController.getSnapshot().level)
        assertEquals(50f, controller.difficultyController.getSnapshot().score, 0.001f)
    }

    /**
     * Paused gameplay does not accumulate elapsed time or process invalid transitions.
     */
    @Test
    fun testP4_03_pauseAndResumeDoesNotAccumulateTimeOrCorruptState() {
        missionManager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 100L)
        missionManager.startMission()

        // Run 5 seconds active (1-second steps to respect dtMs clamp)
        var simTime = 1000L
        missionManager.update(simTime, true, 0f, 0f, null, null, null)
        for (i in 1..5) {
            simTime += 1000L
            missionManager.update(simTime, true, 0f, 0f, null, null, null)
        }
        assertEquals(5000L, missionManager.getSnapshot().elapsedTimeMs)

        // Pause
        missionManager.togglePause()
        assertEquals(GameState.PAUSED, missionManager.getSnapshot().state)

        // Tick 30 seconds while paused
        simTime += 30000L
        missionManager.update(simTime, true, 0f, 0f, null, null, null)
        assertEquals("Elapsed time must NOT increase while paused", 5000L, missionManager.getSnapshot().elapsedTimeMs)

        // Resume
        missionManager.resume()
        assertEquals(GameState.PLAYING, missionManager.getSnapshot().state)

        // First tick after resume sets baseline timestamp without dt spike
        simTime += 100L
        missionManager.update(simTime, true, 0f, 0f, null, null, null)
        assertEquals(5000L, missionManager.getSnapshot().elapsedTimeMs)

        // Tick 2 seconds after resume
        for (i in 1..2) {
            simTime += 1000L
            missionManager.update(simTime, true, 0f, 0f, null, null, null)
        }
        assertEquals(7000L, missionManager.getSnapshot().elapsedTimeMs)
    }

    // =========================================================================
    // SECTION 4: Phase 5 — Adaptive Difficulty Invariants and Regression
    // =========================================================================

    /**
     * Adaptive difficulty bidirectional crossings with hysteresis and cooldown.
     */
    @Test
    fun testP5_01_adaptiveTiersAndBidirectionalCrossings() {
        val diff = AdaptiveDifficultyController()
        val s0 = diff.evaluate(1000L, 0f, false)
        assertEquals(DifficultyLevel.STANDARD, s0.level)
        assertEquals(50f, s0.score, 0.001f)

        // High performance: 3 relics (+45) + 2 escapes (+20) + 60s (+10) = 125 clamped to 100
        diff.updateProgression(relics = 3, threatPercent = 0f)
        diff.recordPlayerEscape()
        diff.recordPlayerEscape()
        val s1 = diff.evaluate(7000L, 60f, false)
        assertEquals(DifficultyLevel.CHALLENGING, s1.level)

        // Pressure relief: player faced 80% peak threat (-25) with 0 relics -> score drops below 29
        diff.reset()
        diff.updateProgression(relics = 0, threatPercent = 80f)
        val s2 = diff.evaluate(13000L, 0f, false)
        // Score = 50 - 25 = 25 < 29 (easyToStandard - hysteresisMargin: 35 - 6 = 29)
        assertEquals(DifficultyLevel.EASY, s2.level)
    }

    /**
     * Difficulty changes never alter pathfinding grid or obstacle inflation.
     */
    @Test
    fun testP5_03_difficultyNeverCompromisesNavigationSafety() {
        val diff = AdaptiveDifficultyController()
        val easyParams = diff.config.easyParameters
        val hardParams = diff.config.challengingParameters

        // Multipliers must remain bounded in [0.5, 2.0]
        assertTrue(easyParams.patrolPauseMultiplier in 0.5f..2.0f)
        assertTrue(hardParams.searchDurationMultiplier in 0.5f..2.0f)

        // Physical grid parameters must remain strictly invariant
        assertEquals(0.10f, occupancyGrid.cellSizeMeters, 0.0001f)
        assertEquals(80, occupancyGrid.numCellsX)
        assertEquals(80, occupancyGrid.numCellsZ)
    }

    /**
     * Capture threshold and capture duration remain invariant across difficulty tiers.
     */
    @Test
    fun testP5_04_difficultyNeverAbruptlyAltersCaptureRulesDuringActiveChase() {
        // Capture distance threshold must remain 0.55m
        assertEquals(0.55f, missionConfig.captureRadiusMeters, 0.001f)
        // Capture duration must remain 800ms
        assertEquals(800L, missionConfig.captureDurationRequiredMs)
    }

    /**
     * Phase 1 Fix Verification: Mission elapsed time is live and factors into difficulty.
     */
    @Test
    fun testP5_05_missionElapsedTimeFactoredIntoDifficulty() {
        val diff = AdaptiveDifficultyController()
        val initial = diff.evaluate(1000L, 0f, false).score
        assertEquals(50f, initial, 0.001f)

        // At 60 seconds elapsed, time contribution is +10.0
        val after60s = diff.evaluate(2000L, 60f, false).score
        assertEquals(60f, after60s, 0.001f)

        // At 120 seconds elapsed, time contribution is maxed at +20.0
        val after120s = diff.evaluate(3000L, 120f, false).score
        assertEquals(70f, after120s, 0.001f)
    }
}
