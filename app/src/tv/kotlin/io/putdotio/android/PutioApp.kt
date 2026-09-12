package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.SdkFilesStreamUrls
import io.putdotio.android.files.SdkFilesWatchedRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.canStartOperation
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.SdkHistoryRepository
import io.putdotio.android.history.authoritativeSessionFailure
import io.putdotio.android.search.AppConfigRecentSearchStore
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchOutput
import io.putdotio.android.search.authoritativeSessionFailure
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.authoritativeSessionFailure
import io.putdotio.android.settings.confirmedHistoryEnabled
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.settings.confirmedTrashEnabled
import io.putdotio.android.tv.files.launchVlc
import io.putdotio.android.tv.files.tvMessage
import androidx.compose.ui.platform.LocalContext
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.tv.account.TvAccountScreen
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvSession
import io.putdotio.android.tv.TvSessionDependencies
import io.putdotio.android.tv.TvSessionViewModel
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.tv.trash.TvTrashScreen
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import io.putdotio.android.tv.tvSessionViewModelFactory
import kotlinx.coroutines.launch

/** TV root: the generated Compose for TV scheme, then whichever screen the session state names. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PutioApp(runtime: TvAuthRuntime) {
    val authController = runtime.authController
    val sessionViewModel: TvSessionViewModel = viewModel(factory = tvSessionViewModelFactory(authController.state))
    val authState by authController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(authController) { authController.restoreSession() }

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
            appConfigRepository = SdkAndroidAppConfigRepository(runtime.putioClient),
            watchedRepository = SdkFilesWatchedRepository(runtime.putioClient),
            streamUrls = SdkFilesStreamUrls(runtime.putioClient),
            filesItemResolver = filesRepository,
            recentSearchStore = { scope -> AppConfigRecentSearchStore(runtime.putioClient, scope) },
        )
    }
    // Looked up every composition, not remembered: the view model closes the session on its
    // own auth collector, and a cached closed controller would silently swallow events.
    val session = sessionViewModel.sessionFor(signedIn.account, signedIn.sessionId, dependencies)
    if (session == null) {
        TvStatusScreen(stringResource(R.string.tv_session_restoring))
        return
    }
    val filesState by session.files.state.collectAsStateWithLifecycle()
    val searchState by session.search.state.collectAsStateWithLifecycle()
    val historyState by session.history.state.collectAsStateWithLifecycle()
    val recentSearchFailure by session.recentSearchFailure.collectAsStateWithLifecycle()
    val historyOpenFailure by session.historyOpenFailure.collectAsStateWithLifecycle()
    val fileActionFailure by session.fileActionFailure.collectAsStateWithLifecycle()
    val trashState by session.trash.state.collectAsStateWithLifecycle()
    val settingsState by session.settings.state.collectAsStateWithLifecycle()
    val appConfigState by session.appConfig.state.collectAsStateWithLifecycle()
    // A 401 from the proxy list is as authoritative as one from any controller.
    var tunnelRoutesRejected by remember(session) { mutableStateOf(false) }
    val sessionRejected = filesState.authoritativeSessionFailure() != null ||
        trashState.authenticationFailure != null ||
        settingsState.authoritativeSessionFailure() != null ||
        appConfigState.authoritativeSessionFailure() != null ||
        tunnelRoutesRejected ||
        searchState.authoritativeSessionFailure() != null ||
        historyState.authoritativeSessionFailure() != null ||
        recentSearchFailure is FilesFailure.AuthenticationRequired ||
        fileActionFailure is FilesFailure.AuthenticationRequired ||
        historyOpenFailure is FilesFailure.AuthenticationRequired
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }
    // The controller starts from the setting read at validation; each confirmed value from
    // the settings controller supersedes it, so the Account toggle flips the History pane.
    // An unsettled History write confirms nothing and leaves the last value in place, like mobile.
    val confirmedHistoryEnabled = settingsState.confirmedHistoryEnabled()
    LaunchedEffect(session, confirmedHistoryEnabled) {
        confirmedHistoryEnabled?.let { session.history.dispatch(HistoryEvent.SetEnabled(it)) }
    }

    // A search result or a history event opens in Files: the browser jumps to the item's
    // folder and the shell switches destinations. A refused jump stays put with an explanation.
    var requestedDestination by remember(session) { mutableStateOf<TvDestination?>(null) }
    var openRejected by remember(session) { mutableStateOf(false) }
    var historyOpenRejected by remember(session) { mutableStateOf(false) }
    val openInFiles: (FilesItem) -> Boolean = { item ->
        session.files.dispatch(FilesBrowserEvent.OpenExternalItem(item)).also { accepted ->
            if (accepted) requestedDestination = TvDestination.Files
        }
    }
    LaunchedEffect(session) {
        session.search.outputs.collect { output ->
            when (output) {
                is SearchOutput.OpenResult -> openRejected = !openInFiles(output.item)
            }
        }
    }
    LaunchedEffect(session) {
        session.historyOpens.collect { item -> historyOpenRejected = !openInFiles(item) }
    }
    // A restore changes Files behind the browser's cache: one item's folder, or every folder.
    LaunchedEffect(session, trashState.restoredVersion) {
        trashState.lastRestoredItem?.let { session.files.dispatch(FilesBrowserEvent.InvalidateRestoredItem(it)) }
    }
    LaunchedEffect(session, trashState.bulkRestoreVersion) {
        if (trashState.bulkRestoreVersion > 0L) session.files.dispatch(FilesBrowserEvent.InvalidateAllFolders)
    }
    val sessionKey = signedIn.account.userId to signedIn.sessionId.value

    TvShell(
        account = signedIn.account,
        onSignOut = onSignOut,
        requestedDestination = requestedDestination,
        onDestinationRequestHandled = { requestedDestination = null },
        filesPane = { paneFocus ->
            // Composed only while Files is the destination, so Back on another pane
            // cannot pop the folder stack behind it.
            BackHandler(enabled = filesState.canNavigateBack) {
                session.files.dispatch(FilesBrowserEvent.NavigateBack)
            }
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
                if (current.needsReload && current.operation.canStartOperation && current.content !is FilesContent.Loading) {
                    session.files.dispatch(FilesBrowserEvent.ReloadIfStale)
                }
            }
            // A row's actions: VLC gets the original file; a watched toggle writes the
            // account's position; deletion runs on the shared browser operation.
            val context = LocalContext.current
            var filesNotice by remember(session) { mutableStateOf<Int?>(null) }
            // A requester on the pane lands on its first focusable descendant (Refresh); the
            // pane's own entry effects then move focus to the row it remembers.
            TvFilesScreen(
                state = filesState,
                onEvent = session.files::dispatch,
                onPlayMedia = {},
                modifier = Modifier.focusRequester(paneFocus),
                sessionKey = sessionKey,
                focusMemory = session.filesFocusMemory,
                confirmedTrashEnabled = settingsState.confirmedTrashEnabled(),
                watchedToggleEnabled = settingsState.confirmedResumePlayback() == true,
                onOpenInVlc = { item ->
                    val url = session.originalStreamUrl(item)
                    filesNotice = when {
                        url == null -> R.string.tv_files_stream_unavailable
                        launchVlc(context, url, item) -> null
                        else -> R.string.tv_files_vlc_missing
                    }
                },
                onSetWatched = session::setWatched,
                notice = filesNotice?.let { stringResource(it) }
                    ?: fileActionFailure?.takeUnless { it is FilesFailure.AuthenticationRequired }
                        ?.let { stringResource(R.string.tv_files_watched_error, stringResource(it.tvMessage())) },
                // OK clears only what it was shown; a failure that arrived behind a VLC
                // notice is shown next.
                onDismissNotice = {
                    if (filesNotice != null) filesNotice = null else session.dismissFileActionFailure()
                },
            )
        },
        searchPane = { paneFocus ->
            TvSearchScreen(
                state = searchState,
                actions = remember(session) { tvSearchActions(session, onQueryEdited = { openRejected = false }) },
                notice = when {
                    openRejected -> FilesFailure.NavigationBlocked
                    else -> recentSearchFailure?.takeUnless { it is FilesFailure.AuthenticationRequired }
                },
                modifier = Modifier.focusRequester(paneFocus),
                sessionKey = sessionKey,
            )
        },
        historyPane = { paneFocus ->
            // A stale explanation must not greet a visit to the pane: cleared on entry as well
            // as on leaving, since a slow resolution can settle after the user has left.
            DisposableEffect(session) {
                historyOpenRejected = false
                session.dismissHistoryOpenFailure()
                onDispose {
                    historyOpenRejected = false
                    session.dismissHistoryOpenFailure()
                }
            }
            TvHistoryScreen(
                state = historyState,
                onEvent = { event ->
                    if (event is HistoryEvent.OpenFile) historyOpenRejected = false
                    session.history.dispatch(event)
                },
                notice = when {
                    historyOpenRejected -> FilesFailure.NavigationBlocked
                    else -> historyOpenFailure?.takeUnless { it is FilesFailure.AuthenticationRequired }
                },
                modifier = Modifier.focusRequester(paneFocus),
                sessionKey = sessionKey,
            )
        },
        accountPane = { paneFocus ->
            // The listing is read on entry so Manage your trash can show the trash's size, and
            // kept while the pane is away; a pending mutation's recovery stays available
            // because the controller outlives the pane.
            LaunchedEffect(session) { session.trash.dispatch(TrashEvent.Open) }
            TvAccountScreen(
                account = signedIn.account,
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
                    dependencies.settingsRepository.loadTunnelRoutes().also { result ->
                        if (result is AccountSettingsRepositoryResult.Failure &&
                            result.failure is AccountSettingsFailure.AuthenticationRequired
                        ) {
                            tunnelRoutesRejected = true
                        }
                    }
                },
                sessionKey = sessionKey,
            )
        },
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
        onRecentSearch = { term ->
            onQueryEdited()
            session.search.updateQuery(term.value)
            session.search.submit()
        },
        onRecentEdit = { session.search.editRecentSearches(it) },
        onRecentRetry = session::retryRecentSearches,
    )
