package io.putdotio.android.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.AuthoritativeSessionFailureEffect
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesMoveDestinationController
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.MobileFilesMoveDestination
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.sdk.files.PutioFileType

internal const val MOBILE_TRANSFER_DESTINATION_TAG = "mobile-transfer-destination"
internal const val MOBILE_TRANSFER_CHANGE_DESTINATION_TAG = "mobile-transfer-change-destination"
internal const val MOBILE_TRANSFER_TORRENT_TAG = "mobile-transfer-torrent"

@Composable
internal fun MobileTransferDestinationRow(
    destination: FilesFolder?,
    enabled: Boolean,
    onChoose: (() -> Unit)?,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_ph_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).size(24.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .testTag(MOBILE_TRANSFER_DESTINATION_TAG),
        ) {
            Text(
                stringResource(R.string.mobile_transfers_save_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    destination == null -> stringResource(R.string.mobile_transfers_default_folder)
                    else -> destination.name ?: stringResource(R.string.mobile_destination_files)
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The labels, not the buttons' padding, line up with the field's edge above.
        Row(modifier = Modifier.offset(x = TEXT_BUTTON_INSET)) {
            if (destination != null) {
                TextButton(colors = neutralTextButtonColors(), onClick = onReset, enabled = enabled) {
                    Text(stringResource(R.string.mobile_transfers_use_default_folder))
                }
            }
            if (onChoose != null) {
                TextButton(
                    colors = neutralTextButtonColors(),
                    onClick = onChoose,
                    enabled = enabled,
                    modifier = Modifier.testTag(MOBILE_TRANSFER_CHANGE_DESTINATION_TAG),
                ) {
                    Text(stringResource(R.string.mobile_transfers_change_folder))
                }
            }
        }
    }
}

/** Material's text button pads its label by 12dp on each side. */
private val TEXT_BUTTON_INSET = 12.dp

@Composable
internal fun MobileTransferTorrentRow(torrent: TorrentUpload, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(MOBILE_TRANSFER_TORRENT_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileTypeIcon(PutioFileType.FILE)
        Text(
            text = torrent.fileName,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_ph_x), stringResource(R.string.mobile_transfers_remove_torrent))
        }
    }
}

/** DESIGN.md keeps every button but the one primary action neutral; Material's text button is yellow. */
@Composable
internal fun neutralTextButtonColors(): ButtonColors =
    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

/** The Files move picker, choosing where a new transfer saves instead of where an item moves. */
@Composable
internal fun MobileTransferDestinationPicker(
    repository: FilesRepository,
    onAuthenticationRequired: suspend () -> Unit,
    onDismiss: () -> Unit,
    onChoose: (FilesFolder) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(repository) { FilesMoveDestinationController(repository, scope) }
    DisposableEffect(controller) { onDispose(controller::close) }
    val destination by controller.state.collectAsState()
    val sessionFailure = destination.current.content.authoritativeSessionFailure() != null
    AuthoritativeSessionFailureEffect(shouldReject = sessionFailure, onReject = onAuthenticationRequired)
    MobileFilesMoveDestination(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onCancel = onDismiss,
        onConfirm = {
            val current = controller.state.value
            if (current.canMoveHere) onChoose(current.current.folder)
        },
        canSubmit = !sessionFailure,
        title = stringResource(R.string.mobile_transfers_save_to),
        confirmLabel = stringResource(R.string.mobile_transfers_save_here),
    )
}

internal fun MobileShareValidation.messageResource(): Int = when (this) {
    MobileShareValidation.InvalidLink -> R.string.mobile_transfers_add_invalid
    MobileShareValidation.TooManyLinks -> R.string.mobile_transfers_add_too_many
    MobileShareValidation.TooLong -> R.string.mobile_share_too_long
    MobileShareValidation.NotAdded -> R.string.mobile_transfers_add_not_added
    MobileShareValidation.InvalidTorrent -> R.string.mobile_transfers_torrent_invalid
    MobileShareValidation.TorrentTooLarge -> R.string.mobile_transfers_torrent_too_large
}
