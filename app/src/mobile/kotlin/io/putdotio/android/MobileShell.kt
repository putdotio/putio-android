package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.MobileFilesSortMenu
import io.putdotio.android.files.mobileMessageResource
import io.putdotio.android.files.pendingDelete
import io.putdotio.android.files.pendingMove
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobileNowPlayingBar
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.rememberNowPlaying
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchContent
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
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferNotice
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.trash.TrashController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first

internal const val MOBILE_NAV_BAR_TAG = "mobile-navigation-bar"

internal const val MOBILE_NAV_RAIL_TAG = "mobile-navigation-rail"

@Composable
internal fun MobileShell(
    filesState: FilesBrowserState,
    filesRepository: FilesRepository? = null,
    trashController: TrashController? = null,
    downloadsController: DownloadsController? = null,
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
            AccountSettingsFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
        )
    },
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit = {},
    onPlaybackAuthenticationRequired: suspend () -> Unit,
    onFilesAuthenticationRequired: suspend () -> Unit = {},
    searchHistoryActions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    onTransfersEvent: (TransfersEvent) -> Unit = {},
    resolveTransferFile: suspend (TransferFileId) -> FilesRepositoryResult<FilesItem> = {
        FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("No transfer file resolver")))
    },
    onTransferAuthenticationRequired: suspend () -> Unit = {},
    contentNavigation: Flow<FilesItem> = emptyFlow(),
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
    deepLinkRequests: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
    onOpenFile: suspend (FilesItemId) -> Unit = {},
    onShareItem: ((FilesItem) -> Unit)? = null,
    navigationFailure: FilesFailure? = null,
    onDismissNavigationFailure: () -> Unit = {},
    onSignOut: () -> Unit,
    transferDraft: MobileTransferDraft = remember { MobileTransferDraft() },
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
    val isDownloads = backStackEntry?.destination?.route == MOBILE_DOWNLOADS_ROUTE
    val downloadsState = downloadsController?.state?.collectAsStateWithLifecycle()?.value ?: DownloadsState()
    val trashState = trashController?.state?.collectAsStateWithLifecycle()?.value
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
    val shareNavigationBlocked = filesState.stack.any {
        it.operation.pendingDelete != null || it.operation.pendingMove != null
    } || trashState?.hasPendingMutation == true || transfersState.navigation is TransferNavigation.Resolving ||
        transfersState.mutation is TransferMutation.Running || nowPlayingPending
    val pendingDeepLink by deepLinkRequests.pending.collectAsStateWithLifecycle()
    val navigationReady = backStackEntry != null
    // Keyed on readiness rather than the entry so the navigation a link causes cannot restart it.
    LaunchedEffect(pendingDeepLink, navigationReady, shareNavigationBlocked) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (!navigationReady || shareNavigationBlocked) return@LaunchedEffect
        // A newer link restarts this effect and cancels an unfinished file resolve.
        when (link) {
            MobileDeepLink.Files -> navController.navigateTo(MobileDestination.Files)
            is MobileDeepLink.File -> {
                navController.navigateTo(MobileDestination.Files)
                onOpenFile(link.id)
            }
            MobileDeepLink.Transfers -> navController.navigateTo(MobileDestination.Transfers)
            MobileDeepLink.Search, MobileDeepLink.History -> navController.navigateTo(MobileDestination.Search)
            MobileDeepLink.Trash -> {
                navController.navigateTo(MobileDestination.Account)
                navController.navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true }
            }
            MobileDeepLink.Downloads -> {
                navController.navigateTo(MobileDestination.Account)
                navController.navigate(MOBILE_DOWNLOADS_ROUTE) { launchSingleTop = true }
            }
        }
        deepLinkRequests.acknowledge(link)
    }
    LaunchedEffect(incomingDraft.incomingRequestId, backStackEntry, shareNavigationBlocked) {
        val requestId = incomingDraft.incomingRequestId ?: return@LaunchedEffect
        if (backStackEntry == null || shareNavigationBlocked) return@LaunchedEffect
        navController.navigateTo(MobileDestination.Transfers)
        // Tab restoration can bring back playback above Transfers; a share must reach its editor.
        navController.popBackStack(MobileDestination.Transfers.route, inclusive = false)
        transferDraft.acknowledgeNavigation(requestId)
    }
    // Folders without their own sort inherit the account default, so a change the server
    // accepted makes every loaded listing stale. The first settled value is the baseline.
    val confirmedDefaultSort = accountSettingsState.confirmedDefaultSort()
    var knownDefaultSort by remember(sessionId) { mutableStateOf<ConfirmedDefaultSort?>(null) }
    LaunchedEffect(confirmedDefaultSort) {
        if (confirmedDefaultSort == null) return@LaunchedEffect
        if (knownDefaultSort != null && knownDefaultSort != confirmedDefaultSort) {
            onFilesEvent(FilesBrowserEvent.InvalidateSortOrder)
        }
        knownDefaultSort = confirmedDefaultSort
    }
    LaunchedEffect(selectedDestination, isTrash, filesState.current.folder.id,
        filesState.current.needsReload, filesState.current.operation,
        filesState.current.content is FilesContent.Loading) {
        if (selectedDestination == MobileDestination.Files && !isTrash && filesState.current.needsReload) {
            onFilesEvent(FilesBrowserEvent.ReloadIfStale)
        }
    }

    BackHandler(enabled = isPlayback) {
        navController.popBackStack()
    }

    val currentOnFilesEvent by rememberUpdatedState(onFilesEvent)
    val resolvingTransfer = transfersState.navigation as? TransferNavigation.Resolving

    // The collector outlives any one Activity, so it must not hold one.
    val appContext = LocalContext.current.applicationContext
    val currentPlaybackFileId by rememberUpdatedState(
        if (isPlayback) backStackEntry?.arguments?.getLong("fileId") else null,
    )
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
    LaunchedEffect(contentNavigation, transferDraft) {
        contentNavigation.collect { item ->
            if (currentOnFilesEvent(FilesBrowserEvent.OpenExternalItem(item))) {
                val draft = transferDraft.state.value
                val editingTransfer = navController.currentDestination?.route == MobileDestination.Transfers.route &&
                    (draft.open || draft.pendingReplacement)
                // A delayed history result may update Files without displacing a newer transfer draft.
                if (!editingTransfer) navController.navigateTo(MobileDestination.Files)
            } else {
                rejectedNavigation = FilesFailure.NavigationBlocked
            }
        }
    }
    LaunchedEffect(resolvingTransfer?.requestId, transfersSessionId) {
        val resolving = resolvingTransfer ?: return@LaunchedEffect
        val sessionOnFilesEvent = onFilesEvent
        val sessionOnTransfersEvent = onTransfersEvent
        val sessionResolveTransferFile = resolveTransferFile
        val sessionOnTransferAuthenticationRequired = onTransferAuthenticationRequired
        val resolved = sessionResolveTransferFile(resolving.fileId)
        currentCoroutineContext().ensureActive()
        when (resolved) {
            is FilesRepositoryResult.Success -> {
                navController.currentBackStackEntryFlow.first()
                currentCoroutineContext().ensureActive()
                if (sessionOnFilesEvent(FilesBrowserEvent.OpenExternalItem(resolved.value))) {
                    navController.navigateTo(MobileDestination.Files)
                    sessionOnTransfersEvent(TransfersEvent.OpenSucceeded(resolving.requestId))
                } else {
                    sessionOnTransfersEvent(TransfersEvent.OpenFailed(
                        resolving.requestId, FilesFailure.NavigationBlocked,
                    ))
                }
            }
            is FilesRepositoryResult.Failure ->
                if (resolved.failure is FilesFailure.AuthenticationRequired) {
                    sessionOnTransferAuthenticationRequired()
                } else {
                    sessionOnTransfersEvent(TransfersEvent.OpenFailed(resolving.requestId, resolved.failure))
                }
        }
    }

    val filesOwnsBack = filesState.stack.any { it.operation.pendingMove != null } ||
        selectedDestination == MobileDestination.Files && filesState.canNavigateBack
    BackHandler(enabled = !isPlayback && filesOwnsBack) {
        onFilesEvent(FilesBrowserEvent.NavigateBack)
    }

    val protectTrashRecovery = trashState?.hasPendingMutation == true && !filesOwnsBack
    BackHandler(enabled = !isPlayback && (isTrash || isDownloads || protectTrashRecovery)) {
        if (isTrash || isDownloads) {
            navController.popBackStack()
        } else {
            navController.navigateTo(MobileDestination.Account)
            navController.navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true }
        }
    }

    // One NavHost call site in one slot: playback only hides the chrome, so destinations keep saved state.
    key(transfersSessionId) {
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
                    openNavigation = openNavigation,
                    navController = navController,
                    selectedDestination = selectedDestination,
                    filesState = filesState,
                    playbackPlayerFactory = playbackPlayerFactory,
                    onFilesEvent = { onFilesEvent(it) },
                ) { contentModifier ->
                    MobileNavHost(
                        transferDraft = transferDraft,
                        navController = navController,
                        filesState = filesState,
                        filesRepository = filesRepository,
                        trashController = trashController,
                        downloadsController = downloadsController,
                        downloadsState = downloadsState,
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
                        onFilesEvent = { onFilesEvent(it) },
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
                    )
                }
            }
        }
    }
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

@Composable
private fun MobileNavigationAlerts(
    navigationFailure: FilesFailure?,
    transfersState: TransfersState,
    onDismissNavigationFailure: () -> Unit,
    onTransfersEvent: (TransfersEvent) -> Unit,
) {
    if (navigationFailure != null && navigationFailure !is FilesFailure.AuthenticationRequired) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissNavigationFailure,
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(stringResource(navigationFailure.mobileMessageResource())) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismissNavigationFailure) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
    (transfersState.navigation as? TransferNavigation.Failed)?.let { failed ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { onTransfersEvent(TransfersEvent.DismissNavigationFailure) },
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(stringResource(failed.failure.mobileMessageResource())) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { onTransfersEvent(TransfersEvent.DismissNavigationFailure) },
                ) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
    transfersState.notice?.let { notice ->
        androidx.compose.material3.AlertDialog(
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
                androidx.compose.material3.TextButton(
                    onClick = { onTransfersEvent(TransfersEvent.DismissNotice(notice.requestId)) },
                ) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
}

/** Top bar, navigation bar or rail, and now-playing bar around one content slot; hiding them never moves it. */
@Composable
private fun MobileChrome(
    visible: Boolean,
    layout: MobileNavigationLayout,
    openNavigation: (() -> Unit)?,
    navController: NavHostController,
    selectedDestination: MobileDestination,
    filesState: FilesBrowserState,
    playbackPlayerFactory: MobilePlayerFactory,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        if (visible && layout == MobileNavigationLayout.Rail) {
            NavigationRail(modifier = Modifier.testTag(MOBILE_NAV_RAIL_TAG)) {
                MobileDestination.entries.forEach { destination ->
                    NavigationRailItem(
                        selected = destination == selectedDestination,
                        onClick = { navController.navigateTo(destination) },
                        icon = { MobileDestinationIcon(destination, destination == selectedDestination) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                if (visible) {
                    val route = navController.currentBackStackEntryAsState().value?.destination?.route
                    MobileTopBar(
                        openNavigation = openNavigation,
                        destination = selectedDestination,
                        isTrash = route == MOBILE_TRASH_ROUTE,
                        isDownloads = route == MOBILE_DOWNLOADS_ROUTE,
                        onTrashBack = { navController.popBackStack() },
                        filesState = filesState,
                        onFilesBack = { onFilesEvent(FilesBrowserEvent.NavigateBack) },
                        onFilesEvent = onFilesEvent,
                    )
                }
            },
            bottomBar = {
                if (visible) {
                    val showBar = layout == MobileNavigationLayout.Bar
                    Column {
                        // NavigationBar pads its own content; the bar above it needs the side insets only.
                        MobileNowPlayingSlot(
                            navController,
                            playbackPlayerFactory,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(
                                if (showBar) WindowInsetsSides.Horizontal
                                else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            )),
                        )
                        if (showBar) NavigationBar(modifier = Modifier.testTag(MOBILE_NAV_BAR_TAG)) {
                            MobileDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = destination == selectedDestination,
                                    onClick = { navController.navigateTo(destination) },
                                    icon = { MobileDestinationIcon(destination, destination == selectedDestination) },
                                    label = { Text(stringResource(destination.labelRes)) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // Playback draws edge to edge and handles its own insets.
            content(if (visible) Modifier.padding(padding) else Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileTopBar(
    destination: MobileDestination,
    isTrash: Boolean,
    onTrashBack: () -> Unit,
    filesState: FilesBrowserState,
    isDownloads: Boolean = false,
    onFilesBack: () -> Unit,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    openNavigation: (() -> Unit)? = null,
) {
    val filesFolderName = filesState.current.folder.name?.takeIf(String::isNotBlank)
    TopAppBar(
        title = {
            Text(
                if (isTrash) {
                    stringResource(R.string.mobile_trash_title)
                } else if (isDownloads) {
                    stringResource(R.string.mobile_downloads_title)
                } else if (destination == MobileDestination.Files && filesFolderName != null) {
                    filesFolderName
                } else {
                    stringResource(destination.labelRes)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (isTrash || isDownloads) {
                IconButton(onClick = onTrashBack) {
                    Icon(painterResource(R.drawable.ic_ph_arrow_left),
                        contentDescription = stringResource(R.string.mobile_action_back))
                }
            } else if (destination == MobileDestination.Files && filesState.canNavigateBack) {
                IconButton(onClick = onFilesBack, enabled = filesState.current.operation.pendingDelete == null &&
                    filesState.stack.none { it.operation.pendingMove != null }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_arrow_left),
                        contentDescription = stringResource(R.string.mobile_action_back),
                    )
                }
            }
        },
        actions = {
            if (openNavigation != null) {
                MobileNavigationMenuButton(openNavigation)
            }
            if (
                destination == MobileDestination.Files &&
                (filesState.current.content is FilesContent.Empty || filesState.current.content is FilesContent.Ready)
            ) {
                MobileFilesSortMenu(
                    folder = filesState.current,
                    onSelect = { onFilesEvent(FilesBrowserEvent.SelectSort(it)) },
                )
            }
        },
    )
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
    val recentSearchFailure: FilesFailure?,
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
)

private fun emptySearchHistoryState(): MobileSearchHistoryState =
    MobileSearchHistoryState(
        search =
            SearchState(
                query = "",
                content = SearchContent.Idle,
                recentTerms = emptyList(),
                consumedCursors = emptySet(),
                nextRequestValue = 1L,
            ),
        history = HistoryState(HistoryContent.Disabled),
        recentSearchFailure = null,
    )

private fun emptyTransfersState(): TransfersState =
    TransfersState(content = TransfersContent.Empty)
