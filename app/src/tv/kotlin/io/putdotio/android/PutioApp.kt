package io.putdotio.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAuthController
import io.putdotio.android.tv.auth.TvAuthState
import kotlinx.coroutines.launch

/** TV root: the generated Compose for TV scheme, then whichever screen the session state names. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PutioApp(authController: TvAuthController) {
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
                TvShell(
                    account = state.account,
                    onSignOut = { scope.launch { authController.logout() } },
                )
        }
    }
}

@Composable
private fun TvStatusScreen(
    title: String,
    message: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(action) { if (action != null) actionFocus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (action != null) {
            TvButton(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 40.dp)
                    .focusRequester(actionFocus),
            ) {
                Text(action)
            }
        }
    }
}
