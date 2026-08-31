package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState

internal const val MOBILE_ACCOUNT_LIST_TAG = "mobile-account-list"

@Composable
internal fun MobileAccountScreen(
    account: MobileAccount,
    settingsState: AccountSettingsState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTrashDisable by rememberSaveable { mutableStateOf(false) }
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
}

@Composable
private fun MobileAccountIdentity(account: MobileAccount) {
    ListItem(
        headlineContent = { Text(account.username) },
        supportingContent = { Text(account.email) },
        leadingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_ph_user_circle_fill),
                contentDescription = null,
            )
        },
        overlineContent = { Text(stringResource(R.string.mobile_account_signed_in_as)) },
    )
}

private fun LazyListScope.accountSettingsItems(
    preferences: AccountSettingsPreferences,
    mutation: AccountSettingsMutation,
    onChange: (AccountSettingsChange) -> Unit,
    onRetryChange: () -> Unit,
) {
    val enabled = mutation == AccountSettingsMutation.Idle
    val savingKey = (mutation as? AccountSettingsMutation.Saving)?.change?.key
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
            onCheckedChange = {
                onChange(AccountSettingsChange(AccountSettingsKey.Trash, it))
            },
        )
    }
    if (mutation is AccountSettingsMutation.Failed) {
        item(key = SETTINGS_MUTATION_ERROR_KEY) {
            MobileAccountMutationError(
                failure = mutation.failure,
                onRetry = onRetryChange,
            )
        }
    }
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
    onCheckedChange: (Boolean) -> Unit,
) {
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
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        },
    )
}

@Composable
private fun MobileAccountMutationError(
    failure: AccountSettingsFailure,
    onRetry: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.mobile_settings_save_error)) },
        supportingContent = { Text(stringResource(failure.messageResource())) },
        trailingContent = {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        },
    )
}

@StringRes
private fun AccountSettingsFailure.messageResource(): Int =
    when (this) {
        is AccountSettingsFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is AccountSettingsFailure.AccessDenied -> R.string.mobile_state_error_forbidden
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
private const val SETTINGS_MUTATION_ERROR_KEY = "account-settings-mutation-error"
private const val SIGN_OUT_KEY = "account-sign-out"
