package com.embedded.argame.game

/**
 * Immutable thread-safe snapshot of mission progression.
 * Consumed by UI HUD and OpenGL ES 3.0 renderers.
 */
data class MissionSnapshot(
    val state: GameState = GameState.SCANNING,
    val objectives: List<MissionObjective> = emptyList(),
    val relicsCollected: Int = 0,
    val totalRelics: Int = 3,
    val isExtractionUnlocked: Boolean = false,
    val elapsedTimeMs: Long = 0L,
    val captureProgressPercent: Float = 0f,
    val statusMessage: String = "Scan the room floor to generate mission",
    val guidancePrompt: String = "SCAN THE ROOM",
    val missionGenerationCount: Int = 0,
    val isNavigableSufficient: Boolean = false,
    val traversableCellCount: Int = 0
) {
    val formattedElapsedTime: String
        get() {
            val totalSeconds = (elapsedTimeMs / 1000L).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
}
