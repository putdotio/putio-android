package io.putdotio.android.settings

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesSort

@JvmInline
public value class AccountSettingsRequestId(
    internal val value: Long,
)

public data class AccountSettingsPreferences(
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
    // Privacy controls per the frontend analytics contract; the server defaults all three to true.
    val diagnosticsEnabled: Boolean = true,
    val productAnalyticsEnabled: Boolean = true,
    val supportWidgetEnabled: Boolean = true,
)

/** Server route identifier. `default` is the direct Amsterdam route and what a null setting means. */
@JvmInline
public value class TunnelRouteName(public val value: String) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Tunnel route names are non-blank and trimmed" }
    }

    public companion object {
        public val DEFAULT: TunnelRouteName = TunnelRouteName("default")

        internal fun fromServer(raw: String?): TunnelRouteName =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::TunnelRouteName) ?: DEFAULT
    }
}

public data class TunnelRouteOption(
    val name: TunnelRouteName,
    val description: String,
)

public enum class AccountSettingsKey {
    History,
    Trash,
    ShowSubtitles,
    AutoSelectSubtitles,
    ResumePlayback,
    TunnelRoute,
    DefaultSort,
    Diagnostics,
    ProductAnalytics,
    SupportWidget,
}

public sealed interface AccountSettingsChange {
    public val key: AccountSettingsKey

    public data class Toggle(
        override val key: AccountSettingsKey,
        val enabled: Boolean,
    ) : AccountSettingsChange {
        init {
            require(key !in NON_TOGGLE_KEYS) { "$key is not a toggle" }
        }
    }

    public data class Route(
        val name: TunnelRouteName,
    ) : AccountSettingsChange {
        override val key: AccountSettingsKey get() = AccountSettingsKey.TunnelRoute
    }

    public data class Sort(
        val sort: FilesSort,
    ) : AccountSettingsChange {
        override val key: AccountSettingsKey get() = AccountSettingsKey.DefaultSort
    }

    public companion object {
        // Keeps the boolean call sites readable: AccountSettingsChange(key, enabled).
        public operator fun invoke(key: AccountSettingsKey, enabled: Boolean): AccountSettingsChange =
            Toggle(key, enabled)
    }
}

public sealed interface AccountSettingsContent {
    public data class Loading(
        val requestId: AccountSettingsRequestId,
    ) : AccountSettingsContent

    public data class Ready(
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsContent

    public data class Failed(
        val failure: AccountSettingsFailure,
    ) : AccountSettingsContent
}

public sealed interface AccountSettingsMutation {
    public enum class Operation {
        Save,
        Refresh,
    }

    public data object Idle : AccountSettingsMutation

    public data class Saving(
        val requestId: AccountSettingsRequestId,
        val change: AccountSettingsChange,
        val previousPreferences: AccountSettingsPreferences,
        val operation: Operation,
    ) : AccountSettingsMutation

    public data class Failed(
        val change: AccountSettingsChange,
        val failure: AccountSettingsFailure,
        val previousPreferences: AccountSettingsPreferences,
        val operation: Operation,
    ) : AccountSettingsMutation
}

@ConsistentCopyVisibility
public data class AccountSettingsState internal constructor(
    val content: AccountSettingsContent,
    val mutation: AccountSettingsMutation,
    internal val nextRequestValue: Long,
)

public sealed interface AccountSettingsEvent {
    public data object RetryLoad : AccountSettingsEvent

    public data class ChangeRequested(
        val change: AccountSettingsChange,
    ) : AccountSettingsEvent

    public data object RetryChange : AccountSettingsEvent

    public data class LoadSucceeded(
        val requestId: AccountSettingsRequestId,
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsEvent

    public data class LoadFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent

    public data class SaveSucceeded(
        val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEvent

    public data class SaveFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent

    public data class RefreshSucceeded(
        val requestId: AccountSettingsRequestId,
        val preferences: AccountSettingsPreferences,
    ) : AccountSettingsEvent

    public data class RefreshFailed(
        val requestId: AccountSettingsRequestId,
        val failure: AccountSettingsFailure,
    ) : AccountSettingsEvent
}

public sealed interface AccountSettingsEffect {
    public val requestId: AccountSettingsRequestId

    public data class Load(
        override val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEffect

    public data class Save(
        override val requestId: AccountSettingsRequestId,
        val change: AccountSettingsChange,
    ) : AccountSettingsEffect

    public data class Refresh(
        override val requestId: AccountSettingsRequestId,
    ) : AccountSettingsEffect
}

public data class AccountSettingsTransition(
    val state: AccountSettingsState,
    val effect: AccountSettingsEffect? = null,
    val consumed: Boolean = true,
)

public object AccountSettingsReducer {
    public fun start(): AccountSettingsTransition {
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

    public fun reduce(
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

public fun AccountSettingsState.authoritativeSessionFailure(): AccountSettingsFailure? =
    listOfNotNull(
        (content as? AccountSettingsContent.Failed)?.failure,
        (mutation as? AccountSettingsMutation.Failed)?.failure,
    ).firstOrNull { it.putioFailure is PutioFailure.AuthenticationRequired }

public fun AccountSettingsState.confirmedHistoryEnabled(): Boolean? =
    confirmedPreferences(AccountSettingsKey.History)?.historyEnabled

public fun AccountSettingsState.confirmedTrashEnabled(): Boolean? =
    confirmedPreferences(AccountSettingsKey.Trash)?.trashEnabled

public fun AccountSettingsState.confirmedResumePlayback(): Boolean? =
    confirmedPreferences(AccountSettingsKey.ResumePlayback)?.resumePlayback

/** Whether the account shows subtitles (`!hide_subtitles`), or null while unloaded or while that write is unsettled. */
public fun AccountSettingsState.confirmedShowSubtitles(): Boolean? =
    confirmedPreferences(AccountSettingsKey.ShowSubtitles)?.showSubtitles

/**
 * The default sort the server holds, or null while unloaded or while a sort write is unsettled.
 * A refresh failure after an accepted write still counts: the server took the value.
 */
public fun AccountSettingsState.confirmedDefaultSort(): ConfirmedDefaultSort? {
    val ready = content as? AccountSettingsContent.Ready ?: return null
    val unsettledWrite = when (val current = mutation) {
        AccountSettingsMutation.Idle -> false
        is AccountSettingsMutation.Saving ->
            current.change.key == AccountSettingsKey.DefaultSort &&
                current.operation == AccountSettingsMutation.Operation.Save
        is AccountSettingsMutation.Failed ->
            current.change.key == AccountSettingsKey.DefaultSort &&
                current.operation == AccountSettingsMutation.Operation.Save
    }
    return ConfirmedDefaultSort(ready.preferences.defaultSort).takeUnless { unsettledWrite }
}

// Wraps the nullable sort so "confirmed as unknown to this app" stays distinct from "not confirmed".
@JvmInline
public value class ConfirmedDefaultSort(internal val sort: FilesSort?)

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
