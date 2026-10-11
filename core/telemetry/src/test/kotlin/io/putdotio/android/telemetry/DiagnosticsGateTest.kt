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

        assertEquals(listOf("start at launch"), runtime.calls)
        assertTrue(gate.isCapturing)
        assertTrue(runtime.filters.single().admits(clock))
    }

    @Test
    fun anOptOutStopsCaptureAndPurgesAtOnceAndOptingInResumes() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        val recordedWhileOn = clock

        gate.accountAnswered(SESSION, enabled = false)
        gate.capture(FAILURE)
        assertFalse(gate.isCapturing)
        assertFalse(runtime.filters.single().admits(recordedWhileOn))
        assertEquals("a failure was captured while off", listOf("start at launch", "stop"), runtime.calls)

        val recordedWhileOff = ++clock
        clock += 1_000
        gate.accountAnswered(SESSION, enabled = true)
        gate.capture(FAILURE)
        assertEquals(listOf("start at launch", "stop", "start", "capture"), runtime.calls)
        val reopened = runtime.filters.last()
        assertTrue(reopened.admits(clock))
        assertFalse("a report from the opt-out period left after opting in", reopened.admits(recordedWhileOff))
    }

    @Test
    fun anSdkStillClosingAfterAnOptOutCannotUploadThroughALaterOptIn() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        val closing = runtime.filters.single()

        gate.accountAnswered(SESSION, enabled = false)
        gate.accountAnswered(SESSION, enabled = true)

        assertTrue(gate.isCapturing)
        assertFalse("the closing SDK's transport reopened", closing.isCapturing)
        assertFalse(closing.admits(clock))
        assertTrue(runtime.filters.last().isCapturing)
    }

    @Test
    fun theAccountsAnswerAtSignInApplies() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = false))

        assertFalse(gate.isCapturing)
        assertEquals(listOf("start at launch", "stop"), runtime.calls)
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
        assertEquals(listOf("start at launch", "stop", "start"), runtime.calls)
    }

    @Test
    fun anOptOutStopsCaptureBeforeItsWriteAndQueuesThePurgeAfter() {
        lateinit var gate: DiagnosticsGate
        store.onAnswerWrite = { runtime.calls += "write ${store.answer}, capturing ${gate.isCapturing}" }
        gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))

        gate.accountAnswered(SESSION, enabled = false)

        assertEquals(
            listOf("start at launch", "write true, capturing true", "write false, capturing false", "stop"),
            runtime.calls,
        )
    }

    @Test
    fun repeatedAnswersWriteTheStoreOnce() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        repeat(3) { gate.accountAnswered(SESSION, enabled = true) }
        repeat(2) { gate.update(DiagnosticsSession.SignedOut) }

        assertEquals(2, store.answerWrites)
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
        assertEquals(listOf("start at launch"), runtime.calls)
    }

    private fun gate(configuration: SentryConfiguration? = this.configuration) =
        DiagnosticsGate(configuration, DiagnosticsConsent(store) { clock }, runtime)

    private class MemoryStore : DiagnosticsConsentStore {
        var answerWrites = 0
        var onAnswerWrite: () -> Unit = {}
        override var answer: Boolean? = null
            set(value) {
                answerWrites++
                field = value
                onAnswerWrite()
            }
        override var enabledSince: Long? = null
    }

    private class RecordingRuntime : DiagnosticsRuntime {
        val calls = mutableListOf<String>()
        val filters = mutableListOf<DiagnosticsFilter>()

        override fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter, launch: Boolean) {
            assertTrue("started while the filter refuses", filter.isCapturing)
            filters += filter
            calls += if (launch) "start at launch" else "start"
        }

        override fun stopAndPurge() {
            calls += "stop"
        }

        override fun capture(failure: TelemetryFailure) {
            calls += "capture"
        }
    }

    private companion object {
        const val SESSION = "session-1"
        val FAILURE = TelemetryFailure("playback_failure", listOf("network"), emptyMap())
    }
}
