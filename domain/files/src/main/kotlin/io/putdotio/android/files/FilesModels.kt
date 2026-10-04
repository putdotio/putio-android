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
    /** A friend's file shown under Items shared with you; the viewer can read it but not change it. */
    val isShared: Boolean = false,
    val folderType: PutioFolderType = PutioFolderType.REGULAR,
) {
    val isFolder: Boolean
        get() = type == PutioFileType.FOLDER

    /**
     * Rename, Move, Delete and the watched toggle need the viewer to own the item. The server
     * rejects them for friends' files and for the virtual shared folders, so neither surface
     * offers them there; reading actions such as Download stay.
     */
    val acceptsOwnerActions: Boolean
        get() = !isShared && folderType != PutioFolderType.SHARED_ROOT && folderType != PutioFolderType.SHARED_FRIEND

    val isPlayable: Boolean
        get() = type == PutioFileType.VIDEO || type == PutioFileType.AUDIO
}

/**
 * Server-side watch position for media rows. `start_from` is the account's saved
 * position in seconds; duration comes from video metadata and may be unknown.
 * Web treats any position above zero as watched, so this does too.
 */
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

    /** Fraction in [0, 1] when duration is known; null otherwise. */
    val fraction: Float?
        get() = durationSeconds?.let { (startFromSeconds / it).coerceIn(0.0, 1.0).toFloat() }
}

public data class FilesPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
    val sort: FilesSort? = null,
    /** The listed item itself; for a media file, the only read that carries its duration. */
    val parent: FilesItem? = null,
)

/** Where an item opened from outside the Files browser came from; Back from its folder returns there. */
public enum class FilesOpenOrigin {
    SEARCH,
    HISTORY,
    TRANSFERS,

    /** A product link; its folder sits on top of the prior Files location and Back stays in Files. */
    LINK,
}

/** An item to open from outside the Files browser, with where the viewer chose it. */
public data class FilesExternalOpen(
    val item: FilesItem,
    val origin: FilesOpenOrigin,
)
