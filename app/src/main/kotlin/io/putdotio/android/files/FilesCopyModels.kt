package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFolderType

/** A background copy put.io started for an item shared with the viewer. */
@JvmInline
value class FilesCopyId(
    val value: Long,
)

/** What put.io reports for a started copy. */
sealed interface FilesCopyProgress {
    data object Running : FilesCopyProgress

    data object Done : FilesCopyProgress

    /** put.io's own reason, in English, when it gives one. */
    data class Failed(val message: String?) : FilesCopyProgress
}

enum class FilesCopyStatus {
    STARTING,
    COPYING,
    COPIED,
    FAILED,

    /** The copy started, but no answer said it finished; it may still land in the destination. */
    UNCONFIRMED,
}

/** The one copy a session runs at a time; it outlives folder navigation until dismissed. */
@ConsistentCopyVisibility
data class FilesCopyOutcome internal constructor(
    val itemName: String,
    val destination: FilesFolder,
    val status: FilesCopyStatus,
    val failure: FilesFailure? = null,
    val serverMessage: String? = null,
    internal val requestId: FilesRequestId? = null,
    internal val copyId: FilesCopyId? = null,
    internal val checks: Int = 0,
) {
    val isRunning: Boolean
        get() = status == FilesCopyStatus.STARTING || status == FilesCopyStatus.COPYING
}

/**
 * Web and iOS offer Make a copy on a friend's file or folder and on anything inside one, but not
 * on the virtual shared folders, which put.io cannot copy.
 */
val FilesItem.canMakeCopy: Boolean
    get() = id.value > 0L && isShared &&
        folderType != PutioFolderType.SHARED_ROOT && folderType != PutioFolderType.SHARED_FRIEND

val FilesBrowserState.canStartCopy: Boolean
    get() = copyOutcome?.isRunning != true
