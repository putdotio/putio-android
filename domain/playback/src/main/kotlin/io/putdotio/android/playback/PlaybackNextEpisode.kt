package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.putdotio.android.PutioFailure

/**
 * With the account's next_episode on, a playing video asks autoplay's folder walk for the one after
 * it, once. A failed walk offers none; a rejected session still ends.
 */
@Composable
public fun rememberNextEpisode(
    state: PlaybackState,
    findNextEpisode: (suspend (PlaybackTarget) -> PlaybackNextResult)?,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackTarget? {
    val ready = state.content is PlaybackContent.Ready && state.target.mediaType == PlaybackMediaType.VIDEO
    val nextEpisode by produceState<PlaybackTarget?>(null, state.target.fileId, ready, findNextEpisode != null) {
        value = null
        val find = findNextEpisode ?: return@produceState
        if (!ready) return@produceState
        when (val next = find(state.target)) {
            is PlaybackNextResult.Found -> value = next.target
            is PlaybackNextResult.Failure ->
                if (next.failure.putioFailure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
            PlaybackNextResult.Ended -> Unit
        }
    }
    return nextEpisode
}
