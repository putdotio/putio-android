package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PutioFileType

data class PlaybackTarget(
    val fileId: FilesItemId,
    val name: String,
)

@JvmInline
value class PlaybackRequestId(
    val value: Long,
)

sealed interface PlaybackContent {
    data class Loading(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    data class Ready(
        val source: PlaybackSource,
    ) : PlaybackContent

    data class Conversion(
        val state: PlaybackConversionState,
    ) : PlaybackContent

    data class Unsupported(
        val fileType: PutioFileType,
    ) : PlaybackContent

    data class Failed(
        val failure: PlaybackFailure,
    ) : PlaybackContent
}

data class PlaybackState(
    val target: PlaybackTarget,
    val content: PlaybackContent,
    internal val nextRequestValue: Long,
    val resumePositionMillis: Long? = null,
)

sealed interface PlaybackEvent {
    data object Retry : PlaybackEvent

    data class PlayerFailed(
        val failure: PlaybackFailure,
        val resumePositionMillis: Long,
    ) : PlaybackEvent

    data class ResolveSucceeded(
        val requestId: PlaybackRequestId,
        val resolution: PlaybackResolution,
    ) : PlaybackEvent

    data class ResolveFailed(
        val requestId: PlaybackRequestId,
        val failure: PlaybackFailure,
    ) : PlaybackEvent
}

data class PlaybackEffect(
    val target: PlaybackTarget,
    val requestId: PlaybackRequestId,
)

data class PlaybackTransition(
    val state: PlaybackState,
    val effect: PlaybackEffect? = null,
    val consumed: Boolean = true,
)

object PlaybackReducer {
    fun start(target: PlaybackTarget): PlaybackTransition {
        val requestId = PlaybackRequestId(INITIAL_REQUEST_VALUE)
        return PlaybackTransition(
            state = PlaybackState(
                target = target,
                content = PlaybackContent.Loading(requestId),
                nextRequestValue = INITIAL_REQUEST_VALUE + 1,
            ),
            effect = PlaybackEffect(target, requestId),
        )
    }

    fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): PlaybackTransition =
        when (event) {
            PlaybackEvent.Retry -> state.retry()
            is PlaybackEvent.PlayerFailed -> state.playerFailed(event)
            is PlaybackEvent.ResolveSucceeded -> state.resolveSucceeded(event)
            is PlaybackEvent.ResolveFailed -> state.resolveFailed(event)
        }
}

private fun PlaybackState.playerFailed(event: PlaybackEvent.PlayerFailed): PlaybackTransition {
    if (content !is PlaybackContent.Ready) {
        return PlaybackTransition(this, consumed = false)
    }
    return PlaybackTransition(
        copy(
            content = PlaybackContent.Failed(event.failure),
            resumePositionMillis = event.resumePositionMillis,
        ),
    )
}

private fun PlaybackState.retry(): PlaybackTransition {
    val retryable = content is PlaybackContent.Failed || content is PlaybackContent.Conversion
    if (!retryable) {
        return PlaybackTransition(this, consumed = false)
    }
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        state = copy(
            content = PlaybackContent.Loading(requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        effect = PlaybackEffect(target, requestId),
    )
}

private fun PlaybackState.resolveSucceeded(
    event: PlaybackEvent.ResolveSucceeded,
): PlaybackTransition {
    if (!isLoading(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    val nextContent =
        when (val resolution = event.resolution) {
            is PlaybackResolution.Ready -> PlaybackContent.Ready(resolution.source)
            is PlaybackResolution.Conversion -> PlaybackContent.Conversion(resolution.state)
            is PlaybackResolution.Unsupported -> PlaybackContent.Unsupported(resolution.fileType)
        }
    return PlaybackTransition(copy(content = nextContent))
}

private fun PlaybackState.resolveFailed(event: PlaybackEvent.ResolveFailed): PlaybackTransition {
    if (!isLoading(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    return PlaybackTransition(copy(content = PlaybackContent.Failed(event.failure)))
}

private fun PlaybackState.isLoading(requestId: PlaybackRequestId): Boolean =
    (content as? PlaybackContent.Loading)?.requestId == requestId

private const val INITIAL_REQUEST_VALUE = 1L
