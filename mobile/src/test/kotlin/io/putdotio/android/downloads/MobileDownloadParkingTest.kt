package io.putdotio.android.downloads

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MediaDownloadToken
import io.putdotio.sdk.account.AccountDownloadToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileDownloadParkingTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val downloads = MobileDownloadCache.get(context)
    private val downloaders = ParkingScriptedDownloaders()
    private val managers = mutableListOf<DownloadManager>()

    @After
    fun tearDown() {
        downloads.downloadManagerForParking = null
        downloads.downloadToken = null
        managers.forEach { it.release() }
        downloaders.releaseAll()
    }

    @Test
    fun clearingDownloadTokenParksNonPausedDownloadsWithoutAnEngine() {
        val index = DefaultDownloadIndex(StandaloneDatabaseProvider(context), "ParkingActive")
        downloads.downloadToken = MediaDownloadToken { AccountDownloadToken("token") }
        index.putDownload(download("$ALICE:10", Download.STATE_QUEUED))
        index.putDownload(
            download(
                "$ALICE:11",
                Download.STATE_STOPPED,
                stopReason = MobileDownloadEngine.STOP_REASON_PAUSED,
            ),
        )
        val manager = manager(index)
        downloads.downloadManagerForParking = manager
        manager.resumeDownloads()
        awaitMain { manager.stateOf("$ALICE:10") == Download.STATE_DOWNLOADING }

        downloads.downloadToken = null

        awaitMain { manager.stateOf("$ALICE:10") == Download.STATE_STOPPED }
        assertEquals(MobileDownloadEngine.STOP_REASON_OTHER_USER, manager.stopReasonOf("$ALICE:10"))
        assertEquals(Download.STATE_STOPPED, manager.stateOf("$ALICE:11"))
        assertEquals(MobileDownloadEngine.STOP_REASON_PAUSED, manager.stopReasonOf("$ALICE:11"))
    }

    @Test
    fun clearingDownloadTokenBeforeInitializationParksOnceManagerLoadsUnlessReplaced() {
        val index = DefaultDownloadIndex(StandaloneDatabaseProvider(context), "ParkingInit")
        downloads.downloadToken = MediaDownloadToken { AccountDownloadToken("token") }
        index.putDownload(download("$ALICE:10", Download.STATE_QUEUED))
        val manager = manager(index)
        downloads.downloadManagerForParking = manager
        downloads.downloadToken = null

        awaitMain {
            manager.isInitialized && manager.stateOf("$ALICE:10") == Download.STATE_STOPPED
        }
        assertEquals(MobileDownloadEngine.STOP_REASON_OTHER_USER, manager.stopReasonOf("$ALICE:10"))

        val secondIndex = DefaultDownloadIndex(StandaloneDatabaseProvider(context), "ParkingRebound")
        secondIndex.putDownload(download("$ALICE:12", Download.STATE_QUEUED))
        val secondManager = manager(secondIndex)
        downloads.downloadManagerForParking = secondManager
        downloads.downloadToken = null
        downloads.downloadToken = MediaDownloadToken { AccountDownloadToken("fresh") }
        secondManager.resumeDownloads()

        awaitMain { secondManager.isInitialized && secondManager.stateOf("$ALICE:12") == Download.STATE_DOWNLOADING }
        assertEquals(Download.STOP_REASON_NONE, secondManager.stopReasonOf("$ALICE:12"))
    }

    private fun manager(index: DefaultDownloadIndex): DownloadManager =
        DownloadManager(context, index, downloaders).apply {
            requirements = Requirements(0)
            maxParallelDownloads = 1
            managers += this
        }

    private fun download(id: String, state: Int, stopReason: Int = 0) = Download(
        DownloadRequest.Builder(id, Uri.parse("https://api.put.io/v2/files/$id/stream")).build(),
        state,
        0L,
        0L,
        TOTAL_BYTES,
        stopReason,
        Download.FAILURE_REASON_NONE,
    )

    private fun DownloadManager.stopReasonOf(id: String): Int? =
        currentDownloads.firstOrNull { it.request.id == id }?.stopReason

    private fun DownloadManager.stateOf(id: String): Int? =
        currentDownloads.firstOrNull { it.request.id == id }?.state

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.nanoTime() < deadline) { "Condition not met within $AWAIT_SECONDS s" }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val ALICE = 1L
        const val TOTAL_BYTES = 1_000L
        const val AWAIT_SECONDS = 10L
        const val POLL_MILLIS = 10L
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class ParkingScriptedDownloaders : DownloaderFactory {
    private val transfers = ConcurrentHashMap<String, LinkedBlockingQueue<ParkingScriptedTransfer>>()

    override fun createDownloader(request: DownloadRequest): Downloader =
        ParkingScriptedTransfer().also { transfers.getOrPut(request.id) { LinkedBlockingQueue() }.put(it) }

    fun releaseAll() = transfers.values.forEach { queue -> queue.forEach { it.cancel() } }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class ParkingScriptedTransfer : Downloader {
    private val released = CountDownLatch(1)

    override fun download(progressListener: Downloader.ProgressListener?) {
        released.await()
        throw InterruptedException("cancelled")
    }

    override fun cancel() = released.countDown()

    override fun remove() = Unit
}
