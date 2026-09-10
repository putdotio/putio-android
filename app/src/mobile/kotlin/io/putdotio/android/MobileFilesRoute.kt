package io.putdotio.android

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
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.FilesContent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationController
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.canStartMove

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
) {
    key(repository, state.current.folder.id.value) {
        var movingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val movingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == movingItemId }
        MobileFilesScreen(
            state = state,
            onEvent = { onEvent(it) },
            onPlayMedia = onPlayMedia,
            confirmedTrashEnabled = confirmedTrashEnabled,
            onMoveItem = if (repository == null || !state.canStartMove) null else { item -> movingItemId = item.id.value },
            downloads = downloads,
            onDownloadItem = onDownloadItem,
            onShareItem = onShareItem,
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
    }
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
