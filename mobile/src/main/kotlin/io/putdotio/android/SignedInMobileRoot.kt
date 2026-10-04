package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.account.MobileAccountSettingsViewModel
import io.putdotio.android.account.MobileAndroidAppConfigViewModel
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.MobileDownloadsViewModel
import io.putdotio.android.downloads.OfflinePlaybackRepository
import io.putdotio.android.downloads.OfflinePositionsResume
import io.putdotio.android.downloads.OfflineResume
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.MobileFilesViewModel
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.SdkHistoryRepository
import io.putdotio.android.history.authoritativeSessionFailure
import io.putdotio.android.playback.ConvertingPlaybackRepository
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.dispatch
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.search.ActiveSearchHistorySession
import io.putdotio.android.search.MobileSearchHistoryViewModel
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.authoritativeSessionFailure
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.authoritativeSessionFailure
import io.putdotio.android.settings.confirmedHistoryEnabled
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.sharing.MobilePublicLinksViewModel
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.sharing.SdkPublicLinksRepository
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransfersViewModel
import io.putdotio.android.transfers.SdkTransfersRepository
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransferRetryOutcome
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersController
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.trash.MobileTrashViewModel
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.android.trash.TrashController
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    downloadsViewModel: MobileDownloadsViewModel? = null,
    publicLinksViewModel: MobilePublicLinksViewModel? = null,
    playbackPlayerFactory: MobilePlayerFactory = DefaultMobilePlayerFactory,
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
    deepLinkRequests: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
    transferDraft: MobileTransferDraft = remember { MobileTransferDraft() },
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
    val downloadsContext = LocalContext.current.applicationContext
    val downloadsController = remember(downloadsViewModel, downloadsContext, account.userId, sessionId) {
        downloadsViewModel?.controllerFor(downloadsContext, account.userId, sessionId)
    }
    val publicLinksRepository = remember(runtime.putioClient) { SdkPublicLinksRepository(runtime.putioClient) }
    val publicLinksController =
        remember(publicLinksViewModel, publicLinksRepository, account.userId, sessionId) {
            publicLinksViewModel?.controllerFor(account.userId, sessionId, publicLinksRepository)
        }
    // Each view model hands out null once its session is no longer current.
    val controllersReady = trashController != null && filesController != null &&
        accountSettingsController != null && appConfigController != null &&
        searchHistorySession != null && transfersController != null
    if (!controllersReady) {
        MobileLoadingState(stringResource(R.string.mobile_state_loading))
        return
    }
    SignedInMobileSession(
        runtime = runtime,
        signedIn = signedIn,
        filesRepository = filesRepository,
        accountSettingsRepository = accountSettingsRepository,
        filesController = filesController,
        accountSettingsController = accountSettingsController,
        appConfigController = appConfigController,
        searchHistorySession = searchHistorySession,
        transfersController = transfersController,
        trashController = trashController,
        downloadsController = downloadsController,
        publicLinksController = publicLinksController,
        authController = authController,
        rootScope = rootScope,
        playbackPlayerFactory = playbackPlayerFactory,
        nowPlayingRequests = nowPlayingRequests,
        deepLinkRequests = deepLinkRequests,
        transferDraft = transferDraft,
    )
}

@Composable
private fun SignedInMobileSession(
    runtime: MobileOAuthRuntime,
    signedIn: MobileAuthState.SignedIn,
    filesRepository: SdkFilesRepository,
    accountSettingsRepository: SdkAccountSettingsRepository,
    filesController: FilesBrowserController,
    accountSettingsController: AccountSettingsController,
    appConfigController: AndroidAppConfigController,
    searchHistorySession: ActiveSearchHistorySession,
    transfersController: TransfersController,
    trashController: TrashController,
    downloadsController: DownloadsController?,
    publicLinksController: PublicLinksController?,
    authController: MobileAuthController,
    rootScope: CoroutineScope,
    playbackPlayerFactory: MobilePlayerFactory,
    nowPlayingRequests: NowPlayingRequests,
    deepLinkRequests: MobileDeepLinkRequests,
    transferDraft: MobileTransferDraft,
) {
    val account = signedIn.account
    val sessionId = signedIn.sessionId
    val appContext = LocalContext.current.applicationContext
    val playbackRepository = remember(
        runtime,
        appConfigController,
        accountSettingsController,
        downloadsController,
        account.userId,
    ) {
        val streaming = ConvertingPlaybackRepository(runtime.putioClient) {
            appConfigController.state.value.playbackPreference()
        }
        downloadsController?.offlineFirstPlayback(
            MobileDownloadCache.get(appContext),
            runtime,
            account.userId,
            accountSettingsController,
            streaming,
        ) ?: streaming
    }
    RememberResumeSettingEffect(runtime, account.userId, accountSettingsController)
    val reportingPlayerFactory = remember(runtime, sessionId, accountSettingsController, playbackPlayerFactory) {
        runtime.playbackReporting.factoryFor(sessionId, accountSettingsController.state, playbackPlayerFactory)
    }
    LaunchedEffect(runtime, filesController) {
        runtime.playbackReporting.savedPositions.collect { saved ->
            filesController.dispatch(
                FilesBrowserEvent.PlaybackPositionReported(FilesItemId(saved.fileId), saved.seconds),
            )
        }
    }
    val trashState by trashController.state.collectAsStateWithLifecycle()
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val accountSettingsState by accountSettingsController.state.collectAsStateWithLifecycle()
    val appConfigState by appConfigController.state.collectAsStateWithLifecycle()
    val searchState by searchHistorySession.search.state.collectAsStateWithLifecycle()
    val historyState by searchHistorySession.history.state.collectAsStateWithLifecycle()
    val transfersState by transfersController.state.collectAsStateWithLifecycle()
    val publicLinksState = publicLinksController?.state?.collectAsStateWithLifecycle()?.value
    val navigationFailure by searchHistorySession.navigationFailure.collectAsStateWithLifecycle()
    val recentSearchFailure by searchHistorySession.recentSearchFailure.collectAsStateWithLifecycle()
    val confirmedHistoryEnabled = accountSettingsState.confirmedHistoryEnabled()
    val authoritativeFailure =
        filesState.authoritativeSessionFailure()
            ?: searchState.authoritativeSessionFailure()
            ?: historyState.authoritativeSessionFailure()
            ?: transfersState.authoritativeSessionFailure()
            ?: publicLinksState?.authenticationFailure
            ?: recentSearchFailure?.takeIf { it is PutioFailure.AuthenticationRequired }
            ?: navigationFailure?.takeIf { it is PutioFailure.AuthenticationRequired }

    AuthoritativeSessionFailureEffect(
        shouldReject = trashState.authenticationFailure != null ||
            authoritativeFailure != null ||
            settingsRequireSessionRejection(
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
        transferDraft = transferDraft,
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
                    recentSearchFailure?.takeUnless { it is PutioFailure.AuthenticationRequired },
            ),
        transfersState = transfersState,
        transfersSessionId = sessionId,
        account = account,
        playbackRepository = playbackRepository,
        playbackPlayerFactory = reportingPlayerFactory,
        nowPlayingRequests = nowPlayingRequests,
        deepLinkRequests = deepLinkRequests,
        onOpenFile = searchHistorySession::openFile,
        onShareItem = { item -> MobileFileShareService.start(appContext, item.id, item.name) },
        fileDragOut = remember(appContext, sessionId) { MobileFileDragOut(appContext, sessionId) },
        downloadsController = downloadsController,
        publicLinksController = publicLinksController,
        sessionId = sessionId,
        onFilesEvent = filesController::dispatch,
        onAccountSettingsEvent = accountSettingsController::dispatch,
        loadTunnelRoutes = {
            accountSettingsRepository.loadTunnelRoutes().also { result ->
                // A 401 here is as authoritative as one from Files or Playback.
                if (result is AccountSettingsRepositoryResult.Failure &&
                    result.failure.putioFailure is PutioFailure.AuthenticationRequired
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

/** Offline playback falls back to the resume setting this device last confirmed. */
@Composable
private fun RememberResumeSettingEffect(
    runtime: MobileOAuthRuntime,
    userId: Long,
    accountSettingsController: AccountSettingsController,
) {
    val settings by accountSettingsController.state.collectAsStateWithLifecycle()
    val confirmed = settings.confirmedResumePlayback()
    LaunchedEffect(runtime, userId, confirmed) {
        confirmed?.let { runtime.offlinePositions?.store(userId)?.rememberResumeSetting(it) }
    }
}

/** Completed downloads play from this device, resuming where this device last left them. */
private fun DownloadsController.offlineFirstPlayback(
    downloads: MobileDownloadCache,
    runtime: MobileOAuthRuntime,
    userId: Long,
    accountSettingsController: AccountSettingsController,
    streaming: PlaybackRepository,
): PlaybackRepository =
    OfflinePlaybackRepository(
        downloads = state,
        delegate = streaming,
        credentialUrl = PutioCredentialUrl::of,
        localCopyAvailable = { fileId ->
            withContext(Dispatchers.IO) { downloads.hasLocalCopy(userId, fileId) }.also { present ->
                if (!present) dispatch(DownloadsEvent.LocalCopyMissing(fileId))
            }
        },
        resume = runtime.offlinePositions?.let { positions ->
            OfflinePositionsResume(positions, userId, accountSettingsController.state)
        } ?: OfflineResume.Off,
        requestedUrl = { fileId -> withContext(Dispatchers.IO) { downloads.requestedUrl(userId, fileId) } },
    )

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

internal fun TransfersState.authoritativeSessionFailure(): PutioFailure? =
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
        (retryOutcome as? TransferRetryOutcome.Failed)?.failure,
    ).firstOrNull { it is PutioFailure.AuthenticationRequired }
