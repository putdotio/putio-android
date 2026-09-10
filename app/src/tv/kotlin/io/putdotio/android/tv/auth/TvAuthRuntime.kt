package io.putdotio.android.tv.auth

import android.content.Context
import io.putdotio.android.auth.KeystoreAuthTokenStore
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Process-wide TV session: one SDK client, one controller, one application scope. */
class TvAuthRuntime internal constructor(
    val putioClient: PutioClient,
    val authController: TvAuthController,
    private val applicationScope: CoroutineScope,
) {
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
            return TvAuthRuntime(
                putioClient = putioClient,
                authController = TvAuthController(
                    tokenStore = KeystoreAuthTokenStore(context),
                    sessionGateway = PutioTvSessionGateway(putioClient),
                    scope = applicationScope,
                ),
                applicationScope = applicationScope,
            )
        }
    }
}
