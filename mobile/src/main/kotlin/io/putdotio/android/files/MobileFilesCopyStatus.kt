package io.putdotio.android.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius

internal const val MOBILE_FILES_COPY_STATUS_TAG = "mobile-files-copy-status"
internal const val MOBILE_FILES_COPY_DISMISS_TAG = "mobile-files-copy-dismiss"

/**
 * A copy runs on put.io after the picker closes, so its card stays under the bar of every folder:
 * a small yellow spinner while it runs, a red dot and the reason when it fails, and a close button
 * once it has settled. A running copy keeps its card, so a second copy never starts unseen.
 */
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(PutioRadius.large))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = if (outcome.isRunning) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp)
            .testTag(MOBILE_FILES_COPY_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            outcome.isRunning -> CircularProgressIndicator(
                modifier = Modifier.size(MARK_SIZE).clearAndSetSemantics {},
                strokeWidth = 2.dp,
                color = PutioDesignTokens.yellowSolid,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            outcome.status == FilesCopyStatus.FAILED -> Box(
                Modifier.size(MARK_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(DOT_SIZE).clip(CircleShape).background(MaterialTheme.colorScheme.error))
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                stringResource(message, outcome.itemName, destination),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            reason?.let {
                Text(it, color = PutioDesignTokens.redTextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!outcome.isRunning) {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag(MOBILE_FILES_COPY_DISMISS_TAG)) {
                Icon(
                    painterResource(R.drawable.ic_ph_x),
                    contentDescription = stringResource(R.string.mobile_action_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
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

private val MARK_SIZE = 16.dp
private val DOT_SIZE = 8.dp
