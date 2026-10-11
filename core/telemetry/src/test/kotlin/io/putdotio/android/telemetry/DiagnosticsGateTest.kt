package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))

        assertEquals(listOf("stop"), runtime.calls)
    }

    @Test
    fun noAnswerYetCountsAsOn() {
        val gate = gate().apply { start() }

        assertEquals(listOf("start at launch"), runtime.calls)
        assertTrue(gate.isCapturing)
    }

    @Test
    fun anOptOutStopsAndPurgesAtOnceAndNothingFromItLeavesAfterOptingIn() {
        lateinit var gate: DiagnosticsGate
        store.onAnswerWrite = { runtime.calls += "write ${store.answer}, capturing ${gate.isCapturing}" }
        gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        val closing = runtime.filters.single()

        gate.accountAnswered(SESSION, enabled = false)
        gate.capture(FAILURE)
        val recordedWhileOff = ++clock
        clock += 1_000
        gate.accountAnswered(SESSION, enabled = true)

        assertEquals(
            listOf(
                "start at launch",
                "write false, capturing false",
                "stop",
                "write true, capturing false",
                "start",
            ),
            runtime.calls,
        )
        assertFalse("the closing SDK's transport reopened", closing.isCapturing)
        assertFalse("a report from the opt-out period left", runtime.filters.last().admits(recordedWhileOff))
        assertTrue(runtime.filters.last().admits(clock))
    }

    @Test
    fun aColdLaunchHonoursTheLastAnswerUntilTheSessionResolves() {
        store.answer = false
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.Unresolved)
        assertEquals("a launch with diagnostics off must purge what an earlier run left", listOf("stop"), runtime.calls)

        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = true))
        assertEquals(listOf("stop", "start"), runtime.calls)
    }

    @Test
    fun signingOutTurnsDiagnosticsBackOnAndIgnoresTheDepartedSession() {
        val gate = gate().apply { start() }
        gate.update(DiagnosticsSession.SignedIn(SESSION, diagnosticsEnabled = false))
        gate.update(DiagnosticsSession.Unresolved)
        assertFalse("signing out dropped the account's opt-out", gate.isCapturing)

        gate.update(DiagnosticsSession.SignedOut)
        gate.accountAnswered(SESSION, enabled = false)

        assertTrue(gate.isCapturing)
        assertEquals(listOf("start at launch", "stop", "start"), runtime.calls)
    }

    private fun gate(configuration: SentryConfiguration? = this.configuration) =
        DiagnosticsGate(configuration, store, runtime) { clock }

    private class MemoryStore : DiagnosticsConsentStore {
        var onAnswerWrite: () -> Unit = {}
        override var answer: Boolean = true
            set(value) {
                field = value
                onAnswerWrite()
            }
        override var enabledSince: Long? = null
    }

    private class RecordingRuntime : DiagnosticsRuntime {
        val calls = mutableListOf<String>()
        val filters = mutableListOf<DiagnosticsFilter>()

        override fun start(configuration: SentryConfiguration, filter: DiagnosticsFilter, launch: Boolean) {
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
