package io.putdotio.android.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import io.putdotio.android.MobileErrorState
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.RetryWhenOnline
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.isUnreachable
import io.putdotio.android.neutralTextButtonColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val MOBILE_LOGGED_IN_APPS_ROUTE = "account/logged-in-apps"
internal const val MOBILE_LOGGED_IN_APPS_ROW_TAG = "mobile-logged-in-apps-row"
internal const val MOBILE_LOGGED_IN_APPS_LIST_TAG = "mobile-logged-in-apps-list"
internal const val MOBILE_SIGN_OUT_OTHERS_TAG = "mobile-sign-out-others"
internal const val MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG = "mobile-sign-out-others-confirm"
internal const val MOBILE_LOGGED_IN_APPS_OUTCOME_TAG = "mobile-logged-in-apps-outcome"

internal fun mobileLoggedInAppRevokeTag(id: LoggedInAppId): String = "mobile-logged-in-app-revoke-${id.value}"

@Composable
internal fun MobileLoggedInAppsScreen(
    state: LoggedInAppsState,
    onEvent: (LoggedInAppsEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    when (val content = state.content) {
        LoggedInAppsContent.Loading -> MobileLoadingState(
            message = stringResource(R.string.mobile_logged_in_apps_loading),
            modifier = modifier,
        )
        is LoggedInAppsContent.Failed -> {
            if (content.failure.isUnreachable) RetryWhenOnline(content.failure) { onEvent(LoggedInAppsEvent.Load) }
            MobileErrorState(
                title = stringResource(R.string.mobile_logged_in_apps_load_error),
                message = content.failure.mobileMessage(),
                retryLabel = stringResource(R.string.mobile_action_retry),
                onRetry = { onEvent(LoggedInAppsEvent.Load) },
                retryEnabled = state.authenticationFailure == null,
                modifier = modifier,
            )
        }
        is LoggedInAppsContent.Ready -> LoggedInAppsList(
            apps = content.apps,
            state = state,
            onEvent = onEvent,
            onSignOutOthers = { confirming = true },
            modifier = modifier,
        )
    }
    if (confirming && state.otherApps.isNotEmpty()) {
        MobileTurnOffDialog(
            title = R.string.mobile_logged_in_apps_sign_out_others_title,
            message = R.string.mobile_logged_in_apps_sign_out_others_message,
            confirm = R.string.mobile_account_sign_out,
            confirmTag = MOBILE_SIGN_OUT_OTHERS_CONFIRM_TAG,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                onEvent(LoggedInAppsEvent.SignOutOthers)
            },
        )
    }
}

@Composable
private fun LoggedInAppsList(
    apps: List<LoggedInApp>,
    state: LoggedInAppsState,
    onEvent: (LoggedInAppsEvent) -> Boolean,
    onSignOutOthers: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(MOBILE_LOGGED_IN_APPS_LIST_TAG),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 24.dp,
                end = (maxWidth - LOGGED_IN_APPS_MAX_WIDTH).coerceAtLeast(0.dp),
            ),
        ) {
            state.outcome?.let { outcome ->
                item(key = "outcome") {
                    LoggedInAppsOutcomeLine(outcome) { onEvent(LoggedInAppsEvent.DismissOutcome) }
                }
            }
            itemsIndexed(apps, key = { _, app -> app.id.value }) { index, app ->
                Box(Modifier.padding(horizontal = 16.dp).padding(top = if (index == 0) 0.dp else 2.dp)) {
                    LoggedInAppRow(app, settingsSegment(index, apps.size), state, onEvent)
                }
            }
            if (state.otherApps.isNotEmpty()) {
                item(key = "sign-out-others") {
                    SignOutOthersButton(
                        enabled = state.canSignOutOthers,
                        working = state.work is LoggedInAppsWork.SigningOutOthers,
                        onClick = onSignOutOthers,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoggedInAppRow(
    app: LoggedInApp,
    segment: SettingsSegment,
    state: LoggedInAppsState,
    onEvent: (LoggedInAppsEvent) -> Boolean,
) {
    val revoking = when (val work = state.work) {
        is LoggedInAppsWork.Revoking -> work.app.id == app.id
        is LoggedInAppsWork.SigningOutOthers -> work.app.id == app.id
        null -> false
    }
    MobileSettingsSegment(segment = segment) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoggedInAppIcon(app)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.name, style = PutioTypeScale.bodyMd, color = MaterialTheme.colorScheme.onSurface)
                app.meta()?.let { meta ->
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                app.thisDevice -> Unit
                revoking -> CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
                    strokeWidth = 2.dp,
                )
                else -> TextButton(
                    onClick = { onEvent(LoggedInAppsEvent.Revoke(app.id)) },
                    enabled = state.canRevoke,
                    colors = neutralTextButtonColors(),
                    modifier = Modifier.testTag(mobileLoggedInAppRevokeTag(app.id)),
                ) { Text(stringResource(R.string.mobile_logged_in_apps_revoke)) }
            }
        }
    }
}

@Composable
private fun LoggedInApp.meta(): String? =
    when {
        thisDevice -> stringResource(R.string.mobile_logged_in_apps_this_device)
        else -> lastUsedAt?.let {
            stringResource(
                R.string.mobile_logged_in_apps_used_on,
                USED_ON_FORMAT.format(it.atZone(ZoneId.systemDefault())),
            )
        }
    }

@Composable
private fun LoggedInAppIcon(app: LoggedInApp) {
    val tile = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(PutioRadius.small))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    val url = app.icon?.value
    if (url == null) {
        GenericAppIcon(tile)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = tile,
        contentScale = ContentScale.Crop,
        loading = { GenericAppIcon(Modifier.fillMaxSize()) },
        error = { GenericAppIcon(Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun GenericAppIcon(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_app_window),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SignOutOthersButton(enabled: Boolean, working: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(PutioRadius.full))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(PutioRadius.full))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(MOBILE_SIGN_OUT_OTHERS_TAG)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.mobile_logged_in_apps_sign_out_others),
            style = PutioTypeScale.bodyMd,
            color = if (enabled) {
                PutioDesignTokens.redTextSecondary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LoggedInAppsOutcomeLine(outcome: LoggedInAppsOutcome, onDismiss: () -> Unit) {
    val failed = outcome !is LoggedInAppsOutcome.SignedOutOthers
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, bottom = 8.dp)
            .testTag(MOBILE_LOGGED_IN_APPS_OUTCOME_TAG),
    ) {
        Text(
            text = outcome.message(),
            color = if (failed) PutioDesignTokens.redTextSecondary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onDismiss, colors = neutralTextButtonColors()) {
            Text(stringResource(R.string.mobile_action_ok))
        }
    }
}

@Composable
private fun LoggedInAppsOutcome.message(): String =
    when (this) {
        LoggedInAppsOutcome.SignedOutOthers -> stringResource(R.string.mobile_logged_in_apps_signed_out_others)
        is LoggedInAppsOutcome.RevokeFailed ->
            stringResource(R.string.mobile_logged_in_apps_revoke_failed, app.name, failure.message())
        is LoggedInAppsOutcome.SignOutOthersFailed ->
            stringResource(R.string.mobile_logged_in_apps_sign_out_others_failed, app.name, failure.message())
    }

@Composable
private fun PutioFailure.message(): String = mobileMessage()

private val USED_ON_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val LOGGED_IN_APPS_MAX_WIDTH = 640.dp

@Composable
internal fun MobileLoggedInAppsRoute(
    repository: LoggedInAppsRepository?,
    onAuthenticationRequired: suspend () -> Unit,
) {
    if (repository == null) return
    val model: MobileLoggedInAppsViewModel = viewModel(factory = mobileLoggedInAppsViewModelFactory(repository))
    val state by model.controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) {
        if (model.controller.state.value.content == LoggedInAppsContent.Loading) {
            model.controller.dispatch(LoggedInAppsEvent.Load)
        }
    }
    val authenticationFailure = state.authenticationFailure
    LaunchedEffect(authenticationFailure) {
        if (authenticationFailure != null) onAuthenticationRequired()
    }
    MobileLoggedInAppsScreen(state, model.controller::dispatch)
}

internal class MobileLoggedInAppsViewModel(repository: LoggedInAppsRepository) : ViewModel() {
    val controller: LoggedInAppsController = LoggedInAppsController(repository, viewModelScope)

    override fun onCleared() {
        controller.close()
    }
}

private fun mobileLoggedInAppsViewModelFactory(repository: LoggedInAppsRepository): ViewModelProvider.Factory =
    viewModelFactory { initializer { MobileLoggedInAppsViewModel(repository) } }
