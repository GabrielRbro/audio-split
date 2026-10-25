package io.github.audiosplit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLifecycleTest {
    private fun acquire(session: SessionController, release: () -> Unit) {
        session.begin(listOf(object : SessionStep {
            override val name = "audio policy"
            override fun acquire() = SessionLease(release)
        }))
    }

    @Test fun healthyClientAndOutputKeepSessionAcrossPolls() {
        val session = SessionController()
        var releases = 0
        acquire(session) { releases++ }
        val lifecycle = SessionLifecycle(session)
        repeat(5) { assertNull(lifecycle.inspect(true, true, false)) }
        assertTrue(session.snapshot().active)
        assertEquals(0, releases)
    }

    @Test fun clientDeathReleasesPolicyEvenWhenBluetoothRemainsConnected() {
        val session = SessionController()
        var released = false
        acquire(session) { released = true }
        val result = SessionLifecycle(session).inspect(false, true, false)!!
        assertEquals(SessionStopCause.CLIENT_DISCONNECTED, result.cause)
        assertTrue(result.restoration.restored)
        assertTrue(released)
    }

    @Test fun bluetoothLossReleasesPolicyWhileClientIsAlive() {
        val session = SessionController()
        acquire(session) {}
        val lifecycle = SessionLifecycle(session)
        val result = lifecycle.inspect(true, false, false)!!
        assertEquals(SessionStopCause.OUTPUT_DISCONNECTED, result.cause)
        assertTrue(result.restoration.restored)
        assertFalse(session.snapshot().active)
        assertNull(lifecycle.inspect(true, false, false))
    }

    @Test fun failedStopIsRetriedEvenWhenOutputAndClientRecover() {
        val session = SessionController()
        var releases = 0
        acquire(session) {
            if (++releases == 1) throw IllegalStateException("audio server temporarily unavailable")
        }
        val lifecycle = SessionLifecycle(session)
        assertFalse(lifecycle.inspect(true, false, false)!!.restoration.restored)
        val retry = lifecycle.inspect(true, true, true)!!
        assertEquals(SessionStopCause.CLEANUP_RETRY, retry.cause)
        assertTrue(retry.restoration.restored)
        assertEquals(2, releases)
    }

    @Test fun shizukuDeathReleasesPolicyEvenWhenClientAndBluetoothRemainAlive() {
        val session = SessionController()
        var released = false
        acquire(session) { released = true }
        val result = SessionLifecycle(session).inspect(
            clientAlive = true,
            outputAvailable = true,
            retryCleanup = false,
            privilegedSessionAlive = false,
        )!!
        assertEquals(SessionStopCause.PRIVILEGED_SESSION_DISCONNECTED, result.cause)
        assertTrue(result.restoration.restored)
        assertTrue(released)
    }
}
