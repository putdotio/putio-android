package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.search.AppConfigRecentSearchStore
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchOutput
import io.putdotio.android.search.authoritativeSessionFailure
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
        TvSessionDependencies(
            filesRepository = SdkFilesRepository(runtime.putioClient),
            searchRepository = SdkSearchRepository(runtime.putioClient),
            recentSearchStore = { scope -> AppConfigRecentSearchStore(runtime.putioClient, scope) },
        )
    }
    // Looked up every composition, not remembered: the view model closes the session on its
    // own auth collector, and a cached closed controller would silently swallow events.
    val session = sessionViewModel.sessionFor(signedIn.account.userId, signedIn.sessionId, dependencies)
    if (session == null) {
        TvStatusScreen(stringResource(R.string.tv_session_restoring))
        return
    }
    val filesState by session.files.state.collectAsStateWithLifecycle()
    val searchState by session.search.state.collectAsStateWithLifecycle()
    val recentSearchFailure by session.recentSearchFailure.collectAsStateWithLifecycle()
    val sessionRejected = filesState.authoritativeSessionFailure() != null ||
        searchState.authoritativeSessionFailure() != null ||
        recentSearchFailure is FilesFailure.AuthenticationRequired
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }

    // A search result opens in Files: the browser jumps to the result's folder and the
    // shell switches destinations. A refused jump stays on Search with an explanation.
    var requestedDestination by remember(session) { mutableStateOf<TvDestination?>(null) }
    var openRejected by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        session.search.outputs.collect { output ->
            when (output) {
                is SearchOutput.OpenResult -> {
                    if (session.files.dispatch(FilesBrowserEvent.OpenExternalItem(output.item))) {
                        openRejected = false
                        requestedDestination = TvDestination.Files
                    } else {
                        openRejected = true
                    }
                }
            }
        }
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
            // A requester on the pane lands on its first focusable descendant (Refresh); the
            // pane's own entry effects then move focus to the row it remembers.
            TvFilesScreen(
                state = filesState,
                onEvent = session.files::dispatch,
                onPlayMedia = {},
                modifier = Modifier.focusRequester(paneFocus),
                sessionKey = sessionKey,
                focusMemory = session.filesFocusMemory,
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
