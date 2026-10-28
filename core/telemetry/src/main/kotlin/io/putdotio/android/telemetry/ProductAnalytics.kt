package io.putdotio.android.telemetry

import android.app.Application
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The app's only PostHog entry point: detekt rejects `com.posthog` imports anywhere else, so every
 * event leaves through [AnalyticsAllowlist]. It sends only from a release build with a project
 * token, for a signed-in account whose `product_analytics_enabled` is on.
 */
public object ProductAnalytics {
    @Volatile
    private var gate: ProductAnalyticsGate? = null
    private var appSession: AppSessionAnalytics? = null

    /** Once per process, from `Application.onCreate`; a null [configuration] keeps PostHog off. */
    @Synchronized
    public fun start(application: Application, configuration: ProductAnalyticsConfiguration?) {
        if (gate != null || configuration == null) return
        gate = ProductAnalyticsGate(
            PostHogAnalyticsSdk(application, configuration),
            Executors.newSingleThreadExecutor(),
        )
        appSession = AppSessionAnalytics(::capture).also(application::registerActivityLifecycleCallbacks)
    }

    /** A link or launcher card opened the app; call before the activity resumes. Main thread only. */
    public fun didOpenLink() {
        appSession?.didOpenLink()
    }

    /** The account's answer: a null [accountId] is signed out, which counts as off. */
    public fun update(accountId: Long?, enabled: Boolean) {
        gate?.update(accountId, enabled)
    }

    public fun capture(event: AnalyticsEvent) {
        gate?.capture(event)
    }
}

/** What the gate needs from PostHog; every call runs on the gate's executor, in order. */
internal interface AnalyticsSdk {
    /** A fresh install identified as [accountId]; [stop] always runs first. */
    fun start(accountId: Long)

    fun capture(name: String, properties: Map<String, String>, timestampMillis: Long)

    /** Closes the SDK and deletes everything it stored, so nothing outlives an opt-out. */
    fun stop()
}

/**
 * Starts PostHog only once the account opts in, so nothing, not even its ids, exists on the device
 * before then. Events from before this launch's first answer, such as the cold launch's
 * `app_opened`, are held and dropped if that answer is off.
 */
internal class ProductAnalyticsGate(
    private val sdk: AnalyticsSdk,
    private val executor: Executor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var answered = false
    private var running: Long? = null

    // Without an open since the start, a background would time a session that began before consent.
    private var hasSentOpen = false
    private val held = ArrayDeque<Pair<AnalyticsEvent, Long>>()

    init {
        // A killed process may have left a queue or ids from an earlier account or opt-in.
        submit(sdk::stop)
    }

    @Synchronized
    fun update(accountId: Long?, enabled: Boolean) {
        answered = true
        val target = accountId.takeIf { enabled }
        if (target != running) {
            running = target
            hasSentOpen = false
            submit {
                sdk.stop()
                if (target != null) sdk.start(target)
            }
        }
        val pending = held.toList()
        held.clear()
        pending.forEach { (event, at) -> send(event, at) }
    }

    @Synchronized
    fun capture(event: AnalyticsEvent) {
        val at = now()
        if (answered) {
            send(event, at)
        } else {
            held.addLast(event to at)
            if (held.size > HELD_LIMIT) held.removeFirst()
        }
    }

    // A held event keeps the time it happened, not the time the answer came.
    private fun send(event: AnalyticsEvent, at: Long) {
        if (running == null) return
        when (event) {
            is AnalyticsEvent.AppOpened -> hasSentOpen = true
            is AnalyticsEvent.AppBackgrounded -> if (!hasSentOpen) return
            else -> Unit
        }
        val name = event.name
        val properties = event.properties
        submit { sdk.capture(name, properties, at) }
    }

    // PostHog failing never breaks a flow.
    private fun submit(step: () -> Unit) {
        executor.execute { runCatching(step) }
    }

    private companion object {
        const val HELD_LIMIT = 16
    }
}
