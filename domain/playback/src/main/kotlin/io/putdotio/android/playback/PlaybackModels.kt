package io.putdotio.android.playback

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.playbackFinishedAt
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PutioFileType
import kotlin.math.abs

public data class PlaybackTarget(
    val fileId: FilesItemId,
    val name: String,
    val mediaType: PlaybackMediaType = PlaybackMediaType.VIDEO,
    val durationSeconds: Double? = null,
    val posterUrl: String? = null,
    val method: StreamingMethod? = null,
)

public enum class PlaybackMediaType {
    VIDEO,
    AUDIO,
    ;

    public companion object {
        public fun fromFileType(fileType: PutioFileType): PlaybackMediaType? =
            when (fileType) {
                PutioFileType.VIDEO -> VIDEO
                PutioFileType.AUDIO -> AUDIO
                else -> null
            }
    }
}

@JvmInline
public value class PlaybackRequestId(
    internal val value: Long,
)

public enum class PlaybackStartup {
    Resolve,
    AttachAudioSession,
}

public sealed interface PlaybackContent {
    public data object Session : PlaybackContent

    public data class Loading(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    public data class AwaitingResume(
        val source: PlaybackSource,
        val subtitlesHidden: Boolean = false,
        val offline: Boolean = false,
    ) : PlaybackContent

    public data class Ready(
        val source: PlaybackSource,
        val useStartFrom: Boolean = false,
        val subtitlesHidden: Boolean = false,
        val offline: Boolean = false,
    ) : PlaybackContent

    public data class FindingNext(
        val requestId: PlaybackRequestId,
    ) : PlaybackContent

    public data class NextFailed(
        val failure: PlaybackFailure,
    ) : PlaybackContent

    public data object Ended : PlaybackContent

    public data class Conversion(
        val state: PlaybackConversionState,
        val refreshRequestId: PlaybackRequestId? = null,
        val statusCheckFailed: Boolean = false,
    ) : PlaybackContent

    public data class Unsupported(
        val fileType: PutioFileType,
    ) : PlaybackContent

    public data class Failed(
        val failure: PlaybackFailure,
        val conversionStart: Boolean = false,
    ) : PlaybackContent
}

@ConsistentCopyVisibility
public data class PlaybackState internal constructor(
    val target: PlaybackTarget,
    val content: PlaybackContent,
    internal val nextRequestValue: Long,
    val resumePositionMillis: Long? = null,
    val visitedFileIds: Set<FilesItemId> = setOf(target.fileId),
    internal val conversionStatusRead: Boolean = false,
    internal val convertedOnly: Boolean = false,
    internal val linkRefetchedAtMillis: Long? = null,
    internal val castFallbackTarget: PlaybackTarget? = null,
    internal val switchedToConverted: Boolean = false,
) {
    public val convertedForCast: Boolean get() = castFallbackTarget != null

    public val offersOriginal: Boolean
        get() = switchedToConverted && castFallbackTarget == null && target.mediaType == PlaybackMediaType.VIDEO &&
            (content as? PlaybackContent.Ready)
                ?.let { !it.offline && it.source.kind != PlaybackSourceKind.ORIGINAL } == true
}

public sealed interface PlaybackEvent {
    public data object Resume : PlaybackEvent

    public data object Restart : PlaybackEvent

    public data object Retry : PlaybackEvent

    public data object RefreshConversion : PlaybackEvent

    public data object StartConversion : PlaybackEvent

    public data object PlayerEnded : PlaybackEvent

    public data class NextChosen(
        val target: PlaybackTarget,
    ) : PlaybackEvent

    public data class SourceRequired(
        val resumePositionMillis: Long? = null,
    ) : PlaybackEvent

    public data class PlayerFailed(
        val failure: PlaybackFailure,
        val resumePositionMillis: Long,
    ) : PlaybackEvent

    public data class OriginalRejected(
        val reason: PlaybackFallbackReason,
        val positionMillis: Long,
    ) : PlaybackEvent

    public data class OriginalChosen(
        val positionMillis: Long,
    ) : PlaybackEvent

    public data object CastEnded : PlaybackEvent

    public data class ResolveSucceeded(
        val requestId: PlaybackRequestId,
        val resolution: PlaybackResolution,
    ) : PlaybackEvent

    public data class ResolveFailed(
        val requestId: PlaybackRequestId,
        val failure: PlaybackFailure,
    ) : PlaybackEvent

    public data class NextFound(
        val requestId: PlaybackRequestId,
        val target: PlaybackTarget,
    ) : PlaybackEvent

    public data class NextEnded(
        val requestId: PlaybackRequestId,
    ) : PlaybackEvent

    public data class NextFailed(
        val requestId: PlaybackRequestId,
        val failure: PlaybackFailure,
    ) : PlaybackEvent
}

public sealed interface PlaybackEffect {
    public val target: PlaybackTarget
    public val requestId: PlaybackRequestId

    public data class Resolve(
        override val target: PlaybackTarget,
        override val requestId: PlaybackRequestId,
        val convertedOnly: Boolean = false,
    ) : PlaybackEffect

    public data class FindNext(
        override val target: PlaybackTarget,
        override val requestId: PlaybackRequestId,
    ) : PlaybackEffect

    public data class StartConversion(
        override val target: PlaybackTarget,
        override val requestId: PlaybackRequestId,
        val convertedOnly: Boolean = false,
    ) : PlaybackEffect
}

public data class PlaybackTransition(
    val state: PlaybackState,
    val effect: PlaybackEffect? = null,
    val consumed: Boolean = true,
)

public object PlaybackReducer {
    public fun start(
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

    public fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): PlaybackTransition =
        when (event) {
            PlaybackEvent.Resume, PlaybackEvent.Restart -> state.chooseResume(restart = event == PlaybackEvent.Restart)
            PlaybackEvent.Retry -> state.retry()
            PlaybackEvent.RefreshConversion -> state.refreshConversion(start = false)
            PlaybackEvent.StartConversion -> state.refreshConversion(start = true)
            PlaybackEvent.PlayerEnded -> state.playerEnded()
            is PlaybackEvent.SourceRequired -> state.sourceRequired(event)
            is PlaybackEvent.PlayerFailed -> state.playerFailed(event)
            is PlaybackEvent.OriginalRejected -> state.originalRejected(event)
            is PlaybackEvent.OriginalChosen -> state.originalChosen(event)
            PlaybackEvent.CastEnded -> state.castEnded()
            is PlaybackEvent.ResolveSucceeded -> state.resolveSucceeded(event)
            is PlaybackEvent.ResolveFailed -> state.resolveFailed(event)
            is PlaybackEvent.NextChosen,
            is PlaybackEvent.NextFound,
            is PlaybackEvent.NextEnded,
            is PlaybackEvent.NextFailed,
            -> state.nextVideo(event)
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
        effect = PlaybackEffect.Resolve(target, requestId, convertedOnly),
    )
}

private fun PlaybackState.playerFailed(event: PlaybackEvent.PlayerFailed): PlaybackTransition {
    if (content !is PlaybackContent.Ready && content != PlaybackContent.Session) {
        return PlaybackTransition(this, consumed = false)
    }
    val positionMillis = event.resumePositionMillis.coerceAtLeast(0L)
    val refetchesExpiredLink = content is PlaybackContent.Ready &&
        target.mediaType == PlaybackMediaType.VIDEO &&
        event.failure is PlaybackFailure.MediaCredentialUnavailable &&
        linkRefetchedAtMillis.let { it == null || abs(positionMillis - it) > SAME_LINK_WINDOW_MILLIS }
    return if (refetchesExpiredLink) {
        val requestId = PlaybackRequestId(nextRequestValue)
        PlaybackTransition(
            state = copy(
                content = PlaybackContent.Loading(requestId),
                nextRequestValue = nextRequestValue + 1,
                resumePositionMillis = positionMillis,
                linkRefetchedAtMillis = positionMillis,
            ),
            effect = PlaybackEffect.Resolve(target, requestId, convertedOnly),
        )
    } else {
        PlaybackTransition(
            copy(
                content = PlaybackContent.Failed(event.failure),
                resumePositionMillis = positionMillis,
            ),
        )
    }
}

private const val SAME_LINK_WINDOW_MILLIS = 5_000L

private fun PlaybackState.retry(): PlaybackTransition {
    val requestId = PlaybackRequestId(nextRequestValue)
    return when (content) {
        is PlaybackContent.Conversion if content.refreshRequestId != null ->
            PlaybackTransition(this, consumed = false)

        is PlaybackContent.Failed,
        is PlaybackContent.Conversion,
        ->
            PlaybackTransition(
                state = copy(
                    content = PlaybackContent.Loading(requestId),
                    nextRequestValue = nextRequestValue + 1,
                    linkRefetchedAtMillis = null,
                ),
                effect = PlaybackEffect.Resolve(target, requestId, convertedOnly),
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
            (
                resolution.durationSeconds
                    ?.takeIf { target.durationSeconds == null }
                    ?.let { copy(target = target.copy(durationSeconds = it)) }
                    ?: this
                ).copy(switchedToConverted = switchedToConverted || resolution.switchedToConverted)
                .ready(resolution)
        is PlaybackResolution.Conversion -> conversionResolved(resolution.state)
        is PlaybackResolution.Unsupported ->
            PlaybackTransition(copy(content = PlaybackContent.Unsupported(resolution.fileType)))
    }
}

private fun PlaybackState.ready(resolution: PlaybackResolution.Ready): PlaybackTransition =
    if (resolution.useStartFrom && resolution.source.startFromSeconds > 0 && resumePositionMillis == null) {
        val awaiting = copy(
            content = PlaybackContent.AwaitingResume(resolution.source, resolution.subtitlesHidden, resolution.offline),
        )
        if (playbackFinishedAt(resolution.source.startFromSeconds, target.durationSeconds)) {
            awaiting.chooseResume(restart = true)
        } else if (target.mediaType == PlaybackMediaType.AUDIO) {
            awaiting.chooseResume(restart = false)
        } else {
            PlaybackTransition(awaiting)
        }
    } else {
        PlaybackTransition(
            copy(
                content = PlaybackContent.Ready(
                    resolution.source,
                    resolution.useStartFrom,
                    resolution.subtitlesHidden,
                    resolution.offline,
                ),
            ),
        )
    }

private fun PlaybackState.conversionResolved(state: PlaybackConversionState): PlaybackTransition {
    val previous = content as? PlaybackContent.Conversion
    val start = state == PlaybackConversionState.NotAvailable && !conversionStatusRead
    val refresh = state == PlaybackConversionState.Completed && previous?.state != PlaybackConversionState.Completed
    if (!start && !refresh) {
        return PlaybackTransition(
            copy(content = PlaybackContent.Conversion(state), conversionStatusRead = true),
        )
    }
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        copy(
            content = PlaybackContent.Conversion(state, requestId),
            nextRequestValue = nextRequestValue + 1,
            conversionStatusRead = true,
        ),
        if (start) {
            PlaybackEffect.StartConversion(target, requestId, convertedOnly)
        } else {
            PlaybackEffect.Resolve(target, requestId, convertedOnly)
        },
    )
}

private fun PlaybackState.refreshConversion(start: Boolean): PlaybackTransition {
    val conversion = (content as? PlaybackContent.Conversion)
        ?.takeIf { it.refreshRequestId == null && (!start || it.startable) }
        ?: return PlaybackTransition(this, consumed = false)
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        copy(
            content = conversion.copy(refreshRequestId = requestId),
            nextRequestValue = nextRequestValue + 1,
        ),
        if (start) {
            PlaybackEffect.StartConversion(target, requestId, convertedOnly)
        } else {
            PlaybackEffect.Resolve(target, requestId, convertedOnly)
        },
    )
}

private fun PlaybackState.resolveFailed(event: PlaybackEvent.ResolveFailed): PlaybackTransition {
    if (!isLoading(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    val conversion = content as? PlaybackContent.Conversion
    val statusRead = conversion?.takeIf { it.readsStatus && event.failure.lostConnection }
    return PlaybackTransition(
        copy(
            content = statusRead?.copy(refreshRequestId = null, statusCheckFailed = true)
                ?: PlaybackContent.Failed(event.failure, conversionStart = conversion?.readsStatus == false),
        ),
    )
}

private val PlaybackContent.Conversion.readsStatus: Boolean
    get() = !starting && !startable

private val PlaybackFailure.lostConnection: Boolean
    get() = putioFailure.let { it is PutioFailure.NetworkUnavailable || it is PutioFailure.Timeout }

internal fun PlaybackState.isLoading(requestId: PlaybackRequestId): Boolean =
    (content as? PlaybackContent.Loading)?.requestId == requestId ||
        (content as? PlaybackContent.Conversion)?.refreshRequestId == requestId

private const val INITIAL_REQUEST_VALUE = 1L

public const val PLAYBACK_CONVERSION_POLL_MILLIS: Long = 5_000L

internal val PlaybackConversionState.pollsAutomatically: Boolean
    get() = this == PlaybackConversionState.Queued || this is PlaybackConversionState.Converting

public val PlaybackContent.Conversion.startable: Boolean
    get() = state == PlaybackConversionState.Failed

public val PlaybackContent.Conversion.starting: Boolean
    get() = state == PlaybackConversionState.NotAvailable && refreshRequestId != null

public enum class PlaybackConversionAction {
    ConvertAgain,

    CheckAgain,

    RetryStatusCheck,
}

public val PlaybackContent.Conversion.action: PlaybackConversionAction?
    get() = when {
        statusCheckFailed -> PlaybackConversionAction.RetryStatusCheck
        startable -> PlaybackConversionAction.ConvertAgain
        state == PlaybackConversionState.NotAvailable || state.pollsAutomatically -> null
        else -> PlaybackConversionAction.CheckAgain
    }

private fun PlaybackState.chooseResume(restart: Boolean): PlaybackTransition {
    val pending = content as? PlaybackContent.AwaitingResume ?: return PlaybackTransition(this, consumed = false)
    return PlaybackTransition(
        copy(
            content = PlaybackContent.Ready(
                pending.source,
                useStartFrom = true,
                subtitlesHidden = pending.subtitlesHidden,
                offline = pending.offline,
            ),
            resumePositionMillis = if (restart) 0L else pending.source.startFromSeconds.toPlaybackMillis(),
        ),
    )
}
