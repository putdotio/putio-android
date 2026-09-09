package io.putdotio.android.downloads

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
    private val databaseProvider = StandaloneDatabaseProvider(context)
    val cache: SimpleCache = SimpleCache(
        File(context.getExternalFilesDir(null) ?: context.filesDir, CACHE_DIRECTORY),
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

    /** What the player reads through offline: cache first, network only on a miss. */
    val playbackFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setCacheKeyFactory(TokenFreeCacheKeys)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // The convenience constructor would wrap this factory in one with default keys;
    // the downloader must write under the same token-free keys the player reads.
    val downloadManager: DownloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(databaseProvider),
        DefaultDownloaderFactory(
            CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(TokenFreeCacheKeys)
                .setUpstreamDataSourceFactory(upstreamFactory),
            Executors.newFixedThreadPool(DOWNLOAD_THREADS),
        ),
    ).apply {
        maxParallelDownloads = 1
        minRetryCount = MIN_RETRIES
    }

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

/** The signed segment URL minus its token identifies the bytes; the token is per-session noise. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object TokenFreeCacheKeys : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String = dataSpec.uri.withoutOauthToken().toString()
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
