package com.embedded.argame.ai

/**
 * Categorization of evidence sources that influence creature suspicion (Milestone 13).
 */
enum class EvidenceType(val isNoise: Boolean) {
    NOISE_RELIC(isNoise = true),
    NOISE_DISTRACTION(isNoise = true),
    NOISE_MOVEMENT(isNoise = true),
    VISUAL_CONFIRMED(isNoise = false),
    VISUAL_LAST_KNOWN(isNoise = false),
    INVESTIGATION_CLEARED(isNoise = false)
}

/**
 * Individual recorded evidence entry with position and timestamp.
 */
data class SuspicionEvidence(
    val id: Long,
    val type: EvidenceType,
    val floorX: Float,
    val floorZ: Float,
    val timestampMs: Long,
    val strength: Float
)

/**
 * Provenance category describing the origin of current creature suspicion.
 * Explicitly separates sound alert from stale visual memory and confirmed visual presence.
 */
enum class EvidenceProvenance {
    NONE,           // Baseline, no active evidence
    SOUND,          // Derived purely from acoustic events (relic, distraction, movement)
    STALE_VISUAL,   // Derived from previous visual sightings after sight is lost
    CURRENT_VISUAL  // Derived from active, unoccluded visual confirmation of player proxy
}

/**
 * Immutable snapshot of the current suspicion state for telemetry and rendering.
 */
data class SuspicionSnapshot(
    val level: Float = 0f,
    val isLow: Boolean = true,
    val isModerate: Boolean = false,
    val isHigh: Boolean = false,
    val provenance: EvidenceProvenance = EvidenceProvenance.NONE,
    val activeEvidenceCount: Int = 0,
    val lastEvidenceType: EvidenceType? = null,
    val isVisualConfirmed: Boolean = false,
    val timestampMs: Long = 0L
)

/**
 * Deterministic model maintaining creature suspicion level and evidence memory (Milestone 13).
 *
 * All time progression is driven by explicit delta-time and timestamps.
 * Zero per-frame allocations during regular update ticks.
 */
class SuspicionModel(
    val config: SuspicionConfig = SuspicionConfig()
) {
    private val lock = Any()
    private var currentSuspicion: Float = config.baselineSuspicion
    private val evidenceHistory = ArrayList<SuspicionEvidence>(config.maxEvidenceCount)
    private var nextEvidenceId: Long = 1L
    private var cumulativeNoiseScore: Float = 0f
    private var lastUpdateTimeMs: Long = 0L
    private var isCurrentlyVisualConfirmed: Boolean = false

    /**
     * Adds an evidence event that contributes to suspicion.
     *
     * @return Resulting suspicion value in [0.0, 100.0].
     */
    fun addEvidence(
        type: EvidenceType,
        strength: Float,
        floorX: Float,
        floorZ: Float,
        currentTimeMs: Long
    ): Float {
        synchronized(lock) {
            pruneExpiredLocked(currentTimeMs)

            val baseGain = when (type) {
                EvidenceType.NOISE_RELIC -> config.relicCollectedGain
                EvidenceType.NOISE_DISTRACTION -> config.distractionGain
                EvidenceType.NOISE_MOVEMENT -> config.movementNoiseGain
                EvidenceType.VISUAL_CONFIRMED -> config.visualConfirmedGain
                EvidenceType.VISUAL_LAST_KNOWN -> config.visualConfirmedGain * 0.5f
                EvidenceType.INVESTIGATION_CLEARED -> -20f
            }

            val scaledGain = baseGain * strength.coerceIn(0f, 1f)

            if (type.isNoise) {
                // Enforce cap on repeated noise contributions
                val availableHeadroom = (config.repeatedNoiseCap - cumulativeNoiseScore).coerceAtLeast(0f)
                val effectiveGain = scaledGain.coerceAtMost(availableHeadroom)
                cumulativeNoiseScore += effectiveGain
                currentSuspicion = (currentSuspicion + effectiveGain).coerceIn(0f, config.maxSuspicion)
            } else {
                currentSuspicion = (currentSuspicion + scaledGain).coerceIn(0f, config.maxSuspicion)
            }

            // Record in bounded history
            if (evidenceHistory.size >= config.maxEvidenceCount) {
                evidenceHistory.removeAt(0)
            }
            evidenceHistory.add(
                SuspicionEvidence(
                    id = nextEvidenceId++,
                    type = type,
                    floorX = floorX,
                    floorZ = floorZ,
                    timestampMs = currentTimeMs,
                    strength = strength
                )
            )

            return currentSuspicion
        }
    }

    /**
     * Advances simulation time, applying continuous decay and visual confirmation updates.
     */
    fun update(
        currentTimeMs: Long,
        dtMs: Long,
        isVisualConfirmed: Boolean,
        isTrackingValid: Boolean,
        decayMultiplier: Float = 1.0f
    ): Float {
        synchronized(lock) {
            lastUpdateTimeMs = currentTimeMs
            pruneExpiredLocked(currentTimeMs)
            isCurrentlyVisualConfirmed = isVisualConfirmed && isTrackingValid

            if (!isTrackingValid) {
                // Tracking loss pauses suspicion accumulation and decays slowly
                return currentSuspicion
            }

            if (isVisualConfirmed) {
                // Confirmed direct visual contact drives suspicion to maximum
                currentSuspicion = config.maxSuspicion
                return currentSuspicion
            }

            // Continuous decay when unprompted
            if (dtMs > 0L) {
                val decayAmount = config.decayRatePerSec * (dtMs / 1000f) * decayMultiplier
                currentSuspicion = (currentSuspicion - decayAmount).coerceAtLeast(config.baselineSuspicion)

                // Decay noise accumulation ceiling as well
                cumulativeNoiseScore = (cumulativeNoiseScore - decayAmount).coerceAtLeast(0f)
            }

            return currentSuspicion
        }
    }

    private fun pruneExpiredLocked(currentTimeMs: Long) {
        val initialCount = evidenceHistory.size
        evidenceHistory.removeAll { (currentTimeMs - it.timestampMs) > config.evidenceMaxAgeMs }
        if (evidenceHistory.isEmpty() && initialCount > 0) {
            cumulativeNoiseScore = 0f
        }
    }

    /**
     * Resets suspicion to baseline and clears all evidence memory.
     */
    fun reset() {
        synchronized(lock) {
            currentSuspicion = config.baselineSuspicion
            cumulativeNoiseScore = 0f
            evidenceHistory.clear()
            lastUpdateTimeMs = 0L
            isCurrentlyVisualConfirmed = false
        }
    }

    /**
     * Returns an immutable snapshot of current suspicion telemetry.
     */
    fun getSnapshot(currentTimeMs: Long = lastUpdateTimeMs): SuspicionSnapshot {
        synchronized(lock) {
            val level = currentSuspicion
            val prov = when {
                isCurrentlyVisualConfirmed -> EvidenceProvenance.CURRENT_VISUAL
                evidenceHistory.any { it.type == EvidenceType.VISUAL_LAST_KNOWN || it.type == EvidenceType.VISUAL_CONFIRMED } -> EvidenceProvenance.STALE_VISUAL
                evidenceHistory.any { it.type.isNoise } -> EvidenceProvenance.SOUND
                else -> EvidenceProvenance.NONE
            }
            return SuspicionSnapshot(
                level = level,
                isLow = level < config.lowThreshold,
                isModerate = level >= config.lowThreshold && level < config.highThreshold,
                isHigh = level >= config.highThreshold,
                provenance = prov,
                activeEvidenceCount = evidenceHistory.size,
                lastEvidenceType = evidenceHistory.lastOrNull()?.type,
                isVisualConfirmed = isCurrentlyVisualConfirmed,
                timestampMs = currentTimeMs
            )
        }
    }
}
