package io.putdotio.android.tv.auth

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import io.putdotio.android.PutioApplication
import io.putdotio.android.auth.AccessToken
import io.putdotio.android.auth.COIL_DISK_CACHE_DIR
import io.putdotio.android.auth.InMemoryAuthTokenStore
import io.putdotio.android.auth.NoTokenRevocations
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.DeviceContinueWatchingStore
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.tv.home.TvDeviceShowHomeStore
import io.putdotio.android.tv.watchnext.TvStoredWatchNextProgram
import io.putdotio.android.tv.watchnext.TvWatchNext
import io.putdotio.android.tv.watchnext.TvWatchNextOwner
import io.putdotio.android.tv.watchnext.TvWatchNextProgram
import io.putdotio.android.tv.watchnext.TvWatchNextStore
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.auth.DeviceCodeAuthState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TvSessionCacheCleanupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scopes = mutableListOf<CoroutineScope>()
    private var installedLoader: ImageLoader? = null

    @After
    fun tearDown() {
        runBlocking {
            scopes.forEach { it.cancel() }
            SingletonImageLoader.reset()
            installedLoader?.shutdown()
            installedLoader = null
            DeviceContinueWatchingStore.clearAll(context)
            TvDeviceShowHomeStore.clearAll(context)
            DataStoreSwitchedToConvertedFiles.get(context).clear()
            waitUntilSwitchedContains(SWITCHED_FILE_ID, expected = false)
            File(context.cacheDir, COIL_DISK_CACHE_DIR).deleteRecursively()
            File(context.cacheDir, VIEWER_CACHE_DIR).deleteRecursively()
        }
    }

    @Test
    fun logoutAndInteractive401ClearAllTvSessionCachesOffMain() = runBlocking {
        val logoutHarness = RuntimeHarness(TvSessionValidation.Valid(accountInfo().toTvAccount()))
        assertTrue(logoutHarness.controller.restoreSession(interactive = true))
        val seededLogout = seedCaches()
        assertDirty(seededLogout)

        logoutHarness.controller.logout()
        checkNotNull(logoutHarness.lastClearJob).join()
        assertCleared(seededLogout)
        val nextEditor = checkNotNull(seededLogout.diskCache?.openEditor("next-session"))
        nextEditor.data.toFile().writeText("next-bytes")
        nextEditor.commit()
        seededLogout.diskCache?.openSnapshot("next-session").use { assertNotNull(it) }

        val rejectedHarness = RuntimeHarness(TvSessionValidation.Valid(accountInfo().toTvAccount()))
        assertTrue(rejectedHarness.controller.restoreSession(interactive = true))
        val sessionId = (rejectedHarness.controller.state.value as TvAuthState.SignedIn).sessionId
        val seeded401 = seedCaches()
        assertDirty(seeded401)

        assertTrue(rejectedHarness.controller.rejectAuthoritativeSession(sessionId, quiet = false))
        checkNotNull(rejectedHarness.lastClearJob).join()
        assertCleared(seeded401)
    }

    @Test
    fun quiet401ClearsAllTvSessionCachesOffMain() = runBlocking {
        val harness = RuntimeHarness(TvSessionValidation.Valid(accountInfo().toTvAccount()))
        assertTrue(harness.controller.restoreSession(interactive = true))
        val sessionId = (harness.controller.state.value as TvAuthState.SignedIn).sessionId
        val seeded = seedCaches()
        assertDirty(seeded)

        assertTrue(harness.controller.rejectAuthoritativeSession(sessionId, quiet = true))
        checkNotNull(harness.lastClearJob).join()
        assertCleared(seeded)
    }

    @Test
    fun coldStart401InteractiveAndQuietClearSeededCachesWithoutPriorImageLoad() = runBlocking {
        SingletonImageLoader.reset()
        val seededInteractive = seedCaches(installImageLoader = false)
        assertDirty(seededInteractive)
        val interactive = RuntimeHarness(TvSessionValidation.Rejected)
        assertFalse(interactive.controller.restoreSession(interactive = true))
        checkNotNull(interactive.lastClearJob).join()
        assertCleared(seededInteractive)

        SingletonImageLoader.reset()
        val seededQuiet = seedCaches(installImageLoader = false)
        assertDirty(seededQuiet)
        val quiet = RuntimeHarness(TvSessionValidation.Rejected)
        assertFalse(quiet.controller.restoreSession(interactive = false))
        checkNotNull(quiet.lastClearJob).join()
        assertCleared(seededQuiet)
    }

    @Test
    fun tvPutioApplicationOnCreateClearsViewerPdfCacheOffMain() = runBlocking {
        val pdfFile = File(context.cacheDir, "$VIEWER_CACHE_DIR/crash.pdf").apply {
            parentFile?.mkdirs()
            writeText("%PDF-1.4")
        }
        assertTrue(pdfFile.exists())

        (context.applicationContext as PutioApplication).onCreate()

        withTimeout(TIMEOUT_MS) {
            while (pdfFile.exists()) {
                delay(POLL_DELAY_MS)
            }
        }
        assertFalse(pdfFile.exists())
    }

    private suspend fun seedCaches(installImageLoader: Boolean = true): SeededCaches {
        val clearThreads = mutableListOf<Thread>()
        var memoryCache: MemoryCache? = null
        var diskCache: DiskCache? = null
        if (installImageLoader) {
            installedLoader?.shutdown()
            val realMemory = MemoryCache.Builder().maxSizeBytes(CACHE_BYTES).build()
            val realDisk = DiskCache.Builder()
                .directory(File(context.cacheDir, COIL_DISK_CACHE_DIR))
                .maxSizeBytes(CACHE_BYTES)
                .build()
            val wrappedMemory = RecordingMemoryCache(realMemory, clearThreads)
            val wrappedDisk = RecordingDiskCache(realDisk, clearThreads)
            val loader = ImageLoader.Builder(context)
                .memoryCache { wrappedMemory }
                .diskCache { wrappedDisk }
                .build()
            installedLoader = loader
            SingletonImageLoader.setUnsafe { loader }
            realMemory[MEMORY_KEY] = MemoryCache.Value(
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage(),
            )
            val editor = checkNotNull(realDisk.openEditor(DISK_KEY))
            editor.data.toFile().writeText("cached-thumb")
            editor.commit()
            memoryCache = realMemory
            diskCache = realDisk
        }
        val orphanCoilFile = File(context.cacheDir, "$COIL_DISK_CACHE_DIR/prior.1").apply {
            parentFile?.mkdirs()
            writeText("stale-coil")
        }
        val pdfFile = File(context.cacheDir, "$VIEWER_CACHE_DIR/doc.pdf").apply {
            parentFile?.mkdirs()
            writeText("%PDF-1.4")
        }
        DeviceContinueWatchingStore(context, USER_ID).write(
            listOf(ContinueWatchingEntry(FilesItemId(SWITCHED_FILE_ID), "video.mkv", 30.0, 120.0, 1L)),
        )
        TvDeviceShowHomeStore(context, USER_ID).write(false)
        DataStoreSwitchedToConvertedFiles.get(context).add(SWITCHED_FILE_ID)
        waitUntilSwitchedContains(SWITCHED_FILE_ID, expected = true)
        return SeededCaches(memoryCache, diskCache, orphanCoilFile, pdfFile, clearThreads)
    }

    private suspend fun assertDirty(seeded: SeededCaches) {
        if (seeded.memoryCache != null) assertNotNull(seeded.memoryCache[MEMORY_KEY])
        seeded.diskCache?.openSnapshot(DISK_KEY).let { snapshot ->
            if (seeded.diskCache != null) assertNotNull(snapshot)
            snapshot?.close()
        }
        assertTrue(seeded.orphanCoilFile.exists())
        assertTrue(seeded.pdfFile.exists())
        assertFalse(DeviceContinueWatchingStore(context, USER_ID).read().isEmpty())
        assertFalse(TvDeviceShowHomeStore(context, USER_ID).read())
        assertTrue(DataStoreSwitchedToConvertedFiles.get(context).contains(SWITCHED_FILE_ID))
    }

    private suspend fun assertCleared(seeded: SeededCaches) {
        if (seeded.memoryCache != null) assertNull(seeded.memoryCache[MEMORY_KEY])
        seeded.diskCache?.openSnapshot(DISK_KEY).let { snapshot ->
            if (seeded.diskCache != null) assertNull(snapshot)
            snapshot?.close()
        }
        assertFalse(seeded.orphanCoilFile.exists())
        assertFalse(seeded.pdfFile.exists())
        assertTrue(DeviceContinueWatchingStore(context, USER_ID).read().isEmpty())
        assertTrue(TvDeviceShowHomeStore(context, USER_ID).read())
        waitUntilSwitchedContains(SWITCHED_FILE_ID, expected = false)
        assertTrue(seeded.clearThreads.all { it !== Looper.getMainLooper().thread })
    }

    private suspend fun waitUntilSwitchedContains(fileId: Long, expected: Boolean) {
        val switched = DataStoreSwitchedToConvertedFiles.get(context)
        withTimeout(TIMEOUT_MS) {
            while (switched.contains(fileId) != expected) {
                delay(POLL_DELAY_MS)
            }
        }
    }

    private inner class RuntimeHarness(validation: TvSessionValidation) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        var lastClearJob: Job? = null
        val tokenStore = InMemoryAuthTokenStore(checkNotNull(AccessToken.parse("stored-token")))
        val controller = TvAuthController(
            tokenStore = tokenStore,
            sessionGateway = FakeGateway(validation),
            tokenRevocations = NoTokenRevocations,
            scope = scope,
            legacySession = EmptyLegacySession,
        )

        init {
            TvAuthRuntime(
                putioClient = PutioClient(PutioConfig(clientId = "1", clientName = "tv-test")),
                authController = controller,
                applicationScope = scope,
                watchNext = TvWatchNext(
                    store = NoOpWatchNextStore,
                    authState = controller.state,
                    quietSignOuts = controller.quietSignOuts,
                    scope = scope,
                ),
                onSessionEnded = { lastClearJob = clearTvSessionCaches(context, scope) },
            )
        }
    }

    private class SeededCaches(
        val memoryCache: MemoryCache?,
        val diskCache: DiskCache?,
        val orphanCoilFile: File,
        val pdfFile: File,
        val clearThreads: List<Thread>,
    )

    private class RecordingMemoryCache(
        private val delegate: MemoryCache,
        private val clearThreads: MutableList<Thread>,
    ) : MemoryCache by delegate {
        override fun clear() {
            clearThreads += Thread.currentThread()
            delegate.clear()
        }
    }

    private class RecordingDiskCache(
        private val delegate: DiskCache,
        private val clearThreads: MutableList<Thread>,
    ) : DiskCache by delegate {
        override fun clear() {
            clearThreads += Thread.currentThread()
            delegate.clear()
        }
    }

    private class FakeGateway(private val validation: TvSessionValidation) : TvSessionGateway {
        override fun link(): Flow<DeviceCodeAuthState> = emptyFlow()

        override fun setAccessToken(accessToken: AccessToken) = Unit

        override fun clearAccessToken() = Unit

        override suspend fun validateSession(): TvSessionValidation = validation
    }

    private object EmptyLegacySession : LegacyTvSession {
        override suspend fun read(): AccessToken? = null

        override suspend fun delete() = Unit
    }

    private object NoOpWatchNextStore : TvWatchNextStore {
        override suspend fun programs(): List<TvStoredWatchNextProgram> = emptyList()

        override suspend fun insert(owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long) = Unit

        override suspend fun update(
            rowId: Long,
            owner: TvWatchNextOwner,
            program: TvWatchNextProgram,
            engagedAtMillis: Long,
        ) = Unit

        override suspend fun delete(rowId: Long) = Unit
    }

    private companion object {
        const val USER_ID = 42L
        const val VIEWER_CACHE_DIR = "viewer"
        const val CACHE_BYTES = 1024L * 1024L
        const val SWITCHED_FILE_ID = 42L
        const val POLL_DELAY_MS = 10L
        const val TIMEOUT_MS = 5_000L
        const val DISK_KEY = "thumb-key"
        val MEMORY_KEY = MemoryCache.Key("avatar")

        fun accountInfo(): AccountInfo = AccountInfo(
            userId = USER_ID,
            username = "user",
            mail = "user@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1, size = 2, used = 1),
            settings = AccountSettings(sortBy = "NAME_ASC"),
            accountStatus = "active",
        )
    }
}
