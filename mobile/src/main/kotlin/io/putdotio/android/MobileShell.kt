package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.MobileInactiveAccountNotice
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.MOBILE_DOWNLOADS_ROUTE
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesExternalOpen
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.MobileFilesSortMenu
import io.putdotio.android.files.mobileMessage
import io.putdotio.android.files.pendingDelete
import io.putdotio.android.files.pendingMove
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryReducer
import io.putdotio.android.history.HistoryState
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobileNowPlayingBar
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.rememberNowPlaying
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SearchReducer
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
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.transfers.MobileTransferDraft
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
    val isPublicLinks = backStackEntry?.destination?.route == MOBILE_PUBLIC_LINKS_ROUTE
    val publicLinks = publicLinksController?.let { controller ->
        MobilePublicLinks(controller.state.collectAsStateWithLifecycle().value, controller::dispatch)
    }
    val downloadsState = downloadsController?.state?.collectAsStateWithLifecycle()?.value ?: DownloadsState()
    val trashState = trashController?.state?.collectAsStateWithLifecycle()?.value
    TrashRestoreEffects(trashState, onFilesEvent)
    val shareNavigationBlocked = shareNavigationBlocked(filesState, trashState, transfersState, nowPlayingPending)
    DeepLinkNavigationEffect(
        deepLinkRequests = deepLinkRequests,
        navigationReady = backStackEntry != null,
        navigationBlocked = shareNavigationBlocked,
        navController = navController,
        onOpenFile = onOpenFile,
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

    BackHandler(enabled = isPlayback) {
        navController.popBackStack()
    }

    val playbackFileId = if (isPlayback) backStackEntry?.arguments?.getLong("fileId") else null
    val resolvingTransfer = transfersState.navigation as? TransferNavigation.Resolving
    NowPlayingNavigationEffect(nowPlayingRequests, playbackPlayerFactory, transferDraft, navController, playbackFileId)
    ContentNavigationEffect(
        contentNavigation = contentNavigation,
        transferDraft = transferDraft,
        navController = navController,
        playbackFileId = playbackFileId,
        onFilesEvent = onFilesEvent,
        onNavigationRejected = { rejectedNavigation = FilesFailure.NavigationBlocked },
    )
    TransferFileNavigationEffect(
        resolving = resolvingTransfer,
        transfersSessionId = transfersSessionId,
        navController = navController,
        onFilesEvent = onFilesEvent,
        onTransfersEvent = onTransfersEvent,
        resolveTransferFile = resolveTransferFile,
        onTransferAuthenticationRequired = onTransferAuthenticationRequired,
    )

    val filesOwnsBack = filesOwnsBack(filesState, selectedDestination)
    // Back from a folder opened from Search, History or Transfers returns to that screen.
    val onFilesBack = {
        val origin = filesState.current.openedFrom
        if (onFilesEvent(FilesBrowserEvent.NavigateBack)) origin?.returnDestination()?.let(navController::navigateTo)
    }
    BackHandler(enabled = !isPlayback && filesOwnsBack, onBack = onFilesBack)
    SubpageBackHandler(
        enabled = !isPlayback,
        onSubpage = isTrash || isDownloads || isPublicLinks,
        protectTrashRecovery = trashState?.hasPendingMutation == true && !filesOwnsBack,
        navController = navController,
    )

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
                    onFilesBack = onFilesBack,
                    inactiveNotice = account.inactiveNotice,
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
                        publicLinks = publicLinks,
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

@Composable
private fun DeepLinkNavigationEffect(
    deepLinkRequests: MobileDeepLinkRequests,
    navigationReady: Boolean,
    navigationBlocked: Boolean,
    navController: NavHostController,
    onOpenFile: suspend (FilesItemId) -> Unit,
) {
    val pendingDeepLink by deepLinkRequests.pending.collectAsStateWithLifecycle()
    // Keyed on readiness rather than the entry so the navigation a link causes cannot restart it.
    LaunchedEffect(pendingDeepLink, navigationReady, navigationBlocked) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (!navigationReady || navigationBlocked) return@LaunchedEffect
        // A newer link restarts this effect and cancels an unfinished file resolve.
        navController.openDeepLink(link, onOpenFile)
        deepLinkRequests.acknowledge(link)
    }
}

private suspend fun NavHostController.openDeepLink(
    link: MobileDeepLink,
    onOpenFile: suspend (FilesItemId) -> Unit,
) {
    when (link) {
        MobileDeepLink.Files -> navigateTo(MobileDestination.Files)
        is MobileDeepLink.File -> {
            navigateTo(MobileDestination.Files)
            onOpenFile(link.id)
        }
        MobileDeepLink.Transfers -> navigateTo(MobileDestination.Transfers)
        MobileDeepLink.Search, MobileDeepLink.History -> navigateTo(MobileDestination.Search)
        MobileDeepLink.Trash -> navigateToTrash()
        MobileDeepLink.Downloads -> {
            navigateTo(MobileDestination.Account)
            navigate(MOBILE_DOWNLOADS_ROUTE) { launchSingleTop = true }
        }
    }
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
                if (!editingTransfer) navController.navigateTo(MobileDestination.Files)
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
    onTransfersEvent: (TransfersEvent) -> Unit,
    resolveTransferFile: suspend (TransferFileId) -> PutioResult<FilesItem>,
    onTransferAuthenticationRequired: suspend () -> Unit,
) {
    LaunchedEffect(resolving?.requestId, transfersSessionId) {
        val resolvingTransfer = resolving ?: return@LaunchedEffect
        val sessionOnFilesEvent = onFilesEvent
        val sessionOnTransfersEvent = onTransfersEvent
        val sessionResolveTransferFile = resolveTransferFile
        val sessionOnTransferAuthenticationRequired = onTransferAuthenticationRequired
        val resolved = sessionResolveTransferFile(resolvingTransfer.fileId)
        currentCoroutineContext().ensureActive()
        when (resolved) {
            is PutioResult.Success -> {
                navController.currentBackStackEntryFlow.first()
                currentCoroutineContext().ensureActive()
                val open = FilesBrowserEvent.OpenExternalItem(resolved.value, FilesOpenOrigin.TRANSFERS)
                if (sessionOnFilesEvent(open)) {
                    navController.navigateTo(MobileDestination.Files)
                    sessionOnTransfersEvent(TransfersEvent.OpenSucceeded(resolvingTransfer.requestId))
                } else {
                    sessionOnTransfersEvent(TransfersEvent.OpenFailed(
                        resolvingTransfer.requestId, FilesFailure.NavigationBlocked,
                    ))
                }
            }
            is PutioResult.Failure ->
                if (resolved.failure is PutioFailure.AuthenticationRequired) {
                    sessionOnTransferAuthenticationRequired()
                } else {
                    sessionOnTransfersEvent(TransfersEvent.OpenFailed(resolvingTransfer.requestId, resolved.failure))
                }
        }
    }
}

private fun filesOwnsBack(
    filesState: FilesBrowserState,
    selectedDestination: MobileDestination,
): Boolean = filesState.stack.any { it.operation.pendingMove != null } ||
    selectedDestination == MobileDestination.Files && filesState.canNavigateBack

/** Back leaves an Account subpage; elsewhere it returns to Trash while a Trash change is pending. */
@Composable
private fun SubpageBackHandler(
    enabled: Boolean,
    onSubpage: Boolean,
    protectTrashRecovery: Boolean,
    navController: NavHostController,
) {
    BackHandler(enabled = enabled && (onSubpage || protectTrashRecovery)) {
        if (onSubpage) {
            navController.popBackStack()
        } else {
            navController.navigateToTrash()
        }
    }
}

@Composable
private fun MobileNavigationAlerts(
    navigationFailure: FilesFailure?,
    transfersState: TransfersState,
    onDismissNavigationFailure: () -> Unit,
    onTransfersEvent: (TransfersEvent) -> Unit,
) {
    if (navigationFailure != null && navigationFailure !is PutioFailure.AuthenticationRequired) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissNavigationFailure,
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(navigationFailure.mobileMessage()) },
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
            text = { Text(failed.failure.mobileMessage()) },
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
    onFilesBack: () -> Unit,
    inactiveNotice: InactiveAccountNotice?,
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
                    Column {
                        MobileTopBar(
                            openNavigation = openNavigation,
                            destination = selectedDestination,
                            subpageTitle = accountSubpageTitle(route),
                            onSubpageBack = { navController.popBackStack() },
                            filesState = filesState,
                            onFilesBack = onFilesBack,
                            onFilesEvent = onFilesEvent,
                        )
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
    @StringRes subpageTitle: Int?,
    onSubpageBack: () -> Unit,
    filesState: FilesBrowserState,
    onFilesBack: () -> Unit,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    openNavigation: (() -> Unit)? = null,
) {
    val filesFolderName = filesState.current.folder.name?.takeIf(String::isNotBlank)
    TopAppBar(
        title = {
            Text(
                if (subpageTitle != null) {
                    stringResource(subpageTitle)
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
            if (subpageTitle != null) {
                IconButton(onClick = onSubpageBack) {
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

@StringRes
private fun accountSubpageTitle(route: String?): Int? =
    when (route) {
        MOBILE_TRASH_ROUTE -> R.string.mobile_trash_title
        MOBILE_DOWNLOADS_ROUTE -> R.string.mobile_downloads_title
        MOBILE_PUBLIC_LINKS_ROUTE -> R.string.mobile_public_links_manage
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
)

private fun FilesOpenOrigin.returnDestination(): MobileDestination? =
    when (this) {
        FilesOpenOrigin.SEARCH, FilesOpenOrigin.HISTORY -> MobileDestination.Search
        FilesOpenOrigin.TRANSFERS -> MobileDestination.Transfers
        FilesOpenOrigin.LINK -> null
    }

private fun emptySearchHistoryState(): MobileSearchHistoryState =
    MobileSearchHistoryState(
        search = SearchReducer.start(),
        history = HistoryReducer.start(historyEnabled = false).state,
        recentSearchFailure = null,
    )

private fun emptyTransfersState(): TransfersState = TransfersReducer.start().state
