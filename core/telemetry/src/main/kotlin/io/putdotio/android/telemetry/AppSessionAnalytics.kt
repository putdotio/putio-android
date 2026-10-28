package io.putdotio.android.telemetry

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Turns the app's foregrounds into `app_opened` and `app_backgrounded`. Main thread only, as
 * Android calls activity callbacks. The open waits for the first resume, since a link to a stopped
 * activity arrives through `onNewIntent` after `onStart`.
 */
internal class AppSessionAnalytics(
    private val capture: (AnalyticsEvent) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : Application.ActivityLifecycleCallbacks {
    /** The account's tunnel route name, null until its settings load. */
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
            openedFromLink -> AnalyticsEvent.Entry.Deeplink
            hasOpened -> AnalyticsEvent.Entry.Resume
            else -> AnalyticsEvent.Entry.Direct
        }
        opensOnResume = false
        openedFromLink = false
        hasOpened = true
        activeSince = now()
        capture(AnalyticsEvent.AppOpened(entry, route))
    }

    override fun onActivityStopped(activity: Activity) {
        started--
        // Rotation stops and restarts the activity; that is no background.
        if (started > 0 || activity.isChangingConfigurations) return
        openedFromLink = false
        val since = activeSince ?: return
        activeSince = null
        capture(AnalyticsEvent.AppBackgrounded(AnalyticsEvent.SessionDuration.of(now() - since)))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit

    override fun onActivityPaused(activity: Activity): Unit = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit

    override fun onActivityDestroyed(activity: Activity): Unit = Unit
}
