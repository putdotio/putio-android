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
        val completed = entry(1L, DownloadStatus.Completed(4_096L))
        val failed = entry(
            2L, DownloadStatus.Failed(DownloadFailureReason.STORAGE, 12L), PutioFileType.AUDIO, DownloadArtifact.ORIGINAL,
        )
        val running = entry(3L, DownloadStatus.Downloading(50L, 100L))
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
