package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROUTE
import io.putdotio.android.account.MobileInactiveAccountNotice
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsSelection
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MobileDownloadsTopBar
import io.putdotio.android.downloads.rememberDownloadsSelection
import io.putdotio.android.downloads.toFilesItem
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesExternalOpen
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.MobileContinueWatching
import io.putdotio.android.files.MobileFilesBarActions
import io.putdotio.android.files.MobileFilesSearchBar
import io.putdotio.android.files.MobileFolderTopBar
import io.putdotio.android.files.MobileNewFolderHost
import io.putdotio.android.files.rememberNewFolderRequest
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.files.pendingDelete
import io.putdotio.android.files.pendingMove
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryReducer
import io.putdotio.android.history.HistoryState
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobileNowPlayingBar
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.rememberNowPlaying
import io.putdotio.android.search.MobileHistoryOverflow
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchReducer
import io.putdotio.android.search.SearchScope
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.ConfirmedDefaultSort
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.confirmedDefaultSort
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINKS_ROUTE
import io.putdotio.android.sharing.MobilePublicLinks
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransferDropZone
import io.putdotio.android.transfers.MobileTransfersOverflow
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferNotice
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersReducer
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.trash.MOBILE_TRASH_ROUTE
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

internal const val MOBILE_NAV_BAR_TAG = "mobile-navigation-bar"

internal const val MOBILE_NAV_RAIL_TAG = "mobile-navigation-rail"

internal const val MOBILE_FILES_SEARCH_TAG = "mobile-files-search"

@Composable
internal fun MobileShell(
    filesState: FilesBrowserState,
    filesRepository: FilesRepository? = null,
    trashController: TrashController? = null,
    downloadsController: DownloadsController? = null,
    publicLinksController: PublicLinksController? = null,
    accountSettingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    searchHistoryState: MobileSearchHistoryState = emptySearchHistoryState(),
    transfersState: TransfersState = emptyTransfersState(),
    transfersSessionId: MobileAuthSessionId? = null,
    account: MobileAccount,
    playbackRepository: PlaybackRepository,
    playbackPlayerFactory: MobilePlayerFactory = DefaultMobilePlayerFactory,
    sessionId: MobileAuthSessionId,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onAccountSettingsEvent: (AccountSettingsEvent) -> Unit,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Putio(
                PutioFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
            ),
        )
    },
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit = {},
    onPlaybackAuthenticationRequired: suspend () -> Unit,
    onFilesAuthenticationRequired: suspend () -> Unit = {},
    searchHistoryActions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    onTransfersEvent: (TransfersEvent) -> Unit = {},
    resolveTransferFile: suspend (TransferFileId) -> PutioResult<FilesItem> = {
        PutioResult.Failure(PutioFailure.Unexpected(IllegalStateException("No transfer file resolver")))
    },
    onTransferAuthenticationRequired: suspend () -> Unit = {},
    contentNavigation: Flow<FilesExternalOpen> = emptyFlow(),
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
    deepLinkRequests: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
    onOpenFile: suspend (FilesItemId) -> Unit = {},
    onShareItem: ((FilesItem) -> Unit)? = null,
    navigationFailure: FilesFailure? = null,
    onDismissNavigationFailure: () -> Unit = {},
    onSignOut: () -> Unit,
    transferDraft: MobileTransferDraft = remember { MobileTransferDraft() },
    fileDragOut: MobileFileDragOut? = null,
    folderCreator: FilesFolderCreator? = null,
    continueWatching: MobileContinueWatching? = null,
) {
    val navController = rememberNavController()
    var rejectedNavigation by remember(sessionId) { mutableStateOf<FilesFailure?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = MobileDestination.fromRoute(backStackEntry?.destination?.route)
    val incomingDraft by transferDraft.state.collectAsStateWithLifecycle()
    val nowPlayingPending by nowPlayingRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(
        transferDraft,
        transfersSessionId,
        transfersState.mutation,
        transfersState.lastSuccessfulAddRequestId,
    ) {
        transferDraft.reconcileSession(transfersSessionId)
        transferDraft.reconcileTransfers(transfersState)
    }
    val isPlayback = backStackEntry?.destination?.route == MOBILE_PLAYBACK_ROUTE
    val isTrash = backStackEntry?.destination?.route == MOBILE_TRASH_ROUTE
    // Search opens over Files and stays open across tab switches, like a folder does.
    var searchOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    // Which way Search last moved: forward when opened or left for a result, back on a Back.
    var searchForward by remember { mutableStateOf(true) }
    val currentSearchActions by rememberUpdatedState(searchHistoryActions)
    // A folder's search stays inside it; the root's pill, Ctrl+F and links search everything.
    val showSearch = {
        navController.navigateTo(MobileDestination.Files)
        // Tab restoration can bring back playback above Files; Search opens on Files itself.
        navController.popBackStack(MobileDestination.Files.route, inclusive = false)
        searchForward = true
        searchOpen = true
    }
    val openScopedSearch = { scope: SearchScope? ->
        currentSearchActions.onScopeChanged(scope)
        showSearch()
    }
    val openSearch = { openScopedSearch(null) }
    // The folder New folder was asked for; the dialog shows while that folder is on screen.
    val newFolder = rememberNewFolderRequest(sessionId)
    val requestNewFolder = newFolder.action(folderCreator, filesState.current)
    val leaveSearch = { forward: Boolean ->
        searchForward = forward
        searchOpen = false
    }
    val publicLinks = publicLinksController?.let { controller ->
        val state = controller.state.collectAsStateWithLifecycle().value
        remember(controller, state) { MobilePublicLinks(state, controller::dispatch) }
    }
    val downloadsState = downloadsController?.state?.collectAsStateWithLifecycle()?.value ?: DownloadsState()
    // The Downloads top bar and list share one selection: in selection its bar replaces the title.
    val downloadsSelection = rememberDownloadsSelection()
    val trashState = trashController?.state?.collectAsStateWithLifecycle()?.value
    TrashRestoreEffects(trashState, onFilesEvent)
    val shareNavigationBlocked = shareNavigationBlocked(filesState, trashState, transfersState, nowPlayingPending)
    DeepLinkNavigationEffect(
        deepLinkRequests = deepLinkRequests,
        userId = account.userId,
        navigationReady = backStackEntry != null,
        navigationBlocked = shareNavigationBlocked,
        navController = navController,
        targets = DeepLinkTargets(
            openFile = onOpenFile,
            openDownload = { fileId, play -> navController.openDownload(downloadsController, fileId, play) },
            addTransfer = transferDraft::open,
            openSearch = openSearch,
            closeSearch = { leaveSearch(true) },
        ),
    )
    IncomingTransferNavigationEffect(
        incomingRequestId = incomingDraft.incomingRequestId,
        backStackEntry = backStackEntry,
        navigationBlocked = shareNavigationBlocked,
        navController = navController,
        transferDraft = transferDraft,
    )
    DefaultSortInvalidationEffect(sessionId, accountSettingsState, onFilesEvent)
    StaleFilesReloadEffect(selectedDestination, isTrash, filesState, onFilesEvent)

    // The NavHost owns Back between routes, the player's included, with its predictive preview.
    var searchFocusPending by remember { mutableStateOf(false) }
    MobileShellKeyboardEffect(
        searchEnabled = !isPlayback,
        onSearch = {
            openSearch()
            searchFocusPending = true
        },
    )

    val playbackFileId = if (isPlayback) backStackEntry?.arguments?.getLong("fileId") else null
    val resolvingTransfer = transfersState.navigation as? TransferNavigation.Resolving
    NowPlayingNavigationEffect(nowPlayingRequests, playbackPlayerFactory, transferDraft, navController, playbackFileId)
    ContentNavigationEffect(
        contentNavigation = contentNavigation,
        transferDraft = transferDraft,
        navController = navController,
        playbackFileId = playbackFileId,
        onFilesEvent = onFilesEvent,
        onFilesOpened = { leaveSearch(true) },
        onNavigationRejected = { rejectedNavigation = FilesFailure.NavigationBlocked },
    )
    TransferFileNavigationEffect(
        resolving = resolvingTransfer,
        transfersSessionId = transfersSessionId,
        navController = navController,
        onFilesEvent = onFilesEvent,
        onFilesOpened = { leaveSearch(true) },
        onTransfersEvent = onTransfersEvent,
        resolveTransferFile = resolveTransferFile,
        onTransferAuthenticationRequired = onTransferAuthenticationRequired,
    )

    val filesBack = filesBack(
        filesState = filesState,
        onFiles = selectedDestination == MobileDestination.Files,
        closeSearch = if (searchOpen) ({ leaveSearch(false) }) else null,
        onFilesEvent = onFilesEvent,
        returnTo = { origin ->
            // Back to the search a result came from, scope and query as they were.
            navController.returnTo(origin) {
                showSearch()
                searchForward = false
            }
        },
    )
    val onFilesBack = filesBack.onBack
    val filesBackPreview = rememberMobileBackPreview()
    MobilePredictiveBackHandler(
        enabled = !isPlayback && filesBack.ownsBack,
        preview = filesBackPreview.takeIf { filesBack.previewable },
        onBack = onFilesBack,
    )
    TrashRecoveryBackHandler(
        enabled = !isPlayback && trashState?.hasPendingMutation == true && !filesBack.ownsBack,
        navController = navController,
    )

    // One NavHost call site in one slot: playback only hides the chrome, so destinations keep saved state.
    key(transfersSessionId) {
        MobileTransferDropZone(
            enabled = !isPlayback,
            draft = transferDraft,
            onOwnDragEnded = { drag, dropped -> fileDragOut?.ended(drag, dropped) },
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val navigationLayout = mobileNavigationLayout(maxWidth, maxHeight)
                MobileNavigationContainer(
                    layout = navigationLayout,
                    selectedDestination = selectedDestination,
                    onDestination = { navController.navigateTo(it) },
                    enabled = !isPlayback,
                ) { openNavigation ->
                    MobileChrome(
                        visible = !isPlayback,
                        layout = navigationLayout,
                        navController = navController,
                        selectedDestination = selectedDestination,
                        playbackPlayerFactory = playbackPlayerFactory,
                        inactiveNotice = account.inactiveNotice,
                        topBar = {
                            MobileTopBar(
                                destination = selectedDestination,
                                route = backStackEntry?.destination?.route,
                                searchOpen = searchOpen,
                                searchScope = searchHistoryState.search.scope,
                                onOpenSearch = openScopedSearch,
                                onSubpageBack = { navController.popBackStack() },
                                filesState = filesState,
                                onFilesBack = onFilesBack,
                                onFilesEvent = onFilesEvent,
                                onNewFolder = requestNewFolder,
                                openNavigation = openNavigation,
                                summary = tabSummary(selectedDestination, transfersState, downloadsState),
                                tabActions = tabActions(
                                    selectedDestination,
                                    transfersState,
                                    transfersSessionId,
                                    onTransfersEvent,
                                ) ?: historyActions(
                                    selectedDestination,
                                    searchHistoryState.history,
                                    searchHistoryActions.onHistoryEvent,
                                ),
                                downloads = downloadsController?.let {
                                    MobileDownloadsBar(downloadsState, downloadsSelection, it::dispatch)
                                },
                            )
                        },
                    ) { contentModifier ->
                        MobileNavHost(
                            transferDraft = transferDraft,
                            navController = navController,
                            filesState = filesState,
                            filesRepository = filesRepository,
                            trashController = trashController,
                            downloadsController = downloadsController,
                            downloadsState = downloadsState,
                            downloadsSelection = downloadsSelection,
                            onShareItem = onShareItem,
                            accountSettingsState = accountSettingsState,
                            appConfigState = appConfigState,
                            searchHistoryState = searchHistoryState,
                            transfersState = transfersState,
                            transfersSessionId = transfersSessionId,
                            account = account,
                            playbackRepository = playbackRepository,
                            playbackPlayerFactory = playbackPlayerFactory,
                            sessionId = sessionId,
                            onFilesEvent = onFilesEvent,
                            onAccountSettingsEvent = onAccountSettingsEvent,
                            loadTunnelRoutes = loadTunnelRoutes,
                            deviceClass = if (maxWidth >= 600.dp) AppDiagnostics.DeviceClass.Tablet
                                else AppDiagnostics.DeviceClass.Phone,
                            onAppConfigEvent = onAppConfigEvent,
                            onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
                            onFilesAuthenticationRequired = onFilesAuthenticationRequired,
                            searchHistoryActions = searchHistoryActions,
                            onTransfersEvent = onTransfersEvent,
                            onSignOut = onSignOut,
                            modifier = contentModifier,
                            publicLinks = publicLinks,
                            searchOpen = searchOpen,
                            searchForward = searchForward,
                            filesBackPreview = filesBackPreview,
                            focusSearch = searchFocusPending,
                            onSearchFocused = { searchFocusPending = false },
                            fileDragOut = fileDragOut,
                            continueWatching = continueWatching,
                            onNewFolder = requestNewFolder,
                        )
                    }
                }
            }
        }
    }
    MobileNewFolderHost(
        request = newFolder,
        folder = filesState.current.folder,
        playing = isPlayback,
        onFiles = selectedDestination == MobileDestination.Files,
        searchOpen = searchOpen,
        creator = folderCreator,
        onCreated = {
            // Stale first, so a folder busy with another operation reloads once it settles; the
            // refresh reloads now when it can, after a failed refresh included.
            onFilesEvent(FilesBrowserEvent.InvalidateRestoredItem(it))
            onFilesEvent(FilesBrowserEvent.Refresh)
        },
        onAuthenticationRequired = onFilesAuthenticationRequired,
    )
    if (!isPlayback) {
        MobileNavigationAlerts(
            navigationFailure = rejectedNavigation ?: navigationFailure,
            transfersState = transfersState,
            onDismissNavigationFailure = {
                if (rejectedNavigation != null) rejectedNavigation = null else onDismissNavigationFailure()
            },
            onTransfersEvent = onTransfersEvent,
        )
    }
}

/**
 * Backspace and Escape go up a folder or back wherever no control used them, through the same Back
 * the system gesture takes; with nothing left to close they do nothing rather than leave the app.
 * Ctrl+F opens Search and puts the cursor in its field.
 */
@Composable
private fun MobileShellKeyboardEffect(searchEnabled: Boolean, onSearch: () -> Unit) {
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    MobileUnhandledKeyEffect(MobileShortcutScope.Files) { command ->
        when (command) {
            MobileKeyCommand.Back -> {
                backDispatcher?.takeIf { it.hasEnabledCallbacks() }?.onBackPressed()
                true
            }
            MobileKeyCommand.Search -> searchEnabled.also { if (it) onSearch() }
            else -> false
        }
    }
}

@Composable
private fun TrashRestoreEffects(
    trashState: TrashState?,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
) {
    LaunchedEffect(trashState?.restoredVersion) {
        trashState?.lastRestoredItem?.let { item ->
            onFilesEvent(FilesBrowserEvent.InvalidateRestoredItem(item))
        }
    }
    LaunchedEffect(trashState?.bulkRestoreVersion) {
        if ((trashState?.bulkRestoreVersion ?: 0L) > 0L) {
            onFilesEvent(FilesBrowserEvent.InvalidateAllFolders)
        }
    }
}

private fun shareNavigationBlocked(
    filesState: FilesBrowserState,
    trashState: TrashState?,
    transfersState: TransfersState,
    nowPlayingPending: Boolean,
): Boolean = filesState.stack.any {
    it.operation.pendingDelete != null || it.operation.pendingMove != null
} || trashState?.hasPendingMutation == true || transfersState.navigation is TransferNavigation.Resolving ||
    transfersState.mutation is TransferMutation.Running || nowPlayingPending

/** What a routed link opens beyond plain navigation. */
private class DeepLinkTargets(
    val openFile: suspend (FilesItemId) -> Unit,
    val openDownload: (FilesItemId, play: Boolean) -> Unit,
    val addTransfer: () -> Unit,
    val openSearch: () -> Unit,
    val closeSearch: () -> Unit,
)

@Composable
private fun DeepLinkNavigationEffect(
    deepLinkRequests: MobileDeepLinkRequests,
    userId: Long,
    navigationReady: Boolean,
    navigationBlocked: Boolean,
    navController: NavHostController,
    targets: DeepLinkTargets,
) {
    val pendingDeepLink by deepLinkRequests.pending.collectAsStateWithLifecycle()
    // Keyed on readiness rather than the entry so the navigation a link causes cannot restart it.
    LaunchedEffect(pendingDeepLink, navigationReady, navigationBlocked, userId) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (!navigationReady || navigationBlocked) return@LaunchedEffect
        // A newer link restarts this effect and cancels an unfinished file resolve. One scoped to
        // another account, such as an earlier account's notification, is dropped unopened.
        if (link.belongsTo(userId)) navController.openDeepLink(link, targets)
        deepLinkRequests.acknowledge(link)
    }
}

private suspend fun NavHostController.openDeepLink(link: MobileDeepLink, targets: DeepLinkTargets) {
    when (link) {
        MobileDeepLink.Files -> {
            navigateTo(MobileDestination.Files)
            targets.closeSearch()
        }
        is MobileDeepLink.File -> {
            navigateTo(MobileDestination.Files)
            targets.closeSearch()
            targets.openFile(link.id)
        }
        MobileDeepLink.Transfers -> navigateTo(MobileDestination.Transfers)
        MobileDeepLink.AddTransfer -> {
            navigateTo(MobileDestination.Transfers)
            // Tab restoration can bring back playback above Transfers; the sheet belongs to Transfers.
            popBackStack(MobileDestination.Transfers.route, inclusive = false)
            targets.addTransfer()
        }
        MobileDeepLink.Search -> targets.openSearch()
        MobileDeepLink.History -> {
            navigateTo(MobileDestination.History)
            // Tab restoration can bring back playback above History; the link shows History itself.
            popBackStack(MobileDestination.History.route, inclusive = false)
        }
        MobileDeepLink.Trash -> navigateToTrash()
        is MobileDeepLink.Downloads -> {
            navigateTo(MobileDestination.Downloads)
            // Tab restoration can bring back playback above Downloads; the row belongs to Downloads.
            popBackStack(MobileDestination.Downloads.route, inclusive = false)
            link.fileId?.let { targets.openDownload(it, link.play) }
        }
    }
}

/** Plays a copy on this device over Downloads when asked to and it is there; otherwise opens its row. */
private fun NavHostController.openDownload(controller: DownloadsController?, fileId: FilesItemId, play: Boolean) {
    val state = controller?.state?.value ?: return
    val entry = state.entry(fileId)?.takeIf { play && state.isAvailableOffline(fileId) }
    if (entry != null) navigateToPlayback(entry.toFilesItem()) else controller.dispatch(DownloadsEvent.Focus(fileId))
}

@Composable
private fun IncomingTransferNavigationEffect(
    incomingRequestId: Long?,
    backStackEntry: NavBackStackEntry?,
    navigationBlocked: Boolean,
    navController: NavHostController,
    transferDraft: MobileTransferDraft,
) {
    LaunchedEffect(incomingRequestId, backStackEntry, navigationBlocked) {
        val requestId = incomingRequestId ?: return@LaunchedEffect
        if (backStackEntry == null || navigationBlocked) return@LaunchedEffect
        navController.navigateTo(MobileDestination.Transfers)
        // Tab restoration can bring back playback above Transfers; a share must reach its editor.
        navController.popBackStack(MobileDestination.Transfers.route, inclusive = false)
        transferDraft.acknowledgeNavigation(requestId)
    }
}

/**
 * Folders without their own sort inherit the account default, so a change the server
 * accepted makes every loaded listing stale. The first settled value is the baseline.
 */
@Composable
private fun DefaultSortInvalidationEffect(
    sessionId: MobileAuthSessionId,
    accountSettingsState: AccountSettingsState,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
) {
    val confirmedDefaultSort = accountSettingsState.confirmedDefaultSort()
    var knownDefaultSort by remember(sessionId) { mutableStateOf<ConfirmedDefaultSort?>(null) }
    LaunchedEffect(confirmedDefaultSort) {
        if (confirmedDefaultSort == null) return@LaunchedEffect
        if (knownDefaultSort != null && knownDefaultSort != confirmedDefaultSort) {
            onFilesEvent(FilesBrowserEvent.InvalidateSortOrder)
        }
        knownDefaultSort = confirmedDefaultSort
    }
}

@Composable
private fun StaleFilesReloadEffect(
    selectedDestination: MobileDestination,
    isTrash: Boolean,
    filesState: FilesBrowserState,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
) {
    LaunchedEffect(selectedDestination, isTrash, filesState.current.folder.id,
        filesState.current.needsReload, filesState.current.operation,
        filesState.current.content is FilesContent.Loading) {
        if (selectedDestination == MobileDestination.Files && !isTrash && filesState.current.needsReload) {
            onFilesEvent(FilesBrowserEvent.ReloadIfStale)
        }
    }
}

@Composable
private fun NowPlayingNavigationEffect(
    nowPlayingRequests: NowPlayingRequests,
    playbackPlayerFactory: MobilePlayerFactory,
    transferDraft: MobileTransferDraft,
    navController: NavHostController,
    playbackFileId: Long?,
) {
    // The collector outlives any one Activity, so it must not hold one.
    val appContext = LocalContext.current.applicationContext
    val currentPlaybackFileId by rememberUpdatedState(playbackFileId)
    LaunchedEffect(nowPlayingRequests, playbackPlayerFactory, appContext, transferDraft) {
        nowPlayingRequests.pending.collect { pending ->
            if (!pending) return@collect
            val target = playbackPlayerFactory.activeAudio(appContext)
            // Acknowledged only once the lookup finished: a cancelled lookup leaves it pending.
            nowPlayingRequests.acknowledge()
            if (target == null || transferDraft.state.value.incomingRequestId != null) return@collect
            val onPlaybackRoute = currentPlaybackFileId
            if (onPlaybackRoute == target.fileId.value) return@collect
            // A different item's route, still loading or failed, gives no controls for the live audio.
            navController.navigateToPlayback(
                target.fileId,
                target.title,
                PlaybackMediaType.AUDIO,
                replaceCurrentPlayback = onPlaybackRoute != null,
            )
        }
    }
}

@Composable
private fun ContentNavigationEffect(
    contentNavigation: Flow<FilesExternalOpen>,
    transferDraft: MobileTransferDraft,
    navController: NavHostController,
    playbackFileId: Long?,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onFilesOpened: () -> Unit,
    onNavigationRejected: () -> Unit,
) {
    val currentOnFilesEvent by rememberUpdatedState(onFilesEvent)
    val currentPlaybackFileId by rememberUpdatedState(playbackFileId)
    // Media plays above the screen it was chosen on; anything else opens in Files above its prior location.
    LaunchedEffect(contentNavigation, transferDraft) {
        contentNavigation.collect { (item, origin) ->
            val draft = transferDraft.state.value
            val editingTransfer = navController.currentDestination?.route == MobileDestination.Transfers.route &&
                (draft.open || draft.pendingReplacement)
            if (item.isPlayable) {
                // A delayed history result must not displace a newer transfer draft. A newer pick
                // replaces the player, so Back still returns to the screen below it.
                if (!editingTransfer) {
                    navController.navigateToPlayback(item, replaceCurrentPlayback = currentPlaybackFileId != null)
                }
            } else if (currentOnFilesEvent(FilesBrowserEvent.OpenExternalItem(item, origin))) {
                // A delayed history result may update Files without displacing a newer transfer draft.
                if (!editingTransfer) {
                    navController.navigateTo(MobileDestination.Files)
                    onFilesOpened()
                }
            } else {
                onNavigationRejected()
            }
        }
    }
}

@Composable
private fun TransferFileNavigationEffect(
    resolving: TransferNavigation.Resolving?,
    transfersSessionId: MobileAuthSessionId?,
    navController: NavHostController,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onFilesOpened: () -> Unit,
    onTransfersEvent: (TransfersEvent) -> Unit,
    resolveTransferFile: suspend (TransferFileId) -> PutioResult<FilesItem>,
    onTransferAuthenticationRequired: suspend () -> Unit,
) {
    LaunchedEffect(resolving?.requestId, transfersSessionId) {
        val resolvingTransfer = resolving ?: return@LaunchedEffect
        val resolved = resolveTransferFile(resolvingTransfer.fileId)
        currentCoroutineContext().ensureActive()
        when (resolved) {
            is PutioResult.Success -> {
                navController.currentBackStackEntryFlow.first()
                currentCoroutineContext().ensureActive()
                val open = FilesBrowserEvent.OpenExternalItem(resolved.value, FilesOpenOrigin.TRANSFERS)
                if (onFilesEvent(open)) {
                    navController.navigateTo(MobileDestination.Files)
                    onFilesOpened()
                    onTransfersEvent(TransfersEvent.OpenSucceeded(resolvingTransfer.requestId))
                } else {
                    onTransfersEvent(TransfersEvent.OpenFailed(
                        resolvingTransfer.requestId, FilesFailure.NavigationBlocked,
                    ))
                }
            }
            is PutioResult.Failure ->
                if (resolved.failure is PutioFailure.AuthenticationRequired) {
                    onTransferAuthenticationRequired()
                } else {
                    onTransfersEvent(TransfersEvent.OpenFailed(resolvingTransfer.requestId, resolved.failure))
                }
        }
    }
}

/** Who takes Back on Files, whether the gesture may preview it, and what it does. */
private class FilesBack(val ownsBack: Boolean, val previewable: Boolean, val onBack: () -> Unit)

/**
 * Back closes Search, then leaves a folder; a folder opened from Search, History or Transfers
 * returns there. While a move settles anywhere, Files takes Back and refuses it, so there is
 * nothing to preview.
 */
private fun filesBack(
    filesState: FilesBrowserState,
    onFiles: Boolean,
    closeSearch: (() -> Unit)?,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    returnTo: (FilesOpenOrigin) -> Unit,
): FilesBack {
    val moveSettling = filesState.stack.any { it.operation.pendingMove != null }
    // Closing Search leaves the folder alone, so it stays allowed while a move settles; Search would
    // otherwise hide the folder's Check status.
    val searchOwnsBack = closeSearch != null && onFiles
    val ownsBack = searchOwnsBack || moveSettling || onFiles && filesState.canNavigateBack
    // The reducer refuses to leave a folder while a move or its deletion settles: no preview then.
    val refused = !searchOwnsBack && (moveSettling || filesState.current.operation.pendingDelete != null)
    return FilesBack(ownsBack, previewable = ownsBack && !refused) {
        if (searchOwnsBack) {
            closeSearch?.invoke()
        } else {
            val origin = filesState.current.openedFrom
            if (onFilesEvent(FilesBrowserEvent.NavigateBack)) origin?.let(returnTo)
        }
    }
}

private fun NavHostController.returnTo(origin: FilesOpenOrigin, openSearch: () -> Unit) {
    when (origin) {
        FilesOpenOrigin.SEARCH -> openSearch()
        FilesOpenOrigin.HISTORY -> navigateTo(MobileDestination.History)
        FilesOpenOrigin.TRANSFERS -> navigateTo(MobileDestination.Transfers)
        FilesOpenOrigin.LINK -> Unit
    }
}

/**
 * While a Trash change is pending, a Back that no route or folder takes returns to Trash. The
 * NavHost registers after this handler, so a route it can pop still pops first.
 */
@Composable
private fun TrashRecoveryBackHandler(enabled: Boolean, navController: NavHostController) {
    BackHandler(enabled = enabled) { navController.navigateToTrash() }
}

@Composable
private fun MobileNavigationAlerts(
    navigationFailure: FilesFailure?,
    transfersState: TransfersState,
    onDismissNavigationFailure: () -> Unit,
    onTransfersEvent: (TransfersEvent) -> Unit,
) {
    if (navigationFailure != null && navigationFailure !is PutioFailure.AuthenticationRequired) {
        AlertDialog(
            onDismissRequest = onDismissNavigationFailure,
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(navigationFailure.mobileMessage()) },
            confirmButton = {
                TextButton(onClick = onDismissNavigationFailure) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
    (transfersState.navigation as? TransferNavigation.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = { onTransfersEvent(TransfersEvent.DismissNavigationFailure) },
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(failed.failure.mobileMessage()) },
            confirmButton = {
                TextButton(
                    onClick = { onTransfersEvent(TransfersEvent.DismissNavigationFailure) },
                ) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
    transfersState.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { onTransfersEvent(TransfersEvent.DismissNotice(notice.requestId)) },
            title = { Text(stringResource(R.string.mobile_transfers_open_unavailable_title)) },
            text = {
                Text(
                    stringResource(
                        when (notice) {
                            is TransferNotice.FilePreparing -> R.string.mobile_transfers_file_preparing
                            is TransferNotice.FileUnavailable -> R.string.mobile_transfers_file_unavailable
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onTransfersEvent(TransfersEvent.DismissNotice(notice.requestId)) },
                ) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
}

/**
 * The navigation suite, top bar and now-playing bar around one content slot; hiding them never
 * moves it. Below 600dp the suite is a navigation bar, from 600dp a rail, and in the drawer layout
 * none, since the modal drawer outside this slot takes its place.
 */
@Composable
private fun MobileChrome(
    visible: Boolean,
    layout: MobileNavigationLayout,
    navController: NavHostController,
    selectedDestination: MobileDestination,
    playbackPlayerFactory: MobilePlayerFactory,
    inactiveNotice: InactiveAccountNotice?,
    topBar: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val suiteType = when {
        !visible -> NavigationSuiteType.None
        layout == MobileNavigationLayout.Bar -> NavigationSuiteType.NavigationBar
        layout == MobileNavigationLayout.Rail -> NavigationSuiteType.WideNavigationRailCollapsed
        else -> NavigationSuiteType.None
    }
    NavigationSuiteScaffoldLayout(
        navigationSuite = {
            if (suiteType != NavigationSuiteType.None) {
                val container = MaterialTheme.colorScheme.surfaceContainerLow
                NavigationSuite(
                    navigationSuiteType = suiteType,
                    modifier = Modifier.testTag(
                        if (suiteType == NavigationSuiteType.NavigationBar) MOBILE_NAV_BAR_TAG else MOBILE_NAV_RAIL_TAG,
                    ),
                    colors = NavigationSuiteDefaults.colors(
                        wideNavigationRailColors = WideNavigationRailDefaults.colors(containerColor = container),
                        navigationBarContainerColor = container,
                    ),
                ) {
                    MobileDestination.entries.forEach { destination ->
                        val selected = destination == selectedDestination
                        NavigationSuiteItem(
                            selected = selected,
                            onClick = { navController.navigateTo(destination) },
                            icon = { MobileDestinationIcon(destination, selected) },
                            label = { Text(stringResource(destination.labelRes)) },
                            navigationSuiteType = suiteType,
                        )
                    }
                }
            }
        },
        navigationSuiteType = suiteType,
    ) {
        Scaffold(
            // The suite pads itself for the system bars it sits against; the slot must not again.
            modifier = Modifier.consumeWindowInsets(navigationSuiteInsets(suiteType)),
            topBar = {
                if (visible) {
                    Column {
                        topBar()
                        // Every signed-in screen, like web's app layout; the top bar owns the status bar inset.
                        inactiveNotice?.let { notice ->
                            MobileInactiveAccountNotice(
                                notice,
                                Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                                ),
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (visible) {
                    // The bar pads its own content; above it the now-playing bar needs only the side insets.
                    MobileNowPlayingSlot(
                        navController,
                        playbackPlayerFactory,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                            if (suiteType == NavigationSuiteType.NavigationBar) WindowInsetsSides.Horizontal
                            else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        )),
                    )
                }
            },
        ) { padding ->
            // Playback draws edge to edge and handles its own insets.
            content(if (visible) Modifier.padding(padding) else Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun navigationSuiteInsets(type: NavigationSuiteType): WindowInsets =
    when (type) {
        NavigationSuiteType.NavigationBar -> NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Bottom)
        NavigationSuiteType.WideNavigationRailCollapsed ->
            WideNavigationRailDefaults.windowInsets.only(WindowInsetsSides.Start)
        else -> WindowInsets(0)
    }

/**
 * The Files root has a search app bar and a folder a small top app bar with back, its name,
 * search in it, sort and overflow. Search over Files has back and its title. The other tabs open
 * on a [MobileTabTitle]; their subpages keep a small bar with back.
 */
@Composable
private fun MobileTopBar(
    destination: MobileDestination,
    route: String?,
    searchOpen: Boolean,
    searchScope: SearchScope?,
    onOpenSearch: (SearchScope?) -> Unit,
    onSubpageBack: () -> Unit,
    filesState: FilesBrowserState,
    onFilesBack: () -> Unit,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onNewFolder: (() -> Unit)?,
    openNavigation: (() -> Unit)?,
    summary: AnnotatedString?,
    tabActions: (@Composable RowScope.() -> Unit)? = null,
    downloads: MobileDownloadsBar? = null,
) {
    val subpageTitle = accountSubpageTitle(route)
    when {
        subpageTitle != null -> MobileSmallTopBar(
            title = stringResource(subpageTitle),
            onBack = onSubpageBack,
            openNavigation = openNavigation,
        )
        destination == MobileDestination.Downloads && downloads != null -> MobileDownloadsTopBar(
            title = stringResource(destination.labelRes),
            summary = summary,
            state = downloads.state,
            selection = downloads.selection,
            onEvent = downloads.onEvent,
        ) {
            openNavigation?.let { MobileNavigationMenuButton(it) }
        }
        destination != MobileDestination.Files -> MobileTabTitle(
            title = stringResource(destination.labelRes),
            summary = summary,
            hasActions = openNavigation != null || tabActions != null,
        ) {
            openNavigation?.let { MobileNavigationMenuButton(it) }
            tabActions?.invoke(this)
        }
        searchOpen -> MobileSmallTopBar(
            title = searchScope?.folderName?.let { stringResource(R.string.mobile_files_search_in, it) }
                ?: stringResource(R.string.mobile_search_title),
            onBack = onFilesBack,
            openNavigation = openNavigation,
        )
        else -> MobileFilesTopBar(filesState, onFilesBack, onFilesEvent, onOpenSearch, onNewFolder, openNavigation)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileSmallTopBar(title: String, onBack: () -> Unit, openNavigation: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { MobileBackButton(onClick = onBack) },
        actions = { openNavigation?.let { MobileNavigationMenuButton(it) } },
    )
}

/**
 * The search app bar at the Files root, a folder's small bar elsewhere. A folder opened from
 * outside Files sits on top of the root too, so the stack decides, not the folder ID.
 */
@Composable
private fun MobileFilesTopBar(
    filesState: FilesBrowserState,
    onFilesBack: () -> Unit,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onOpenSearch: (SearchScope?) -> Unit,
    onNewFolder: (() -> Unit)?,
    openNavigation: (() -> Unit)?,
) {
    val folder = filesState.current.folder
    val atRoot = !filesState.canNavigateBack && folder.id == FilesFolder.Root.id
    val title = folder.name?.takeIf(String::isNotBlank) ?: stringResource(MobileDestination.Files.labelRes)
    val actions = MobileFilesBarActions(
        folder = filesState.current,
        onSelectSort = { onFilesEvent(FilesBrowserEvent.SelectSort(it)) },
        onOpenSearch = {
            onOpenSearch(folder.id.takeIf { it != FilesFolder.Root.id }?.let { SearchScope(it, title) })
        },
        onNewFolder = onNewFolder,
        navigation = openNavigation?.let { { MobileNavigationMenuButton(it) } },
    )
    if (atRoot) {
        MobileFilesSearchBar(actions)
    } else {
        MobileFolderTopBar(
            title = title,
            actions = actions,
            onBack = onFilesBack,
            backEnabled = filesState.canNavigateBack && filesState.current.operation.pendingDelete == null &&
                filesState.stack.none { it.operation.pendingMove != null },
        )
    }
}

@Composable
private fun MobileBackButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_arrow_left),
            contentDescription = stringResource(R.string.mobile_action_back),
        )
    }
}

/** What the Downloads top bar reads: its rows, the selection it shares with the list, and its events. */
private class MobileDownloadsBar(
    val state: DownloadsState,
    val selection: DownloadsSelection,
    val onEvent: (DownloadsEvent) -> Boolean,
)

/** The one-line summary under a tab title, with its numbers in mono. */
@Composable
private fun tabSummary(
    destination: MobileDestination,
    transfersState: TransfersState,
    downloadsState: DownloadsState,
): AnnotatedString? {
    val context = LocalContext.current
    val mono = summaryMonoStyle()
    return when (destination) {
        MobileDestination.Transfers -> summaryOf(transfersSummaryParts(context, transfersState), mono)
        MobileDestination.Downloads -> downloadsSummary(downloadsState, mono)
        MobileDestination.Files, MobileDestination.History, MobileDestination.Account -> null
    }
}

/** A tab's own actions in its title row; Transfers keeps Refresh and Clear completed there. */
private fun tabActions(
    destination: MobileDestination,
    transfersState: TransfersState,
    transfersSessionId: MobileAuthSessionId?,
    onTransfersEvent: (TransfersEvent) -> Unit,
): (@Composable RowScope.() -> Unit)? =
    when (destination) {
        MobileDestination.Transfers -> {
            { MobileTransfersOverflow(transfersState, onTransfersEvent, transfersSessionId) }
        }
        MobileDestination.Files, MobileDestination.History, MobileDestination.Downloads, MobileDestination.Account ->
            null
    }

/** History's overflow holds Clear history, so it shows only over a list to clear. */
private fun historyActions(
    destination: MobileDestination,
    history: HistoryState,
    onHistoryEvent: (HistoryEvent) -> Unit,
): (@Composable RowScope.() -> Unit)? =
    if (destination == MobileDestination.History && history.content is HistoryContent.Ready) {
        { MobileHistoryOverflow(history.clearing, onHistoryEvent) }
    } else {
        null
    }

@StringRes
private fun accountSubpageTitle(route: String?): Int? =
    when (route) {
        MOBILE_TRASH_ROUTE -> R.string.mobile_trash_title
        MOBILE_PUBLIC_LINKS_ROUTE -> R.string.mobile_public_links_manage
        MOBILE_PRIVACY_CONTROLS_ROUTE -> R.string.mobile_settings_privacy_controls
        else -> null
    }

@Composable
private fun MobileNowPlayingSlot(
    navController: NavHostController,
    playerFactory: MobilePlayerFactory,
    modifier: Modifier = Modifier,
) {
    val handle = rememberNowPlaying(playerFactory)
    val nowPlaying = handle.nowPlaying ?: return
    MobileNowPlayingBar(
        modifier = modifier,
        nowPlaying = nowPlaying,
        onOpen = { navController.navigateToPlayback(nowPlaying.fileId, nowPlaying.title, PlaybackMediaType.AUDIO) },
        onToggle = handle::togglePlayback,
        onDismiss = handle::dismiss,
    )
}

@Composable
private fun MobileDestinationIcon(
    destination: MobileDestination,
    selected: Boolean,
) {
    Icon(
        painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
        contentDescription = null,
    )
}

internal data class MobileSearchHistoryState(
    val search: SearchState,
    val history: HistoryState,
    val recentSearchFailure: PutioFailure?,
)

internal data class MobileSearchHistoryActions(
    val onQueryChanged: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onResult: (FilesItem) -> Unit = {},
    val onNextPage: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRecentSearch: (SearchTerm) -> Unit = {},
    val onRecentEdit: (RecentSearchEdit) -> Unit = {},
    val onRecentRetry: () -> Unit = {},
    val onHistoryEvent: (HistoryEvent) -> Unit = {},
    val onScopeChanged: (SearchScope?) -> Unit = {},
)

private fun emptySearchHistoryState(): MobileSearchHistoryState =
    MobileSearchHistoryState(
        search = SearchReducer.start(),
        history = HistoryReducer.start(historyEnabled = false).state,
        recentSearchFailure = null,
    )

private fun emptyTransfersState(): TransfersState = TransfersReducer.start().state
