package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsState

internal const val MOBILE_DEFAULT_SORT_ROW_TAG = "mobile-default-sort-row"
internal const val MOBILE_DEFAULT_SORT_DIALOG_TAG = "mobile-default-sort-dialog"

// Account-wide `sort_by`: the order every folder uses unless it has its own.
internal fun LazyListScope.defaultSortItem(
    settingsState: AccountSettingsState,
    onChoose: () -> Unit,
    onRetryChange: () -> Unit,
) {
    val preferences = (settingsState.content as? AccountSettingsContent.Ready)?.preferences ?: return
    val mutation = settingsState.mutation
    item(key = AccountSettingsKey.DefaultSort) {
        val saving = (mutation as? AccountSettingsMutation.Saving)?.change is AccountSettingsChange.Sort
        val failure = (mutation as? AccountSettingsMutation.Failed)?.takeIf { it.change is AccountSettingsChange.Sort }
        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_settings_default_sort)) },
                supportingContent = {
                    MobileAccountValueDescription(
                        value = preferences.defaultSort.displayName(),
                        description = stringResource(R.string.mobile_settings_default_sort_description),
                    )
                },
                leadingContent = {
                    Icon(painter = painterResource(R.drawable.ic_ph_sort_ascending), contentDescription = null)
                },
                trailingContent = if (saving) {
                    { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MOBILE_DEFAULT_SORT_ROW_TAG)
                    .clickable(
                        enabled = settingsState.accountControlsEnabled(),
                        role = Role.Button,
                        onClick = onChoose,
                    ),
            )
            failure?.let {
                MobileAccountMutationError(failure = it.failure, operation = it.operation, onRetry = onRetryChange)
            }
        }
    }
}

@Composable
internal fun MobileDefaultSortDialog(
    selected: FilesSort?,
    onSelect: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(MOBILE_DEFAULT_SORT_DIALOG_TAG),
        title = { Text(stringResource(R.string.mobile_settings_default_sort)) },
        text = {
            // Twelve options exceed most phone dialogs at large font scales; keep them reachable.
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                FilesSort.entries.forEach { sort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = sort == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(sort) },
                            )
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sort == selected, onClick = null)
                        Text(stringResource(sort.labelResource()))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mobile_action_cancel)) }
        },
    )
}

@Composable
private fun FilesSort?.displayName(): String =
    stringResource(this?.labelResource() ?: R.string.mobile_settings_default_sort_unknown)
