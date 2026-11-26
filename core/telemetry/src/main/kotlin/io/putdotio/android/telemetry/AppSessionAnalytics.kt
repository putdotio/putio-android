package io.putdotio.android.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class AppSessionAnalytics(
    private val capture: (AnalyticsEvent) -> Unit,
    private val onReturn: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : Application.ActivityLifecycleCallbacks {
    @Volatile
    var route: String? = null

    private var started = 0
    private var opensOnResume = false
    private var openedFromLink = false
    private var hasOpened = false
    private var activeSince: Long? = null

    fun didOpenLink() {
        openedFromLink = true
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0 && activeSince == null) opensOnResume = true
    }

    override fun onActivityResumed(activity: Activity) {
        if (!opensOnResume) return
        val entry = when {
            openedFromLink -> Entry.Deeplink
            hasOpened -> Entry.Resume
            else -> Entry.Direct
        }
        opensOnResume = false
        openedFromLink = false
        if (hasOpened) onReturn()
        hasOpened = true
        activeSince = now()
        capture(AppOpened(entry, route))
    }

    override fun onActivityStopped(activity: Activity) {
        started--
        if (started > 0 || activity.isChangingConfigurations) return
        openedFromLink = false
        val since = activeSince ?: return
        activeSince = null
        capture(AppBackgrounded(SessionDuration.of(now() - since)))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit

    override fun onActivityPaused(activity: Activity): Unit = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit

    override fun onActivityDestroyed(activity: Activity): Unit = Unit
}
