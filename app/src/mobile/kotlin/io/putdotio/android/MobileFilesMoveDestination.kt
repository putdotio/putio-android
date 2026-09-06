package io.putdotio.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationEvent
import io.putdotio.android.files.FilesMoveDestinationState
import io.putdotio.android.files.FilesPaging
import io.putdotio.sdk.files.PutioFileType

internal const val MOBILE_FILES_MOVE_PICKER_TAG = "mobile-files-move-picker"
internal const val MOBILE_FILES_MOVE_LIST_TAG = "mobile-files-move-list"
internal const val MOBILE_FILES_MOVE_HERE_TAG = "mobile-files-move-here"
internal const val MOBILE_FILES_MOVE_CANCEL_TAG = "mobile-files-move-cancel"
internal const val MOBILE_FILES_MOVE_BACK_TAG = "mobile-files-move-back"
internal const val MOBILE_FILES_MOVE_RETRY_TAG = "mobile-files-move-retry"
internal const val MOBILE_FILES_MOVE_LOAD_MORE_TAG = "mobile-files-move-load-more"
internal const val MOBILE_FILES_MOVE_FOLDER_TAG = "mobile-files-move-folder"

internal fun mobileFilesMoveFolderTag(id: FilesItemId): String = "mobile-files-move-folder-${id.value}"

@Composable
internal fun MobileFilesMoveDestination(
    state: FilesMoveDestinationState,
    onEvent: (FilesMoveDestinationEvent) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    canSubmit: Boolean = true,
) {
    val back = {
        if (state.canNavigateBack) onEvent(FilesMoveDestinationEvent.NavigateBack) else onCancel()
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false,
    )) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.92f)
                .testTag(MOBILE_FILES_MOVE_PICKER_TAG),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.mobile_files_move), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel, modifier = Modifier.testTag(MOBILE_FILES_MOVE_CANCEL_TAG)) {
                        Text(stringResource(R.string.mobile_action_cancel))
                    }
                }
                Text(state.sourceItem.name, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back, enabled = state.canNavigateBack,
                        modifier = Modifier.testTag(MOBILE_FILES_MOVE_BACK_TAG)) {
                        Icon(painterResource(R.drawable.ic_ph_arrow_left), stringResource(R.string.mobile_action_back))
                    }
                    Text(state.current.folder.name ?: stringResource(R.string.mobile_destination_files),
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 16.dp).testTag(MOBILE_FILES_MOVE_FOLDER_TAG))
                }
                key(state.current.folder.id.value) {
                    MobileMoveFolderContent(state, onEvent, Modifier.weight(1f))
                }
                HorizontalDivider()
                Button(onClick = onConfirm, enabled = canSubmit && state.canMoveHere,
                    modifier = Modifier.padding(16.dp).fillMaxWidth().testTag(MOBILE_FILES_MOVE_HERE_TAG)) {
                    Text(stringResource(R.string.mobile_files_move_here))
                }
            }
        }
    }
}

@Composable
private fun MobileMoveFolderContent(
    state: FilesMoveDestinationState,
    onEvent: (FilesMoveDestinationEvent) -> Unit,
    modifier: Modifier,
) {
    when (val content = state.current.content) {
        is FilesContent.Loading -> MobileLoadingState(stringResource(R.string.mobile_files_move_loading), modifier)
        is FilesContent.Failed -> Column(modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(stringResource(content.failure.moveDestinationMessageResource()))
            TextButton(onClick = { onEvent(FilesMoveDestinationEvent.Retry) },
                modifier = Modifier.testTag(MOBILE_FILES_MOVE_RETRY_TAG)) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
        is FilesContent.Ready, is FilesContent.Empty -> {
            val items = (content as? FilesContent.Ready)?.items.orEmpty()
            val paging = when (content) {
                is FilesContent.Ready -> content.paging
                is FilesContent.Empty -> content.paging
            }
            LazyColumn(modifier = modifier.fillMaxWidth().testTag(MOBILE_FILES_MOVE_LIST_TAG)) {
                if (items.isEmpty()) item {
                    Text(stringResource(if (paging == FilesPaging.Complete) R.string.mobile_files_move_empty
                        else R.string.mobile_files_move_empty_page), modifier = Modifier.padding(16.dp))
                }
                items(items, key = { it.id.value }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { FileTypeIcon(PutioFileType.FOLDER) },
                        modifier = Modifier.testTag(mobileFilesMoveFolderTag(folder.id))
                            .clickable(role = Role.Button, enabled = state.canOpenFolder(folder.id)) {
                                onEvent(FilesMoveDestinationEvent.OpenFolder(folder.id))
                            },
                    )
                }
                item { MobileMovePaging(paging, onEvent) }
            }
        }
    }
}

@Composable
private fun MobileMovePaging(paging: FilesPaging, onEvent: (FilesMoveDestinationEvent) -> Unit) {
    when (paging) {
        FilesPaging.Complete -> Unit
        is FilesPaging.Loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        is FilesPaging.Available -> TextButton(onClick = { onEvent(FilesMoveDestinationEvent.LoadNextPage) },
            modifier = Modifier.testTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG)) {
            Text(stringResource(R.string.mobile_files_move_more))
        }
        is FilesPaging.Failed -> Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(paging.failure.moveDestinationMessageResource()))
            TextButton(onClick = { onEvent(FilesMoveDestinationEvent.Retry) },
                modifier = Modifier.testTag(MOBILE_FILES_MOVE_RETRY_TAG)) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

private fun FilesFailure.moveDestinationMessageResource(): Int = when {
    this is FilesFailure.AccessDenied || this is FilesFailure.ApiRejected && statusCode == 404 ->
        R.string.mobile_files_move_destination_unavailable
    else -> mobileMessageResource()
}
