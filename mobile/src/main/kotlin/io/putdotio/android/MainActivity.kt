package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.browser.auth.AuthTabIntent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.putdotio.android.account.EXTRA_HARNESS_ANALYTICS_PROMPT
import io.putdotio.android.account.LocalMobileProductAnalyticsPromptMode
import io.putdotio.android.account.MobileProductAnalyticsPromptMode
import io.putdotio.android.auth.handleAuthTabActivityResult
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.share.MobileResumedActivity
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.consumeMobileIncomingTransfer
import io.putdotio.android.transfers.receive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivity : BasePutioActivity() {
    internal val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        handleAuthTabActivityResult(
            context = applicationContext,
            resultCode = result.resultCode,
            rawResultUri = result.resultUri?.toString(),
        )
    }

    internal val transferDraft: MobileTransferDraft by lazy {
        ViewModelProvider(this)[MobileTransferDraft::class.java]
    }

    internal val deepLinkRequests: MobileDeepLinkRequests by lazy {
        ViewModelProvider(this)[MobileDeepLinkRequests::class.java]
    }

    private val pendingNowPlayingRequest = MutableStateFlow(false)

    /** Stays pending until the shell has acted on it, so a recreation mid-flight cannot lose it. */
    internal val nowPlayingRequests: NowPlayingRequests =
        NowPlayingRequests(pendingNowPlayingRequest) { pendingNowPlayingRequest.value = false }

    // Survives a configuration change but not a system destroy, which is exactly the line
    // between "same launch intent again" and "a new instance answering a fresh tap".
    private val launch: NowPlayingLaunchState by lazy { ViewModelProvider(this)[NowPlayingLaunchState::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        // Before this activity resumes, so Cast looks for receivers while it shows.
        MobileCast.controller(this)
        if (savedInstanceState == null) MobileFileShareService.pruneStale(applicationContext)
        consumeShare(intent, savedInstanceState?.getBoolean(STATE_SHARE_CONSUMED) == true)
        consumeDeepLink(intent, savedInstanceState?.getBoolean(STATE_DEEP_LINK_CONSUMED) == true)
        restorePendingDeepLink(savedInstanceState)
        if (shouldPublishLaunchIntent(intent, launch.launchIntentConsumed)) {
            launch.launchIntentConsumed = true
            pendingNowPlayingRequest.value = true
        } else if (savedInstanceState?.getBoolean(STATE_NOW_PLAYING_PENDING) == true) {
            pendingNowPlayingRequest.value = true
        }
        val promptMode = MobileProductAnalyticsPromptMode.of(
            debug = BuildConfig.DEBUG,
            harnessAsks = intent?.getBooleanExtra(EXTRA_HARNESS_ANALYTICS_PROMPT, false) == true,
        )
        setContent {
            CompositionLocalProvider(LocalMobileProductAnalyticsPromptMode provides promptMode) {
                PutioApp(authTabLauncher, nowPlayingRequests, transferDraft, deepLinkRequests)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_NOW_PLAYING_PENDING, pendingNowPlayingRequest.value)
        outState.putBoolean(STATE_SHARE_CONSUMED, launch.shareLaunchConsumed)
        outState.putBoolean(STATE_DEEP_LINK_CONSUMED, launch.deepLinkLaunchConsumed)
        outState.putString(STATE_DEEP_LINK_PENDING, deepLinkRequests.pending.value?.toRouteUri()?.toString())
    }

    override fun onResume() {
        super.onResume()
        MobileResumedActivity.resumed(this)
    }

    override fun onPause() {
        MobileResumedActivity.paused(this)
        super.onPause()
    }

    /** Fills the system Keyboard Shortcuts Helper (Meta + /) with the shortcuts the app takes. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += mobileKeyboardShortcutGroups(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeShare(intent, restoredConsumed = false, freshIntent = true)
        consumeDeepLink(intent, restoredConsumed = false, freshIntent = true)
        launch.launchIntentConsumed = true
        if (intent.isNowPlayingAction) pendingNowPlayingRequest.value = true
    }

    private fun consumeShare(intent: Intent?, restoredConsumed: Boolean, freshIntent: Boolean = false) {
        val incoming = intent?.consumeMobileIncomingTransfer() ?: return
        val consumed = !freshIntent && (launch.shareLaunchConsumed || restoredConsumed)
        launch.shareLaunchConsumed = true
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (consumed || fromHistory) return
        transferDraft.receive(incoming, applicationContext.contentResolver, packageName)
    }

    private fun consumeDeepLink(intent: Intent?, restoredConsumed: Boolean, freshIntent: Boolean = false) {
        val link = intent?.consumeMobileDeepLink() ?: return
        val consumed = !freshIntent && (launch.deepLinkLaunchConsumed || restoredConsumed)
        launch.deepLinkLaunchConsumed = true
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (!consumed && !fromHistory) {
            ProductAnalytics.didOpenLink()
            deepLinkRequests.receive(link)
        }
    }

    /** A link the shell has not routed yet, for example one received signed out, survives process death. */
    private fun restorePendingDeepLink(savedInstanceState: Bundle?) {
        if (deepLinkRequests.pending.value != null) return
        val saved = savedInstanceState?.getString(STATE_DEEP_LINK_PENDING) ?: return
        parseMobileDeepLink(saved.toUri())?.let(deepLinkRequests::receive)
    }

    @androidx.annotation.VisibleForTesting
    internal fun deliverIntentForTest(intent: Intent) = onNewIntent(intent)

    private companion object {
        const val STATE_SHARE_CONSUMED = "shareLaunchConsumed"
        const val STATE_DEEP_LINK_CONSUMED = "deepLinkLaunchConsumed"
        const val STATE_DEEP_LINK_PENDING = "deepLinkPending"
        const val STATE_NOW_PLAYING_PENDING = "nowPlayingPending"
    }
}

internal class NowPlayingLaunchState : ViewModel() {
    var launchIntentConsumed = false
    var shareLaunchConsumed = false
    var deepLinkLaunchConsumed = false
}

/** A request to open the live audio player; [acknowledge] clears it once handled. */
internal class NowPlayingRequests(
    internal val pending: StateFlow<Boolean>,
    internal val acknowledge: () -> Unit,
) {
    internal companion object {
        val None = NowPlayingRequests(MutableStateFlow(false)) {}
    }
}

private val Intent?.isNowPlayingAction: Boolean
    get() = this?.action == MobilePlaybackService.ACTION_OPEN_NOW_PLAYING

// A task reopened from recents replays its original launch intent; that is not a new tap.
internal fun shouldPublishLaunchIntent(
    intent: Intent?,
    alreadyConsumed: Boolean,
): Boolean =
    intent.isNowPlayingAction &&
        !alreadyConsumed &&
        intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
