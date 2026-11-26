package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.isUnknownLanguageName
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FileSubtitlesResponse

public data class FilesSubtitles(public val languages: List<String?>, public val sidecarFiles: Int = 0)

public fun interface FilesSubtitleLanguages {
    public suspend fun subtitleLanguages(itemId: FilesItemId): PutioResult<FilesSubtitles>
}

public class SdkFilesSubtitleLanguages internal constructor(
    private val listSubtitles: suspend (Long) -> FileSubtitlesResponse,
) : FilesSubtitleLanguages {
    public constructor(client: PutioClient) : this(listSubtitles = { fileId -> client.files.listSubtitles(fileId) })

    override suspend fun subtitleLanguages(itemId: FilesItemId): PutioResult<FilesSubtitles> = putioRequest {
        require(itemId.value > 0L) { "Subtitles belong to one positive file ID" }
        val subtitles = listSubtitles(itemId.value).subtitles
        FilesSubtitles(
            languages = subtitles
                .map { subtitle ->
                    subtitle.language.trim()
                        .takeUnless { it.isUnknownLanguageName() || subtitle.languageCode == UNDETERMINED_CODE }
                }
                .distinct(),
            sidecarFiles = subtitles.count { it.source == SIDECAR_SOURCE },
        )
    }

    private companion object {
        const val SIDECAR_SOURCE = "folder"
        const val UNDETERMINED_CODE = "und"
    }
}
