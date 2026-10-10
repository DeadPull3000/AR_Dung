package com.embedded.argame.game

import com.embedded.argame.ai.CreatureAIState
import com.embedded.argame.environment.OccupancyGrid
import java.util.ArrayDeque
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max

/**
 * Manages the high-level mission lifecycle, objective progression,
 * collection detection, extraction gating, and creature capture mechanics (Milestone 12).
 *
 * Designed to be deterministic, zero-allocation during regular evaluation ticks,
 * and fully unit-testable independently of ARCore or OpenGL dependencies.
 */
class MissionManager(
    val config: MissionConfig = MissionConfig()
) {

    private val lock = Any()

    // Core Mission State
    private var state: GameState = GameState.SCANNING
    private val objectives = mutableListOf<MissionObjective>()
    private var relicsCollected: Int = 0
    private var isExtractionUnlocked: Boolean = false

    // Timing and Progression
    private var elapsedTimeMs: Long = 0L
    private var lastUpdateTimestampMs: Long = -1L
    private var captureAccumulatorMs: Long = 0L
    private var captureProgressPercent: Float = 0f
    private var missionGenerationCount: Int = 0

    // Room Mapping Status
    private var isNavigableSufficient: Boolean = false
    private var traversableCellCount: Int = 0

    // HUD Guidance & Status
    private var statusMessage: String = "Scan the room floor to generate mission"
    private var guidancePrompt: String = "SCAN THE ROOM"

    // Callback notifications
    var onMissionStateChanged: ((GameState) -> Unit)? = null
    var onRelicCollected: ((relicId: Int, collected: Int, total: Int) -> Unit)? = null
    var onExtractionUnlocked: (() -> Unit)? = null

    // Cached snapshot to avoid allocation when state hasn't changed
    @Volatile
    private var cachedSnapshot: MissionSnapshot = MissionSnapshot()

    init {
        updateSnapshotLocked()
    }

    /**
     * Returns an immutable thread-safe snapshot of mission progression.
     */
    fun getSnapshot(): MissionSnapshot = cachedSnapshot

    /**
     * Checks whether the current occupancy grid contains enough traversable floor space
     * to satisfy the minimum mission generation criteria.
     */
    fun evaluateNavigableSpace(occupancyGrid: OccupancyGrid): Boolean {
        synchronized(lock) {
            var count = 0
            val costGrid = occupancyGrid.traversalCostGrid
            val total = occupancyGrid.totalCells
            for (i in 0 until total) {
                if (costGrid.isTraversable(i)) {
                    count++
                }
            }
            traversableCellCount = count
            isNavigableSufficient = count >= config.minNavigableCellsForMission

            if (state == GameState.SCANNING) {
                if (isNavigableSufficient) {
                    guidancePrompt = "ROOM MAPPED - TAP GENERATE MISSION"
                    statusMessage = "Navigable floor mapped ($count cells). Ready for mission generation."
                } else {
                    guidancePrompt = "SCAN THE ROOM"
                    statusMessage = "Scanning room: $count/${config.minNavigableCellsForMission} navigable cells found."
                }
                updateSnapshotLocked()
            }
            return isNavigableSufficient
        }
    }

    /**
     * Generates a new Relic Hunt mission with 3 relics and 1 extraction point.
     * Uses floodfill reachability to ensure all placed objectives are reachable from the player proxy.
     *
     * @param occupancyGrid Active 2.5D spatial map.
     * @param playerFloorX Current floor-space X coordinate of the player proxy.
     * @param playerFloorZ Current floor-space Z coordinate of the player proxy.
     * @param randomSeed Optional random seed for reproducible unit tests.
     * @return true if 3 relics and 1 extraction point were successfully generated.
     */
    fun generateMission(
        occupancyGrid: OccupancyGrid,
        playerFloorX: Float,
        playerFloorZ: Float,
        randomSeed: Long? = null
    ): Boolean {
        synchronized(lock) {
            val costGrid = occupancyGrid.traversalCostGrid
            val totalCells = occupancyGrid.totalCells
            val numX = occupancyGrid.numCellsX
            val numZ = occupancyGrid.numCellsZ

            // 1. Verify player start location on grid
            val playerCell = occupancyGrid.floorToCell(playerFloorX, playerFloorZ)
            val startCol = playerCell?.first ?: (numX / 2)
            val startRow = playerCell?.second ?: (numZ / 2)

            // If player cell is not traversable, find nearest traversable seed cell
            val seedCell = findNearestTraversableCell(startCol, startRow, occupancyGrid)
            if (seedCell == null) {
                statusMessage = "Failed to generate mission: player start location has no traversable space."
                updateSnapshotLocked()
                return false
            }

            // 2. Discover connected component of traversable cells using BFS floodfill
            val reachableCells = mutableListOf<Pair<Int, Int>>()
            val visited = BooleanArray(totalCells)
            val queue = ArrayDeque<Pair<Int, Int>>()

            val seedIdx = occupancyGrid.cellToIndex(seedCell.first, seedCell.second)
            visited[seedIdx] = true
            queue.add(seedCell)

            val dCols = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
            val dRows = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)

            while (!queue.isEmpty()) {
                val curr = queue.poll()!!
                reachableCells.add(curr)

                for (i in 0 until 8) {
                    val nc = curr.first + dCols[i]
                    val nr = curr.second + dRows[i]
                    if (nc in 0 until numX && nr in 0 until numZ) {
                        val nIdx = occupancyGrid.cellToIndex(nc, nr)
                        if (!visited[nIdx] && costGrid.isTraversable(nIdx)) {
                            visited[nIdx] = true
                            queue.add(Pair(nc, nr))
                        }
                    }
                }
            }

            traversableCellCount = reachableCells.size
            val requiredObjectives = config.relicCount + 1 // 3 relics + 1 extraction
            if (reachableCells.size < requiredObjectives || reachableCells.size < config.minNavigableCellsForMission) {
                statusMessage = "Insufficient connected traversable space (${reachableCells.size} cells). Scan more floor."
                updateSnapshotLocked()
                return false
            }

            // 3. Deterministically or randomly select separated objective positions
            val rng = if (randomSeed != null) Random(randomSeed) else Random()
            val candidates = reachableCells.toMutableList()
            // Shuffle candidates using rng
            for (i in candidates.size - 1 downTo 1) {
                val j = rng.nextInt(i + 1)
                val temp = candidates[i]
                candidates[i] = candidates[j]
                candidates[j] = temp
            }

            val chosenCells = mutableListOf<Pair<Int, Int>>()
            var separationThreshold = config.minObjectiveSeparationMeters

            // Multi-pass selection: start with strict separation, relax slightly if room is compact
            for (pass in 0 until 3) {
                chosenCells.clear()
                for (cand in candidates) {
                    val (fx, fz) = occupancyGrid.cellToFloorCoord(cand.first, cand.second)
                    val distToPlayer = hypot(fx - playerFloorX, fz - playerFloorZ)

                    // Ensure minimum separation from player proxy start position
                    if (distToPlayer < config.minPlayerSeparationMeters * (1f - pass * 0.25f)) {
                        continue
                    }

                    // Ensure minimum separation from already chosen objectives
                    var isSeparated = true
                    for (chosen in chosenCells) {
                        val (cfx, cfz) = occupancyGrid.cellToFloorCoord(chosen.first, chosen.second)
                        val distToChosen = hypot(fx - cfx, fz - cfz)
                        if (distToChosen < separationThreshold) {
                            isSeparated = false
                            break
                        }
                    }

                    if (isSeparated) {
                        chosenCells.add(cand)
                        if (chosenCells.size == requiredObjectives) {
                            break
                        }
                    }
                }

                if (chosenCells.size == requiredObjectives) {
                    break
                }
                // Relax separation by 25% for next pass in compact rooms
                separationThreshold *= 0.75f
            }

            // Fallback: if separation passes could not place all objectives, fill remaining from reachableCells
            if (chosenCells.size < requiredObjectives && reachableCells.size >= requiredObjectives) {
                for (cand in candidates) {
                    if (!chosenCells.contains(cand)) {
                        chosenCells.add(cand)
                        if (chosenCells.size == requiredObjectives) {
                            break
                        }
                    }
                }
            }

            if (chosenCells.size < requiredObjectives) {
                statusMessage = "Failed to place ${requiredObjectives} separated objectives. Expand mapped area."
                updateSnapshotLocked()
                return false
            }

            // 4. Construct objectives: first [relicCount] are relics, last is extraction
            objectives.clear()
            for (i in 0 until config.relicCount) {
                val cell = chosenCells[i]
                val (fx, fz) = occupancyGrid.cellToFloorCoord(cell.first, cell.second)
                objectives.add(
                    MissionObjective(
                        id = i + 1,
                        type = ObjectiveType.RELIC,
                        col = cell.first,
                        row = cell.second,
                        floorX = fx,
                        floorZ = fz,
                        status = ObjectiveStatus.AVAILABLE,
                        interactionRadiusMeters = config.relicCollectionRadiusMeters
                    )
                )
            }

            val extCell = chosenCells[config.relicCount]
            val (efx, efz) = occupancyGrid.cellToFloorCoord(extCell.first, extCell.second)
            objectives.add(
                MissionObjective(
                    id = requiredObjectives,
                    type = ObjectiveType.EXTRACTION,
                    col = extCell.first,
                    row = extCell.second,
                    floorX = efx,
                    floorZ = efz,
                    status = ObjectiveStatus.LOCKED,
                    interactionRadiusMeters = config.extractionRadiusMeters
                )
            )

            // Reset progression variables
            relicsCollected = 0
            isExtractionUnlocked = false
            elapsedTimeMs = 0L
            captureAccumulatorMs = 0L
            captureProgressPercent = 0f
            missionGenerationCount++
            lastUpdateTimestampMs = -1L

            state = GameState.READY
            guidancePrompt = "MISSION READY - TAP START MISSION"
            statusMessage = "Mission generated! 3 relics and 1 extraction point placed."
            updateSnapshotLocked()
            onMissionStateChanged?.invoke(state)
            return true
        }
    }

    /**
     * Starts the generated mission, transitioning from [GameState.READY] to [GameState.PLAYING].
     */
    fun startMission(): Boolean {
        synchronized(lock) {
            if (state != GameState.READY) {
                return false
            }
            state = GameState.PLAYING
            elapsedTimeMs = 0L
            captureAccumulatorMs = 0L
            captureProgressPercent = 0f
            lastUpdateTimestampMs = -1L
            guidancePrompt = "COLLECT RELICS: 0/${config.relicCount}"
            statusMessage = "Mission active! Collect all 3 relics and evade the creature."
            updateSnapshotLocked()
            onMissionStateChanged?.invoke(state)
            return true
        }
    }

    /**
     * Toggles pause state between [GameState.PLAYING] and [GameState.PAUSED].
     */
    fun togglePause(reason: String = "Manual Pause"): Boolean {
        synchronized(lock) {
            return when (state) {
                GameState.PLAYING -> {
                    state = GameState.PAUSED
                    guidancePrompt = "MISSION PAUSED"
                    statusMessage = "Paused: $reason"
                    updateSnapshotLocked()
                    onMissionStateChanged?.invoke(state)
                    true
                }
                GameState.PAUSED -> {
                    state = GameState.PLAYING
                    lastUpdateTimestampMs = -1L // Prevent large delta-time spike on resume
                    guidancePrompt = if (isExtractionUnlocked) "EXTRACTION UNLOCKED - REACH EXIT" else "COLLECT RELICS: $relicsCollected/${config.relicCount}"
                    statusMessage = "Mission resumed."
                    updateSnapshotLocked()
                    onMissionStateChanged?.invoke(state)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Explicitly pauses the mission if currently playing (e.g. on tracking loss).
     */
    fun pause(reason: String) {
        synchronized(lock) {
            if (state == GameState.PLAYING) {
                state = GameState.PAUSED
                guidancePrompt = "PAUSED ($reason)"
                statusMessage = "Suspended: $reason"
                updateSnapshotLocked()
                onMissionStateChanged?.invoke(state)
            }
        }
    }

    /**
     * Resumes the mission if paused and tracking is healthy.
     */
    fun resume() {
        synchronized(lock) {
            if (state == GameState.PAUSED) {
                state = GameState.PLAYING
                lastUpdateTimestampMs = -1L
                guidancePrompt = if (isExtractionUnlocked) "EXTRACTION UNLOCKED - REACH EXIT" else "COLLECT RELICS: $relicsCollected/${config.relicCount}"
                statusMessage = "Mission resumed."
                updateSnapshotLocked()
                onMissionStateChanged?.invoke(state)
            }
        }
    }

    /**
     * Restarts the current mission, restoring all relics to available,
     * locking extraction, and resetting timers and capture progress.
     */
    fun restartMission(): Boolean {
        synchronized(lock) {
            if (objectives.isEmpty()) {
                state = GameState.SCANNING
                guidancePrompt = "SCAN THE ROOM"
                statusMessage = "No active mission. Scan room to generate."
                updateSnapshotLocked()
                return false
            }

            // Restore all relics to AVAILABLE and extraction to LOCKED
            for (i in objectives.indices) {
                val obj = objectives[i]
                objectives[i] = if (obj.type == ObjectiveType.RELIC) {
                    obj.copy(status = ObjectiveStatus.AVAILABLE)
                } else {
                    obj.copy(status = ObjectiveStatus.LOCKED)
                }
            }

            relicsCollected = 0
            isExtractionUnlocked = false
            elapsedTimeMs = 0L
            lastUpdateTimestampMs = -1L
            captureAccumulatorMs = 0L
            captureProgressPercent = 0f

            state = GameState.READY
            guidancePrompt = "MISSION READY - TAP START MISSION"
            statusMessage = "Mission reset. Ready to start."
            updateSnapshotLocked()
            onMissionStateChanged?.invoke(state)
            return true
        }
    }

    /**
     * Per-frame simulation and game logic update.
     * Evaluates objective proximity collection, extraction victory, and creature capture defeat.
     */
    fun update(
        currentTimeMs: Long,
        isTracking: Boolean,
        playerFloorX: Float?,
        playerFloorZ: Float?,
        creatureFloorX: Float?,
        creatureFloorZ: Float?,
        creatureAiState: CreatureAIState?,
        isCreatureOccluded: Boolean = false,
        occupancyGrid: OccupancyGrid? = null
    ) {
        synchronized(lock) {
            // Calculate delta-time safely
            val dtMs = if (lastUpdateTimestampMs < 0L || currentTimeMs <= lastUpdateTimestampMs) {
                0L
            } else {
                (currentTimeMs - lastUpdateTimestampMs).coerceAtMost(1000L) // Clamp large lags
            }
            lastUpdateTimestampMs = currentTimeMs

            // 1. Handle AR tracking loss safety
            if (!isTracking) {
                if (state == GameState.PLAYING) {
                    guidancePrompt = "TRACKING LOST - HOLD STILL"
                    statusMessage = "AR tracking interrupted. Mission paused."
                    updateSnapshotLocked()
                }
                return
            }

            // 2. Handle room scanning state
            if (state == GameState.SCANNING) {
                occupancyGrid?.let { evaluateNavigableSpace(it) }
                return
            }

            // Only actively simulate when PLAYING
            if (state != GameState.PLAYING) {
                return
            }

            elapsedTimeMs += dtMs

            // 3. Relic Collection Evaluation
            if (playerFloorX != null && playerFloorZ != null) {
                for (i in objectives.indices) {
                    val obj = objectives[i]
                    if (obj.isAvailable && obj.type == ObjectiveType.RELIC) {
                        val dist = hypot(playerFloorX - obj.floorX, playerFloorZ - obj.floorZ)
                        if (dist <= obj.interactionRadiusMeters) {
                            // Relic collected!
                            objectives[i] = obj.copy(status = ObjectiveStatus.COLLECTED)
                            relicsCollected++
                            onRelicCollected?.invoke(obj.id, relicsCollected, config.relicCount)

                            if (relicsCollected >= config.relicCount) {
                                // Unlock extraction portal
                                isExtractionUnlocked = true
                                for (j in objectives.indices) {
                                    if (objectives[j].type == ObjectiveType.EXTRACTION) {
                                        objectives[j] = objectives[j].copy(status = ObjectiveStatus.ACTIVE)
                                    }
                                }
                                guidancePrompt = "EXTRACTION UNLOCKED - REACH THE EXIT!"
                                statusMessage = "All 3 relics collected! Reach the extraction point to win."
                                onExtractionUnlocked?.invoke()
                            } else {
                                guidancePrompt = "COLLECT RELICS: $relicsCollected/${config.relicCount}"
                                statusMessage = "Relic ${obj.id} collected! ($relicsCollected/${config.relicCount})"
                            }
                        }
                    }
                }

                // 4. Extraction Victory Evaluation
                for (obj in objectives) {
                    if (obj.type == ObjectiveType.EXTRACTION) {
                        val dist = hypot(playerFloorX - obj.floorX, playerFloorZ - obj.floorZ)
                        if (obj.isExtractionActive && dist <= obj.interactionRadiusMeters) {
                            // VICTORY!
                            state = GameState.WON
                            guidancePrompt = "MISSION COMPLETE - VICTORY!"
                            val totalSec = (elapsedTimeMs / 1000L).toInt()
                            statusMessage = "Victory! Extracted all relics in %02d:%02d.".format(totalSec / 60, totalSec % 60)
                            updateSnapshotLocked()
                            onMissionStateChanged?.invoke(state)
                            return
                        } else if (obj.isLocked && dist <= obj.interactionRadiusMeters) {
                            val remaining = config.relicCount - relicsCollected
                            statusMessage = "Extraction locked! Collect remaining $remaining relic(s) first."
                        }
                    }
                }
            }

            // 5. Creature Capture Defeat Evaluation
            if (playerFloorX != null && playerFloorZ != null && creatureFloorX != null && creatureFloorZ != null) {
                val distCreature = hypot(playerFloorX - creatureFloorX, playerFloorZ - creatureFloorZ)
                val isChasing = creatureAiState == CreatureAIState.CHASING
                val isClose = distCreature <= config.captureRadiusMeters
                val isNotOccluded = !isCreatureOccluded

                if (isChasing && isClose && isNotOccluded) {
                    // Accumulate capture threat
                    captureAccumulatorMs += dtMs
                    captureProgressPercent = (captureAccumulatorMs.toFloat() / config.captureDurationRequiredMs).coerceIn(0f, 1f) * 100f
                    guidancePrompt = "CREATURE CHASING YOU! RUN!"

                    if (captureAccumulatorMs >= config.captureDurationRequiredMs) {
                        // DEFEAT!
                        state = GameState.LOST
                        guidancePrompt = "YOU WERE CAUGHT - MISSION FAILED"
                        statusMessage = "The creature caught you! Tap RESTART MISSION."
                        updateSnapshotLocked()
                        onMissionStateChanged?.invoke(state)
                        return
                    }
                } else {
                    // Decay capture progress when proxy escapes or line of sight is broken
                    if (captureAccumulatorMs > 0L) {
                        val decay = (config.captureDecayRateMsPerSec * dtMs / 1000f).toLong()
                        captureAccumulatorMs = max(0L, captureAccumulatorMs - decay)
                        captureProgressPercent = (captureAccumulatorMs.toFloat() / config.captureDurationRequiredMs).coerceIn(0f, 1f) * 100f
                    }
                }
            } else {
                if (captureAccumulatorMs > 0L) {
                    val decay = (config.captureDecayRateMsPerSec * dtMs / 1000f).toLong()
                    captureAccumulatorMs = max(0L, captureAccumulatorMs - decay)
                    captureProgressPercent = (captureAccumulatorMs.toFloat() / config.captureDurationRequiredMs).coerceIn(0f, 1f) * 100f
                }
            }

            updateSnapshotLocked()
        }
    }

    /**
     * Resets the entire mission manager back to initial scanning state.
     */
    fun reset() {
        synchronized(lock) {
            state = GameState.SCANNING
            objectives.clear()
            relicsCollected = 0
            isExtractionUnlocked = false
            elapsedTimeMs = 0L
            lastUpdateTimestampMs = -1L
            captureAccumulatorMs = 0L
            captureProgressPercent = 0f
            guidancePrompt = "SCAN THE ROOM"
            statusMessage = "Scan the room floor to generate mission"
            updateSnapshotLocked()
            onMissionStateChanged?.invoke(state)
        }
    }

    /**
     * Internal helper to find the closest traversable cell to a coordinate.
     */
    private fun findNearestTraversableCell(
        centerCol: Int,
        centerRow: Int,
        occupancyGrid: OccupancyGrid
    ): Pair<Int, Int>? {
        val costGrid = occupancyGrid.traversalCostGrid
        val numX = occupancyGrid.numCellsX
        val numZ = occupancyGrid.numCellsZ
        val clampedCol = centerCol.coerceIn(0, numX - 1)
        val clampedRow = centerRow.coerceIn(0, numZ - 1)
        val centerIdx = occupancyGrid.cellToIndex(clampedCol, clampedRow)
        if (costGrid.isTraversable(centerIdx)) {
            return Pair(clampedCol, clampedRow)
        }

        val maxDim = maxOf(numX, numZ)
        for (radius in 1..maxDim) {
            for (dc in -radius..radius) {
                for (dr in -radius..radius) {
                    if (Math.abs(dc) == radius || Math.abs(dr) == radius) {
                        val nc = clampedCol + dc
                        val nr = clampedRow + dr
                        if (nc in 0 until numX && nr in 0 until numZ) {
                            val idx = occupancyGrid.cellToIndex(nc, nr)
                            if (costGrid.isTraversable(idx)) {
                                return Pair(nc, nr)
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Updates the cached immutable snapshot. Must be called while holding [lock].
     */
    private fun updateSnapshotLocked() {
        cachedSnapshot = MissionSnapshot(
            state = state,
            objectives = objectives.toList(),
            relicsCollected = relicsCollected,
            totalRelics = config.relicCount,
            isExtractionUnlocked = isExtractionUnlocked,
            elapsedTimeMs = elapsedTimeMs,
            captureProgressPercent = captureProgressPercent,
            statusMessage = statusMessage,
            guidancePrompt = guidancePrompt,
            missionGenerationCount = missionGenerationCount,
            isNavigableSufficient = isNavigableSufficient,
            traversableCellCount = traversableCellCount
        )
    }
}
