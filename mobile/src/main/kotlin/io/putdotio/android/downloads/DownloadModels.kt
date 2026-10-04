package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType

/**
 * Which rendition the cache holds. Video downloads the same HLS rendition the
 * player streams; audio has no HLS rendition and downloads the original file.
 */
internal enum class DownloadArtifact {
    HLS,
    ORIGINAL,
}

internal sealed interface DownloadStatus {
    /** Waiting for a free slot; Media3 starts queued rows oldest first. */
    data object Queued : DownloadStatus

    /** Partial bytes are kept; Media3 resumes by itself once [reason] clears. */
    data class Paused(
        val reason: DownloadPauseReason,
        val bytesDownloaded: Long,
    ) : DownloadStatus

    /**
     * [percent] is set only when Media3 knows the denominator: the content length of an
     * original, or the segment count of an HLS rendition. Otherwise only bytes are known.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val percent: Float?,
    ) : DownloadStatus

    data class Failed(
        val reason: DownloadFailureReason,
        val bytesDownloaded: Long,
    ) : DownloadStatus

    data class Completed(
        val bytes: Long,
    ) : DownloadStatus

    /** It finished once, but its bytes are no longer on this device; Download again fetches them. */
    data object Missing : DownloadStatus
}

internal enum class DownloadPauseReason {
    NETWORK,

    /** The system reports low storage; Media3 holds every transfer until it clears. */
    STORAGE,
}

internal enum class DownloadFailureReason {
    /** Connection dropped or timed out; a retry resumes from the partial file. */
    NETWORK,
    /** The session is no longer valid; the shell handles re-authentication. */
    AUTHENTICATION,
    /** The file is gone, not converted, or the server refused; retry re-checks. */
    UNAVAILABLE,
    STORAGE,
    UNEXPECTED,
}

internal data class DownloadEntry(
    val fileId: FilesItemId,
    val name: String,
    val type: PutioFileType,
    val artifact: DownloadArtifact,
    val status: DownloadStatus,
    val createdAt: Long,
    /** Set once the platform downloader has recorded this request. */
    val accepted: Boolean = false,
    /** When Media3 last queued the request; it starts requests in this order, oldest first. */
    val queuedAt: Long = createdAt,
    /** Set when the viewer confirmed Delete; the row leaves once Media3 has removed the bytes. */
    val removing: Boolean = false,
    /** Rebuilt from Media3's index after this row was lost; its name may be a stand-in. */
    val recovered: Boolean = false,
    /**
     * The account's `hide_subtitles` as confirmed when the download started; null when it was
     * not known. An HLS download made while it was true holds no subtitle rendition.
     */
    val subtitlesHidden: Boolean? = null,
    /** The listing's saved position when the download started; offline resume falls back to it. */
    val startFromSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
) {
    val isCompleted: Boolean get() = status is DownloadStatus.Completed
    val canRetry: Boolean get() = status is DownloadStatus.Failed || status is DownloadStatus.Missing
    val isActive: Boolean
        get() = status is DownloadStatus.Queued || status is DownloadStatus.Downloading ||
            status is DownloadStatus.Paused
}

/** Enough of a Files item to start a download without another API call. */
internal data class DownloadRequest(
    val fileId: FilesItemId,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    /** The account's confirmed `hide_subtitles`; null while settings are not confirmed. */
    val subtitlesHidden: Boolean? = null,
    val startFromSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
)

/** How many transfers Media3 runs at once; iOS offers the same choices and default. */
internal const val DOWNLOAD_CONCURRENCY_DEFAULT = 3
private const val DOWNLOAD_CONCURRENCY_MAX = 4
internal val DOWNLOAD_CONCURRENCY_CHOICES = 1..DOWNLOAD_CONCURRENCY_MAX
