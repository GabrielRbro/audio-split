package io.github.audiosplit.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMismatchMonitorTest {
    private val expected = mapOf(10001 to "bluetooth", 10002 to "speaker")
    private fun playing(uid: Int, vararg devices: String) = PlaybackRouteObservation(uid, true, devices.toSet())
    private val collapsed = listOf(playing(10001, "bluetooth"), playing(10002, "bluetooth"))
    private val separated = listOf(playing(10001, "bluetooth"), playing(10002, "speaker"))

    @Test fun collapseRequiresGraceAndThreeConsistentSamples() {
        val monitor = RouteMismatchMonitor(expected)
        assertFalse(monitor.sample(1_500, collapsed))
        assertFalse(monitor.sample(3_000, collapsed))
        assertFalse(monitor.sample(4_500, collapsed))
        assertTrue(monitor.sample(6_000, collapsed))
    }

    @Test fun correctRoutesResetTransientMismatch() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        assertFalse(monitor.sample(0, collapsed))
        assertFalse(monitor.sample(1_500, collapsed))
        assertFalse(monitor.sample(3_000, separated))
        assertFalse(monitor.sample(4_500, collapsed))
        assertFalse(monitor.sample(6_000, collapsed))
    }

    @Test fun missingOrUnknownRoutesNeverCountAsMismatch() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        for (devices in listOf<Set<String>?>(null, emptySet())) {
            repeat(5) {
                assertFalse(monitor.sample(it.toLong(), listOf(
                    playing(10001, "bluetooth"), PlaybackRouteObservation(10002, true, devices),
                )))
            }
        }
    }

    @Test fun bothSelectedAppsMustBeActiveAndKnown() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        for (state in listOf<Boolean?>(false, null)) {
            repeat(5) {
                assertFalse(monitor.sample(it.toLong(), listOf(
                    playing(10001, "speaker"), PlaybackRouteObservation(10002, state, setOf("speaker")),
                )))
            }
        }
        repeat(5) { assertFalse(monitor.sample(it.toLong(), listOf(playing(10001, "speaker")))) }
    }

    @Test fun changingWrongRoutesRestartTheObservationWindow() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        assertFalse(monitor.sample(0, collapsed))
        assertFalse(monitor.sample(1_500, collapsed))
        val moved = listOf(playing(10001, "speaker"), playing(10002, "speaker"))
        assertFalse(monitor.sample(3_000, moved))
        assertFalse(monitor.sample(4_500, moved))
        assertTrue(monitor.sample(6_000, moved))
    }

    @Test fun everyActivePlayerMustStayOnItsRequestedDevice() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        val leak = separated + playing(10002, "bluetooth", "speaker")
        repeat(2) { assertFalse(monitor.sample(it.toLong(), leak)) }
        assertTrue(monitor.sample(2, leak))
    }

    @Test fun inactiveAndUnrelatedPlayersDoNotCauseFailures() {
        val monitor = RouteMismatchMonitor(expected, gracePeriodMs = 0)
        val observations = separated + PlaybackRouteObservation(10002, false, null) + playing(99999, "other")
        repeat(6) { assertFalse(monitor.sample(it.toLong(), observations)) }
    }
}
