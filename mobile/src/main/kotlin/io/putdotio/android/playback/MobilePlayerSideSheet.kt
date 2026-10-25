package io.putdotio.android.playback

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale

internal const val MOBILE_PLAYER_SIDE_SHEET_TAG = "mobile-player-side-sheet"

/** The panels the video's option chips open from the right. */
internal enum class MobileVideoSheet { Audio, Subtitles, SubtitleAppearance, Speed }

internal val MOBILE_PLAYER_SIDE_SHEET_WIDTH = 340.dp
private const val SIDE_SHEET_SCRIM_ALPHA = 0.55f

/**
 * A 340dp side sheet from the right over the dimmed video. It lives in the player's own window, so
 * the system bars stay hidden; Back and a tap on the dimmed video close it.
 */
@Composable
internal fun MobilePlayerSideSheet(
    visible: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = visible) { onBack?.invoke() ?: onClose() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SIDE_SHEET_SCRIM_ALPHA))
                    .clearAndSetSemantics {}
                    .pointerInput(onClose) { detectTapGestures { onClose() } },
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { focus.requestFocus() }
            Column(
                panelModifier
                    .width(MOBILE_PLAYER_SIDE_SHEET_WIDTH.coerceAtMost(maxWidth))
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        RoundedCornerShape(topStart = PutioRadius.extraLarge, bottomStart = PutioRadius.extraLarge),
                    )
                    // A tap inside the panel never reaches the scrim behind it.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
                    )
                    .padding(top = 16.dp, bottom = 16.dp)
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .focusRequester(focus)
                    .focusGroup()
                    .testTag(MOBILE_PLAYER_SIDE_SHEET_TAG),
            ) {
                MobileSideSheetHeader(title, onClose, onBack)
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun MobileSideSheetHeader(title: String, onClose: () -> Unit, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) 24.dp else 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_ph_arrow_left),
                    contentDescription = stringResource(R.string.mobile_action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onBack == null) {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_ph_x),
                    contentDescription = stringResource(R.string.mobile_action_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A small section label inside a side sheet. */
@Composable
internal fun MobileSideSheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp).semantics { heading() },
    )
}

/** A radio row: the check marks the current choice, and its row takes a tonal step up. */
@Composable
internal fun MobileSideSheetOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_ph_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = PutioTypeScale.bodyMd)
            if (supporting != null) {
                ProvideTextStyle(
                    MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { supporting() }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f
