package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import io.putdotio.android.PutioFailure
import kotlinx.coroutines.flow.first

@Composable
public fun rememberFolderNeighbours(
    state: PlaybackState,
    findNeighbours: (suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours>)?,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackNeighbours {
    val audioReady by rememberUpdatedState(
        state.target.mediaType == PlaybackMediaType.AUDIO &&
            (state.content is PlaybackContent.Ready || state.content == PlaybackContent.Session),
    )
    val target by rememberUpdatedState(state.target)
    val neighbours by produceState(PlaybackNeighbours(), state.target.fileId, findNeighbours != null) {
        value = PlaybackNeighbours()
        val find = findNeighbours ?: return@produceState
        snapshotFlow { audioReady }.first { it }
        value = when (val result = find(target)) {
            is PlaybackRepositoryResult.Success -> result.value
            is PlaybackRepositoryResult.Failure -> PlaybackNeighbours().also {
                if (result.failure.putioFailure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
            }
        }
    }
    return neighbours
}

public fun PlaybackNeighbours.previousAt(positionMillis: Long): PlaybackTarget? =
    previous?.takeIf { positionMillis < PREVIOUS_RESTARTS_AFTER_MILLIS }

internal const val PREVIOUS_RESTARTS_AFTER_MILLIS = 3_000L
