package io.putdotio.android

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAccountStorage
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.VideoPlaybackType
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MOBILE_ACCOUNT_LIST_TAG = "mobile-account-list"
internal const val MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG = "mobile-account-avatar-fallback"
internal const val MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG = "mobile-account-storage-progress"

@Composable
internal fun MobileAccountScreen(
    account: MobileAccount,
    sessionId: MobileAuthSessionId,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTrashDisable by rememberSaveable(sessionId) { mutableStateOf(false) }
    var choosePlaybackType by rememberSaveable(sessionId) { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_ACCOUNT_LIST_TAG),
    ) {
        item(key = ACCOUNT_IDENTITY_KEY) {
            MobileAccountIdentity(account)
        }
        item(key = ACCOUNT_IDENTITY_DIVIDER_KEY) {
            HorizontalDivider()
        }
        when (val content = settingsState.content) {
            is AccountSettingsContent.Loading ->
                item(key = SETTINGS_LOADING_KEY) {
                    MobileAccountSettingsLoading()
                }

            is AccountSettingsContent.Failed ->
                item(key = SETTINGS_ERROR_KEY) {
                    MobileAccountSettingsError(
                        failure = content.failure,
                        onRetry = { onSettingsEvent(AccountSettingsEvent.RetryLoad) },
                    )
                }

            is AccountSettingsContent.Ready ->
                accountSettingsItems(
                    preferences = content.preferences,
                    mutation = settingsState.mutation,
                    onChange = { change ->
                        if (change.key == AccountSettingsKey.Trash && !change.enabled) {
                            confirmTrashDisable = true
                        } else {
                            onSettingsEvent(AccountSettingsEvent.ChangeRequested(change))
                        }
                    },
                    onRetryChange = { onSettingsEvent(AccountSettingsEvent.RetryChange) },
                )
        }
        appConfigItems(
            state = appConfigState,
            onChoosePlaybackType = { choosePlaybackType = true },
            onEvent = onAppConfigEvent,
        )
        item(key = SIGN_OUT_KEY) {
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.mobile_account_sign_out))
            }
        }
    }

    if (confirmTrashDisable) {
        AlertDialog(
            onDismissRequest = { confirmTrashDisable = false },
            title = { Text(stringResource(R.string.mobile_settings_trash_confirm_title)) },
            text = { Text(stringResource(R.string.mobile_settings_trash_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrashDisable = false
                        onSettingsEvent(
                            AccountSettingsEvent.ChangeRequested(
                                AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.mobile_settings_trash_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrashDisable = false }) {
                    Text(stringResource(R.string.mobile_action_cancel))
                }
            },
        )
    }

    val appConfig = appConfigState.content as? AndroidAppConfigContent.Ready
    if (choosePlaybackType && appConfig != null && appConfigState.appConfigControlsEnabled()) {
        MobilePlaybackTypeDialog(
            selected = appConfig.preferences.videoPlaybackType,
            onSelect = { playbackType ->
                choosePlaybackType = false
                onAppConfigEvent(
                    AndroidAppConfigEvent.ChangeRequested(
                        AndroidAppConfigChange.VideoPlayback(playbackType),
                    ),
                )
            },
            onDismiss = { choosePlaybackType = false },
        )
    }
}

@Composable
private fun MobileAccountIdentity(account: MobileAccount) {
    val context = LocalContext.current
    val storageCopy =
        stringResource(
            R.string.mobile_account_storage,
            Formatter.formatShortFileSize(context, account.storage.usedBytes.coerceAtLeast(0L)),
            Formatter.formatShortFileSize(context, account.storage.availableBytes.coerceAtLeast(0L)),
        )
    ListItem(
        headlineContent = { Text(account.username) },
        supportingContent = {
            Column {
                Text(account.email)
                Text(
                    text = storageCopy,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { account.storage.usedFraction() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG),
                )
            }
        },
        leadingContent = {
            MobileAccountAvatar(account.avatarUrl)
        },
        overlineContent = { Text(stringResource(R.string.mobile_account_signed_in_as)) },
    )
}

@Composable
private fun MobileAccountAvatar(avatarUrl: String?) {
    val resolvedUrl = avatarUrl?.takeIf(String::isSupportedAvatarUrl)
    val avatarModifier =
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    if (resolvedUrl == null) {
        MobileAccountAvatarFallback(avatarModifier)
        return
    }

    SubcomposeAsyncImage(
        model = resolvedUrl,
        contentDescription = null,
        modifier = avatarModifier,
        contentScale = ContentScale.Crop,
        loading = { MobileAccountAvatarFallback(Modifier.fillMaxSize()) },
        error = { MobileAccountAvatarFallback(Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun MobileAccountAvatarFallback(modifier: Modifier) {
    Box(
        modifier = modifier.testTag(MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_user_circle_fill),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}

internal fun String.isSupportedAvatarUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        uri.host.isNullOrBlank() ||
        uri.userInfo != null ||
        uri.rawAuthority?.endsWith(':') == true
    ) {
        return false
    }
    val url = toHttpUrlOrNull() ?: return false
    return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
}

private fun MobileAccountStorage.usedFraction(): Float {
    if (sizeBytes <= 0L) return 0f
    return (usedBytes.toDouble() / sizeBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

private fun LazyListScope.accountSettingsItems(
    preferences: AccountSettingsPreferences,
    mutation: AccountSettingsMutation,
    onChange: (AccountSettingsChange) -> Unit,
    onRetryChange: () -> Unit,
) {
    val enabled =
        when (mutation) {
            AccountSettingsMutation.Idle -> true
            is AccountSettingsMutation.Saving -> false
            is AccountSettingsMutation.Failed ->
                mutation.failure !is AccountSettingsFailure.AuthenticationRequired
        }
    val savingKey = (mutation as? AccountSettingsMutation.Saving)?.change?.key
    val failedMutation = mutation as? AccountSettingsMutation.Failed
    item(key = SUBTITLES_HEADER_KEY) {
        MobileAccountSectionHeader(R.string.mobile_settings_section_subtitles)
    }
    item(key = AccountSettingsKey.ShowSubtitles) {
        MobileAccountSettingRow(
            title = R.string.mobile_settings_show_subtitles,
            description = R.string.mobile_settings_show_subtitles_description,
            icon = R.drawable.ic_ph_subtitles,
            checked = preferences.showSubtitles,
            enabled = enabled,
            saving = savingKey == AccountSettingsKey.ShowSubtitles,
            failure = failedMutation?.takeIf { it.change.key == AccountSettingsKey.ShowSubtitles },
            onRetry = onRetryChange,
            onCheckedChange = {
                onChange(AccountSettingsChange(AccountSettingsKey.ShowSubtitles, it))
            },
        )
    }
    if (preferences.showSubtitles) {
        item(key = AccountSettingsKey.AutoSelectSubtitles) {
            MobileAccountSettingRow(
                title = R.string.mobile_settings_auto_select_subtitles,
                description = R.string.mobile_settings_auto_select_subtitles_description,
                icon = R.drawable.ic_ph_list_checks,
                checked = preferences.autoSelectSubtitles,
                enabled = enabled,
                saving = savingKey == AccountSettingsKey.AutoSelectSubtitles,
                failure = failedMutation?.takeIf { it.change.key == AccountSettingsKey.AutoSelectSubtitles },
                onRetry = onRetryChange,
                onCheckedChange = {
                    onChange(AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, it))
                },
            )
        }
    }
    item(key = PRIVACY_STORAGE_HEADER_KEY) {
        MobileAccountSectionHeader(R.string.mobile_settings_section_privacy_storage)
    }
    item(key = AccountSettingsKey.History) {
        MobileAccountSettingRow(
            title = R.string.mobile_settings_history,
            description = R.string.mobile_settings_history_description,
            icon = R.drawable.ic_ph_clock_counter_clockwise,
            checked = preferences.historyEnabled,
            enabled = enabled,
            saving = savingKey == AccountSettingsKey.History,
            failure = failedMutation?.takeIf { it.change.key == AccountSettingsKey.History },
            onRetry = onRetryChange,
            onCheckedChange = {
                onChange(AccountSettingsChange(AccountSettingsKey.History, it))
            },
        )
    }
    item(key = AccountSettingsKey.Trash) {
        MobileAccountSettingRow(
            title = R.string.mobile_settings_trash,
            description = R.string.mobile_settings_trash_description,
            icon = R.drawable.ic_ph_trash,
            checked = preferences.trashEnabled,
            enabled = enabled,
            saving = savingKey == AccountSettingsKey.Trash,
            failure = failedMutation?.takeIf { it.change.key == AccountSettingsKey.Trash },
            onRetry = onRetryChange,
            onCheckedChange = {
                onChange(AccountSettingsChange(AccountSettingsKey.Trash, it))
            },
        )
    }
}

private fun LazyListScope.appConfigItems(
    state: AndroidAppConfigState,
    onChoosePlaybackType: () -> Unit,
    onEvent: (AndroidAppConfigEvent) -> Unit,
) {
    item(key = PLAYBACK_HEADER_KEY) {
        MobileAccountSectionHeader(R.string.mobile_settings_section_playback)
    }
    when (val content = state.content) {
        is AndroidAppConfigContent.Loading ->
            item(key = APP_CONFIG_LOADING_KEY) {
                MobileAppConfigLoading()
            }
        is AndroidAppConfigContent.Failed ->
            item(key = APP_CONFIG_ERROR_KEY) {
                MobileAppConfigLoadError(
                    failure = content.failure,
                    onRetry = { onEvent(AndroidAppConfigEvent.RetryLoad) },
                )
            }
        is AndroidAppConfigContent.Ready -> {
            val mutation = state.mutation
            val enabled = state.appConfigControlsEnabled()
            val saving = mutation as? AndroidAppConfigMutation.Saving
            val failed = mutation as? AndroidAppConfigMutation.Failed
            item(key = VIDEO_PLAYBACK_TYPE_KEY) {
                MobilePlaybackTypeRow(
                    preferences = content.preferences,
                    enabled = enabled,
                    saving = saving?.change is AndroidAppConfigChange.VideoPlayback,
                    failure = failed?.takeIf { it.change is AndroidAppConfigChange.VideoPlayback },
                    onChoose = onChoosePlaybackType,
                    onRetry = { onEvent(AndroidAppConfigEvent.RetryChange) },
                )
            }
            item(key = AUTOPLAY_NEXT_VIDEO_KEY) {
                MobileAppConfigSwitchRow(
                    title = R.string.mobile_settings_autoplay_next,
                    description = R.string.mobile_settings_autoplay_next_description,
                    icon = R.drawable.ic_ph_list_checks,
                    checked = content.preferences.autoplayNextVideo,
                    enabled = enabled,
                    saving = saving?.change is AndroidAppConfigChange.AutoplayNextVideo,
                    failure = failed?.takeIf { it.change is AndroidAppConfigChange.AutoplayNextVideo },
                    onRetry = { onEvent(AndroidAppConfigEvent.RetryChange) },
                    onCheckedChange = {
                        onEvent(
                            AndroidAppConfigEvent.ChangeRequested(
                                AndroidAppConfigChange.AutoplayNextVideo(it),
                            ),
                        )
                    },
                )
            }
        }
    }
}

private fun AndroidAppConfigState.appConfigControlsEnabled(): Boolean =
    when (val currentMutation = mutation) {
        AndroidAppConfigMutation.Idle -> true
        is AndroidAppConfigMutation.Saving -> false
        is AndroidAppConfigMutation.Failed ->
            currentMutation.failure !is AndroidAppConfigFailure.AuthenticationRequired
    }

@Composable
private fun MobilePlaybackTypeRow(
    preferences: AndroidAppConfigPreferences,
    enabled: Boolean,
    saving: Boolean,
    failure: AndroidAppConfigMutation.Failed?,
    onChoose: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.mobile_settings_video_playback_type)) },
            supportingContent = { Text(stringResource(R.string.mobile_settings_video_playback_type_description)) },
            leadingContent = {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_file_video_fill),
                    contentDescription = null,
                )
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(preferences.videoPlaybackType.labelResource()))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onChoose,
                ),
        )
        failure?.let {
            MobileAppConfigMutationError(it.failure, it.operation, onRetry)
        }
    }
}

@Composable
private fun MobileAppConfigSwitchRow(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    checked: Boolean,
    enabled: Boolean,
    saving: Boolean,
    failure: AndroidAppConfigMutation.Failed?,
    onRetry: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(description)) },
            leadingContent = { Icon(painterResource(icon), contentDescription = null) },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        )
        failure?.let {
            MobileAppConfigMutationError(it.failure, it.operation, onRetry)
        }
    }
}

@Composable
private fun MobilePlaybackTypeDialog(
    selected: VideoPlaybackType,
    onSelect: (VideoPlaybackType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mobile_settings_video_playback_type)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                VideoPlaybackType.entries.forEach { playbackType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = playbackType == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(playbackType) },
                            )
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = playbackType == selected,
                            onClick = null,
                        )
                        Text(stringResource(playbackType.labelResource()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

@StringRes
private fun VideoPlaybackType.labelResource(): Int =
    when (this) {
        VideoPlaybackType.Hls -> R.string.mobile_settings_video_playback_hls
        VideoPlaybackType.Mp4 -> R.string.mobile_settings_video_playback_mp4
    }

@Composable
private fun MobileAppConfigLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.mobile_settings_playback_loading))
    }
}

@Composable
private fun MobileAppConfigLoadError(
    failure: AndroidAppConfigFailure,
    onRetry: () -> Unit,
) {
    ListItem(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        headlineContent = { Text(stringResource(R.string.mobile_settings_playback_error_title)) },
        supportingContent = { Text(stringResource(failure.messageResource())) },
        trailingContent = {
            if (failure !is AndroidAppConfigFailure.AuthenticationRequired) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        },
    )
}

@Composable
private fun MobileAppConfigMutationError(
    failure: AndroidAppConfigFailure,
    operation: AndroidAppConfigMutation.Operation,
    onRetry: () -> Unit,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(failure, operation) {
        bringIntoViewRequester.bringIntoView()
    }
    val title =
        when (operation) {
            AndroidAppConfigMutation.Operation.Save -> R.string.mobile_settings_save_error
            AndroidAppConfigMutation.Operation.Refresh -> R.string.mobile_settings_refresh_error
        }
    ListItem(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .semantics { liveRegion = LiveRegionMode.Polite },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(failure.messageResource())) },
        trailingContent = {
            if (failure !is AndroidAppConfigFailure.AuthenticationRequired) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        },
    )
}

@StringRes
private fun AndroidAppConfigFailure.messageResource(): Int =
    when (this) {
        is AndroidAppConfigFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is AndroidAppConfigFailure.AccessDenied -> R.string.mobile_settings_playback_error_access_denied
        is AndroidAppConfigFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is AndroidAppConfigFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
        is AndroidAppConfigFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is AndroidAppConfigFailure.ApiRejected,
        is AndroidAppConfigFailure.InvalidResponse,
        is AndroidAppConfigFailure.Misconfigured,
        is AndroidAppConfigFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }

@Composable
private fun MobileAccountSectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun MobileAccountSettingRow(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    checked: Boolean,
    enabled: Boolean,
    saving: Boolean,
    failure: AccountSettingsMutation.Failed?,
    onRetry: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(description)) },
            leadingContent = {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        )
        failure?.let {
            MobileAccountMutationError(
                failure = it.failure,
                operation = it.operation,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun MobileAccountSettingsLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.mobile_settings_loading))
    }
}

@Composable
private fun MobileAccountSettingsError(
    failure: AccountSettingsFailure,
    onRetry: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.mobile_settings_error_title)) },
        supportingContent = { Text(stringResource(failure.messageResource())) },
        trailingContent = {
            if (failure !is AccountSettingsFailure.AuthenticationRequired) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        },
    )
}

@Composable
private fun MobileAccountMutationError(
    failure: AccountSettingsFailure,
    operation: AccountSettingsMutation.Operation,
    onRetry: () -> Unit,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(failure, operation) {
        bringIntoViewRequester.bringIntoView()
    }
    val title =
        when (operation) {
            AccountSettingsMutation.Operation.Save -> R.string.mobile_settings_save_error
            AccountSettingsMutation.Operation.Refresh -> R.string.mobile_settings_refresh_error
        }
    ListItem(
        modifier = Modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .semantics { liveRegion = LiveRegionMode.Polite },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(failure.messageResource())) },
        trailingContent = {
            if (failure !is AccountSettingsFailure.AuthenticationRequired) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.mobile_action_retry))
                }
            }
        },
    )
}

@StringRes
private fun AccountSettingsFailure.messageResource(): Int =
    when (this) {
        is AccountSettingsFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is AccountSettingsFailure.AccessDenied -> R.string.mobile_settings_error_access_denied
        is AccountSettingsFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is AccountSettingsFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
        is AccountSettingsFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is AccountSettingsFailure.ApiRejected,
        is AccountSettingsFailure.InvalidResponse,
        is AccountSettingsFailure.Misconfigured,
        is AccountSettingsFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }

private const val ACCOUNT_IDENTITY_KEY = "account-identity"
private const val ACCOUNT_IDENTITY_DIVIDER_KEY = "account-identity-divider"
private const val SETTINGS_LOADING_KEY = "account-settings-loading"
private const val SETTINGS_ERROR_KEY = "account-settings-error"
private const val SUBTITLES_HEADER_KEY = "account-settings-subtitles-header"
private const val PRIVACY_STORAGE_HEADER_KEY = "account-settings-privacy-storage-header"
private const val PLAYBACK_HEADER_KEY = "app-config-playback-header"
private const val APP_CONFIG_LOADING_KEY = "app-config-loading"
private const val APP_CONFIG_ERROR_KEY = "app-config-error"
private const val VIDEO_PLAYBACK_TYPE_KEY = "app-config-video-playback-type"
private const val AUTOPLAY_NEXT_VIDEO_KEY = "app-config-autoplay-next-video"
private const val SIGN_OUT_KEY = "account-sign-out"
