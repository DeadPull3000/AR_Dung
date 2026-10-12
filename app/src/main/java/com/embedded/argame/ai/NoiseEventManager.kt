package com.embedded.argame.ai

/**
 * Manages active virtual noise events, propagation attenuation, and target selection (Milestone 13).
 *
 * Thread-safe, bounded, zero unbounded allocations, and fully deterministic under injected timestamps.
 */
class NoiseEventManager(
    val maxActiveEvents: Int = 8,
    private val minEventSeparationTimeMs: Long = 200L
) {
    private val lock = Any()
    private val activeEvents = ArrayList<NoiseEvent>(maxActiveEvents)
    private var nextEventId: Long = 1L
    private var lastEventEmitTimeMs: Long = 0L

    /**
     * Emits a new virtual noise event.
     *
     * @return The created [NoiseEvent], or null if rejected due to rate-limiting or invalid coordinates.
     */
    fun emitEvent(
        type: NoiseEventType,
        floorX: Float,
        floorZ: Float,
        cellCol: Int,
        cellRow: Int,
        currentTimeMs: Long,
        initialIntensity: Float = type.defaultIntensity,
        effectiveRadiusMeters: Float = type.defaultRadiusMeters,
        durationMs: Long = type.defaultDurationMs
    ): NoiseEvent? {
        synchronized(lock) {
            // Coordinate validity validation
            if (!floorX.isFinite() || !floorZ.isFinite() || cellCol < 0 || cellRow < 0) {
                return null
            }

            // Rapid emit rate-limiting for the same event type
            if (currentTimeMs - lastEventEmitTimeMs < minEventSeparationTimeMs) {
                // If an identical uninvestigated event already exists within 0.5m, do not duplicate
                val duplicate = activeEvents.find {
                    it.type == type && !it.isInvestigated &&
                            kotlin.math.hypot(it.floorX - floorX, it.floorZ - floorZ) < 0.5f &&
                            (currentTimeMs - it.timestampMs) < 1000L
                }
                if (duplicate != null) {
                    return null
                }
            }

            // 1. Prune expired events
            pruneExpiredLocked(currentTimeMs)

            // 2. Capacity enforcement: evict oldest investigated or oldest event if capacity reached
            if (activeEvents.size >= maxActiveEvents) {
                val toRemove = activeEvents.firstOrNull { it.isInvestigated } ?: activeEvents.first()
                activeEvents.remove(toRemove)
            }

            val event = NoiseEvent(
                id = nextEventId++,
                type = type,
                floorX = floorX,
                floorZ = floorZ,
                cellCol = cellCol,
                cellRow = cellRow,
                timestampMs = currentTimeMs,
                initialIntensity = initialIntensity.coerceIn(0f, 1f),
                effectiveRadiusMeters = effectiveRadiusMeters.coerceAtLeast(0.5f),
                durationMs = durationMs.coerceAtLeast(500L),
                isInvestigated = false
            )

            activeEvents.add(event)
            lastEventEmitTimeMs = currentTimeMs
            return event
        }
    }

    /**
     * Marks a specific noise event as investigated so the creature does not repeatedly target it.
     */
    fun markInvestigated(eventId: Long) {
        synchronized(lock) {
            val idx = activeEvents.indexOfFirst { it.id == eventId }
            if (idx >= 0) {
                activeEvents[idx] = activeEvents[idx].copy(isInvestigated = true)
            }
        }
    }

    /**
     * Removes expired noise events from memory.
     */
    fun pruneExpired(currentTimeMs: Long) {
        synchronized(lock) {
            pruneExpiredLocked(currentTimeMs)
        }
    }

    private fun pruneExpiredLocked(currentTimeMs: Long) {
        activeEvents.removeAll { it.isExpired(currentTimeMs) }
    }

    /**
     * Returns an immutable copy of currently active noise events.
     */
    fun getActiveEvents(currentTimeMs: Long): List<NoiseEvent> {
        synchronized(lock) {
            pruneExpiredLocked(currentTimeMs)
            return ArrayList(activeEvents)
        }
    }

    /**
     * Evaluates active, uninvestigated events and selects the highest-priority target for investigation.
     *
     * @param creatureFloorX Creature's current floor-space X coordinate.
     * @param creatureFloorZ Creature's current floor-space Z coordinate.
     * @param currentTimeMs Current simulation timestamp in milliseconds.
     * @param minPerceivedThreshold Minimum perceived intensity required to draw attention.
     * @param isOccluded Optional obstacle occlusion test function.
     * @return Best candidate [NoiseEvent], or null if no qualifying event exists.
     */
    fun findBestInvestigationTarget(
        creatureFloorX: Float,
        creatureFloorZ: Float,
        currentTimeMs: Long,
        minPerceivedThreshold: Float = 0.15f,
        isOccluded: ((floorX: Float, floorZ: Float) -> Boolean)? = null
    ): NoiseEvent? {
        synchronized(lock) {
            pruneExpiredLocked(currentTimeMs)

            var bestEvent: NoiseEvent? = null
            var bestScore = minPerceivedThreshold

            for (event in activeEvents) {
                if (event.isInvestigated) continue

                val occluded = isOccluded?.invoke(event.floorX, event.floorZ) ?: false
                val perceivedIntensity = event.getPerceivedIntensity(
                    observerFloorX = creatureFloorX,
                    observerFloorZ = creatureFloorZ,
                    currentTimeMs = currentTimeMs,
                    isOccludedByObstacle = occluded
                )

                if (perceivedIntensity >= minPerceivedThreshold) {
                    // Recency score factor (recent events get up to 20% bonus)
                    val ageSec = (currentTimeMs - event.timestampMs) / 1000f
                    val recencyFactor = (1f - (ageSec / (event.durationMs / 1000f)).coerceIn(0f, 0.5f))
                    val combinedScore = perceivedIntensity * recencyFactor

                    if (combinedScore > bestScore) {
                        bestScore = combinedScore
                        bestEvent = event
                    }
                }
            }

            return bestEvent
        }
    }

    /**
     * Clears all active noise events and resets state.
     */
    fun clear() {
        synchronized(lock) {
            activeEvents.clear()
            lastEventEmitTimeMs = 0L
        }
    }
}
