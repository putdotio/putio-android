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
import java.io.File
import java.util.concurrent.Executors
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
    val cache: SimpleCache = SimpleCache(
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, CACHE_DIRECTORY),
        NoOpCacheEvictor(),
        databaseProvider,
    )
    private val httpClient = OkHttpClient()

    /** Replaced by the auth layer on sign-in and sign-out; never persisted here. */
    @Volatile
    var accessToken: String? = null

    /** Network access for downloads and for the player's cache misses. */
    val upstreamFactory: DataSource.Factory = DataSource.Factory {
        ResolvingDataSource(OkHttpDataSource.Factory(httpClient).createDataSource(), ::authorize)
    }

    private val databaseIndex = DefaultDownloadIndex(databaseProvider)
    private val managers = mutableMapOf<Long, DownloadManager>()
    private val executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)

    /**
     * The player reads completed downloads from the cache and streams everything
     * else straight from the network: a null write sink keeps online playback from
     * filling the no-eviction download directory.
     */
    fun playbackFactory(userId: Long): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(UserScopedCacheKeys(userId))
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * One manager per user over the shared cache and index. Each writes under
     * user-prefixed keys, which is the only way to scope HLS child requests since
     * Media3 hands segment specs to the key factory without the request's key.
     * The service reads the manager for the signed-in user only.
     */
    fun downloadManager(userId: Long): DownloadManager = synchronized(managers) {
        managers.getOrPut(userId) {
            DownloadManager(
                appContext,
                databaseIndex,
                DefaultDownloaderFactory(
                    CacheDataSource.Factory()
                        .setCache(cache)
                        .setCacheKeyFactory(UserScopedCacheKeys(userId))
                        .setUpstreamDataSourceFactory(upstreamFactory),
                    executor,
                ),
            ).apply {
                maxParallelDownloads = 1
                minRetryCount = MIN_RETRIES
            }
        }
    }

    /** The user whose downloads the foreground service currently drives. */
    @Volatile
    var activeUserId: Long? = null

    private fun authorize(spec: DataSpec): DataSpec {
        val token = accessToken
        if (token == null || spec.uri.host != API_HOST) return spec
        return spec.buildUpon()
            .setHttpRequestHeaders(spec.httpRequestHeaders + ("Authorization" to "Token $token"))
            .build()
    }

    companion object {
        private const val CACHE_DIRECTORY = "downloads"
        private const val DOWNLOAD_THREADS = 4
        private const val MIN_RETRIES = 3

        @Volatile
        private var instance: MobileDownloadCache? = null

        fun get(context: Context): MobileDownloadCache =
            instance ?: synchronized(this) {
                instance ?: MobileDownloadCache(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Key for one cached object: the owning user, then the signed URL minus its
 * token. The token is per-session noise; the user keeps accounts apart on a
 * shared cache.
 */
internal fun userScopedCacheKey(userId: Long, uri: Uri): String = "u$userId|${uri.withoutOauthToken()}"

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class UserScopedCacheKeys(private val userId: Long) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String = userScopedCacheKey(userId, dataSpec.uri)
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
