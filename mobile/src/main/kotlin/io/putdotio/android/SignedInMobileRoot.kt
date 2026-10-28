package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.putdotio.android.account.MobileAccountSettingsViewModel
import io.putdotio.android.account.MobileAndroidAppConfigViewModel
import io.putdotio.android.account.MobileProductAnalyticsPrompt
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.MobileDownloadsViewModel
import io.putdotio.android.downloads.OfflinePlaybackRepository
import io.putdotio.android.downloads.OfflinePositionsResume
import io.putdotio.android.downloads.OfflineResume
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.MobileContinueWatching
import io.putdotio.android.files.MobileContinueWatchingStore
import io.putdotio.android.files.MobileFilesActionSources
import io.putdotio.android.files.MobileFilesActionsController
import io.putdotio.android.files.MobileFilesViewModel
import io.putdotio.android.files.MobileVlcIntentHandoff
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.SdkFilesSubtitleLanguages
import io.putdotio.android.files.SdkFilesStreamUrls
import io.putdotio.android.files.SdkFilesWatchedRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.confirmedGone
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
import io.putdotio.android.search.SearchController
import io.putdotio.android.search.authoritativeSessionFailure
import io.putdotio.android.settings.AccountSettingsController
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.acceptedProductAnalyticsChoices
import io.putdotio.android.settings.canRecordProductAnalyticsAnswer
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.authoritativeSessionFailure
import io.putdotio.android.settings.captureSavedAppConfig
import io.putdotio.android.settings.captureSavedSettings
import io.putdotio.android.settings.confirmedHistoryEnabled
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.settings.diagnosticsAllowed
import io.putdotio.android.settings.productAnalyticsAllowed
import io.putdotio.android.settings.productAnalyticsPromptDue
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.share.MobileFileDragOut
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.sharing.MobilePublicLinksViewModel
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.sharing.SdkPublicLinksRepository
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.PutioDiagnostics
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
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.widgets.MobileWidgets
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import io.putdotio.android.playback.PlaybackRepositoryResult

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
    val continueWatching = remember(filesViewModel, downloadsContext, account.userId, sessionId) {
        filesViewModel.continueWatchingFor(account.userId, sessionId) {
            MobileContinueWatchingStore(downloadsContext, account.userId)
        }
    }
    val filesActions =
        remember(
            filesViewModel, filesController, trashRepository, continueWatching, searchHistorySession,
            account.userId, sessionId,
        ) {
            filesController?.let { browser ->
                filesViewModel.actionsFor(account.userId, sessionId) { scope ->
                    MobileFilesActionsController(
                        scope = scope,
                        sources = MobileFilesActionSources(
                            watched = SdkFilesWatchedRepository(runtime.putioClient),
                            streamUrls = SdkFilesStreamUrls(runtime.putioClient),
                            trash = trashRepository,
                        ),
                        onFilesEvent = browser::dispatch,
                        onPositionSaved = { fileId, seconds ->
                            // Finished or cleared, the video leaves Continue watching; an offline position follows.
                            continueWatching?.positionSaved(fileId, seconds)
                            searchHistorySession?.search?.positionSaved(fileId, seconds)
                            runtime.offlinePositions?.afterWrite(
                                account.userId, fileId.value, seconds, PlaybackRepositoryResult.Success(Unit),
                            )
                        },
                        onAuthenticationRequired = authController::rejectAuthoritativeSession,
                        handoff = MobileVlcIntentHandoff(downloadsContext),
                    )
                }
            }
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
        continueWatching = continueWatching,
        filesActions = filesActions,
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
    continueWatching: MobileContinueWatching?,
    filesActions: MobileFilesActionsController?,
) {
    val account = signedIn.account
    val sessionId = signedIn.sessionId
    val appContext = LocalContext.current.applicationContext
    val signOut = remember(authController, sessionId, downloadsController) {
        MobileSignOut(authController, sessionId, downloadsController)
    }
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
    LaunchedEffect(accountSettingsController) { accountSettingsController.state.captureSavedSettings() }
    LaunchedEffect(appConfigController) { appConfigController.state.captureSavedAppConfig() }
    LaunchedEffect(accountSettingsController) {
        accountSettingsController.state.collect { state ->
            state.diagnosticsAllowed()?.let(PutioDiagnostics::update)
            state.productAnalyticsAllowed()?.let { ProductAnalytics.update(account.userId, it) }
        }
    }
    ProductAnalyticsAnswerEffect(sessionId, accountSettingsController, appConfigController)
    val reportingPlayerFactory = remember(runtime, sessionId, accountSettingsController, playbackPlayerFactory) {
        runtime.playbackReporting.factoryFor(sessionId, accountSettingsController.state, playbackPlayerFactory)
    }
    SavedPositionEffects(
        runtime, filesController, filesRepository, continueWatching, searchHistorySession.search, authController,
    )
    val trashState by trashController.state.collectAsStateWithLifecycle()
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val accountSettingsState by accountSettingsController.state.collectAsStateWithLifecycle()
    val appConfigState by appConfigController.state.collectAsStateWithLifecycle()
    val searchState by searchHistorySession.search.state.collectAsStateWithLifecycle()
    val historyState by searchHistorySession.history.state.collectAsStateWithLifecycle()
    val searchFolderNames by searchHistorySession.folderNames.names.collectAsStateWithLifecycle()
    val folderNameFailure by searchHistorySession.folderNames.authenticationFailure.collectAsStateWithLifecycle()
    val transfersState by transfersController.state.collectAsStateWithLifecycle()
    TransfersWidgetEffect(transfersState.content, MobileSessionKey(account.userId, sessionId))
    val publicLinksState = publicLinksController?.state?.collectAsStateWithLifecycle()?.value
    val navigationFailure by searchHistorySession.navigationFailure.collectAsStateWithLifecycle()
    val recentSearchFailure by searchHistorySession.recentSearchFailure.collectAsStateWithLifecycle()
    val confirmedHistoryEnabled = accountSettingsState.confirmedHistoryEnabled()
    val authoritativeFailure = listOfNotNull(
        filesState.authoritativeSessionFailure(),
        searchState.authoritativeSessionFailure(),
        historyState.authoritativeSessionFailure(),
        transfersState.authoritativeSessionFailure(),
        publicLinksState?.authenticationFailure,
        recentSearchFailure?.takeIf { it is PutioFailure.AuthenticationRequired },
        navigationFailure?.takeIf { it is PutioFailure.AuthenticationRequired },
        folderNameFailure,
    ).firstOrNull()

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
                folderNames = searchFolderNames,
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
        fileDragOut = remember(appContext, account.userId, sessionId) {
            MobileFileDragOut(appContext, MobileSessionKey(account.userId, sessionId))
        },
        downloadsController = downloadsController,
        publicLinksController = publicLinksController,
        folderCreator = filesRepository,
        continueWatching = continueWatching,
        subtitleLanguages = remember(runtime.putioClient) { SdkFilesSubtitleLanguages(runtime.putioClient) },
        filesActions = filesActions,
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
                onScopeChanged = { searchHistorySession.search.scope = it },
                onFilterSelected = { searchHistorySession.search.filter = it },
            ),
        onTransfersEvent = transfersController::dispatch,
        resolveTransferFile = { fileId -> filesRepository.resolveItem(FilesItemId(fileId.value)) },
        onTransferAuthenticationRequired = authController::rejectAuthoritativeSession,
        contentNavigation = searchHistorySession.navigation,
        navigationFailure = navigationFailure,
        onDismissNavigationFailure = searchHistorySession::dismissNavigationFailure,
        onSignOut = { removeDownloads ->
            MobilePlaybackService.stop(appContext)
            signOut.start(rootScope, removeDownloads)
        },
    )
    ProductAnalyticsPromptHost(
        sessionId = sessionId,
        accountSettingsState = accountSettingsState,
        appConfigState = appConfigState,
        opensLaunchLink = {
            deepLinkRequests.receivedLink || transferDraft.receivedShare || transferDraft.state.value.open
        },
        onSettingsEvent = { accountSettingsController.dispatch(it) },
        onAppConfigEvent = { appConfigController.dispatch(it) },
    )
}

/**
 * A choice put.io accepts, from the prompt or Privacy controls, answers the prompt. The answer waits
 * while the config is unloaded or busy rather than being dropped.
 */
@Composable
private fun ProductAnalyticsAnswerEffect(
    sessionId: MobileAuthSessionId,
    accountSettingsController: AccountSettingsController,
    appConfigController: AndroidAppConfigController,
) {
    var answerPending by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(accountSettingsController) {
        accountSettingsController.state.acceptedProductAnalyticsChoices().collect { answerPending = true }
    }
    LaunchedEffect(appConfigController, answerPending) {
        if (!answerPending) return@LaunchedEffect
        appConfigController.state.collect { state ->
            when (state.canRecordProductAnalyticsAnswer()) {
                null -> answerPending = false
                true -> appConfigController.dispatch(
                    AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.ProductAnalyticsPromptAnswered),
                )
                false -> Unit
            }
        }
    }
}

/**
 * Asks once per launch, decided when the account settings and app config first land. A launch that
 * opens a link or a shared transfer skips the ask, so the sheet never covers what the viewer came for.
 */
@Composable
internal fun ProductAnalyticsPromptHost(
    sessionId: MobileAuthSessionId,
    accountSettingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    opensLaunchLink: () -> Boolean,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
) {
    var asking by rememberSaveable(sessionId) { mutableStateOf<Boolean?>(null) }
    val due = productAnalyticsPromptDue(accountSettingsState, appConfigState)
    LaunchedEffect(due) {
        if (asking == null && due != null) asking = due && !opensLaunchLink()
    }
    if (asking == true) {
        MobileProductAnalyticsPrompt(
            settingsState = accountSettingsState,
            appConfigState = appConfigState,
            onSettingsEvent = onSettingsEvent,
            onAppConfigEvent = onAppConfigEvent,
            onDismiss = { asking = false },
        )
    }
}

/**
 * Positions put.io accepted update the cached Files rows and Continue watching, whose entries are
 * read again once per session. A 401 from that read is as authoritative as one from Files.
 */
@Composable
private fun SavedPositionEffects(
    runtime: MobileOAuthRuntime,
    filesController: FilesBrowserController,
    filesRepository: SdkFilesRepository,
    continueWatching: MobileContinueWatching?,
    search: SearchController,
    authController: MobileAuthController,
) {
    LaunchedEffect(runtime, filesController, continueWatching, search) {
        runtime.playbackReporting.savedPositions.collect { saved ->
            val fileId = FilesItemId(saved.fileId)
            filesController.dispatch(FilesBrowserEvent.PlaybackPositionReported(fileId, saved.seconds))
            continueWatching?.positionSaved(fileId, saved.seconds)
            search.positionSaved(fileId, saved.seconds)
        }
    }
    LaunchedEffect(continueWatching, filesRepository) {
        if (continueWatching?.reconcile(filesRepository::resolveItem) != null) {
            authController.rejectAuthoritativeSession()
        }
    }
    // A delete the fresh read confirmed takes the video, or a deleted folder's videos, out of Continue watching.
    LaunchedEffect(filesController, continueWatching) {
        val row = continueWatching ?: return@LaunchedEffect
        filesController.state
            .map { state ->
                state.stack.mapNotNull { folder ->
                    folder.deleteOutcome?.takeIf { it.confirmedGone().isNotEmpty() }
                }
            }
            .distinctUntilChanged()
            .collect { gone -> row.removed(gone) }
    }
    LaunchedEffect(continueWatching, filesRepository) {
        if (continueWatching?.recheckAfterDeletes(filesRepository::resolveItem) != null) {
            authController.rejectAuthoritativeSession()
        }
    }
}

/**
 * One confirmed sign-out per session; a second confirmation while the first runs is ignored. It first
 * deletes [downloads] when the viewer asked: deletes reach Media3 before the session ends, so they run
 * even though sign-out parks this account's transfers next. It finishes even if the screen is recreated
 * meanwhile, and leaves a session that already ended, or was replaced, alone.
 */
private class MobileSignOut(
    private val authController: MobileAuthController,
    private val sessionId: MobileAuthSessionId,
    private val downloads: DownloadsController?,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope, removeDownloads: Boolean) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            withContext(NonCancellable) {
                if (removeDownloads) downloads?.removeAll()
                val current = authController.state.value
                if (current is MobileAuthState.SignedIn && current.sessionId == sessionId) authController.logout()
            }
        }
    }
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
        if (shouldReject) onReject()
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

/** What the Transfers screen reads reaches the home-screen widget without another request. */
@Composable
private fun TransfersWidgetEffect(content: TransfersContent, session: MobileSessionKey) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(content, session) {
        val items = when (content) {
            is TransfersContent.Ready -> content.items
            TransfersContent.Empty -> emptyList()
            else -> return@LaunchedEffect
        }
        MobileWidgets.transfers(appContext).show(session, items)
    }
}
