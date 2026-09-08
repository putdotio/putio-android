package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PutioFileType

data class PlaybackTarget(
    val fileId: FilesItemId,
    val name: String,
    val mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
)

enum class PlaybackMediaType {
    VIDEO,
    AUDIO,
    ;

    companion object {
        fun fromFileType(fileType: PutioFileType): PlaybackMediaType? =
            when (fileType) {
                PutioFileType.VIDEO -> VIDEO
                PutioFileType.AUDIO -> AUDIO
                else -> null
            }
    }
}

@JvmInline
value class PlaybackRequestId(
    val value: Long,
)

enum class PlaybackStartup {
    Resolve,
    AttachAudioSession,
}

sealed interface PlaybackContent {
    data object Session : PlaybackContent

    data class Loading(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    data class Ready(
        val source: PlaybackSource,
    ) : PlaybackContent

    data class FindingNext(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    data class NextFailed(
        val failure: PlaybackFailure,
    ) : PlaybackContent

    data object Ended : PlaybackContent

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
    val visitedFileIds: Set<FilesItemId> = setOf(target.fileId),
)

sealed interface PlaybackEvent {
    data object Retry : PlaybackEvent

    data object PlayerEnded : PlaybackEvent

    data class SourceRequired(
        val resumePositionMillis: Long? = null,
    ) : PlaybackEvent

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

    data class NextFound(
        val requestId: PlaybackRequestId,
        val target: PlaybackTarget,
    ) : PlaybackEvent

    data class NextEnded(
        val requestId: PlaybackRequestId,
    ) : PlaybackEvent

    data class NextFailed(
        val requestId: PlaybackRequestId,
        val failure: PlaybackFailure,
    ) : PlaybackEvent
}

sealed interface PlaybackEffect {
    val target: PlaybackTarget
    val requestId: PlaybackRequestId

    data class Resolve(
        override val target: PlaybackTarget,
        override val requestId: PlaybackRequestId,
    ) : PlaybackEffect

    data class FindNext(
        override val target: PlaybackTarget,
        override val requestId: PlaybackRequestId,
    ) : PlaybackEffect
}

data class PlaybackTransition(
    val state: PlaybackState,
    val effect: PlaybackEffect? = null,
    val consumed: Boolean = true,
)

object PlaybackReducer {
    fun start(
        target: PlaybackTarget,
        startup: PlaybackStartup = PlaybackStartup.Resolve,
    ): PlaybackTransition {
        if (startup == PlaybackStartup.AttachAudioSession && target.mediaType == PlaybackMediaType.AUDIO) {
            return PlaybackTransition(
                state = PlaybackState(
                    target = target,
                    content = PlaybackContent.Session,
                    nextRequestValue = INITIAL_REQUEST_VALUE,
                ),
            )
        }
        val requestId = PlaybackRequestId(INITIAL_REQUEST_VALUE)
        return PlaybackTransition(
            state = PlaybackState(
                target = target,
                content = PlaybackContent.Loading(requestId),
                nextRequestValue = INITIAL_REQUEST_VALUE + 1,
            ),
            effect = PlaybackEffect.Resolve(target, requestId),
        )
    }

    fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): PlaybackTransition =
        when (event) {
            PlaybackEvent.Retry -> state.retry()
            PlaybackEvent.PlayerEnded -> state.playerEnded()
            is PlaybackEvent.SourceRequired -> state.sourceRequired(event)
            is PlaybackEvent.PlayerFailed -> state.playerFailed(event)
            is PlaybackEvent.ResolveSucceeded -> state.resolveSucceeded(event)
            is PlaybackEvent.ResolveFailed -> state.resolveFailed(event)
            is PlaybackEvent.NextFound -> state.nextFound(event)
            is PlaybackEvent.NextEnded -> state.nextEnded(event)
            is PlaybackEvent.NextFailed -> state.nextFailed(event)
        }
}

private fun PlaybackState.sourceRequired(event: PlaybackEvent.SourceRequired): PlaybackTransition {
    if (content != PlaybackContent.Session) return PlaybackTransition(this, consumed = false)
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        state = copy(
            content = PlaybackContent.Loading(requestId),
            nextRequestValue = nextRequestValue + 1,
            resumePositionMillis = event.resumePositionMillis?.coerceAtLeast(0L) ?: resumePositionMillis,
        ),
        effect = PlaybackEffect.Resolve(target, requestId),
    )
}

private fun PlaybackState.playerFailed(event: PlaybackEvent.PlayerFailed): PlaybackTransition {
    if (content !is PlaybackContent.Ready && content != PlaybackContent.Session) {
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
    val requestId = PlaybackRequestId(nextRequestValue)
    return when (content) {
        is PlaybackContent.Failed,
        is PlaybackContent.Conversion,
        ->
            PlaybackTransition(
                state = copy(
                    content = PlaybackContent.Loading(requestId),
                    nextRequestValue = nextRequestValue + 1,
                ),
                effect = PlaybackEffect.Resolve(target, requestId),
            )

        is PlaybackContent.NextFailed ->
            PlaybackTransition(
                state = copy(
                    content = PlaybackContent.FindingNext(requestId),
                    nextRequestValue = nextRequestValue + 1,
                ),
                effect = PlaybackEffect.FindNext(target, requestId),
            )

        else -> PlaybackTransition(this, consumed = false)
    }
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
