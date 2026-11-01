package io.putdotio.android.tv.trash

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.trash.TrashAction
import io.putdotio.android.trash.TrashActionCheck
import io.putdotio.android.trash.TrashActionOutcome
import io.putdotio.android.trash.TrashActionSubmission
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreOutcome
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.android.trash.TrashState
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.ROW_BLEED
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.files.tvMessageText
import io.putdotio.android.tv.paneSection

/** The restore and mutation outcomes and a failed refresh, one line each above the list. */
@Composable
internal fun TvTrashNotices(
    state: TrashState,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    val enabled = state.authenticationFailure == null
    state.restoreOutcome?.let { TvTrashRestoreOutcome(it, enabled, onEvent, owner) }
    state.actionOutcome?.let { TvTrashActionOutcome(it, enabled, onEvent, owner) }
    (state.content as? TrashContent.Loaded)?.refreshFailure?.let { failure ->
        TvTrashNotice(
            text = stringResource(R.string.tv_trash_refresh_error, failure.tvMessageText()),
            action = stringResource(R.string.tv_files_retry),
            onAction = { onEvent(TrashEvent.Refresh) },
            owner = owner,
        )
    }
}

/** One line above the list with an optional action; the action is a focus section. */
@Composable
private fun TvTrashNotice(
    text: String,
    action: String?,
    onAction: () -> Unit,
    owner: TvPaneFocusOwner,
    modifier: Modifier = Modifier,
) {
    val actionFocus = remember { FocusRequester() }
    // A group, so Up from a row measures against the full-width line rather than the
    // right-aligned button, which the header would otherwise beat on distance.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ROW_BLEED, bottom = 12.dp)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Bounded so a long server-supplied name cannot push the action below the fold.
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TvButton(
                onClick = onAction,
                modifier = Modifier.paneSection(owner, actionFocus).focusRequester(actionFocus),
            ) {
                Text(action)
            }
        }
    }
}

@Composable
private fun TvTrashRestoreOutcome(
    outcome: TrashRestoreOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    val name = outcome.resolvedItem?.name ?: outcome.item.name
    val checkFailure = outcome.checkFailure
    val text = when {
        outcome.check == TrashRestoreCheck.AVAILABLE -> stringResource(R.string.tv_trash_restore_available, name)
        outcome.check == TrashRestoreCheck.CHECKING -> stringResource(R.string.tv_trash_checking)
        outcome.check == TrashRestoreCheck.UNAVAILABLE -> stringResource(R.string.tv_trash_restore_not_available, name)
        outcome.check == TrashRestoreCheck.FAILED && checkFailure != null -> checkFailure.tvMessageText()
        else -> when (outcome.submission) {
            TrashRestoreSubmission.SUBMITTING -> stringResource(R.string.tv_trash_submitting)
            TrashRestoreSubmission.ACKNOWLEDGED -> stringResource(R.string.tv_trash_restore_started, name)
            TrashRestoreSubmission.UNCERTAIN -> stringResource(R.string.tv_trash_restore_uncertain, name)
            TrashRestoreSubmission.REJECTED -> stringResource(R.string.tv_trash_restore_rejected, name) +
                (outcome.submissionFailure?.let { " " + it.tvMessageText() } ?: "")
        }
    }
    TvTrashOutcomeNotice(
        text = text,
        submitting = outcome.submission == TrashRestoreSubmission.SUBMITTING,
        pending = outcome.isPending,
        checking = outcome.check == TrashRestoreCheck.CHECKING,
        checkLabel = stringResource(R.string.tv_trash_check_status),
        enabled = enabled,
        onCheck = { onEvent(TrashEvent.CheckRestore) },
        onDismiss = { onEvent(TrashEvent.DismissRestoreOutcome) },
        owner = owner,
    )
}

@Composable
private fun TvTrashActionOutcome(
    outcome: TrashActionOutcome,
    enabled: Boolean,
    onEvent: (TrashEvent) -> Boolean,
    owner: TvPaneFocusOwner,
) {
    TvTrashOutcomeNotice(
        text = outcome.tvText(),
        submitting = outcome.submission == TrashActionSubmission.SUBMITTING,
        pending = outcome.isPending,
        checking = outcome.check == TrashActionCheck.CHECKING,
        checkLabel = stringResource(R.string.tv_trash_check_trash),
        enabled = enabled,
        onCheck = { onEvent(TrashEvent.CheckAction) },
        onDismiss = { onEvent(TrashEvent.DismissActionOutcome) },
        owner = owner,
    )
}

@Composable
private fun TrashActionOutcome.tvText(): String = when (check) {
    TrashActionCheck.CHECKING -> stringResource(R.string.tv_trash_checking)
    TrashActionCheck.VERIFIED -> action.verifiedText()
    TrashActionCheck.INCONCLUSIVE -> action.inconclusiveText()
    TrashActionCheck.FAILED -> checkFailure?.tvMessageText() ?: action.stillPresentText()
    TrashActionCheck.NOT_CHECKED -> submissionText()
}

@Composable
private fun TrashActionOutcome.submissionText(): String = when (submission) {
    TrashActionSubmission.SUBMITTING -> stringResource(R.string.tv_trash_submitting)
    TrashActionSubmission.ACKNOWLEDGED -> action.startedText()
    TrashActionSubmission.UNCERTAIN -> stringResource(R.string.tv_trash_action_uncertain)
    TrashActionSubmission.REJECTED -> stringResource(R.string.tv_trash_action_rejected) +
        (submissionFailure?.let { " " + it.tvMessageText() } ?: "")
}

@Composable
private fun TrashAction.startedText(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_started, item.name)
    TrashAction.RestoreAll -> stringResource(R.string.tv_trash_restore_all_started)
    TrashAction.Empty -> stringResource(R.string.tv_trash_empty_started)
}

@Composable
private fun TrashAction.verifiedText(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_verified, item.name)
    TrashAction.RestoreAll -> stringResource(R.string.tv_trash_restore_all_verified)
    TrashAction.Empty -> stringResource(R.string.tv_trash_empty_verified)
}

@Composable
private fun TrashAction.inconclusiveText(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_inconclusive, item.name)
    TrashAction.RestoreAll -> stringResource(R.string.tv_trash_restore_all_inconclusive)
    TrashAction.Empty -> stringResource(R.string.tv_trash_empty_inconclusive)
}

@Composable
private fun TrashAction.stillPresentText(): String = when (this) {
    is TrashAction.DeleteItem -> stringResource(R.string.tv_trash_delete_still_present, item.name)
    else -> stringResource(R.string.tv_trash_empty_still_present)
}

/**
 * An outcome keeps one button across its life: Check while unconfirmed, OK once settled,
 * none while the request is in flight, so the node holding focus survives each step.
 */
@Composable
private fun TvTrashOutcomeNotice(
    text: String,
    submitting: Boolean,
    pending: Boolean,
    checking: Boolean,
    checkLabel: String,
    enabled: Boolean,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    owner: TvPaneFocusOwner,
) {
    TvTrashNotice(
        text = text,
        action = when {
            submitting -> null
            pending -> checkLabel
            else -> stringResource(R.string.tv_trash_ok)
        },
        onAction = {
            when {
                submitting -> Unit
                pending -> if (enabled && !checking) onCheck()
                else -> onDismiss()
            }
        },
        owner = owner,
    )
}
