package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsGateTest {
    private val configuration =
        checkNotNull(SentryConfiguration.of("https://synthetickey@o0.ingest.example.invalid/1", "nightly", "r", "tv"))
    private val store = MemoryStore()
    private val runtime = RecordingRuntime()
    private var clock = 1_000L

    @Test
    fun aBuildWithoutADsnNeverReportsAndClearsWhatAnEarlierBuildLeft() {
        val gate = gate(configuration = null).apply { start() }
        gate.update(DiagnosticsSession.SignedOut)
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        gate.accountAnswered(SESSION, enabled = true)

        assertEquals(listOf("stop"), runtime.calls)
        assertFalse(gate.isCapturing)
    }

    @Test
    fun noAnswerYetCountsAsOn() {
        val gate = gate().apply { start() }

        assertEquals(listOf("start"), runtime.calls)
        assertTrue(gate.isCapturing)
        assertTrue(gate.admits(clock))
    }

    @Test
    fun anOptOutStopsCaptureAndPurgesAtOnceAndOptingInResumes() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        val recordedWhileOn = clock

        gate.accountAnswered(SESSION, enabled = false)
        assertFalse(gate.isCapturing)
        assertFalse(gate.admits(recordedWhileOn))
        assertEquals(listOf("start", "stop"), runtime.calls)

        val recordedWhileOff = ++clock
        clock += 1_000
        gate.accountAnswered(SESSION, enabled = true)
        assertEquals(listOf("start", "stop", "start"), runtime.calls)
        assertTrue(gate.admits(clock))
        assertFalse("a report from the opt-out period left after opting in", gate.admits(recordedWhileOff))
    }

    @Test
    fun theAccountsAnswerAtSignInApplies() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = false))

        assertFalse(gate.isCapturing)
        assertEquals(listOf("start", "stop"), runtime.calls)
        assertEquals(false, store.answer)
    }

    @Test
    fun signingOutKeepsTheOptOutUntilSignedOutThenTurnsDiagnosticsBackOn() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = false))

        gate.update(DiagnosticsSession.Unresolved)
        assertFalse("signing out dropped the account's opt-out", gate.isCapturing)

        gate.update(DiagnosticsSession.SignedOut)
        assertTrue(gate.isCapturing)
        assertNull("the signed-out device kept the departed account's answer", store.answer)
        assertEquals(listOf("start", "stop", "start"), runtime.calls)
    }

    @Test
    fun aColdLaunchHonoursTheLastAnswerUntilTheSessionResolves() {
        store.answer = false
        val gate = gate().apply { start() }
        assertFalse(gate.isCapturing)
        assertEquals("a launch with diagnostics off must purge what an earlier run left", listOf("stop"), runtime.calls)

        gate.update(DiagnosticsSession.Unresolved)
        assertFalse(gate.isCapturing)

        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        assertTrue(gate.isCapturing)
        assertEquals(listOf("stop", "start"), runtime.calls)
    }

    @Test
    fun aDepartedSessionCannotOverruleTheNextOne() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        gate.update(DiagnosticsSession.Unresolved)
        gate.update(DiagnosticsSession.SignedOut)

        gate.accountAnswered(SESSION, enabled = false)
        assertTrue(gate.isCapturing)

        gate.update(DiagnosticsSession.SignedIn("next", diagnosticsEnabled = true))
        gate.accountAnswered(SESSION, enabled = false)
        assertTrue(gate.isCapturing)
        assertEquals(listOf("start"), runtime.calls)
    }

    private fun gate(configuration: SentryConfiguration? = this.configuration) =
        DiagnosticsGate(configuration, DiagnosticsConsent(store) { clock }, runtime)

    private class MemoryStore : DiagnosticsConsentStore {
        override var answer: Boolean? = null
        override var enabledSince: Long? = null
    }

    private class RecordingRuntime : DiagnosticsRuntime {
        val calls = mutableListOf<String>()

        override fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter) {
            assertTrue("started while the filter refuses", filter.isCapturing)
            calls += "start"
        }

        override fun stopAndPurge() {
            calls += "stop"
        }
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
