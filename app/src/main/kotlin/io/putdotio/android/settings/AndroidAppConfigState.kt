package io.putdotio.android.settings

internal enum class VideoPlaybackType(
    val wireValue: String,
) {
    Hls("hls"),
    Mp4("mp4"),
}

internal data class AndroidAppConfigPreferences(
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

internal sealed interface AndroidAppConfigChange {
    data class VideoPlayback(
        val value: VideoPlaybackType,
    ) : AndroidAppConfigChange

    data class AutoplayNextVideo(
        val enabled: Boolean,
    ) : AndroidAppConfigChange
}

@JvmInline
internal value class AndroidAppConfigRequestId(
    val value: Long,
)

internal sealed interface AndroidAppConfigContent {
    data class Loading(
        val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigContent

    data class Ready(
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigContent

    data class Failed(
        val failure: AndroidAppConfigFailure,
    ) : AndroidAppConfigContent
}

internal sealed interface AndroidAppConfigMutation {
    enum class Operation {
        Save,
        Refresh,
    }

    data object Idle : AndroidAppConfigMutation

    data class Saving(
        val requestId: AndroidAppConfigRequestId,
        val change: AndroidAppConfigChange,
        val previousPreferences: AndroidAppConfigPreferences,
        val operation: Operation,
    ) : AndroidAppConfigMutation

    data class Failed(
        val change: AndroidAppConfigChange,
        val failure: AndroidAppConfigFailure,
        val previousPreferences: AndroidAppConfigPreferences,
        val operation: Operation,
    ) : AndroidAppConfigMutation
}

@ConsistentCopyVisibility
internal data class AndroidAppConfigState internal constructor(
    val content: AndroidAppConfigContent,
    val mutation: AndroidAppConfigMutation,
    internal val nextRequestValue: Long,
)

internal sealed interface AndroidAppConfigEvent {
    data object RetryLoad : AndroidAppConfigEvent

    data object RetryChange : AndroidAppConfigEvent

    data class ChangeRequested(
        val change: AndroidAppConfigChange,
    ) : AndroidAppConfigEvent

    data class LoadSucceeded(
        val requestId: AndroidAppConfigRequestId,
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigEvent

    data class LoadFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: AndroidAppConfigFailure,
    ) : AndroidAppConfigEvent

    data class SaveSucceeded(
        val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEvent

    data class SaveFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: AndroidAppConfigFailure,
    ) : AndroidAppConfigEvent

    data class RefreshSucceeded(
        val requestId: AndroidAppConfigRequestId,
        val preferences: AndroidAppConfigPreferences,
    ) : AndroidAppConfigEvent

    data class RefreshFailed(
        val requestId: AndroidAppConfigRequestId,
        val failure: AndroidAppConfigFailure,
    ) : AndroidAppConfigEvent
}

internal sealed interface AndroidAppConfigEffect {
    val requestId: AndroidAppConfigRequestId

    data class Load(
        override val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEffect

    data class Save(
        override val requestId: AndroidAppConfigRequestId,
        val change: AndroidAppConfigChange,
    ) : AndroidAppConfigEffect

    data class Refresh(
        override val requestId: AndroidAppConfigRequestId,
    ) : AndroidAppConfigEffect
}

internal data class AndroidAppConfigTransition(
    val state: AndroidAppConfigState,
    val effect: AndroidAppConfigEffect? = null,
    val consumed: Boolean = true,
)

internal object AndroidAppConfigReducer {
    fun start(): AndroidAppConfigTransition {
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

    fun reduce(
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

internal fun AndroidAppConfigState.authoritativeSessionFailure(): AndroidAppConfigFailure.AuthenticationRequired? =
    listOfNotNull(
        (content as? AndroidAppConfigContent.Failed)?.failure,
        (mutation as? AndroidAppConfigMutation.Failed)?.failure,
    ).filterIsInstance<AndroidAppConfigFailure.AuthenticationRequired>()
        .firstOrNull()

private const val INITIAL_REQUEST_VALUE = 1L
