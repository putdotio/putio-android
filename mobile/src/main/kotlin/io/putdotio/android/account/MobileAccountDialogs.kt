package io.putdotio.android.account

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioDestructiveButton
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.labelResource
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.neutralTextButtonColors

internal const val MOBILE_DEFAULT_SORT_ROW_TAG = "mobile-default-sort-row"
internal const val MOBILE_DEFAULT_SORT_DIALOG_TAG = "mobile-default-sort-dialog"
internal const val MOBILE_TUNNEL_ROUTE_RETRY_TAG = "mobile-tunnel-route-retry"
internal const val MOBILE_SIGN_OUT_DIALOG_TAG = "mobile-sign-out-dialog"
internal const val MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG = "mobile-sign-out-remove-downloads"

/** Account's dialogs sit 24dp from the window's sides, as [MobileChoiceDialog] does; the platform's are narrower. */
private val AccountDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

private fun Modifier.accountDialogWidth(): Modifier =
    widthIn(max = ACCOUNT_DIALOG_MAX_WIDTH).fillMaxWidth().padding(horizontal = 24.dp)

// Material's widest dialog, plus the 24dp margins either side.
private val ACCOUNT_DIALOG_MAX_WIDTH = 608.dp

/** Account-wide `sort_by`: the order every folder uses unless it has its own. */
@Composable
internal fun MobileDefaultSortDialog(
    selected: FilesSort?,
    onSave: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
) {
    MobileChoiceDialog<FilesSort?>(
        title = stringResource(R.string.mobile_settings_default_sort),
        message = stringResource(R.string.mobile_settings_default_sort_description),
        choices = FilesSort.entries,
        label = { stringResource(requireNotNull(it).labelResource()) },
        description = { null },
        selected = selected,
        onSave = { sort -> sort?.let(onSave) },
        onDismiss = onDismiss,
        modifier = Modifier.testTag(MOBILE_DEFAULT_SORT_DIALOG_TAG),
    )
}

@Composable
internal fun MobilePlaybackTypeDialog(
    selected: VideoPlaybackType,
    onSave: (VideoPlaybackType) -> Unit,
    onDismiss: () -> Unit,
) {
    MobileChoiceDialog(
        title = stringResource(R.string.mobile_settings_video_playback_type),
        message = stringResource(R.string.mobile_settings_video_playback_type_description),
        choices = VideoPlaybackType.entries,
        label = { stringResource(it.labelResource()) },
        description = {
            stringResource(
                when (it) {
                    VideoPlaybackType.Hls -> R.string.mobile_settings_video_playback_hls_description
                    VideoPlaybackType.Mp4 -> R.string.mobile_settings_video_playback_mp4_description
                },
            )
        },
        selected = selected,
        onSave = onSave,
        onDismiss = onDismiss,
    )
}

// Routes load when the picker opens: the list depends on the account's CDN eligibility
// and the caller's address, so it is not worth caching across the session.
@Composable
internal fun MobileTunnelRouteDialog(
    selected: TunnelRouteName,
    loadRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onSave: (TunnelRouteName) -> Unit,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<AccountSettingsRepositoryResult<List<TunnelRouteOption>>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = loadRoutes()
    }
    val routes = (result as? AccountSettingsRepositoryResult.Success)?.value
    val direct = stringResource(R.string.mobile_settings_tunnel_route_default_description)
    MobileChoiceDialog(
        title = stringResource(R.string.mobile_settings_tunnel_route),
        message = stringResource(R.string.mobile_settings_tunnel_route_description),
        choices = routes?.map { it.name },
        label = { it.displayName() },
        description = { name ->
            val label = name.displayName()
            if (name == TunnelRouteName.DEFAULT) {
                direct
            } else {
                routes?.firstOrNull { it.name == name }?.description?.takeIf { it.isNotEmpty() && it != label }
            }
        },
        selected = selected,
        onSave = onSave,
        onDismiss = onDismiss,
        placeholder = {
            when (val loaded = result) {
                is AccountSettingsRepositoryResult.Failure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.mobile_settings_tunnel_route_error),
                        color = PutioDesignTokens.redTextSecondary,
                    )
                    Text(loaded.failure.message())
                    if (loaded.failure.putioFailure !is PutioFailure.AuthenticationRequired) {
                        TextButton(
                            onClick = { attempt += 1 },
                            colors = neutralTextButtonColors(),
                            modifier = Modifier.testTag(MOBILE_TUNNEL_ROUTE_RETRY_TAG),
                        ) { Text(stringResource(R.string.mobile_action_retry)) }
                    }
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.mobile_settings_tunnel_route_loading))
                }
            }
        },
    )
}

/** Turning Trash off makes every later delete permanent, so it confirms in red. */
@Composable
internal fun MobileTrashOffDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.accountDialogWidth(),
        properties = AccountDialogProperties,
        title = { Text(stringResource(R.string.mobile_settings_trash_confirm_title)) },
        text = {
            Text(
                stringResource(R.string.mobile_settings_trash_confirm_message),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            PutioDestructiveButton(onClick = onConfirm) {
                Text(stringResource(R.string.mobile_settings_trash_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

/**
 * Sign out confirms. Downloads stay unless the viewer ticks Remove downloads, which shows only when this
 * account has some on the device, with what they take in mono.
 */
@Composable
internal fun MobileSignOutDialog(
    downloadsOnDeviceBytes: Long?,
    onDismiss: () -> Unit,
    onSignOut: (removeDownloads: Boolean) -> Unit,
) {
    var removeDownloads by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.accountDialogWidth().testTag(MOBILE_SIGN_OUT_DIALOG_TAG),
        properties = AccountDialogProperties,
        title = { Text(stringResource(R.string.mobile_account_sign_out_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mobile_account_sign_out_message))
                downloadsOnDeviceBytes?.let { bytes ->
                    Row(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = removeDownloads, role = Role.Checkbox) { removeDownloads = it }
                            .testTag(MOBILE_SIGN_OUT_REMOVE_DOWNLOADS_TAG),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = removeDownloads,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(uncheckedColor = PutioDesignTokens.solid),
                        )
                        Text(
                            text = removeDownloadsLabel(bytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            PutioDestructiveButton(onClick = { onSignOut(removeDownloads && downloadsOnDeviceBytes != null) }) {
                Text(stringResource(R.string.mobile_account_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = neutralTextButtonColors()) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}

/** "Remove downloads from this device", then their size in parentheses, mono and secondary; no size at 0. */
@Composable
private fun removeDownloadsLabel(bytes: Long): AnnotatedString {
    val label = stringResource(R.string.mobile_account_sign_out_remove_downloads)
    if (bytes <= 0L) return AnnotatedString(label)
    val size = stringResource(
        R.string.mobile_account_sign_out_downloads_size,
        Formatter.formatShortFileSize(LocalContext.current, bytes),
    )
    val style: SpanStyle = MaterialTheme.typography.bodyMedium.putioMono().toSpanStyle()
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    return buildAnnotatedString {
        append(label)
        append(' ')
        withStyle(style) { append(size) }
    }
}
