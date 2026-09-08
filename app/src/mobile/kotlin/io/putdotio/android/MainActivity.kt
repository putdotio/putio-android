package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.browser.auth.AuthTabIntent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : BasePutioActivity() {
    internal val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        handleAuthTabActivityResult(
            context = applicationContext,
            resultCode = result.resultCode,
            rawResultUri = result.resultUri?.toString(),
        )
    }

    // Replayed once so a request that arrives before the shell exists is not lost.
    private val nowPlayingRequests =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val nowPlayingRequestFlow: NowPlayingRequests = nowPlayingRequests.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        if (savedInstanceState == null) publishNowPlayingRequest(intent)
        setContent {
            PutioApp(authTabLauncher, nowPlayingRequestFlow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        publishNowPlayingRequest(intent)
    }

    private fun publishNowPlayingRequest(intent: Intent?) {
        if (intent?.action == MobilePlaybackService.ACTION_OPEN_NOW_PLAYING) nowPlayingRequests.tryEmit(Unit)
    }
}

internal typealias NowPlayingRequests = Flow<Unit>
