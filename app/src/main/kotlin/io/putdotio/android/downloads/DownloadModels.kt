package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType

/**
 * Which rendition the cache holds. Video downloads the same HLS rendition the
 * player streams, subtitles included; audio has no HLS rendition and downloads
 * the original file.
 */
enum class DownloadArtifact {
    HLS,
    ORIGINAL,
}

sealed interface DownloadStatus {
    data object Queued : DownloadStatus

    /** Partial bytes are kept; the transfer continues by itself once the network returns. */
    data class WaitingForNetwork(
        val bytesDownloaded: Long,
    ) : DownloadStatus

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : DownloadStatus

    data class Failed(
        val reason: DownloadFailureReason,
        val bytesDownloaded: Long,
    ) : DownloadStatus

    data class Completed(
        val bytes: Long,
    ) : DownloadStatus
}

enum class DownloadFailureReason {
    /** Connection dropped or timed out; a retry resumes from the partial file. */
    NETWORK,
    /** The session is no longer valid; the shell handles re-authentication. */
    AUTHENTICATION,
    /** The file is gone, not converted, or the server refused; retry re-checks. */
    UNAVAILABLE,
    STORAGE,
    UNEXPECTED,
}

data class DownloadEntry(
    val fileId: FilesItemId,
    val name: String,
    val type: PutioFileType,
    val artifact: DownloadArtifact,
    val status: DownloadStatus,
    val createdAt: Long,
) {
    val isCompleted: Boolean get() = status is DownloadStatus.Completed
    val canRetry: Boolean get() = status is DownloadStatus.Failed
    val isActive: Boolean
        get() = status is DownloadStatus.Queued || status is DownloadStatus.Downloading ||
            status is DownloadStatus.WaitingForNetwork
}

/** Enough of a Files item to start a download without another API call. */
data class DownloadRequest(
    val fileId: FilesItemId,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
)
