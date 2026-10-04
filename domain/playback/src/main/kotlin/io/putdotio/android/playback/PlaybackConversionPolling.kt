package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/**
 * Reads a queued or running [conversion] again every [PLAYBACK_CONVERSION_POLL_MILLIS] while the
 * app is in the foreground and no read is in flight, as tv-native's `useConversionStatus` did.
 */
@Composable
public fun PlaybackConversionPolling(conversion: PlaybackContent.Conversion, onRefresh: () -> Unit) {
    val foreground by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val polling = conversion.refreshRequestId == null &&
        conversion.state.pollsAutomatically &&
        foreground.isAtLeast(Lifecycle.State.STARTED)
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(conversion, polling) {
        // A read that starts and settles between frames can leave `conversion` equal to the last
        // one (Queued again, the same percent), so the wait repeats rather than relying on a change.
        while (polling) {
            delay(PLAYBACK_CONVERSION_POLL_MILLIS)
            refresh()
        }
    }
}
