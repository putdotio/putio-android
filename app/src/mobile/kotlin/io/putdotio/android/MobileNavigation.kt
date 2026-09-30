package io.putdotio.android

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.downloads.DownloadRequest
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.playback.subtitleStartupPolicy
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.confirmedTrashEnabled
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent

internal const val MOBILE_PLAYBACK_ROUTE = "playback/{fileId}?name={name}&media={media}"

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
    onSignOut: () -> Unit,
    downloadsController: DownloadsController?,
    downloadsState: DownloadsState,
    onShareItem: ((FilesItem) -> Unit)?,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    modifier: Modifier = Modifier,
    deviceClass: AppDiagnostics.DeviceClass = AppDiagnostics.DeviceClass.Phone,
) {
    val currentTransfersState by rememberUpdatedState(transfersState)
    val currentTransfersSessionId by rememberUpdatedState(transfersSessionId)
    val currentOnTransfersEvent by rememberUpdatedState(onTransfersEvent)
    NavHost(
        navController = navController,
        startDestination = MobileDestination.start.route,
        modifier = modifier,
    ) {
        composable(MobileDestination.Files.route) {
            MobileFilesRoute(
                state = filesState,
                repository = filesRepository,
                onEvent = onFilesEvent,
                onPlayMedia = navController::navigateToPlayback,
                onAuthenticationRequired = onFilesAuthenticationRequired,
                confirmedTrashEnabled = accountSettingsState.confirmedTrashEnabled(),
                downloads = downloadsState,
                onDownloadItem = downloadsController?.let { controller ->
                    { item -> controller.dispatch(DownloadsEvent.Start(item.toDownloadRequest())) }
                },
                onShareItem = onShareItem,
            )
        }
        composable(MobileDestination.Search.route) {
            MobileSearchHistoryScreen(
                searchState = searchHistoryState.search,
                historyState = searchHistoryState.history,
                recentSearchFailure = searchHistoryState.recentSearchFailure,
                onSearchQueryChanged = searchHistoryActions.onQueryChanged,
                onSearchSubmit = searchHistoryActions.onSubmit,
                onSearchResult = searchHistoryActions.onResult,
                onSearchNextPage = searchHistoryActions.onNextPage,
                onSearchRetry = searchHistoryActions.onRetry,
                onRecentSearch = searchHistoryActions.onRecentSearch,
                onRecentEdit = searchHistoryActions.onRecentEdit,
                onRecentRetry = searchHistoryActions.onRecentRetry,
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
            )
        }
        composable(MobileDestination.Account.route) {
            MobileAccountScreen(
                account = account,
                sessionId = sessionId,
                settingsState = accountSettingsState,
                appConfigState = appConfigState,
                onSettingsEvent = onAccountSettingsEvent,
                onAppConfigEvent = onAppConfigEvent,
                loadTunnelRoutes = loadTunnelRoutes,
                deviceClass = deviceClass,
                onSignOut = onSignOut,
                onManageTrash = { navController.navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true } },
                onManageDownloads = downloadsController?.let {
                    { navController.navigate(MOBILE_DOWNLOADS_ROUTE) { launchSingleTop = true } }
                },
            )
        }
        composable(MOBILE_DOWNLOADS_ROUTE) {
            downloadsController?.let { controller ->
                MobileDownloadsScreen(
                    state = downloadsState,
                    onEvent = controller::dispatch,
                    onPlay = navController::navigateToPlayback,
                    onShare = onShareItem,
                )
            }
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
                ),
        ) { backStackEntry ->
            val fileId = requireNotNull(backStackEntry.arguments?.getLong("fileId"))
            val name = backStackEntry.arguments?.getString("name").orEmpty()
            val mediaType =
                PlaybackMediaType.entries.firstOrNull { it.name == backStackEntry.arguments?.getString("media") }
                    ?: PlaybackMediaType.VIDEO
            val subtitleStartupPolicy = accountSettingsState.subtitleStartupPolicy()
            val target = PlaybackTarget(io.putdotio.android.files.FilesItemId(fileId), name, mediaType)
            val playbackViewModel: MobilePlaybackViewModel =
                viewModel(
                    viewModelStoreOwner = backStackEntry,
                    factory = mobilePlaybackViewModelFactory(target, playbackRepository),
                )
            MobilePlaybackRoute(
                controller = playbackViewModel.controller,
                subtitleStartupPolicy = subtitleStartupPolicy,
                autoplayNextVideo = appConfigState.confirmedAutoplayNextVideo(),
                onAuthenticationRequired = onPlaybackAuthenticationRequired,
                onBack = navController::popBackStack,
                playerFactory = playbackPlayerFactory,
            )
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
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val authenticationFailure =
        when (val content = state.content) {
            is PlaybackContent.Failed -> content.failure
            is PlaybackContent.NextFailed -> content.failure
            else -> null
        }?.takeIf { it is PlaybackFailure.AuthenticationRequired }

    LaunchedEffect(authenticationFailure) {
        if (authenticationFailure != null) {
            onAuthenticationRequired()
        }
    }
    LaunchedEffect(state.content) {
        if (state.content is PlaybackContent.Ended) onBack()
    }

    MobilePlayerScreen(
        state = state,
        onRetry = { controller.dispatch(PlaybackEvent.Retry) },
        onRefreshConversion = { controller.dispatch(PlaybackEvent.RefreshConversion) },
        onStartConversion = { controller.dispatch(PlaybackEvent.StartConversion) },
        onPlayerFailure = { failure, positionMillis ->
            controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis))
        },
        onBack = onBack,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
        playerFactory = playerFactory,
        onSourceRequired = { controller.dispatch(PlaybackEvent.SourceRequired(it)) },
        onResume = { controller.dispatch(PlaybackEvent.Resume) },
        onRestart = { controller.dispatch(PlaybackEvent.Restart) },
    )
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

private fun NavHostController.navigateToPlayback(item: FilesItem) {
    val mediaType = PlaybackMediaType.fromFileType(item.type) ?: return
    navigateToPlayback(item.id, item.name, mediaType)
}

internal fun NavHostController.navigateToPlayback(
    fileId: FilesItemId,
    name: String,
    mediaType: PlaybackMediaType,
    replaceCurrentPlayback: Boolean = false,
) {
    navigate("playback/${fileId.value}?name=${Uri.encode(name)}&media=${mediaType.name}") {
        if (replaceCurrentPlayback) popUpTo(MOBILE_PLAYBACK_ROUTE) { inclusive = true }
    }
}

private fun FilesItem.toDownloadRequest(): DownloadRequest = DownloadRequest(id, name, type, sizeBytes)
