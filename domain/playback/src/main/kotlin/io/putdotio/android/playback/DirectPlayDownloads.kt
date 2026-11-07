package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

public enum class DirectPlayDownloadSource {
    Original,
    Converted,
    Unavailable,
}

/** What a Direct play video download fetches: one progressive file, and the subtitle files that go with it. */
public data class DirectPlayDownload(
    val source: DirectPlayDownloadSource,
    val subtitles: List<FileSubtitle> = emptyList(),
)

public class DirectPlayDownloads internal constructor(
    private val originalVideoPolicy: () -> OriginalVideoPolicy,
    private val decision: OriginalPlaybackDecision,
    private val switchedToConverted: SwitchedToConvertedFiles,
    private val loadFile: suspend (Long) -> PutioFile,
    private val listSubtitles: suspend (Long) -> List<FileSubtitle>,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    public constructor(
        client: PutioClient,
        originalVideoPolicy: () -> OriginalVideoPolicy,
        decoders: DecoderInventory,
        switchedToConverted: SwitchedToConvertedFiles = SwitchedToConvertedFiles.None,
    ) : this(
        originalVideoPolicy = originalVideoPolicy,
        decision = OriginalPlaybackDecision(decoders),
        switchedToConverted = switchedToConverted,
        loadFile = { fileId -> client.files.get(fileId, MEDIA_INFO_QUERY) },
        listSubtitles = { fileId -> client.files.listSubtitles(fileId).subtitles },
    )

    /** Null when the file could not be read; subtitles are best effort and never fail the answer. */
    public suspend fun resolve(fileId: FilesItemId, includeSubtitles: Boolean): DirectPlayDownload? =
        withContext(io) {
            val source = withTimeoutOrNull(LOOKUP_TIMEOUT) { sourceOrNull(fileId.value) } ?: return@withContext null
            val subtitles = if (includeSubtitles && source != DirectPlayDownloadSource.Unavailable) {
                withTimeoutOrNull(SUBTITLES_TIMEOUT) { subtitles(fileId.value) }.orEmpty()
            } else {
                emptyList()
            }
            DirectPlayDownload(source, subtitles)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sourceOrNull(fileId: Long): DirectPlayDownloadSource? =
        try {
            source(fileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private suspend fun source(fileId: Long): DirectPlayDownloadSource {
        val file = loadFile(fileId)
        return when {
            file.fileType != PutioFileType.VIDEO -> DirectPlayDownloadSource.Unavailable
            acceptsOriginal(file) -> DirectPlayDownloadSource.Original
            file.isMp4Available -> DirectPlayDownloadSource.Converted
            else -> DirectPlayDownloadSource.Unavailable
        }
    }

    private suspend fun acceptsOriginal(file: PutioFile): Boolean {
        val policy = originalVideoPolicy()
        return policy != OriginalVideoPolicy.Never &&
            file.mediaInfo?.playbackIssues.isNullOrEmpty() &&
            !switchedToConverted.contains(file.id) &&
            policy.capabilities(decision).accept(file)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun subtitles(fileId: Long): List<FileSubtitle> =
        try {
            listSubtitles(fileId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }

    private fun PlaybackCapabilities.accept(file: PutioFile): Boolean =
        originalVideoPlayable ||
            originalVideoDecision?.canPlayOriginal(
                mediaInfo = file.mediaInfo,
                needConvert = file.needConvert,
                isMp4Available = file.isMp4Available,
            ) == true

    private companion object {
        val LOOKUP_TIMEOUT = 10.seconds
        val SUBTITLES_TIMEOUT = 5.seconds
        val MEDIA_INFO_QUERY = FileDetailsQuery(
            mp4Size = false,
            startFrom = false,
            streamUrl = false,
            mp4StreamUrl = false,
            mp4Status = true,
            mediaInfo = true,
        )
    }
}
