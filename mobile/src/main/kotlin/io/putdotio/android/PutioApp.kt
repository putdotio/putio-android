package io.putdotio.android

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
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
import io.putdotio.android.account.MobileAccountSettingsViewModel
import io.putdotio.android.account.MobileAndroidAppConfigViewModel
import io.putdotio.android.account.mobileAccountSettingsViewModelFactory
import io.putdotio.android.account.mobileAndroidAppConfigViewModelFactory
import io.putdotio.android.auth.AuthTabOAuthBrowser
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.OAuthBrowserLaunchResult
import io.putdotio.android.auth.OAuthLaunchResult
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.downloads.MobileDownloadsViewModel
import io.putdotio.android.downloads.mobileDownloadsViewModelFactory
import io.putdotio.android.files.MobileFilesViewModel
import io.putdotio.android.files.mobileFilesViewModelFactory
import io.putdotio.android.search.MobileSearchHistoryViewModel
import io.putdotio.android.search.mobileSearchHistoryViewModelFactory
import io.putdotio.android.transfers.MobileTransferDraft
import io.putdotio.android.transfers.MobileTransfersViewModel
import io.putdotio.android.transfers.mobileTransfersViewModelFactory
import io.putdotio.android.trash.MobileTrashViewModel
import io.putdotio.android.trash.mobileTrashViewModelFactory
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
            MobileWelcomeScreen(
                content = MobileBrowserWelcomeContent,
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
    MobileWelcomeScreen(
        content = mobileWelcomeContent(reason, canSignIn),
        onAction = onSignIn,
    )
}
