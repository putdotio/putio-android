package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType

public data class FilesFolder(
    val id: FilesItemId,
    val name: String?,
    val sort: FilesSort? = null,
) {
    public companion object {
        public val Root: FilesFolder = FilesFolder(id = FilesItemId(0L), name = null)
    }
}

public data class FilesItem(
    val id: FilesItemId,
    val parentId: FilesItemId?,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    val createdAt: String,
    val playback: FilesPlaybackProgress? = null,
    val isShared: Boolean = false,
    val folderType: PutioFolderType = PutioFolderType.REGULAR,
    val screenshotUrl: String? = null,
    val updatedAt: String? = null,
    val contentType: String? = null,
) {
    val isFolder: Boolean
        get() = type == PutioFileType.FOLDER

    val acceptsOwnerActions: Boolean
        get() = !isShared && folderType != PutioFolderType.SHARED_ROOT && folderType != PutioFolderType.SHARED_FRIEND

    val isPlayable: Boolean
        get() = type == PutioFileType.VIDEO || type == PutioFileType.AUDIO
}

public data class FilesPlaybackProgress(
    val startFromSeconds: Double,
    val durationSeconds: Double?,
) {
    init {
        require(startFromSeconds.isFinite() && startFromSeconds >= 0.0) { "start_from must be finite and nonnegative" }
        require(durationSeconds == null || (durationSeconds.isFinite() && durationSeconds > 0.0)) {
            "duration must be finite and positive when present"
        }
    }

    val isWatched: Boolean
        get() = startFromSeconds > 0.0
}

public data class FilesPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
    val sort: FilesSort? = null,
    val parent: FilesItem? = null,
    val total: Int? = null,
)

public data class FilesFolderSummary(
    val itemCount: Int,
    val sizeBytes: Long?,
    val acceptsNewItems: Boolean = true,
)

internal fun FilesPage.summary(): FilesFolderSummary? =
    total?.takeIf { it >= 0 }?.let { count ->
        FilesFolderSummary(
            itemCount = count,
            sizeBytes = parent?.sizeBytes?.takeIf { it >= 0L },
            acceptsNewItems = parent?.acceptsOwnerActions ?: false,
        )
    }

public enum class FilesOpenOrigin {
    SEARCH,
    HISTORY,
    TRANSFERS,

    LINK,

    HOME,
}

public data class FilesExternalOpen(
    val item: FilesItem,
    val origin: FilesOpenOrigin,
)
