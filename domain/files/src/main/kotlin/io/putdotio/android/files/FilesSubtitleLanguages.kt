package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileSubtitlesResponse

/** The languages of the subtitles put.io lists for a video, and how many of them are sidecar files beside it. */
public data class FilesSubtitles(public val languages: List<String>, public val sidecarFiles: Int = 0)

/** The subtitles put.io lists for a video: sidecar files, embedded tracks and found ones. */
public fun interface FilesSubtitleLanguages {
    public suspend fun subtitleLanguages(itemId: FilesItemId): PutioResult<FilesSubtitles>
}

public class SdkFilesSubtitleLanguages internal constructor(
    private val listSubtitles: suspend (Long) -> FileSubtitlesResponse,
) : FilesSubtitleLanguages {
    public constructor(client: PutioClient) : this(listSubtitles = { fileId -> client.files.listSubtitles(fileId) })

    /** Each language once, in put.io's order; a subtitle without a language falls back to its name. */
    override suspend fun subtitleLanguages(itemId: FilesItemId): PutioResult<FilesSubtitles> = putioRequest {
        require(itemId.value > 0L) { "Subtitles belong to one positive file ID" }
        val subtitles = listSubtitles(itemId.value).subtitles
        FilesSubtitles(
            languages = subtitles
                .mapNotNull { subtitle ->
                    subtitle.language.trim().ifEmpty { subtitle.name.trim() }.takeIf(String::isNotEmpty)
                }
                .distinct(),
            sidecarFiles = subtitles.count { it.source == SIDECAR_SOURCE },
        )
    }

    private companion object {
        const val SIDECAR_SOURCE = "folder"
    }
}
