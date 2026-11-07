package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    @Suppress("TooGenericExceptionCaught")
    public suspend fun resolve(fileId: FilesItemId, includeSubtitles: Boolean): DirectPlayDownload? =
        withContext(Dispatchers.IO) {
            try {
                val source = source(fileId.value)
                val subtitles = if (includeSubtitles && source != DirectPlayDownloadSource.Unavailable) {
                    subtitles(fileId.value)
                } else {
                    emptyList()
                }
                DirectPlayDownload(source, subtitles)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
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
