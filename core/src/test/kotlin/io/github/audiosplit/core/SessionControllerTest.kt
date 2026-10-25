package io.github.audiosplit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    private fun step(name: String, acquire: () -> SessionLease) = object : SessionStep {
        override val name = name
        override fun acquire() = acquire.invoke()
    }

    @Test fun secondMixFailureRollsBackFirstMix() {
        val events = mutableListOf<String>()
        val controller = SessionController()
        val result = controller.begin(listOf(
            step("policy") {
                events += "register first"
                SessionLease { events += "unregister policy" }
            },
            step("second mix") { throw IllegalStateException("HAL refused second device") },
        ))
        assertFalse(result.applied)
        assertFalse(result.snapshot.active)
        assertEquals(listOf("register first", "unregister policy"), events)
    }

    @Test fun restoreReleasesInReverseOrderAndIsIdempotent() {
        val events = mutableListOf<String>()
        val controller = SessionController()
        assertTrue(controller.begin(listOf(
            step("first") { SessionLease { events += "first" } },
            step("second") { SessionLease { events += "second" } },
        )).applied)
        assertTrue(controller.restore().restored)
        assertTrue(controller.restore().restored)
        assertEquals(listOf("second", "first"), events)
    }

    @Test fun platformLinkageFailureAlsoRollsBackRegisteredPolicy() {
        var released = false
        val controller = SessionController()
        val result = controller.begin(listOf(
            step("policy") { SessionLease { released = true } },
            step("second mix") { throw NoSuchMethodError("OEM changed the framework") },
        ))
        assertFalse(result.applied)
        assertFalse(result.snapshot.active)
        assertTrue(released)
        assertTrue(result.error is LinkageError)
    }

    @Test fun failedCleanupRemainsOwnedAndCanBeRetried() {
        var attempts = 0
        val controller = SessionController()
        controller.begin(listOf(step("policy") {
            SessionLease {
                attempts++
                if (attempts == 1) throw IllegalStateException("audio service unavailable")
            }
        }))
        val first = controller.restore()
        assertFalse(first.restored)
        assertEquals(listOf("policy"), first.snapshot.retainedResources)
        assertEquals("policy", first.failures.single().name)
        assertTrue(controller.restore().restored)
        assertEquals(2, attempts)
    }

    @Test fun failedAcquisitionReportsItsRollbackFailure() {
        val controller = SessionController()
        val result = controller.begin(listOf(
            step("policy") { SessionLease { throw IllegalStateException("cleanup failed") } },
            step("second") { throw IllegalArgumentException("registration failed") },
        ))
        assertFalse(result.applied)
        assertTrue(result.snapshot.active)
        assertEquals("registration failed", result.error?.message)
        assertEquals("cleanup failed", result.cleanupFailures.single().error.message)
    }

    @Test(expected = IllegalStateException::class)
    fun activeSessionCannotBeOverwritten() {
        val controller = SessionController()
        val first = step("policy") { SessionLease {} }
        controller.begin(listOf(first))
        controller.begin(listOf(first))
    }

    @Test fun cleanupInstalledBeforeRegistrationCoversPartialRegistrationFailure() {
        var policyPresent = false
        val controller = SessionController()
        val result = controller.begin(listOf(
            step("cleanup guard") { SessionLease { policyPresent = false } },
            step("registration") {
                policyPresent = true
                throw IllegalStateException("service failed after making a partial change")
            },
        ))
        assertFalse(result.applied)
        assertFalse(policyPresent)
        assertFalse(result.snapshot.active)
    }

    @Test fun restoredSessionCanBeReplacedWithoutRetainingOldResources() {
        val events = mutableListOf<String>()
        val controller = SessionController()
        controller.begin(listOf(step("old") { SessionLease { events += "old" } }))
        controller.restore()
        val second = controller.begin(listOf(step("new") { SessionLease { events += "new" } }))
        assertTrue(second.applied)
        assertEquals(listOf("new"), second.snapshot.retainedResources)
        controller.restore()
        assertEquals(listOf("old", "new"), events)
    }
}
