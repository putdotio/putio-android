package io.putdotio.android.auth

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
import io.putdotio.android.files.ContinueWatchingEntry
import io.putdotio.android.files.DeviceContinueWatchingStore
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveTargetMemory
import io.putdotio.android.files.MobileMoveTargetStore
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
class MobileSessionCacheCleanupTest {
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
            MobileMoveTargetStore.clearAll(context)
            DataStoreSwitchedToConvertedFiles.get(context).clear()
            waitUntilSwitchedContains(SWITCHED_FILE_ID, expected = false)
            File(context.cacheDir, COIL_DISK_CACHE_DIR).deleteRecursively()
            File(context.cacheDir, VIEWER_CACHE_DIR).deleteRecursively()
        }
    }

    @Test
    fun logoutClearsAllSessionCachesOffMainAndKeepsDiskCacheUsable() = runBlocking {
        val harness = ControllerHarness(listOf(SessionValidationResult.Valid(ACCOUNT)))
        harness.controller.restoreSession()
        val seeded = seedCaches()
        assertDirty(seeded)

        harness.controller.logout()
        checkNotNull(harness.lastClearJob).join()

        assertCleared(seeded)
        val diskCache = checkNotNull(seeded.diskCache)
        val nextEditor = checkNotNull(diskCache.openEditor("next-session"))
        nextEditor.data.toFile().writeText("next-bytes")
        nextEditor.commit()
        diskCache.openSnapshot("next-session").use { assertNotNull(it) }
    }

    @Test
    fun authoritative401ClearsAllSessionCachesOffMain() = runBlocking {
        val harness = ControllerHarness(listOf(SessionValidationResult.Valid(ACCOUNT)))
        harness.controller.restoreSession()
        val sessionId = (harness.controller.state.value as MobileAuthState.SignedIn).sessionId
        val seeded = seedCaches()
        assertDirty(seeded)

        assertTrue(harness.controller.rejectAuthoritativeSession(sessionId))
        checkNotNull(harness.lastClearJob).join()

        assertCleared(seeded)
    }

    @Test
    fun coldStart401ClearsSeededDiskAndViewerCachesWithoutPriorImageLoad() = runBlocking {
        SingletonImageLoader.reset()
        val seeded = seedCaches(installImageLoader = false)
        assertDirty(seeded)

        val harness = ControllerHarness(listOf(SessionValidationResult.Rejected(SessionRejectionReason.Unauthorized)))
        harness.controller.restoreSession()
        checkNotNull(harness.lastClearJob).join()

        assertCleared(seeded)
    }

    @Test
    fun offlineRevalidationAccountSwitchClearsPriorAccountCaches() = runBlocking {
        val other = ACCOUNT.copy(userId = 99L, username = "other")
        val harness = ControllerHarness(
            validations = listOf(
                SessionValidationResult.Unavailable(IOException("offline")),
                SessionValidationResult.Valid(other),
            ),
            confirmedAccount = ACCOUNT,
        )
        harness.controller.restoreSession()
        val seeded = seedCaches()
        assertDirty(seeded)

        assertTrue(harness.controller.revalidate())
        checkNotNull(harness.lastClearJob).join()

        assertCleared(seeded)
    }

    private suspend fun seedCaches(installImageLoader: Boolean = true): SeededCaches {
        val clearThreads = mutableListOf<Thread>()
        var memoryCache: MemoryCache? = null
        var diskCache: DiskCache? = null
        if (installImageLoader) {
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
        val orphanCoilFile = File(context.cacheDir, "$COIL_DISK_CACHE_DIR/prior-process.1").apply {
            parentFile?.mkdirs()
            writeText("stale-coil")
        }
        val pdfFile = File(context.cacheDir, "$VIEWER_CACHE_DIR/doc.pdf").apply {
            parentFile?.mkdirs()
            writeText("%PDF-1.4")
        }
        DeviceContinueWatchingStore(context, ACCOUNT.userId).write(
            listOf(ContinueWatchingEntry(FilesItemId(SWITCHED_FILE_ID), "video.mkv", 30.0, 120.0, 1L)),
        )
        MobileMoveTargetStore(context, ACCOUNT.userId).write(
            FilesMoveTargetMemory(remember = true, lastTarget = listOf(FilesFolder(FilesItemId(10L), "Folder"))),
        )
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
        assertFalse(DeviceContinueWatchingStore(context, ACCOUNT.userId).read().isEmpty())
        assertTrue(MobileMoveTargetStore(context, ACCOUNT.userId).read().remember)
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
        assertTrue(DeviceContinueWatchingStore(context, ACCOUNT.userId).read().isEmpty())
        assertFalse(MobileMoveTargetStore(context, ACCOUNT.userId).read().remember)
        waitUntilSwitchedContains(SWITCHED_FILE_ID, expected = false)
        assertTrue(seeded.clearThreads.all { it !== Looper.getMainLooper().thread })
    }

    private suspend fun waitUntilSwitchedContains(fileId: Long, expected: Boolean) {
        val switched = DataStoreSwitchedToConvertedFiles.get(context)
        withTimeout(DATASTORE_TIMEOUT_MS) {
            while (switched.contains(fileId) != expected) {
                delay(POLL_DELAY_MS)
            }
        }
    }

    private inner class ControllerHarness(
        validations: List<SessionValidationResult>,
        confirmedAccount: MobileAccount? = null,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        var lastClearJob: Job? = null
        val controller = MobileAuthController(
            oauthConfiguration = MobileOAuthConfiguration.Configured("9677"),
            tokenStore = FakeAuthTokenStore(checkNotNull(AccessToken.parse("stored-token"))),
            oauthAttempts = OAuthAttempts(FakePendingOAuthAttemptStore()),
            sessionGateway = FakeAuthSessionGateway(validations),
            tokenRevocations = NoTokenRevocations,
            clearAccountLocalState = { lastClearJob = clearMobileSessionCaches(context, scope) },
            validatedAccounts = FakeValidatedAccounts(confirmedAccount),
        )
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

    private companion object {
        const val VIEWER_CACHE_DIR = "viewer"
        const val CACHE_BYTES = 1024L * 1024L
        const val SWITCHED_FILE_ID = 42L
        const val POLL_DELAY_MS = 10L
        const val DATASTORE_TIMEOUT_MS = 5_000L
        const val DISK_KEY = "thumb-key"
        val MEMORY_KEY = MemoryCache.Key("avatar")
        val ACCOUNT = MobileAccount(userId = 42L, username = "user", email = "user@example.com")
    }
}
