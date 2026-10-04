package io.putdotio.android.downloads

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import io.putdotio.android.R
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.HLS_ALL_SUBTITLES
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridges one user's download index to the process-wide Media3 manager.
 * Requests carry the token-free API URL; Media3 persists them, resumes them, and
 * reports progress back here. Requests of any other user are stopped while this
 * user is signed in, so no session ever drives another account's transfers.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileDownloadEngine(
    context: Context,
    private val store: MobileDownloadStore,
    private val userId: Long,
    scope: CoroutineScope,
    private val downloadManager: DownloadManager = MobileDownloadCache.get(context).downloadManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DownloadEngine {
    private val appContext = context.applicationContext
    private val downloads = MobileDownloadCache.get(appContext)
    private val settings = MobileDownloadSettings(appContext)
    private val standInName = appContext.getString(R.string.mobile_downloads_recovered_name)
    private val removing = mutableSetOf<FilesItemId>()
    private val parkOnTokenClearing: () -> Unit = { park() }

    // A sign-out closes this engine; nothing it scheduled may un-park that user's transfers afterwards.
    @Volatile
    private var closed = false
    private val reconcileJob: Job

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?,
        ) {
            reflect(download, finalException)
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            val fileId = fileIdOf(userId, download.request.id)
            if (closed || fileId == null) return
            synchronized(removing) { removing -= fileId }
            store.removeBlocking(fileId)
        }

        // Media3 keeps queued rows queued while offline or low on storage; the rows show why nothing moves.
        override fun onWaitingForRequirementsChanged(
            downloadManager: DownloadManager,
            waitingForRequirements: Boolean,
        ) {
            for (download in downloadManager.currentDownloads) reflect(download, null)
        }

        // A wait can change cause, from network to storage, without ending.
        override fun onRequirementsStateChanged(
            downloadManager: DownloadManager,
            requirements: Requirements,
            notMetRequirements: Int,
        ) {
            for (download in downloadManager.currentDownloads) reflect(download, null)
        }
    }

    init {
        downloads.activeUserId = userId
        downloads.onTokenClearing = parkOnTokenClearing
        downloadManager.addListener(listener)
        // The index read is SQLite; only the manager calls must run on its looper.
        reconcileJob = scope.launch {
            val (snapshot, missing) = withContext(ioDispatcher) {
                val indexed = downloadManager.downloadIndex.getDownloads().use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.download) }
                }
                val gone = indexed.filter { it.state == Download.STATE_COMPLETED && !downloads.holdsLocalCopy(it) }
                    .mapNotNullTo(mutableSetOf()) { fileIdOf(userId, it.request.id) }
                indexed to gone
            }
            recover(reconcile(snapshot, missing))
            if (!closed) downloadManager.resumeDownloads()
        }
    }

    /** Media3's index is the source of truth for states reached while no UI listened. */
    private fun reconcile(indexed: List<Download>, missing: Set<FilesItemId>): Map<FilesItemId, Download> {
        if (closed) return emptyMap()
        val known = mutableMapOf<FilesItemId, Download>()
        for (download in indexed) {
            val fileId = fileIdOf(userId, download.request.id)
            if (fileId == null) {
                // Another account's request: park it; its owner's session resumes it.
                if (download.request.ownerUserId() != null && !download.isTerminalState) {
                    downloadManager.setStopReason(download.request.id, STOP_REASON_OTHER_USER)
                }
                continue
            }
            known[fileId] = download
            if (download.state == Download.STATE_REMOVING) synchronized(removing) { removing += fileId }
            // A download whose row was lost stays visible, playable and deletable; only a
            // confirmed delete removes bytes.
            if (download.state != Download.STATE_REMOVING) {
                store.addBlocking(download.recoveredEntry(fileId, standInName))
            }
            if (download.stopReason == STOP_REASON_OTHER_USER) {
                downloadManager.setStopReason(download.request.id, Download.STOP_REASON_NONE)
            }
            if (fileId in missing) store.markMissing(fileId) else reflect(download, null)
        }
        return known
    }

    /** Every app row ends in a state the viewer can act on; only a confirmed delete drops one. */
    private fun recover(known: Map<FilesItemId, Download>) {
        if (closed) return
        for (entry in store.entries.value) {
            val download = known[entry.fileId]
            when {
                // The delete was confirmed but the process died before Media3 heard of it.
                entry.removing && download?.state != Download.STATE_REMOVING ->
                    if (download == null) store.removeBlocking(entry.fileId) else remove(entry.fileId)
                download != null -> Unit
                // Media3 has no record: it lost the finished bytes, never saw the request, or
                // dropped it. Finished rows read as missing, failed rows wait for Retry, and
                // everything else goes back in the queue.
                entry.isCompleted -> store.markMissing(entry.fileId)
                entry.canRetry -> Unit
                else -> start(entry)
            }
        }
    }

    override fun start(entry: DownloadEntry) = sendDownloadRequest(appContext, userId, entry)

    override fun remove(fileId: FilesItemId) {
        MobileDownloadNotifications.cancel(appContext, userId, fileId)
        // The service queue runs a remove after any add already posted, so the intent
        // always goes out; only the row's fate depends on whether Media3 has a record.
        val contentId = downloadContentId(userId, fileId)
        DownloadService.sendRemoveDownload(appContext, MobileDownloadService::class.java, contentId, false)
        val known = runCatching { downloadManager.downloadIndex.getDownload(contentId) }.getOrNull()
        if (known == null) {
            store.removeBlocking(fileId)
        } else {
            synchronized(removing) { removing += fileId }
        }
    }

    /** True while Media3 is still deleting this file's bytes; a new download must wait. */
    override fun isRemoving(fileId: FilesItemId): Boolean = synchronized(removing) { fileId in removing }

    override val concurrency: Int get() = settings.concurrency

    /** Media3 stops the transfers beyond a lower limit and keeps their bytes; they rejoin the queue. */
    override fun setConcurrency(limit: Int) {
        settings.concurrency = limit
        downloadManager.maxParallelDownloads = limit
    }

    /**
     * Detaches the UI. Transfers keep running: the service owns them and the token
     * stays valid while the user is signed in. A sign-in by another user parks them
     * through that user's reconcile, and a sign-out parks them through [park].
     */
    fun close() {
        closed = true
        reconcileJob.cancel()
        downloadManager.removeListener(listener)
        if (downloads.activeUserId == userId) downloads.activeUserId = null
        if (downloads.onTokenClearing === parkOnTokenClearing) downloads.onTokenClearing = null
    }

    /** Sign-out: stop this user's transfers before the token disappears; the next sign-in resumes them. */
    fun park() {
        for (download in downloadManager.currentDownloads) {
            if (fileIdOf(userId, download.request.id) != null) {
                downloadManager.setStopReason(download.request.id, STOP_REASON_OTHER_USER)
            }
        }
    }

    /**
     * Media3 reports state transitions but not byte progress, so the Downloads
     * screen asks here while visible. Progress stays in memory: Media3's index
     * holds the bytes, and reconcile restores them after a restart.
     */
    override fun refreshProgress() {
        if (closed) return
        for (download in downloadManager.currentDownloads) {
            val fileId = fileIdOf(userId, download.request.id)
            if (download.state != Download.STATE_DOWNLOADING || fileId == null) continue
            store.updateProgressInMemory(fileId, download.bytesDownloaded, download.knownPercent())
        }
    }

    private fun reflect(download: Download, error: Exception?) {
        val fileId = fileIdOf(userId, download.request.id)
        if (closed || fileId == null || download.state == Download.STATE_REMOVING) return
        val pause = downloadManager.pauseReason()
        val status = download.toStatus(error, pause) ?: return
        store.updateStatusBlocking(fileId) { current ->
            // Reconcile sees the failed state without its exception; the stored reason is better.
            val kept = current.status as? DownloadStatus.Failed
            val next = if (status is DownloadStatus.Failed && error == null && kept != null) kept else status
            current.copy(
                status = next,
                accepted = true,
                queuedAt = download.startTimeMs.takeIf { it > 0L } ?: current.queuedAt,
            )
        }
    }

    internal companion object {
        /** Media3 stop reason for requests whose owner is not the signed-in user. */
        const val STOP_REASON_OTHER_USER = 1
    }
}

/**
 * Hands [userId]'s download to Media3 through the download service: a new request, or a failed or
 * missing one again, which joins the back of the queue. Its last outcome notification goes.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun sendDownloadRequest(context: Context, userId: Long, entry: DownloadEntry) {
    val url = entry.artifact.apiUrl(entry.fileId, subtitlesHidden = entry.subtitlesHidden == true)
    val request = DownloadRequest.Builder(downloadContentId(userId, entry.fileId), url.toUri())
        .setMimeType(if (entry.artifact == DownloadArtifact.HLS) MimeTypes.APPLICATION_M3U8 else null)
        // The name rides along so a row lost from the app's index can be rebuilt with it.
        .setData(entry.name.encodeToByteArray())
        .build()
    MobileDownloadNotifications.cancel(context, userId, entry.fileId)
    DownloadService.sendAddDownload(context, MobileDownloadService::class.java, request, true)
}

/** This user's file id in a `userId:fileId` request id; null for another account's request. */
private fun fileIdOf(userId: Long, contentId: String): FilesItemId? {
    if (contentId.substringBefore(':', missingDelimiterValue = "").toLongOrNull() != userId) return null
    return contentId.substringAfter(':').toLongOrNull()?.takeIf { it > 0L }?.let(::FilesItemId)
}

/**
 * A row rebuilt from Media3's record when the app's own row is gone. The rendition comes from the
 * request: an HLS playlist is a video, an original is audio, as only audio downloads the original.
 * The name is the one the request carried, else a neutral stand-in; the status follows Media3.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.recoveredEntry(fileId: FilesItemId, standInName: String): DownloadEntry {
    val hls = request.mimeType == MimeTypes.APPLICATION_M3U8 || request.uri.path.orEmpty().endsWith(".m3u8")
    val subtitleCount = request.uri.getQueryParameter("max_subtitle_count")
    val time = startTimeMs.takeIf { it > 0L } ?: updateTimeMs
    return DownloadEntry(
        fileId = fileId,
        name = request.data.decodeToString().ifBlank { standInName },
        type = if (hls) PutioFileType.VIDEO else PutioFileType.AUDIO,
        artifact = if (hls) DownloadArtifact.HLS else DownloadArtifact.ORIGINAL,
        status = DownloadStatus.Queued,
        createdAt = time,
        accepted = true,
        queuedAt = time,
        recovered = true,
        subtitlesHidden = if (hls) subtitleCount?.let { it == "0" } else null,
    )
}

private fun MobileDownloadStore.markMissing(fileId: FilesItemId) =
    updateStatusBlocking(fileId) { it.copy(status = DownloadStatus.Missing, accepted = true) }

/**
 * Built here, not by the SDK: its URL builders embed `oauth_token`, and Media3 persists
 * this URL, so it must stay token-free. HLS asks for every subtitle rendition, or for none
 * when the account hides subtitles, as streaming does (#237, #53).
 */
internal fun DownloadArtifact.apiUrl(fileId: FilesItemId, subtitlesHidden: Boolean = false): String =
    when (this) {
        DownloadArtifact.HLS ->
            "https://api.put.io/v2/files/${fileId.value}/hls/media.m3u8" +
                "?subtitle_key=all&max_subtitle_count=${if (subtitlesHidden) 0 else HLS_ALL_SUBTITLES}"
        DownloadArtifact.ORIGINAL -> "https://api.put.io/v2/files/${fileId.value}/stream"
    }

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.toStatus(error: Exception?, pause: DownloadPauseReason?): DownloadStatus? =
    when (state) {
        // Parked by sign-out, or held by a missing network or low storage; all resume without user action.
        Download.STATE_QUEUED, Download.STATE_RESTARTING, Download.STATE_STOPPED ->
            pause?.let { DownloadStatus.Paused(it, bytesDownloaded) } ?: DownloadStatus.Queued
        Download.STATE_DOWNLOADING -> DownloadStatus.Downloading(bytesDownloaded, knownPercent())
        Download.STATE_COMPLETED -> DownloadStatus.Completed(bytesDownloaded)
        Download.STATE_FAILED -> DownloadStatus.Failed(error.toFailureReason(), bytesDownloaded)
        else -> null
    }

/**
 * Media3 reports a percentage only over a known denominator: the content length, or for HLS
 * the segment count once the playlists are read. Without one the row shows bytes alone.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Download.knownPercent(): Float? =
    // C.PERCENTAGE_UNSET is negative.
    percentDownloaded.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(PERCENT)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun DownloadManager.pauseReason(): DownloadPauseReason? {
    if (!isWaitingForRequirements) return null
    val unmet = notMetRequirements
    return when {
        unmet and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0 -> DownloadPauseReason.NETWORK
        unmet and Requirements.DEVICE_STORAGE_NOT_LOW != 0 -> DownloadPauseReason.STORAGE
        else -> null
    }
}

internal fun Exception?.toFailureReason(): DownloadFailureReason =
    generateSequence<Throwable>(this) { it.cause }
        .firstNotNullOfOrNull { it.failureReasonOrNull() }
        ?: DownloadFailureReason.UNEXPECTED

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Throwable.failureReasonOrNull(): DownloadFailureReason? =
    when (this) {
        is HttpDataSource.InvalidResponseCodeException -> when (responseCode) {
            HTTP_UNAUTHORIZED -> DownloadFailureReason.AUTHENTICATION
            HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_GONE -> DownloadFailureReason.UNAVAILABLE
            else -> DownloadFailureReason.UNEXPECTED
        }
        is UnknownHostException, is SocketException, is TimeoutException -> DownloadFailureReason.NETWORK
        is HttpDataSource.HttpDataSourceException -> DownloadFailureReason.NETWORK
        is ErrnoException -> DownloadFailureReason.STORAGE.takeIf { errno == OsConstants.ENOSPC }
        is IOException -> DownloadFailureReason.STORAGE.takeIf { message?.contains("ENOSPC") == true }
        else -> null
    }

private const val PERCENT = 100f

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
