package io.putdotio.android.settings

import io.putdotio.android.PutioFailure

public enum class VideoPlaybackType(
    public val wireValue: String,
) {
    Hls("hls"),
    Mp4("mp4"),
}

public data class AndroidAppConfigPreferences(
    val videoPlaybackType: VideoPlaybackType = VideoPlaybackType.Hls,
    val autoplayNextVideo: Boolean = false,
)

internal fun AndroidAppConfigPreferences.applying(
    change: AndroidAppConfigChange,
): AndroidAppConfigPreferences =
    when (change) {
        is AndroidAppConfigChange.VideoPlayback -> copy(videoPlaybackType = change.value)
        is AndroidAppConfigChange.AutoplayNextVideo -> copy(autoplayNextVideo = change.enabled)
    }

public sealed interface AndroidAppConfigChange {
    public data class VideoPlayback(
        val value: VideoPlaybackType,
    ) : AndroidAppConfigChange

    public data class AutoplayNextVideo(
        val enabled: Boolean,
    ) : AndroidAppConfigChange
}

@JvmInline
public value class AndroidAppConfigRequestId(
    internal val value: Long,
)

public sealed interface AndroidAppConfigContent {
    public data class Loading(
        val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigContent

    public data class Ready(
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigContent

    public data class Failed(
        val failure: PutioFailure,
    ) : AndroidAppConfigContent
}

public sealed interface AndroidAppConfigMutation {
    public enum class Operation {
        Save,
        Refresh,
    }

    public data object Idle : AndroidAppConfigMutation

    public data class Saving(
        val requestId: AndroidAppConfigRequestId,
        val change: AndroidAppConfigChange,
        val previousPreferences: AndroidAppConfigPreferences,
        val operation: Operation,
    ) : AndroidAppConfigMutation

    public data class Failed(
        val change: AndroidAppConfigChange,
        val failure: PutioFailure,
        val previousPreferences: AndroidAppConfigPreferences,
        val operation: Operation,
    ) : AndroidAppConfigMutation
}

@ConsistentCopyVisibility
public data class AndroidAppConfigState internal constructor(
    val content: AndroidAppConfigContent,
    val mutation: AndroidAppConfigMutation,
    internal val nextRequestValue: Long,
    val confirmedPreferences: AndroidAppConfigPreferences? =
        (content as? AndroidAppConfigContent.Ready)?.preferences,
)

public sealed interface AndroidAppConfigEvent {
    public data object RetryLoad : AndroidAppConfigEvent

    public data object RetryChange : AndroidAppConfigEvent

    public data class ChangeRequested(
        val change: AndroidAppConfigChange,
    ) : AndroidAppConfigEvent

    public data class LoadSucceeded(
        val requestId: AndroidAppConfigRequestId,
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigEvent

    public data class LoadFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: PutioFailure,
    ) : AndroidAppConfigEvent

    public data class SaveSucceeded(
        val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEvent

    public data class SaveFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: PutioFailure,
    ) : AndroidAppConfigEvent

    public data class RefreshSucceeded(
        val requestId: AndroidAppConfigRequestId,
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigEvent

    public data class RefreshFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: PutioFailure,
    ) : AndroidAppConfigEvent
}

public sealed interface AndroidAppConfigEffect {
    public val requestId: AndroidAppConfigRequestId

    public data class Load(
        override val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEffect

    public data class Save(
        override val requestId: AndroidAppConfigRequestId,
        val change: AndroidAppConfigChange,
    ) : AndroidAppConfigEffect

    public data class Refresh(
        override val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEffect
}

public data class AndroidAppConfigTransition(
    val state: AndroidAppConfigState,
    val effect: AndroidAppConfigEffect? = null,
    val consumed: Boolean = true,
)

public object AndroidAppConfigReducer {
    public fun start(): AndroidAppConfigTransition {
        val requestId = AndroidAppConfigRequestId(INITIAL_REQUEST_VALUE)
        return AndroidAppConfigTransition(
            state =
                AndroidAppConfigState(
                    content = AndroidAppConfigContent.Loading(requestId),
                    mutation = AndroidAppConfigMutation.Idle,
                    nextRequestValue = INITIAL_REQUEST_VALUE + 1,
                ),
            effect = AndroidAppConfigEffect.Load(requestId),
        )
    }

    public fun reduce(
        state: AndroidAppConfigState,
        event: AndroidAppConfigEvent,
    ): AndroidAppConfigTransition =
        when (event) {
            AndroidAppConfigEvent.RetryLoad -> state.retryLoad()
            AndroidAppConfigEvent.RetryChange -> state.retryChange()
            is AndroidAppConfigEvent.ChangeRequested -> state.requestChange(event.change)
            is AndroidAppConfigEvent.LoadSucceeded -> state.loadSucceeded(event)
            is AndroidAppConfigEvent.LoadFailed -> state.loadFailed(event)
            is AndroidAppConfigEvent.SaveSucceeded -> state.saveSucceeded(event)
            is AndroidAppConfigEvent.SaveFailed -> state.saveFailed(event)
            is AndroidAppConfigEvent.RefreshSucceeded -> state.refreshSucceeded(event)
            is AndroidAppConfigEvent.RefreshFailed -> state.refreshFailed(event)
        }
}

public fun AndroidAppConfigState.authoritativeSessionFailure(): PutioFailure.AuthenticationRequired? =
    listOfNotNull(
        (content as? AndroidAppConfigContent.Failed)?.failure,
        (mutation as? AndroidAppConfigMutation.Failed)?.failure,
    ).filterIsInstance<PutioFailure.AuthenticationRequired>()
        .firstOrNull()

private const val INITIAL_REQUEST_VALUE = 1L
