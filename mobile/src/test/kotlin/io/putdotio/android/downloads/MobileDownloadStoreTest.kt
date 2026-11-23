package io.putdotio.android.downloads

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("downloads-test", Context.MODE_PRIVATE)

    @Test
    fun rowsSurviveAReloadAndInFlightTransfersComeBackQueued() = runBlocking {
        val store = MobileDownloadStore(preferences, "user-1", Dispatchers.Unconfined)
        val completed = entry(1L, DownloadStatus.Completed(4_096L)).copy(accepted = true)
        val failed = entry(
            2L,
            DownloadStatus.Failed(DownloadFailureReason.STORAGE, 12L),
            PutioFileType.AUDIO,
            DownloadArtifact.ORIGINAL,
        )
        val running = entry(3L, DownloadStatus.Downloading(50L, 50f))
        val original = entry(4L, DownloadStatus.Completed(8_192L), artifact = DownloadArtifact.ORIGINAL_HLS)
        store.upsert(completed)
        store.upsert(failed)
        store.upsert(running)
        store.upsert(original)

        val reloaded = MobileDownloadStore(preferences, "user-1", Dispatchers.Unconfined)
            .entries.value.sortedBy { it.fileId.value }
        assertEquals(completed, reloaded[0])
        assertEquals(failed, reloaded[1])
        assertEquals(running.copy(status = DownloadStatus.Queued), reloaded[2])
        assertEquals(original, reloaded[3])
    }

    @Test
    fun queueTimeDeleteMarkSubtitleSettingPositionAndPickSurviveAReload() = runBlocking {
        val store = MobileDownloadStore(preferences, "user-3", Dispatchers.Unconfined)
        val missing = entry(1L, DownloadStatus.Missing).copy(
            queuedAt = 77L,
            removing = true,
            subtitlesHidden = true,
            startFromSeconds = 42.5,
            durationSeconds = 1_800.0,
            directPlayChosen = true,
        )
        val paused = entry(2L, DownloadStatus.Paused(DownloadPauseReason.STORAGE, 9L))
        store.upsert(missing)
        store.upsert(paused)

        val reloaded = MobileDownloadStore(preferences, "user-3", Dispatchers.Unconfined)
            .entries.value.sortedBy { it.fileId.value }
        assertEquals(missing, reloaded[0])
        // A pause resumes by itself, so the next process starts it from the queue.
        assertEquals(paused.copy(status = DownloadStatus.Queued), reloaded[1])
        // The viewer's pause holds, so the row offers Resume before Media3 reports.
        val held = entry(3L, DownloadStatus.Paused(DownloadPauseReason.USER, 9L)).copy(pauseRequested = true)
        store.upsert(held)
        val heldAgain = MobileDownloadStore(preferences, "user-3", Dispatchers.Unconfined).find(FilesItemId(3L))
        assertEquals(held, heldAgain)
        // A row written before these fields existed: queued when created, setting unknown.
        preferences.edit().putString(
            "user-4",
            """[{"fileId":5,"name":"old","type":"${PutioFileType.VIDEO.raw}","artifact":"HLS","createdAt":50,""" +
                """"accepted":true,"status":{"kind":"completed","bytes":7}}]""",
        ).commit()
        val legacy = MobileDownloadStore(preferences, "user-4", Dispatchers.Unconfined).entries.value.single()
        val expected = entry(5L, DownloadStatus.Completed(7L))
            .copy(name = "old", createdAt = 50L, queuedAt = 50L, accepted = true)
        assertEquals(expected, legacy)
    }

    @Test
    fun aRowThisBuildCannotReadCostsOnlyThatRowAndSurvivesTheNextWrite() = runBlocking {
        val good = """{"fileId":5,"name":"good","type":"VIDEO","artifact":"HLS","createdAt":50,""" +
            """"accepted":true,"status":{"kind":"completed","bytes":7}}"""
        // A newer build's rendition and a damaged row.
        val newer = """{"fileId":6,"name":"newer","type":"VIDEO","artifact":"DASH","createdAt":60,""" +
            """"status":{"kind":"completed","bytes":8}}"""
        preferences.edit().putString("user-5", "[$good,$newer,\"damaged\"]").commit()

        val store = MobileDownloadStore(preferences, "user-5", Dispatchers.Unconfined)
        assertEquals(listOf(5L), store.entries.value.map { it.fileId.value })

        store.upsert(entry(7L, DownloadStatus.Queued))
        store.remove(FilesItemId(5L))
        val written = preferences.getString("user-5", "").orEmpty()
        assertTrue(written.contains("\"DASH\"") && written.contains("damaged"))
        assertEquals(listOf(7L), MobileDownloadStore(preferences, "user-5", Dispatchers.Unconfined)
            .entries.value.map { it.fileId.value })

        // Deleting that file drops its unreadable row too, so a later build cannot bring it back.
        store.remove(FilesItemId(6L))
        assertTrue(!preferences.getString("user-5", "").orEmpty().contains("DASH"))
    }

    @Test
    fun aDocumentThatIsNotAListIsSetAsideBeforeItIsReplaced() = runBlocking {
        preferences.edit().putString("user-6", "[{not json").commit()
        val store = MobileDownloadStore(preferences, "user-6", Dispatchers.Unconfined)
        assertTrue(store.entries.value.isEmpty())
        store.upsert(entry(8L, DownloadStatus.Queued))
        assertEquals("[{not json", preferences.getString("user-6.unreadable", null))
    }

    @Test
    fun usersAreIsolatedAndStatusUpdatesAreVisibleImmediately() = runBlocking {
        val first = MobileDownloadStore(preferences, "user-1", Dispatchers.Unconfined)
        val second = MobileDownloadStore(preferences, "user-2", Dispatchers.Unconfined)
        first.upsert(entry(1L, DownloadStatus.Queued))
        assertTrue(second.entries.value.isEmpty())

        first.updateStatusBlocking(FilesItemId(1L)) { it.copy(status = DownloadStatus.Completed(7L)) }
        assertEquals(DownloadStatus.Completed(7L), first.entries.value.single().status)
        first.remove(FilesItemId(1L))
        assertTrue(first.entries.value.isEmpty())
    }

    @Test
    fun theIndexNeverContainsUrlsOrTokens() = runBlocking {
        val store = MobileDownloadStore(preferences, "user-9", Dispatchers.Unconfined)
        store.upsert(entry(5L, DownloadStatus.Completed(1L)))
        val raw = preferences.getString("user-9", "").orEmpty()
        assertTrue(raw.isNotEmpty())
        assertTrue(!raw.contains("http") && !raw.contains("token"))
    }

    @Test
    fun aViewersPauseIsOnDiskWhenUpdateStatusReturns() {
        val store = MobileDownloadStore(UnflushedApplies(preferences), "user-11", Dispatchers.Unconfined)
        store.addBlocking(entry(1L, DownloadStatus.Queued))

        runBlocking { store.updateStatus(FilesItemId(1L)) { it.copy(pauseRequested = true) } }

        val restored = MobileDownloadStore(preferences, "user-11", Dispatchers.Unconfined).entries.value
        assertEquals(listOf(true), restored.map { it.pauseRequested })
    }

    @Test
    fun mainThreadTransitionsNeverWaitOnAViewersDiskWrite() {
        val gated = GatedPreferences(preferences)
        val store = MobileDownloadStore(gated, "user-10", Dispatchers.Unconfined)
        store.addBlocking(entry(1L, DownloadStatus.Queued))
        store.addBlocking(entry(2L, DownloadStatus.Queued))
        val pause = thread { runBlocking { store.updateStatus(FilesItemId(1L)) { it.copy(pauseRequested = true) } } }
        try {
            assertTrue(gated.committing.await(5, TimeUnit.SECONDS))
            val transition = thread {
                store.updateStatusBlocking(FilesItemId(2L)) { it.copy(status = DownloadStatus.Completed(1L)) }
            }
            transition.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(transition.isAlive)
        } finally {
            gated.release.countDown()
            pause.join()
        }
        val rows = MobileDownloadStore(preferences, "user-10", Dispatchers.Unconfined).entries.value
            .sortedBy { it.fileId.value }
        assertEquals(listOf(true, false), rows.map { it.pauseRequested })
        assertEquals(DownloadStatus.Completed(1L), rows[1].status)
    }

    @Test
    fun settingConcurrencyDoesNotCallSynchronousCommit() {
        val noCommit = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        throw AssertionError("commit() must not run on main thread")
                    }
                }
            }
        }
        val settings = MobileDownloadSettings(noCommit)

        settings.concurrency = 2

        assertEquals(2, settings.concurrency)
    }

    @Test
    fun sharedDoesNotHoldGlobalLockAcrossDiskLoad() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gatedPrefs = object : SharedPreferences by preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "user-101") {
                    reading.countDown()
                    release.await()
                }
                return preferences.getString(key, defValue)
            }
        }
        val gatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                if (name == "io.putdotio.android.downloads") gatedPrefs else super.getSharedPreferences(name, mode)
        }
        val first = thread { MobileDownloadStore.shared(gatedContext, 101L) }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val second = thread { MobileDownloadStore.shared(gatedContext, 102L) }
            second.join(TimeUnit.SECONDS.toMillis(2))
            assertFalse(second.isAlive)
        } finally {
            release.countDown()
            first.join()
            MobileDownloadStore.clearSharedForTest(gatedContext)
        }
    }

    private class GatedPreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        val committing = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun edit(): SharedPreferences.Editor = GatedEditor(delegate.edit())

        private inner class GatedEditor(private val editor: SharedPreferences.Editor) :
            SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                committing.countDown()
                release.await()
                return editor.commit()
            }
        }
    }

    private fun entry(
        id: Long,
        status: DownloadStatus,
        type: PutioFileType = PutioFileType.VIDEO,
        artifact: DownloadArtifact = DownloadArtifact.HLS,
    ) = DownloadEntry(FilesItemId(id), "file-$id", type, artifact, status, createdAt = id * 10L)
}
