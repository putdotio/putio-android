package io.putdotio.android.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.putdotio.android.BuildConfig
import io.putdotio.android.playback.SdkPlaybackPositionRepository
import io.putdotio.android.documents.MobileDocumentsProvider
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.MobileDownloadNotifications
import io.putdotio.android.downloads.OfflinePlaybackPositions
import io.putdotio.android.downloads.PositionRemote
import io.putdotio.android.downloads.downloadPreferences
import io.putdotio.android.files.MobileMoveTargetStore
import io.putdotio.android.share.MobileFileDrags
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.widgets.MobileWidgets
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import io.putdotio.android.playback.MobilePlaybackReporting

internal fun interface OAuthRuntimeFailureReporter {
    fun report(error: Exception)
}

class MobileOAuthRuntime internal constructor(
    val putioClient: PutioClient,
    val authController: MobileAuthController,
    private val applicationScope: CoroutineScope,
    private val failureReporter: OAuthRuntimeFailureReporter = AndroidOAuthRuntimeFailureReporter,
    onSessionLeft: (MobileAuthSessionId?) -> Unit = {},
    onSessionStarted: (MobileAuthSessionId) -> Unit = {},
) {
    private val positions = SdkPlaybackPositionRepository(putioClient)

    /** Saved positions of downloaded files; attached by [create], absent in runtimes tests build. */
    internal var offlinePositions: OfflinePlaybackPositions? = null
        private set

    init {
        // Sessions also end without a UI (a background rejection), so the process scope owns this boundary.
        applicationScope.launch {
            var previous: MobileAuthSessionId? = null
            // An earlier process may have left state for its persisted session; a restore that ends signed out ends it.
            var persistedSessionPending = true
            authController.state.collect { state ->
                val current = (state as? MobileAuthState.SignedIn)?.sessionId
                val restoreEnded = persistedSessionPending && state is MobileAuthState.SignedOut
                if (current != null || restoreEnded) persistedSessionPending = false
                // The collector can lag a switch to the next session, so it names the departed one.
                when {
                    // The earlier process's session id is unknown, and this process has no session yet.
                    restoreEnded -> onSessionLeft(null)
                    previous != null && current != previous -> onSessionLeft(previous)
                }
                if (current != null && current != previous) {
                    onSessionStarted(current)
                    offlinePositions?.requestSync()
                }
                previous = current
            }
        }
    }

    internal val playbackReporting = MobilePlaybackReporting(
        authController.state,
        applicationScope,
        onAuthenticationRequired = { sessionId -> authController.rejectAuthoritativeSession(sessionId) },
        write = positions::write,
        offline = { offlinePositions },
    )

    private fun keepOfflinePositions(preferences: SharedPreferences): OfflinePlaybackPositions =
        OfflinePlaybackPositions(
            preferences = preferences,
            signedInUser = { (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId },
            remote = object : PositionRemote {
                override suspend fun read(fileId: Long) = positions.read(fileId)

                override suspend fun write(fileId: Long, seconds: Double) = positions.write(fileId, seconds)

                override suspend fun resumeEnabled() = positions.resumeEnabled()
            },
            scope = applicationScope,
            onSynced = { fileId, seconds -> playbackReporting.publishSaved(fileId, seconds) },
        ).also {
            offlinePositions = it
            // A session restored before this attached still has its waiting positions sent.
            it.requestSync()
        }

    /**
     * Background components that outlive the UI restore the session so the download
     * resolver has its token; [onSessionSettled] fires whether or not a token exists.
     */
    fun ensureSessionRestored(onSessionSettled: () -> Unit) {
        applicationScope.launch {
            try {
                authController.restoreSession()
            } finally {
                onSessionSettled()
            }
        }
    }

    /**
     * The session once it settles, for a component without a screen: a stored one is restored first.
     * Null while restore or validation is still running after [timeout].
     */
    internal suspend fun awaitSettledSession(timeout: Duration): MobileAuthState? {
        ensureSessionRestored {}
        return withTimeoutOrNull(timeout) { authController.state.first { it.isSettled() } }
    }

    /** put.io rejected [sessionId] outside any screen; signs it out unless another session replaced it. */
    internal fun rejectSession(sessionId: MobileAuthSessionId) {
        applicationScope.launch { authController.rejectAuthoritativeSession(sessionId) }
    }

    fun dispatchAuthTabResult(
        resultCode: Int,
        rawResultUri: String?,
    ): Job =
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
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val tokenStore = KeystoreAuthTokenStore(context)
            val authController = MobileAuthController(
                oauthConfiguration = oauthConfiguration,
                tokenStore = tokenStore,
                oauthAttempts = OAuthAttempts(SharedPreferencesPendingOAuthAttemptStore(context)),
                sessionGateway = PutioAuthSessionGateway(putioClient) { token ->
                    MobileDownloadCache.get(context).let {
                        if (token == null) it.onTokenClearing?.invoke()
                        it.accessToken = token
                        it.markSessionSettled()
                    }
                },
                tokenRevocations = PendingTokenRevocations(
                    store = KeystoreAuthTokenStore.pendingRevocation(context),
                    sessionStore = tokenStore,
                    revoker = PutioAuthTokenRevoker(putioClient.config),
                    scope = applicationScope,
                ),
                clearAccountLocalState = { MobileMoveTargetStore.clearAll(context) },
            )
            return MobileOAuthRuntime(
                putioClient = putioClient,
                authController = authController,
                applicationScope = applicationScope,
                onSessionLeft = { session ->
                    MobileFileShareService.endSession(context, session)
                    MobileFileDrags.endSession(session)
                    MobileDocumentsProvider.sessionLeft(context)
                    // The collector can lag the next sign-in; that account's outcomes stay.
                    MobileDownloadNotifications.cancelOtherAccounts(
                        context,
                        (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId,
                    )
                    MobileWidgets.sessionLeft(context)
                },
                onSessionStarted = {
                    MobileDocumentsProvider.sessionStarted(context)
                    MobileWidgets.sessionStarted(context)
                },
            ).also { runtime ->
                syncWhenOnline(context, runtime.keepOfflinePositions(downloadPreferences(context)))
            }
        }

        /** put.io answers again once a validated network returns; positions saved offline go then. */
        private fun syncWhenOnline(context: Context, positions: OfflinePlaybackPositions) {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                            positions.requestSync()
                        }
                    }
                },
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
