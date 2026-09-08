package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.browser.auth.AuthTabIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : BasePutioActivity() {
    internal val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        handleAuthTabActivityResult(
            context = applicationContext,
            resultCode = result.resultCode,
            rawResultUri = result.resultUri?.toString(),
        )
    }

    // A channel holds a request until the shell collects it and hands it over exactly once;
    // a shared flow without replay would drop it before the first collector exists.
    private val nowPlayingRequests = Channel<Unit>(Channel.CONFLATED)
    private val nowPlayingRequestFlow: NowPlayingRequests = nowPlayingRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        // A restored Activity can still carry a fresh notification tap; only a consumed one is skipped.
        if (savedInstanceState?.getBoolean(STATE_NOW_PLAYING_CONSUMED) != true) publishNowPlayingRequest(intent)
        setContent {
            PutioApp(authTabLauncher, nowPlayingRequestFlow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        publishNowPlayingRequest(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_NOW_PLAYING_CONSUMED, nowPlayingConsumed)
    }

    private var nowPlayingConsumed = false

    private fun publishNowPlayingRequest(intent: Intent?) {
        if (intent?.action != MobilePlaybackService.ACTION_OPEN_NOW_PLAYING) return
        nowPlayingConsumed = true
        nowPlayingRequests.trySend(Unit)
    }

    private companion object {
        const val STATE_NOW_PLAYING_CONSUMED = "nowPlayingConsumed"
    }
}

internal typealias NowPlayingRequests = Flow<Unit>
