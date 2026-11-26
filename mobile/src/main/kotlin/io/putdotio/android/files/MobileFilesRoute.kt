package io.putdotio.android.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import io.putdotio.android.viewer.MobileFileViewerPaging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

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
    moveTargetStore: FilesMoveTargetStore? = null,
    publicLinks: MobilePublicLinks? = null,
    fileDragOut: MobileFileDragOut? = null,
    continueWatching: List<ContinueWatchingEntry> = emptyList(),
    pinned: List<FilesItem> = emptyList(),
    onRefreshPinned: (() -> Unit)? = null,
    onNewFolder: (() -> Unit)? = null,
    listPaneTopBar: (@Composable () -> Unit)? = null,
    fileOpener: MobileFileOpener? = null,
    subtitleLanguages: FilesSubtitleLanguages? = null,
    resumePlayback: Boolean = true,
    itemActions: MobileFilesItemActions = MobileFilesItemActions(),
    folderCreator: FilesFolderCreator? = null,
    selection: FilesSelection? = null,
    linkedFile: FilesItemId? = null,
    onLinkedFileLanded: () -> Unit = {},
) {
    val context = LocalContext.current
    val folderKey = { browser: FilesBrowserState -> browser.stack.size to browser.current.folder.id.value }
    val currentKey = folderKey(state)
    val items = state.current.content.items()
    val showsDetail = listPaneTopBar != null
    val selecting = selection?.isActive(state) == true
    var detailId by rememberSaveable(currentKey.first, currentKey.second) { mutableStateOf<Long?>(null) }
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(selecting) { if (selecting) viewerId = null }
    var pinToOpen by rememberSaveable { mutableStateOf<Long?>(null) }
    val openWithAnotherApp: (FilesItem) -> Unit = { item -> fileOpener?.openWithAnotherApp(context, item) }
    LandOnLinkedFile(
        state = state,
        linkedFile = linkedFile,
        pinToOpen = pinToOpen?.let(::FilesItemId),
        showsDetail = showsDetail,
        views = fileOpener != null,
        onDetail = { detailId = it.id.value },
        onView = { viewerId = it.id.value },
        onPinElsewhere = openWithAnotherApp,
        onLanded = {
            pinToOpen = null
            onLinkedFileLanded()
        },
    )
    val actionsRequests = remember { MutableSharedFlow<MobileFilesActionsRequest>(extraBufferCapacity = 1) }
    val viewFile: (FilesItem) -> Unit = { viewerId = it.id.value }
    val openFile = fileOpenAction(onPlayMedia, onView = viewFile, onOther = openWithAnotherApp)
    val sheetActions = itemActions.opening(
        views = fileOpener != null,
        view = viewFile,
        openWith = openWithAnotherApp,
        viewerShown = items.any { it.id.value == viewerId },
    )
    val fileOpening = fileOpener?.let {
        MobileFilesOpening(
            onOpenFile = if (showsDetail) { item -> detailId = item.id.value } else openFile,
            showsDetail = showsDetail,
            selectedFileId = detailId?.takeIf { showsDetail && !selecting }?.let(::FilesItemId),
            actionsRequests = actionsRequests,
            onOpenWhenRevealed = { pinToOpen = it.value },
        )
    }
    val actionsFrom = { inViewer: Boolean ->
        { item: FilesItem ->
            if (item.hasMobileActions(onDownloadItem != null, onShareItem != null, repository != null)) {
                actionsRequests.tryEmit(MobileFilesActionsRequest(item.id, inViewer))
            }
        }
    }
    val openActions = actionsFrom(false)
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
            moveTargetStore = moveTargetStore,
            publicLinks = publicLinks,
            fileDragOut = fileDragOut,
            continueWatching = continueWatching,
            pinned = pinned,
            onRefreshPinned = onRefreshPinned,
            onNewFolder = onNewFolder,
            fileOpening = fileOpening,
            itemActions = sheetActions,
            folderCreator = folderCreator,
            selection = selection,
        )
    if (listPaneTopBar == null) {
        folders(foldersArgs)
    } else {
        MobileFilesListDetail(
            topBar = listPaneTopBar,
            list = { folders(foldersArgs) },
            detail = {
                val shown = items.firstOrNull { it.id.value == detailId && !it.isFolder }?.takeUnless { selecting }
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
                        resumePlayback = resumePlayback,
                    ),
                )
            },
        )
    }
    if (fileOpener != null) {
        MobileFilesViewerHost(
            state = state,
            viewerId = viewerId,
            onLoadNextPage = { onEvent(FilesBrowserEvent.LoadNextPage) },
            onRetryPage = { onEvent(FilesBrowserEvent.Retry) },
            actions = MobileFileViewerActions(
                documentUri = { fileOpener.documentUri(context, it) },
                onOpenWithAnotherApp = openWithAnotherApp,
                onShare = onShareItem,
                onMore = actionsFrom(true),
                notices = itemActions.pinNotices,
            ),
            onShown = { id ->
                viewerId = id.value
                if (showsDetail) detailId = id.value
            },
            onClose = { viewerId = null },
        )
    }
}

@Composable
private fun LandOnLinkedFile(
    state: FilesBrowserState,
    linkedFile: FilesItemId?,
    pinToOpen: FilesItemId?,
    showsDetail: Boolean,
    views: Boolean,
    onDetail: (FilesItem) -> Unit,
    onView: (FilesItem) -> Unit,
    onPinElsewhere: (FilesItem) -> Unit,
    onLanded: () -> Unit,
) {
    val folder = state.current
    val pin = pinToOpen?.takeIf { folder.revealItemId == it }
    val pending = linkedFile?.takeIf { folder.openedFrom == FilesOpenOrigin.LINK && folder.revealItemId == it } ?: pin
    val linkedItem = folder.content.items().firstOrNull { it.id == pending && !it.isFolder }
    val land = { item: FilesItem ->
        when {
            showsDetail -> onDetail(item)
            views && item.viewerKind != null -> onView(item)
            item.id == pin -> onPinElsewhere(item)
        }
        onLanded()
    }
    val currentLand by rememberUpdatedState(land)
    LaunchedEffect(linkedItem?.id) { linkedItem?.let(currentLand) }
}

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

private fun MobileFilesItemActions.opening(
    views: Boolean,
    view: (FilesItem) -> Unit,
    openWith: (FilesItem) -> Unit,
    viewerShown: Boolean,
): MobileFilesItemActions = when {
    !views -> this
    viewerShown -> copy(onOpen = view, onOpenWith = openWith, pinNotices = emptyFlow())
    else -> copy(onOpen = view, onOpenWith = openWith)
}

@Composable
private fun MobileFilesViewerHost(
    state: FilesBrowserState,
    viewerId: Long?,
    onLoadNextPage: () -> Unit,
    onRetryPage: () -> Unit,
    actions: MobileFileViewerActions,
    onShown: (FilesItemId) -> Unit,
    onClose: () -> Unit,
) {
    val items = state.current.content.items()
    val viewerItem = items.firstOrNull { it.id.value == viewerId }
    if (viewerItem != null) {
        val kind = viewerItem.viewerKind
        val paging = (state.current.content as? FilesContent.Ready)?.paging
        val idle = state.current.operation == FilesFolderOperation.Idle
        MobileFileViewer(
            files = items.filter { it.viewerKind == kind },
            shownId = viewerItem.id,
            actions = actions,
            onShown = onShown,
            onDismiss = onClose,
            paging = MobileFileViewerPaging(
                nextPage = (paging as? FilesPaging.Available)?.takeIf { idle },
                more = paging != null && paging != FilesPaging.Complete,
                onLoadNextPage = onLoadNextPage,
                failed = paging is FilesPaging.Failed && idle,
                onRetry = onRetryPage,
            ),
        )
    } else if (viewerId != null && state.current.content !is FilesContent.Loading) {
        SideEffect(onClose)
    }
}

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
    val moveTargetStore: FilesMoveTargetStore?,
    val publicLinks: MobilePublicLinks?,
    val fileDragOut: MobileFileDragOut?,
    val continueWatching: List<ContinueWatchingEntry>,
    val pinned: List<FilesItem>,
    val onRefreshPinned: (() -> Unit)?,
    val onNewFolder: (() -> Unit)?,
    val fileOpening: MobileFilesOpening?,
    val itemActions: MobileFilesItemActions,
    val folderCreator: FilesFolderCreator?,
    val selection: FilesSelection?,
)

@Composable
private fun MobileFilesFolders(args: MobileFilesFoldersArgs) = with(args) {
    MobileAxisContent(
        state = state,
        contentKey = folderKey,
        forward = { initial, target -> target.stack.size >= initial.stack.size },
    ) { shown ->
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
            moveTargetStore = moveTargetStore,
            publicLinks = publicLinks,
            fileDragOut = fileDragOut,
            continueWatching = continueWatching,
            pinned = pinned,
            onRefreshPinned = onRefreshPinned?.takeUnless { leaving },
            onNewFolder = onNewFolder?.takeUnless { leaving },
            fileOpening = fileOpening?.takeUnless { leaving },
            itemActions = itemActions,
            folderCreator = folderCreator,
            selection = selection?.takeUnless { leaving },
        )
    }
}

private fun refreshingPinsAtRoot(
    state: FilesBrowserState,
    onRefreshPinned: (() -> Unit)?,
    onEvent: (FilesBrowserEvent) -> Boolean,
): (FilesBrowserEvent) -> Unit {
    val refreshPins = onRefreshPinned?.takeIf { state.current.folder.id == FilesFolder.Root.id }
    return { event ->
        if (event == FilesBrowserEvent.Refresh) refreshPins?.invoke()
        onEvent(event)
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
    moveTargetStore: FilesMoveTargetStore?,
    publicLinks: MobilePublicLinks?,
    fileDragOut: MobileFileDragOut?,
    continueWatching: List<ContinueWatchingEntry>,
    pinned: List<FilesItem>,
    onRefreshPinned: (() -> Unit)?,
    onNewFolder: (() -> Unit)?,
    fileOpening: MobileFilesOpening?,
    itemActions: MobileFilesItemActions,
    folderCreator: FilesFolderCreator?,
    selection: FilesSelection?,
) {
    key(repository, state.current.folder.id.value) {
        var movingItemIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
        val movingItems = state.listedAll(movingItemIds)
        val startMove = movePicker(repository, state) { items -> movingItemIds = items.map { it.id.value } }
        var copyingItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val copyingItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == copyingItemId && it.canMakeCopy }
        var publicLinkItemId by rememberSaveable { mutableStateOf<Long?>(null) }
        val publicLinkItem = (state.current.content as? FilesContent.Ready)?.items
            ?.firstOrNull { it.id.value == publicLinkItemId && it.acceptsOwnerActions }
        MobileFilesScreen(
            state = state,
            onEvent = refreshingPinsAtRoot(state, onRefreshPinned, onEvent),
            onPlayMedia = onPlayMedia,
            confirmedTrashEnabled = confirmedTrashEnabled,
            onMoveItem = startMove?.let { start -> { item -> start(listOf(item)) } },
            downloads = downloads,
            onDownloadItem = onDownloadItem,
            onShareItem = onShareItem,
            onCopyItem = if (repository == null) null else { item -> copyingItemId = item.id.value },
            onPublicLinkItem = publicLinks?.let { { item -> publicLinkItemId = item.id.value } },
            fileDragOut = fileDragOut,
            continueWatching = continueWatching,
            pinned = pinned,
            onNewFolder = onNewFolder,
            fileOpening = fileOpening,
            itemActions = itemActions,
            selection = selection,
            onMoveItems = startMove,
        )
        val createdInPicker = remember { PickerCreatedFolders() }
        val pickerFolders = MobileMovePickerFolders(folderCreator, onAuthenticationRequired, createdInPicker::add)
        val closePicker = { createdInPicker.invalidate(onEvent) }
        if (movingItems != null && repository != null) {
            MobileFilesMoveSession(
                items = movingItems,
                sourceFolderId = state.current.folder.id,
                repository = repository,
                canSubmit = state.canStartMove,
                targetStore = moveTargetStore,
                onAuthenticationRequired = onAuthenticationRequired,
                onEvent = onEvent,
                onDismiss = {
                    movingItemIds = emptyList()
                    closePicker()
                },
                onMoved = { selection?.end() },
                folders = pickerFolders,
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
                onDismiss = {
                    copyingItemId = null
                    closePicker()
                },
                folders = pickerFolders,
            )
        }
        if (publicLinkItem != null && publicLinks != null) {
            MobilePublicLinkSheet(
                item = publicLinkItem,
                publicLinks = publicLinks,
                onDismiss = { publicLinkItemId = null },
                onCopied = itemActions.onLinkCopied?.let { announce ->
                    {
                        publicLinkItemId = null
                        announce()
                    }
                },
            )
        }
    }
}

private fun movePicker(
    repository: FilesRepository?,
    state: FilesBrowserState,
    open: (List<FilesItem>) -> Unit,
): ((List<FilesItem>) -> Unit)? = open.takeIf { repository != null && state.canStartMove }

private fun FilesBrowserState.listedAll(ids: List<Long>): List<FilesItem>? {
    val listed = (current.content as? FilesContent.Ready)?.items.orEmpty().filter { it.id.value in ids }
    return listed.takeIf { it.isNotEmpty() && it.size == ids.size }
}

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
    folders: MobileMovePickerFolders,
) {
    val scope = rememberCoroutineScope()
    val memory = rememberMoveTargetMemory(targetStore, listOf(item.id))
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
        title = pluralStringResource(R.plurals.mobile_files_copy_title, 1, 1),
        confirmLabel = { stringResource(R.string.mobile_files_copy_to, it) },
        rememberTarget = targetStore?.let { memory.value.remember },
        onRememberTargetChange = { memory.setRemember(targetStore, it) },
        folders = folders,
    )
}

@Composable
private fun MobileFilesMoveSession(
    items: List<FilesItem>,
    sourceFolderId: FilesItemId,
    repository: FilesRepository,
    canSubmit: Boolean,
    targetStore: FilesMoveTargetStore?,
    onAuthenticationRequired: suspend () -> Unit,
    onEvent: (FilesBrowserEvent) -> Boolean,
    onDismiss: () -> Unit,
    onMoved: () -> Unit,
    folders: MobileMovePickerFolders,
) {
    val scope = rememberCoroutineScope()
    val itemIds = items.map { it.id }
    val memory = rememberMoveTargetMemory(targetStore, itemIds)
    val controller = remember(itemIds, sourceFolderId, repository) {
        FilesMoveDestinationController(items, sourceFolderId, repository, scope, memory.value.startPath(items))
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
                if (onEvent(FilesBrowserEvent.Move(sourceFolderId, itemIds, current.current.folder.id))) {
                    memory.choose(targetStore, current.path)
                    onMoved()
                    onDismiss()
                } else {
                    finished = false
                }
            }
        },
        title = pluralStringResource(R.plurals.mobile_files_move_title, items.size, items.size),
        rememberTarget = targetStore?.let { memory.value.remember },
        onRememberTargetChange = { memory.setRemember(targetStore, it) },
        folders = folders,
    )
}

private val FilesMoveDestinationState.confirmable: Boolean
    get() = canMoveHere && current.content.authoritativeSessionFailure() == null

@Composable
private fun rememberMoveTargetMemory(
    store: FilesMoveTargetStore?,
    itemIds: List<FilesItemId>,
): MutableState<FilesMoveTargetMemory> =
    remember(store, itemIds) { mutableStateOf(store?.read() ?: FilesMoveTargetMemory()) }

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

private class PickerCreatedFolders {
    private val folders = mutableListOf<FilesItem>()

    fun add(folder: FilesItem) {
        folders += folder
    }

    fun invalidate(onEvent: (FilesBrowserEvent) -> Boolean) {
        folders.forEach { onEvent(FilesBrowserEvent.InvalidateRestoredItem(it)) }
        folders.clear()
    }
}
