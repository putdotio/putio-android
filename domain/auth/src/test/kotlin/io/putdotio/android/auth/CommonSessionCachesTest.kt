package io.putdotio.android.auth

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommonSessionCachesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val defaultCoilDir = File(context.cacheDir, COIL_DISK_CACHE_DIR)
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        defaultCoilDir.deleteRecursively()
        File(context.cacheDir, "other_coil_dir").deleteRecursively()
    }

    @Test
    fun clearRunsLocalStateImmediatelyAndDiskAndImageCleanupOffMain() = runBlocking {
        val mainThread = Looper.getMainLooper().thread
        val localOrder = mutableListOf<String>()
        var memoryOffMain = false
        var diskOffMain = false
        var pdfOffMain = false

        val scope = trackedScope()
        val job = CommonSessionCaches(
            clearImageMemoryCache = { memoryOffMain = Thread.currentThread() !== mainThread },
            clearImageDiskCache = {
                diskOffMain = Thread.currentThread() !== mainThread
                null
            },
            clearPdfCache = { pdfOffMain = Thread.currentThread() !== mainThread },
            clearContinueWatching = { localOrder += "continue_watching" },
            clearSwitchedToConverted = { localOrder += "switched" },
        ).clear(context, scope) {
            localOrder += "app"
        }

        assertEquals(listOf("app", "continue_watching", "switched"), localOrder)
        job.join()
        assertTrue(memoryOffMain)
        assertTrue(diskOffMain)
        assertTrue(pdfOffMain)
    }

    @Test
    fun clearDeletesDefaultCoilDirWhenDiskCacheIsAbsentOrElsewhere() = runBlocking {
        val stale = File(defaultCoilDir, "stale.1").apply {
            parentFile?.mkdirs()
            writeText("cached")
        }
        val scope = trackedScope()
        CommonSessionCaches(
            clearImageMemoryCache = {},
            clearImageDiskCache = { null },
            clearPdfCache = {},
            clearContinueWatching = {},
            clearSwitchedToConverted = {},
        ).clear(context, scope).join()
        assertFalse(stale.exists())
        assertFalse(defaultCoilDir.exists())

        stale.parentFile?.mkdirs()
        stale.writeText("cached-again")
        val otherDir = File(context.cacheDir, "other_coil_dir").apply { mkdirs() }
        CommonSessionCaches(
            clearImageMemoryCache = {},
            clearImageDiskCache = { otherDir },
            clearPdfCache = {},
            clearContinueWatching = {},
            clearSwitchedToConverted = {},
        ).clear(context, scope).join()
        assertFalse(defaultCoilDir.exists())
        assertTrue(otherDir.exists())
    }

    @Test
    fun clearPreservesJournalWhenActiveDiskCacheUsesDefaultDir() = runBlocking {
        defaultCoilDir.mkdirs()
        val journal = File(defaultCoilDir, "journal").apply { writeText("j") }
        val journalTmp = File(defaultCoilDir, "journal.tmp").apply { writeText("t") }
        val journalBkp = File(defaultCoilDir, "journal.bkp").apply { writeText("b") }
        val entry = File(defaultCoilDir, "entry.0").apply { writeText("data") }
        val nested = File(defaultCoilDir, "sub/orphan.bin").apply {
            parentFile?.mkdirs()
            writeText("orphan")
        }

        val scope = trackedScope()
        CommonSessionCaches(
            clearImageMemoryCache = {},
            clearImageDiskCache = { defaultCoilDir },
            clearPdfCache = {},
            clearContinueWatching = {},
            clearSwitchedToConverted = {},
        ).clear(context, scope).join()

        assertTrue(journal.exists())
        assertTrue(journalTmp.exists())
        assertTrue(journalBkp.exists())
        assertFalse(entry.exists())
        assertFalse(nested.exists())
    }

    @Test
    fun clearStillClearsPdfCacheWhenImageDiskClearFails() = runBlocking {
        var pdfCleared = false
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ -> },
        ).also { scopes += it }

        val job = CommonSessionCaches(
            clearImageMemoryCache = {},
            clearImageDiskCache = { error("disk cache failed") },
            clearPdfCache = { pdfCleared = true },
            clearContinueWatching = {},
            clearSwitchedToConverted = {},
            ioDispatcher = Dispatchers.Unconfined,
        ).clear(context, scope)

        job.join()
        assertTrue(pdfCleared)
    }

    private fun trackedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
}
