package io.putdotio.android.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserScopedCacheKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        MobileOAuthRuntime.resetForTests()
    }

    @Test
    fun keysDropTheSessionTokenAndKeepEveryOtherParameter() {
        val signed = "https://cdn.put.io/hls/7/seg-1.ts?oauth_token=secret-a&range=0-99&tag=a&tag=b"
        val key = UserScopedCacheKeys(42L).buildCacheKey(DataSpec(Uri.parse(signed)))

        assertEquals("u42|https://cdn.put.io/hls/7/seg-1.ts?range=0-99&tag=a&tag=b", key)
    }

    @Test
    fun anotherSessionTokenMapsToTheSameBytesAndAnotherUserDoesNot() {
        val first = DataSpec(Uri.parse("https://cdn.put.io/seg.ts?oauth_token=one"))
        val second = DataSpec(Uri.parse("https://cdn.put.io/seg.ts?oauth_token=two"))

        assertEquals(UserScopedCacheKeys(1L).buildCacheKey(first), UserScopedCacheKeys(1L).buildCacheKey(second))
        assertNotEquals(UserScopedCacheKeys(1L).buildCacheKey(first), UserScopedCacheKeys(2L).buildCacheKey(first))
    }

    @Test
    fun tokenFreeApiUrlsAreKeptVerbatim() {
        val api = DownloadArtifact.HLS.apiUrl(FilesItemId(7L))

        assertEquals("u3|$api", UserScopedCacheKeys(3L).buildCacheKey(DataSpec(Uri.parse(api))))
    }

    @Test
    fun dynamicKeysResolveCurrentUserOnEveryBuild() {
        var activeUser = NO_DOWNLOAD_USER
        val keys = UserScopedCacheKeys { activeUser }
        val spec = DataSpec("https://cdn.put.io/files/7/stream?oauth_token=tok".toUri())

        assertEquals("u-1|https://cdn.put.io/files/7/stream", keys.buildCacheKey(spec))

        activeUser = 11L
        assertEquals("u11|https://cdn.put.io/files/7/stream", keys.buildCacheKey(spec))

        activeUser = 22L
        assertEquals("u22|https://cdn.put.io/files/7/stream", keys.buildCacheKey(spec))

        activeUser = NO_DOWNLOAD_USER
        assertEquals("u-1|https://cdn.put.io/files/7/stream", keys.buildCacheKey(spec))
    }

    @Test
    fun singlePlaybackCacheFactorySwitchesUsersWithoutRecreation() {
        val dir = File(context.cacheDir, "dynamic-cache-${UUID.randomUUID()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(dir, NoOpCacheEvictor(), database)
        try {
            val url = "https://api.put.io/v2/files/99/stream".toUri()
            writeToCache(cache, UserScopedCacheKeys(1L), url, "alice-audio".encodeToByteArray())
            writeToCache(cache, UserScopedCacheKeys(2L), url, "bob-audio".encodeToByteArray())

            var upstreamOpens = 0
            val upstream = DataSource.Factory {
                object : BaseDataSource(false) {
                    private var delegate: ByteArrayDataSource? = null

                    override fun open(dataSpec: DataSpec): Long {
                        upstreamOpens++
                        return ByteArrayDataSource("upstream-audio".encodeToByteArray()).also {
                            delegate = it
                        }.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        requireNotNull(delegate).read(buffer, offset, length)

                    override fun getUri(): Uri? = delegate?.uri

                    override fun close() {
                        delegate?.close()
                        delegate = null
                    }
                }
            }
            var activeUser = NO_DOWNLOAD_USER
            val playbackFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setCacheKeyFactory(UserScopedCacheKeys { activeUser })
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)

            assertEquals("upstream-audio", readString(playbackFactory, url))
            assertEquals(1, upstreamOpens)

            activeUser = 1L
            assertEquals("alice-audio", readString(playbackFactory, url))
            assertEquals(1, upstreamOpens)

            activeUser = 2L
            assertEquals("bob-audio", readString(playbackFactory, url))
            assertEquals(1, upstreamOpens)

            activeUser = NO_DOWNLOAD_USER
            assertEquals("upstream-audio", readString(playbackFactory, url))
            assertEquals(2, upstreamOpens)
        } finally {
            cache.release()
            dir.deleteRecursively()
        }
    }

    @Test
    fun defaultPlaybackFactoryResolvesSignedInAccountsDynamically() {
        val downloads = MobileDownloadCache.get(context)
        val originalClient = downloads.httpClient
        val originalAuthState = downloads.authState
        val uri = "https://cdn.put.io/files/90400/track.mp3?oauth_token=test-tok".toUri()
        val aliceKey = UserScopedCacheKeys(90401L).buildCacheKey(DataSpec(uri))
        val bobKey = UserScopedCacheKeys(90402L).buildCacheKey(DataSpec(uri))
        try {
            var currentState: MobileAuthState = MobileAuthState.SignedOut()
            downloads.authState = { currentState }
            downloads.httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("network-stream".toResponseBody())
                        .build()
                }
                .build()
            writeToCache(downloads.cache, UserScopedCacheKeys(90401L), uri, "alice-bytes".encodeToByteArray())
            writeToCache(downloads.cache, UserScopedCacheKeys(90402L), uri, "bob-bytes".encodeToByteArray())

            val defaultFactory = downloads.playbackFactory()
            assertEquals("network-stream", readString(defaultFactory, uri))

            currentState = MobileAuthState.SignedIn(
                MobileAccount(90401L, "alice", "alice@example.com"),
                MobileAuthSessionId(1L),
            )
            assertEquals("alice-bytes", readString(defaultFactory, uri))

            currentState = MobileAuthState.SignedIn(
                MobileAccount(90402L, "bob", "bob@example.com"),
                MobileAuthSessionId(2L),
            )
            assertEquals("bob-bytes", readString(defaultFactory, uri))

            currentState = MobileAuthState.SignedOut()
            assertEquals("network-stream", readString(defaultFactory, uri))
        } finally {
            downloads.cache.removeResource(aliceKey)
            downloads.cache.removeResource(bobKey)
            downloads.httpClient = originalClient
            downloads.authState = originalAuthState
        }
    }

    @Test
    fun resolvePlaybackUserIdWaitsForSessionRestoreOffMainThread() {
        var currentState: MobileAuthState = MobileAuthState.RestoringSession
        val entered = CountDownLatch(1)
        val settled = CountDownLatch(1)
        var resolvedUser = NO_DOWNLOAD_USER
        val worker = thread {
            resolvedUser = resolvePlaybackUserId(
                authState = { currentState },
                awaitSessionSettled = {
                    entered.countDown()
                    settled.await(WAIT_SECONDS, TimeUnit.SECONDS)
                },
                onMainThread = { false },
            )
        }
        assertEquals(true, entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        currentState = MobileAuthState.SignedIn(
            MobileAccount(90401L, "alice", "alice@example.com"),
            MobileAuthSessionId(1L),
        )
        settled.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertEquals(90401L, resolvedUser)

        var mainWaits = 0
        val mainResolved = resolvePlaybackUserId(
            authState = { MobileAuthState.RestoringSession },
            awaitSessionSettled = { mainWaits++ },
            onMainThread = { true },
        )
        assertEquals(NO_DOWNLOAD_USER, mainResolved)
        assertEquals(0, mainWaits)
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }

    private fun writeToCache(
        cache: SimpleCache,
        keys: UserScopedCacheKeys,
        uri: Uri,
        bytes: ByteArray,
    ) {
        val writer = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(keys)
            .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) })
        readString(writer, uri)
    }

    private fun readString(factory: DataSource.Factory, uri: Uri): String =
        DataSourceInputStream(factory.createDataSource(), DataSpec(uri)).use {
            it.readBytes().decodeToString()
        }
}
