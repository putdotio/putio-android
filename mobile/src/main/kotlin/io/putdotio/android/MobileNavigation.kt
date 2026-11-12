package io.putdotio.android

import android.net.Uri
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.putdotio.android.files.rememberVlcInstalled
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.putdotio.android.account.LoggedInAppsController
import io.putdotio.android.account.MOBILE_APP_INFO_ROUTE
import io.putdotio.android.account.loggedInAppsDestination
import io.putdotio.android.account.MOBILE_OPEN_SOURCE_LICENSES_ROUTE
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROUTE
import io.putdotio.android.account.MobileAccountLibrary
import io.putdotio.android.account.MobileAccountListDetail
import io.putdotio.android.account.MobileAccountPage
import io.putdotio.android.account.MobileAccountPanes
import io.putdotio.android.account.rememberAccountListScroll
import io.putdotio.android.account.MobileAccountScreen
import io.putdotio.android.account.MobileAppInfoScreen
import io.putdotio.android.account.bytesOnDevice
import io.putdotio.android.account.MobileOpenSourceLicensesScreen
import io.putdotio.android.account.MobilePrivacyControlsScreen
import io.putdotio.android.account.mobileAppDiagnostics
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.DownloadsSelection
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.MobileFileDownloads
import io.putdotio.android.downloads.rememberFirstDownloadNotificationPrompt
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.MobileFileOpener
import io.putdotio.android.files.ContinueWatching
import io.putdotio.android.files.MobilePinnedFiles
import io.putdotio.android.files.MobileFilesActionsController
import io.putdotio.android.files.MobileFilesItemActions
import io.putdotio.android.files.FilesSelection
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.MobileMoveTargetStore
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.MobileCastMediaLoader
import io.putdotio.android.playback.castMediaFor
import io.putdotio.android.playback.MobilePlaybackViewModel
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackAnalyticsReporter
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackFallbackReason
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.rememberNextEpisode
import io.putdotio.android.playback.rememberUpNext
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.rememberFolderNeighbours
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.playback.confirmedRotateToLandscape
import io.putdotio.android.playback.confirmedStreamingMethod
import io.putdotio.android.playback.dispatch
import io.putdotio.android.playback.mobilePlaybackViewModelFactory
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.playback.originalFallbackTelemetry
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.playback.rememberPictureInPictureMode
import io.putdotio.android.playback.subtitleStartupPolicy
import io.putdotio.android.playback.nextEpisodeEnabled
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.android.search.MobileHistoryScreen
import io.putdotio.android.settings.historyOpen
import io.putdotio.android.search.MobileSearchScreen
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.settings.confirmedShowSubtitles
import io.putdotio.android.settings.confirmedTrashEnabled
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINKS_ROUTE
import io.putdotio.android.sharing.MobilePublicLinks
import io.putdotio.android.sharing.MobilePublicLinksScreen
import io.putdotio.android.sharing.PublicLinksContent
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.trash.MOBILE_TRASH_ROUTE
import io.putdotio.android.trash.TrashSelection
import io.putdotio.android.trash.MobileTrashScreen
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.putdotio.android.files.MobileFilesNoticeHost
import androidx.compose.ui.Alignment
import io.putdotio.android.files.MobileSearchResultActions
import io.putdotio.android.files.MobileSearchResultActionsHost
import io.putdotio.android.search.SearchContent

internal const val MOBILE_PLAYBACK_ROUTE =
    "playback/{fileId}?name={name}&media={media}" + "&duration={duration}&method={method}"

@Composable
internal fun MobileNavHost(
    transferDraft: MobileTransferDraft,
    navController: NavHostController,
    filesState: FilesBrowserState,
    filesRepository: FilesRepository?,
    trashController: TrashController?,
    trashSelection: TrashSelection,
    accountSettingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    searchHistoryState: MobileSearchHistoryState,
    transfersState: TransfersState,
    transfersSessionId: MobileAuthSessionId?,
    account: MobileAccount,
    playbackRepository: PlaybackRepository,
    playbackPlayerFactory: MobilePlayerFactory,
    sessionId: MobileAuthSessionId,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    onAccountSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onPlaybackAuthenticationRequired: suspend () -> Unit,
    onFilesAuthenticationRequired: suspend () -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onTransfersEvent: (TransfersEvent) -> Unit,
    onSignOut: (removeDownloads: Boolean) -> Unit,
    downloadsController: DownloadsController?,
    downloadsState: DownloadsState,
    downloadsSelection: DownloadsSelection,
    onShareItem: ((FilesItem) -> Unit)?,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    modifier: Modifier = Modifier,
    deviceClass: AppDiagnostics.DeviceClass = AppDiagnostics.DeviceClass.Phone,
    publicLinks: MobilePublicLinks? = null,
    loggedInAppsController: LoggedInAppsController? = null,
    searchOpen: Boolean = false,
    searchForward: Boolean = true,
    filesBackPreview: MobileBackPreview = rememberMobileBackPreview(),
    fileDragOut: MobileFileDragOut? = null,
    continueWatching: ContinueWatching? = null,
    pinnedFiles: MobilePinnedFiles? = null,
    onNewFolder: (() -> Unit)? = null,
    /** From 840dp, the Files bar over the list pane; Files then shows the selected file beside the list. */
    filesListPaneTopBar: (@Composable () -> Unit)? = null,
    linkedFile: FilesItemId? = null,
    onLinkedFileLanded: () -> Unit = {},
    subtitleLanguages: FilesSubtitleLanguages? = null,
    filesActions: MobileFilesActionsController? = null,
    folderCreator: FilesFolderCreator? = null,
    filesSelection: FilesSelection? = null,
    accountPanes: MobileAccountPanes? = null,
) {
    val currentTransfersState by rememberUpdatedState(transfersState)
    val currentTransfersSessionId by rememberUpdatedState(transfersSessionId)
    val currentOnTransfersEvent by rememberUpdatedState(onTransfersEvent)
    val appContext = LocalContext.current.applicationContext
    val moveTargetStore = remember(appContext, account.userId) { MobileMoveTargetStore(appContext, account.userId) }
    val motion = rememberMobileMotion()
    val panes = accountPanes != null
    val accountScroll = rememberAccountListScroll()
    val accountList: @Composable (MobileAccountPage?) -> Unit = { open ->
        MobileAccountTab(
            account = account,
            sessionId = sessionId,
            settingsState = accountSettingsState,
            appConfigState = appConfigState,
            onSettingsEvent = onAccountSettingsEvent,
            onAppConfigEvent = onAppConfigEvent,
            loadTunnelRoutes = loadTunnelRoutes,
            onSignOut = onSignOut,
            onOpenPage = { navController.openAccountPage(it, besideList = panes) },
            open = open,
            listState = accountScroll.rememberListState(),
            trashController = trashController,
            publicLinks = publicLinks,
            loggedInApps = loggedInAppsController != null,
            downloadsState = downloadsState,
        )
    }
    val accountPage: @Composable (MobileAccountPage, @Composable () -> Unit) -> Unit = { page, content ->
        MobileAccountListDetail(accountPanes, page, accountList, content)
    }
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalMobileSharedTransition provides takeUnless { motion.reducedMotion }) {
            NavHost(
                navController = navController,
                startDestination = MobileDestination.start.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { motion.enter(motion.between(initialState, targetState, false, panes)) },
                exitTransition = { motion.exit(motion.between(initialState, targetState, false, panes)) },
                popEnterTransition = { motion.enter(motion.between(initialState, targetState, true, panes)) },
                popExitTransition = { motion.exit(motion.between(initialState, targetState, true, panes)) },
                // A Back gesture seeks the same pop, so it previews what Back will do.
                predictivePopEnterTransition = { motion.enter(motion.between(initialState, targetState, true, panes)) },
                predictivePopExitTransition = { motion.exit(motion.between(initialState, targetState, true, panes)) },
            ) {
                composable(MobileDestination.Files.route) {
                    val askForDownloadNotifications = rememberFirstDownloadNotificationPrompt()
                    val showSubtitles = accountSettingsState.confirmedShowSubtitles()
                    val fileDownloads = downloadsController?.let {
                        MobileFileDownloads(it, showSubtitles, askForDownloadNotifications)
                    }
                    val startDownload = fileDownloads?.bySetting
                    val pinnedIds = pinnedFiles?.ids?.collectAsStateWithLifecycle()?.value
                    val filesItemActions = (
                        filesActions?.itemActions(
                            watchedToggle = accountSettingsState.confirmedResumePlayback() == true,
                            vlcInstalled = rememberVlcInstalled(),
                        ) ?: MobileFilesItemActions()
                        ).withContinueWatching(continueWatching)
                        .withPins(pinnedFiles, pinnedIds)
                        .withPlayWith(appConfigState.confirmedStreamingMethod()) { item, method ->
                            navController.navigateToPlayback(item, method = method)
                        }
                        .withDownloadWith(appConfigState.confirmedStreamingMethod(), fileDownloads?.withMethod)
                    val fileOpener = remember(account.userId) { MobileFileOpener(account.userId) }
                    val filesContext = LocalContext.current
                    MobileFilesTab(
                        routeAnimation = this,
                        searchOpen = searchOpen,
                        searchForward = searchForward,
                        backPreview = filesBackPreview,
                        search = {
                            val searchResults = (searchHistoryState.search.content as? SearchContent.Ready)?.items
                            MobileSearchResultActionsHost(
                                results = searchResults.orEmpty(),
                                actions = MobileSearchResultActions(
                                    itemActions = filesItemActions.copy(
                                        onOpenWith = { fileOpener.openWithAnotherApp(filesContext, it) },
                                    ).playingWithVia(searchHistoryActions.onResultWith),
                                    downloads = downloadsState,
                                    // Through the result's own open, so the query joins Recent searches.
                                    onPlayItem = searchHistoryActions.onResult,
                                    onDownloadItem = startDownload,
                                    onShareItem = onShareItem,
                                    publicLinks = publicLinks,
                                ),
                            ) { onResultActions ->
                                MobileSearchScreen(
                                    searchState = searchHistoryState.search,
                                    recentSearchFailure = searchHistoryState.recentSearchFailure,
                                    onSearchResult = searchHistoryActions.onResult,
                                    onSearchNextPage = searchHistoryActions.onNextPage,
                                    onSearchRetry = searchHistoryActions.onRetry,
                                    onRecentSearch = searchHistoryActions.onRecentSearch,
                                    onRecentEdit = searchHistoryActions.onRecentEdit,
                                    onRecentRetry = searchHistoryActions.onRecentRetry,
                                    onFilterSelected = searchHistoryActions.onFilterSelected,
                                    folderNames = searchHistoryState.folderNames,
                                    downloadStatus = downloadsState::rowStatus,
                                    onResultActions = onResultActions,
                                )
                            }
                        },
                    ) {
                        val startedVideos = continueWatching?.entries?.collectAsStateWithLifecycle()?.value
                        val pinnedItems = pinnedFiles?.items?.collectAsStateWithLifecycle()?.value
                        MobileFilesRoute(
                            state = filesState,
                            repository = filesRepository,
                            onEvent = onFilesEvent,
                            onPlayMedia = navController::navigateToPlayback,
                            onAuthenticationRequired = onFilesAuthenticationRequired,
                            confirmedTrashEnabled = accountSettingsState.confirmedTrashEnabled(),
                            downloads = downloadsState,
                            onDownloadItem = startDownload,
                            onShareItem = onShareItem,
                            moveTargetStore = moveTargetStore,
                            publicLinks = publicLinks,
                            fileDragOut = fileDragOut,
                            continueWatching = startedVideos.orEmpty(),
                            pinned = pinnedItems.orEmpty(),
                            onRefreshPinned = pinnedFiles?.let { it::refresh },
                            onNewFolder = onNewFolder,
                            listPaneTopBar = filesListPaneTopBar,
                            linkedFile = linkedFile,
                            onLinkedFileLanded = onLinkedFileLanded,
                            fileOpener = fileOpener,
                            subtitleLanguages = subtitleLanguages,
                            resumePlayback = accountSettingsState.confirmedResumePlayback() != false,
                            itemActions = filesItemActions,
                            folderCreator = folderCreator,
                            selection = filesSelection,
                        )
                    }
                }
                composable(MobileDestination.History.route) {
                    MobileHistoryScreen(
                        historyState = searchHistoryState.history,
                        onHistoryEvent = searchHistoryActions.onHistoryEvent,
                        open = accountSettingsState.historyOpen(),
                        onRetryOpen = { onAccountSettingsEvent(AccountSettingsEvent.RetryLoad) },
                    )
                }
                composable(MobileDestination.Transfers.route) {
                    val eventHandler = currentOnTransfersEvent
                    TransfersVisibilityEffect(currentTransfersSessionId, eventHandler)
                    MobileTransfersScreen(
                        draft = transferDraft,
                        state = currentTransfersState,
                        onEvent = eventHandler,
                        sessionId = currentTransfersSessionId,
                        filesRepository = filesRepository,
                        onFilesAuthenticationRequired = onFilesAuthenticationRequired,
                    )
                }
                composable(MobileDestination.Downloads.route) {
                    MobileRouteAnimation(this) {
                        downloadsController?.let { controller ->
                            MobileDownloadsScreen(
                                state = downloadsState,
                                onEvent = controller::dispatch,
                                onPlay = navController::navigateToPlayback,
                                onShare = onShareItem,
                                selection = downloadsSelection,
                            )
                        }
                    }
                }
                composable(MobileDestination.Account.route) {
                    MobileAccountListDetail(accountPanes, open = null, list = accountList, page = {})
                }
                composable(MOBILE_APP_INFO_ROUTE) {
                    accountPage(MobileAccountPage.AppInfo) {
                        MobileAppInfoScreen(
                            mobileAppDiagnostics(
                                appConfigState.playbackPreference(),
                                appConfigState.confirmedStreamingMethod(),
                                deviceClass,
                            ),
                        )
                    }
                }
                composable(MOBILE_OPEN_SOURCE_LICENSES_ROUTE) {
                    accountPage(MobileAccountPage.OpenSourceLicenses) { MobileOpenSourceLicensesScreen() }
                }
                composable(MOBILE_PRIVACY_CONTROLS_ROUTE) {
                    accountPage(MobileAccountPage.PrivacyControls) {
                        MobilePrivacyControlsScreen(
                            settingsState = accountSettingsState,
                            onSettingsEvent = onAccountSettingsEvent,
                        )
                    }
                }
                loggedInAppsDestination(loggedInAppsController, onFilesAuthenticationRequired) { content ->
                    accountPage(MobileAccountPage.LoggedInApps, content)
                }
                composable(MOBILE_PUBLIC_LINKS_ROUTE) {
                    accountPage(MobileAccountPage.ExclusiveLinks) {
                        publicLinks?.let { MobilePublicLinksScreen(it) }
                    }
                }
                composable(MOBILE_TRASH_ROUTE) {
                    accountPage(MobileAccountPage.Trash) {
                        trashController?.let { controller ->
                            val state by controller.state.collectAsStateWithLifecycle()
                            LaunchedEffect(controller) { controller.dispatch(TrashEvent.Open) }
                            MobileTrashScreen(state, controller::dispatch, selection = trashSelection)
                        }
                    }
                }
                composable(
                    route = MOBILE_PLAYBACK_ROUTE,
                    arguments =
                        listOf(
                            navArgument("fileId") { type = NavType.LongType },
                            navArgument("name") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("media") {
                                type = NavType.StringType
                                defaultValue = PlaybackMediaType.VIDEO.name
                            },
                            navArgument("duration") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("method") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                        ),
                ) { backStackEntry ->
                    val fileId = requireNotNull(backStackEntry.arguments?.getLong("fileId"))
                    val name = backStackEntry.arguments?.getString("name").orEmpty()
                    val media = backStackEntry.arguments?.getString("media")
                    val mediaType =
                        PlaybackMediaType.entries.firstOrNull { it.name == media } ?: PlaybackMediaType.VIDEO
                    val subtitleStartupPolicy = accountSettingsState.subtitleStartupPolicy()
                    // The listing's duration spares resolution a lookup for the near-end rule.
                    val durationSeconds = backStackEntry.arguments?.getString("duration")?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0.0 }
                    val method = backStackEntry.arguments?.getString("method")
                        ?.let { wire -> StreamingMethod.entries.firstOrNull { it.wireValue == wire } }
                    val target = PlaybackTarget(FilesItemId(fileId), name, mediaType, durationSeconds, method = method)
                    val castContext = LocalContext.current
                    val cast = remember(castContext) { MobileCast.controller(castContext) }
                    val playbackViewModel: MobilePlaybackViewModel =
                        viewModel(
                            viewModelStoreOwner = backStackEntry,
                            factory = mobilePlaybackViewModelFactory(target, playbackRepository),
                        )
                    continueWatching?.let { RecordOpenedVideos(playbackViewModel.controller, it) }
                    // The player grows out of the row it was opened from: a container transform.
                    Box(Modifier.fillMaxSize().playbackContainer(fileId, route = this)) {
                        MobilePlaybackRoute(
                            controller = playbackViewModel.controller,
                            analytics = playbackViewModel.analytics,
                            subtitleStartupPolicy = subtitleStartupPolicy,
                            autoplayNextVideo = appConfigState.confirmedAutoplayNextVideo(),
                            rotateToLandscape = appConfigState.confirmedRotateToLandscape(),
                            onAuthenticationRequired = onPlaybackAuthenticationRequired,
                            onBack = navController::popBackStack,
                            playerFactory = playbackPlayerFactory,
                            findNextEpisode = playbackRepository::findNextVideo
                                .takeIf { accountSettingsState.nextEpisodeEnabled() },
                            findNeighbours = playbackRepository::findNeighbours,
                            // Another file replaces this route, so Back still returns to the screen below.
                            onPlay = { next ->
                                navController.navigateToPlayback(
                                    next.fileId,
                                    next.name,
                                    next.mediaType,
                                    replaceCurrentPlayback = true,
                                    durationSeconds = next.durationSeconds,
                                )
                            },
                            onOpenInVlc = filesActions?.let { actions ->
                                { target -> actions.openInVlc(target.fileId, target.name) }
                            },
                            cast = cast,
                            castMedia = playbackRepository::castMediaFor,
                        )
                        // Over the player, which paints its own background.
                        filesActions?.let { MobileFilesNoticeHost(it.notices, Modifier.align(Alignment.BottomCenter)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileAccountTab(
    account: MobileAccount,
    sessionId: MobileAuthSessionId,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onSignOut: (removeDownloads: Boolean) -> Unit,
    onOpenPage: (MobileAccountPage) -> Unit,
    open: MobileAccountPage?,
    listState: LazyListState,
    trashController: TrashController?,
    publicLinks: MobilePublicLinks?,
    loggedInApps: Boolean,
    downloadsState: DownloadsState,
) {
    val trashState = trashController?.state?.collectAsStateWithLifecycle()?.value
    MobileAccountScreen(
        account = account,
        sessionId = sessionId,
        settingsState = settingsState,
        appConfigState = appConfigState,
        onSettingsEvent = onSettingsEvent,
        onAppConfigEvent = onAppConfigEvent,
        loadTunnelRoutes = loadTunnelRoutes,
        onSignOut = onSignOut,
        onManageTrash = { onOpenPage(MobileAccountPage.Trash) },
        onManagePublicLinks = publicLinks?.let { { onOpenPage(MobileAccountPage.ExclusiveLinks) } },
        onPrivacyControls = { onOpenPage(MobileAccountPage.PrivacyControls) },
        onLoggedInApps = { onOpenPage(MobileAccountPage.LoggedInApps) }.takeIf { loggedInApps },
        onAppInfo = { onOpenPage(MobileAccountPage.AppInfo) },
        onOpenSourceLicenses = { onOpenPage(MobileAccountPage.OpenSourceLicenses) },
        open = open,
        listState = listState,
        library = MobileAccountLibrary(
            exclusiveLinkCount = (publicLinks?.state?.content as? PublicLinksContent.Ready)?.links?.size,
            trashSizeBytes = (trashState?.content as? TrashContent.Loaded)?.trashSizeBytes,
        ),
        downloadsOnDeviceBytes = downloadsState.bytesOnDevice(),
    )
}

private fun NavHostController.openAccountPage(page: MobileAccountPage, besideList: Boolean) {
    if (currentDestination?.route == page.route) return
    navigate(page.route) {
        launchSingleTop = true
        if (besideList) popUpTo(MobileDestination.Account.route)
    }
}

/**
 * Files and the Search that opens over it. Search moves along the shared axis in [searchForward]'s
 * direction: forward when it opens or a result opens, back on Back. Folders move the same way
 * inside [MobileFilesRoute], and Back previews either pop on [backPreview].
 */
@Composable
private fun MobileFilesTab(
    routeAnimation: AnimatedVisibilityScope,
    searchOpen: Boolean,
    searchForward: Boolean,
    backPreview: MobileBackPreview,
    search: @Composable () -> Unit,
    files: @Composable () -> Unit,
) {
    // Search keeps its scroll position and field while a folder it opened is on screen.
    val saved = rememberSaveableStateHolder()
    MobileRouteAnimation(routeAnimation) {
        MobileAxisContent(
            state = searchOpen,
            contentKey = { it },
            forward = { _, _ -> searchForward },
            modifier = Modifier.fillMaxSize().mobileBackPreview(backPreview),
        ) { showSearch ->
            saved.SaveableStateProvider(showSearch) { if (showSearch) search() else files() }
        }
    }
}

@Composable
private fun MobilePlaybackRoute(
    controller: PlaybackController,
    analytics: PlaybackAnalyticsReporter,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    autoplayNextVideo: Boolean,
    rotateToLandscape: Boolean,
    onAuthenticationRequired: suspend () -> Unit,
    onBack: () -> Unit,
    playerFactory: MobilePlayerFactory,
    findNextEpisode: (suspend (PlaybackTarget) -> PlaybackNextResult)? = null,
    findNeighbours: (suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours>)? = null,
    onPlay: (PlaybackTarget) -> Unit = {},
    onOpenInVlc: ((PlaybackTarget) -> Unit)? = null,
    cast: MobileCastController? = null,
    castMedia: (suspend (PlaybackTarget, PlaybackSource?, String?) -> PlaybackRepositoryResult<PlaybackCastMedia>)? =
        null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    // The lookups outlive a recomposition; a rejection reaches the current session's handler.
    val currentOnAuthenticationRequired by rememberUpdatedState(onAuthenticationRequired)
    val nextEpisode = rememberNextEpisode(state, findNextEpisode) { currentOnAuthenticationRequired() }
    val neighbours = rememberFolderNeighbours(state, findNeighbours) { currentOnAuthenticationRequired() }
        ?: PlaybackNeighbours()
    val upNext = rememberUpNext(state, findNeighbours.takeIf { autoplayNextVideo }) {
        currentOnAuthenticationRequired()
    }
    val authenticationFailure =
        when (val content = state.content) {
            is PlaybackContent.Failed -> content.failure
            is PlaybackContent.NextFailed -> content.failure
            else -> null
        }?.takeIf { it.rejectsSession }

    LaunchedEffect(authenticationFailure) {
        if (authenticationFailure != null) onAuthenticationRequired()
    }
    // A picture-in-picture window never shows the shell: the finished video waits for the viewer to leave it.
    val pictureInPicture by rememberPictureInPictureMode()
    LaunchedEffect(state.content, pictureInPicture) {
        if (state.content is PlaybackContent.Ended && !pictureInPicture) onBack()
    }

    MobilePlayerScreen(
        state = state,
        onRetry = { controller.dispatch(PlaybackEvent.Retry) },
        onRefreshConversion = { controller.dispatch(PlaybackEvent.RefreshConversion) },
        onStartConversion = { controller.dispatch(PlaybackEvent.StartConversion) },
        onPlayerFailure = { failure, positionMillis ->
            val playing = controller.state.value
            // Audio plays on the session service's player, which reports its own failures.
            if (controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis)) &&
                controller.state.value.content is PlaybackContent.Failed &&
                playing.target.mediaType == PlaybackMediaType.VIDEO
            ) {
                PutioDiagnostics.capture(playing.playerFailureTelemetry(failure))
            }
        },
        onOriginalRejected = controller::rejectOriginal,
        onBack = onBack,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        rotateToLandscape = rotateToLandscape,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
        analytics = analytics,
        playerFactory = playerFactory,
        onSourceRequired = { controller.dispatch(PlaybackEvent.SourceRequired(it)) },
        onResume = { controller.dispatch(PlaybackEvent.Resume) },
        onRestart = { controller.dispatch(PlaybackEvent.Restart) },
        nextEpisode = nextEpisode,
        onNextEpisode = { nextEpisode?.let { controller.dispatch(PlaybackEvent.NextChosen(it)) } },
        upNext = upNext,
        onPlayUpNext = { controller.dispatch(PlaybackEvent.NextChosen(it)) },
        neighbours = neighbours,
        onPlay = onPlay,
        onOpenInVlc = onOpenInVlc?.let { open -> { open(state.target) } },
        cast = cast,
        castMedia = castMediaFor(castMedia, state.target),
    )
}

private fun PlaybackController.rejectOriginal(reason: PlaybackFallbackReason, positionMillis: Long): Boolean {
    val playing = state.value
    val consumed = dispatch(PlaybackEvent.OriginalRejected(reason, positionMillis))
    if (consumed) playing.originalFallbackTelemetry(reason)?.let(PutioDiagnostics::capture)
    return consumed
}

private val PlaybackFailure.rejectsSession: Boolean get() = putioFailure is PutioFailure.AuthenticationRequired

/** [load] for the file on screen, or no Cast without it. */
private fun castMediaFor(
    load: (suspend (PlaybackTarget, PlaybackSource?, String?) -> PlaybackRepositoryResult<PlaybackCastMedia>)?,
    target: PlaybackTarget,
): MobileCastMediaLoader? =
    if (load == null) null else { source, subtitleKey -> load(target, source, subtitleKey) }

@Composable
internal fun TransfersVisibilityEffect(
    sessionId: MobileAuthSessionId?,
    onEvent: (TransfersEvent) -> Unit,
) {
    LifecycleStartEffect(sessionId) {
        onEvent(TransfersEvent.VisibilityChanged(true))
        onStopOrDispose { onEvent(TransfersEvent.VisibilityChanged(false)) }
    }
}

internal fun NavHostController.navigateTo(destination: MobileDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Shows [destination]'s own screen. Tab restoration can bring back a player saved above it, which
 * a link or an item opened from another screen must not show: they land as on a cold start.
 */
internal fun NavHostController.showTab(destination: MobileDestination) {
    navigateTo(destination)
    popBackStack(destination.route, inclusive = false)
}

/** Trash lives under Account, so Back from it lands on Account wherever it was opened from. */
internal fun NavHostController.navigateToTrash() {
    showTab(MobileDestination.Account)
    navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true }
}

internal fun NavHostController.navigateToPlayback(
    item: FilesItem,
    replaceCurrentPlayback: Boolean = false,
    method: StreamingMethod? = null,
) {
    val mediaType = PlaybackMediaType.fromFileType(item.type) ?: return
    val route = playbackRoute(item.id, item.name, mediaType, item.playback?.durationSeconds, method)
    navigatePlayback(route, replaceCurrentPlayback)
}

internal fun NavHostController.navigateToPlayback(
    fileId: FilesItemId,
    name: String,
    mediaType: PlaybackMediaType,
    replaceCurrentPlayback: Boolean = false,
    durationSeconds: Double? = null,
) {
    navigatePlayback(playbackRoute(fileId, name, mediaType, durationSeconds), replaceCurrentPlayback)
}

private fun playbackRoute(
    fileId: FilesItemId,
    name: String,
    mediaType: PlaybackMediaType,
    durationSeconds: Double?,
    method: StreamingMethod? = null,
): String {
    val duration = durationSeconds?.let { "&duration=$it" }.orEmpty()
    val methodArgument = method?.let { "&method=${it.wireValue}" }.orEmpty()
    return "playback/${fileId.value}?name=${Uri.encode(name)}&media=${mediaType.name}$duration$methodArgument"
}

private fun NavHostController.navigatePlayback(route: String, replaceCurrentPlayback: Boolean) {
    navigate(route) {
        if (replaceCurrentPlayback) popUpTo(MOBILE_PLAYBACK_ROUTE) { inclusive = true }
    }
}

/** Each video the player resolves, autoplay's next ones included, can join Continue watching. */
@Composable
private fun RecordOpenedVideos(controller: PlaybackController, continueWatching: ContinueWatching) {
    LaunchedEffect(controller, continueWatching) {
        controller.state.map { it.target }.distinctUntilChanged().collect { target ->
            if (target.mediaType == PlaybackMediaType.VIDEO) {
                continueWatching.opened(target.fileId, target.name, target.durationSeconds)
            }
        }
    }
}
