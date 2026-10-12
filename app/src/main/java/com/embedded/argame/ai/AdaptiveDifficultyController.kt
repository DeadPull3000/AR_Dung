package com.embedded.argame.ai

/**
 * Immutable snapshot of adaptive difficulty state for UI and telemetry.
 */
data class AdaptiveDifficultySnapshot(
    val level: DifficultyLevel = DifficultyLevel.STANDARD,
    val score: Float = 50f,
    val reason: String = "Initial baseline difficulty",
    val parameters: AdaptiveParameters = AdaptiveParameters(),
    val adjustmentCount: Int = 0,
    val timestampMs: Long = 0L
)

/**
 * Controller adjusting creature intelligence and persistence based on player progression (Milestone 13).
 *
 * Implements strict hysteresis, cooldown intervals, and safety bounds to guarantee fair, non-punitive gameplay.
 */
class AdaptiveDifficultyController(
    val config: DifficultyConfig = DifficultyConfig()
) {
    private val lock = Any()
    private var currentLevel: DifficultyLevel = config.initialLevel
    private var currentScore: Float = 50f
    private var lastAdjustmentTimeMs: Long = 0L
    private var lastReason: String = "Initial baseline"
    private var adjustmentCount: Int = 0

    // Gameplay signals
    private var relicsCollected: Int = 0
    private var playerEscapesCount: Int = 0
    private var peakThreatObservedPercent: Float = 0f

    /**
     * Records a successful player escape from active pursuit.
     */
    fun recordPlayerEscape() {
        synchronized(lock) {
            playerEscapesCount++
        }
    }

    /**
     * Updates gameplay progression metrics.
     */
    fun updateProgression(
        relics: Int,
        threatPercent: Float
    ) {
        synchronized(lock) {
            relicsCollected = relics
            if (threatPercent > peakThreatObservedPercent) {
                peakThreatObservedPercent = threatPercent
            }
        }
    }

    /**
     * Evaluates adaptive difficulty tier based on accumulated gameplay performance metrics.
     *
     * @param currentTimeMs Current simulation timestamp in milliseconds.
     * @param missionElapsedSec Total mission time spent in active PLAYING state.
     * @param isPausedOrTerminal Whether game is paused or finished (suspends adaptation).
     * @return Updated [AdaptiveDifficultySnapshot].
     */
    fun evaluate(
        currentTimeMs: Long,
        missionElapsedSec: Float,
        isPausedOrTerminal: Boolean
    ): AdaptiveDifficultySnapshot {
        synchronized(lock) {
            if (isPausedOrTerminal) {
                return createSnapshotLocked(currentTimeMs)
            }

            // Calculate composite challenge score [0..100] around baseline 50
            val baseScore = 50.0f
            val relicContribution = relicsCollected * 15.0f
            val timeContribution = ((missionElapsedSec / 60.0f) * 10.0f).coerceAtMost(20.0f)
            val escapeContribution = (playerEscapesCount * 10.0f).coerceAtMost(20.0f)

            // Pressure relief: if creature recently exerted extreme threat, lower difficulty score
            val reliefContribution = if (peakThreatObservedPercent >= 75f) 25f else if (peakThreatObservedPercent >= 45f) 15f else 0f

            currentScore = (baseScore + relicContribution + timeContribution + escapeContribution - reliefContribution)
                .coerceIn(0f, 100f)

            // Enforce adjustment cooldown
            if (currentTimeMs - lastAdjustmentTimeMs >= config.adjustmentCooldownMs) {
                val nextLevel = computeNextTier(currentLevel, currentScore)
                if (nextLevel != currentLevel) {
                    val prev = currentLevel
                    currentLevel = nextLevel
                    lastAdjustmentTimeMs = currentTimeMs
                    adjustmentCount++
                    lastReason = when {
                        nextLevel > prev -> "Increased to $nextLevel (Relics: $relicsCollected, Escapes: $playerEscapesCount)"
                        else -> "Relaxed to $nextLevel (Player pressure relief)"
                    }
                }
            }

            return createSnapshotLocked(currentTimeMs)
        }
    }

    /**
     * Hysteresis-aware tier transition calculation.
     */
    private fun computeNextTier(current: DifficultyLevel, score: Float): DifficultyLevel {
        val h = config.hysteresisMargin
        return when (current) {
            DifficultyLevel.EASY -> {
                if (score >= config.easyToStandardScore + h) DifficultyLevel.STANDARD else DifficultyLevel.EASY
            }
            DifficultyLevel.STANDARD -> {
                if (score < config.easyToStandardScore - h) {
                    DifficultyLevel.EASY
                } else if (score >= config.standardToChallengingScore + h) {
                    DifficultyLevel.CHALLENGING
                } else {
                    DifficultyLevel.STANDARD
                }
            }
            DifficultyLevel.CHALLENGING -> {
                if (score < config.standardToChallengingScore - h) DifficultyLevel.STANDARD else DifficultyLevel.CHALLENGING
            }
        }
    }

    /**
     * Current active parameter set for the current tier.
     */
    fun getActiveParameters(): AdaptiveParameters {
        synchronized(lock) {
            return when (currentLevel) {
                DifficultyLevel.EASY -> config.easyParameters
                DifficultyLevel.STANDARD -> config.standardParameters
                DifficultyLevel.CHALLENGING -> config.challengingParameters
            }
        }
    }

    /**
     * Resets difficulty to initial baseline.
     */
    fun reset() {
        synchronized(lock) {
            currentLevel = config.initialLevel
            currentScore = 50f
            lastAdjustmentTimeMs = 0L
            lastReason = "Reset to baseline"
            adjustmentCount = 0
            relicsCollected = 0
            playerEscapesCount = 0
            peakThreatObservedPercent = 0f
        }
    }

    fun getSnapshot(currentTimeMs: Long = 0L): AdaptiveDifficultySnapshot {
        synchronized(lock) {
            return createSnapshotLocked(currentTimeMs)
        }
    }

    private fun createSnapshotLocked(currentTimeMs: Long): AdaptiveDifficultySnapshot {
        return AdaptiveDifficultySnapshot(
            level = currentLevel,
            score = currentScore,
            reason = lastReason,
            parameters = getActiveParameters(),
            adjustmentCount = adjustmentCount,
            timestampMs = currentTimeMs
        )
    }
}
