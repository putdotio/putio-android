package io.putdotio.android.auth

import android.content.Context
import android.util.Log
import io.putdotio.android.BuildConfig
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal fun interface OAuthRuntimeFailureReporter {
    fun report(error: Exception)
}

class MobileOAuthRuntime internal constructor(
    val putioClient: PutioClient,
    val authController: MobileAuthController,
    private val applicationScope: CoroutineScope,
    private val failureReporter: OAuthRuntimeFailureReporter = AndroidOAuthRuntimeFailureReporter,
) {
    fun dispatchAuthTabResult(
        resultCode: Int,
        rawResultUri: String?,
    ) {
        // Activity results outlive individual UI owners; keep a boundary failure inside this application scope.
        @Suppress("TooGenericExceptionCaught")
        applicationScope.launch {
            try {
                when (val result = classifyAuthTabResult(resultCode, rawResultUri)) {
                    is OAuthBrowserResult.Callback -> authController.handleOAuthCallback(result.uri)
                    OAuthBrowserResult.Cancelled -> authController.cancelSignIn()
                    OAuthBrowserResult.Failed -> authController.failSignIn()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failureReporter.report(error)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MobileOAuthRuntime? = null

        fun get(context: Context): MobileOAuthRuntime =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        internal fun resetForTests() {
            synchronized(this) {
                instance?.applicationScope?.cancel()
                instance = null
            }
        }

        private fun create(context: Context): MobileOAuthRuntime {
            val oauthConfiguration = MobileOAuthConfiguration.fromClientId(
                clientId = BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID,
                debugOverride = BuildConfig.PUTIO_MOBILE_OAUTH_CLIENT_ID_DEBUG_OVERRIDE.takeIf { BuildConfig.DEBUG },
            )
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

private object AndroidOAuthRuntimeFailureReporter : OAuthRuntimeFailureReporter {
    override fun report(error: Exception) {
        Log.e(MOBILE_OAUTH_LOG_TAG, oauthRuntimeFailureLog(error))
    }
}

internal fun oauthRuntimeFailureLog(error: Exception): String =
    "event=oauth_auth_tab_result operation=dispatch outcome=failed error_kind=unknown " +
        "error_type=${error.javaClass.simpleName}"

private const val MOBILE_OAUTH_LOG_TAG = "PutioOAuth"
