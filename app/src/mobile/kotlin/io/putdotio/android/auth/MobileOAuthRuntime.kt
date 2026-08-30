package io.putdotio.android.auth

import android.content.Context
import io.putdotio.android.BuildConfig
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class MobileOAuthRuntime private constructor(
    val putioClient: PutioClient,
    val authController: MobileAuthController,
    private val applicationScope: CoroutineScope,
) {
    private val callbackDispatchCounter = AtomicLong()

    fun dispatchOAuthCallback(rawCallbackUri: String?) {
        callbackDispatchCounter.incrementAndGet()
        applicationScope.launch {
            authController.handleOAuthCallback(rawCallbackUri)
        }
    }

    fun callbackDispatchSequence(): Long = callbackDispatchCounter.get()

    companion object {
        @Volatile
        private var instance: MobileOAuthRuntime? = null

        fun get(context: Context): MobileOAuthRuntime =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): MobileOAuthRuntime {
            val oauthConfiguration = MobileOAuthConfiguration.fromClientId(BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID)
            val configuredClientId = (oauthConfiguration as? MobileOAuthConfiguration.Configured)?.clientId
            val putioClient = PutioClient(
                PutioConfig(
                    clientId = configuredClientId,
                    clientName = MOBILE_OAUTH_CLIENT_NAME,
                ),
            )
            val authController = MobileAuthController(
                oauthConfiguration = oauthConfiguration,
                tokenStore = KeystoreAuthTokenStore(context),
                pendingOAuthAttemptStore = SharedPreferencesPendingOAuthAttemptStore(context),
                sessionGateway = PutioAuthSessionGateway(putioClient),
            )
            return MobileOAuthRuntime(
                putioClient = putioClient,
                authController = authController,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
