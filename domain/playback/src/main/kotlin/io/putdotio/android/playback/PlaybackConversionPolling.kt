package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

@Composable
public fun PlaybackConversionPolling(conversion: PlaybackContent.Conversion, onRefresh: () -> Unit) {
    val foreground by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val polling = conversion.refreshRequestId == null &&
        conversion.state.pollsAutomatically &&
        foreground.isAtLeast(Lifecycle.State.STARTED)
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(conversion, polling) {
        while (polling) {
            delay(PLAYBACK_CONVERSION_POLL_MILLIS)
            refresh()
        }
    }
}
