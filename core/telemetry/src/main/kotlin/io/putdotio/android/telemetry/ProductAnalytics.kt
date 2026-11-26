package io.putdotio.android.telemetry

import android.app.Application
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

public object ProductAnalytics {
    @Volatile
    private var gate: ProductAnalyticsGate? = null
    private var appSession: AppSessionAnalytics? = null
    private var accountId: Long? = null

    @Volatile
    private var consentCheck: ((ConsentCheck) -> Unit)? = null

    @Synchronized
    public fun start(application: Application, configuration: ProductAnalyticsConfiguration?) {
        if (gate != null || configuration == null) return
        gate = ProductAnalyticsGate(
            PostHogAnalyticsSdk(application, configuration),
            Executors.newSingleThreadExecutor(),
        )
        appSession = AppSessionAnalytics(::capture, ::recheckConsent)
            .also(application::registerActivityLifecycleCallbacks)
    }

    public fun didOpenLink() {
        appSession?.didOpenLink()
    }

    public fun updateRoute(route: String?) {
        appSession?.route = route
    }

    @Synchronized
    public fun update(accountId: Long?, enabled: Boolean) {
        if (accountId != this.accountId) updateRoute(null)
        this.accountId = accountId
        gate?.update(accountId, enabled)
    }

    public fun setConsentCheck(check: ((ConsentCheck) -> Unit)?) {
        consentCheck = check
    }

    @Synchronized
    public fun confirm(check: ConsentCheck, accountId: Long?, enabled: Boolean?) {
        val current = accountId != null && accountId == this.accountId
        gate?.answer(check.revision, accountId.takeIf { current }, enabled.takeIf { current })
    }

    public fun capture(event: AnalyticsEvent) {
        gate?.capture(event)
    }

    private fun recheckConsent() {
        val check = consentCheck ?: return
        val revision = gate?.hold() ?: return
        check(ConsentCheck(revision))
    }
}

@JvmInline
public value class ConsentCheck internal constructor(internal val revision: Int)

internal interface AnalyticsSdk {
    fun start(accountId: Long)

    fun capture(name: String, properties: Map<String, String>, timestampMillis: Long)

    fun stop()
}

internal class ProductAnalyticsGate(
    private val sdk: AnalyticsSdk,
    private val executor: Executor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var answered = false
    private var rechecking = false
    private var revision = 0
    private var running: Long? = null

    private val generation = AtomicInteger()

    private var hasSentOpen = false
    private val held = ArrayDeque<Pair<AnalyticsEvent, Long>>()

    init {
        submit(sdk::stop)
    }

    @Synchronized
    fun hold(): Int {
        if (answered) {
            answered = false
            rechecking = true
        }
        return ++revision
    }

    @Synchronized
    fun answer(revision: Int, accountId: Long?, enabled: Boolean?) {
        if (revision != this.revision || !rechecking) return
        if (accountId != null && enabled != null) {
            update(accountId, enabled)
        } else {
            rechecking = false
            answered = true
            flushHeld()
        }
    }

    @Synchronized
    fun update(accountId: Long?, enabled: Boolean) {
        answered = true
        rechecking = false
        revision++
        val target = accountId.takeIf { enabled }
        if (target != running) {
            running = target
            hasSentOpen = false
            generation.incrementAndGet()
            submit {
                sdk.stop()
                if (target != null) sdk.start(target)
            }
        }
        flushHeld()
    }

    private fun flushHeld() {
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

    private fun send(event: AnalyticsEvent, at: Long) {
        if (running == null) return
        when (event) {
            is AppOpened -> hasSentOpen = true
            is AppBackgrounded -> if (!hasSentOpen) return
            else -> Unit
        }
        val name = event.name
        val properties = event.properties
        val queuedIn = generation.get()
        submit { if (generation.get() == queuedIn) sdk.capture(name, properties, at) }
    }

    private fun submit(step: () -> Unit) {
        executor.execute { runCatching(step) }
    }

    private companion object {
        const val HELD_LIMIT = 16
    }
}
