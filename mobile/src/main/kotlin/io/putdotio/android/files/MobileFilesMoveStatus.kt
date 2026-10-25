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

internal const val MOBILE_FILES_MOVE_OUTCOME_TAG = "mobile-files-move-outcome"

@Composable
internal fun MobileFilesMoveStatus(outcome: FilesMoveOutcome) {
    val message = outcome.statusMessage()
    if (message != null) {
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

/** The settled move's line: the item by name, or several by count; a lone name clash names it. */
@Composable
private fun FilesMoveOutcome.statusMessage(): String? {
    val collision = errors?.singleOrNull()?.takeIf { error ->
        intent.itemIds.any { it.value == error.id } && error.errorType == "NAME_ALREADY_EXIST"
    }
    val many = itemCount > 1
    return when (status) {
        FilesMoveStatus.MOVED -> if (many) {
            pluralStringResource(R.plurals.mobile_files_move_moved_many, itemCount, itemCount)
        } else {
            stringResource(R.string.mobile_files_move_moved, itemName)
        }
        FilesMoveStatus.STILL_PRESENT -> if (many) {
            pluralStringResource(R.plurals.mobile_files_move_still_present_many, remainingCount, remainingCount)
        } else {
            stringResource(R.string.mobile_files_move_still_present, itemName)
        }
        FilesMoveStatus.REJECTED -> when {
            collision != null -> stringResource(R.string.mobile_files_move_collision, collision.name ?: itemName)
            many -> pluralStringResource(R.plurals.mobile_files_move_rejected_many, itemCount, itemCount)
            else -> stringResource(R.string.mobile_files_move_rejected, itemName)
        }
        FilesMoveStatus.CHECKING, FilesMoveStatus.UNKNOWN -> null
    }
}

// put.io answers a move with a bare 403 only when the destination is the item or a folder inside
// it; the app never sends the negative IDs that also get one.
@Composable
private fun PutioFailure.moveFailureMessage(): String =
    if (this is PutioFailure.AccessDenied) stringResource(R.string.mobile_files_move_into_itself) else mobileMessage()
