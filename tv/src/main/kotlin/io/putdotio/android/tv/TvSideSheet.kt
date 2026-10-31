package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius

@Composable
internal fun TvSideSheet(
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.(initialFocus: FocusRequester) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    val opener = rememberUpdatedState(returnFocus)
    DisposableEffect(Unit) {
        onDispose { opener.value?.returnFocusFromSheet() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LaunchedEffect(Unit) {
            withFrameNanos {}
            initialFocus.requestFocus()
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
        ) {
            val margins = with(LocalDensity.current) { tvSafeAreaMargins(constraints) }
            Column(
                modifier = modifier
                    .align(Alignment.CenterEnd)
                    .width(SHEET_WIDTH)
                    .fillMaxHeight()
                    .background(
                        PutioDesignTokens.componentBgHover,
                        RoundedCornerShape(topStart = PutioRadius.extraLarge, bottomStart = PutioRadius.extraLarge),
                    )
                    .padding(start = 16.dp, top = 28.dp, end = margins.side, bottom = margins.vertical)
                    .focusGroup(),
            ) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    )
                }
                content(initialFocus)
            }
        }
    }
}

private fun FocusRequester.returnFocusFromSheet() {
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        Unit
    }
}

private val SHEET_WIDTH = 360.dp
private const val SCRIM_ALPHA = 0.72f
