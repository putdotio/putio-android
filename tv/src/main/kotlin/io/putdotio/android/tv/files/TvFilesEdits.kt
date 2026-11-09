package io.putdotio.android.tv.files

import androidx.annotation.StringRes
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesDeleteOutcome
import io.putdotio.android.files.FilesDeleteStatus
import io.putdotio.android.files.FilesEditCheck
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationController
import io.putdotio.android.files.FilesMoveOutcome
import io.putdotio.android.files.FilesMoveStatus
import io.putdotio.android.files.FilesMoveTargetMemory
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.files.checked
import io.putdotio.android.files.recording
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface TvItemEditState {
    data object Idle : TvItemEditState

    data class Renaming(val itemId: FilesItemId, val name: String) : TvItemEditState

    data class RenameFailed(val itemId: FilesItemId, val name: String, val failure: PutioFailure) : TvItemEditState

    data class Renamed(val itemId: FilesItemId) : TvItemEditState

    data class Moved(val itemName: String) : TvItemEditState

    data class Failed(@param:StringRes val message: Int, val itemName: String?, val failure: PutioFailure?) :
        TvItemEditState

    data class TrashLimit(val item: FilesItem) : TvItemEditState
}

internal class TvItemChanges(
    val onUpdated: (FilesItem) -> Unit = {},
    val onRemoved: (FilesItem, FilesDeleteOutcome) -> Unit = { _, _ -> },
    val onSessionRejected: (PutioFailure) -> Unit = {},
)

internal class TvFilesEdits(
    private val repository: FilesRepository,
    private val scope: CoroutineScope,
    private val changes: TvItemChanges = TvItemChanges(),
) {
    private val mutableState = MutableStateFlow<TvItemEditState>(TvItemEditState.Idle)
    private val mutableBusy = MutableStateFlow(false)
    private val requestIds = AtomicLong(FIRST_OUTCOME_ID)
    private val announcedMoves = mutableSetOf<FilesRequestId>()
    private var moveMemory = FilesMoveTargetMemory(remember = true)

    val state: StateFlow<TvItemEditState> = mutableState.asStateFlow()

    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    fun moveDestinations(
        item: FilesItem,
        sourceFolderId: FilesItemId,
        controllerScope: CoroutineScope,
    ): FilesMoveDestinationController =
        FilesMoveDestinationController(
            item,
            sourceFolderId,
            repository,
            controllerScope,
            moveMemory.startPath(item),
        )

    fun destinationChosen(path: List<FilesFolder>) {
        moveMemory = moveMemory.chosen(path)
    }

    fun announceMoveOnce(requestId: FilesRequestId): Boolean = announcedMoves.add(requestId)

    fun rename(item: FilesItem, name: String): Boolean =
        startIfIdle {
            mutableState.value = TvItemEditState.Renaming(item.id, name)
            when (val result = repository.rename(item.id, name)) {
                is PutioResult.Failure -> if (!sessionRejected(result.failure)) {
                    mutableState.value = TvItemEditState.RenameFailed(item.id, name, result.failure)
                }
                is PutioResult.Success -> {
                    changes.onUpdated(fresh(item.id) ?: item.copy(name = name))
                    mutableState.value = TvItemEditState.Renamed(item.id)
                }
            }
        }

    fun move(item: FilesItem, destinationId: FilesItemId): Boolean =
        startIfIdle {
            val intent = FilesFolderOperationIntent.Move(item.id, destinationId)
            val outcome = FilesMoveOutcome(FilesRequestId(requestIds.getAndIncrement()), intent, item.name)
                .recording(repository.move(intent.itemIds, destinationId))
            if (sessionRejected(outcome.failure)) return@startIfIdle
            val read = repository.resolveItem(item.id)
            when (val check = outcome.checked(listOf(read))) {
                is FilesEditCheck.Unconfirmed -> if (!sessionRejected(check.failure)) {
                    mutableState.value = TvItemEditState.Failed(R.string.tv_files_move_unknown, null, check.failure)
                }
                is FilesEditCheck.Settled -> if (check.outcome.status == FilesMoveStatus.MOVED) {
                    changes.onUpdated((read as PutioResult.Success).value)
                    mutableState.value = TvItemEditState.Moved(item.name)
                } else {
                    mutableState.value = check.outcome.tvFailedEdit()
                }
            }
        }

    fun delete(item: FilesItem, mode: FilesDeleteMode): Boolean =
        startIfIdle {
            val intent = FilesFolderOperationIntent.Delete(item.id, mode)
            val outcome = FilesDeleteOutcome(FilesRequestId(requestIds.getAndIncrement()), intent, item.name)
                .recording(repository.delete(intent.itemIds, mode))
            if (sessionRejected(outcome.failure)) return@startIfIdle
            when (val check = outcome.checked(listOf(repository.resolveItem(item.id)))) {
                is FilesEditCheck.Unconfirmed -> if (!sessionRejected(check.failure)) {
                    mutableState.value = TvItemEditState.Failed(R.string.tv_files_delete_unknown, null, check.failure)
                }
                is FilesEditCheck.Settled -> when (check.outcome.status) {
                    FilesDeleteStatus.NO_LONGER_AVAILABLE -> {
                        changes.onRemoved(item, check.outcome)
                        mutableState.value = TvItemEditState.Idle
                    }
                    FilesDeleteStatus.TOO_LARGE_FOR_TRASH -> mutableState.value = TvItemEditState.TrashLimit(item)
                    else -> mutableState.value = check.outcome.tvFailedEdit()
                }
            }
        }

    fun acknowledge() {
        mutableState.value = TvItemEditState.Idle
    }

    private suspend fun fresh(itemId: FilesItemId): FilesItem? =
        when (val read = repository.resolveItem(itemId)) {
            is PutioResult.Success -> read.value
            is PutioResult.Failure -> {
                sessionRejected(read.failure)
                null
            }
        }

    private fun sessionRejected(failure: PutioFailure?): Boolean {
        if (failure !is PutioFailure.AuthenticationRequired) return false
        changes.onSessionRejected(failure)
        mutableState.value = TvItemEditState.Idle
        return true
    }

    private fun startIfIdle(block: suspend () -> Unit): Boolean {
        if (!mutableBusy.compareAndSet(expect = false, update = true)) return false
        scope.launch {
            try {
                block()
            } finally {
                mutableBusy.value = false
            }
        }
        return true
    }
}

private fun FilesMoveOutcome.tvFailedEdit(): TvItemEditState.Failed {
    val notice = checkNotNull(tvNotice())
    return TvItemEditState.Failed(
        notice.message,
        notice.itemName,
        failure.takeUnless { it is PutioFailure.AccessDenied },
    )
}

private fun FilesDeleteOutcome.tvFailedEdit(): TvItemEditState.Failed =
    TvItemEditState.Failed(
        status.tvOutcomeMessage() ?: R.string.tv_files_delete_unknown,
        itemName.takeUnless { status.tvOutcomeMessage() == null },
        failure,
    )

private const val FIRST_OUTCOME_ID = 1L shl 40
