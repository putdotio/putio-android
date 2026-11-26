package io.putdotio.android.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.sdk.files.FileMoveError

internal const val MOBILE_FILES_MOVE_OUTCOME_TAG = "mobile-files-move-outcome"

@Composable
internal fun MobileFilesMoveStatus(outcome: FilesMoveOutcome, onRetry: (() -> Unit)? = null) {
    val message = outcome.statusMessage(retryable = onRetry != null)
    if (message != null && onRetry != null) {
        MobileFilesRetryStatus(message, outcome.failure?.moveFailureMessage(), onRetry,
            Modifier.testTag(MOBILE_FILES_MOVE_OUTCOME_TAG))
    } else if (message != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(MOBILE_FILES_MOVE_OUTCOME_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (outcome.status != FilesMoveStatus.MOVED) {
                outcome.failure?.let {
                    Text(it.moveFailureMessage(), color = PutioDesignTokens.redTextSecondary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
internal fun FilesMoveOutcome.movedMessage(): String =
    if (itemCount > 1) {
        pluralStringResource(R.plurals.mobile_files_move_moved_many, itemCount, itemCount)
    } else {
        stringResource(R.string.mobile_files_move_moved, itemName)
    }

@Composable
private fun FilesMoveOutcome.statusMessage(retryable: Boolean): String? {
    val collision = errors?.singleOrNull()?.takeIf { error ->
        intent.itemIds.any { it.value == error.id } && error.errorType == "NAME_ALREADY_EXIST"
    }
    val many = itemCount > 1
    return when (status) {
        FilesMoveStatus.STILL_PRESENT -> if (retryable) {
            pluralStringResource(R.plurals.mobile_files_move_still_present_retry_many, remainingCount, remainingCount)
        } else if (many) {
            pluralStringResource(R.plurals.mobile_files_move_still_present_many, remainingCount, remainingCount)
        } else {
            stringResource(R.string.mobile_files_move_still_present, itemName)
        }
        FilesMoveStatus.REJECTED -> rejectedMessage(collision, many)
        FilesMoveStatus.MOVED, FilesMoveStatus.CHECKING, FilesMoveStatus.UNKNOWN -> null
    }
}

@Composable
private fun FilesMoveOutcome.rejectedMessage(collision: FileMoveError?, many: Boolean): String {
    val clashName = collision?.name ?: itemName.takeUnless { many }
    return when {
        collision != null && clashName != null && (!many || refusedCount == 1) ->
            stringResource(R.string.mobile_files_move_collision, clashName)
        many -> pluralStringResource(R.plurals.mobile_files_move_rejected_many, refusedCount, refusedCount)
        else -> stringResource(R.string.mobile_files_move_rejected, itemName)
    }
}

private val FilesMoveOutcome.refusedCount: Int
    get() {
        val refused = intent.itemIds.filter { id -> errors.orEmpty().any { it.id == id.value } }.toSet()
        return (refused + remainingIds).size.takeIf { it > 0 } ?: itemCount
    }

@Composable
private fun PutioFailure.moveFailureMessage(): String =
    if (this is PutioFailure.AccessDenied) stringResource(R.string.mobile_files_move_into_itself) else mobileMessage()
