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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.putdotio.android.account.MOBILE_APP_INFO_ROUTE
import io.putdotio.android.account.MOBILE_PRIVACY_CONTROLS_ROUTE
import io.putdotio.android.account.MobileAccountLibrary
import io.putdotio.android.account.MobileAccountScreen
import io.putdotio.android.account.MobileAppInfoScreen
import io.putdotio.android.account.bytesOnDevice
import io.putdotio.android.account.MobilePrivacyControlsScreen
import io.putdotio.android.account.mobileAppDiagnostics
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadRequest
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.downloads.DownloadsSelection
import io.putdotio.android.downloads.MobileDownloadsScreen
import io.putdotio.android.downloads.rememberFirstDownloadNotificationPrompt
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolderCreator
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.MobileFileOpener
import io.putdotio.android.files.MobileContinueWatching
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
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.playback.dispatch
import io.putdotio.android.playback.mobilePlaybackViewModelFactory
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.playback.playerFailureTelemetry
import io.putdotio.android.playback.putioFailure
import io.putdotio.android.playback.rememberPictureInPictureMode
import io.putdotio.android.playback.subtitleStartupPolicy
import io.putdotio.android.playback.nextEpisodeEnabled
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.android.search.MobileHistoryScreen
import io.putdotio.android.search.MobileSearchScreen
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
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
import io.putdotio.android.trash.MobileTrashScreen
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import io.putdotio.android.files.MobileFilesNoticeHost
import androidx.compose.ui.Alignment
import io.putdotio.android.files.MobileSearchResultActions
import io.putdotio.android.files.MobileSearchResultActionsHost
import io.putdotio.android.search.SearchContent

internal const val MOBILE_PLAYBACK_ROUTE = "playback/{fileId}?name={name}&media={media}&duration={duration}"

@Composable
internal fun MobileNavHost(
    transferDraft: MobileTransferDraft,
    navController: NavHostController,
    filesState: FilesBrowserState,
    filesRepository: FilesRepository?,
    trashController: TrashController?,
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
    searchOpen: Boolean = false,
    searchForward: Boolean = true,
    filesBackPreview: MobileBackPreview = rememberMobileBackPreview(),
    fileDragOut: MobileFileDragOut? = null,
    continueWatching: MobileContinueWatching? = null,
    onNewFolder: (() -> Unit)? = null,
    /** From 840dp, the Files bar over the list pane; Files then shows the selected file beside the list. */
    filesListPaneTopBar: (@Composable () -> Unit)? = null,
    subtitleLanguages: FilesSubtitleLanguages? = null,
    filesActions: MobileFilesActionsController? = null,
    folderCreator: FilesFolderCreator? = null,
    filesSelection: FilesSelection? = null,
) {
    val currentTransfersState by rememberUpdatedState(transfersState)
    val currentTransfersSessionId by rememberUpdatedState(transfersSessionId)
    val currentOnTransfersEvent by rememberUpdatedState(onTransfersEvent)
    val appContext = LocalContext.current.applicationContext
    val moveTargetStore = remember(appContext, account.userId) { MobileMoveTargetStore(appContext, account.userId) }
    val motion = rememberMobileMotion()
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalMobileSharedTransition provides this) {
            NavHost(
                navController = navController,
                startDestination = MobileDestination.start.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { motion.enter(motion.between(initialState, targetState, pop = false)) },
                exitTransition = { motion.exit(motion.between(initialState, targetState, pop = false)) },
                popEnterTransition = { motion.enter(motion.between(initialState, targetState, pop = true)) },
                popExitTransition = { motion.exit(motion.between(initialState, targetState, pop = true)) },
                // A Back gesture seeks the same pop, so it previews what Back will do.
                predictivePopEnterTransition = { motion.enter(motion.between(initialState, targetState, pop = true)) },
                predictivePopExitTransition = { motion.exit(motion.between(initialState, targetState, pop = true)) },
            ) {
                composable(MobileDestination.Files.route) {
                    val askForDownloadNotifications = rememberFirstDownloadNotificationPrompt()
                    val showSubtitles = accountSettingsState.confirmedShowSubtitles()
                    val startDownload = downloadsController?.let { controller ->
                        { item: FilesItem ->
                            val start = DownloadsEvent.Start(item.toDownloadRequest(showSubtitles))
                            if (controller.dispatch(start)) askForDownloadNotifications()
                        }
                    }
                    val filesItemActions = filesActions?.itemActions(
                        watchedToggle = accountSettingsState.confirmedResumePlayback() == true,
                    ) ?: MobileFilesItemActions()
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
                                    itemActions = filesItemActions,
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
                            onNewFolder = onNewFolder,
                            listPaneTopBar = filesListPaneTopBar,
                            fileOpener = remember(account.userId) { MobileFileOpener(account.userId) },
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
                    val trashState = trashController?.state?.collectAsStateWithLifecycle()?.value
                    MobileAccountScreen(
                        account = account,
                        sessionId = sessionId,
                        settingsState = accountSettingsState,
                        appConfigState = appConfigState,
                        onSettingsEvent = onAccountSettingsEvent,
                        onAppConfigEvent = onAppConfigEvent,
                        loadTunnelRoutes = loadTunnelRoutes,
                        onSignOut = onSignOut,
                        onManageTrash = { navController.navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true } },
                        onManagePublicLinks = publicLinks?.let {
                            { navController.navigate(MOBILE_PUBLIC_LINKS_ROUTE) { launchSingleTop = true } }
                        },
                        onPrivacyControls = {
                            navController.navigate(MOBILE_PRIVACY_CONTROLS_ROUTE) { launchSingleTop = true }
                        },
                        onAppInfo = { navController.navigate(MOBILE_APP_INFO_ROUTE) { launchSingleTop = true } },
                        library = MobileAccountLibrary(
                            exclusiveLinkCount =
                                (publicLinks?.state?.content as? PublicLinksContent.Ready)?.links?.size,
                            trashSizeBytes = (trashState?.content as? TrashContent.Loaded)?.trashSizeBytes,
                        ),
                        downloadsOnDeviceBytes = downloadsState.bytesOnDevice(),
                    )
                }
                composable(MOBILE_APP_INFO_ROUTE) {
                    MobileAppInfoScreen(mobileAppDiagnostics(appConfigState.playbackPreference(), deviceClass))
                }
                composable(MOBILE_PRIVACY_CONTROLS_ROUTE) {
                    MobilePrivacyControlsScreen(
                        settingsState = accountSettingsState,
                        onSettingsEvent = onAccountSettingsEvent,
                    )
                }
                composable(MOBILE_PUBLIC_LINKS_ROUTE) {
                    publicLinks?.let { MobilePublicLinksScreen(it) }
                }
                composable(MOBILE_TRASH_ROUTE) {
                    trashController?.let { controller ->
                        val state by controller.state.collectAsStateWithLifecycle()
                        LaunchedEffect(controller) { controller.dispatch(TrashEvent.Open) }
                        MobileTrashScreen(state, controller::dispatch)
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
                    val target = PlaybackTarget(FilesItemId(fileId), name, mediaType, durationSeconds)
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
                            subtitleStartupPolicy = subtitleStartupPolicy,
                            autoplayNextVideo = appConfigState.confirmedAutoplayNextVideo(),
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
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    autoplayNextVideo: Boolean,
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
                playing.target.mediaType == PlaybackMediaType.VIDEO
            ) {
                PutioDiagnostics.capture(playing.playerFailureTelemetry(failure))
            }
        },
        onBack = onBack,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
        analytics = controller.analyticsReporter,
        playerFactory = playerFactory,
        onSourceRequired = { controller.dispatch(PlaybackEvent.SourceRequired(it)) },
        onResume = { controller.dispatch(PlaybackEvent.Resume) },
        onRestart = { controller.dispatch(PlaybackEvent.Restart) },
        nextEpisode = nextEpisode,
        onNextEpisode = { nextEpisode?.let { controller.dispatch(PlaybackEvent.NextChosen(it)) } },
        neighbours = neighbours,
        onPlay = onPlay,
        onOpenInVlc = onOpenInVlc?.let { open -> { open(state.target) } },
        cast = cast,
        castMedia = castMediaFor(castMedia, state.target),
    )
}

private val PlaybackFailure.rejectsSession: Boolean get() = putioFailure is PutioFailure.AuthenticationRequired

/** [load] for the file on screen, or no Cast without it. */
private fun castMediaFor(
    load: (suspend (PlaybackTarget, PlaybackSource?, String?) -> PlaybackRepositoryResult<PlaybackCastMedia>)?,
    target: PlaybackTarget,
): MobileCastMediaLoader? =
    if (load == null) null else { source, subtitleKey -> load(target, source, subtitleKey) }

/**
 * With the account's next_episode on, a playing video asks autoplay's folder walk for the one after
 * it, once. A failed walk offers none; a rejected session still ends.
 */
@Composable
private fun rememberNextEpisode(
    state: PlaybackState,
    findNextEpisode: (suspend (PlaybackTarget) -> PlaybackNextResult)?,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackTarget? {
    val ready = state.content is PlaybackContent.Ready && state.target.mediaType == PlaybackMediaType.VIDEO
    val nextEpisode by produceState<PlaybackTarget?>(null, state.target.fileId, ready, findNextEpisode != null) {
        value = null
        val find = findNextEpisode ?: return@produceState
        if (!ready) return@produceState
        when (val next = find(state.target)) {
            is PlaybackNextResult.Found -> value = next.target
            is PlaybackNextResult.Failure -> if (next.failure.rejectsSession) onAuthenticationRequired()
            PlaybackNextResult.Ended -> Unit
        }
    }
    return nextEpisode
}

/**
 * Playing audio asks for its folder once: the files around it for the skip buttons and Up next, and
 * the folder's name. A failed read leaves them off; playback does not depend on it, but a rejected
 * session still ends.
 */
@Composable
private fun rememberFolderNeighbours(
    state: PlaybackState,
    findNeighbours: (suspend (PlaybackTarget) -> PlaybackRepositoryResult<PlaybackNeighbours>)?,
    onAuthenticationRequired: suspend () -> Unit,
): PlaybackNeighbours {
    val audioReady by rememberUpdatedState(
        state.target.mediaType == PlaybackMediaType.AUDIO &&
            (state.content is PlaybackContent.Ready || state.content == PlaybackContent.Session),
    )
    val target by rememberUpdatedState(state.target)
    // Startup passes through Loading between Session and Ready; the walk still runs once per file.
    val neighbours by produceState(PlaybackNeighbours(), state.target.fileId, findNeighbours != null) {
        val find = findNeighbours ?: return@produceState
        snapshotFlow { audioReady }.first { it }
        value = when (val result = find(target)) {
            is PlaybackRepositoryResult.Success -> result.value
            is PlaybackRepositoryResult.Failure -> PlaybackNeighbours().also {
                if (result.failure.rejectsSession) onAuthenticationRequired()
            }
        }
    }
    return neighbours
}

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

internal fun NavHostController.navigateToPlayback(item: FilesItem, replaceCurrentPlayback: Boolean = false) {
    val mediaType = PlaybackMediaType.fromFileType(item.type) ?: return
    navigateToPlayback(item.id, item.name, mediaType, replaceCurrentPlayback, item.playback?.durationSeconds)
}

internal fun NavHostController.navigateToPlayback(
    fileId: FilesItemId,
    name: String,
    mediaType: PlaybackMediaType,
    replaceCurrentPlayback: Boolean = false,
    durationSeconds: Double? = null,
) {
    val duration = durationSeconds?.let { "&duration=$it" }.orEmpty()
    navigate("playback/${fileId.value}?name=${Uri.encode(name)}&media=${mediaType.name}$duration") {
        if (replaceCurrentPlayback) popUpTo(MOBILE_PLAYBACK_ROUTE) { inclusive = true }
    }
}

/** Each video the player resolves, autoplay's next ones included, can join Continue watching. */
@Composable
private fun RecordOpenedVideos(controller: PlaybackController, continueWatching: MobileContinueWatching) {
    LaunchedEffect(controller, continueWatching) {
        controller.state.map { it.target }.distinctUntilChanged().collect { target ->
            if (target.mediaType == PlaybackMediaType.VIDEO) {
                continueWatching.opened(target.fileId, target.name, target.durationSeconds)
            }
        }
    }
}

/** The confirmed subtitle setting travels with the download, which skips subtitles for it. */
private fun FilesItem.toDownloadRequest(showSubtitles: Boolean?): DownloadRequest = DownloadRequest(
    fileId = id,
    name = name,
    type = type,
    sizeBytes = sizeBytes,
    subtitlesHidden = showSubtitles?.not(),
    startFromSeconds = playback?.startFromSeconds ?: 0.0,
    durationSeconds = playback?.durationSeconds,
)
