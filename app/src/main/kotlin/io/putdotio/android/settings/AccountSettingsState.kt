package io.putdotio.android.settings

@JvmInline
internal value class AccountSettingsRequestId(
    val value: Long,
)

internal data class AccountSettingsPreferences(
    val historyEnabled: Boolean,
    val trashEnabled: Boolean,
    val showSubtitles: Boolean,
    val autoSelectSubtitles: Boolean,
    // Account-wide `use_start_from`: playback resumes from and writes back saved positions.
    val resumePlayback: Boolean = true,
)

internal enum class AccountSettingsKey {
    History,
    Trash,
    ShowSubtitles,
    AutoSelectSubtitles,
    ResumePlayback,
}

internal data class AccountSettingsChange(
    val key: AccountSettingsKey,
    val enabled: Boolean,
)

internal sealed interface AccountSettingsContent {
    data class Loading(
        val requestId: AccountSettingsRequestId,
    ) : AccountSettingsContent

    data class Ready(
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsContent

    data class Failed(
        val failure: AccountSettingsFailure,
    ) : AccountSettingsContent
}

internal sealed interface AccountSettingsMutation {
    enum class Operation {
        Save,
        Refresh,
    }

    data object Idle : AccountSettingsMutation

    data class Saving(
        val requestId: AccountSettingsRequestId,
        val change: AccountSettingsChange,
        val previousPreferences: AccountSettingsPreferences,
        val operation: Operation,
    ) : AccountSettingsMutation

    data class Failed(
        val change: AccountSettingsChange,
        val failure: AccountSettingsFailure,
        val previousPreferences: AccountSettingsPreferences,
        val operation: Operation,
    ) : AccountSettingsMutation
}

@ConsistentCopyVisibility
internal data class AccountSettingsState internal constructor(
    val content: AccountSettingsContent,
    val mutation: AccountSettingsMutation,
    internal val nextRequestValue: Long,
)

internal sealed interface AccountSettingsEvent {
    data object RetryLoad : AccountSettingsEvent

    data class ChangeRequested(
        val change: AccountSettingsChange,
    ) : AccountSettingsEvent

    data object RetryChange : AccountSettingsEvent

    data class LoadSucceeded(
        val requestId: AccountSettingsRequestId,
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsEvent

    data class LoadFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent

    data class SaveSucceeded(
        val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEvent

    data class SaveFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent

    data class RefreshSucceeded(
        val requestId: AccountSettingsRequestId,
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsEvent

    data class RefreshFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent
}

internal sealed interface AccountSettingsEffect {
    val requestId: AccountSettingsRequestId

    data class Load(
        override val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEffect

    data class Save(
        override val requestId: AccountSettingsRequestId,
        val change: AccountSettingsChange,
    ) : AccountSettingsEffect

    data class Refresh(
        override val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEffect
}

internal data class AccountSettingsTransition(
    val state: AccountSettingsState,
    val effect: AccountSettingsEffect? = null,
    val consumed: Boolean = true,
)

internal object AccountSettingsReducer {
    fun start(): AccountSettingsTransition {
        val requestId = AccountSettingsRequestId(INITIAL_REQUEST_VALUE)
        return AccountSettingsTransition(
            state =
                AccountSettingsState(
                    content = AccountSettingsContent.Loading(requestId),
                    mutation = AccountSettingsMutation.Idle,
                    nextRequestValue = INITIAL_REQUEST_VALUE + 1,
                ),
            effect = AccountSettingsEffect.Load(requestId),
        )
    }

    fun reduce(
        state: AccountSettingsState,
        event: AccountSettingsEvent,
    ): AccountSettingsTransition =
        when (event) {
            AccountSettingsEvent.RetryLoad -> state.retryLoad()
            is AccountSettingsEvent.ChangeRequested -> state.requestChange(event.change)
            AccountSettingsEvent.RetryChange -> state.retryChange()
            is AccountSettingsEvent.LoadSucceeded -> state.loadSucceeded(event)
            is AccountSettingsEvent.LoadFailed -> state.loadFailed(event)
            is AccountSettingsEvent.SaveSucceeded -> state.saveSucceeded(event)
            is AccountSettingsEvent.SaveFailed -> state.saveFailed(event)
            is AccountSettingsEvent.RefreshSucceeded -> state.refreshSucceeded(event)
            is AccountSettingsEvent.RefreshFailed -> state.refreshFailed(event)
        }
}

internal fun AccountSettingsState.authoritativeSessionFailure(): AccountSettingsFailure.AuthenticationRequired? =
    listOfNotNull(
        (content as? AccountSettingsContent.Failed)?.failure,
        (mutation as? AccountSettingsMutation.Failed)?.failure,
    ).filterIsInstance<AccountSettingsFailure.AuthenticationRequired>()
        .firstOrNull()

internal fun AccountSettingsState.confirmedHistoryEnabled(): Boolean? =
    if (mutation is AccountSettingsMutation.Idle) {
        (content as? AccountSettingsContent.Ready)?.preferences?.historyEnabled
    } else {
        null
    }

private const val INITIAL_REQUEST_VALUE = 1L
