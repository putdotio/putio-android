package io.putdotio.android.tv.auth

import android.content.Context
import io.putdotio.android.auth.KeystoreAuthTokenStore
import io.putdotio.android.auth.PendingTokenRevocations
import io.putdotio.android.auth.PutioAuthTokenRevoker
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.confirmProductAnalytics
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.tv.search.TvGlobalSearch
import io.putdotio.android.tv.search.tvGlobalSearch
import io.putdotio.android.tv.watchnext.TvProviderWatchNextStore
import io.putdotio.android.tv.watchnext.TvWatchNext
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Process-wide TV session: one SDK client, one controller, one application scope, and the
 * launcher's Watch Next row, which follows the session even with no screen showing.
 */
internal class TvAuthRuntime(
    val putioClient: PutioClient,
    val authController: TvAuthController,
    private val applicationScope: CoroutineScope,
    internal val watchNext: TvWatchNext,
) {
    private val backgroundRestore = lazy {
        applicationScope.async { authController.restoreSession(interactive = false) }
    }

    /**
     * The signed-in session for a caller with no screen, such as the system search provider:
     * restores a stored session once per process if the app has not, and never starts a sign-in.
     */
    private suspend fun signedInSession(): TvAuthState.SignedIn? {
        (authController.state.value as? TvAuthState.SignedIn)?.let { return it }
        backgroundRestore.value.await()
        return authController.state.value as? TvAuthState.SignedIn
    }

    /** What the system search provider runs. */
    internal val globalSearch: TvGlobalSearch by lazy {
        tvGlobalSearch(authController, ::signedInSession, SdkSearchRepository(putioClient)::search)
    }

    companion object {
        @Volatile
        private var instance: TvAuthRuntime? = null

        fun get(context: Context): TvAuthRuntime =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        internal fun resetForTests() {
            synchronized(this) {
                instance?.applicationScope?.cancel()
                instance = null
            }
        }

        private fun create(context: Context): TvAuthRuntime {
            val oauthClient = TvOAuthClient.forDevice(context)
            val putioClient = PutioClient(
                PutioConfig(clientId = oauthClient.clientId, clientName = oauthClient.clientName),
            )
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val tokenStore = KeystoreAuthTokenStore(context)
            val authController = TvAuthController(
                tokenStore = tokenStore,
                sessionGateway = PutioTvSessionGateway(putioClient),
                tokenRevocations = PendingTokenRevocations(
                    store = KeystoreAuthTokenStore.pendingRevocation(context),
                    sessionStore = tokenStore,
                    revoker = PutioAuthTokenRevoker(putioClient.config),
                    scope = applicationScope,
                ),
                scope = applicationScope,
                legacySession = AsyncStorageLegacyTvSession(context),
            )
            // Sessions also change without a UI, so the process scope tells diagnostics and analytics.
            applicationScope.launch {
                authController.state.collect { state ->
                    state.diagnosticsAllowed()?.let(PutioDiagnostics::update)
                    state.productAnalyticsAnswer()?.let { (accountId, enabled) ->
                        ProductAnalytics.update(accountId, enabled)
                    }
                }
            }
            val revalidation = TvSessionRevalidation(authController, applicationScope)
            watchNetworkReturns(context) { applicationScope.launch { revalidation.onNetworkReturned() } }
            val accountSettings = SdkAccountSettingsRepository(putioClient)
            ProductAnalytics.setConsentCheck { check ->
                val accountId = (authController.state.value as? TvAuthState.SignedIn)?.account?.userId
                applicationScope.launch { accountSettings.confirmProductAnalytics(check, accountId) }
            }
            // A quiet sign-out lands on Initializing, which keeps the last answer; it is an off answer.
            applicationScope.launch {
                authController.quietSignOuts.filter { it > 0 }.collect {
                    ProductAnalytics.update(null, enabled = false)
                }
            }
            return TvAuthRuntime(
                putioClient = putioClient,
                authController = authController,
                applicationScope = applicationScope,
                watchNext = TvWatchNext(
                    store = TvProviderWatchNextStore(context),
                    authState = authController.state,
                    quietSignOuts = authController.quietSignOuts,
                    scope = applicationScope,
                ),
            )
        }
    }
}

/** Null while restoring, validating, signing out, or unable to read or clear the token, so the last answer stands. */
internal fun TvAuthState.diagnosticsAllowed(): Boolean? =
    when (this) {
        TvAuthState.Initializing,
        TvAuthState.RestoringSession,
        is TvAuthState.ValidatingSession,
        is TvAuthState.ValidationUnavailable,
        TvAuthState.SigningOut,
        -> null

        is TvAuthState.Linking ->
            true.takeUnless { (phase as? TvLinkPhase.Stopped)?.reason == TvLinkStop.StorageUnavailable }
        is TvAuthState.SignedIn -> account.diagnosticsEnabled
    }

/** The account and its product analytics answer: signed out is `null to false`; null keeps the last answer. */
internal fun TvAuthState.productAnalyticsAnswer(): Pair<Long?, Boolean>? =
    when (this) {
        is TvAuthState.Linking -> null to false
        is TvAuthState.SignedIn -> account.userId to account.productAnalyticsEnabled
        else -> null
    }
