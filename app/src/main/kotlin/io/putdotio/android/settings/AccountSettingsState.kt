package io.putdotio.android.settings

import io.putdotio.android.files.FilesSort

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
    // Account-wide `tunnel_route_name`; the server reports null for the direct route.
    val tunnelRoute: TunnelRouteName = TunnelRouteName.DEFAULT,
    // Account-wide `sort_by`: folders without their own sort use it. Null means the
    // server reported a value this app does not know.
    val defaultSort: FilesSort? = null,
)

/** Server route identifier. `default` is the direct Amsterdam route and what a null setting means. */
@JvmInline
internal value class TunnelRouteName(val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Tunnel route names are non-blank and trimmed" }
    }

    companion object {
        val DEFAULT = TunnelRouteName("default")

        fun fromServer(raw: String?): TunnelRouteName =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::TunnelRouteName) ?: DEFAULT
    }
}

internal data class TunnelRouteOption(
    val name: TunnelRouteName,
    val description: String,
)

internal enum class AccountSettingsKey {
    History,
    Trash,
    ShowSubtitles,
    AutoSelectSubtitles,
    ResumePlayback,
    TunnelRoute,
    DefaultSort,
}

internal sealed interface AccountSettingsChange {
    val key: AccountSettingsKey

    data class Toggle(
        override val key: AccountSettingsKey,
        val enabled: Boolean,
    ) : AccountSettingsChange {
        init {
            require(key !in NON_TOGGLE_KEYS) { "$key is not a toggle" }
        }
    }

    data class Route(
        val name: TunnelRouteName,
    ) : AccountSettingsChange {
        override val key: AccountSettingsKey get() = AccountSettingsKey.TunnelRoute
    }

    data class Sort(
        val sort: FilesSort,
    ) : AccountSettingsChange {
        override val key: AccountSettingsKey get() = AccountSettingsKey.DefaultSort
    }

    companion object {
        // Keeps the boolean call sites readable: AccountSettingsChange(key, enabled).
        operator fun invoke(key: AccountSettingsKey, enabled: Boolean): AccountSettingsChange = Toggle(key, enabled)
    }
}

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
    confirmedPreferences(AccountSettingsKey.History)?.historyEnabled

internal fun AccountSettingsState.confirmedTrashEnabled(): Boolean? =
    confirmedPreferences(AccountSettingsKey.Trash)?.trashEnabled

/** The confirmed account default sort, or null while unloaded or while a sort save is pending. */
internal fun AccountSettingsState.confirmedDefaultSort(): ConfirmedDefaultSort? =
    confirmedPreferences(AccountSettingsKey.DefaultSort)?.let { ConfirmedDefaultSort(it.defaultSort) }

// Wraps the nullable sort so "confirmed as unknown to this app" stays distinct from "not confirmed".
@JvmInline
internal value class ConfirmedDefaultSort(val sort: FilesSort?)

// A pending or failed mutation only makes its own key unconfirmed; the other
// preferences still reflect the last authoritative read.
private fun AccountSettingsState.confirmedPreferences(key: AccountSettingsKey): AccountSettingsPreferences? {
    val ready = content as? AccountSettingsContent.Ready ?: return null
    val pendingKey = when (val current = mutation) {
        AccountSettingsMutation.Idle -> null
        is AccountSettingsMutation.Saving -> current.change.key
        is AccountSettingsMutation.Failed -> current.change.key
    }
    return ready.preferences.takeUnless { pendingKey == key }
}

private const val INITIAL_REQUEST_VALUE = 1L
private val NON_TOGGLE_KEYS = setOf(AccountSettingsKey.TunnelRoute, AccountSettingsKey.DefaultSort)
