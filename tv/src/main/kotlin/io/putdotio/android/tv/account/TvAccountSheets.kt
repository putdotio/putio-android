package io.putdotio.android.tv.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvButtonStyle
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvQrCode
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.files.tvLabel
import io.putdotio.android.tv.focusRequesterIf

internal data class TvSheetChoice<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

@Composable
internal fun <T> TvChoiceSheet(
    title: String,
    choices: List<TvSheetChoice<T>>,
    selected: T?,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvSideSheet(onDismiss = onDismiss, returnFocus = returnFocus, title = title) { initialFocus ->
        TvChoiceSheetBody(choices, selected, onSave, onDismiss, initialFocus)
    }
}

@Composable
private fun <T> ColumnScope.TvChoiceSheetBody(
    choices: List<TvSheetChoice<T>>,
    selected: T?,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
) {
    var pending by remember(selected) { mutableStateOf(selected) }
    val focusIndex = choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
    ) {
        choices.forEachIndexed { index, choice ->
            TvSheetRadioOption(
                choice = choice,
                checked = choice.value == pending,
                onClick = { pending = choice.value },
                modifier = Modifier.focusRequesterIf(index == focusIndex, initialFocus),
            )
        }
    }
    TvSheetActions {
        TvButton(onClick = onDismiss, style = TvButtonStyle.Text) {
            Text(stringResource(R.string.tv_account_cancel))
        }
        TvButton(onClick = { pending?.let(onSave) }, style = TvButtonStyle.Filled, enabled = pending != null) {
            Text(stringResource(R.string.tv_account_save))
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
private fun <T> TvSheetRadioOption(
    choice: TvSheetChoice<T>,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .semantics {
                role = Role.RadioButton
                selected = checked
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (checked) PutioDesignTokens.componentBgActive else Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier.heightIn(min = ROW_HEIGHT).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvRadio(checked = checked, focused = focused, modifier = Modifier.padding(horizontal = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    choice.label,
                    style = TvAccountType.option,
                    color = if (focused) TvFocus.content else colors.onSurface,
                )
                if (choice.description != null) {
                    Text(
                        choice.description,
                        style = TvAccountType.optionDetail,
                        color = if (focused) TvAccountColors.focusSecondary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvRadio(checked: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val ring = when {
        focused && checked -> TvFocus.content
        focused -> TvAccountColors.focusSecondary
        checked -> PutioDesignTokens.yellowSolid
        else -> PutioDesignTokens.solid
    }
    val gap = if (focused) TvFocus.container else PutioDesignTokens.componentBgHover
    val dot = if (focused) TvFocus.content else PutioDesignTokens.yellowSolid
    Canvas(modifier.size(RADIO_SIZE)) {
        val border = RADIO_BORDER.toPx()
        val radius = size.minDimension / 2
        if (checked) {
            drawCircle(gap, radius - border / 2)
            drawCircle(dot, radius - border - RADIO_GAP.toPx())
        }
        drawCircle(ring, radius - border / 2, style = Stroke(border))
    }
}

@Composable
internal fun TvTunnelRouteSheet(
    selected: TunnelRouteName,
    loadRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onSave: (TunnelRouteName) -> Unit,
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
    ) { initialFocus ->
        val loaded = result
        LaunchedEffect(loaded == null) {
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
                TvChoiceSheetBody(choices, selected, onSave, onDismiss, initialFocus)
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
internal fun TvVideoPlaybackSheet(
    selected: VideoPlaybackType,
    onSave: (VideoPlaybackType) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvChoiceSheet(
        title = stringResource(R.string.tv_account_video_playback_type),
        choices = VideoPlaybackType.entries.map {
            TvSheetChoice(it, stringResource(it.tvLabel()), stringResource(it.tvDescription()))
        },
        selected = selected,
        onSave = onSave,
        onDismiss = onDismiss,
        returnFocus = returnFocus,
    )
}

@Composable
internal fun TvDefaultSortSheet(
    selected: FilesSort?,
    onSave: (FilesSort) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvChoiceSheet(
        title = stringResource(R.string.tv_account_default_sort),
        choices = FilesSort.entries.map { TvSheetChoice(it, stringResource(it.tvLabel())) },
        selected = selected,
        onSave = onSave,
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
    TvSideSheet(onDismiss = onDismiss, returnFocus = returnFocus, title = title) { initialFocus ->
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

private val RADIO_SIZE = 16.dp
private val RADIO_BORDER = 1.5.dp
private val RADIO_GAP = 3.dp
private val QR_SHEET_SIZE = 170.dp
private val QR_SHEET_PADDING = 11.dp
