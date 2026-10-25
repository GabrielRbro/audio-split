package io.github.audiosplit.tone

/** Process-local state: the service and Activity deliberately run in the same process. */
internal object ToneStatus {
    data class Snapshot(
        val active: Boolean = false,
        val playing: Boolean = false,
        val requestsFocus: Boolean = false,
        val message: String? = null,
    )

    private val listeners = linkedSetOf<(Snapshot) -> Unit>()
    var current: Snapshot = Snapshot()
        private set

    // All access is on the main thread, including AudioFocus callbacks.
    fun update(snapshot: Snapshot) {
        current = snapshot
        listeners.toList().forEach { it(snapshot) }
    }

    fun subscribe(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun unsubscribe(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
