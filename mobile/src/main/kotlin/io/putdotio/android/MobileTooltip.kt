package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun MobileTooltip(label: String, shortcut: String? = null, control: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    var byHover by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    val text = shortcut?.let { stringResource(R.string.mobile_tooltip_with_shortcut, label, it) }
    val longPressLabel = text?.let { stringResource(R.string.mobile_tooltip_show) }
    Box(
        Modifier
            .tooltipTrigger(
                onLongPress = {
                    byHover = false
                    shown = true
                },
                onHover = { inside ->
                    hovered = inside
                    if (inside && !shown) {
                        byHover = true
                        shown = true
                    }
                },
            )
            .then(
                if (longPressLabel == null) {
                    Modifier
                } else {
                    Modifier.semantics {
                        onLongClick(longPressLabel) {
                            byHover = false
                            shown = true
                            true
                        }
                    }
                },
            ),
    ) {
        control()
        if (shown) {
            MobileTooltipPopup(
                text = text ?: label,
                left = { byHover && !hovered },
                modifier = Modifier.matchParentSize(),
                onDismissed = { shown = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileTooltipPopup(text: String, left: () -> Boolean, modifier: Modifier, onDismissed: () -> Unit) {
    val state = rememberTooltipState()
    LaunchedEffect(state) {
        try {
            coroutineScope {
                val leave = launch {
                    snapshotFlow(left).first { it }
                    state.dismiss()
                }
                state.show(MutatePriority.UserInput)
                leave.cancel()
            }
        } finally {
            onDismissed()
        }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = state,
        modifier = modifier,
        enableUserInput = false,
    ) {
        Box(Modifier.fillMaxSize())
    }
}

private fun Modifier.tooltipTrigger(onLongPress: () -> Unit, onHover: (Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val pass = PointerEventPass.Initial
            val type = awaitFirstDown(pass = pass).type
            if (type == PointerType.Touch || type == PointerType.Stylus) {
                var ended = false
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(pass = pass)
                    ended = true
                }
                if (!ended) {
                    onLongPress()
                    do {
                        val event = awaitPointerEvent(pass = pass)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
    }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.firstOrNull()?.type == PointerType.Mouse) {
                    when (event.type) {
                        PointerEventType.Enter -> onHover(true)
                        PointerEventType.Exit -> onHover(false)
                    }
                }
            }
        }
    }

@Composable
internal fun MobileIconButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    shortcut: String? = null,
) {
    MobileTooltip(label, shortcut) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
            Icon(painterResource(icon), contentDescription = label)
        }
    }
}
