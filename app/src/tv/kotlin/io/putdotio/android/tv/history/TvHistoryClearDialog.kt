package io.putdotio.android.tv.history

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.files.tvMessage

/**
 * The clear confirmation per the TV contract: a centred 28dp dialog with stacked
 * full-width buttons. Focus lands on Cancel, since Clear is not undoable; Back cancels.
 * While the request runs the dialog has no action, and a failure explains itself.
 */
@Composable
internal fun TvHistoryClearDialog(
    clearing: HistoryClearing,
    onEvent: (HistoryEvent) -> Boolean,
) {
    when (clearing) {
        HistoryClearing.AwaitingConfirmation ->
            TvHistoryDialog(
                title = stringResource(R.string.tv_history_clear_title),
                message = stringResource(R.string.tv_history_clear_message),
                onDismiss = { onEvent(HistoryEvent.DismissClear) },
            ) { focus ->
                TvButton(onClick = { onEvent(HistoryEvent.ConfirmClear) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tv_history_clear_confirm))
                }
                TvButton(
                    onClick = { onEvent(HistoryEvent.DismissClear) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                ) {
                    Text(stringResource(R.string.tv_history_cancel))
                }
            }
        is HistoryClearing.Clearing ->
            TvHistoryDialog(title = stringResource(R.string.tv_history_clearing), message = null, onDismiss = {})
        is HistoryClearing.Failed ->
            TvHistoryDialog(
                title = stringResource(R.string.tv_history_clear_error_title),
                message = stringResource(clearing.failure.tvMessage()),
                onDismiss = { onEvent(HistoryEvent.DismissClear) },
            ) { focus ->
                TvButton(
                    onClick = { onEvent(HistoryEvent.DismissClear) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                ) {
                    Text(stringResource(R.string.tv_history_ok))
                }
            }
        HistoryClearing.Idle -> Unit
    }
}

@Composable
private fun TvHistoryDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    actions: (@Composable (focus: FocusRequester) -> Unit)? = null,
) {
    val actionFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        if (actions != null) {
            // Requested from inside the dialog window, one frame after its content attaches.
            LaunchedEffect(Unit) {
                withFrameNanos {}
                actionFocus.requestFocus()
            }
        }
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(24.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            actions?.invoke(actionFocus)
        }
    }
}
