package io.putdotio.android.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.scheduler.Requirements
import io.putdotio.android.PutioHttpClient
import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.files.FilesApi
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

internal const val OAUTH_TOKEN_QUERY = "oauth_token"
private const val API_HOST = "api.put.io"
private const val PUTIO_DOMAIN = ".put.io"

/**
 * Process-wide Media3 download plumbing. Requests and the playlists a download stores hold token-free
 * URLs. The resolver adds the account download token when a put.io request has none: a header on the API
 * host, the query on media hosts, which refuse the header. Online streams keep the SDK's query token.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadCache private constructor(context: Context) {
    // Only the application context is retained; lint cannot see the caller passed it.
    @SuppressLint("StaticFieldLeak")
    private val appContext: Context = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    // Internal storage: playlists a download stored before they were made token-free still carry
    // the server's oauth_token query, and app-external directories are readable by other apps on Android 8 to 10.
    val cache: SimpleCache = SimpleCache(
        File(appContext.filesDir, CACHE_DIRECTORY),
        NoOpCacheEvictor(),
        databaseProvider,
    )
    @VisibleForTesting
    internal var httpClient: OkHttpClient = PutioHttpClient.newBuilder().build()
    @VisibleForTesting
    internal var authState: () -> MobileAuthState = {
        MobileOAuthRuntime.get(appContext).authController.state.value
    }
    @VisibleForTesting
    internal var downloadManagerForParking: DownloadManager? = null
    private val downloadExecutor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)
    private val storedTokensSettled = CountDownLatch(1)
    private val storedTokensCleared: Future<*> = downloadExecutor.submit {
        try {
            cache.dropSignedMetadata()
            cache.sanitizeSignedPlaylists()
        } finally {
            storedTokensSettled.countDown()
        }
    }

    private val tokenGeneration = AtomicLong(0L)
    private val headlessParker = HeadlessDownloadParker()

    /** The signed-in session's download token, replaced by the auth layer on sign-in and sign-out. */
    @Volatile
    var downloadToken: MediaDownloadToken? = null
        set(value) {
            val generation = tokenGeneration.incrementAndGet()
            if (value != null) {
                field = value
            } else {
                val manager = downloadManagerForParking ?: downloadManager
                if (Looper.myLooper() == manager.applicationLooper) {
                    headlessParker.parkOnLooper(manager) { tokenGeneration.get() == generation }
                    field = null
                } else {
                    field = null
                    headlessParker.park(manager) { tokenGeneration.get() == generation }
                }
            }
        }

    /** Runs before the token is cleared so in-flight transfers park instead of failing with 401. */
    @Volatile
    var onTokenClearing: (() -> Unit)? = null

    /**
     * Completes once the auth layer has decided whether a stored session exists. A
     * background restart of the download service can open requests before that, so
     * the resolver waits here instead of sending an unauthenticated request.
     */
    val sessionSettled = CountDownLatch(1)

    fun markSessionSettled() = sessionSettled.countDown()

    /** Network access for downloads and for the player's cache misses. */
    val upstreamFactory: DataSource.Factory = DataSource.Factory {
        val network = OkHttpDataSource.Factory(httpClient).createDataSource()
        RefusedTokenDataSource(ResolvingDataSource(network, ::authorize)) { downloadToken?.refuse(it) }
    }

    /**
     * The player reads completed downloads from the cache and streams everything
     * else straight from the network: a null write sink keeps online playback from
     * filling the no-eviction download directory.
     */
    fun playbackFactory(
        userId: () -> Long = {
            resolvePlaybackUserId(
                authState = authState,
                awaitSessionSettled = { sessionSettled.await(SESSION_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS) },
            )
        },
    ): DataSource.Factory =
        cacheFactory(userId, upstreamFactory).setCacheWriteDataSinkFactory(null)

    private fun cacheFactory(userId: () -> Long, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(UserScopedCacheKeys(userId))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val downloadUpstreamFactory = DataSource.Factory {
        RequestUriDataSource(TokenFreePlaylistDataSource(upstreamFactory.createDataSource()))
    }

    internal val downloaderFactory: DownloaderFactory =
        UserScopedDownloaderFactory(
            { userId -> cacheFactory({ userId }, downloadUpstreamFactory) },
            downloadExecutor,
            storedTokensCleared,
        )

    private val managerConfigured = AtomicBoolean(false)
    private val initialConcurrency: Int = MobileDownloadSettings(appContext).concurrency
    private val rawDownloadManager: DownloadManager = DownloadManager(
        appContext,
        DefaultDownloadIndex(databaseProvider),
        downloaderFactory,
    ).also { manager ->
        if (Looper.myLooper() == manager.applicationLooper) {
            ensureManagerConfigured(appContext, manager, initialConcurrency, managerConfigured)
        } else {
            Handler(manager.applicationLooper).post {
                ensureManagerConfigured(appContext, manager, initialConcurrency, managerConfigured)
            }
        }
    }

    /**
     * Media3's DownloadService binds the first manager it sees for the whole
     * process, so there is exactly one. Request ids are `userId:fileId`; each
     * request gets a downloader writing under that user's cache keys, and the
     * engine stops requests that belong to a user who is not signed in. Transfers
     * wait for a network and for the system to stop reporting low storage.
     */
    val downloadManager: DownloadManager
        get() = rawDownloadManager.also { manager ->
            if (!managerConfigured.get() && Looper.myLooper() == manager.applicationLooper) {
                ensureManagerConfigured(appContext, manager, initialConcurrency, managerConfigured)
            }
        }

    /**
     * Whether a completed download's bytes are still here. The request URL keys the HLS
     * master playlist or the original file; a cleared cache directory loses both.
     */
    fun holdsLocalCopy(download: Download): Boolean {
        val userId = download.request.ownerUserId() ?: return false
        return holds(userId, download.request.uri)
    }

    fun holdsSubtitle(userId: Long, subtitle: DownloadSubtitle): Boolean = holds(userId, subtitle.url.toUri())

    private fun holds(userId: Long, uri: Uri): Boolean {
        storedTokensSettled.await(STORED_TOKENS_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val key = UserScopedCacheKeys(userId).buildCacheKey(DataSpec(uri))
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return if (length > 0L) cache.isCached(key, 0L, length) else cache.getCachedSpans(key).isNotEmpty()
    }

    /** Blocking index and cache read: whether this user's completed download still has its bytes. */
    fun hasLocalCopy(userId: Long, fileId: FilesItemId): Boolean =
        runCatching { downloadManager.downloadIndex.getDownload(downloadContentId(userId, fileId)) }.getOrNull()
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.let(::holdsLocalCopy) == true

    /** Blocking index and cache read; see [DownloadIndex.offlineRequest]. Only subtitle files on disk are kept. */
    fun offlineRequest(userId: Long, fileId: FilesItemId): OfflineRequest? =
        downloadManager.downloadIndex.offlineRequest(userId, fileId)
            ?.let { request -> request.copy(subtitles = request.subtitles.filter { holdsSubtitle(userId, it) }) }

    private val replacingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val replacing: Set<String> get() = replacingIds

    suspend fun dropReplacedBytes(
        manager: DownloadManager,
        userId: Long,
        entry: DownloadEntry,
        io: CoroutineContext,
        files: FilesApi,
    ) {
        val contentId = downloadContentId(userId, entry.fileId)
        val recorded = withContext(io) {
            try {
                manager.downloadIndex.getDownload(contentId)
            } catch (_: IOException) {
                null
            }
        }
        if (recorded == null || recorded.request.fetchesSameAs(entry.downloadRequest(userId, files))) return
        if (recorded.isTerminalState) {
            withContext(io) { downloaderFactory.createDownloader(recorded.request).remove() }
            return
        }
        replacingIds += contentId
        try {
            withTimeoutOrNull(REPLACE_TIMEOUT) { manager.removeAndAwait(contentId) }
        } finally {
            replacingIds -= contentId
        }
    }

    private fun authorize(spec: DataSpec): DataSpec {
        val host = spec.uri.host.orEmpty()
        if (!host.endsWith(PUTIO_DOMAIN) || spec.uri.getQueryParameter(OAUTH_TOKEN_QUERY) != null) return spec
        // Data sources open on Media3's loader threads; the wait is bounded by the restore itself.
        sessionSettled.await(SESSION_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val token = try {
            downloadToken?.let { runBlocking { it.get() } }
        } catch (error: PutioException) {
            throw IOException("The download token is unavailable", error)
        }
        return when {
            token == null -> spec
            host == API_HOST -> spec.buildUpon()
                .setHttpRequestHeaders(spec.httpRequestHeaders + ("Authorization" to "Token ${token.value}"))
                .build()
            else -> spec.withUri(spec.uri.buildUpon().appendQueryParameter(OAUTH_TOKEN_QUERY, token.value).build())
        }
    }

    companion object {
        private const val CACHE_DIRECTORY = "downloads"
        private const val DOWNLOAD_THREADS = 4
        private const val SESSION_SETTLE_TIMEOUT_SECONDS = 30L
        private const val STORED_TOKENS_SETTLE_TIMEOUT_SECONDS = 30L
        private val REPLACE_TIMEOUT = 10.seconds

        @Volatile
        private var instance: MobileDownloadCache? = null

        fun get(context: Context): MobileDownloadCache =
            instance ?: synchronized(this) {
                instance ?: MobileDownloadCache(context.applicationContext).also { instance = it }
            }
    }
}

/** Forgets a download token put.io answered 401 to, so the next attempt loads a fresh one. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class RefusedTokenDataSource(
    private val upstream: DataSource,
    private val onRefused: (AccountDownloadToken) -> Unit,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long =
        try {
            upstream.open(dataSpec)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (error.responseCode == HTTP_UNAUTHORIZED) error.dataSpec.sentToken()?.let(onRefused)
            throw error
        }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun DataSpec.sentToken(): AccountDownloadToken? =
    (httpRequestHeaders["Authorization"]?.removePrefix("Token ") ?: uri.getQueryParameter(OAUTH_TOKEN_QUERY))
        ?.let(::AccountDownloadToken)

private const val HTTP_UNAUTHORIZED = 401

/** Request ids are `userId:fileId`; the user prefix scopes every cached byte the request produces. */
internal fun downloadContentId(userId: Long, fileId: FilesItemId): String = "$userId:${fileId.value}"

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadRequest.ownerUserId(): Long? = id.substringBefore(':', missingDelimiterValue = "").toLongOrNull()

/**
 * The URL this user's download was requested with, and the subtitle files it asked for. The cache is keyed by the
 * URL, so offline playback replays it verbatim, including downloads made before [apiUrl] last changed.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadIndex.offlineRequest(userId: Long, fileId: FilesItemId): OfflineRequest? =
    getDownload(downloadContentId(userId, fileId))?.request?.let { request ->
        OfflineRequest(request.uri.toString(), request.data.toDownloadRequestData().subtitles)
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private suspend fun DownloadManager.removeAndAwait(contentId: String) =
    suspendCancellableCoroutine { continuation ->
        val listener = object : DownloadManager.Listener {
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                if (download.request.id != contentId) return
                downloadManager.removeListener(this)
                continuation.resume(Unit)
            }
        }
        addListener(listener)
        continuation.invokeOnCancellation { removeListener(listener) }
        removeDownload(contentId)
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class UserScopedDownloaderFactory(
    private val cacheFactory: (Long) -> CacheDataSource.Factory,
    private val executor: Executor,
    private val storedTokensCleared: Future<*>,
) : DownloaderFactory {
    override fun createDownloader(request: DownloadRequest): Downloader {
        val userId = requireNotNull(request.ownerUserId()) { "Download request without an owner" }
        val cache = cacheFactory(userId)
        val download = DefaultDownloaderFactory(cache, executor).createDownloader(request)
        val subtitles = request.data.toDownloadRequestData().subtitles.map { subtitle ->
            ProgressiveDownloader(MediaItem.fromUri(subtitle.url), cache, executor)
        }
        val downloader = if (subtitles.isEmpty()) download else SubtitledDownloader(download, subtitles)
        return AfterTokenCleanupDownloader(downloader, storedTokensCleared)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class AfterTokenCleanupDownloader(
    private val downloader: Downloader,
    private val storedTokensCleared: Future<*>,
) : Downloader by downloader {
    override fun download(progressListener: Downloader.ProgressListener?) {
        awaitCleanup()
        downloader.download(progressListener)
    }

    override fun remove() {
        awaitCleanup()
        downloader.remove()
    }

    private fun awaitCleanup() {
        try {
            storedTokensCleared.get()
        } catch (error: ExecutionException) {
            throw IOException("Stored playlist tokens were not removed", error)
        }
    }
}

internal const val NO_DOWNLOAD_USER = -1L

internal fun resolvePlaybackUserId(
    authState: () -> MobileAuthState,
    awaitSessionSettled: () -> Unit,
    onMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
): Long {
    val current = authState()
    val pendingRestore = current is MobileAuthState.RestoringSession ||
        current is MobileAuthState.ValidatingSession
    if (pendingRestore && !onMainThread()) awaitSessionSettled()
    return (authState() as? MobileAuthState.SignedIn)?.account?.userId ?: NO_DOWNLOAD_USER
}

/** The owning user, then the signed URL minus its token. The token is per-session noise. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class UserScopedCacheKeys(private val userId: () -> Long) : CacheKeyFactory {
    constructor(userId: Long) : this({ userId })

    override fun buildCacheKey(dataSpec: DataSpec): String = "u${userId()}|${dataSpec.uri.withoutOauthToken()}"
}

internal fun Uri.withoutOauthToken(): Uri {
    if (getQueryParameter(OAUTH_TOKEN_QUERY) == null) return this
    val builder = buildUpon().clearQuery()
    for (name in queryParameterNames) {
        if (name == OAUTH_TOKEN_QUERY) continue
        for (value in getQueryParameters(name)) builder.appendQueryParameter(name, value)
    }
    return builder.build()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class HeadlessDownloadParker {
    private var pending: Pair<DownloadManager, DownloadManager.Listener>? = null

    fun park(manager: DownloadManager, isCurrent: () -> Boolean) {
        Handler(manager.applicationLooper).post { parkOnLooper(manager, isCurrent) }
    }

    fun parkOnLooper(manager: DownloadManager, isCurrent: () -> Boolean) {
        if (!isCurrent()) return
        if (manager.isInitialized) {
            clearPending(manager)
            parkInitialized(manager)
            return
        }
        if (pending?.first === manager) {
            clearPending(manager)
        } else {
            pending?.let { (previousManager, listener) -> previousManager.removeListener(listener) }
            pending = null
        }
        lateinit var attached: DownloadManager.Listener
        val listener = object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                downloadManager.removeListener(this)
                if (pending?.second === attached) pending = null
                if (isCurrent()) parkInitialized(downloadManager)
            }
        }
        attached = listener
        pending = manager to listener
        manager.addListener(listener)
    }

    private fun clearPending(manager: DownloadManager) {
        val current = pending
        if (current?.first === manager) {
            manager.removeListener(current.second)
            pending = null
        }
    }

    private fun parkInitialized(manager: DownloadManager) {
        manager.currentDownloads
            .filter(Download::shouldParkWhenTokenClears)
            .forEach { manager.setStopReason(it.request.id, MobileDownloadEngine.STOP_REASON_OTHER_USER) }
    }
}

private const val MIN_RETRIES = 3

private fun Context.signedInUserId(): Long? =
    (MobileOAuthRuntime.get(this).authController.state.value as? MobileAuthState.SignedIn)?.account?.userId

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ensureManagerConfigured(
    appContext: Context,
    manager: DownloadManager,
    concurrency: Int,
    configured: AtomicBoolean,
) {
    if (!configured.compareAndSet(false, true)) return
    manager.maxParallelDownloads = concurrency
    manager.minRetryCount = MIN_RETRIES
    manager.requirements = Requirements(Requirements.NETWORK or Requirements.DEVICE_STORAGE_NOT_LOW)
    manager.addListener(
        MobileDownloadNotifications(
            context = appContext,
            files = { MobileOAuthRuntime.get(appContext).putioClient.files },
            signedInUser = { appContext.signedInUserId() },
        ),
    )
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.shouldParkWhenTokenClears(): Boolean =
    !isTerminalState &&
        stopReason != MobileDownloadEngine.STOP_REASON_PAUSED &&
        stopReason != MobileDownloadEngine.STOP_REASON_OTHER_USER
