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
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationController
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.canStartOperation

@Composable
internal fun MobileFilesRoute(
    state: FilesBrowserState,
    repository: FilesRepository?,
    onEvent: (FilesBrowserEvent) -> Unit,
    onPlayVideo: (FilesItem) -> Unit,
    confirmedTrashEnabled: Boolean?,
) {
    key(repository, state.current.folder.id.value) {
        var movingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val movingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == movingItemId }
        MobileFilesScreen(
            state = state,
            onEvent = onEvent,
            onPlayVideo = onPlayVideo,
            confirmedTrashEnabled = confirmedTrashEnabled,
            onMoveItem = if (repository == null) null else { item -> movingItemId = item.id.value },
        )
        if (movingItem != null && repository != null) {
            MobileFilesMoveSession(
                item = movingItem,
                sourceFolderId = state.current.folder.id,
                repository = repository,
                canSubmit = state.current.operation.canStartOperation,
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
    onEvent: (FilesBrowserEvent) -> Unit,
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
    MobileFilesMoveDestination(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onCancel = {
            finished = true
            onDismiss()
        },
        canSubmit = canSubmit && !finished,
        onConfirm = {
            val current = controller.state.value
            if (!finished && canSubmit && current.canMoveHere) {
                finished = true
                onEvent(FilesBrowserEvent.Move(sourceFolderId, item.id, current.current.folder.id))
                // The session-owned browser retains the submitted operation after the picker closes.
                onDismiss()
            }
        },
    )
}
