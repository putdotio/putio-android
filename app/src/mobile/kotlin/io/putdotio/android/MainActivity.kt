package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.browser.auth.AuthTabIntent
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

    private val pendingNowPlayingRequest = MutableStateFlow(false)

    /** Stays pending until the shell has acted on it, so a recreation mid-flight cannot lose it. */
    internal val nowPlayingRequests: NowPlayingRequests =
        NowPlayingRequests(pendingNowPlayingRequest) { pendingNowPlayingRequest.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        when {
            savedInstanceState == null -> publishNowPlayingRequest(intent)
            savedInstanceState.getBoolean(STATE_NOW_PLAYING_PENDING) -> pendingNowPlayingRequest.value = true
        }
        setContent {
            PutioApp(authTabLauncher, nowPlayingRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        publishNowPlayingRequest(intent)
    }

    @androidx.annotation.VisibleForTesting
    internal fun deliverIntentForTest(intent: Intent) = onNewIntent(intent)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_NOW_PLAYING_PENDING, pendingNowPlayingRequest.value)
    }

    private fun publishNowPlayingRequest(intent: Intent?) {
        if (intent?.action == MobilePlaybackService.ACTION_OPEN_NOW_PLAYING) pendingNowPlayingRequest.value = true
    }

    private companion object {
        const val STATE_NOW_PLAYING_PENDING = "nowPlayingPending"
    }
}

/** A request to open the live audio player; [acknowledge] clears it once handled. */
class NowPlayingRequests internal constructor(
    internal val pending: StateFlow<Boolean>,
    internal val acknowledge: () -> Unit,
) {
    internal companion object {
        val None = NowPlayingRequests(MutableStateFlow(false)) {}
    }
}
