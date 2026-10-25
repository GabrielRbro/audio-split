package io.github.audiosplit.core

/** A successful acquisition must return a lease that can undo everything it changed. */
interface SessionStep {
    val name: String
    fun acquire(): SessionLease
}

fun interface SessionLease {
    fun release()
}

data class CleanupFailure(val name: String, val error: Throwable)

data class SessionSnapshot(val retainedResources: List<String>) {
    val active: Boolean get() = retainedResources.isNotEmpty()
}

data class RestoreResult(val snapshot: SessionSnapshot, val failures: List<CleanupFailure>) {
    val restored: Boolean get() = !snapshot.active
}

data class BeginResult(
    val applied: Boolean,
    val snapshot: SessionSnapshot,
    val error: Throwable? = null,
    val cleanupFailures: List<CleanupFailure> = emptyList(),
)

/**
 * Owns temporary resources for exactly one session. Failed cleanup remains owned and can be retried;
 * callers must never report restoration while [snapshot] still contains a resource.
 */
class SessionController {
    private data class Owned(val name: String, val lease: SessionLease)
    private val owned = mutableListOf<Owned>()

    @Synchronized
    fun snapshot(): SessionSnapshot = SessionSnapshot(owned.map { it.name })

    @Synchronized
    fun begin(steps: List<SessionStep>): BeginResult {
        check(owned.isEmpty()) { "Restore the existing session before applying another." }
        require(steps.isNotEmpty()) { "A session needs at least one acquisition." }
        try {
            for (step in steps) owned += Owned(step.name, step.acquire())
            return BeginResult(applied = true, snapshot = snapshot())
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val rollback = restore()
            return BeginResult(false, rollback.snapshot, error, rollback.failures)
        }
    }

    @Synchronized
    fun restore(): RestoreResult {
        val failures = mutableListOf<CleanupFailure>()
        for (index in owned.lastIndex downTo 0) {
            val item = owned[index]
            try {
                item.lease.release()
                owned.removeAt(index)
            } catch (error: Throwable) {
                if (error !is Exception && error !is LinkageError) throw error
                failures += CleanupFailure(item.name, error)
            }
        }
        return RestoreResult(snapshot(), failures)
    }
}
