package io.putdotio.android.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.R

internal const val MOBILE_FILES_COPY_STATUS_TAG = "mobile-files-copy-status"
internal const val MOBILE_FILES_COPY_DISMISS_TAG = "mobile-files-copy-dismiss"

/** A copy runs on put.io after the picker closes, so its line stays under every folder until dismissed. */
@Composable
internal fun MobileFilesCopyStatus(outcome: FilesCopyOutcome, onDismiss: () -> Unit) {
    val destination = outcome.destination.name ?: stringResource(R.string.mobile_destination_files)
    val message = when (outcome.status) {
        FilesCopyStatus.STARTING, FilesCopyStatus.COPYING -> R.string.mobile_files_copying
        FilesCopyStatus.COPIED -> R.string.mobile_files_copied
        FilesCopyStatus.FAILED -> R.string.mobile_files_copy_failed
        FilesCopyStatus.UNCONFIRMED -> R.string.mobile_files_copy_unconfirmed
    }
    val reason = when {
        outcome.status != FilesCopyStatus.FAILED -> null
        outcome.serverMessage != null -> outcome.serverMessage
        else -> outcome.failure?.copyMessage()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .testTag(MOBILE_FILES_COPY_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (outcome.isRunning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Column(modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(message, outcome.itemName, destination), style = MaterialTheme.typography.bodyMedium)
            reason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!outcome.isRunning) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(MOBILE_FILES_COPY_DISMISS_TAG)) {
                Text(stringResource(R.string.mobile_action_ok))
            }
        }
    }
}

/** Web's wording for put.io's copy limits wins over put.io's reason, as other specific copy does. */
@Composable
private fun PutioFailure.copyMessage(): String = when ((this as? PutioFailure.ApiRejected)?.errorType) {
    "SharedFileCloneConcurrentLimit" -> stringResource(R.string.mobile_files_copy_concurrent_limit)
    "SharedFileCloneTooManyFiles", "SharedFileCloneTooManyChildren" ->
        stringResource(R.string.mobile_files_copy_too_many_files)
    else -> mobileMessage()
}
