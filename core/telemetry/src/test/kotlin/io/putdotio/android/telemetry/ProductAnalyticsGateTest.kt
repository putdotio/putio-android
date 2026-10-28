package io.putdotio.android.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductAnalyticsGateTest {
    private val sdk = RecordingSdk()
    private var clock = 1_000L
    private val gate = ProductAnalyticsGate(sdk, { it.run() }, now = { clock })

    @Test
    fun nothingStartsOrSendsUntilTheAccountOptsIn() {
        gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Files))
        gate.update(accountId = 7, enabled = false)
        gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Account))
        gate.update(accountId = null, enabled = false)

        assertEquals(listOf("stop"), sdk.calls)
    }

    @Test
    fun eventsBeforeTheFirstAnswerWaitAndKeepTheirTime() {
        gate.capture(AppOpened(Entry.Direct))
        clock = 5_000L

        gate.update(accountId = 7, enabled = true)

        assertEquals(listOf("stop", "stop", "start 7", "app_opened {entry=direct} @1000"), sdk.calls)
    }

    @Test
    fun anOptOutStopsAndDropsWhatFollows() {
        gate.update(accountId = 7, enabled = true)
        gate.update(accountId = 7, enabled = false)
        gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Files))

        assertEquals(listOf("stop", "stop", "start 7", "stop"), sdk.calls)
    }

    @Test
    fun aCaptureStillQueuedAtAnOptOutIsDropped() {
        val queued = ArrayDeque<Runnable>()
        val sdk = RecordingSdk()
        val gate = ProductAnalyticsGate(sdk, { queued.addLast(it) }, now = { clock })
        gate.update(accountId = 7, enabled = true)
        gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Files))
        gate.update(accountId = 7, enabled = false)

        while (queued.isNotEmpty()) queued.removeFirst().run()

        assertEquals(listOf("stop", "stop", "start 7", "stop"), sdk.calls)
    }

    @Test
    fun aRecheckHoldsEventsUntilPutioAnswersAgain() {
        gate.update(accountId = 7, enabled = true)
        gate.hold()
        gate.capture(AppOpened(Entry.Resume))
        gate.update(accountId = 7, enabled = false)

        assertEquals(listOf("stop", "stop", "start 7", "stop"), sdk.calls)
    }

    @Test
    fun aRecheckWithoutAnAnswerSendsOnTheLastOne() {
        gate.update(accountId = 7, enabled = true)
        gate.hold()
        gate.capture(AppOpened(Entry.Resume))
        gate.release()

        assertEquals(listOf("stop", "stop", "start 7", "app_opened {entry=resume} @1000"), sdk.calls)
    }

    @Test
    fun aReleaseBeforeTheFirstAnswerKeepsHolding() {
        gate.capture(AppOpened(Entry.Direct))
        gate.hold()
        gate.release()
        gate.update(accountId = 7, enabled = true)

        assertEquals(listOf("stop", "stop", "start 7", "app_opened {entry=direct} @1000"), sdk.calls)
    }

    @Test
    fun anAccountSwitchStartsAFreshIdentity() {
        gate.update(accountId = 7, enabled = true)
        gate.update(accountId = 7, enabled = true)
        gate.update(accountId = 8, enabled = true)

        assertEquals(listOf("stop", "stop", "start 7", "stop", "start 8"), sdk.calls)
    }

    @Test
    fun aBackgroundWithoutAnOpenSinceTheStartSendsNothing() {
        gate.update(accountId = 7, enabled = true)
        gate.capture(AppBackgrounded(SessionDuration.Under1m))
        gate.capture(AppOpened(Entry.Resume))
        gate.capture(AppBackgrounded(SessionDuration.Under1m))

        assertEquals(
            listOf("app_opened {entry=resume} @1000", "app_backgrounded {session_duration=lt_1m} @1000"),
            sdk.calls.drop(3),
        )
    }

    @Test
    fun onlyTheLatestHeldEventsSurvive() {
        repeat(20) { gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Files)) }

        gate.update(accountId = 7, enabled = true)

        assertEquals(16, sdk.calls.count { it.startsWith("screen_viewed") })
    }

    @Test
    fun aFailingSdkNeverReachesTheCaller() {
        val gate = ProductAnalyticsGate(ThrowingSdk, { it.run() })

        gate.update(accountId = 7, enabled = true)
        gate.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Files))
    }

    private class RecordingSdk : AnalyticsSdk {
        val calls = mutableListOf<String>()

        override fun start(accountId: Long) {
            calls += "start $accountId"
        }

        override fun capture(name: String, properties: Map<String, String>, timestampMillis: Long) {
            calls += "$name $properties @$timestampMillis"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private object ThrowingSdk : AnalyticsSdk {
        override fun start(accountId: Long): Unit = error("start")

        override fun capture(name: String, properties: Map<String, String>, timestampMillis: Long): Unit =
            error("capture")

        override fun stop(): Unit = error("stop")
    }
}
