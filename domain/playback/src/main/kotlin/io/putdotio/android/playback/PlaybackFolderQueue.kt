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
): PlaybackNeighbours? {
    val audioReady by rememberUpdatedState(state.awaitsFolderNeighbours)
    val target by rememberUpdatedState(state.target)
    val currentFind by rememberUpdatedState(findNeighbours)
    val currentOnAuthenticationRequired by rememberUpdatedState(onAuthenticationRequired)
    val neighbours by produceState<PlaybackNeighbours?>(null, state.target.fileId, findNeighbours != null) {
        value = null
        if (currentFind == null) {
            value = PlaybackNeighbours()
            return@produceState
        }
        snapshotFlow { audioReady }.first { it }
        val find = currentFind ?: return@produceState
        value = find.neighboursOf(target) { currentOnAuthenticationRequired() }
    }
    return neighbours
}

public suspend fun PlaybackFindNeighbours.neighboursOf(
    target: PlaybackTarget,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackNeighbours =
    when (val result = this(target)) {
        is PlaybackRepositoryResult.Success -> result.value
        is PlaybackRepositoryResult.Failure -> PlaybackNeighbours().also {
            if (result.failure.putioFailure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
        }
    }

public val PlaybackState.awaitsFolderNeighbours: Boolean
    get() = target.mediaType == PlaybackMediaType.AUDIO &&
        (content is PlaybackContent.Ready || content == PlaybackContent.Session)

public fun PlaybackNeighbours.previousAt(positionMillis: Long): PlaybackTarget? =
    previous?.takeIf { positionMillis < PREVIOUS_RESTARTS_AFTER_MILLIS }

internal const val PREVIOUS_RESTARTS_AFTER_MILLIS = 3_000L
