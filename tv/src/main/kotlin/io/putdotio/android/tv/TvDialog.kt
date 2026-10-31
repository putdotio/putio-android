package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius

/**
 * A centred dialog per DESIGN.md › TV components: a title, an optional message, an optional
 * non-focusable body (such as a progress bar), and stacked full-width actions. The action given
 * the requester takes focus once the window is up; Back is the caller's dismissal. New dialogs
 * use [TvConfirmDialog] or [TvNoticeDialog].
 */
@Composable
internal fun TvDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    body: (@Composable () -> Unit)? = null,
    actions: (@Composable (focus: FocusRequester) -> Unit)? = null,
) {
    TvDialogFrame(title, message, onDismiss, focusesAction = actions != null) { focus ->
        body?.invoke()
        if (actions != null) {
            Column(
                modifier = Modifier.padding(top = ACTIONS_GAP),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { actions(focus) }
        }
    }
}

@Composable
internal fun TvConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    TvDialogFrame(title, message, onDismiss, focusesAction = true) { focus ->
        TvDialogActions {
            TvButton(
                onClick = onDismiss,
                style = TvButtonStyle.Text,
            ) { Text(stringResource(R.string.tv_dialog_cancel)) }
            TvButton(
                onClick = onConfirm,
                style = if (destructive) TvButtonStyle.Destructive else TvButtonStyle.Filled,
                modifier = Modifier.focusRequester(focus),
            ) { Text(confirmLabel) }
        }
    }
}

@Composable
internal fun TvNoticeDialog(
    title: String,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    TvDialogFrame(title, message, onDismiss, focusesAction = true) { focus ->
        TvDialogActions {
            TvButton(
                onClick = onDismiss,
                style = TvButtonStyle.Text,
                modifier = Modifier.focusRequester(focus),
            ) { Text(stringResource(R.string.tv_dialog_ok)) }
        }
    }
}

@Composable
private fun TvDialogActions(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ACTIONS_GAP),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun TvDialogFrame(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    focusesAction: Boolean,
    content: @Composable ColumnScope.(focus: FocusRequester) -> Unit,
) {
    val actionFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        if (focusesAction) {
            LaunchedEffect(Unit) {
                withFrameNanos {}
                actionFocus.requestFocus()
            }
        }
        Column(
            modifier = Modifier
                .width(DIALOG_WIDTH)
                .background(PutioDesignTokens.componentBgHover, RoundedCornerShape(PutioRadius.extraLarge))
                .padding(24.dp)
                .focusGroup(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (message != null) {
                // Bounded so a long server-supplied name cannot push the actions off screen.
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            content(actionFocus)
        }
    }
}

private val DIALOG_WIDTH = 380.dp
private val ACTIONS_GAP = 20.dp
