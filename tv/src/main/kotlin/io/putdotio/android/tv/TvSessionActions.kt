package io.putdotio.android.tv

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.trash.undoTrashMove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TvUndoFailure(val itemName: String, val failure: PutioFailure)

internal class TvTrashUndo(
    private val trash: TrashRepository,
    private val files: FilesBrowserController,
    private val scope: CoroutineScope,
    private val onUndone: () -> Unit = {},
) {
    private val mutableFailure = MutableStateFlow<TvUndoFailure?>(null)

    val failure: StateFlow<TvUndoFailure?> = mutableFailure.asStateFlow()

    fun undo(outcome: FilesDeleteOutcome) {
        if (outcome.intent.mode != FilesDeleteMode.TRASH) return
        scope.launch {
            when (val result = trash.undoTrashMove(outcome.intent.itemIds)) {
                is PutioResult.Success -> {
                    onUndone()
                    if (!files.dispatch(FilesBrowserEvent.TrashUndone(outcome))) {
                        files.dispatch(FilesBrowserEvent.InvalidateAllFolders)
                    }
                    files.dispatch(FilesBrowserEvent.ReloadIfStale)
                }
                is PutioResult.Failure -> mutableFailure.update { held ->
                    held?.takeIf { it.failure is PutioFailure.AuthenticationRequired }
                        ?: TvUndoFailure(outcome.itemName, result.failure)
                }
            }
        }
    }

    fun dismissFailure() {
        mutableFailure.update { it?.takeIf { undo -> undo.failure is PutioFailure.AuthenticationRequired } }
    }
}

internal class TvTrashUndoOffer(
    private val scope: CoroutineScope,
    private val onUndo: (FilesDeleteOutcome) -> Unit,
) {
    private val mutableOffered = MutableStateFlow<FilesDeleteOutcome?>(null)
    private var expiry: Job? = null

    val offered: StateFlow<FilesDeleteOutcome?> = mutableOffered.asStateFlow()

    fun offer(outcome: FilesDeleteOutcome) {
        expiry?.cancel()
        mutableOffered.value = outcome
        expiry = scope.launch {
            delay(SNACKBAR_MILLIS)
            mutableOffered.compareAndSet(outcome, null)
        }
    }

    fun undo(outcome: FilesDeleteOutcome) {
        if (mutableOffered.compareAndSet(outcome, null)) expiry?.cancel()
        onUndo(outcome)
    }
}

internal class TvSubtitlesFirst {
    private val pending = MutableStateFlow(false)

    val requested: StateFlow<Boolean> = pending.asStateFlow()

    fun request(first: Boolean) {
        pending.value = first
    }

    fun shown() {
        pending.value = false
    }
}
