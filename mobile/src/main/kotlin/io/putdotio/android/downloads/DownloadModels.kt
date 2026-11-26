package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.PutioFileType

internal enum class DownloadArtifact {
    HLS,
    ORIGINAL,
    ORIGINAL_HLS,
    MP4,
    ;

    val isHls: Boolean get() = this == HLS || this == ORIGINAL_HLS

    val method: StreamingMethod get() = if (this == HLS) StreamingMethod.Hls else StreamingMethod.File
}

internal sealed interface DownloadStatus {
    data object Queued : DownloadStatus

    data class Paused(
        val reason: DownloadPauseReason,
        val bytesDownloaded: Long,
        val percent: Float? = null,
        val contentLength: Long? = null,
    ) : DownloadStatus

    data class Downloading(
        val bytesDownloaded: Long,
        val percent: Float?,
        val contentLength: Long? = null,
    ) : DownloadStatus

    data class Failed(
        val reason: DownloadFailureReason,
        val bytesDownloaded: Long,
    ) : DownloadStatus

    data class Completed(
        val bytes: Long,
    ) : DownloadStatus

    data object Missing : DownloadStatus
}

internal enum class DownloadPauseReason {
    NETWORK,

    STORAGE,

    USER,
}

internal enum class DownloadFailureReason {
    NETWORK,
    AUTHENTICATION,
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
    val accepted: Boolean = false,
    val queuedAt: Long = createdAt,
    val removing: Boolean = false,
    val recovered: Boolean = false,
    val subtitlesHidden: Boolean? = null,
    val startFromSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val pauseRequested: Boolean = false,
    val subtitles: List<DownloadSubtitle> = emptyList(),
    val directPlayChosen: Boolean = false,
    val directPlayUnanswered: Boolean = false,
) {
    val isCompleted: Boolean get() = status is DownloadStatus.Completed
    val canRetry: Boolean get() = status is DownloadStatus.Failed || status is DownloadStatus.Missing
    val isActive: Boolean
        get() = status is DownloadStatus.Queued || status is DownloadStatus.Downloading ||
            status is DownloadStatus.Paused

    val canPause: Boolean get() = isActive && !isPausedByUser

    val isPausedByUser: Boolean get() = (status as? DownloadStatus.Paused)?.reason == DownloadPauseReason.USER

    val holdsBytes: Boolean
        get() = status == DownloadStatus.Missing || ((status as? DownloadStatus.Failed)?.bytesDownloaded ?: 0L) > 0L
}

internal fun DownloadArtifact.convertedFallback(type: PutioFileType): DownloadArtifact? =
    when {
        this == DownloadArtifact.ORIGINAL_HLS -> DownloadArtifact.HLS
        this == DownloadArtifact.ORIGINAL && type == PutioFileType.VIDEO -> DownloadArtifact.MP4
        else -> null
    }

internal fun DownloadFailureReason.fallsBackToConverted(bytesDownloaded: Long): Boolean =
    bytesDownloaded == 0L && (this == DownloadFailureReason.UNAVAILABLE || this == DownloadFailureReason.UNEXPECTED)

internal fun DownloadEntry.convertedFallback(): DownloadEntry? =
    artifact.convertedFallback(type)?.let { converted ->
        copy(artifact = converted, status = DownloadStatus.Queued, accepted = true, pauseRequested = false)
    }

internal data class DownloadRequest(
    val fileId: FilesItemId,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    val subtitlesHidden: Boolean? = null,
    val startFromSeconds: Double = 0.0,
    val durationSeconds: Double? = null,
    val method: StreamingMethod? = null,
)

internal const val DOWNLOAD_CONCURRENCY_DEFAULT = 3
private const val DOWNLOAD_CONCURRENCY_MAX = 4
internal val DOWNLOAD_CONCURRENCY_CHOICES = 1..DOWNLOAD_CONCURRENCY_MAX
