package io.putdotio.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus

internal const val MOBILE_FILES_MOVE_OUTCOME_TAG = "mobile-files-move-outcome"

@Composable
internal fun MobileFilesMoveStatus(outcome: FilesMoveOutcome) {
    val collision = outcome.errors?.singleOrNull()?.takeIf {
        it.id == outcome.intent.itemId.value && it.errorType == "NAME_ALREADY_EXIST"
    }
    val message = when (outcome.status) {
        FilesMoveStatus.MOVED -> R.string.mobile_files_move_moved
        FilesMoveStatus.STILL_PRESENT -> R.string.mobile_files_move_still_present
        FilesMoveStatus.REJECTED -> if (collision == null) R.string.mobile_files_move_rejected
            else R.string.mobile_files_move_collision
        FilesMoveStatus.CHECKING, FilesMoveStatus.UNKNOWN -> null
    }
    if (message != null) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(MOBILE_FILES_MOVE_OUTCOME_TAG).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(message, collision?.name ?: outcome.itemName), style = MaterialTheme.typography.bodyMedium)
            if (outcome.status != FilesMoveStatus.MOVED) {
                outcome.failure?.let {
                    Text(stringResource(it.mobileMessageResource()), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
