package com.embedded.argame.ai

/**
 * Configuration parameters for the creature suspicion model (Milestone 13).
 */
data class SuspicionConfig(
    /** Minimum baseline suspicion level (0.0). */
    val baselineSuspicion: Float = 0f,

    /** Maximum normalized suspicion ceiling (100.0). */
    val maxSuspicion: Float = 100f,

    /** Threshold below which creature continues normal patrol without investigating (0..30). */
    val lowThreshold: Float = 30f,

    /** Threshold at which creature investigates noise or suspicious location (30..70). */
    val moderateThreshold: Float = 45f,

    /** Threshold at which creature is on high alert (70..100). */
    val highThreshold: Float = 75f,

    /** Rate at which suspicion decays towards baseline when unprompted (points per second). */
    val decayRatePerSec: Float = 12f,

    /** Suspicion increase when a mission relic is collected. */
    val relicCollectedGain: Float = 45f,

    /** Suspicion increase from an active distraction event. */
    val distractionGain: Float = 50f,

    /** Suspicion increase from rapid player movement noise. */
    val movementNoiseGain: Float = 20f,

    /** Direct suspicion increase upon confirmed visual perception of the player proxy. */
    val visualConfirmedGain: Float = 85f,

    /** Maximum cumulative suspicion contribution permitted from noise alone within evidence window. */
    val repeatedNoiseCap: Float = 65f,

    /** Maximum age in milliseconds before historical evidence is discarded. */
    val evidenceMaxAgeMs: Long = 8000L,

    /** Maximum bounded number of recent evidence records stored. */
    val maxEvidenceCount: Int = 8
)
