package io.putdotio.android.tv.auth

import android.content.Context
import io.putdotio.android.auth.KeystoreAuthTokenStore
import io.putdotio.android.auth.PendingTokenRevocations
import io.putdotio.android.auth.PutioAuthTokenRevoker
import io.putdotio.android.tv.watchnext.TvProviderWatchNextStore
import io.putdotio.android.tv.watchnext.TvWatchNext
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/**
 * Process-wide TV session: one SDK client, one controller, one application scope, and the
 * launcher's Watch Next row, which follows the session even with no screen showing.
 */
class TvAuthRuntime internal constructor(
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
    internal suspend fun signedInSession(): TvAuthState.SignedIn? {
        (authController.state.value as? TvAuthState.SignedIn)?.let { return it }
        backgroundRestore.value.await()
        return authController.state.value as? TvAuthState.SignedIn
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
            return TvAuthRuntime(
                putioClient = putioClient,
                authController = authController,
                applicationScope = applicationScope,
                watchNext = TvWatchNext(TvProviderWatchNextStore(context), authController.state, applicationScope),
            )
        }
    }
}
