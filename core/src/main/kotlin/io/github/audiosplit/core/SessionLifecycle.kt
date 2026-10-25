package io.github.audiosplit.core

enum class SessionStopCause {
    CLIENT_DISCONNECTED, PRIVILEGED_SESSION_DISCONNECTED, OUTPUT_DISCONNECTED, CLEANUP_RETRY
}

data class LifecycleStop(val cause: SessionStopCause, val restoration: RestoreResult)

/** Applies the same resource cleanup to process death, missing outputs, and retry ticks. */
class SessionLifecycle(private val session: SessionController) {
    fun inspect(
        clientAlive: Boolean,
        outputAvailable: Boolean,
        retryCleanup: Boolean,
        privilegedSessionAlive: Boolean = true,
    ): LifecycleStop? {
        if (!session.snapshot().active) return null
        val cause = when {
            retryCleanup -> SessionStopCause.CLEANUP_RETRY
            !clientAlive -> SessionStopCause.CLIENT_DISCONNECTED
            !privilegedSessionAlive -> SessionStopCause.PRIVILEGED_SESSION_DISCONNECTED
            !outputAvailable -> SessionStopCause.OUTPUT_DISCONNECTED
            else -> return null
        }
        return LifecycleStop(cause, session.restore())
    }
}
