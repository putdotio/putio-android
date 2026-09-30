package io.putdotio.android

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.putdotio.android.auth.AuthTabOAuthBrowser
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.OAuthBrowserLaunchResult
import io.putdotio.android.auth.OAuthLaunchResult
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.MobileFilesViewModel
import io.putdotio.android.files.mobileFilesViewModelFactory
import io.putdotio.android.search.MobileSearchHistoryViewModel
import io.putdotio.android.search.mobileSearchHistoryViewModelFactory
import kotlinx.coroutines.launch

@Composable
fun PutioApp(
    authTabLauncher: ActivityResultLauncher<Intent>? = null,
    nowPlayingRequests: NowPlayingRequests = NowPlayingRequests.None,
    transferDraft: MobileTransferDraft = viewModel(),
    deepLinkRequests: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { MobileOAuthRuntime.get(context) }
    val oauthBrowser = remember(context.applicationContext, authTabLauncher) {
        authTabLauncher?.let { AuthTabOAuthBrowser(context, it) }
    }

    PutioTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobileAuthRoot(
                transferDraft = transferDraft,
                runtime = runtime,
                oauthBrowser = oauthBrowser,
                nowPlayingRequests = nowPlayingRequests,
                deepLinkRequests = deepLinkRequests,
            )
        }
    }
}

@Composable
private fun MobileAuthRoot(
    transferDraft: MobileTransferDraft,
    runtime: MobileOAuthRuntime,
    oauthBrowser: AuthTabOAuthBrowser?,
    nowPlayingRequests: NowPlayingRequests,
    deepLinkRequests: MobileDeepLinkRequests = MobileDeepLinkRequests.None,
) {
    val context = LocalContext.current
    val authController = runtime.authController
    val authState by authController.state.collectAsStateWithLifecycle()
    // Session exit must clear sensitive drafts even while lifecycle-aware UI collection is stopped.
    LaunchedEffect(authController, transferDraft) {
        authController.state.collect { state ->
            transferDraft.reconcileSession((state as? MobileAuthState.SignedIn)?.sessionId)
        }
    }
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
    val downloadsViewModel = viewModel<MobileDownloadsViewModel>(
        factory = remember(authController) { mobileDownloadsViewModelFactory(authController.state) },
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
                transferDraft = transferDraft,
                runtime = runtime,
                signedIn = state,
                filesViewModel = filesViewModel,
                accountSettingsViewModel = accountSettingsViewModel,
                appConfigViewModel = appConfigViewModel,
                searchHistoryViewModel = searchHistoryViewModel,
                transfersViewModel = transfersViewModel,
                trashViewModel = trashViewModel,
                downloadsViewModel = downloadsViewModel,
                authController = authController,
                rootScope = rootScope,
                nowPlayingRequests = nowPlayingRequests,
                deepLinkRequests = deepLinkRequests,
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

    if (!canSignIn || reason == MobileSignedOutReason.OAuthNotConfigured) {
        MobileEmptyState(
            title = stringResource(title),
            message = stringResource(message),
        )
        return
    }

    MobileAuthMessageScreen(
        title = stringResource(title),
        message = stringResource(message),
        actionLabel = stringResource(
            if (reason == MobileSignedOutReason.SecureStorageUnavailable) {
                R.string.mobile_auth_storage_reset
            } else {
                R.string.mobile_auth_sign_in
            },
        ),
        onAction = onSignIn,
    )
}
