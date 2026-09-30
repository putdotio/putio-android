package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PutioFileType

data class PlaybackTarget(
    val fileId: FilesItemId,
    val name: String,
    val mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
    /** The listing's media duration, when known; TV offers resume only with one. */
    val durationSeconds: Double? = null,
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

    data class AwaitingResume(
        val source: PlaybackSource,
    ) : PlaybackContent

    data class Ready(
        val source: PlaybackSource,
        val useStartFrom: Boolean = false,
    ) : PlaybackContent

    data class FindingNext(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    data class NextFailed(
        val failure: PlaybackFailure,
    ) : PlaybackContent

    data object Ended : PlaybackContent

    /**
     * The file needs MP4 conversion first. A [refreshRequestId] is a resolution or conversion
     * start in flight; the interstitial stays up meanwhile instead of flashing a loading screen.
     */
    data class Conversion(
        val state: PlaybackConversionState,
        val refreshRequestId: PlaybackRequestId? = null,
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
    data object Resume : PlaybackEvent

    data object Restart : PlaybackEvent

    data object Retry : PlaybackEvent

    /** Reads the conversion again while its interstitial stays up. */
    data object RefreshConversion : PlaybackEvent

    /** The viewer asked to convert again after a failed conversion. */
    data object StartConversion : PlaybackEvent

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

    /** Starts the MP4 conversion, then resolves again. */
    data class StartConversion(
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
            PlaybackEvent.Resume -> state.chooseResume(restart = false)
            PlaybackEvent.Restart -> state.chooseResume(restart = true)
            PlaybackEvent.Retry -> state.retry()
            PlaybackEvent.RefreshConversion -> state.refreshConversion(start = false)
            PlaybackEvent.StartConversion -> state.refreshConversion(start = true)
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
    return when (val resolution = event.resolution) {
        is PlaybackResolution.Ready ->
            PlaybackTransition(
                copy(
                    content =
                        if (resolution.useStartFrom && resolution.source.startFromSeconds > 0 &&
                            resumePositionMillis == null
                        ) {
                            PlaybackContent.AwaitingResume(resolution.source)
                        } else {
                            PlaybackContent.Ready(resolution.source, resolution.useStartFrom)
                        },
                ),
            )
        is PlaybackResolution.Conversion -> conversionResolved(resolution.state)
        is PlaybackResolution.Unsupported ->
            PlaybackTransition(copy(content = PlaybackContent.Unsupported(resolution.fileType)))
    }
}

/**
 * A finished conversion gets one immediate refresh so the file details can resolve a source;
 * if it still reads completed, the viewer checks again.
 */
private fun PlaybackState.conversionResolved(state: PlaybackConversionState): PlaybackTransition {
    val wasCompleted = (content as? PlaybackContent.Conversion)?.state == PlaybackConversionState.Completed
    if (state != PlaybackConversionState.Completed || wasCompleted) {
        return PlaybackTransition(copy(content = PlaybackContent.Conversion(state)))
    }
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        copy(
            content = PlaybackContent.Conversion(state, requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        PlaybackEffect.Resolve(target, requestId),
    )
}

/**
 * Keeps the conversion interstitial while it reads the state again, or, after a failed
 * conversion only, starts converting again. One request at a time.
 */
private fun PlaybackState.refreshConversion(start: Boolean): PlaybackTransition {
    val conversion = (content as? PlaybackContent.Conversion)
        ?.takeIf { it.refreshRequestId == null && (!start || it.state == PlaybackConversionState.Failed) }
        ?: return PlaybackTransition(this, consumed = false)
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        copy(
            content = conversion.copy(refreshRequestId = requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        if (start) PlaybackEffect.StartConversion(target, requestId) else PlaybackEffect.Resolve(target, requestId),
    )
}

private fun PlaybackState.resolveFailed(event: PlaybackEvent.ResolveFailed): PlaybackTransition {
    if (!isLoading(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    return PlaybackTransition(copy(content = PlaybackContent.Failed(event.failure)))
}

private fun PlaybackState.isLoading(requestId: PlaybackRequestId): Boolean =
    (content as? PlaybackContent.Loading)?.requestId == requestId ||
        (content as? PlaybackContent.Conversion)?.refreshRequestId == requestId

private const val INITIAL_REQUEST_VALUE = 1L

/** How often a queued or running conversion is read again (tv-native `useConversionStatus`: 3 s). */
const val PLAYBACK_CONVERSION_POLL_MILLIS = 3_000L

/**
 * Queued and running conversions are read again on their own; the others wait for the viewer
 * (the SDK's consumer contract in putio-sdk-kotlin `docs/ARCHITECTURE.md`).
 */
val PlaybackConversionState.pollsAutomatically: Boolean
    get() = this == PlaybackConversionState.Queued || this is PlaybackConversionState.Converting

private fun PlaybackState.chooseResume(restart: Boolean): PlaybackTransition {
    val pending = content as? PlaybackContent.AwaitingResume ?: return PlaybackTransition(this, consumed = false)
    return PlaybackTransition(
        copy(
            content = PlaybackContent.Ready(pending.source, useStartFrom = true),
            resumePositionMillis = if (restart) 0L else pending.source.startFromSeconds.toPlaybackMillis(),
        ),
    )
}
