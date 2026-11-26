package io.putdotio.android.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import coil3.SingletonImageLoader
import io.putdotio.android.BuildConfig
import io.putdotio.android.MobilePlaybackService
import io.putdotio.android.PutioHttpClient
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.SdkPlaybackPositionRepository
import io.putdotio.android.documents.MobileDocumentsProvider
import io.putdotio.android.downloads.MobileDownloadCache
import io.putdotio.android.downloads.MobileDownloadNotifications
import io.putdotio.android.downloads.MobileDownloadStore
import io.putdotio.android.downloads.OfflinePlaybackPositions
import io.putdotio.android.downloads.OfflinePositionSession
import io.putdotio.android.downloads.PositionRemote
import io.putdotio.android.downloads.downloadPreferences
import io.putdotio.android.files.DeviceContinueWatchingStore
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.files.MobileMoveTargetStore
import io.putdotio.android.share.MobileFileDrags
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.confirmProductAnalytics
import io.putdotio.android.share.MobileFileShareService
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.DiagnosticsAnswer
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.widgets.MobileWidgets
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineDispatcher
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
import io.putdotio.android.files.FilesPdfDocument

internal fun interface OAuthRuntimeFailureReporter {
    fun report(error: Exception)
}

internal class MobileOAuthRuntime(
    val putioClient: PutioClient,
    val authController: MobileAuthController,
    private val applicationScope: CoroutineScope,
    private val failureReporter: OAuthRuntimeFailureReporter = AndroidOAuthRuntimeFailureReporter,
    onSessionLeft: (MobileAuthSessionId?) -> Unit = {},
    onSessionStarted: (MobileAuthSessionId) -> Unit = {},
) {
    private val positions = SdkPlaybackPositionRepository(putioClient)

    internal val uploadClient: PutioClient = PutioClient(putioClient.config, okHttpClient = PutioHttpClient.shared)

    internal var offlinePositions: OfflinePlaybackPositions? = null
        private set

    init {
        applicationScope.launch {
            var previous: MobileAuthSessionId? = null
            var persistedSessionPending = true
            authController.state.collect { state ->
                val current = (state as? MobileAuthState.SignedIn)?.sessionId
                val restoreEnded = persistedSessionPending && state is MobileAuthState.SignedOut
                if (current != null || restoreEnded) persistedSessionPending = false
                when {
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
            session = OfflinePositionSession(
                userId = { (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId },
                captureRejection = {
                    val sessionId = (authController.state.value as? MobileAuthState.SignedIn)?.sessionId
                    if (sessionId != null) {
                        { rejectSession(sessionId) }
                    } else {
                        {}
                    }
                },
            ),
            remote = object : PositionRemote {
                override suspend fun read(fileId: Long) = positions.read(fileId)

                override suspend fun write(fileId: Long, seconds: Double) = positions.write(fileId, seconds)

                override suspend fun resumeEnabled() = positions.resumeEnabled()
            },
            scope = applicationScope,
            onSynced = { fileId, seconds -> playbackReporting.publishSaved(fileId, seconds) },
            writing = playbackReporting::exclusive,
        ).also {
            offlinePositions = it
            it.requestSync()
        }

    fun ensureSessionRestored(onSessionSettled: () -> Unit) {
        applicationScope.launch {
            try {
                authController.restoreSession()
            } finally {
                onSessionSettled()
            }
        }
    }

    internal suspend fun awaitSettledSession(timeout: Duration): MobileAuthState? {
        ensureSessionRestored {}
        return withTimeoutOrNull(timeout) { authController.state.first { it.isSettled() } }
    }

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
                okHttpClient = mobileHttpClient(),
            )
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val tokenStore = KeystoreAuthTokenStore(context)
            val downloadToken = MediaDownloadToken.from(putioClient)
            val authController = MobileAuthController(
                oauthConfiguration = oauthConfiguration,
                tokenStore = tokenStore,
                oauthAttempts = OAuthAttempts(SharedPreferencesPendingOAuthAttemptStore(context)),
                sessionGateway = PutioAuthSessionGateway(putioClient) { token ->
                    MobileDownloadCache.get(context).let {
                        if (token == null) it.onTokenClearing?.invoke()
                        downloadToken.reset()
                        it.downloadToken = downloadToken.takeIf { token != null }
                        it.markSessionSettled()
                    }
                },
                tokenRevocations = PendingTokenRevocations(
                    stores = KeystoreAuthTokenStore.pendingRevocations(context),
                    sessionStore = tokenStore,
                    revoker = PutioAuthTokenRevoker(putioClient),
                    scope = applicationScope,
                ),
                clearAccountLocalState = { clearMobileSessionCaches(context, applicationScope) },
                validatedAccounts = SharedPreferencesValidatedAccountStore(context),
            )
            reportConsent(authController, putioClient, applicationScope)
            return MobileOAuthRuntime(
                putioClient = putioClient,
                authController = authController,
                applicationScope = applicationScope,
                onSessionLeft = { session -> sessionLeft(context, session, authController, applicationScope) },
                onSessionStarted = {
                    MobileDocumentsProvider.sessionStarted(context)
                    MobileWidgets.sessionStarted(context)
                    (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId?.let { userId ->
                        applicationScope.launch(Dispatchers.IO) { MobileDownloadStore.shared(context, userId) }
                    }
                },
            ).also { runtime ->
                val revalidation = SessionRevalidation(authController, applicationScope)
                val positions = runtime.keepOfflinePositions(downloadPreferences(context))
                watchNetworkReturns(context, onValidated = positions::requestSync) {
                    applicationScope.launch { revalidation.onNetworkReturned() }
                }
            }
        }

        private fun reportConsent(
            authController: MobileAuthController,
            putioClient: PutioClient,
            applicationScope: CoroutineScope,
        ) {
            applicationScope.launch {
                authController.state.collect { state ->
                    state.diagnosticsAnswer()?.let(PutioDiagnostics::update)
                    state.productAnalyticsAnswer()?.let { (accountId, enabled) ->
                        ProductAnalytics.update(accountId, enabled)
                    }
                }
            }
            val accountSettings = SdkAccountSettingsRepository(putioClient)
            ProductAnalytics.setConsentCheck { check ->
                val accountId = (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId
                applicationScope.launch { accountSettings.confirmProductAnalytics(check, accountId) }
            }
        }

        private fun sessionLeft(
            context: Context,
            session: MobileAuthSessionId?,
            authController: MobileAuthController,
            applicationScope: CoroutineScope,
        ) {
            applicationScope.launch(Dispatchers.IO) {
                MobileFileShareService.endSession(context, session)
            }
            MobileFileDrags.endSession(session)
            MobileDocumentsProvider.sessionLeft(context)
            MobileDownloadNotifications.cancelOtherAccounts(
                context,
                (authController.state.value as? MobileAuthState.SignedIn)?.account?.userId,
            )
            MobileWidgets.sessionLeft(context)
            MobileCast.sessionLeft(context, session)
            if (session != null) MobilePlaybackService.stop(context)
        }
    }
}

internal fun clearMobileSessionCaches(
    context: Context,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job {
    val appContext = context.applicationContext
    return CommonSessionCaches(
        clearImageMemoryCache = { SingletonImageLoader.get(appContext).memoryCache?.clear() },
        clearImageDiskCache = {
            SingletonImageLoader.get(appContext).diskCache?.let { diskCache ->
                diskCache.clear()
                diskCache.directory.toFile()
            }
        },
        clearPdfCache = { FilesPdfDocument.clearCache(appContext) },
        clearContinueWatching = { DeviceContinueWatchingStore.clearAll(appContext) },
        clearSwitchedToConverted = { DataStoreSwitchedToConvertedFiles.get(appContext).clear() },
        ioDispatcher = ioDispatcher,
    ).clear(
        context = appContext,
        scope = scope,
        clearAppLocalState = { MobileMoveTargetStore.clearAll(appContext) },
    )
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

internal fun MobileAuthState.diagnosticsAnswer(): DiagnosticsAnswer? =
    when (this) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        is MobileAuthState.ValidatingSession,
        is MobileAuthState.ValidationUnavailable,
        MobileAuthState.SigningOut,
        -> null

        is MobileAuthState.SignedOut ->
            DiagnosticsAnswer.SignedOut.takeUnless { reason == MobileSignedOutReason.SecureStorageUnavailable }
        MobileAuthState.AwaitingOAuthCallback -> DiagnosticsAnswer.SignedOut
        is MobileAuthState.SignedIn -> DiagnosticsAnswer.Account(account.diagnosticsEnabled).takeIf { validated }
    }

internal fun MobileAuthState.productAnalyticsAnswer(): Pair<Long?, Boolean>? =
    when (this) {
        is MobileAuthState.SignedOut, MobileAuthState.AwaitingOAuthCallback -> null to false
        is MobileAuthState.SignedIn -> (account.userId to account.productAnalyticsEnabled).takeIf { validated }
        else -> null
    }
