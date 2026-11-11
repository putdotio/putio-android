package io.putdotio.android.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import io.putdotio.android.PutioFailure

public const val PLAYBACK_UP_NEXT_MILLIS: Long = 10_000L

public data class PlaybackUpNext(val target: PlaybackTarget, val folderName: String?)

public sealed interface PlaybackUpNextLookup {
    public data object Off : PlaybackUpNextLookup

    public data object Pending : PlaybackUpNextLookup

    public data class Found(val upNext: PlaybackUpNext) : PlaybackUpNextLookup

    public data object FolderEnd : PlaybackUpNextLookup

    public data object Failed : PlaybackUpNextLookup
}

public typealias PlaybackFindNeighbours = suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours>

@Composable
public fun rememberUpNext(
    state: PlaybackState,
    findNeighbours: PlaybackFindNeighbours?,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackUpNextLookup {
    val ready = state.content is PlaybackContent.Ready && state.target.mediaType == PlaybackMediaType.VIDEO
    val initial = if (findNeighbours == null) PlaybackUpNextLookup.Off else PlaybackUpNextLookup.Pending
    val lookup by produceState(initial, state.target.fileId, ready, findNeighbours != null) {
        value = initial
        val find = findNeighbours ?: return@produceState
        if (!ready) return@produceState
        value = when (val found = find(state.target)) {
            is PlaybackRepositoryResult.Success -> found.value.upNextAfter(state)
            is PlaybackRepositoryResult.Failure -> {
                if (found.failure.putioFailure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
                PlaybackUpNextLookup.Failed
            }
        }
    }
    return lookup
}

internal fun PlaybackNeighbours.upNextAfter(state: PlaybackState): PlaybackUpNextLookup =
    next?.takeUnless { it.fileId in state.visitedFileIds }
        ?.let { PlaybackUpNextLookup.Found(PlaybackUpNext(it, folderName)) }
        ?: PlaybackUpNextLookup.FolderEnd
