package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.isNotFound
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class MobilePinnedFiles(
    private val pins: PinnedFiles,
    private val resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
    private val scope: CoroutineScope,
) : Closeable {
    private val known = mutableMapOf<FilesItemId, FilesItem>()
    private val gone = mutableSetOf<FilesItemId>()
    private val refreshes = MutableStateFlow(0)
    private val mutableItems = MutableStateFlow<List<FilesItem>>(emptyList())
    private val mutableNotices =
        MutableSharedFlow<MobileFilesNotice>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var editedName = ""

    val ids: StateFlow<List<FilesItemId>?> = pins.pins
    val items: StateFlow<List<FilesItem>> = mutableItems.asStateFlow()
    val notices: SharedFlow<MobileFilesNotice> = mutableNotices.asSharedFlow()

    init {
        scope.launch {
            combine(pins.pins, refreshes) { ids, _ -> ids }.collectLatest { ids ->
                mutableItems.value = ids?.let { resolveAll(it) }.orEmpty()
            }
        }
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
            known[item.id] = item
            gone.remove(item.id)
            pins.pin(item.id)
        } else {
            pins.unpin(item.id)
        }
    }

    fun refresh() {
        known.clear()
        gone.clear()
        pins.reload()
        refreshes.update { it + 1 }
    }

    override fun close() {
        pins.close()
        scope.cancel()
    }

    private suspend fun resolveAll(ids: List<FilesItemId>): List<FilesItem> = coroutineScope {
        ids.map { id -> async { known[id] ?: read(id) } }.awaitAll().filterNotNull()
    }

    private suspend fun read(id: FilesItemId): FilesItem? {
        if (id in gone) return null
        return when (val result = resolve(id)) {
            is PutioResult.Success -> result.value.also { known[id] = it }
            is PutioResult.Failure -> {
                if (result.failure.isNotFound && gone.add(id)) pins.unpinGone(id)
                null
            }
        }
    }
}
