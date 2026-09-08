package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType

@JvmInline
value class FilesItemId(
    val value: Long,
)

@JvmInline
value class FilesCursor(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Files cursor cannot be blank" }
    }
}

enum class FilesSort(
    internal val apiValue: String,
) {
    NAME_ASCENDING("NAME_ASC"),
    NAME_DESCENDING("NAME_DESC"),
    SIZE_ASCENDING("SIZE_ASC"),
    SIZE_DESCENDING("SIZE_DESC"),
    DATE_ADDED_ASCENDING("DATE_ASC"),
    DATE_ADDED_DESCENDING("DATE_DESC"),
    DATE_MODIFIED_ASCENDING("MODIFIED_ASC"),
    DATE_MODIFIED_DESCENDING("MODIFIED_DESC"),
    TYPE_ASCENDING("TYPE_ASC"),
    TYPE_DESCENDING("TYPE_DESC"),
    WATCH_STATUS_ASCENDING("WATCH_ASC"),
    WATCH_STATUS_DESCENDING("WATCH_DESC"),
    ;

    companion object {
        internal fun fromApiValue(value: String?): FilesSort? =
            entries.firstOrNull { it.apiValue == value }
    }
}

data class FilesFolder(
    val id: FilesItemId,
    val name: String?,
    val sort: FilesSort? = null,
) {
    companion object {
        val Root = FilesFolder(id = FilesItemId(0L), name = null)
    }
}

data class FilesItem(
    val id: FilesItemId,
    val parentId: FilesItemId?,
    val name: String,
    val type: PutioFileType,
    val sizeBytes: Long,
    val createdAt: String,
    val playback: FilesPlaybackProgress? = null,
) {
    val isFolder: Boolean
        get() = type == PutioFileType.FOLDER

    val isPlayable: Boolean
        get() = type == PutioFileType.VIDEO || type == PutioFileType.AUDIO
}

/**
 * Server-side watch position for media rows. `start_from` is the account's saved
 * position in seconds; duration comes from video metadata and may be unknown.
 * Web treats any position above zero as watched, so this does too.
 */
data class FilesPlaybackProgress(
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

data class FilesPage(
    val items: List<FilesItem>,
    val nextCursor: FilesCursor?,
    val sort: FilesSort? = null,
)
