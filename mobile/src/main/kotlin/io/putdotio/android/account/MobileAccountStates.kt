package io.putdotio.android.account

import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsState
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.labelResource
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.apiReason
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.transfers.neutralTextButtonColors

/** [template] with its `%1$s`, `%2$s`… slots filled by [values] in [mono]. */
internal fun monoArguments(template: String, mono: SpanStyle, vararg values: String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        val slot = Regex("%(\\d+)\\\$s")
        for (match in slot.findAll(template)) {
            append(template.substring(cursor, match.range.first))
            val value = values.getOrNull(match.groupValues[1].toInt() - 1).orEmpty()
            withStyle(mono) { append(value) }
            cursor = match.range.last + 1
        }
        append(template.substring(cursor))
    }

internal fun androidx.compose.ui.platform.UriHandler.openSafely(url: String) {
    try {
        openUri(url)
    } catch (ignored: IllegalArgumentException) {
        // No app can open the link; there is nothing else to open it with.
    }
}

@Composable
internal fun FilesSort?.displayName(): String =
    stringResource(this?.labelResource() ?: R.string.mobile_settings_default_sort_unknown)

@Composable
internal fun TunnelRouteName.displayName(): String =
    if (this == TunnelRouteName.DEFAULT) stringResource(R.string.mobile_settings_tunnel_route_default) else value

@StringRes
internal fun VideoPlaybackType.labelResource(): Int =
    when (this) {
        VideoPlaybackType.Hls -> R.string.mobile_settings_video_playback_hls
        VideoPlaybackType.Mp4 -> R.string.mobile_settings_video_playback_mp4
    }

internal val AccountSettingsChange.disablesTrash: Boolean
    get() = this is AccountSettingsChange.Toggle && key == AccountSettingsKey.Trash && !enabled

internal fun AccountSettingsState.accountControlsEnabled(): Boolean = mutation.controlsEnabled()

private fun AccountSettingsMutation.controlsEnabled(): Boolean =
    when (this) {
        AccountSettingsMutation.Idle -> true
        is AccountSettingsMutation.Saving -> false
        is AccountSettingsMutation.Failed -> failure.putioFailure !is PutioFailure.AuthenticationRequired
    }

internal fun AndroidAppConfigState.appConfigControlsEnabled(): Boolean =
    when (val currentMutation = mutation) {
        AndroidAppConfigMutation.Idle -> true
        is AndroidAppConfigMutation.Saving -> false
        is AndroidAppConfigMutation.Failed -> currentMutation.failure !is PutioFailure.AuthenticationRequired
    }

/** A load failure in place of a group: the reason across the row, then Try again under it. */
@Composable
private fun MobileLoadFailure(@StringRes title: Int, message: String, onRetry: (() -> Unit)?) {
    ListItem(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = {
            Column {
                Text(message)
                onRetry?.let {
                    TextButton(onClick = it, colors = neutralTextButtonColors()) {
                        Text(stringResource(R.string.mobile_action_retry))
                    }
                }
            }
        },
    )
}

@Composable
internal fun MobileAppConfigLoadError(failure: PutioFailure, onRetry: () -> Unit) {
    MobileLoadFailure(
        title = R.string.mobile_settings_playback_error_title,
        message = failure.appConfigMessage(),
        onRetry = onRetry.takeIf { failure !is PutioFailure.AuthenticationRequired },
    )
}

@Composable
internal fun MobileAccountSettingsError(failure: AccountSettingsFailure, onRetry: () -> Unit) {
    MobileLoadFailure(
        title = R.string.mobile_settings_error_title,
        message = failure.message(),
        onRetry = onRetry.takeIf { failure.putioFailure !is PutioFailure.AuthenticationRequired },
    )
}

@Composable
internal fun MobileAccountSettingsLoading() {
    MobileAccountLoadingRow(R.string.mobile_settings_loading)
}

@Composable
internal fun MobileAccountLoadingRow(@StringRes text: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(stringResource(text))
    }
}

/** A save that failed, under its row on the same surface; it scrolls itself into view and announces. */
@Composable
private fun MobileSettingsMutationFailure(@StringRes title: Int, message: String, onRetry: (() -> Unit)?) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(title, message) { bringIntoViewRequester.bringIntoView() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 54.dp, end = 16.dp),
    ) {
        Text(stringResource(title), style = PutioTypeScale.bodyMd, color = PutioDesignTokens.redTextSecondary)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        onRetry?.let {
            TextButton(onClick = it, colors = neutralTextButtonColors(), modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

@Composable
internal fun MobileAccountMutationError(
    failure: AccountSettingsFailure,
    operation: AccountSettingsMutation.Operation,
    onRetry: () -> Unit,
) {
    MobileSettingsMutationFailure(
        title = when (operation) {
            AccountSettingsMutation.Operation.Save -> R.string.mobile_settings_save_error
            AccountSettingsMutation.Operation.Refresh -> R.string.mobile_settings_refresh_error
        },
        message = failure.message(),
        onRetry = onRetry.takeIf { failure.putioFailure !is PutioFailure.AuthenticationRequired },
    )
}

@Composable
internal fun MobileAppConfigMutationError(
    failure: PutioFailure,
    operation: AndroidAppConfigMutation.Operation,
    onRetry: () -> Unit,
) {
    MobileSettingsMutationFailure(
        title = when (operation) {
            AndroidAppConfigMutation.Operation.Save -> R.string.mobile_settings_save_error
            AndroidAppConfigMutation.Operation.Refresh -> R.string.mobile_settings_refresh_error
        },
        message = failure.appConfigMessage(),
        onRetry = onRetry.takeIf { failure !is PutioFailure.AuthenticationRequired },
    )
}

@Composable
private fun PutioFailure.appConfigMessage(): String = apiReason ?: stringResource(appConfigMessageResource())

@StringRes
private fun PutioFailure.appConfigMessageResource(): Int =
    when (this) {
        is PutioFailure.AuthenticationRequired -> R.string.mobile_state_error_session
        is PutioFailure.AccessDenied -> R.string.mobile_settings_playback_error_access_denied
        is PutioFailure.RateLimited -> R.string.mobile_state_error_rate_limited
        is PutioFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
        is PutioFailure.NetworkUnavailable -> R.string.mobile_state_error_message
        is PutioFailure.ApiRejected,
        is PutioFailure.InvalidResponse,
        is PutioFailure.Misconfigured,
        is PutioFailure.Unexpected,
        -> R.string.mobile_state_error_unavailable
    }

@Composable
internal fun AccountSettingsFailure.message(): String = apiReason ?: stringResource(messageResource())

@StringRes
private fun AccountSettingsFailure.messageResource(): Int =
    when (this) {
        is AccountSettingsFailure.RouteUnavailable -> R.string.mobile_settings_tunnel_route_unavailable
        is AccountSettingsFailure.Putio -> when (failure) {
            is PutioFailure.AuthenticationRequired -> R.string.mobile_state_error_session
            is PutioFailure.AccessDenied -> R.string.mobile_settings_error_access_denied
            is PutioFailure.RateLimited -> R.string.mobile_state_error_rate_limited
            is PutioFailure.ServerUnavailable -> R.string.mobile_state_error_unavailable
            is PutioFailure.NetworkUnavailable -> R.string.mobile_state_error_message
            is PutioFailure.ApiRejected,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Misconfigured,
            is PutioFailure.Unexpected,
            -> R.string.mobile_state_error_unavailable
        }
    }

/** What this account's downloads hold on the device, finished or not; null when it has none. */
internal fun DownloadsState.bytesOnDevice(): Long? =
    entries.takeIf { it.isNotEmpty() }?.sumOf { entry ->
        when (val status = entry.status) {
            is DownloadStatus.Completed -> status.bytes
            is DownloadStatus.Downloading -> status.bytesDownloaded
            is DownloadStatus.Paused -> status.bytesDownloaded
            is DownloadStatus.Failed -> status.bytesDownloaded
            DownloadStatus.Queued, DownloadStatus.Missing -> 0L
        }
    }
