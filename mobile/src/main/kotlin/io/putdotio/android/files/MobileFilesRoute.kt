package io.putdotio.android.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.AuthoritativeSessionFailureEffect
import io.putdotio.android.MobileAxisContent
import io.putdotio.android.authoritativeSessionFailure
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.playback.dispatch
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.sharing.MobilePublicLinkSheet
import io.putdotio.android.sharing.MobilePublicLinks
import io.putdotio.android.viewer.MobileFileViewer
import io.putdotio.android.viewer.MobileFileViewerActions
import kotlinx.coroutines.flow.MutableSharedFlow

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
    continueWatching: List<ContinueWatchingEntry> = emptyList(),
    onNewFolder: (() -> Unit)? = null,
    /** From 840dp, the Files bar; it then sits over the list pane, beside the selected file's detail. */
    listPaneTopBar: (@Composable () -> Unit)? = null,
    /** Reaches files for the viewers and other apps; null leaves only media playable. */
    fileOpener: MobileFileOpener? = null,
    subtitleLanguages: FilesSubtitleLanguages? = null,
) {
    val context = LocalContext.current
    // Opening a folder pushes along the shared axis and Back pops; the leaving folder keeps its own state.
    val folderKey = { browser: FilesBrowserState -> browser.stack.size to browser.current.folder.id.value }
    val currentKey = folderKey(state)
    val items = state.current.content.items()
    val showsDetail = listPaneTopBar != null
    // The file the detail pane shows; another folder starts with none.
    var detailId by rememberSaveable(currentKey.first, currentKey.second) { mutableStateOf<Long?>(null) }
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    val actionsRequests = remember { MutableSharedFlow<FilesItemId>(extraBufferCapacity = 1) }
    val openWithAnotherApp: (FilesItem) -> Unit = { item -> fileOpener?.openWithAnotherApp(context, item) }
    val openFile = fileOpenAction(onPlayMedia, onView = { viewerId = it.id.value }, onOther = openWithAnotherApp)
    val fileOpening = fileOpener?.let {
        MobileFilesOpening(
            onOpenFile = if (showsDetail) { item -> detailId = item.id.value } else openFile,
            showsDetail = showsDetail,
            selectedFileId = detailId?.takeIf { showsDetail }?.let(::FilesItemId),
            actionsRequests = actionsRequests,
        )
    }
    val openActions: (FilesItem) -> Unit = { item ->
        if (item.hasMobileActions(onDownloadItem != null, onShareItem != null, repository != null)) {
            actionsRequests.tryEmit(item.id)
        }
    }
    // Movable, so crossing 840dp keeps the folder's sheet, rename draft and pickers.
    val folders = remember { movableContentOf { args: MobileFilesFoldersArgs -> MobileFilesFolders(args) } }
    val foldersArgs =
        MobileFilesFoldersArgs(
            state = state,
            currentKey = currentKey,
            folderKey = folderKey,
            repository = repository,
            onEvent = onEvent,
            onPlayMedia = onPlayMedia,
            confirmedTrashEnabled = confirmedTrashEnabled,
            onAuthenticationRequired = onAuthenticationRequired,
            downloads = downloads,
            onDownloadItem = onDownloadItem,
            onShareItem = onShareItem,
            onViewTrash = onViewTrash,
            moveTargetStore = moveTargetStore,
            publicLinks = publicLinks,
            fileDragOut = fileDragOut,
            continueWatching = continueWatching,
            onNewFolder = onNewFolder,
            fileOpening = fileOpening,
        )
    if (listPaneTopBar == null) {
        folders(foldersArgs)
    } else {
        MobileFilesListDetail(
            topBar = listPaneTopBar,
            list = { folders(foldersArgs) },
            detail = {
                val shown = items.firstOrNull { it.id.value == detailId && !it.isFolder }
                MobileFilesDetailPane(
                    item = shown,
                    folderPath = state.folderPath() ?: stringResource(R.string.mobile_destination_files),
                    downloadStatus = shown?.let { downloads.entry(it.id)?.status },
                    subtitleLanguages = subtitleLanguages,
                    onAuthenticationRequired = onAuthenticationRequired,
                    actions = MobileFilesDetailActions(
                        onOpen = openFile,
                        onDownload = onDownloadItem,
                        onShare = onShareItem,
                        onMore = openActions,
                        imageUri = fileOpener?.documentUris(),
                    ),
                )
            },
        )
    }
    if (fileOpener != null) {
        MobileFilesViewerHost(
            state = state,
            viewerId = viewerId,
            actions = MobileFileViewerActions(
                documentUri = { fileOpener.documentUri(context, it) },
                onOpenWithAnotherApp = openWithAnotherApp,
                onShare = onShareItem,
                onMore = openActions,
            ),
            onShown = { id ->
                viewerId = id.value
                if (showsDetail) detailId = id.value
            },
            onClose = { viewerId = null },
        )
    }
}

/** Media plays, images, PDFs and text open in the viewer, and anything else in another app. */
private fun fileOpenAction(
    onPlayMedia: (FilesItem) -> Unit,
    onView: (FilesItem) -> Unit,
    onOther: (FilesItem) -> Unit,
): (FilesItem) -> Unit = { item ->
    when {
        item.isPlayable -> onPlayMedia(item)
        item.viewerKind != null -> onView(item)
        else -> onOther(item)
    }
}

/** The viewer over the folder's files of the shown file's kind, while that file is still listed. */
@Composable
private fun MobileFilesViewerHost(
    state: FilesBrowserState,
    viewerId: Long?,
    actions: MobileFileViewerActions,
    onShown: (FilesItemId) -> Unit,
    onClose: () -> Unit,
) {
    val items = state.current.content.items()
    val viewerItem = items.firstOrNull { it.id.value == viewerId }
    if (viewerItem != null) {
        val kind = viewerItem.viewerKind
        MobileFileViewer(
            files = items.filter { it.viewerKind == kind },
            shownId = viewerItem.id,
            actions = actions,
            onShown = onShown,
            onDismiss = onClose,
        )
    } else if (viewerId != null && state.current.content !is FilesContent.Loading) {
        // The file left the listing, or the listing changed folder: there is nothing left to view.
        SideEffect(onClose)
    }
}

/** Everything the folder listings take; one value, so the movable listing gets it as its parameter. */
private data class MobileFilesFoldersArgs(
    val state: FilesBrowserState,
    val currentKey: Pair<Int, Long>,
    val folderKey: (FilesBrowserState) -> Pair<Int, Long>,
    val repository: FilesRepository?,
    val onEvent: (FilesBrowserEvent) -> Boolean,
    val onPlayMedia: (FilesItem) -> Unit,
    val confirmedTrashEnabled: Boolean?,
    val onAuthenticationRequired: suspend () -> Unit,
    val downloads: DownloadsState,
    val onDownloadItem: ((FilesItem) -> Unit)?,
    val onShareItem: ((FilesItem) -> Unit)?,
    val onViewTrash: (() -> Unit)?,
    val moveTargetStore: FilesMoveTargetStore?,
    val publicLinks: MobilePublicLinks?,
    val fileDragOut: MobileFileDragOut?,
    val continueWatching: List<ContinueWatchingEntry>,
    val onNewFolder: (() -> Unit)?,
    val fileOpening: MobileFilesOpening?,
)

@Composable
private fun MobileFilesFolders(args: MobileFilesFoldersArgs) = with(args) {
    MobileAxisContent(
        state = state,
        contentKey = folderKey,
        forward = { initial, target -> target.stack.size >= initial.stack.size },
    ) { shown ->
        // The reducer applies most events to the current folder, so the leaving one sends only an
        // announced deletion, which the reducer matches anywhere in the stack, and starts nothing.
        val leaving = folderKey(shown) != currentKey
        MobileFilesFolder(
            state = shown,
            repository = repository,
            onEvent = { event ->
                (!leaving || event is FilesBrowserEvent.DeleteOutcomeAnnounced) && onEvent(event)
            },
            onPlayMedia = { item -> if (!leaving) onPlayMedia(item) },
            confirmedTrashEnabled = confirmedTrashEnabled,
            onAuthenticationRequired = onAuthenticationRequired,
            downloads = downloads,
            onDownloadItem = onDownloadItem?.let { download -> { item -> if (!leaving) download(item) } },
            onShareItem = onShareItem?.let { share -> { item -> if (!leaving) share(item) } },
            onViewTrash = onViewTrash?.let { view -> { if (!leaving) view() } },
            moveTargetStore = moveTargetStore,
            publicLinks = publicLinks,
            fileDragOut = fileDragOut,
            continueWatching = continueWatching,
            onNewFolder = onNewFolder?.takeUnless { leaving },
            fileOpening = fileOpening?.takeUnless { leaving },
        )
    }
}

@Composable
private fun MobileFilesFolder(
    state: FilesBrowserState,
    repository: FilesRepository?,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onPlayMedia: (FilesItem) -> Unit,
    confirmedTrashEnabled: Boolean?,
    onAuthenticationRequired: suspend () -> Unit,
    downloads: DownloadsState,
    onDownloadItem: ((FilesItem) -> Unit)?,
    onShareItem: ((FilesItem) -> Unit)?,
    onViewTrash: (() -> Unit)?,
    moveTargetStore: FilesMoveTargetStore?,
    publicLinks: MobilePublicLinks?,
    fileDragOut: MobileFileDragOut?,
    continueWatching: List<ContinueWatchingEntry>,
    onNewFolder: (() -> Unit)?,
    fileOpening: MobileFilesOpening?,
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
            continueWatching = continueWatching,
            onNewFolder = onNewFolder,
            fileOpening = fileOpening,
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
    val sessionFailure = destination.current.content.authoritativeSessionFailure() != null
    AuthoritativeSessionFailureEffect(shouldReject = sessionFailure, onReject = onAuthenticationRequired)
    MobileFilesMoveDestination(
        state = destination,
        onEvent = { controller.dispatch(it) },
        onCancel = {
            finished = true
            onDismiss()
        },
        canSubmit = canSubmit && !finished && !sessionFailure,
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
