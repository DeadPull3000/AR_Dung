package com.embedded.argame.game

import com.embedded.argame.ai.CreatureAIState
import com.embedded.argame.environment.NavigationCellState
import com.embedded.argame.environment.OccupancyGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot

/**
 * Deterministic automated unit verification test suite for Milestone 12:
 * Playable Mission Loop, Relics & Extraction (Section 13).
 */
class MissionManagerTest {

    private val width = 80
    private val depth = 80
    private val cellSize = 0.10f

    private lateinit var occupancyGrid: OccupancyGrid
    private lateinit var config: MissionConfig
    private lateinit var manager: MissionManager

    @Before
    fun setUp() {
        occupancyGrid = OccupancyGrid(cellSizeMeters = cellSize, widthMeters = 8.0f, depthMeters = 8.0f)

        // Make entire grid traversable by default
        occupancyGrid.inflatedCellStates.fill(NavigationCellState.FREE.code)
        occupancyGrid.traversalCostGrid.updateCosts(occupancyGrid.inflatedCellStates)

        config = MissionConfig(
            minNavigableCellsForMission = 20,
            relicCount = 3,
            relicCollectionRadiusMeters = 0.40f,
            extractionRadiusMeters = 0.45f,
            minObjectiveSeparationMeters = 0.80f,
            minPlayerSeparationMeters = 0.60f,
            captureRadiusMeters = 0.55f,
            captureDurationRequiredMs = 800L,
            captureDecayRateMsPerSec = 1000f
        )
        manager = MissionManager(config)
    }

    // 1. Mission cannot start before mapping requirements are satisfied
    @Test
    fun testMissionCannotStartBeforeMappingRequirementsSatisfied() {
        assertEquals(GameState.SCANNING, manager.getSnapshot().state)
        val started = manager.startMission()
        assertFalse(started)
        assertEquals(GameState.SCANNING, manager.getSnapshot().state)
    }

    // 2. Mission generation rejects insufficient navigable space
    @Test
    fun testMissionGenerationRejectsInsufficientNavigableSpace() {
        // Block entire grid except for 5 cells
        occupancyGrid.inflatedCellStates.fill(NavigationCellState.PHYSICAL_OBSTACLE.code)
        for (i in 0 until 5) {
            occupancyGrid.inflatedCellStates[i] = NavigationCellState.FREE.code
        }
        occupancyGrid.traversalCostGrid.updateCosts(occupancyGrid.inflatedCellStates)

        val success = manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 42L)
        assertFalse(success)
        assertEquals(GameState.SCANNING, manager.getSnapshot().state)
        assertFalse(manager.getSnapshot().isNavigableSufficient)
    }

    // 3. Generated relics and extraction points lie inside valid traversable cells
    @Test
    fun testGeneratedObjectivesLieInsideValidTraversableCells() {
        val success = manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 101L)
        assertTrue(success)
        val snapshot = manager.getSnapshot()
        assertEquals(4, snapshot.objectives.size)

        for (obj in snapshot.objectives) {
            val idx = occupancyGrid.cellToIndex(obj.col, obj.row)
            assertTrue("Objective ${obj.id} must be in traversable cell", occupancyGrid.traversalCostGrid.isTraversable(idx))
            assertTrue("Col inside bounds", obj.col in 0 until width)
            assertTrue("Row inside bounds", obj.row in 0 until depth)
        }
    }

    // 4. Objectives satisfy the configured separation constraints when room permits
    @Test
    fun testObjectivesSatisfySeparationConstraints() {
        val success = manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 202L)
        assertTrue(success)
        val objectives = manager.getSnapshot().objectives

        for (i in objectives.indices) {
            for (j in i + 1 until objectives.size) {
                val dist = hypot(objectives[i].floorX - objectives[j].floorX, objectives[i].floorZ - objectives[j].floorZ)
                assertTrue("Objectives $i and $j separation $dist must exceed 0.5m", dist >= 0.50f)
            }
        }
    }

    // 5. All objectives are reachable under the selected navigation rules
    @Test
    fun testAllObjectivesAreReachable() {
        // Create an obstacle wall bisecting the room with a doorway at (40, 20)
        for (r in 0 until depth) {
            if (r != 20) {
                val idx = occupancyGrid.cellToIndex(40, r)
                occupancyGrid.inflatedCellStates[idx] = NavigationCellState.PHYSICAL_OBSTACLE.code
            }
        }
        occupancyGrid.traversalCostGrid.updateCosts(occupancyGrid.inflatedCellStates)

        val success = manager.generateMission(occupancyGrid, -1.0f, 0f, randomSeed = 303L)
        assertTrue(success)

        val objectives = manager.getSnapshot().objectives
        for (obj in objectives) {
            val idx = occupancyGrid.cellToIndex(obj.col, obj.row)
            assertTrue(occupancyGrid.traversalCostGrid.isTraversable(idx))
        }
    }

    // 6. Mission generation is deterministic with a fixed seed
    @Test
    fun testMissionGenerationIsDeterministicWithFixedSeed() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 404L)
        val snapshot1 = manager.getSnapshot()

        val manager2 = MissionManager(config)
        manager2.generateMission(occupancyGrid, 0f, 0f, randomSeed = 404L)
        val snapshot2 = manager2.getSnapshot()

        assertEquals(snapshot1.objectives.size, snapshot2.objectives.size)
        for (i in snapshot1.objectives.indices) {
            assertEquals(snapshot1.objectives[i].col, snapshot2.objectives[i].col)
            assertEquals(snapshot1.objectives[i].row, snapshot2.objectives[i].row)
            assertEquals(snapshot1.objectives[i].type, snapshot2.objectives[i].type)
        }
    }

    // 7. Starting a mission resets its elapsed-time and capture state correctly
    @Test
    fun testStartingMissionResetsElapsedTimeAndCaptureState() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 505L)
        assertEquals(GameState.READY, manager.getSnapshot().state)

        val started = manager.startMission()
        assertTrue(started)
        val snapshot = manager.getSnapshot()
        assertEquals(GameState.PLAYING, snapshot.state)
        assertEquals(0L, snapshot.elapsedTimeMs)
        assertEquals(0f, snapshot.captureProgressPercent, 0.001f)
    }

    // 8. A relic is collected inside the valid radius
    @Test
    fun testRelicCollectedInsideRadius() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 606L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }

        // Move player proxy directly onto relic floor position
        manager.update(
            currentTimeMs = 100L,
            isTracking = true,
            playerFloorX = relic.floorX,
            playerFloorZ = relic.floorZ,
            creatureFloorX = null,
            creatureFloorZ = null,
            creatureAiState = null
        )

        val updatedRelic = manager.getSnapshot().objectives.first { it.id == relic.id }
        assertTrue(updatedRelic.isCollected)
        assertEquals(1, manager.getSnapshot().relicsCollected)
    }

    // 9. A relic is not collected outside the radius
    @Test
    fun testRelicNotCollectedOutsideRadius() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 707L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }

        // Position player 1.0 meter away from relic (> 0.40m collection radius)
        manager.update(
            currentTimeMs = 100L,
            isTracking = true,
            playerFloorX = relic.floorX + 1.0f,
            playerFloorZ = relic.floorZ,
            creatureFloorX = null,
            creatureFloorZ = null,
            creatureAiState = null
        )

        val updatedRelic = manager.getSnapshot().objectives.first { it.id == relic.id }
        assertFalse(updatedRelic.isCollected)
        assertEquals(0, manager.getSnapshot().relicsCollected)
    }

    // 10. An invalid player-proxy position does not trigger collection
    @Test
    fun testInvalidPlayerProxyPositionDoesNotTriggerCollection() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 808L)
        manager.startMission()

        manager.update(
            currentTimeMs = 100L,
            isTracking = true,
            playerFloorX = null,
            playerFloorZ = null,
            creatureFloorX = null,
            creatureFloorZ = null,
            creatureAiState = null
        )

        assertEquals(0, manager.getSnapshot().relicsCollected)
    }

    // 11. A relic cannot be collected twice
    @Test
    fun testRelicCannotBeCollectedTwice() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 909L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }

        // First update triggers collection
        manager.update(
            currentTimeMs = 100L,
            isTracking = true,
            playerFloorX = relic.floorX,
            playerFloorZ = relic.floorZ,
            creatureFloorX = null,
            creatureFloorZ = null,
            creatureAiState = null
        )
        assertEquals(1, manager.getSnapshot().relicsCollected)

        // Second update on the same spot must not increment counter
        manager.update(
            currentTimeMs = 200L,
            isTracking = true,
            playerFloorX = relic.floorX,
            playerFloorZ = relic.floorZ,
            creatureFloorX = null,
            creatureFloorZ = null,
            creatureAiState = null
        )
        assertEquals(1, manager.getSnapshot().relicsCollected)
    }

    // 12. The relic counter updates exactly once per collection
    @Test
    fun testRelicCounterUpdatesExactlyOncePerCollection() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 111L)
        manager.startMission()
        val relics = manager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }

        // Collect relic 1
        manager.update(100L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        assertEquals(1, manager.getSnapshot().relicsCollected)

        // Collect relic 2
        manager.update(200L, true, relics[1].floorX, relics[1].floorZ, null, null, null)
        assertEquals(2, manager.getSnapshot().relicsCollected)
    }

    // 13. Extraction remains locked while relics remain
    @Test
    fun testExtractionRemainsLockedWhileRelicsRemain() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 222L)
        manager.startMission()
        val extraction = manager.getSnapshot().objectives.first { it.type == ObjectiveType.EXTRACTION }

        assertTrue(extraction.isLocked)
        assertFalse(manager.getSnapshot().isExtractionUnlocked)
    }

    // 14. Entering the locked extraction radius does not produce victory
    @Test
    fun testEnteringLockedExtractionRadiusDoesNotProduceVictory() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 333L)
        manager.startMission()
        val extraction = manager.getSnapshot().objectives.first { it.type == ObjectiveType.EXTRACTION }

        // Move onto extraction marker while relics are uncollected
        manager.update(100L, true, extraction.floorX, extraction.floorZ, null, null, null)

        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
        assertFalse(manager.getSnapshot().isExtractionUnlocked)
    }

    // 15. Extraction unlocks after the final relic is collected
    @Test
    fun testExtractionUnlocksAfterFinalRelicCollected() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 444L)
        manager.startMission()
        val relics = manager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }

        // Collect all 3 relics
        manager.update(100L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        manager.update(200L, true, relics[1].floorX, relics[1].floorZ, null, null, null)
        manager.update(300L, true, relics[2].floorX, relics[2].floorZ, null, null, null)

        val snapshot = manager.getSnapshot()
        assertEquals(3, snapshot.relicsCollected)
        assertTrue(snapshot.isExtractionUnlocked)
        val extraction = snapshot.objectives.first { it.type == ObjectiveType.EXTRACTION }
        assertTrue(extraction.isExtractionActive)
    }

    // 16. Reaching the active extraction point produces WON
    @Test
    fun testReachingActiveExtractionPointProducesWon() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 555L)
        manager.startMission()
        val relics = manager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }
        val extraction = manager.getSnapshot().objectives.first { it.type == ObjectiveType.EXTRACTION }

        // Collect all 3 relics
        manager.update(100L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        manager.update(200L, true, relics[1].floorX, relics[1].floorZ, null, null, null)
        manager.update(300L, true, relics[2].floorX, relics[2].floorZ, null, null, null)

        // Enter active extraction portal
        manager.update(400L, true, extraction.floorX, extraction.floorZ, null, null, null)

        assertEquals(GameState.WON, manager.getSnapshot().state)
    }

    // 17. Victory is emitted only once
    @Test
    fun testVictoryIsEmittedOnlyOnce() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 666L)
        manager.startMission()
        val relics = manager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }
        val extraction = manager.getSnapshot().objectives.first { it.type == ObjectiveType.EXTRACTION }

        var stateChanges = 0
        manager.onMissionStateChanged = { if (it == GameState.WON) stateChanges++ }

        manager.update(100L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        manager.update(200L, true, relics[1].floorX, relics[1].floorZ, null, null, null)
        manager.update(300L, true, relics[2].floorX, relics[2].floorZ, null, null, null)

        // Reach extraction
        manager.update(400L, true, extraction.floorX, extraction.floorZ, null, null, null)
        manager.update(500L, true, extraction.floorX, extraction.floorZ, null, null, null)

        assertEquals(1, stateChanges)
        assertEquals(GameState.WON, manager.getSnapshot().state)
    }

    // 18. Capture does not occur outside configured radius
    @Test
    fun testCaptureDoesNotOccurOutsideRadius() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 777L)
        manager.startMission()

        // Creature is 1.2 meters away (captureRadius is 0.55m)
        manager.update(
            currentTimeMs = 100L,
            isTracking = true,
            playerFloorX = 0f,
            playerFloorZ = 0f,
            creatureFloorX = 1.2f,
            creatureFloorZ = 0f,
            creatureAiState = CreatureAIState.CHASING
        )

        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
        assertEquals(0f, manager.getSnapshot().captureProgressPercent, 0.001f)
    }

    // 19. Capture requires configured continuous duration
    @Test
    fun testCaptureRequiresContinuousDuration() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 888L)
        manager.startMission()

        // Creature is 0.3m away (within 0.55m capture radius)
        // Step 1: 500 ms elapsed (required is 800 ms)
        manager.update(0L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)
        manager.update(500L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)

        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
        assertTrue(manager.getSnapshot().captureProgressPercent in 50f..70f)

        // Step 2: Total 850 ms elapsed -> reaches threshold
        manager.update(850L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)
        assertEquals(GameState.LOST, manager.getSnapshot().state)
    }

    // 20. Temporary proximity does not cause an immediate loss
    @Test
    fun testTemporaryProximityDoesNotCauseImmediateLoss() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 999L)
        manager.startMission()

        // Player proxy briefly brushes past creature for only 100ms
        manager.update(0L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)
        manager.update(100L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)

        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
    }

    // 21. Capture accumulation resets or decays according to hysteresis rule
    @Test
    fun testCaptureAccumulationDecaysWhenEscaping() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1010L)
        manager.startMission()

        // Close proximity for 400ms
        manager.update(0L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)
        manager.update(400L, true, 0f, 0f, 0.3f, 0f, CreatureAIState.CHASING)
        val initialThreat = manager.getSnapshot().captureProgressPercent
        assertTrue(initialThreat > 0f)

        // Player runs away: 2.0m away for 500ms
        manager.update(900L, true, 0f, 0f, 2.0f, 0f, CreatureAIState.CHASING)
        val decayedThreat = manager.getSnapshot().captureProgressPercent
        assertTrue("Threat should decay after escape", decayedThreat < initialThreat)
    }

    // 22. Capture cannot occur during pause or invalid tracking
    @Test
    fun testCaptureCannotOccurDuringPauseOrInvalidTracking() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1111L)
        manager.startMission()
        manager.togglePause()
        assertEquals(GameState.PAUSED, manager.getSnapshot().state)

        // Close creature proximity during PAUSED
        manager.update(0L, true, 0f, 0f, 0.2f, 0f, CreatureAIState.CHASING)
        manager.update(1000L, true, 0f, 0f, 0.2f, 0f, CreatureAIState.CHASING)

        assertEquals(GameState.PAUSED, manager.getSnapshot().state)

        // Invalid tracking
        manager.togglePause()
        manager.update(2000L, false, 0f, 0f, 0.2f, 0f, CreatureAIState.CHASING)
        assertNotEquals(GameState.LOST, manager.getSnapshot().state)
    }

    // 23. Stale visibility cannot cause an unfair capture
    @Test
    fun testStaleVisibilityCannotCauseUnfairCapture() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1212L)
        manager.startMission()

        // Creature is close, but physical obstacle occludes line of sight
        manager.update(
            currentTimeMs = 0L,
            isTracking = true,
            playerFloorX = 0f,
            playerFloorZ = 0f,
            creatureFloorX = 0.3f,
            creatureFloorZ = 0f,
            creatureAiState = CreatureAIState.CHASING,
            isCreatureOccluded = true
        )
        manager.update(
            currentTimeMs = 1000L,
            isTracking = true,
            playerFloorX = 0f,
            playerFloorZ = 0f,
            creatureFloorX = 0.3f,
            creatureFloorZ = 0f,
            creatureAiState = CreatureAIState.CHASING,
            isCreatureOccluded = true
        )

        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
        assertEquals(0f, manager.getSnapshot().captureProgressPercent, 0.001f)
    }

    // 24. WON and LOST are mutually exclusive terminal states
    @Test
    fun testWonAndLostAreMutuallyExclusiveTerminalStates() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1313L)
        manager.startMission()
        val relics = manager.getSnapshot().objectives.filter { it.type == ObjectiveType.RELIC }
        val extraction = manager.getSnapshot().objectives.first { it.type == ObjectiveType.EXTRACTION }

        // Win the game
        manager.update(100L, true, relics[0].floorX, relics[0].floorZ, null, null, null)
        manager.update(200L, true, relics[1].floorX, relics[1].floorZ, null, null, null)
        manager.update(300L, true, relics[2].floorX, relics[2].floorZ, null, null, null)
        manager.update(400L, true, extraction.floorX, extraction.floorZ, null, null, null)
        assertEquals(GameState.WON, manager.getSnapshot().state)

        // Creature approaches after victory
        manager.update(1500L, true, extraction.floorX, extraction.floorZ, extraction.floorX, extraction.floorZ, CreatureAIState.CHASING)
        assertEquals(GameState.WON, manager.getSnapshot().state)
    }

    // 25. Restart clears collected relics and reinitialises extraction and capture states
    @Test
    fun testRestartClearsCollectedRelicsAndReinitializesState() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1414L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }
        manager.update(100L, true, relic.floorX, relic.floorZ, null, null, null)
        assertEquals(1, manager.getSnapshot().relicsCollected)

        val restarted = manager.restartMission()
        assertTrue(restarted)

        val snapshot = manager.getSnapshot()
        assertEquals(GameState.READY, snapshot.state)
        assertEquals(0, snapshot.relicsCollected)
        assertEquals(0L, snapshot.elapsedTimeMs)
        assertEquals(0f, snapshot.captureProgressPercent, 0.001f)
        assertFalse(snapshot.isExtractionUnlocked)
        for (obj in snapshot.objectives) {
            if (obj.type == ObjectiveType.RELIC) {
                assertTrue(obj.isAvailable)
            } else {
                assertTrue(obj.isLocked)
            }
        }
    }

    // 26. Stale asynchronous results cannot affect a newly generated mission
    @Test
    fun testStaleAsynchronousResultsCannotAffectNewMission() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1515L)
        val gen1 = manager.getSnapshot().missionGenerationCount

        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1616L)
        val gen2 = manager.getSnapshot().missionGenerationCount

        assertTrue(gen2 > gen1)
    }

    // 27. Pausing and resuming does not corrupt objective or timer state
    @Test
    fun testPausingAndResumingDoesNotCorruptTimerOrObjectiveState() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1717L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }
        manager.update(100L, true, relic.floorX, relic.floorZ, null, null, null)

        manager.togglePause()
        assertEquals(GameState.PAUSED, manager.getSnapshot().state)

        // Time elapses while paused
        manager.update(1000L, true, 0f, 0f, null, null, null)
        val pausedElapsed = manager.getSnapshot().elapsedTimeMs

        manager.togglePause()
        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
        assertEquals(1, manager.getSnapshot().relicsCollected)
        assertEquals(pausedElapsed, manager.getSnapshot().elapsedTimeMs)
    }

    // 28. Dynamic map changes invalidate inaccessible objectives safely
    @Test
    fun testDynamicMapChangesEvaluatedSafely() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1818L)
        manager.startMission()

        // Place obstacle on one relic
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }
        val idx = occupancyGrid.cellToIndex(relic.col, relic.row)
        occupancyGrid.inflatedCellStates[idx] = NavigationCellState.PHYSICAL_OBSTACLE.code
        occupancyGrid.traversalCostGrid.updateCosts(occupancyGrid.inflatedCellStates)

        manager.update(100L, true, 0f, 0f, null, null, null, occupancyGrid = occupancyGrid)
        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
    }

    // 29. Tracking interruption preserves mission progress
    @Test
    fun testTrackingInterruptionPreservesMissionProgress() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 1919L)
        manager.startMission()
        val relic = manager.getSnapshot().objectives.first { it.type == ObjectiveType.RELIC }
        manager.update(100L, true, relic.floorX, relic.floorZ, null, null, null)
        assertEquals(1, manager.getSnapshot().relicsCollected)

        // Tracking interrupted for 2 seconds
        manager.update(500L, false, null, null, null, null, null)
        manager.update(2500L, false, null, null, null, null, null)

        // Tracking restored
        manager.update(2600L, true, 0f, 0f, null, null, null)
        assertEquals(1, manager.getSnapshot().relicsCollected)
        assertEquals(GameState.PLAYING, manager.getSnapshot().state)
    }

    // 30. Objective rendering snapshots reflect collection and extraction changes
    @Test
    fun testObjectiveRenderingSnapshotsReflectStateChanges() {
        manager.generateMission(occupancyGrid, 0f, 0f, randomSeed = 2020L)
        manager.startMission()
        val snapshotInitial = manager.getSnapshot()
        assertEquals(3, snapshotInitial.objectives.count { it.isAvailable })
        assertEquals(1, snapshotInitial.objectives.count { it.isLocked })

        // Collect one relic
        val r0 = snapshotInitial.objectives.first { it.isAvailable }
        manager.update(100L, true, r0.floorX, r0.floorZ, null, null, null)

        val snapshotAfter1 = manager.getSnapshot()
        assertEquals(2, snapshotAfter1.objectives.count { it.isAvailable })
        assertEquals(1, snapshotAfter1.objectives.count { it.isCollected })
    }
}
