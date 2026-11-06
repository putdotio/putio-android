package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public class OriginalVideoDownloads internal constructor(
    private val originalVideoPolicy: () -> OriginalVideoPolicy,
    private val decision: OriginalPlaybackDecision,
    private val switchedToConverted: SwitchedToConvertedFiles,
    private val loadFile: suspend (Long) -> PutioFile,
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
    )

    @Suppress("TooGenericExceptionCaught")
    public suspend fun accepts(fileId: FilesItemId): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                decide(fileId.value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun decide(fileId: Long): Boolean {
        val policy = originalVideoPolicy()
        if (policy == OriginalVideoPolicy.Never || switchedToConverted.contains(fileId)) return false
        val file = loadFile(fileId)
        return file.fileType == PutioFileType.VIDEO &&
            policy.capabilities(decision).accept(file)
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
