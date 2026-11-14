package io.putdotio.android

import androidx.compose.runtime.SnapshotMutationPolicy
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolderState

internal data class FilesBackGate(
    val moveSettling: Boolean,
    val canNavigateBack: Boolean,
    val deletePending: Boolean,
) {
    companion object {
        fun of(state: FilesBrowserState) = FilesBackGate(
            moveSettling = state.stack.any { it.operation.pendingMove != null },
            canNavigateBack = state.canNavigateBack,
            deletePending = state.current.operation.pendingDelete != null,
        )
    }
}

internal object FolderChrome : SnapshotMutationPolicy<FilesFolderState> {
    override fun equivalent(a: FilesFolderState, b: FilesFolderState): Boolean =
        a.folder == b.folder && a.operation == b.operation && a.summary == b.summary &&
            a.content::class == b.content::class
}

internal fun filesSelectionShown(destination: MobileDestination, searchOpen: Boolean, active: Boolean): Boolean =
    destination == MobileDestination.Files && !searchOpen && active
