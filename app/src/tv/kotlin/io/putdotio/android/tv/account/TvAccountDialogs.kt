package io.putdotio.android.tv.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvChoice
import io.putdotio.android.tv.TvChoiceDialog
import io.putdotio.android.tv.TvDialog

/**
 * The proxy picker per oracle 10. Routes load when it opens: the list depends on the
 * account's CDN eligibility and the caller's address, so it is not worth caching.
 */
@Composable
internal fun TvTunnelRouteDialog(
    selected: TunnelRouteName,
    loadRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onSelect: (TunnelRouteName) -> Unit,
    onDismiss: () -> Unit,
) {
    var attempt by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<AccountSettingsRepositoryResult<List<TunnelRouteOption>>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = loadRoutes()
    }
    val title = stringResource(R.string.tv_account_tunnel_route)
    when (val loaded = result) {
        null -> TvDialog(
            title = title,
            message = stringResource(R.string.tv_account_tunnel_route_loading),
            onDismiss = onDismiss,
        )
        is AccountSettingsRepositoryResult.Failure -> TvDialog(
            title = stringResource(R.string.tv_account_tunnel_route_error),
            message = stringResource(loaded.failure.tvMessage()),
            onDismiss = onDismiss,
        ) { focus ->
            // A session verdict is final; every other failure is worth one more try.
            val retryable = loaded.failure !is AccountSettingsFailure.AuthenticationRequired
            if (retryable) {
                TvButton(
                    onClick = { attempt += 1 },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                ) { Text(stringResource(R.string.tv_account_retry)) }
            }
            TvButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().then(if (retryable) Modifier else Modifier.focusRequester(focus)),
            ) { Text(stringResource(R.string.tv_account_cancel)) }
        }
        is AccountSettingsRepositoryResult.Success -> TvChoiceDialog(
            title = title,
            choices = loaded.value.map { TvChoice(it.name, it.tvLabel()) },
            selected = selected,
            onSelect = onSelect,
            onDismiss = onDismiss,
        )
    }
}

/** The server describes each route; the direct one is named for where it lands. */
@Composable
internal fun TunnelRouteOption.tvLabel(): String =
    if (name == TunnelRouteName.DEFAULT) {
        stringResource(R.string.tv_account_tunnel_route_default)
    } else {
        description.takeIf { it.isNotEmpty() } ?: name.value
    }

@Composable
internal fun TunnelRouteName.tvLabel(): String =
    if (this == TunnelRouteName.DEFAULT) stringResource(R.string.tv_account_tunnel_route_default) else value

/** Turning Trash off makes deletions final; it confirms first, with Cancel focused. */
@Composable
internal fun TvTrashDisableDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = stringResource(R.string.tv_account_trash_disable_title),
        message = stringResource(R.string.tv_account_trash_disable_message),
        onDismiss = onDismiss,
    ) { focus ->
        TvButton(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tv_account_trash_disable_confirm))
        }
        TvButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
            Text(stringResource(R.string.tv_account_cancel))
        }
    }
}

/** Support-safe facts about this install, the same set mobile's App info shows. */
@Composable
internal fun TvDiagnosticsDialog(
    diagnostics: AppDiagnostics,
    onDismiss: () -> Unit,
) {
    val channel = when (diagnostics.releaseChannel) {
        AppDiagnostics.ReleaseChannel.Stable -> R.string.tv_account_diagnostics_channel_stable
        AppDiagnostics.ReleaseChannel.Internal -> R.string.tv_account_diagnostics_channel_internal
    }
    val rows = listOf(
        stringResource(R.string.tv_account_diagnostics_version, diagnostics.appVersion, diagnostics.versionCode),
        stringResource(channel, diagnostics.buildType),
        stringResource(R.string.tv_account_diagnostics_api, diagnostics.runtimeVersion),
        stringResource(R.string.tv_account_diagnostics_device),
        stringResource(R.string.tv_account_diagnostics_player, diagnostics.player),
    )
    TvDialog(
        title = stringResource(R.string.tv_account_diagnostics),
        message = null,
        onDismiss = onDismiss,
    ) { focus ->
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            rows.forEach { row ->
                Text(
                    text = row,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
        TvButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(focus)) {
            Text(stringResource(R.string.tv_account_ok))
        }
    }
}
