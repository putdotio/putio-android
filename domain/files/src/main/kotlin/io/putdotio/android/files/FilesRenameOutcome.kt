package io.putdotio.android.files

import io.putdotio.android.PutioFailure

public fun FilesRenameCompletion?.confirms(
    itemId: FilesItemId,
    submittedName: String?,
    previousRequestValue: Long?,
): Boolean =
    submittedName != null &&
        this?.intent == FilesFolderOperationIntent.Rename(itemId, submittedName) &&
        requestId.value != previousRequestValue

public fun FilesFolderOperation.renameReloadStarted(): Boolean =
    when (this) {
        is FilesFolderOperation.Loading ->
            intent is FilesFolderOperationIntent.Rename && phase == FilesFolderOperationPhase.RELOADING
        is FilesFolderOperation.Failed ->
            intent is FilesFolderOperationIntent.Rename && phase == FilesFolderOperationPhase.RELOADING
        FilesFolderOperation.Idle -> false
    }

public fun FilesFolderOperation.renameFailureFor(itemId: FilesItemId): PutioFailure? =
    (this as? FilesFolderOperation.Failed)?.takeIf {
        (it.intent as? FilesFolderOperationIntent.Rename)?.itemId == itemId &&
            it.phase == FilesFolderOperationPhase.RENAMING
    }?.failure
