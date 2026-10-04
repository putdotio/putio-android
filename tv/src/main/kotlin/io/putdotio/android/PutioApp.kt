package io.putdotio.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.SdkFilesStreamUrls
import io.putdotio.android.files.SdkFilesWatchedRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.SdkHistoryRepository
import io.putdotio.android.history.authoritativeSessionFailure
import io.putdotio.android.search.AppConfigRecentSearchStore
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchOutput
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.authoritativeSessionFailure
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.playback.subtitleStartupPolicy
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.authoritativeSessionFailure
import io.putdotio.android.settings.confirmedHistoryEnabled
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.settings.confirmedTrashEnabled
import io.putdotio.android.tv.files.launchVlc
import io.putdotio.android.tv.files.tvMessageText
import androidx.compose.ui.platform.LocalContext
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashState
import io.putdotio.android.playback.SdkPlaybackPositionRepository
import io.putdotio.android.playback.ConvertingPlaybackRepository
import io.putdotio.android.tv.player.TvPlaybackLayer
import io.putdotio.android.tv.player.TvPlaybackRoute
import io.putdotio.android.tv.account.TvAccountScreen
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvExternalOpen
import io.putdotio.android.tv.TvLaunchRequest
import io.putdotio.android.tv.TvLaunchRequests
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvSession
import io.putdotio.android.tv.TvSessionDependencies
import io.putdotio.android.tv.tvAppConfigRepository
import io.putdotio.android.tv.TvSessionViewModel
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.trash.TvTrashScreen
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.tv.search.TvRecentSearchActions
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.android.tv.tvSessionViewModelFactory
import kotlinx.coroutines.launch
import io.putdotio.android.settings.putioFailure

/** TV root: the generated Compose for TV scheme, then whichever screen the session state names. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PutioApp(runtime: TvAuthRuntime, launchRequests: TvLaunchRequests) {
    val authController = runtime.authController
    val sessionViewModel: TvSessionViewModel = viewModel(factory = tvSessionViewModelFactory(authController.state))
    val authState by authController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The app's start, and again after a session ended with no screen (a 401 to system search),
    // which leaves Initializing: restoring then offers a code when no session is left.
    LaunchedEffect(authController) {
        authController.state.collect { if (it == TvAuthState.Initializing) authController.restoreSession() }
    }

    MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
        when (val state = authState) {
            TvAuthState.Initializing, TvAuthState.RestoringSession ->
                TvStatusScreen(stringResource(R.string.tv_session_restoring))
            is TvAuthState.ValidatingSession -> TvStatusScreen(stringResource(R.string.tv_session_validating))
            TvAuthState.SigningOut -> TvStatusScreen(stringResource(R.string.tv_session_signing_out))
            is TvAuthState.ValidationUnavailable ->
                TvStatusScreen(
                    title = stringResource(R.string.tv_session_unavailable_title),
                    message = stringResource(R.string.tv_session_unavailable_message),
                    action = stringResource(R.string.tv_session_retry),
                    onAction = { scope.launch { authController.retryValidation() } },
                )
            is TvAuthState.Linking ->
                TvLinkScreen(
                    phase = state.phase,
                    sessionExpired = state.sessionExpired,
                    onRequestNewCode = { scope.launch { authController.requestNewCode() } },
                )
            is TvAuthState.SignedIn ->
                TvSignedInApp(
                    signedIn = state,
                    runtime = runtime,
                    sessionViewModel = sessionViewModel,
                    launchRequests = launchRequests,
                    onSignOut = { scope.launch { authController.logout() } },
                    onSessionRejected = { authController.rejectAuthoritativeSession(state.sessionId) },
                )
        }
    }
}

@Composable
private fun TvSignedInApp(
    signedIn: TvAuthState.SignedIn,
    runtime: TvAuthRuntime,
    sessionViewModel: TvSessionViewModel,
    launchRequests: TvLaunchRequests,
    onSignOut: () -> Unit,
    onSessionRejected: suspend () -> Unit,
) {
    val dependencies = remember(runtime.putioClient) {
        val filesRepository = SdkFilesRepository(runtime.putioClient)
        TvSessionDependencies(
            filesRepository = filesRepository,
            searchRepository = SdkSearchRepository(runtime.putioClient),
            historyRepository = SdkHistoryRepository(runtime.putioClient),
            trashRepository = SdkTrashRepository(runtime.putioClient),
            settingsRepository = SdkAccountSettingsRepository(runtime.putioClient),
            appConfigRepository = tvAppConfigRepository(runtime.putioClient),
            watchedRepository = SdkFilesWatchedRepository(runtime.putioClient),
            streamUrls = SdkFilesStreamUrls(runtime.putioClient),
            filesItemResolver = filesRepository,
            recentSearchStore = { scope -> AppConfigRecentSearchStore(runtime.putioClient, scope) },
            playbackRepository = { preference -> ConvertingPlaybackRepository(runtime.putioClient, preference) },
            writePlaybackPosition = SdkPlaybackPositionRepository(runtime.putioClient)::write,
            watchNext = runtime.watchNext::recorder,
        )
    }
    val pendingLaunch by launchRequests.pending.collectAsStateWithLifecycle()
    // Looked up every composition, not remembered: the view model closes the session on its
    // own auth collector, and a cached closed controller would silently swallow events.
    val session = sessionViewModel.sessionFor(signedIn.account, signedIn.sessionId, dependencies)
    if (session == null) {
        TvStatusScreen(stringResource(R.string.tv_session_restoring))
        return
    }
    TvSessionShell(
        session = session,
        account = signedIn.account,
        sessionKey = signedIn.account.userId to signedIn.sessionId.value,
        onSignOut = onSignOut,
        onSessionRejected = onSessionRejected,
        loadTunnelRoutes = dependencies.settingsRepository::loadTunnelRoutes,
        pendingLaunch = pendingLaunch,
        onLaunchHandled = launchRequests::acknowledge,
    )
}

/** One signed-in session's shell and player; the controllers all come from [session]. */
@Composable
internal fun TvSessionShell(
    session: TvSession,
    account: TvAccount,
    /** Changes with the signed-in session so one account's saved UI state never greets the next. */
    sessionKey: Any,
    onSignOut: () -> Unit,
    onSessionRejected: suspend () -> Unit,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    /** A file or search the system asked for; it is handled once the shell exists. */
    pendingLaunch: TvLaunchRequest? = null,
    onLaunchHandled: (TvLaunchRequest) -> Unit = {},
) {
    val filesState by session.files.state.collectAsStateWithLifecycle()
    val searchState by session.search.state.collectAsStateWithLifecycle()
    val historyState by session.history.state.collectAsStateWithLifecycle()
    val recentSearchFailure by session.recentSearchFailure.collectAsStateWithLifecycle()
    val historyOpenFailure by session.historyOpenFailure.collectAsStateWithLifecycle()
    val linkOpenFailure by session.links.failure.collectAsStateWithLifecycle()
    val fileActionFailure by session.fileActionFailure.collectAsStateWithLifecycle()
    val trashState by session.trash.state.collectAsStateWithLifecycle()
    val settingsState by session.settings.state.collectAsStateWithLifecycle()
    val appConfigState by session.appConfig.state.collectAsStateWithLifecycle()
    val positionWriteRejected by session.playbackReporting.authenticationRejected.collectAsStateWithLifecycle()
    // A 401 from the proxy list is as authoritative as one from any controller.
    var tunnelRoutesRejected by remember(session) { mutableStateOf(false) }
    val controllerRejected = listOf(
        filesState.authoritativeSessionFailure(),
        trashState.authenticationFailure,
        settingsState.authoritativeSessionFailure(),
        appConfigState.authoritativeSessionFailure(),
        searchState.authoritativeSessionFailure(),
        historyState.authoritativeSessionFailure(),
    ).any { it != null }
    val sideActionRejected = listOf(recentSearchFailure, fileActionFailure, historyOpenFailure, linkOpenFailure)
        .any { it is PutioFailure.AuthenticationRequired }
    val sessionRejected = controllerRejected || tunnelRoutesRejected || positionWriteRejected || sideActionRejected
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }
    // The controller starts from the setting read at validation; each confirmed value from
    // the settings controller supersedes it, so the Account toggle flips the History pane.
    // An unsettled History write confirms nothing and leaves the last value in place, like mobile.
    val confirmedHistoryEnabled = settingsState.confirmedHistoryEnabled()
    LaunchedEffect(session, confirmedHistoryEnabled) {
        confirmedHistoryEnabled?.let { session.history.dispatch(HistoryEvent.SetEnabled(it)) }
    }

    // A search result or a history event plays when it is media; anything else opens in Files
    // and the shell switches destinations. A refused jump stays put with an explanation.
    var requestedDestination by remember(session) { mutableStateOf<TvDestination?>(null) }
    var openRejected by remember(session) { mutableStateOf(false) }
    var historyOpenRejected by remember(session) { mutableStateOf(false) }
    val openExternal: (FilesItem, FilesOpenOrigin) -> Boolean = { item, origin ->
        session.openPick(item, origin, onInFiles = { requestedDestination = TvDestination.Files })
    }
    LaunchedEffect(session) {
        session.search.outputs.collect { output ->
            when (output) {
                is SearchOutput.OpenResult -> openRejected = !openExternal(output.item, FilesOpenOrigin.SEARCH)
            }
        }
    }
    LaunchedEffect(session) {
        session.historyOpens.collect { item -> historyOpenRejected = !openExternal(item, FilesOpenOrigin.HISTORY) }
    }
    val linkNotice = rememberTvLaunchHandling(
        session = session,
        pendingLaunch = pendingLaunch,
        onLaunchHandled = onLaunchHandled,
        linkOpenFailure = linkOpenFailure,
        onDestination = { requestedDestination = it },
        onSearchLaunched = { openRejected = false },
    )
    // A restore changes Files behind the browser's cache: one item's folder, or every folder.
    LaunchedEffect(session, trashState.restoredVersion) {
        trashState.lastRestoredItem?.let { session.files.dispatch(FilesBrowserEvent.InvalidateRestoredItem(it)) }
    }
    LaunchedEffect(session, trashState.bulkRestoreVersion) {
        if (trashState.bulkRestoreVersion > 0L) session.files.dispatch(FilesBrowserEvent.InvalidateAllFolders)
    }
    val playback by session.playback.collectAsStateWithLifecycle()

    TvPlaybackLayer(
        playing = playback != null,
        player = {
            playback?.let { controller ->
                TvPlaybackRoute(
                    controller = controller,
                    onExit = session::stopPlayback,
                    onSessionRejected = onSessionRejected,
                    reporter = session.playbackReporting,
                    subtitleStartupPolicy = settingsState.subtitleStartupPolicy(),
                    autoplayNextVideo = appConfigState.confirmedAutoplayNextVideo(),
                )
            }
        },
    ) {
        TvShell(
            account = account,
            onSignOut = onSignOut,
            requestedDestination = requestedDestination,
            onDestinationRequestHandled = { requestedDestination = null },
            // Back from a folder opened from Search or History returns to that pane.
            onFilesBack = if (filesState.canNavigateBack) {
                {
                    val returnTo = filesState.current.openedFrom.returnDestination()
                    if (session.files.dispatch(FilesBrowserEvent.NavigateBack) && returnTo != null) {
                        requestedDestination = returnTo
                    }
                }
            } else {
                null
            },
            filesPane = { paneFocus ->
                TvFilesPane(
                    session = session,
                    filesState = filesState,
                    settingsState = settingsState,
                    fileActionFailure = fileActionFailure,
                    linkNotice = linkNotice,
                    sessionKey = sessionKey,
                    paneFocus = paneFocus,
                )
            },
            searchPane = { paneFocus ->
                TvSearchPane(
                    session = session,
                    searchState = searchState,
                    openRejected = openRejected,
                    recentSearchFailure = recentSearchFailure,
                    onQueryEdited = { openRejected = false },
                    sessionKey = sessionKey,
                    paneFocus = paneFocus,
                )
            },
            historyPane = { paneFocus ->
                TvHistoryPane(
                    session = session,
                    historyState = historyState,
                    openRejected = historyOpenRejected,
                    openFailure = historyOpenFailure,
                    onClearOpenRejection = { historyOpenRejected = false },
                    sessionKey = sessionKey,
                    paneFocus = paneFocus,
                )
            },
            accountPane = { paneFocus ->
                TvAccountPane(
                    session = session,
                    account = account,
                    settingsState = settingsState,
                    appConfigState = appConfigState,
                    trashState = trashState,
                    onSignOut = onSignOut,
                    loadTunnelRoutes = loadTunnelRoutes,
                    onTunnelRoutesRejected = { tunnelRoutesRejected = true },
                    sessionKey = sessionKey,
                    paneFocus = paneFocus,
                )
            },
        )
    }
}

/** Opens a Search or History pick; false when Files refused it while a move or deletion settles. */
private fun TvSession.openPick(item: FilesItem, origin: FilesOpenOrigin, onInFiles: () -> Unit): Boolean =
    when (openExternal(item, origin)) {
        TvExternalOpen.PLAYING -> true
        TvExternalOpen.IN_FILES -> {
            onInFiles()
            true
        }
        TvExternalOpen.REFUSED -> false
    }

/** The pane Back from a folder returns to: the one that opened it, or none to stay in Files. */
private fun FilesOpenOrigin?.returnDestination(): TvDestination? =
    when (this) {
        FilesOpenOrigin.SEARCH -> TvDestination.Search
        FilesOpenOrigin.HISTORY -> TvDestination.History
        FilesOpenOrigin.TRANSFERS, FilesOpenOrigin.LINK, null -> null
    }

@Composable
private fun TvFilesPane(
    session: TvSession,
    filesState: FilesBrowserState,
    settingsState: AccountSettingsState,
    fileActionFailure: PutioFailure?,
    linkNotice: TvLinkNotice,
    sessionKey: Any,
    paneFocus: FocusRequester,
) {
    // A restore from Trash marks its folder stale; the listing reloads when Files shows
    // again, or once a refresh that was running at that moment has settled.
    // Keyed like mobile: the folder, the operation, and whether the content is still
    // loading, so a reload deferred by any of them runs once that settles.
    val current = filesState.current
    LaunchedEffect(
        session,
        current.folder.id,
        current.needsReload,
        current.operation,
        current.content is FilesContent.Loading,
    ) {
        if (current.needsReload &&
            current.operation.canStartOperation &&
            current.content !is FilesContent.Loading
        ) {
            session.files.dispatch(FilesBrowserEvent.ReloadIfStale)
        }
    }
    // A row's actions: VLC gets the original file; a watched toggle writes the
    // account's position; deletion runs on the shared browser operation.
    val context = LocalContext.current
    val streamScope = rememberCoroutineScope()
    var filesNotice by remember(session) { mutableStateOf<Int?>(null) }
    var streamFailure by remember(session) { mutableStateOf<PutioFailure?>(null) }
    // A requester on the pane lands on its first focusable descendant (Refresh); the
    // pane's own entry effects then move focus to the row it remembers.
    TvFilesScreen(
        state = filesState,
        onEvent = session.files::dispatch,
        onPlayMedia = session::play,
        modifier = Modifier.focusRequester(paneFocus),
        sessionKey = sessionKey,
        focusMemory = session.filesFocusMemory,
        confirmedTrashEnabled = settingsState.confirmedTrashEnabled(),
        watchedToggleEnabled = settingsState.confirmedResumePlayback() == true,
        onOpenInVlc = { item ->
            streamScope.launch {
                val stream = session.originalStreamUrl(item)
                filesNotice = stream.openInVlc(context, item)
                // A 401 is the session's verdict, which the session already holds.
                streamFailure = (stream as? FilesStreamUrlResult.Failure)?.failure
                    ?.takeUnless { it is PutioFailure.AuthenticationRequired }
            }
        },
        onSetWatched = session::setWatched,
        notice = linkNotice.text() ?: filesNoticeText(filesNotice, streamFailure, fileActionFailure),
        // OK clears only what it was shown; a failure that arrived behind a VLC
        // notice is shown next.
        onDismissNotice = {
            when {
                linkNotice.shown -> linkNotice.onDismiss()
                filesNotice != null -> filesNotice = null
                streamFailure != null -> streamFailure = null
                else -> session.dismissFileActionFailure()
            }
        },
    )
}

/**
 * Acts on what system search or a Watch Next card asked for: a file resolves, then plays or
 * opens in Files; a query opens Search with it. Returns what Files explains when a file did not
 * open; a 401 is left to the shell, which signs out.
 */
@Composable
private fun rememberTvLaunchHandling(
    session: TvSession,
    pendingLaunch: TvLaunchRequest?,
    onLaunchHandled: (TvLaunchRequest) -> Unit,
    linkOpenFailure: PutioFailure?,
    onDestination: (TvDestination) -> Unit,
    onSearchLaunched: () -> Unit,
): TvLinkNotice {
    var rejected by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        session.links.opens.collect { opened ->
            rejected = opened == TvExternalOpen.REFUSED
            if (opened != TvExternalOpen.PLAYING) {
                session.stopPlayback()
                onDestination(TvDestination.Files)
            }
        }
    }
    LaunchedEffect(session, pendingLaunch) {
        val request = pendingLaunch ?: return@LaunchedEffect
        when (request) {
            is TvLaunchRequest.OpenFile -> {
                rejected = false
                session.links.dismissFailure()
                session.links.open(request.id, request.continueWatching)
            }
            is TvLaunchRequest.Search -> {
                session.stopPlayback()
                onSearchLaunched()
                session.search.updateQuery(request.term.value)
                session.search.submit()
                onDestination(TvDestination.Search)
            }
        }
        onLaunchHandled(request)
    }
    LaunchedEffect(linkOpenFailure) {
        if (linkOpenFailure != null) {
            session.stopPlayback()
            onDestination(TvDestination.Files)
        }
    }
    return TvLinkNotice(
        rejected = rejected,
        failure = linkOpenFailure,
        onDismiss = {
            rejected = false
            session.links.dismissFailure()
        },
    )
}

/** Why a file from system search or Watch Next did not open; Files shows it. */
private class TvLinkNotice(
    rejected: Boolean,
    failure: PutioFailure?,
    val onDismiss: () -> Unit,
) {
    // A 401 is the session's verdict, which the shell already acts on.
    private val shownFailure: FilesFailure? = when {
        rejected -> FilesFailure.NavigationBlocked
        else -> failure?.takeUnless { it is PutioFailure.AuthenticationRequired }
    }
    val shown: Boolean get() = shownFailure != null

    @Composable
    fun text(): String? = shownFailure?.let { stringResource(R.string.tv_link_open_error, it.tvMessageText()) }
}

/** Hands a ready stream to VLC; the notice to show instead, if any. */
private fun FilesStreamUrlResult.openInVlc(context: Context, item: FilesItem): Int? =
    when (this) {
        is FilesStreamUrlResult.Ready -> R.string.tv_files_vlc_missing.takeUnless { launchVlc(context, url, item) }
        FilesStreamUrlResult.DownloadTokenUnavailable -> R.string.tv_files_stream_unavailable
        is FilesStreamUrlResult.Failure -> null
    }

@Composable
private fun filesNoticeText(
    filesNotice: Int?,
    streamFailure: PutioFailure?,
    fileActionFailure: PutioFailure?,
): String? =
    filesNotice?.let { stringResource(it) }
        ?: streamFailure?.let { stringResource(R.string.tv_files_stream_error, it.tvMessageText()) }
        ?: fileActionFailure?.takeUnless { it is PutioFailure.AuthenticationRequired }
            ?.let { stringResource(R.string.tv_files_watched_error, it.tvMessageText()) }

@Composable
private fun TvSearchPane(
    session: TvSession,
    searchState: SearchState,
    openRejected: Boolean,
    recentSearchFailure: PutioFailure?,
    onQueryEdited: () -> Unit,
    sessionKey: Any,
    paneFocus: FocusRequester,
) {
    TvSearchScreen(
        state = searchState,
        actions = remember(session) { tvSearchActions(session, onQueryEdited = onQueryEdited) },
        notice = when {
            openRejected -> FilesFailure.NavigationBlocked
            else -> recentSearchFailure?.takeUnless { it is PutioFailure.AuthenticationRequired }
        },
        modifier = Modifier.focusRequester(paneFocus),
        sessionKey = sessionKey,
        pickedRow = session.searchPickedRow,
    )
}

@Composable
private fun TvHistoryPane(
    session: TvSession,
    historyState: HistoryState,
    openRejected: Boolean,
    openFailure: PutioFailure?,
    onClearOpenRejection: () -> Unit,
    sessionKey: Any,
    paneFocus: FocusRequester,
) {
    // A stale explanation must not greet a visit to the pane: cleared on entry as well
    // as on leaving, since a slow resolution can settle after the user has left.
    DisposableEffect(session) {
        onClearOpenRejection()
        session.dismissHistoryOpenFailure()
        onDispose {
            onClearOpenRejection()
            session.dismissHistoryOpenFailure()
        }
    }
    TvHistoryScreen(
        state = historyState,
        onEvent = { event ->
            if (event is HistoryEvent.OpenFile) onClearOpenRejection()
            session.history.dispatch(event)
        },
        notice = when {
            openRejected -> FilesFailure.NavigationBlocked
            else -> openFailure?.takeUnless { it is PutioFailure.AuthenticationRequired }
        },
        modifier = Modifier.focusRequester(paneFocus),
        sessionKey = sessionKey,
        pickedRow = session.historyPickedRow,
    )
}

@Composable
private fun TvAccountPane(
    session: TvSession,
    account: TvAccount,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    trashState: TrashState,
    onSignOut: () -> Unit,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    onTunnelRoutesRejected: () -> Unit,
    sessionKey: Any,
    paneFocus: FocusRequester,
) {
    // The listing is read on entry so Manage your trash can show the trash's size, and
    // kept while the pane is away; a pending mutation's recovery stays available
    // because the controller outlives the pane.
    LaunchedEffect(session) { session.trash.dispatch(TrashEvent.Open) }
    TvAccountScreen(
        account = account,
        settingsState = settingsState,
        appConfigState = appConfigState,
        onSettingsEvent = session.settings::dispatch,
        onAppConfigEvent = session.appConfig::dispatch,
        onSignOut = onSignOut,
        paneFocus = paneFocus,
        trashSizeBytes = (trashState.content as? TrashContent.Loaded)?.trashSizeBytes,
        trashPane = { trashFocus ->
            TvTrashScreen(
                state = trashState,
                onEvent = session.trash::dispatch,
                modifier = Modifier.focusRequester(trashFocus),
                sessionKey = sessionKey,
            )
        },
        loadTunnelRoutes = {
            loadTunnelRoutes().also { result ->
                if (result is AccountSettingsRepositoryResult.Failure &&
                    result.failure.putioFailure is PutioFailure.AuthenticationRequired
                ) {
                    onTunnelRoutesRejected()
                }
            }
        },
        sessionKey = sessionKey,
    )
}

private fun tvSearchActions(
    session: TvSession,
    onQueryEdited: () -> Unit,
): TvSearchActions =
    TvSearchActions(
        onQueryChanged = { query ->
            onQueryEdited()
            session.search.updateQuery(query)
        },
        onSubmit = { session.search.submit() },
        onResult = { item: FilesItem -> session.search.openResult(item.id) },
        onNextPage = { session.search.loadNextPage() },
        onRetry = { session.search.retry() },
        recent = TvRecentSearchActions(
            onSearch = { term ->
                onQueryEdited()
                session.search.updateQuery(term.value)
                session.search.submit()
            },
            onEdit = { session.search.editRecentSearches(it) },
            onRetry = session::retryRecentSearches,
        ),
    )
