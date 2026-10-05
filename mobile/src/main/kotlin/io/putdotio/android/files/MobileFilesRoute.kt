package io.putdotio.android.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.AuthoritativeSessionFailureEffect
import io.putdotio.android.authoritativeSessionFailure
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.playback.dispatch
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.sharing.MobilePublicLinkSheet
import io.putdotio.android.sharing.MobilePublicLinks

@Composable
internal fun MobileFilesRoute(
    state: FilesBrowserState,
    repository: FilesRepository?,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onPlayMedia: (FilesItem) -> Unit,
    confirmedTrashEnabled: Boolean?,
    onAuthenticationRequired: suspend () -> Unit,
    downloads: DownloadsState = DownloadsState(),
    onDownloadItem: ((FilesItem) -> Unit)? = null,
    onShareItem: ((FilesItem) -> Unit)? = null,
    onViewTrash: (() -> Unit)? = null,
    moveTargetStore: FilesMoveTargetStore? = null,
    publicLinks: MobilePublicLinks? = null,
    fileDragOut: MobileFileDragOut? = null,
) {
    key(repository, state.current.folder.id.value) {
        var movingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val movingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == movingItemId }
        var copyingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val copyingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == copyingItemId && it.canMakeCopy }
        var publicLinkItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val publicLinkItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == publicLinkItemId && it.acceptsOwnerActions }
        MobileFilesScreen(
            state = state,
            onEvent = { onEvent(it) },
            onPlayMedia = onPlayMedia,
            confirmedTrashEnabled = confirmedTrashEnabled,
            onMoveItem =
                if (repository == null || !state.canStartMove) null else { item -> movingItemId = item.id.value },
            downloads = downloads,
            onDownloadItem = onDownloadItem,
            onShareItem = onShareItem,
            onViewTrash = onViewTrash,
            onCopyItem = if (repository == null) null else { item -> copyingItemId = item.id.value },
            onPublicLinkItem = publicLinks?.let { { item -> publicLinkItemId = item.id.value } },
            fileDragOut = fileDragOut,
        )
        if (movingItem != null && repository != null) {
            MobileFilesMoveSession(
                item = movingItem,
                sourceFolderId = state.current.folder.id,
                repository = repository,
                canSubmit = state.canStartMove,
                targetStore = moveTargetStore,
                onAuthenticationRequired = onAuthenticationRequired,
                onEvent = onEvent,
                onDismiss = { movingItemId = null },
            )
        }
        if (copyingItem != null && repository != null) {
            MobileFilesCopySession(
                item = copyingItem,
                folderId = state.current.folder.id,
                repository = repository,
                canSubmit = state.canStartCopy,
                targetStore = moveTargetStore,
                onAuthenticationRequired = onAuthenticationRequired,
                onEvent = onEvent,
                onDismiss = { copyingItemId = null },
            )
        }
        if (publicLinkItem != null && publicLinks != null) {
            MobilePublicLinkSheet(publicLinkItem, publicLinks, onDismiss = { publicLinkItemId = null })
        }
    }
}

/**
 * The move picker without a source item, so every folder of the viewer's own, root included, is a
 * destination. Like Move, it opens at root unless "Remember target folder" is on.
 */
@Composable
private fun MobileFilesCopySession(
    item: FilesItem,
    folderId: FilesItemId,
    repository: FilesRepository,
    canSubmit: Boolean,
    targetStore: FilesMoveTargetStore?,
    onAuthenticationRequired: suspend () -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val memory = rememberMoveTargetMemory(targetStore, item.id)
    val controller = remember(item.id, repository) {
        FilesMoveDestinationController(repository, scope, memory.value.startPath(sourceItem = null))
    }
    var finished by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) {
        onDispose {
            finished = true
            controller.close()
        }
    }
    val destination by controller.state.collectAsState()
    val sessionFailure = destination.current.content.authoritativeSessionFailure() != null
    AuthoritativeSessionFailureEffect(shouldReject = sessionFailure, onReject = onAuthenticationRequired)
    MobileFilesMoveDestination(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onCancel = {
            finished = true
            onDismiss()
        },
        onConfirm = {
            val current = controller.state.value
            if (!finished && canSubmit && current.confirmable) {
                finished = true
                if (onEvent(FilesBrowserEvent.Copy(folderId, item.id, current.current.folder))) {
                    memory.choose(targetStore, current.path)
                    onDismiss()
                } else {
                    finished = false
                }
            }
        },
        canSubmit = canSubmit && !finished && !sessionFailure,
        title = stringResource(R.string.mobile_files_make_copy),
        confirmLabel = stringResource(R.string.mobile_files_copy_here),
        sourceName = item.name,
        rememberTarget = targetStore?.let { memory.value.remember },
        onRememberTargetChange = { memory.setRemember(targetStore, it) },
    )
}

@Composable
private fun MobileFilesMoveSession(
    item: FilesItem,
    sourceFolderId: FilesItemId,
    repository: FilesRepository,
    canSubmit: Boolean,
    targetStore: FilesMoveTargetStore?,
    onAuthenticationRequired: suspend () -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val memory = rememberMoveTargetMemory(targetStore, item.id)
    val controller = remember(item.id, sourceFolderId, repository) {
        FilesMoveDestinationController(item, sourceFolderId, repository, scope, memory.value.startPath(item))
    }
    var finished by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) {
        onDispose {
            finished = true
            controller.close()
        }
    }
    val destination by controller.state.collectAsState()
    AuthoritativeSessionFailureEffect(
        shouldReject = destination.current.content.authoritativeSessionFailure() != null,
        onReject = onAuthenticationRequired,
    )
    MobileFilesMoveDestination(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onCancel = {
            finished = true
            onDismiss()
        },
        canSubmit = canSubmit && !finished && destination.current.content.authoritativeSessionFailure() == null,
        onConfirm = {
            val current = controller.state.value
            if (!finished && canSubmit && current.confirmable) {
                finished = true
                if (onEvent(FilesBrowserEvent.Move(sourceFolderId, item.id, current.current.folder.id))) {
                    memory.choose(targetStore, current.path)
                    // The session-owned browser retains the submitted operation after the picker closes.
                    onDismiss()
                } else {
                    finished = false
                }
            }
        },
        rememberTarget = targetStore?.let { memory.value.remember },
        onRememberTargetChange = { memory.setRemember(targetStore, it) },
    )
}

private val FilesMoveDestinationState.confirmable: Boolean
    get() = canMoveHere && current.content.authoritativeSessionFailure() == null

/** Read once per picker, so a toggle changes where the next picker opens, as on web. */
@Composable
private fun rememberMoveTargetMemory(
    store: FilesMoveTargetStore?,
    itemId: FilesItemId,
): MutableState<FilesMoveTargetMemory> =
    remember(store, itemId) { mutableStateOf(store?.read() ?: FilesMoveTargetMemory()) }

private fun MutableState<FilesMoveTargetMemory>.setRemember(store: FilesMoveTargetStore?, enabled: Boolean) {
    value = value.copy(remember = enabled)
    store?.write(value)
}

private fun MutableState<FilesMoveTargetMemory>.choose(store: FilesMoveTargetStore?, path: List<FilesFolder>) {
    val chosen = value.chosen(path)
    if (chosen != value) {
        value = chosen
        store?.write(chosen)
    }
}
