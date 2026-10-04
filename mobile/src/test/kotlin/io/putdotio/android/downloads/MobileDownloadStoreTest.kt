package io.putdotio.android.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        store.upsert(completed)
        store.upsert(failed)
        store.upsert(running)

        val reloaded = MobileDownloadStore(preferences, "user-1", Dispatchers.Unconfined)
            .entries.value.sortedBy { it.fileId.value }
        assertEquals(completed, reloaded[0])
        assertEquals(failed, reloaded[1])
        assertEquals(running.copy(status = DownloadStatus.Queued), reloaded[2])
    }

    @Test
    fun queueTimeDeleteMarkSubtitleSettingAndPositionSurviveAReload() = runBlocking {
        val store = MobileDownloadStore(preferences, "user-3", Dispatchers.Unconfined)
        val missing = entry(1L, DownloadStatus.Missing).copy(
            queuedAt = 77L,
            removing = true,
            subtitlesHidden = true,
            startFromSeconds = 42.5,
            durationSeconds = 1_800.0,
        )
        val paused = entry(2L, DownloadStatus.Paused(DownloadPauseReason.STORAGE, 9L))
        store.upsert(missing)
        store.upsert(paused)

        val reloaded = MobileDownloadStore(preferences, "user-3", Dispatchers.Unconfined)
            .entries.value.sortedBy { it.fileId.value }
        assertEquals(missing, reloaded[0])
        // A pause resumes by itself, so the next process starts it from the queue.
        assertEquals(paused.copy(status = DownloadStatus.Queued), reloaded[1])
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

    private fun entry(
        id: Long,
        status: DownloadStatus,
        type: PutioFileType = PutioFileType.VIDEO,
        artifact: DownloadArtifact = DownloadArtifact.HLS,
    ) = DownloadEntry(FilesItemId(id), "file-$id", type, artifact, status, createdAt = id * 10L)
}
