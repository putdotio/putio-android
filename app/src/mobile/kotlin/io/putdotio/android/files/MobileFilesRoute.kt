package io.putdotio.android.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
) {
    key(repository, state.current.folder.id.value) {
        var movingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val movingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == movingItemId }
        var copyingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val copyingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == copyingItemId && it.canMakeCopy }
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
        )
        if (movingItem != null && repository != null) {
            MobileFilesMoveSession(
                item = movingItem,
                sourceFolderId = state.current.folder.id,
                repository = repository,
                canSubmit = state.canStartMove,
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
                onAuthenticationRequired = onAuthenticationRequired,
                onEvent = onEvent,
                onDismiss = { copyingItemId = null },
            )
        }
    }
}

/**
 * The move picker without a source item, so every folder of the viewer's own, root included, is a
 * destination. It opens at root, as web's does unless its "Remember target folder" is on.
 */
@Composable
private fun MobileFilesCopySession(
    item: FilesItem,
    folderId: FilesItemId,
    repository: FilesRepository,
    canSubmit: Boolean,
    onAuthenticationRequired: suspend () -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(item.id, repository) { FilesMoveDestinationController(repository, scope) }
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
            if (canSubmit && current.canMoveHere &&
                onEvent(FilesBrowserEvent.Copy(folderId, item.id, current.current.folder))
            ) {
                onDismiss()
            }
        },
        canSubmit = canSubmit && !sessionFailure,
        title = stringResource(R.string.mobile_files_make_copy),
        confirmLabel = stringResource(R.string.mobile_files_copy_here),
        sourceName = item.name,
    )
}

@Composable
private fun MobileFilesMoveSession(
    item: FilesItem,
    sourceFolderId: FilesItemId,
    repository: FilesRepository,
    canSubmit: Boolean,
    onAuthenticationRequired: suspend () -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(item.id, sourceFolderId, repository) {
        FilesMoveDestinationController(item, sourceFolderId, repository, scope)
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
            if (!finished && canSubmit && current.canMoveHere &&
                current.current.content.authoritativeSessionFailure() == null) {
                finished = true
                if (onEvent(FilesBrowserEvent.Move(sourceFolderId, item.id, current.current.folder.id))) {
                    // The session-owned browser retains the submitted operation after the picker closes.
                    onDismiss()
                } else {
                    finished = false
                }
            }
        },
    )
}
