package io.putdotio.android.tv.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvQrCode
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.focusRequesterIf

@Composable
internal fun <T> TvChoiceSheet(
    title: String,
    choices: List<TvSheetChoice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = title,
        bottomPadding = SHEET_BOTTOM,
    ) { initialFocus ->
        TvChoiceSheetBody(choices, selected, onSelect, onDismiss, initialFocus)
    }
}

@Composable
private fun <T> ColumnScope.TvChoiceSheetBody(
    choices: List<TvSheetChoice<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
) {
    val focusIndex = choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { initialFocus.requestFocus() }
    }
    TvSheetRows {
        choices.forEachIndexed { index, choice ->
            TvSheetOption(
                label = choice.label,
                detail = choice.description?.let(::AnnotatedString),
                selected = choice.value == selected,
                onClick = { if (choice.value == selected) onDismiss() else onSelect(choice.value) },
                modifier = Modifier.focusRequesterIf(index == focusIndex, initialFocus),
            )
        }
    }
}

@Composable
private fun TvSheetActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
internal fun TvTunnelRouteSheet(
    selected: TunnelRouteName,
    loadRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onSelect: (TunnelRouteName) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<AccountSettingsRepositoryResult<List<TunnelRouteOption>>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = loadRoutes()
    }
    val direct = stringResource(R.string.tv_account_tunnel_route_default_description)
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = stringResource(R.string.tv_account_tunnel_route),
        bottomPadding = SHEET_BOTTOM,
    ) { initialFocus ->
        val loaded = result
        LaunchedEffect(loaded) {
            if (loaded is AccountSettingsRepositoryResult.Success) return@LaunchedEffect
            withFrameNanos {}
            runCatching { initialFocus.requestFocus() }
        }
        when (loaded) {
            is AccountSettingsRepositoryResult.Success -> {
                val choices = loaded.value.map { route ->
                    val label = route.name.tvLabel()
                    TvSheetChoice(
                        value = route.name,
                        label = label,
                        description = if (route.name == TunnelRouteName.DEFAULT) {
                            direct
                        } else {
                            route.description.takeIf { it.isNotEmpty() && it != label }
                        },
                    )
                }
                TvChoiceSheetBody(choices, selected, onSelect, onDismiss, initialFocus)
            }
            is AccountSettingsRepositoryResult.Failure -> {
                TvSheetMessage(stringResource(R.string.tv_account_tunnel_route_error))
                TvSheetMessage(loaded.failure.tvMessageText())
                Spacer(Modifier.weight(1f))
                val retryable = loaded.failure.putioFailure !is PutioFailure.AuthenticationRequired
                TvSheetActions {
                    TvButton(
                        onClick = onDismiss,
                        style = TvButtonStyle.Text,
                        modifier = Modifier.focusRequesterIf(!retryable, initialFocus),
                    ) { Text(stringResource(R.string.tv_account_cancel)) }
                    if (retryable) {
                        TvButton(
                            onClick = { attempt += 1 },
                            style = TvButtonStyle.Filled,
                            modifier = Modifier.focusRequester(initialFocus),
                        ) { Text(stringResource(R.string.tv_account_retry)) }
                    }
                }
            }
            null -> {
                TvSheetMessage(stringResource(R.string.tv_account_tunnel_route_loading))
                Spacer(Modifier.weight(1f))
                TvSheetActions {
                    TvButton(
                        onClick = onDismiss,
                        style = TvButtonStyle.Text,
                        modifier = Modifier.focusRequester(initialFocus),
                    ) { Text(stringResource(R.string.tv_account_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun TvSheetMessage(text: String) {
    Text(
        text = text,
        style = PutioTypeScale.tvBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
internal fun TvPlaybackMethodSheet(
    selected: StreamingMethod,
    onSelect: (StreamingMethod) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvChoiceSheet(
        title = stringResource(R.string.tv_account_playback_method),
        choices = StreamingMethod.entries.map {
            TvSheetChoice(it, stringResource(it.tvLabel()), stringResource(it.tvDescription()))
        },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
        returnFocus = returnFocus,
    )
}

@Composable
internal fun TvQrSheet(
    title: String,
    url: String,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = title,
        bottomPadding = SHEET_BOTTOM,
    ) { initialFocus ->
        val light = MaterialTheme.colorScheme.inverseSurface
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvQrCode(
                text = url,
                description = stringResource(R.string.tv_account_qr_description, title),
                dark = MaterialTheme.colorScheme.background,
                light = light,
                modifier = Modifier
                    .size(QR_SHEET_SIZE)
                    .clip(RoundedCornerShape(PutioRadius.large))
                    .background(light)
                    .padding(QR_SHEET_PADDING),
            )
            Text(url, style = PutioTypeScale.tvMono, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(R.string.tv_account_qr_caption),
                style = TvAccountType.footnote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        TvSheetActions {
            TvButton(
                onClick = onDismiss,
                style = TvButtonStyle.Text,
                modifier = Modifier.focusRequester(initialFocus),
            ) { Text(stringResource(R.string.tv_account_done)) }
        }
    }
}

@Composable
internal fun TunnelRouteName.tvLabel(): String =
    if (this == TunnelRouteName.DEFAULT) stringResource(R.string.tv_account_tunnel_route_default) else value

private val SHEET_BOTTOM = 27.dp
private val QR_SHEET_SIZE = 170.dp
private val QR_SHEET_PADDING = 11.dp
