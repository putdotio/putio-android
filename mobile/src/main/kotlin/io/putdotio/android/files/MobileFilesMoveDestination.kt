package io.putdotio.android.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.LoadNextPageNearEnd
import io.putdotio.android.MobileLoadingState
import io.putdotio.android.MobileMiddleCutText
import io.putdotio.android.MobileRow
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.design.FileTypeIcon
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.neutralTextButtonColors
import io.putdotio.sdk.files.PutioFileType
import androidx.compose.foundation.layout.sizeIn

internal const val MOBILE_FILES_MOVE_PICKER_TAG = "mobile-files-move-picker"
internal const val MOBILE_FILES_MOVE_LIST_TAG = "mobile-files-move-list"
internal const val MOBILE_FILES_MOVE_HERE_TAG = "mobile-files-move-here"
internal const val MOBILE_FILES_MOVE_CANCEL_TAG = "mobile-files-move-cancel"
internal const val MOBILE_FILES_MOVE_BACK_TAG = "mobile-files-move-back"
internal const val MOBILE_FILES_MOVE_RETRY_TAG = "mobile-files-move-retry"
internal const val MOBILE_FILES_MOVE_LOAD_MORE_TAG = "mobile-files-move-load-more"
internal const val MOBILE_FILES_MOVE_FOLDER_TAG = "mobile-files-move-folder"
internal const val MOBILE_FILES_MOVE_OPTIONS_TAG = "mobile-files-move-options"
internal const val MOBILE_FILES_MOVE_REMEMBER_TAG = "mobile-files-move-remember"
internal const val MOBILE_FILES_MOVE_NEW_FOLDER_TAG = "mobile-files-move-new-folder"

internal fun mobileFilesMoveFolderTag(id: FilesItemId): String = "mobile-files-move-folder-${id.value}"

/** The picker's New folder: who creates it, and who else learns of the new folder; no creator hides it. */
internal class MobileMovePickerFolders(
    val creator: FilesFolderCreator?,
    val onAuthenticationRequired: suspend () -> Unit,
    val onCreated: (FilesItem) -> Unit,
) {
    companion object {
        val None = MobileMovePickerFolders(null, {}, {})
    }
}

/**
 * A full-screen folder picker: close and the title, breadcrumb chips, folder rows with chevrons, and
 * a bottom bar with New folder and a filled confirm that names the target. Back goes up a folder,
 * then closes; a crumb goes straight to its folder.
 */
@Composable
internal fun MobileFilesMoveDestination(
    state: FilesMoveDestinationState,
    onEvent: (FilesMoveDestinationEvent) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    canSubmit: Boolean = true,
    title: String = pluralStringResource(R.plurals.mobile_files_move_title, 1, 1),
    confirmLabel: @Composable (target: String) -> String = { stringResource(R.string.mobile_files_move_to, it) },
    rememberTarget: Boolean? = null,
    onRememberTargetChange: (Boolean) -> Unit = {},
    folders: MobileMovePickerFolders = MobileMovePickerFolders.None,
) {
    val back = {
        if (state.canNavigateBack) onEvent(FilesMoveDestinationEvent.NavigateBack) else onCancel()
    }
    // The folder the form was opened in; a picker rebuilt elsewhere after recreation drops the form.
    var creatingIn by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.current.folder.id.value) {
        if (creatingIn != state.current.folder.id.value) creatingIn = null
    }
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag(MOBILE_FILES_MOVE_PICKER_TAG),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                MobileMovePickerBar(title, onCancel, rememberTarget, onRememberTargetChange)
                MobileMoveCrumbs(state, onEvent)
                key(state.current.folder.id.value) {
                    MobileMoveFolderContent(state, onEvent, Modifier.weight(1f))
                }
                val target = state.current.folder.name ?: stringResource(R.string.mobile_destination_files)
                MobileMovePickerDock(
                    confirmLabel = confirmLabel(target),
                    canConfirm = canSubmit && state.canMoveHere,
                    onConfirm = onConfirm,
                    onNewFolder = { creatingIn = state.current.folder.id.value }
                        .takeIf { folders.creator != null && state.canCreateFolder },
                )
            }
        }
    }
    val creator = folders.creator
    if (creator != null && creatingIn == state.current.folder.id.value) {
        val parent = state.current.folder
        MobileNewFolderDialog(
            parentId = parent.id,
            parentName = parent.name ?: stringResource(R.string.mobile_destination_files),
            creator = creator,
            onCreated = { created ->
                creatingIn = null
                onEvent(FilesMoveDestinationEvent.FolderCreated(created))
                folders.onCreated(created)
            },
            onDismiss = { creatingIn = null },
            onAuthenticationRequired = folders.onAuthenticationRequired,
        )
    }
}

/** A listed folder of the viewer's own can take a new one; put.io refuses the rest with a reason. */
private val FilesMoveDestinationState.canCreateFolder: Boolean
    get() = current.folder.id.value >= 0L &&
        (current.content is FilesContent.Ready || current.content is FilesContent.Empty)

@Composable
private fun MobileMovePickerBar(
    title: String,
    onCancel: () -> Unit,
    rememberTarget: Boolean?,
    onRememberTargetChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(BAR_HEIGHT).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.testTag(MOBILE_FILES_MOVE_CANCEL_TAG)) {
            Icon(painterResource(R.drawable.ic_ph_x), stringResource(R.string.mobile_action_close))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
        )
        rememberTarget?.let { MobileMovePickerOptions(it, onRememberTargetChange) }
    }
}

/** "Remember target folder" sits in an overflow, so the bar keeps the board's close and title. */
@Composable
private fun MobileMovePickerOptions(rememberTarget: Boolean, onChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(MOBILE_FILES_MOVE_OPTIONS_TAG)) {
            Icon(
                painterResource(R.drawable.ic_ph_dots_three_vertical),
                stringResource(R.string.mobile_files_move_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(PutioRadius.large),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .toggleable(rememberTarget, role = Role.Checkbox, onValueChange = onChange)
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 12.dp)
                    .testTag(MOBILE_FILES_MOVE_REMEMBER_TAG),
            ) {
                Text(
                    stringResource(R.string.mobile_files_move_remember_target),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Checkbox(checked = rememberTarget, onCheckedChange = null)
            }
        }
    }
}

/** Files › … › the current folder; the current crumb is filled, the others go back to their folder. */
@Composable
private fun MobileMoveCrumbs(state: FilesMoveDestinationState, onEvent: (FilesMoveDestinationEvent) -> Unit) {
    val path = state.path
    val scroll = rememberScrollState()
    LaunchedEffect(path.size) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        path.forEachIndexed { index, folder ->
            if (index > 0) {
                Icon(
                    painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CRUMB_CARET_SIZE),
                )
            }
            val current = index == path.lastIndex
            val tag = when (index) {
                path.lastIndex -> MOBILE_FILES_MOVE_FOLDER_TAG
                path.lastIndex - 1 -> MOBILE_FILES_MOVE_BACK_TAG
                else -> null
            }
            MobileMoveCrumb(
                name = folder.name ?: stringResource(R.string.mobile_destination_files),
                current = current,
                modifier = tag?.let { Modifier.testTag(it) } ?: Modifier,
            ) {
                repeat(path.lastIndex - index) { onEvent(FilesMoveDestinationEvent.NavigateBack) }
            }
        }
    }
}

@Composable
private fun MobileMoveCrumb(name: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = !current, role = Role.Button, onClick = onClick)
            .semantics { if (current) selected = true },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = CRUMB_HEIGHT)
                .clip(RoundedCornerShape(PutioRadius.small))
                .background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.run { if (current) onSurface else onSurfaceVariant },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MobileMovePickerDock(
    confirmLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onNewFolder: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        onNewFolder?.let { create ->
            TextButton(
                onClick = create,
                colors = neutralTextButtonColors(),
                modifier = Modifier.testTag(MOBILE_FILES_MOVE_NEW_FOLDER_TAG),
            ) {
                Icon(
                    painterResource(R.drawable.ic_ph_folder_plus),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mobile_files_new_folder))
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.testTag(MOBILE_FILES_MOVE_HERE_TAG),
            ) {
                Text(confirmLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    val content = state.current.content
    val listModifier = if (content is FilesContent.Ready || content is FilesContent.Empty) {
        modifier.testTag(MOBILE_FILES_MOVE_LIST_TAG)
    } else {
        modifier
    }
    val listState = rememberLazyListState()
    val paging = when (content) {
        is FilesContent.Ready -> content.paging
        is FilesContent.Empty -> content.paging
        is FilesContent.Loading, is FilesContent.Failed -> FilesPaging.Complete
    }
    // A folders-only page can hold no folders at all; its footer is in view, so the next one follows.
    LoadNextPageNearEnd(
        listState = listState,
        nextPage = paging as? FilesPaging.Available,
        onLoadNextPage = { onEvent(FilesMoveDestinationEvent.LoadNextPage) },
    )
    val context = LocalContext.current
    LazyColumn(state = listState, modifier = listModifier.fillMaxWidth()) {
        when (content) {
            is FilesContent.Loading -> item {
                MobileLoadingState(stringResource(R.string.mobile_files_move_loading))
            }
            is FilesContent.Failed -> item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Text(content.failure.moveDestinationMessage())
                    TextButton(
                        onClick = { onEvent(FilesMoveDestinationEvent.Retry) },
                        colors = neutralTextButtonColors(),
                        modifier = Modifier.testTag(MOBILE_FILES_MOVE_RETRY_TAG),
                    ) {
                        Text(stringResource(R.string.mobile_action_retry))
                    }
                }
            }
            is FilesContent.Ready, is FilesContent.Empty -> {
                val items = (content as? FilesContent.Ready)?.items.orEmpty()
                if (items.isEmpty()) {
                    item {
                        Text(
                            stringResource(
                                if (paging == FilesPaging.Complete) {
                                    R.string.mobile_files_move_empty
                                } else {
                                    R.string.mobile_files_move_empty_page
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(items, key = { it.id.value }) { folder ->
                    MobileRow(
                        leading = { FileTypeIcon(PutioFileType.FOLDER, Modifier.size(FOLDER_ICON_SIZE)) },
                        headline = { MobileMiddleCutText(folder.name) },
                        meta = folder.createdAt.toDisplayDate(context)?.let { date -> { Text(date, maxLines = 1) } },
                        trailing = {
                            Icon(
                                painterResource(R.drawable.ic_ph_caret_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp).size(CHEVRON_SIZE),
                            )
                        },
                        modifier = Modifier
                            .testTag(mobileFilesMoveFolderTag(folder.id))
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
        is FilesPaging.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        is FilesPaging.Available -> TextButton(
            onClick = { onEvent(FilesMoveDestinationEvent.LoadNextPage) },
            colors = neutralTextButtonColors(),
            modifier = Modifier.testTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG),
        ) {
            Text(stringResource(R.string.mobile_files_move_more))
        }
        is FilesPaging.Failed -> Column(modifier = Modifier.padding(16.dp)) {
            Text(paging.failure.moveDestinationMessage())
            TextButton(
                onClick = { onEvent(FilesMoveDestinationEvent.Retry) },
                colors = neutralTextButtonColors(),
                modifier = Modifier.testTag(MOBILE_FILES_MOVE_RETRY_TAG),
            ) {
                Text(stringResource(R.string.mobile_action_retry))
            }
        }
    }
}

@Composable
private fun PutioFailure.moveDestinationMessage(): String = when {
    this is PutioFailure.AccessDenied || this is PutioFailure.ApiRejected && statusCode == HTTP_NOT_FOUND ->
        stringResource(R.string.mobile_files_move_destination_unavailable)
    else -> mobileMessage()
}

private const val HTTP_NOT_FOUND = 404
private val BAR_HEIGHT = 64.dp
private val CRUMB_HEIGHT = 32.dp
private val CRUMB_CARET_SIZE = 14.dp
private val FOLDER_ICON_SIZE = 26.dp
private val CHEVRON_SIZE = 20.dp
