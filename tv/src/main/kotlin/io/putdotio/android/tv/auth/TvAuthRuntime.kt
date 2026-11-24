package io.putdotio.android.tv.auth

import android.content.Context
import coil3.SingletonImageLoader
import io.putdotio.android.auth.CommonSessionCaches
import io.putdotio.android.auth.KeystoreAuthTokenStore
import io.putdotio.android.auth.PendingTokenRevocations
import io.putdotio.android.auth.PutioAuthTokenRevoker
import io.putdotio.android.auth.watchNetworkReturns
import io.putdotio.android.files.DeviceContinueWatchingStore
import io.putdotio.android.files.FilesPdfDocument
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.confirmProductAnalytics
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.telemetry.DiagnosticsAnswer
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.tv.home.TvDeviceShowHomeStore
import io.putdotio.android.tv.search.TvGlobalSearch
import io.putdotio.android.tv.search.tvGlobalSearch
import io.putdotio.android.tv.watchnext.TvProviderWatchNextStore
import io.putdotio.android.tv.watchnext.TvWatchNext
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide TV session: one SDK client, one controller, one application scope, and the
 * launcher's Watch Next row, which follows the session even with no screen showing.
 */
internal class TvAuthRuntime(
    val putioClient: PutioClient,
    val authController: TvAuthController,
    private val applicationScope: CoroutineScope,
    internal val watchNext: TvWatchNext,
    private val onSessionEnded: () -> Unit = {},
) {
    init {
        applicationScope.launch {
            authController.quietSignOuts.filter { it > 0 }.collect { onSessionEnded() }
        }
        applicationScope.launch {
            authController.state
                .map { it is TvAuthState.Linking }
                .distinctUntilChanged()
                .filter { it }
                .collect { onSessionEnded() }
        }
    }
    private val restoreMutex = Mutex()
    private var backgroundRestore: Deferred<Boolean>? = null

    /**
     * The signed-in session for a caller with no screen, such as the system search provider:
     * restores a stored session when none ran or an earlier failure left auth in
     * [TvAuthState.Initializing], and never starts a sign-in.
     */
    private suspend fun signedInSession(): TvAuthState.SignedIn? {
        (authController.state.value as? TvAuthState.SignedIn)?.let { return it }
        val deferred = restoreMutex.withLock {
            if (authController.state.value is TvAuthState.SignedIn) return@withLock null
            backgroundRestore ?: applicationScope.async(start = CoroutineStart.LAZY) {
                try {
                    authController.restoreSession(interactive = false)
                } finally {
                    withContext(NonCancellable) {
                        if (authController.state.value == TvAuthState.Initializing) {
                            restoreMutex.withLock { backgroundRestore = null }
                        }
                    }
                }
            }.also { backgroundRestore = it }
        }
        deferred?.await()
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
                okHttpClient = tvHttpClient(),
            )
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val tokenStore = KeystoreAuthTokenStore(context)
            val authController = TvAuthController(
                tokenStore = tokenStore,
                sessionGateway = PutioTvSessionGateway(putioClient),
                tokenRevocations = PendingTokenRevocations(
                    stores = KeystoreAuthTokenStore.pendingRevocations(context),
                    sessionStore = tokenStore,
                    revoker = PutioAuthTokenRevoker(putioClient),
                    scope = applicationScope,
                ),
                scope = applicationScope,
                legacySession = AsyncStorageLegacyTvSession(context),
            )
            applicationScope.launch {
                authController.state.collect { state ->
                    state.diagnosticsAnswer()?.let(PutioDiagnostics::update)
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
                onSessionEnded = { clearTvSessionCaches(context, applicationScope) },
            )
        }
    }
}

internal fun clearTvSessionCaches(
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
        clearAppLocalState = { TvDeviceShowHomeStore.clearAll(appContext) },
    )
}

internal fun TvAuthState.diagnosticsAnswer(): DiagnosticsAnswer? =
    when (this) {
        TvAuthState.Initializing,
        TvAuthState.RestoringSession,
        is TvAuthState.ValidatingSession,
        is TvAuthState.ValidationUnavailable,
        TvAuthState.SigningOut,
        -> null

        is TvAuthState.Linking ->
            DiagnosticsAnswer.SignedOut.takeUnless {
                (phase as? TvLinkPhase.Stopped)?.reason?.isStorageFailure == true
            }
        is TvAuthState.SignedIn -> DiagnosticsAnswer.Account(account.diagnosticsEnabled)
    }

/** The account and its product analytics answer: signed out is `null to false`; null keeps the last answer. */
internal fun TvAuthState.productAnalyticsAnswer(): Pair<Long?, Boolean>? =
    when (this) {
        is TvAuthState.Linking -> null to false
        is TvAuthState.SignedIn -> account.userId to account.productAnalyticsEnabled
        else -> null
    }
