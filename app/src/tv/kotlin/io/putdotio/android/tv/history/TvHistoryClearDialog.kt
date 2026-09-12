package io.putdotio.android.tv.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog
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
            TvDialog(
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
            TvDialog(title = stringResource(R.string.tv_history_clearing), message = null, onDismiss = {})
        is HistoryClearing.Failed ->
            TvDialog(
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
