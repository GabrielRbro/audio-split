package io.github.audiosplit.core

/** A missing route observation is unknown, never evidence of successful or failed routing. */
data class PlaybackRouteObservation(
    val uid: Int,
    val active: Boolean?,
    val observedDeviceKeys: Set<String>?,
)

/** Waits for stable, complete evidence while both selected apps are actively playing. */
class RouteMismatchMonitor(
    val expectedRoutes: Map<Int, String>,
    private val gracePeriodMs: Long = 3_000L,
    private val requiredSamples: Int = 3,
) {
    private var previousMismatch: Map<Int, Set<Set<String>>>? = null
    private var consecutiveSamples = 0

    init {
        require(expectedRoutes.size == 2)
        require(gracePeriodMs >= 0 && requiredSamples > 0)
    }

    fun sample(elapsedSinceApplyMs: Long, observations: List<PlaybackRouteObservation>): Boolean {
        val targetPlayers = observations.filter { it.uid in expectedRoutes }
        val active = targetPlayers.filter { it.active == true }
        if (elapsedSinceApplyMs < gracePeriodMs || targetPlayers.any { it.active == null } ||
            active.map { it.uid }.toSet() != expectedRoutes.keys ||
            active.any { it.observedDeviceKeys.isNullOrEmpty() }) {
            reset()
            return false
        }
        if (active.all { it.observedDeviceKeys == setOf(expectedRoutes.getValue(it.uid)) }) {
            reset()
            return false
        }
        val mismatch = active.groupBy { it.uid }.mapValues { (_, players) ->
            players.map { requireNotNull(it.observedDeviceKeys) }.toSet()
        }
        consecutiveSamples = if (mismatch == previousMismatch) consecutiveSamples + 1 else 1
        previousMismatch = mismatch
        return consecutiveSamples >= requiredSamples
    }

    private fun reset() {
        previousMismatch = null
        consecutiveSamples = 0
    }
}
