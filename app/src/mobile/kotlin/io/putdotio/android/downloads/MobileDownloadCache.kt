package io.putdotio.android.downloads

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

private const val OAUTH_TOKEN_QUERY = "oauth_token"
private const val API_HOST = "api.put.io"

/**
 * Process-wide Media3 download plumbing. Requests hold the token-free API URL;
 * the resolver adds the bearer header for API hosts and CDN segments arrive
 * pre-signed in playlist text, which the cache key strips before indexing.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadCache private constructor(context: Context) {
    // Only the application context is retained; lint cannot see the caller passed it.
    @SuppressLint("StaticFieldLeak")
    private val appContext: Context = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    // Internal storage: cached playlist bodies carry the server's oauth_token query, and
    // app-external directories are readable by other apps on Android 8 to 10.
    val cache: SimpleCache = SimpleCache(
        File(appContext.filesDir, CACHE_DIRECTORY),
        NoOpCacheEvictor(),
        databaseProvider,
    )
    private val httpClient = OkHttpClient()

    /** Replaced by the auth layer on sign-in and sign-out; never persisted here. */
    @Volatile
    var accessToken: String? = null

    /**
     * Completes once the auth layer has decided whether a stored session exists. A
     * background restart of the download service can open requests before that, so
     * the resolver waits here instead of sending an unauthenticated request.
     */
    val sessionSettled = CountDownLatch(1)

    fun markSessionSettled() = sessionSettled.countDown()

    /** Network access for downloads and for the player's cache misses. */
    val upstreamFactory: DataSource.Factory = DataSource.Factory {
        ResolvingDataSource(OkHttpDataSource.Factory(httpClient).createDataSource(), ::authorize)
    }

    /**
     * The player reads completed downloads from the cache and streams everything
     * else straight from the network: a null write sink keeps online playback from
     * filling the no-eviction download directory.
     */
    fun playbackFactory(userId: Long): DataSource.Factory = cacheFactory(userId).setCacheWriteDataSinkFactory(null)

    /** Who the signed-in player belongs to; a player built before sign-in matches no cached bytes. */
    @Volatile
    var activeUserId: Long? = null

    private fun cacheFactory(userId: Long): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(UserScopedCacheKeys(userId))
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * Media3's DownloadService binds the first manager it sees for the whole
     * process, so there is exactly one. Request ids are `userId:fileId`; each
     * request gets a downloader writing under that user's cache keys, and the
     * engine stops requests that belong to a user who is not signed in.
     */
    val downloadManager: DownloadManager = DownloadManager(
        appContext,
        DefaultDownloadIndex(databaseProvider),
        UserScopedDownloaderFactory(::cacheFactory, Executors.newFixedThreadPool(DOWNLOAD_THREADS)),
    ).apply {
        maxParallelDownloads = 1
        minRetryCount = MIN_RETRIES
    }

    private fun authorize(spec: DataSpec): DataSpec {
        if (spec.uri.host != API_HOST) return spec
        // Data sources open on Media3's loader threads; the wait is bounded by the restore itself.
        sessionSettled.await(SESSION_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val token = accessToken ?: return spec
        return spec.buildUpon()
            .setHttpRequestHeaders(spec.httpRequestHeaders + ("Authorization" to "Token $token"))
            .build()
    }

    companion object {
        private const val CACHE_DIRECTORY = "downloads"
        private const val DOWNLOAD_THREADS = 4
        private const val MIN_RETRIES = 3
        private const val SESSION_SETTLE_TIMEOUT_SECONDS = 30L

        @Volatile
        private var instance: MobileDownloadCache? = null

        fun get(context: Context): MobileDownloadCache =
            instance ?: synchronized(this) {
                instance ?: MobileDownloadCache(context.applicationContext).also { instance = it }
            }
    }
}

/** Request ids are `userId:fileId`; the user prefix scopes every cached byte the request produces. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun DownloadRequest.ownerUserId(): Long? = id.substringBefore(':', missingDelimiterValue = "").toLongOrNull()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class UserScopedDownloaderFactory(
    private val cacheFactory: (Long) -> CacheDataSource.Factory,
    private val executor: Executor,
) : DownloaderFactory {
    override fun createDownloader(request: DownloadRequest): Downloader {
        val userId = requireNotNull(request.ownerUserId()) { "Download request without an owner" }
        return DefaultDownloaderFactory(cacheFactory(userId), executor).createDownloader(request)
    }
}

/** The owning user, then the signed URL minus its token. The token is per-session noise. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class UserScopedCacheKeys(private val userId: Long) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String = "u$userId|${dataSpec.uri.withoutOauthToken()}"
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
