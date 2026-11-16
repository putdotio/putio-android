package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class MobilePinnedFiles(
    private val pins: PinnedFiles,
    resolver: FilesItemResolver,
    private val scope: CoroutineScope,
) : Closeable {
    private val resolved = PinnedItems(pins, resolver, folderCounts = null, scope)
    private val mutableNotices =
        MutableSharedFlow<MobileFilesNotice>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val handledEdits = mutableMapOf<FilesRequestId, PinEdit>()
    private var editedName = ""

    val ids: StateFlow<List<FilesItemId>?> = pins.pins
    val items: StateFlow<List<FilesItem>> = resolved.row
        .map { row -> (row as? PinnedRow.Ready)?.cards?.map(PinnedCard::item).orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    val notices: SharedFlow<MobileFilesNotice> = mutableNotices.asSharedFlow()
    val sessionRejected: StateFlow<PutioFailure.AuthenticationRequired?> =
        combine(resolved.rejected, pins.failure) { item, config ->
            item ?: config as? PutioFailure.AuthenticationRequired
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            pins.editFailure.filterNotNull().collect { failure ->
                mutableNotices.tryEmit(MobileFilesNotice(R.string.mobile_files_pin_failed, editedName, failure))
                pins.dismissEditFailure()
            }
        }
    }

    fun setPinned(item: FilesItem, pinned: Boolean) {
        editedName = item.name
        if (pinned) {
            resolved.pinned(item)
            pins.pin(item.id)
        } else {
            pins.unpin(item.id)
        }
    }

    fun refresh() {
        resolved.retry()
    }

    suspend fun follow(browser: Flow<FilesBrowserState>) {
        browser
            .map { state -> state.stack.flatMap(FilesFolderState::pinEdits) }
            .distinctUntilChanged()
            .collect { edits -> edits.forEach(::apply) }
    }

    private fun apply(edit: PinEdit) {
        val previous = handledEdits.put(edit.requestId, edit)
        if (previous == null) {
            edit.edited.forEach(resolved::itemEdited)
            edit.removed.forEach(resolved::itemRemoved)
        } else {
            (previous.removed - edit.removed.toSet()).forEach(resolved::itemRestored)
            (edit.removed - previous.removed.toSet()).forEach(resolved::itemRemoved)
        }
    }

    fun trashUndone(outcome: FilesDeleteOutcome) {
        outcome.intent.itemIds.forEach(resolved::itemRestored)
    }

    fun trashRestored() {
        resolved.trashRestored()
    }

    override fun close() {
        pins.close()
        scope.cancel()
    }
}

private data class PinEdit(
    val requestId: FilesRequestId,
    val edited: List<FilesItemId> = emptyList(),
    val removed: List<FilesItemId> = emptyList(),
)

private fun FilesFolderState.pinEdits(): List<PinEdit> = listOfNotNull(
    renameCompletion?.let { PinEdit(it.requestId, edited = listOf(it.intent.itemId)) },
    moveOutcome?.takeIf { it.status == FilesMoveStatus.MOVED }?.let { move ->
        PinEdit(move.requestId, edited = move.intent.itemIds)
    },
    deleteOutcome?.let { PinEdit(it.requestId, removed = it.confirmedGone()) },
)
