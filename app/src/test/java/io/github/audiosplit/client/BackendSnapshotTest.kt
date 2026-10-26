package io.github.audiosplit.client

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSnapshotTest {
    private fun report(code: String = "READY", active: Boolean = false): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("phase", "diagnostic")
        .put("code", code)
        .put("message", "Resultado do sistema")
        .put("active", active)
        .put("devices", JSONArray())
        .put("packages", JSONArray())

    private fun applicableReport(code: String = "READY", preflightPassed: Boolean = true): JSONObject = report(code)
        .put("details", JSONObject().put("preflightPassed", preflightPassed))
        .put("devices", JSONArray()
            .put(JSONObject().put("key", "a2dp").put("label", "Caixa").put("type", 8)))
        .put("packages", JSONArray()
            .put(JSONObject().put("packageName", "com.spotify.music").put("installed", true))
            .put(JSONObject().put("packageName", "com.instagram.android").put("installed", true)))

    private fun applicableState(json: JSONObject = applicableReport()): AudioSplitUiState = AudioSplitUiState(
        backendConnected = true,
        testMode = false,
        selectedBluetoothKey = "a2dp",
        snapshot = BackendSnapshot.parse(json.toString()),
    )

    @Test fun internalSpeakerIsNeverOfferedAsBluetoothDestination() {
        val json = report().put("devices", JSONArray()
            .put(JSONObject().put("key", "speaker").put("label", "Celular").put("type", 2))
            .put(JSONObject().put("key", "a2dp").put("label", "Caixa").put("type", 8))
            .put(JSONObject().put("key", "ble").put("label", "Caixa LE").put("type", 27)))
        val parsed = BackendSnapshot.parse(json.toString())
        assertEquals(listOf("a2dp", "ble"), parsed.bluetoothDevices.map { it.key })
        assertEquals(3, parsed.devices.size)
    }

    @Test fun failedCleanupKeepsRestorationAvailableDespiteErrorCode() {
        val parsed = BackendSnapshot.parse(report(code = "ERROR", active = true).toString())
        val state = AudioSplitUiState(backendConnected = true, snapshot = parsed)
        assertTrue(state.active)
        assertTrue(state.controlsEnabled)
        assertFalse(state.canApply)
        assertEquals("ERROR", parsed.code)
    }

    @Test fun missingEitherTargetPreventsApplyingTheSelectedScenario() {
        val json = report().put("packages", JSONArray()
            .put(JSONObject().put("packageName", "com.spotify.music").put("installed", true))
            .put(JSONObject().put("packageName", "com.instagram.android").put("installed", false)))
        val state = AudioSplitUiState(testMode = false, snapshot = BackendSnapshot.parse(json.toString()))
        assertFalse(state.targetsInstalled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSchemaFailsBeforeConsumingUnrecognizedProtocol() {
        BackendSnapshot.parse(report().put("schemaVersion", 2).toString())
    }

    @Test fun additiveDiagnosticFieldsRemainCompatibleAndPreservedForExport() {
        val raw = report().put("futureDiagnosticField", JSONObject().put("example", true)).toString()
        val parsed = BackendSnapshot.parse(raw)
        assertEquals("READY", parsed.code)
        assertTrue(JSONObject(parsed.raw).getJSONObject("futureDiagnosticField").getBoolean("example"))
    }

    @Test fun validPreflightWithInstalledTargetsAndCurrentBluetoothAllowsApply() {
        assertTrue(applicableState().canApply)
    }

    @Test fun blockedDiagnosticPreventsApplyEvenWhenShizukuIsConnected() {
        for (code in listOf("PERMISSION_DENIED", "UNSUPPORTED", "ERROR", "NO_BLUETOOTH", "DISCONNECTED")) {
            val state = applicableState(applicableReport(code = code))
            assertTrue(state.controlsEnabled)
            assertFalse("Blocked diagnostic must not enable Apply: $code", state.canApply)
        }
    }

    @Test fun oldServiceWithoutPreflightFlagDoesNotEnableApply() {
        val json = applicableReport()
        json.remove("details")
        val state = applicableState(json)
        assertFalse(state.snapshot!!.preflightPassed)
        assertFalse(state.canApply)
    }

    @Test fun readyLabelAloneDoesNotSubstituteForPassedPreflight() {
        assertFalse(applicableState(applicableReport(preflightPassed = false)).canApply)
    }

    @Test fun busyConnectingOrDisconnectedClientDoesNotAllowApply() {
        val state = applicableState()
        assertFalse(state.copy(busy = true).canApply)
        assertFalse(state.copy(connecting = true).canApply)
        assertFalse(state.copy(backendConnected = false).canApply)
    }

    @Test fun staleBluetoothSelectionDoesNotAllowApply() {
        assertFalse(applicableState().copy(selectedBluetoothKey = "old-device").canApply)
    }

    @Test fun restoredSessionCanBeAppliedAgainWithPassedPreflight() {
        assertTrue(applicableState(applicableReport(code = "RESTORED")).canApply)
    }
}
