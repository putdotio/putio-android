package io.putdotio.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.TvStatusScreen
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.android.tv.files.TvFilesViewModel
import io.putdotio.android.tv.files.tvFilesViewModelFactory
import kotlinx.coroutines.launch

/** TV root: the generated Compose for TV scheme, then whichever screen the session state names. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PutioApp(runtime: TvAuthRuntime) {
    val authController = runtime.authController
    val filesViewModel: TvFilesViewModel = viewModel(factory = tvFilesViewModelFactory(authController.state))
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
                    filesViewModel = filesViewModel,
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
    filesViewModel: TvFilesViewModel,
    onSignOut: () -> Unit,
    onSessionRejected: suspend () -> Unit,
) {
    val filesRepository = remember(runtime.putioClient) { SdkFilesRepository(runtime.putioClient) }
    val filesController = remember(filesViewModel, filesRepository, signedIn.account.userId, signedIn.sessionId) {
        filesViewModel.controllerFor(signedIn.account.userId, signedIn.sessionId, filesRepository)
    }
    val focusMemory = remember(filesController) {
        filesViewModel.focusMemoryFor(signedIn.account.userId, signedIn.sessionId, filesRepository)
    }
    if (filesController == null || focusMemory == null) {
        TvStatusScreen(stringResource(R.string.tv_session_restoring))
        return
    }
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val sessionRejected = filesState.authoritativeSessionFailure() != null
    LaunchedEffect(sessionRejected) { if (sessionRejected) onSessionRejected() }

    TvShell(
        account = signedIn.account,
        onSignOut = onSignOut,
        filesPane = { paneFocus ->
            // Composed only while Files is the destination, so Back on another pane
            // cannot pop the folder stack behind it.
            BackHandler(enabled = filesState.canNavigateBack) {
                filesController.dispatch(FilesBrowserEvent.NavigateBack)
            }
            // A requester on the pane lands on its first focusable descendant (Refresh); the
            // pane's own entry effects then move focus to the row it remembers.
            TvFilesScreen(
                state = filesState,
                onEvent = filesController::dispatch,
                onPlayMedia = {},
                modifier = Modifier.focusRequester(paneFocus),
                sessionKey = signedIn.sessionId.value,
                focusMemory = focusMemory,
            )
        },
    )
}
