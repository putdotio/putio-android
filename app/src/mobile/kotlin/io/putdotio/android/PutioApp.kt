package io.putdotio.android

import android.content.Intent
import android.net.Uri
import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.putdotio.android.auth.AuthTabOAuthBrowser
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.OAuthBrowserLaunchResult
import io.putdotio.android.auth.OAuthLaunchResult
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SdkPlaybackRepository
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.pendingDelete
import io.putdotio.android.files.pendingMove
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.authoritativeSessionFailure
import io.putdotio.android.settings.ConfirmedDefaultSort
import io.putdotio.android.settings.confirmedDefaultSort
import io.putdotio.android.settings.confirmedHistoryEnabled
import io.putdotio.android.settings.confirmedTrashEnabled
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.SdkHistoryRepository
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.transfers.SdkTransfersRepository
import io.putdotio.android.transfers.TransferFileId
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferNavigation
import io.putdotio.android.transfers.TransferNotice
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersEvent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal const val MOBILE_NAV_BAR_TAG = "mobile-navigation-bar"
internal const val MOBILE_NAV_RAIL_TAG = "mobile-navigation-rail"
internal const val MOBILE_PLAYBACK_ROUTE = "playback/{fileId}?name={name}&media={media}"

private val TabletMinWidth = 600.dp

@Composable
fun PutioApp(
    authTabLauncher: ActivityResultLauncher<Intent>? = null,
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { MobileOAuthRuntime.get(context) }
    val oauthBrowser = remember(context.applicationContext, authTabLauncher) {
        authTabLauncher?.let { AuthTabOAuthBrowser(context, it) }
    }

    PutioTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobileAuthRoot(
                runtime = runtime,
                oauthBrowser = oauthBrowser,
                nowPlayingRequests = nowPlayingRequests,
            )
        }
    }
}

@Composable
private fun MobileAuthRoot(
    runtime: MobileOAuthRuntime,
    oauthBrowser: AuthTabOAuthBrowser?,
    nowPlayingRequests: NowPlayingRequests,
) {
    val context = LocalContext.current
    val authController = runtime.authController
    val authState by authController.state.collectAsStateWithLifecycle()
    val rootScope = rememberCoroutineScope()
    val filesViewModel = viewModel<MobileFilesViewModel>(
        factory = remember(authController) { mobileFilesViewModelFactory(authController.state) },
    )
    val accountSettingsViewModel = viewModel<MobileAccountSettingsViewModel>(
        factory = remember(authController) { mobileAccountSettingsViewModelFactory(authController.state) },
    )
    val appConfigViewModel = viewModel<MobileAndroidAppConfigViewModel>(
        factory = remember(authController) { mobileAndroidAppConfigViewModelFactory(authController.state) },
    )
    val searchHistoryViewModel = viewModel<MobileSearchHistoryViewModel>(
        factory = remember(authController, context.applicationContext) {
            mobileSearchHistoryViewModelFactory(
                context.applicationContext as Application,
                authController.state,
            )
        },
    )
    val trashViewModel = viewModel<MobileTrashViewModel>(
        factory = remember(authController) { mobileTrashViewModelFactory(authController.state) },
    )
    val transfersViewModel = viewModel<MobileTransfersViewModel>(
        factory = remember(authController) { mobileTransfersViewModelFactory(authController.state) },
    )

    LaunchedEffect(authController) {
        authController.restoreSession()
    }
    PlaybackSessionBoundaryEffect(
        sessionId = (authState as? MobileAuthState.SignedIn)?.sessionId,
        onSessionLeft = { MobilePlaybackService.stop(context.applicationContext) },
    )
    when (val state = authState) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        -> MobileLoadingState(stringResource(R.string.mobile_auth_restoring))

        is MobileAuthState.ValidatingSession ->
            MobileLoadingState(stringResource(R.string.mobile_auth_validating))

        MobileAuthState.SigningOut ->
            MobileLoadingState(stringResource(R.string.mobile_auth_signing_out))

        is MobileAuthState.SignedOut ->
            MobileSignedOutScreen(
                reason = state.reason,
                canSignIn = authController.isOAuthConfigured,
                onSignIn = {
                    rootScope.launch {
                        when (val authorization = authController.beginSignIn()) {
                            is OAuthLaunchResult.Ready -> {
                                val launchResult = oauthBrowser?.launch(authorization)
                                if (launchResult !is OAuthBrowserLaunchResult.Launched) {
                                    authController.failSignIn()
                                }
                            }

                            OAuthLaunchResult.NotAllowed,
                            OAuthLaunchResult.NotConfigured,
                            OAuthLaunchResult.StorageUnavailable,
                            -> Unit
                        }
                    }
                },
            )

        MobileAuthState.AwaitingOAuthCallback ->
            MobileAuthMessageScreen(
                title = stringResource(R.string.mobile_auth_browser_title),
                message = stringResource(R.string.mobile_auth_browser_message),
                actionLabel = stringResource(R.string.mobile_auth_cancel),
                onAction = { rootScope.launch { authController.cancelSignIn() } },
            )

        is MobileAuthState.ValidationUnavailable ->
            MobileAuthMessageScreen(
                title = stringResource(R.string.mobile_auth_unavailable_title),
                message = stringResource(R.string.mobile_auth_unavailable_message),
                actionLabel = stringResource(R.string.mobile_action_retry),
                onAction = { rootScope.launch { authController.retryValidation() } },
            )

        is MobileAuthState.SignedIn ->
            SignedInMobileRoot(
                runtime = runtime,
                signedIn = state,
                filesViewModel = filesViewModel,
                accountSettingsViewModel = accountSettingsViewModel,
                appConfigViewModel = appConfigViewModel,
                searchHistoryViewModel = searchHistoryViewModel,
                transfersViewModel = transfersViewModel,
                trashViewModel = trashViewModel,
                authController = authController,
                rootScope = rootScope,
                nowPlayingRequests = nowPlayingRequests,
            )
    }
}

@Composable
internal fun MobileSignedOutScreen(
    reason: MobileSignedOutReason?,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
) {
    @StringRes val title: Int
    @StringRes val message: Int
    when (reason) {
        null -> {
            title = R.string.mobile_auth_signed_out_title
            message = R.string.mobile_auth_signed_out_message
        }

        MobileSignedOutReason.SessionExpired -> {
            title = R.string.mobile_auth_expired_title
            message = R.string.mobile_auth_expired_message
        }

        MobileSignedOutReason.SignInFailed -> {
            title = R.string.mobile_auth_failed_title
            message = R.string.mobile_auth_failed_message
        }

        MobileSignedOutReason.OAuthNotConfigured -> {
            title = R.string.mobile_auth_not_configured_title
            message = R.string.mobile_auth_not_configured_message
        }

        MobileSignedOutReason.SecureStorageUnavailable -> {
            title = R.string.mobile_auth_storage_title
            message = R.string.mobile_auth_storage_message
        }
    }

    if (
        !canSignIn ||
        reason == MobileSignedOutReason.OAuthNotConfigured ||
        reason == MobileSignedOutReason.SecureStorageUnavailable
    ) {
        MobileEmptyState(
            title = stringResource(title),
            message = stringResource(message),
        )
        return
    }

    MobileAuthMessageScreen(
        title = stringResource(title),
        message = stringResource(message),
        actionLabel = stringResource(R.string.mobile_auth_sign_in),
        onAction = onSignIn,
    )
}

@Composable
internal fun SignedInMobileRoot(
    runtime: MobileOAuthRuntime,
    signedIn: MobileAuthState.SignedIn,
    filesViewModel: MobileFilesViewModel,
    accountSettingsViewModel: MobileAccountSettingsViewModel,
    appConfigViewModel: MobileAndroidAppConfigViewModel,
    searchHistoryViewModel: MobileSearchHistoryViewModel,
    transfersViewModel: MobileTransfersViewModel,
    trashViewModel: MobileTrashViewModel,
    authController: MobileAuthController,
    rootScope: CoroutineScope,
    playbackPlayerFactory: MobilePlayerFactory = DefaultMobilePlayerFactory,
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
) {
    val account = signedIn.account
    val sessionId = signedIn.sessionId
    val filesRepository = remember(runtime.putioClient) {
        SdkFilesRepository(runtime.putioClient)
    }
    val filesController = remember(filesViewModel, filesRepository, account.userId, sessionId) {
        filesViewModel.controllerFor(
            userId = account.userId,
            sessionId = sessionId,
            repository = filesRepository,
        )
    }
    val accountSettingsRepository = remember(runtime.putioClient) {
        SdkAccountSettingsRepository(runtime.putioClient)
    }
    val accountSettingsController =
        remember(accountSettingsViewModel, accountSettingsRepository, account.userId, sessionId) {
            accountSettingsViewModel.controllerFor(
                userId = account.userId,
                sessionId = sessionId,
                repository = accountSettingsRepository,
            )
        }
    val appConfigRepository = remember(runtime.putioClient) {
        SdkAndroidAppConfigRepository(runtime.putioClient)
    }
    val appConfigController =
        remember(appConfigViewModel, appConfigRepository, account.userId, sessionId) {
            appConfigViewModel.controllerFor(
                userId = account.userId,
                sessionId = sessionId,
                repository = appConfigRepository,
            )
        }
    val searchRepository = remember(runtime.putioClient) { SdkSearchRepository(runtime.putioClient) }
    val historyRepository = remember(runtime.putioClient) { SdkHistoryRepository(runtime.putioClient) }
    val transfersRepository = remember(runtime.putioClient) { SdkTransfersRepository(runtime.putioClient) }
    val searchHistorySession =
        remember(searchHistoryViewModel, runtime.putioClient, account, sessionId) {
            searchHistoryViewModel.controllersFor(
                session = signedIn,
                putioClient = runtime.putioClient,
                searchRepository = searchRepository,
                historyRepository = historyRepository,
                filesItemResolver = filesRepository,
            )
        }
    val transfersController = remember(transfersViewModel, transfersRepository, account.userId, sessionId) {
        transfersViewModel.controllerFor(
            userId = account.userId,
            sessionId = sessionId,
            repository = transfersRepository,
        )
    }
    val trashRepository = remember(runtime.putioClient) { SdkTrashRepository(runtime.putioClient) }
    val trashController = remember(trashViewModel, trashRepository, account.userId, sessionId) {
        trashViewModel.controllerFor(account.userId, sessionId, trashRepository)
    }
    if (trashController == null || filesController == null ||
        accountSettingsController == null ||
        appConfigController == null ||
        searchHistorySession == null ||
        transfersController == null
    ) {
        MobileLoadingState(stringResource(R.string.mobile_state_loading))
        return
    }
    val appContext = LocalContext.current.applicationContext
    val playbackRepository = remember(runtime.putioClient, appConfigController) {
        SdkPlaybackRepository(runtime.putioClient) {
            appConfigController.state.value.playbackPreference()
        }
    }
    val trashState by trashController.state.collectAsStateWithLifecycle()
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val accountSettingsState by accountSettingsController.state.collectAsStateWithLifecycle()
    val appConfigState by appConfigController.state.collectAsStateWithLifecycle()
    val searchState by searchHistorySession.search.state.collectAsStateWithLifecycle()
    val historyState by searchHistorySession.history.state.collectAsStateWithLifecycle()
    val transfersState by transfersController.state.collectAsStateWithLifecycle()
    val navigationFailure by searchHistorySession.navigationFailure.collectAsStateWithLifecycle()
    val recentSearchFailure by searchHistorySession.recentSearchFailure.collectAsStateWithLifecycle()
    val confirmedHistoryEnabled = accountSettingsState.confirmedHistoryEnabled()
    val authoritativeFailure =
        filesState.authoritativeSessionFailure()
            ?: searchState.authoritativeSessionFailure()
            ?: historyState.authoritativeSessionFailure()
            ?: transfersState.authoritativeSessionFailure()
            ?: recentSearchFailure?.takeIf { it is FilesFailure.AuthenticationRequired }
            ?: navigationFailure?.takeIf { it is FilesFailure.AuthenticationRequired }

    AuthoritativeSessionFailureEffect(
        shouldReject = trashState.authenticationFailure != null || authoritativeFailure != null || settingsRequireSessionRejection(
            accountSettingsState = accountSettingsState,
            appConfigState = appConfigState,
        ),
        onReject = authController::rejectAuthoritativeSession,
    )

    LaunchedEffect(searchHistorySession, confirmedHistoryEnabled) {
        confirmedHistoryEnabled?.let { enabled ->
            searchHistorySession.history.dispatch(HistoryEvent.SetEnabled(enabled))
        }
    }

    MobileShell(
        trashController = trashController,
        filesState = filesState,
        filesRepository = filesRepository,
        accountSettingsState = accountSettingsState,
        appConfigState = appConfigState,
        searchHistoryState =
            MobileSearchHistoryState(
                search = searchState,
                history = historyState,
                recentSearchFailure =
                    recentSearchFailure?.takeUnless { it is FilesFailure.AuthenticationRequired },
            ),
        transfersState = transfersState,
        transfersSessionId = sessionId,
        account = account,
        playbackRepository = playbackRepository,
        playbackPlayerFactory = playbackPlayerFactory,
        nowPlayingRequests = nowPlayingRequests,
        sessionId = sessionId,
        onFilesEvent = filesController::dispatch,
        onAccountSettingsEvent = accountSettingsController::dispatch,
        loadTunnelRoutes = {
            accountSettingsRepository.loadTunnelRoutes().also { result ->
                // A 401 here is as authoritative as one from Files or Playback.
                if (result is AccountSettingsRepositoryResult.Failure &&
                    result.failure is AccountSettingsFailure.AuthenticationRequired
                ) {
                    authController.rejectAuthoritativeSession()
                }
            }
        },
        onAppConfigEvent = appConfigController::dispatch,
        onPlaybackAuthenticationRequired = authController::rejectAuthoritativeSession,
        onFilesAuthenticationRequired = authController::rejectAuthoritativeSession,
        searchHistoryActions =
            MobileSearchHistoryActions(
                onQueryChanged = { searchHistorySession.search.updateQuery(it) },
                onSubmit = { searchHistorySession.search.submit() },
                onResult = { searchHistorySession.search.openResult(it.id) },
                onNextPage = { searchHistorySession.search.loadNextPage() },
                onRetry = { searchHistorySession.search.retry() },
                onRecentSearch = { term ->
                    searchHistorySession.search.updateQuery(term.value)
                    searchHistorySession.search.submit()
                },
                onRecentEdit = { searchHistorySession.search.editRecentSearches(it) },
                onRecentRetry = searchHistorySession::retryRecentSearches,
                onHistoryEvent = { searchHistorySession.history.dispatch(it) },
            ),
        onTransfersEvent = transfersController::dispatch,
        resolveTransferFile = { fileId ->
            filesRepository.resolveItem(FilesItemId(fileId.value))
        },
        onTransferAuthenticationRequired = authController::rejectAuthoritativeSession,
        contentNavigation = searchHistorySession.navigation,
        navigationFailure = navigationFailure,
        onDismissNavigationFailure = searchHistorySession::dismissNavigationFailure,
        onSignOut = {
            MobilePlaybackService.stop(appContext)
            rootScope.launch { authController.logout() }
        },
    )
}

/**
 * The playback service outlives the signed-in UI, so every way out of a session, sign-out
 * or an authoritative rejection, must end audio that streams with that session's credential.
 */
@Composable
internal fun PlaybackSessionBoundaryEffect(
    sessionId: MobileAuthSessionId?,
    onSessionLeft: () -> Unit,
) {
    var previous by remember { mutableStateOf<MobileAuthSessionId?>(null) }
    LaunchedEffect(sessionId) {
        if (playbackSessionLeft(previous, sessionId)) onSessionLeft()
        previous = sessionId
    }
}

// A cold start with no session yet is not a departure; a different session is.
internal fun playbackSessionLeft(
    previous: MobileAuthSessionId?,
    current: MobileAuthSessionId?,
): Boolean = previous != null && current != previous

internal fun settingsRequireSessionRejection(
    accountSettingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
): Boolean =
    accountSettingsState.authoritativeSessionFailure() != null ||
        appConfigState.authoritativeSessionFailure() != null

@Composable
internal fun AuthoritativeSessionFailureEffect(
    shouldReject: Boolean,
    onReject: suspend () -> Unit,
) {
    LaunchedEffect(shouldReject) {
        if (shouldReject) {
            onReject()
        }
    }
}

@Composable
internal fun MobileShell(
    filesState: FilesBrowserState,
    filesRepository: FilesRepository? = null,
    trashController: TrashController? = null,
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
    navigationFailure: FilesFailure? = null,
    onDismissNavigationFailure: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    var rejectedNavigation by remember(sessionId) { mutableStateOf<FilesFailure?>(null) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = MobileDestination.fromRoute(backStackEntry?.destination?.route)
    val isPlayback = backStackEntry?.destination?.route == MOBILE_PLAYBACK_ROUTE
    val isTrash = backStackEntry?.destination?.route == MOBILE_TRASH_ROUTE
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
        filesState.current.needsReload, filesState.current.operation, filesState.current.content is FilesContent.Loading) {
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
    // Reopening the app after the task was swiped away must tell the session its task is back.
    LifecycleStartEffect(playbackPlayerFactory, appContext) {
        val handle = playbackPlayerFactory.touchAudioSession(appContext)
        onStopOrDispose { handle.closeQuietly() }
    }
    LaunchedEffect(nowPlayingRequests, playbackPlayerFactory, appContext) {
        nowPlayingRequests.pending.collect { pending ->
            if (!pending) return@collect
            val target = playbackPlayerFactory.activeAudio(appContext)
            // Acknowledged only once the lookup finished: a cancelled lookup leaves it pending.
            nowPlayingRequests.acknowledge()
            if (target == null) return@collect
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
    LaunchedEffect(contentNavigation) {
        contentNavigation.collect { item ->
            if (currentOnFilesEvent(FilesBrowserEvent.OpenExternalItem(item))) {
                navController.navigateTo(MobileDestination.Files)
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
    BackHandler(enabled = !isPlayback && (isTrash || protectTrashRecovery)) {
        if (isTrash) {
            navController.popBackStack()
        } else {
            navController.navigateTo(MobileDestination.Account)
            navController.navigate(MOBILE_TRASH_ROUTE) { launchSingleTop = true }
        }
    }

    if (isPlayback) {
        MobileNavHost(
            navController = navController,
            filesState = filesState,
            filesRepository = filesRepository,
            trashController = trashController,
            accountSettingsState = accountSettingsState,
            appConfigState = appConfigState,
            searchHistoryState = searchHistoryState,
            transfersState = transfersState,
            transfersSessionId = transfersSessionId,
            account = account,
            sessionId = sessionId,
            playbackRepository = playbackRepository,
            playbackPlayerFactory = playbackPlayerFactory,
            onFilesEvent = { onFilesEvent(it) },
            onAccountSettingsEvent = onAccountSettingsEvent,
            loadTunnelRoutes = loadTunnelRoutes,
            onAppConfigEvent = onAppConfigEvent,
            searchHistoryActions = searchHistoryActions,
            onTransfersEvent = onTransfersEvent,
            onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
            onFilesAuthenticationRequired = onFilesAuthenticationRequired,
            onSignOut = onSignOut,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    key(transfersSessionId) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= TabletMinWidth) {
                TabletShell(
                    navController = navController,
                    selectedDestination = selectedDestination,
                    filesState = filesState,
                    filesRepository = filesRepository,
                    trashController = trashController,
                    accountSettingsState = accountSettingsState,
                    appConfigState = appConfigState,
                    searchHistoryState = searchHistoryState,
                    transfersState = transfersState,
                    transfersSessionId = transfersSessionId,
                    account = account,
                    sessionId = sessionId,
                    playbackRepository = playbackRepository,
                    playbackPlayerFactory = playbackPlayerFactory,
                    onFilesEvent = { onFilesEvent(it) },
                    onAccountSettingsEvent = onAccountSettingsEvent,
                    loadTunnelRoutes = loadTunnelRoutes,
                    onAppConfigEvent = onAppConfigEvent,
                    searchHistoryActions = searchHistoryActions,
                    onTransfersEvent = onTransfersEvent,
                    onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
                    onFilesAuthenticationRequired = onFilesAuthenticationRequired,
                    onSignOut = onSignOut,
                )
            } else {
                PhoneShell(
                    navController = navController,
                    selectedDestination = selectedDestination,
                    filesState = filesState,
                    filesRepository = filesRepository,
                    trashController = trashController,
                    accountSettingsState = accountSettingsState,
                    appConfigState = appConfigState,
                    searchHistoryState = searchHistoryState,
                    transfersState = transfersState,
                    transfersSessionId = transfersSessionId,
                    account = account,
                    sessionId = sessionId,
                    playbackRepository = playbackRepository,
                    playbackPlayerFactory = playbackPlayerFactory,
                    onFilesEvent = { onFilesEvent(it) },
                    onAccountSettingsEvent = onAccountSettingsEvent,
                    loadTunnelRoutes = loadTunnelRoutes,
                    onAppConfigEvent = onAppConfigEvent,
                    searchHistoryActions = searchHistoryActions,
                    onTransfersEvent = onTransfersEvent,
                    onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
                    onFilesAuthenticationRequired = onFilesAuthenticationRequired,
                    onSignOut = onSignOut,
                )
            }
        }
    }
    MobileNavigationAlerts(
        navigationFailure = rejectedNavigation ?: navigationFailure,
        transfersState = transfersState,
        onDismissNavigationFailure = {
            if (rejectedNavigation != null) rejectedNavigation = null else onDismissNavigationFailure()
        },
        onTransfersEvent = onTransfersEvent,
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneShell(
    navController: NavHostController,
    selectedDestination: MobileDestination,
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
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
        )
    },
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onPlaybackAuthenticationRequired: suspend () -> Unit,
    onFilesAuthenticationRequired: suspend () -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onTransfersEvent: (TransfersEvent) -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            MobileTopBar(
                destination = selectedDestination,
                isTrash = navController.currentBackStackEntryAsState().value?.destination?.route == MOBILE_TRASH_ROUTE,
                onTrashBack = { navController.popBackStack() },
                filesState = filesState,
                onFilesBack = { onFilesEvent(FilesBrowserEvent.NavigateBack) },
                onFilesEvent = { onFilesEvent(it) },
            )
        },
        bottomBar = {
            Column {
                MobileNowPlayingSlot(navController, playbackPlayerFactory)
                NavigationBar(modifier = Modifier.testTag(MOBILE_NAV_BAR_TAG)) {
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
        },
    ) { padding ->
        MobileNavHost(
            navController = navController,
            filesState = filesState,
            filesRepository = filesRepository,
            trashController = trashController,
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
            deviceClass = AppDiagnostics.DeviceClass.Phone,
            onAppConfigEvent = onAppConfigEvent,
            onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
            onFilesAuthenticationRequired = onFilesAuthenticationRequired,
            searchHistoryActions = searchHistoryActions,
            onTransfersEvent = onTransfersEvent,
            onSignOut = onSignOut,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletShell(
    navController: NavHostController,
    selectedDestination: MobileDestination,
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
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
        )
    },
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onPlaybackAuthenticationRequired: suspend () -> Unit,
    onFilesAuthenticationRequired: suspend () -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onTransfersEvent: (TransfersEvent) -> Unit,
    onSignOut: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
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
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                MobileTopBar(
                    destination = selectedDestination,
                    isTrash = navController.currentBackStackEntryAsState().value?.destination?.route == MOBILE_TRASH_ROUTE,
                    onTrashBack = { navController.popBackStack() },
                    filesState = filesState,
                    onFilesBack = { onFilesEvent(FilesBrowserEvent.NavigateBack) },
                    onFilesEvent = onFilesEvent,
                )
            },
            bottomBar = { MobileNowPlayingSlot(navController, playbackPlayerFactory) },
        ) { padding ->
            MobileNavHost(
                navController = navController,
                filesState = filesState,
                filesRepository = filesRepository,
                trashController = trashController,
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
                deviceClass = AppDiagnostics.DeviceClass.Tablet,
                onAppConfigEvent = onAppConfigEvent,
                onPlaybackAuthenticationRequired = onPlaybackAuthenticationRequired,
                onFilesAuthenticationRequired = onFilesAuthenticationRequired,
                searchHistoryActions = searchHistoryActions,
                onTransfersEvent = onTransfersEvent,
                onSignOut = onSignOut,
                modifier = Modifier.padding(padding),
            )
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
    onFilesBack: () -> Unit,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
) {
    val filesFolderName = filesState.current.folder.name?.takeIf(String::isNotBlank)
    TopAppBar(
        title = {
            Text(
                if (isTrash) {
                    stringResource(R.string.mobile_trash_title)
                } else if (destination == MobileDestination.Files && filesFolderName != null) {
                    filesFolderName
                } else {
                    stringResource(destination.labelRes)
                },
            )
        },
        navigationIcon = {
            if (isTrash) {
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
) {
    val handle = rememberNowPlaying(playerFactory)
    val nowPlaying = handle.nowPlaying ?: return
    MobileNowPlayingBar(
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

@Composable
private fun MobileNavHost(
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
    modifier: Modifier = Modifier,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
        )
    },
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
            )
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
            val subtitleStartupPolicy =
                (accountSettingsState.content as? AccountSettingsContent.Ready)
                    ?.preferences
                    ?.let { SubtitleStartupPolicy(it.showSubtitles, it.autoSelectSubtitles) }
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
        onPlayerFailure = { failure, positionMillis ->
            controller.dispatch(PlaybackEvent.PlayerFailed(failure, positionMillis))
        },
        onBack = onBack,
        subtitleStartupPolicy = subtitleStartupPolicy,
        autoplayNextVideo = autoplayNextVideo,
        onPlaybackEnded = { controller.dispatch(PlaybackEvent.PlayerEnded) },
        playerFactory = playerFactory,
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

internal fun FilesBrowserState.authoritativeSessionFailure(): FilesFailure? =
    stack.asReversed().firstNotNullOfOrNull { folder ->
        val contentFailure = folder.content.authoritativeSessionFailure()
        val operationFailure = (folder.operation as? FilesFolderOperation.Failed)?.failure
        listOfNotNull(contentFailure, operationFailure)
            .firstOrNull { it is FilesFailure.AuthenticationRequired }
    }

internal fun FilesContent.authoritativeSessionFailure(): FilesFailure? = when (this) {
    is FilesContent.Failed -> failure
    is FilesContent.Empty -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Ready -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Loading -> null
}?.takeIf { it is FilesFailure.AuthenticationRequired }

internal fun SearchState.authoritativeSessionFailure(): FilesFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        is SearchContent.Empty -> (value.paging as? io.putdotio.android.search.SearchPaging.Failed)?.failure
        is SearchContent.Ready -> (value.paging as? io.putdotio.android.search.SearchPaging.Failed)?.failure
        SearchContent.Idle,
        is SearchContent.Debouncing,
        is SearchContent.Loading,
        -> null
    }?.takeIf { it is FilesFailure.AuthenticationRequired }

internal fun HistoryState.authoritativeSessionFailure(): FilesFailure? =
    listOfNotNull(
        authoritativeFailure,
        when (val value = content) {
            is HistoryContent.Failed -> value.failure
            is HistoryContent.Ready ->
                (value.paging as? io.putdotio.android.history.HistoryPaging.Failed)?.failure
            HistoryContent.Disabled,
            HistoryContent.Empty,
            is HistoryContent.Loading,
            -> null
        },
        (clearing as? io.putdotio.android.history.HistoryClearing.Failed)?.failure,
    ).firstOrNull { it is FilesFailure.AuthenticationRequired }

internal fun TransfersState.authoritativeSessionFailure(): FilesFailure? =
    listOfNotNull(
        when (val value = content) {
            is TransfersContent.Failed -> value.failure
            is TransfersContent.Ready -> (value.paging as? TransfersPaging.Failed)?.failure
            TransfersContent.Empty,
            is TransfersContent.InitialLoading,
            -> null
        },
        (refresh as? TransfersRefresh.Failed)?.failure,
        (mutation as? TransferMutation.Failed)?.failure,
    ).firstOrNull { it is FilesFailure.AuthenticationRequired }

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

private fun NavHostController.navigateTo(destination: MobileDestination) {
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

private fun NavHostController.navigateToPlayback(
    fileId: FilesItemId,
    name: String,
    mediaType: PlaybackMediaType,
    replaceCurrentPlayback: Boolean = false,
) {
    navigate("playback/${fileId.value}?name=${Uri.encode(name)}&media=${mediaType.name}") {
        if (replaceCurrentPlayback) popUpTo(MOBILE_PLAYBACK_ROUTE) { inclusive = true }
    }
}
