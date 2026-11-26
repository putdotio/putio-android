package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.files.PutioFolderType

@JvmInline
public value class FilesCopyId(
    internal val value: Long,
)

public sealed interface FilesCopyProgress {
    public data object Running : FilesCopyProgress

    public data object Done : FilesCopyProgress

    public data class Failed(val message: String?) : FilesCopyProgress
}

public enum class FilesCopyStatus {
    STARTING,
    COPYING,
    COPIED,
    FAILED,

    UNCONFIRMED,
}

@ConsistentCopyVisibility
public data class FilesCopyOutcome internal constructor(
    val itemName: String,
    val destination: FilesFolder,
    val status: FilesCopyStatus,
    val failure: PutioFailure? = null,
    val serverMessage: String? = null,
    internal val requestId: FilesRequestId? = null,
    internal val copyId: FilesCopyId? = null,
    internal val checks: Int = 0,
) {
    val isRunning: Boolean
        get() = status == FilesCopyStatus.STARTING || status == FilesCopyStatus.COPYING
}

public val FilesItem.canMakeCopy: Boolean
    get() = id.value > 0L && isShared &&
        folderType != PutioFolderType.SHARED_ROOT && folderType != PutioFolderType.SHARED_FRIEND

public val FilesBrowserState.canStartCopy: Boolean
    get() = copyOutcome?.isRunning != true
